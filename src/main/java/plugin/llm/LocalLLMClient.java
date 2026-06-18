package plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import plugin.llm.model.ChatMessage;

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
    private final HttpClient http;

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
        JsonObject body = buildBody(model, messages);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept",       "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .timeout(Duration.ofSeconds(180))
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

    private JsonObject buildBody(String model, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model",  model);
        body.addProperty("stream", true);

        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role",    m.role());
            msg.addProperty("content", m.content());
            msgs.add(msg);
        }
        body.add("messages", msgs);
        return body;
    }
}
