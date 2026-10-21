package me.nageshk.omnichannel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * How a sender is doing: a run of sends nobody received pauses it, a low reply rate halves it, and lookups failing in
 * a row pause it. Only the sends after the sender's {@code healthFrom} count, so a resume is not undone by the sends
 * that caused the pause.
 */
@Component
class SenderHealth {

    static final int UNDELIVERED_RUN = 5;
    static final int REPLY_WINDOW = 30;
    static final int MIN_REPLIES = 3;
    static final String UNDELIVERED_REASON = "UNDELIVERED_RUN";
    static final int LOOKUP_FAILURES = 3;
    static final String LOOKUP_FAILED_REASON = "LOOKUP_FAILED";

    private static final Duration UNDELIVERED_AGE = Duration.ofMinutes(10);
    private static final Duration REPLY_AGE = Duration.ofHours(24);
    private static final List<RecipientStatus> SENT_STATES =
            List.of(RecipientStatus.SENT, RecipientStatus.DELIVERED, RecipientStatus.READ);

    private final MongoTemplate mongo;

    SenderHealth(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Pauses the sender until the end of the day when its last five sends that are 10 minutes old are all still SENT. */
    void afterSend(String phoneNumberId, CampaignSettings settings, Instant now) {
        List<CampaignRecipient> latest = latest(phoneNumberId, now.minus(UNDELIVERED_AGE), UNDELIVERED_RUN);
        if (latest.size() < UNDELIVERED_RUN
                || latest.stream().anyMatch(r -> r.getStatus() != RecipientStatus.SENT)) return;
        pauseForTheDay(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)), UNDELIVERED_REASON, settings,
                now);
    }

    /** Counts a failed lookup; the third in a row pauses the sender until the end of the day and starts the count over. */
    void lookupFailed(String phoneNumberId, CampaignSettings settings, Instant now) {
        CampaignSender s = mongo.findAndModify(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)),
                new Update().inc("lookupFailures", 1), FindAndModifyOptions.options().returnNew(true),
                CampaignSender.class);
        if (s == null || s.getLookupFailures() < LOOKUP_FAILURES) return;
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)
                .and("lookupFailures").gte(LOOKUP_FAILURES)), new Update().set("lookupFailures", 0),
                CampaignSender.class);
        pauseForTheDay(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)), LOOKUP_FAILED_REASON, settings,
                now);
    }

    /** A lookup the device answered ends a run of failures. */
    void lookupSucceeded(String phoneNumberId) {
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId).and("lookupFailures").ne(0)),
                new Update().set("lookupFailures", 0), CampaignSender.class);
    }

    /** Pauses the sender until the end of the day unless a pause is already on. */
    private void pauseForTheDay(Query sender, String reason, CampaignSettings settings, Instant now) {
        Criteria idle = new Criteria().orOperator(Criteria.where("pausedUntil").is(null),
                Criteria.where("pausedUntil").lte(now));
        mongo.updateFirst(sender.addCriteria(idle), new Update().set("pausedUntil", SenderPolicy.endOfDay(settings, now))
                .set("pauseReason", reason), CampaignSender.class);
    }

    /** Whether fewer than 3 of the last 30 sends that are a day old were replied to; null while there are fewer than 30. */
    Boolean lowReplyRate(String phoneNumberId, Instant now) {
        List<CampaignRecipient> latest = latest(phoneNumberId, now.minus(REPLY_AGE), REPLY_WINDOW);
        if (latest.size() < REPLY_WINDOW) return null;
        return latest.stream().filter(r -> r.getRepliedAt() != null).count() < MIN_REPLIES;
    }

    /** The latest {@code count} sends sent by {@code sentBy}, of those after the sender's {@code healthFrom}. */
    private List<CampaignRecipient> latest(String phoneNumberId, Instant sentBy, int count) {
        Criteria sentAt = Criteria.where("sentAt").lte(sentBy);
        Instant from = healthFrom(phoneNumberId);
        if (from != null) sentAt.gt(from);
        return mongo.find(new Query(Criteria.where("senderId").is(phoneNumberId).and("status").in(SENT_STATES)
                .andOperator(sentAt)).with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(count),
                CampaignRecipient.class);
    }

    private Instant healthFrom(String phoneNumberId) {
        Query q = new Query(Criteria.where("phoneNumberId").is(phoneNumberId));
        q.fields().include("healthFrom");
        CampaignSender s = mongo.findOne(q, CampaignSender.class);
        return s == null ? null : s.getHealthFrom();
    }
}
