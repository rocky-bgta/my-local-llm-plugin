package plugin.testing;

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
            return new AnalysisResult(true, "All tests passed: " + result.summary(), List.of(), List.of());
        }

        List<String> compileErrors = extractCompileErrors(result.output());
        List<String> testFailures = extractTestFailures(result.results());

        String diagnosis;
        if (!compileErrors.isEmpty()) {
            diagnosis = "Compilation errors:\n" + String.join("\n", compileErrors);
        } else if (!testFailures.isEmpty()) {
            diagnosis = "Test failures:\n" + String.join("\n", testFailures);
        } else {
            diagnosis = "Unknown failure:\n" + truncate(result.output(), 500);
        }

        return new AnalysisResult(false, diagnosis, compileErrors, testFailures);
    }

    public String buildFixPrompt(AnalysisResult analysis, String lastResponse) {
        return "The previous attempt failed:\n\n" +
                analysis.diagnosis() + "\n\n" +
                "Fix the issue. Use XML tags for file operations.";
    }

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

    public record AnalysisResult(
            boolean success,
            String diagnosis,
            List<String> compileErrors,
            List<String> testFailures
    ) {}
}
