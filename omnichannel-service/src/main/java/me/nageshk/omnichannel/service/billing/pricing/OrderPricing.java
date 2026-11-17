package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.LimitAddOn;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderLine;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.PlanPricing;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnRequest;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;

/**
 * Prices a request. A pure function of its input: it reads no database and no clock, so the same input always
 * gives the same quote, and every rule about money is testable with plain values.
 */
public final class OrderPricing {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private OrderPricing() {}

    /** @throws ValidationException when the request cannot be priced, naming the field at fault */
    public static Quote quote(QuoteInput in) {
        return switch (in.kind()) {
            case NEW -> newPlan(in);
            case RENEWAL -> renewal(in);
            default -> throw new ValidationException("kind", "cannot be priced");
        };
    }

    // --- a new plan ---------------------------------------------------------------------------------------------

    private static Quote newPlan(QuoteInput in) {
        if (in.subscription() != null && in.subscription().getCurrent() != null
                && in.state() != SubscriptionState.EXPIRED) {
            throw new ValidationException("kind", "The customer has a plan; change or renew it");
        }
        Plan plan = activePlan(in);
        LocalDate start = in.startDate() == null ? in.today() : in.startDate();
        int anchor = start.getDayOfMonth();
        LocalDate end = BillingPeriod.endOf(start, plan.getPeriod(), anchor);
        if (!end.isAfter(in.today())) throw new ValidationException("startDate", "That term has already ended");

        Draft draft = new Draft(in, plan, start, end);
        draft.planLine();
        for (Wanted w : wantedAddOns(in, plan, in.addOns())) draft.addOnLine(w, null);
        return draft.finish(OrderTarget.CURRENT, anchor, List.of());
    }

    // --- a renewal ----------------------------------------------------------------------------------------------

    private static Quote renewal(QuoteInput in) {
        Subscription sub = in.subscription();
        if (sub == null || sub.getCurrent() == null) throw new ValidationException("kind", "The customer has no plan to renew");
        if (sub.isCancelled()) throw new ValidationException("kind", "The plan was cancelled; start a new one");
        if (sub.getNext() != null) throw new ValidationException("kind", "The plan is already renewed");
        Plan plan = in.plan();
        if (plan == null) throw new ValidationException("planCode", "The plan no longer exists");
        if (plan.getStatus() != PlanStatus.ACTIVE && !(plan.getStatus() == PlanStatus.RETIRED && in.staff())) {
            throw new ValidationException("planCode", "This plan is no longer sold; ask your account manager");
        }
        LocalDate lastEnd = date(in, SubscriptionClock.lastEnd(sub));
        LocalDate opens = lastEnd.minusDays(in.settings().getRenewalWindowDays());
        if (in.state() == SubscriptionState.ACTIVE && in.today().isBefore(opens)) {
            throw new ValidationException("kind", "Renewal opens on " + DAY.format(opens));
        }
        boolean lapsed = in.state() == SubscriptionState.EXPIRED;
        LocalDate start = lapsed ? in.today() : lastEnd;
        int anchor = lapsed || sub.getAnchorDay() < 1 ? start.getDayOfMonth() : sub.getAnchorDay();
        LocalDate end = BillingPeriod.endOf(start, plan.getPeriod(), anchor);

        Draft draft = new Draft(in, plan, start, end);
        draft.planLine();
        Set<String> included = includedGroups(plan);
        for (SubscriptionAddOn held : sub.getCurrent().getAddOns()) {
            if (in.keepAddOnIds() != null && !in.keepAddOnIds().contains(held.getId())) continue;
            // a group the plan now includes needs no add-on any more
            if (held.getKind() == AddOnKind.GROUP && included.contains(held.getGroupCode())) continue;
            if (held.getKind() == AddOnKind.LIMIT && !plan.getLimits().containsKey(held.getLimitKey())) continue;
            draft.addOnLine(carried(held, plan, plan.getPeriod(), sub.getCurrent().getTerms().period(), in), held.getId());
        }
        String segment = in.state() == SubscriptionState.ACTIVE ? OrderTarget.NEXT : OrderTarget.CURRENT;
        return draft.finish(segment, anchor, List.of());
    }

