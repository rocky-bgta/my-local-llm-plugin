package plugin.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records mistakes the local LLM makes and exposes them as a prompt section so future
 * conversations automatically inherit learned rules. Each unique mistake is written once
 * to .llm_corrections.md in the project root.
 */
public class LLMCorrectionsUtil {

    static final String CORRECTIONS_FILE = ".llm_corrections.md";

    public static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("no-chatpanel-test",
                "NEVER write ChatPanelTest.java or ChatToolWindowFactoryTest.java. These require " +
                "IntelliJ Platform (Project/ApplicationManager) and cannot be unit-tested outside the IDE. " +
                "Testable classes: plugin.llm.model.ChatMessage, plugin.settings.PluginSettings, " +
                "plugin.llm.LocalLLMClient.");
        RULES.put("no-placeholder-path",
                "ALWAYS use real project paths in file operation tags. NEVER use placeholder paths " +
                "such as \"path/to/file\", \"your/path\", or \"example/path\". " +
                "Java test files go in src/test/java/plugin/. Source files go in src/main/java/plugin/.");
        RULES.put("use-xml-tags",
                "In EDITING mode, ALWAYS use <MODIFY_FILE> or <CREATE_FILE> XML tags to write files. " +
                "Markdown ``` code blocks and plain text descriptions do NOT write to disk.");
        RULES.put("no-tree-for-tests",
                "When asked to write tests, NEVER inspect the directory tree with tree/ls/dir or " +
                "custom commands. Use the retrieved source context and write a concrete JUnit 5 test file directly. " +
                "If no class is named, choose the most relevant source class from context and create its test file.");
        RULES.put("junit5-only",
                "ALWAYS use JUnit 5 syntax (import org.junit.jupiter.api.Test). NEVER use JUnit 4 " +
                "annotations like @Test(expected=...) or @RunWith. " +
                "Use assertThrows(Exception.class, () -> ...) for exception tests.");
        RULES.put("uppercase-xml-tags",
                "File operation XML tags MUST be UPPERCASE: <MODIFY_FILE>, <CREATE_FILE>, <DELETE_FILE>. " +
                "Never use lowercase (<modify_file>) or mixed-case (<Modify_File>).");
        RULES.put("correct-test-package",
                "Java unit test files MUST be placed in src/test/java/plugin/ (package plugin). " +
                "Do NOT place tests in sub-packages like plugin.llm, plugin.settings, or plugin.util.");
    }

    public static String loadCorrectionsForPrompt(String projectBasePath) {
        if (projectBasePath == null) return "";
        Path file = Paths.get(projectBasePath, CORRECTIONS_FILE);
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void recordMistake(String projectBasePath, String ruleKey) {
        if (projectBasePath == null || !RULES.containsKey(ruleKey)) return;
        Path file = Paths.get(projectBasePath, CORRECTIONS_FILE);
        try {
            String existing = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : "";

            if (existing.contains("[" + ruleKey + "]")) return;

            String header = "# LLM Learned Corrections\n\n" +
                    "These rules were automatically learned from past mistakes in this project. " +
                    "The LLM MUST follow ALL rules listed here without exception.\n\n";

            String newRule = "- **[" + ruleKey + "]** (" + LocalDate.now() + "): " +
                    RULES.get(ruleKey) + "\n";

            String updated = existing.isEmpty() ? header + newRule : existing + newRule;

            Files.writeString(file, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // best-effort — corrections are advisory, not critical
        }
    }
}
