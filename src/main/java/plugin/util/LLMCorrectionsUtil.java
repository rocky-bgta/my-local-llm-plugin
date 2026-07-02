package plugin.util;

import plugin.memory.InternalWorkspaceStore;

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
 * to .local-llm/corrections/llm-corrections.md.
 */
public class LLMCorrectionsUtil {

    public static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("no-chatpanel-test",
                "NEVER write direct unit tests for ChatPanelTest.java or ChatToolWindowFactoryTest.java. " +
                "Instead, write tests for extracted helper logic such as plugin.ui.ChatPanelSupport. " +
                "Keep IntelliJ Platform UI wrappers thin and test the pure logic behind them.");
        RULES.put("no-placeholder-path",
                "ALWAYS use real project paths in file operation tags. NEVER use placeholder paths " +
                "such as \"path/to/file\", \"your/path\", or \"example/path\". " +
                "Use the project's actual source/test layout for the detected language.");
        RULES.put("use-xml-tags",
                "In EDITING mode, ALWAYS use <MODIFY_FILE> or <CREATE_FILE> XML tags to write files. " +
                "Markdown ``` code blocks and plain text descriptions do NOT write to disk.");
        RULES.put("no-tree-for-tests",
                "When asked to write tests, NEVER inspect the directory tree with tree/ls/dir or " +
                "custom commands. Use the retrieved source context and write a concrete JUnit 5 test file directly. " +
                "If no class is named, choose the most relevant source class from context and create its test file.");
        RULES.put("windows-shell-notes",
                "This project is running on Windows PowerShell. Do NOT use Unix-only commands like grep, find, sed, awk, xargs, head, tail, or cat. " +
                "Use PowerShell equivalents such as Get-ChildItem, Select-String, Get-Content, and Select-Object.");
        RULES.put("junit5-only",
                "ALWAYS use JUnit 5 syntax (import org.junit.jupiter.api.Test). NEVER use JUnit 4 " +
                "annotations like @Test(expected=...) or @RunWith. " +
                "Use assertThrows(Exception.class, () -> ...) for exception tests.");
        RULES.put("uppercase-xml-tags",
                "File operation XML tags MUST be UPPERCASE: <MODIFY_FILE>, <CREATE_FILE>, <DELETE_FILE>. " +
                "Never use lowercase (<modify_file>) or mixed-case (<Modify_File>).");
        RULES.put("correct-test-package",
                "Test files MUST follow the test layout conventions of the detected language and project. " +
                "Keep the test path aligned with the source file/module/package structure when the language requires it.");
    }

    public static String loadCorrectionsForPrompt(String projectBasePath) {
        if (projectBasePath == null) return "";
        Path file = InternalWorkspaceStore.correctionsFile(projectBasePath);
        Path legacyFile = InternalWorkspaceStore.legacyRoot(projectBasePath).resolve(".llm_corrections.md");
        Path sourceFile = Files.exists(file) ? file : legacyFile;
        if (!Files.exists(sourceFile)) return "";
        try {
            return Files.readString(sourceFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void recordMistake(String projectBasePath, String ruleKey) {
        if (projectBasePath == null || !RULES.containsKey(ruleKey)) return;
        Path file = InternalWorkspaceStore.correctionsFile(projectBasePath);
        Path legacyFile = InternalWorkspaceStore.legacyRoot(projectBasePath).resolve(".llm_corrections.md");
        try {
            Files.createDirectories(file.getParent());
            Path sourceFile = Files.exists(file) ? file : legacyFile;
            String existing = Files.exists(sourceFile)
                    ? Files.readString(sourceFile, StandardCharsets.UTF_8) : "";

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
