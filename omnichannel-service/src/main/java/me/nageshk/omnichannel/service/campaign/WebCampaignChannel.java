package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.SubscriptionInactiveException;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.common.inbox.LookupFailedException;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.service.billing.WorkGate;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.inbox.NewChatService;
import me.nageshk.omnichannel.service.inbox.NumberLookup;
import me.nageshk.omnichannel.service.inbox.NumberLookupService;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import me.nageshk.omnichannel.service.template.RenderedTemplate;
import me.nageshk.omnichannel.service.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * How a WhatsApp Web number sends campaigns: one recipient per eligible sender per tick, after its warm-up cap,
 * hourly cap, day-start offset and random gap allow it; the number is looked up, the saved template rendered (no
 * links), the message sent from the device, and the sender paced.
 */
@Component
final class WebCampaignChannel implements CampaignChannel {

    static final int FREE_SKIPS = 20;

    private static final Logger log = LoggerFactory.getLogger(WebCampaignChannel.class);

    private final RecipientClaims claims;
    private final CampaignSenderService senders;
    private final RecipientSkips skips;
    private final PlaceholderValues placeholderValues;
    private final NumberLookupService lookups;
    private final NewChatService newChats;
    private final TemplateRenderer renderer;
    private final InboxDevices devices;
    private final PacingRandom random;
    private final SenderHealth health;
    private final Clock clock;
    private final WorkGate workGate;

    WebCampaignChannel(RecipientClaims claims, CampaignSenderService senders, RecipientSkips skips,
            PlaceholderValues placeholderValues, NumberLookupService lookups, NewChatService newChats,
            TemplateRenderer renderer, InboxDevices devices, PacingRandom random, SenderHealth health, Clock clock,
            WorkGate workGate) {
        this.claims = claims;
        this.senders = senders;
        this.skips = skips;
        this.placeholderValues = placeholderValues;
        this.lookups = lookups;
        this.newChats = newChats;
        this.renderer = renderer;
        this.devices = devices;
        this.random = random;
        this.health = health;
        this.clock = clock;
        this.workGate = workGate;
    }

    @Override
    public boolean eligible(String ws, String senderId, CampaignSettings settings, Instant now) {
        CampaignSender sender = senders.refresh(senders.ensure(ws, senderId), settings, now);
        return eligible(ws, sender, settings, now);
    }

    private boolean eligible(String ws, CampaignSender s, CampaignSettings settings, Instant now) {
        String id = s.getPhoneNumberId();
        return (s.getState() == SenderState.WARMING || s.getState() == SenderState.ACTIVE)
                && devices.isReady(ws, id)
                && workGate.allows(ws, WorkGate.CAMPAIGNS)
                && (s.getPausedUntil() == null || !s.getPausedUntil().isAfter(now))
                && !now.isBefore(SenderPolicy.dayStartsAt(settings, SenderPolicy.today(settings, now),
                        s.getDayStartOffsetMinutes()))
                && senders.usedToday(id, settings, now) < senders.capToday(s, settings, now)
                && senders.hourlyUsed(id, now) < settings.getHourlyCap()
                && (s.getNextSendAt() == null || !s.getNextSendAt().isAfter(now));
    }

