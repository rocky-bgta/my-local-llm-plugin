package plugin.rag;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import plugin.index.IndexEntry;
import plugin.index.SymbolIndex;
import plugin.psi.ClassFinder;
import plugin.psi.DependencyAnalyzer;
import plugin.psi.SourceFileScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PSICollector {

    private final Project project;
    private final SymbolIndex symbolIndex;
    private final ClassFinder classFinder;
    private final DependencyAnalyzer dependencyAnalyzer;

    public PSICollector(Project project, SymbolIndex symbolIndex) {
        this.project = project;
        this.symbolIndex = symbolIndex;
        this.classFinder = new ClassFinder(project);
        this.dependencyAnalyzer = new DependencyAnalyzer(project);
    }

    public List<RetrievalResult> collectForQuery(String query, String targetClass) {
        List<RetrievalResult> results = new ArrayList<>();

        // Step 1: find the primary target class
        if (targetClass != null && !targetClass.isBlank()) {
            Optional<IndexEntry> entry = symbolIndex.findBySymbol(targetClass);
            entry.ifPresent(e -> results.add(toResult(e, 10.0, "PSI_PRIMARY")));
        }

        // Step 2: collect related classes via dependency analysis
        if (targetClass != null && !targetClass.isBlank()) {
            DependencyAnalyzer.DependencyGraph graph = dependencyAnalyzer.analyze(targetClass);
            for (String dep : graph.directDependencies()) {
                symbolIndex.findBySymbol(dep).ifPresent(e ->
                        results.add(toResult(e, 7.0, "PSI_DEPENDENCY")));
            }
        }

        // Step 3: find existing test file for the target
        if (targetClass != null && !targetClass.isBlank()) {
            String testName = targetClass + "Test";
            symbolIndex.findBySymbol(testName).ifPresent(e ->
                    results.add(toResult(e, 6.0, "PSI_EXISTING_TEST")));
        }

        // Step 4: collect config files (pom.xml, plugin.xml, CLAUDE.md)
        for (IndexEntry entry : symbolIndex.all()) {
            if ("CONFIG".equals(entry.symbolType())) {
                String name = entry.symbolName();
                if (name.equals("pom.xml") || name.equals("plugin.xml") || name.equals("CLAUDE.md")) {
                    results.add(toResult(entry, 5.0, "PSI_CONFIG"));
                }
            }
        }

        return results;
    }

    public List<RetrievalResult> collectConfigFiles() {
        List<RetrievalResult> results = new ArrayList<>();
        for (VirtualFile vf : SourceFileScanner.scanConfigFiles(project)) {
            String name = vf.getName();
            double score = switch (name) {
                case "pom.xml" -> 5.0;
                case "plugin.xml" -> 5.0;
                case "CLAUDE.md" -> 4.0;
                default -> 2.0;
            };
            String content = readContent(vf);
            String basePath = project.getBasePath();
            String relative = basePath != null ? vf.getPath().replace(basePath, "").replace("\\", "/") : vf.getName();
            results.add(RetrievalResult.of(vf.getPath(), relative, name, "CONFIG", content, score, "PSI_CONFIG"));
        }
        return results;
    }

    private RetrievalResult toResult(IndexEntry e, double score, String source) {
        return RetrievalResult.of(e.filePath(), e.relativeFilePath(), e.symbolName(),
                e.symbolType(), e.content(), score, source);
    }

    private String readContent(VirtualFile vf) {
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
