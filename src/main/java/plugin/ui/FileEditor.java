package plugin.ui;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileEditor {

    private FileEditor() {}

    public static void showDiffAndApply(Project project, String filePath, String newContent) {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        File absoluteFile = new File(filePath.startsWith("/") || filePath.contains(":")
                ? filePath : basePath + "/" + filePath);

        if (absoluteFile.exists()) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(absoluteFile);
            if (vf == null) return;
            try {
                String oldContent = new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
                boolean confirmed = showDiffDialog(project, vf.getName(), oldContent, newContent, filePath);
                if (confirmed) {
                    applyDirectly(project, vf, newContent);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Error reading file: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            int result = JOptionPane.showConfirmDialog(null,
                    "File does not exist. Create:\n" + filePath + "?",
                    "Create New File", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                createNewFile(project, filePath, newContent);
            }
        }
    }

    private static boolean showDiffDialog(Project project, String fileName,
                                           String oldContent, String newContent, String filePath) {
        JDialog dialog = new JDialog((Frame) null, "Proposed Changes: " + filePath, true);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(null);

        DiffViewer diffViewer = new DiffViewer();
        diffViewer.showDiff(oldContent, newContent, fileName);

        boolean[] confirmed = {false};

        JButton applyBtn = new JButton("Apply Changes");
        applyBtn.setBackground(new Color(0, 120, 0));
        applyBtn.setForeground(Color.WHITE);
        applyBtn.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dialog.dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelBtn);
        buttonPanel.add(applyBtn);

        dialog.setLayout(new BorderLayout());
        dialog.add(diffViewer, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);

        return confirmed[0];
    }

    public static void applyDirectly(Project project, VirtualFile vf, String newContent) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                vf.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(null, "Error writing file: " + e.getMessage(),
                            "Write Error", JOptionPane.ERROR_MESSAGE));
            }
        });
        VfsUtil.markDirtyAndRefresh(false, false, true, vf);
    }

    public static void createNewFile(Project project, String relativePath, String content) {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        Path fullPath = Path.of(basePath, relativePath);
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                Files.createDirectories(fullPath.getParent());
                Files.writeString(fullPath, content);
                VirtualFile vf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(fullPath);
                if (vf != null) {
                    VfsUtil.markDirtyAndRefresh(false, false, true, vf);
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(null, "Error creating file: " + e.getMessage(),
                            "Create Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }
}
