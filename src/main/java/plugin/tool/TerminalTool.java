package plugin.tool;

import com.intellij.openapi.project.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TerminalTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final Project project;

    public TerminalTool(Project project) {
        this.project = project;
    }

    public Result execute(String command) {
        return execute(command, DEFAULT_TIMEOUT_SECONDS);
    }

    public Result execute(String command, int timeoutSeconds) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", command)
                    : List.of("sh", "-c", command);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(project.getBasePath() != null ? project.getBasePath() : "."));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, "Command timed out after " + timeoutSeconds + "s", command);
            }
            return new Result(process.exitValue() == 0, output.trim(), command);
        } catch (Exception e) {
            return new Result(false, e.getMessage(), command);
        }
    }

    public record Result(boolean success, String output, String command) {}
}
