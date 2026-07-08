package plugin.llm;

import java.util.Map;

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
        sb.append("<CHECK_COMPILATION />\n");
        sb.append("The client handles test execution and terminal commands; do not emit RUN_TESTS or EXECUTE_COMMAND tags in normal answers.\n");
        sb.append("Every file tag MUST end with its closing tag. Output has a hard token limit: keep each file short and focused, emit at most one large file per response, and never start a file you cannot finish — an unclosed tag is discarded entirely.\n");
        sb.append("TEST REQUESTS: write tests directly from retrieved context in the project's native framework. Do not use tree/ls/dir or custom commands to inspect structure. If no symbol is named, choose the most relevant source file in the current workspace and create its test file. If the package folders do not exist yet, create them in the file operation path instead of asking the user. When the user says 'this file', infer the matching source file from the current context and write the conventional test file path for it (for example, OpenChatAction.java -> OpenChatActionTest.java). Produce compile-ready JUnit 5 tests that match the current project setup and use the AAA pattern in each test: Arrange, Act, Assert. Do not turn the request into a generic example; use the real repository class, package, and framework conventions.\n");
        sb.append("PRE-TEST CONTEXT RULE (applies to ALL languages — Java, Kotlin, Go, Python, JavaScript, TypeScript, and others): Before generating any test file, ALL of the following context items must be present in the conversation. If an item is not yet in context, infer it from the attached file and retrieved project files before writing any test code:\n");
        sb.append("  1. Target production file — content must be read or attached\n");
        sb.append("  2. All constructor or initializer dependencies of the target class/function\n");
        sb.append("  3. Import or require/use statements from the target file (reveals its dependencies)\n");
        sb.append("  4. Related model, DTO, interface, or utility classes referenced by the target\n");
        sb.append("  5. At least one existing test file in the same package or module (to follow project test conventions)\n");
        sb.append("  6. Build descriptor: pom.xml, build.gradle, go.mod, package.json, Cargo.toml, or equivalent\n");
        sb.append("  7. Test framework and version detected from the build descriptor\n");
        sb.append("  8. Mocking and assertion libraries in use (Mockito, AssertJ, testify, Jest, etc.)\n");
        sb.append("  9. Only public methods and constructors — never test private members directly\n");
        sb.append(" 10. The compile and test command for this project (mvn test, go test ./..., pytest, npm test, etc.)\n");
        sb.append("Do NOT generate the test until all reachable context items have been collected. Infer missing items from the build descriptor and existing test conventions.\n");
        sb.append("ERROR-GUIDED REPAIR RULE: When a generated test fails to compile or run, do NOT regenerate the whole test blindly. Instead: (1) read the exact compiler or test error output; (2) read the current content of the failing test file; (3) read the related production file; (4) fetch any additional missing files if the error references them; (5) fix ONLY the first root-cause error; (6) re-run the test; (7) repeat until the test passes or a true blocker is found. Each fix attempt must be driven by the actual error — never guess at fixes without reading the current error.\n");
        sb.append("INTELLIJ ACTION TESTS: do not instantiate, subclass, or implement AnActionEvent. Do not create fake IntelliJ interfaces/classes such as ProjectDelegate. Do not mock ToolWindowManager.getInstance(project). Do not write placeholder code such as 'methods omitted for brevity'. Prefer package-private helper methods or protected seams from the retrieved source. If the source does not expose a testable seam, modify the production class minimally first, then test that seam with JUnit 5.\n");
        sb.append("For project structure requests, use the current workspace root. Do not ask which repository or directory to inspect. Do not call EXECUTE_COMMAND at all. Return only the filtered tree of actual project files and folders. Do not wrap the tree in <PROJECT_TREE> or <PROJECT_STRUCTURE> tags. The client will render the tree directly. Exclude IDE/build/generated artifacts and keep tree format only.\n");
        sb.append("Normalize obvious user typos internally before acting; infer the intended meaning instead of asking the user to retype the prompt.\n");
        sb.append("Do not ask clarification questions when the repository context is sufficient. Infer the likely task from the current workspace, recent history, attachments, and project conventions, then act.\n");
        sb.append("For supported repository tasks, answer directly instead of returning a generic example, test plan, or template. Write the concrete artifact for the current workspace.\n");
        sb.append("Never answer a repository task with a shell command as the primary output unless the workflow explicitly requires EXECUTE_COMMAND and the command is appropriate for the current operating system.\n");
        sb.append("ANALYSIS TASKS: when the user asks for project understanding, architecture, folder structure, build system, frameworks, entry point, dependency graph, module relationships, external services, database, message brokers, test framework, current file explanation, related files, dependency analysis, security review, performance review, build failure analysis, or screenshot understanding, answer with a structured report instead of asking for the repo path. Use headings and concrete findings from the current workspace context.\n");
        sb.append("For analysis tasks, do not say you need to inspect the repository, list files, or run discovery commands first; the client already provides project context and expects the final report.\n");
        sb.append("For current-file requests, use the selected or most recently targeted symbol/file if available. For related-files requests, include interfaces, implementations, tests, controllers, services, repositories, configuration, and DTOs when relevant.\n");
        sb.append("For build-failure or auto-fix requests, fix issues one by one and rebuild until clean. For dependency analysis, identify unused, duplicate, outdated, and risky dependencies. For security and performance reviews, list findings, impact, and concrete fixes.\n");
        sb.append("· Test generation and repair must behave consistently across Java, Kotlin, Go, Python, JavaScript, TypeScript, and other supported languages — always adapt the test framework, file path conventions, import style, and mocking approach to the target language.\n");
        sb.append("For screenshot understanding, inspect the attached image first, identify UI errors or stack traces, then locate the source files to fix.\n");
        sb.append("For large features or autonomous mode, create a plan, apply changes incrementally, and validate after each meaningful edit.\n");
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
        sb.append("· If the user asks to delete unnecessary files or clean up code, identify obsolete files or dead code, use DELETE_FILE or refactor changes as appropriate, and explain what was removed.\n");
        sb.append("· If the user asks for tests, README, cleanup, or commit review in the current repository, do not ask for the repo path again; use the current workspace and the retrieved context.\n");
        sb.append("· If the user asks for tests for a named symbol, generate the actual test file in the repository's test framework instead of a generic test checklist or example. Use the AAA pattern in each test. Create any missing test folders in the file-operation path. If the file already exists, modify it to fix the failing test rather than creating a duplicate.\n");
        sb.append("· If the user asks to run all tests or show test results, the client will run the current workspace's test runner directly; respond with human-readable status and do not emit RUN_TESTS or EXECUTE_COMMAND.\n");
        sb.append("· If the user asks for README.md, create it from the current project structure and environment context instead of asking what the repository contains.\n");
        sb.append("· If the user asks to review the latest commit, use the current repository commit diff and the supplied ticket/spec context; do not ask the user to fetch the commit hash manually.\n");
        sb.append("· If the user asks for project understanding, current file explanation, related files, build failure analysis, dependency analysis, security review, or performance review, answer with concrete repository findings and structured sections.\n");
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
        sb.append("· For AnAction classes, avoid direct AnActionEvent construction, AnActionEvent stubs, fake IntelliJ types, and static IntelliJ service mocking; test package-private behavior helpers or protected overrides instead.\n");
        sb.append("· The client trims older conversation context when needed. Rely on the current files, the latest error output, and the most recent instructions.\n");
        sb.append("· Keep responses concise and task-focused when the client is showing a working/debugging state.\n");
        sb.append("· Protected test files (do not delete): canonical tests already present in the project.\n\n");

        sb.append("Mode: ").append(mode).append("\n");
        if ("PLANNING".equals(mode)) {
            sb.append("PLANNING: discuss steps only, no file tags, and do not wrap output in <PLAN> tags.\n");
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

    /**
     * Builds the structured repair prompt defined by the error-guided repair spec.
     *
     * <p>Every placeholder in the template is populated. The LLM receives the exact
     * error location, the current content of the affected file, related files (e.g.
     * the production class when the test is broken), and a summary of what the
     * previous attempt tried — so it can choose a different approach.
     *
     * @param userRequest          original user request text
     * @param failedCommand        the build/test command that failed (e.g. {@code "mvn test"})
     * @param errorSnippet         the most relevant portion of the error output
     * @param affectedFilePath     path of the file that contains the error
     * @param affectedLine         line number of the first error (0 = unknown)
     * @param currentFileContent   current on-disk content of the affected file
     * @param relatedFileContents  map of path → content for related files (may be empty)
     * @param previousAttemptSummary  what the previous LLM fix tried (may be null)
     * @param attempt              1-based retry attempt number
     * @param sameError            true when the error fingerprint matches the previous attempt
     */
    public static String buildRepairPrompt(
            String userRequest,
            String failedCommand,
            String errorSnippet,
            String affectedFilePath,
            int affectedLine,
            String currentFileContent,
            Map<String, String> relatedFileContents,
            String previousAttemptSummary,
            int attempt,
            boolean sameError) {

        StringBuilder sb = new StringBuilder(1024);

        if (sameError) {
            sb.append("⚠ Same error persisted after the previous fix. Try a different approach.\n\n");
        }

        sb.append("The previous implementation failed (attempt ").append(attempt).append(").\n\n");

        sb.append("Original user request:\n");
        sb.append(orEmpty(userRequest)).append("\n\n");

        sb.append("Failed command:\n");
        sb.append(orEmpty(failedCommand)).append("\n\n");

        sb.append("Relevant error:\n");
        sb.append(orEmpty(errorSnippet)).append("\n\n");

        if (affectedFilePath != null && !affectedFilePath.isBlank()) {
            sb.append("Affected file:\n").append(affectedFilePath).append("\n\n");
            if (affectedLine > 0) {
                sb.append("Affected line:\n").append(affectedLine).append("\n\n");
            }
        }

        if (currentFileContent != null && !currentFileContent.isBlank()) {
            sb.append("Current file content:\n")
              .append(cap(currentFileContent, 3_500)).append("\n\n");
        }

        if (relatedFileContents != null && !relatedFileContents.isEmpty()) {
            sb.append("Related files:\n");
            relatedFileContents.forEach((path, content) ->
                sb.append("=== ").append(path).append(" ===\n")
                  .append(cap(content, 2_000)).append("\n"));
            sb.append("\n");
        }

        if (previousAttemptSummary != null && !previousAttemptSummary.isBlank()) {
            sb.append("Previous attempt summary:\n")
              .append(previousAttemptSummary).append("\n\n");
        }

        sb.append("Task:\n");
        sb.append("Fix this specific error first.\n\n");

        sb.append("Rules:\n");
        sb.append("- Do not regenerate unrelated files.\n");
        sb.append("- Do not change public/private visibility only to make tests pass.\n");
        sb.append("- Do not test private methods directly.\n");
        sb.append("- Preserve project style.\n");
        sb.append("- Keep tests in AAA pattern.\n");
        sb.append("- Return the corrected file using XML file-operation tags "
                + "(<MODIFY_FILE path=\"...\">...</MODIFY_FILE>).\n");
        sb.append("- After this fix the build/tests will run again; "
                + "the next error (if any) will be provided in the following message.\n");

        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String cap(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }
}
