package plugin.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts a structured {@link ErrorContext} from raw build or test output.
 *
 * <p>Error grouping rule: all compile errors in the same source file are treated
 * as one repair target — the first file encountered is the root cause. After that
 * file is fixed and the build re-runs, the next file's errors become the new root
 * cause. This implements the spec's multi-error handling policy: fix one root
 * cause per LLM round-trip.
 */
public final class ErrorExtractor {

    // Matches: [ERROR] /abs/or/rel/Foo.java:[14,5] message
    //          [ERROR] src/test/java/Foo.java:[14,5] message
    // Windows:  [ERROR] C:\path\Foo.java:[14,5] message
    private static final Pattern COMPILE_ERROR = Pattern.compile(
            "\\[ERROR\\][^\\n]*?([^\\s\\n\\[]*\\.(?:java|kt)):\\[?(\\d+)[,\\d]*\\]?\\s+(.{1,300})");

    // Matches failing test class / method references in Maven output.
    // Uses [\w.]+ so package-qualified names like plugin.rag.RerankerTest are captured.
    private static final Pattern TEST_FAILURE_CLASS = Pattern.compile(
            "\\[ERROR\\]\\s+([\\w.]+(?:Test|IT|Spec)(?:\\.\\w+)?)");

    private static final int MAX_FILE_CHARS = 4_000;

    private ErrorExtractor() {}

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Parses {@code buildOutput} and returns the first structured error group.
     * Returns an {@link ErrorContext#unknown} fallback when no error can be parsed.
     *
     * @param buildOutput   raw build/test output from the failed command
     * @param basePath      project root directory; used to read file content (may be null)
     * @param failedCommand the command that produced the output (e.g. {@code "mvn test"})
     */
    public static ErrorContext extract(String buildOutput, String basePath, String failedCommand) {
        if (buildOutput == null || buildOutput.isBlank()) {
            return ErrorContext.unknown(failedCommand, "No error output captured.");
        }

        List<ParsedError> compileErrors = parseCompileErrors(buildOutput);
        if (!compileErrors.isEmpty()) {
            return buildFromCompileErrors(compileErrors, basePath, failedCommand);
        }

        String testSnippet = extractTestFailureSnippet(buildOutput);
        if (!testSnippet.isBlank()) {
            return new ErrorContext(failedCommand, testSnippet, "", 0, "", Map.of(), testSnippet);
        }

        return ErrorContext.unknown(failedCommand, firstErrorLine(buildOutput));
    }

    // ── compile error handling ────────────────────────────────────────────────

    static List<ParsedError> parseCompileErrors(String output) {
        List<ParsedError> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = COMPILE_ERROR.matcher(output);
        while (m.find()) {
            String rawPath = m.group(1).trim();
            int line       = Integer.parseInt(m.group(2));
            String message = m.group(3).trim();
            String key     = rawPath + ":" + line + ":" + message;
            if (seen.add(key)) {
                errors.add(new ParsedError(normalizeSeparators(rawPath), line, message));
            }
        }
        return errors;
    }

    private static ErrorContext buildFromCompileErrors(
            List<ParsedError> errors, String basePath, String failedCommand) {

        // First file encountered in output order is the root cause target.
        String rootFile = errors.get(0).filePath();
        List<ParsedError> rootGroup = errors.stream()
                .filter(e -> e.filePath().equals(rootFile))
                .toList();

        ParsedError first      = rootGroup.get(0);
        int affectedLine       = first.line();

        String snippet = buildSnippet(rootFile, rootGroup);
        String rootCauseGroup = rootGroup.stream()
                .map(e -> "  Line " + e.line() + ": " + e.message())
                .collect(Collectors.joining("\n"));

        String affectedContent    = readFile(basePath, rootFile);
        Map<String, String> related = readRelatedFiles(basePath, rootFile);

        return new ErrorContext(
                failedCommand, snippet, rootFile, affectedLine,
                affectedContent, related, rootCauseGroup);
    }

    private static String buildSnippet(String rootFile, List<ParsedError> group) {
        ParsedError first = group.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("[ERROR] ").append(rootFile)
          .append(":[").append(first.line()).append("] ")
          .append(first.message());
        if (group.size() > 1) {
            sb.append("\n  (and ").append(group.size() - 1)
              .append(" more error(s) in the same file)");
        }
        return sb.toString();
    }

    // ── test failure handling ─────────────────────────────────────────────────

    static String extractTestFailureSnippet(String output) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        Matcher m = TEST_FAILURE_CLASS.matcher(output);
        while (m.find() && seen.size() < 5) {
            String cls = m.group(1).trim();
            if (seen.add(cls)) sb.append("[FAIL] ").append(cls).append("\n");
        }
        return sb.toString().trim();
    }

    // ── related file resolution ───────────────────────────────────────────────

    static Map<String, String> readRelatedFiles(String basePath, String affectedFilePath) {
        if (basePath == null || affectedFilePath == null || affectedFilePath.isBlank()) {
            return Map.of();
        }
        String counterpart = findCounterpartPath(affectedFilePath);
        if (counterpart == null) return Map.of();
        String content = readFile(basePath, counterpart);
        if (content.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        result.put(counterpart, cap(content, MAX_FILE_CHARS));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Given a test file path returns the corresponding production file path, and
     * vice versa. Returns {@code null} when no counterpart can be inferred.
     */
    static String findCounterpartPath(String filePath) {
        if (filePath == null) return null;
        String p = normalizeSeparators(filePath);
        if (p.contains("src/test/")) {
            String main = p.replace("src/test/", "src/main/");
            // strip Test / IT / Spec suffix before the file extension
            return main.replaceAll("(Test|IT|Spec)(\\.(?:java|kt))$", "$2");
        }
        if (p.contains("src/main/")) {
            String test = p.replace("src/main/", "src/test/");
            int dot = test.lastIndexOf('.');
            if (dot < 0) return null;
            return test.substring(0, dot) + "Test" + test.substring(dot);
        }
        return null;
    }

    // ── I/O helpers ──────────────────────────────────────────────────────────

    private static String readFile(String basePath, String filePath) {
        if (filePath == null || filePath.isBlank()) return "";

        // 1. try as an absolute path
        try {
            Path abs = Paths.get(filePath);
            if (Files.isRegularFile(abs)) {
                return cap(Files.readString(abs, StandardCharsets.UTF_8), MAX_FILE_CHARS);
            }
        } catch (IOException | java.nio.file.InvalidPathException ignored) {}

        // 2. try relative to basePath
        if (basePath != null && !basePath.isBlank()) {
            try {
                Path rel = Paths.get(basePath, filePath.replace("/", java.io.File.separator));
                if (Files.isRegularFile(rel)) {
                    return cap(Files.readString(rel, StandardCharsets.UTF_8), MAX_FILE_CHARS);
                }
            } catch (IOException | java.nio.file.InvalidPathException ignored) {}
        }
        return "";
    }

    private static String normalizeSeparators(String path) {
        return path.replace('\\', '/');
    }

    private static String firstErrorLine(String output) {
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[ERROR]") && t.length() > 8) {
                return t.length() > 300 ? t.substring(0, 300) : t;
            }
        }
        return output.length() > 300 ? output.substring(0, 300) : output;
    }

    private static String cap(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }

    // ── internal data model ───────────────────────────────────────────────────

    record ParsedError(String filePath, int line, String message) {}
}
