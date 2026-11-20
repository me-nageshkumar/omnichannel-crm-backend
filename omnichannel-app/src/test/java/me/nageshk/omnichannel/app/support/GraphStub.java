package me.nageshk.omnichannel.app.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for graph.facebook.com. Answers are matched by path prefix (method optional, {@code "POST /333/messages"})
 * first, in the order queued, then by the plain queue; with nothing queued it answers 200 {@code {"data":[]}}. Every
 * request is recorded. Paths are recorded without the version segment, so tests read {@code /333/messages}. Tests
 * point {@code omnichannel.meta.graph-base-url} at {@link #baseUrl()}.
 */
public final class GraphStub {

    public record Recorded(String method, String path, String query, String authorization, String contentType,
            byte[] bytes) {
        public String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private record Canned(String match, int status, byte[] body, String contentType) {}

    private final HttpServer server;
    private final List<Canned> matched = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Canned> queue = new ConcurrentLinkedQueue<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private volatile Runnable beforeNext;

    public GraphStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String raw = ex.getRequestURI().getRawPath();
        // "/v23.0/333/messages" -> "/333/messages"; a download URL ("/files/1") has no version
        String path = raw.matches("^/v\\d+\\.\\d+/.*") ? raw.substring(raw.indexOf('/', 1)) : raw;
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        recorded.add(new Recorded(method, path, query, ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("Content-Type"), body));
        Runnable hook = beforeNext;
        if (hook != null) {
            beforeNext = null;
            hook.run();
        }
        Canned c = take(method, path);
        if (c == null) c = queue.poll();
        if (c == null) c = new Canned(null, 200, "{\"data\":[]}".getBytes(StandardCharsets.UTF_8), "application/json");
        ex.getResponseHeaders().add("Content-Type", c.contentType());
        ex.sendResponseHeaders(c.status(), c.body().length == 0 ? -1 : c.body().length);
        if (c.body().length > 0) ex.getResponseBody().write(c.body());
        ex.close();
    }

    private synchronized Canned take(String method, String path) {
        for (Iterator<Canned> it = matched.iterator(); it.hasNext();) {
            Canned c = it.next();
            String m = c.match();
            boolean hit = m.contains(" ") ? (method + " " + path).startsWith(m) : path.startsWith(m);
            if (hit) {
                matched.remove(c);
                return c;
            }
        }
        return null;
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Answered to the next request that no path-matched answer takes. */
    public void enqueue(int status, String body) {
        queue.add(new Canned(null, status, body.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    /** Answered once to the first request whose path (or {@code "METHOD path"}) starts with {@code match}. */
    public void enqueue(String match, int status, String body) {
        matched.add(new Canned(match, status, body.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    /** Raw bytes, for media downloads. */
    public void enqueueBytes(String match, byte[] body, String contentType) {
        matched.add(new Canned(match, 200, body, contentType));
    }

    /** Runs once, inside the next request, before it is answered. */
    public void beforeNext(Runnable hook) {
        this.beforeNext = hook;
    }

    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    /** The recorded requests whose {@code "METHOD path"} starts with {@code prefix}. */
    public List<Recorded> recorded(String prefix) {
        List<Recorded> out = new ArrayList<>();
        for (Recorded r : recorded) if ((r.method() + " " + r.path()).startsWith(prefix)) out.add(r);
        return out;
    }

    public void reset() {
        matched.clear();
        queue.clear();
        recorded.clear();
        beforeNext = null;
    }

    /** {@code debug_token} for a valid token of {@code appId} with both WhatsApp scopes on the given WABAs. */
    public static String validToken(String appId, String... wabaIds) {
        String targets = wabaIds.length == 0 ? "" : "\"" + String.join("\",\"", wabaIds) + "\"";
        return "{\"data\":{\"app_id\":\"" + appId + "\",\"type\":\"SYSTEM_USER\",\"is_valid\":true,\"expires_at\":0,"
                + "\"scopes\":[\"whatsapp_business_management\",\"whatsapp_business_messaging\"],"
                + "\"granular_scopes\":[{\"scope\":\"whatsapp_business_management\",\"target_ids\":[" + targets + "]},"
                + "{\"scope\":\"whatsapp_business_messaging\",\"target_ids\":[" + targets + "]}]}}";
    }

}
