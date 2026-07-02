package plugin.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContainerProjectUtilTest {

    @Test
    void detectsDockerAndHelmArtifacts() throws Exception {
        Path projectDir = Files.createTempDirectory("container-project");
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM eclipse-temurin:21", StandardCharsets.UTF_8);
        Files.createDirectories(projectDir.resolve("charts/demo/templates"));
        Files.writeString(projectDir.resolve("charts/demo/Chart.yaml"), "name: demo", StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("charts/demo/values.yaml"), "replicaCount: 1", StandardCharsets.UTF_8);

        assertTrue(ContainerProjectUtil.hasDockerArtifacts(projectDir.toString()));
        assertTrue(ContainerProjectUtil.hasHelmArtifacts(projectDir.toString()));
        assertTrue(ContainerProjectUtil.buildPromptSummary(projectDir.toString()).contains("Docker"));
        assertTrue(ContainerProjectUtil.buildPromptSummary(projectDir.toString()).contains("Helm"));
    }

    @Test
    void returnsFalseWhenNoContainerFilesExist() throws Exception {
        Path projectDir = Files.createTempDirectory("no-container-project");

        assertFalse(ContainerProjectUtil.hasDockerArtifacts(projectDir.toString()));
        assertFalse(ContainerProjectUtil.hasHelmArtifacts(projectDir.toString()));
    }
}
