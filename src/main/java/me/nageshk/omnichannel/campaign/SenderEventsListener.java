package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Turns device events into sender state: a logout suspends the sender, slows the others for the day and, on the
 * second logout within a week, pauses the running campaigns; a reconnection starts the rest. It runs on the ingest
 * thread, so it logs and swallows every {@link RuntimeException}.
 */
@Component
public class SenderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(SenderEventsListener.class);
    /** Logged out, or the number was banned by WhatsApp: either way it cannot send until it is linked again. */
    private static final Set<String> LOGGED_OUT = Set.of("UNPAIRED", "UNPAIRED_IDLE", "TOS_BLOCK", "SMB_TOS_BLOCK");
    private static final Duration LOGOUT_WINDOW = Duration.ofDays(7);

    private final CampaignSenderService senders;
    private final CampaignSettingsService settingsService;
    private final MongoTemplate mongo;
    private final Clock clock;

    SenderEventsListener(CampaignSenderService senders, CampaignSettingsService settingsService, MongoTemplate mongo,
            Clock clock) {
        this.senders = senders;
        this.settingsService = settingsService;
        this.mongo = mongo;
        this.clock = clock;
    }

    @EventListener
    public void on(WhatsAppEvent e) {
        try {
            switch (e.name()) {
                case "change_state" -> {
                    String state = e.payload() == null ? null : e.payload().path("state").asString(null);
                    if (state != null && LOGGED_OUT.contains(state)) loggedOut(e);
                }
                case "ready" -> reconnected(e);
                default -> { }
            }
        } catch (RuntimeException ex) {
            log.warn("could not apply {} event of {} to its sender", e.name(), e.phoneNumberId(), ex);
        }
    }

    private void loggedOut(WhatsAppEvent e) {
        String ws = e.workspaceId();
        senders.ensure(ws, e.phoneNumberId());
        Instant now = clock.instant();
        boolean suspended = mongo.updateFirst(
                new Query(Criteria.where("phoneNumberId").is(e.phoneNumberId()).and("state").nin(SenderState.SUSPENDED, SenderState.RETIRED)),
                new Update().set("state", SenderState.SUSPENDED).set("logoutAt", now), CampaignSender.class)
                .getModifiedCount() > 0;
        if (!suspended) return;
        CampaignSettings settings = settingsService.get(ws);
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(ws)
                        .and("phoneNumberId").ne(e.phoneNumberId())
                        .and("state").in(SenderState.WARMING, SenderState.ACTIVE)),
                new Update().set("halfPaceUntil", SenderPolicy.endOfDay(settings, now)), CampaignSender.class);
        long recent = mongo.count(new Query(Criteria.where("workspaceId").is(ws)
                .and("logoutAt").gt(now.minus(LOGOUT_WINDOW))), CampaignSender.class);
        if (recent >= 2) {
            mongo.updateMulti(new Query(Criteria.where("workspaceId").is(ws).and("status").is(CampaignStatus.RUNNING)),
                    new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.SYSTEM)
                            .set("pauseReason", "TWO_LOGOUTS"), Campaign.class);
        }
    }

    private void reconnected(WhatsAppEvent e) {
        Instant now = clock.instant();
        CampaignSettings settings = settingsService.get(e.workspaceId());
        mongo.updateFirst(
                new Query(Criteria.where("phoneNumberId").is(e.phoneNumberId()).and("state").is(SenderState.SUSPENDED)),
                new Update().set("state", SenderState.RESTING)
                        .set("restUntil", now.plus(Duration.ofDays(settings.getRestDays()))), CampaignSender.class);
    }
}
