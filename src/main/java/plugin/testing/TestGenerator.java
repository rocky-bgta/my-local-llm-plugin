package plugin.testing;

import plugin.agent.AgentContext;
import plugin.agent.AgentTask;
import plugin.psi.ClassFinder;
import plugin.psi.MethodFinder;
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

public class TestGenerator {

    public String buildTestPrompt(AgentContext ctx, String targetClass) {
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectPrimaryLanguage(ctx.getProject());
        List<MethodFinder.MethodInfo> methods = List.of();
        List<ClassFinder.ClassInfo> related = List.of();
        String inferredTestPath = inferTestPath(targetClass);

        if (LanguageSupportUtil.isJvmLanguage(language)) {
            ClassFinder classFinder = new ClassFinder(ctx.getProject());
            MethodFinder methodFinder = new MethodFinder(ctx.getProject());
            methods = methodFinder.findPublicMethods(targetClass);
            related = classFinder.findRelatedClasses(targetClass);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate comprehensive ").append(LanguageSupportUtil.frameworkHint(language))
                .append(" tests for `").append(targetClass).append("`.\n\n");

        if (!methods.isEmpty()) {
            prompt.append("Public methods to test:\n");
            methods.forEach(m -> prompt.append("- `").append(m.methodName())
                    .append("(").append(m.parameters()).append(")`\n"));
            prompt.append("\n");
        }

        if (!related.isEmpty()) {
            prompt.append("Related classes (may need mocking):\n");
            related.stream().limit(5).forEach(c -> prompt.append("- ").append(c.className()).append("\n"));
            prompt.append("\n");
        }

        prompt.append("Requirements:\n")
                .append("- Test ONLY public methods and constructors — never call private methods, private constants, or private nested types\n")
                .append("- Include EVERY import the test needs (test framework and all java.util classes used)\n")
                .append("- Use the project's native test framework\n")
                .append("- Follow the language's usual testing style and conventions\n")
                .append("- Use the AAA pattern in each test: Arrange, Act, Assert\n")
                .append("- Test happy path, edge cases, and error conditions\n")
                .append("- Place the file at ").append(inferredTestPath).append("\n")
                .append("- Use one complete XML tag only: <CREATE_FILE path=\"")
                .append(inferredTestPath)
                .append("\">...full content...</CREATE_FILE>\n");

        return prompt.toString();
    }

    /**
     * Builds a comprehensive test-generation prompt for a file dragged into the chat.
     *
     * Collects — without any PSI dependency — all context items mandated by the
     * PRE-TEST CONTEXT RULE: build descriptor, existing test conventions, source
     * imports, constructor dependencies, language, framework, and commands.
     *
     * Returns "" when the source file cannot be read.
     */
    public String buildFileDropTestPrompt(Path sourceFile, String basePath) {
        if (sourceFile == null || !Files.exists(sourceFile)) return "";
        String sourceContent;
        try {
            sourceContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }

        String relSourcePath = toRelativePath(sourceFile, basePath);
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectLanguage(relSourcePath);
        String testPath = LanguageSupportUtil.suggestedTestPath(relSourcePath);

        StringBuilder sb = new StringBuilder();
        sb.append("## Mandatory Pre-Test Context\n\n");
        sb.append("**Target file:** `").append(relSourcePath).append("`\n");
        sb.append("**Detected language:** ").append(language.name()).append("\n");
        sb.append("**Test framework hint:** ").append(LanguageSupportUtil.frameworkHint(language)).append("\n");
        sb.append("**Expected test path:** `").append(testPath).append("`\n\n");

        // 1. Maven module detection (which sub-module owns this file)
        String moduleName = detectMavenModuleName(sourceFile, basePath);
        if (!moduleName.isBlank()) {
            sb.append("**Maven module:** `").append(moduleName).append("`\n\n");
        }

        // 2. Imports from source (shows dependencies)
        List<String> importLines = extractImportLines(sourceContent, language);
        if (!importLines.isEmpty()) {
            sb.append("**Source imports (dependencies visible here):**\n");
            importLines.forEach(l -> sb.append("  ").append(l).append("\n"));
            sb.append("\n");
        }

        // 3. Content of project-local dependencies (models, DTOs, interfaces, utils)
        String relatedSources = collectRelatedSourceContents(importLines, basePath, 3);
        if (!relatedSources.isBlank()) {
            sb.append("**Project-local dependencies (mock or use these APIs):**\n");
            sb.append(relatedSources);
        }

        // 4. Constructor / initializer dependencies
        List<String> ctorDeps = extractConstructorDeps(sourceContent, language);
        if (!ctorDeps.isEmpty()) {
            sb.append("**Constructor parameters (likely need mocking):**\n");
            ctorDeps.forEach(d -> sb.append("  ").append(d).append("\n"));
            sb.append("\n");
        }

        // 5. Build descriptor (module pom.xml first, then root)
        String moduleBuildPath = moduleName.isBlank()
                ? basePath
                : Paths.get(basePath, moduleName.replace("/", java.io.File.separator)).toString();
        String buildContent = findBuildFileContent(moduleBuildPath, language);
        if (buildContent.isBlank()) buildContent = findBuildFileContent(basePath, language);
        if (!buildContent.isBlank()) {
            sb.append("**Build descriptor:**\n```\n").append(buildContent).append("\n```\n\n");
        }

        // 7. Existing test convention sample
        String existingTest = findExistingTestSample(basePath, relSourcePath, language);
        if (!existingTest.isBlank()) {
            sb.append("**Existing test convention (follow this style):**\n```\n")
              .append(existingTest).append("\n```\n\n");
        }

        // 8. Compile and test commands
        sb.append("**Commands:**\n");
        sb.append("  Compile: `").append(detectCompileCommand(basePath, language)).append("`\n");
        sb.append("  Test: `").append(detectTestCommand(basePath, language)).append("`\n\n");

        sb.append("**Generation rules:**\n");
        sb.append("- Include EVERY import the test file needs (all annotations, assertions, mocks)\n");
        sb.append("- Test ONLY public methods and constructors — never access private members\n");
        sb.append("- Use AAA pattern in every test: Arrange, Act, Assert\n");
        sb.append("- Mock all external dependencies with the library shown in the build descriptor\n");
        sb.append("- Follow the conventions shown in the existing test sample above\n");

        return sb.toString();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static String toRelativePath(Path file, String basePath) {
        if (basePath == null || basePath.isBlank()) return file.toString().replace("\\", "/");
        try {
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            Path abs  = file.toAbsolutePath().normalize();
            if (abs.startsWith(base)) {
                return base.relativize(abs).toString().replace("\\", "/");
            }
        } catch (Exception ignored) {}
        return file.toString().replace("\\", "/");
    }

    private static List<String> extractImportLines(String content, LanguageSupportUtil.Language lang) {
        List<String> lines = new ArrayList<>();
        if (content == null) return lines;
        String keyword = switch (lang) {
            case PYTHON -> "^from |^import ";
            case GO     -> "import";
            case JAVASCRIPT, TYPESCRIPT -> "^import |^require\\(";
            default -> "^import ";
        };
        Pattern p = Pattern.compile(keyword + ".*", Pattern.MULTILINE);
        Matcher m = p.matcher(content);
        while (m.find() && lines.size() < 30) {
            String l = m.group().strip();
            if (!l.isBlank()) lines.add(l);
        }
        return lines;
    }

    private static List<String> extractConstructorDeps(String content, LanguageSupportUtil.Language lang) {
        List<String> deps = new ArrayList<>();
        if (content == null) return deps;
        Pattern ctorPattern = switch (lang) {
            case JAVA, KOTLIN, SCALA ->
                Pattern.compile("(?:public|protected)\\s+\\w+\\s*\\(([^)]{1,400})\\)");
            case GO ->
                Pattern.compile("func\\s+New\\w*\\s*\\(([^)]{1,400})\\)");
            case PYTHON ->
                Pattern.compile("def\\s+__init__\\s*\\(self(?:,\\s*([^)]{1,400}))?\\)");
            default ->
                Pattern.compile("constructor\\s*\\(([^)]{1,400})\\)");
        };
        Matcher m = ctorPattern.matcher(content);
        if (m.find()) {
            String params = m.group(1);
            if (params != null && !params.isBlank()) {
                for (String p : params.split(",")) {
                    String dep = p.strip();
                    if (!dep.isBlank()) deps.add(dep);
                }
            }
        }
        return deps;
    }

    private static String findBuildFileContent(String basePath, LanguageSupportUtil.Language lang) {
        if (basePath == null || basePath.isBlank()) return "";
        List<String> candidates = switch (lang) {
            case JAVA, KOTLIN, SCALA -> List.of("pom.xml", "build.gradle", "build.gradle.kts");
            case GO                  -> List.of("go.mod");
            case PYTHON              -> List.of("pyproject.toml", "setup.py", "requirements.txt");
            case JAVASCRIPT, TYPESCRIPT -> List.of("package.json");
            case RUST                -> List.of("Cargo.toml");
            case PHP                 -> List.of("composer.json");
            case RUBY                -> List.of("Gemfile");
            default                  -> List.of("pom.xml", "build.gradle", "package.json", "go.mod");
        };
        for (String name : candidates) {
            Path p = Paths.get(basePath, name);
            if (Files.isRegularFile(p)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    return content.length() > 3000 ? content.substring(0, 3000) + "\n[...truncated]" : content;
                } catch (IOException ignored) {}
            }
        }
        return "";
    }

    private static String findExistingTestSample(String basePath, String relSourcePath,
                                                  LanguageSupportUtil.Language lang) {
        if (basePath == null || basePath.isBlank() || relSourcePath == null) return "";
        String testDir = switch (lang) {
            case JAVA, KOTLIN, SCALA -> "src/test/java";
            case GO                  -> extractGoPackageDir(basePath, relSourcePath);
            case PYTHON              -> "tests";
            default                  -> "src";
        };
        Path testRoot = Paths.get(basePath, testDir.replace("/", java.io.File.separator));
        if (!Files.isDirectory(testRoot)) return "";
        try (Stream<Path> walk = Files.walk(testRoot)) {
            return walk.filter(Files::isRegularFile)
                       .filter(p -> LanguageSupportUtil.isTestFile(p.toString()))
                       .filter(p -> !p.getFileName().toString().contains("Abstract"))
                       .findFirst()
                       .map(p -> {
                           try {
                               String c = Files.readString(p, StandardCharsets.UTF_8);
                               return c.length() > 1500 ? c.substring(0, 1500) + "\n[...truncated]" : c;
                           } catch (IOException e) { return ""; }
                       })
                       .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private static String extractGoPackageDir(String basePath, String relSourcePath) {
        String dir = relSourcePath.contains("/")
                ? relSourcePath.substring(0, relSourcePath.lastIndexOf('/'))
                : "";
        return dir;
    }

    private static String detectCompileCommand(String basePath, LanguageSupportUtil.Language lang) {
        if (basePath != null) {
            if (Files.isRegularFile(Paths.get(basePath, "pom.xml")))          return "mvn compile";
            if (Files.isRegularFile(Paths.get(basePath, "build.gradle")))     return "gradle classes";
            if (Files.isRegularFile(Paths.get(basePath, "build.gradle.kts"))) return "gradle classes";
        }
        return switch (lang) {
            case GO         -> "go build ./...";
            case PYTHON     -> "python -m py_compile";
            case JAVASCRIPT, TYPESCRIPT -> "npm run build";
            case RUST       -> "cargo build";
            default         -> "mvn compile";
        };
    }

    /**
     * Walks up from the source file toward the project root, returning the first
     * sub-directory that contains its own {@code pom.xml}. Returns "" when the
     * file is in the root module or no sub-module is detected.
     */
    private static String detectMavenModuleName(Path sourceFile, String basePath) {
        if (sourceFile == null || basePath == null || basePath.isBlank()) return "";
        try {
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            Path current = sourceFile.toAbsolutePath().normalize().getParent();
            while (current != null && !current.equals(base)) {
                if (!current.startsWith(base)) break;
                if (Files.isRegularFile(current.resolve("pom.xml"))) {
                    return base.relativize(current).toString().replace("\\", "/");
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Reads the source content of up to {@code maxFiles} project-local classes
     * referenced in the import lines. Skips JDK, JUnit, Mockito, and IntelliJ
     * SDK classes — only classes found under {@code src/main/java} matter here.
     */
    private static String collectRelatedSourceContents(List<String> importLines,
                                                       String basePath,
                                                       int maxFiles) {
        if (importLines == null || importLines.isEmpty() || basePath == null) return "";
        Path srcMain = Paths.get(basePath, "src", "main", "java");
        if (!Files.isDirectory(srcMain)) return "";

        Pattern fqcnPat = Pattern.compile(
                "import\\s+(?:static\\s+)?([\\w]+(?:\\.[\\w]+)*)\\s*;");
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (String line : importLines) {
            if (count >= maxFiles) break;
            Matcher m = fqcnPat.matcher(line);
            if (!m.find()) continue;
            String fqcn = m.group(1);
            if (fqcn.startsWith("java.") || fqcn.startsWith("javax.")
                    || fqcn.startsWith("org.junit") || fqcn.startsWith("org.mockito")
                    || fqcn.startsWith("com.intellij") || fqcn.startsWith("org.jetbrains")) {
                continue;
            }
            String[] parts = fqcn.split("\\.");
            String className = parts[parts.length - 1];
            if ("*".equals(className) || seen.contains(className)) continue;
            seen.add(className);

            try (Stream<Path> walk = Files.walk(srcMain)) {
                java.util.Optional<Path> found = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(className + ".java"))
                        .findFirst();
                if (found.isPresent()) {
                    String content = Files.readString(found.get(), StandardCharsets.UTF_8);
                    if (content.length() > 1200) {
                        content = content.substring(0, 1200) + "\n[...truncated]";
                    }
                    sb.append("**`").append(className).append("`:**\n```java\n")
                      .append(content).append("\n```\n\n");
                    count++;
                }
            } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private static String detectTestCommand(String basePath, LanguageSupportUtil.Language lang) {
        if (basePath != null) {
            if (Files.isRegularFile(Paths.get(basePath, "pom.xml")))          return "mvn test";
            if (Files.isRegularFile(Paths.get(basePath, "build.gradle")))     return "gradle test";
            if (Files.isRegularFile(Paths.get(basePath, "build.gradle.kts"))) return "gradle test";
        }
        return switch (lang) {
            case GO         -> "go test ./...";
            case PYTHON     -> "pytest";
            case JAVASCRIPT, TYPESCRIPT -> "npm test";
            case RUST       -> "cargo test";
            default         -> "mvn test";
        };
    }

    public String inferTestClassName(String targetClass) {
        return targetClass + "Test";
    }

    public String inferTestPath(String targetClass) {
        return LanguageSupportUtil.suggestedTestPath("src/main/java/" + targetClass.replace('.', '/') + ".java");
    }
}
