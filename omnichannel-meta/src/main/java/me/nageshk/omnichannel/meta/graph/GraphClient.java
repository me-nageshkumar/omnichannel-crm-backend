package me.nageshk.omnichannel.meta.graph;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.nageshk.omnichannel.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Meta's Graph API over a plain {@code RestClient} (no SDK). Paths are relative to {@code <base>/<version>/}. The
 * token goes only into the {@code Authorization} header and is cut out of anything logged or thrown.
 */
@Component
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

    /** A file part of a multipart upload. */
    public record FilePart(String filename, String contentType, byte[] bytes) {}

    private record Raw(int status, byte[] body, HttpHeaders headers) {}

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String base;

    public GraphClient(MetaProperties props, ObjectMapper mapper) {
        Duration timeout = props.requestTimeout();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.mapper = mapper;
        String b = props.graphBaseUrl().replaceAll("/+$", "");
        this.base = b + "/" + props.graphVersion() + "/";
    }

    public JsonNode get(String token, String path, Map<String, String> query) {
        return json(exchange(token, HttpMethod.GET, uri(path, query), null, null), token);
    }

    public JsonNode post(String token, String path, Object body) {
        byte[] bytes = body == null ? new byte[0] : mapper.writeValueAsBytes(body);
        return json(exchange(token, HttpMethod.POST, uri(path, Map.of()), MediaType.APPLICATION_JSON_VALUE, bytes),
                token);
    }

    /** A form post ({@code application/x-www-form-urlencoded}), as the app subscriptions edge takes. */
    public JsonNode postForm(String token, String path, Map<String, String> form) {
        UriComponentsBuilder b = UriComponentsBuilder.newInstance();
        form.forEach(b::queryParam);
        String encoded = b.encode().build().getQuery();
        byte[] bytes = (encoded == null ? "" : encoded).getBytes(StandardCharsets.UTF_8);
        return json(exchange(token, HttpMethod.POST, uri(path, Map.of()), MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                bytes), token);
    }

    /** A multipart post of plain fields and file parts. */
    public JsonNode postMultipart(String token, String path, Map<String, Object> parts) {
        String boundary = "----omnichannel" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> e : parts.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (e.getValue() instanceof FilePart f) {
                String name = f.filename() == null ? "file" : f.filename().replace("\"", "'").replace("\r", " ")
                        .replace("\n", " ");
                out.writeBytes(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"; filename=\"" + name
                        + "\"\r\nContent-Type: " + f.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(f.bytes());
            } else {
                out.writeBytes(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue())
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return json(exchange(token, HttpMethod.POST, uri(path, Map.of()), "multipart/form-data; boundary=" + boundary,
                out.toByteArray()), token);
    }

    /** A JSON page at an absolute URL Meta gave (a {@code paging.next}), fetched with the token. */
    public JsonNode getUrl(String token, String absoluteUrl) {
        return json(exchange(token, HttpMethod.GET, URI.create(absoluteUrl), null, null), token);
    }

    /** The bytes at an absolute URL Meta gave (a media URL), fetched with the token. */
    public byte[] download(String token, String absoluteUrl) {
        return exchange(token, HttpMethod.GET, URI.create(absoluteUrl), null, null).body();
    }

    private URI uri(String path, Map<String, String> query) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base + path.replaceFirst("^/+", ""));
        if (query != null) query.forEach(b::queryParam);
        return b.encode().build().toUri();
    }

    private Raw exchange(String token, HttpMethod method, URI uri, String contentType, byte[] body) {
        Raw raw;
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(uri).headers(h -> {
                if (token != null) h.setBearerAuth(token);
                h.setAccept(List.of(MediaType.ALL));
                if (contentType != null) h.set(HttpHeaders.CONTENT_TYPE, contentType);
            });
            if (body != null) spec.body(body);
            raw = spec.exchange((req, res) -> new Raw(res.getStatusCode().value(), res.getBody().readAllBytes(),
                    res.getHeaders()), false);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException
                    || String.valueOf(e.getMessage()).toLowerCase().contains("timed out");
            throw new GraphException(timeout ? GraphException.Kind.TIMEOUT : GraphException.Kind.UNAVAILABLE, 0,
                    null, redact("Meta could not be reached: " + e.getMessage(), token), null, null);
        }
        if (raw.status() >= 400) {
            GraphException e = GraphErrors.of(raw.status(), parse(raw.body()), retryAfter(raw.headers()));
            log.info("Graph {} {} answered {} (code {}): {}", method, uri.getPath(), raw.status(), e.metaCode(),
                    redact(e.getMessage(), token));
            throw new GraphException(e.kind(), e.httpStatus(), e.metaCode(), redact(e.title(), token),
                    redact(e.details(), token), e.retryAfter());
        }
        return raw;
    }

    private JsonNode json(Raw raw, String token) {
        if (raw.body().length == 0) return mapper.createObjectNode();
        JsonNode node = parse(raw.body());
        if (node == null) {
            throw new GraphException(GraphException.Kind.UNAVAILABLE, raw.status(), null,
                    "Meta returned a reply that is not JSON", null, null);
        }
        return node;
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration retryAfter(HttpHeaders h) {
        String v = h.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(v.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Cuts the token (and its app-secret half, for an app token) out of a text. */
    static String redact(String text, String token) {
        if (text == null || token == null || token.isEmpty()) return text;
        String out = text.replace(token, "[token]");
        int bar = token.indexOf('|');
        if (bar > 0 && bar < token.length() - 1) out = out.replace(token.substring(bar + 1), "[secret]");
        return out;
    }
}
