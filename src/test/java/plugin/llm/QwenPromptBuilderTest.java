package plugin.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class QwenPromptBuilderTest {

    @Test
    void systemPromptIncludesRuntimeEnvironmentInfo() {
        String prompt = QwenPromptBuilder.buildSystemPrompt(
                "EDITING",
                "past correction",
                "## Developer Environment\n- **OS**: Linux 6.8.0 (amd64)\n- **Shell**: Bash\n",
                "# Skills / Memory\n- **angular-app**: Build Angular apps from images and PDFs.\n");

        assertTrue(prompt.contains("Runtime environment:"));
        assertTrue(prompt.contains("## Developer Environment"));
        assertTrue(prompt.contains("Linux 6.8.0"));
        assertTrue(prompt.contains("Skills and memory:"));
        assertTrue(prompt.contains("angular-app"));
        assertTrue(prompt.contains("Use configured integrations"));
        assertTrue(prompt.contains("review the latest git commit"));
        assertTrue(prompt.contains("Dockerfile, docker-compose, or Helm chart files"));
        assertTrue(prompt.contains("README or project documentation"));
        assertTrue(prompt.contains("filtered tree of actual project files and folders"));
        assertTrue(prompt.contains("update your skill set"));
    }

    @Test
    void fixPromptsIncludeProjectType() {
        String compilePrompt = QwenPromptBuilder.buildCompileFixPrompt(
                "error output",
                "",
                "source context",
                "Go modules / GO",
                1,
                false);

        String testPrompt = QwenPromptBuilder.buildTestFixPrompt(
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
