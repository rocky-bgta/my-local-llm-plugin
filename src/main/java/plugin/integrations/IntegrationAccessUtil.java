package plugin.integrations;

import plugin.settings.PluginSettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class IntegrationAccessUtil {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private IntegrationAccessUtil() {}

    public record IntegrationTestResult(boolean success, String message) {}

    public static IntegrationTestResult testGitLab(PluginSettings settings) {
        String cli = settings.getGitlabCliPath() == null || settings.getGitlabCliPath().isBlank()
                ? "glab"
                : settings.getGitlabCliPath().trim();

        List<String> command = new ArrayList<>();
        command.add(cli);
        command.add("--version");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n").trim();
            }
            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new IntegrationTestResult(false, "GitLab CLI timed out.");
            }
            if (process.exitValue() != 0) {
                return new IntegrationTestResult(false, output.isBlank() ? "GitLab CLI returned an error." : output);
            }
            return new IntegrationTestResult(true, output.isBlank() ? "GitLab CLI is available." : output);
        } catch (Exception e) {
            return new IntegrationTestResult(false, "GitLab CLI test failed: " + e.getMessage());
        }
    }

    public static IntegrationTestResult testDocker() {
        return runVersionCommand("docker", "version --format '{{.Server.Version}}'", "Docker");
    }

    public static IntegrationTestResult testHelm() {
        return runVersionCommand("helm", "version --short", "Helm");
    }

    public static IntegrationTestResult postGitLabCommitComment(PluginSettings settings, String commitSha, String note) {
        String project = trim(settings.getGitlabProject());
        String apiUrl = normalizeBaseUrl(settings.getGitlabApiUrl());
        String token = trim(settings.getGitlabToken());
        if (project.isBlank()) {
            return new IntegrationTestResult(false, "GitLab project is empty.");
        }
        if (apiUrl.isBlank()) {
            return new IntegrationTestResult(false, "GitLab API URL is empty.");
        }
        if (commitSha == null || commitSha.isBlank()) {
            return new IntegrationTestResult(false, "GitLab commit SHA is empty.");
        }
        if (note == null || note.isBlank()) {
            return new IntegrationTestResult(false, "GitLab review note is empty.");
        }

        try {
            String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8).replace("+", "%20");
            String endpoint = apiUrl + "/api/v4/projects/" + encodedProject + "/repository/commits/" + commitSha + "/comments";
            String body = "{\"note\":" + jsonString(note) + "}";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (!token.isBlank()) {
                builder.header("PRIVATE-TOKEN", token);
            }

            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new IntegrationTestResult(true, "GitLab review comment posted: HTTP " + response.statusCode());
            }
            return new IntegrationTestResult(false, "GitLab review comment failed: HTTP " + response.statusCode() + " - " + response.body());
        } catch (Exception e) {
            return new IntegrationTestResult(false, "GitLab review comment failed: " + e.getMessage());
        }
    }

    public static IntegrationTestResult testJira(PluginSettings settings) {
        String baseUrl = normalizeBaseUrl(settings.getJiraBaseUrl());
        if (baseUrl.isBlank()) {
            return new IntegrationTestResult(false, "Jira base URL is empty.");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/api/2/myself"))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .header("Accept", "application/json");

            String email = trim(settings.getJiraEmail());
            String token = settings.getJiraToken() == null ? "" : settings.getJiraToken().trim();
            if (!email.isBlank() && !token.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }

            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new IntegrationTestResult(true, "Jira access OK: " + response.statusCode());
            }
            return new IntegrationTestResult(false, "Jira access failed: HTTP " + response.statusCode());
        } catch (Exception e) {
            return new IntegrationTestResult(false, "Jira test failed: " + e.getMessage());
        }
    }

    public static IntegrationTestResult testMcp(PluginSettings settings) {
        String endpoint = normalizeBaseUrl(settings.getMcpServerUrl());
        if (endpoint.isBlank()) {
            return new IntegrationTestResult(false, "MCP server URL is empty.");
        }
        try {
            String protocolVersion = trim(settings.getMcpProtocolVersion());
            if (protocolVersion.isBlank()) protocolVersion = "2024-11-05";

            String body = """
                    {
                      "jsonrpc":"2.0",
                      "id":1,
                      "method":"initialize",
                      "params":{
                        "protocolVersion":"%s",
                        "clientInfo":{"name":"local-llm-plugin","version":"1.0.0"},
                        "capabilities":{}
                      }
                    }
                    """.formatted(protocolVersion);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new IntegrationTestResult(true, "MCP server responded with HTTP " + response.statusCode());
            }
            return new IntegrationTestResult(false, "MCP test failed: HTTP " + response.statusCode());
        } catch (Exception e) {
            return new IntegrationTestResult(false, "MCP test failed: " + e.getMessage());
        }
    }

    public static String buildPromptSummary(PluginSettings settings) {
        StringBuilder sb = new StringBuilder();
        if (settings == null) return "";
        String gitlabCli = trim(settings.getGitlabCliPath());
        String gitlabProject = trim(settings.getGitlabProject());
        String gitlabApiUrl = normalizeBaseUrl(settings.getGitlabApiUrl());
        if (!gitlabCli.isBlank() || !gitlabProject.isBlank() || !gitlabApiUrl.isBlank()) {
            sb.append("- GitLab CLI: enabled");
            if (!gitlabCli.isBlank()) sb.append(" [").append(gitlabCli).append("]");
            if (!gitlabProject.isBlank()) sb.append(" project=").append(gitlabProject);
            if (!gitlabApiUrl.isBlank()) sb.append(" api=").append(gitlabApiUrl);
            sb.append("\n");
        }
        String jiraBaseUrl = normalizeBaseUrl(settings.getJiraBaseUrl());
        if (!jiraBaseUrl.isBlank()) {
            sb.append("- Jira: enabled (").append(jiraBaseUrl).append(")\n");
        }
        String mcpServerUrl = normalizeBaseUrl(settings.getMcpServerUrl());
        if (!mcpServerUrl.isBlank()) {
            sb.append("- MCP: enabled (").append(mcpServerUrl).append(")\n");
        }
        return sb.toString().trim();
    }

    public static String summarizeResult(String name, IntegrationTestResult result) {
        if (result == null) return name + ": unknown";
        return name + ": " + (result.success() ? "OK" : "FAILED") + " - " + result.message();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = trim(value);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static IntegrationTestResult runVersionCommand(String executable, String arguments, String displayName) {
        try {
            List<String> command = new ArrayList<>();
            command.add(executable);
            for (String part : arguments.split(" ")) {
                if (!part.isBlank()) {
                    command.add(part);
                }
            }
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n").trim();
            }
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new IntegrationTestResult(false, displayName + " timed out.");
            }
            if (process.exitValue() != 0) {
                return new IntegrationTestResult(false, output.isBlank() ? displayName + " returned an error." : output);
            }
            return new IntegrationTestResult(true, output.isBlank() ? displayName + " is available." : output);
        } catch (Exception e) {
            return new IntegrationTestResult(false, displayName + " test failed: " + e.getMessage());
        }
    }
}
