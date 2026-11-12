package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Spec §4.2 HTTP request: rendered, bounded, success on 2xx, failure otherwise; the guard keeps it off our network. */
class HttpRequestExecutorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> seenBodies = new CopyOnWriteArrayList<>();
    private final List<String> seenHeaders = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "{\"ok\":true,\"n\":3}";
    private volatile String contentType = "application/json";
    private volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            seenBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenHeaders.add(ex.getRequestHeaders().getFirst("X-Token"));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static OmnichannelProperties props(Duration timeout, int maxBody, boolean allowPrivate) {
        OmnichannelProperties.Chatbot c = new OmnichannelProperties.Chatbot(false, Duration.ofSeconds(2), Duration.ofMinutes(2),
                4, 200, 5, timeout, maxBody, Duration.ofSeconds(60), 3, Duration.ofSeconds(30), 50, allowPrivate);
        return new OmnichannelProperties(null, null, null, null, null, null, null, null, null, null, c);
    }

    private HttpRequestExecutor executor(Duration timeout, int maxBody, boolean allowPrivate) {
        OmnichannelProperties p = props(timeout, maxBody, allowPrivate);
        return new HttpRequestExecutor(new FlowParser(MAPPER), new ChatbotHttpClient(p, new HttpAddressGuard(allowPrivate), MAPPER));
    }

    private RunContext ctx() {
        ChatbotRun run = new ChatbotRun();
        run.setId("r1");
        run.setWorkspaceId("w1");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("AskName", Map.of("answer", "O'Brien \"Bob\""));
        vars.put("trigger", Map.of("ruleId", "rule-9"));
        run.setVariables(vars);
        return new RunContext(run, null, new Flow(List.of(), List.of()), null, null, null, Instant.now());
    }

    private FlowNode node(Map<String, Object> settings) {
        return new FlowNode("n1", "Call", NodeType.HTTP_REQUEST, MAPPER.valueToTree(settings));
    }

    @Test
    void postsRenderedJsonBody() {
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, true).run(ctx(), node(Map.of(
                "method", "POST", "url", url(), "contentType", "application/json",
                "headers", Map.of("X-Token", "{{trigger.ruleId}}"),
                "body", "{\"name\":\"{{AskName.answer}}\"}")));
        assertThat(r.kind()).isEqualTo(StepResult.Kind.NEXT);
        assertThat(r.output()).isEqualTo(Outputs.SUCCESS);
        assertThat(seenHeaders).containsExactly("rule-9");
        assertThat(MAPPER.readTree(seenBodies.get(0)).get("name").asString()).isEqualTo("O'Brien \"Bob\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesJsonResponse() {
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        Map<String, Object> response = (Map<String, Object>) r.result().get("response");
        assertThat(response).containsEntry("status", 200);
        assertThat((Map<String, Object>) response.get("body")).containsEntry("ok", true).containsEntry("n", 3);
        assertThat((Map<String, Object>) response.get("headers")).containsKey("content-type");
    }

    @Test
    @SuppressWarnings("unchecked")
    void textResponseStaysText() {
        reply = "plain words";
        contentType = "text/plain";
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(((Map<String, Object>) r.result().get("response")).get("body")).isEqualTo("plain words");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonTwoHundredIsFailure() {
        status = 500;
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(r.output()).isEqualTo(Outputs.FAILURE);
        assertThat(((Map<String, Object>) r.result().get("response")).get("status")).isEqualTo(500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutIsFailure() {
        delayMs = 800;
        StepResult r = executor(Duration.ofMillis(300), 1 << 20, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(r.output()).isEqualTo(Outputs.FAILURE);
        assertThat(String.valueOf(((Map<String, Object>) r.result().get("response")).get("error"))).containsIgnoringCase("time");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizeBodyIsFailure() {
        reply = "x".repeat(5000);
        contentType = "text/plain";
        StepResult r = executor(Duration.ofSeconds(5), 1000, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(r.output()).isEqualTo(Outputs.FAILURE);
        assertThat(String.valueOf(((Map<String, Object>) r.result().get("response")).get("error"))).contains("too large");
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateAddressIsRefused() {
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, false).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(r.output()).isEqualTo(Outputs.FAILURE);
        assertThat(String.valueOf(((Map<String, Object>) r.result().get("response")).get("error"))).contains("private address");
        assertThat(seenBodies).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redirectIsFailure() {
        status = 302;
        StepResult r = executor(Duration.ofSeconds(5), 1 << 20, true).run(ctx(), node(Map.of("method", "GET", "url", url())));
        assertThat(r.output()).isEqualTo(Outputs.FAILURE);
        assertThat(((Map<String, Object>) r.result().get("response")).get("status")).isEqualTo(302);
    }
}
