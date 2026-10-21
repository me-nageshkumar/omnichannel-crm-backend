package me.nageshk.omnichannel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** How a sender is doing: a run of sends nobody received pauses it, a low reply rate halves it. */
@Component
class SenderHealth {

    static final int UNDELIVERED_RUN = 5;
    static final int REPLY_WINDOW = 30;
    static final int MIN_REPLIES = 3;
    static final String UNDELIVERED_REASON = "UNDELIVERED_RUN";

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
        Criteria idle = new Criteria().orOperator(Criteria.where("pausedUntil").is(null),
                Criteria.where("pausedUntil").lte(now));
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId).andOperator(idle)),
                new Update().set("pausedUntil", SenderPolicy.endOfDay(settings, now))
                        .set("pauseReason", UNDELIVERED_REASON), CampaignSender.class);
    }

    /** Whether fewer than 3 of the last 30 sends that are a day old were replied to; null while there are fewer than 30. */
    Boolean lowReplyRate(String phoneNumberId, Instant now) {
        List<CampaignRecipient> latest = latest(phoneNumberId, now.minus(REPLY_AGE), REPLY_WINDOW);
        if (latest.size() < REPLY_WINDOW) return null;
        return latest.stream().filter(r -> r.getRepliedAt() != null).count() < MIN_REPLIES;
    }

    private List<CampaignRecipient> latest(String phoneNumberId, Instant sentBy, int count) {
        return mongo.find(new Query(Criteria.where("senderId").is(phoneNumberId).and("status").in(SENT_STATES)
                .and("sentAt").lte(sentBy)).with(Sort.by(Sort.Direction.DESC, "sentAt")).limit(count),
                CampaignRecipient.class);
    }
}
