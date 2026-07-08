package plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileOperationUtilTest {

    @Test
    void findTruncatedFileOpPathReturnsPathWhenClosingTagMissing() {
        // Arrange
        String response = "Here is the file:\n<CREATE_FILE path=\"src/test/java/plugin/memory/ConversationMemoryTest.java\">\n"
                + "package plugin.memory;\n\nclass ConversationMemoryTest {\n    void test";

        // Act
        String path = FileOperationUtil.findTruncatedFileOpPath(response);

        // Assert
        assertEquals("src/test/java/plugin/memory/ConversationMemoryTest.java", path);
    }

    @Test
    void findTruncatedFileOpPathReturnsNullForCompleteOperation() {
        // Arrange
        String response = "<CREATE_FILE path=\"src/main/java/Foo.java\">class Foo {}</CREATE_FILE>";

        // Act
        String path = FileOperationUtil.findTruncatedFileOpPath(response);

        // Assert
        assertNull(path);
    }

    @Test
    void findTruncatedFileOpPathReturnsLastPathWhenEarlierOpsAreComplete() {
        // Arrange
        String response = "<CREATE_FILE path=\"src/main/java/Foo.java\">class Foo {}</CREATE_FILE>\n"
                + "<MODIFY_FILE path=\"src/main/java/Bar.java\">class Bar {";

        // Act
        String path = FileOperationUtil.findTruncatedFileOpPath(response);

        // Assert
        assertEquals("src/main/java/Bar.java", path);
    }

    @Test
    void findTruncatedFileOpPathReturnsNullForPlainTextResponse() {
        // Arrange
        String response = "I could not generate the file.";

        // Act
        String path = FileOperationUtil.findTruncatedFileOpPath(response);

        // Assert
        assertNull(path);
    }

    @Test
    void findTruncatedFileOpPathReturnsNullForNullOrBlank() {
        // Act + Assert
        assertNull(FileOperationUtil.findTruncatedFileOpPath(null));
        assertNull(FileOperationUtil.findTruncatedFileOpPath("  "));
    }
}
