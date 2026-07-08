package plugin.testing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorExtractorTest {

    // ── parseCompileErrors ────────────────────────────────────────────────────

    @Test
    void parseCompileErrors_emptyOutput_returnsEmpty() {
        // Arrange
        String output = "";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertTrue(errors.isEmpty());
    }

    @Test
    void parseCompileErrors_noErrorLine_returnsEmpty() {
        // Arrange
        String output = "[INFO] BUILD SUCCESS\n[INFO] Total time: 3.2 s";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertTrue(errors.isEmpty());
    }

    @Test
    void parseCompileErrors_relativeJavaPath_extractsFileLineAndMessage() {
        // Arrange
        String output = "[ERROR] src/test/java/plugin/rag/RerankerTest.java:[14,16] cannot find symbol";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(1, errors.size());
        ErrorExtractor.ParsedError e = errors.get(0);
        assertEquals("src/test/java/plugin/rag/RerankerTest.java", e.filePath());
        assertEquals(14, e.line());
        assertEquals("cannot find symbol", e.message());
    }

    @Test
    void parseCompileErrors_absoluteUnixPath_extractsCorrectly() {
        // Arrange
        String output = "[ERROR] /home/user/project/src/main/java/plugin/Foo.java:[42,8] incompatible types";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(1, errors.size());
        assertEquals(42, errors.get(0).line());
        assertTrue(errors.get(0).filePath().endsWith("Foo.java"));
    }

    @Test
    void parseCompileErrors_multipleErrorsSameFile_allParsed() {
        // Arrange
        String output =
                "[ERROR] src/test/java/plugin/FooTest.java:[10,5] cannot find symbol\n" +
                "[ERROR] src/test/java/plugin/FooTest.java:[20,5] method not found\n" +
                "[ERROR] src/test/java/plugin/FooTest.java:[30,5] incompatible types";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(3, errors.size());
        assertEquals(10, errors.get(0).line());
        assertEquals(20, errors.get(1).line());
        assertEquals(30, errors.get(2).line());
    }

    @Test
    void parseCompileErrors_multipleErrorsDifferentFiles_allParsed() {
        // Arrange
        String output =
                "[ERROR] src/test/java/plugin/FooTest.java:[5,1] cannot find symbol\n" +
                "[ERROR] src/test/java/plugin/BarTest.java:[12,3] method not found";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).filePath().contains("FooTest"));
        assertTrue(errors.get(1).filePath().contains("BarTest"));
    }

    @Test
    void parseCompileErrors_duplicateLines_deduplicatedByKey() {
        // Arrange — same file, line, and message repeated twice in output
        String line = "[ERROR] src/test/java/plugin/FooTest.java:[10,5] cannot find symbol\n";
        String output = line + line;

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(1, errors.size());
    }

    @Test
    void parseCompileErrors_kotlinFile_recognized() {
        // Arrange
        String output = "[ERROR] src/main/kotlin/plugin/Bar.kt:[7,3] unresolved reference: Baz";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).filePath().endsWith("Bar.kt"));
    }

    @Test
    void parseCompileErrors_infoLineNotMatched() {
        // Arrange — [INFO] lines must never be matched as errors
        String output = "[INFO] src/main/java/plugin/Foo.java:[1,1] compiling";

        // Act
        List<ErrorExtractor.ParsedError> errors = ErrorExtractor.parseCompileErrors(output);

        // Assert
        assertTrue(errors.isEmpty());
    }

    // ── extractTestFailureSnippet ─────────────────────────────────────────────

    @Test
    void extractTestFailureSnippet_noFailure_returnsBlank() {
        // Arrange
        String output = "[INFO] Tests run: 5, Failures: 0, Errors: 0";

        // Act
        String snippet = ErrorExtractor.extractTestFailureSnippet(output);

        // Assert
        assertTrue(snippet.isBlank());
    }

    @Test
    void extractTestFailureSnippet_singleFailure_returnsFormattedLine() {
        // Arrange
        String output = "[ERROR] plugin.rag.RerankerTest.rerank_emptyList_returnsEmpty";

        // Act
        String snippet = ErrorExtractor.extractTestFailureSnippet(output);

        // Assert
        assertTrue(snippet.contains("[FAIL]"));
        assertTrue(snippet.contains("RerankerTest"));
    }

    @Test
    void extractTestFailureSnippet_multipleFailures_allCaptured() {
        // Arrange
        String output =
                "[ERROR] plugin.FooTest.test1\n" +
                "[ERROR] plugin.BarTest.test2\n" +
                "[ERROR] plugin.BazIT.test3";

        // Act
        String snippet = ErrorExtractor.extractTestFailureSnippet(output);

        // Assert
        assertTrue(snippet.contains("FooTest"));
        assertTrue(snippet.contains("BarTest"));
        assertTrue(snippet.contains("BazIT"));
    }

    @Test
    void extractTestFailureSnippet_duplicates_deduplicatedInOutput() {
        // Arrange
        String line = "[ERROR] plugin.FooTest.test1\n";
        String output = line + line + line;

        // Act
        String snippet = ErrorExtractor.extractTestFailureSnippet(output);

        // Assert — should appear only once
        assertEquals(1, snippet.lines().filter(l -> l.contains("FooTest")).count());
    }

    // ── findCounterpartPath ───────────────────────────────────────────────────

    @Test
    void findCounterpartPath_testFile_returnsProductionFile() {
        // Arrange
        String testPath = "src/test/java/plugin/rag/RerankerTest.java";

        // Act
        String counterpart = ErrorExtractor.findCounterpartPath(testPath);

        // Assert
        assertEquals("src/main/java/plugin/rag/Reranker.java", counterpart);
    }

    @Test
    void findCounterpartPath_itFile_returnsProductionFile() {
        // Arrange
        String testPath = "src/test/java/plugin/FooIT.java";

        // Act
        String counterpart = ErrorExtractor.findCounterpartPath(testPath);

        // Assert
        assertEquals("src/main/java/plugin/Foo.java", counterpart);
    }

    @Test
    void findCounterpartPath_productionFile_returnsTestFile() {
        // Arrange
        String mainPath = "src/main/java/plugin/rag/Reranker.java";

        // Act
        String counterpart = ErrorExtractor.findCounterpartPath(mainPath);

        // Assert
        assertEquals("src/test/java/plugin/rag/RerankerTest.java", counterpart);
    }

    @Test
    void findCounterpartPath_windowsSeparators_normalizedCorrectly() {
        // Arrange
        String testPath = "src\\test\\java\\plugin\\FooTest.java";

        // Act
        String counterpart = ErrorExtractor.findCounterpartPath(testPath);

        // Assert — should still produce the production path
        assertNotNull(counterpart);
        assertTrue(counterpart.contains("src/main/java/plugin/Foo.java"));
    }

    @Test
    void findCounterpartPath_noSrcLayout_returnsNull() {
        // Arrange
        String arbitrary = "scripts/build.sh";

        // Act
        String counterpart = ErrorExtractor.findCounterpartPath(arbitrary);

        // Assert
        assertNull(counterpart);
    }

    @Test
    void findCounterpartPath_nullInput_returnsNull() {
        assertNull(ErrorExtractor.findCounterpartPath(null));
    }

    // ── extract — root cause grouping ─────────────────────────────────────────

    @Test
    void extract_emptyOutput_returnsUnknownFallback() {
        // Arrange / Act
        ErrorContext ctx = ErrorExtractor.extract("", null, "mvn test");

        // Assert
        assertFalse(ctx.hasLocation());
        assertFalse(ctx.errorSnippet().isBlank());
    }

    @Test
    void extract_nullOutput_returnsUnknownFallback() {
        // Arrange / Act
        ErrorContext ctx = ErrorExtractor.extract(null, null, "mvn compile");

        // Assert
        assertFalse(ctx.hasLocation());
    }

    @Test
    void extract_singleCompileError_populatesLocation() {
        // Arrange
        String output =
                "[ERROR] src/test/java/plugin/rag/RerankerTest.java:[42,5] cannot find symbol";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn test");

        // Assert
        assertTrue(ctx.hasLocation());
        assertTrue(ctx.affectedFilePath().contains("RerankerTest.java"));
        assertEquals(42, ctx.affectedLine());
        assertEquals("mvn test", ctx.failedCommand());
        assertTrue(ctx.errorSnippet().contains("cannot find symbol"));
    }

    @Test
    void extract_errorsInTwoFiles_firstFileIsRootCause() {
        // Arrange
        String output =
                "[ERROR] src/test/java/plugin/FooTest.java:[5,1] error A\n" +
                "[ERROR] src/test/java/plugin/BarTest.java:[9,2] error B";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn test");

        // Assert — FooTest is listed first so it is the root cause
        assertTrue(ctx.affectedFilePath().contains("FooTest.java"));
    }

    @Test
    void extract_multipleErrorsSameFile_snippetMentionsCount() {
        // Arrange
        String output =
                "[ERROR] src/test/java/plugin/FooTest.java:[5,1] error A\n" +
                "[ERROR] src/test/java/plugin/FooTest.java:[10,3] error B\n" +
                "[ERROR] src/test/java/plugin/FooTest.java:[15,7] error C";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn test");

        // Assert — snippet should mention the additional errors
        assertTrue(ctx.errorSnippet().contains("more error"));
    }

    @Test
    void extract_noCompileErrors_fallsBackToTestFailureSnippet() {
        // Arrange — only a test failure marker, no compiler error
        String output = "[INFO] BUILD FAILURE\n[ERROR] plugin.FooTest.testSomething";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn test");

        // Assert
        assertFalse(ctx.hasLocation());
        assertTrue(ctx.errorSnippet().contains("FooTest"));
    }

    @Test
    void extract_noRecognizedError_returnsFirstErrorLineAsSnippet() {
        // Arrange
        String output = "[ERROR] Some unexpected build system failure message";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn install");

        // Assert
        assertFalse(ctx.errorSnippet().isBlank());
        assertTrue(ctx.errorSnippet().contains("unexpected build system failure"));
    }

    @Test
    void extract_rootCauseGroup_containsAllErrorsForRootFile() {
        // Arrange
        String output =
                "[ERROR] src/main/java/plugin/Foo.java:[3,1] error one\n" +
                "[ERROR] src/main/java/plugin/Foo.java:[7,5] error two\n" +
                "[ERROR] src/main/java/plugin/Bar.java:[1,1] error three";

        // Act
        ErrorContext ctx = ErrorExtractor.extract(output, null, "mvn compile");

        // Assert — rootCauseGroup contains both errors for Foo.java but not Bar.java
        assertTrue(ctx.rootCauseGroup().contains("error one"));
        assertTrue(ctx.rootCauseGroup().contains("error two"));
        assertFalse(ctx.rootCauseGroup().contains("error three"));
    }

    // ── readRelatedFiles ──────────────────────────────────────────────────────

    @Test
    void readRelatedFiles_nullBasePath_returnsEmptyMap() {
        // Arrange / Act
        Map<String, String> result = ErrorExtractor.readRelatedFiles(null, "src/test/java/Foo.java");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void readRelatedFiles_blankFilePath_returnsEmptyMap() {
        // Arrange / Act
        Map<String, String> result = ErrorExtractor.readRelatedFiles("/some/path", "   ");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void readRelatedFiles_nonExistentCounterpart_returnsEmptyMap() {
        // Arrange — basePath exists but counterpart file does not
        String nonExistentBase = "C:/definitely/does/not/exist/at/all";
        String filePath        = "src/test/java/plugin/FooTest.java";

        // Act
        Map<String, String> result = ErrorExtractor.readRelatedFiles(nonExistentBase, filePath);

        // Assert
        assertTrue(result.isEmpty());
    }
}
