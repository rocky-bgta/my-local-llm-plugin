package plugin.dependency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DependencyManagerTest {

    @TempDir
    Path projectDir;

    private static final String MINIMAL_POM = """
            <project>
                <dependencies>
                </dependencies>
            </project>
            """;

    @Test
    void returnsEmptyForNullOrBlankInput() {
        assertEquals("", DependencyManager.attemptAutoResolve(null, "package x does not exist"));
        assertEquals("", DependencyManager.attemptAutoResolve("C:/tmp", ""));
        assertEquals("", DependencyManager.attemptAutoResolve("C:/tmp", null));
    }

    @Test
    void lookupPrefersLongestPackagePrefix() {
        assertEquals("mockito-junit-jupiter",
                DependencyManager.lookupKnownJavaLib("org.mockito.junit.jupiter").artifactId());
        assertEquals("mockito-core",
                DependencyManager.lookupKnownJavaLib("org.mockito.stubbing").artifactId());
        assertEquals("junit-jupiter",
                DependencyManager.lookupKnownJavaLib("org.junit.jupiter.api").artifactId());
        assertEquals("junit",
                DependencyManager.lookupKnownJavaLib("org.junit.runner").artifactId());
        assertNull(DependencyManager.lookupKnownJavaLib("com.unknown.library"));
        assertNull(DependencyManager.lookupKnownJavaLib(null));
    }

    @Test
    void resolvesMissingJavaPackageIntoPom() throws IOException {
        Files.writeString(projectDir.resolve("pom.xml"), MINIMAL_POM);
        String buildOutput = "[ERROR] Foo.java:[3,1] package org.mockito does not exist";

        String summary = DependencyManager.attemptAutoResolve(projectDir.toString(), buildOutput);

        assertTrue(summary.contains("Auto-resolved missing dependencies"));
        assertTrue(summary.contains("mockito-core"));
        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>mockito-core</artifactId>"));
        assertTrue(pom.contains("<scope>test</scope>"));
    }

    @Test
    void resolvesRuntimeNoClassDefFoundError() throws IOException {
        Files.writeString(projectDir.resolve("pom.xml"), MINIMAL_POM);
        String buildOutput = "java.lang.NoClassDefFoundError: com/google/gson/Gson";

        String summary = DependencyManager.attemptAutoResolve(projectDir.toString(), buildOutput);

        assertTrue(summary.contains("gson"));
        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>gson</artifactId>"));
        assertFalse(pom.contains("<scope>test</scope>"));
    }

    @Test
    void doesNotDuplicateExistingMavenDependency() throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        Files.writeString(pom, MINIMAL_POM);
        DependencyManager.MavenCoordinate gson =
                new DependencyManager.MavenCoordinate("com.google.code.gson", "gson", "2.10.1", false);

        assertFalse(DependencyManager.addMavenDependency(pom, gson).isBlank());
        assertEquals("", DependencyManager.addMavenDependency(pom, gson));

        String content = Files.readString(pom);
        int first = content.indexOf("<artifactId>gson</artifactId>");
        assertEquals(first, content.lastIndexOf("<artifactId>gson</artifactId>"));
    }

    @Test
    void createsDependenciesBlockWhenMissing() throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        Files.writeString(pom, "<project>\n</project>\n");
        DependencyManager.MavenCoordinate junit =
                new DependencyManager.MavenCoordinate("org.junit.jupiter", "junit-jupiter", "5.10.2", true);

        String change = DependencyManager.addMavenDependency(pom, junit);

        assertTrue(change.contains("junit-jupiter"));
        String content = Files.readString(pom);
        assertTrue(content.contains("<dependencies>"));
        assertTrue(content.contains("</dependencies>"));
        assertTrue(content.indexOf("</dependencies>") < content.indexOf("</project>"));
    }

    @Test
    void addsGradleDependencyInsideDependenciesBlock() throws IOException {
        Path gradle = projectDir.resolve("build.gradle");
        Files.writeString(gradle, "plugins { id 'java' }\n\ndependencies {\n    implementation 'x:y:1'\n}\n");
        DependencyManager.MavenCoordinate mockito =
                new DependencyManager.MavenCoordinate("org.mockito", "mockito-core", "5.11.0", true);

        String change = DependencyManager.addGradleDependency(gradle, mockito);

        assertTrue(change.contains("testImplementation"));
        String content = Files.readString(gradle);
        assertTrue(content.contains("testImplementation 'org.mockito:mockito-core:5.11.0'"));
        assertTrue(content.indexOf("dependencies {") < content.indexOf("testImplementation"));
    }

    @Test
    void usesKotlinDslQuotingForKtsBuildFiles() throws IOException {
        Path gradle = projectDir.resolve("build.gradle.kts");
        Files.writeString(gradle, "dependencies {\n}\n");
        DependencyManager.MavenCoordinate gson =
                new DependencyManager.MavenCoordinate("com.google.code.gson", "gson", "2.10.1", false);

        DependencyManager.addGradleDependency(gradle, gson);

        String content = Files.readString(gradle);
        assertTrue(content.contains("implementation(\"com.google.code.gson:gson:2.10.1\")"));
    }

    @Test
    void skipsGradleDependencyAlreadyPresent() throws IOException {
        Path gradle = projectDir.resolve("build.gradle");
        Files.writeString(gradle, "dependencies {\n    implementation 'com.google.code.gson:gson:2.10.1'\n}\n");
        DependencyManager.MavenCoordinate gson =
                new DependencyManager.MavenCoordinate("com.google.code.gson", "gson", "2.10.1", false);

        assertEquals("", DependencyManager.addGradleDependency(gradle, gson));
    }

    @Test
    void appendsPythonRequirementOnce() throws IOException {
        Path requirements = projectDir.resolve("requirements.txt");
        Files.writeString(requirements, "flask\n");

        assertEquals("requirements.txt: added requests",
                DependencyManager.appendPythonRequirement(projectDir.toString(), "requests"));
        assertEquals("", DependencyManager.appendPythonRequirement(projectDir.toString(), "requests"));
        assertEquals("", DependencyManager.appendPythonRequirement(projectDir.toString(), "flask"));

        assertEquals("flask\nrequests\n", Files.readString(requirements));
    }

    @Test
    void pythonModuleErrorUpdatesRequirements() throws IOException {
        Files.writeString(projectDir.resolve("requirements.txt"), "");
        String buildOutput = "ModuleNotFoundError: No module named 'yaml'";

        String summary = DependencyManager.attemptAutoResolve(projectDir.toString(), buildOutput);

        assertTrue(summary.contains("requirements.txt: added yaml"));
    }

    @Test
    void unknownPackageProducesNoChanges() throws IOException {
        Files.writeString(projectDir.resolve("pom.xml"), MINIMAL_POM);
        String buildOutput = "package com.internal.corp.secret does not exist";

        assertEquals("", DependencyManager.attemptAutoResolve(projectDir.toString(), buildOutput));
        assertEquals(MINIMAL_POM, Files.readString(projectDir.resolve("pom.xml")));
    }
}
