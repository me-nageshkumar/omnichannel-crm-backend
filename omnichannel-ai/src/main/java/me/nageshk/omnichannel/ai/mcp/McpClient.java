package me.nageshk.omnichannel.ai.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Our own MCP client: JSON-RPC 2.0 over Streamable HTTP, for servers a profile reaches "through our app". Every
 * call opens its own session (initialize, then the request); nothing is kept between calls except the tool list cache.
 */
@Component
public class McpClient {

    static final String PROTOCOL = "2025-06-18";
    private static final int MAX_PAGES = 5;

    private record Raw(int status, String body, HttpHeaders headers) {}

    private record Cached(Instant at, List<McpTool> tools) {}

    private final RestClient client;
    private final HttpAddressGuard guard;
    private final int maxResult;
    private final Duration cacheFor;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public McpClient(AiProperties props, HttpAddressGuard guard) {
        this.guard = guard;
        this.maxResult = props.mcp().maxResult();
        this.cacheFor = props.mcp().cacheFor();
        Duration timeout = props.mcp().toolTimeout();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public List<McpTool> tools(McpEndpoint e) {
        String key = e.url() + "|" + fingerprint(e.headers());
        Cached hit = cache.get(key);
        Instant now = Instant.now();
        if (hit != null && hit.at().plus(cacheFor).isAfter(now)) return hit.tools();
        String session = open(e);
        List<McpTool> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpc(e, session, "tools/list", params);
            for (JsonNode t : result.path("tools")) {
                String name = t.path("name").asString(null);
                if (name == null) continue;
                Map<String, Object> schema = t.path("inputSchema").isObject()
                        ? mapper.convertValue(t.path("inputSchema"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {})
                        : Map.of("type", "object");
                out.add(new McpTool(name, t.path("description").asString(""), schema));
            }
            cursor = result.path("nextCursor").asString(null);
            if (cursor == null || cursor.isEmpty()) break;
        }
        List<McpTool> tools = List.copyOf(out);
        cache.put(key, new Cached(now, tools));
        return tools;
    }

    public String call(McpEndpoint e, String tool, String argumentsJson) {
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank() ? mapper.createObjectNode() : mapper.readTree(argumentsJson);
        } catch (RuntimeException ex) {
            throw new McpException("The tool arguments are not valid JSON");
        }
        String session = open(e);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        JsonNode result = rpc(e, session, "tools/call", params);
        StringBuilder text = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (!"text".equals(c.path("type").asString())) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(c.path("text").asString(""));
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpException(redact(text.isEmpty() ? "The tool failed" : text.toString(), e));
        }
        String out = text.toString();
        return out.length() > maxResult ? out.substring(0, maxResult) + "…[cut]" : out;
    }

    /** initialize, then the initialized notification; returns the session id, or null when the server gave none. */
    private String open(McpEndpoint e) {
        try {
            guard.check(URI.create(e.url()));
        } catch (IllegalArgumentException ex) {
            throw new McpException("address not allowed");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL);
        params.putObject("capabilities");
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "omnichannel");
        info.put("version", "1");
        long id = ids.incrementAndGet();
        Raw raw = post(e, null, request("initialize", id, params));
        parse(raw, e, id);
        String session = raw.headers().getFirst("Mcp-Session-Id");
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        Raw ack = post(e, session, note);
        if (ack.status() >= 400) throw new McpException("The MCP server answered HTTP " + ack.status());
        return session;
    }

    private JsonNode rpc(McpEndpoint e, String session, String method, ObjectNode params) {
        long id = ids.incrementAndGet();
        return parse(post(e, session, request(method, id, params)), e, id);
    }

    private ObjectNode request(String method, long id, ObjectNode params) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", id);
        n.put("method", method);
        n.set("params", params);
        return n;
    }

    private Raw post(McpEndpoint e, String session, ObjectNode body) {
        try {
            return client.post().uri(URI.create(e.url()))
                    .headers(h -> {
                        e.headers().forEach(h::set);
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
                        if (!"initialize".equals(body.path("method").asString())) h.set("MCP-Protocol-Version", PROTOCOL);
                        if (session != null) h.set("Mcp-Session-Id", session);
                    })
                    .body(mapper.writeValueAsString(body))
                    .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8), res.getHeaders()), false);
        } catch (ResourceAccessException ex) {
            throw transport(ex, e);
        } catch (RuntimeException ex) {
            throw new McpException(redact("The MCP call failed: " + ex.getMessage(), e));
        }
    }

    private McpException transport(ResourceAccessException ex, McpEndpoint e) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return new McpException("The MCP server did not answer in time");
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        if (msg.toLowerCase(Locale.ROOT).contains("timed out")) return new McpException("The MCP server did not answer in time");
        return new McpException(redact("The MCP server could not be reached: " + msg, e));
    }

    /** The {@code result} of the JSON-RPC reply to request {@code id}; a JSON-RPC error becomes an {@link McpException}. */
    private JsonNode parse(Raw raw, McpEndpoint e, long id) {
        if (raw.status() >= 400) throw new McpException("The MCP server answered HTTP " + raw.status());
        MediaType type = raw.headers().getContentType();
        JsonNode message = null;
        try {
            if (type != null && type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
                for (String data : events(raw.body())) {
                    JsonNode m = mapper.readTree(data);
                    if (m.has("id") && m.get("id").asLong(-1) == id) {
                        message = m;
                        break;
                    }
                }
            } else {
                message = mapper.readTree(raw.body());
            }
        } catch (RuntimeException ex) {
            throw new McpException("The MCP server replied with something that is not JSON");
        }
        if (message == null || !message.isObject()) throw new McpException("The MCP server sent no reply to the request");
        if (message.has("error")) {
            throw new McpException(redact(message.path("error").path("message").asString("The MCP server reported an error"), e));
        }
        return message.path("result");
    }

    private static List<String> events(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) out.add(data.toString());
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!data.isEmpty()) out.add(data.toString());
        return out;
    }

    private static String redact(String s, McpEndpoint e) {
        String out = s;
        for (String v : e.headers().values()) {
            if (v == null || v.isEmpty()) continue;
            out = out.replace(v, "«secret»");
            if (v.regionMatches(true, 0, "Bearer ", 0, 7) && v.length() > 7) out = out.replace(v.substring(7), "«secret»");
        }
        return out;
    }

    private static String fingerprint(Map<String, String> headers) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(headers).forEach((k, v) -> {
                d.update(k.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
                d.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            });
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