    // --- shared -------------------------------------------------------------------------------------------------

    /** An add-on to charge: what it is and its price for one full period. */
    record Wanted(AddOnKind kind, String groupCode, LimitKey limitKey, int quantity, BigDecimal periodPrice,
            String label) {}

    static Plan activePlan(QuoteInput in) {
        Plan plan = in.plan();
        if (plan == null) throw new ValidationException("planCode", "is not a plan");
        if (plan.getStatus() != PlanStatus.ACTIVE) throw new ValidationException("planCode", "Only an active plan can be sold");
        return plan;
    }

    static Set<String> includedGroups(Plan plan) {
        Set<String> included = new HashSet<>();
        plan.getItems().forEach(i -> included.add(i.groupCode()));
        return included;
    }

    /** The add-ons asked for, each checked against what the plan offers and priced for a full period. */
    static List<Wanted> wantedAddOns(QuoteInput in, Plan plan, List<AddOnRequest> requested) {
        List<Wanted> out = new ArrayList<>();
        if (requested == null) return out;
        Set<String> included = includedGroups(plan);
        Set<String> groups = new HashSet<>();
        Set<LimitKey> keys = new HashSet<>();
        for (AddOnRequest r : requested) {
            if (r == null || r.kind() == null) throw new ValidationException("addOns", "each add-on needs a kind");
            out.add(wanted(in, plan, r, included, "addOns"));
            if (r.kind() == AddOnKind.GROUP && !groups.add(r.groupCode())) {
                throw new ValidationException("addOns", nameOf(in, r.groupCode()) + " is listed twice");
            }
            if (r.kind() == AddOnKind.LIMIT && !keys.add(r.limitKey())) {
                throw new ValidationException("addOns", r.limitKey().label() + " is listed twice");
            }
        }
        requireGroups(in, included, groups, "addOns");
        return out;
    }

    /** One add-on the plan offers, priced for a full period. */
    static Wanted wanted(QuoteInput in, Plan plan, AddOnRequest r, Set<String> included, String field) {
        if (r.kind() == AddOnKind.GROUP) {
            FeatureGroup g = r.groupCode() == null ? null : in.groups().get(r.groupCode());
            if (g == null) throw new ValidationException(field, r.groupCode() + " is not a feature group");
            if (g.getCategory() != GroupCategory.PAID) throw new ValidationException(field, g.getName() + " is in every plan already");
            if (included.contains(g.getCode())) throw new ValidationException(field, g.getName() + " is in the plan already");
            PlanItem offer = plan.getAddOns().stream().filter(i -> i.groupCode().equals(g.getCode())).findFirst()
                    .orElseThrow(() -> new ValidationException(field, g.getName() + " is not offered as an add-on by this plan"));
            return new Wanted(AddOnKind.GROUP, g.getCode(), null, 0, Money.round(offer.price()), g.getName());
        }
        LimitKey key = r.limitKey();
        if (key == null) throw new ValidationException(field, "extra units need a limit");
        LimitAddOn offer = plan.getLimitAddOns().stream().filter(l -> l.key() == key).findFirst()
                .orElseThrow(() -> new ValidationException(field, "This plan sells no extra " + key.noun()));
        int quantity = r.quantity() == null ? 0 : r.quantity();
        if (quantity < 1 || quantity % offer.unit() != 0) {
            throw new ValidationException(field, "Extra " + key.noun() + " are sold in units of " + offer.unit());
        }
        BigDecimal price = Money.round(offer.unitPrice().multiply(BigDecimal.valueOf(quantity / offer.unit())));
        return new Wanted(AddOnKind.LIMIT, null, key, quantity, price, quantity + " extra " + key.noun());
    }

