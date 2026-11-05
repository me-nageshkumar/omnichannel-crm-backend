package me.nageshk.omnichannel.service.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEvent;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Keeps every converted webhook event, with its raw model when small enough, until the retention period ends. */
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

    /** Saves the event; returns the saved document, or null after a logged failure. Never throws. */
    public ChannelWebhookEvent save(ChannelContext ctx, WebhookEvent event, JsonNode raw, Long seq, Instant capturedAt) {
        try {
            ChannelWebhookEvent doc = new ChannelWebhookEvent();
            doc.setChannelKind(ctx.kind());
            doc.setChannelAccountId(ctx.channelAccountId());
            doc.setWorkspaceId(ctx.workspaceId());
            doc.setCustomerId(ctx.customerId());
            doc.setSeq(seq);
            doc.setCapturedAt(capturedAt);
            if (event != null) {
                doc.setField(firstField(event));
                doc.setEvent(mapper.convertValue(event, MAP));
            }
            if (raw != null && !raw.isNull()) {
                JsonNode kept = raw.deepCopy();
                MongoKeys.dropRefused(kept);
                if (props.keepRaw() && mapper.writeValueAsBytes(kept).length <= props.rawMaxBytes()) {
                    doc.setRaw(mapper.convertValue(kept, MAP));
                } else {
                    doc.setRawDropped(true);
                }
            }
            return mongo.save(doc);
        } catch (RuntimeException e) {
            log.warn("Could not store the webhook event of {}: {}", ctx.channelAccountId(), e.toString());
            return null;
        }
    }

    private static String firstField(WebhookEvent event) {
        if (event.entry() == null || event.entry().isEmpty()) return null;
        var changes = event.entry().getFirst().changes();
        return changes == null || changes.isEmpty() ? null : changes.getFirst().field();
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
