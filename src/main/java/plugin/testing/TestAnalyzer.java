package plugin.testing;

import plugin.llm.PromptBuilder;
import plugin.util.TestReportUtil;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestAnalyzer {

    private static final Pattern COMPILE_ERROR = Pattern.compile(
            "ERROR] (.+\\.java):\\[(\\d+),(\\d+)\\] (.+)"
    );
    private static final Pattern TEST_FAILURE = Pattern.compile(
            "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)"
    );

    public AnalysisResult analyze(TestRunner.TestRunResult result) {
        if (result.success()) {
            return new AnalysisResult(true, "All tests passed: " + result.summary(),
                    List.of(), List.of(), "");
        }

        List<String> compileErrors = extractCompileErrors(result.output());
        List<String> testFailures  = extractTestFailures(result.results());

        String diagnosis;
        if (!compileErrors.isEmpty()) {
            diagnosis = "Compilation errors:\n" + String.join("\n", compileErrors);
        } else if (!testFailures.isEmpty()) {
            diagnosis = "Test failures:\n" + String.join("\n", testFailures);
        } else {
            diagnosis = "Unknown failure:\n" + truncate(result.output(), 500);
        }

        return new AnalysisResult(false, diagnosis, compileErrors, testFailures, result.output());
    }

    // ── fix prompt — full error-guided repair ─────────────────────────────────

    /**
     * Builds a structured repair prompt for the LLM that includes the exact
     * error location, current file content, related files, and previous attempt
     * context — matching the error-guided repair spec template.
     *
     * @param analysis             result of the failed run
     * @param lastResponse         last LLM response (used to detect same-error repeat)
     * @param basePath             project root, used to read file content from disk
     * @param userRequest          original user request that triggered this task
     * @param attempt              1-based retry attempt number
     * @param previousAttemptSummary  brief description of what the last fix tried
     */
    public String buildFixPrompt(AnalysisResult analysis,
                                  String lastResponse,
                                  String basePath,
                                  String userRequest,
                                  int attempt,
                                  String previousAttemptSummary) {

        String rawOutput = analysis.rawOutput();
        String failedCommand = analysis.compileErrors().isEmpty() ? "mvn test" : "mvn compile";

        ErrorContext error = ErrorExtractor.extract(rawOutput, basePath, failedCommand);

        String currentFingerprint = analysis.compileErrors().isEmpty()
                ? AutoFixLoop.extractTestFingerprint(rawOutput)
                : AutoFixLoop.extractCompileFingerprint(rawOutput);
        String previousFingerprint = lastResponse == null ? ""
                : AutoFixLoop.extractCompileFingerprint(lastResponse);
        boolean sameError = AutoFixLoop.isSameError(previousFingerprint, currentFingerprint);

        return PromptBuilder.buildRepairPrompt(
                userRequest,
                error.failedCommand(),
                error.errorSnippet(),
                error.affectedFilePath(),
                error.affectedLine(),
                error.affectedFileContent(),
                error.relatedFileContents(),
                previousAttemptSummary,
                attempt,
                sameError
        );
    }

    /** Backward-compatible overload for callers that do not yet supply full repair context. */
    public String buildFixPrompt(AnalysisResult analysis, String lastResponse) {
        return buildFixPrompt(analysis, lastResponse, null, null, 1, null);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private List<String> extractCompileErrors(String output) {
        return COMPILE_ERROR.matcher(output).results()
                .map(mr -> mr.group(1) + ":" + mr.group(2) + " - " + mr.group(4))
                .limit(5)
                .toList();
    }

    private List<String> extractTestFailures(List<TestReportUtil.TestResult> results) {
        return results.stream()
                .filter(r -> "FAIL".equals(r.status()) || "ERROR".equals(r.status()))
                .map(r -> r.name() + " [" + r.status() + "]")
                .toList();
    }

    private String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // ── result record ─────────────────────────────────────────────────────────

    public record AnalysisResult(
            boolean success,
            String diagnosis,
            List<String> compileErrors,
            List<String> testFailures,
            String rawOutput
    ) {}
}
