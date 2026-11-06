package me.nageshk.omnichannel.app.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEvent;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEventRepository;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.inbox.InboxCleanup;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = "omnichannel.webhooks.raw-max-bytes=200")
class ChannelWebhookEventStoreIT {

    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired ChannelWebhookEventStore store;
    @Autowired ChannelWebhookEventRepository repo;
    @Autowired InboxCleanup cleanup;
    @Autowired OmnichannelProperties props;

    @BeforeEach
    @AfterEach
    void clean() {
        mongo.remove(new Query(), ChannelWebhookEvent.class);
    }

    private static ChannelContext ctx(String account) {
        return new ChannelContext(ChannelKind.WHATSAPP_WEB, "c1", "w1", account, "919928088743");
    }

    private static WebhookEvent event(ChannelContext ctx) {
        return WebhookEvent.of(ctx, List.of(new Change("messages", Value.of(ctx))));
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    @Test
    void savesTheEventAndTheRaw() {
        ChannelContext c = ctx("p1");
        Instant at = Instant.parse("2026-11-05T10:00:00Z");
        ChannelWebhookEvent saved = store.save(c, event(c), json("{\"id\":\"x\",\"body\":\"hi\"}"), 7L, at, 0);

        ChannelWebhookEvent found = repo.findById(saved.getId()).orElseThrow();
        assertThat(found.getSeq()).isEqualTo(7L);
        assertThat(found.getCapturedAt()).isEqualTo(at);
        assertThat(found.getCreationTime()).isNotNull();
        assertThat(found.isRawDropped()).isFalse();
        assertThat(found.getRaw()).containsEntry("body", "hi");
        assertThat(found.getEvent()).containsEntry("object", "whatsapp_web_account");
        assertThat((List<?>) found.getEvent().get("entry")).hasSize(1);
    }

    @Test
    void theSecondEventOfABridgeEventCarriesNoRaw() {
        ChannelContext c = ctx("p1");
        Instant at = Instant.parse("2026-11-05T10:00:00Z");
        JsonNode raw = json("{\"id\":\"x\",\"body\":\"hi\"}");
        ChannelWebhookEvent first = store.save(c, event(c), raw, 7L, at, 0);
        ChannelWebhookEvent second = store.save(c, event(c), raw, 7L, at, 1);

        ChannelWebhookEvent a = repo.findById(first.getId()).orElseThrow();
        ChannelWebhookEvent b = repo.findById(second.getId()).orElseThrow();
        assertThat(a.getOrdinal()).isZero();
        assertThat(a.getRaw()).containsEntry("body", "hi");
        assertThat(b.getOrdinal()).isEqualTo(1);
        assertThat(b.getSeq()).isEqualTo(7L);
        assertThat(b.getCapturedAt()).isEqualTo(at);
        assertThat(b.getRaw()).isNull();
        assertThat(b.isRawDropped()).isFalse();
        assertThat(b.getEvent()).isNotNull();
        Document d = mongo.findById(second.getId(), Document.class, "channelWebhookEvents");
        assertThat(d.getInteger("ordinal")).isEqualTo(1);
    }

    @Test
    void dropsARawOverTheCap() {
        ChannelContext c = ctx("p1");
        ChannelWebhookEvent saved =
                store.save(c, event(c), json("{\"blob\":\"" + "x".repeat(1024) + "\"}"), 1L, null, 0);

        ChannelWebhookEvent found = repo.findById(saved.getId()).orElseThrow();
        assertThat(found.getRaw()).isNull();
        assertThat(found.isRawDropped()).isTrue();
        assertThat(found.getEvent()).isNotNull();
    }

    @Test
    void removesKeysMongoRefuses() {
        ChannelContext c = ctx("p1");
        ChannelWebhookEvent saved = store.save(c, event(c),
                json("{\"a.b\":1,\"$c\":2,\"ok\":3,\"n\":{\"x.y\":1,\"keep\":2}}"), null, null, 0);

        Map<String, Object> raw = repo.findById(saved.getId()).orElseThrow().getRaw();
        assertThat(raw).containsOnlyKeys("ok", "n");
        assertThat(((Map<?, ?>) raw.get("n")).keySet().toArray()).containsExactly("keep");
    }

    @Test
    void createsTheTtlIndexFromTheProperty() {
        assertThat(props.webhooks().retention()).isEqualTo(Duration.ofDays(30));
        var ttl = mongo.indexOps(ChannelWebhookEvent.class).getIndexInfo().stream()
                .filter(i -> i.getIndexFields().stream().anyMatch(f -> f.getKey().equals("creationTime")
                        && i.getExpireAfter().isPresent()))
                .toList();
        assertThat(ttl).hasSize(1);
        assertThat(ttl.getFirst().getExpireAfter()).contains(Duration.ofDays(30));
    }

    @Test
    void aWriteFailureIsLoggedNotThrown() {
        MongoTemplate broken = mock(MongoTemplate.class);
        when(broken.save(any(ChannelWebhookEvent.class))).thenThrow(new DataAccessResourceFailureException("down"));
        ChannelWebhookEventStore s = new ChannelWebhookEventStore(broken, mapper, props);

        ChannelContext c = ctx("p1");
        assertThat(s.save(c, event(c), json("{}"), 1L, null, 0)).isNull();
    }

    @Test
    void cleanupDeletesTheNumbersEvents() {
        ChannelContext a = ctx("p1");
        ChannelContext b = ctx("p2");
        store.save(a, event(a), json("{}"), 1L, null, 0);
        store.save(a, event(a), json("{}"), 2L, null, 0);
        store.save(b, event(b), json("{}"), 1L, null, 0);

        cleanup.deleteForNumber("p1");

        assertThat(repo.findAll()).hasSize(1);
        assertThat(mongo.findAll(Document.class, "channelWebhookEvents").getFirst()
                .get("event", Document.class).getList("entry", Document.class).getFirst()
                .getList("changes", Document.class).getFirst().get("value", Document.class)
                .get("metadata", Document.class).getString("phone_number_id")).isEqualTo("p2");
    }

    @Test
    void theDocumentHoldsTheEventAndNothingElseAboutTheChannel() {
        ChannelContext c = ctx("p1");
        store.save(c, event(c), json("{}"), 1L, null, 0);

        Document d = mongo.findAll(Document.class, "channelWebhookEvents").getFirst();
        assertThat(d).doesNotContainKeys("channelKind", "channelAccountId", "workspaceId", "customerId", "field");
        assertThat(d.get("event", Document.class).getList("entry", Document.class).getFirst().getString("id"))
                .isEqualTo("w1");
    }

    @Test
    void anUnconvertibleRawIsStillFoundByNumber() {
        ChannelContext c = ctx("p1");
        store.save(c, null, json("{\"body\":\"hi\"}"), 1L, null, 0);

        Document d = mongo.findAll(Document.class, "channelWebhookEvents").getFirst();
        Document ev = d.get("event", Document.class);
        assertThat(ev.getString("object")).isEqualTo("whatsapp_web_account");
        assertThat(ev.getList("entry", Document.class).getFirst().getList("changes", Document.class).getFirst()
                .get("value", Document.class).get("metadata", Document.class).getString("phone_number_id"))
                .isEqualTo("p1");
        assertThat(d.get("raw", Document.class).getString("body")).isEqualTo("hi");
        cleanup.deleteForNumber("p1");
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void theNumberIndexExists() {
        assertThat(mongo.indexOps(ChannelWebhookEvent.class).getIndexInfo()).anyMatch(i ->
                i.getIndexFields().stream().map(f -> f.getKey()).toList()
                        .equals(List.of("event.entry.changes.value.metadata.phone_number_id", "creationTime")));
    }
}
