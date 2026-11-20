package me.nageshk.omnichannel.meta.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GraphErrorsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    private static String error(int code) {
        return "{\"error\":{\"message\":\"m" + code + "\",\"type\":\"OAuthException\",\"code\":" + code + "}}";
    }

    @Test
    void http401IsAuth() {
        assertThat(GraphErrors.of(401, null, null).kind()).isEqualTo(GraphException.Kind.AUTH);
    }

    @Test
    void code190IsAuth() {
        GraphException e = GraphErrors.of(400, json(error(190)), null);
        assertThat(e.kind()).isEqualTo(GraphException.Kind.AUTH);
        assertThat(e.metaCode()).isEqualTo(190);
    }

    @Test
    void code131047IsWindowClosed() {
        assertThat(GraphErrors.of(400, json(error(131047)), null).kind()).isEqualTo(GraphException.Kind.WINDOW_CLOSED);
    }

    @Test
    void rateLimitCodes() {
        for (int code : new int[] {130429, 131056, 4, 80007}) {
            assertThat(GraphErrors.of(400, json(error(code)), null).kind()).as("code %s", code)
                    .isEqualTo(GraphException.Kind.RATE_LIMIT);
        }
        assertThat(GraphErrors.of(429, null, Duration.ofSeconds(7)).retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void serverErrorIsUnavailable() {
        assertThat(GraphErrors.of(503, null, null).kind()).isEqualTo(GraphException.Kind.UNAVAILABLE);
    }

    @Test
    void notFound() {
        assertThat(GraphErrors.of(404, null, null).kind()).isEqualTo(GraphException.Kind.NOT_FOUND);
    }

    @Test
    void badRequestCarriesTitleAndDetails() {
        GraphException e = GraphErrors.of(400,
                json("{\"error\":{\"code\":100,\"message\":\"x\",\"error_data\":{\"details\":\"y\"}}}"), null);
        assertThat(e.kind()).isEqualTo(GraphException.Kind.BAD_REQUEST);
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.title()).isEqualTo("x");
        assertThat(e.details()).isEqualTo("y");
        assertThat(e.getMessage()).contains("x").contains("y");
    }
}
