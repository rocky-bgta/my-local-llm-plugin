package plugin.llm.model;

import java.util.List;

/**
 * A single conversation turn.
 * {@code images} holds raw image bytes for vision requests; empty list for text-only.
 */
public record ChatMessage(String role, String content, List<byte[]> images) {

    /** Convenience constructor for text-only messages. */
    public ChatMessage(String role, String content) {
        this(role, content, List.of());
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
