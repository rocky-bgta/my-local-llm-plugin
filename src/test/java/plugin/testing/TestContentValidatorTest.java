package plugin.testing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestContentValidatorTest {

    private static final String SOURCE = """
            public class Reranker {
                private static final int DEFAULT_TOP_K = 6;
                public List<String> rerank(List<String> candidates, String query, int topK) { return candidates; }
                public List<String> rerank(List<String> candidates, String query) { return rerank(candidates, query, DEFAULT_TOP_K); }
                private double computeFinalScore(String r, List<String> terms) { return 0; }
                private String truncateContent(String r) { return r; }
            }
            """;

    @Test
    void flagsPrivateMethodCall() {
        String test = """
                import java.util.List;
                import java.util.ArrayList;
                class T {
                    void t() { new Reranker().computeFinalScore("r", new ArrayList<>()); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.stream().anyMatch(v -> v.contains("computeFinalScore")));
    }

    @Test
    void flagsPrivateConstantReference() {
        String test = """
                class T {
                    void t() { int k = 0 + 6; use(DEFAULT_TOP_K); }
                    void use(int i) {}
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.stream().anyMatch(v -> v.contains("DEFAULT_TOP_K")));
    }

    @Test
    void doesNotFlagPublicMethodCall() {
        String test = """
                import java.util.List;
                import java.util.ArrayList;
                class T {
                    void t() { new Reranker().rerank(new ArrayList<>(), "q"); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.isEmpty(), "unexpected: " + violations);
    }

    @Test
    void flagsMissingJavaUtilImport() {
        String test = """
                import org.junit.jupiter.api.Test;
                class T {
                    void t() { List<String> l = new ArrayList<>(); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.stream().anyMatch(v -> v.contains("java.util.List")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("java.util.ArrayList")));
    }

    @Test
    void acceptsWildcardJavaUtilImport() {
        String test = """
                import java.util.*;
                class T {
                    void t() { List<String> l = new ArrayList<>(); Arrays.asList("a"); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.isEmpty(), "unexpected: " + violations);
    }

    @Test
    void privateViolationNamesTheSourceClass() {
        String test = """
                import java.util.List;
                import java.util.ArrayList;
                class T {
                    void t() { new Reranker().computeFinalScore("r", new ArrayList<>()); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.stream().anyMatch(v -> v.contains("Reranker")));
    }

    @Test
    void flagsMissingJavaUtilStreamImport() {
        String test = """
                import java.util.*;
                import org.junit.jupiter.api.Test;
                class T {
                    void t() { List<String> l = Arrays.stream(new String[]{"a"}).collect(Collectors.toList()); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.stream().anyMatch(v -> v.contains("java.util.stream.Collectors")));
    }

    @Test
    void acceptsWildcardJavaUtilStreamImport() {
        String test = """
                import java.util.*;
                import java.util.stream.*;
                class T {
                    void t() { List<String> l = Arrays.stream(new String[]{"a"}).collect(Collectors.toList()); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(SOURCE, test);

        assertTrue(violations.isEmpty(), "unexpected: " + violations);
    }

    @Test
    void doesNotFlagPrivateNameRedeclaredInTest() {
        String source = """
                public class Reranker {
                    public List<String> rerank(List<String> candidates, String query) { return candidates; }
                    private List<String> tokenize(String text) { return List.of(); }
                }
                """;
        String test = """
                import java.util.List;
                import java.util.ArrayList;
                class T {
                    void t() { List<String> terms = tokenize("example query"); }
                    private List<String> tokenize(String text) { return new ArrayList<>(); }
                }
                """;

        List<String> violations = TestContentValidator.findViolations(source, test);

        assertTrue(violations.isEmpty(), "unexpected: " + violations);
    }

    @Test
    void emptyTestContentYieldsNoViolations() {
        assertTrue(TestContentValidator.findViolations(SOURCE, "").isEmpty());
        assertTrue(TestContentValidator.findViolations(SOURCE, null).isEmpty());
    }

    @Test
    void blankSourceStillChecksImports() {
        String test = "class T { void t() { Map<String, String> m = new HashMap<>(); } }";

        List<String> violations = TestContentValidator.findViolations("", test);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("java.util.Map")));
    }
}
