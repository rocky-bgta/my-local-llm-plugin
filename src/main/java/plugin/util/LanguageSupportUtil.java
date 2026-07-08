package plugin.util;

import com.intellij.openapi.project.Project;
import plugin.psi.SourceFileScanner;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class LanguageSupportUtil {

    public enum Language {
        JAVA,
        KOTLIN,
        GO,
        PYTHON,
        JAVASCRIPT,
        TYPESCRIPT,
        SCALA,
        RUST,
        PHP,
        RUBY,
        CSHARP,
        UNKNOWN
    }

    private LanguageSupportUtil() {}

    public static Language detectLanguage(String path) {
        if (path == null) return Language.UNKNOWN;
        String normalized = path.replace("\\", "/").toLowerCase(Locale.ROOT);

        if (normalized.endsWith(".java")) return Language.JAVA;
        if (normalized.endsWith(".kt") || normalized.endsWith(".kts")) return Language.KOTLIN;
        if (normalized.endsWith(".go")) return Language.GO;
        if (normalized.endsWith(".py")) return Language.PYTHON;
        if (normalized.endsWith(".js") || normalized.endsWith(".mjs") || normalized.endsWith(".cjs")) return Language.JAVASCRIPT;
        if (normalized.endsWith(".ts") || normalized.endsWith(".mts") || normalized.endsWith(".cts")) return Language.TYPESCRIPT;
        if (normalized.endsWith(".scala")) return Language.SCALA;
        if (normalized.endsWith(".rs")) return Language.RUST;
        if (normalized.endsWith(".php")) return Language.PHP;
        if (normalized.endsWith(".rb")) return Language.RUBY;
        if (normalized.endsWith(".cs")) return Language.CSHARP;
        return Language.UNKNOWN;
    }

    public static boolean isSourceFile(String path) {
        Language language = detectLanguage(path);
        return language != Language.UNKNOWN && !isGeneratedOrBinary(path);
    }

    public static boolean isTestFile(String path) {
        if (path == null) return false;
        String normalized = path.replace("\\", "/").toLowerCase(Locale.ROOT);
        return normalized.contains("/test/")
                || normalized.contains("tests/")
                || normalized.endsWith("_test.go")
                || normalized.contains("test_")
                || normalized.endsWith(".test.js")
                || normalized.endsWith(".test.jsx")
                || normalized.endsWith(".test.ts")
                || normalized.endsWith(".test.tsx")
                || normalized.endsWith(".spec.js")
                || normalized.endsWith(".spec.jsx")
                || normalized.endsWith(".spec.ts")
                || normalized.endsWith(".spec.tsx");
    }

    public static boolean isJvmLanguage(Language language) {
        return language == Language.JAVA || language == Language.KOTLIN || language == Language.SCALA;
    }

    public static String suggestedTestPath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) return sourcePath;

        String normalized = sourcePath.replace("\\", "/");
        Language language = detectLanguage(normalized);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);

        switch (language) {
            case JAVA -> {
                if (fileName.endsWith("Test.java") || fileName.endsWith("Tests.java")) return normalized;
                // Root module: src/main/java/...
                if (normalized.startsWith("src/main/java/")) {
                    return "src/test/java/" + normalized.substring("src/main/java/".length(), normalized.length() - 5) + "Test.java";
                }
                if (normalized.startsWith("src/test/java/")) return normalized;
                // Multi-module: <module>/src/main/java/...
                int idx = normalized.indexOf("/src/main/java/");
                if (idx >= 0) {
                    String prefix = normalized.substring(0, idx);
                    String classRel = normalized.substring(idx + "/src/main/java/".length(), normalized.length() - 5);
                    return prefix + "/src/test/java/" + classRel + "Test.java";
                }
                return normalized.substring(0, normalized.length() - 5) + "Test.java";
            }
            case KOTLIN -> {
                if (fileName.endsWith("Test.kt")) return normalized;
                if (normalized.startsWith("src/main/kotlin/")) {
                    return "src/test/kotlin/" + normalized.substring("src/main/kotlin/".length(), normalized.length() - 3) + "Test.kt";
                }
                if (normalized.startsWith("src/test/kotlin/")) return normalized;
                int idx = normalized.indexOf("/src/main/kotlin/");
                if (idx >= 0) {
                    String prefix = normalized.substring(0, idx);
                    String classRel = normalized.substring(idx + "/src/main/kotlin/".length(), normalized.length() - 3);
                    return prefix + "/src/test/kotlin/" + classRel + "Test.kt";
                }
                return normalized.substring(0, normalized.length() - 3) + "Test.kt";
            }
            case SCALA -> {
                if (fileName.endsWith("Test.scala")) return normalized;
                if (normalized.startsWith("src/main/scala/")) {
                    return "src/test/scala/" + normalized.substring("src/main/scala/".length(), normalized.length() - 6) + "Test.scala";
                }
                if (normalized.startsWith("src/test/scala/")) return normalized;
                int idx = normalized.indexOf("/src/main/scala/");
                if (idx >= 0) {
                    String prefix = normalized.substring(0, idx);
                    String classRel = normalized.substring(idx + "/src/main/scala/".length(), normalized.length() - 6);
                    return prefix + "/src/test/scala/" + classRel + "Test.scala";
                }
                return normalized.substring(0, normalized.length() - 6) + "Test.scala";
            }
            case GO -> {
                if (fileName.endsWith(".go") && !fileName.endsWith("_test.go")) {
                    return normalized.substring(0, normalized.length() - 3) + "_test.go";
                }
                return normalized.endsWith("_test.go") ? normalized : normalized;
            }
            case PYTHON -> {
                if (fileName.startsWith("test_") || fileName.endsWith("_test.py")) return normalized;
                return "tests/test_" + fileName;
            }
            case JAVASCRIPT, TYPESCRIPT -> {
                if (fileName.endsWith(".test.js") || fileName.endsWith(".spec.js")
                        || fileName.endsWith(".test.jsx") || fileName.endsWith(".spec.jsx")
                        || fileName.endsWith(".test.ts") || fileName.endsWith(".spec.ts")
                        || fileName.endsWith(".test.tsx") || fileName.endsWith(".spec.tsx")) {
                    return normalized;
                }
                String ext = normalized.substring(normalized.lastIndexOf('.'));
                return normalized.substring(0, normalized.length() - ext.length()) + ".test" + ext;
            }
            case RUBY -> {
                if (fileName.startsWith("test_")) return normalized;
                return "test/" + fileName.replace(".rb", "_test.rb");
            }
            case PHP -> {
                if (fileName.endsWith("Test.php")) return normalized;
                return normalized.substring(0, normalized.length() - 4) + "Test.php";
            }
            default -> {
                return normalized;
            }
        }
    }

    public static String frameworkHint(Language language) {
        return switch (language) {
            case JAVA, KOTLIN, SCALA -> "JUnit 5 / Kotest";
            case GO -> "Go testing package";
            case PYTHON -> "pytest / unittest";
            case JAVASCRIPT, TYPESCRIPT -> "Jest / Vitest / Mocha";
            case RUST -> "cargo test";
            case PHP -> "PHPUnit";
            case RUBY -> "RSpec / Minitest";
            case CSHARP -> "xUnit / NUnit / MSTest";
            default -> "the project's native test framework";
        };
    }

    public static Language detectPrimaryLanguage(Project project) {
        if (project == null) return Language.UNKNOWN;

        Map<Language, Integer> counts = new EnumMap<>(Language.class);
        for (var file : SourceFileScanner.scanAllSourceFiles(project)) {
            Language language = detectLanguage(file.getPath());
            if (language == Language.UNKNOWN) continue;
            counts.merge(language, 1, Integer::sum);
        }

        Language best = Language.UNKNOWN;
        int bestCount = 0;
        for (Map.Entry<Language, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static boolean isGeneratedOrBinary(String path) {
        String normalized = path.replace("\\", "/").toLowerCase(Locale.ROOT);
        return normalized.contains("/target/")
                || normalized.contains("/out/")
                || normalized.contains("/build/")
                || normalized.contains("/dist/")
                || normalized.contains("/node_modules/");
    }
}
