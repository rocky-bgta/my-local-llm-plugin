package plugin.llm.model;

public record ChatMessage(String role, String content) {
    @Override
    public String toString() {
        return "ChatMessage(role=%s, content=%s)".formatted(role, content);
    }
}