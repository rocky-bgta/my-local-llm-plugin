package plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import plugin.context.GitContextBuilder;
import plugin.context.ProjectContextBuilder;
import plugin.llm.LMStudioClient;
import plugin.llm.model.ChatMessage;
import plugin.settings.PluginSettings;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChatPanel {

    private final JPanel   root;
    private final Project  project;

    // Mode selector
    private JComboBox<String> modeCombo;

    // Conversation title (auto-generated from first message)
    private JLabel  titleLabel;
    private boolean titleGenerated = false;

    // Pending image attachments (cleared after each Send)
    private final List<byte[]> pendingImages    = new ArrayList<>();
    private       JPanel       imagePreviewPanel;

    // Large-prompt chunking thresholds
    private static final int MAX_SINGLE_PROMPT = 48_000;
    private static final int CHUNK_SIZE        = 12_000;

    // Chat display
    private JTextPane      chatPane;
    private StyledDocument chatDoc;

    // Input
    private JTextArea    promptArea;
    private JButton      sendBtn;      // toggles between ▶ send and ■ stop
    private JProgressBar spinner;

    // Streaming state (all accessed on EDT only)
    private final Timer           blinkTimer;
    private       boolean         streaming       = false;
    private volatile Thread       streamThread    = null; // daemon thread running current stream
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

    // Font families — same stack used by Claude / ChatGPT / GitHub Copilot on Windows
    private static final String UI_FONT   = availableFont(
            "Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue", "Arial", Font.SANS_SERIF);
    private static final String CODE_FONT = availableFont(
            "JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Consolas", "Courier New", Font.MONOSPACED);

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

        titleLabel = new JLabel("  New Conversation");
        titleLabel.setFont(new Font(UI_FONT, Font.PLAIN, 12));
        bar.add(titleLabel, BorderLayout.WEST);

        JButton gearBtn = new JButton(AllIcons.General.Settings);
        gearBtn.setBorderPainted(false);
        gearBtn.setContentAreaFilled(false);
        gearBtn.setFocusPainted(false);
        gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearBtn.setToolTipText("Settings");
        gearBtn.addActionListener(e -> showSettingsDialog());

        modeCombo = new JComboBox<>(new String[]{"Planning", "Editing", "Bypass"});
        modeCombo.setSelectedItem("Editing");
        modeCombo.setPreferredSize(new Dimension(90, 22));
        modeCombo.setFont(modeCombo.getFont().deriveFont(11f));
        modeCombo.setToolTipText("Planning: analyse only  |  Editing: make changes  |  Bypass: fast, minimal explanation");

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        right.setOpaque(false);
        right.add(new JLabel("Mode:"));
        right.add(modeCombo);
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
        fc.gridy = 3; form.add(saveBtn,     fc);
        fc.gridy = 4; form.add(statusLabel, fc);

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

        final int ROLE_SIZE = 13;  // "You" / "Assistant" labels
        final int TEXT_SIZE = 15;  // message body
        final int SYS_SIZE  = 13;  // system / error notices

        userRoleStyle = chatPane.addStyle("userRole", base);
        StyleConstants.setForeground(userRoleStyle, new Color(0x4EC9B0));
        StyleConstants.setBold(userRoleStyle, true);
        StyleConstants.setFontFamily(userRoleStyle, UI_FONT);
        StyleConstants.setFontSize(userRoleStyle, ROLE_SIZE);

        userTextStyle = chatPane.addStyle("userText", base);
        StyleConstants.setForeground(userTextStyle, new Color(0xD4D4D4));
        StyleConstants.setFontFamily(userTextStyle, UI_FONT);
        StyleConstants.setFontSize(userTextStyle, TEXT_SIZE);

        assistantRoleStyle = chatPane.addStyle("assistantRole", base);
        StyleConstants.setForeground(assistantRoleStyle, new Color(0x569CD6));
        StyleConstants.setBold(assistantRoleStyle, true);
        StyleConstants.setFontFamily(assistantRoleStyle, UI_FONT);
        StyleConstants.setFontSize(assistantRoleStyle, ROLE_SIZE);

        // Assistant body: UI_FONT for prose, CODE_FONT for code snippets (future)
        assistantTextStyle = chatPane.addStyle("assistantText", base);
        StyleConstants.setForeground(assistantTextStyle, new Color(0xE8E8E8));
        StyleConstants.setFontFamily(assistantTextStyle, UI_FONT);
        StyleConstants.setFontSize(assistantTextStyle, TEXT_SIZE);

        systemStyle = chatPane.addStyle("system", base);
        StyleConstants.setForeground(systemStyle, new Color(0xCE9178));
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontFamily(systemStyle, UI_FONT);
        StyleConstants.setFontSize(systemStyle, SYS_SIZE);

        cursorStyle = chatPane.addStyle("cursor", base);
        StyleConstants.setForeground(cursorStyle, new Color(0x569CD6));
        StyleConstants.setBold(cursorStyle, true);
        StyleConstants.setFontFamily(cursorStyle, UI_FONT);
        StyleConstants.setFontSize(cursorStyle, TEXT_SIZE);
    }

    // -------------------------------------------------------------------------
    // Input panel — 4-row textarea, Send bottom-right, Clear bottom-left
    // -------------------------------------------------------------------------

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")),
                new EmptyBorder(6, 10, 10, 10)));

        // ── Image preview strip (hidden when empty) ───────────────────────────
        imagePreviewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        imagePreviewPanel.setVisible(false);
        imagePreviewPanel.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        // ── Prompt textarea ───────────────────────────────────────────────────
        promptArea = new JTextArea(4, 0);
        promptArea.setFont(new Font(UI_FONT, Font.PLAIN, 15));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setMargin(new Insets(6, 8, 6, 8));

        // Enter = send | Shift+Enter = newline
        promptArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    if (streaming) {
                        Thread t = streamThread;
                        if (t != null) t.interrupt();
                    } else {
                        sendMessage();
                    }
                }
            }
        });

        // Ctrl+V: paste image from clipboard first, fall back to text paste
        KeyStroke ctrlV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
        promptArea.getInputMap().put(ctrlV, "smartPaste");
        promptArea.getActionMap().put("smartPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!tryPasteImageFromClipboard()) promptArea.paste();
            }
        });

        // Drag-and-drop image files onto the input area
        promptArea.setDropTarget(new DropTarget() {
            @Override
            public synchronized void drop(DropTargetDropEvent evt) {
                evt.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    Transferable t = evt.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        for (File f : files) {
                            String n = f.getName().toLowerCase();
                            if (n.endsWith(".png") || n.endsWith(".jpg")
                                    || n.endsWith(".jpeg") || n.endsWith(".webp")) {
                                addPendingImage(Files.readAllBytes(f.toPath()));
                            }
                        }
                        evt.dropComplete(true);
                    } else {
                        evt.rejectDrop();
                    }
                } catch (Exception ex) {
                    evt.rejectDrop();
                }
            }
        });

        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // ── Buttons ───────────────────────────────────────────────────────────
        // ── Send / Stop toggle button ─────────────────────────────────────────
        // Blue ▶ when idle → Red ■ while streaming. One button, two roles.
        sendBtn = new JButton("▶");
        sendBtn.setFont(sendBtn.getFont().deriveFont(Font.BOLD, 15f));
        sendBtn.setPreferredSize(new Dimension(46, 32));
        sendBtn.setToolTipText("Send (Enter)");
        sendBtn.setFocusPainted(false);
        sendBtn.setBackground(new Color(0x3574F0));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setOpaque(true);
        sendBtn.setBorderPainted(false);
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendBtn.addActionListener(e -> {
            if (streaming) {
                Thread t = streamThread;
                if (t != null) t.interrupt();
            } else {
                sendMessage();
            }
        });

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            try { chatDoc.remove(0, chatDoc.getLength()); } catch (BadLocationException ignored) {}
            history.clear();
            pendingImages.clear();
            refreshImagePreview();
            titleGenerated = false;
            titleLabel.setText("  New Conversation");
        });

        // Attach button — opens file chooser for image files
        JButton attachBtn = new JButton("📎");
        attachBtn.setToolTipText("Attach image (or drag-and-drop / Ctrl+V)");
        attachBtn.addActionListener(e -> chooseImageFile());

        spinner = new JProgressBar();
        spinner.setIndeterminate(false);
        spinner.setPreferredSize(new Dimension(80, 14));
        spinner.setVisible(false);

        JPanel ctrlRow  = new JPanel(new BorderLayout(4, 0));
        JPanel leftCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftCtrl.add(clearBtn);
        leftCtrl.add(attachBtn);
        leftCtrl.add(spinner);
        ctrlRow.add(leftCtrl, BorderLayout.WEST);
        ctrlRow.add(sendBtn,  BorderLayout.EAST);

        panel.add(imagePreviewPanel, BorderLayout.NORTH);
        panel.add(promptScroll,      BorderLayout.CENTER);
        panel.add(ctrlRow,           BorderLayout.SOUTH);
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

        // Capture prior turns BEFORE adding the current message — used as SESSION_HISTORY.
        List<ChatMessage> priorHistory = new ArrayList<>(history);

        // Capture and clear pending images (must happen on EDT before daemon starts)
        List<byte[]> images = new ArrayList<>(pendingImages);
        pendingImages.clear();
        refreshImagePreview();

        appendUserMessage(text, images);
        history.add(new ChatMessage("user", text, images));
        promptArea.setText("");

        beginAssistantMessage();
        blinkTimer.start();
        setLoading(true);

        String mode = modeCombo.getSelectedItem() != null ? (String) modeCombo.getSelectedItem() : "Editing";

        // Phase 1 (EDT): collect IntelliJ-API-dependent context while still on the EDT.
        ProjectContextBuilder.IdeSnapshot ideSnapshot =
                new ProjectContextBuilder(project).collectSnapshot();

        // Mutable snapshot list — the daemon thread will fill in the enriched last entry.
        List<ChatMessage> snapshot = new ArrayList<>(history);

        final String capturedText = text; // effectively final for lambda capture
        daemon(() -> {
            streamThread = Thread.currentThread();
            try {
                // Phase 2 (daemon): run git commands (blocking I/O, safe off EDT).
                String gitSection = new GitContextBuilder(project).buildGitSection(capturedText);

                // Build the full enriched prompt entirely off the EDT.
                String enriched = ProjectContextBuilder.buildPrompt(
                        capturedText, mode, priorHistory, ideSnapshot, gitSection);
                // Preserve images from the original user message in the enriched entry.
                ChatMessage original = snapshot.get(snapshot.size() - 1);
                snapshot.set(snapshot.size() - 1, new ChatMessage("user", enriched, original.images()));

                Consumer<String> onToken = token -> SwingUtilities.invokeLater(() -> appendToken(token));

                if (enriched.length() > MAX_SINGLE_PROMPT) {
                    sendChunked(endpoint, model, snapshot, enriched, original.images(), onToken);
                } else {
                    new LMStudioClient(endpoint).streamChat(model, snapshot, onToken);
                }

                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    history.add(new ChatMessage("assistant", assistantBuffer.toString()));
                    setLoading(false);
                    maybeGenerateTitle(capturedText, endpoint, model);
                });
            } catch (Exception ex) {
                boolean wasStopped = Thread.currentThread().isInterrupted()
                        || ex.getCause() instanceof InterruptedException
                        || ex instanceof java.io.InterruptedIOException;
                final String partial = assistantBuffer.toString();
                SwingUtilities.invokeLater(() -> {
                    finalizeAssistantMessage();
                    if (wasStopped) {
                        // Save partial response so the LLM can see what was already generated
                        if (!partial.isBlank()) {
                            history.add(new ChatMessage("assistant", partial));
                        }
                        // Silent context note — not shown in UI, but sent to LLM on next turn.
                        // Enables "resume" to continue from the exact cutoff point.
                        history.add(new ChatMessage("user",
                                "[SYSTEM NOTE: The above assistant response was interrupted by " +
                                "the user mid-generation. The partial content above is what was " +
                                "generated before the stop. If the user asks to 'resume', " +
                                "'continue', or 'keep going', seamlessly continue from the exact " +
                                "cutoff point without repeating anything already written.]"));
                    } else {
                        appendSystemMessage("Error: " + ex.getMessage());
                    }
                    setLoading(false);
                });
            } finally {
                streamThread = null;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Document helpers — EDT only
    // -------------------------------------------------------------------------

    private void appendUserMessage(String text, List<byte[]> images) {
        insert("You\n", userRoleStyle);
        insert(text + "\n", userTextStyle);
        if (!images.isEmpty()) {
            insert("[" + images.size() + " image" + (images.size() > 1 ? "s" : "") + " attached]\n", systemStyle);
        }
        insert("\n", userTextStyle);
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
        if (loading) {
            sendBtn.setText("■");
            sendBtn.setBackground(new Color(0xC0392B));
            sendBtn.setToolTipText("Stop generation (Enter)");
        } else {
            sendBtn.setText("▶");
            sendBtn.setBackground(new Color(0x3574F0));
            sendBtn.setToolTipText("Send (Enter)");
        }
        spinner.setIndeterminate(loading);
        spinner.setVisible(loading);
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Chunked sending — transparent to the user; no UI indicators shown
    // -------------------------------------------------------------------------

    /**
     * Splits {@code enriched} into chunks and sends them sequentially.
     * Each intermediate chunk requests a silent acknowledgement before the next
     * is sent. The final chunk streams its response to the user via {@code onToken}.
     */
    private void sendChunked(String endpoint, String model,
                              List<ChatMessage> baseSnapshot, String enriched,
                              List<byte[]> firstImages, Consumer<String> onToken) throws Exception {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < enriched.length(); i += CHUNK_SIZE) {
            chunks.add(enriched.substring(i, Math.min(i + CHUNK_SIZE, enriched.length())));
        }
        int total = chunks.size();

        // Work on a fresh conversation list that excludes the oversized enriched entry
        List<ChatMessage> conv = new ArrayList<>(baseSnapshot.subList(0, baseSnapshot.size() - 1));

        for (int i = 0; i < total; i++) {
            boolean isLast = (i == total - 1);

            String header = isLast
                    ? "Part " + (i + 1) + " of " + total + " — FINAL:\n\n"
                    : "Part " + (i + 1) + " of " + total + ":\n\n";
            String footer = isLast
                    ? "\n\nAll context delivered. Please now fulfill the original request."
                    : "\n\n[Acknowledge with a single 'OK'. More context follows — do not respond yet.]";

            List<byte[]> msgImages = (i == 0) ? firstImages : List.of();
            conv.add(new ChatMessage("user", header + chunks.get(i) + footer, msgImages));

            if (!isLast) {
                // Collect ack silently on the daemon thread — nothing shown in the UI
                StringBuilder ack = new StringBuilder();
                new LMStudioClient(endpoint).streamChat(model, conv, ack::append);
                conv.add(new ChatMessage("assistant", ack.toString().trim()));
            }
        }

        // Stream the final response to the user
        new LMStudioClient(endpoint).streamChat(model, conv, onToken);
    }

    // -------------------------------------------------------------------------
    // Conversation title auto-generation
    // -------------------------------------------------------------------------

    /**
     * Fires a lightweight background LLM call after the first assistant response
     * to generate a short conversation title. Runs only once per conversation.
     */
    private void maybeGenerateTitle(String firstUserText, String endpoint, String model) {
        if (titleGenerated || model == null || model.isBlank()) return;
        titleGenerated = true;
        daemon(() -> {
            try {
                String prompt = "Generate a concise coding task title (3-5 words, no quotes, no punctuation). "
                        + "Return ONLY the title:\n\n"
                        + firstUserText.substring(0, Math.min(300, firstUserText.length()));

                StringBuilder sb = new StringBuilder();
                new LMStudioClient(endpoint).streamChat(
                        model, List.of(new ChatMessage("user", prompt)), sb::append);

                String title = sb.toString().trim()
                        .replaceAll("(?i)^title:\\s*", "")
                        .replaceAll("[\"'\n].*", "")   // take first line, strip quotes
                        .trim();

                if (!title.isBlank() && title.length() <= 60) {
                    SwingUtilities.invokeLater(() -> titleLabel.setText("  " + title));
                }
            } catch (Exception ignored) {}
        });
    }

    // -------------------------------------------------------------------------
    // Image attachment helpers
    // -------------------------------------------------------------------------

    /** Add a raw image byte array to the pending list and refresh the preview. */
    private void addPendingImage(byte[] imgBytes) {
        pendingImages.add(imgBytes);
        refreshImagePreview();
    }

    /** Rebuild the thumbnail strip from {@code pendingImages}. */
    private void refreshImagePreview() {
        imagePreviewPanel.removeAll();
        for (byte[] img : new ArrayList<>(pendingImages)) {
            imagePreviewPanel.add(buildThumbnail(img));
        }
        imagePreviewPanel.setVisible(!pendingImages.isEmpty());
        imagePreviewPanel.revalidate();
        imagePreviewPanel.repaint();
    }

    /** Build a 64×64 thumbnail panel with a remove (×) button. */
    private JPanel buildThumbnail(byte[] imgBytes) {
        JPanel cell = new JPanel(new BorderLayout(0, 0));
        cell.setPreferredSize(new Dimension(70, 82));
        cell.setBorder(BorderFactory.createLineBorder(new Color(0x555555), 1));

        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imgBytes));
            if (bi != null) {
                Image scaled = bi.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                cell.add(new JLabel(new ImageIcon(scaled)), BorderLayout.CENTER);
            }
        } catch (Exception ignored) {}

        JButton remove = new JButton("×");
        remove.setFont(remove.getFont().deriveFont(Font.BOLD, 10f));
        remove.setPreferredSize(new Dimension(70, 16));
        remove.setBorderPainted(false);
        remove.setContentAreaFilled(false);
        remove.setFocusPainted(false);
        remove.addActionListener(e -> {
            pendingImages.remove(imgBytes);
            refreshImagePreview();
        });
        cell.add(remove, BorderLayout.NORTH);
        return cell;
    }

    /** Try to paste an image from the system clipboard. Returns true if an image was found. */
    private boolean tryPasteImageFromClipboard() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.imageFlavor)) return false;
            Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
            addPendingImage(toPngBytes(img));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Open a file chooser to pick an image file. */
    private void chooseImageFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select Image");
        fc.setAcceptAllFileFilterUsed(false);
        fc.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (PNG, JPG, JPEG, WEBP)", "png", "jpg", "jpeg", "webp"));
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            try {
                addPendingImage(Files.readAllBytes(fc.getSelectedFile().toPath()));
            } catch (Exception ignored) {}
        }
    }

    /** Convert any AWT Image to PNG bytes. */
    private static byte[] toPngBytes(Image img) throws IOException {
        BufferedImage bi;
        if (img instanceof BufferedImage) {
            bi = (BufferedImage) img;
        } else {
            bi = new BufferedImage(
                    img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bi, "PNG", out);
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------

    public JPanel getSwingComponent() {
        return root;
    }

    // Returns the first font family name that is actually installed on this system.
    private static String availableFont(String... candidates) {
        java.util.Set<String> installed = new java.util.HashSet<>(java.util.Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : candidates) {
            if (installed.contains(f)) return f;
        }
        return Font.SANS_SERIF;
    }
}
