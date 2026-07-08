package plugin.dependency;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects missing libraries from build/test output and adds them to the
 * project's build configuration automatically, so the user never has to
 * install dependencies by hand.
 *
 * Supported: Maven (pom.xml), Gradle (build.gradle[.kts]), npm (npm install),
 * Python (requirements.txt), Go (go get), Rust (cargo add).
 */
public final class DependencyManager {

    public record MavenCoordinate(String groupId, String artifactId, String version, boolean testScope) {}

    // Java compile errors
    private static final Pattern JAVA_MISSING_PACKAGE =
            Pattern.compile("package ([\\w.]+) does not exist");
    private static final Pattern JAVA_MISSING_CLASS_RUNTIME =
            Pattern.compile("NoClassDefFoundError: ([\\w/.]+)");
    // Node
    private static final Pattern NODE_MISSING_MODULE =
            Pattern.compile("Cannot find (?:module|package) '([^']+)'");
    // Python
    private static final Pattern PYTHON_MISSING_MODULE =
            Pattern.compile("ModuleNotFoundError: No module named '([^']+)'");
    // Go
    private static final Pattern GO_MISSING_PACKAGE =
            Pattern.compile("no required module provides package ([^\\s;:]+)");
    // Rust
    private static final Pattern RUST_MISSING_CRATE =
            Pattern.compile("use of undeclared crate or module `(\\w+)`");

    /**
     * Well-known Java package prefixes mapped to Maven coordinates with pinned
     * stable versions. Longest-prefix match wins.
     */
    private static final Map<String, MavenCoordinate> KNOWN_JAVA_LIBS = buildKnownJavaLibs();

    private static Map<String, MavenCoordinate> buildKnownJavaLibs() {
        Map<String, MavenCoordinate> m = new LinkedHashMap<>();
        m.put("org.junit.jupiter", new MavenCoordinate("org.junit.jupiter", "junit-jupiter", "5.10.2", true));
        m.put("org.mockito.junit.jupiter", new MavenCoordinate("org.mockito", "mockito-junit-jupiter", "5.11.0", true));
        m.put("org.mockito", new MavenCoordinate("org.mockito", "mockito-core", "5.11.0", true));
        m.put("org.assertj", new MavenCoordinate("org.assertj", "assertj-core", "3.25.3", true));
        m.put("org.hamcrest", new MavenCoordinate("org.hamcrest", "hamcrest", "2.2", true));
        m.put("org.testng", new MavenCoordinate("org.testng", "testng", "7.9.0", true));
        m.put("org.junit", new MavenCoordinate("junit", "junit", "4.13.2", true));
        m.put("com.google.gson", new MavenCoordinate("com.google.code.gson", "gson", "2.10.1", false));
        m.put("com.fasterxml.jackson", new MavenCoordinate("com.fasterxml.jackson.core", "jackson-databind", "2.17.0", false));
        m.put("org.apache.commons.lang3", new MavenCoordinate("org.apache.commons", "commons-lang3", "3.14.0", false));
        m.put("org.apache.commons.io", new MavenCoordinate("commons-io", "commons-io", "2.15.1", false));
        m.put("org.apache.commons.collections4", new MavenCoordinate("org.apache.commons", "commons-collections4", "4.4", false));
        m.put("okhttp3", new MavenCoordinate("com.squareup.okhttp3", "okhttp", "4.12.0", false));
        m.put("org.slf4j", new MavenCoordinate("org.slf4j", "slf4j-api", "2.0.12", false));
        m.put("ch.qos.logback", new MavenCoordinate("ch.qos.logback", "logback-classic", "1.5.3", false));
        m.put("com.google.common", new MavenCoordinate("com.google.guava", "guava", "33.1.0-jre", false));
        m.put("org.yaml.snakeyaml", new MavenCoordinate("org.yaml", "snakeyaml", "2.2", false));
        m.put("org.apache.pdfbox", new MavenCoordinate("org.apache.pdfbox", "pdfbox", "3.0.2", false));
        return m;
    }

