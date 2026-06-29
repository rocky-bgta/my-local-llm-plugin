package plugin.memory;

import plugin.llm.model.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConversationMemory {

    private static final int MAX_HISTORY = 50;

    private final List<ChatMessage> history = new ArrayList<>();

    public void addMessage(String role, String content) {
        if (history.size() >= MAX_HISTORY) {
            // Keep the first system message + recent messages
            if (history.size() > 1) history.remove(1);
        }
        history.add(new ChatMessage(role, content));
    }

    public void addUserMessage(String content) { addMessage("user", content); }

    public void addAssistantMessage(String content) { addMessage("assistant", content); }

    public List<ChatMessage> getHistory() { return Collections.unmodifiableList(history); }

    public List<ChatMessage> getRecentHistory(int n) {
        if (history.size() <= n) return getHistory();
        return Collections.unmodifiableList(history.subList(history.size() - n, history.size()));
    }

    public void reset() { history.clear(); }

    public int size() { return history.size(); }

    public boolean isEmpty() { return history.isEmpty(); }
}
