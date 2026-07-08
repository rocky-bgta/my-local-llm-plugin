package plugin.util;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;

public class FileOperationUtil {

    private static final Pattern FILE_OP_PATTERN = Pattern.compile(
            "<(CREATE_FILE|MODIFY_FILE)\\s+path=\"([^\"]+)\">\\s*(.*?)\\s*</\\1>", Pattern.DOTALL);
    private static final Pattern PARTIAL_FILE_OP_PATTERN = Pattern.compile(
            "<(CREATE_FILE|MODIFY_FILE)\\s+path=\"([^\"]+)\">", Pattern.DOTALL);
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
    private static final Pattern XML_FILE_OPERATION_CLOSE_PATTERN = Pattern.compile(
            "\\s*</(?:CREATE_FILE|MODIFY_FILE)>\\s*$", Pattern.DOTALL);

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
        public final List<String> appliedFiles;
        public final List<String> createdFiles;
        public final List<FileSnapshot> fileSnapshots;
        public final List<String> warnings;
        /** Keys into LLMCorrectionsUtil.RULES for each mistake that was triggered. */
        public final List<String> mistakeKeys;

        public FileOpResult(boolean runTests, boolean checkCompilation, String testName,
                            String customCommand, List<String> appliedFiles, List<String> createdFiles,
                            List<FileSnapshot> fileSnapshots, List<String> warnings, List<String> mistakeKeys) {
            this.runTests = runTests;
            this.checkCompilation = checkCompilation;
            this.testName = testName;
            this.customCommand = customCommand;
            this.appliedFiles = appliedFiles;
            this.createdFiles = createdFiles;
            this.fileSnapshots = fileSnapshots;
            this.warnings = warnings;
            this.mistakeKeys = mistakeKeys;
        }
    }

    public record FileSnapshot(String path, boolean existed, String content) {}

    // ── Streaming file-write API ──────────────────────────────────────────────

    /**
     * A complete file-op extracted while the LLM response is still streaming.
     * {@code endOffset} is the position in the original buffer immediately after this match,
     * so the caller can advance its scan pointer without re-scanning consumed content.
     */
    public record StreamFileOp(String type, String path, String content, int endOffset) {}

    /**
     * Scans {@code buffer} from {@code fromOffset} for complete XML file-op blocks and
     * returns every one found.  Does not mutate any state; safe to call from the EDT.
     */
    public static List<StreamFileOp> pollCompleteFileOps(String buffer, int fromOffset) {
        if (fromOffset >= buffer.length()) return Collections.emptyList();
        List<StreamFileOp> ops = new ArrayList<>();
        Matcher m = FILE_OP_PATTERN.matcher(buffer);
        m.region(fromOffset, buffer.length());
        while (m.find()) {
            String type    = m.group(1);
            String path    = m.group(2).trim();
            String content = normalizeFileOperationContent(m.group(3).trim());
            ops.add(new StreamFileOp(type, path, content, m.end()));
        }
        return ops;
    }

    /**
     * Validates and writes a single file-op that was detected during streaming.
     * Runs on whatever thread the caller is on (EDT only — same contract as
     * {@link #processFileOperations}).
     */
    public static FileOpResult applyStreamedFileOp(Project project, StreamFileOp op) {
        List<String> appliedFiles  = new ArrayList<>();
        List<String> createdFiles  = new ArrayList<>();
        List<FileSnapshot> fileSnapshots = new ArrayList<>();
        List<String> warnings    = new ArrayList<>();
        List<String> mistakeKeys = new ArrayList<>();
        processSingleFileOp(project, op.type(), op.path(), op.content(),
                            appliedFiles, createdFiles, fileSnapshots, warnings, mistakeKeys);
        return new FileOpResult(false, false, null, null,
                                appliedFiles, createdFiles, fileSnapshots, warnings, mistakeKeys);
    }

    // ── Main entry point ─────────────────────────────────────────────────────

    public static FileOpResult processFileOperations(Project project, String response) {
        return processFileOperations(project, response, Collections.emptySet());
    }

    public static FileOpResult processFileOperations(Project project, String response,
                                                     Set<String> alreadyWritten) {
        boolean runTests = false;
        boolean checkCompilation = false;
        String testName = null;
        String customCommand = null;
        List<String> appliedFiles = new ArrayList<>();
        List<String> createdFiles = new ArrayList<>();
        List<FileSnapshot> fileSnapshots = new ArrayList<>();
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
            if (createFolder(project, path)) {
                appliedFiles.add(path);
            }
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
        boolean hasCompleteFileOperation = false;
        while (fileMatcher.find()) {
            hasCompleteFileOperation = true;
            String type    = fileMatcher.group(1);
            String path    = fileMatcher.group(2).trim();
            String content = normalizeFileOperationContent(fileMatcher.group(3).trim());

            // Skip files already written during streaming — count as applied so callers
            // don't treat the response as having "no changes".
            String correctedForSkip = autoCorrectTestPath(path, content);
            if (!alreadyWritten.isEmpty() &&
                    (alreadyWritten.contains(path) || alreadyWritten.contains(correctedForSkip))) {
                appliedFiles.add(correctedForSkip);
                continue;
            }

            processSingleFileOp(project, type, path, content,
                                appliedFiles, createdFiles, fileSnapshots, warnings, mistakeKeys);
        }

        if (!hasCompleteFileOperation) {
            ParsedFileOperation partialOperation = extractPartialFileOperation(response);
            if (partialOperation != null) {
                // Warn but do NOT write: an incomplete file won't compile and is harder to recover
                // from than having no file at all. The caller should retry the full generation.
                warnings.add(truncatedXmlWarning());
                warnings.add("⛔ File \"" + partialOperation.path() + "\" was NOT written — writing partial code produces uncompilable output. Ask the model to regenerate.");
                mistakeKeys.add("truncated-response");
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

        return new FileOpResult(runTests, checkCompilation, testName, customCommand, appliedFiles, createdFiles, fileSnapshots, warnings, mistakeKeys);
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
    static String detectInvalidTestContent(String path, String content) {
        String normalized = path.replace("\\", "/");
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectLanguage(normalized);
        if (LanguageSupportUtil.isJvmLanguage(language)) {
            if (content.contains("omitted for brevity") || content.contains("other methods omitted")) {
                return "⛔ Blocked write to \"" + normalized + "\": generated content contains placeholder pseudo-code " +
                       "(\"methods omitted for brevity\"). File content must be complete and compile-ready.";
            }
            if (content.contains("implements com.intellij.openapi.actionSystem.AnActionEvent")
                    || content.contains("implements AnActionEvent")) {
                return "⛔ Blocked write to \"" + normalized + "\": AnActionEvent is not a test stub interface. " +
                       "Do not implement or instantiate AnActionEvent directly; test package-private helper methods instead.";
            }
            if (content.contains("ProjectDelegate")) {
                return "⛔ Blocked write to \"" + normalized + "\": generated content references non-project IntelliJ stub types. " +
                       "Use Mockito for Project or test package-private helper methods instead.";
            }
            if (content.contains("openChatCalled")
                    || content.contains("showToolWindowCalls")
                    || content.contains("enabledCalls")) {
                return "⛔ Blocked write to \"" + normalized + "\": generated test asserts fields that do not exist on the source class. " +
                       "Read the source and test real methods or a local test subclass.";
            }

            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            boolean testTreeJava = normalized.startsWith("src/test/java/");
            boolean conventionalTestName = fileName.endsWith("Test.java") || fileName.endsWith("Tests.java");
            boolean looksLikeProductionIntellijAction = content.contains("extends AnAction")
                    || content.contains("ToolWindowManager.getInstance")
                    || content.contains("import com.intellij.openapi.actionSystem.AnAction;");
            if (testTreeJava && looksLikeProductionIntellijAction) {
                return "⛔ Blocked write to \"" + normalized + "\": content looks like production IntelliJ action code " +
                       "placed in the test tree. Write a JUnit 5 test class whose file name ends with Test.java.";
            }
            if (testTreeJava && !conventionalTestName && looksLikeJvmTestContent(content)) {
                return "⛔ Blocked write to \"" + normalized + "\": generated JUnit content is in a non-test Java file. " +
                       "Write it to a file whose name ends with Test.java.";
            }
        }

        if (!PROTECTED_TEST_FILES.contains(normalized)) return null;

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
    static String autoCorrectTestPath(String path, String content) {
        String normalized = path.replace("\\", "/");
        if (normalized.startsWith("src/test/java/") && normalized.endsWith(".java")) {
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            boolean conventionalTestName = fileName.endsWith("Test.java") || fileName.endsWith("Tests.java");
            boolean looksLikeJUnitTest = content.contains("org.junit.jupiter") || content.contains("@Test");
            if (!conventionalTestName && looksLikeJUnitTest) {
                return normalized.substring(0, normalized.length() - ".java".length()) + "Test.java";
            }
        }

        String suggested = LanguageSupportUtil.suggestedTestPath(normalized);
        if (suggested.equals(normalized)) {
            if (normalized.startsWith("src/test/") || normalized.contains("/test/")) return path;
            return path;
        }
        LanguageSupportUtil.Language language = LanguageSupportUtil.detectLanguage(normalized);
        if (LanguageSupportUtil.isJvmLanguage(language)) {
            if (!looksLikeJvmTestContent(content)) {
                return path;
            }
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

    private static boolean looksLikeJvmTestContent(String content) {
        return content.contains("org.junit.jupiter")
                || content.contains("@Test")
                || content.contains("org.mockito")
                || content.matches("(?s).*\\bclass\\s+\\w+(?:Test|Tests)\\b.*");
    }

    private static boolean createFolder(Project project, String relativePath) {
        Path targetPath = resolveProjectPath(project, relativePath);
        if (targetPath == null) return false;
        return runFileWrite(() -> createFolderAtPath(targetPath));
    }

    private static void deletePath(Project project, String relativePath) {
        Path targetPath = resolveProjectPath(project, relativePath);
        if (targetPath == null) return;
        runFileWrite(() -> deletePathAtPath(targetPath));
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

    private static String normalizeFileOperationContent(String content) {
        content = stripCodeFence(content);
        while (XML_FILE_OPERATION_CLOSE_PATTERN.matcher(content).find()) {
            content = XML_FILE_OPERATION_CLOSE_PATTERN.matcher(content).replaceFirst("");
        }
        return content.trim();
    }

    private static String stripCodeFence(String content) {
        // Remove opening fence: ```<optional-lang>\n
        content = content.replaceFirst("^```[a-zA-Z0-9]*\\r?\\n", "");
        // Remove closing fence: \n```
        content = content.replaceFirst("\\r?\\n```\\s*$", "");
        return content.trim();
    }

    private static boolean writeFile(Project project, String relativePath, String content) {
        Path targetPath = resolveProjectPath(project, relativePath);
        if (targetPath == null) return false;
        return runFileWrite(() -> writeFileAtPath(targetPath, content));
    }

    private static boolean runFileWrite(IoBooleanSupplier supplier) {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            try {
                return supplier.getAsBoolean();
            } catch (IOException e) {
                return false;
            }
        }

        final boolean[] success = {false};
        application.runWriteAction(() -> {
            try {
                success[0] = supplier.getAsBoolean();
            } catch (IOException ignored) {
                // Caller reports failure through the returned boolean.
            }
        });
        return success[0];
    }

    /**
     * Path of a file-op whose opening tag streamed but whose closing tag never arrived
     * (truncated model output), or null if the response has no such partial op.
     */
    public static String findTruncatedFileOpPath(String response) {
        if (response == null || response.isBlank()) return null;
        Matcher matcher = PARTIAL_FILE_OP_PATTERN.matcher(response);
        String lastType = null;
        String lastPath = null;
        int lastEnd = -1;
        while (matcher.find()) {
            lastType = matcher.group(1);
            lastPath = matcher.group(2).trim();
            lastEnd = matcher.end();
        }
        if (lastPath == null) return null;
        return response.indexOf("</" + lastType + ">", lastEnd) >= 0 ? null : lastPath;
    }

    static ParsedFileOperation extractPartialFileOperation(String response) {
        Matcher matcher = PARTIAL_FILE_OP_PATTERN.matcher(response);
        if (!matcher.find()) return null;

        String type = matcher.group(1);
        String path = matcher.group(2).trim();
        String content = normalizeFileOperationContent(response.substring(matcher.end()).trim());
        if (content.isBlank()) return null;
        return new ParsedFileOperation(type, path, content);
    }

    static Path resolveProjectPath(Project project, String relativePath) {
        String baseDirPath = null;
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir != null && baseDir.getPath() != null && !baseDir.getPath().isBlank()) {
            baseDirPath = baseDir.getPath();
        }
        if ((baseDirPath == null || baseDirPath.isBlank()) && project.getBasePath() != null) {
            baseDirPath = project.getBasePath();
        }
        if (baseDirPath == null || baseDirPath.isBlank()) return null;

        Path basePath = Paths.get(baseDirPath).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath.replace("\\", "/")).normalize();
        if (!targetPath.startsWith(basePath)) return null;
        return targetPath;
    }

    static boolean createFolderAtPath(Path targetPath) throws IOException {
        Files.createDirectories(targetPath);
        refreshPathIfPossible(targetPath.toFile());
        return Files.isDirectory(targetPath);
    }

    static boolean writeFileAtPath(Path targetPath, String content) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(targetPath, content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        refreshPathIfPossible(targetPath.toFile());
        return Files.isRegularFile(targetPath);
    }

    static boolean deletePathAtPath(Path targetPath) throws IOException {
        if (!Files.exists(targetPath)) return true;
        if (Files.isDirectory(targetPath)) {
            try (var paths = Files.walk(targetPath)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            Files.deleteIfExists(targetPath);
        }
        refreshPathIfPossible(targetPath.toFile());
        return !Files.exists(targetPath);
    }

    private static FileSnapshot snapshotFile(Project project, String relativePath) {
        try {
            Path targetPath = resolveProjectPath(project, relativePath);
            if (targetPath == null) return null;
            boolean existed = Files.exists(targetPath);
            String content = existed ? Files.readString(targetPath, StandardCharsets.UTF_8) : "";
            return new FileSnapshot(relativePath.replace("\\", "/"), existed, content);
        } catch (IOException e) {
            return null;
        }
    }

    public static List<String> restoreFileSnapshots(Project project, List<FileSnapshot> snapshots) {
        List<String> restored = new ArrayList<>();
        if (snapshots == null || snapshots.isEmpty()) return restored;

        for (int i = snapshots.size() - 1; i >= 0; i--) {
            FileSnapshot snapshot = snapshots.get(i);
            if (snapshot == null || snapshot.path() == null || snapshot.path().isBlank()) continue;
            try {
                Path targetPath = resolveProjectPath(project, snapshot.path());
                if (targetPath == null) continue;
                if (snapshot.existed()) {
                    writeFileAtPath(targetPath, snapshot.content() == null ? "" : snapshot.content());
                } else {
                    Files.deleteIfExists(targetPath);
                    refreshPathIfPossible(targetPath.toFile());
                }
                restored.add(snapshot.path());
            } catch (IOException ignored) {
                // Best-effort rollback; caller reports the files that were restored.
            }
        }
        return restored;
    }

    private static void refreshPathIfPossible(File path) {
        try {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path);
            if (virtualFile != null) {
                virtualFile.refresh(false, false);
            }
        } catch (RuntimeException ignored) {
            // Best-effort refresh only.
        }
    }

    static String truncatedXmlWarning() {
        return "⚠ Model response ended before the closing XML tag — the file was NOT written.";
    }

    private static void processSingleFileOp(Project project, String type, String path, String content,
            List<String> appliedFiles, List<String> createdFiles, List<FileSnapshot> fileSnapshots,
            List<String> warnings, List<String> mistakeKeys) {

        String placeholderWarning = detectPlaceholderPath(path);
        if (placeholderWarning != null) {
            warnings.add(placeholderWarning);
            mistakeKeys.add("no-placeholder-path");
            return;
        }

        String untestableWarning = detectUntestableClass(path);
        if (untestableWarning != null) {
            warnings.add(untestableWarning);
            mistakeKeys.add("no-chatpanel-test");
            return;
        }

        String correctedPath = autoCorrectTestPath(path, content);
        if (!correctedPath.equals(path)) {
            warnings.add("⚠ Auto-corrected path: \"" + path + "\" → \"" + correctedPath + "\"");
            mistakeKeys.add("correct-test-package");
            path = correctedPath;
        }

        String invalidContentWarning = detectInvalidTestContent(path, content);
        if (invalidContentWarning != null) {
            warnings.add(invalidContentWarning);
            mistakeKeys.add("junit5-only");
            return;
        }

        content = fixPackageDeclaration(path, content);
        FileSnapshot snapshot = snapshotFile(project, path);
        if (writeFile(project, path, content)) {
            if (snapshot != null) fileSnapshots.add(snapshot);
            appliedFiles.add(path);
            if ("CREATE_FILE".equals(type)) createdFiles.add(path);
        } else {
            warnings.add("⛔ Failed to write file \"" + path + "\". The path may be invalid or outside the project root.");
            mistakeKeys.add("use-xml-tags");
        }
    }

    record ParsedFileOperation(String type, String path, String content) {}

    @FunctionalInterface
    private interface IoBooleanSupplier {
        boolean getAsBoolean() throws IOException;
    }
}
