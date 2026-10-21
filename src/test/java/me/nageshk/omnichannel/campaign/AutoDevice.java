package me.nageshk.omnichannel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.support.TestDevices;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connected, ready device for a number that answers every request on its own thread: {@code getNumberId} with
 * {@link #lookupAnswer}, {@code sendMessage} with {@link #sendAnswer}. Every request is recorded before it is
 * answered, so a finished tick has left all of its requests here.
 */
class AutoDevice implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SERIAL = new AtomicInteger();

    /** The {@code getNumberId} result for a phone; null answers JSON null (not on WhatsApp), a throw answers an error. */
    volatile Function<String, JsonNode> lookupAnswer = AutoDevice::registered;
    /** The whole response to a {@code sendMessage} request. */
    volatile Function<Envelope, Envelope> sendAnswer = req -> Envelope.response(req.id(), model(req, 1));

    private final FakeDevice device;
    private final List<Envelope> requests = new CopyOnWriteArrayList<>();
    private final Thread answering;
    private volatile Consumer<String> onLookup = phone -> { };
    private volatile boolean closed;

    AutoDevice(int port, TestDevices testDevices, PhoneNumber n) throws Exception {
        device = FakeDevice.connectAndAuth(port, testDevices.redeem(n).deviceKey());
        device.send(Envelope.event("ready",
                MAPPER.createObjectNode().put("pushname", "N").put("wid", n.getNumber() + "@c.us")));
        Thread.sleep(200);
        answering = new Thread(this::answerAll, "auto-device-" + n.getNumber());
        answering.setDaemon(true);
        answering.start();
    }

    /** {@code {"phone": phone@c.us, "lid": null}}. */
    static JsonNode registered(String phone) {
        return MAPPER.createObjectNode().put("phone", phone + "@c.us").putNull("lid");
    }

    /** A sent-message model for the request, the way whatsapp-web.js reports it, with the given ack. */
    static ObjectNode model(Envelope req, int ack) {
        String remote = req.params().get("chatId").asString();
        ObjectNode m = MAPPER.createObjectNode();
        m.set("id", MAPPER.createObjectNode().put("_serialized", "true_" + remote + "_" + SERIAL.incrementAndGet())
                .put("fromMe", true).put("remote", remote));
        m.put("fromMe", true).put("body", req.params().get("content").asString()).put("type", "chat")
                .put("ack", ack).put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    /** Runs before each lookup is answered. */
    void onLookup(Runnable r) {
        onLookup = phone -> r.run();
    }

    /** Runs with the looked-up phone before each lookup is answered. */
    void onLookup(Consumer<String> c) {
        onLookup = c;
    }

    List<Envelope> requests() {
        return List.copyOf(requests);
    }

    List<Envelope> requests(String method) {
        return requests.stream().filter(e -> method.equals(e.method())).toList();
    }

    private void answerAll() {
        while (!closed) {
            try {
                Optional<Envelope> frame = device.poll(Duration.ofMillis(100));
                if (frame.isEmpty() || !Envelope.REQUEST.equals(frame.get().type())) continue;
                Envelope req = frame.get();
                requests.add(req);
                device.send(answer(req));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!closed) e.printStackTrace();
            }
        }
    }

    private Envelope answer(Envelope req) {
        if ("getNumberId".equals(req.method())) {
            String phone = req.params().get("phone").asString();
            onLookup.accept(phone);
            try {
                JsonNode result = lookupAnswer.apply(phone);
                return Envelope.response(req.id(), result == null ? MAPPER.nullNode() : result);
            } catch (RuntimeException e) {
                return Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom");
            }
        }
        if ("sendMessage".equals(req.method())) return sendAnswer.apply(req);
        return Envelope.errorResponse(req.id(), "METHOD_NOT_FOUND", req.method());
    }

    @Override
    public void close() throws Exception {
        closed = true;
        answering.join(2000);
        device.close();
    }
}