    /** A held add-on taken into a new term: at the plan's price for it now, else at what it cost before. */
    static Wanted carried(SubscriptionAddOn held, Plan plan, BillingPeriod newPeriod, BillingPeriod oldPeriod,
            QuoteInput in) {
        if (held.getKind() == AddOnKind.GROUP) {
            BigDecimal price = plan.getAddOns().stream().filter(i -> i.groupCode().equals(held.getGroupCode()))
                    .map(PlanItem::price).findFirst().orElseGet(() -> scaled(held.getUnitPrice(), newPeriod, oldPeriod));
            return new Wanted(AddOnKind.GROUP, held.getGroupCode(), null, 0, Money.round(price),
                    nameOf(in, held.getGroupCode()));
        }
        BigDecimal price = plan.getLimitAddOns().stream().filter(l -> l.key() == held.getLimitKey()).findFirst()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(held.getQuantity()))
                        .divide(BigDecimal.valueOf(l.unit()), 10, RoundingMode.HALF_UP))
                .orElseGet(() -> scaled(held.getUnitPrice(), newPeriod, oldPeriod));
        return new Wanted(AddOnKind.LIMIT, null, held.getLimitKey(), held.getQuantity(), Money.round(price),
                held.getQuantity() + " extra " + held.getLimitKey().noun());
    }

    /** A full-period price moved to a period of another length. */
    private static BigDecimal scaled(BigDecimal periodPrice, BillingPeriod to, BillingPeriod from) {
        BigDecimal price = Money.zeroIfNull(periodPrice);
        if (to == from) return price;
        return price.multiply(BigDecimal.valueOf(to.months())).divide(BigDecimal.valueOf(from.months()), 10, RoundingMode.HALF_UP);
    }

    /** Every group among {@code added} has what it requires, in the plan or among the add-ons. */
    static void requireGroups(QuoteInput in, Set<String> included, Set<String> added, String field) {
        for (String code : added) {
            FeatureGroup g = in.groups().get(code);
            if (g == null) continue;
            for (String needs : g.getRequires()) {
                FeatureGroup needed = in.groups().get(needs);
                boolean always = needed != null && needed.getCategory() == GroupCategory.DEFAULT;
                if (!always && !included.contains(needs) && !added.contains(needs)) {
                    throw new ValidationException(field, g.getName() + " needs " + nameOf(in, needs));
                }
            }
        }
    }

    static String nameOf(QuoteInput in, String groupCode) {
        FeatureGroup g = in.groups().get(groupCode);
        return g == null ? groupCode : g.getName();
    }

    static LocalDate date(QuoteInput in, Instant at) {
        return at.atZone(in.zone()).toLocalDate();
    }

    static Instant instant(QuoteInput in, LocalDate day) {
        return day.atStartOfDay(in.zone()).toInstant();
    }

    static String span(LocalDate start, LocalDate end) {
        return "(" + DAY.format(start) + " – " + DAY.format(end.minusDays(1)) + ")";
    }

    static String periodWord(BillingPeriod period) {
        return switch (period) {
            case MONTHLY -> "monthly";
            case QUARTERLY -> "quarterly";
            case HALF_YEARLY -> "half-yearly";
            case YEARLY -> "yearly";
        };
    }

    static BigDecimal extraDiscount(QuoteInput in) {
        BigDecimal percent = in.extraDiscountPercent();
        if (percent == null || percent.signum() == 0) return BigDecimal.ZERO;
        if (!in.staff()) throw new ValidationException("extraDiscountPercent", "Only staff can give a discount");
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new ValidationException("extraDiscountPercent", "must be between 0 and 100");
        }
        if (percent.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("extraDiscountPercent", "at most 2 decimals");
        }
        return percent;
    }

    /** The totals of a set of lines and credits for this customer. */
    static Quote totals(QuoteInput in, List<OrderLine> lines, BigDecimal extraPercent, List<OrderCredit> credits,
            OrderTarget target, Plan plan) {
        BigDecimal taxable = Money.ZERO;
        for (OrderLine l : lines) taxable = taxable.add(l.amount());
        boolean interState = Gst.interState(in.settings().getSupplierStateCode(),
                in.customer() == null ? null : in.customer().stateCode());
        TaxAmounts tax = Gst.of(taxable, in.settings().getGstRatePercent(), interState);
        BigDecimal total = taxable.add(tax.total());
        BigDecimal fromNotes = Money.ZERO;
        for (OrderCredit c : credits) fromNotes = fromNotes.add(c.total());
        BigDecimal held = in.subscription() == null ? Money.ZERO : Money.round(Money.zeroIfNull(in.subscription().getCreditBalance()));
        BigDecimal used = total.min(held.add(fromNotes));
        return new Quote(in.kind(), List.copyOf(lines), extraPercent, taxable, tax, total, List.copyOf(credits), fromNotes,
                held, used, total.subtract(used), target, plan);
    }

    /** The lines of a term being sold and the segment they pay for, built together so each part knows what it cost. */
    static final class Draft {
        private final QuoteInput in;
        private final Plan plan;
        private final LocalDate start;
        private final LocalDate end;
        private final BigDecimal extra;
        private final List<OrderLine> lines = new ArrayList<>();
        private final TermSegment segment = new TermSegment();

        Draft(QuoteInput in, Plan plan, LocalDate start, LocalDate end) {
            this.in = in;
            this.plan = plan;
            this.start = start;
            this.end = end;
            this.extra = extraDiscount(in);
            segment.setPlanCode(plan.getCode());
            segment.setPlanRevision(plan.getRevision());
            segment.setTerms(PlanTerms.of(plan));
            segment.setGroupCodes(includedGroups(plan));
            segment.setAddOns(new ArrayList<>());
            segment.setTermStart(instant(in, start));
            segment.setTermEnd(instant(in, end));
        }

        void planLine() {
            PlanPricing.Price price = PlanPricing.price(plan.getItems(), plan.getDiscountPercent());
            BigDecimal amount = Money.percentOff(price.discounted(), extra);
            lines.add(new OrderLine(OrderLine.PLAN, plan.getName() + ", " + periodWord(plan.getPeriod()) + " " + span(start, end),
                    null, null, 0, price.actual(), amount));
            segment.setPlanPaid(amount);
        }

        /** @param keepId the id of the held add-on this continues, or null for a new one */
        void addOnLine(Wanted w, String keepId) {
            BigDecimal amount = Money.percentOff(w.periodPrice(), extra);
            lines.add(new OrderLine(w.kind() == AddOnKind.GROUP ? OrderLine.ADDON : OrderLine.LIMIT_ADDON,
                    "Add-on: " + w.label() + " " + span(start, end), w.groupCode(), w.limitKey(), w.quantity(),
                    w.periodPrice(), amount));
            SubscriptionAddOn a = new SubscriptionAddOn();
            a.setId(keepId == null ? UUID.randomUUID().toString() : keepId);
            a.setKind(w.kind());
            a.setGroupCode(w.groupCode());
            a.setLimitKey(w.limitKey());
            a.setQuantity(w.quantity());
            a.setUnitPrice(w.periodPrice());
            a.setPaid(amount);
            a.setCoveredFrom(segment.getTermStart());
            segment.getAddOns().add(a);
        }

        TermSegment segment() {
            return segment;
        }

        Quote finish(String which, int anchorDay, List<OrderCredit> credits) {
            OrderTarget target = new OrderTarget();
            target.setSegment(which);
            target.setSegmentAfter(segment);
            target.setAnchorDay(anchorDay);
            return totals(in, lines, extra, credits, target, plan);
        }
    }
}
