package me.nageshk.omnichannel.app.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for api.openai.com: canned responses are served in the order queued (200 with an empty list once
 * the queue is empty) and every request is recorded. Tests point {@code omnichannel.ai.openai.base-url} at
 * {@link #baseUrl()}.
 */
public final class OpenAiStub {

    public record Recorded(String method, String path, String authorization, String body, String organization,
            String project) {}

    private record Canned(int status, String body) {}

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Canned> queue = new ConcurrentLinkedQueue<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private volatile Runnable beforeNext;

    public OpenAiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = ex.getRequestURI().getRawQuery();
        recorded.add(new Recorded(ex.getRequestMethod(),
                ex.getRequestURI().getRawPath() + (query == null ? "" : "?" + query),
                ex.getRequestHeaders().getFirst("Authorization"), body,
                ex.getRequestHeaders().getFirst("OpenAI-Organization"), ex.getRequestHeaders().getFirst("OpenAI-Project")));
        Runnable hook = beforeNext;
        if (hook != null) {
            beforeNext = null;
            hook.run();
        }
        Canned c = queue.poll();
        if (c == null) c = new Canned(200, "{\"data\":[]}");
        byte[] out = c.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(c.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) ex.getResponseBody().write(out);
        ex.close();
    }

    /** The value for {@code omnichannel.ai.openai.base-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void enqueue(int status, String body) {
        queue.add(new Canned(status, body));
    }

    /** Runs once, on the stub's thread, when the next request arrives and before it is answered (a change in mid-call). */
    public void beforeNextRequest(Runnable hook) {
        beforeNext = hook;
    }

    public List<Recorded> requests() {
        return new ArrayList<>(recorded);
    }

    public Recorded last() {
        return recorded.get(recorded.size() - 1);
    }

    public void reset() {
        queue.clear();
        recorded.clear();
        beforeNext = null;
    }

    public void stop() {
        server.stop(0);
    }
}
