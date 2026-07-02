package plugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ProjectContextUtil {

    interface ProjectNode {
        String name();
        boolean directory();
        List<ProjectNode> children();
    }

    record TreeNode(String name, boolean directory, List<ProjectNode> children) implements ProjectNode {
        TreeNode {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    public static String getProjectContext(Project project, boolean includeContent) {
        StringBuilder sb = new StringBuilder();
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) return "Project base directory not found.";

        sb.append("Current Project Structure:\n");
        sb.append("Note: IDE/build/generated artifacts are excluded.\n\n");
        sb.append(baseDir.getName()).append("/\n");
        appendTree(new VirtualFileNode(baseDir), "", sb, 16000);

        if (includeContent) {
            sb.append("\nDetailed File Contents:\n");
            int maxChars = 64000;
            appendFileContents(baseDir, sb, maxChars);
        }
        return sb.toString();
    }

    static void appendTree(ProjectNode node, String indent, StringBuilder sb, int maxChars) {
        if (sb.length() > maxChars) return;

        List<ProjectNode> children = node.children();
        if (children == null) return;

        List<ProjectNode> nodes = new ArrayList<>();
        for (ProjectNode child : children) {
            if (shouldExclude(child.name())) continue;
            nodes.add(child);
        }

        nodes.sort(Comparator
                .comparing(ProjectNode::directory).reversed()
                .thenComparing(ProjectNode::name, String.CASE_INSENSITIVE_ORDER));

        for (int i = 0; i < nodes.size(); i++) {
            ProjectNode child = nodes.get(i);
            boolean isLast = (i == nodes.size() - 1);

            sb.append(indent).append(isLast ? "└── " : "├── ").append(child.name());
            if (child.directory()) {
                sb.append("/");
            }
            sb.append("\n");

            if (child.directory()) {
                // Special case for target: don't recurse
                if (child.name().equals("target")) continue;
                appendTree(child, indent + (isLast ? "    " : "│   "), sb, maxChars);
            }
        }
    }

    private static void appendFileContents(VirtualFile file, StringBuilder sb, int maxChars) {
        appendFileContentsRecursive(file, sb, maxChars);
    }

    private static void appendFileContentsRecursive(VirtualFile file, StringBuilder sb, int maxChars) {
        if (sb.length() > maxChars) return;
        if (shouldExclude(file.getName())) return;

        if (file.isDirectory()) {
            if (file.getName().equals("target")) return;
            VirtualFile[] children = file.getChildren();
            if (children != null) {
                for (VirtualFile child : children) {
                    appendFileContentsRecursive(child, sb, maxChars);
                }
            }
        } else {
            try {
                if (isTextFile(file) && file.getLength() < 50000) {
                    sb.append("\n--- FILE: ").append(file.getName()).append(" ---\n");
                    String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);

                    int remaining = maxChars - sb.length();
                    if (remaining > 0) {
                        if (content.length() > remaining) {
                            content = content.substring(0, remaining) + "\n... (content truncated)";
                        }
                        sb.append(content).append("\n");
                    }
                }
            } catch (IOException e) {
                sb.append("\n--- FILE: ").append(file.getName()).append(" (Error reading: ").append(e.getMessage()).append(") ---\n");
            }
        }
    }

    static boolean shouldExclude(String name) {
        if (name == null || name.isBlank()) return true;

        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(".git") || lower.equals(".idea") || lower.equals(".vscode") || lower.equals(".gradle")) return true;
        if (lower.equals("target") || lower.equals("build") || lower.equals("out") || lower.equals("dist")
                || lower.equals("bin") || lower.equals("obj") || lower.equals("node_modules")
                || lower.equals("generated") || lower.equals("generated-sources")
                || lower.equals("generated-test-sources") || lower.equals("coverage")
                || lower.equals("debug") || lower.equals("release") || lower.equals("tmp")
                || lower.equals("temp")) return true;
        if (lower.endsWith(".iml") || lower.endsWith(".ipr") || lower.endsWith(".iws")) return true;
        if (lower.endsWith(".class") || lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".ear")) return true;
        if (lower.equals(".ds_store")) return true;
        return false;
    }

    private static final class VirtualFileNode implements ProjectNode {
        private final VirtualFile file;

        private VirtualFileNode(VirtualFile file) {
            this.file = file;
        }

        @Override
        public String name() {
            return file.getName();
        }

        @Override
        public boolean directory() {
            return file.isDirectory();
        }

        @Override
        public List<ProjectNode> children() {
            VirtualFile[] files = file.getChildren();
            if (files == null || files.length == 0) return List.of();
            List<ProjectNode> nodes = new ArrayList<>(files.length);
            for (VirtualFile child : files) {
                nodes.add(new VirtualFileNode(child));
            }
            return nodes;
        }
    }

    /**
     * Splits a large string into chunks of approximately chunkSize characters.
     */
    public static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        int length = text.length();
        for (int i = 0; i < length; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(length, i + chunkSize)));
        }
        return chunks;
    }

    private static String getFileDescription(VirtualFile file) {
        String name = file.getName();
        if (name.equals("pom.xml")) return "Maven project configuration";
        if (name.equals("README.md")) return "Project documentation";
        if (name.equals(".gitignore")) return "Git ignore rules";
        if (name.equals("plugin.xml")) return "Plugin descriptor (ID, actions, extensions)";
        if (name.equals("ChatPanel.java")) return "Main chat UI panel with streaming output";
        if (name.equals("ChatPanelSupport.java")) return "Pure helper logic extracted from ChatPanel for unit testing";
        if (name.equals("ProjectContextUtil.java")) return "Builds project structure context for LLM";
        if (name.equals("LocalLLMClient.java")) return "HTTP client for LLM Studio / Ollama API";
        if (name.equals("ChatMessage.java")) return "Chat message data model";
        if (name.equals("PluginSettings.java")) return "Persistent plugin settings (PersistentStateComponent)";
        if (name.equals("ChatToolWindowFactory.java")) return "Registers the chat tool window in the IDE";
        if (name.equals("FileOperationUtil.java")) return "File read/write helpers for LLM context";
        if (name.equals("target")) return "Maven build output (compiled classes + jar)";
        return null;
    }

    private static boolean isTextFile(VirtualFile file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") ||
               name.endsWith(".go") || name.endsWith(".py") || name.endsWith(".js") ||
               name.endsWith(".ts") || name.endsWith(".rs") || name.endsWith(".php") ||
               name.endsWith(".rb") || name.endsWith(".cs") || name.endsWith(".scala") ||
               name.endsWith(".xml") || name.endsWith(".md") || name.endsWith(".txt") ||
               name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".gradle") ||
               name.endsWith(".pom") || name.endsWith(".yaml") || name.endsWith(".yml") ||
               name.endsWith(".toml") || name.endsWith(".lock") || name.equals("makefile");
    }
}
