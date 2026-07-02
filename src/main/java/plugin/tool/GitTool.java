package plugin.tool;

import com.intellij.openapi.project.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitTool {

    private final Project project;

    public GitTool(Project project) {
        this.project = project;
    }

    public ToolResult add(List<String> files) {
        List<String> cmd = new ArrayList<>(List.of("git", "add"));
        cmd.addAll(files);
        return run(cmd, 30);
    }

    public ToolResult status() {
        return run(List.of("git", "status", "--short"), 15);
    }

    public ToolResult diff(String file) {
        List<String> cmd = file != null
                ? List.of("git", "diff", file)
                : List.of("git", "diff");
        return run(cmd, 15);
    }

    public ToolResult log(int n) {
        return run(List.of("git", "log", "--oneline", "-" + n), 15);
    }

    public ToolResult lastCommit() {
        return run(List.of("git", "show", "--stat", "--patch", "--format=fuller", "--no-ext-diff", "-1"), 30);
    }

    public ToolResult show(String ref) {
        if (ref == null || ref.isBlank()) {
            return lastCommit();
        }
        return run(List.of("git", "show", "--stat", "--patch", "--format=fuller", "--no-ext-diff", ref), 30);
    }

    public ToolResult commit(String message) {
        return run(List.of("git", "commit", "-m", message), 30);
    }

    public ToolResult currentBranch() {
        return run(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), 10);
    }

    public ToolResult currentCommitSha() {
        return run(List.of("git", "rev-parse", "HEAD"), 10);
    }

    private ToolResult run(List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(project.getBasePath() != null ? project.getBasePath() : "."));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readOutput(process);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, "Git command timed out", cmd.toString());
            }
            boolean success = process.exitValue() == 0;
            return new ToolResult(success, output, cmd.toString());
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage(), cmd.toString());
        }
    }

    private String readOutput(Process process) throws IOException {
        try (InputStream is = process.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    public record ToolResult(boolean success, String output, String command) {}
}
