package me.nageshk.omnichannel.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.auth.AccessTokenAuthenticator;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.rpc.RpcDispatcher;
import me.nageshk.omnichannel.rpc.RpcException;
import me.nageshk.omnichannel.scripts.ScriptCatalog;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class BridgeWebSocketHandler extends TextWebSocketHandler {

    public static final int CLOSE_UNAUTHORIZED = 4401;
    public static final int CLOSE_REPLACED = 4409;

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final AccessTokenAuthenticator authenticator;
    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;
    private final ApplicationEventPublisher publisher;
    private final ScheduledExecutorService scheduler;
    private final OmnichannelProperties props;
    private final ScriptCatalog scripts;
    private final EventSequenceTracker sequences;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> authTimers = new ConcurrentHashMap<>();

    public BridgeWebSocketHandler(
            ObjectMapper mapper,
            AccessTokenAuthenticator authenticator,
            DeviceRegistry registry,
            RpcDispatcher dispatcher,
            ApplicationEventPublisher publisher,
            ScheduledExecutorService wsScheduler,
            OmnichannelProperties props,
            ScriptCatalog scripts,
            EventSequenceTracker sequences) {
        this.mapper = mapper;
        this.authenticator = authenticator;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.publisher = publisher;
        this.scheduler = wsScheduler;
        this.props = props;
        this.scripts = scripts;
        this.sequences = sequences;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long millis = props.rpc().authTimeout().toMillis();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (registry.findBySessionId(session.getId()).isEmpty()) {
                closeQuietly(session, CLOSE_UNAUTHORIZED, "auth timeout");
            }
        }, millis, TimeUnit.MILLISECONDS);
        authTimers.put(session.getId(), timer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Envelope frame;
        try {
            frame = mapper.readValue(message.getPayload(), Envelope.class);
        } catch (IOException e) {
            log.warn("Unparseable frame from {}: {}", session.getId(), e.getMessage());
            return;
        }

        Optional<DeviceSession> maybeDevice = registry.findBySessionId(session.getId());
        if (maybeDevice.isEmpty()) {
            handleUnauthenticated(session, frame);
            return;
        }
        DeviceSession device = maybeDevice.get();

        switch (frame.type() == null ? "" : frame.type()) {
            case Envelope.REQUEST -> handleRequest(device, frame);
            case Envelope.RESPONSE -> {
                if (!dispatcher.complete(frame)) {
                    log.debug("[{}] response for unknown id {}", device.username(), frame.id());
                }
            }
            case Envelope.EVENT -> handleEvent(device, frame);
            case Envelope.PING -> device.send(Envelope.pong());
            case Envelope.PONG -> { /* keepalive reply, nothing to do */ }
            default -> log.warn("[{}] unknown frame type {}", device.username(), frame.type());
        }
    }

    private void handleUnauthenticated(WebSocketSession session, Envelope frame) throws IOException {
        if (!Envelope.AUTH.equals(frame.type()) || frame.token() == null) {
            closeQuietly(session, CLOSE_UNAUTHORIZED, "auth required");
            return;
        }
        Optional<String> username = authenticator.authenticate(frame.token())
                .filter(user -> user.hasAuthority(Authority.WHATSAPP_BRIDGE.code()))
                .map(AuthenticatedUser::getUsername);
        if (username.isEmpty()) {
            closeQuietly(session, CLOSE_UNAUTHORIZED, "invalid token");
            return;
        }
        cancelAuthTimer(session.getId());
        DeviceSession device = new DeviceSession(username.get(), session, mapper);
        registry.register(device).ifPresent(old -> {
            log.info("[{}] replacing device {} with {}", old.username(), old.sessionId(), device.sessionId());
            old.close(CLOSE_REPLACED, "replaced by newer connection");
        });
        ObjectNode ok = mapper.createObjectNode().put("ok", true).put("username", username.get());
        device.send(Envelope.response("auth", ok));
        log.info("[{}] device connected ({})", username.get(), session.getId());
    }

    private void handleRequest(DeviceSession device, Envelope frame) throws IOException {
        if ("hello".equals(frame.method())) {
            JsonNode params = frame.params();
            if (params != null) {
                if (params.hasNonNull("waState")) device.setWaState(params.get("waState").asText());
                if (params.hasNonNull("info")) device.setInfo(params.get("info"));
                JsonNode outbox = params.path("outbox");
                log.info("[{}] hello waState={} outbox pending={} dropped={}", device.username(),
                        device.waState(), outbox.path("pending").asInt(0), outbox.path("dropped").asInt(0));
            }
            ObjectNode result = mapper.createObjectNode().put("ok", true);
            result.set("scripts", scripts.manifest());
            device.send(Envelope.response(frame.id(), result));
            return;
        }
        device.send(Envelope.errorResponse(frame.id(), RpcException.METHOD_NOT_FOUND,
                "unknown server method: " + frame.method()));
    }

    private void handleEvent(DeviceSession device, Envelope frame) {
        JsonNode payload = frame.payload() == null ? mapper.nullNode() : frame.payload();
        Long seq = frame.seq();
        if (seq != null && sequences.isDuplicate(device.username(), seq)) {
            log.debug("[{}] duplicate event seq={} ({}), re-acking", device.username(), seq, frame.name());
            sendAck(device, seq);
            return;
        }
        switch (frame.name() == null ? "" : frame.name()) {
            case "ready" -> {
                device.setWaState(DeviceSession.STATE_CONNECTED);
                device.setInfo(payload);
            }
            case "change_state" -> device.setWaState(payload.path("state").asText(DeviceSession.STATE_UNKNOWN));
            case "disconnected" -> device.setWaState("DISCONNECTED");
            default -> { /* no state change */ }
        }
        publisher.publishEvent(new WhatsAppEvent(this, device.username(), frame.name(), payload, seq, frame.ts()));
        if (seq != null) {
            sequences.record(device.username(), seq);
            sendAck(device, seq);
        }
    }

    private void sendAck(DeviceSession device, long seq) {
        try {
            device.send(Envelope.eventAck(seq));
        } catch (IOException e) {
            log.warn("[{}] could not ack event seq={}: {}", device.username(), seq, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelAuthTimer(session.getId());
        registry.unregister(session.getId()).ifPresent(device -> {
            int failed = dispatcher.failAll(device.username(), RpcException.DEVICE_DISCONNECTED);
            log.info("[{}] device disconnected ({}), status={}, failedCalls={}",
                    device.username(), session.getId(), status.getCode(), failed);
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("transport error on {}: {}", session.getId(), exception.toString());
    }

    private void cancelAuthTimer(String sessionId) {
        ScheduledFuture<?> timer = authTimers.remove(sessionId);
        if (timer != null) timer.cancel(false);
    }

    private static void closeQuietly(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) session.close(new CloseStatus(code, reason));
        } catch (IOException ignoredError) {
            // already closed
        }
    }
}
