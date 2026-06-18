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
    private static final Pattern DELETE_OP_PATTERN = Pattern.compile(
            "<DELETE_(FILE|FOLDER)\\s+path=\"([^\"]+)\"\\s*/>");
    private static final Pattern RUN_TESTS_PATTERN = Pattern.compile(
            "<(RUN_TESTS|CHECK_COMPILATION)(?:\\s+test=\"([^\"]+)\")?\\s*/>");
    private static final Pattern EXECUTE_COMMAND_PATTERN = Pattern.compile(
            "<EXECUTE_COMMAND\\s+command=\"([^\"]+)\"\\s*/>");

    public static class FileOpResult {
        public final boolean runTests;
        public final boolean checkCompilation;
        public final String testName;
        public final String customCommand;
        public final java.util.List<String> createdFiles;

        public FileOpResult(boolean runTests, boolean checkCompilation, String testName, String customCommand, java.util.List<String> createdFiles) {
            this.runTests = runTests;
            this.checkCompilation = checkCompilation;
            this.testName = testName;
            this.customCommand = customCommand;
            this.createdFiles = createdFiles;
        }
    }

    public static FileOpResult processFileOperations(Project project, String response) {
        boolean runTests = false;
        boolean checkCompilation = false;
        String testName = null;
        String customCommand = null;
        java.util.List<String> createdFiles = new java.util.ArrayList<>();

        // Handle folder creation
        Matcher folderMatcher = FOLDER_OP_PATTERN.matcher(response);
        while (folderMatcher.find()) {
            String path = folderMatcher.group(1).trim();
            createFolder(project, path);
        }

        // Handle deletions
        Matcher deleteMatcher = DELETE_OP_PATTERN.matcher(response);
        while (deleteMatcher.find()) {
            String path = deleteMatcher.group(2).trim();
            deletePath(project, path);
        }

        // Handle file creation/modification
        Matcher fileMatcher = FILE_OP_PATTERN.matcher(response);
        while (fileMatcher.find()) {
            String type = fileMatcher.group(1);
            String path = fileMatcher.group(2).trim();
            String content = fixPackageDeclaration(path, stripCodeFence(fileMatcher.group(3).trim()));
            writeFile(project, path, content);
            if ("CREATE_FILE".equals(type)) {
                createdFiles.add(path);
            }
        }

        // Handle test run or compilation check request
        Matcher testMatcher = RUN_TESTS_PATTERN.matcher(response);
        if (testMatcher.find()) {
            if ("RUN_TESTS".equals(testMatcher.group(1))) {
                runTests = true;
                testName = testMatcher.group(2);
            } else if ("CHECK_COMPILATION".equals(testMatcher.group(1))) {
                checkCompilation = true;
            }
        }

        // Handle custom command execution
        Matcher commandMatcher = EXECUTE_COMMAND_PATTERN.matcher(response);
        if (commandMatcher.find()) {
            customCommand = commandMatcher.group(1);
        }

        return new FileOpResult(runTests, checkCompilation, testName, customCommand, createdFiles);
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

    private static void deletePath(Project project, String relativePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    VirtualFile baseDir = project.getBaseDir();
                    if (baseDir == null) return;

                    String normalizedPath = relativePath.replace("\\", "/");
                    VirtualFile target = baseDir.findFileByRelativePath(normalizedPath);
                    if (target != null && target.exists()) {
                        target.delete(null);
                    }
                } catch (IOException e) {
                    // Log error
                }
            });
        });
    }

    private static String fixPackageDeclaration(String filePath, String content) {
        if (!filePath.endsWith(".java")) return content;

        String normalized = filePath.replace("\\", "/");
        String expectedPackage = null;
        for (String root : new String[]{"src/main/java/", "src/test/java/"}) {
            int idx = normalized.indexOf(root);
            if (idx >= 0) {
                String relative = normalized.substring(idx + root.length());
                int lastSlash = relative.lastIndexOf('/');
                expectedPackage = lastSlash > 0
                        ? relative.substring(0, lastSlash).replace("/", ".")
                        : "";
                break;
            }
        }
        if (expectedPackage == null || expectedPackage.isEmpty()) return content;

        java.util.regex.Matcher m = Pattern.compile(
                "^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE)
                .matcher(content);

        String declaration = "package " + expectedPackage + ";";
        if (m.find()) {
            if (!m.group(1).equals(expectedPackage)) {
                content = content.substring(0, m.start()) + declaration + content.substring(m.end());
            }
        } else {
            content = declaration + "\n\n" + content;
        }
        return content;
    }

    private static String stripCodeFence(String content) {
        // Remove opening fence: ```<optional-lang>\n
        content = content.replaceFirst("^```[a-zA-Z0-9]*\\r?\\n", "");
        // Remove closing fence: \n```
        content = content.replaceFirst("\\r?\\n```\\s*$", "");
        return content.trim();
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
