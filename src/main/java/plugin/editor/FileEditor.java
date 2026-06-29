package plugin.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class FileEditor {

    private final Project project;

    public FileEditor(Project project) {
        this.project = project;
    }

    public EditResult create(String relativePath, String content) {
        String basePath = project.getBasePath() != null ? project.getBasePath() : ".";
        Path target = Paths.get(basePath, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            refreshVirtualFile(target.toString());
            return EditResult.success("Created: " + relativePath);
        } catch (IOException e) {
            return EditResult.failure("Failed to create " + relativePath + ": " + e.getMessage());
        }
    }

    public EditResult modify(String relativePath, String content) {
        String basePath = project.getBasePath() != null ? project.getBasePath() : ".";
        Path target = Paths.get(basePath, relativePath);
        try {
            if (!Files.exists(target)) {
                return EditResult.failure("File not found: " + relativePath);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            refreshVirtualFile(target.toString());
            return EditResult.success("Modified: " + relativePath);
        } catch (IOException e) {
            return EditResult.failure("Failed to modify " + relativePath + ": " + e.getMessage());
        }
    }

    public EditResult delete(String relativePath) {
        String basePath = project.getBasePath() != null ? project.getBasePath() : ".";
        Path target = Paths.get(basePath, relativePath);
        try {
            Files.deleteIfExists(target);
            return EditResult.success("Deleted: " + relativePath);
        } catch (IOException e) {
            return EditResult.failure("Failed to delete " + relativePath + ": " + e.getMessage());
        }
    }

    public String read(String relativePath) throws IOException {
        String basePath = project.getBasePath() != null ? project.getBasePath() : ".";
        Path target = Paths.get(basePath, relativePath);
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    private void refreshVirtualFile(String absolutePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
            if (vf != null) vf.refresh(false, false);
        });
    }

    public record EditResult(boolean success, String message) {
        static EditResult success(String msg) { return new EditResult(true, msg); }
        static EditResult failure(String msg) { return new EditResult(false, msg); }
    }
}
