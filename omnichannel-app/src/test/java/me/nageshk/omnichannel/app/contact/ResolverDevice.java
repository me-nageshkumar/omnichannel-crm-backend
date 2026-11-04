package me.nageshk.omnichannel.app.contact;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.service.rpc.Envelope;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connected device for a number that answers {@code resolveIds} on its own thread from {@link #answers} (an id
 * WhatsApp knows nothing about answers both null), or as an {@link #outdated} extension. Ready unless built with
 * {@code ready = false}. Every request is
 * recorded, with the time it arrived, before it is answered; a closed {@link #gate} holds the answers back.
 */
class ResolverDevice implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A recorded request and when it arrived ({@link System#nanoTime()}). */
    record Request(Envelope envelope, long arrivedNanos) {
        String id() {
            return envelope.params().get("id").asString();
        }
    }

    /** What {@code resolveIds} answers for an id: {@code {phone, lid}}, either null. */
    final Map<String, ObjectNode> answers = new ConcurrentHashMap<>();
    /** An extension older than {@code resolveIds}: it answers {@code METHOD_NOT_FOUND}. */
    volatile boolean outdated;
    /** Released, the device answers; closed, it holds each answer until released. */
    volatile CountDownLatch gate = new CountDownLatch(0);

    private final FakeDevice device;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Thread answering;
    private volatile boolean closed;

    ResolverDevice(int port, TestDevices testDevices, PhoneNumber n, boolean ready) throws Exception {
        device = FakeDevice.connectAndAuth(port, testDevices.redeem(n).deviceKey());
        if (ready) {
            device.send(Envelope.event("ready",
                    MAPPER.createObjectNode().put("pushname", "N").put("wid", n.getNumber() + "@c.us")));
        }
        Thread.sleep(200);
        answering = new Thread(this::answerAll, "resolver-device-" + n.getNumber());
        answering.setDaemon(true);
        answering.start();
    }

    /** The person behind {@code phone} ({@code digits@c.us}) and {@code lid}, under both ids. */
    void knows(String phone, String lid) {
        ObjectNode both = MAPPER.createObjectNode();
        if (phone == null) both.putNull("phone");
        else both.put("phone", phone);
        if (lid == null) both.putNull("lid");
        else both.put("lid", lid);
        if (phone != null) answers.put(phone, both);
        if (lid != null) answers.put(lid, both);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<Request> requests(String method) {
        return requests.stream().filter(r -> method.equals(r.envelope().method())).toList();
    }

    private void answerAll() {
        while (!closed) {
            try {
                Optional<Envelope> frame = device.poll(Duration.ofMillis(100));
                if (frame.isEmpty() || !Envelope.REQUEST.equals(frame.get().type())) continue;
                Envelope req = frame.get();
                requests.add(new Request(req, System.nanoTime()));
                if (!gate.await(10, TimeUnit.SECONDS)) continue;
                device.send(answer(req));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!closed) e.printStackTrace();
            }
        }
    }

    private Envelope answer(Envelope req) {
        if (outdated || !"resolveIds".equals(req.method())) return Envelope.errorResponse(req.id(), "METHOD_NOT_FOUND", req.method());
        ObjectNode known = answers.get(req.params().get("id").asString());
        return Envelope.response(req.id(), known != null ? known
                : MAPPER.createObjectNode().putNull("phone").putNull("lid"));
    }

    @Override
    public void close() throws Exception {
        closed = true;
        answering.join(2000);
        device.close();
    }
}
