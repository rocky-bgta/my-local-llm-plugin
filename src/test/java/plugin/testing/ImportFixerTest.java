package plugin.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImportFixerTest {

    @TempDir
    Path projectDir;

    private Path write(String relPath, String content) throws IOException {
        Path file = projectDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private String mavenError(Path file, String symbol) {
        String unixPath = "/" + file.toString().replace("\\", "/");
        return "[ERROR] " + unixPath + ":[15,60] cannot find symbol\n"
                + "[ERROR]   symbol:   class " + symbol + "\n"
                + "[ERROR]   location: class plugin.memory.ConversationMemoryTest\n";
    }

    @Test
    void returnsEmptyForNullOrBlankInput() {
        assertEquals("", ImportFixer.attemptAutoFix(null, "cannot find symbol"));
        assertEquals("", ImportFixer.attemptAutoFix(projectDir.toString(), ""));
        assertEquals("", ImportFixer.attemptAutoFix(projectDir.toString(), null));
    }

    @Test
    void parsesMissingSymbolsGroupedByFile() {
        String output = """
                [ERROR] /C:/repo/src/test/java/pkg/FooTest.java:[15,60] cannot find symbol
                [ERROR]   symbol:   class ChatMessage
                [ERROR] /C:/repo/src/test/java/pkg/FooTest.java:[53,9] cannot find symbol
                [ERROR]   symbol:   class List
                """;

        Map<String, Set<String>> parsed = ImportFixer.missingSymbolsByFile("C:/repo", output);

        assertEquals(1, parsed.size());
        assertEquals(Set.of("ChatMessage", "List"), parsed.get("C:/repo/src/test/java/pkg/FooTest.java"));
    }

    @Test
    void resolvesProjectClassBeforeWellKnownTypes() throws IOException {
        write("src/main/java/plugin/llm/model/ChatMessage.java",
                "package plugin.llm.model;\npublic record ChatMessage(String role, String content) {}");

        assertEquals("plugin.llm.model.ChatMessage", ImportFixer.resolve(projectDir.toString(), "ChatMessage"));
        assertEquals("java.util.List", ImportFixer.resolve(projectDir.toString(), "List"));
        assertNull(ImportFixer.resolve(projectDir.toString(), "TotallyUnknownClass"));
    }

    @Test
    void ambiguousProjectClassIsNotResolved() throws IOException {
        write("src/main/java/a/Helper.java", "package a;\npublic class Helper {}");
        write("src/main/java/b/Helper.java", "package b;\npublic class Helper {}");

        assertNull(ImportFixer.resolve(projectDir.toString(), "Helper"));
    }

    @Test
    void insertsMissingImportsAfterPackageDeclaration() throws IOException {
        write("src/main/java/plugin/llm/model/ChatMessage.java",
                "package plugin.llm.model;\npublic record ChatMessage(String role, String content) {}");
        Path test = write("src/test/java/plugin/memory/ConversationMemoryTest.java", """
                package plugin.memory;

                import org.junit.jupiter.api.Test;

                class ConversationMemoryTest {
                    List<ChatMessage> messages;
                }
                """);
        String output = mavenError(test, "ChatMessage") + mavenError(test, "List");

        String summary = ImportFixer.attemptAutoFix(projectDir.toString(), output);

        assertTrue(summary.contains("Auto-fixed missing imports"));
        String content = Files.readString(test);
        assertTrue(content.contains("import plugin.llm.model.ChatMessage;"));
        assertTrue(content.contains("import java.util.List;"));
        assertTrue(content.indexOf("package plugin.memory;") < content.indexOf("import plugin.llm.model.ChatMessage;"));
    }

    @Test
    void doesNotDuplicateExistingImport() throws IOException {
        Path test = write("src/test/java/plugin/memory/FooTest.java", """
                package plugin.memory;

                import java.util.List;

                class FooTest {}
                """);
        String output = mavenError(test, "List");

        assertEquals("", ImportFixer.attemptAutoFix(projectDir.toString(), output));
        String content = Files.readString(test);
        assertEquals(content.indexOf("import java.util.List;"), content.lastIndexOf("import java.util.List;"));
    }

    @Test
    void fixCommonImportsInsertsMissingJavaUtilImportsPreBuild() throws IOException {
        write("src/test/java/plugin/rag/RerankerTest.java", """
                package plugin.rag;

                import org.junit.jupiter.api.Test;

                class RerankerTest {
                    void t() {
                        List<String> l = new ArrayList<>();
                        Collections.sort(l);
                        Arrays.stream(new String[]{"a"}).collect(Collectors.toList());
                    }
                }
                """);

        String summary = ImportFixer.fixCommonImports(projectDir.toString(),
                "src/test/java/plugin/rag/RerankerTest.java");

        assertTrue(summary.contains("Auto-fixed missing imports before build"));
        String content = Files.readString(projectDir.resolve("src/test/java/plugin/rag/RerankerTest.java"));
        assertTrue(content.contains("import java.util.List;"));
        assertTrue(content.contains("import java.util.ArrayList;"));
        assertTrue(content.contains("import java.util.Collections;"));
        assertTrue(content.contains("import java.util.Arrays;"));
        assertTrue(content.contains("import java.util.stream.Collectors;"));
    }

    @Test
    void fixCommonImportsReturnsEmptyWhenNothingMissing() throws IOException {
        write("src/test/java/plugin/rag/CleanTest.java", """
                package plugin.rag;

                import java.util.List;

                class CleanTest { List<String> l; }
                """);

        assertEquals("", ImportFixer.fixCommonImports(projectDir.toString(),
                "src/test/java/plugin/rag/CleanTest.java"));
        assertEquals("", ImportFixer.fixCommonImports(projectDir.toString(),
                "src/test/java/plugin/rag/MissingTest.java"));
        assertEquals("", ImportFixer.fixCommonImports(null, "x.java"));
        assertEquals("", ImportFixer.fixCommonImports(projectDir.toString(), ""));
    }

    @Test
    void skipsSamePackageSymbols() throws IOException {
        write("src/main/java/plugin/memory/Sibling.java", "package plugin.memory;\npublic class Sibling {}");
        Path test = write("src/test/java/plugin/memory/BarTest.java",
                "package plugin.memory;\n\nclass BarTest { Sibling s; }\n");
        String output = mavenError(test, "Sibling");

        assertEquals("", ImportFixer.attemptAutoFix(projectDir.toString(), output));
    }
}
