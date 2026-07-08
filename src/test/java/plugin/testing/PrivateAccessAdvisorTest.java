package plugin.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateAccessAdvisorTest {

    private static final String JAVA_METHOD_ERROR =
            "[ERROR] /C:/repo/src/test/java/plugin/rag/RerankerTest.java:[25,30] "
                    + "computeFinalScore(plugin.rag.RetrievalResult,java.util.List<java.lang.String>) "
                    + "has private access in plugin.rag.Reranker";

    private static final String JAVA_FIELD_ERROR =
            "[ERROR] /C:/repo/src/test/java/plugin/memory/WorkingMemoryTest.java:[12,40] "
                    + "MAX_HISTORY has private access in plugin.memory.WorkingMemory";

    @Test
    void extractViolationsParsesPrivateMethodError() {
        String buildOutput = JAVA_METHOD_ERROR;

        List<String[]> violations = PrivateAccessAdvisor.extractViolations(buildOutput);

        assertEquals(1, violations.size());
        assertEquals("computeFinalScore(plugin.rag.RetrievalResult,java.util.List<java.lang.String>)",
                violations.get(0)[0]);
        assertEquals("plugin.rag.Reranker", violations.get(0)[1]);
    }

    @Test
    void extractViolationsParsesPrivateFieldError() {
        String buildOutput = JAVA_FIELD_ERROR;

        List<String[]> violations = PrivateAccessAdvisor.extractViolations(buildOutput);

        assertEquals(1, violations.size());
        assertEquals("MAX_HISTORY", violations.get(0)[0]);
        assertEquals("plugin.memory.WorkingMemory", violations.get(0)[1]);
    }

    @Test
    void extractViolationsParsesKotlinError() {
        String buildOutput = "e: RerankerTest.kt:10:5 cannot access 'computeFinalScore': it is private in 'Reranker'";

        List<String[]> violations = PrivateAccessAdvisor.extractViolations(buildOutput);

        assertEquals(1, violations.size());
        assertEquals("computeFinalScore", violations.get(0)[0]);
        assertEquals("Reranker", violations.get(0)[1]);
    }

    @Test
    void extractViolationsDeduplicatesRepeatedErrors() {
        String buildOutput = JAVA_METHOD_ERROR + "\n" + JAVA_METHOD_ERROR;

        List<String[]> violations = PrivateAccessAdvisor.extractViolations(buildOutput);

        assertEquals(1, violations.size());
    }

    @Test
    void buildRetryGuidanceReturnsEmptyWhenNoPrivateAccessError() {
        String buildOutput = "[ERROR] /C:/repo/src/test/java/FooTest.java:[3,8] cannot find symbol";

        String guidance = PrivateAccessAdvisor.buildRetryGuidance(null, buildOutput);

        assertEquals("", guidance);
    }

    @Test
    void buildRetryGuidanceNamesViolationAndRules() {
        String guidance = PrivateAccessAdvisor.buildRetryGuidance(null, JAVA_METHOD_ERROR);

        assertTrue(guidance.contains("computeFinalScore(plugin.rag.RetrievalResult,java.util.List<java.lang.String>) in plugin.rag.Reranker"));
        assertTrue(guidance.contains("Do NOT modify production code visibility"));
        assertTrue(guidance.contains("public API"));
        assertTrue(guidance.contains("AAA pattern"));
    }

    @Test
    void buildRetryGuidanceListsPublicApiOfOwnerClass(@TempDir Path projectRoot) throws IOException {
        writeRerankerSource(projectRoot);

        String guidance = PrivateAccessAdvisor.buildRetryGuidance(projectRoot.toString(), JAVA_METHOD_ERROR);

        assertTrue(guidance.contains("Public API of plugin.rag.Reranker"));
        assertTrue(guidance.contains("rerank(List<String> candidates, String query)"));
        assertFalse(guidance.contains("- truncateContent("));
    }

    @Test
    void publicMethodsOfSkipsPrivateMethods(@TempDir Path projectRoot) throws IOException {
        writeRerankerSource(projectRoot);

        List<String> methods = PrivateAccessAdvisor.publicMethodsOf(projectRoot.toString(), "plugin.rag.Reranker");

        assertTrue(methods.stream().anyMatch(m -> m.startsWith("rerank(")));
        assertTrue(methods.stream().noneMatch(m -> m.startsWith("computeFinalScore(")));
    }

    @Test
    void publicMethodSignaturesExtractsFromContent() {
        String source = """
                public class Reranker {
                    public List<String> rerank(List<String> candidates, String query) { return candidates; }
                    private double computeFinalScore(String r, List<String> terms) { return 0; }
                }
                """;

        List<String> methods = PrivateAccessAdvisor.publicMethodSignatures(source);

        assertEquals(List.of("rerank(List<String> candidates, String query)"), methods);
    }

    @Test
    void publicMethodSignaturesHandlesBlankContent() {
        assertTrue(PrivateAccessAdvisor.publicMethodSignatures("").isEmpty());
        assertTrue(PrivateAccessAdvisor.publicMethodSignatures(null).isEmpty());
    }

    @Test
    void publicMethodsOfReturnsEmptyWhenSourceMissing(@TempDir Path projectRoot) {
        List<String> methods = PrivateAccessAdvisor.publicMethodsOf(projectRoot.toString(), "plugin.rag.Missing");

        assertTrue(methods.isEmpty());
    }

    private static void writeRerankerSource(Path projectRoot) throws IOException {
        Path source = projectRoot.resolve("src/main/java/plugin/rag/Reranker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package plugin.rag;

                import java.util.List;

                public class Reranker {
                    public List<String> rerank(List<String> candidates, String query) { return candidates; }
                    private double computeFinalScore(String r, List<String> terms) { return 0; }
                    private String truncateContent(String r) { return r; }
                }
                """);
    }
}
