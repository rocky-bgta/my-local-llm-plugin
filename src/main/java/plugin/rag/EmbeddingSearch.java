package plugin.rag;

import plugin.index.IndexEntry;
import plugin.index.SymbolIndex;
import plugin.util.LanguageSupportUtil;

import java.util.List;
import java.util.stream.Collectors;

public class EmbeddingSearch {

    private final SymbolIndex symbolIndex;

    public EmbeddingSearch(SymbolIndex symbolIndex) {
        this.symbolIndex = symbolIndex;
    }

    public List<RetrievalResult> search(String query, int topK) {
        List<IndexEntry> entries = symbolIndex.search(query, topK * 2);
        return entries.stream()
                .map(e -> RetrievalResult.of(
                        e.filePath(), e.relativeFilePath(), e.symbolName(),
                        e.symbolType(), e.content(), computeScore(e, query), "EMBEDDING_TFIDF"
                ))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<RetrievalResult> searchSimilarTests(String targetClass, int topK) {
        String query = "test " + targetClass + " assert mock";
        return symbolIndex.search(query, topK * 2).stream()
                .filter(e -> "TEST".equals(e.symbolType()) ||
                        e.symbolName().endsWith("Test") || e.filePath().contains("test") ||
                        LanguageSupportUtil.isTestFile(e.filePath()))
                .map(e -> RetrievalResult.of(
                        e.filePath(), e.relativeFilePath(), e.symbolName(),
                        e.symbolType(), e.content(), computeScore(e, query), "EMBEDDING_TEST"
                ))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<RetrievalResult> searchSimilarImplementations(String query, int topK) {
        return search(query + " implementation service repository", topK);
    }

    private double computeScore(IndexEntry entry, String query) {
        List<String> queryTerms = SymbolIndex.tokenize(query);
        List<String> contentTerms = SymbolIndex.tokenize(entry.content() + " " + entry.symbolName());
        long matches = queryTerms.stream().filter(contentTerms::contains).count();
        return (double) matches / Math.max(queryTerms.size(), 1);
    }
}
