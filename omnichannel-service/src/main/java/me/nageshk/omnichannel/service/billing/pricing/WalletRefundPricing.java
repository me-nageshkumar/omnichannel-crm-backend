package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;
import org.springframework.http.HttpStatus;

/**
 * What taking money back out of the wallet gives: the amount leaves the wallet, and comes back as credit notes on
 * the top-up invoices that brought it in, newest first, each with the GST that was paid on it. Only top-up money can
 * come back: what staff added by adjustment was never paid for. Pure, like {@link OrderPricing}.
 */
public final class WalletRefundPricing {

    private WalletRefundPricing() {}

    /**
     * @param amount rupees to take out of the wallet, before tax
     * @param balance what the wallet holds
     * @param topUpInvoices the customer's top-up invoices, newest first
     * @param spent what the customer's messages have cost so far; they spend top-up money before anything else
     * @throws CodedException 409 NOT_ENOUGH_WALLET when the wallet, or the top-ups still uncredited, do not cover it
     */
    public static Quote quote(BigDecimal amount, BigDecimal balance, List<Invoice> topUpInvoices, BigDecimal spent,
            Subscription sub, BillingSettings settings) {
        if (amount == null || amount.signum() <= 0) throw new ValidationException("amount", "must be more than 0");
        if (amount.stripTrailingZeros().scale() > 2) throw new ValidationException("amount", "at most 2 decimals");
        if (sub == null || sub.getCurrent() == null) throw new ValidationException("amount", "The customer has no plan");
        if (amount.compareTo(balance) > 0) throw notEnough("The wallet holds less than that");
        BigDecimal paidFor = BigDecimal.ZERO;
        for (Invoice i : topUpInvoices) {
            paidFor = paidFor.add(i.getTaxable().subtract(Money.zeroIfNull(i.getCredited())).max(BigDecimal.ZERO));
        }
        if (amount.compareTo(paidFor.subtract(Money.zeroIfNull(spent)).max(BigDecimal.ZERO)) > 0) {
            throw notEnough("Only money that came from top-ups can be refunded, and less than that is left of it");
        }
        BigDecimal left = amount;
        Map<String, BigDecimal> byInvoice = new LinkedHashMap<>();
        Map<String, Invoice> byId = new LinkedHashMap<>();
        for (Invoice i : topUpInvoices) {
            if (left.signum() == 0) break;
            BigDecimal open = i.getTaxable().subtract(Money.zeroIfNull(i.getCredited())).max(BigDecimal.ZERO);
            BigDecimal take = left.min(open);
            if (take.signum() > 0) {
                byInvoice.put(i.getId(), take);
                byId.put(i.getId(), i);
                left = left.subtract(take);
            }
        }
        if (left.signum() > 0) {
            throw notEnough("Only money that came from top-ups can be refunded, and less than that is left of it");
        }
        List<OrderCredit> credits = UnusedCredit.forAmounts(byInvoice, byId::get);
        BigDecimal fromNotes = Money.ZERO;
        for (OrderCredit c : credits) fromNotes = fromNotes.add(c.total());
        OrderTarget target = new OrderTarget();
        target.setSegmentAfter(sub.getCurrent());
        target.setWalletAmount(Money.round(amount));
        return new Quote(OrderKind.WALLET_REFUND, new ArrayList<>(), BigDecimal.ZERO, Money.ZERO,
                Gst.of(Money.ZERO, settings.getGstRatePercent(), false), Money.ZERO, List.copyOf(credits), fromNotes,
                Money.round(Money.zeroIfNull(sub.getCreditBalance())), Money.ZERO, Money.ZERO, target, null);
    }

    private static CodedException notEnough(String message) {
        return new CodedException(HttpStatus.CONFLICT, "NOT_ENOUGH_WALLET", null, message);
    }
}
