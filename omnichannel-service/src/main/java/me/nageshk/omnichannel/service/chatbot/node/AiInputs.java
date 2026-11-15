package me.nageshk.omnichannel.service.chatbot.node;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.automation.MessageKinds;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The customer's messages a conversation has not given the AI yet (plan ruling R1): the inbox is the queue, so a message
 * that lands while the AI thinks, or while the run is busy and no listener could hand it over, is still read. A message
 * is unconsumed when it is an inbound real message of the contact stored after the last one the node took (by
 * {@code creationTime}, ties by id) and sent no earlier than the conversation start (by WhatsApp's {@code timestamp},
 * so a history import of old messages is not mistaken for new ones). Served by the {@code chat_created} index.
 */
@Component
public class AiInputs {

    /** Real message types (as the reply listener takes them), plus button and list replies. */
    static final Set<String> TYPES;

    static {
        Set<String> t = new HashSet<>(MessageKinds.REAL_TYPES);
        t.add("button");
        t.add("interactive");
        TYPES = Set.copyOf(t);
    }

    /** WhatsApp's time is whole seconds and may run a little behind ours. */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(1);
    /** At most this many messages go into one turn; the rest are the next turn's. */
    static final int MAX_BATCH = 50;

    private static final Map<String, String> STAND_IN = Map.of("image", "an image", "video", "a video",
            "document", "a document", "audio", "an audio message", "ptt", "a voice note", "sticker", "a sticker");

    private final MongoTemplate mongo;

    public AiInputs(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** As {@link #unconsumed(String, Instant, String, Instant)} with no tie-break id. */
    public List<WaWebMessage> unconsumed(String chatId, Instant consumedUntil, Instant startedAt) {
        return unconsumed(chatId, consumedUntil, null, startedAt);
    }

    /**
     * Oldest first, by {@code creationTime} then id. {@code consumedId} is the last message taken: another message
     * stored in the same millisecond with a greater id still counts as new.
     */
    public List<WaWebMessage> unconsumed(String chatId, Instant consumedUntil, String consumedId, Instant startedAt) {
        Criteria c = Criteria.where("chatId").is(chatId).and("direction").is(MessageDirection.IN.name())
                .and("source").is(MessageSource.CONTACT.name()).and("type").in(TYPES);
        if (startedAt != null) {
            c = c.and("timestamp").gte(startedAt.truncatedTo(ChronoUnit.SECONDS).minus(CLOCK_SLACK));
        }
        if (consumedUntil != null) {
            c = consumedId == null ? c.and("creationTime").gt(consumedUntil)
                    : c.orOperator(Criteria.where("creationTime").gt(consumedUntil),
                            Criteria.where("creationTime").is(consumedUntil).and("_id").gt(consumedId));
        }
        Query q = new Query(c).with(Sort.by(Sort.Order.asc("creationTime"), Sort.Order.asc("_id"))).limit(MAX_BATCH);
        return mongo.find(q, WaWebMessage.class);
    }

    /** The newest message the contact sent in the chat, of any type: everything up to it was there before. */
    public Optional<WaWebMessage> latest(String chatId) {
        Query q = new Query(Criteria.where("chatId").is(chatId).and("direction").is(MessageDirection.IN.name())
                .and("source").is(MessageSource.CONTACT.name()))
                .with(Sort.by(Sort.Order.desc("creationTime"), Sort.Order.desc("_id"))).limit(1);
        return Optional.ofNullable(mongo.findOne(q, WaWebMessage.class));
    }

    /**
     * What the model reads for a message (spec §5.5): the text; a button or list reply's title; a location as
     * {@code [location: <lat>, <lng> <name>]}; media as a stand-in such as {@code [customer sent an image: <caption>]},
     * so the model can ask the customer to type instead.
     */
    public static String text(WaWebMessage m) {
        String type = m.getType() == null ? "" : m.getType();
        String body = m.getBody() == null ? "" : m.getBody().strip();
        if ("location".equals(type)) {
            WaWebMessage.Location l = m.getLocation();
            if (l == null) return "[location]";
            String name = l.description() == null || l.description().isBlank() ? "" : " " + l.description().strip();
            return "[location: " + l.latitude() + ", " + l.longitude() + name + "]";
        }
        String standIn = STAND_IN.get(type);
        if (standIn != null) {
            String caption = body;
            if (caption.isEmpty() && "document".equals(type) && m.getMedia() != null && m.getMedia().filename() != null) {
                caption = m.getMedia().filename();
            }
            return "[customer sent " + standIn + (caption.isEmpty() ? "" : ": " + caption) + "]";
        }
        if ("contacts".equals(type)) return "[customer sent a contact card" + (body.isEmpty() ? "" : ": " + body) + "]";
        if ("poll_creation".equals(type)) return "[customer sent a poll" + (body.isEmpty() ? "" : ": " + body) + "]";
        return body;
    }
}
