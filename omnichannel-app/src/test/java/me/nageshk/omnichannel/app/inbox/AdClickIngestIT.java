package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.history.FetchedMessageImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
class AdClickIngestIT {

    private static final String WS = "ws-1";
    private static final String CUSTOMER = "cust-1";
    private static final String REMOTE = "919911111111@c.us";
    private static final long T = 1_700_000_000L;

    @Autowired ApplicationEventPublisher publisher;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired FetchedMessageImporter importer;
    @Autowired MongoTemplate mongo;
    @Autowired TestUsers testUsers;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, Contact.class))
            mongo.remove(new Query(), c);
    }

    private ObjectNode message(String remote, boolean fromMe, long epochSeconds) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", fromMe);
        id.put("remote", remote);
        id.put("id", "ABC" + epochSeconds);
        p.put("fromMe", fromMe);
        p.put("timestamp", epochSeconds);
        p.put("body", "hi");
        return p;
    }

    private ObjectNode withAd(ObjectNode p, String sourceId, String title) {
        p.putObject("ctwaContext").put("sourceId", sourceId).put("sourceUrl", "https://fb.me/" + sourceId)
                .put("title", title).put("thumbnail", "AAAA");
        return p;
    }

    private void create(JsonNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create", payload, null, null));
    }

    private WaWebChat onlyChat() {
        assertThat(chats.count()).isEqualTo(1);
        return chats.findAll().getFirst();
    }

    private WaWebMessage message(String waId) {
        return messages.findAll().stream().filter(m -> m.getWaMessageId().endsWith(waId)).findFirst().orElseThrow();
    }

    @Test
    void adMessageMarksMessageAndChat() {
        create(withAd(message(REMOTE, false, T), "1200", "Diwali sale"));

        WaWebMessage stored = messages.findAll().getFirst();
        assertThat(stored.getAdClick().headline()).isEqualTo("Diwali sale");
        assertThat(stored.getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(stored.getAdClick().raw()).doesNotContainKey("thumbnail");
        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("1200");
        assertThat(onlyChat().getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
    }

    @Test
    void chatKeepsItsFirstAd() {
        create(withAd(message(REMOTE, false, T), "1200", "First"));
        create(withAd(message(REMOTE, false, T + 60), "9999", "Second"));

        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("1200");
        assertThat(message("ABC" + (T + 60)).getAdClick().sourceId()).isEqualTo("9999");
    }

    @Test
    void plainMessageHasNoAd() {
        create(message(REMOTE, false, T));

        assertThat(messages.findAll().getFirst().getAdClick()).isNull();
        assertThat(onlyChat().getAdClick()).isNull();
    }

    @Test
    void historyImportSetsTheChatAd() {
        ObjectNode p = withAd(message(REMOTE, false, T), "1200", "Diwali sale");
        p.putObject("id").put("_serialized", "false_" + REMOTE + "_H1").put("remote", REMOTE).put("fromMe", false);
        p.put("t", T);

        importer.importMessage(new ImportContext(CUSTOMER, WS, "pn-1"), p);

        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("1200");
        assertThat(onlyChat().getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(messages.findAll().getFirst().getAdClick().headline()).isEqualTo("Diwali sale");
    }

    private ObjectNode historyAd(String waId, long t, String sourceId) {
        ObjectNode p = withAd(message(REMOTE, false, t), sourceId, "Ad " + sourceId);
        p.putObject("id").put("_serialized", "false_" + REMOTE + "_" + waId).put("remote", REMOTE).put("fromMe", false);
        p.put("t", t);
        return p;
    }

    @Test
    void importOfAnOlderAdReplacesTheChatAd() {
        ImportContext ctx = new ImportContext(CUSTOMER, WS, "pn-1");
        importer.importMessage(ctx, historyAd("NEW", T + 600, "2000"));
        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("2000");

        importer.importMessage(ctx, historyAd("OLD", T, "1000"));

        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("1000");
        assertThat(onlyChat().getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(message("OLD").getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(message("NEW").getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T + 600));
    }

    @Test
    void laterAdNeverReplacesTheFirst() {
        create(withAd(message(REMOTE, false, T), "1200", "First"));
        create(withAd(message(REMOTE, false, T + 600), "9999", "Later"));

        assertThat(onlyChat().getAdClick().sourceId()).isEqualTo("1200");
        assertThat(onlyChat().getAdClick().at()).isEqualTo(Instant.ofEpochSecond(T));
    }

    @Test
    void outboundContextIsIgnored() {
        create(withAd(message(REMOTE, true, T), "1200", "Mine"));

        assertThat(messages.findAll().getFirst().getAdClick()).isNull();
        assertThat(onlyChat().getAdClick()).isNull();
    }
}
