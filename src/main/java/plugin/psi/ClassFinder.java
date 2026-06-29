package plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassFinder {

    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?:^|\\n)\\s*(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum|record)\\s+(\\w+)"
    );
    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;"
    );

    private final Project project;

    public ClassFinder(Project project) {
        this.project = project;
    }

    public Optional<VirtualFile> findClassFile(String className) {
        return SourceFileScanner.scanSourceFiles(project).stream()
                .filter(vf -> matchesClassName(vf, className))
                .findFirst();
    }

    public List<ClassInfo> findAllClasses() {
        List<ClassInfo> result = new ArrayList<>();
        for (VirtualFile vf : SourceFileScanner.scanSourceFiles(project)) {
            String content = readContent(vf);
            if (content.isEmpty()) continue;
            String pkg = extractPackage(content);
            extractClasses(content, vf.getPath(), pkg).forEach(result::add);
        }
        return result;
    }

    public List<ClassInfo> findRelatedClasses(String targetClass) {
        Optional<VirtualFile> target = findClassFile(targetClass);
        if (target.isEmpty()) return Collections.emptyList();

        String content = readContent(target.get());
        Set<String> imports = extractImportedClasses(content);
        List<ClassInfo> related = new ArrayList<>();

        for (VirtualFile vf : SourceFileScanner.scanSourceFiles(project)) {
            String name = vf.getNameWithoutExtension();
            if (imports.contains(name) || isReferencedIn(content, name)) {
                String fileContent = readContent(vf);
                String pkg = extractPackage(fileContent);
                related.addAll(extractClasses(fileContent, vf.getPath(), pkg));
            }
        }
        return related;
    }

    public record ClassInfo(String className, String packageName, String filePath, String symbolType) {}

    private boolean matchesClassName(VirtualFile vf, String className) {
        if (!vf.getNameWithoutExtension().equals(className)) return false;
        String content = readContent(vf);
        Matcher m = CLASS_DECL.matcher(content);
        while (m.find()) {
            if (m.group(1).equals(className)) return true;
        }
        return false;
    }

    private List<ClassInfo> extractClasses(String content, String filePath, String pkg) {
        List<ClassInfo> result = new ArrayList<>();
        Matcher m = CLASS_DECL.matcher(content);
        while (m.find()) {
            String type = m.group(0).trim().split("\\s+")[0];
            result.add(new ClassInfo(m.group(1), pkg, filePath, type.toUpperCase()));
        }
        return result;
    }

    private Set<String> extractImportedClasses(String content) {
        Set<String> names = new HashSet<>();
        Pattern imp = Pattern.compile("^\\s*import\\s+[\\w.]+\\.(\\w+)\\s*;", Pattern.MULTILINE);
        Matcher m = imp.matcher(content);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private boolean isReferencedIn(String content, String className) {
        return content.contains(className);
    }

    private String extractPackage(String content) {
        Matcher m = PACKAGE_DECL.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private String readContent(VirtualFile vf) {
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
