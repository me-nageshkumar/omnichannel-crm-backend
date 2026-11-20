package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Sends the RUNNING campaigns. Each tick, inside the sending window, every eligible sender's work for the tick runs
 * on {@code campaignScheduler} through its kind's {@link CampaignChannel} (spec 2026-11-21 §5.2); a sender still at
 * work from an earlier tick is left alone. The tick also fails recipients stuck in SENDING, skips the ones waiting
 * for a sender that will not send again, and completes campaigns with nothing left.
 */
@Service
public class CampaignRunner {

    static final String SEND_REFUSED = "SEND_REFUSED";
    static final String WALLET_EMPTY = "WALLET_EMPTY";

    private static final Logger log = LoggerFactory.getLogger(CampaignRunner.class);
    private static final Duration STUCK_MARGIN = Duration.ofMinutes(2);
    private static final List<RecipientStatus> OPEN = List.of(RecipientStatus.PENDING, RecipientStatus.SENDING);

    private final MongoTemplate mongo;
    private final CampaignSenderService senders;
    private final CampaignSettingsService settingsService;
    private final CampaignCounter counter;
    private final WebCampaignChannel web;
    private final Clock clock;
    private final Executor executor;
    private final Duration stuckAfter;
    /** Senders whose work of an earlier tick has not finished. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean ticking = new AtomicBoolean();

    CampaignRunner(MongoTemplate mongo, CampaignSenderService senders, CampaignSettingsService settingsService,
            CampaignCounter counter, WebCampaignChannel web, Clock clock,
            @Qualifier("campaignScheduler") Executor executor, OmnichannelProperties props) {
        this.mongo = mongo;
        this.senders = senders;
        this.settingsService = settingsService;
        this.counter = counter;
        this.web = web;
        this.clock = clock;
        this.executor = executor;
        this.stuckAfter = props.rpc().timeout().plus(STUCK_MARGIN);
    }

    // ---- the tick ----

    /**
     * Starts each eligible sender's work and completes the campaigns that have nothing left. The future completes
     * when the work started by this tick has finished; a tick while another is still starting does nothing.
     */
    public CompletableFuture<Void> tick() {
        if (!ticking.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        try {
            Instant now = clock.instant();
            List<Campaign> running = mongo.find(new Query(Criteria.where("status").is(CampaignStatus.RUNNING))
                    .with(Sort.by("startedAt", "_id")), Campaign.class);
            failStuck(now);
            Map<String, List<Campaign>> byWorkspace = new LinkedHashMap<>();
            for (Campaign c : running) byWorkspace.computeIfAbsent(c.getWorkspaceId(), k -> new ArrayList<>()).add(c);
            List<CompletableFuture<Void>> started = new ArrayList<>();
            byWorkspace.forEach((ws, campaigns) -> {
                try {
                    startSenders(ws, campaigns, now, started);
                } catch (RuntimeException e) {
                    log.warn("campaign tick of workspace {} failed", ws, e);
                }
            });
            try {
                skipUnusablePreferred(running);
            } catch (RuntimeException e) {
                log.warn("could not skip the recipients waiting for senders that cannot send", e);
            }
            completeFinished(running);
            return CompletableFuture.allOf(started.toArray(CompletableFuture[]::new));
        } finally {
            ticking.set(false);
        }
    }

    /** SENDING for longer than a device call can take: failed as INTERRUPTED, never retried. */
    public int failStuck(Instant now) {
        Query stuck = new Query(Criteria.where("status").is(RecipientStatus.SENDING)
                .and("attemptedAt").lt(now.minus(stuckAfter)));
        List<String> campaignIds = mongo.findDistinct(stuck, "campaignId", CampaignRecipient.class, String.class);
        if (campaignIds.isEmpty()) return 0;
        long failed = mongo.updateMulti(stuck, new Update().set("status", RecipientStatus.FAILED)
                .set("error", "INTERRUPTED"), CampaignRecipient.class).getModifiedCount();
        campaignIds.forEach(counter::refresh);
        log.warn("failed {} campaign recipients stuck in SENDING as INTERRUPTED", failed);
        return (int) failed;
    }

    private void startSenders(String ws, List<Campaign> campaigns, Instant now,
            List<CompletableFuture<Void>> started) {
        CampaignSettings settings = settingsService.get(ws);
        if (!SenderPolicy.inWindow(settings, now)) return;
        Set<String> senderIds = new LinkedHashSet<>();
        for (Campaign c : campaigns) senderIds.addAll(c.getSenderIds());
        for (String senderId : senderIds) {
            if (busy.contains(senderId)) continue;
            List<Campaign> mine = campaigns.stream().filter(c -> c.getSenderIds().contains(senderId)).toList();
            CampaignChannel channel = channelOf(mine);
            if (channel == null || !channel.eligible(ws, senderId, settings, now)) continue;
            busy.add(senderId);
            try {
                started.add(CompletableFuture.runAsync(() -> {
                    try {
                        channel.process(ws, senderId, mine, settings);
                    } finally {
                        busy.remove(senderId);
                    }
                }, executor));
            } catch (RuntimeException rejected) {
                busy.remove(senderId);
                throw rejected;
            }
        }
    }

    /** The channel of a sender's campaigns, which are all of its own kind; null for kinds not sent here yet. */
    private CampaignChannel channelOf(List<Campaign> campaigns) {
        return campaigns.stream().anyMatch(Campaign::isCloud) ? null : web;
    }

    /**
     * PENDING recipients waiting for a preferred sender that will not send again (RETIRED, or its number disabled,
     * not WhatsApp Web or gone from the workspace) are skipped as chats on another number, so the campaign can finish.
     * A RESTING or SUSPENDED sender comes back, so its recipients keep waiting.
     */
    private void skipUnusablePreferred(List<Campaign> running) {
        Map<String, Boolean> usable = new HashMap<>();
        for (Campaign c : running) {
            if (c.isCloud()) continue;
            List<String> preferred = mongo.findDistinct(new Query(Criteria.where("campaignId").is(c.getId())
                    .and("status").is(RecipientStatus.PENDING).and("preferredSenderId").ne(null)),
                    "preferredSenderId", CampaignRecipient.class, String.class);
            boolean skipped = false;
            for (String senderId : preferred) {
                if (usable.computeIfAbsent(c.getWorkspaceId() + "/" + senderId,
                        k -> senders.mayBeWaitedFor(c.getWorkspaceId(), senderId))) continue;
                skipped |= mongo.updateMulti(new Query(Criteria.where("campaignId").is(c.getId())
                                .and("status").is(RecipientStatus.PENDING).and("preferredSenderId").is(senderId)),
                        new Update().set("status", RecipientStatus.SKIPPED)
                                .set("skipReason", SkipReason.CHAT_ON_OTHER_NUMBER), CampaignRecipient.class)
                        .getModifiedCount() > 0;
            }
            if (skipped) counter.refresh(c.getId());
        }
    }

    /** RUNNING campaigns without a PENDING or SENDING recipient are COMPLETED. */
    private void completeFinished(List<Campaign> running) {
        for (Campaign c : running) {
            if (mongo.exists(new Query(Criteria.where("campaignId").is(c.getId()).and("status").in(OPEN)),
                    CampaignRecipient.class)) continue;
            Instant now = clock.instant();
            mongo.updateFirst(new Query(Criteria.where("_id").is(c.getId()).and("status").is(CampaignStatus.RUNNING)),
                    new Update().set("status", CampaignStatus.COMPLETED).set("finishedAt", now)
                            .set("lastUpdateTime", now).inc("version", 1), Campaign.class);
        }
    }
}
