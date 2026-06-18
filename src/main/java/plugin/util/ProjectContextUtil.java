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
        appendFileTree(baseDir, "", sb, false, 8000); // Increased limit for structure

        if (includeContent) {
            sb.append("\nDetailed File Contents:\n");
            // Increased limit to stay within common 128k context windows often used today, 
            // but still being conservative for LM Studio's default models.
            int maxChars = 64000; 
            appendFileTree(baseDir, "", sb, true, maxChars);
        }
        return sb.toString();
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

    private static void appendFileTree(VirtualFile file, String indent, StringBuilder sb, boolean includeContent, int maxChars) {
        appendFileTreeRecursive(file, "", true, sb, includeContent, maxChars);
    }

    private static void appendFileTreeRecursive(VirtualFile file, String indent, boolean isLast, StringBuilder sb, boolean includeContent, int maxChars) {
        if (sb.length() > maxChars) return;

        String fileName = file.getName();
        // Adjust exclusions: allow .gitignore and target (per user request)
        if (fileName.startsWith(".") && !fileName.equals(".gitignore")) {
            return;
        }
        if (fileName.equals("out") || fileName.equals("node_modules")) {
            return;
        }

        String prefix = indent.isEmpty() ? "" : (isLast ? "└── " : "├── ");
        sb.append(indent).append(prefix).append(fileName).append(file.isDirectory() ? "/" : "");

        // Add descriptions for key files
        String description = getFileDescription(file);
        if (description != null) {
            sb.append("         # ").append(description);
        }
        sb.append("\n");

        if (file.isDirectory()) {
            // Special case: don't recurse into target/ to keep it clean, but show it exists
            if (fileName.equals("target")) {
                return;
            }

            VirtualFile[] children = file.getChildren();
            if (children != null) {
                // Filter children to avoid showing excluded ones in the count
                List<VirtualFile> filteredChildren = new ArrayList<>();
                for (VirtualFile child : children) {
                    String childName = child.getName();
                    if (childName.startsWith(".") && !childName.equals(".gitignore")) continue;
                    if (childName.equals("out") || childName.equals("node_modules")) continue;
                    filteredChildren.add(child);
                }

                String newIndent = indent + (indent.isEmpty() ? "" : (isLast ? "    " : "│   "));
                for (int i = 0; i < filteredChildren.size(); i++) {
                    appendFileTreeRecursive(filteredChildren.get(i), newIndent, i == filteredChildren.size() - 1, sb, includeContent, maxChars);
                }
            }
        } else {
            if (includeContent) {
                try {
                    // Only include text files and reasonably sized files
                    if (isTextFile(file) && file.getLength() < 50000) {
                        String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                        
                        int remaining = maxChars - sb.length();
                        if (remaining > 0) {
                            if (content.length() > remaining) {
                                content = content.substring(0, remaining) + "\n... (content truncated)";
                            }
                            String contentIndent = indent + (isLast ? "    " : "│   ");
                            sb.append(contentIndent).append("  --- CONTENT START ---\n");
                            // Indent content for readability
                            for (String line : content.split("\n")) {
                                sb.append(contentIndent).append("  ").append(line).append("\n");
                            }
                            sb.append(contentIndent).append("  --- CONTENT END ---\n");
                        }
                    }
                } catch (IOException e) {
                    sb.append(indent).append("  Error reading file: ").append(e.getMessage()).append("\n");
                }
            }
        }
    }

    private static String getFileDescription(VirtualFile file) {
        String name = file.getName();
        if (name.equals("pom.xml")) return "Maven project configuration";
        if (name.equals("README.md")) return "Project documentation";
        if (name.equals(".gitignore")) return "Git ignore rules";
        if (name.equals("plugin.xml")) return "Plugin descriptor (ID, actions, extensions)";
        if (name.equals("ChatPanel.java")) return "Main chat UI panel with streaming output";
        if (name.equals("ProjectContextUtil.java")) return "Builds project structure context for LLM";
        if (name.equals("LMStudioClient.java")) return "HTTP client for LLM Studio / Ollama API";
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
