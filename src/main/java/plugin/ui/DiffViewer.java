package plugin.ui;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;

public class DiffViewer extends JPanel {

    private final JTextArea diffArea;

    public DiffViewer() {
        setLayout(new BorderLayout());

        diffArea = new JTextArea();
        diffArea.setEditable(false);
        diffArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        diffArea.setBackground(new Color(25, 25, 35));
        diffArea.setForeground(new Color(200, 200, 200));

        add(new JScrollPane(diffArea), BorderLayout.CENTER);
    }

    public void showDiff(String originalContent, String newContent, String fileName) {
        String diff = buildUnifiedDiff(originalContent, newContent, fileName);
        diffArea.setText(diff);
        diffArea.setCaretPosition(0);
        highlightDiffLines();
    }

    private void highlightDiffLines() {
        diffArea.getHighlighter().removeAllHighlights();
        String[] lines = diffArea.getText().split("\n");
        int offset = 0;
        for (String line : lines) {
            Color highlight = null;
            if (line.startsWith("+") && !line.startsWith("+++")) {
                highlight = new Color(0, 80, 0, 140);
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                highlight = new Color(110, 0, 0, 140);
            } else if (line.startsWith("@@")) {
                highlight = new Color(0, 60, 100, 120);
            }
            if (highlight != null) {
                try {
                    diffArea.getHighlighter().addHighlight(offset, offset + line.length(),
                            new DefaultHighlighter.DefaultHighlightPainter(highlight));
                } catch (BadLocationException ignored) {
                }
            }
            offset += line.length() + 1;
        }
    }

    private String buildUnifiedDiff(String original, String updated, String fileName) {
        String[] originalLines = original.split("\n", -1);
        String[] updatedLines = updated.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(fileName).append("\n");
        sb.append("+++ b/").append(fileName).append("\n");

        int i = 0, j = 0;
        while (i < originalLines.length || j < updatedLines.length) {
            if (i < originalLines.length && j < updatedLines.length
                    && originalLines[i].equals(updatedLines[j])) {
                sb.append(" ").append(originalLines[i]).append("\n");
                i++;
                j++;
            } else {
                if (i < originalLines.length) {
                    sb.append("-").append(originalLines[i]).append("\n");
                    i++;
                }
                if (j < updatedLines.length) {
                    sb.append("+").append(updatedLines[j]).append("\n");
                    j++;
                }
            }
        }
        return sb.toString();
    }
}
