package plugin.index;

public record IndexEntry(
        String filePath,
        String relativeFilePath,
        String symbolName,
        String symbolType,
        String content,
        String packageName,
        double relevanceScore
) {
    public enum SymbolType {
        CLASS, INTERFACE, ENUM, METHOD, FIELD, CONFIG, TEST, DOCUMENTATION
    }
}
