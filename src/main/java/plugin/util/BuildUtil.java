package plugin.util;

import com.intellij.openapi.project.Project;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class BuildUtil {

    public record BuildResult(boolean success, String output, List<TestReportUtil.TestResult> testResults) {}

    public static BuildResult runCompile(Project project) {
        if (isMavenProject(project)) {
            return runMavenCommand(project, "clean compile test-compile");
        }
        return runGenericBuild(project);
    }

    private static BuildResult runGenericBuild(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return new BuildResult(false, "Could not determine project base path.", List.of());
        
        // Try to detect other build systems or common build scripts
        if (Paths.get(basePath, "gradlew").toFile().exists() || Paths.get(basePath, "build.gradle").toFile().exists() || Paths.get(basePath, "build.gradle.kts").toFile().exists()) {
            String gradleCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "./gradlew";
            return runCustomCommand(project, gradleCmd + " clean build");
        }
        if (Paths.get(basePath, "go.mod").toFile().exists()) {
            return runCustomCommand(project, "go clean; go build ./...");
        }
        if (Paths.get(basePath, "package.json").toFile().exists()) {
            return runCustomCommand(project, "npm run build");
        }
        if (Paths.get(basePath, "Makefile").toFile().exists()) {
            return runCustomCommand(project, "make");
        }
        
        return new BuildResult(false, "No recognized build system found. Please use <EXECUTE_COMMAND command=\"...\" /> to specify the build command.", List.of());
    }

    public static BuildResult runTest(Project project, String testName) {
        if (isMavenProject(project)) {
            if (testName != null && !testName.isBlank()) {
                return runMavenCommand(project, "clean test -Dtest=" + testName);
            }
            return runMavenCommand(project, "clean test");
        }
        return runGenericTest(project, testName);
    }

    private static BuildResult runGenericTest(Project project, String testName) {
        String basePath = project.getBasePath();
        if (basePath == null) return new BuildResult(false, "Could not determine project base path.", List.of());

        if (Paths.get(basePath, "gradlew").toFile().exists() || Paths.get(basePath, "build.gradle").toFile().exists() || Paths.get(basePath, "build.gradle.kts").toFile().exists()) {
            String gradleCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "./gradlew";
            if (testName != null && !testName.isBlank()) {
                return runCustomCommand(project, gradleCmd + " clean test --tests " + testName);
            }
            return runCustomCommand(project, gradleCmd + " clean test");
        }
        if (Paths.get(basePath, "go.mod").toFile().exists()) {
            return runCustomCommand(project, "go clean -testcache; go test ./...");
        }
        if (Paths.get(basePath, "package.json").toFile().exists()) {
            return runCustomCommand(project, "npm test");
        }

        return new BuildResult(false, "No recognized test runner found. Please use <EXECUTE_COMMAND command=\"...\" /> to specify the test command.", List.of());
    }

    public static BuildResult runCustomCommand(Project project, String command) {
        String basePath = project.getBasePath();
        if (basePath == null) return new BuildResult(false, "Could not determine project base path.", List.of());
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String normalizedCommand = normalizeCustomCommand(command, isWindows);
            List<String> fullCommand = new ArrayList<>();
            if (isWindows) {
                fullCommand.add("cmd");
                fullCommand.add("/c");
                fullCommand.add(normalizedCommand);
            } else {
                fullCommand.add("sh");
                fullCommand.add("-c");
                fullCommand.add(normalizedCommand);
            }

            Process process = new ProcessBuilder(fullCommand)
                    .directory(Paths.get(basePath).toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }

            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new BuildResult(false, "Command timed out after 600 seconds.", List.of());
            }
            return new BuildResult(process.exitValue() == 0, output.toString().trim(), List.of());
        } catch (Exception e) {
            return new BuildResult(false, "Command failed to start: " + e.getMessage(), List.of());
        }
    }

    static String normalizeCustomCommand(String command, boolean isWindows) {
        if (command == null) return null;
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return trimmed;

        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("tree")) {
            return trimmed;
        }

        String[] tokens = trimmed.split("\\s+");
        List<String> pathTokens = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.equalsIgnoreCase("-a") ||
                token.equalsIgnoreCase("--noreport") ||
                token.equalsIgnoreCase("/a") ||
                token.equalsIgnoreCase("/f")) {
                continue;
            }
            pathTokens.add(token);
        }

        StringBuilder normalized = new StringBuilder(isWindows ? "tree /F /A" : "tree -a --noreport");
        for (String token : pathTokens) {
            normalized.append(' ').append(token);
        }
        return normalized.toString();
    }

    public static boolean isMavenProject(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return false;
        return Paths.get(basePath, "pom.xml").toFile().exists();
    }

    private static BuildResult runMavenCommand(Project project, String goal) {
        String basePath = project.getBasePath();
        if (basePath == null) return new BuildResult(false, "Could not determine project base path.", List.of());
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> command = new ArrayList<>();
            if (isWindows) {
                command.add("cmd");
                command.add("/c");
            }
            command.add("mvn");
            
            // Handle multiple commands (e.g., "go clean; go build") in runCustomCommand style if needed, 
            // but for Maven we usually just pass goals to a single mvn process.
            // If goal contains ';', we might need to handle it differently.
            // However, runMavenCommand is currently only called with space-separated goals.
            
            String[] goalParts = goal.split("\\s+");
            for (String part : goalParts) {
                command.add(part);
            }
            command.add("--no-transfer-progress");

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(Paths.get(basePath).toFile())
                    .redirectErrorStream(true);
            
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new BuildResult(false, goal + " timed out after 300 seconds.", List.of());
            }
            List<TestReportUtil.TestResult> testResults = goal.contains("test") ? TestReportUtil.parseReports(basePath) : List.of();
            return new BuildResult(process.exitValue() == 0, output.toString().trim(), testResults);
        } catch (Exception e) {
            return new BuildResult(false, goal + " failed to start: " + e.getMessage(), List.of());
        }
    }
}
