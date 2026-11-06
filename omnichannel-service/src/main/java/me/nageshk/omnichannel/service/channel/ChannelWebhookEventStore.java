package me.nageshk.omnichannel.service.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEvent;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps every converted webhook event, with its raw model when small enough, until the retention period ends. An event
 * is stored once: its {@link WebhookEventKeys key} is unique, so a replayed or refetched one inserts nothing. Its id is
 * made from its own time, so {@code _id} order is conversation order.
 */
@Slf4j
@Component
public class ChannelWebhookEventStore implements SmartInitializingSingleton {

    static final String TTL_INDEX = "creationTime_ttl";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final OmnichannelProperties.Webhooks props;

    public ChannelWebhookEventStore(MongoTemplate mongo, ObjectMapper mapper, OmnichannelProperties props) {
        this.mongo = mongo;
        this.mapper = mapper;
        this.props = props.webhooks();
    }

    /** A live event: {@link #save(ChannelContext, WebhookEvent, JsonNode, Long, Instant, int, boolean)}, not imported. */
    public ChannelWebhookEvent save(ChannelContext ctx, WebhookEvent event, JsonNode raw, Long seq, Instant capturedAt,
            int ordinal) {
        return save(ctx, event, raw, seq, capturedAt, ordinal, false);
    }

    /**
     * Saves the event, the {@code ordinal}-th (0-based) of its bridge event; the raw is kept on the first only, as it
     * is one payload. {@code imported} is true for a message the history import fetched. Returns the saved document,
     * the one already stored under the same key, or null after a logged failure. Never throws.
     */
    public ChannelWebhookEvent save(ChannelContext ctx, WebhookEvent event, JsonNode raw, Long seq, Instant capturedAt,
            int ordinal, boolean imported) {
        String key = WebhookEventKeys.of(event, imported);
        try {
            ChannelWebhookEvent doc = new ChannelWebhookEvent();
            doc.setId(new ObjectId(Date.from(eventTime(event, capturedAt))).toHexString());
            doc.setEventKey(key);
            doc.setSeq(seq);
            doc.setCapturedAt(capturedAt);
            doc.setOrdinal(ordinal);
            // auditing sees a set id as an existing document and would leave the creation time (the expiry) unset
            doc.setCreationTime(Instant.now());
            // An unconvertible raw gets an event shell so the document is still found by number.
            WebhookEvent toStore = event != null
                    ? event
                    : WebhookEvent.of(ctx, List.of(new Change(null, Value.of(ctx))));
            doc.setEvent(mapper.convertValue(toStore, MAP));
            if (ordinal == 0 && raw != null && !raw.isNull()) {
                JsonNode kept = raw.deepCopy();
                MongoKeys.dropRefused(kept);
                if (props.keepRaw() && mapper.writeValueAsBytes(kept).length <= props.rawMaxBytes()) {
                    doc.setRaw(mapper.convertValue(kept, MAP));
                } else {
                    doc.setRawDropped(true);
                }
            }
            return mongo.insert(doc);
        } catch (DuplicateKeyException e) {
            log.debug("webhook event {} of {} is already stored", key, ctx.channelAccountId());
            return existing(key);
        } catch (RuntimeException e) {
            log.warn("Could not store the webhook event of {}: {}", ctx.channelAccountId(), e.toString());
            return null;
        }
    }

    /** The message's or status's own time, else the capture time, else now. */
    private static Instant eventTime(WebhookEvent event, Instant capturedAt) {
        Long seconds = WebhookEventKeys.secondsOf(event);
        if (seconds != null) return Instant.ofEpochSecond(seconds);
        return capturedAt != null ? capturedAt : Instant.now();
    }

    private ChannelWebhookEvent existing(String key) {
        if (key == null) return null;
        try {
            return mongo.findOne(new Query(Criteria.where("eventKey").is(key)), ChannelWebhookEvent.class);
        } catch (RuntimeException e) {
            log.warn("Could not read the stored webhook event {}: {}", key, e.toString());
            return null;
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            ensureTtlIndex();
        } catch (RuntimeException e) {
            log.warn("Could not set up the {} index: {}", TTL_INDEX, e.toString());
        }
    }

    /** Creates the expiry index from the retention property; replaces one that expires after another period. */
    void ensureTtlIndex() {
        Duration retention = props.retention();
        var ops = mongo.indexOps(ChannelWebhookEvent.class);
        for (IndexInfo info : ops.getIndexInfo()) {
            boolean onTime = info.getIndexFields().size() == 1
                    && info.getIndexFields().getFirst().getKey().equals("creationTime");
            if (!onTime || info.getExpireAfter().isEmpty()) continue;
            if (info.getExpireAfter().get().equals(retention)) return;
            ops.dropIndex(info.getName());
        }
        ops.createIndex(new Index().on("creationTime", Sort.Direction.ASC).expire(retention).named(TTL_INDEX));
    }
}
