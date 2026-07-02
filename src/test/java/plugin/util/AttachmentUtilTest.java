package plugin.util;

import org.junit.jupiter.api.Test;
import plugin.llm.AttachmentData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public class AttachmentUtilTest {

    @Test
    void loadsTextAttachmentsAndBuildsPromptBlock() throws Exception {
        Path tempFile = Files.createTempFile("attachment", ".txt");
        Files.writeString(tempFile, "hello attachment", StandardCharsets.UTF_8);

        AttachmentData attachment = AttachmentUtil.loadAttachment(tempFile);

        assertNotNull(attachment);
        assertFalse(attachment.image());
        assertTrue(attachment.hasTextContent());
        assertTrue(AttachmentUtil.buildPromptBlock(List.of(attachment)).contains("hello attachment"));
    }

    @Test
    void loadsImageAttachmentsAsBase64() throws Exception {
        Path tempFile = Files.createTempFile("image-attachment", ".png");
        Files.write(tempFile, new byte[] {1, 2, 3, 4});

        AttachmentData attachment = AttachmentUtil.loadAttachment(tempFile);

        assertNotNull(attachment);
        assertTrue(attachment.image());
        assertTrue(attachment.hasImageContent());
        assertNotNull(attachment.base64Content());
    }

    @Test
    void identifiesSupportedAttachmentTypes() {
        assertTrue(AttachmentUtil.isImage("screenshot.png"));
        assertTrue(AttachmentUtil.isPatch("change.patch"));
        assertTrue(AttachmentUtil.isTextLike("notes.md", "text/markdown"));
        assertFalse(AttachmentUtil.isImage("notes.md"));
    }

    @Test
    void buildsTaskHintsForPatchAndImageAttachments() throws Exception {
        Path patchFile = Files.createTempFile("change", ".patch");
        Files.writeString(patchFile, "<<<<<<< HEAD\ndiff --git a/a b/a\n=======\nconflict\n>>>>>>> branch\n", StandardCharsets.UTF_8);
        Path imageFile = Files.createTempFile("annotated", ".png");
        Files.write(imageFile, new byte[] {1, 2, 3, 4});

        List<AttachmentData> attachments = AttachmentUtil.loadAttachments(List.of(patchFile, imageFile));
        String hint = AttachmentUtil.buildTaskHint(attachments);
        String prompt = AttachmentUtil.suggestedUserPrompt(attachments);

        assertTrue(hint.contains("git patch"));
        assertTrue(hint.contains("Inspect the attached image"));
        assertTrue(hint.contains("merge-conflict markers"));
        assertTrue(prompt.contains("Apply the attached git patch"));
        assertTrue(AttachmentUtil.containsMergeConflictMarkers(attachments));
    }

    @Test
    void detectsJiraTicketAttachmentsAndBuildsTicketPrompt() throws Exception {
        Path ticketFile = Files.createTempFile("jira-ticket", ".md");
        Files.writeString(ticketFile, """
                JIRA-123
                Title: Add export button
                Acceptance Criteria:
                - User can export the report as CSV
                - Add unit tests
                """, StandardCharsets.UTF_8);

        AttachmentData attachment = AttachmentUtil.loadAttachment(ticketFile);
        assertNotNull(attachment);

        List<AttachmentData> attachments = List.of(attachment);
        String hint = AttachmentUtil.buildTaskHint(attachments);
        String prompt = AttachmentUtil.suggestedUserPrompt(attachments);

        assertTrue(AttachmentUtil.containsJiraTicketAttachment(attachments));
        assertTrue(AttachmentUtil.looksLikeJiraTicketText(attachment.textContent()));
        assertTrue(hint.contains("Jira ticket"));
        assertTrue(prompt.contains("Use the ticket requirements"));
    }

    @Test
    void extractsPdfTextAndBuildsAngularHint() throws Exception {
        Path pdfFile = Files.createTempFile("angular-spec", ".pdf");
        createPdf(pdfFile, "Build an Angular app with a sidebar and dashboard.");

        AttachmentData attachment = AttachmentUtil.loadAttachment(pdfFile);
        assertNotNull(attachment);
        assertTrue(attachment.hasTextContent());
        assertTrue(attachment.textContent().contains("Angular app"));

        List<AttachmentData> attachments = List.of(attachment);
        String hint = AttachmentUtil.buildTaskHint(attachments);

        assertTrue(AttachmentUtil.containsPdfAttachment(attachments));
        assertTrue(hint.contains("Angular app"));
    }

    @Test
    void detectsAngularBuildIntent() {
        assertTrue(AttachmentUtil.containsAngularBuildIntent("Please build an Angular app from this image"));
        assertTrue(AttachmentUtil.containsAngularBuildIntent("Build app from it"));
        assertTrue(AttachmentUtil.containsAngularBuildIntent("ui from this PDF"));
    }

    private static void createPdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(path.toFile());
        }
    }
}
