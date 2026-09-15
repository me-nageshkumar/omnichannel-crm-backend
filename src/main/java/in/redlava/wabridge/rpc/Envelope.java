package in.redlava.wabridge.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** One frame of the extension <-> server protocol. Unused fields are null and omitted on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Envelope(
        String type,
        String id,
        String token,
        String method,
        JsonNode params,
        JsonNode result,
        RpcError error,
        String name,
        JsonNode payload) {

    public static final String AUTH = "auth";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String EVENT = "event";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcError(String code, String message) {}

    public static Envelope auth(String token) {
        return new Envelope(AUTH, null, token, null, null, null, null, null, null);
    }

    public static Envelope request(String id, String method, JsonNode params) {
        return new Envelope(REQUEST, id, null, method, params, null, null, null, null);
    }

    public static Envelope response(String id, JsonNode result) {
        return new Envelope(RESPONSE, id, null, null, null, result, null, null, null);
    }

    public static Envelope errorResponse(String id, String code, String message) {
        return new Envelope(RESPONSE, id, null, null, null, null, new RpcError(code, message), null, null);
    }

    public static Envelope event(String name, JsonNode payload) {
        return new Envelope(EVENT, null, null, null, null, null, null, name, payload);
    }

    public static Envelope ping() {
        return new Envelope(PING, null, null, null, null, null, null, null, null);
    }

    public static Envelope pong() {
        return new Envelope(PONG, null, null, null, null, null, null, null, null);
    }
}
