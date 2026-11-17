package me.nageshk.omnichannel.service.billing.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderLine;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnRequest;

/** What the billing API takes and returns for quotes and orders. */
public final class OrderViews {

    private OrderViews() {}

    /**
     * @param addOns NEW and CHANGE: the add-ons wanted
     * @param keepAddOnIds RENEWAL: the held add-ons to keep; null keeps all
     * @param addOn ADDON: the one being bought
     */
    public record QuoteRequest(OrderKind kind, String planCode, LocalDate startDate, List<AddOnRequest> addOns,
            List<String> keepAddOnIds, AddOnRequest addOn, BigDecimal extraDiscountPercent, String extraDiscountReason) {}

    /** The dates are midnights in the billing zone; {@code termEnd} is the first moment after the last day. */
    public record QuoteView(OrderKind kind, List<OrderLine> lines, BigDecimal extraDiscountPercent, BigDecimal taxable,
            TaxAmounts tax, BigDecimal total, List<OrderCredit> credits, BigDecimal creditFromNotes,
            BigDecimal accountCredit, BigDecimal accountCreditUsed, BigDecimal payable, String planCode, String planName,
            BillingPeriod period, Instant termStart, Instant termEnd, Instant validUntil) {}

    public record PaymentView(String method, String reference, LocalDate receivedOn, String note, String recordedByName,
            Instant recordedAt) {}

    /** A quote that was raised: the same amounts, with who raised it and what became of it. */
    public record OrderView(String id, String number, String customerId, String customerName, OrderStatus status,
            String raisedBy, String raisedByName, boolean raisedByStaff, Instant raisedAt, String reason,
            String extraDiscountReason, PaymentView payment, String invoiceId, String invoiceNumber,
            List<String> creditNoteIds, String rejectedReason,
            OrderKind kind, List<OrderLine> lines, BigDecimal extraDiscountPercent, BigDecimal taxable, TaxAmounts tax,
            BigDecimal total, List<OrderCredit> credits, BigDecimal creditFromNotes, BigDecimal accountCredit,
            BigDecimal accountCreditUsed, BigDecimal payable, String planCode, String planName, BillingPeriod period,
            Instant termStart, Instant termEnd, Instant validUntil) {}

    public record OrderPage(List<OrderView> items, long total, int page) {}

    public record RejectRequest(String reason) {}

    /**
     * One of the two, with a reason.
     *
     * @param termEnd the day after the last day of the term, as in every term's {@code termEnd}
     * @param creditAmount an amount to add to (or, negative, take from) the account credit, tax included
     */
    public record AdjustRequest(LocalDate termEnd, BigDecimal creditAmount, String reason) {}

    /**
     * @param addOnId the add-on to cancel; null cancels the plan
     * @param creditTaxable the amount before tax to credit instead of the unused part; null for the unused part
     */
    public record CancelRequest(String addOnId, BigDecimal creditTaxable, String reason) {}

    public record PayRequest(String method, String reference, LocalDate receivedOn, String note) {}

    /** {@code from}: the first day a renewal can be asked for, when it is not allowed yet. */
    public record RenewalOption(boolean allowed, String reason, LocalDate from) {}

    public record PlanOption(String code, String name, BillingPeriod period, int rank, BigDecimal discountedPrice) {}

    /** An add-on the customer may buy: a feature group, or {@code unit} more of a limit at {@code unitPrice}. */
    public record AddOnOption(AddOnKind kind, String groupCode, String name, LimitKey limitKey, String label, int unit,
            BigDecimal unitPrice) {}

    public record OptionsView(String paymentInstructions, RenewalOption renewal, List<PlanOption> upgrades,
            List<AddOnOption> addOns) {}
}
