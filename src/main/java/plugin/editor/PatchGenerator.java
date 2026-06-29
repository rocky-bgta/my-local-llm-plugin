package plugin.editor;

import java.util.*;

public class PatchGenerator {

    public String generateUnifiedDiff(String original, String modified, String fileName) {
        List<String> origLines = splitLines(original);
        List<String> modLines = splitLines(modified);

        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(fileName).append("\n");
        diff.append("+++ b/").append(fileName).append("\n");

        int maxLines = Math.max(origLines.size(), modLines.size());
        int i = 0, j = 0;
        while (i < origLines.size() || j < modLines.size()) {
            String orig = i < origLines.size() ? origLines.get(i) : null;
            String mod = j < modLines.size() ? modLines.get(j) : null;

            if (Objects.equals(orig, mod)) {
                diff.append(" ").append(orig).append("\n");
                i++; j++;
            } else {
                if (orig != null) { diff.append("-").append(orig).append("\n"); i++; }
                if (mod != null) { diff.append("+").append(mod).append("\n"); j++; }
            }
        }
        return diff.toString();
    }

    public DiffSummary summarize(String original, String modified) {
        List<String> origLines = splitLines(original);
        List<String> modLines = splitLines(modified);

        int added = 0, removed = 0;
        Set<String> origSet = new HashSet<>(origLines);
        Set<String> modSet = new HashSet<>(modLines);

        for (String line : modLines) if (!origSet.contains(line)) added++;
        for (String line : origLines) if (!modSet.contains(line)) removed++;

        return new DiffSummary(added, removed, origLines.size(), modLines.size());
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return Arrays.asList(text.split("\n", -1));
    }

    public record DiffSummary(int linesAdded, int linesRemoved, int originalLines, int modifiedLines) {
        public String describe() {
            return String.format("+%d -%d (total: %d→%d lines)", linesAdded, linesRemoved, originalLines, modifiedLines);
        }
    }
}
