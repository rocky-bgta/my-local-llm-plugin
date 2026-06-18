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
    }

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

        JButton clearBtn = new JButton("Clear History");
        clearBtn.setToolTipText("Clear chat history and start a new conversation");
        clearBtn.addActionListener(e -> {
            history.clear();
            chatDoc = new DefaultStyledDocument();
            initStyles();
            chatPane.setStyledDocument(chatDoc);
            appendSystemMessage("Conversation history cleared.");
            titleGenerated = false;
            buildFixAttempts = 0;
            titleLabel.setText("  New Chat");
            if (tabContent != null) tabContent.setDisplayName("New Chat");
        });

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
        StyleConstants.setFontSize(userRoleStyle, 13);

        userTextStyle = chatPane.addStyle("userText", base);
        StyleConstants.setForeground(userTextStyle, new Color(0xD4D4D4));
        StyleConstants.setFontFamily(userTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(userTextStyle, 14);

        assistantRoleStyle = chatPane.addStyle("assistantRole", base);
        StyleConstants.setForeground(assistantRoleStyle, new Color(0x569CD6));
        StyleConstants.setBold(assistantRoleStyle, true);
        StyleConstants.setFontSize(assistantRoleStyle, 13);

        assistantTextStyle = chatPane.addStyle("assistantText", base);
        StyleConstants.setForeground(assistantTextStyle, new Color(0xE8E8E8));
        StyleConstants.setFontFamily(assistantTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(assistantTextStyle, 14);

        systemStyle = chatPane.addStyle("system", base);
        StyleConstants.setForeground(systemStyle, new Color(0xCE9178));
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontSize(systemStyle, 12);

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
        promptArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
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
            appendSystemMessage("Interrupted by user.");
            setLoading(false);
        });

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            try { chatDoc.remove(0, chatDoc.getLength()); } catch (BadLocationException ignored) {}
            history.clear();
        });

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
            String systemInstructions = "You are a specialized AI coding assistant for this project. " +
                    "I have provided you with the project structure and file contents below to help you understand the codebase.\n" +
                    "Current Mode: " + mode + "\n" +
                    "When in PLANNING mode, discuss the task and outline the steps. Do not use file operation tags.\n" +
                    "EDITING MODE — MANDATORY FILE OPERATION RULES:\n" +
                    "In EDITING mode you MUST write files using the XML tags below. There is NO other way to write to disk.\n" +
                    "WRONG — this will NOT write the file (do not do this):\n" +
                    "```markdown\n...file content...\n```\n" +
                    "CORRECT — this WILL write the file (always do this instead):\n" +
                    "<MODIFY_FILE path=\"path/to/file\">complete new file content here</MODIFY_FILE>\n" +
                    "All available tags:\n" +
                    "<MODIFY_FILE path=\"path/to/file\">complete new file content</MODIFY_FILE>\n" +
                    "<CREATE_FILE path=\"path/to/file\">complete file content</CREATE_FILE>\n" +
                    "<CREATE_FOLDER path=\"path/to/folder\" />\n" +
                    "<DELETE_FILE path=\"path/to/file\" />\n" +
                    "<DELETE_FOLDER path=\"path/to/folder\" />\n" +
                    "<RUN_TESTS />\n" +
                    "Rules:\n" +
                    "1. Output the raw XML tag directly — never wrap it in ``` fences.\n" +
                    "2. Always include the COMPLETE file content inside the tag — never truncate or summarize.\n" +
                    "3. You may add a brief explanation AFTER the closing XML tag.\n" +
                    "4. If you use a ``` code block for file content, the file will NOT be changed.\n" +
                    "5. To run all project tests, use the <RUN_TESTS /> tag. This can be combined with file changes.\n" +
                    "Execute tasks one by one and inform the user of your progress.\n" +
                    "When in BYPASS mode, ignore file operations and behave like a general assistant.\n" +
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
        if (snapshot.size() > 15) {
            List<ChatMessage> trimmed = new ArrayList<>();
            trimmed.add(snapshot.get(0));
            for (int i = 1; i < snapshot.size() - 10; i++) {
                if (snapshot.get(i).content().contains("Project Context")) trimmed.add(snapshot.get(i));
            }
            int lastCount = Math.min(10, snapshot.size() - 1);
            for (int i = snapshot.size() - lastCount; i < snapshot.size(); i++) trimmed.add(snapshot.get(i));
            snapshot = trimmed;
        }

        List<ChatMessage> finalSnapshot = snapshot;
        currentChatThread = new Thread(() -> {
            try {
                new LocalLLMClient(endpoint).streamChat(model, finalSnapshot,
                        token -> {
                            if (stopRequested) throw new RuntimeException("INTERRUPTED");
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
                        boolean hasOps = fullResponse.contains("<CREATE_FILE") ||
                                         fullResponse.contains("<MODIFY_FILE") ||
                                         fullResponse.contains("<CREATE_FOLDER") ||
                                         fullResponse.contains("<DELETE_FILE") ||
                                         fullResponse.contains("<DELETE_FOLDER") ||
                                         fullResponse.contains("<RUN_TESTS");
                        if (hasOps) {
                            boolean runTests = FileOperationUtil.processFileOperations(project, fullResponse);
                            if (runTests) {
                                appendSystemMessage("Test execution requested. Running tests…");
                                scheduleTestRun();
                            } else {
                                appendSystemMessage("File operations applied. Running build check…");
                                scheduleBuildCheck(model, endpoint);
                            }
                            return;
                        } else if (canRetry && isFileOpIntent(userText)) {
                            // Model either used a code block or gave plain text — auto-correct once
                            boolean usedCodeBlock = fullResponse.contains("```");
                            String correction = usedCodeBlock
                                ? "CORRECTION REQUIRED: You responded with file content inside a ``` code block. " +
                                  "A code block is display-only — it does NOT write to disk. "
                                : "CORRECTION REQUIRED: You described what to do instead of actually doing it. " +
                                  "A text description does NOT write to disk. ";
                            appendSystemMessage("⚠ Model did not use XML tags — auto-correcting…");
                            history.add(new ChatMessage("user",
                                    correction +
                                    "You MUST re-send your response using the XML tag format:\n" +
                                    "<MODIFY_FILE path=\"path/to/file\">complete new file content</MODIFY_FILE>\n" +
                                    "<CREATE_FILE path=\"path/to/file\">complete file content</CREATE_FILE>\n" +
                                    "<RUN_TESTS />\n" +
                                    "Output the raw XML tag directly with the full file content inside it."));
                            beginAssistantMessage();
                            blinkTimer.start();
                            streamAndHandle(model, endpoint, null, false);
                            return;
                        } else {
                            appendSystemMessage("No file operation tags found — no files were changed. " +
                                    "Make sure you are in EDITING mode and the model uses <MODIFY_FILE> tags.");
                        }
                    } else if ("PLANNING".equals(mode)) {
                        if (fullResponse.contains("<CREATE_FILE") || fullResponse.contains("<MODIFY_FILE") || fullResponse.contains("<CREATE_FOLDER") || fullResponse.contains("<RUN_TESTS")) {
                            appendSystemMessage("Operation detected but skipped — switch to EDITING mode to allow changes or test execution.");
                        }
                    }

                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    if (!"INTERRUPTED".equals(ex.getMessage())) {
                        appendSystemMessage("Error: " + ex.getMessage());
                    } else {
                        history.add(new ChatMessage("assistant", assistantBuffer.toString() + " [Interrupted]"));
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
                BuildUtil.BuildResult result = BuildUtil.runMavenCompile(project);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Build successful.");
                        setLoading(false);
                    } else if (buildFixAttempts < 2) {
                        buildFixAttempts++;
                        String errors = result.output();
                        if (errors.length() > 3000) errors = errors.substring(0, 3000) + "\n[...truncated]";
                        appendSystemMessage("⚠ Build errors — asking LLM to fix (attempt " + buildFixAttempts + "/2)…");
                        history.add(new ChatMessage("user",
                                "The code you just wrote has compile errors. Fix ALL errors now.\n" +
                                "If a Maven dependency is missing, add it to pom.xml.\n" +
                                "Use <MODIFY_FILE> or <CREATE_FILE> XML tags for every file you change.\n\n" +
                                "Compiler output:\n" + errors));
                        beginAssistantMessage();
                        blinkTimer.start();
                        streamAndHandle(model, endpoint, null, false);
                    } else {
                        String errors = result.output();
                        appendSystemMessage("⚠ Build still failing after 2 fix attempts — manual intervention needed.\n" +
                                errors.substring(0, Math.min(1000, errors.length())));
                        setLoading(false);
                    }
                });
            })
        );
    }

    private void scheduleTestRun() {
        ApplicationManager.getApplication().invokeLater(() ->
            daemon(() -> {
                BuildUtil.BuildResult result = BuildUtil.runMavenTest(project);
                SwingUtilities.invokeLater(() -> {
                    if (result.success()) {
                        appendSystemMessage("✓ Tests passed successfully.\n" + result.output());
                    } else {
                        appendSystemMessage("❌ Tests failed.\n" + result.output());
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
               lower.contains("run test") || lower.contains("execute test") || lower.contains("check test") ||
               lower.contains("implement") || lower.contains("add") && (lower.contains("file") || lower.contains("class") || lower.contains("method"));
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
        if (!loading) stopRequested = false;

        sendBtn.setEnabled(!loading);
        stopBtn.setVisible(loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
        promptArea.setEnabled(!loading);
        if (!loading) promptArea.requestFocusInWindow();
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
