package plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import plugin.llm.LocalLLMClient;
import plugin.llm.model.ChatMessage;
import plugin.settings.PluginSettings;
import plugin.util.BuildUtil;
import plugin.util.FileOperationUtil;
import plugin.util.GitUtil;
import plugin.util.ProjectContextUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class ChatPanel {

    private final Project project;
    final JPanel root;

    // Mode
    private String mode = "PLANNING";
    private JComboBox<String> modeCombo;

    // Dynamic tab/toolbar title
    private JLabel  titleLabel;
    private Content tabContent;
    private boolean titleGenerated = false;

    // Build auto-fix loop counter (reset per user message, max 2 fix attempts)
    private int buildFixAttempts = 0;

    // Chat display
    private JTextPane      chatPane;
    private StyledDocument chatDoc;

    // Input
    private JTextArea    promptArea;
    private JButton      sendBtn;
    private JButton      stopBtn;
    private JProgressBar spinner;

    private volatile boolean isGenerating = false;
    private volatile boolean stopRequested = false;
    private Thread currentChatThread;

    // Streaming state (all accessed on EDT only)
    private final Timer         blinkTimer;
    private       boolean       streaming       = false;
    private       boolean       cursorOn        = false;
    private final StringBuilder assistantBuffer = new StringBuilder();

    // Conversation history
    private final List<ChatMessage> history = new ArrayList<>();

    // Track newly created files for Git
    private final List<String> newlyCreatedFiles = new ArrayList<>();

    // Text styles
    private Style userRoleStyle;
    private Style userTextStyle;
    private Style assistantRoleStyle;
    private Style assistantTextStyle;
    private Style systemStyle;
    private Style cursorStyle;

    public ChatPanel(@NotNull Project project) {
        this.project = project;
        blinkTimer = new Timer(500, e -> toggleBlink());
        blinkTimer.setRepeats(true);

        root = new JPanel(new BorderLayout());
        root.add(buildToolbar(),    BorderLayout.NORTH);
        root.add(buildChatArea(),   BorderLayout.CENTER);
        root.add(buildInputPanel(), BorderLayout.SOUTH);

        // Ctrl+Shift+N = New Chat from anywhere in the panel
        javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_N,
                java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        root.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, "newChat");
        root.getActionMap().put("newChat", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { clearConversation(); }
        });
    }

    public JTextPane getChatPane() { return chatPane; }
    public JTextArea getPromptArea() { return promptArea; }
    public JButton getSendBtn() { return sendBtn; }
    public JButton getStopBtn() { return stopBtn; }
    public JProgressBar getSpinner() { return spinner; }
    public Timer getBlinkTimer() { return blinkTimer; }
    public JLabel getTitleLabel() { return titleLabel; }
    public boolean isTitleGenerated() { return titleGenerated; }
    public int getBuildFixAttempts() { return buildFixAttempts; }
    public List<ChatMessage> getHistory() { return history; }
    public List<String> getNewlyCreatedFiles() { return newlyCreatedFiles; }
    public Style getUserRoleStyle() { return userRoleStyle; }
    public Style getUserTextStyle() { return userTextStyle; }
    public Style getAssistantRoleStyle() { return assistantRoleStyle; }
    public Style getAssistantTextStyle() { return assistantTextStyle; }
    public Style getSystemStyle() { return systemStyle; }
    public Style getCursorStyle() { return cursorStyle; }

    // -------------------------------------------------------------------------
    // Toolbar — title on the left, gear on the right
    // -------------------------------------------------------------------------

    JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(0, 0, 2, 8)
        ));
        bar.setPreferredSize(new Dimension(0, 36));

        titleLabel = new JLabel("  New Chat");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        bar.add(titleLabel, BorderLayout.WEST);

        modeCombo = new JComboBox<>(new String[]{"PLANNING", "EDITING", "BYPASS"});
        modeCombo.setSelectedItem("PLANNING");
        modeCombo.addActionListener(e -> mode = (String) modeCombo.getSelectedItem());

        JButton clearBtn = new JButton("New Chat");
        clearBtn.setToolTipText("Clear chat display and reset conversation context (Ctrl+Shift+N)");
        clearBtn.addActionListener(e -> clearConversation());

        JButton gearBtn = new JButton(AllIcons.General.Settings);
        gearBtn.setBorderPainted(false);
        gearBtn.setContentAreaFilled(false);
        gearBtn.setFocusPainted(false);
        gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearBtn.setToolTipText("Settings");
        gearBtn.addActionListener(e -> showSettingsDialog());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(new JLabel("Mode:"));
        right.add(modeCombo);
        right.add(clearBtn);
        right.add(gearBtn);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // -------------------------------------------------------------------------
    // Settings dialog
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        Window parent = SwingUtilities.getWindowAncestor(root);
        JDialog dialog = new JDialog(parent, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.add(buildSettingsForm(dialog));
        dialog.pack();
        dialog.setMinimumSize(new Dimension(380, dialog.getHeight()));
        dialog.setLocationRelativeTo(root);
        dialog.setResizable(false);
        dialog.setVisible(true);
    }

    private JPanel buildSettingsForm(JDialog dialog) {
        PluginSettings s = PluginSettings.getInstance();

        JTextField        endpointField = new JTextField(s.getEndpoint(), 28);
        JComboBox<String> modelCombo    = new JComboBox<>();
        JLabel            statusLabel   = new JLabel(" ");
        JButton           refreshBtn    = new JButton("Refresh Models");
        JButton           saveBtn       = new JButton("Save");
        JCheckBox         contextCheck  = new JCheckBox("Include full file contents in context", s.isIncludeFullContext());

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        if (s.getModel() != null && !s.getModel().isBlank()) {
            modelCombo.addItem(s.getModel());
            modelCombo.setSelectedItem(s.getModel());
        }

        refreshBtn.addActionListener(e -> {
            String ep = endpointField.getText().trim();
            refreshBtn.setEnabled(false);
            statusLabel.setText("Fetching models…");
            daemon(() -> {
                try {
                    List<String> models = new LocalLLMClient(ep).fetchModels();
                    SwingUtilities.invokeLater(() -> {
                        modelCombo.removeAllItems();
                        models.forEach(modelCombo::addItem);
                        if (s.getModel() != null && models.contains(s.getModel())) {
                            modelCombo.setSelectedItem(s.getModel());
                        }
                        statusLabel.setText("Loaded " + models.size() + " model(s).");
                        refreshBtn.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Error: " + ex.getMessage());
                        refreshBtn.setEnabled(true);
                    });
                }
            });
        });

        saveBtn.addActionListener(e -> {
            s.setEndpoint(endpointField.getText().trim());
            s.setIncludeFullContext(contextCheck.isSelected());
            Object sel = modelCombo.getSelectedItem();
            if (sel != null && !sel.toString().isBlank()) s.setModel(sel.toString());
            dialog.dispose();
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(14, 18, 14, 18));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(5, 0, 5, 10);

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill      = GridBagConstraints.HORIZONTAL;
        fc.weightx   = 1.0;
        fc.gridwidth = GridBagConstraints.REMAINDER;
        fc.insets    = new Insets(5, 0, 5, 0);

        addFormRow(form, "Endpoint:", endpointField, lc, fc, 0);
        fc.gridy = 1; form.add(refreshBtn,  fc);
        addFormRow(form, "Model:",    modelCombo,    lc, fc, 2);
        fc.gridy = 3; form.add(contextCheck, fc);
        fc.gridy = 4; form.add(saveBtn,     fc);
        fc.gridy = 5; form.add(statusLabel, fc);

        return form;
    }

    private static void addFormRow(JPanel p, String label, JComponent field,
                                   GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridx = 0; lc.gridy = row; p.add(new JLabel(label), lc);
        fc.gridx = 1; fc.gridy = row; p.add(field, fc);
    }

    // -------------------------------------------------------------------------
    // Chat area — styled JTextPane, always-on scroll bar
    // -------------------------------------------------------------------------

    JScrollPane buildChatArea() {
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setMargin(new Insets(10, 12, 10, 12));
        chatPane.setBackground(UIManager.getColor("Editor.background"));
        chatDoc  = chatPane.getStyledDocument();
        initStyles();

        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void initStyles() {
        Style base = StyleContext.getDefaultStyleContext()
                                 .getStyle(StyleContext.DEFAULT_STYLE);

        userRoleStyle = chatPane.addStyle("userRole", base);
        StyleConstants.setForeground(userRoleStyle, new Color(0x4EC9B0));
        StyleConstants.setBold(userRoleStyle, true);
        StyleConstants.setFontSize(userRoleStyle, 16);

        userTextStyle = chatPane.addStyle("userText", base);
        StyleConstants.setForeground(userTextStyle, new Color(0xD4D4D4));
        StyleConstants.setFontFamily(userTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(userTextStyle, 18);

        assistantRoleStyle = chatPane.addStyle("assistantRole", base);
        StyleConstants.setForeground(assistantRoleStyle, new Color(0x569CD6));
        StyleConstants.setBold(assistantRoleStyle, true);
        StyleConstants.setFontSize(assistantRoleStyle, 16);

        assistantTextStyle = chatPane.addStyle("assistantText", base);
        StyleConstants.setForeground(assistantTextStyle, new Color(0xE8E8E8));
        StyleConstants.setFontFamily(assistantTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(assistantTextStyle, 18);

        systemStyle = chatPane.addStyle("system", base);
        StyleConstants.setForeground(systemStyle, new Color(0xCE9178));
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontSize(systemStyle, 16);

        cursorStyle = chatPane.addStyle("cursor", base);
        StyleConstants.setForeground(cursorStyle, new Color(0x569CD6));
        StyleConstants.setBold(cursorStyle, true);
    }

    // -------------------------------------------------------------------------
    // Input panel — 4-row textarea, Send bottom-right, Clear bottom-left
    // -------------------------------------------------------------------------

    JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(8, 12, 12, 12)
        ));

        promptArea = new JTextArea(4, 0);
        promptArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setMargin(new Insets(6, 8, 6, 8));
        // Enter = send   |   Shift+Enter = newline
        promptArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        promptArea.insert("\n", promptArea.getCaretPosition());
                        e.consume();
                    } else {
                        e.consume();
                        sendMessage();
                    }
                }
            }
        });

        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        sendBtn = new JButton("Send");
        sendBtn.setPreferredSize(new Dimension(80, 28));
        sendBtn.addActionListener(e -> sendMessage());

        stopBtn = new JButton("Stop", AllIcons.Actions.Suspend);
        stopBtn.setPreferredSize(new Dimension(80, 28));
        stopBtn.setVisible(false);
        stopBtn.addActionListener(e -> {
            stopRequested = true;
            if (currentChatThread != null) {
                currentChatThread.interrupt();
            }
            appendSystemMessage("Interrupted by user.");
            setLoading(false);
        });

        JButton clearBtn = new JButton("New Chat");
        clearBtn.setToolTipText("Clear chat display and reset conversation context (Ctrl+Shift+N)");
        clearBtn.addActionListener(e -> clearConversation());

        spinner = new JProgressBar();
        spinner.setIndeterminate(false);
        spinner.setPreferredSize(new Dimension(80, 14));
        spinner.setVisible(false);

        JPanel ctrlRow  = new JPanel(new BorderLayout(4, 0));
        JPanel leftCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftCtrl.add(clearBtn);
        leftCtrl.add(spinner);
        ctrlRow.add(leftCtrl, BorderLayout.WEST);
        JPanel rightCtrl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightCtrl.add(stopBtn);
        rightCtrl.add(sendBtn);
        ctrlRow.add(rightCtrl, BorderLayout.EAST);

        panel.add(promptScroll, BorderLayout.CENTER);
        panel.add(ctrlRow,      BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Send — streams response token by token
    // -------------------------------------------------------------------------

    private void sendMessage() {
        String text = promptArea.getText().trim();
        if (text.isEmpty()) return;
        newlyCreatedFiles.clear();
        buildFixAttempts = 0;

        PluginSettings s  = PluginSettings.getInstance();
        String model      = s.getModel();
        String endpoint   = s.getEndpoint();

        if (model == null || model.isBlank()) {
            appendSystemMessage("No model configured — click ⚙ to open Settings.");
            return;
        }

        // Add system message with context if history is empty or it's a new conversation
        if (history.isEmpty()) {
            String context = ProjectContextUtil.getProjectContext(project, s.isIncludeFullContext());
            String corrections = plugin.util.LLMCorrectionsUtil.loadCorrectionsForPrompt(project.getBasePath());
            String correctionSection = corrections.isEmpty() ? "" :
                    "\n\n" + corrections + "\n\n";

            String systemInstructions = "You are a specialized AI coding assistant for this project. " +
                    "I have provided you with the project structure and file contents below to help you understand the codebase." +
                    correctionSection +
                    "CRITICAL — PROJECT LANGUAGE: This is a Java 21 Maven project. " +
                    "You MUST write ALL code in Java. " +
                    "NEVER generate Go, Python, JavaScript, TypeScript, Kotlin, or any other language. " +
                    "The build tool is Maven (pom.xml) — never use go build, go test, gradle, npm, or cargo.\n" +
                    "Current Mode: " + mode + "\n" +
                    "When in PLANNING mode, discuss the task and outline the steps. Do not use file operation tags.\n" +
                    "If you believe the user wants to make changes to files, suggest they switch to EDITING mode.\n" +
                    "EDITING MODE — MANDATORY FILE OPERATION RULES:\n" +
                    "In EDITING mode you MUST write files using the XML tags below. There is NO other way to write to disk.\n" +
                    "WRONG — this will NOT write the file (do not do this):\n" +
                    "```markdown\n...file content...\n```\n" +
                    "CORRECT — this WILL write the file (always do this instead):\n" +
                    "<MODIFY_FILE path=\"src/main/java/plugin/ui/ChatPanel.java\">complete new file content here</MODIFY_FILE>\n" +
                    "All available tags (use real project paths — NEVER use path/to/file or other placeholders):\n" +
                    "<MODIFY_FILE path=\"src/main/java/plugin/ui/ChatPanel.java\">complete new file content</MODIFY_FILE>\n" +
                    "<CREATE_FILE path=\"src/test/java/plugin/ChatMessageTest.java\">complete file content</CREATE_FILE>\n" +
                    "<CREATE_FOLDER path=\"src/main/java/plugin/newpackage\" />\n" +
                    "<DELETE_FILE path=\"src/test/java/plugin/OldTest.java\" />\n" +
                    "<DELETE_FOLDER path=\"src/main/java/plugin/oldpackage\" />\n" +
                    "<RUN_TESTS />\n" +
                    "<RUN_TESTS test=\"ClassName\" />\n" +
                    "<CHECK_COMPILATION />\n" +
                    "<EXECUTE_COMMAND command=\"your-command-here\" />\n" +
                    "<GIT_ADD_NEW />\n" +
                    "Rules:\n" +
                    "1. Output the raw XML tag directly — never wrap it in ``` fences.\n" +
                    "2. Always include the COMPLETE file content inside the tag — never truncate or summarize.\n" +
                    "3. You may add a brief explanation AFTER the closing XML tag.\n" +
                    "4. If you use a ``` code block for file content, the file will NOT be changed.\n" +
                    "5. To run all project tests, use the <RUN_TESTS /> tag.\n" +
                    "6. To run a specific test case, use <RUN_TESTS test=\"ClassName\" /> (e.g., <RUN_TESTS test=\"ChatMessageTest\" />).\n" +
                    "7. To check if the project compiles without running tests, use <CHECK_COMPILATION />. This will automatically detect the build system (Maven, Gradle, Go, etc.) and run the appropriate command.\n" +
                    "8. For non-Java projects or if auto-detection fails, use <EXECUTE_COMMAND command=\"...\" /> to run build or test commands (e.g., <EXECUTE_COMMAND command=\"go build\" />).\n" +
                    "9. To add ALL newly created files from the current task to Git, use <GIT_ADD_NEW />.\n" +
                    "10. Tags can be combined (e.g., CREATE_FILE and then GIT_ADD_NEW).\n" +
                    "Execute tasks one by one and inform the user of your progress.\n" +
                    "JAVA TEST WRITING RULES — follow these whenever you generate or modify a Java test file:\n" +
                    "0. LANGUAGE: This project is Java 21 Maven. Test files MUST be written in Java using JUnit 5. " +
                    "NEVER write test code in Go (no 'func Test', no 'testing.T'), Python, or any other language.\n" +
                    "1. READ THE SOURCE FILE FIRST. Before writing any test, read the actual class file to learn its real package, constructor signatures, method names, and return types. Never assume.\n" +
                    "2. ALWAYS include all Java import statements at the top of the test file:\n" +
                    "   - import org.junit.jupiter.api.Test;\n" +
                    "   - import org.junit.jupiter.api.BeforeEach; (if used)\n" +
                    "   - import static org.junit.jupiter.api.Assertions.*;\n" +
                    "   - import <exact.package.ClassName>; for every class used in the test\n" +
                    "   - import java.util.List; / import java.util.Map; etc. for any JDK type used\n" +
                    "3. USE THE REAL CONSTRUCTOR. If the constructor requires arguments (e.g. LocalLLMClient(String baseUrl)), pass them. Never call new LocalLLMClient() if no no-arg constructor exists.\n" +
                    "4. USE REAL METHOD NAMES. Only call methods that actually exist on the class. Do not invent methods like getSetting(String key) or sendMessage(ChatMessage). Verify by reading the source file.\n" +
                    "5. RECORD FIELD ORDER. Java records expose fields in declaration order. ChatMessage is defined as record ChatMessage(String role, String content) — so new ChatMessage(\"user\", \"Hello\") is correct; new ChatMessage(\"Hello\", \"user\") is WRONG.\n" +
                    "6. DO NOT UNIT TEST IntelliJ PLATFORM CLASSES. plugin.ui.ChatPanel requires a live com.intellij.openapi.project.Project instance and cannot be unit-tested outside the IDE. The same applies to any class in plugin.ui or plugin.toolwindow. Testable classes are: plugin.llm.model.ChatMessage, plugin.settings.PluginSettings, plugin.llm.LocalLLMClient.\n" +
                    "   If you are asked to FIX compilation errors in ChatPanelTest or any other IntelliJ-platform-dependent test file, do NOT attempt to fix the errors — they are unfixable without a running IDE. Instead:\n" +
                    "   a) Delete the broken file: <DELETE_FILE path=\"src/test/java/plugin/ui/ChatPanelTest.java\" />\n" +
                    "   b) Then write correct tests for a testable class (ChatMessage, PluginSettings, or LocalLLMClient) using <CREATE_FILE path=\"src/test/java/plugin/FooTest.java\">.\n" +
                    "7. TEST ONLY WHAT IS TESTABLE. For classes that make network calls (like LocalLLMClient), test construction and that network errors throw exceptions — do not try to assert on live server responses.\n" +
                    "8. CORRECT FILE PATHS FOR TESTS. Java test files MUST go in src/test/java/ mirroring the package. For this project all tests go in src/test/java/plugin/ — for example src/test/java/plugin/ChatMessageTest.java. NEVER use placeholder paths like path/to/file.\n" +
                    "9. CREATE_FILE vs MODIFY_FILE. Use <CREATE_FILE path=\"src/test/java/plugin/FooTest.java\"> for test files that do not yet exist. Use <MODIFY_FILE> only to update a file that already exists.\n" +
                    "10. PROTECTED TEST FILES. The following test files already exist and are correct — do NOT delete them, do NOT move them, do NOT change their package or class name:\n" +
                    "    src/test/java/plugin/ChatMessageTest.java\n" +
                    "    src/test/java/plugin/PluginSettingsTest.java\n" +
                    "    src/test/java/plugin/LocalLLMClientTest.java\n" +
                    "    You may only MODIFY their content via <MODIFY_FILE> with valid JUnit 5 content.\n" +
                    "When in BYPASS mode, ignore file operations and behave like a general assistant.\n" +
                    "PLANNING mode is for discussion and outlining steps. File modification tags are ignored in this mode, but test execution and Git operations are allowed.\n" +
                    "EDITING mode is required to write files to disk or perform delete operations.\n" +
                    "Tests, compilation checks, custom commands, and Git operations can be run in ANY mode using the respective tags.\n" +
                    "Maintain the session context until the user says to discard it.\n" +
                    "If the user asks about the project structure or specific files, use the provided context to answer. " +
                    "Always refer to the 'Current Project Structure' section for the complete file hierarchy. " +
                    "If a file is not listed there, it does not exist in the project.\n" +
                    "When asked for the project structure, provide ONLY the visual tree representation (using ├──, └──, │) from the 'Current Project Structure' section. " +
                    "DO NOT include file contents, headers like '--- CONTENT START ---', or any additional text within the tree code block.";
            
            history.add(new ChatMessage("system", systemInstructions));

            // System prompt + context
            if (context.length() > 6000) {
                List<String> chunks = ProjectContextUtil.splitIntoChunks(context, 6000);
                for (int i = 0; i < chunks.size(); i++) {
                    history.add(new ChatMessage("user", "Project Context (Part " + (i + 1) + "/" + chunks.size() + "):\n" + chunks.get(i)));
                    history.add(new ChatMessage("assistant", "Received context part " + (i + 1) + ". Please continue."));
                }
            } else {
                history.add(new ChatMessage("user", "Project Context:\n" + context));
                history.add(new ChatMessage("assistant", "Received project context. How can I help you today?"));
            }
        } else {
            // Update mode in system message if it already exists
            ChatMessage first = history.get(0);
            if ("system".equals(first.role())) {
                String updatedSystemPrompt = first.content().replaceFirst("Current Mode: (PLANNING|EDITING|BYPASS)", "Current Mode: " + mode);
                history.set(0, new ChatMessage("system", updatedSystemPrompt));
            }
        }

        appendUserMessage(text);
        
        // Split large user message into chunks if necessary (max 6000 chars per part)
        if (text.length() > 6000) {
            List<String> chunks = ProjectContextUtil.splitIntoChunks(text, 6000);
            for (int i = 0; i < chunks.size() - 1; i++) {
                history.add(new ChatMessage("user", "Message Part " + (i + 1) + "/" + chunks.size() + ":\n" + chunks.get(i)));
                history.add(new ChatMessage("assistant", "Part " + (i + 1) + " received. Please send the next part."));
            }
            history.add(new ChatMessage("user", "Final Part " + chunks.size() + "/" + chunks.size() + ":\n" + chunks.get(chunks.size() - 1)));
        } else {
            history.add(new ChatMessage("user", text));
        }
        
        promptArea.setText("");

        beginAssistantMessage();
        blinkTimer.start();
        setLoading(true);

        streamAndHandle(model, endpoint, text, true);
    }

    private void streamAndHandle(String model, String endpoint, String userText, boolean canRetry) {
        List<ChatMessage> snapshot = new ArrayList<>(history);
        // History management: preserve system prompt, initial project context, and recent conversation
        if (snapshot.size() > 30) {
            List<ChatMessage> trimmed = new ArrayList<>();
            // 1. Always keep the system prompt (instruction)
            trimmed.add(snapshot.get(0));
            
            // 2. Keep all project context messages (crucial for codebase understanding)
            // They are usually sent at the very beginning after the system prompt
            int lastContextIndex = 0;
            for (int i = 1; i < snapshot.size(); i++) {
                String content = snapshot.get(i).content();
                if (content != null && (content.contains("Project Context") || content.contains("Received context part") || content.contains("Received project context"))) {
                    trimmed.add(snapshot.get(i));
                    lastContextIndex = i;
                }
            }
            
            // 3. Keep the most recent messages for conversation continuity
            int recentCount = 20;
            int startOfRecent = Math.max(lastContextIndex + 1, snapshot.size() - recentCount);
            for (int i = startOfRecent; i < snapshot.size(); i++) {
                trimmed.add(snapshot.get(i));
            }
            snapshot = trimmed;
        }

        List<ChatMessage> finalSnapshot = snapshot;
        currentChatThread = new Thread(() -> {
            try {
                stopRequested = false;
                new LocalLLMClient(endpoint).streamChat(model, finalSnapshot,
                        token -> {
                            if (stopRequested) throw new RuntimeException("STREAM_INTERRUPTED");
                            SwingUtilities.invokeLater(() -> appendToken(token));
                        });
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    String fullResponse = assistantBuffer.toString();
                    history.add(new ChatMessage("assistant", fullResponse));

                    if (!titleGenerated && userText != null) {
                        titleGenerated = true;
                        generateTitle(userText);
                    }

                    if ("EDITING".equals(mode)) {
                        String responseLower = fullResponse.toLowerCase();
                        boolean hasFileOps = fullResponse.contains("<CREATE_FILE") ||
                                             fullResponse.contains("<MODIFY_FILE") ||
                                             fullResponse.contains("<CREATE_FOLDER") ||
                                             fullResponse.contains("<DELETE_FILE") ||
                                             fullResponse.contains("<DELETE_FOLDER");
                        // Detect wrong-case tags (e.g. <modify_file> instead of <MODIFY_FILE>)
                        boolean hasMalformedTags = !hasFileOps && (
                                responseLower.contains("<create_file") || responseLower.contains("<modify_file") ||
                                responseLower.contains("<create_folder") || responseLower.contains("<delete_file") ||
                                responseLower.contains("<delete_folder"));
                        boolean hasTests = fullResponse.contains("<RUN_TESTS") || fullResponse.contains("<CHECK_COMPILATION");
                        boolean hasCustomCommand = fullResponse.contains("<EXECUTE_COMMAND");
                        
                        if (canRetry && hasMalformedTags) {
                            recordMistakes(java.util.List.of("uppercase-xml-tags"));
                            appendSystemMessage("⚠ XML tags detected with wrong case — tags must be UPPERCASE (e.g. <MODIFY_FILE>, not <modify_file>). Auto-correcting…");
                            history.add(new ChatMessage("user",
                                    "CORRECTION REQUIRED: You used lowercase XML tags. All file operation tags must be UPPERCASE:\n" +
                                    "<MODIFY_FILE path=\"src/test/java/plugin/ChatMessageTest.java\">complete content</MODIFY_FILE>\n" +
                                    "<CREATE_FILE path=\"src/test/java/plugin/NewTest.java\">complete content</CREATE_FILE>\n" +
                                    "Re-send your response using UPPERCASE tags with the complete file content inside."));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false);
                        } else if (hasFileOps || hasTests || hasCustomCommand) {
                            FileOperationUtil.FileOpResult opResult = FileOperationUtil.processFileOperations(project, fullResponse);
                            if (opResult.createdFiles != null) {
                                newlyCreatedFiles.addAll(opResult.createdFiles);
                            }
                            if (opResult.warnings != null && !opResult.warnings.isEmpty()) {
                                opResult.warnings.forEach(this::appendSystemMessage);
                                injectBlockedWriteFeedback(opResult.warnings);
                            }
                            recordMistakes(opResult.mistakeKeys);
                            if (opResult.runTests) {
                                if (opResult.testName != null) {
                                    appendSystemMessage("Test execution requested for " + opResult.testName + ". Running tests…");
                                    scheduleTestRun(model, endpoint, opResult.testName);
                                } else {
                                    appendSystemMessage("Test execution requested. Running tests…");
                                    scheduleTestRun(model, endpoint, null);
                                }
                            } else if (opResult.checkCompilation) {
                                appendSystemMessage("Compilation check requested. Running build…");
                                scheduleBuildCheck(model, endpoint);
                            } else if (opResult.customCommand != null) {
                                appendSystemMessage("Custom command execution requested: " + opResult.customCommand + ". Running…");
                                scheduleCustomCommand(opResult.customCommand);
                            } else if (hasFileOps) {
                                appendSystemMessage("File operations applied. Running build check…");
                                scheduleBuildCheck(model, endpoint);
                            }

                            if (fullResponse.contains("<GIT_ADD_NEW")) {
                                scheduleGitAdd();
                            }
                            return;
                        } else if (canRetry && (isFileOpIntent(userText) || fullResponse.contains("```"))) {
                            // Model either used a code block or gave plain text — auto-correct once.
                            // Trigger also when LLM responded with code blocks regardless of user phrasing
                            // (e.g. user said "yes" or "add test case" and LLM replied with markdown).
                            boolean usedCodeBlock = fullResponse.contains("```");
                            String correction = usedCodeBlock
                                ? "CORRECTION REQUIRED: You responded with file content inside a ``` code block. " +
                                  "A code block is display-only — it does NOT write to disk. "
                                : "CORRECTION REQUIRED: You described what to do instead of actually doing it. " +
                                  "A text description does NOT write to disk. ";
                            appendSystemMessage("⚠ Model did not use XML tags — auto-correcting…");
                            history.add(new ChatMessage("user",
                                    correction +
                                    "You MUST re-send your response as a raw XML tag with REAL Java code inside it. " +
                                    "This project is Java 21 Maven — write Java, not Go, not Python.\n" +
                                    "Copy this exact structure and fill in your Java code:\n\n" +
                                    "<MODIFY_FILE path=\"src/test/java/plugin/LocalLLMClientTest.java\">\n" +
                                    "package plugin;\n\n" +
                                    "import org.junit.jupiter.api.Test;\n" +
                                    "import plugin.llm.LocalLLMClient;\n" +
                                    "import plugin.llm.model.ChatMessage;\n" +
                                    "import java.util.List;\n" +
                                    "import static org.junit.jupiter.api.Assertions.*;\n\n" +
                                    "public class LocalLLMClientTest {\n\n" +
                                    "    @Test\n" +
                                    "    void yourTestMethod() {\n" +
                                    "        // REPLACE THIS with real Java test logic\n" +
                                    "    }\n" +
                                    "}\n" +
                                    "</MODIFY_FILE>\n\n" +
                                    "Output ONLY the XML tag with complete Java code inside. No ``` fences, no explanation before the tag."));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false);
                            return;
                        } else {
                            recordMistakes(java.util.List.of("use-xml-tags"));
                            appendSystemMessage("No file operation tags found — no files were changed. " +
                                    "A correction has been injected. Please ask again.");
                            // Always inject — covers plain-text responses AND post-retry failures
                            history.add(new ChatMessage("user",
                                    "CORRECTION REQUIRED: Your last response still did not write any files. " +
                                    "REMINDER — this is a Java 21 Maven project. Write Java only, never Go or Python.\n" +
                                    "You MUST output a raw XML tag with complete Java code inside it. " +
                                    "Use this exact structure:\n\n" +
                                    "<MODIFY_FILE path=\"src/test/java/plugin/LocalLLMClientTest.java\">\n" +
                                    "package plugin;\n\n" +
                                    "import org.junit.jupiter.api.Test;\n" +
                                    "import plugin.llm.LocalLLMClient;\n" +
                                    "import plugin.llm.model.ChatMessage;\n" +
                                    "import java.util.List;\n" +
                                    "import static org.junit.jupiter.api.Assertions.*;\n\n" +
                                    "public class LocalLLMClientTest {\n\n" +
                                    "    @Test\n" +
                                    "    void yourTestMethod() {\n" +
                                    "        // write your Java test here\n" +
                                    "    }\n" +
                                    "}\n" +
                                    "</MODIFY_FILE>\n\n" +
                                    "Replace the path and content with what you actually want to write. " +
                                    "Output ONLY the XML tag — no ``` fences, no explanation before it."));
                            history.add(new ChatMessage("assistant",
                                    "Understood. I will output only the <MODIFY_FILE> XML tag " +
                                    "with complete Java code inside it."));
                        }
                    } else {
                        // Not in EDITING mode
                        boolean hasFileOps = fullResponse.contains("<CREATE_FILE") ||
                                             fullResponse.contains("<MODIFY_FILE") ||
                                             fullResponse.contains("<CREATE_FOLDER") ||
                                             fullResponse.contains("<DELETE_FILE") ||
                                             fullResponse.contains("<DELETE_FOLDER");

                        if (fullResponse.contains("<RUN_TESTS") || fullResponse.contains("<CHECK_COMPILATION") || fullResponse.contains("<EXECUTE_COMMAND")) {
                            FileOperationUtil.FileOpResult opResult = FileOperationUtil.processFileOperations(project, fullResponse);
                            if (opResult.warnings != null && !opResult.warnings.isEmpty()) {
                                opResult.warnings.forEach(this::appendSystemMessage);
                                injectBlockedWriteFeedback(opResult.warnings);
                            }
                            recordMistakes(opResult.mistakeKeys);
                            if (opResult.runTests) {
                                if (opResult.testName != null) {
                                    appendSystemMessage("Test execution requested for " + opResult.testName + ". Running tests…");
                                    scheduleTestRun(model, endpoint, opResult.testName);
                                } else {
                                    appendSystemMessage("Test execution requested. Running tests…");
                                    scheduleTestRun(model, endpoint, null);
                                }
                                return;
                            } else if (opResult.checkCompilation) {
                                appendSystemMessage("Compilation check requested. Running build…");
                                scheduleBuildCheck(model, endpoint);
                                return;
                            } else if (opResult.customCommand != null) {
                                appendSystemMessage("Custom command execution requested: " + opResult.customCommand + ". Running…");
                                scheduleCustomCommand(opResult.customCommand);
                                return;
                            }
                        }

                        if (fullResponse.contains("<GIT_ADD_NEW")) {
                            scheduleGitAdd();
                            return;
                        }

                        if ("PLANNING".equals(mode)) {
                            if (hasFileOps) {
                                appendSystemMessage("⚠ File operation tags detected but skipped because you are in PLANNING mode. Switch to EDITING mode to allow changes.");
                            } else if (isFileOpIntent(userText)) {
                                appendSystemMessage("💡 It looks like you want to make changes. Please switch to EDITING mode and ask again to have the files written to disk.");
                            }
                        }
                    }

                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    boolean isStop = "STOPPED_BY_USER".equals(ex.getMessage()) ||
                                     "STREAM_INTERRUPTED".equals(ex.getMessage()) ||
                                     ex instanceof InterruptedException ||
                                     (ex.getCause() instanceof InterruptedException);

                    if (isStop || stopRequested) {
                        history.add(new ChatMessage("assistant", assistantBuffer.toString() + " [Interrupted]"));
                        // Already showed "Interrupted by user." via stop button listener
                    } else {
                        appendSystemMessage("Error: " + ex.getMessage());
                    }
                    setLoading(false);
                });
            }
        });
        currentChatThread.setDaemon(true);
        currentChatThread.start();
    }

    // -------------------------------------------------------------------------
    // Document helpers — EDT only
    // -------------------------------------------------------------------------

    private void appendUserMessage(String text) {
        insert("You\n", userRoleStyle);
        insert(text + "\n\n", userTextStyle);
    }

    private void beginAssistantMessage() {
        streaming = true;
        cursorOn  = false;
        assistantBuffer.setLength(0);
        insert("Assistant\n", assistantRoleStyle);
    }

    private void appendToken(String token) {
        assistantBuffer.append(token);
        removeCursorIfPresent();
        insert(token, assistantTextStyle);
        insert("▌", cursorStyle);
        cursorOn = true;
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void finalizeAssistantMessage() {
        streaming = false;
        blinkTimer.stop();
        removeCursorIfPresent();
        insert("\n\n", assistantTextStyle);
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void toggleBlink() {
        if (!streaming) return;
        try {
            int end = chatDoc.getLength();
            if (cursorOn) {
                if (end > 0 && chatDoc.getText(end - 1, 1).equals("▌")) {
                    chatDoc.remove(end - 1, 1);
                }
                cursorOn = false;
            } else {
                if (end == 0 || !chatDoc.getText(end - 1, 1).equals("▌")) {
                    chatDoc.insertString(end, "▌", cursorStyle);
                }
                cursorOn = true;
            }
        } catch (BadLocationException ignored) {}
    }

    private void appendSystemMessage(String text) {
        insert(text + "\n\n", systemStyle);
        chatPane.setCaretPosition(chatDoc.getLength());
    }

    private void insert(String text, Style style) {
        try {
            chatDoc.insertString(chatDoc.getLength(), text, style);
        } catch (BadLocationException ignored) {}
    }

    private void removeCursorIfPresent() {
        try {
            int end = chatDoc.getLength();
            if (end > 0 && chatDoc.getText(end - 1, 1).equals("▌")) {
                chatDoc.remove(end - 1, 1);
            }
        } catch (BadLocationException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void scheduleBuildCheck(String model, String endpoint) {
        // Runs after all VFS write actions have been dispatched to the EDT queue
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runCompile(project);
                // Scan for source files WHILE still in daemon thread — never on EDT
                String sourceContext = result.success() ? "" : scanProjectForErrorContext(result.output());
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Build successful.");
                        setLoading(false);
                    } else if (buildFixAttempts < 2) {
                        buildFixAttempts++;
                        String errors = result.output();
                        if (errors.length() > 3000) errors = errors.substring(0, 3000) + "\n[...truncated]";

                        boolean syntaxError = isSimpleSyntaxError(errors);
                        String fixInstruction;
                        String contextLabel;
                        if (syntaxError) {
                            fixInstruction =
                                "The file has a simple syntax error (e.g. missing `}`, `;`, or `)`). " +
                                "Look at the error line number and the file content below. " +
                                "Find ONLY the syntax mistake and fix it — do NOT rewrite the logic. " +
                                "Re-output the COMPLETE corrected file using <MODIFY_FILE>.\n";
                            contextLabel = "\nCurrent content of the broken file:\n";
                        } else {
                            fixInstruction =
                                "The code has compile errors. Fix ALL errors now. " +
                                "Use ONLY the methods and constructors shown in the source files below. " +
                                "Use <MODIFY_FILE> or <CREATE_FILE> XML tags for every file you change.\n";
                            contextLabel =
                                "\nRelevant source files (use ONLY these methods and constructors):\n";
                        }
                        String contextSection = sourceContext.isEmpty() ? "" : contextLabel + sourceContext;

                        appendSystemMessage("⚠ Build errors — scanning project and asking LLM to fix " +
                                "(attempt " + buildFixAttempts + "/2)…");
                        history.add(new ChatMessage("user",
                                fixInstruction +
                                "\nBuild output:\n" + errors +
                                contextSection));
                        beginAssistantMessage();
                        blinkTimer.start();
                        streamAndHandle(model, endpoint, null, false);
                    } else {
                        String errors = result.output();
                        if (errors.length() > 3000) errors = errors.substring(0, 3000) + "\n[...truncated]";
                        appendSystemMessage("❌ Build failed:\n" + errors);
                        setLoading(false);
                    }
                });
            })
        );
    }

    private void scheduleTestRun(String model, String endpoint, String testName) {
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runTest(project, testName);
                // Scan for source files WHILE still in daemon thread — never on EDT
                String sourceContext = result.success() ? "" : scanProjectForErrorContext(result.output());
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Tests passed successfully.");
                        setLoading(false);
                    } else {
                        appendSystemMessage("❌ Tests failed.");

                        StringBuilder output = new StringBuilder();
                        if (result.testResults() != null && !result.testResults().isEmpty()) {
                            output.append("\nTest Results:\n");
                            output.append(String.format("%-40s | %-10s\n", "Test Name", "Status"));
                            output.append("-".repeat(41)).append("|").append("-".repeat(11)).append("\n");
                            for (var tr : result.testResults()) {
                                output.append(String.format("%-40s | %-10s\n",
                                        tr.name().length() > 40 ? tr.name().substring(0, 37) + "..." : tr.name(),
                                        tr.status()));
                            }
                        }
                        String rawOutput = result.output();
                        if (!rawOutput.isBlank()) output.append("\n").append(rawOutput);
                        appendSystemMessage(output.toString());

                        if (buildFixAttempts < 2) {
                            buildFixAttempts++;
                            String errors = rawOutput.length() > 3000
                                    ? rawOutput.substring(0, 3000) + "\n[...truncated]" : rawOutput;

                            boolean syntaxError = isSimpleSyntaxError(errors);
                            String fixInstruction;
                            String contextLabel;
                            if (syntaxError) {
                                fixInstruction =
                                    "The test file has a syntax error. Look at the error and the file content. " +
                                    "Fix ONLY the syntax mistake — do NOT rewrite the logic. " +
                                    "Re-output the complete corrected file using <MODIFY_FILE>.\n";
                                contextLabel = "\nCurrent content of the broken file:\n";
                            } else {
                                fixInstruction =
                                    "The tests have failures. Fix the failing assertions now. " +
                                    "Use ONLY the methods shown in the source files below. " +
                                    "Use <MODIFY_FILE> XML tags to fix the test file.\n";
                                contextLabel =
                                    "\nRelevant source files (use ONLY these methods):\n";
                            }
                            String contextSection = sourceContext.isEmpty() ? "" : contextLabel + sourceContext;

                            appendSystemMessage("⚠ Test failures — scanning project and asking LLM to fix " +
                                    "(attempt " + buildFixAttempts + "/2)…");
                            history.add(new ChatMessage("user",
                                    fixInstruction +
                                    "\nTest output:\n" + errors +
                                    contextSection));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false);
                        } else {
                            setLoading(false);
                        }
                    }
                });
            })
        );
    }

    /**
     * Returns true when the error is a simple syntax mistake (missing brace, semicolon, etc.)
     * that the LLM should fix by patching the file, not by studying the source API.
     */
    private static boolean isSimpleSyntaxError(String errorOutput) {
        String lower = errorOutput.toLowerCase();
        return lower.contains("reached end of file") ||
               lower.contains("';' expected")         ||
               lower.contains("'(' expected")         ||
               lower.contains("')' expected")         ||
               lower.contains("'{' expected")         ||
               lower.contains("'}' expected")         ||
               lower.contains("illegal start of expression") ||
               lower.contains("class, interface, or enum expected") ||
               lower.contains("not a statement");
    }

    /**
     * Scans src/main/java for source files whose class names appear in the error output,
     * and ALWAYS includes the failing test file itself.
     * Called off the EDT (daemon thread only).
     */
    private String scanProjectForErrorContext(String errorOutput) {
        // Normalise separators — Maven on Windows can emit either \ or /
        String normalised = errorOutput.replace("\\", "/");

        java.util.Set<String> classNames = new java.util.LinkedHashSet<>();

        // "cannot find symbol: class Foo" → Foo
        java.util.regex.Matcher symbolMatcher =
                java.util.regex.Pattern.compile("symbol:\\s+class\\s+(\\w+)").matcher(normalised);
        while (symbolMatcher.find()) classNames.add(symbolMatcher.group(1));

        // "location: class plugin.x.ClassName" → ClassName
        java.util.regex.Matcher locationMatcher =
                java.util.regex.Pattern.compile("location:.*?(\\w+)$", java.util.regex.Pattern.MULTILINE)
                        .matcher(normalised);
        while (locationMatcher.find()) classNames.add(locationMatcher.group(1));

        // "/path/FooTest.java:[line,col]" → strip optional Test suffix → source class Foo
        java.util.regex.Matcher fileInErrorMatcher =
                java.util.regex.Pattern.compile("/(\\w+?)(?:Test)?\\.java:\\[?\\d").matcher(normalised);
        while (fileInErrorMatcher.find()) classNames.add(fileInErrorMatcher.group(1));

        String basePath = project.getBasePath();
        if (basePath == null) return "";

        StringBuilder context = new StringBuilder();

        // For API/semantic errors: scan main sources
        if (!classNames.isEmpty()) {
            java.nio.file.Path srcMain = java.nio.file.Paths.get(basePath, "src", "main", "java");
            for (String className : classNames) {
                try {
                    java.util.Optional<java.nio.file.Path> found = java.nio.file.Files.walk(srcMain)
                            .filter(p -> p.getFileName().toString().equals(className + ".java"))
                            .findFirst();
                    if (found.isPresent()) {
                        String content = java.nio.file.Files.readString(found.get(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        context.append("=== ").append(className).append(".java ===\n")
                               .append(content).append("\n\n");
                    }
                } catch (Exception ignored) {}
            }
        }

        // ALWAYS include the failing test file — this is critical for syntax error fixes
        java.nio.file.Path srcTest = java.nio.file.Paths.get(basePath, "src", "test", "java");
        java.util.regex.Matcher tf =
                java.util.regex.Pattern.compile("/(\\w+Test)\\.java").matcher(normalised);
        java.util.Set<String> testFiles = new java.util.LinkedHashSet<>();
        while (tf.find()) testFiles.add(tf.group(1));

        // Fallback: if no test file found in error text, scan the whole test tree
        if (testFiles.isEmpty()) {
            try {
                java.nio.file.Files.walk(srcTest)
                        .filter(p -> p.getFileName().toString().endsWith("Test.java"))
                        .forEach(p -> testFiles.add(p.getFileName().toString().replace(".java", "")));
            } catch (Exception ignored) {}
        }

        for (String testClass : testFiles) {
            try {
                java.util.Optional<java.nio.file.Path> found = java.nio.file.Files.walk(srcTest)
                        .filter(p -> p.getFileName().toString().equals(testClass + ".java"))
                        .findFirst();
                if (found.isPresent()) {
                    String content = java.nio.file.Files.readString(found.get(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    context.append("=== ").append(testClass).append(".java (current content — fix this file) ===\n")
                           .append(content).append("\n\n");
                }
            } catch (Exception ignored) {}
        }

        return context.toString();
    }

    private void scheduleGitAdd() {
        if (newlyCreatedFiles.isEmpty()) {
            appendSystemMessage("No new files to add to Git.");
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                GitUtil.GitResult result = GitUtil.addFiles(project, newlyCreatedFiles);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Successfully added " + newlyCreatedFiles.size() + " new file(s) to Git.");
                    } else {
                        appendSystemMessage("❌ Git add failed:\n" + result.output());
                    }
                });
            })
        );
    }

    private void scheduleCustomCommand(String command) {
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runCustomCommand(project, command);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Command executed successfully:\n" + result.output());
                    } else {
                        appendSystemMessage("❌ Command failed:\n" + result.output());
                    }
                    setLoading(false);
                });
            })
        );
    }

    private static boolean isFileOpIntent(String userText) {
        if (userText == null) return false;
        String lower = userText.toLowerCase();
        return lower.contains("edit") || lower.contains("modify") || lower.contains("update") ||
               lower.contains("create") || lower.contains("delete") || lower.contains("change") ||
               lower.contains("write") || lower.contains("fix") || lower.contains("remove") ||
               lower.contains("rename") || lower.contains("replace") || lower.contains("refactor") ||
               lower.contains("implement") ||
               lower.contains("run test") || lower.contains("execute test") || lower.contains("check test") ||
               lower.contains("add test") || lower.contains("missing test") || lower.contains("test case") ||
               (lower.contains("add") && (lower.contains("file") || lower.contains("class") || lower.contains("method")));
    }

    public void setTabContent(Content content) {
        this.tabContent = content;
    }

    private void generateTitle(String firstUserMessage) {
        PluginSettings s = PluginSettings.getInstance();
        String model    = s.getModel();
        String endpoint = s.getEndpoint();
        if (model == null || model.isBlank()) return;

        String excerpt = firstUserMessage.substring(0, Math.min(200, firstUserMessage.length()));
        List<ChatMessage> req = List.of(new ChatMessage("user",
                "Give a very short title (3-5 words) for a chat conversation that starts with: \""
                + excerpt + "\". Reply with ONLY the title, no quotes, no explanation."));

        daemon(() -> {
            StringBuilder buf = new StringBuilder();
            try {
                new LocalLLMClient(endpoint).streamChat(model, req, buf::append);
                String title = buf.toString().trim().replaceAll("^[\"']+|[\"']+$", "");
                if (!title.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        titleLabel.setText("  " + title);
                        if (tabContent != null) tabContent.setDisplayName(title);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void setLoading(boolean loading) {
        isGenerating = loading;

        sendBtn.setEnabled(!loading);
        stopBtn.setVisible(loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
        promptArea.setEnabled(!loading);
        if (!loading) {
            promptArea.requestFocusInWindow();
            showDoneNotification();
        }
    }

    private void showDoneNotification() {
        SwingUtilities.invokeLater(() -> {
            try {
                JWindow toast = new JWindow();
                JPanel panel = new JPanel();
                panel.setBorder(new javax.swing.border.LineBorder(new java.awt.Color(0,0,0,120), 1, true));
                panel.setBackground(new java.awt.Color(30, 30, 30, 230));
                panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
                JLabel label = new JLabel("  Local LLM: Task completed.  ");
                label.setForeground(new java.awt.Color(240, 240, 240));
                panel.add(label);
                toast.add(panel);
                toast.pack();
                java.awt.Point base = titleLabel.isShowing() ? titleLabel.getLocationOnScreen() : root.getLocationOnScreen();
                int x = base.x + 10;
                int y = Math.max(0, base.y - toast.getHeight() - 10);
                toast.setLocation(x, y);
                toast.setAlwaysOnTop(true);
                toast.setVisible(true);
                new javax.swing.Timer(2500, e -> toast.dispose()).start();
            } catch (Throwable ignored) {
                // Last resort: do nothing if even Swing is unavailable
            }
        });
    }

    private void clearConversation() {
        history.clear();
        newlyCreatedFiles.clear();
        chatDoc = new DefaultStyledDocument();
        initStyles();
        chatPane.setStyledDocument(chatDoc);
        titleGenerated = false;
        buildFixAttempts = 0;
        titleLabel.setText("  New Chat");
        if (tabContent != null) tabContent.setDisplayName("New Chat");
        appendSystemMessage("Chat cleared. New conversation started.");
        promptArea.requestFocusInWindow();
    }

    private void recordMistakes(java.util.List<String> mistakeKeys) {
        if (mistakeKeys == null || mistakeKeys.isEmpty()) return;
        String basePath = project.getBasePath();
        if (basePath == null) return;
        for (String key : mistakeKeys) {
            plugin.util.LLMCorrectionsUtil.recordMistake(basePath, key);
        }
    }

    private void injectBlockedWriteFeedback(java.util.List<String> warnings) {
        java.util.List<String> blocked = warnings.stream()
                .filter(w -> w.startsWith("⛔"))
                .collect(java.util.stream.Collectors.toList());
        if (blocked.isEmpty()) return;
        String feedback = "SYSTEM FEEDBACK — The following file operations were rejected:\n" +
                String.join("\n", blocked) + "\n\n" +
                "REQUIRED ACTIONS:\n" +
                "1. If the rejected file exists on disk with errors, delete it immediately using the XML tag: " +
                "<DELETE_FILE path=\"src/test/java/plugin/ui/ChatPanelTest.java\" /> " +
                "(adjust the path to match the actual file).\n" +
                "2. Do NOT attempt to write or fix that file again.\n" +
                "3. The ONLY unit-testable classes in this project are: " +
                "plugin.llm.model.ChatMessage, plugin.settings.PluginSettings, plugin.llm.LocalLLMClient.\n" +
                "4. Write tests only for those three classes using <CREATE_FILE path=\"src/test/java/plugin/FooTest.java\">.";
        history.add(new ChatMessage("user", feedback));
        history.add(new ChatMessage("assistant",
                "Understood. I will delete the untestable test file and write correct tests " +
                "only for ChatMessage, PluginSettings, or LocalLLMClient."));
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    public JPanel getSwingComponent() {
        return root;
    }
}
