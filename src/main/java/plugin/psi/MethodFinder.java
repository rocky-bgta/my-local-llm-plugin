package plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MethodFinder {

    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?:^|\\n)\\s*(?:public|private|protected)?(?:\\s+static)?(?:\\s+(?:[\\w<>\\[\\]]+))\\s+(\\w+)\\s*\\(([^)]*)\\)"
    );

    private final Project project;
    private final ClassFinder classFinder;

    public MethodFinder(Project project) {
        this.project = project;
        this.classFinder = new ClassFinder(project);
    }

    public List<MethodInfo> findMethodsInClass(String className) {
        return classFinder.findClassFile(className)
                .map(vf -> extractMethods(readContent(vf), className))
                .orElse(Collections.emptyList());
    }

    public Optional<MethodInfo> findMethod(String className, String methodName) {
        return findMethodsInClass(className).stream()
                .filter(m -> m.methodName().equals(methodName))
                .findFirst();
    }

    public List<MethodInfo> findPublicMethods(String className) {
        return findMethodsInClass(className).stream()
                .filter(MethodInfo::isPublic)
                .toList();
    }

    public record MethodInfo(
            String className,
            String methodName,
            String parameters,
            String returnType,
            boolean isPublic,
            int lineNumber
    ) {}

    private List<MethodInfo> extractMethods(String content, String className) {
        List<MethodInfo> result = new ArrayList<>();
        String[] lines = content.split("\n");
        Matcher m = METHOD_DECL.matcher(content);
        while (m.find()) {
            String decl = m.group(0).trim();
            String name = m.group(1);
            String params = m.group(2).trim();
            if (isKeyword(name)) continue;
            boolean pub = decl.contains("public");
            String retType = extractReturnType(decl, name);
            int line = countLines(content, m.start());
            result.add(new MethodInfo(className, name, params, retType, pub, line));
        }
        return result;
    }

    private String extractReturnType(String decl, String methodName) {
        int idx = decl.indexOf(methodName);
        if (idx <= 0) return "void";
        String before = decl.substring(0, idx).trim();
        String[] parts = before.split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : "void";
    }

    private boolean isKeyword(String name) {
        return Set.of("if", "for", "while", "switch", "catch", "class", "new").contains(name);
    }

    private int countLines(String text, int offset) {
        int count = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private String readContent(VirtualFile vf) {
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
