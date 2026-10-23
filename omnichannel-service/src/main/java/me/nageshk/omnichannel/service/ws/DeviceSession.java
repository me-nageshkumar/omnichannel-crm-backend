package me.nageshk.omnichannel.service.ws;

import java.io.IOException;
import me.nageshk.omnichannel.security.phone.DevicePrincipal;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One authenticated extension connection for one onboarded number, plus what we know about its
 * WhatsApp state. {@link #confirmed()} turns true once WhatsApp Web reports that very number.
 */
public final class DeviceSession {

    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_CONNECTED = "CONNECTED";

    private final DevicePrincipal principal;
    private final WebSocketSession session;
    private final ObjectMapper mapper;
    private volatile String waState = STATE_UNKNOWN;
    private volatile JsonNode info;
    private volatile boolean confirmed;

    public DeviceSession(DevicePrincipal principal, WebSocketSession session, ObjectMapper mapper) {
        this.principal = principal;
        this.session = session;
        this.mapper = mapper;
        this.info = mapper.createObjectNode();
    }

    public DevicePrincipal principal() {
        return principal;
    }

    public String phoneNumberId() {
        return principal.phoneNumberId();
    }

    public String sessionId() {
        return session.getId();
    }

    public boolean confirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String waState() {
        return waState;
    }

    public void setWaState(String waState) {
        this.waState = waState == null ? STATE_UNKNOWN : waState;
    }

    public JsonNode info() {
        return info;
    }

    public void setInfo(JsonNode info) {
        if (info != null && !info.isNull()) {
            this.info = info;
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    /** Serializes and sends one frame. Synchronized because the standard WebSocketSession is not thread-safe. */
    public void send(Envelope frame) throws IOException {
        String json = mapper.writeValueAsString(frame);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    public void close(int code, String reason) {
        try {
            synchronized (session) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (IOException ignoredError) {
            // already closed
        }
    }
}