    private DependencyManager() {}

    /**
     * Detects missing dependencies in the build output and adds them to the
     * project's build configuration.
     *
     * @return a human-readable summary of what was changed, or "" when nothing
     *         could be resolved automatically.
     */
    public static String attemptAutoResolve(String basePath, String buildOutput) {
        if (basePath == null || buildOutput == null || buildOutput.isBlank()) return "";

        List<String> changes = new ArrayList<>();

        Set<String> javaPackages = matchAll(JAVA_MISSING_PACKAGE, buildOutput);
        matchAll(JAVA_MISSING_CLASS_RUNTIME, buildOutput).stream()
                .map(cls -> cls.replace('/', '.'))
                .map(DependencyManager::packageOf)
                .filter(p -> !p.isBlank())
                .forEach(javaPackages::add);
        if (!javaPackages.isEmpty()) {
            changes.addAll(resolveJavaPackages(basePath, javaPackages));
        }

        for (String module : matchAll(NODE_MISSING_MODULE, buildOutput)) {
            if (Files.exists(Paths.get(basePath, "package.json")) && !module.startsWith(".")) {
                if (runInstall(basePath, List.of(npmCmd(), "install", "--save-dev", module))) {
                    changes.add("npm: installed " + module);
                }
            }
        }

        for (String module : matchAll(PYTHON_MISSING_MODULE, buildOutput)) {
            String change = appendPythonRequirement(basePath, module);
            if (!change.isBlank()) changes.add(change);
        }

        for (String pkg : matchAll(GO_MISSING_PACKAGE, buildOutput)) {
            if (Files.exists(Paths.get(basePath, "go.mod"))) {
                if (runInstall(basePath, List.of("go", "get", pkg))) {
                    changes.add("go: go get " + pkg);
                }
            }
        }

        for (String crate : matchAll(RUST_MISSING_CRATE, buildOutput)) {
            if (Files.exists(Paths.get(basePath, "Cargo.toml"))) {
                if (runInstall(basePath, List.of("cargo", "add", crate))) {
                    changes.add("cargo: added " + crate);
                }
            }
        }

        if (changes.isEmpty()) return "";
        return "Auto-resolved missing dependencies:\n- " + String.join("\n- ", changes);
    }

    // ── Java / Maven / Gradle ────────────────────────────────────────────────

    private static List<String> resolveJavaPackages(String basePath, Set<String> packages) {
        List<String> changes = new ArrayList<>();
        Set<String> handledArtifacts = new LinkedHashSet<>();
        for (String pkg : packages) {
            MavenCoordinate coordinate = lookupKnownJavaLib(pkg);
            if (coordinate == null || !handledArtifacts.add(coordinate.artifactId())) continue;
            String change = addJavaDependency(basePath, coordinate);
            if (!change.isBlank()) changes.add(change);
        }
        return changes;
    }

