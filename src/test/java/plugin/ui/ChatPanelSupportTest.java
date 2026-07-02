package plugin.ui;

import org.junit.jupiter.api.Test;
import plugin.llm.model.ChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChatPanelSupportTest {

    @Test
    void extractsBrokenPathsWithoutDuplicates() {
        String output = """
                [ERROR] C:\\repo\\src\\test\\java\\plugin\\ui\\ChatPanelTest.java:[12,8] error
                [ERROR] C:\\repo\\src\\test\\java\\plugin\\ui\\ChatPanelTest.java:[15,8] error
                [ERROR] C:\\repo\\src\\main\\java\\plugin\\ui\\ChatPanel.java:[20,1] error
                [ERROR] C:\\repo\\src\\main\\go\\cmd\\app\\main.go:12:1 error
                [ERROR] C:\\repo\\app\\main.py:8:1 error
                """;

        assertEquals(List.of(
                "src/test/java/plugin/ui/ChatPanelTest.java",
                "src/main/java/plugin/ui/ChatPanel.java",
                "src/main/go/cmd/app/main.go",
                "C:/repo/app/main.py"
        ), ChatPanelSupport.extractBrokenFilePaths(output));
    }

    @Test
    void detectsSimpleSyntaxErrors() {
        assertTrue(ChatPanelSupport.isSimpleSyntaxError("reached end of file while parsing"));
        assertTrue(ChatPanelSupport.isSimpleSyntaxError("';' expected"));
        assertFalse(ChatPanelSupport.isSimpleSyntaxError("cannot find symbol: class Foo"));
    }

    @Test
    void detectsFileOperationIntent() {
        assertTrue(ChatPanelSupport.isFileOpIntent("write tests for ChatPanel"));
        assertTrue(ChatPanelSupport.isFileOpIntent("add class for helper logic"));
        assertFalse(ChatPanelSupport.isFileOpIntent("explain this code"));
    }

    @Test
    void detectsGitAddCreatedFilesIntent() {
        assertTrue(ChatPanelSupport.isGitAddCreatedFilesIntent("add them to git"));
        assertTrue(ChatPanelSupport.isGitAddCreatedFilesIntent("what files were created, add it to git"));
        assertFalse(ChatPanelSupport.isGitAddCreatedFilesIntent("list the files"));
    }

    @Test
    void detectsDockerAndHelmIntent() {
        assertTrue(ChatPanelSupport.isDockerOrHelmIntent("build the Dockerfile"));
        assertTrue(ChatPanelSupport.isDockerOrHelmIntent("deploy the Helm chart"));
        assertFalse(ChatPanelSupport.isDockerOrHelmIntent("review this class"));
    }

    @Test
    void detectsCommitReviewIntent() {
        assertTrue(ChatPanelSupport.isCommitReviewIntent("review this last git commit with the Jira ticket"));
        assertTrue(ChatPanelSupport.isCommitReviewIntent("code review the diff and add reviewer comments"));
        assertFalse(ChatPanelSupport.isCommitReviewIntent("explain this commit message"));
    }

    @Test
    void detectsReadmeIntent() {
        assertTrue(ChatPanelSupport.isReadmeIntent("create a README file for this project"));
        assertTrue(ChatPanelSupport.isReadmeIntent("write project documentation"));
        assertFalse(ChatPanelSupport.isReadmeIntent("fix this bug"));
    }

    @Test
    void detectsProjectStructureIntent() {
        assertTrue(ChatPanelSupport.isProjectStructureIntent("show me project structure"));
        assertTrue(ChatPanelSupport.isProjectStructureIntent("display the directory tree"));
        assertFalse(ChatPanelSupport.isProjectStructureIntent("show me the README"));
    }

    @Test
    void detectsSkillUpdateIntent() {
        assertTrue(ChatPanelSupport.isSkillUpdateIntent("update your skill set"));
        assertTrue(ChatPanelSupport.isSkillUpdateIntent("learn this solution"));
        assertFalse(ChatPanelSupport.isSkillUpdateIntent("remember the file path"));
    }

    @Test
    void recordsOnlyLastTenPrompts() {
        List<String> history = List.of(
                "one", "two", "three", "four", "five",
                "six", "seven", "eight", "nine", "ten"
        );

        List<String> updated = ChatPanelSupport.recordPromptHistory(history, "eleven", 10);

        assertEquals(10, updated.size());
        assertEquals("two", updated.get(0));
        assertEquals("eleven", updated.get(updated.size() - 1));
    }

    @Test
    void navigatesPromptHistoryWithDraftRestoration() {
        List<String> history = List.of("first prompt", "second prompt", "third prompt");

        ChatPanelSupport.PromptHistoryState first = ChatPanelSupport.navigatePromptHistory(
                history, "", -1, "", true);
        assertEquals("third prompt", first.displayedText());
        assertEquals(2, first.index());

        ChatPanelSupport.PromptHistoryState second = ChatPanelSupport.navigatePromptHistory(
                history, first.displayedText(), first.index(), "draft text", true);
        assertEquals("second prompt", second.displayedText());
        assertEquals(1, second.index());

        ChatPanelSupport.PromptHistoryState restore = ChatPanelSupport.navigatePromptHistory(
                history, second.displayedText(), second.index(), "draft text", false);
        assertEquals("third prompt", restore.displayedText());
        assertEquals(2, restore.index());

        ChatPanelSupport.PromptHistoryState backToDraft = ChatPanelSupport.navigatePromptHistory(
                history, restore.displayedText(), restore.index(), "draft text", false);
        assertEquals("draft text", backToDraft.displayedText());
        assertEquals(-1, backToDraft.index());
    }

    @Test
    void splitsTextIntoChunks() {
        assertEquals(List.of("abc", "def", "g"), ChatPanelSupport.splitIntoChunks("abcdefg", 3));
    }

    @Test
    void estimatesTokensFromTextAndMessages() {
        assertEquals(0, ChatPanelSupport.estimateTokens(""));
        assertEquals(2, ChatPanelSupport.estimateTokens("abcdefgh"));
        assertEquals(3, ChatPanelSupport.estimateTokens(List.of(
                new ChatMessage("user", "abcd"),
                new ChatMessage("assistant", "efghij")
        )));
    }

    @Test
    void formatsWorkspaceStatusText() {
        assertEquals(
                "Working on: Gradle / Java | Tokens in/out: ~12 / ~34 (est.)",
                ChatPanelSupport.formatWorkspaceStatus("Gradle / Java", 12, 34)
        );
    }

    @Test
    void trimsConversationHistoryToTokenBudget() {
        List<ChatMessage> history = List.of(
                new ChatMessage("system", "system prompt"),
                new ChatMessage("user", "Retrieved Context block"),
                new ChatMessage("assistant", "ack"),
                new ChatMessage("user", "older message one"),
                new ChatMessage("assistant", "older message two"),
                new ChatMessage("user", "latest message")
        );

        List<ChatMessage> trimmed = ChatPanelSupport.trimConversationHistory(history, 8);

        assertEquals("system", trimmed.get(0).role());
        assertEquals("latest message", trimmed.get(trimmed.size() - 1).content());
        assertTrue(trimmed.size() <= history.size());
    }

    @Test
    void calculatesContextUsagePercent() {
        List<ChatMessage> history = List.of(
                new ChatMessage("system", "system prompt"),
                new ChatMessage("user", "abcdefgh")
        );

        assertEquals(0, ChatPanelSupport.contextUsagePercent(history, 0));
        assertTrue(ChatPanelSupport.contextUsagePercent(history, 100) > 0);
    }
}
