package me.nageshk.omnichannel.service.billing.wallet;

import java.math.BigDecimal;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.inbox.SendActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Charges a message to the customer's wallet at the rate of the term it is on. The charge is taken before the
 * message goes to the phone, so a message is never sent that could not be paid for; it is given back only when the
 * phone definitely did not send it.
 */
@Component
public class UsageCharger {

    private static final Logger log = LoggerFactory.getLogger(UsageCharger.class);

    /** A charge that was taken, so it can be given back or noted on the message. */
    public record Charge(String customerId, String entryId, BigDecimal amount, UsageSource source, String workspaceId,
            String ref, java.time.Instant at) {}

    private final Entitlements entitlements;
    private final WorkspaceRepository workspaces;
    private final WalletService wallets;
    private final MongoTemplate mongo;
    private final java.time.Clock clock;

    public UsageCharger(Entitlements entitlements, WorkspaceRepository workspaces, WalletService wallets,
            MongoTemplate mongo, java.time.Clock clock) {
        this.clock = clock;
        this.entitlements = entitlements;
        this.workspaces = workspaces;
        this.wallets = wallets;
        this.mongo = mongo;
    }

    /**
     * Takes the price of one message from the wallet of the brand's customer.
     *
     * @return the charge, or null when the customer is not charged for messages (no plan, or a plan with no rate)
     * @throws WalletEmptyException when the wallet does not cover it; nothing is taken and nothing must be sent
     */
    public Charge charge(String workspaceId, SendActor actor) {
        BigDecimal rate = entitlements.ofWorkspace(workspaceId).rate(UsageMeter.WHATSAPP_MESSAGE);
        if (rate == null) return null;
        String customerId = workspaces.findById(workspaceId).map(Workspace::getCustomerId).orElse(null);
        if (customerId == null) return null;
        UsageSource source = sourceOf(actor);
        WalletEntry draft = new WalletEntry();
        draft.setType(WalletEntryType.USAGE);
        draft.setMeter(UsageMeter.WHATSAPP_MESSAGE);
        draft.setSource(source);
        draft.setWorkspaceId(workspaceId);
        draft.setRef(actor.campaignId());
        draft.setBy(actor.userId());
        java.time.Instant at = java.time.Instant.now(clock);
        String entryId = wallets.debit(customerId, rate, draft);
        return new Charge(customerId, entryId, rate, source, workspaceId, actor.campaignId(), at);
    }

    /** Returns a charge for a message that was not sent. Never throws: the send's own failure is what matters. */
    public void giveBack(Charge charge, String why) {
        if (charge == null) return;
        try {
            WalletEntry draft = new WalletEntry();
            draft.setType(WalletEntryType.REVERSAL);
            draft.setMeter(UsageMeter.WHATSAPP_MESSAGE);
            draft.setSource(charge.source());
            draft.setWorkspaceId(charge.workspaceId());
            draft.setRef(charge.entryId());
            draft.setNote(why);
            draft.setUsageAt(charge.at());
            wallets.credit(charge.customerId(), charge.amount(), draft);
        } catch (RuntimeException e) {
            log.error("could not give back charge {} of customer {} ({})", charge.entryId(), charge.customerId(),
                    charge.amount(), e);
        }
    }

    /** The answers that mean the page never tried to send: it was not reachable, not ready, or had no such chat. */
    private static final java.util.Set<String> NOT_TRIED = java.util.Set.of(RpcException.SEND_FAILED,
            RpcException.WHATSAPP_NOT_READY, RpcException.CHAT_NOT_FOUND, RpcException.METHOD_NOT_FOUND);

    /**
     * Whether a failed send definitely did not go out, so its charge is given back: only when the answer says the
     * page never tried. A timeout, the phone dropping off mid-send, or an error inside the page (which can come
     * after the message left) is not definite: the message may have gone, and the customer may have got what it
     * paid for.
     */
    public static boolean definitelyNotSent(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause instanceof RpcException rpc && NOT_TRIED.contains(rpc.code());
    }

    /** Notes on the stored message which wallet entry paid for it. Best effort. */
    public void stamp(String messageId, Charge charge) {
        if (charge == null || messageId == null) return;
        try {
            mongo.updateFirst(new Query(Criteria.where("_id").is(messageId)),
                    new Update().set("usageEntryId", charge.entryId()), WaWebMessage.class);
        } catch (RuntimeException e) {
            log.warn("could not note charge {} on message {}", charge.entryId(), messageId, e);
        }
    }

    private static UsageSource sourceOf(SendActor actor) {
        return switch (actor.source()) {
            case CAMPAIGN -> UsageSource.CAMPAIGN;
            case AUTOMATION -> UsageSource.AUTOMATION;
            case CHATBOT -> UsageSource.CHATBOT;
            default -> UsageSource.CONSOLE;
        };
    }
}