    /**
     * Claims and handles recipients until one is not a free skip, at most {@link #FREE_SKIPS} free skips more. What
     * the tick saw may have changed while this sender waited for a thread: the sender is read again and does nothing
     * when it is no longer eligible (retired, paused, at a cap, in a gap); a claim is released when the window has
     * closed (and the sender stops), when its campaign is no longer RUNNING (and that campaign is passed over), or
     * when another sender is working on the same phone of the workspace (and this sender stops for the tick).
     */
    @Override
    public void process(String ws, String senderId, List<Campaign> campaigns, CampaignSettings settings) {
        try {
            CampaignSender sender = senders.find(senderId);
            if (sender == null || !eligible(ws, sender, settings, clock.instant())) return;
            List<Campaign> open = new ArrayList<>(campaigns);
            int freeSkips = FREE_SKIPS;
            while (true) {
                CampaignClaim claim = claims.claim(senderId, sender, open, settings);
                if (claim == null) return;
                if (!SenderPolicy.inWindow(settings, clock.instant())) {
                    claims.release(claim);
                    return;
                }
                if (!claims.isRunning(claim.campaign())) {
                    claims.release(claim);
                    open.remove(claim.campaign());
                    continue;
                }
                if (!claims.enterPhone(claim)) {
                    claims.release(claim);
                    return;
                }
                boolean free;
                try {
                    free = handle(claim);
                } finally {
                    claims.leavePhone(claim);
                }
                if (!free || freeSkips-- <= 0) return;
            }
        } catch (RuntimeException e) {
            log.warn("campaign work of sender {} failed", senderId, e);
        }
    }

    /** Returns true when the recipient was skipped without asking the device and without a gap. */
    private boolean handle(CampaignClaim claim) {
        String ws = claim.campaign().getWorkspaceId();
        String phone = claim.recipient().getPhone();
        if (skips.isDoNotContact(ws, phone)) return claims.freeSkip(claim, SkipReason.DO_NOT_CONTACT);
        if (skips.isRecentlyMessaged(ws, phone, clock.instant(), claim.settings().getRecontactDays())) {
            return claims.freeSkip(claim, SkipReason.RECENTLY_MESSAGED);
        }
        NumberLookup lookup;
        try {
            lookup = await(lookups.lookup(ws, claim.senderId(), phone, false));
        } catch (NoDeviceException e) {
            claims.release(claim);
            return false;
        } catch (ExecutionException | RuntimeException e) {
            lookupFailed(claim, e instanceof ExecutionException ? e.getCause() : e);
            return false;
        }
        if (!lookup.cached()) health.lookupSucceeded(claim.senderId());
        if (!lookup.registered()) {
            if (lookup.cached()) return claims.freeSkip(claim, SkipReason.NOT_ON_WHATSAPP);
            notOnWhatsApp(claim);
            return false;
        }
        send(claim, lookup);
        return false;
    }

    private void send(CampaignClaim claim, NumberLookup lookup) {
        Campaign c = claim.campaign();
        CampaignRecipient r = claim.recipient();
        Map<String, String> values = placeholderValues.of(c, r, claims.contactOf(r));
        try {
            RenderedTemplate rendered = renderer.renderForCampaign(c.getWorkspaceId(), r.getTemplateId(), values);
            if (CampaignLinks.containsLink(rendered.content().getBody())) {
                claims.failed(claim, "HAS_LINK");
                return;
            }
        } catch (RuntimeException e) {
            log.warn("could not render template {} for recipient {}", r.getTemplateId(), r.getId(), e);
            claims.failed(claim, "RENDER_FAILED");
            return;
        }
        CompletableFuture<MessageView> sent;
        // from here the message may reach the phone: it counts as recently messaged whatever becomes of the send
        if (!claims.whileSending(claim, new Update().set("dispatchedAt", clock.instant()))) return;
        try {
            sent = newChats.startForCampaign(new CampaignActor(c.getId(), c.getName()), c.getWorkspaceId(),
                    claim.senderId(), r.getPhone(), lookup.remoteId(), new SendRequest(null, r.getTemplateId(), values));
        } catch (NoDeviceException | SubscriptionInactiveException e) {
            // nothing left the server: the recipient waits for the number, or for the plan to cover campaigns again
            claims.release(claim);
            return;
        } catch (WalletEmptyException e) {
            // nothing left the server and nobody failed: the campaign waits for money and for a person to resume it
            log.info("the wallet cannot pay for campaign {}; pausing it", c.getId());
            claims.release(claim);
            claims.pause(c.getId(), CampaignRunner.WALLET_EMPTY);
            return;
        } catch (RuntimeException e) {
            sendRefused(claim, e);
            return;
        }
        try {
            succeeded(claim, await(sent));
        } catch (ExecutionException e) {
            sendFailed(claim, e.getCause());
        }
    }

