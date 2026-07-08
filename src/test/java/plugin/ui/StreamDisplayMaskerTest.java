package plugin.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamDisplayMaskerTest {

    private StreamDisplayMasker masker;

    @BeforeEach
    void setUp() {
        masker = new StreamDisplayMasker();
    }

    private String feedAll(String... tokens) {
        StringBuilder out = new StringBuilder();
        for (String t : tokens) {
            out.append(masker.feed(t));
        }
        out.append(masker.finish());
        return out.toString();
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        String out = feedAll("Hello ", "world, no tags here.");

        assertEquals("Hello world, no tags here.", out);
    }

    @Test
    void createFileBlockIsReplacedByPlaceholder() {
        String out = feedAll("Before.\n<CREATE_FILE path=\"src/test/Foo.java\">secret content</CREATE_FILE>\nAfter.");

        assertFalse(out.contains("secret content"));
        assertFalse(out.contains("<CREATE_FILE"));
        assertTrue(out.contains("CREATE_FILE src/test/Foo.java"));
        assertTrue(out.startsWith("Before.\n"));
        assertTrue(out.endsWith("\nAfter."));
    }

    @Test
    void modifyFileBlockIsMasked() {
        String out = feedAll("<MODIFY_FILE path=\"a/B.java\">body</MODIFY_FILE>");

        assertFalse(out.contains("body"));
        assertTrue(out.contains("MODIFY_FILE a/B.java"));
    }

    @Test
    void tagSplitAcrossManyTokensIsStillMasked() {
        String out = feedAll("x<CRE", "ATE_FILE pa", "th=\"p/Q.java\"", ">secret", " stuff</CREATE_F", "ILE>y");

        assertFalse(out.contains("secret"));
        assertFalse(out.contains("<CREATE_FILE"));
        assertTrue(out.contains("CREATE_FILE p/Q.java"));
        assertTrue(out.startsWith("x"));
        assertTrue(out.endsWith("y"));
    }

    @Test
    void nonFileTagAngleBracketsPassThrough() {
        String out = feedAll("use <b>bold</b> and a < b comparison");

        assertEquals("use <b>bold</b> and a < b comparison", out);
    }

    @Test
    void unterminatedBlockDoesNotLeakContentOnFinish() {
        String out = feedAll("intro\n<CREATE_FILE path=\"x/Y.java\">partial content that never closes");

        assertFalse(out.contains("partial content"));
        assertTrue(out.contains("CREATE_FILE x/Y.java"));
    }

    @Test
    void resetClearsPendingState() {
        masker.feed("<CREATE_FILE path=\"a.java\">open");
        masker.reset();

        assertEquals("clean text", feedAll("clean text"));
    }

    @Test
    void twoFileBlocksBothMasked() {
        String out = feedAll("<CREATE_FILE path=\"A.java\">one</CREATE_FILE>mid"
                + "<MODIFY_FILE path=\"B.java\">two</MODIFY_FILE>");

        assertFalse(out.contains("one"));
        assertFalse(out.contains("two"));
        assertTrue(out.contains("CREATE_FILE A.java"));
        assertTrue(out.contains("MODIFY_FILE B.java"));
        assertTrue(out.contains("mid"));
    }
}
