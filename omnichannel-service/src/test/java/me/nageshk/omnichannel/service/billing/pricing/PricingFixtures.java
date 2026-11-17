package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.LimitAddOn;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnRequest;

/** The worked example of the phase 2 plan: Gold and Platinum, Acme in the supplier's state and Bolt outside it. */
final class PricingFixtures {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private PricingFixtures() {}

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    static Plan gold() {
        Plan p = new Plan();
        p.setCode("gold-monthly");
        p.setName("Gold");
        p.setPeriod(BillingPeriod.MONTHLY);
        p.setRank(2);
        p.setItems(List.of(new PlanItem("crm", bd("1000"))));
        p.setAddOns(List.of(new PlanItem("automations", bd("300"))));
        p.setLimits(new HashMap<>(Map.of(LimitKey.USERS, 5)));
        p.setLimitAddOns(List.of(new LimitAddOn(LimitKey.USERS, bd("100"), 5)));
        p.setDiscountPercent(bd("10"));
        p.setOffered(true);
        p.setStatus(PlanStatus.ACTIVE);
        return p;
    }

    static Plan platinum() {
        Plan p = new Plan();
        p.setCode("platinum-monthly");
        p.setName("Platinum");
        p.setPeriod(BillingPeriod.MONTHLY);
        p.setRank(3);
        p.setItems(List.of(new PlanItem("crm", bd("1000")), new PlanItem("automations", bd("500"))));
        p.setDiscountPercent(BigDecimal.ZERO);
        p.setOffered(true);
        p.setStatus(PlanStatus.ACTIVE);
        return p;
    }

    static Map<String, FeatureGroup> groups() {
        Map<String, FeatureGroup> all = new HashMap<>();
        all.put("core", group("core", "Core", GroupCategory.DEFAULT));
        all.put("inbox", group("inbox", "Inbox", GroupCategory.DEFAULT));
        all.put("crm", group("crm", "CRM", GroupCategory.PAID));
        all.put("automations", group("automations", "Automations", GroupCategory.PAID));
        all.put("campaigns", group("campaigns", "Campaigns", GroupCategory.PAID));
        FeatureGroup chatbots = group("chatbots", "Chatbots", GroupCategory.PAID);
        chatbots.setRequires(new HashSet<>(Set.of("automations")));
        all.put("chatbots", chatbots);
        FeatureGroup ai = group("ai", "AI", GroupCategory.PAID);
        ai.setRequires(new HashSet<>(Set.of("chatbots")));
        all.put("ai", ai);
        return all;
    }

    private static FeatureGroup group(String code, String name, GroupCategory category) {
        FeatureGroup g = new FeatureGroup();
        g.setCode(code);
        g.setName(name);
        g.setCategory(category);
        return g;
    }

    static BillingSettings settings() {
        BillingSettings s = new BillingSettings();
        s.setSupplierLegalName("Omni Cloud Pvt Ltd");
        s.setSupplierAddress("12 Residency Road, Bengaluru");
        s.setSupplierStateCode("29");
        return s;
    }

    static final BillingProfile ACME = new BillingProfile("Acme Retail Pvt Ltd", "1 MG Road, Bengaluru", "29", null);
    static final BillingProfile BOLT = new BillingProfile("Bolt Traders", "9 FC Road, Pune", "27", null);

    /** A request builder: start from {@link #ask} and set what the case needs. */
    static final class Ask {
        OrderKind kind;
        Subscription subscription;
        Plan plan;
        BillingSettings settings = settings();
        BillingProfile customer = ACME;
        LocalDate today;
        LocalDate startDate;
        List<AddOnRequest> addOns = List.of();
        List<String> keepAddOnIds;
        AddOnRequest addOn;
        BigDecimal extraDiscountPercent;
        boolean staff = true;
        Map<String, Invoice> invoices = new HashMap<>();

        Quote quote() {
            SubscriptionState state = subscription == null || subscription.getCurrent() == null ? SubscriptionState.NONE
                    : SubscriptionClock.stateAt(subscription, settings.getGraceDays(), IST,
                            today.atStartOfDay(IST).toInstant());
            return OrderPricing.quote(new QuoteInput(kind, subscription, state, plan, groups(), settings, customer, IST,
                    today, startDate, addOns, keepAddOnIds, addOn, extraDiscountPercent, staff, invoices::get));
        }
    }

    static Ask ask(OrderKind kind, Subscription subscription, Plan plan, LocalDate today) {
        Ask a = new Ask();
        a.kind = kind;
        a.subscription = subscription;
        a.plan = plan;
        a.today = today;
        return a;
    }

    /** The subscription as it is after the quote's order was paid and billed on the invoice. */
    static Subscription applied(Subscription before, Quote q, String invoiceId) {
        Subscription s = before == null ? new Subscription() : before;
        OrderTarget t = q.target();
        if (s.getCustomerId() == null) s.setCustomerId("c1");
        if (q.kind() == OrderKind.ADDON) {
            stamp(t.getSegmentAfter(), invoiceId, false);
            s.setCurrent(t.getSegmentAfter());
            if (t.getNextAfter() != null) {
                stamp(t.getNextAfter(), invoiceId, false);
                s.setNext(t.getNextAfter());
            }
        } else {
            stamp(t.getSegmentAfter(), invoiceId, true);
            if (OrderTarget.NEXT.equals(t.getSegment())) {
                s.setNext(t.getSegmentAfter());
            } else {
                s.setCurrent(t.getSegmentAfter());
                s.setNext(null);
            }
            if (t.getAnchorDay() != null) s.setAnchorDay(t.getAnchorDay());
        }
        s.setRevision(s.getRevision() + 1);
        return s;
    }

    private static void stamp(TermSegment seg, String invoiceId, boolean plan) {
        if (plan && seg.getPlanInvoiceId() == null && seg.getPlanPaid().signum() > 0) seg.setPlanInvoiceId(invoiceId);
        for (SubscriptionAddOn a : seg.getAddOns()) {
            if (a.getInvoiceId() == null && a.getPaid().signum() > 0) a.setInvoiceId(invoiceId);
        }
    }

    /** An invoice as the order of the quote would issue it. */
    static Invoice invoiceOf(String id, String number, Quote q) {
        Invoice i = new Invoice();
        i.setId(id);
        i.setNumber(number);
        i.setTaxable(q.taxable());
        i.setTax(q.tax());
        i.setTotal(q.total());
        return i;
    }

    static AddOnRequest groupAddOn(String code) {
        return new AddOnRequest(AddOnKind.GROUP, code, null, null);
    }

    static AddOnRequest limitAddOn(LimitKey key, int quantity) {
        return new AddOnRequest(AddOnKind.LIMIT, null, key, quantity);
    }

    static List<SubscriptionAddOn> addOnsOf(TermSegment seg) {
        return new ArrayList<>(seg.getAddOns());
    }
}
