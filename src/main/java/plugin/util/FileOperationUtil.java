package plugin.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    // Path segments that indicate the LLM copied an example path verbatim
    private static final Set<String> PLACEHOLDER_SEGMENTS = Set.of(
            "path/to/", "your/path/", "placeholder/", "example/path/",
            "path/to/your/", "your/file/"
    );

    // Direct tests for these IntelliJ Platform classes should be redirected to extracted helpers.
    private static final Set<String> UNTESTABLE_TEST_CLASSES = Set.of(
            "ChatPanelTest", "ChatToolWindowFactoryTest"
    );

    // Canonical test files — the LLM must not overwrite these with wrong content.
    // If it tries to MODIFY them, validate that the content is JUnit 5 (not JUnit 4).
    private static final Set<String> PROTECTED_TEST_FILES = Set.of(
            "src/test/java/plugin/ChatMessageTest.java",
            "src/test/java/plugin/PluginSettingsTest.java",
            "src/test/java/plugin/LocalLLMClientTest.java"
    );

    public static class FileOpResult {
        public final boolean runTests;
        public final boolean checkCompilation;
        public final String testName;
        public final String customCommand;
        public final List<String> createdFiles;
        public final List<String> warnings;
        /** Keys into LLMCorrectionsUtil.RULES for each mistake that was triggered. */
        public final List<String> mistakeKeys;

        public FileOpResult(boolean runTests, boolean checkCompilation, String testName,
                            String customCommand, List<String> createdFiles,
                            List<String> warnings, List<String> mistakeKeys) {
            this.runTests = runTests;
            this.checkCompilation = checkCompilation;
            this.testName = testName;
            this.customCommand = customCommand;
            this.createdFiles = createdFiles;
            this.warnings = warnings;
            this.mistakeKeys = mistakeKeys;
        }
    }

    public static FileOpResult processFileOperations(Project project, String response) {
        boolean runTests = false;
        boolean checkCompilation = false;
        String testName = null;
        String customCommand = null;
        List<String> createdFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> mistakeKeys = new ArrayList<>();

        // Handle folder creation
        Matcher folderMatcher = FOLDER_OP_PATTERN.matcher(response);
        while (folderMatcher.find()) {
            String path = folderMatcher.group(1).trim();
            String placeholderWarning = detectPlaceholderPath(path);
            if (placeholderWarning != null) {
                warnings.add(placeholderWarning);
                mistakeKeys.add("no-placeholder-path");
                continue;
            }
            createFolder(project, path);
        }

        // Handle deletions — protected canonical test files cannot be deleted
        Matcher deleteMatcher = DELETE_OP_PATTERN.matcher(response);
        while (deleteMatcher.find()) {
            String path = deleteMatcher.group(2).trim();
            String normalized = path.replace("\\", "/");
            if (PROTECTED_TEST_FILES.contains(normalized)) {
                warnings.add("⛔ Blocked deletion of protected test file \"" + normalized + "\". " +
                             "These canonical test files may not be deleted by the LLM.");
                mistakeKeys.add("correct-test-package");
            } else {
                deletePath(project, path);
            }
        }

        // Handle file creation/modification
        Matcher fileMatcher = FILE_OP_PATTERN.matcher(response);
        while (fileMatcher.find()) {
            String type = fileMatcher.group(1);
            String path = fileMatcher.group(2).trim();
            String content = stripCodeFence(fileMatcher.group(3).trim());

            // Guard 1: reject placeholder paths (e.g. path/to/ChatPanelTest.java)
            String placeholderWarning = detectPlaceholderPath(path);
            if (placeholderWarning != null) {
                warnings.add(placeholderWarning);
                mistakeKeys.add("no-placeholder-path");
                continue;
            }

            // Guard 2: reject known IntelliJ-dependent test classes that cannot be unit-tested
            String untestableWarning = detectUntestableClass(path);
            if (untestableWarning != null) {
                warnings.add(untestableWarning);
                mistakeKeys.add("no-chatpanel-test");
                continue;
            }

            // Guard 3: auto-correct test file paths that land outside the conventional test location
            String correctedPath = autoCorrectTestPath(path, content);
            if (!correctedPath.equals(path)) {
                warnings.add("⚠ Auto-corrected path: \"" + path + "\" → \"" + correctedPath + "\"");
                mistakeKeys.add("correct-test-package");
                path = correctedPath;
            }

            // Guard 4: protect canonical test files from being overwritten with invalid content
            String invalidContentWarning = detectInvalidTestContent(path, content);
            if (invalidContentWarning != null) {
                warnings.add(invalidContentWarning);
                mistakeKeys.add("junit5-only");
                continue;
            }

            content = fixPackageDeclaration(path, content);
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

        return new FileOpResult(runTests, checkCompilation, testName, customCommand, createdFiles, warnings, mistakeKeys);
    }

    /**
     * Returns a warning string if the path contains a known placeholder segment, null otherwise.
     */
    private static String detectPlaceholderPath(String path) {
        String lower = path.replace("\\", "/").toLowerCase();
        for (String segment : PLACEHOLDER_SEGMENTS) {
            if (lower.contains(segment)) {
                return "⛔ Blocked write to placeholder path \"" + path + "\" — the LLM used an example path " +
                       "instead of a real project path. Ask it to use the correct path, e.g. " +
                       "src/test/java/plugin/FooTest.java.";
            }
        }
        return null;
    }

    private static String detectUntestableClass(String path) {
        String normalized = path.replace("\\", "/");
        String fileName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        String className = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        if (UNTESTABLE_TEST_CLASSES.contains(className)) {
            return "⛔ Blocked: " + className + " depends on IntelliJ Platform (Project / ApplicationManager) " +
                   "and should not be unit-tested directly. No file was written. " +
                   "Write tests for extracted helpers instead, for example plugin.ui.ChatPanelSupport. " +
                   "If this file exists on disk with errors, delete it with " +
                   "<DELETE_FILE path=\"" + normalized + "\" /> " +
                   "then write tests for the helper logic.";
        }
        return null;
    }

    /**
     * Rejects writes to canonical test files when the new content uses JUnit 4 syntax or
     * calls real network endpoints (both indicate the LLM generated incorrect test code).
     */
    private static String detectInvalidTestContent(String path, String content) {
        String normalized = path.replace("\\", "/");
        if (!PROTECTED_TEST_FILES.contains(normalized)) return null;

        LanguageSupportUtil.Language language = LanguageSupportUtil.detectLanguage(normalized);
        if (LanguageSupportUtil.isJvmLanguage(language)) {
            // JUnit 4 pattern: @Test(expected = ...) — not valid in JUnit 5
            if (content.contains("@Test(expected")) {
                return "⛔ Blocked overwrite of \"" + normalized + "\": content uses JUnit 4 syntax " +
                       "(@Test(expected=...)) which is not supported. Use JUnit 5: " +
                       "assertThrows(Exception.class, () -> ...).";
            }
            // Missing JUnit 5 import — likely generated without reading the source file
            if (!content.contains("org.junit.jupiter")) {
                return "⛔ Blocked overwrite of \"" + normalized + "\": content is missing JUnit 5 imports " +
                       "(org.junit.jupiter.api.Test). Read the existing file first and keep the correct imports.";
            }
        }
        return null;
    }

    /**
     * If a Java test file (*Test.java / *Tests.java) is given a path that does not start with
     * src/test/java/, reconstructs the correct path from the package declaration in its content.
     */
    private static String autoCorrectTestPath(String path, String content) {
        String normalized = path.replace("\\", "/");
        String suggested = LanguageSupportUtil.suggestedTestPath(normalized);
        if (suggested.equals(normalized)) {
            if (normalized.startsWith("src/test/") || normalized.contains("/test/")) return path;
            return path;
        }
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectLanguage(normalized);
        if (LanguageSupportUtil.isJvmLanguage(language)) {
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
            if (pkgMatcher.find()) {
                String pkgPath = pkgMatcher.group(1).replace('.', '/');
                if (normalized.startsWith("src/main/java/")) {
                    return "src/test/java/" + pkgPath + "/" + fileName;
                }
                if (normalized.startsWith("src/main/kotlin/")) {
                    return "src/test/kotlin/" + pkgPath + "/" + fileName;
                }
                if (normalized.startsWith("src/main/scala/")) {
                    return "src/test/scala/" + pkgPath + "/" + fileName;
                }
            }
        }
        return suggested;
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

        Matcher m = PACKAGE_PATTERN.matcher(content);
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

                    String normalizedPath = relativePath.replace("\\", "/");
                    File file = new File(baseDir.getPath(), normalizedPath);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        Files.createDirectories(parent.toPath());
                    }

                    Files.writeString(file.toPath(), content, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
                    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
                    if (virtualFile != null) {
                        virtualFile.refresh(false, false);
                    }
                } catch (IOException e) {
                    // Log error
                }
            });
        });
    }
}
