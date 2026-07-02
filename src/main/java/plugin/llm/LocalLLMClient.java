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
import java.util.function.Consumer;

public class LocalLLMClient {

    private final String baseUrl;
    final HttpClient http;

    public LocalLLMClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<String> fetchModels() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models"))
                .header("Accept", "application/json")
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
        JsonObject body = buildBody(model, messages, List.of());
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept",       "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .timeout(Duration.ofSeconds(300))
                .build();

        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofLines());
            res.body().forEach(line -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("STREAM_INTERRUPTED");
                }
                if (!line.startsWith("data: ")) return;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) return;
                try {
                    JsonObject obj    = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) return;
                    JsonObject delta  = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) return;
                    String token      = delta.get("content").getAsString();
                    if (!token.isEmpty()) onToken.accept(token);
                } catch (Exception ignored) {}
            });
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("STOPPED_BY_USER");
            }
            throw e;
        }
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
                .timeout(Duration.ofSeconds(300))
                .build();

        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofLines());
            res.body().forEach(line -> {
                if (Thread.currentThread().isInterrupted()) {
                    // We throw a dedicated exception to be caught in ChatPanel
                    throw new RuntimeException("STREAM_INTERRUPTED");
                }
                if (!line.startsWith("data: ")) return;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) return;
                try {
                    JsonObject obj    = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) return;
                    JsonObject delta  = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) return;
                    String token      = delta.get("content").getAsString();
                    if (!token.isEmpty()) onToken.accept(token);
                } catch (Exception ignored) {}
            });
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("STOPPED_BY_USER");
            }
            throw e;
        }
    }

    private JsonObject buildBody(String model, List<ChatMessage> messages, List<AttachmentData> attachments) {
        JsonObject body = new JsonObject();
        body.addProperty("model",      model);
        body.addProperty("stream",     true);
        body.addProperty("max_tokens", 4096);

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
