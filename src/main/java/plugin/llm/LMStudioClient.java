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

public class LMStudioClient implements LLMClient {

    private static final String DONE_SENTINEL = "[DONE]";

    private String baseUrl;
    private String model;
    private final int maxTokens;
    private final double temperature;

    public LMStudioClient(String baseUrl, String model, int maxTokens, double temperature) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public void streamChat(List<ChatMessage> messages,
                           String systemPrompt,
                           List<String> images,
                           Consumer<String> onToken,
                           Runnable onComplete,
                           Consumer<Throwable> onError) {
        new Thread(() -> doStream(messages, systemPrompt, images, onToken, onComplete, onError),
                "lmstudio-stream").start();
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
            body.addProperty("max_tokens", maxTokens);
            body.addProperty("temperature", temperature);

            String fullSystem = OllamaClient.AUTO_EDIT_SYSTEM_PROMPT +
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

                boolean isLastUser = i == messages.size() - 1
                        && msg.getRole() == ChatMessage.Role.USER
                        && images != null && !images.isEmpty();

                boolean hasAttachedImages = msg.hasImages();

                if (isLastUser || hasAttachedImages) {
                    JsonArray contentArray = new JsonArray();
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", msg.getContent());
                    contentArray.add(textPart);

                    List<String> imageSources = new ArrayList<>();
                    if (isLastUser && images != null) imageSources.addAll(images);
                    if (hasAttachedImages) {
                        for (ImageAttachment img : msg.getImages()) {
                            imageSources.add(img.toDataUri());
                        }
                    }
                    for (String imgSrc : imageSources) {
                        JsonObject imagePart = new JsonObject();
                        imagePart.addProperty("type", "image_url");
                        JsonObject urlNode = new JsonObject();
                        String url = imgSrc.startsWith("data:") ? imgSrc : "data:image/png;base64," + imgSrc;
                        urlNode.addProperty("url", url);
                        imagePart.add("image_url", urlNode);
                        contentArray.add(imagePart);
                    }
                    msgNode.add("content", contentArray);
                } else {
                    msgNode.addProperty("content", msg.getContent());
                }
                messagesArray.add(msgNode);
            }
            body.add("messages", messagesArray);

            HttpPost request = new HttpPost(baseUrl + "/v1/chat/completions");
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
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if (DONE_SENTINEL.equals(data)) break;
                        JsonObject node = JsonParser.parseString(data).getAsJsonObject();
                        if (node.has("choices")) {
                            JsonObject delta = node.getAsJsonArray("choices")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("delta");
                            if (delta.has("content")) {
                                String token = delta.get("content").getAsString();
                                if (!token.isEmpty()) onToken.accept(token);
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
            HttpGet request = new HttpGet(baseUrl + "/v1/models");
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getCode() != 200) return models;
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("data")) {
                    for (JsonElement el : root.getAsJsonArray("data")) {
                        models.add(el.getAsJsonObject().get("id").getAsString());
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
            HttpGet request = new HttpGet(baseUrl + "/v1/models");
            try (CloseableHttpResponse response = client.execute(request)) {
                return response.getCode() == 200;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getBackendName() {
        return "LM Studio";
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
