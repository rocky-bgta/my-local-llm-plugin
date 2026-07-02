package plugin.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LLMCorrectionsUtilTest {

    @Test
    void writesCorrectionsIntoStructuredFolder() throws Exception {
        Path baseDir = Files.createTempDirectory("llm-corrections");
        String previous = System.getProperty("local.llm.instance.id");
        System.setProperty("local.llm.instance.id", "instance-a");
        try {
            LLMCorrectionsUtil.recordMistake(baseDir.toString(), "use-xml-tags");

            Path expected = baseDir.resolve(".local-llm/instances/instance-a/corrections/llm-corrections.md");
            assertTrue(Files.exists(expected));
            assertTrue(LLMCorrectionsUtil.loadCorrectionsForPrompt(baseDir.toString()).contains("use-xml-tags"));
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }
}
