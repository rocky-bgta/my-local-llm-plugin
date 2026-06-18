package plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import plugin.llm.LMStudioClient;
import plugin.llm.model.ChatMessage;
import plugin.settings.PluginSettings;
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
    private final JPanel root;

    // Mode
    private String mode = "PLANNING";
    private JComboBox<String> modeCombo;

    // Chat display
    private JTextPane      chatPane;
    private StyledDocument chatDoc;

    // Input
    private JTextArea    promptArea;
    private JButton      sendBtn;
    private JProgressBar spinner;

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

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        bar.setPreferredSize(new Dimension(0, 32));

        JLabel title = new JLabel("  Local LLM");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        bar.add(title, BorderLayout.WEST);

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
                    List<String> models = new LMStudioClient(ep).fetchModels();
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

    private JScrollPane buildChatArea() {
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setMargin(new Insets(10, 12, 10, 12));
        chatDoc  = chatPane.getStyledDocument();
        initStyles();

        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private void initStyles() {
        Style base = StyleContext.getDefaultStyleContext()
                                 .getStyle(StyleContext.DEFAULT_STYLE);

        userRoleStyle = chatPane.addStyle("userRole", base);
        StyleConstants.setForeground(userRoleStyle, new Color(0x4EC9B0));
        StyleConstants.setBold(userRoleStyle, true);
        StyleConstants.setFontSize(userRoleStyle, 12);

        userTextStyle = chatPane.addStyle("userText", base);
        StyleConstants.setForeground(userTextStyle, new Color(0xD4D4D4));
        StyleConstants.setFontFamily(userTextStyle, Font.SANS_SERIF);
        StyleConstants.setFontSize(userTextStyle, 13);

        assistantRoleStyle = chatPane.addStyle("assistantRole", base);
        StyleConstants.setForeground(assistantRoleStyle, new Color(0x569CD6));
        StyleConstants.setBold(assistantRoleStyle, true);
        StyleConstants.setFontSize(assistantRoleStyle, 12);

        assistantTextStyle = chatPane.addStyle("assistantText", base);
        StyleConstants.setForeground(assistantTextStyle, new Color(0xE8E8E8));
        StyleConstants.setFontFamily(assistantTextStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(assistantTextStyle, 13);

        systemStyle = chatPane.addStyle("system", base);
        StyleConstants.setForeground(systemStyle, new Color(0xCE9178));
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontSize(systemStyle, 11);

        cursorStyle = chatPane.addStyle("cursor", base);
        StyleConstants.setForeground(cursorStyle, new Color(0x569CD6));
        StyleConstants.setBold(cursorStyle, true);
    }

    // -------------------------------------------------------------------------
    // Input panel — 4-row textarea, Send bottom-right, Clear bottom-left
    // -------------------------------------------------------------------------

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")),
                new EmptyBorder(8, 10, 10, 10)));

        promptArea = new JTextArea(4, 0);
        promptArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setMargin(new Insets(6, 8, 6, 8));
        // Enter = send   |   Shift+Enter = newline
        promptArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        sendBtn = new JButton("Send");
        sendBtn.setPreferredSize(new Dimension(80, 28));
        sendBtn.addActionListener(e -> sendMessage());

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
        ctrlRow.add(sendBtn,  BorderLayout.EAST);

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
                    "When in EDITING mode, you can create or modify files/folders. Use the following XML-like tags:\n" +
                    "<CREATE_FOLDER path=\"path/to/folder\" />\n" +
                    "<CREATE_FILE path=\"path/to/file\">content</CREATE_FILE>\n" +
                    "<MODIFY_FILE path=\"path/to/file\">new content</MODIFY_FILE>\n" +
                    "In EDITING mode, you should execute tasks one by one and inform the user of your progress.\n" +
                    "When in BYPASS mode, ignore file operations and behave like a general assistant.\n" +
                    "Maintain the session context until the user says to discard it.\n" +
                    "If the user asks about the project structure or specific files, use the provided context to answer. " +
                    "Always refer to the 'Project Structure' section for the complete file hierarchy.";
            
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

        List<ChatMessage> snapshot = new ArrayList<>(history);
        // Basic history management: if too long, keep system prompt and last 6 messages
        // Trimming more aggressively to stay within context limits
        if (snapshot.size() > 8) {
            List<ChatMessage> trimmed = new ArrayList<>();
            trimmed.add(snapshot.get(0)); // Keep system prompt
            trimmed.addAll(snapshot.subList(snapshot.size() - 7, snapshot.size()));
            snapshot = trimmed;
        }

        List<ChatMessage> finalSnapshot = snapshot;
        daemon(() -> {
            try {
                new LMStudioClient(endpoint).streamChat(model, finalSnapshot,
                        token -> SwingUtilities.invokeLater(() -> appendToken(token)));
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    String fullResponse = assistantBuffer.toString();
                    history.add(new ChatMessage("assistant", fullResponse));
                    
                    if ("EDITING".equals(mode)) {
                        FileOperationUtil.processFileOperations(project, fullResponse);
                    }
                    
                    setLoading(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    appendSystemMessage("Error: " + ex.getMessage());
                    setLoading(false);
                });
            }
        });
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

    private void setLoading(boolean loading) {
        sendBtn.setEnabled(!loading);
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
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
