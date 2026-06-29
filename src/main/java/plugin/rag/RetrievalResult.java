package plugin.rag;

import java.util.List;

public record RetrievalResult(
        String filePath,
        String relativeFilePath,
        String symbolName,
        String symbolType,
        String content,
        double score,
        String retrievalSource
) {
    public static RetrievalResult of(String filePath, String relPath, String symbol,
                                      String type, String content, double score, String source) {
        return new RetrievalResult(filePath, relPath, symbol, type, content, score, source);
    }

    public static List<RetrievalResult> empty() {
        return List.of();
    }
}
