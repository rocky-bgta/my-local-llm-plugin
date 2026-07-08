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
 * Deterministically repairs "X has private access in Y" compile errors in
 * generated Java files — local LLMs keep referencing private constants of the
 * class under test. When the private member is a compile-time constant with a
 * literal initializer, the qualified reference is replaced with the literal
 * value, which is cheaper and more reliable than spending an LLM fix attempt.
 */
public final class VisibilityFixer {

    // [ERROR] /C:/repo/src/test/java/pkg/FooTest.java:[24,47] MAX_HISTORY has private access in pkg.Foo
    private static final Pattern PRIVATE_ACCESS = Pattern.compile(
            "\\[ERROR\\]\\s+/?([^\\s\\[\\]]+\\.java):\\[\\d+,\\d+\\]\\s+(\\w+)\\s+has\\s+private\\s+access\\s+in\\s+([\\w.$]+)");
    private static final Pattern SIMPLE_LITERAL = Pattern.compile(
            "^(?:-?\\d[\\d_]*(?:\\.\\d+)?[LlFfDd]?|\"[^\"]*\"|'\\\\?.'|true|false)$");

    private VisibilityFixer() {}

    /**
     * Parses private-access compile errors and replaces qualified references to
     * private literal constants with their values.
     *
     * @return human-readable summary of the replacements, or "" when nothing
     *         could be fixed deterministically.
     */
    public static String attemptAutoFix(String basePath, String buildOutput) {
        if (basePath == null || buildOutput == null || buildOutput.isBlank()) return "";

        List<String> changes = new ArrayList<>();
        for (String[] error : privateAccessErrors(buildOutput)) {
            Path file = Paths.get(error[0]);
            if (!Files.isRegularFile(file)) continue;
            String symbol   = error[1];
            String ownerFqn = error[2];
            Path ownerSource = findSource(basePath, ownerFqn);
            if (ownerSource == null) continue;
            String literal = constantLiteral(ownerSource, symbol);
            if (literal == null) continue;
            String ownerSimple = ownerFqn.substring(ownerFqn.lastIndexOf('.') + 1);
            if (replaceReferences(file, ownerSimple, symbol, literal) > 0) {
                changes.add(file.getFileName() + ": " + ownerSimple + "." + symbol + " → " + literal);
            }
        }
        if (changes.isEmpty()) return "";
        return "Auto-fixed private member references (replaced with constant values):\n- "
                + String.join("\n- ", changes);
    }

    /** @return deduplicated [brokenFilePath, symbol, ownerFqn] triples. */
    static List<String[]> privateAccessErrors(String buildOutput) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> errors = new ArrayList<>();
        Matcher m = PRIVATE_ACCESS.matcher(buildOutput);
        while (m.find()) {
            String file = normalizeErrorPath(m.group(1));
            String key  = file + "|" + m.group(2) + "|" + m.group(3);
            if (seen.add(key)) errors.add(new String[]{file, m.group(2), m.group(3)});
        }
        return errors;
    }

    private static String normalizeErrorPath(String rawPath) {
        // Maven prints /C:/repo/... on Windows
        String path = rawPath.replace("\\", "/");
        if (path.matches("^/[A-Za-z]:/.*")) path = path.substring(1);
        return path;
    }

    private static Path findSource(String basePath, String fqn) {
        String relative = fqn.replace('.', '/') + ".java";
        for (String sourceRoot : List.of("src/main/java", "src/test/java")) {
            Path candidate = Paths.get(basePath, sourceRoot, relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** @return the literal initializer of a private constant, or null when not a simple literal. */
    static String constantLiteral(Path ownerSource, String symbol) {
        try {
            String content = Files.readString(ownerSource, StandardCharsets.UTF_8);
            Pattern decl = Pattern.compile(
                    "private\\s+(?:static\\s+)?(?:final\\s+)?\\S[^=;]*?\\b"
                            + Pattern.quote(symbol) + "\\s*=\\s*([^;]+);");
            Matcher m = decl.matcher(content);
            if (!m.find()) return null;
            String value = m.group(1).trim();
            return SIMPLE_LITERAL.matcher(value).matches() ? value : null;
        } catch (IOException e) {
            return null;
        }
    }

    static int replaceReferences(Path file, String ownerSimple, String symbol, String literal) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Pattern ref = Pattern.compile(
                    "\\b" + Pattern.quote(ownerSimple) + "\\s*\\.\\s*" + Pattern.quote(symbol) + "\\b");
            Matcher m = ref.matcher(content);
            int count = 0;
            StringBuilder updated = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(updated, Matcher.quoteReplacement(literal));
                count++;
            }
            if (count == 0) return 0;
            m.appendTail(updated);
            Files.writeString(file, updated.toString(), StandardCharsets.UTF_8);
            return count;
        } catch (IOException e) {
            return 0;
        }
    }
}
