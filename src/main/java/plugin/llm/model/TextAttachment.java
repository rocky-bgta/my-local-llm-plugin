package plugin.llm.model;

public class TextAttachment {

    private final String fileName;
    private final String content;

    public TextAttachment(String fileName, String content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }

    public String toPromptString() {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "txt";
        return "[Attached file: " + fileName + "]\n```" + ext + "\n" + content + "\n```\n";
    }
}
