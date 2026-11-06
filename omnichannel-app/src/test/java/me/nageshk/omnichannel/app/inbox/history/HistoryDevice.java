package me.nageshk.omnichannel.app.inbox.history;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.service.rpc.Envelope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connected, ready fake extension that answers {@code listChats} and {@code fetchChatHistory} from the in-memory
 * {@link #chats} (remote id to messages, any order), and {@code messageInfo} from {@link #receipts}, on its own thread.
 * Every request is recorded before it is answered. {@link #override}, when set, answers every request instead (null
 * from it never answers).
 */
class HistoryDevice implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SERIAL = new AtomicInteger();

    final Map<String, List<ObjectNode>> chats = new ConcurrentHashMap<>();
    /** The name {@code listChats} gives a chat; none when absent. */
    final Map<String, String> names = new ConcurrentHashMap<>();
    /** The unread count on the phone {@code fetchChatHistory} gives a chat; 0 when absent. */
    final Map<String, Integer> unread = new ConcurrentHashMap<>();
    /** The chats {@code fetchChatHistory} says are with a business. */
    final Set<String> business = ConcurrentHashMap.newKeySet();
    /** True to answer like a page script from before {@code unreadCount} and {@code business}. */
    volatile boolean oldPageScript = false;
    /** The receipt times (seconds) {@code messageInfo} gives a message id; all null when absent. */
    final Map<String, Long[]> receipts = new ConcurrentHashMap<>();
    volatile Function<Envelope, Envelope> override = null;

    private final FakeDevice device;
    private final List<Envelope> requests = new CopyOnWriteArrayList<>();
    private final Thread answering;
    private volatile boolean closed;

    HistoryDevice(int port, TestDevices testDevices, PhoneNumber n) throws Exception {
        device = FakeDevice.connectAndAuth(port, testDevices.redeem(n).deviceKey());
        device.send(Envelope.event("ready",
                MAPPER.createObjectNode().put("pushname", "N").put("wid", n.getNumber() + "@c.us")));
        Thread.sleep(200);
        answering = new Thread(this::answerAll, "history-device-" + n.getNumber());
        answering.setDaemon(true);
        answering.start();
    }

    /** A message model the way whatsapp-web.js reports it. */
    static ObjectNode msg(String remote, boolean fromMe, long t, int ack, String body) {
        ObjectNode m = MAPPER.createObjectNode();
        m.set("id", MAPPER.createObjectNode()
                .put("_serialized", (fromMe ? "true_" : "false_") + remote + "_"
                        + Integer.toHexString(SERIAL.incrementAndGet()).toUpperCase())
                .put("fromMe", fromMe).put("remote", remote));
        m.put("fromMe", fromMe).put("body", body).put("type", "chat").put("t", t).put("ack", ack);
        return m;
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
                Function<Envelope, Envelope> o = override;
                Envelope reply = o != null ? o.apply(req) : answer(req);
                if (reply != null) device.send(reply);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!closed) e.printStackTrace();
            }
        }
    }

    /** The answer {@link #chats} gives, for an {@link #override} that answers some requests normally. */
    Envelope answer(Envelope req) {
        return switch (req.method()) {
            case "listChats" -> Envelope.response(req.id(), listChats());
            case "fetchChatHistory" -> fetchChatHistory(req);
            case "messageInfo" -> Envelope.response(req.id(), messageInfo(req));
            case "getState" -> Envelope.response(req.id(), MAPPER.getNodeFactory().textNode("CONNECTED"));
            default -> Envelope.errorResponse(req.id(), "METHOD_NOT_FOUND", req.method());
        };
    }

    /** {@code {receipts: [{id, delivered, read, played}]}} in the order of {@code ids}, as the page answers. */
    private ObjectNode messageInfo(Envelope req) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode out = result.putArray("receipts");
        req.params().path("ids").forEach(i -> {
            String id = i.asString();
            Long[] times = receipts.getOrDefault(id, new Long[3]);
            ObjectNode r = out.addObject().put("id", id);
            String[] names = {"delivered", "read", "played"};
            for (int k = 0; k < 3; k++) {
                if (times[k] == null) r.putNull(names[k]);
                else r.put(names[k], times[k]);
            }
        });
        return result;
    }

    /** Sets the receipts of {@code message}: delivered, read and played seconds, each null when WhatsApp has none. */
    void receipt(ObjectNode message, Long delivered, Long read, Long played) {
        receipts.put(id(message), new Long[] {delivered, read, played});
    }

    private ArrayNode listChats() {
        ArrayNode rows = MAPPER.createArrayNode();
        chats.forEach((id, list) -> {
            ObjectNode row = rows.addObject().put("id", id);
            if (names.containsKey(id)) row.put("name", names.get(id));
            else row.putNull("name");
            Long t = list.stream().map(HistoryDevice::t).max(Long::compare).orElse(null);
            if (t == null) row.putNull("t");
            else row.put("t", t);
            row.put("unreadCount", 0);
        });
        return rows;
    }

    /** Spec section 3 {@code fetchChatHistory} over the in-memory list; everything is loaded, so exhausted is true. */
    private Envelope fetchChatHistory(Envelope req) {
        JsonNode p = req.params();
        String chatId = p.path("chatId").asString();
        List<ObjectNode> all = chats.get(chatId);
        if (all == null) return Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", chatId);
        Long before = p.hasNonNull("before") ? p.get("before").asLong() : null;
        Long from = p.hasNonNull("from") ? p.get("from").asLong() : null;
        Long to = p.hasNonNull("to") ? p.get("to").asLong() : null;
        int limit = p.hasNonNull("limit") ? p.get("limit").asInt() : 100;
        Set<String> exclude = new LinkedHashSet<>();
        p.path("excludeIds").forEach(i -> exclude.add(i.asString()));

        List<ObjectNode> kept = new ArrayList<>();
        for (ObjectNode m : all) {
            long t = t(m);
            if ((before == null || t <= before) && !exclude.contains(id(m))
                    && (to == null || t <= to) && (from == null || t >= from)) kept.add(m);
        }
        kept.sort((a, b) -> Long.compare(t(a), t(b)));
        List<ObjectNode> messages = kept.size() > limit ? kept.subList(kept.size() - limit, kept.size()) : kept;

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode out = result.putArray("messages");
        messages.forEach(out::add);
        if (messages.isEmpty()) {
            result.putNull("oldest");
        } else {
            long oldestT = t(messages.get(0));
            Set<String> ids = new LinkedHashSet<>();
            if (before != null && oldestT == before) ids.addAll(exclude);
            for (ObjectNode m : messages) if (t(m) == oldestT) ids.add(id(m));
            ObjectNode oldest = result.putObject("oldest").put("t", oldestT);
            ArrayNode idArray = oldest.putArray("ids");
            ids.forEach(idArray::add);
        }
        result.put("hasMore", kept.size() > limit);
        if (!oldPageScript) {
            result.put("unreadCount", unread.getOrDefault(chatId, 0));
            result.put("business", business.contains(chatId));
            // everything is already in the browser: WhatsApp is never asked for earlier messages
            result.put("asked", false);
        }
        return Envelope.response(req.id(), result);
    }

    private static long t(ObjectNode m) {
        return m.get("t").asLong();
    }

    static String id(ObjectNode m) {
        return m.get("id").get("_serialized").asString();
    }

    @Override
    public void close() throws Exception {
        closed = true;
        answering.join(2000);
        device.close();
    }
}
