package plugin.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PromptBuilderTest {

    @Test
    void systemPromptIsModelNeutralAndIncludesWorkflowRules() {
        String editingPrompt = PromptBuilder.buildSystemPrompt(
                "EDITING",
                "past correction",
                "## Developer Environment\n- **OS**: Linux 6.8.0 (amd64)\n- **Shell**: Bash\n",
                "# Skills / Memory\n- **angular-app**: Build Angular apps from images and PDFs.\n");
        String planningPrompt = PromptBuilder.buildSystemPrompt(
                "PLANNING",
                "past correction",
                "## Developer Environment\n- **OS**: Linux 6.8.0 (amd64)\n- **Shell**: Bash\n",
                "# Skills / Memory\n- **angular-app**: Build Angular apps from images and PDFs.\n");

        assertTrue(editingPrompt.contains("Runtime environment:"));
        assertTrue(editingPrompt.contains("Skills and memory:"));
        assertTrue(editingPrompt.contains("Use configured integrations"));
        assertTrue(editingPrompt.contains("review the latest git commit"));
        assertTrue(editingPrompt.contains("Dockerfile, docker-compose, or Helm chart files"));
        assertTrue(editingPrompt.contains("README or project documentation"));
        assertTrue(editingPrompt.contains("filtered tree of actual project files and folders"));
        assertTrue(editingPrompt.contains("Normalize obvious user typos internally before acting"));
        assertTrue(editingPrompt.contains("Do not ask clarification questions when the repository context is sufficient"));
        assertTrue(editingPrompt.contains("use the current workspace root"));
        assertTrue(editingPrompt.contains("answer directly instead of returning a generic example"));
        assertTrue(editingPrompt.contains("Never answer a repository task with a shell command as the primary output"));
        assertTrue(editingPrompt.contains("do not ask for the repo path again"));
        assertTrue(editingPrompt.contains("Do not wrap the tree in <PROJECT_TREE> or <PROJECT_STRUCTURE> tags"));
        assertTrue(editingPrompt.contains("run all tests or show test results"));
        assertTrue(editingPrompt.contains("the client will run the current workspace's test runner directly"));
        assertTrue(editingPrompt.contains("ANALYSIS TASKS"));
        assertTrue(editingPrompt.contains("project understanding"));
        assertTrue(editingPrompt.contains("update your skill set"));
        assertTrue(editingPrompt.contains("create them in the file operation path"));
        assertTrue(editingPrompt.contains("do not emit RUN_TESTS or EXECUTE_COMMAND tags in normal answers"));
        assertTrue(editingPrompt.contains("The client handles test execution and terminal commands"));
        assertTrue(planningPrompt.contains("do not wrap output in <PLAN> tags"));
        assertTrue(!editingPrompt.contains("<RUN_TESTS"));
        assertTrue(!editingPrompt.contains("<EXECUTE_COMMAND"));
    }

    @Test
    void fixPromptsIncludeProjectType() {
        String compilePrompt = PromptBuilder.buildCompileFixPrompt(
                "error output",
                "",
                "source context",
                "Go modules / GO",
                1,
                false);

        String testPrompt = PromptBuilder.buildTestFixPrompt(
                "test output",
                "",
                "source context",
                "TypeScript / TYPESCRIPT",
                1,
                false);

        assertTrue(compilePrompt.contains("Project type: Go modules / GO"));
        assertTrue(testPrompt.contains("Project type: TypeScript / TYPESCRIPT"));
    }
}
