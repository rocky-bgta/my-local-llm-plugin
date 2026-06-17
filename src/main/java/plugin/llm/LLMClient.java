package plugin.llm;

import plugin.llm.model.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

public interface LLMClient {

    void streamChat(List<ChatMessage> messages,
                    String systemPrompt,
                    List<String> images,
                    Consumer<String> onToken,
                    Runnable onComplete,
                    Consumer<Throwable> onError);

    List<String> listModels();

    boolean isAvailable();

    String getBackendName();
}
