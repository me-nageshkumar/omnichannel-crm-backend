package me.nageshk.omnichannel.service.chatbot.node;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.service.chatbot.flow.settings.HttpRequestSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP request node's client (spec §4.2): rendered URL, headers and body; a bounded timeout and body size; no
 * redirects followed; JSON responses parsed. Every failure becomes a {@link Failed} the executor turns into the
 * {@code failure} output.
 */
@Component
public class ChatbotHttpClient {

    public record Response(int status, Map<String, String> headers, Object body) {
    }

    /** A request that did not complete with a response: the reason goes into {@code response.error}. */
    public static class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    private final RestClient client;
    private final HttpAddressGuard guard;
    private final ObjectMapper mapper;
    private final int maxBody;

    public ChatbotHttpClient(OmnichannelProperties props, HttpAddressGuard guard, ObjectMapper mapper) {
        Duration timeout = props.chatbot().httpTimeout();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.guard = guard;
        this.mapper = mapper;
        this.maxBody = props.chatbot().httpMaxBody();
    }

    public Response call(HttpRequestSettings s, RunContext ctx) {
        URI uri;
        try {
            uri = URI.create(ctx.render(s.url()).strip());
            guard.check(uri);
        } catch (IllegalArgumentException e) {
            throw new Failed(e.getMessage());
        }
        HttpMethod method = HttpMethod.valueOf(s.method() == null ? "GET" : s.method().toUpperCase(Locale.ROOT));
        boolean json = HttpRequestExecutor.isJson(s.contentType());
        try {
            RestClient.RequestBodySpec req = client.method(method).uri(uri).headers(h -> {
                if (s.headers() != null) s.headers().forEach((k, v) -> h.set(k, ctx.render(v)));
                if (s.contentType() != null && !s.contentType().isBlank()) h.set(HttpHeaders.CONTENT_TYPE, s.contentType());
            });
            if (s.body() != null && !s.body().isBlank() && method != HttpMethod.GET) {
                String body = json ? ctx.renderJson(s.body()) : ctx.render(s.body());
                if (s.contentType() == null || s.contentType().isBlank()) req.contentType(json ? MediaType.APPLICATION_JSON : MediaType.TEXT_PLAIN);
                req.body(body);
            }
            return req.exchange((request, response) -> read(response), false);
        } catch (Failed e) {
            throw e;
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            if (root instanceof java.net.http.HttpTimeoutException || msg.toLowerCase(Locale.ROOT).contains("timed out")
                    || msg.toLowerCase(Locale.ROOT).contains("timeout")) {
                throw new Failed("the request timed out");
            }
            throw new Failed("the request failed: " + msg);
        }
    }

    private Response read(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
        byte[] bytes;
        try (InputStream in = response.getBody()) {
            bytes = in.readNBytes(maxBody + 1);
        }
        if (bytes.length > maxBody) throw new Failed("the response is too large (over " + maxBody + " bytes)");
        String text = new String(bytes, StandardCharsets.UTF_8);
        Object body = text;
        String type = headers.getOrDefault("content-type", "");
        if (type.toLowerCase(Locale.ROOT).contains("json") && !text.isBlank()) {
            try {
                body = mapper.readValue(text, Object.class);
            } catch (RuntimeException notJson) {
                body = text;
            }
        }
        return new Response(status, headers, body);
    }

    static List<String> methods() {
        return List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    }
}
