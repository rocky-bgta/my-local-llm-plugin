package plugin.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministically repairs "cannot find symbol: class X" compile errors in
 * generated Java files by inserting the missing import — local LLMs forget
 * imports constantly, and resolving them plugin-side is cheaper and more
 * reliable than spending an LLM fix attempt.
 */
public final class ImportFixer {

    // [ERROR] /C:/repo/src/test/java/pkg/FooTest.java:[15,60] cannot find symbol
    private static final Pattern ERROR_FILE = Pattern.compile(
            "\\[ERROR\\]\\s+/?([^\\s\\[\\]]+\\.java):\\[\\d+");
    private static final Pattern MISSING_SYMBOL = Pattern.compile(
            "symbol:\\s+(?:class|interface|variable)\\s+(\\w+)");
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** Common JDK / JUnit types that cannot be found by scanning project sources. */
    private static final Map<String, String> WELL_KNOWN = buildWellKnown();

    private static Map<String, String> buildWellKnown() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String cls : List.of("List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap",
                "Set", "HashSet", "LinkedHashSet", "TreeMap", "TreeSet", "Arrays", "Collections",
                "Optional", "Iterator", "Objects", "Locale", "UUID", "Random", "Comparator")) {
            m.put(cls, "java.util." + cls);
        }
        m.put("Collectors", "java.util.stream.Collectors");
        m.put("Stream", "java.util.stream.Stream");
        m.put("IOException", "java.io.IOException");
        m.put("Path", "java.nio.file.Path");
        m.put("Paths", "java.nio.file.Paths");
        m.put("Files", "java.nio.file.Files");
        m.put("StandardCharsets", "java.nio.charset.StandardCharsets");
        m.put("Duration", "java.time.Duration");
        m.put("Instant", "java.time.Instant");
        m.put("LocalDate", "java.time.LocalDate");
        m.put("LocalDateTime", "java.time.LocalDateTime");
        m.put("BigDecimal", "java.math.BigDecimal");
        m.put("Test", "org.junit.jupiter.api.Test");
        m.put("BeforeEach", "org.junit.jupiter.api.BeforeEach");
        m.put("AfterEach", "org.junit.jupiter.api.AfterEach");
        m.put("DisplayName", "org.junit.jupiter.api.DisplayName");
        m.put("TempDir", "org.junit.jupiter.api.io.TempDir");
        m.put("Mock", "org.mockito.Mock");
        m.put("Mockito", "org.mockito.Mockito");
        return m;
    }

    private ImportFixer() {}

    /**
     * Parses missing-symbol compile errors and inserts resolvable imports into
     * the affected files.
     *
     * @return human-readable summary of inserted imports, or "" when nothing
     *         could be fixed deterministically.
     */
    public static String attemptAutoFix(String basePath, String buildOutput) {
        if (basePath == null || buildOutput == null || buildOutput.isBlank()) return "";

        Map<String, Set<String>> symbolsByFile = missingSymbolsByFile(basePath, buildOutput);
        if (symbolsByFile.isEmpty()) return "";

        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : symbolsByFile.entrySet()) {
            Path file = Paths.get(entry.getKey());
            if (!Files.isRegularFile(file)) continue;
            List<String> imports = new ArrayList<>();
            for (String symbol : entry.getValue()) {
                String fqn = resolve(basePath, symbol);
                if (fqn != null) imports.add(fqn);
            }
            String change = insertImports(file, imports);
            if (!change.isBlank()) changes.add(change);
        }
        if (changes.isEmpty()) return "";
        return "Auto-fixed missing imports:\n- " + String.join("\n- ", changes);
    }

    static Map<String, Set<String>> missingSymbolsByFile(String basePath, String buildOutput) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        String currentFile = null;
        for (String line : buildOutput.split("\\R")) {
            Matcher f = ERROR_FILE.matcher(line);
            if (f.find() && line.contains("cannot find symbol")) {
                currentFile = normalizeErrorPath(f.group(1));
                continue;
            }
            Matcher s = MISSING_SYMBOL.matcher(line);
            if (s.find() && currentFile != null) {
                result.computeIfAbsent(currentFile, k -> new LinkedHashSet<>()).add(s.group(1));
            }
        }
        return result;
    }

    private static String normalizeErrorPath(String rawPath) {
        // Maven prints /C:/repo/... on Windows
        String path = rawPath.replace("\\", "/");
        if (path.matches("^/[A-Za-z]:/.*")) path = path.substring(1);
        return path;
    }

    /** Resolves a simple class name to a fully qualified name, or null if unknown/ambiguous. */
    static String resolve(String basePath, String symbol) {
        List<String> projectMatches = findProjectClasses(basePath, symbol);
        if (projectMatches.size() == 1) return projectMatches.get(0);
        if (projectMatches.size() > 1) return null;
        return WELL_KNOWN.get(symbol);
    }

    private static List<String> findProjectClasses(String basePath, String symbol) {
        List<String> matches = new ArrayList<>();
        for (String sourceRoot : List.of("src/main/java", "src/test/java")) {
            Path root = Paths.get(basePath, sourceRoot.replace("/", java.io.File.separator));
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(symbol + ".java"))
                    .forEach(p -> {
                        String fqn = fqnOf(p);
                        if (fqn != null && !matches.contains(fqn)) matches.add(fqn);
                    });
            } catch (IOException ignored) {}
        }
        return matches;
    }

    private static String fqnOf(Path javaFile) {
        try {
            String head = Files.readString(javaFile, StandardCharsets.UTF_8);
            Matcher m = PACKAGE_DECL.matcher(head);
            String className = javaFile.getFileName().toString().replace(".java", "");
            return m.find() ? m.group(1) + "." + className : className;
        } catch (IOException e) {
            return null;
        }
    }

    static String insertImports(Path file, List<String> fqns) {
        if (fqns.isEmpty()) return "";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String filePackage = "";
            Matcher pkg = PACKAGE_DECL.matcher(content);
            int insertAt = 0;
            if (pkg.find()) {
                filePackage = pkg.group(1);
                insertAt = content.indexOf('\n', pkg.end()) + 1;
                if (insertAt == 0) insertAt = content.length();
            }
            StringBuilder block = new StringBuilder();
            List<String> added = new ArrayList<>();
            for (String fqn : fqns) {
                String importPackage = fqn.substring(0, Math.max(fqn.lastIndexOf('.'), 0));
                if (importPackage.equals(filePackage)) continue;      // same package — no import needed
                if (content.contains("import " + fqn + ";")) continue;
                block.append("import ").append(fqn).append(";\n");
                added.add(fqn);
            }
            if (added.isEmpty()) return "";
            String updated = content.substring(0, insertAt) + "\n" + block + content.substring(insertAt);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return file.getFileName() + ": " + String.join(", ", added);
        } catch (IOException e) {
            return "";
        }
    }
}
