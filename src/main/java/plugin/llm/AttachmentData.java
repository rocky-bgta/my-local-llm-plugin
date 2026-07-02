package plugin.llm;

import java.nio.file.Path;

public record AttachmentData(
        Path path,
        String displayName,
        String mimeType,
        boolean image,
        String textContent,
        String base64Content,
        long sizeBytes
) {
    public boolean hasTextContent() {
        return textContent != null && !textContent.isBlank();
    }

    public boolean hasImageContent() {
        return image && base64Content != null && !base64Content.isBlank();
    }
}
