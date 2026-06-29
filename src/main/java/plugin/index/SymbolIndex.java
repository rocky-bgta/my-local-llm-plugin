package plugin.index;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory symbol index using BM25 scoring (Okapi BM25).
 *
 * BM25 outperforms plain TF-IDF for code retrieval because:
 *  - Term saturation (k1): a term appearing 10× in a file scores only
 *    marginally higher than 3×, preventing large files from dominating.
 *  - Length normalisation (b): short focused files (e.g. a single service
 *    class) score higher than giant files with the same term count.
 *
 * Parameters k1=1.5, b=0.75 are the standard Elasticsearch defaults and
 * work well for source-code corpora.
 */
public class SymbolIndex {

    private static final double K1 = 1.5;
    private static final double B  = 0.75;

    private final Map<String, IndexEntry>          entries          = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> termFrequency   = new HashMap<>();
    private final Map<String, Integer>              documentFrequency = new HashMap<>();
    private final Map<String, Integer>              docLengths       = new HashMap<>();
    private int    documentCount  = 0;
    private long   totalTermCount = 0;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "was", "one", "our", "out", "get", "has", "how", "its", "may",
            "new", "now", "see", "use", "java", "public", "private", "class",
            "void", "return", "import", "package", "static", "final", "null",
            "true", "false", "string", "list", "map", "int", "boolean", "this"
    );

    public synchronized void add(IndexEntry entry) {
        entries.put(entry.filePath(), entry);
        List<String> terms = tokenize(
                entry.content() + " " + entry.symbolName() + " " + entry.packageName());
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) tf.merge(term, 1, Integer::sum);
        termFrequency.put(entry.filePath(), tf);
        docLengths.put(entry.filePath(), terms.size());
        totalTermCount += terms.size();
        for (String term : tf.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        documentCount++;
    }

    public List<IndexEntry> search(String query, int topK) {
        if (entries.isEmpty()) return Collections.emptyList();
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return Collections.emptyList();

        double avgdl = documentCount > 0 ? (double) totalTermCount / documentCount : 1.0;
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Map<String, Integer>> docEntry : termFrequency.entrySet()) {
            String docId = docEntry.getKey();
            double dl = docLengths.getOrDefault(docId, 1);
            double score = 0.0;

            for (String term : queryTerms) {
                Integer freq = docEntry.getValue().get(term);
                if (freq == null) continue;
                int df = documentFrequency.getOrDefault(term, 1);
                // Robertson IDF — always positive
                double idf = Math.log((documentCount - df + 0.5) / (df + 0.5) + 1.0);
                double tf  = (freq * (K1 + 1))
                           / (freq + K1 * (1 - B + B * dl / avgdl));
                score += idf * tf;
            }

            // Symbol-name exact/partial match boost (structural signal BM25 can't see)
            IndexEntry e = entries.get(docId);
            if (e != null) {
                String sym = e.symbolName().toLowerCase();
                for (String term : queryTerms) {
                    if (sym.equals(term))        score += 5.0;
                    else if (sym.contains(term)) score += 1.5;
                }
            }

            if (score > 0) scores.put(docId, score);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> entries.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Optional<IndexEntry> findBySymbol(String symbolName) {
        return entries.values().stream()
                .filter(e -> e.symbolName().equalsIgnoreCase(symbolName))
                .findFirst();
    }

    public List<IndexEntry> findByPackage(String packageName) {
        return entries.values().stream()
                .filter(e -> e.packageName().startsWith(packageName))
                .collect(Collectors.toList());
    }

    public synchronized void clear() {
        entries.clear();
        termFrequency.clear();
        documentFrequency.clear();
        docLengths.clear();
        documentCount  = 0;
        totalTermCount = 0;
    }

    public Collection<IndexEntry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public int size() { return entries.size(); }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String[] parts = text.split(
                "[^a-zA-Z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        return Arrays.stream(parts)
                .map(String::toLowerCase)
                .filter(s -> s.length() > 2)
                .filter(s -> !STOP_WORDS.contains(s))
                .collect(Collectors.toList());
    }
}
