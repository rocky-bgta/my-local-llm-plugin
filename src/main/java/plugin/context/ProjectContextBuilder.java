package plugin.context;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ProjectContextBuilder {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".", "target", "build", "out", ".git", ".idea", "node_modules", ".gradle"
    );

    private final Project project;
    private final FileContextReader fileReader;
    private final List<VirtualFile> attachedFiles;

    public ProjectContextBuilder(Project project) {
        this.project = project;
        this.fileReader = new FileContextReader(project);
        this.attachedFiles = new ArrayList<>();
    }

    public void attachFile(VirtualFile file) {
        if (!attachedFiles.contains(file)) {
            attachedFiles.add(file);
        }
    }

    public void detachFile(VirtualFile file) {
        attachedFiles.remove(file);
    }

    public void clearFiles() {
        attachedFiles.clear();
    }

    public List<VirtualFile> getAttachedFiles() {
        return List.copyOf(attachedFiles);
    }

    public String buildContextBlock() {
        if (attachedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("The following files are provided as context:\n\n");
        for (VirtualFile file : attachedFiles) {
            try {
                sb.append(fileReader.formatForContext(file)).append("\n");
            } catch (IOException e) {
                sb.append("### ").append(file.getName())
                  .append("\n[Error reading file: ").append(e.getMessage()).append("]\n\n");
            }
        }
        return sb.toString();
    }

    public String injectContext(String userMessage) {
        String contextBlock = buildContextBlock();
        if (contextBlock.isEmpty()) return userMessage;
        return contextBlock + "\n---\n\n" + userMessage;
    }

    public String buildFileTree() {
        StringBuilder sb = new StringBuilder("Project structure:\n");
        VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
        for (VirtualFile root : roots) {
            appendTree(sb, root, 0, 2);
        }
        return sb.toString();
    }

    private void appendTree(StringBuilder sb, VirtualFile dir, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        if (SKIP_DIRS.contains(dir.getName())) return;
        String indent = "  ".repeat(depth);
        VirtualFile[] children = dir.getChildren();
        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                if (!SKIP_DIRS.contains(child.getName())) {
                    sb.append(indent).append("📁 ").append(child.getName()).append("/\n");
                    appendTree(sb, child, depth + 1, maxDepth);
                }
            } else {
                sb.append(indent).append("  ").append(child.getName()).append("\n");
            }
        }
    }
}
