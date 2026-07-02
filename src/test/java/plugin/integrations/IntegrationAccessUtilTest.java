package plugin.integrations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import plugin.settings.PluginSettings;
import plugin.util.CommandIntentUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationAccessUtilTest {

    @Test
    void buildPromptSummaryIncludesConfiguredIntegrations() {
        PluginSettings settings = new PluginSettings();
        settings.setGitlabCliPath("glab");
        settings.setGitlabProject("group/project");
        settings.setGitlabApiUrl("https://gitlab.example.com/");
        settings.setJiraBaseUrl("https://jira.example.com/");
        settings.setMcpServerUrl("https://mcp.example.com/");

        String summary = IntegrationAccessUtil.buildPromptSummary(settings);

        assertTrue(summary.contains("GitLab CLI: enabled"));
        assertTrue(summary.contains("project=group/project"));
        assertTrue(summary.contains("api=https://gitlab.example.com"));
        assertTrue(summary.contains("Jira: enabled (https://jira.example.com)"));
        assertTrue(summary.contains("MCP: enabled (https://mcp.example.com)"));
    }

    @Test
    void testGitLabRunsConfiguredExecutable() {
        PluginSettings settings = new PluginSettings();
        settings.setGitlabCliPath(javaExecutable());

        IntegrationAccessUtil.IntegrationTestResult result = IntegrationAccessUtil.testGitLab(settings);

        assertTrue(result.success(), result.message());
    }

    @Test
    void testJiraSucceedsAgainstLocalServer() throws Exception {
        HttpServer server = startServer(exchange -> respond(exchange, 200, "{\"self\":\"ok\"}"));
        try {
            PluginSettings settings = new PluginSettings();
            settings.setJiraBaseUrl(baseUrl(server));
            settings.setJiraEmail("dev@example.com");
            settings.setJiraToken("secret");

            IntegrationAccessUtil.IntegrationTestResult result = IntegrationAccessUtil.testJira(settings);

            assertTrue(result.success(), result.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testMcpSucceedsAgainstLocalServer() throws Exception {
        HttpServer server = startServer(exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method\"}");
                return;
            }
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        });
        try {
            PluginSettings settings = new PluginSettings();
            settings.setMcpServerUrl(baseUrl(server));
            settings.setMcpProtocolVersion("2024-11-05");

            IntegrationAccessUtil.IntegrationTestResult result = IntegrationAccessUtil.testMcp(settings);

            assertTrue(result.success(), result.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postsGitLabCommitCommentToLocalServer() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"id\":1}");
        });
        try {
            PluginSettings settings = new PluginSettings();
            settings.setGitlabProject("group/project");
            settings.setGitlabApiUrl(baseUrl(server));
            settings.setGitlabToken("token");

            IntegrationAccessUtil.IntegrationTestResult result = IntegrationAccessUtil.postGitLabCommitComment(
                    settings,
                    "abc123",
                    "Missing tests for export flow");

            assertTrue(result.success(), result.message());
            assertTrue(requestBody.get().contains("Missing tests for export flow"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summarizeResultMarksFailures() {
        String summary = IntegrationAccessUtil.summarizeResult(
                "GitLab",
                new IntegrationAccessUtil.IntegrationTestResult(false, "not found"));

        assertFalse(summary.contains("OK"));
        assertTrue(summary.contains("FAILED"));
    }

    private static String javaExecutable() {
        String extension = CommandIntentUtil.isWindowsPlatform() ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + extension).toString();
    }

    private static HttpServer startServer(ServerHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler::handle);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ServerHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
