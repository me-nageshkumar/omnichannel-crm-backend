package me.nageshk.omnichannel.service.inbox.history;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.service.rpc.RpcDispatcher;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Typed wrapper over the extension's {@code listChats} and {@code fetchChatHistory} page methods. */
@Component
@RequiredArgsConstructor
public class HistoryDeviceClient {

    public record ChatRow(String id, String name, Long t, int unreadCount) {}

    public record FetchRequest(String chatId, Long before, List<String> excludeIds, Long from, Long to, int limit,
            int maxLoads) {}

    /** {@code loaded}: the page brought older messages into WhatsApp Web, whether or not any were kept. */
    /*
     * unreadCount (on the phone) and business tell whether the chat waits on us; null from a page script older than
     * them.
     */
    public record HistoryPage(List<JsonNode> messages, Long oldestT, List<String> oldestIds, boolean hasMore,
            boolean loaded, Integer unreadCount, Boolean business) {}

    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;

    public CompletableFuture<List<ChatRow>> listChats(DeviceSession device) {
        return dispatcher.call(device, "listChats", mapper.createObjectNode()).thenApply(HistoryDeviceClient::chatRows);
    }

    public CompletableFuture<HistoryPage> fetchChatHistory(DeviceSession device, FetchRequest req) {
        return dispatcher.call(device, "fetchChatHistory", params(req)).thenApply(HistoryDeviceClient::page);
    }

    static ObjectNode params(FetchRequest req) {
        ObjectNode p = JsonNodeFactory.instance.objectNode().put("chatId", req.chatId());
        if (req.before() != null) p.put("before", req.before());
        ArrayNode ids = p.putArray("excludeIds");
        if (req.excludeIds() != null) req.excludeIds().forEach(ids::add);
        if (req.from() != null) p.put("from", req.from());
        if (req.to() != null) p.put("to", req.to());
        p.put("limit", req.limit()).put("maxLoads", req.maxLoads());
        return p;
    }

    static List<ChatRow> chatRows(JsonNode result) {
        List<ChatRow> rows = new ArrayList<>();
        if (result == null || !result.isArray()) return rows;
        for (JsonNode n : result) {
            rows.add(new ChatRow(text(n, "id"), text(n, "name"), longOrNull(n, "t"), n.path("unreadCount").asInt(0)));
        }
        return rows;
    }

    static HistoryPage page(JsonNode result) {
        List<JsonNode> messages = new ArrayList<>();
        JsonNode raw = result == null ? null : result.get("messages");
        if (raw != null && raw.isArray()) raw.forEach(messages::add);
        Long oldestT = null;
        List<String> oldestIds = new ArrayList<>();
        JsonNode oldest = result == null ? null : result.get("oldest");
        if (oldest != null && oldest.isObject()) {
            oldestT = longOrNull(oldest, "t");
            JsonNode ids = oldest.get("ids");
            // a lost id is left out: as "" it would look like a real id to exclude
            if (ids != null && ids.isArray()) {
                ids.forEach(i -> {
                    if (i.isString() && !i.asString().isEmpty()) oldestIds.add(i.asString());
                });
            }
        }
        JsonNode unread = result == null ? null : result.get("unreadCount");
        JsonNode business = result == null ? null : result.get("business");
        return new HistoryPage(messages, oldestT, oldestIds, result != null && result.path("hasMore").asBoolean(false),
                result != null && result.path("loaded").asBoolean(false),
                unread != null && unread.isNumber() ? unread.asInt() : null,
                business != null && business.isBoolean() ? business.asBoolean() : null);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Long longOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isNumber() ? null : v.asLong();
    }
}
