package plugin.retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetryContextEngineTest {

    @TempDir
    Path projectDir;

    private void write(String relPath, String content) throws IOException {
        Path file = projectDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void levelOneIncludesAttachedSource() {
        String context = RetryContextEngine.buildRetryContext(
                null, 1, "src/main/java/com/example/Foo.java", "public class Foo {}");

        assertTrue(context.contains("expansion level 1/4"));
        assertTrue(context.contains("# Source under test/fix"));
        assertTrue(context.contains("src/main/java/com/example/Foo.java"));
        assertTrue(context.contains("public class Foo {}"));
        assertFalse(context.contains("# Related project classes"));
    }

    @Test
    void clampsAttemptToValidLevelRange() {
        String low = RetryContextEngine.buildRetryContext(null, 0, "Foo.java", "class Foo {}");
        String high = RetryContextEngine.buildRetryContext(null, 99, "Foo.java", "class Foo {}");

        assertTrue(low.contains("expansion level 1/4"));
        assertTrue(high.contains("expansion level 4/4"));
    }

    @Test
    void returnsEmptyWhenNothingCollected() {
        assertEquals("", RetryContextEngine.buildRetryContext(null, 1, "", ""));
    }

    @Test
    void levelTwoAddsClassesReferencedByImports() throws IOException {
        write("src/main/java/com/example/Helper.java", "package com.example;\npublic class Helper {}");
        String target = "package com.example;\nimport com.example.Helper;\npublic class Foo {}";

        String related = RetryContextEngine.collectRelatedClasses(
                projectDir.toString(), "src/main/java/com/example/Foo.java", target);

        assertTrue(related.contains("src/main/java/com/example/Helper.java"));
        assertTrue(related.contains("public class Helper {}"));
    }

    @Test
    void relatedClassesSkipsTargetItself() throws IOException {
        write("src/main/java/com/example/Foo.java", "package com.example;\npublic class Foo {}");
        String target = "package com.example;\nimport com.example.Foo;\npublic class Foo {}";

        String related = RetryContextEngine.collectRelatedClasses(
                projectDir.toString(), "src/main/java/com/example/Foo.java", target);

        assertEquals("", related);
    }

    @Test
    void levelThreeAddsExistingTestExamples() throws IOException {
        write("src/test/java/com/example/BarTest.java",
                "package com.example;\nclass BarTest { void ok() {} }");

        String examples = RetryContextEngine.collectTestExamples(
                projectDir.toString(), "src/main/java/com/example/Foo.java");

        assertTrue(examples.contains("src/test/java/com/example/BarTest.java"));
        assertTrue(examples.contains("class BarTest"));
    }

    @Test
    void testExamplesSkipTheTargetTestPath() throws IOException {
        write("src/test/java/com/example/FooTest.java", "class FooTest {}");

        String examples = RetryContextEngine.collectTestExamples(
                projectDir.toString(), "src/main/java/com/example/Foo.java");

        assertEquals("", examples);
    }

    @Test
    void dependencyInfoExtractsMavenDependenciesSection() throws IOException {
        write("pom.xml", "<project>\n<dependencies>\n<dependency><artifactId>gson</artifactId></dependency>\n</dependencies>\n</project>");

        String deps = RetryContextEngine.collectDependencyInfo(projectDir.toString());

        assertTrue(deps.startsWith("<dependencies>"));
        assertTrue(deps.contains("gson"));
        assertTrue(deps.endsWith("</dependencies>"));
    }

    @Test
    void dependencyInfoFallsBackToOtherBuildFiles() throws IOException {
        write("build.gradle", "dependencies { implementation 'com.google.code.gson:gson:2.10.1' }");

        String deps = RetryContextEngine.collectDependencyInfo(projectDir.toString());

        assertTrue(deps.startsWith("build.gradle:"));
        assertTrue(deps.contains("gson"));
    }

    @Test
    void projectStructureExcludesBuildOutputDirectories() throws IOException {
        write("src/main/java/com/example/Foo.java", "class Foo {}");
        write("target/classes/Foo.class", "binary");
        write(".git/HEAD", "ref");

        String structure = RetryContextEngine.collectProjectStructure(projectDir.toString());

        assertTrue(structure.contains("src/main/java/com/example/Foo.java"));
        assertFalse(structure.contains("target/classes"));
        assertFalse(structure.contains(".git/HEAD"));
    }

    @Test
    void higherLevelsAccumulateAllSections() throws IOException {
        write("src/main/java/com/example/Helper.java", "package com.example;\npublic class Helper {}");
        write("src/test/java/com/example/BarTest.java", "class BarTest {}");
        write("pom.xml", "<project>\n<dependencies>\n</dependencies>\n</project>");
        String target = "package com.example;\nimport com.example.Helper;\npublic class Foo {}";

        String context = RetryContextEngine.buildRetryContext(
                projectDir.toString(), 4, "src/main/java/com/example/Foo.java", target);

        assertTrue(context.contains("expansion level 4/4"));
        assertTrue(context.contains("# Source under test/fix"));
        assertTrue(context.contains("# Related project classes"));
        assertTrue(context.contains("# Existing test examples"));
        assertTrue(context.contains("# Declared build dependencies"));
        assertTrue(context.contains("# Project structure preview"));
    }
}
