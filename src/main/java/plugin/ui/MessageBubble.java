package plugin.ui;

import com.intellij.openapi.project.Project;
import plugin.llm.model.ChatMessage;
import plugin.llm.model.ImageAttachment;
import plugin.settings.PluginSettings;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageBubble extends JPanel {

    private static final Pattern FILE_BLOCK_PATTERN =
        Pattern.compile("```(\\w+):([^\\n]+)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK_PATTERN =
        Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatMessage.Role role;
    private final Project project;
    private final List<ImageAttachment> images;
    private final StringBuilder rawContent;
    private JPanel contentPanel;

    public MessageBubble(ChatMessage.Role role, String initialContent, Project project) {
        this(role, initialContent, List.of(), project);
    }

    public MessageBubble(ChatMessage.Role role, String initialContent,
                         List<ImageAttachment> images, Project project) {
        this.role = role;
        this.project = project;
        this.images = images;
        this.rawContent = new StringBuilder(initialContent);

        setLayout(new BorderLayout());
        setOpaque(false);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        contentPanel = buildContent(initialContent);

        JPanel wrapper = new JPanel(new FlowLayout(
                role == ChatMessage.Role.USER ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.add(contentPanel);

        add(wrapper, BorderLayout.CENTER);

        JLabel timestamp = new JLabel(LocalTime.now().format(TIME_FMT));
        timestamp.setForeground(new Color(120, 120, 130));
        timestamp.setFont(timestamp.getFont().deriveFont(10f));
        timestamp.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
        JPanel tsPanel = new JPanel(new FlowLayout(
                role == ChatMessage.Role.USER ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        tsPanel.setOpaque(false);
        tsPanel.add(timestamp);
        add(tsPanel, BorderLayout.SOUTH);
    }

    public void appendToken(String token) {
        rawContent.append(token);
        rebuildContent();
    }

    public void setText(String text) {
        rawContent.setLength(0);
        rawContent.append(text);
        rebuildContent();
    }

    private void rebuildContent() {
        JPanel newContent = buildContent(rawContent.toString());
        BorderLayout layout = (BorderLayout) getLayout();
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);
        if (center != null) remove(center);

        JPanel wrapper = new JPanel(new FlowLayout(
                role == ChatMessage.Role.USER ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.add(newContent);
        add(wrapper, BorderLayout.CENTER);
        contentPanel = newContent;
        revalidate();
        repaint();
    }

    private JPanel buildContent(String text) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setBackground(role == ChatMessage.Role.USER
                ? new Color(50, 50, 70) : new Color(40, 44, 52));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        for (ImageAttachment img : images) {
            panel.add(buildImagePanel(img));
        }

        int lastEnd = 0;
        Matcher fileMatcher = FILE_BLOCK_PATTERN.matcher(text);

        while (fileMatcher.find()) {
            if (fileMatcher.start() > lastEnd) {
                String before = text.substring(lastEnd, fileMatcher.start());
                addTextSegments(panel, before);
            }
            String lang = fileMatcher.group(1);
            String filePath = fileMatcher.group(2).trim();
            String code = fileMatcher.group(3);
            panel.add(buildFileCodeBlock(lang, filePath, code));
            lastEnd = fileMatcher.end();
        }

        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            int segEnd = 0;
            Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(remaining);
            while (codeMatcher.find()) {
                if (codeMatcher.start() > segEnd) {
                    addTextSegments(panel, remaining.substring(segEnd, codeMatcher.start()));
                }
                panel.add(buildPlainCodeBlock(codeMatcher.group(1), codeMatcher.group(2)));
                segEnd = codeMatcher.end();
            }
            if (segEnd < remaining.length()) {
                addTextSegments(panel, remaining.substring(segEnd));
            }
        }

        if (panel.getComponentCount() == 0) {
            panel.add(makeTextLabel(""));
        }
        return panel;
    }

    private void addTextSegments(JPanel panel, String text) {
        if (text.isBlank()) return;
        JLabel label = makeTextLabel(toSimpleHtml(text));
        panel.add(label);
    }

    private JPanel buildFileCodeBlock(String lang, String filePath, String code) {
        JPanel block = new JPanel(new BorderLayout(0, 0));
        block.setOpaque(true);
        block.setBackground(new Color(25, 28, 35));
        block.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 90), 1));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        header.setBackground(new Color(35, 38, 48));
        JLabel langLabel = new JLabel(lang);
        langLabel.setForeground(new Color(150, 150, 170));
        langLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JLabel pathLabel = new JLabel(filePath);
        pathLabel.setForeground(new Color(100, 180, 255));
        pathLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        header.add(langLabel);
        header.add(pathLabel);
        block.add(header, BorderLayout.NORTH);

        JTextArea codeArea = buildCodeArea(code);
        block.add(new JScrollPane(codeArea), BorderLayout.CENTER);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttonBar.setBackground(new Color(35, 38, 48));

        JButton copyBtn = new JButton("📋 Copy");
        styleButton(copyBtn);
        copyBtn.addActionListener(e ->
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code), null));

        JButton applyBtn = new JButton("📝 Apply to File");
        styleButton(applyBtn);
        applyBtn.setBackground(new Color(0, 80, 40));
        String capturedPath = filePath;
        String capturedCode = code;
        applyBtn.addActionListener(e -> {
            if (project != null) {
                if (PluginSettings.getInstance().isAutoApplyEdits()) {
                    com.intellij.openapi.vfs.LocalFileSystem lfs =
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance();
                    String base = project.getBasePath();
                    java.io.File ioFile = base != null
                        ? new java.io.File(base, capturedPath) : new java.io.File(capturedPath);
                    com.intellij.openapi.vfs.VirtualFile vf = lfs.findFileByIoFile(ioFile);
                    if (vf != null) {
                        FileEditor.applyDirectly(project, vf, capturedCode);
                    } else {
                        FileEditor.createNewFile(project, capturedPath, capturedCode);
                    }
                } else {
                    FileEditor.showDiffAndApply(project, capturedPath, capturedCode);
                }
            }
        });

        buttonBar.add(copyBtn);
        buttonBar.add(applyBtn);
        block.add(buttonBar, BorderLayout.SOUTH);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    private JPanel buildPlainCodeBlock(String lang, String code) {
        JPanel block = new JPanel(new BorderLayout(0, 0));
        block.setOpaque(true);
        block.setBackground(new Color(25, 28, 35));
        block.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 90), 1));

        if (lang != null && !lang.isBlank()) {
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            header.setBackground(new Color(35, 38, 48));
            JLabel langLabel = new JLabel(lang);
            langLabel.setForeground(new Color(150, 150, 170));
            langLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            header.add(langLabel);
            block.add(header, BorderLayout.NORTH);
        }

        JTextArea codeArea = buildCodeArea(code);
        block.add(new JScrollPane(codeArea), BorderLayout.CENTER);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttonBar.setBackground(new Color(35, 38, 48));
        JButton copyBtn = new JButton("📋 Copy");
        styleButton(copyBtn);
        copyBtn.addActionListener(e ->
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code), null));
        buttonBar.add(copyBtn);
        block.add(buttonBar, BorderLayout.SOUTH);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    private JPanel buildImagePanel(ImageAttachment img) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setOpaque(false);
        if (img.getThumbnail() != null) {
            int maxW = 300;
            int w = img.getThumbnail().getWidth();
            int h = img.getThumbnail().getHeight();
            if (w > maxW) {
                h = h * maxW / w;
                w = maxW;
            }
            Image scaled = img.getThumbnail().getScaledInstance(w, h, Image.SCALE_SMOOTH);
            panel.add(new JLabel(new ImageIcon(scaled)));
        }
        return panel;
    }

    private JTextArea buildCodeArea(String code) {
        JTextArea area = new JTextArea(code);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new Color(25, 28, 35));
        area.setForeground(new Color(212, 212, 212));
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        area.setLineWrap(false);
        int lines = Math.min(code.split("\n", -1).length, 30);
        area.setRows(lines);
        return area;
    }

    private JLabel makeTextLabel(String html) {
        JLabel label = new JLabel("<html><body style='font-family:sans-serif;font-size:12px;'>"
                + html + "</body></html>");
        label.setForeground(role == ChatMessage.Role.USER
                ? Color.WHITE : new Color(220, 220, 220));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return label;
    }

    private String toSimpleHtml(String text) {
        String escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
        escaped = escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        escaped = escaped.replaceAll("\\*(.+?)\\*", "<i>$1</i>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        return escaped;
    }

    private void styleButton(JButton btn) {
        btn.setBackground(new Color(55, 60, 75));
        btn.setForeground(new Color(200, 200, 210));
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}
