package plugin.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ContainerProjectUtil {

    private ContainerProjectUtil() {}

    public static boolean hasDockerArtifacts(String basePath) {
        return !findDockerArtifactPaths(basePath).isEmpty();
    }

    public static boolean hasHelmArtifacts(String basePath) {
        return !findHelmArtifactPaths(basePath).isEmpty();
    }

    public static List<String> findDockerArtifactPaths(String basePath) {
        List<String> paths = new ArrayList<>();
        if (basePath == null || basePath.isBlank()) return paths;
        scan(basePath, 4, path -> {
            String name = path.getFileName().toString();
            if (name.equalsIgnoreCase("Dockerfile")
                    || name.endsWith(".Dockerfile")
                    || name.equalsIgnoreCase("docker-compose.yml")
                    || name.equalsIgnoreCase("docker-compose.yaml")
                    || name.equalsIgnoreCase("compose.yml")
                    || name.equalsIgnoreCase("compose.yaml")
                    || name.equalsIgnoreCase("docker-bake.hcl")) {
                paths.add(normalize(basePath, path));
            }
        });
        return paths;
    }

    public static List<String> findHelmArtifactPaths(String basePath) {
        List<String> paths = new ArrayList<>();
        if (basePath == null || basePath.isBlank()) return paths;
        scan(basePath, 5, path -> {
            String name = path.getFileName().toString();
            if (name.equalsIgnoreCase("Chart.yaml")
                    || name.equalsIgnoreCase("Chart.yml")
                    || name.equalsIgnoreCase("values.yaml")
                    || name.equalsIgnoreCase("values.yml")
                    || path.toString().contains("templates")) {
                paths.add(normalize(basePath, path));
            }
        });
        return paths;
    }

    public static String buildPromptSummary(String basePath) {
        if (basePath == null || basePath.isBlank()) return "";
        List<String> dockerFiles = findDockerArtifactPaths(basePath);
        List<String> helmFiles = findHelmArtifactPaths(basePath);
        if (dockerFiles.isEmpty() && helmFiles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        if (!dockerFiles.isEmpty()) {
            sb.append("- Docker files:\n");
            dockerFiles.stream().limit(8).forEach(path -> sb.append("  - ").append(path).append("\n"));
        }
        if (!helmFiles.isEmpty()) {
            sb.append("- Helm files:\n");
            helmFiles.stream().limit(8).forEach(path -> sb.append("  - ").append(path).append("\n"));
        }
        return sb.toString().trim();
    }

    private static void scan(String basePath, int maxDepth, PathConsumer consumer) {
        try {
            Path root = Path.of(basePath);
            if (!Files.exists(root)) return;
            try (var stream = Files.walk(root, maxDepth)) {
                stream.filter(Files::isRegularFile).forEach(consumer::accept);
            }
        } catch (IOException ignored) {
        }
    }

    private static String normalize(String basePath, Path path) {
        return Path.of(basePath).relativize(path).toString().replace("\\", "/");
    }

    @FunctionalInterface
    private interface PathConsumer {
        void accept(Path path);
    }
}
