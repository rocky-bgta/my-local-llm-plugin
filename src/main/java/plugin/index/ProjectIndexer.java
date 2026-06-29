package plugin.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import plugin.psi.SourceFileScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectIndexer {

    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?(class|interface|enum|record)\\s+(\\w+)"
    );
    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE
    );

    private final Project project;
    private final SymbolIndex index;
    private volatile boolean indexed = false;

    public ProjectIndexer(Project project, SymbolIndex index) {
        this.project = project;
        this.index = index;
    }

    public synchronized void index() {
        index.clear();
        indexSourceFiles();
        indexConfigFiles();
        indexed = true;
    }

    public synchronized void reindex() {
        index();
    }

    public boolean isIndexed() { return indexed; }

    public SymbolIndex getIndex() { return index; }

    private void indexSourceFiles() {
        for (VirtualFile vf : SourceFileScanner.scanAllJavaFiles(project)) {
            String content = readContent(vf);
            if (content.isEmpty()) continue;
            String pkg = extractPackage(content);
            String basePath = project.getBasePath();
            String relative = basePath != null ? vf.getPath().replace(basePath, "").replace("\\", "/") : vf.getName();
            if (relative.startsWith("/")) relative = relative.substring(1);

            Matcher m = CLASS_DECL.matcher(content);
            String primarySymbol = vf.getNameWithoutExtension();
            String symbolType = "CLASS";
            if (m.find()) {
                symbolType = m.group(1).toUpperCase();
                primarySymbol = m.group(2);
            }

            boolean isTest = vf.getPath().contains("test") || vf.getName().contains("Test");
            if (isTest) symbolType = "TEST";

            index.add(new IndexEntry(
                    vf.getPath(), relative, primarySymbol,
                    symbolType, content, pkg, 0.0
            ));
        }
    }

    private void indexConfigFiles() {
        for (VirtualFile vf : SourceFileScanner.scanConfigFiles(project)) {
            String content = readContent(vf);
            if (content.isEmpty()) continue;
            String basePath = project.getBasePath();
            String relative = basePath != null ? vf.getPath().replace(basePath, "").replace("\\", "/") : vf.getName();
            if (relative.startsWith("/")) relative = relative.substring(1);

            index.add(new IndexEntry(
                    vf.getPath(), relative, vf.getName(),
                    "CONFIG", content, "", 0.0
            ));
        }
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
