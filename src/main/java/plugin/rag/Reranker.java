package plugin.rag;

import java.util.*;
import java.util.stream.Collectors;

public class Reranker {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_CONTENT_CHARS = 4000;

    public List<RetrievalResult> rerank(List<RetrievalResult> candidates, String query, int topK) {
        if (candidates.isEmpty()) return candidates;

        List<String> queryTerms = tokenize(query);
        Map<String, Double> seen = new LinkedHashMap<>();

        List<ScoredResult> scored = candidates.stream()
                .map(r -> new ScoredResult(r, computeFinalScore(r, queryTerms)))
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .toList();

        List<RetrievalResult> result = new ArrayList<>();
        for (ScoredResult sr : scored) {
            if (result.size() >= topK) break;
            String key = deduplicationKey(sr.result());
            if (!seen.containsKey(key)) {
                seen.put(key, sr.score());
                result.add(truncateContent(sr.result()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<RetrievalResult> rerank(List<RetrievalResult> candidates, String query) {
        return rerank(candidates, query, DEFAULT_TOP_K);
    }

    private double computeFinalScore(RetrievalResult r, List<String> queryTerms) {
        double score = r.score();

        // Boost by source type priority
        score += switch (r.retrievalSource()) {
            case "PSI_PRIMARY" -> 5.0;
            case "PSI_DEPENDENCY" -> 3.0;
            case "PSI_EXISTING_TEST" -> 2.0;
            case "PSI_CONFIG" -> 2.0;
            case "EMBEDDING_TFIDF" -> 1.0;
            case "EMBEDDING_TEST" -> 1.5;
            default -> 0.0;
        };

        // Boost for exact symbol name match
        String symbol = r.symbolName().toLowerCase();
        for (String term : queryTerms) {
            if (symbol.equals(term)) score += 4.0;
            else if (symbol.contains(term)) score += 1.5;
        }

        // Prefer non-trivially small files
        if (r.content().length() > 200) score += 0.5;

        // Penalize very large files (noise risk)
        if (r.content().length() > 8000) score -= 1.0;

        return score;
    }

    private String deduplicationKey(RetrievalResult r) {
        return r.filePath();
    }

    private RetrievalResult truncateContent(RetrievalResult r) {
        if (r.content().length() <= MAX_CONTENT_CHARS) return r;
        return RetrievalResult.of(
                r.filePath(), r.relativeFilePath(), r.symbolName(),
                r.symbolType(), r.content().substring(0, MAX_CONTENT_CHARS) + "\n// [truncated]",
                r.score(), r.retrievalSource()
        );
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.toLowerCase().split("[\\s_\\-./]+"))
                .filter(s -> s.length() > 2)
                .toList();
    }

    private record ScoredResult(RetrievalResult result, double score) {}
}
