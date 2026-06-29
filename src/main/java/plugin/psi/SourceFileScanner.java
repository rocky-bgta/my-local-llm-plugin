package plugin.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourceFileScanner {

    private SourceFileScanner() {}

    public static List<VirtualFile> scanSourceFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (!vf.isDirectory() && "java".equals(vf.getExtension())
                        && !isTestFile(vf) && !isGenerated(vf)) {
                    result.add(vf);
                }
                return true;
            });
        });
        return Collections.unmodifiableList(result);
    }

    public static List<VirtualFile> scanAllJavaFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex.getInstance(project).iterateContent(vf -> {
                if (!vf.isDirectory() && "java".equals(vf.getExtension())) {
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
                if (isConfigFile(name)) result.add(child);
            }
        }
    }

    private static boolean isConfigFile(String name) {
        return name.equals("pom.xml") || name.equals("plugin.xml")
                || name.equals("CLAUDE.md") || name.equals("README.md")
                || name.equals("application.yml") || name.equals("application.properties")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("package.json") || name.equals("go.mod");
    }

    private static boolean isTestFile(VirtualFile vf) {
        return vf.getPath().contains("src/test/") || vf.getPath().contains("src\\test\\");
    }

    private static boolean isGenerated(VirtualFile vf) {
        String path = vf.getPath();
        return path.contains("/target/") || path.contains("\\target\\")
                || path.contains("/out/") || path.contains("\\out\\");
    }
}
