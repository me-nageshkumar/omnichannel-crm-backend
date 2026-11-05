package me.nageshk.omnichannel.db.channel;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One converted webhook event, kept for the retention period. The {@code creationTime_ttl} index is created by the
 * store (its expiry comes from a property), not by an annotation.
 */
@Document("channelWebhookEvents")
@CompoundIndex(name = "account_time", def = "{'channelAccountId': 1, 'creationTime': -1}")
@Getter
@Setter
public class ChannelWebhookEvent {

    @Id
    private String id;
    private ChannelKind channelKind;
    private String channelAccountId;
    private String workspaceId;
    private String customerId;
    /** The first change's field; null when the event has none. */
    private String field;
    /** The webhook JSON (snake_case keys); null when conversion failed and only the raw is kept. */
    private Map<String, Object> event;
    /** WhatsApp Web's own model; null when not kept or over the size cap. */
    private Map<String, Object> raw;
    /** True when a raw existed but was too big to keep. */
    private boolean rawDropped;
    private Long seq;
    private Instant capturedAt;
    @CreatedDate
    private Instant creationTime;
}