    // ---- outcomes ----

    /**
     * The sender could not look the number up: a fault of the sender, not of the lead. The claim is released and the
     * sender waits a gap; {@link SenderHealth#lookupFailed} pauses it on the third failure in a row.
     */
    private void lookupFailed(CampaignClaim claim, Throwable cause) {
        if (cause instanceof LookupFailedException) {
            log.warn("lookup for recipient {} on {} failed: {}", claim.recipient().getId(), claim.senderId(),
                    cause.toString());
        } else {
            log.warn("lookup for recipient {} on {} failed", claim.recipient().getId(), claim.senderId(), cause);
        }
        claims.release(claim);
        Instant now = clock.instant();
        paced(claim, false, now);
        health.lookupFailed(claim.senderId(), claim.settings(), now);
    }

    /**
     * The send was refused before anything reached the device (a template archived since the start, say): the claim
     * is released at no cost and the campaign is paused for someone to look at it.
     */
    private void sendRefused(CampaignClaim claim, RuntimeException e) {
        log.warn("send to recipient {} of campaign {} was refused; pausing the campaign", claim.recipient().getId(),
                claim.campaign().getId(), e);
        claims.release(claim);
        claims.pause(claim.campaign().getId(), CampaignRunner.SEND_REFUSED);
    }

    private void notOnWhatsApp(CampaignClaim claim) {
        claims.whileSending(claim, new Update().set("status", RecipientStatus.SKIPPED)
                .set("skipReason", SkipReason.NOT_ON_WHATSAPP));
        paced(claim, true, clock.instant());
    }

    /** The device was asked to send and failed: the attempt costs its slot and its gap. */
    private void sendFailed(CampaignClaim claim, Throwable cause) {
        Throwable t = cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
        String error = t instanceof RpcException rpc ? rpc.code()
                : t instanceof TimeoutException ? "TIMEOUT" : t.getClass().getSimpleName();
        if (t instanceof RpcException || t instanceof TimeoutException) {
            log.warn("campaign send to recipient {} on {} failed: {}", claim.recipient().getId(), claim.senderId(),
                    t.toString());
        } else {
            log.warn("campaign send to recipient {} on {} failed", claim.recipient().getId(), claim.senderId(), t);
        }
        attempted(claim);
        claims.failed(claim, error);
    }

    private void succeeded(CampaignClaim claim, MessageView message) {
        Instant now = attempted(claim);
        claims.sent(claim, message, now);
        health.afterSend(claim.senderId(), claim.settings(), now);
    }

    // ---- helpers ----

    /** Paces the sender and counts the attempt for its day (pacing first, so it is never left out); returns the time. */
    private Instant attempted(CampaignClaim claim) {
        Instant now = clock.instant();
        paced(claim, false, now);
        senders.sendAttempted(claim.senderId(), SenderPolicy.today(claim.settings(), now), now);
        return now;
    }

    /** The sender's next send after a gap from {@code now}: a skip's short one, doubled while at half pace. */
    private void paced(CampaignClaim claim, boolean skip, Instant now) {
        Instant halfPaceUntil = claim.sender().getHalfPaceUntil();
        boolean halfPace = halfPaceUntil != null && halfPaceUntil.isAfter(now);
        senders.paced(claim.senderId(),
                now.plusSeconds(SenderPolicy.gapSeconds(skip, halfPace, claim.settings(), random)));
    }

    /**
     * Waits for the device. An interrupted wait throws: during a lookup it counts as a failed lookup, during a send
     * it leaves the recipient SENDING for {@link CampaignRunner#failStuck}, as the message may have gone out.
     */
    private static <T> T await(CompletableFuture<T> future) throws ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the device", e);
        }
    }
}
