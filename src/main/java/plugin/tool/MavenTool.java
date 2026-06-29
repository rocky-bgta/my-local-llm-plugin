package plugin.tool;

import com.intellij.openapi.project.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class MavenTool {

    private final Project project;
    private final String basePath;

    public MavenTool(Project project) {
        this.project = project;
        this.basePath = project.getBasePath() != null ? project.getBasePath() : ".";
    }

    public Result compile() { return run("compile", 120); }

    public Result test() { return run("test", 300); }

    public Result testClass(String className) {
        return run("test -Dtest=" + className, 120);
    }

    public Result install() { return run("install -DskipTests", 300); }

    public Result clean() { return run("clean", 60); }

    public Result cleanTest() { return run("clean test", 300); }

    public boolean isMavenProject() {
        return Files.exists(Paths.get(basePath, "pom.xml"));
    }

    private Result run(String goals, int timeoutSeconds) {
        if (!isMavenProject()) {
            return new Result(false, "No pom.xml found in " + basePath, "mvn " + goals);
        }
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String mvn = isWindows ? "mvn.cmd" : "mvn";
            String[] cmd = isWindows
                    ? new String[]{"cmd", "/c", mvn + " " + goals}
                    : new String[]{"sh", "-c", "mvn " + goals};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(basePath));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, "Maven timed out", "mvn " + goals);
            }
            return new Result(process.exitValue() == 0, output.trim(), "mvn " + goals);
        } catch (Exception e) {
            return new Result(false, e.getMessage(), "mvn " + goals);
        }
    }

    public record Result(boolean success, String output, String command) {}
}
