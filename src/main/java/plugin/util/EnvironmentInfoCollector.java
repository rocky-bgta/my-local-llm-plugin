package plugin.util;

import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.IOException;

public class EnvironmentInfoCollector {

    public static String collect(Project project) {
        return collect(project, true);
    }

    public static String collectForPrompt(Project project) {
        return collect(project, false);
    }

    private static String collect(Project project, boolean includeShellNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Developer Environment\n\n");

        String osName    = System.getProperty("os.name", "Unknown");
        String osVersion = System.getProperty("os.version", "");
        String osArch    = System.getProperty("os.arch", "");
        sb.append("- **OS**: ").append(osName).append(" ").append(osVersion)
          .append(" (").append(osArch).append(")\n");

        String javaVersion = System.getProperty("java.version", "Unknown");
        String javaVendor  = System.getProperty("java.vendor", "");
        sb.append("- **Java**: ").append(javaVersion).append(" — ").append(javaVendor).append("\n");

        sb.append("- **Shell**: ").append(detectShell()).append("\n");

        if (project != null && project.getBasePath() != null) {
            String base = project.getBasePath();
            sb.append("- **Project path**: `").append(base).append("`\n");
            sb.append("- **Build tool**: ").append(detectBuildTool(base)).append("\n");
        }

        String mavenOut = runCommand("mvn", "--version");
        if (mavenOut != null && !mavenOut.isBlank()) {
            sb.append("- **Maven**: ").append(mavenOut.lines().findFirst().orElse("")).append("\n");
        }

        if (includeShellNotes && osName.toLowerCase().contains("windows")) {
            sb.append("\n### Windows Shell Notes\n");
            sb.append("- Unix commands (`head`, `tail`, `grep`, `find`) do **not** work in CMD/PowerShell\n");
            sb.append("- Use PowerShell equivalents: `Select-Object -First N`, `Select-String`, `Get-ChildItem -Recurse`\n");
            sb.append("- Path separator is `\\` (backslash); absolute paths start with drive letter: `C:\\...`\n");
            sb.append("- Pipe `|` passes objects in PowerShell, not text — behaviour differs from bash\n");
        }

        return sb.toString();
    }

    private static String detectShell() {
        String psModulePath = System.getenv("PSModulePath");
        if (psModulePath != null) return "PowerShell";
        String shell = System.getenv("SHELL");
        if (shell != null) return "Bash (" + shell + ")";
        String comspec = System.getenv("COMSPEC");
        if (comspec != null) return "CMD (" + comspec + ")";
        return "Unknown";
    }

    private static String detectBuildTool(String basePath) {
        if (new File(basePath, "pom.xml").exists())                    return "Maven (pom.xml)";
        if (new File(basePath, "build.gradle.kts").exists())           return "Gradle (Kotlin DSL)";
        if (new File(basePath, "build.gradle").exists())               return "Gradle (Groovy DSL)";
        if (new File(basePath, "package.json").exists())               return "npm / Node.js";
        if (new File(basePath, "go.mod").exists())                     return "Go modules";
        if (new File(basePath, "Cargo.toml").exists())                 return "Cargo (Rust)";
        if (new File(basePath, "pyproject.toml").exists()
                || new File(basePath, "requirements.txt").exists())    return "Python";
        return "Unknown";
    }

    private static String runCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out).trim();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
