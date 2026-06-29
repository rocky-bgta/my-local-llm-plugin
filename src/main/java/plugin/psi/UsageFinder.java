package plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class UsageFinder {

    private final Project project;

    public UsageFinder(Project project) {
        this.project = project;
    }

    public List<UsageInfo> findUsages(String symbolName) {
        List<UsageInfo> usages = new ArrayList<>();
        for (VirtualFile vf : SourceFileScanner.scanAllJavaFiles(project)) {
            String content = readContent(vf);
            if (content.contains(symbolName)) {
                int line = 1;
                for (String l : content.split("\n")) {
                    if (l.contains(symbolName) && !l.trim().startsWith("//")) {
                        usages.add(new UsageInfo(vf.getPath(), vf.getNameWithoutExtension(), line, l.trim()));
                    }
                    line++;
                }
            }
        }
        return usages;
    }

    public List<String> findCallers(String methodName) {
        Set<String> callers = new LinkedHashSet<>();
        for (VirtualFile vf : SourceFileScanner.scanSourceFiles(project)) {
            String content = readContent(vf);
            if (content.contains("." + methodName + "(") || content.contains(methodName + "(")) {
                callers.add(vf.getNameWithoutExtension());
            }
        }
        return new ArrayList<>(callers);
    }

    public boolean isUsedInTests(String className) {
        for (VirtualFile vf : SourceFileScanner.scanAllJavaFiles(project)) {
            if (vf.getPath().contains("test") || vf.getPath().contains("Test")) {
                if (readContent(vf).contains(className)) return true;
            }
        }
        return false;
    }

    public record UsageInfo(String filePath, String fileName, int lineNumber, String lineContent) {}

    private String readContent(VirtualFile vf) {
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
