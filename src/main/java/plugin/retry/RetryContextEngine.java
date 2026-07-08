package plugin.retry;

import plugin.util.LanguageSupportUtil;

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
import java.util.stream.Stream;

/**
 * Builds progressively richer project context for auto-fix retries.
 *
 * Each retry attempt expands the context so the LLM never receives the same
 * prompt twice:
 *   level 1 — the source file under test/fix
 *   level 2 — + project classes referenced by its imports
 *   level 3 — + existing test examples and declared build dependencies
 *   level 4 — + a project structure preview
 */
public final class RetryContextEngine {

    public static final int MAX_LEVEL = 4;

    private static final int MAX_FILE_CHARS = 4_000;
    private static final int MAX_RELATED_CLASSES = 4;
    private static final int MAX_TEST_EXAMPLES = 2;
    private static final int MAX_STRUCTURE_CHARS = 2_000;
    private static final int MAX_TREE_ENTRIES = 200;

    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    private RetryContextEngine() {}

    /**
     * @param basePath           project root (may be null)
     * @param attempt            1-based retry attempt; higher attempts include all lower levels
     * @param targetSourcePath   project-relative path of the file under test/fix (may be blank)
     * @param attachedContent    already-loaded content of the target file (may be blank)
     */
    public static String buildRetryContext(String basePath, int attempt,
                                           String targetSourcePath, String attachedContent) {
        int level = Math.min(Math.max(attempt, 1), MAX_LEVEL);
        String header = "── Retry context (expansion level " + level + "/" + MAX_LEVEL + ") ──\n";
        StringBuilder sb = new StringBuilder(header);

        String targetContent = firstNonBlank(attachedContent, readRelative(basePath, targetSourcePath));
        if (!targetContent.isBlank()) {
            sb.append("\n# Source under test/fix");
            if (targetSourcePath != null && !targetSourcePath.isBlank()) {
                sb.append(" (").append(targetSourcePath).append(")");
            }
            sb.append("\n").append(cap(targetContent, MAX_FILE_CHARS)).append("\n");
        }

        if (level >= 2) {
            String related = collectRelatedClasses(basePath, targetSourcePath, targetContent);
            if (!related.isBlank()) {
                sb.append("\n# Related project classes\n").append(related);
            }
        }

        if (level >= 3) {
            String examples = collectTestExamples(basePath, targetSourcePath);
            if (!examples.isBlank()) {
                sb.append("\n# Existing test examples (follow this style)\n").append(examples);
            }
            String deps = collectDependencyInfo(basePath);
            if (!deps.isBlank()) {
                sb.append("\n# Declared build dependencies\n").append(deps).append("\n");
            }
        }

        if (level >= 4) {
            String structure = collectProjectStructure(basePath);
            if (!structure.isBlank()) {
                sb.append("\n# Project structure preview\n").append(structure).append("\n");
            }
        }

        return sb.length() == header.length() ? "" : sb.toString();
    }

    // ── level 2 ──────────────────────────────────────────────────────────────

    static String collectRelatedClasses(String basePath, String targetSourcePath, String targetContent) {
        if (basePath == null || targetContent == null || targetContent.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        int added = 0;
        Matcher m = JAVA_IMPORT.matcher(targetContent);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && added < MAX_RELATED_CLASSES) {
            String fqn = m.group(1);
            String relPath = "src/main/java/" + fqn.replace('.', '/') + ".java";
            if (targetSourcePath != null && relPath.equals(targetSourcePath)) continue;
            if (!seen.add(relPath)) continue;
            String content = readRelative(basePath, relPath);
            if (content.isBlank()) continue;
            sb.append("=== ").append(relPath).append(" ===\n")
              .append(cap(content, MAX_FILE_CHARS / 2)).append("\n");
            added++;
        }
        return sb.toString();
    }

    // ── level 3 ──────────────────────────────────────────────────────────────

    static String collectTestExamples(String basePath, String targetSourcePath) {
        if (basePath == null) return "";
        Path testRoot = Paths.get(basePath, "src", "test");
        if (!Files.isDirectory(testRoot)) return "";
        String targetTestPath = targetSourcePath == null ? "" : LanguageSupportUtil.suggestedTestPath(targetSourcePath);
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(testRoot)) {
            List<Path> tests = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> LanguageSupportUtil.isTestFile(p.toString().replace("\\", "/")))
                    .limit(50)
                    .toList();
            int added = 0;
            for (Path test : tests) {
                if (added >= MAX_TEST_EXAMPLES) break;
                String rel = Paths.get(basePath).relativize(test).toString().replace("\\", "/");
                if (rel.equals(targetTestPath)) continue;
                String content = readFile(test);
                if (content.isBlank()) continue;
                sb.append("=== ").append(rel).append(" ===\n")
                  .append(cap(content, MAX_FILE_CHARS / 2)).append("\n");
                added++;
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    static String collectDependencyInfo(String basePath) {
        if (basePath == null) return "";
        String pom = readRelative(basePath, "pom.xml");
        if (!pom.isBlank()) {
            int start = pom.indexOf("<dependencies>");
            int end = pom.indexOf("</dependencies>");
            if (start >= 0 && end > start) {
                return cap(pom.substring(start, end + "</dependencies>".length()), MAX_FILE_CHARS / 2);
            }
            return "";
        }
        for (String buildFile : List.of("build.gradle", "build.gradle.kts", "package.json",
                "go.mod", "Cargo.toml", "requirements.txt", "pyproject.toml")) {
            String content = readRelative(basePath, buildFile);
            if (!content.isBlank()) {
                return buildFile + ":\n" + cap(content, MAX_FILE_CHARS / 2);
            }
        }
        return "";
    }

    // ── level 4 ──────────────────────────────────────────────────────────────

    static String collectProjectStructure(String basePath) {
        if (basePath == null) return "";
        Path root = Paths.get(basePath);
        if (!Files.isDirectory(root)) return "";
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root, 6)) {
            walk.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace("\\", "/"))
                .filter(RetryContextEngine::isProjectFile)
                .limit(MAX_TREE_ENTRIES)
                .forEach(p -> sb.append(p).append("\n"));
        } catch (IOException ignored) {}
        return cap(sb.toString(), MAX_STRUCTURE_CHARS);
    }

    private static boolean isProjectFile(String relPath) {
        return !relPath.startsWith(".git/")
                && !relPath.startsWith(".idea/")
                && !relPath.startsWith("target/")
                && !relPath.startsWith("build/")
                && !relPath.startsWith("out/")
                && !relPath.startsWith("node_modules/")
                && !relPath.startsWith("dist/")
                && !relPath.startsWith(".gradle/");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String readRelative(String basePath, String relPath) {
        if (basePath == null || relPath == null || relPath.isBlank()) return "";
        return readFile(Paths.get(basePath, relPath.replace("/", java.io.File.separator)));
    }

    private static String readFile(Path path) {
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {}
        return "";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null ? "" : b;
    }

    private static String cap(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }
}
