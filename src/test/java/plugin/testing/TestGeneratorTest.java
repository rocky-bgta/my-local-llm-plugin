package plugin.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGeneratorTest {

    @TempDir
    Path projectDir;

    private final TestGenerator generator = new TestGenerator();

    private Path write(String relPath, String content) throws IOException {
        Path file = projectDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ── inferTestClassName ────────────────────────────────────────────────────

    @Test
    void inferTestClassNameAppendsTestSuffix() {
        assertEquals("FooTest", generator.inferTestClassName("Foo"));
        assertEquals("OrderServiceTest", generator.inferTestClassName("OrderService"));
    }

    // ── inferTestPath ─────────────────────────────────────────────────────────

    @Test
    void inferTestPathProducesJUnit5ConventionalPath() {
        // Arrange
        String fullyQualifiedName = "plugin.testing.ImportFixer";

        // Act
        String path = generator.inferTestPath(fullyQualifiedName);

        // Assert
        assertEquals("src/test/java/plugin/testing/ImportFixerTest.java", path);
    }

    @Test
    void inferTestPathHandlesTopLevelClass() {
        String path = generator.inferTestPath("Foo");

        assertTrue(path.endsWith("FooTest.java"));
    }

    // ── buildFileDropTestPrompt — guard cases ─────────────────────────────────

    @Test
    void returnsEmptyForMissingFile() {
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("does_not_exist.java"), projectDir.toString());

        assertEquals("", result);
    }

    @Test
    void returnsEmptyForNullFile() {
        String result = generator.buildFileDropTestPrompt(null, projectDir.toString());

        assertEquals("", result);
    }

    // ── buildFileDropTestPrompt — language and path ───────────────────────────

    @Test
    void detectsJavaLanguageAndInfersTestPath() throws IOException {
        // Arrange
        write("src/main/java/plugin/Greeter.java",
                "package plugin;\npublic class Greeter { public String greet() { return \"hi\"; } }");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/Greeter.java"),
                projectDir.toString());

        // Assert
        assertFalse(result.isBlank());
        assertTrue(result.contains("JAVA"),                                  "should report detected language");
        assertTrue(result.contains("src/test/java/plugin/GreeterTest.java"), "should infer Maven test path");
    }

    // ── buildFileDropTestPrompt — source imports ──────────────────────────────

    @Test
    void listsSourceImports() throws IOException {
        // Arrange
        write("src/main/java/plugin/Svc.java", """
                package plugin;
                import java.util.List;
                import java.util.Map;
                public class Svc { public List<String> list() { return List.of(); } }
                """);

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/Svc.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("import java.util.List"), "should include List import");
        assertTrue(result.contains("import java.util.Map"),  "should include Map import");
    }

    // ── buildFileDropTestPrompt — constructor dependencies ────────────────────

    @Test
    void extractsConstructorParametersAsMockCandidates() throws IOException {
        // Arrange
        write("src/main/java/plugin/OrderService.java", """
                package plugin;
                public class OrderService {
                    private final OrderRepo repo;
                    private final PaymentGateway gateway;
                    public OrderService(OrderRepo repo, PaymentGateway gateway) {
                        this.repo = repo;
                        this.gateway = gateway;
                    }
                    public void placeOrder(String id) {}
                }
                """);

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/OrderService.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("OrderRepo"),      "should list first constructor param");
        assertTrue(result.contains("PaymentGateway"), "should list second constructor param");
    }

    // ── buildFileDropTestPrompt — build descriptor ────────────────────────────

    @Test
    void embedsPomContentWhenPresent() throws IOException {
        // Arrange
        write("src/main/java/plugin/A.java", "package plugin;\npublic class A {}");
        write("pom.xml", "<project><groupId>test</groupId></project>");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/A.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("<project>"), "should embed pom.xml content");
    }

    // ── buildFileDropTestPrompt — existing test convention ────────────────────

    @Test
    void includesExistingTestFileAsSampleConvention() throws IOException {
        // Arrange
        write("src/main/java/plugin/B.java", "package plugin;\npublic class B {}");
        write("src/test/java/plugin/SampleTest.java", """
                package plugin;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class SampleTest {
                    @Test void itWorks() { assertTrue(true); }
                }
                """);

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/B.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("itWorks") || result.contains("SampleTest"),
                "should embed an existing test file as style reference");
    }

    // ── buildFileDropTestPrompt — commands ────────────────────────────────────

    @Test
    void reportsMavenCommandsWhenPomPresent() throws IOException {
        // Arrange
        write("src/main/java/plugin/C.java", "package plugin;\npublic class C {}");
        write("pom.xml", "<project/>");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/C.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("mvn compile"), "should show compile command");
        assertTrue(result.contains("mvn test"),    "should show test command");
    }

    // ── buildFileDropTestPrompt — generation rules ────────────────────────────

    @Test
    void alwaysIncludesGenerationRules() throws IOException {
        // Arrange
        write("src/main/java/plugin/D.java", "package plugin;\npublic class D {}");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/D.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("AAA pattern"),    "should mandate AAA pattern");
        assertTrue(result.contains("public methods"), "should mandate public-only testing");
        assertTrue(result.contains("EVERY import"),   "should mandate complete imports");
    }

    // ── buildFileDropTestPrompt — Go language ─────────────────────────────────

    @Test
    void detectsGoLanguageAndInfersTestPath() throws IOException {
        // Arrange
        write("pkg/greeter/greeter.go", """
                package greeter
                func Greet(name string) string { return "Hello, " + name }
                """);
        write("go.mod", "module example.com/demo\ngo 1.21\n");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("pkg/greeter/greeter.go"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("GO"),            "should detect Go language");
        assertTrue(result.contains("go test ./..."), "should show Go test command");
        assertTrue(result.contains("greeter_test.go"), "should infer _test.go path");
    }

    // ── buildFileDropTestPrompt — Maven module detection ──────────────────────

    @Test
    void detectsMavenSubModuleWhenPomPresentBelowRoot() throws IOException {
        // Arrange — source file is inside module-core which has its own pom.xml
        write("pom.xml", "<project/>");
        write("module-core/pom.xml", "<project/>");
        write("module-core/src/main/java/com/example/OrderService.java",
                "package com.example;\npublic class OrderService { public void place() {} }");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("module-core/src/main/java/com/example/OrderService.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("module-core"), "should report the detected sub-module name");
        assertTrue(result.contains("module-core/src/test/java/com/example/OrderServiceTest.java"),
                "should compute test path relative to the sub-module");
    }

    @Test
    void prefersModulePomOverRootPomForBuildDescriptor() throws IOException {
        // Arrange
        write("pom.xml", "<project><groupId>root</groupId></project>");
        write("service/pom.xml", "<project><groupId>service-module</groupId></project>");
        write("service/src/main/java/svc/Svc.java",
                "package svc;\npublic class Svc {}");

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("service/src/main/java/svc/Svc.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("service-module"),
                "build descriptor should be from the sub-module pom.xml, not the root");
    }

    // ── buildFileDropTestPrompt — related project-local dependencies ──────────

    @Test
    void includesProjectLocalDependencySource() throws IOException {
        // Arrange — OrderService imports a project-local class (OrderRepo)
        write("src/main/java/plugin/OrderRepo.java", """
                package plugin;
                import java.util.List;
                public class OrderRepo {
                    public List<String> findAll() { return List.of(); }
                }
                """);
        write("src/main/java/plugin/OrderService.java", """
                package plugin;
                import plugin.OrderRepo;
                public class OrderService {
                    private final OrderRepo repo;
                    public OrderService(OrderRepo repo) { this.repo = repo; }
                    public List<String> listOrders() { return repo.findAll(); }
                }
                """);

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/OrderService.java"),
                projectDir.toString());

        // Assert
        assertTrue(result.contains("OrderRepo"),
                "should include the source of the project-local dependency");
        assertTrue(result.contains("findAll"),
                "should embed the public API of the dependency so the LLM can mock it");
    }

    @Test
    void skipsExternalLibraryImportsWhenReadingDependencies() throws IOException {
        // Arrange — only JDK and JUnit imports; no project-local classes
        write("src/main/java/plugin/Pure.java", """
                package plugin;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                public class Pure { public int add(int a, int b) { return a + b; } }
                """);

        // Act
        String result = generator.buildFileDropTestPrompt(
                projectDir.resolve("src/main/java/plugin/Pure.java"),
                projectDir.toString());

        // Assert — should not contain a "Project-local dependencies" section at all
        // because every import is either JDK or JUnit
        assertFalse(result.contains("Project-local dependencies"),
                "should skip external library imports when reading dependencies");
    }
}
