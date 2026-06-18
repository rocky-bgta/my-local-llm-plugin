package plugin.context;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import plugin.llm.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the full autonomous-agent system prompt sent to the LLM on every turn.
 * The UI always shows the clean user text; the enriched version is used only in
 * the LLM request snapshot.
 */
public class ProjectContextBuilder {

    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TREE_DEPTH = 4;
    private static final int MAX_OPEN_FILES = 3;

    private final Project project;

    public ProjectContextBuilder(@NotNull Project project) {
        this.project = project;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * @param userMessage   the raw text the user typed
     * @param mode          "Planning", "Editing", or "Bypass"
     * @param priorHistory  conversation turns that happened before this message
     */
    public String buildEnrichedPrompt(@NotNull String userMessage,
                                      @NotNull String mode,
                                      @NotNull List<ChatMessage> priorHistory) {

        String tree           = buildProjectTree();
        String currentPath    = getCurrentFilePath();
        String currentContent = getCurrentFileContent();
        String selectedCode   = getSelectedText();
        String openFiles      = buildOpenFilesSection(currentPath);
        String buildFiles     = getBuildFilesContent();
        String sessionHistory = formatHistory(priorHistory);

        StringBuilder sb = new StringBuilder();

        // ── System identity ──────────────────────────────────────────────────
        sb.append("# Local LLM Assistant — System Prompt\n\n");
        sb.append("You are an autonomous AI software engineering agent running inside IntelliJ IDEA.\n\n");
        sb.append("You have access to the user's project, source files, project structure, ")
          .append("open files, selected code, and conversation history.\n\n");
        sb.append("Your goal is to help the user complete software engineering tasks with ")
          .append("minimal interruption while maintaining correctness and project consistency.\n\n");
        sb.append("---\n\n");

        // ── Active mode ───────────────────────────────────────────────────────
        sb.append("# Active Mode: **").append(mode).append("**\n\n");
        switch (mode) {
            case "Planning" -> sb.append("""
                    Purpose: Analyse requirements, explore architecture, create implementation plans,
                    suggest approaches. Do NOT modify files. Break large tasks into smaller tasks,
                    identify dependencies, explain reasoning, and produce an implementation roadmap.
                    """);
            case "Bypass" -> sb.append("""
                    Purpose: Fast execution. Make reasonable assumptions, minimise explanations,
                    focus on implementation, generate changes rapidly.
                    """);
            default -> sb.append("""
                    Purpose: Perform implementation work. Modify files, create files, create folders,
                    refactor code, update configurations, generate tests.
                    Do NOT repeatedly ask for confirmation. Continue performing all required
                    modifications until the requested task is complete.
                    Only stop if critical information is missing, multiple valid implementation
                    choices exist, or user intervention is absolutely required.
                    """);
        }
        sb.append("\n---\n\n");

        // ── Persistent session rules ──────────────────────────────────────────
        sb.append("""
                # Persistent Session Context

                Maintain session context across the entire conversation.
                Treat all messages as part of the same task unless the user says:
                "Start a new conversation", "New task", "Reset context",
                "Discard previous context", or "Forget current task".

                Until then:
                - Remember previous requirements and architectural decisions.
                - Remember created and modified files.
                - Continue unfinished work.
                - Avoid asking the user to repeat information already provided.

                ---

                # Task Execution Rules

                1. Analyse the entire requirement.
                2. Identify all sub-tasks.
                3. Determine affected and new files.
                4. Execute tasks sequentially.
                5. Track progress internally.
                6. Continue until ALL tasks are completed.

                Never stop after completing only the first sub-task.

                ---

                # Code Generation Rules

                Generated code must:
                - Follow project conventions and preserve existing architecture.
                - Compile successfully.
                - Avoid unnecessary refactoring or unrelated changes.
                - Include all required imports.
                - Include tests when appropriate.

                ---

                # Response Format

                For each cycle return a JSON block followed by implementation details:

                ```json
                {
                  "mode": "planning|editing|bypass",
                  "current_step": "Current activity",
                  "completed_steps": [],
                  "remaining_steps": [],
                  "files_modified": [],
                  "files_created": [],
                  "status": "in_progress|completed|blocked"
                }
                ```

                ---

                """);

        // ── Project context ───────────────────────────────────────────────────
        sb.append("# Project Context\n\n");

        sb.append("## Project Tree\n\n```\n").append(tree).append("```\n\n");

        sb.append("## Current File\n\n");
        sb.append("Path: `").append(currentPath).append("`\n\n");
        if (!currentContent.isBlank()) {
            String ext = ext(currentPath);
            sb.append("```").append(ext).append("\n")
              .append(truncate(currentContent, MAX_FILE_CHARS))
              .append("\n```\n\n");
        }

        if (!selectedCode.isBlank()) {
            sb.append("## Selected Code\n\n```").append(ext(currentPath)).append("\n")
              .append(selectedCode).append("\n```\n\n");
        } else {
            sb.append("## Selected Code\n\n_(none)_\n\n");
        }

        sb.append("## Open Files\n\n").append(openFiles.isBlank() ? "_(none)_\n" : openFiles).append("\n");

        sb.append("## Build Files\n\n").append(buildFiles.isBlank() ? "_(none found)_\n" : buildFiles).append("\n");

        sb.append("## Conversation History\n\n")
          .append(sessionHistory.isBlank() ? "_(new session — no prior turns)_\n" : sessionHistory)
          .append("\n");

        sb.append("---\n\n");

        // ── User request ──────────────────────────────────────────────────────
        sb.append("## User Request\n\n").append(userMessage);

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // IDE data collectors
    // -------------------------------------------------------------------------

    private String getCurrentFilePath() {
        VirtualFile[] sel = FileEditorManager.getInstance(project).getSelectedFiles();
        return sel.length > 0 ? sel[0].getPath() : "None";
    }

    private String getCurrentFileContent() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        return editor != null ? editor.getDocument().getText() : "";
    }

    private String getSelectedText() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";
        String s = editor.getSelectionModel().getSelectedText();
        return s != null ? s : "";
    }

