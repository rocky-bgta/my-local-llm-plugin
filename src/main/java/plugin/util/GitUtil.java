package plugin.util;

import com.intellij.openapi.project.Project;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitUtil {

    public record GitResult(boolean success, String output) {}

    public static GitResult addFiles(Project project, List<String> files) {
        if (files == null || files.isEmpty()) {
            return new GitResult(true, "No files to add.");
        }
        
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("add");
        command.addAll(files);
        
        return runGitCommand(project, command);
    }

    private static GitResult runGitCommand(Project project, List<String> command) {
        String basePath = project.getBasePath();
        if (basePath == null) return new GitResult(false, "Could not determine project base path.");
        
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> fullCommand = new ArrayList<>();
            if (isWindows) {
                fullCommand.add("cmd");
                fullCommand.add("/c");
            }
            fullCommand.addAll(command);

            Process process = new ProcessBuilder(fullCommand)
                    .directory(Paths.get(basePath).toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(false, "Git command timed out.");
            }
            
            return new GitResult(process.exitValue() == 0, output.toString().trim());
        } catch (Exception e) {
            return new GitResult(false, "Git command failed: " + e.getMessage());
        }
    }
}
