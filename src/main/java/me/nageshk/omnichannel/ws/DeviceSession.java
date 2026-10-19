package me.nageshk.omnichannel.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.omnichannel.rpc.Envelope;
import java.io.IOException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** One authenticated extension connection for one user, plus what we know about its WhatsApp state. */
public final class DeviceSession {

    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_CONNECTED = "CONNECTED";

    private final String username;
    private final WebSocketSession session;
    private final ObjectMapper mapper;
    private volatile String waState = STATE_UNKNOWN;
    private volatile JsonNode info;

    public DeviceSession(String username, WebSocketSession session, ObjectMapper mapper) {
        this.username = username;
        this.session = session;
        this.mapper = mapper;
        this.info = mapper.createObjectNode();
    }

    public String username() {
        return username;
    }

    public String sessionId() {
        return session.getId();
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
