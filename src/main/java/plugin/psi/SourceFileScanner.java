package plugin.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import plugin.util.LanguageSupportUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourceFileScanner {

    private SourceFileScanner() {}

    public static List<VirtualFile> scanSourceFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (!vf.isDirectory() && LanguageSupportUtil.isSourceFile(vf.getPath())
                        && !isTestFile(vf) && !isGenerated(vf)) {
                    result.add(vf);
                }
                return true;
            });
        });
        return Collections.unmodifiableList(result);
    }

    public static List<VirtualFile> scanAllJavaFiles(Project project) {
        return scanAllSourceFiles(project);
    }

    public static List<VirtualFile> scanAllSourceFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (!vf.isDirectory() && LanguageSupportUtil.isSourceFile(vf.getPath())) {
                    result.add(vf);
                }
                return true;
            });
        });
        return Collections.unmodifiableList(result);
    }

    public static List<VirtualFile> scanConfigFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) return result;
        collectConfigFiles(baseDir, result, 0);
        return result;
    }

    private static void collectConfigFiles(VirtualFile dir, List<VirtualFile> result, int depth) {
        if (depth > 3) return;
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (!name.equals("target") && !name.equals("out") && !name.equals(".git")) {
                    collectConfigFiles(child, result, depth + 1);
                }
            } else {
                String name = child.getName();
                if (isConfigFile(name) || isContainerConfigFile(name)) result.add(child);
            }
        }
    }

    private static boolean isConfigFile(String name) {
        return name.equals("pom.xml") || name.equals("plugin.xml")
                || name.equals("CLAUDE.md") || name.equals("README.md")
                || name.equals("application.yml") || name.equals("application.properties")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("package.json") || name.equals("package-lock.json")
                || name.equals("pnpm-lock.yaml") || name.equals("yarn.lock")
                || name.equals("go.mod") || name.equals("Cargo.toml")
                || name.equals("pyproject.toml") || name.equals("requirements.txt")
                || name.equals("composer.json") || name.equals("composer.lock")
                || name.equals("Gemfile") || name.equals("Gemfile.lock")
                || name.endsWith(".sln") || name.endsWith(".csproj")
                || name.equals("tsconfig.json")
                || name.equals("Makefile");
    }

    private static boolean isContainerConfigFile(String name) {
        return name.equalsIgnoreCase("Dockerfile")
                || name.endsWith(".Dockerfile")
                || name.equalsIgnoreCase("docker-compose.yml")
                || name.equalsIgnoreCase("docker-compose.yaml")
                || name.equalsIgnoreCase("compose.yml")
                || name.equalsIgnoreCase("compose.yaml")
                || name.equalsIgnoreCase("docker-bake.hcl")
                || name.equalsIgnoreCase("Chart.yaml")
                || name.equalsIgnoreCase("Chart.yml")
                || name.equalsIgnoreCase("values.yaml")
                || name.equalsIgnoreCase("values.yml");
    }

    private static boolean isTestFile(VirtualFile vf) {
        return LanguageSupportUtil.isTestFile(vf.getPath());
    }

    private static boolean isGenerated(VirtualFile vf) {
        String path = vf.getPath();
        return path.contains("/target/") || path.contains("\\target\\")
                || path.contains("/out/") || path.contains("\\out\\")
                || path.contains("/build/") || path.contains("\\build\\")
                || path.contains("/dist/") || path.contains("\\dist\\")
                || path.contains("/node_modules/") || path.contains("\\node_modules\\");
    }
}
