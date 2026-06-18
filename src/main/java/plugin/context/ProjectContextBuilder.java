package plugin.context;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects live IDE context (current file, selection, open files, project tree)
 * and formats it into a structured prompt that is prepended to the user's message.
 */
public class ProjectContextBuilder {

    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TREE_DEPTH = 4;
    private static final int MAX_OPEN_FILES = 3;

    private final Project project;

    public ProjectContextBuilder(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Builds the full context-enriched prompt sent to the LLM.
     * The UI always shows only the clean {@code userMessage}.
     */
    public String buildEnrichedPrompt(@NotNull String userMessage) {
        String projectName    = project.getName();
        String language       = detectLanguage();
        String framework      = detectFramework();
        String tree           = buildProjectTree();
        String currentPath    = getCurrentFilePath();
        String currentContent = getCurrentFileContent();
        String selectedText   = getSelectedText();
        String relatedFiles   = buildRelatedFilesSection(currentPath);

        StringBuilder sb = new StringBuilder();

        sb.append("You are an AI coding assistant running inside IntelliJ IDEA.\n\n");

        sb.append("## Project Information\n\n");
        sb.append("Project Name: ").append(projectName).append("\n");
        sb.append("Language: ").append(language).append("\n");
        sb.append("Framework: ").append(framework).append("\n\n");

        sb.append("## Project Structure\n\n```\n").append(tree).append("```\n\n");

        sb.append("## Current File\n\n");
        sb.append("Path: ").append(currentPath).append("\n\n");
        if (!currentContent.isBlank()) {
            String ext = fileExtension(currentPath);
            sb.append("Content:\n\n```").append(ext).append("\n")
              .append(truncate(currentContent, MAX_FILE_CHARS))
              .append("\n```\n\n");
        }

        if (selectedText != null && !selectedText.isBlank()) {
            String ext = fileExtension(currentPath);
            sb.append("## Selected Code\n\n```").append(ext).append("\n")
              .append(selectedText)
              .append("\n```\n\n");
        }

        if (!relatedFiles.isBlank()) {
            sb.append(relatedFiles);
        }

        sb.append("## User Request\n\n").append(userMessage);

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // IDE data collectors (safe to call on EDT)
    // -------------------------------------------------------------------------

    private String getCurrentFilePath() {
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        return selected.length > 0 ? selected[0].getPath() : "None";
    }

    private String getCurrentFileContent() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        return editor != null ? editor.getDocument().getText() : "";
    }

    private String getSelectedText() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";
        String sel = editor.getSelectionModel().getSelectedText();
        return sel != null ? sel : "";
    }

    private String buildRelatedFilesSection(String currentPath) {
        VirtualFile[] openFiles = FileEditorManager.getInstance(project).getOpenFiles();
        List<String> sections = new ArrayList<>();
        int count = 0;

        for (VirtualFile file : openFiles) {
            if (count >= MAX_OPEN_FILES) break;
            if (file.getPath().equals(currentPath)) continue;

            String content = readFile(file);
            if (content.isBlank()) continue;

            String ext = fileExtension(file.getName());
            sections.add("### File: " + file.getPath() + "\n\n"
                    + "```" + ext + "\n"
                    + truncate(content, MAX_FILE_CHARS)
                    + "\n```\n");
            count++;
        }

        if (sections.isEmpty()) return "";
        return "## Related Files\n\n" + String.join("\n", sections) + "\n";
    }

    // -------------------------------------------------------------------------
    // Project tree
    // -------------------------------------------------------------------------

    private String buildProjectTree() {
        VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
        StringBuilder sb = new StringBuilder();
        for (VirtualFile root : roots) {
            appendTree(sb, root, 0);
        }
        return sb.toString();
    }

    private void appendTree(StringBuilder sb, VirtualFile node, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        String name = node.getName();
        if (name.startsWith(".") || name.equals("target") || name.equals("node_modules")
                || name.equals("build") || name.equals("out")) return;

        String indent = "  ".repeat(depth);
        if (node.isDirectory()) {
            sb.append(indent).append(name).append("/\n");
            VirtualFile[] children = node.getChildren();
            if (children != null) {
                for (VirtualFile child : children) {
                    appendTree(sb, child, depth + 1);
                }
            }
        } else {
            sb.append(indent).append(name).append("\n");
        }
    }

    // -------------------------------------------------------------------------
    // Language / framework detection
    // -------------------------------------------------------------------------

    private String detectLanguage() {
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
            if (exists(root, "pom.xml") || exists(root, "build.gradle")
                    || exists(root, "build.gradle.kts")) return "Java";
            if (exists(root, "go.mod"))               return "Go";
            if (exists(root, "Cargo.toml"))           return "Rust";
            if (exists(root, "package.json"))         return "JavaScript / TypeScript";
            if (exists(root, "requirements.txt")
                    || exists(root, "pyproject.toml")) return "Python";
        }
        return "Unknown";
    }

    private String detectFramework() {
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
            if (exists(root, "pom.xml"))          return "Maven";
            if (exists(root, "build.gradle.kts")) return "Gradle (Kotlin DSL)";
            if (exists(root, "build.gradle"))     return "Gradle";
            if (exists(root, "go.mod"))           return "Go Modules";
            if (exists(root, "package.json"))     return "Node.js";
        }
        return "Unknown";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean exists(VirtualFile dir, String child) {
        return dir.findChild(child) != null;
    }

    private static String readFile(VirtualFile file) {
        try {
            // contentsToByteArray requires a read action
            byte[][] buf = {null};
            ApplicationManager.getApplication().runReadAction(
                    () -> {
                        try { buf[0] = file.contentsToByteArray(); }
                        catch (Exception ignored) {}
                    });
            return buf[0] != null ? new String(buf[0], file.getCharset()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n... [truncated]";
    }

    private static String fileExtension(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }
}
