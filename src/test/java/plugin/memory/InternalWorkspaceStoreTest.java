package plugin.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InternalWorkspaceStoreTest {

    @Test
    void buildsStructuredPathsUnderLocalLlmFolder() {
        String basePath = "C:/repo/project";
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-one");

            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one"), InternalWorkspaceStore.root(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/memory"), InternalWorkspaceStore.memoryDir(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/skills"), InternalWorkspaceStore.skillsDir(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/corrections"), InternalWorkspaceStore.correctionsDir(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/attachments"), InternalWorkspaceStore.attachmentsDir(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/sessions"), InternalWorkspaceStore.sessionsDir(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/memory/project-memory.json"), InternalWorkspaceStore.projectMemoryFile(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/skills/skills.json"), InternalWorkspaceStore.skillMemoryFile(basePath));
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-one/corrections/llm-corrections.md"), InternalWorkspaceStore.correctionsFile(basePath));

            System.setProperty("local.llm.instance.id", "instance-two");
            assertEquals(Path.of("C:/repo/project/.local-llm/instances/instance-two"), InternalWorkspaceStore.root(basePath));
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }
}
