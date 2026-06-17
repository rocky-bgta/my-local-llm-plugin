package plugin.llm.model;

import java.awt.image.BufferedImage;

public class ImageAttachment {

    private final String fileName;
    private final String base64Data;
    private final String mimeType;
    private final BufferedImage thumbnail;

    public ImageAttachment(String fileName, String base64Data, String mimeType, BufferedImage thumbnail) {
        this.fileName = fileName;
        this.base64Data = base64Data;
        this.mimeType = mimeType;
        this.thumbnail = thumbnail;
    }

    public String getFileName() {
        return fileName;
    }

    public String getBase64Data() {
        return base64Data;
    }

    public String getMimeType() {
        return mimeType;
    }

    public BufferedImage getThumbnail() {
        return thumbnail;
    }

    public String toDataUri() {
        return "data:" + mimeType + ";base64," + base64Data;
    }
}
