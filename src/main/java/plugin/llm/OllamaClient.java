package plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import plugin.llm.model.ChatMessage;
import plugin.llm.model.ImageAttachment;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class OllamaClient implements LLMClient {

    static final String AUTO_EDIT_SYSTEM_PROMPT =
        "When suggesting code changes, always format them as fenced code blocks " +
        "with the file path after the opening fence, like:\n" +
        "```java:src/main/java/com/example/MyClass.java\n" +
        "// COMPLETE file content here\n" +
        "```\n" +
        "Always output COMPLETE file content, never partial snippets. " +
        "For multiple file changes, output each as a separate fenced block.";

    private String baseUrl;
    private String model;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public void streamChat(List<ChatMessage> messages,
                           String systemPrompt,
                           List<String> images,
                           Consumer<String> onToken,
                           Runnable onComplete,
                           Consumer<Throwable> onError) {
        new Thread(() -> doStream(messages, systemPrompt, images, onToken, onComplete, onError),
                "ollama-stream").start();
    }

    private void doStream(List<ChatMessage> messages,
                          String systemPrompt,
                          List<String> images,
                          Consumer<String> onToken,
                          Runnable onComplete,
                          Consumer<Throwable> onError) {
        try (CloseableHttpClient client = buildHttpClient()) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", true);

            String fullSystem = AUTO_EDIT_SYSTEM_PROMPT +
                (systemPrompt != null && !systemPrompt.isBlank() ? "\n\n" + systemPrompt : "");

            JsonArray messagesArray = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", fullSystem);
            messagesArray.add(sysMsg);

            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                JsonObject msgNode = new JsonObject();
                msgNode.addProperty("role", msg.getRole().name().toLowerCase());
                msgNode.addProperty("content", msg.getContent());

                boolean isLastUser = i == messages.size() - 1
                        && msg.getRole() == ChatMessage.Role.USER
                        && images != null && !images.isEmpty();

                if (isLastUser) {
                    JsonArray imagesArray = new JsonArray();
                    for (String b64 : images) {
                        imagesArray.add(b64);
                    }
                    msgNode.add("images", imagesArray);
                } else if (msg.hasImages()) {
                    JsonArray imagesArray = new JsonArray();
                    for (ImageAttachment img : msg.getImages()) {
                        imagesArray.add(img.getBase64Data());
                    }
                    msgNode.add("images", imagesArray);
                }
                messagesArray.add(msgNode);
            }
            body.add("messages", messagesArray);

            HttpPost request = new HttpPost(baseUrl + "/api/chat");
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getCode() != 200) {
                    onError.accept(new RuntimeException("HTTP " + response.getCode()));
                    return;
                }
                try (InputStream is = response.getEntity().getContent();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (line.isBlank()) continue;
                        JsonObject node = JsonParser.parseString(line).getAsJsonObject();
                        if (node.has("done") && node.get("done").getAsBoolean()) break;
                        if (node.has("message")) {
                            JsonObject msgObj = node.getAsJsonObject("message");
                            if (msgObj.has("content")) {
                                String delta = msgObj.get("content").getAsString();
                                if (!delta.isEmpty()) onToken.accept(delta);
                            }
                        }
                    }
                }
            }
            onComplete.run();
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                onError.accept(e);
            }
        }
    }

    @Override
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        try (CloseableHttpClient client = buildHttpClient()) {
            HttpGet request = new HttpGet(baseUrl + "/api/tags");
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getCode() != 200) return models;
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("models")) {
                    for (JsonElement el : root.getAsJsonArray("models")) {
                        models.add(el.getAsJsonObject().get("name").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return models;
    }

    @Override
    public boolean isAvailable() {
        try (CloseableHttpClient client = buildHttpClient()) {
            HttpGet request = new HttpGet(baseUrl + "/api/tags");
            try (CloseableHttpResponse response = client.execute(request)) {
                return response.getCode() == 200;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getBackendName() {
        return "Ollama";
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setModel(String model) {
        this.model = model;
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(300))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
    }
}
