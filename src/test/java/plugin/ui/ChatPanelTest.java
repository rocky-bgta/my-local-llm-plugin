package plugin.ui;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class ChatPanelTest {

    private ChatPanel chatPanel;

    @BeforeEach
    public void setUp() {
        // Initialize the ChatPanel instance before each test
        Project mockProject = mock(Project.class);
        chatPanel = new ChatPanel(mockProject);
    }

    @Test
    public void testChatPaneInitialization() {
        assertNotNull(chatPanel.getChatPane(), "Chat pane should be initialized");
    }

    @Test
    public void testPromptAreaInitialization() {
        assertNotNull(chatPanel.getPromptArea(), "Prompt area should be initialized");
    }

    @Test
    public void testSendBtnInitialization() {
        JButton sendBtn = chatPanel.getSendBtn();
        assertEquals("Send", sendBtn.getText(), "Send button text should be 'Send'");
    }

    @Test
    public void testStopBtnInitialization() {
        JButton stopBtn = chatPanel.getStopBtn();
        assertNotNull(stopBtn, "Stop button should be initialized");
    }

    @Test
    public void testSpinnerInitialization() {
        JProgressBar spinner = chatPanel.getSpinner();
        assertNotNull(spinner, "Spinner should be initialized");
        assertEquals(0, spinner.getValue(), "Spinner value should be 0 initially");
    }

    @Test
    public void testBlinkTimerInitialization() {
        assertNotNull(chatPanel.getBlinkTimer(), "Blink timer should be initialized");
    }

    @Test
    public void testTitleLabelInitialization() {
        assertEquals("  New Chat", chatPanel.getTitleLabel().getText(), "Title label text should be 'New Chat'");
        assertFalse(chatPanel.isTitleGenerated(), "titleGenerated flag should be false initially");
    }

    @Test
    public void testBuildFixAttemptsInitialization() {
        assertEquals(0, chatPanel.getBuildFixAttempts(), "buildFixAttempts should be 0 initially");
    }

    @Test
    public void testHistoryInitialization() {
        assertNotNull(chatPanel.getHistory(), "history list should be initialized");
        assertTrue(chatPanel.getHistory().isEmpty(), "history list should be empty initially");
    }

    @Test
    public void testNewlyCreatedFilesInitialization() {
        assertNotNull(chatPanel.getNewlyCreatedFiles(), "newlyCreatedFiles list should be initialized");
        assertTrue(chatPanel.getNewlyCreatedFiles().isEmpty(), "newlyCreatedFiles list should be empty initially");
    }

    @Test
    public void testStylesInitialization() {
        assertNotNull(chatPanel.getUserRoleStyle());
        assertNotNull(chatPanel.getUserTextStyle());
        assertNotNull(chatPanel.getAssistantRoleStyle());
        assertNotNull(chatPanel.getAssistantTextStyle());
        assertNotNull(chatPanel.getSystemStyle());
        assertNotNull(chatPanel.getCursorStyle());
    }
}
