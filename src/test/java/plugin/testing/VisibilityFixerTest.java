package plugin.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VisibilityFixerTest {

    @TempDir
    Path projectDir;

    private Path writeSource(String sourceRoot, String relativePath, String content) throws IOException {
        Path file = projectDir.resolve(sourceRoot).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String privateAccessError(Path file, String symbol, String ownerFqn) {
        return "[ERROR] /" + file.toString().replace("\\", "/")
                + ":[24,47] " + symbol + " has private access in " + ownerFqn;
    }

    @Test
    void returnsBlankForNullOrBlankInput() {
        assertEquals("", VisibilityFixer.attemptAutoFix(null, "output"));
        assertEquals("", VisibilityFixer.attemptAutoFix("/base", null));
        assertEquals("", VisibilityFixer.attemptAutoFix("/base", "  "));
        assertEquals("", VisibilityFixer.attemptAutoFix("/base", "[ERROR] unrelated failure"));
    }

    @Test
    void parsesPrivateAccessErrorsWithoutDuplicates() {
        String output = """
                [ERROR] /C:/repo/src/test/java/demo/OwnerTest.java:[24,47] MAX_HISTORY has private access in demo.Owner
                [ERROR] /C:/repo/src/test/java/demo/OwnerTest.java:[28,40] MAX_HISTORY has private access in demo.Owner
                [ERROR] /C:/repo/src/test/java/demo/OtherTest.java:[5,10] NAME has private access in demo.Other
                """;
        List<String[]> errors = VisibilityFixer.privateAccessErrors(output);
        assertEquals(2, errors.size());
        assertEquals("C:/repo/src/test/java/demo/OwnerTest.java", errors.get(0)[0]);
        assertEquals("MAX_HISTORY", errors.get(0)[1]);
        assertEquals("demo.Owner", errors.get(0)[2]);
    }

    @Test
    void replacesIntConstantReferenceWithLiteral() throws IOException {
        writeSource("src/main/java", "demo/Owner.java", """
                package demo;
                public class Owner {
                    private static final int MAX_HISTORY = 50;
                }
                """);
        Path test = writeSource("src/test/java", "demo/OwnerTest.java", """
                package demo;
                public class OwnerTest {
                    int limit = Owner.MAX_HISTORY;
                    int other = Owner.MAX_HISTORY - 1;
                }
                """);

        String summary = VisibilityFixer.attemptAutoFix(projectDir.toString(),
                privateAccessError(test, "MAX_HISTORY", "demo.Owner"));

        assertTrue(summary.contains("Owner.MAX_HISTORY → 50"), summary);
        String fixed = Files.readString(test, StandardCharsets.UTF_8);
        assertFalse(fixed.contains("Owner.MAX_HISTORY"));
        assertTrue(fixed.contains("int limit = 50;"));
        assertTrue(fixed.contains("int other = 50 - 1;"));
    }

    @Test
    void replacesStringConstantReferenceWithLiteral() throws IOException {
        writeSource("src/main/java", "demo/Owner.java", """
                package demo;
                public class Owner {
                    private static final String DEFAULT_ROLE = "user";
                }
                """);
        Path test = writeSource("src/test/java", "demo/OwnerTest.java", """
                package demo;
                public class OwnerTest {
                    String role = Owner.DEFAULT_ROLE;
                }
                """);

        String summary = VisibilityFixer.attemptAutoFix(projectDir.toString(),
                privateAccessError(test, "DEFAULT_ROLE", "demo.Owner"));

        assertFalse(summary.isBlank());
        assertTrue(Files.readString(test, StandardCharsets.UTF_8).contains("String role = \"user\";"));
    }

    @Test
    void skipsNonLiteralInitializers() throws IOException {
        writeSource("src/main/java", "demo/Owner.java", """
                package demo;
                import java.util.List;
                import java.util.ArrayList;
                public class Owner {
                    private static final List<String> ITEMS = new ArrayList<>();
                }
                """);
        Path test = writeSource("src/test/java", "demo/OwnerTest.java", """
                package demo;
                public class OwnerTest {
                    Object items = Owner.ITEMS;
                }
                """);

        assertEquals("", VisibilityFixer.attemptAutoFix(projectDir.toString(),
                privateAccessError(test, "ITEMS", "demo.Owner")));
        assertTrue(Files.readString(test, StandardCharsets.UTF_8).contains("Owner.ITEMS"));
    }

    @Test
    void skipsWhenOwnerSourceIsMissing() throws IOException {
        Path test = writeSource("src/test/java", "demo/OwnerTest.java", """
                package demo;
                public class OwnerTest {
                    int limit = Owner.MAX_HISTORY;
                }
                """);

        assertEquals("", VisibilityFixer.attemptAutoFix(projectDir.toString(),
                privateAccessError(test, "MAX_HISTORY", "demo.Owner")));
    }

    @Test
    void constantLiteralRejectsNonPrivateOrMissingSymbols() throws IOException {
        Path owner = writeSource("src/main/java", "demo/Owner.java", """
                package demo;
                public class Owner {
                    private static final int MAX_HISTORY = 50;
                    public static final int PUBLIC_LIMIT = 10;
                }
                """);
        assertEquals("50", VisibilityFixer.constantLiteral(owner, "MAX_HISTORY"));
        assertNull(VisibilityFixer.constantLiteral(owner, "PUBLIC_LIMIT"));
        assertNull(VisibilityFixer.constantLiteral(owner, "UNKNOWN"));
    }
}
