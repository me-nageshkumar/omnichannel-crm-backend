package me.nageshk.omnichannel.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

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
        JsonNode payload,
        Long seq,
        Long ts,
        String acct) {

    public static final String AUTH = "auth";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String EVENT = "event";
    public static final String EVENT_ACK = "event_ack";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcError(String code, String message) {}

    public static Envelope auth(String token) {
        return new Envelope(AUTH, null, token, null, null, null, null, null, null, null, null, null);
    }

    public static Envelope request(String id, String method, JsonNode params) {
        return new Envelope(REQUEST, id, null, method, params, null, null, null, null, null, null, null);
    }

    public static Envelope response(String id, JsonNode result) {
        return new Envelope(RESPONSE, id, null, null, null, result, null, null, null, null, null, null);
    }

    public static Envelope errorResponse(String id, String code, String message) {
        return new Envelope(RESPONSE, id, null, null, null, null, new RpcError(code, message), null, null, null, null, null);
    }

    public static Envelope event(String name, JsonNode payload) {
        return new Envelope(EVENT, null, null, null, null, null, null, name, payload, null, null, null);
    }

    /** A durable event as the extension sends it: carries its outbox sequence number and capture time. */
    public static Envelope durableEvent(String name, JsonNode payload, long seq, long ts) {
        return new Envelope(EVENT, null, null, null, null, null, null, name, payload, seq, ts, null);
    }

    /** A durable event stamped with the digits of the WhatsApp account it was captured under. */
    public static Envelope durableEvent(String name, JsonNode payload, long seq, long ts, String acct) {
        return new Envelope(EVENT, null, null, null, null, null, null, name, payload, seq, ts, acct);
    }

    /** Cumulative acknowledgement: the extension may drop every outbox entry with seq <= this. */
    public static Envelope eventAck(long seq) {
        return new Envelope(EVENT_ACK, null, null, null, null, null, null, null, null, seq, null, null);
    }

    public static Envelope ping() {
        return new Envelope(PING, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static Envelope pong() {
        return new Envelope(PONG, null, null, null, null, null, null, null, null, null, null, null);
    }
}
