package plugin;

import org.junit.jupiter.api.Test;
import plugin.llm.LocalLLMClient;
import plugin.llm.model.ChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LocalLLMClientTest {

    @Test
    void constructorAcceptsBaseUrl() {
        assertDoesNotThrow(() -> new LocalLLMClient("http://localhost:1234"));
    }

    @Test
    void constructorAcceptsUrlWithTrailingSlash() {
        assertDoesNotThrow(() -> new LocalLLMClient("http://localhost:1234/"));
    }

    @Test
    void fetchModelsThrowsWhenServerUnreachable() {
        LocalLLMClient client = new LocalLLMClient("http://localhost:19999");
        assertThrows(Exception.class, client::fetchModels);
    }

    @Test
    void streamChatThrowsWhenServerUnreachable() {
        LocalLLMClient client = new LocalLLMClient("http://localhost:19999");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));
        assertThrows(Exception.class, () ->
            client.streamChat("test-model", messages, token -> {})
        );
    }
}
