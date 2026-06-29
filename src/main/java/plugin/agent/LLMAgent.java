package plugin.agent;

import plugin.llm.LocalLLMClient;
import plugin.rag.ContextBuilder;
import plugin.rag.RetrievalResult;
import plugin.settings.PluginSettings;
import plugin.util.LLMCorrectionsUtil;

import java.util.List;
import java.util.function.Consumer;

public class LLMAgent {

    private final ContextBuilder contextBuilder;

    public LLMAgent() {
        this.contextBuilder = new ContextBuilder();
    }

    public void stream(AgentContext ctx, Consumer<String> onToken) throws Exception {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (state == null) throw new IllegalStateException("Plugin settings not available");

        String corrections = LLMCorrectionsUtil.loadCorrectionsForPrompt(
                ctx.getProject().getBasePath());
        List<RetrievalResult> retrieved = ctx.getRetrievedContext();

        String fullPrompt = contextBuilder.build(
                ctx.getTask().userMessage(), retrieved,
                ctx.getConversationMemory().getRecentHistory(6),
                corrections
        );

        ctx.setBuiltPrompt(fullPrompt);

        // Build messages: system prompt + history + current user message
        var messages = new java.util.ArrayList<>(
                ctx.getConversationMemory().getRecentHistory(6));

        // If the last message is the current user message already added, skip adding it again
        boolean alreadyAdded = !messages.isEmpty()
                && messages.get(messages.size() - 1).role().equals("user")
                && messages.get(messages.size() - 1).content().equals(fullPrompt);

        if (!alreadyAdded) {
            messages = new java.util.ArrayList<>(
                    ctx.getConversationMemory().getRecentHistory(6));
            // Replace last user message content with the RAG-enriched prompt
            if (!messages.isEmpty() && messages.get(messages.size() - 1).role().equals("user")) {
                messages.set(messages.size() - 1,
                        new plugin.llm.model.ChatMessage("user", fullPrompt));
            } else {
                messages.add(new plugin.llm.model.ChatMessage("user", fullPrompt));
            }
        }

        LocalLLMClient client = new LocalLLMClient(state.endpoint);
        StringBuilder response = new StringBuilder();
        client.streamChat(state.model, messages, token -> {
            response.append(token);
            onToken.accept(token);
        });
        ctx.setLlmResponse(response.toString());
        ctx.getConversationMemory().addAssistantMessage(response.toString());
    }
}
