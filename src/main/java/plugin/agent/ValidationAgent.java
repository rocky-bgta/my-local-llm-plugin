package plugin.agent;

import plugin.util.BuildUtil;
import plugin.util.TestReportUtil;

import java.util.ArrayList;
import java.util.List;

public class ValidationAgent {

    public ValidationResult validate(AgentContext ctx) {
        List<String> issues = new ArrayList<>(ctx.getWorkingMemory().getValidationErrors());
        List<String> passed = new ArrayList<>();

        boolean buildOk = ctx.getWorkingMemory().isBuildPassed();
        boolean testsOk = ctx.getWorkingMemory().isTestsPassed();
        boolean editsApplied = ctx.isEditApplied();

        if (buildOk) passed.add("Build passed");
        else if (editsApplied) issues.add("Build not verified");

        if (testsOk) passed.add("Tests passed");
        else if (ctx.isTestsRan()) issues.add("Tests failed or not all passed");

        // Check if response contains XML tags that were expected but not applied
        String response = ctx.getLlmResponse();
        if (response != null && response.contains("<CREATE_FILE>") && !editsApplied) {
            issues.add("LLM produced CREATE_FILE tags but no files were created");
        }

        boolean overallSuccess = issues.isEmpty() || (!buildOk && !editsApplied);
        ctx.setValidationPassed(overallSuccess);

        return new ValidationResult(overallSuccess, passed, issues);
    }

    public record ValidationResult(boolean success, List<String> passed, List<String> issues) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            passed.forEach(p -> sb.append("✓ ").append(p).append("\n"));
            issues.forEach(i -> sb.append("✗ ").append(i).append("\n"));
            return sb.toString().trim();
        }
    }
}
