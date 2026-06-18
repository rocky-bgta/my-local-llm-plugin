package plugin.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileOperationUtil {

    private static final Pattern FILE_OP_PATTERN = Pattern.compile(
            "<(CREATE_FILE|MODIFY_FILE)\\s+path=\"([^\"]+)\">\\s*(.*?)\\s*</\\1>", Pattern.DOTALL);
    private static final Pattern FOLDER_OP_PATTERN = Pattern.compile(
            "<CREATE_FOLDER\\s+path=\"([^\"]+)\"\\s*/>");

    public static void processFileOperations(Project project, String response) {
        // Handle folder creation
        Matcher folderMatcher = FOLDER_OP_PATTERN.matcher(response);
        while (folderMatcher.find()) {
            String path = folderMatcher.group(1).trim();
            createFolder(project, path);
        }

        // Handle file creation/modification
        Matcher fileMatcher = FILE_OP_PATTERN.matcher(response);
        while (fileMatcher.find()) {
            String type = fileMatcher.group(1);
            String path = fileMatcher.group(2).trim();
            String content = fileMatcher.group(3).trim();
            writeFile(project, path, content);
        }
    }

    private static void createFolder(Project project, String relativePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    VirtualFile baseDir = project.getBaseDir();
                    if (baseDir == null) return;
                    
                    String[] parts = relativePath.replace("\\", "/").split("/");
                    VirtualFile current = baseDir;
                    for (String part : parts) {
                        if (part.isEmpty()) continue;
                        VirtualFile child = current.findChild(part);
                        if (child == null) {
                            child = current.createChildDirectory(null, part);
                        }
                        current = child;
                    }
                } catch (IOException e) {
                    // Log error
                }
            });
        });
    }

    private static void writeFile(Project project, String relativePath, String content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    VirtualFile baseDir = project.getBaseDir();
                    if (baseDir == null) return;

                    // Ensure we are working with forward slashes for cross-platform compatibility
                    String normalizedPath = relativePath.replace("\\", "/");
                    File file = new File(baseDir.getPath(), normalizedPath);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
                    if (virtualFile == null) {
                        // Refresh parent to ensure it's known to VFS
                        VirtualFile parentVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent);
                        if (parentVf != null) {
                            virtualFile = parentVf.createChildData(null, file.getName());
                        }
                    }

                    if (virtualFile != null) {
                        virtualFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                        // Force refresh to show in IDE
                        virtualFile.refresh(false, false);
                    }
                } catch (IOException e) {
                    // Log error
                }
            });
        });
    }
}
