package plugin.memory;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SkillMemoryTest {

    @Test
    void remembersAndRecalledSkillsPersistToDisk() throws Exception {
        Path baseDir = Files.createTempDirectory("skill-memory");
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(baseDir.toString());
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-one");

            SkillMemory memory = new SkillMemory(project);
            memory.remember("angular-app", "Build Angular apps from PDFs and images.");

            SkillMemory reloaded = new SkillMemory(project);
            assertTrue(reloaded.recall("angular-app").isPresent());
            assertEquals("Build Angular apps from PDFs and images.",
                    reloaded.recall("angular-app").orElseThrow().description());
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }

    @Test
    void buildsRelevantSkillContextFromQuery() throws Exception {
        Path baseDir = Files.createTempDirectory("skill-memory-query");
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(baseDir.toString());
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-two");

            SkillMemory memory = new SkillMemory(project);
            memory.remember("patch-workflow", "Apply patch files and resolve conflicts.");
            memory.remember("jira-ticket-workflow", "Implement Jira tickets with tests.");

            String context = memory.buildContextSummary("apply patch with conflicts");

            assertTrue(context.contains("patch-workflow"));
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }

    @Test
    void separatesSkillsAcrossDifferentInstanceIds() throws Exception {
        Path baseDir = Files.createTempDirectory("skill-memory-isolation");
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(baseDir.toString());
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-a");
            SkillMemory first = new SkillMemory(project);
            first.remember("readme", "Write project documentation.");

            System.setProperty("local.llm.instance.id", "instance-b");
            SkillMemory second = new SkillMemory(project);

            assertTrue(first.recall("readme").isPresent());
            assertTrue(second.recall("readme").isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }

    @Test
    void remembersSessionSolutionsAsReusableSkills() throws Exception {
        Path baseDir = Files.createTempDirectory("skill-memory-session");
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(baseDir.toString());
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-session");

            SkillMemory memory = new SkillMemory(project);
            memory.rememberFromSession(
                    "test-case-pattern",
                    "write a test case using the triple F pattern",
                    """
                    AAA pattern: Arrange, Act, Assert.
                    Use the detected language's native test framework.
                    Keep the test focused on the behavior that was solved.
                    """);

            SkillMemory reloaded = new SkillMemory(project);
            assertTrue(reloaded.recall("test-case-pattern").isPresent());
            assertTrue(reloaded.recall("test-case-pattern").orElseThrow().description().contains("triple F pattern"));
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }

    @Test
    void updatesDisablesAndDeletesSkills() throws Exception {
        Path baseDir = Files.createTempDirectory("skill-memory-crud");
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(baseDir.toString());
        String previous = System.getProperty("local.llm.instance.id");
        try {
            System.setProperty("local.llm.instance.id", "instance-crud");

            SkillMemory memory = new SkillMemory(project);
            memory.remember("aaa-pattern", "Use Arrange-Act-Assert.");
            memory.update("aaa-pattern", "aaa-pattern", "Use Arrange-Act-Assert with focused assertions.", true);
            assertTrue(memory.recall("aaa-pattern").isPresent());
            assertTrue(memory.recall("aaa-pattern").orElseThrow().description().contains("focused assertions"));

            memory.setEnabled("aaa-pattern", false);
            assertTrue(memory.recall("aaa-pattern").orElseThrow().enabled() == false);
            assertTrue(memory.buildContextSummary("aaa").isEmpty());

            memory.delete("aaa-pattern");
            assertTrue(memory.recall("aaa-pattern").isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("local.llm.instance.id");
            } else {
                System.setProperty("local.llm.instance.id", previous);
            }
        }
    }
}
