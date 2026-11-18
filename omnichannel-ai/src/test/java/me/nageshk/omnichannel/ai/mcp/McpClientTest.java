package me.nageshk.omnichannel.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TOOLS = "{\"tools\":[{\"name\":\"lookup\",\"description\":\"Find a customer\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]}";

    private record Seen(String method, JsonNode body, Map<String, String> headers) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile String callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}";
    private volatile boolean sse;
    private volatile long callDelayMillis;
    private volatile String toolsResult = TOOLS;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            String method = body.path("method").asString("");
            seen.add(new Seen(method, body, h));
            if (!body.has("id")) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            String id = body.get("id").toString();
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"}}";
                case "tools/list" -> toolsResult;
                default -> callResult;
            };
            if ("tools/call".equals(method) && callDelayMillis > 0) {
                try {
                    Thread.sleep(callDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String msg = result.startsWith("ERR:") ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":" + result.substring(4) + "}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
            if ("initialize".equals(method)) ex.getResponseHeaders().set("Mcp-Session-Id", "sess-1");
            if (sse && !"initialize".equals(method)) {
                byte[] b = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: " + msg + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } else {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
        } finally {
            ex.close();
        }
    }

    private static McpClient client(Duration toolTimeout, int maxResult, boolean allowPrivate) {
        AiProperties props = new AiProperties(true, Duration.ofSeconds(2), 8, Duration.ofSeconds(5), Duration.ofSeconds(120), 2, 8,
                Duration.ofSeconds(10), new AiProperties.OpenAi("http://x"),
                new AiProperties.Mcp(toolTimeout, maxResult, Duration.ofMinutes(5)),
                AiProperties.Knowledge.defaults());
        return new McpClient(props, new HttpAddressGuard(allowPrivate));
    }

    private static McpClient client() {
        return client(Duration.ofSeconds(5), 65536, true);
    }

    private List<String> methods() {
        List<String> m = new ArrayList<>();
        seen.forEach(s -> m.add(s.method()));
        return m;
    }

    @Test
    void initializeThenListTools() throws IOException {
        String url = start();
        List<McpTool> tools = client().tools(new McpEndpoint(url, Map.of()));

        assertThat(methods()).containsExactly("initialize", "notifications/initialized", "tools/list");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("lookup");
        assertThat(tools.get(0).description()).isEqualTo("Find a customer");
        assertThat(tools.get(0).inputSchema()).containsEntry("type", "object");
        Seen init = seen.get(0);
        assertThat(init.body().path("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(init.body().path("params").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.body().path("params").path("clientInfo").path("name").asString()).isEqualTo("omnichannel");
        assertThat(init.headers().get("accept")).isEqualTo("application/json, text/event-stream");
        assertThat(init.headers().get("content-type")).startsWith("application/json");
        assertThat(seen.get(1).headers().get("mcp-session-id")).isEqualTo("sess-1");
        assertThat(seen.get(2).headers().get("mcp-session-id")).isEqualTo("sess-1");
        assertThat(seen.get(2).headers().get("mcp-protocol-version")).isEqualTo("2025-06-18");
    }

    @Test
    void toolListIsCached() throws IOException {
        String url = start();
        McpClient c = client();
        c.tools(new McpEndpoint(url, Map.of("Authorization", "Bearer a")));
        c.tools(new McpEndpoint(url, Map.of("Authorization", "Bearer a")));
        assertThat(methods().stream().filter("tools/list"::equals).count()).isEqualTo(1);
        c.tools(new McpEndpoint(url, Map.of("Authorization", "Bearer b")));
        assertThat(methods().stream().filter("tools/list"::equals).count()).isEqualTo(2);
    }

    @Test
    void sseResponseParsed() throws IOException {
        sse = true;
        String url = start();
        List<McpTool> tools = client().tools(new McpEndpoint(url, Map.of()));
        assertThat(tools).extracting(McpTool::name).containsExactly("lookup");
        assertThat(client().call(new McpEndpoint(url, Map.of()), "lookup", "{}")).isEqualTo("hello");
    }

    @Test
    void callReturnsTextContent() throws IOException {
        callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"image\",\"data\":\"x\"},{\"type\":\"text\",\"text\":\"two\"}]}";
        String url = start();
        String out = client().call(new McpEndpoint(url, Map.of()), "lookup", "{\"q\":\"anna\"}");
        assertThat(out).isEqualTo("one\ntwo");
        Seen call = seen.get(seen.size() - 1);
        assertThat(call.method()).isEqualTo("tools/call");
        assertThat(call.body().path("params").path("name").asString()).isEqualTo("lookup");
        assertThat(call.body().path("params").path("arguments").path("q").asString()).isEqualTo("anna");
    }

    @Test
    void toolErrorIsMcpException() throws IOException {
        callResult = "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"no such customer\"}]}";
        String url = start();
        assertThatThrownBy(() -> client().call(new McpEndpoint(url, Map.of()), "lookup", "{}"))
                .isInstanceOf(McpException.class).hasMessage("no such customer");
    }

    @Test
    void jsonRpcErrorIsMcpException() throws IOException {
        callResult = "ERR:{\"code\":-32602,\"message\":\"unknown tool\"}";
        String url = start();
        assertThatThrownBy(() -> client().call(new McpEndpoint(url, Map.of()), "nope", "{}"))
                .isInstanceOf(McpException.class).hasMessage("unknown tool");
    }

    @Test
    void resultCapped() throws IOException {
        callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(100) + "\"}]}";
        String url = start();
        String out = client(Duration.ofSeconds(5), 10, true).call(new McpEndpoint(url, Map.of()), "lookup", "{}");
        assertThat(out).isEqualTo("xxxxxxxxxx…[cut]");
    }

    @Test
    void slowToolTimesOut() throws IOException {
        callDelayMillis = 1500;
        String url = start();
        assertThatThrownBy(() -> client(Duration.ofMillis(200), 65536, true).call(new McpEndpoint(url, Map.of()), "lookup", "{}"))
                .isInstanceOf(McpException.class).hasMessageContaining("in time");
    }

    @Test
    void privateAddressRefused() throws IOException {
        String url = start();
        assertThatThrownBy(() -> client(Duration.ofSeconds(5), 65536, false).tools(new McpEndpoint(url, Map.of())))
                .isInstanceOf(McpException.class).hasMessage("address not allowed");
        assertThat(seen).isEmpty();
    }

    @Test
    void headersSent() throws IOException {
        String url = start();
        client().tools(new McpEndpoint(url, Map.of("Authorization", "Bearer tok-1")));
        client().call(new McpEndpoint(url, Map.of("X-Api-Key", "key-9")), "lookup", "{}");
        for (Seen s : seen.subList(0, 3)) assertThat(s.headers().get("authorization")).isEqualTo("Bearer tok-1");
        for (Seen s : seen.subList(3, seen.size())) assertThat(s.headers().get("x-api-key")).isEqualTo("key-9");
    }

    @Test
    void secretEchoedByServerIsRedacted() throws IOException {
        callResult = "ERR:{\"code\":-1,\"message\":\"bad token tok-secret-77\"}";
        String url = start();
        assertThatThrownBy(() -> client().call(new McpEndpoint(url, Map.of("Authorization", "Bearer tok-secret-77")), "x", "{}"))
                .isInstanceOf(McpException.class).hasMessage("bad token «secret»");
    }

    @Test
    void httpErrorIsMcpException() throws IOException {
        String url = start();
        server.removeContext("/mcp");
        server.createContext("/mcp", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        assertThatThrownBy(() -> client().tools(new McpEndpoint(url, Map.of())))
                .isInstanceOf(McpException.class).hasMessageContaining("401");
    }

    @Test
    void stalledBodyTimesOutWithinTheDeadline() throws IOException {
        String url = start();
        server.removeContext("/mcp");
        server.createContext("/mcp", ex -> {
            try {
                ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(200, 1000);
                ex.getResponseBody().write("{\"jsonrpc\"".getBytes(StandardCharsets.UTF_8));
                ex.getResponseBody().flush();
                Thread.sleep(4000);
            } catch (Exception ignored) {
                // client went away
            } finally {
                ex.close();
            }
        });
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client(Duration.ofMillis(400), 65536, true).tools(new McpEndpoint(url, Map.of())))
                .isInstanceOf(McpException.class).hasMessageContaining("in time");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void oversizedBodyIsRefused() throws IOException {
        String url = start();
        server.removeContext("/mcp");
        server.createContext("/mcp", ex -> {
            try {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, 0);
                byte[] chunk = "x".repeat(65536).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 200; i++) ex.getResponseBody().write(chunk);
            } catch (Exception ignored) {
                // client closed the stream
            } finally {
                ex.close();
            }
        });
        assertThatThrownBy(() -> client().tools(new McpEndpoint(url, Map.of())))
                .isInstanceOf(McpException.class).hasMessageContaining("too large");
    }
}
