package plugin.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import plugin.llm.AttachmentData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AttachmentUtil {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tif", ".tiff"
    );
    private static final Set<String> PATCH_EXTENSIONS = Set.of(
            ".patch", ".diff", ".rej"
    );
    private static final Set<String> PDF_EXTENSIONS = Set.of(
            ".pdf"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".json", ".yaml", ".yml", ".xml", ".csv", ".tsv",
            ".java", ".kt", ".kts", ".go", ".py", ".js", ".jsx", ".ts", ".tsx",
            ".rs", ".php", ".rb", ".cs", ".scala", ".gradle", ".toml", ".properties",
            ".ini", ".cfg", ".conf", ".sh", ".ps1", ".html", ".css", ".scss", ".sql"
    );
    private static final int MAX_TEXT_CHARS = 20_000;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private AttachmentUtil() {}

    public static List<AttachmentData> loadAttachments(Collection<Path> paths) {
        List<AttachmentData> attachments = new ArrayList<>();
        if (paths == null) return attachments;
        for (Path path : paths) {
            AttachmentData attachment = loadAttachment(path);
            if (attachment != null) {
                attachments.add(attachment);
            }
        }
        return attachments;
    }

    public static AttachmentData loadAttachment(Path path) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) return null;

        try {
            String fileName = path.getFileName().toString();
            String lowerName = fileName.toLowerCase(Locale.ROOT);
            long sizeBytes = Files.size(path);
            String mimeType = detectMimeType(path);

            if (isImage(fileName)) {
                if (sizeBytes > MAX_IMAGE_BYTES) {
                    return new AttachmentData(path, fileName, mimeType, true, null, null, sizeBytes);
                }
                String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                return new AttachmentData(path, fileName, mimeType, true, null, base64, sizeBytes);
            }

            if (isPdf(fileName)) {
                String content = extractPdfText(path);
                if (content == null || content.isBlank()) {
                    content = "PDF attachment: " + fileName + " (" + sizeBytes + " bytes).";
                } else if (content.length() > MAX_TEXT_CHARS) {
                    content = content.substring(0, MAX_TEXT_CHARS) + "\n[...truncated]";
                }
                return new AttachmentData(path, fileName, mimeType, false, content, null, sizeBytes);
            }

            if (isTextLike(fileName, mimeType)) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.length() > MAX_TEXT_CHARS) {
                    content = content.substring(0, MAX_TEXT_CHARS) + "\n[...truncated]";
                }
                return new AttachmentData(path, fileName, mimeType, false, content, null, sizeBytes);
            }

            String preview = "Unsupported file type for direct reading. Path: " + fileName +
                    ", size: " + sizeBytes + " bytes.";
            return new AttachmentData(path, fileName, mimeType, false, preview, null, sizeBytes);
        } catch (IOException e) {
            return null;
        }
    }

    public static String buildPromptBlock(List<AttachmentData> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Attached files:\n");
        for (AttachmentData attachment : attachments) {
            sb.append("- ").append(attachment.displayName())
              .append(" (").append(attachment.mimeType() == null ? "unknown type" : attachment.mimeType())
              .append(", ").append(attachment.sizeBytes()).append(" bytes)");
            if (attachment.image()) {
                sb.append(" [image]");
            }
            sb.append("\n");
            if (attachment.hasTextContent()) {
                sb.append("  Content:\n");
                sb.append(indent(cap(attachment.textContent(), 4000), "  ")).append("\n");
            }
        }
        String taskHint = buildTaskHint(attachments);
        if (!taskHint.isBlank()) {
            sb.append("\nTask guidance:\n");
            sb.append(indent(taskHint, "  "));
        }
        return sb.toString().trim();
    }

    public static boolean containsJavaSourceAttachment(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData a : attachments) {
            if (a != null && a.displayName() != null
                    && a.displayName().toLowerCase(Locale.ROOT).endsWith(".java")
                    && !LanguageSupportUtil.isTestFile(a.displayName())) return true;
        }
        return false;
    }

    public static String buildTaskHint(List<AttachmentData> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        boolean hasPatch = containsPatchAttachment(attachments);
        boolean hasImage = containsImageAttachment(attachments);
        boolean hasConflictMarkers = containsMergeConflictMarkers(attachments);
        boolean hasJira = containsJiraTicketAttachment(attachments);
        boolean hasJavaSource = containsJavaSourceAttachment(attachments);
        StringBuilder sb = new StringBuilder();
        if (hasJavaSource) {
            sb.append("The attached file is a Java source class. If the user asks for tests,")
              .append(" write a compile-ready JUnit 5 test file using the AAA pattern.")
              .append(" Use ONLY the constructors and methods present in the attached source — do NOT invent overloads.")
              .append(" Infer the test file path from the source package (e.g. src/main/java/a/b/Foo.java")
              .append(" → src/test/java/a/b/FooTest.java).")
              .append(" Return exactly one complete XML tag:")
              .append(" <CREATE_FILE path=\"src/test/java/...\">full content</CREATE_FILE>.");
        }
        if (hasPatch) {
            sb.append("Apply the attached git patch to the current branch.");
            sb.append(" If conflicts appear, resolve them against the current workspace and keep the intended changes.");
            if (hasConflictMarkers) {
                sb.append(" The patch already contains merge-conflict markers, so resolve those hunks deliberately.");
            }
        }
        if (hasImage) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Inspect the attached image(s), especially any UI marks, arrows, highlights, boxes, or annotations,");
            sb.append(" then fix the issue they point to.");
        }
        if (containsPdfAttachment(attachments)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Use the attached PDF as the source spec or design document. If the user asks for an Angular app,")
              .append(" infer the UI from the PDF and implement the Angular application structure, components, and styles.");
        }
        if (hasJira) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Read the attached Jira ticket description, identify the requested feature or bug fix,");
            sb.append(" inspect the current codebase, then implement the change with minimal safe edits and matching tests.");
        }
        return sb.toString().trim();
    }

    public static String suggestedUserPrompt(List<AttachmentData> attachments) {
        String hint = buildTaskHint(attachments);
        if (hint.isBlank()) {
            return "Inspect the attached file(s) and help with the most relevant task.";
        }
        if (containsJiraTicketAttachment(attachments)) {
            return hint + " Use the ticket requirements to drive implementation in the current project.";
        }
        return hint;
    }

    public static boolean containsPatchAttachment(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData attachment : attachments) {
            if (attachment != null && isPatch(attachment.displayName())) return true;
        }
        return false;
    }

    public static boolean containsImageAttachment(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData attachment : attachments) {
            if (attachment != null && attachment.image()) return true;
        }
        return false;
    }

    public static boolean containsPdfAttachment(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData attachment : attachments) {
            if (attachment != null && isPdf(attachment.displayName())) return true;
        }
        return false;
    }

    public static boolean containsMergeConflictMarkers(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData attachment : attachments) {
            if (attachment == null || !attachment.hasTextContent()) continue;
            String content = attachment.textContent();
            if (content.contains("<<<<<<<") || content.contains("=======") || content.contains(">>>>>>>")) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsJiraTicketAttachment(List<AttachmentData> attachments) {
        if (attachments == null) return false;
        for (AttachmentData attachment : attachments) {
            if (attachment == null) continue;
            if (looksLikeJiraTicketText(attachment.displayName())) return true;
            if (attachment.hasTextContent() && looksLikeJiraTicketText(attachment.textContent())) return true;
        }
        return false;
    }

    public static boolean looksLikeJiraTicketText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("jira")
                || lower.contains("issue key")
                || lower.contains("acceptance criteria")
                || lower.contains("story points")
                || lower.contains("as a ")
                || lower.contains("i want ")
                || lower.contains("so that ")
                || lower.contains("bug")
                || lower.contains("feature request");
    }

    public static boolean containsAngularBuildIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("angular")
                || lower.contains("build app from it")
                || lower.contains("build an angular app")
                || lower.contains("angular app")
                || lower.contains("component")
                || lower.contains("ui from this");
    }

    public static boolean isImage(String fileName) {
        String lower = normalize(fileName);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    public static boolean isPdf(String fileName) {
        String lower = normalize(fileName);
        for (String extension : PDF_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    public static boolean isPatch(String fileName) {
        String lower = normalize(fileName);
        for (String extension : PATCH_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    public static boolean isTextLike(String fileName, String mimeType) {
        String lower = normalize(fileName);
        for (String extension : TEXT_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return mimeType != null && mimeType.startsWith("text/") || isPatch(fileName);
    }

    private static String detectMimeType(Path path) throws IOException {
        String mime = Files.probeContentType(path);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String extractPdfText(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String fileName) {
        return fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    }

    private static String cap(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return text.length() > max ? text.substring(0, max) + "\n[...truncated]" : text;
    }

    private static String indent(String text, String prefix) {
        String[] lines = text.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(prefix).append(lines[i]);
        }
        return sb.toString();
    }
}
