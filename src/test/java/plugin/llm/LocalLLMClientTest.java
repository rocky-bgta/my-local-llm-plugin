package plugin.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import plugin.llm.model.ChatMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalLLMClientTest {

    private static final long DEFAULT_FIRST = LocalLLMClient.firstDataTimeoutSeconds;
    private static final long DEFAULT_IDLE  = LocalLLMClient.idleTimeoutSeconds;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        LocalLLMClient.firstDataTimeoutSeconds = DEFAULT_FIRST;
        LocalLLMClient.idleTimeoutSeconds     = DEFAULT_IDLE;
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static List<ChatMessage> messages() {
        return List.of(new ChatMessage("user", "hi"));
    }

    @Test
    void checkStateReportsUnreachableServer() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        LocalLLMClient.ServerState state = new LocalLLMClient("http://127.0.0.1:" + freePort).checkState();
        assertFalse(state.reachable());
        assertTrue(state.models().isEmpty());
        assertFalse(state.error().isBlank());
    }

    @Test
    void checkStateReportsLoadedModels() throws Exception {
        String base = startServer();
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"qwen/qwen2.5-vl-7b\"},{\"id\":\"llama3\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        LocalLLMClient.ServerState state = new LocalLLMClient(base).checkState();
        assertTrue(state.reachable());
        assertEquals(List.of("qwen/qwen2.5-vl-7b", "llama3"), state.models());
        assertTrue(state.hasModel("QWEN/qwen2.5-VL-7B"));
        assertFalse(state.hasModel("mistral"));
        assertFalse(state.hasModel(""));
        assertFalse(state.hasModel(null));
    }

    @Test
    void streamChatDeliversTokensFromSse() throws Exception {
        String base = startServer();
        server.createContext("/v1/chat/completions", exchange -> {
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        List<String> tokens = new ArrayList<>();
        new LocalLLMClient(base).streamChat("m", messages(), tokens::add);
        assertEquals(List.of("Hel", "lo"), tokens);
    }

    @Test
    void streamChatTimesOutWhenServerIsSilent() throws Exception {
        LocalLLMClient.firstDataTimeoutSeconds = 1;
        String base = startServer();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
            } finally {
                exchange.close();
            }
        });

        IOException ex = assertThrows(IOException.class,
                () -> new LocalLLMClient(base).streamChat("m", messages(), t -> {}));
        assertTrue(ex.getMessage().contains("did not start responding"), ex.getMessage());
    }

    @Test
    void streamChatTimesOutWhenStreamStallsMidResponse() throws Exception {
        LocalLLMClient.idleTimeoutSeconds = 1;
        String base = startServer();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
            } finally {
                exchange.close();
            }
        });

        List<String> tokens = new ArrayList<>();
        IOException ex = assertThrows(IOException.class,
                () -> new LocalLLMClient(base).streamChat("m", messages(), tokens::add));
        assertTrue(ex.getMessage().contains("stalled"), ex.getMessage());
        assertEquals(List.of("Hel"), tokens);
    }

    @Test
    void streamChatSurfacesHttpErrorBody() throws Exception {
        String base = startServer();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"model not loaded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        IOException ex = assertThrows(IOException.class,
                () -> new LocalLLMClient(base).streamChat("m", messages(), t -> {}));
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        assertTrue(ex.getMessage().contains("model not loaded"), ex.getMessage());
    }
}
