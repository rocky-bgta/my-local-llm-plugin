package plugin.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static pre-build checks for LLM-generated Java tests. Catches the common 7B
 * failure modes (calling private members, dropping imports) before the Maven
 * build runs, so the correction prompt can name the exact violation instead of
 * relaying raw compiler output the model struggles to act on.
 */
public final class TestContentValidator {

    // Char class covers modifiers, generics, and the return type; the capture is
    // the declared name right before '(', '=' or ';'.
    private static final Pattern PRIVATE_MEMBER = Pattern.compile(
            "private\\s+[\\w<>\\[\\],.?\\s]*?(\\w+)\\s*[(=;]");
    private static final Pattern PUBLIC_MEMBER = Pattern.compile(
            "public\\s+[\\w<>\\[\\],.?\\s]*?(\\w+)\\s*[(=;]");

    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:class|record|interface|enum)\\s+(\\w+)");

    private static final Map<String, String> COMMON_TYPE_IMPORTS = new LinkedHashMap<>();

    static {
        for (String type : List.of("List", "ArrayList", "LinkedList", "Arrays", "Map", "HashMap",
                "LinkedHashMap", "Set", "HashSet", "LinkedHashSet", "Optional", "Collections", "Comparator")) {
            COMMON_TYPE_IMPORTS.put(type, "java.util." + type);
        }
        for (String type : List.of("Collectors", "Stream", "IntStream")) {
            COMMON_TYPE_IMPORTS.put(type, "java.util.stream." + type);
        }
        // JUnit 5 — common annotations and types LLMs frequently omit
        COMMON_TYPE_IMPORTS.put("Test", "org.junit.jupiter.api.Test");
        COMMON_TYPE_IMPORTS.put("BeforeEach", "org.junit.jupiter.api.BeforeEach");
        COMMON_TYPE_IMPORTS.put("AfterEach", "org.junit.jupiter.api.AfterEach");
        COMMON_TYPE_IMPORTS.put("BeforeAll", "org.junit.jupiter.api.BeforeAll");
        COMMON_TYPE_IMPORTS.put("AfterAll", "org.junit.jupiter.api.AfterAll");
        COMMON_TYPE_IMPORTS.put("DisplayName", "org.junit.jupiter.api.DisplayName");
        COMMON_TYPE_IMPORTS.put("Nested", "org.junit.jupiter.api.Nested");
        COMMON_TYPE_IMPORTS.put("TempDir", "org.junit.jupiter.api.io.TempDir");
        COMMON_TYPE_IMPORTS.put("ExtendWith", "org.junit.jupiter.api.extension.ExtendWith");
        COMMON_TYPE_IMPORTS.put("ParameterizedTest", "org.junit.jupiter.params.ParameterizedTest");
        // Mockito — class-level annotations
        COMMON_TYPE_IMPORTS.put("Mock", "org.mockito.Mock");
        COMMON_TYPE_IMPORTS.put("InjectMocks", "org.mockito.InjectMocks");
        COMMON_TYPE_IMPORTS.put("Captor", "org.mockito.Captor");
        COMMON_TYPE_IMPORTS.put("Spy", "org.mockito.Spy");
        COMMON_TYPE_IMPORTS.put("MockitoExtension", "org.mockito.junit.jupiter.MockitoExtension");
        COMMON_TYPE_IMPORTS.put("ArgumentCaptor", "org.mockito.ArgumentCaptor");
    }

    private TestContentValidator() {
    }

    /**
     * Returns human-readable violations found in the generated test, or an
     * empty list when the test looks compilable with respect to these checks.
     */
    public static List<String> findViolations(String sourceContent, String testContent) {
        List<String> violations = new ArrayList<>();
        if (testContent == null || testContent.isBlank()) return violations;

        if (sourceContent != null && !sourceContent.isBlank()) {
            String className = classNameOf(sourceContent);
            Set<String> publicNames = memberNames(sourceContent, PUBLIC_MEMBER);
            // Members the test declares itself (e.g. a copied helper method) are
            // resolved against the test class, not the class under test.
            Set<String> testDeclared = new LinkedHashSet<>(memberNames(testContent, PRIVATE_MEMBER));
            testDeclared.addAll(memberNames(testContent, PUBLIC_MEMBER));
            for (String name : memberNames(sourceContent, PRIVATE_MEMBER)) {
                if (publicNames.contains(name) || testDeclared.contains(name)) continue;
                boolean referenced = isConstantName(name)
                        ? containsWord(testContent, name)
                        : containsCall(testContent, name);
                if (referenced) {
                    violations.add("The test references `" + name
                            + "`, which is PRIVATE in `" + className + "` — it cannot compile. "
                            + "Test the behavior through the public method of `" + className
                            + "` that internally uses `" + name + "` instead.");
                }
            }
        }

        for (String fqn : findMissingImports(testContent)) {
            String type = fqn.substring(fqn.lastIndexOf('.') + 1);
            violations.add("The test uses `" + type
                    + "` but is missing `import " + fqn + ";`.");
        }
        return violations;
    }

    /**
     * Returns the fully qualified names of common java.util / java.util.stream
     * types the test uses without importing. These are deterministically
     * fixable (see ImportFixer) — no LLM retry needed.
     */
    public static List<String> findMissingImports(String testContent) {
        List<String> missing = new ArrayList<>();
        if (testContent == null || testContent.isBlank()) return missing;
        for (Map.Entry<String, String> entry : COMMON_TYPE_IMPORTS.entrySet()) {
            String type = entry.getKey();
            String fqn = entry.getValue();
            String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
            // Match both generic/constructor/static-member usage (List<, List(, List.)
            // and annotation usage (@Test, @Mock, @ExtendWith, etc.)
            boolean used = Pattern.compile("\\b" + type + "\\s*[<(.]").matcher(testContent).find()
                    || Pattern.compile("@" + type + "\\b").matcher(testContent).find();
            if (used
                    && !testContent.contains("import " + fqn + ";")
                    && !testContent.contains("import " + pkg + ".*;")) {
                missing.add(fqn);
            }
        }
        return missing;
    }

    private static String classNameOf(String sourceContent) {
        Matcher m = CLASS_NAME.matcher(sourceContent);
        return m.find() ? m.group(1) : "the source class";
    }

    private static Set<String> memberNames(String source, Pattern declaration) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = declaration.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (name.length() > 2) names.add(name);
        }
        return names;
    }

    private static boolean isConstantName(String name) {
        return name.equals(name.toUpperCase()) && name.contains("_");
    }

    private static boolean containsWord(String text, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }

    private static boolean containsCall(String text, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text).find();
    }
}