    private String buildOpenFilesSection(String currentPath) {
        VirtualFile[] open = FileEditorManager.getInstance(project).getOpenFiles();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (VirtualFile f : open) {
            if (count >= MAX_OPEN_FILES) break;
            if (f.getPath().equals(currentPath)) continue;
            String content = readFile(f);
            if (content.isBlank()) continue;
            sb.append("### ").append(f.getPath()).append("\n\n")
              .append("```").append(ext(f.getName())).append("\n")
              .append(truncate(content, MAX_FILE_CHARS))
              .append("\n```\n\n");
            count++;
        }
        return sb.toString();
    }

    private String getBuildFilesContent() {
        String[] buildFileNames = {"pom.xml", "build.gradle", "build.gradle.kts",
                                   "settings.gradle", "package.json", "go.mod", "Cargo.toml"};
        StringBuilder sb = new StringBuilder();
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
            for (String name : buildFileNames) {
                VirtualFile f = root.findChild(name);
                if (f == null) continue;
                String content = readFile(f);
                if (content.isBlank()) continue;
                sb.append("### ").append(name).append("\n\n")
                  .append("```xml\n")
                  .append(truncate(content, 3_000))
                  .append("\n```\n\n");
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Project tree
    // -------------------------------------------------------------------------

    private String buildProjectTree() {
        VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
        StringBuilder sb = new StringBuilder();
        for (VirtualFile root : roots) appendTree(sb, root, 0);
        return sb.toString();
    }

    private void appendTree(StringBuilder sb, VirtualFile node, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        String name = node.getName();
        if (name.startsWith(".") || name.equals("target") || name.equals("build")
                || name.equals("out") || name.equals("node_modules")) return;

        String indent = "  ".repeat(depth);
        if (node.isDirectory()) {
            sb.append(indent).append(name).append("/\n");
            VirtualFile[] children = node.getChildren();
            if (children != null) for (VirtualFile c : children) appendTree(sb, c, depth + 1);
        } else {
            sb.append(indent).append(name).append("\n");
        }
    }

    // -------------------------------------------------------------------------
    // Language / framework detection
    // -------------------------------------------------------------------------

    private String detectLanguage() {
        for (VirtualFile r : ProjectRootManager.getInstance(project).getContentRoots()) {
            if (exists(r, "pom.xml") || exists(r, "build.gradle") || exists(r, "build.gradle.kts")) return "Java";
            if (exists(r, "go.mod"))               return "Go";
            if (exists(r, "Cargo.toml"))           return "Rust";
            if (exists(r, "package.json"))         return "JavaScript / TypeScript";
            if (exists(r, "requirements.txt") || exists(r, "pyproject.toml")) return "Python";
        }
        return "Unknown";
    }

    // -------------------------------------------------------------------------
    // Session history formatter
    // -------------------------------------------------------------------------

    private static String formatHistory(List<ChatMessage> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            String role = "user".equals(m.role()) ? "**User**" : "**Assistant**";
            // Show only first 500 chars of each historical message to stay concise
            String content = truncate(m.content(), 500);
            sb.append(role).append(": ").append(content).append("\n\n");
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean exists(VirtualFile dir, String child) {
        return dir.findChild(child) != null;
    }

    private static String readFile(VirtualFile file) {
        try {
            byte[][] buf = {null};
            ApplicationManager.getApplication().runReadAction(() -> {
                try { buf[0] = file.contentsToByteArray(); } catch (Exception ignored) {}
            });
            return buf[0] != null ? new String(buf[0], file.getCharset()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n... [truncated]";
    }

    private static String ext(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }
}
