package me.nageshk.omnichannel.db.channel;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One converted webhook event, kept for the retention period. The {@code creationTime_ttl} index is created by the
 * store (its expiry comes from a property), not by an annotation. The id is made from the event's own time (the
 * message's or status's timestamp), so {@code _id} order is conversation order; {@code creationTime} is when it was
 * stored.
 */
@Document("channelWebhookEvents")
@CompoundIndex(name = "number_order", def = "{'event.entry.changes.value.metadata.phone_number_id': 1, '_id': -1}")
@Getter
@Setter
public class ChannelWebhookEvent {

    @Id
    private String id;
    /**
     * What the event is about, so a replayed or refetched event is stored once: {@code message:<id>},
     * {@code history:<id>}, {@code status:<id>:<status>} or {@code vote:<id>:<timestamp>}; null on a shell.
     */
    @Indexed(unique = true, sparse = true, name = "eventKey_unique")
    private String eventKey;
    /** The webhook JSON (snake_case keys); says its own origin, workspace and number. */
    private Map<String, Object> event;
    /**
     * WhatsApp Web's own model; null when not kept, over the size cap, or on a later document ({@code ordinal} 1 or
     * more) of the same bridge event, whose raw is on the first.
     */
    private Map<String, Object> raw;
    /** True when a raw existed but was too big to keep. */
    private boolean rawDropped;
    private Long seq;
    /**
     * The 0-based position of this event among those one bridge event gave (an echo is 0, its status 1), so the
     * documents of one {@code seq} keep their order however they are sorted. A Cloud webhook is always 0.
     */
    private int ordinal;
    private Instant capturedAt;
    @CreatedDate
    private Instant creationTime;

    /**
     * Set on a Cloud API event until it has been processed (spec 2026-11-20 §5.3): a database failure or a restart
     * between storing and processing leaves it set, and the replay sweep processes it again. Null otherwise.
     */
    @org.springframework.data.mongodb.core.index.Indexed(name = "pendingSince_sparse", sparse = true)
    private Instant pendingSince;
    /** When the replay sweep last took the event (a lease, so two sweeps never process it together). */
    private Instant sweptAt;
    /** The number a Cloud event belongs to, for the replay (Cloud events only). */
    private CloudTarget cloud;

    public record CloudTarget(String customerId, String workspaceId, String phoneNumberId, String displayDigits) {}
}
