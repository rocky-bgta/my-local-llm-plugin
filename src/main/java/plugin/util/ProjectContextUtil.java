package plugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ProjectContextUtil {

    public static String getProjectContext(Project project, boolean includeContent) {
        StringBuilder sb = new StringBuilder();
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) return "Project base directory not found.";

        sb.append("Current Project Structure:\n");
        sb.append("Note: Directories like target/, out/, node_modules/ and hidden files are excluded.\n\n");
        sb.append(baseDir.getName()).append("/\n");
        appendTree(baseDir, "", sb, 16000);

        if (includeContent) {
            sb.append("\nDetailed File Contents:\n");
            int maxChars = 64000;
            appendFileContents(baseDir, sb, maxChars);
        }
        return sb.toString();
    }

    private static void appendTree(VirtualFile file, String indent, StringBuilder sb, int maxChars) {
        if (sb.length() > maxChars) return;

        VirtualFile[] children = file.getChildren();
        if (children == null) return;

        List<VirtualFile> filtered = new ArrayList<>();
        for (VirtualFile child : children) {
            if (shouldExclude(child.getName())) continue;
            filtered.add(child);
        }

        for (int i = 0; i < filtered.size(); i++) {
            VirtualFile child = filtered.get(i);
            boolean isLast = (i == filtered.size() - 1);

            sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getName());
            if (child.isDirectory()) {
                sb.append("/");
            }
            sb.append("\n");

            if (child.isDirectory()) {
                // Special case for target: don't recurse
                if (child.getName().equals("target")) continue;
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

    private static boolean shouldExclude(String name) {
        return (name.startsWith(".") && !name.equals(".gitignore")) || name.equals("out") || name.equals("node_modules");
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
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".md") || 
               name.endsWith(".txt") || name.endsWith(".properties") || name.endsWith(".json") ||
               name.endsWith(".gradle") || name.endsWith(".kts") || name.endsWith(".pom") ||
               name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
