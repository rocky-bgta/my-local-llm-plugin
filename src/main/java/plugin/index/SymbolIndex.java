package plugin.index;

import java.util.*;
import java.util.stream.Collectors;

public class SymbolIndex {

    private final Map<String, IndexEntry> entries = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> termFrequency = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private int documentCount = 0;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "was", "one", "our", "out", "get", "has", "how", "its", "may",
            "new", "now", "see", "use", "java", "public", "private", "class",
            "void", "return", "import", "package", "static", "final", "null",
            "true", "false", "string", "list", "map", "int", "boolean", "this"
    );

    public synchronized void add(IndexEntry entry) {
        entries.put(entry.filePath(), entry);
        List<String> terms = tokenize(entry.content() + " " + entry.symbolName() + " " + entry.packageName());
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) tf.merge(term, 1, Integer::sum);
        termFrequency.put(entry.filePath(), tf);
        for (String term : tf.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        documentCount++;
    }

    public List<IndexEntry> search(String query, int topK) {
        if (entries.isEmpty()) return Collections.emptyList();
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return Collections.emptyList();

        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> docEntry : termFrequency.entrySet()) {
            double score = 0.0;
            for (String term : queryTerms) {
                Integer freq = docEntry.getValue().get(term);
                if (freq != null) {
                    double idf = Math.log((documentCount + 1.0) /
                            (documentFrequency.getOrDefault(term, 1) + 1.0)) + 1.0;
                    score += freq * idf;
                }
            }
            // Boost exact symbol name matches
            IndexEntry e = entries.get(docEntry.getKey());
            if (e != null) {
                String sym = e.symbolName().toLowerCase();
                for (String term : queryTerms) {
                    if (sym.contains(term)) score += 5.0;
                }
            }
            if (score > 0) scores.put(docEntry.getKey(), score);
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
        documentCount = 0;
    }

    public Collection<IndexEntry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public int size() { return entries.size(); }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String[] parts = text.split("[^a-zA-Z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        return Arrays.stream(parts)
                .map(String::toLowerCase)
                .filter(s -> s.length() > 2)
                .filter(s -> !STOP_WORDS.contains(s))
                .collect(Collectors.toList());
    }
}
