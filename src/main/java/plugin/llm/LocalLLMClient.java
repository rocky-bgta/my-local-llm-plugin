package plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import plugin.llm.model.ChatMessage;
import plugin.util.AttachmentUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class LocalLLMClient {

    // Package-private mutable so tests can shrink them.
    static long firstDataTimeoutSeconds = 120;
    static long idleTimeoutSeconds = 60;
    static long modelsTimeoutSeconds = 5;

    // 4096 was too small for full Java files and caused truncated file-operation tags.
    static volatile int maxOutputTokens = 8192;

    public static void setMaxOutputTokens(int tokens) {
        if (tokens > 0) maxOutputTokens = tokens;
    }

    private final String baseUrl;
    final HttpClient http;

    public LocalLLMClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Snapshot of the local LLM server's state, from GET /v1/models. */
    public record ServerState(boolean reachable, List<String> models, String error) {
        public boolean hasModel(String model) {
            if (model == null || model.isBlank()) return false;
            return models.stream().anyMatch(m -> m.equalsIgnoreCase(model));
        }
    }

    /** Never throws — reports unreachability via the returned state. */
    public ServerState checkState() {
        try {
            return new ServerState(true, fetchModels(), "");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ServerState(false, List.of(), msg);
        }
    }

    public List<String> fetchModels() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(modelsTimeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonArray data  = root.getAsJsonArray("data");

        List<String> ids = new ArrayList<>();
        data.forEach(el -> ids.add(el.getAsJsonObject().get("id").getAsString()));
        return ids;
    }

    // Streams the assistant reply token-by-token via SSE.
    // Blocks until the server sends [DONE]. onToken is called for each text chunk.
    public void streamChat(String model, List<ChatMessage> messages,
                           Consumer<String> onToken) throws Exception {
        streamChat(model, messages, List.of(), onToken);
    }

    public void streamChat(String model, List<ChatMessage> messages,
                           List<AttachmentData> attachments,
                           Consumer<String> onToken) throws Exception {
        JsonObject body = buildBody(model, messages, attachments);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept",       "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        LineSubscriber subscriber = new LineSubscriber();
        CompletableFuture<HttpResponse<Void>> future =
                http.sendAsync(req, info -> {
                    subscriber.statusCode = info.statusCode();
                    return HttpResponse.BodySubscribers.fromLineSubscriber(subscriber);
                });
        future.whenComplete((r, t) -> { if (t != null) subscriber.queue.offer(t); });

        try {
            boolean gotFirstData = false;
            StringBuilder errorBody = new StringBuilder();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("STOPPED_BY_USER");
                }
                long timeout = gotFirstData ? idleTimeoutSeconds : firstDataTimeoutSeconds;
                Object item = subscriber.queue.poll(timeout, TimeUnit.SECONDS);
                if (item == null) {
                    throw new java.io.IOException(gotFirstData
                            ? "LLM stream stalled: no data for " + idleTimeoutSeconds
                              + "s mid-response. The model may have crashed or been unloaded."
                            : "LLM did not start responding within " + firstDataTimeoutSeconds
                              + "s. The server is reachable but silent — check that the model is loaded and not stuck.");
                }
                if (item == LineSubscriber.END) break;
                if (item instanceof Throwable t) {
                    throw t instanceof Exception ex ? ex : new RuntimeException(t);
                }
                String line = (String) item;
                gotFirstData = true;
                if (subscriber.statusCode != 200) {
                    errorBody.append(line).append('\n');
                    continue;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) continue;
                try {
                    JsonObject obj    = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject delta  = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) continue;
                    String token      = delta.get("content").getAsString();
                    if (!token.isEmpty()) onToken.accept(token);
                } catch (Exception ignored) {}
            }
            if (subscriber.statusCode != 200 && subscriber.statusCode != 0) {
                throw new java.io.IOException("LLM server returned HTTP " + subscriber.statusCode
                        + (errorBody.isEmpty() ? "" : ": " + errorBody.toString().strip()));
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("STOPPED_BY_USER");
            }
            throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("STOPPED_BY_USER");
            }
            throw e;
        } finally {
            subscriber.cancel();
            future.cancel(true);
        }
    }

    /** Forwards SSE lines to a queue so the caller can enforce watchdog timeouts. */
    private static final class LineSubscriber implements Flow.Subscriber<String> {
        static final Object END = new Object();
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        volatile int statusCode;
        private volatile Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(String line)      { queue.offer(line); }
        @Override public void onError(Throwable t)     { queue.offer(t); }
        @Override public void onComplete()             { queue.offer(END); }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                try { s.cancel(); } catch (Exception ignored) {}
            }
        }
    }

    private JsonObject buildBody(String model, List<ChatMessage> messages, List<AttachmentData> attachments) {
        JsonObject body = new JsonObject();
        body.addProperty("model",      model);
        body.addProperty("stream",     true);
        body.addProperty("max_tokens", maxOutputTokens);

        JsonArray msgs = new JsonArray();
        int lastIndex = messages == null ? -1 : messages.size() - 1;
        for (int i = 0; i < (messages == null ? 0 : messages.size()); i++) {
            ChatMessage m = messages.get(i);
            JsonObject msg = new JsonObject();
            msg.addProperty("role",    m.role());
            if (i == lastIndex && attachments != null && !attachments.isEmpty()) {
                msg.add("content", buildMultimodalContent(m.content(), attachments));
            } else {
                msg.addProperty("content", m.content());
            }
            msgs.add(msg);
        }
        body.add("messages", msgs);
        return body;
    }

    private JsonArray buildMultimodalContent(String text, List<AttachmentData> attachments) {
        JsonArray content = new JsonArray();

        if (text != null && !text.isBlank()) {
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", text);
            content.add(textPart);
        }

        String attachmentPrompt = AttachmentUtil.buildPromptBlock(attachments);
        if (!attachmentPrompt.isBlank()) {
            JsonObject attachmentPart = new JsonObject();
            attachmentPart.addProperty("type", "text");
            attachmentPart.addProperty("text", attachmentPrompt);
            content.add(attachmentPart);
        }

        for (AttachmentData attachment : attachments) {
            if (attachment == null || !attachment.image() || !attachment.hasImageContent()) continue;
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:" + attachment.mimeType() + ";base64," + attachment.base64Content());
            imagePart.add("image_url", imageUrl);
            content.add(imagePart);
        }

        return content;
    }
}
