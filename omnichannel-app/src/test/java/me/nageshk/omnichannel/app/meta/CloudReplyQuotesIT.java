package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A Cloud reply to our template keeps the template as its quote (spec 2026-11-21 reply quotes). */
class CloudReplyQuotesIT extends MetaTestBase {

    static final String HELLO = "{\"id\":\"t9\",\"name\":\"hello_world\",\"language\":\"en_US\",\"status\":\"APPROVED\","
            + "\"category\":\"UTILITY\",\"components\":[{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"Hello World\"},"
            + "{\"type\":\"BODY\",\"text\":\"Welcome and congratulations!! This message demonstrates your ability.\"}]}";

    private String key;
    private String numberId;
    private WaWebChat chat;

    @BeforeEach
    void cloudChat() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        key = keyOf(connection);
        numberId = addCloudNumber(connection.get("id").asString(), "Meta test", "333", "+1 555 183 3836")
                .get("id").asString();
        webhook(key, envelope(textChange("333", "15551833836", "919928088743", "wamid.HI", "Hi")));
        await(() -> !messages().isEmpty());
        chat = chats.findById(messages().get(0).getChatId()).orElseThrow();
        GRAPH.reset();
    }

    private List<WaWebMessage> messages() {
        return mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class);
    }

    private WaWebMessage stored(String body) {
        return mongo.findOne(new Query(Criteria.where("phoneNumberId").is(numberId).and("body").is(body)),
                WaWebMessage.class);
    }

    private void replyQuoting(String wamid, String quotedId, String body) {
        webhook(key, envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"display_phone_number\":\"15551833836\",\"phone_number_id\":\"333\"},\"contacts\":[{\"profile\":"
                + "{\"name\":\"Nagesh Kumar\"},\"wa_id\":\"919928088743\"}],\"messages\":[{\"from\":\"919928088743\","
                + "\"id\":\"" + wamid + "\",\"timestamp\":\"" + Instant.now().getEpochSecond() + "\",\"type\":\"text\","
                + "\"context\":{\"from\":\"15551833836\",\"id\":\"" + quotedId + "\"},\"text\":{\"body\":\"" + body
                + "\"}}]}}"));
    }

    @Test
    void replyQuotesOurTemplate() throws Exception {
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + HELLO + "]}");
        meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        String templateId = meta(HttpMethod.GET, "/templates", manager, null).getBody().get(0).get("id").asString();
        GRAPH.enqueue("POST /333/messages", 200, "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.T1\"}]}");
        ResponseEntity<JsonNode> sent = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/"
                + chat.getId() + "/cloud-templates"), HttpMethod.POST, auth(manager, Map.of("cloudTemplateId",
                        templateId, "values", Map.of())), JsonNode.class);
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.OK);

        replyQuoting("wamid.OK", "wamid.T1", "Ok");
        await(() -> stored("Ok") != null);

        WaWebMessage template = mongo.findOne(new Query(Criteria.where("waMessageId").is("wamid.T1")),
                WaWebMessage.class);
        WaWebMessage.Quoted q = stored("Ok").getQuoted();
        assertThat(q.messageId()).isEqualTo(template.getId());
        assertThat(q.fromMe()).isTrue();
        assertThat(q.title()).isEqualTo("Hello World");
        assertThat(q.text()).startsWith("Welcome and congratulations");

        JsonNode items = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId()
                + "/thread"), HttpMethod.GET, auth(manager, null), JsonNode.class).getBody().get("items");
        JsonNode ok = null;
        for (JsonNode i : items) if ("Ok".equals(i.at("/message/body").asString())) ok = i.get("message");
        assertThat(ok.at("/quoted/title").asString()).isEqualTo("Hello World");
        assertThat(ok.at("/quoted/fromMe").asBoolean()).isTrue();
    }

    @Test
    void unknownCloudQuoteKeepsOnlyTheId() throws Exception {
        replyQuoting("wamid.OK2", "wamid.UNKNOWN", "Ok");
        await(() -> stored("Ok") != null);
        WaWebMessage.Quoted q = stored("Ok").getQuoted();
        assertThat(q.waMessageId()).isEqualTo("wamid.UNKNOWN");
        assertThat(q.messageId()).isNull();
        assertThat(q.text()).isNull();
        assertThat(q.fromMe()).isTrue();
    }
}
