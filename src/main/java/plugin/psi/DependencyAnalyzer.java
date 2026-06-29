package plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyAnalyzer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?:private|protected|public)\\s+(?:final\\s+)?([\\w<>\\[\\]]+)\\s+(\\w+)\\s*[;=]"
    );

    private final Project project;
    private final ClassFinder classFinder;

    public DependencyAnalyzer(Project project) {
        this.project = project;
        this.classFinder = new ClassFinder(project);
    }

    public DependencyGraph analyze(String className) {
        Optional<VirtualFile> file = classFinder.findClassFile(className);
        if (file.isEmpty()) return DependencyGraph.empty(className);

        String content = readContent(file.get());
        Set<String> imports = extractImports(content);
        Set<String> projectClasses = getProjectClassNames();
        Set<String> directDeps = new LinkedHashSet<>();

        for (String imp : imports) {
            String simpleName = imp.contains(".") ? imp.substring(imp.lastIndexOf('.') + 1) : imp;
            if (projectClasses.contains(simpleName)) {
                directDeps.add(simpleName);
            }
        }

        // Also check field type references
        Matcher m = FIELD_PATTERN.matcher(content);
        while (m.find()) {
            String type = m.group(1);
            if (projectClasses.contains(type)) directDeps.add(type);
        }

        return new DependencyGraph(className, Collections.unmodifiableSet(directDeps));
    }

    public List<String> getTransitiveDependencies(String className, int maxDepth) {
        Set<String> visited = new LinkedHashSet<>();
        collectTransitive(className, visited, 0, maxDepth);
        visited.remove(className);
        return new ArrayList<>(visited);
    }

    private void collectTransitive(String cls, Set<String> visited, int depth, int maxDepth) {
        if (depth >= maxDepth || visited.contains(cls)) return;
        visited.add(cls);
        DependencyGraph graph = analyze(cls);
        for (String dep : graph.directDependencies()) {
            collectTransitive(dep, visited, depth + 1, maxDepth);
        }
    }

    private Set<String> extractImports(String content) {
        Set<String> imports = new LinkedHashSet<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) imports.add(m.group(1));
        return imports;
    }

    private Set<String> getProjectClassNames() {
        Set<String> names = new HashSet<>();
        SourceFileScanner.scanSourceFiles(project)
                .forEach(vf -> names.add(vf.getNameWithoutExtension()));
        return names;
    }

    private String readContent(VirtualFile vf) {
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public record DependencyGraph(String root, Set<String> directDependencies) {
        static DependencyGraph empty(String root) {
            return new DependencyGraph(root, Set.of());
        }
    }
}
