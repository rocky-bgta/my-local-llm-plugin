package plugin.ui;

import plugin.llm.model.ImageAttachment;
import plugin.llm.model.TextAttachment;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class FileChip extends JPanel {

    private final String fileName;
    private final Runnable onRemove;

    public FileChip(TextAttachment attachment, Runnable onRemove) {
        this.fileName = attachment.getFileName();
        this.onRemove = onRemove;
        build(createFileIcon(), fileName);
    }

    public FileChip(ImageAttachment attachment, Runnable onRemove) {
        this.fileName = attachment.getFileName();
        this.onRemove = onRemove;
        JLabel thumb = createThumbnailIcon(attachment);
        build(thumb, fileName);
    }

    private void build(JComponent iconComponent, String name) {
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        setOpaque(true);
        setBackground(new Color(60, 60, 80));
        setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));

        add(iconComponent);

        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));
        add(nameLabel);

        JLabel removeBtn = new JLabel("×");
        removeBtn.setForeground(new Color(180, 180, 180));
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setFont(removeBtn.getFont().deriveFont(Font.BOLD, 13f));
        removeBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onRemove.run(); }
            @Override public void mouseEntered(MouseEvent e) { removeBtn.setForeground(Color.WHITE); }
            @Override public void mouseExited(MouseEvent e) { removeBtn.setForeground(new Color(180, 180, 180)); }
        });
        add(removeBtn);
        setToolTipText(name);
    }

    private JLabel createFileIcon() {
        JLabel label = new JLabel("📄");
        label.setForeground(new Color(200, 200, 200));
        label.setFont(label.getFont().deriveFont(13f));
        return label;
    }

    private JLabel createThumbnailIcon(ImageAttachment attachment) {
        if (attachment.getThumbnail() != null) {
            Image scaled = attachment.getThumbnail().getScaledInstance(40, 40, Image.SCALE_SMOOTH);
            return new JLabel(new ImageIcon(scaled));
        }
        JLabel label = new JLabel("🖼");
        label.setForeground(new Color(200, 200, 200));
        label.setFont(label.getFont().deriveFont(13f));
        return label;
    }

    public String getFileName() {
        return fileName;
    }
}
