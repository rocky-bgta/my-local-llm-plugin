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
 * Two-phase context builder:
 *
 *   Phase 1 – {@link #collectSnapshot()} – must run on the EDT.
 *     Reads IntelliJ APIs (FileEditorManager, Editor, VFS) into a plain
 *     {@link IdeSnapshot} value object.
 *
 *   Phase 2 – {@link #buildPrompt} – thread-safe static method.
 *     Combines the snapshot with a pre-built git section and session history
 *     into the final system prompt sent to the LLM.
 */
public class ProjectContextBuilder {

    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TREE_DEPTH = 4;
    private static final int MAX_OPEN_FILES = 3;

    private final Project project;

    public ProjectContextBuilder(@NotNull Project project) {
        this.project = project;
    }

    // =========================================================================
    // Phase 1 — EDT only
    // =========================================================================

    /** Collect all IntelliJ-API-dependent data. Must be called on the EDT. */
    public IdeSnapshot collectSnapshot() {
        String currentPath = getCurrentFilePath();
        return new IdeSnapshot(
                project.getName(),
                detectLanguage(),
                detectFramework(),
                buildProjectTree(),
                currentPath,
                getCurrentFileContent(),
                getSelectedText(),
                buildOpenFilesSection(currentPath),
                getBuildFilesContent()
        );
    }

    // =========================================================================
    // Phase 2 — thread-safe, no IntelliJ APIs
    // =========================================================================

    /**
     * Builds the complete enriched prompt from pre-collected data.
     * Safe to call from any thread.
     */
    public static String buildPrompt(
            @NotNull String userMessage,
            @NotNull String mode,
            @NotNull List<ChatMessage> priorHistory,
            @NotNull IdeSnapshot ide,
            @NotNull String gitSection) {

        StringBuilder sb = new StringBuilder();

        // ── Identity ─────────────────────────────────────────────────────────
        sb.append("# Local LLM Assistant — System Prompt\n\n");
        sb.append("You are an autonomous AI software engineering agent running inside IntelliJ IDEA.\n\n");
        sb.append("You have access to the user's project, source files, project structure, ")
          .append("open files, selected code, git history, and conversation history.\n\n");
        sb.append("---\n\n");

        // ── Active mode ───────────────────────────────────────────────────────
        sb.append("# Active Mode: **").append(mode).append("**\n\n");
        switch (mode) {
            case "Planning" -> sb.append("""
                    Purpose: Analyse requirements, explore architecture, create implementation plans.
                    Do NOT modify files. Break tasks down, identify dependencies, produce a roadmap.
                    """);
            case "Bypass" -> sb.append("""
                    Purpose: Fast execution. Make reasonable assumptions, minimise explanations,
                    focus on implementation, generate changes rapidly.
                    """);
            default -> sb.append("""
                    Purpose: Full implementation. Modify files, create files, refactor, configure,
                    generate tests. Do NOT ask for repeated confirmation. Continue until done.
                    Only stop if critical information is missing or multiple valid choices exist.
                    """);
        }
        sb.append("\n---\n\n");

        // ── Standing rules ────────────────────────────────────────────────────
        sb.append("""
                # Persistent Session Context

                Maintain context across the entire conversation.
                Remember requirements, architectural decisions, created/modified files.
                Continue unfinished work. Never ask the user to repeat information.
                Reset only on: "New task" | "Reset context" | "Start a new conversation".

                ---

                # Task Execution Rules

                1. Analyse the entire requirement.
                2. Identify all sub-tasks and affected files.
                3. Execute sequentially — never stop after one sub-task.
                4. Report progress during long operations.
                5. Continue until ALL tasks are completed.

                ---

                # Git Integration Rules

                - Use git history as first-class context when debugging regressions.
                - If the user references a commit hash, restrict analysis to that commit.
                - Review findings grouped by: Critical | Major | Minor | Suggestion.
                - When reviewing a PR/MR report: { ready_for_approval, remaining_issues, review_summary }

                ---

                # Code Generation Rules

                Follow project conventions. Compile-clean. Minimal unrelated changes.
                Include all imports. Add or update tests when appropriate.

                ---

                # Response Format

                ```json
                {
                  "mode": "planning|editing|bypass",
                  "current_step": "…",
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
        sb.append("**Project:** `").append(ide.projectName()).append("`  ")
          .append("**Language:** ").append(ide.language()).append("  ")
          .append("**Framework:** ").append(ide.framework()).append("\n\n");

        sb.append("## Project Tree\n\n```\n").append(ide.projectTree()).append("```\n\n");

        sb.append("## Current File\n\nPath: `").append(ide.currentFilePath()).append("`\n\n");
        if (!ide.currentFileContent().isBlank()) {
            sb.append("```").append(ext(ide.currentFilePath())).append("\n")
              .append(truncate(ide.currentFileContent(), MAX_FILE_CHARS))
              .append("\n```\n\n");
        }

        String sel = ide.selectedText();
        sb.append("## Selected Code\n\n");
        if (sel.isBlank()) {
            sb.append("_(none)_\n\n");
        } else {
            sb.append("```").append(ext(ide.currentFilePath())).append("\n")
              .append(sel).append("\n```\n\n");
        }

        String open = ide.openFilesSection();
        sb.append("## Open Files\n\n").append(open.isBlank() ? "_(none)_\n" : open).append("\n");

        String build = ide.buildFilesContent();
        sb.append("## Build Files\n\n").append(build.isBlank() ? "_(none found)_\n" : build).append("\n");

        // ── Git context (populated by GitContextBuilder on daemon thread) ─────
        if (!gitSection.isBlank()) {
            sb.append(gitSection).append("\n");
        }

        // ── Session history ───────────────────────────────────────────────────
        String hist = formatHistory(priorHistory);
        sb.append("## Conversation History\n\n")
          .append(hist.isBlank() ? "_(new session — no prior turns)_\n" : hist)
          .append("\n---\n\n");

        // ── User request ──────────────────────────────────────────────────────
        sb.append("## User Request\n\n").append(userMessage);

        return sb.toString();
    }

    // =========================================================================
    // IDE data collectors — EDT only
    // =========================================================================

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
        String[] names = {"pom.xml", "build.gradle", "build.gradle.kts",
                          "settings.gradle", "package.json", "go.mod", "Cargo.toml"};
        StringBuilder sb = new StringBuilder();
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
            for (String name : names) {
                VirtualFile f = root.findChild(name);
                if (f == null) continue;
                String content = readFile(f);
                if (content.isBlank()) continue;
                sb.append("### ").append(name).append("\n\n```xml\n")
                  .append(truncate(content, 3_000)).append("\n```\n\n");
            }
        }
        return sb.toString();
    }

    private String buildProjectTree() {
        StringBuilder sb = new StringBuilder();
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
            appendTree(sb, root, 0);
        }
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

    private String detectFramework() {
        for (VirtualFile r : ProjectRootManager.getInstance(project).getContentRoots()) {
            if (exists(r, "pom.xml"))          return "Maven";
            if (exists(r, "build.gradle.kts")) return "Gradle (Kotlin DSL)";
            if (exists(r, "build.gradle"))     return "Gradle";
            if (exists(r, "go.mod"))           return "Go Modules";
            if (exists(r, "package.json"))     return "Node.js";
        }
        return "Unknown";
    }

    // =========================================================================
    // Static helpers (thread-safe)
    // =========================================================================

    private static String formatHistory(List<ChatMessage> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            String role = "user".equals(m.role()) ? "**User**" : "**Assistant**";
            sb.append(role).append(": ").append(truncate(m.content(), 500)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static boolean exists(VirtualFile dir, String child) {
        return dir.findChild(child) != null;
    }

    private static String readFile(VirtualFile file) {
        try {
            byte[][] buf = {null};
            ApplicationManager.getApplication().runReadAction(
                    () -> { try { buf[0] = file.contentsToByteArray(); } catch (Exception ignored) {} });
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

    // =========================================================================
    // IdeSnapshot — plain data holder, safe to pass across threads
    // =========================================================================

    public record IdeSnapshot(
            String projectName,
            String language,
            String framework,
            String projectTree,
            String currentFilePath,
            String currentFileContent,
            String selectedText,
            String openFilesSection,
            String buildFilesContent
    ) {}
}
