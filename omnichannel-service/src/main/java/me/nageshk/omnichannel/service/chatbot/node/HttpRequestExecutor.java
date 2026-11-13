package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.HttpRequestSettings;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** HTTP request (spec §4.2): a rendered request; success on 2xx, failure otherwise. */
@Component
public class HttpRequestExecutor extends BaseExecutor {

    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ChatbotHttpClient http;

    public HttpRequestExecutor(FlowParser parser, ChatbotHttpClient http) {
        super(parser);
        this.http = http;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        HttpRequestSettings s = settings(node, HttpRequestSettings.class);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("response", response);
        try {
            ChatbotHttpClient.Response r = http.call(s, ctx);
            response.put("status", r.status());
            response.put("headers", r.headers());
            response.put("body", r.body());
            boolean ok = r.status() >= 200 && r.status() < 300;
            return StepResult.next(ok ? Outputs.SUCCESS : Outputs.FAILURE, result,
                    (s.method() == null ? "GET" : s.method().toUpperCase()) + " " + s.url() + " → " + r.status());
        } catch (ChatbotHttpClient.Failed e) {
            response.put("status", null);
            response.put("error", e.getMessage());
            return StepResult.next(Outputs.FAILURE, result, "Request failed: " + e.getMessage());
        }
    }

    @Override
    public NodeType type() {
        return NodeType.HTTP_REQUEST;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        return List.of(Outputs.SUCCESS, Outputs.FAILURE);
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        HttpRequestSettings s = settings(node, HttpRequestSettings.class);
        Problems p = new Problems(node);
        if (s.method() == null || !METHODS.contains(s.method().toUpperCase())) p.error("method", "must be GET, POST, PUT, PATCH or DELETE");
        if (p.text("url", s.url(), 2000, true)) {
            String url = s.url().strip();
            if (!(url.startsWith("https://") || url.startsWith("http://")) || url.chars().anyMatch(Character::isWhitespace)) {
                p.error("url", "must start with http:// or https://");
            }
        }
        if (s.headers() != null) {
            for (Map.Entry<String, String> e : s.headers().entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue();
                if (blank(e.getKey()) || e.getKey().contains("\r") || e.getKey().contains("\n") || v.contains("\r") || v.contains("\n")) {
                    p.error("headers." + e.getKey(), "header names and values cannot contain line breaks");
                }
            }
        }
        p.text("contentType", s.contentType(), 100, false);
        if (!blank(s.body()) && isJson(s.contentType()) && !s.body().contains("{{")) {
            try {
                JSON.readTree(s.body());
            } catch (RuntimeException e) {
                p.error("body", "is not valid JSON");
            }
        }
        return p.list;
    }

    static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }
}
