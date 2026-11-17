package me.nageshk.omnichannel.ai.provider.openai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiConnectionField;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviderException.Code;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI's Responses API over a plain {@code RestClient}; no SDK. */
@Component
public class OpenAiProvider implements AiProvider {

    private static final AiCapabilities CAPABILITIES =
            new AiCapabilities(StateMode.CHAINED, true, true, true, true, true, true, true);

    private static final String NOT_A_SECRET = " Not a secret; the key goes in API key.";
    private static final List<AiConnectionField> FIELDS = List.of(
            new AiConnectionField("organization", "Organization ID",
                    "Optional. Sent as OpenAI-Organization." + NOT_A_SECRET, AiConnectionField.Kind.TEXT, false),
            new AiConnectionField("project", "Project ID",
                    "Optional. Sent as OpenAI-Project." + NOT_A_SECRET, AiConnectionField.Kind.TEXT, false));

    private record Raw(int status, String body, HttpHeaders headers) {
    }

    private final RestClient client;
    private final String baseUrl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiProvider(AiProperties props) {
        Duration timeout = props.requestTimeout();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        String base = props.openai().baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String label() {
        return "OpenAI";
    }

    @Override
    public AiCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<AiConnectionField> connectionFields() {
        return FIELDS;
    }

    @Override
    public void verify(AiCredential c) {
        get(c, "/models?limit=1");
    }

    @Override
    public List<AiModelInfo> models(AiCredential c) {
        List<AiModelInfo> out = new ArrayList<>();
        for (JsonNode m : get(c, "/models").path("data")) {
            String id = m.path("id").asString(null);
            if (id != null) out.add(new AiModelInfo(id));
        }
        return out;
    }

    @Override
    public List<VectorStoreInfo> vectorStores(AiCredential c) {
        List<VectorStoreInfo> out = new ArrayList<>();
        for (JsonNode v : get(c, "/vector_stores?limit=100").path("data")) {
            out.add(new VectorStoreInfo(v.path("id").asString(""), v.path("name").asString(""),
                    v.path("file_counts").path("completed").asLong(0)));
        }
        return out;
    }

    @Override
    public ProviderReply respond(AiCredential c, ProviderRequest r) {
        String json = mapper.writeValueAsString(OpenAiRequests.body(r));
        return OpenAiReplies.parse(send(c, "POST", "/responses", json));
    }

    private JsonNode get(AiCredential c, String path) {
        return send(c, "GET", path, null);
    }

    private JsonNode send(AiCredential c, String method, String path, String body) {
        Raw raw;
        try {
            raw = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(baseUrl + path)
                    .headers(h -> {
                        h.setBearerAuth(c.apiKey());
                        if (c.setting("organization") != null) h.set("OpenAI-Organization", c.setting("organization"));
                        if (c.setting("project") != null) h.set("OpenAI-Project", c.setting("project"));
                        h.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .body(body == null ? "" : body)
                    .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8), res.getHeaders()), false);
        } catch (ResourceAccessException e) {
            throw transport(e, c);
        } catch (RuntimeException e) {
            throw new AiProviderException(Code.UNAVAILABLE, redact("OpenAI call failed: " + e.getMessage(), c));
        }
        if (raw.status() >= 400) throw httpError(raw, c);
        try {
            return mapper.readTree(raw.body());
        } catch (RuntimeException e) {
            throw new AiProviderException(Code.UNAVAILABLE, "OpenAI returned a reply that is not JSON.");
        }
    }

    private AiProviderException transport(ResourceAccessException e, AiCredential c) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return new AiProviderException(Code.TIMEOUT, "OpenAI did not answer in time.");
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        if (msg.toLowerCase(Locale.ROOT).contains("timed out")) {
            return new AiProviderException(Code.TIMEOUT, "OpenAI did not answer in time.");
        }
        return new AiProviderException(Code.UNAVAILABLE, redact("OpenAI could not be reached: " + msg, c));
    }

    private AiProviderException httpError(Raw raw, AiCredential c) {
        String message = null;
        String code = null;
        try {
            JsonNode err = mapper.readTree(raw.body()).path("error");
            message = err.path("message").asString(null);
            code = err.path("code").asString(null);
        } catch (RuntimeException ignored) {
            // not JSON; fall back to the status
        }
        String text = redact(message != null ? message : "OpenAI answered HTTP " + raw.status(), c);
        int s = raw.status();
        if (s == 401 || s == 403) return new AiProviderException(Code.AUTH, text);
        if (s == 429) return new AiProviderException(Code.RATE_LIMIT, text, retryAfter(raw.headers()));
        if (s >= 500) return new AiProviderException(Code.UNAVAILABLE, text);
        if (s == 400 && ("previous_response_not_found".equals(code) || previousNotFound(message))) {
            return new AiProviderException(Code.STATE_EXPIRED, text);
        }
        if (s == 400 && "context_length_exceeded".equals(code)) return new AiProviderException(Code.CONTEXT_TOO_LONG, text);
        return new AiProviderException(Code.BAD_REQUEST, text);
    }

    private static boolean previousNotFound(String m) {
        if (m == null) return false;
        int i = m.indexOf("Previous response with id");
        return i >= 0 && m.indexOf("not found", i) > i;
    }

    private static Duration retryAfter(HttpHeaders h) {
        String v = h.getFirst("Retry-After");
        if (v == null) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(v.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String redact(String s, AiCredential c) {
        String key = c.apiKey();
        return key == null || key.isEmpty() ? s : s.replace(key, "«key»");
    }
}
