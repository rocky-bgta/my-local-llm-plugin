package plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import plugin.llm.model.ChatMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

public class LMStudioClient {

    private final String baseUrl;
    private final HttpClient http;

    public LMStudioClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Model list
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Streaming chat
    // -------------------------------------------------------------------------

    /**
     * Streams the assistant reply token-by-token via SSE.
     * Messages that carry images use the vision content-array format;
     * text-only messages use the plain string format.
     */
    public void streamChat(String model, List<ChatMessage> messages,
                           Consumer<String> onToken) throws Exception {
        JsonObject body = buildBody(model, messages);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept",       "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(120))
                .build();

        var res = http.send(req, HttpResponse.BodyHandlers.ofLines());
        res.body().forEach(line -> {
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
    }

    // -------------------------------------------------------------------------
    // Request body builder
    // -------------------------------------------------------------------------

    private JsonObject buildBody(String model, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model",  model);
        body.addProperty("stream", true);

        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role());

            if (m.hasImages()) {
                // Vision format: content is a JSON array of parts
                JsonArray contentArray = new JsonArray();

                // Text part
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", m.content());
                contentArray.add(textPart);

                // Image parts
                for (byte[] imgBytes : m.images()) {
                    String mime   = detectMimeType(imgBytes);
                    String b64    = Base64.getEncoder().encodeToString(imgBytes);
                    JsonObject imgUrlObj = new JsonObject();
                    imgUrlObj.addProperty("url", "data:" + mime + ";base64," + b64);

                    JsonObject imgPart = new JsonObject();
                    imgPart.addProperty("type", "image_url");
                    imgPart.add("image_url", imgUrlObj);
                    contentArray.add(imgPart);
                }

                msg.add("content", contentArray);
            } else {
                msg.addProperty("content", m.content());
            }

            msgs.add(msg);
        }
        body.add("messages", msgs);
        return body;
    }

    // -------------------------------------------------------------------------
    // MIME detection from magic bytes
    // -------------------------------------------------------------------------

    private static String detectMimeType(byte[] b) {
        if (b.length >= 4
                && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 2
                && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
    }
}
