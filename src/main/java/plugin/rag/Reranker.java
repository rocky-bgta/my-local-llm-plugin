package plugin.rag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reranks and deduplicates RAG candidates before they reach the LLM.
 *
 * Budget tuned for Qwen2.5-Coder-7B:
 *  - TOP_K = 6 files  (8 was too many for a 7B context window)
 *  - MAX_CONTENT_CHARS = 2 500 per file  (keeps total RAG block ≤ 10 000 chars)
 *
 * Scoring combines the BM25 retrieval score with structural signals:
 *  - PSI_PRIMARY (direct class hit) gets the strongest boost.
 *  - Config files (pom.xml, plugin.xml) are included but not over-weighted.
 *  - Very large files are penalised so they don't crowd out targeted results.
 */
public class Reranker {

    private static final int DEFAULT_TOP_K      = 6;
    private static final int MAX_CONTENT_CHARS  = 2_500;

    public List<RetrievalResult> rerank(List<RetrievalResult> candidates,
                                         String query, int topK) {
        if (candidates.isEmpty()) return candidates;

        List<String> queryTerms = tokenize(query);

        List<ScoredResult> scored = candidates.stream()
                .map(r -> new ScoredResult(r, computeFinalScore(r, queryTerms)))
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .toList();

        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<RetrievalResult> result = new ArrayList<>();
        for (ScoredResult sr : scored) {
            if (result.size() >= topK) break;
            String key = sr.result().filePath();
            if (!seen.containsKey(key)) {
                seen.put(key, Boolean.TRUE);
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

        score += switch (r.retrievalSource()) {
            case "PSI_PRIMARY"       -> 6.0;
            case "PSI_DEPENDENCY"    -> 3.5;
            case "PSI_EXISTING_TEST" -> 3.0;
            case "PSI_CONFIG"        -> 2.0;
            case "EMBEDDING_TEST"    -> 2.0;
            case "EMBEDDING_TFIDF"   -> 1.0;
            default                  -> 0.0;
        };

        String symbol = r.symbolName().toLowerCase();
        for (String term : queryTerms) {
            if (symbol.equals(term))        score += 4.0;
            else if (symbol.contains(term)) score += 1.5;
        }

        int len = r.content().length();
        if (len > 200)  score += 0.5;  // non-trivial file
        if (len > 6000) score -= 1.5;  // penalise very large files (noise risk)

        return score;
    }

    private RetrievalResult truncateContent(RetrievalResult r) {
        if (r.content().length() <= MAX_CONTENT_CHARS) return r;
        return RetrievalResult.of(
                r.filePath(), r.relativeFilePath(), r.symbolName(), r.symbolType(),
                r.content().substring(0, MAX_CONTENT_CHARS) + "\n// [truncated for 7B context budget]",
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
