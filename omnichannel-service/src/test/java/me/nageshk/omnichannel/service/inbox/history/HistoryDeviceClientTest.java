package me.nageshk.omnichannel.service.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.ChatRow;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.FetchRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.HistoryPage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class HistoryDeviceClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chatRowsParsesAndDefaults() {
        ArrayNode arr = mapper.createArrayNode();
        arr.addObject().put("id", "1@c.us").put("name", "Ann").put("t", 100L).put("unreadCount", 3);
        arr.addObject().put("id", "2@c.us");
        List<ChatRow> rows = HistoryDeviceClient.chatRows(arr);
        assertThat(rows).containsExactly(new ChatRow("1@c.us", "Ann", 100L, 3), new ChatRow("2@c.us", null, null, 0));
    }

    @Test
    void chatRowsOfNonArrayIsEmpty() {
        assertThat(HistoryDeviceClient.chatRows(mapper.createObjectNode())).isEmpty();
        assertThat(HistoryDeviceClient.chatRows(null)).isEmpty();
    }

    @Test
    void pageParsesOldestAndHasMore() {
        ObjectNode r = mapper.createObjectNode();
        r.putArray("messages").addObject().put("id", "a");
        r.withArray("messages").addObject().put("id", "b");
        r.putObject("oldest").put("t", 50L).putArray("ids").add("a").add("b");
        r.put("hasMore", true);
        HistoryPage p = HistoryDeviceClient.page(r);
        assertThat(p.messages()).hasSize(2);
        assertThat(p.messages().get(0).path("id").asString()).isEqualTo("a");
        assertThat(p.oldestT()).isEqualTo(50L);
        assertThat(p.oldestIds()).containsExactly("a", "b");
        assertThat(p.hasMore()).isTrue();
    }

    @Test
    void pageDropsIdsTheExtensionLost() {
        ObjectNode r = mapper.createObjectNode();
        r.putArray("messages");
        r.putObject("oldest").put("t", 50L).putArray("ids").addNull().add("").add("a");
        assertThat(HistoryDeviceClient.page(r).oldestIds()).containsExactly("a");
    }

    @Test
    void pageWithoutOldest() {
        ObjectNode r = mapper.createObjectNode();
        r.putArray("messages");
        r.putNull("oldest");
        HistoryPage p = HistoryDeviceClient.page(r);
        assertThat(p.messages()).isEmpty();
        assertThat(p.oldestT()).isNull();
        assertThat(p.oldestIds()).isEmpty();
        assertThat(p.hasMore()).isFalse();
    }

    @Test
    void paramsOmitNullsAndKeepExcludeIdsArray() {
        ObjectNode p = HistoryDeviceClient.params(new FetchRequest("1@c.us", null, List.of(), null, null, 50, 5));
        assertThat(p.has("before")).isFalse();
        assertThat(p.has("from")).isFalse();
        assertThat(p.has("to")).isFalse();
        assertThat(p.get("excludeIds").isArray()).isTrue();
        assertThat(p.get("excludeIds")).isEmpty();
        assertThat(p.path("chatId").asString()).isEqualTo("1@c.us");
        assertThat(p.path("limit").asInt()).isEqualTo(50);
        assertThat(p.path("maxLoads").asInt()).isEqualTo(5);

        ObjectNode q = HistoryDeviceClient.params(new FetchRequest("c", 9L, List.of("x"), 1L, 2L, 10, 2));
        assertThat(q.path("before").asLong()).isEqualTo(9L);
        assertThat(q.path("from").asLong()).isEqualTo(1L);
        assertThat(q.path("to").asLong()).isEqualTo(2L);
        assertThat(q.get("excludeIds")).hasSize(1);
    }
}
