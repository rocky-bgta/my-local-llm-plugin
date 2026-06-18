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
        if (sb.length() > maxChars) return;

        String fileName = file.getName();
        if (fileName.startsWith(".") || fileName.equals("target") || fileName.equals("out") || fileName.equals("node_modules")) {
            return;
        }

        if (file.isDirectory()) {
            if (indent.isEmpty()) {
                sb.append(fileName).append("/\n");
            } else {
                sb.append(indent).append(fileName).append("/\n");
            }
            VirtualFile[] children = file.getChildren();
            if (children != null) {
                for (VirtualFile child : children) {
                    appendFileTree(child, indent + "  ", sb, includeContent, maxChars);
                }
            }
        } else {
            sb.append(indent).append(fileName).append("\n");
            if (includeContent) {
                try {
                    // Only include text files and reasonably sized files
                    if (isTextFile(file) && file.getLength() < 50000) {
                        String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                        
                        int remaining = maxChars - sb.length();
                        if (remaining <= 0) return;
                        
                        if (content.length() > remaining) {
                            content = content.substring(0, remaining) + "\n... (content truncated)";
                        }

                        sb.append(indent).append("  --- CONTENT START ---\n");
                        sb.append(content).append("\n");
                        sb.append(indent).append("  --- CONTENT END ---\n");
                    }
                } catch (IOException e) {
                    sb.append(indent).append("  Error reading file: ").append(e.getMessage()).append("\n");
                }
            }
        }
    }

    private static boolean isTextFile(VirtualFile file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".md") || 
               name.endsWith(".txt") || name.endsWith(".properties") || name.endsWith(".json") ||
               name.endsWith(".gradle") || name.endsWith(".kts") || name.endsWith(".pom") ||
               name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
