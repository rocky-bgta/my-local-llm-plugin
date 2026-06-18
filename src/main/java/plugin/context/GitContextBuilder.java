package plugin.context;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runs git commands against the project repository and formats the result as
 * a markdown section for injection into the LLM system prompt.
 *
 * All methods are safe to call from a background thread.
 * Git commands are capped at 2 seconds each; failures return empty strings.
 */
public class GitContextBuilder {

    private static final int     TIMEOUT_SECONDS = 2;
    private static final int     MAX_DIFF_CHARS  = 5_000;
    private static final Pattern HASH_PATTERN    = Pattern.compile("\\b([0-9a-f]{7,40})\\b");

    private final File workDir;

    public GitContextBuilder(@NotNull Project project) {
        String base = project.getBasePath();
        this.workDir = base != null ? new File(base) : null;
    }

    public boolean isGitRepo() {
        return workDir != null && new File(workDir, ".git").isDirectory();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the full ## Git Context section.
     * If the user message references a commit hash, the section is scoped to that commit.
     */
    public String buildGitSection(@NotNull String userMessage) {
        if (!isGitRepo()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Git Context\n\n");

        // Branch
        String branch = git("rev-parse", "--abbrev-ref", "HEAD");
        sb.append("**Current Branch:** ")
          .append(branch.isBlank() ? "unknown" : branch.trim())
          .append("\n\n");

        // Status
        String status = git("status", "--short");
        if (status.isBlank()) {
            sb.append("**Repository Status:** _(clean — no uncommitted changes)_\n\n");
        } else {
            sb.append("**Repository Status:**\n```\n").append(status).append("\n```\n\n");
        }

        // Recent commits
        String log = git("log", "--oneline", "--decorate", "-10");
        if (!log.isBlank()) {
            sb.append("**Recent Commits:**\n```\n").append(log).append("\n```\n\n");
        }

        // Commit-specific scope detection
        String hash = detectCommitHash(userMessage);
        if (hash != null) {
            appendCommitScope(sb, hash);
        } else {
            appendWorkingTreeDiff(sb);
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    private void appendCommitScope(StringBuilder sb, String hash) {
        sb.append("**Commit Scope: `").append(hash).append("`**\n\n");

        String show = git("show", "--stat", hash);
        if (!show.isBlank()) {
            sb.append("Commit Details:\n```\n").append(show).append("\n```\n\n");
        }

        String diff = git("diff", hash + "^", hash);
        if (!diff.isBlank()) {
            sb.append("Commit Diff:\n```diff\n")
              .append(truncate(diff, MAX_DIFF_CHARS))
              .append("\n```\n\n");
        }
    }

    private void appendWorkingTreeDiff(StringBuilder sb) {
        String changedFiles = git("diff", "--name-status", "HEAD");
        if (!changedFiles.isBlank()) {
            sb.append("**Changed Files (vs HEAD):**\n```\n")
              .append(changedFiles).append("\n```\n\n");
        }

        String diff = git("diff", "HEAD");
        if (!diff.isBlank()) {
            sb.append("**Diff (HEAD):**\n```diff\n")
              .append(truncate(diff, MAX_DIFF_CHARS))
              .append("\n```\n\n");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the first token in the user message that is a real commit hash, or null. */
    @Nullable
    private String detectCommitHash(String message) {
        Matcher m = HASH_PATTERN.matcher(message);
        while (m.find()) {
            String candidate = m.group(1);
            String type = git("cat-file", "-t", candidate).trim();
            if ("commit".equals(type)) return candidate;
        }
        return null;
    }

    private String git(String... args) {
        if (workDir == null) return "";
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                output = r.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) proc.destroyForcibly();
            return output;
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n... [truncated]";
    }
}
