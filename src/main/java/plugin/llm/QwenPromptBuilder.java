package plugin.llm;

/**
 * Builds system prompts and fix prompts tuned for Qwen2.5-Coder-7B / Qwen2.5-VL-7B.
 *
 * Design goals for the 7B model:
 *  - System prompt ≤ 900 chars (7B degrades on very long instructions)
 *  - Explicit XML-tag examples with real paths (Qwen follows concrete examples)
 *  - Direct "senior engineer" framing (improves code quality at small scale)
 *  - Fix prompts are short and targeted — the model reads errors well but loses
 *    track of long context, so we include only the broken file and the exact error.
 */
public final class QwenPromptBuilder {

    private QwenPromptBuilder() {}

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    public static String buildSystemPrompt(String mode, String corrections) {
        StringBuilder sb = new StringBuilder(900);
        sb.append("You are Qwen2.5-Coder, a senior Java software engineer.\n");
        sb.append("Project: Java 21 · Maven · IntelliJ IDEA plugin.\n\n");

        sb.append("FILE OPERATIONS — always raw XML tags, never ``` fences:\n");
        sb.append("<CREATE_FILE path=\"src/test/java/plugin/FooTest.java\">...full content...</CREATE_FILE>\n");
        sb.append("<MODIFY_FILE path=\"src/main/java/plugin/Foo.java\">...full content...</MODIFY_FILE>\n");
        sb.append("<DELETE_FILE path=\"src/...\" />\n");
        sb.append("<RUN_TESTS />  or  <RUN_TESTS test=\"ClassName\" />\n");
        sb.append("<CHECK_COMPILATION />\n");
        sb.append("<EXECUTE_COMMAND command=\"...\" />\n");
        sb.append("<GIT_ADD_NEW />\n\n");

        sb.append("Java rules:\n");
        sb.append("· Java only — never Go, Python, TypeScript.\n");
        sb.append("· Tests → src/test/java/plugin/ · package plugin · JUnit 5 + Mockito.\n");
        sb.append("· Use real constructors and method names from the source — never invent.\n");
        sb.append("· Never unit-test IntelliJ platform classes (ChatPanel, ChatToolWindowFactory).\n");
        sb.append("· Protected test files (do not delete): ChatMessageTest, PluginSettingsTest, LocalLLMClientTest.\n\n");

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

    // -------------------------------------------------------------------------
    // Compile-error fix prompt
    // -------------------------------------------------------------------------

    public static String buildCompileFixPrompt(String errorOutput,
                                                String pathHint,
                                                String sourceContext,
                                                int attempt,
                                                boolean sameError) {
        StringBuilder sb = new StringBuilder(512);

        if (sameError) {
            sb.append("⚠ SAME error persisted after previous fix. Try a different approach.\n\n");
        }
        sb.append("Compile failed (attempt ").append(attempt).append("). Fix ALL errors now.\n");
        if (!pathHint.isBlank()) sb.append(pathHint).append("\n");
        sb.append("\nBuild output:\n").append(cap(errorOutput, 2000));
        if (!sourceContext.isBlank()) {
            sb.append("\n\nRelevant source:\n").append(cap(sourceContext, 3000));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Test-failure fix prompt
    // -------------------------------------------------------------------------

    public static String buildTestFixPrompt(String errorOutput,
                                             String pathHint,
                                             String sourceContext,
                                             int attempt,
                                             boolean sameError) {
        StringBuilder sb = new StringBuilder(512);

        if (sameError) {
            sb.append("⚠ Same test failure persisted. Try a different fix strategy.\n\n");
        }
        sb.append("Tests failed (attempt ").append(attempt).append("). Fix the failing tests.\n");
        if (!pathHint.isBlank()) sb.append(pathHint).append("\n");
        sb.append("\nTest output:\n").append(cap(errorOutput, 2000));
        if (!sourceContext.isBlank()) {
            sb.append("\n\nRelevant source:\n").append(cap(sourceContext, 3000));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String cap(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }
}
