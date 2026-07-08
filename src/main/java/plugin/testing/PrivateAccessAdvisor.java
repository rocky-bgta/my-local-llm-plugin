package plugin.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a targeted retry prompt when generated tests reference private
 * members of the class under test. VisibilityFixer repairs private *constant*
 * references deterministically; private *method* calls cannot be fixed that
 * way — the test must be rewritten against the public API. This advisor turns
 * the compile error into an explicit instruction so the LLM rewrites the test
 * instead of weakening production visibility.
 */
public final class PrivateAccessAdvisor {

    // javac: computeFinalScore(plugin.rag.RetrievalResult,java.util.List<java.lang.String>) has private access in plugin.rag.Reranker
    // javac field form: MAX_HISTORY has private access in plugin.memory.WorkingMemory
    private static final Pattern JAVA_PRIVATE_ACCESS = Pattern.compile(
            "([\\w$]+(?:\\([^)\\n]*\\))?)\\s+has\\s+private\\s+access\\s+in\\s+([\\w.$]+)");

    // kotlinc: cannot access 'computeFinalScore': it is private in 'Reranker'
    private static final Pattern KOTLIN_PRIVATE_ACCESS = Pattern.compile(
            "cannot access '([^'\\n]+)':? it is (?:private|internal) in '?([\\w.$]+)'?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "public\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?(?:<[^>\\n]+>\\s+)?"
                    + "[\\w.$]+(?:<[^;{\\n]*>)?(?:\\[\\])?\\s+(\\w+)\\s*\\(([^)]*)\\)");

    private static final int MAX_VIOLATIONS = 5;
    private static final int MAX_PUBLIC_METHODS = 15;

    private PrivateAccessAdvisor() {}

    /**
     * Scans build/test output for "called a private member" errors and returns
     * a retry instruction that forces the LLM to rewrite the test through the
     * public API of the owning class. Returns "" when no private-access error
     * is present.
     */
    public static String buildRetryGuidance(String basePath, String buildOutput) {
        if (buildOutput == null || buildOutput.isBlank()) return "";

        List<String[]> violations = extractViolations(buildOutput);
        if (violations.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(512);
        sb.append("PRIVATE ACCESS VIOLATION — the previous test failed because it called private members directly:\n");
        for (String[] v : violations) {
            sb.append("- ").append(v[0]).append(" in ").append(v[1]).append("\n");
        }
        sb.append("\nRules for the corrected test:\n")
          .append("1. NEVER call private methods, fields, or nested types directly.\n")
          .append("2. Do NOT modify production code visibility only for testing.\n")
          .append("3. Read the class under test and identify the PUBLIC methods that internally call the private member.\n")
          .append("4. Rewrite the test to verify the behavior through that public API instead.\n")
          .append("5. Keep the AAA pattern (Arrange, Act, Assert) in every test method.\n");

        Set<String> ownersListed = new LinkedHashSet<>();
        for (String[] v : violations) {
            String ownerFqn = v[1];
            if (!ownersListed.add(ownerFqn)) continue;
            List<String> publicApi = publicMethodsOf(basePath, ownerFqn);
            if (publicApi.isEmpty()) continue;
            String ownerSimple = ownerFqn.substring(ownerFqn.lastIndexOf('.') + 1);
            sb.append("\nPublic API of ").append(ownerFqn)
              .append(" (test through these — see ").append(ownerSimple).append(" source):\n");
            publicApi.stream().limit(MAX_PUBLIC_METHODS)
                    .forEach(m -> sb.append("- ").append(m).append("\n"));
        }

        sb.append("\nGenerate a corrected test that does not reference private members.");
        return sb.toString();
    }

    /** @return deduplicated [symbol, ownerFqn] pairs, capped at MAX_VIOLATIONS. */
    static List<String[]> extractViolations(String buildOutput) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> violations = new ArrayList<>();
        for (Pattern pattern : List.of(JAVA_PRIVATE_ACCESS, KOTLIN_PRIVATE_ACCESS)) {
            Matcher m = pattern.matcher(buildOutput);
            while (m.find() && violations.size() < MAX_VIOLATIONS) {
                String symbol = m.group(1).trim();
                String owner  = m.group(2).trim();
                if (seen.add(symbol + "|" + owner)) {
                    violations.add(new String[]{symbol, owner});
                }
            }
        }
        return violations;
    }

    /** @return "name(params)" signatures of public methods declared in the owner's source, or empty. */
    static List<String> publicMethodsOf(String basePath, String ownerFqn) {
        Path source = findSource(basePath, ownerFqn);
        if (source == null) return List.of();
        try {
            return publicMethodSignatures(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** @return "name(params)" signatures of public methods declared in the given source content. */
    public static List<String> publicMethodSignatures(String sourceContent) {
        if (sourceContent == null || sourceContent.isBlank()) return List.of();
        List<String> methods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = PUBLIC_METHOD.matcher(sourceContent);
        while (m.find()) {
            String signature = m.group(1) + "(" + m.group(2).trim() + ")";
            if (seen.add(signature)) methods.add(signature);
        }
        return methods;
    }

    private static Path findSource(String basePath, String fqn) {
        if (basePath == null || fqn == null || fqn.isBlank()) return null;
        String topLevel = fqn.contains("$") ? fqn.substring(0, fqn.indexOf('$')) : fqn;
        String relative = topLevel.replace('.', '/');
        for (String sourceRoot : List.of("src/main/java", "src/main/kotlin")) {
            for (String extension : List.of(".java", ".kt")) {
                Path candidate = Paths.get(basePath, sourceRoot, relative + extension);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }
}
