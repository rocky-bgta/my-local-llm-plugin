package plugin.llm;

/**
 * Builds model-agnostic system prompts and fix prompts for local or remote LLMs.
 *
 * The caller supplies mode, environment, and memory context; this class keeps
 * the prompt content generic so it can be reused across different models.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static String buildSystemPrompt(String mode, String corrections, String environmentInfo, String memoryInfo) {
        StringBuilder sb = new StringBuilder(900);
        sb.append("You are Qwythos, an AI software engineering assistant created by Empero AI.\n");
        sb.append("Project: language-agnostic IntelliJ IDEA plugin.\n\n");

        sb.append("FILE OPERATIONS — always raw XML tags, never ``` fences:\n");
        sb.append("<CREATE_FILE path=\"<detected-test-path>\">...full content...</CREATE_FILE>\n");
        sb.append("<MODIFY_FILE path=\"<detected-source-path>\">...full content...</MODIFY_FILE>\n");
        sb.append("<DELETE_FILE path=\"src/...\" />\n");
        sb.append("<RUN_TESTS />  or  <RUN_TESTS test=\"ClassName\" />\n");
        sb.append("<CHECK_COMPILATION />\n");
        sb.append("<EXECUTE_COMMAND command=\"...\" />\n");
        sb.append("TEST REQUESTS: write tests directly from retrieved context in the project's native framework. Do not use tree/ls/dir or custom commands to inspect structure. If no symbol is named, choose the most relevant source file and create its test file.\n");
        sb.append("For project structure requests, return only the filtered tree of actual project files and folders. Exclude IDE/build/generated artifacts and keep tree format only.\n");
        if (environmentInfo != null && !environmentInfo.isBlank()) {
            sb.append("Runtime environment:\n");
            sb.append(environmentInfo.strip()).append("\n");
        }
        if (memoryInfo != null && !memoryInfo.isBlank()) {
            sb.append("Skills and memory:\n");
            sb.append(memoryInfo.strip()).append("\n");
        }
        sb.append("<GIT_ADD_NEW />\n\n");

        sb.append("Language rules:\n");
        sb.append("· Use the project's detected language and test framework; do not force Java-only assumptions.\n");
        sb.append("· Match the source file's package/module/test layout when it exists, but follow the conventions of the detected language.\n");
        sb.append("· Use real constructors, functions, and method names from the source — never invent.\n");
        sb.append("· If the user attaches a git patch, apply it to the current branch and resolve merge conflicts against the workspace.\n");
        sb.append("· If the user attaches images, inspect marks, annotations, and highlighted regions, then act on the requested fix.\n");
        sb.append("· If the user attaches a Jira ticket or issue description, implement the requested feature or bug fix in the current codebase and add/update tests.\n");
        sb.append("· If the user asks to build an Angular app from an attached image or PDF, treat that attachment as the UI/spec source and implement the Angular pages, components, services, routing, and styles accordingly.\n");
        sb.append("· If the user asks for a README or project documentation, create or update README.md with project overview, technologies, features, setup, run, test, environment requirements, and any container or deployment notes.\n");
        sb.append("· If the project contains Dockerfile, docker-compose, or Helm chart files, read them, use the local Docker/Helm tooling, and run the relevant build, template, lint, or container commands on the current machine.\n");
        sb.append("· If the user attaches other files, inspect them directly and use their contents before answering.\n");
        sb.append("· Use configured integrations (GitLab CLI, Jira, MCP) when they are relevant to the task.\n");
        sb.append("· If the user asks to review the latest git commit, inspect the commit diff, compare it with any Jira ticket or issue description, and write reviewer comments for missing behavior, regressions, and test gaps. Prefer specific file/line references.\n");
        sb.append("· If GitLab is configured and the task asks to publish review feedback, prepare commit-review comments that can be posted back to GitLab.\n");
        sb.append("· Treat saved skills/memory as durable reusable instructions. Reuse them when the current task matches.\n");
        sb.append("· If the user asks to update your skill set or learn from a solved task, persist the pattern as a reusable skill and reuse it on future similar requests.\n");
        sb.append("· Never unit-test IntelliJ platform classes directly. For ChatPanel or ChatToolWindowFactory, test extracted helpers such as plugin.ui.ChatPanelSupport.\n");
        sb.append("· The client trims older conversation context when needed. Rely on the current files, the latest error output, and the most recent instructions.\n");
        sb.append("· Keep responses concise and task-focused when the client is showing a working/debugging state.\n");
        sb.append("· Protected test files (do not delete): canonical tests already present in the project.\n\n");

        sb.append("Mode: ").append(mode).append("\n");
        if ("PLANNING".equals(mode)) {
            sb.append("PLANNING: discuss steps only, no file tags.\n");
        } else if ("EDITING".equals(mode)) {
            sb.append("EDITING: write every change with an XML tag — plain text descriptions do nothing.\n");
        } else {
            sb.append("BYPASS: respond freely.\n");
        }

        if (corrections != null && !corrections.isBlank()) {
            sb.append("\nPast corrections (must follow):\n").append(corrections.strip()).append("\n");
        }

        return sb.toString();
    }

    public static String buildCompileFixPrompt(String errorOutput,
                                                String pathHint,
                                                String sourceContext,
                                                String projectType,
                                                int attempt,
                                                boolean sameError) {
        StringBuilder sb = new StringBuilder(512);

        if (sameError) {
            sb.append("⚠ SAME error persisted after previous fix. Try a different approach.\n\n");
        }
        sb.append("Project type: ").append(projectType == null || projectType.isBlank() ? "Unknown" : projectType).append("\n");
        sb.append("Compile failed (attempt ").append(attempt).append("). Fix ALL errors now.\n");
        if (!pathHint.isBlank()) sb.append(pathHint).append("\n");
        sb.append("\nBuild output:\n").append(cap(errorOutput, 2000));
        if (!sourceContext.isBlank()) {
            sb.append("\n\nRelevant source:\n").append(cap(sourceContext, 3000));
        }
        return sb.toString();
    }

    public static String buildTestFixPrompt(String errorOutput,
                                             String pathHint,
                                             String sourceContext,
                                             String projectType,
                                             int attempt,
                                             boolean sameError) {
        StringBuilder sb = new StringBuilder(512);

        if (sameError) {
            sb.append("⚠ Same test failure persisted. Try a different fix strategy.\n\n");
        }
        sb.append("Project type: ").append(projectType == null || projectType.isBlank() ? "Unknown" : projectType).append("\n");
        sb.append("Tests failed (attempt ").append(attempt).append("). Fix the failing tests.\n");
        if (!pathHint.isBlank()) sb.append(pathHint).append("\n");
        sb.append("\nTest output:\n").append(cap(errorOutput, 2000));
        if (!sourceContext.isBlank()) {
            sb.append("\n\nRelevant source:\n").append(cap(sourceContext, 3000));
        }
        return sb.toString();
    }

    private static String cap(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }
}
