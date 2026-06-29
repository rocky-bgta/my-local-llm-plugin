package plugin.testing;

import com.intellij.openapi.project.Project;
import plugin.util.BuildUtil;
import plugin.util.TestReportUtil;

import java.util.List;

public class TestRunner {

    private final Project project;

    public TestRunner(Project project) {
        this.project = project;
    }

    public TestRunResult runAll() {
        BuildUtil.BuildResult result = BuildUtil.runTest(project, null);
        List<TestReportUtil.TestResult> tests = result.testResults();
        return new TestRunResult(result.success(), result.output(), tests);
    }

    public TestRunResult runClass(String testClassName) {
        BuildUtil.BuildResult result = BuildUtil.runTest(project, testClassName);
        List<TestReportUtil.TestResult> tests = result.testResults();
        return new TestRunResult(result.success(), result.output(), tests);
    }

    public record TestRunResult(
            boolean success,
            String output,
            List<TestReportUtil.TestResult> results
    ) {
        public long passCount() {
            return results.stream().filter(r -> "PASS".equals(r.status())).count();
        }

        public long failCount() {
            return results.stream().filter(r -> "FAIL".equals(r.status()) || "ERROR".equals(r.status())).count();
        }

        public String summary() {
            if (results.isEmpty()) return success ? "Tests passed" : "Tests failed:\n" + output;
            return String.format("%d passed, %d failed", passCount(), failCount());
        }
    }
}
