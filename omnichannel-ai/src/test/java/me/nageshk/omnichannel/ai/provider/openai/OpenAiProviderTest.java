package me.nageshk.omnichannel.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviderException.Code;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiProviderTest {

    private static final String KEY = "sk-secret-123";
    private static final AiCredential CRED = new AiCredential(KEY);

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OpenAiProvider provider(Duration timeout, Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().toString());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            try {
                handler.accept(ex);
            } finally {
                ex.close();
            }
        });
        server.start();
        AiProperties props = new AiProperties(true, Duration.ofSeconds(2), 8, timeout, Duration.ofSeconds(120), 2, 8,
                Duration.ofSeconds(10), new AiProperties.OpenAi("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                new AiProperties.Mcp(Duration.ofSeconds(20), 65536, Duration.ofMinutes(5)));
        return new OpenAiProvider(props);
    }

    private static void reply(HttpExchange ex, int status, String body, String... headers) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            for (int i = 0; i < headers.length; i += 2) ex.getResponseHeaders().set(headers[i], headers[i + 1]);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ProviderRequest request() {
        return new ProviderRequest("gpt-5", null, List.of(new AiItem.UserText("Hi")), null, null, null, null, null,
                null, null, null);
    }

    private AiProviderException failure(OpenAiProvider p) {
        return org.junit.jupiter.api.Assertions.assertThrows(AiProviderException.class,
                () -> p.respond(CRED, request()));
    }

    @Test
    void identity() throws IOException {
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200, "{}"));
        assertThat(p.id()).isEqualTo("openai");
        assertThat(p.label()).isEqualTo("OpenAI");
        assertThat(p.capabilities().hostedMcp()).isTrue();
        assertThat(p.capabilities().state().name()).isEqualTo("CHAINED");
    }

    @Test
    void sendsBearerKey() throws IOException {
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200,
                "{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"yo\"}]}]}"));
        ProviderReply r = p.respond(CRED, request());
        assertThat(auth.get()).isEqualTo("Bearer " + KEY);
        assertThat(path.get()).isEqualTo("/v1/responses");
        assertThat(r.text()).isEqualTo("yo");
    }

    @Test
    void unauthorizedIsAuth() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 401, "{\"error\":{\"message\":\"bad key\"}}"));
        AiProviderException e = failure(p);
        assertThat(e.code()).isEqualTo(Code.AUTH);
        assertThat(e.getMessage()).contains("bad key");
    }

    @Test
    void rateLimitWithRetryAfter() throws IOException {
        var p = provider(Duration.ofSeconds(5),
                ex -> reply(ex, 429, "{\"error\":{\"message\":\"slow down\"}}", "retry-after", "3"));
        AiProviderException e = failure(p);
        assertThat(e.code()).isEqualTo(Code.RATE_LIMIT);
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void previousResponseNotFoundIsStateExpired() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 400,
                "{\"error\":{\"message\":\"nope\",\"code\":\"previous_response_not_found\"}}"));
        assertThat(failure(p).code()).isEqualTo(Code.STATE_EXPIRED);
    }

    @Test
    void previousResponseMessageIsStateExpired() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 400,
                "{\"error\":{\"message\":\"Previous response with id 'resp_x' not found.\"}}"));
        assertThat(failure(p).code()).isEqualTo(Code.STATE_EXPIRED);
    }

    @Test
    void contextLengthIsContextTooLong() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 400,
                "{\"error\":{\"message\":\"too long\",\"code\":\"context_length_exceeded\"}}"));
        assertThat(failure(p).code()).isEqualTo(Code.CONTEXT_TOO_LONG);
    }

    @Test
    void otherClientErrorIsBadRequest() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 400, "{\"error\":{\"message\":\"bad\"}}"));
        assertThat(failure(p).code()).isEqualTo(Code.BAD_REQUEST);
    }

    @Test
    void serverErrorIsUnavailable() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 503, "{\"error\":{\"message\":\"down\"}}"));
        assertThat(failure(p).code()).isEqualTo(Code.UNAVAILABLE);
    }

    @Test
    void slowServerIsTimeout() throws IOException {
        var p = provider(Duration.ofMillis(200), ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "{}");
        });
        assertThat(failure(p).code()).isEqualTo(Code.TIMEOUT);
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        var p = provider(Duration.ofSeconds(2), ex -> reply(ex, 200, "{}"));
        server.stop(0);
        assertThat(failure(p).code()).isEqualTo(Code.UNAVAILABLE);
    }

    @Test
    void modelsListed() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200, "{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"gpt-4o\"}]}"));
        assertThat(p.models(CRED)).containsExactly(new AiModelInfo("gpt-5"), new AiModelInfo("gpt-4o"));
        assertThat(path.get()).isEqualTo("/v1/models");
    }

    @Test
    void vectorStoresListed() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200,
                "{\"data\":[{\"id\":\"vs_1\",\"name\":\"Docs\",\"file_counts\":{\"completed\":7}}]}"));
        assertThat(p.vectorStores(CRED)).containsExactly(new VectorStoreInfo("vs_1", "Docs", 7));
        assertThat(path.get()).isEqualTo("/v1/vector_stores?limit=100");
    }

    @Test
    void verifyCallsModelsLimitOne() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200, "{\"data\":[]}"));
        p.verify(CRED);
        assertThat(path.get()).isEqualTo("/v1/models?limit=1");
    }

    @Test
    void errorBodyNeverContainsKey() throws IOException {
        var p = provider(Duration.ofSeconds(5), ex -> reply(ex, 401,
                "{\"error\":{\"message\":\"Incorrect API key provided: " + ex.getRequestHeaders().getFirst("Authorization")
                        + " and " + KEY + "\"}}"));
        assertThatThrownBy(() -> p.respond(CRED, request())).isInstanceOf(AiProviderException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY).contains("«key»");
                });
    }

    @Test
    void sendsOrganizationAndProjectHeadersWhenSet() throws IOException {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> {
            seen.add(ex.getRequestHeaders().getFirst("OpenAI-Organization") + "|"
                    + ex.getRequestHeaders().getFirst("OpenAI-Project"));
            reply(ex, 200, "{}");
        });
        AiCredential c = new AiCredential(KEY, java.util.Map.of("organization", "org_1", "project", "proj_1"));
        p.verify(c);
        p.models(c);
        p.vectorStores(c);
        p.respond(c, request());
        assertThat(seen).hasSize(4).containsOnly("org_1|proj_1");
    }

    @Test
    void sendsNoAccountHeadersWhenUnset() throws IOException {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> {
            seen.add(ex.getRequestHeaders().getFirst("OpenAI-Organization") + "|"
                    + ex.getRequestHeaders().getFirst("OpenAI-Project"));
            reply(ex, 200, "{}");
        });
        p.verify(CRED);
        assertThat(seen).containsExactly("null|null");
    }

    @Test
    void declaresOrganizationAndProject() throws IOException {
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200, "{}"));
        assertThat(p.connectionFields()).extracting(f -> f.key()).containsExactly("organization", "project");
        assertThat(p.connectionFields()).allSatisfy(f -> {
            assertThat(f.kind()).isEqualTo(me.nageshk.omnichannel.ai.api.AiConnectionField.Kind.TEXT);
            assertThat(f.required()).isFalse();
        });
    }

    @Test
    void credentialToStringHidesSettings() {
        String s = new AiCredential("sk-abc", java.util.Map.of("organization", "org_1")).toString();
        assertThat(s).doesNotContain("sk-abc").doesNotContain("org_1");
    }

    @Test
    void declaresEffortValues() throws IOException {
        OpenAiProvider p = provider(Duration.ofSeconds(5), ex -> reply(ex, 200, "{}"));
        assertThat(p.capabilities().reasoningEfforts())
                .containsSubsequence("minimal", "low", "medium", "high");
    }
}
