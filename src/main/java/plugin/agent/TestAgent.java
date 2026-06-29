package plugin.agent;

import com.intellij.openapi.project.Project;
import plugin.util.BuildUtil;

public class TestAgent {

    public BuildUtil.BuildResult runTests(AgentContext ctx, String testName) {
        Project project = ctx.getProject();
        BuildUtil.BuildResult result = testName != null && !testName.isBlank()
                ? BuildUtil.runTest(project, testName)
                : BuildUtil.runTest(project, null);

        ctx.getWorkingMemory().setTestsPassed(result.success());
        if (!result.success()) {
            ctx.getWorkingMemory().addValidationError("Tests failed: " + result.output());
        }
        return result;
    }

    public BuildUtil.BuildResult runBuild(AgentContext ctx) {
        BuildUtil.BuildResult result = BuildUtil.runCompile(ctx.getProject());
        ctx.getWorkingMemory().setBuildPassed(result.success());
        if (!result.success()) {
            ctx.getWorkingMemory().addValidationError("Build failed: " + result.output());
        }
        return result;
    }
}
