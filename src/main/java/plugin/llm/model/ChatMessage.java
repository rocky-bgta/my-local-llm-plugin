package plugin.llm.model;

import java.util.ArrayList;
import java.util.List;

public class ChatMessage {

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    private final Role role;
    private final String content;
    private final List<ImageAttachment> images;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.images = new ArrayList<>();
    }

    public ChatMessage(Role role, String content, List<ImageAttachment> images) {
        this.role = role;
        this.content = content;
        this.images = images != null ? new ArrayList<>(images) : new ArrayList<>();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<ImageAttachment> getImages() {
        return images;
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }
}
