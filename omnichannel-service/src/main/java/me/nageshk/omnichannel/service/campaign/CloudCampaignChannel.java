package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.SubscriptionInactiveException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCloudTemplate;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.meta.api.CloudSendException;
import me.nageshk.omnichannel.service.billing.WorkGate;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.CloudTemplateRequest;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.inbox.NewChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * How a Cloud API number sends campaigns (spec 2026-11-21 §5.3, §5.4): as fast as Meta allows, inside the sending
 * window, until its WhatsApp account reaches Meta's daily limit. No lookup, warm-up, gaps or link check; one Meta
 * template per campaign, sent one recipient after another for at most {@link #TICK_BUDGET} of each tick.
 */
@Component
final class CloudCampaignChannel implements CampaignChannel {

    static final Duration TICK_BUDGET = Duration.ofSeconds(8);
    static final String MISSING_VALUE = "MISSING_VALUE";
    static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    static final String TEMPLATE_UNAVAILABLE = "TEMPLATE_UNAVAILABLE";
    static final String CLOUD_AUTH = "CLOUD_AUTH";
    /** Meta's "spam rate limit hit": the account may start no more conversations for now. */
    static final int LIMIT_REACHED_CODE = 131048;
    static final Duration LIMIT_HOLD = Duration.ofHours(1);
    static final Duration RATE_HOLD = Duration.ofSeconds(60);
    /** Meta refusals that hold for the whole template: the template does not exist, is paused or is disabled. */
    static final Set<Integer> TEMPLATE_CODES = Set.of(132001, 132015, 132016);
    /** Meta refusals that hold for every recipient: account locked, payment, parameter count or format. */
    static final Set<Integer> CAMPAIGN_CODES = Set.of(131031, 131042, 132000, 132012);
    /** The inbox's title for a header file Meta would not take. */
    static final String HEADER_REFUSED = "Meta did not take the header file";
    /** This many refusals in a row with the same code are taken as the campaign's fault, not the recipients'. */
    static final int BREAKER = 3;

    private static final Logger log = LoggerFactory.getLogger(CloudCampaignChannel.class);

    /** What the loop does after one recipient: SENT counts against the daily limit. */
    private enum Next { SENT, GO_ON, STOP }

    /** Meta's refusals in a row in one loop, by code. */
    private static final class Streak {
        private Integer code;
        private int count;

        /** Counts one more refusal with {@code next}; returns how many in a row had it. */
        int add(Integer next) {
            if (count > 0 && java.util.Objects.equals(code, next)) {
                count++;
            } else {
                code = next;
                count = 1;
            }
            return count;
        }

        void reset() {
            count = 0;
        }
    }

    private final RecipientClaims claims;
    private final CloudCampaignSenders cloudSenders;
    private final PhoneNumberRepository numbers;
    private final RecipientSkips skips;
    private final PlaceholderValues placeholderValues;
    private final NewChatService newChats;
    private final WorkGate workGate;
    private final Clock clock;

    CloudCampaignChannel(RecipientClaims claims, CloudCampaignSenders cloudSenders, PhoneNumberRepository numbers,
            RecipientSkips skips, PlaceholderValues placeholderValues, NewChatService newChats, WorkGate workGate,
            Clock clock) {
        this.claims = claims;
        this.cloudSenders = cloudSenders;
        this.numbers = numbers;
        this.skips = skips;
        this.placeholderValues = placeholderValues;
        this.newChats = newChats;
        this.workGate = workGate;
        this.clock = clock;
    }

    @Override
    public boolean eligible(String ws, String senderId, CampaignSettings settings, Instant now) {
        if (!ready(ws, senderId, settings, now)) return false;
        return !cloudSenders.atLimit(ws, cloudSenders.wabaOf(senderId).orElseThrow(), now);
    }

    /** Everything but the daily limit, which {@link #process} counts once itself. */
    private boolean ready(String ws, String senderId, CampaignSettings settings, Instant now) {
        PhoneNumber n = numbers.findByIdAndWorkspaceId(senderId, ws).orElse(null);
        if (n == null || n.getDisabled() != null || n.getConnectionType() != ConnectionType.WHATSAPP_CLOUD_API) {
            return false;
        }
        return cloudSenders.wabaOf(senderId).isPresent() && cloudSenders.canSend(senderId)
                && workGate.allows(ws, WorkGate.CAMPAIGNS) && SenderPolicy.inWindow(settings, now)
                && cloudSenders.heldUntil(senderId, now) == null;
    }

    /**
     * Sends recipients one after another until none is left, the WhatsApp account's daily limit is used, Meta asks
     * to wait, the window closes, or {@link #TICK_BUDGET} is spent; a campaign that stops running is passed over.
     */
    @Override
    public void process(String ws, String senderId, List<Campaign> campaigns, CampaignSettings settings) {
        String waba = null;
        try {
            Instant now = clock.instant();
            if (!ready(ws, senderId, settings, now)) return;
            String mine = cloudSenders.wabaOf(senderId).orElseThrow();
            // the numbers of one WhatsApp account share its daily limit: one of them sends at a time
            if (!cloudSenders.enterWaba(mine)) return;
            waba = mine;
            Integer limit = cloudSenders.dailyLimit(ws, waba);
            long left = limit == null ? Long.MAX_VALUE : limit - cloudSenders.usedLast24h(ws, waba, now);
            long started = System.nanoTime();
            List<Campaign> open = new ArrayList<>(campaigns);
            Streak streak = new Streak();
            while (left > 0 && System.nanoTime() - started < TICK_BUDGET.toNanos()) {
                CampaignClaim claim = claims.claim(senderId, null, open, settings);
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
                Next next;
                try {
                    next = handle(claim, waba, streak);
                } finally {
                    claims.leavePhone(claim);
                }
                if (next == Next.SENT) left--;
                if (next == Next.STOP) return;
            }
        } catch (RuntimeException e) {
            log.warn("campaign work of Cloud number {} failed", senderId, e);
        } finally {
            if (waba != null) cloudSenders.leaveWaba(waba);
        }
    }

    private Next handle(CampaignClaim claim, String waba, Streak streak) {
        Campaign c = claim.campaign();
        CampaignRecipient r = claim.recipient();
        String ws = c.getWorkspaceId();
        if (skips.isDoNotContact(ws, r.getPhone())) {
            claims.freeSkip(claim, SkipReason.DO_NOT_CONTACT);
            return Next.GO_ON;
        }
        if (skips.isRecentlyMessaged(ws, r.getPhone(), clock.instant(), claim.settings().getRecontactDays())) {
            claims.freeSkip(claim, SkipReason.RECENTLY_MESSAGED);
            return Next.GO_ON;
        }
        CampaignCloudTemplate t = c.getCloudTemplate();
        Map<String, String> values = placeholderValues.of(c, r, claims.contactOf(r));
        // from here the message may reach the phone: it counts as recently messaged whatever becomes of the send
        if (!claims.whileSending(claim, new Update().set("dispatchedAt", clock.instant()))) return Next.GO_ON;
        MessageView message;
        try {
            CompletableFuture<MessageView> sent = newChats.startCloudTemplateForCampaign(
                    new CampaignActor(c.getId(), c.getName()), ws, claim.senderId(), r.getPhone(),
                    new CloudTemplateRequest(t.cloudTemplateId(), values, t.headerMediaId()));
            message = await(sent);
        } catch (ExecutionException e) {
            return outcome(claim, waba, e.getCause(), streak);
        } catch (RuntimeException e) {
            return outcome(claim, waba, e, streak);
        }
        // Meta took it: a failure to record it leaves the recipient SENDING for failStuck, never sent again
        claims.sent(claim, message, clock.instant());
        streak.reset();
        return Next.SENT;
    }

    /** What a failed send does to the recipient, the number and the campaign (spec 2026-11-21 §5.4). */
    private Next outcome(CampaignClaim claim, String waba, Throwable cause, Streak streak) {
        Throwable t = cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
        // the inbox reports a refused token or a rate limit as "no device", with Meta's error as the cause
        if (t instanceof NoDeviceException && t.getCause() instanceof CloudSendException meta) t = meta;
        String campaignId = claim.campaign().getId();
        Instant now = clock.instant();
        if (t instanceof ValidationException v) {
            if (v.fields().keySet().stream().anyMatch(k -> k.startsWith("values."))) {
                claims.failedUnsent(claim, MISSING_VALUE);
                return Next.GO_ON;
            }
            if (v.fields().containsKey("cloudTemplateId")) {
                claims.release(claim);
                claims.pause(campaignId, TEMPLATE_UNAVAILABLE);
                return Next.STOP;
            }
        }
        if (t instanceof NoDeviceException || t instanceof SubscriptionInactiveException) {
            claims.release(claim);
            return Next.STOP;
        }
        if (t instanceof WalletEmptyException) {
            log.info("the wallet cannot pay for campaign {}; pausing it", campaignId);
            claims.release(claim);
            claims.pause(campaignId, CampaignRunner.WALLET_EMPTY);
            return Next.STOP;
        }
        if (t instanceof CloudSendException e) {
            switch (e.kind()) {
                case TOKEN_REFUSED -> {
                    claims.release(claim);
                    claims.pause(campaignId, CLOUD_AUTH);
                    return Next.STOP;
                }
                case RATE_LIMITED -> {
                    claims.release(claim);
                    if (Integer.valueOf(LIMIT_REACHED_CODE).equals(e.metaCode())) {
                        cloudSenders.holdWaba(waba, now.plus(LIMIT_HOLD));
                    } else {
                        Duration wait = e.retryAfter() == null || e.retryAfter().isNegative() ? RATE_HOLD
                                : e.retryAfter();
                        cloudSenders.holdNumber(claim.senderId(), now.plus(wait));
                    }
                    return Next.STOP;
                }
                case REFUSED, WINDOW_CLOSED -> {
                    Integer code = e.metaCode();
                    if (code != null && TEMPLATE_CODES.contains(code)) {
                        claims.release(claim);
                        claims.pause(campaignId, TEMPLATE_UNAVAILABLE);
                        return Next.STOP;
                    }
                    boolean campaignWide = code != null ? CAMPAIGN_CODES.contains(code) : HEADER_REFUSED.equals(e.title());
                    if (campaignWide || streak.add(code) >= BREAKER) {
                        log.warn("Meta refuses campaign {} ({}); pausing it", campaignId, e.getMessage());
                        claims.release(claim);
                        claims.pause(campaignId, CampaignRunner.SEND_REFUSED);
                        return Next.STOP;
                    }
                    claims.failedUnsent(claim, code == null ? e.title() : code + " " + e.title());
                    return Next.GO_ON;
                }
                case UNKNOWN -> {
                    log.warn("Cloud campaign send to recipient {} has no known outcome: {}", claim.recipient().getId(),
                            e.toString());
                    claims.failed(claim, OUTCOME_UNKNOWN);
                    return Next.GO_ON;
                }
            }
        }
        log.warn("send to recipient {} of campaign {} was refused; pausing the campaign", claim.recipient().getId(),
                campaignId, t);
        claims.release(claim);
        claims.pause(campaignId, CampaignRunner.SEND_REFUSED);
        return Next.STOP;
    }

    private static <T> T await(CompletableFuture<T> future) throws ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Meta", e);
        }
    }
}
