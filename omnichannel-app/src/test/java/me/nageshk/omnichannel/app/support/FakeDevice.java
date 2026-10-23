package me.nageshk.omnichannel.app.support;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/** A minimal stand-in for the Chrome extension: connects, sends frames, queues received frames. */
public class FakeDevice extends TextWebSocketHandler implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private WebSocketSession session;

    public static FakeDevice connect(int port) throws Exception {
        FakeDevice device = new FakeDevice();
        device.session = new StandardWebSocketClient()
                .execute(device, URI.create("ws://localhost:" + port + "/ws").toString())
                .get(5, TimeUnit.SECONDS);
        return device;
    }

    /** Connects and completes the auth handshake. */
    public static FakeDevice connectAndAuth(int port, String token) throws Exception {
        FakeDevice device = connect(port);
        device.send(Envelope.auth(token));
        Envelope reply = device.next();
        if (!"auth".equals(reply.id()) || reply.error() != null) {
            throw new IllegalStateException("auth failed: " + reply);
        }
        return device;
    }

    public void send(Envelope frame) throws Exception {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** Next received frame, waiting up to 5 s. */
    public Envelope next() throws InterruptedException {
        Envelope e = inbox.poll(5, TimeUnit.SECONDS);
        if (e == null) throw new AssertionError("no frame received within 5s");
        return e;
    }

    /** Next received frame, waiting at most {@code wait}; empty when none arrived. */
    public Optional<Envelope> poll(Duration wait) throws InterruptedException {
        return Optional.ofNullable(inbox.poll(wait.toMillis(), TimeUnit.MILLISECONDS));
    }

    public CloseStatus awaitClose() throws Exception {
        return closed.get(10, TimeUnit.SECONDS);
    }

    public boolean isOpen() {
        return session != null && session.isOpen();
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
        inbox.add(mapper.readValue(message.getPayload(), Envelope.class));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closed.complete(status);
    }

    /** Closes and waits for the close handshake so the server has unregistered us before the next test. */
    @Override
    public void close() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
            awaitClose();
        }
    }
}
