package plugin.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import plugin.context.FileContextReader;
import plugin.context.ProjectContextBuilder;
import plugin.llm.LLMClient;
import plugin.llm.LMStudioClient;
import plugin.llm.OllamaClient;
import plugin.llm.model.ChatMessage;
import plugin.llm.model.ImageAttachment;
import plugin.llm.model.TextAttachment;
import plugin.settings.PluginSettings;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class ChatPanel extends JPanel {

    private static final String[] IMAGE_EXTS = {"jpg", "jpeg", "png", "gif", "webp"};
    private static final String[] TEXT_EXTS = {
        "java", "kt", "go", "py", "txt", "md", "json", "yaml", "yml",
        "xml", "html", "ts", "js", "css", "sql", "sh", "toml", "gradle", "properties"
    };

    private final Project project;
    private final List<ChatMessage> history = new ArrayList<>();
    private final List<ImageAttachment> pendingImages = new ArrayList<>();
    private final List<TextAttachment> pendingFiles = new ArrayList<>();
    private final FileContextReader contextReader;
    private final ProjectContextBuilder contextBuilder;

    private JPanel messagesPanel;
    private JScrollPane messagesScroll;
    private JTextArea inputArea;
    private JButton sendButton;
    private JButton stopButton;
    private JButton clearButton;
    private JPanel attachmentPanel;
    private Thread streamingThread;

    public ChatPanel(Project project) {
        this.project = project;
        this.contextReader = new FileContextReader(project);
        this.contextBuilder = new ProjectContextBuilder(project);
        setLayout(new BorderLayout());
        buildUI();
        checkBackendAvailability();
    }

    private void buildUI() {
        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(new Color(25, 25, 35));
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        messagesScroll = new JScrollPane(messagesPanel);
        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);
        messagesScroll.setBackground(new Color(25, 25, 35));

        JPanel toolbar = buildToolbar();
        add(toolbar, BorderLayout.NORTH);
        add(messagesScroll, BorderLayout.CENTER);
        add(buildInputPanel(), BorderLayout.SOUTH);

        setupFileDrop();
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(4, 0));
        toolbar.setBackground(new Color(35, 35, 48));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        clearButton = new JButton("Clear");
        clearButton.setFont(clearButton.getFont().deriveFont(11f));
        clearButton.addActionListener(e -> clearConversation());

        JLabel backendLabel = new JLabel("●");
        backendLabel.setForeground(new Color(80, 200, 80));
        backendLabel.setFont(backendLabel.getFont().deriveFont(14f));
        backendLabel.setToolTipText("Backend status");

        toolbar.add(backendLabel, BorderLayout.WEST);
        toolbar.add(clearButton, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel buildInputPanel() {
        attachmentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        attachmentPanel.setBackground(new Color(30, 30, 45));
        attachmentPanel.setVisible(false);

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputArea.setBackground(new Color(40, 40, 55));
        inputArea.setForeground(new Color(220, 220, 220));
        inputArea.setCaretColor(Color.WHITE);
        inputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                }
            }
        });

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_V) {
                    if (pasteImageFromClipboard()) {
                        e.consume();
                    }
                }
            }
        });

        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());

        stopButton = new JButton("Stop");
        stopButton.setBackground(new Color(160, 50, 50));
        stopButton.setForeground(Color.WHITE);
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> stopStreaming());

        JButton attachButton = new JButton("📎");
        attachButton.setToolTipText("Attach file or image");
        attachButton.addActionListener(e -> openFilePicker());

        JPanel buttonCol = new JPanel(new GridLayout(3, 1, 0, 2));
        buttonCol.setOpaque(false);
        buttonCol.add(sendButton);
        buttonCol.add(stopButton);
        buttonCol.add(attachButton);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBackground(new Color(35, 35, 48));
        inputRow.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputRow.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        inputRow.add(buttonCol, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(30, 30, 45));
        bottomPanel.add(attachmentPanel, BorderLayout.NORTH);
        bottomPanel.add(inputRow, BorderLayout.CENTER);
        return bottomPanel;
    }

    private void checkBackendAvailability() {
        new Thread(() -> {
            LLMClient client = buildClient();
            boolean available = client.isAvailable();
            SwingUtilities.invokeLater(() -> {
                if (!available) {
                    addSystemMessage("⚠ Backend not available. Start Ollama or LM Studio and check Settings.");
                }
            });
        }, "backend-check").start();
    }

    private void addSystemMessage(String text) {
        MessageBubble bubble = new MessageBubble(ChatMessage.Role.ASSISTANT, text, project);
        messagesPanel.add(bubble);
        messagesPanel.add(Box.createVerticalStrut(4));
        messagesPanel.revalidate();
    }

    public void sendMessage() {
        String rawText = inputArea.getText().trim();
        if (rawText.isEmpty() && pendingImages.isEmpty() && pendingFiles.isEmpty()) return;

        StringBuilder fullText = new StringBuilder();
        for (TextAttachment ta : pendingFiles) {
            fullText.append(ta.toPromptString()).append("\n");
        }

        if (rawText.contains("@file")) {
            String fileContent = contextReader.getCurrentFileContent();
            String fileName = contextReader.getCurrentFileName();
            if (fileContent != null && fileName != null) {
                fullText.append("[Current file: ").append(fileName).append("]\n```\n")
                        .append(fileContent).append("\n```\n\n");
            }
            rawText = rawText.replace("@file", "").trim();
        }
        if (rawText.contains("@selection")) {
            String selection = contextReader.getSelectedText();
            if (selection != null) {
                fullText.append("[Selected text]\n```\n").append(selection).append("\n```\n\n");
            }
            rawText = rawText.replace("@selection", "").trim();
        }
        if (rawText.contains("@project")) {
            fullText.append(contextBuilder.buildFileTree()).append("\n");
            rawText = rawText.replace("@project", "").trim();
        }

        fullText.append(contextBuilder.injectContext(rawText));
        String messageWithContext = fullText.toString().trim();
        if (messageWithContext.isEmpty()) return;

        inputArea.setText("");

        List<String> imageBase64List = new ArrayList<>();
        for (ImageAttachment img : pendingImages) {
            imageBase64List.add(img.getBase64Data());
        }

        List<ImageAttachment> userImages = new ArrayList<>(pendingImages);
        ChatMessage userMsg = new ChatMessage(ChatMessage.Role.USER, rawText.isEmpty() ? "[image]" : rawText, userImages);
        history.add(userMsg);

        MessageBubble userBubble = new MessageBubble(ChatMessage.Role.USER, rawText.isEmpty() ? "[image]" : rawText, userImages, project);
        appendComponent(userBubble);

        clearAttachments();

        ChatMessage contextMsg = new ChatMessage(ChatMessage.Role.USER, messageWithContext, userImages);
        List<ChatMessage> payload = new ArrayList<>(history.subList(0, history.size() - 1));
        payload.add(contextMsg);

        MessageBubble assistantBubble = new MessageBubble(ChatMessage.Role.ASSISTANT, "", project);
        appendComponent(assistantBubble);

        sendButton.setEnabled(false);
        stopButton.setVisible(true);

        StringBuilder fullResponse = new StringBuilder();
        PluginSettings settings = PluginSettings.getInstance();
        LLMClient client = buildClient();

        streamingThread = new Thread(() -> {
            client.streamChat(
                payload,
                settings.getSystemPrompt(),
                imageBase64List,
                token -> SwingUtilities.invokeLater(() -> {
                    fullResponse.append(token);
                    assistantBubble.appendToken(token);
                    scrollToBottom();
                }),
                () -> SwingUtilities.invokeLater(() -> {
                    history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, fullResponse.toString()));
                    sendButton.setEnabled(true);
                    stopButton.setVisible(false);
                    streamingThread = null;
                    scrollToBottom();
                }),
                err -> SwingUtilities.invokeLater(() -> {
                    assistantBubble.setText("[Error: " + err.getMessage() + "]");
                    sendButton.setEnabled(true);
                    stopButton.setVisible(false);
                    streamingThread = null;
                })
            );
        }, "llm-stream");
        streamingThread.setDaemon(true);
        streamingThread.start();
    }

    public void sendText(String text) {
        SwingUtilities.invokeLater(() -> {
            inputArea.setText(text);
            sendMessage();
        });
    }

    private void stopStreaming() {
        if (streamingThread != null) {
            streamingThread.interrupt();
        }
        sendButton.setEnabled(true);
        stopButton.setVisible(false);
    }

    private void clearConversation() {
        history.clear();
        messagesPanel.removeAll();
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private boolean pasteImageFromClipboard() {
        try {
            Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                return false;
            }
            Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
            BufferedImage buffered = toBufferedImage(image);
            BufferedImage thumbnail = scaleThumbnail(buffered, 120);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            ImageAttachment attachment = new ImageAttachment("clipboard.png", base64, "image/png", thumbnail);
            addImageChip(attachment);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openFilePicker() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(false);

        javax.swing.filechooser.FileNameExtensionFilter imageFilter =
            new javax.swing.filechooser.FileNameExtensionFilter("Images", IMAGE_EXTS);
        javax.swing.filechooser.FileNameExtensionFilter textFilter =
            new javax.swing.filechooser.FileNameExtensionFilter("Text Files", TEXT_EXTS);

        chooser.addChoosableFileFilter(imageFilter);
        chooser.addChoosableFileFilter(textFilter);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(textFilter);

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        String name = file.getName().toLowerCase();

        if (isImageFile(name)) {
            try {
                BufferedImage img = ImageIO.read(file);
                BufferedImage thumbnail = scaleThumbnail(img, 120);
                byte[] bytes = Files.readAllBytes(file.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String mime = name.endsWith(".png") ? "image/png" :
                              name.endsWith(".gif") ? "image/gif" :
                              name.endsWith(".webp") ? "image/webp" : "image/jpeg";
                addImageChip(new ImageAttachment(file.getName(), base64, mime, thumbnail));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Failed to load image: " + e.getMessage());
            }
        } else {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                addTextChip(new TextAttachment(file.getName(), content));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Failed to read file: " + e.getMessage());
            }
        }
    }

    private void addImageChip(ImageAttachment attachment) {
        pendingImages.add(attachment);
        FileChip chip = new FileChip(attachment, () -> {
            pendingImages.remove(attachment);
            rebuildChips();
        });
        attachmentPanel.add(chip);
        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
    }

    private void addTextChip(TextAttachment attachment) {
        pendingFiles.add(attachment);
        FileChip chip = new FileChip(attachment, () -> {
            pendingFiles.remove(attachment);
            rebuildChips();
        });
        attachmentPanel.add(chip);
        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
    }

    private void rebuildChips() {
        attachmentPanel.removeAll();
        for (ImageAttachment img : pendingImages) {
            attachmentPanel.add(new FileChip(img, () -> { pendingImages.remove(img); rebuildChips(); }));
        }
        for (TextAttachment txt : pendingFiles) {
            attachmentPanel.add(new FileChip(txt, () -> { pendingFiles.remove(txt); rebuildChips(); }));
        }
        attachmentPanel.setVisible(attachmentPanel.getComponentCount() > 0);
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    private void clearAttachments() {
        pendingImages.clear();
        pendingFiles.clear();
        attachmentPanel.removeAll();
        attachmentPanel.setVisible(false);
        attachmentPanel.revalidate();
    }

    public void attachVirtualFile(VirtualFile vf) {
        contextBuilder.attachFile(vf);
        try {
            String content = new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
            addTextChip(new TextAttachment(vf.getName(), content));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to attach file: " + e.getMessage());
        }
    }

    private void appendComponent(JComponent component) {
        SwingUtilities.invokeLater(() -> {
            messagesPanel.add(component);
            messagesPanel.add(Box.createVerticalStrut(6));
            messagesPanel.revalidate();
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = messagesScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private LLMClient buildClient() {
        PluginSettings s = PluginSettings.getInstance();
        if (s.getSelectedBackend() == PluginSettings.Backend.OLLAMA) {
            return new OllamaClient(s.getOllamaBaseUrl(), s.getSelectedModel());
        }
        return new LMStudioClient(s.getLmStudioBaseUrl(), s.getSelectedModel(),
                s.getMaxTokens(), s.getTemperature());
    }

    private boolean isImageFile(String name) {
        for (String ext : IMAGE_EXTS) {
            if (name.endsWith("." + ext)) return true;
        }
        return false;
    }

    private BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage bi) return bi;
        BufferedImage bi = new BufferedImage(
            img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return bi;
    }

    private BufferedImage scaleThumbnail(BufferedImage src, int maxSize) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        if (w > h) {
            h = h * maxSize / w;
            w = maxSize;
        } else {
            w = w * maxSize / h;
            h = maxSize;
        }
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private void setupFileDrop() {
        setDropTarget(new DropTarget() {
            @Override
            public synchronized void drop(DropTargetDropEvent event) {
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    @SuppressWarnings("unchecked")
                    List<java.io.File> files = (List<java.io.File>)
                        event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (java.io.File f : files) {
                        VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(f);
                        if (vf != null) attachVirtualFile(vf);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }
}