    static MavenCoordinate lookupKnownJavaLib(String packageName) {
        if (packageName == null || packageName.isBlank()) return null;
        MavenCoordinate best = null;
        int bestLength = -1;
        for (Map.Entry<String, MavenCoordinate> entry : KNOWN_JAVA_LIBS.entrySet()) {
            String prefix = entry.getKey();
            if ((packageName.equals(prefix) || packageName.startsWith(prefix + "."))
                    && prefix.length() > bestLength) {
                best = entry.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }

    static String addJavaDependency(String basePath, MavenCoordinate coordinate) {
        Path pom = Paths.get(basePath, "pom.xml");
        if (Files.exists(pom)) {
            return addMavenDependency(pom, coordinate);
        }
        for (String gradleName : List.of("build.gradle", "build.gradle.kts")) {
            Path gradle = Paths.get(basePath, gradleName);
            if (Files.exists(gradle)) {
                return addGradleDependency(gradle, coordinate);
            }
        }
        return "";
    }

    static String addMavenDependency(Path pom, MavenCoordinate coordinate) {
        try {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            if (content.contains("<artifactId>" + coordinate.artifactId() + "</artifactId>")) {
                return "";
            }
            String block = "        <dependency>\n" +
                    "            <groupId>" + coordinate.groupId() + "</groupId>\n" +
                    "            <artifactId>" + coordinate.artifactId() + "</artifactId>\n" +
                    "            <version>" + coordinate.version() + "</version>\n" +
                    (coordinate.testScope() ? "            <scope>test</scope>\n" : "") +
                    "        </dependency>\n";
            String updated;
            int closeIdx = content.indexOf("</dependencies>");
            if (closeIdx >= 0) {
                updated = content.substring(0, closeIdx) + block + "    " + content.substring(closeIdx).stripLeading();
            } else {
                int projectClose = content.lastIndexOf("</project>");
                if (projectClose < 0) return "";
                updated = content.substring(0, projectClose)
                        + "    <dependencies>\n" + block + "    </dependencies>\n"
                        + content.substring(projectClose);
            }
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
            return "pom.xml: added " + coordinate.groupId() + ":" + coordinate.artifactId() + ":" + coordinate.version()
                    + (coordinate.testScope() ? " (test)" : "");
        } catch (Exception e) {
            return "";
        }
    }

    static String addGradleDependency(Path gradle, MavenCoordinate coordinate) {
        try {
            String content = Files.readString(gradle, StandardCharsets.UTF_8);
            String notation = coordinate.groupId() + ":" + coordinate.artifactId() + ":" + coordinate.version();
            if (content.contains(coordinate.groupId() + ":" + coordinate.artifactId())) {
                return "";
            }
            String configuration = coordinate.testScope() ? "testImplementation" : "implementation";
            boolean kts = gradle.getFileName().toString().endsWith(".kts");
            String line = kts
                    ? "    " + configuration + "(\"" + notation + "\")\n"
                    : "    " + configuration + " '" + notation + "'\n";
            Matcher m = Pattern.compile("(?m)^dependencies\\s*\\{").matcher(content);
            String updated;
            if (m.find()) {
                int insertAt = m.end();
                updated = content.substring(0, insertAt) + "\n" + line + content.substring(insertAt).stripLeading();
            } else {
                updated = content + "\ndependencies {\n" + line + "}\n";
            }
            Files.writeString(gradle, updated, StandardCharsets.UTF_8);
            return gradle.getFileName() + ": added " + configuration + " " + notation;
        } catch (Exception e) {
            return "";
        }
    }

    // ── Python ───────────────────────────────────────────────────────────────

    static String appendPythonRequirement(String basePath, String module) {
        Path requirements = Paths.get(basePath, "requirements.txt");
        if (!Files.exists(requirements)) return "";
        try {
            String content = Files.readString(requirements, StandardCharsets.UTF_8);
            String normalized = module.split("\\.")[0];
            boolean present = content.lines()
                    .map(String::trim)
                    .anyMatch(line -> line.equalsIgnoreCase(normalized)
                            || line.toLowerCase().startsWith(normalized.toLowerCase() + "=")
                            || line.toLowerCase().startsWith(normalized.toLowerCase() + ">"));
            if (present) return "";
            String updated = content.endsWith("\n") || content.isEmpty()
                    ? content + normalized + "\n"
                    : content + "\n" + normalized + "\n";
            Files.writeString(requirements, updated, StandardCharsets.UTF_8);
            return "requirements.txt: added " + normalized;
        } catch (Exception e) {
            return "";
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static Set<String> matchAll(Pattern pattern, String text) {
        Set<String> hits = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) hits.add(m.group(1));
        return hits;
    }

    private static String packageOf(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx > 0 ? fqcn.substring(0, idx) : "";
    }

    private static String npmCmd() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm";
    }

    private static boolean runInstall(String basePath, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(Paths.get(basePath).toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
