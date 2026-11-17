package me.nageshk.omnichannel.service.billing.pricing;

import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.ACME;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.BOLT;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.IST;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.applied;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.ask;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.bd;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.day;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.gold;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.groupAddOn;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.limitAddOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.TermSegment;
import org.junit.jupiter.api.Test;

class NewAndRenewalPricingTest {

    private static final LocalDate DEC_1 = day(2026, 12, 1);

    private static Instant midnight(LocalDate d) {
        return d.atStartOfDay(IST).toInstant();
    }

    private static void refused(PricingFixtures.Ask ask, String field, String message) {
        assertThatThrownBy(ask::quote).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.fields()).containsKey(field);
            if (message != null) assertThat(e.fields().get(field)).isEqualTo(message);
        });
    }

    /** Gold from 1 Dec, paid on invoice A. */
    static Subscription afterA() {
        Quote a = ask(OrderKind.NEW, null, gold(), DEC_1).quote();
        return applied(null, a, "inv-a");
    }

    @Test
    void aNewPlanIsItsDiscountedPricePlusTax() {
        Quote q = ask(OrderKind.NEW, null, gold(), DEC_1).quote();

        assertThat(q.lines()).hasSize(1);
        assertThat(q.lines().get(0).type()).isEqualTo("PLAN");
        assertThat(q.lines().get(0).description()).isEqualTo("Gold, monthly (1 Dec 2026 – 31 Dec 2026)");
        assertThat(q.lines().get(0).listAmount()).isEqualByComparingTo("1000.00");
        assertThat(q.lines().get(0).amount()).isEqualByComparingTo("900.00");
        assertThat(q.taxable()).isEqualByComparingTo("900.00");
        assertThat(q.tax().cgst()).isEqualByComparingTo("81.00");
        assertThat(q.tax().sgst()).isEqualByComparingTo("81.00");
        assertThat(q.tax().igst()).isEqualByComparingTo("0");
        assertThat(q.total()).isEqualByComparingTo("1062.00");
        assertThat(q.credits()).isEmpty();
        assertThat(q.accountCreditUsed()).isEqualByComparingTo("0");
        assertThat(q.payable()).isEqualByComparingTo("1062.00");

        OrderTarget t = q.target();
        assertThat(t.getSegment()).isEqualTo(OrderTarget.CURRENT);
        assertThat(t.getAnchorDay()).isEqualTo(1);
        TermSegment seg = t.getSegmentAfter();
        assertThat(seg.getPlanCode()).isEqualTo("gold-monthly");
        assertThat(seg.getTerms()).isEqualTo(PlanTerms.of(gold()));
        assertThat(seg.getGroupCodes()).containsExactly("crm");
        assertThat(seg.getTermStart()).isEqualTo(midnight(DEC_1));
        assertThat(seg.getTermEnd()).isEqualTo(midnight(day(2027, 1, 1)));
        assertThat(seg.getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(seg.getAddOns()).isEmpty();
    }

    @Test
    void aCustomerInAnotherStatePaysIgst() {
        PricingFixtures.Ask f = ask(OrderKind.NEW, null, gold(), DEC_1);
        f.customer = BOLT;
        Quote q = f.quote();
        assertThat(q.tax().igst()).isEqualByComparingTo("162.00");
        assertThat(q.tax().cgst()).isEqualByComparingTo("0");
        assertThat(q.tax().interState()).isTrue();
        assertThat(q.total()).isEqualByComparingTo("1062.00");
    }

    @Test
    void anExtraDiscountComesOffEveryLineAndIsStaffOnly() {
        PricingFixtures.Ask g = ask(OrderKind.NEW, null, gold(), DEC_1);
        g.extraDiscountPercent = bd("50");
        Quote q = g.quote();
        assertThat(q.taxable()).isEqualByComparingTo("450.00");
        assertThat(q.tax().total()).isEqualByComparingTo("81.00");
        assertThat(q.total()).isEqualByComparingTo("531.00");
        assertThat(q.target().getSegmentAfter().getPlanPaid()).isEqualByComparingTo("450.00");

        PricingFixtures.Ask free = ask(OrderKind.NEW, null, gold(), DEC_1);
        free.extraDiscountPercent = bd("100");
        assertThat(free.quote().payable()).isEqualByComparingTo("0");
        assertThat(free.quote().total()).isEqualByComparingTo("0");

        g.staff = false;
        refused(g, "extraDiscountPercent", null);
        PricingFixtures.Ask tooMuch = ask(OrderKind.NEW, null, gold(), DEC_1);
        tooMuch.extraDiscountPercent = bd("100.5");
        refused(tooMuch, "extraDiscountPercent", null);
        PricingFixtures.Ask tooFine = ask(OrderKind.NEW, null, gold(), DEC_1);
        tooFine.extraDiscountPercent = bd("10.123");
        refused(tooFine, "extraDiscountPercent", null);
    }

    @Test
    void addOnsAtTheStartCostAFullPeriod() {
        PricingFixtures.Ask f = ask(OrderKind.NEW, null, gold(), DEC_1);
        f.addOns = List.of(groupAddOn("automations"), limitAddOn(LimitKey.USERS, 10));
        Quote q = f.quote();
        assertThat(q.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("900.00", "300.00", "200.00");
        assertThat(q.lines().get(1).description()).isEqualTo("Add-on: Automations (1 Dec 2026 – 31 Dec 2026)");
        assertThat(q.lines().get(2).description()).isEqualTo("Add-on: 10 extra users (1 Dec 2026 – 31 Dec 2026)");
        assertThat(q.taxable()).isEqualByComparingTo("1400.00");
        TermSegment seg = q.target().getSegmentAfter();
        assertThat(seg.getAddOns()).hasSize(2);
        assertThat(seg.getAddOns().get(0).getPaid()).isEqualByComparingTo("300.00");
        assertThat(seg.getAddOns().get(0).getCoveredFrom()).isEqualTo(midnight(DEC_1));
        assertThat(seg.getAddOns().get(1).getQuantity()).isEqualTo(10);
        assertThat(seg.getAddOns().get(1).getPaid()).isEqualByComparingTo("200.00");

        PricingFixtures.Ask seven = ask(OrderKind.NEW, null, gold(), DEC_1);
        seven.addOns = List.of(limitAddOn(LimitKey.USERS, 7));
        refused(seven, "addOns", "Extra users are sold in units of 5");
        PricingFixtures.Ask notOffered = ask(OrderKind.NEW, null, gold(), DEC_1);
        notOffered.addOns = List.of(groupAddOn("campaigns"));
        refused(notOffered, "addOns", null);
        PricingFixtures.Ask included = ask(OrderKind.NEW, null, gold(), DEC_1);
        included.addOns = List.of(groupAddOn("crm"));
        refused(included, "addOns", null);
    }

    @Test
    void aNewPlanNeedsAnActivePlanAndACustomerWithoutOne() {
        Plan draft = gold();
        draft.setStatus(PlanStatus.DRAFT);
        refused(ask(OrderKind.NEW, null, draft, DEC_1), "planCode", null);
        refused(ask(OrderKind.NEW, afterA(), gold(), day(2026, 12, 10)), "kind",
                "The customer has a plan; change or renew it");
        // once it has lapsed into read-only, a new plan is the way back
        Quote again = ask(OrderKind.NEW, afterA(), gold(), day(2027, 1, 20)).quote();
        assertThat(again.target().getSegmentAfter().getTermStart()).isEqualTo(midnight(day(2027, 1, 20)));
        assertThat(again.target().getAnchorDay()).isEqualTo(20);
    }

    @Test
    void aStartDateMovesTheTermAndMustNotLeaveItInThePast() {
        PricingFixtures.Ask f = ask(OrderKind.NEW, null, gold(), DEC_1);
        f.startDate = day(2026, 11, 20);
        Quote q = f.quote();
        assertThat(q.target().getSegmentAfter().getTermStart()).isEqualTo(midnight(day(2026, 11, 20)));
        assertThat(q.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2026, 12, 20)));
        f.startDate = day(2026, 10, 1);
        refused(f, "startDate", null);
    }

    @Test
    void aTermAnchoredOnThe31stEndsOnTheLastDayOfShortMonths() {
        Quote jan = ask(OrderKind.NEW, null, gold(), day(2027, 1, 31)).quote();
        assertThat(jan.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2027, 2, 28)));
        assertThat(jan.target().getAnchorDay()).isEqualTo(31);
        Subscription sub = applied(null, jan, "inv-a");
        Quote renewal = ask(OrderKind.RENEWAL, sub, gold(), day(2027, 2, 10)).quote();
        assertThat(renewal.target().getSegmentAfter().getTermStart()).isEqualTo(midnight(day(2027, 2, 28)));
        assertThat(renewal.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2027, 3, 31)));
    }

    @Test
    void accountCreditIsUsedFirst() {
        Subscription lapsed = afterA();
        lapsed.setCreditBalance(bd("500.00"));
        Quote q = ask(OrderKind.NEW, lapsed, gold(), day(2027, 1, 20)).quote();
        assertThat(q.total()).isEqualByComparingTo("1062.00");
        assertThat(q.accountCredit()).isEqualByComparingTo("500.00");
        assertThat(q.accountCreditUsed()).isEqualByComparingTo("500.00");
        assertThat(q.payable()).isEqualByComparingTo("562.00");
        lapsed.setCreditBalance(bd("5000.00"));
        Quote covered = ask(OrderKind.NEW, lapsed, gold(), day(2027, 1, 20)).quote();
        assertThat(covered.accountCreditUsed()).isEqualByComparingTo("1062.00");
        assertThat(covered.payable()).isEqualByComparingTo("0");
    }

    @Test
    void aRenewalPaidEarlyIsTheNextTermWithItsAddOns() {
        Subscription sub = afterA();
        PricingFixtures.Ask b = ask(OrderKind.NEW, null, gold(), DEC_1);
        b.addOns = List.of(groupAddOn("automations"));
        sub = applied(null, b.quote(), "inv-a");

        Quote e = ask(OrderKind.RENEWAL, sub, gold(), day(2026, 12, 20)).quote();

        assertThat(e.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("900.00", "300.00");
        assertThat(e.taxable()).isEqualByComparingTo("1200.00");
        assertThat(e.tax().total()).isEqualByComparingTo("216.00");
        assertThat(e.total()).isEqualByComparingTo("1416.00");
        assertThat(e.credits()).isEmpty();
        assertThat(e.target().getSegment()).isEqualTo(OrderTarget.NEXT);
        TermSegment next = e.target().getSegmentAfter();
        assertThat(next.getTermStart()).isEqualTo(midnight(day(2027, 1, 1)));
        assertThat(next.getTermEnd()).isEqualTo(midnight(day(2027, 2, 1)));
        assertThat(next.getAddOns()).hasSize(1);
        assertThat(next.getAddOns().get(0).getCoveredFrom()).isEqualTo(midnight(day(2027, 1, 1)));
        assertThat(next.getAddOns().get(0).getId()).isEqualTo(sub.getCurrent().getAddOns().get(0).getId());

        PricingFixtures.Ask dropped = ask(OrderKind.RENEWAL, sub, gold(), day(2026, 12, 20));
        dropped.keepAddOnIds = List.of();
        assertThat(dropped.quote().taxable()).isEqualByComparingTo("900.00");
        assertThat(dropped.quote().target().getSegmentAfter().getAddOns()).isEmpty();
    }

    @Test
    void renewalOpensInsideTheWindowAndFollowsTheState() {
        Subscription sub = afterA();
        refused(ask(OrderKind.RENEWAL, sub, gold(), day(2026, 11, 15)), "kind", "Renewal opens on 2 Dec 2026");
        assertThat(ask(OrderKind.RENEWAL, sub, gold(), day(2026, 12, 2)).quote().target().getSegment())
                .isEqualTo(OrderTarget.NEXT);

        Quote grace = ask(OrderKind.RENEWAL, sub, gold(), day(2027, 1, 3)).quote();
        assertThat(grace.target().getSegment()).isEqualTo(OrderTarget.CURRENT);
        assertThat(grace.target().getSegmentAfter().getTermStart()).isEqualTo(midnight(day(2027, 1, 1)));
        assertThat(grace.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2027, 2, 1)));

        Quote expired = ask(OrderKind.RENEWAL, sub, gold(), day(2027, 1, 20)).quote();
        assertThat(expired.target().getSegment()).isEqualTo(OrderTarget.CURRENT);
        assertThat(expired.target().getSegmentAfter().getTermStart()).isEqualTo(midnight(day(2027, 1, 20)));
        assertThat(expired.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2027, 2, 20)));
        assertThat(expired.target().getAnchorDay()).isEqualTo(20);
    }

    @Test
    void aRenewalUsesThePlanAsItIsNowAndKeepsOldPricesOfWhatItNoLongerOffers() {
        PricingFixtures.Ask b = ask(OrderKind.NEW, null, gold(), DEC_1);
        b.addOns = List.of(groupAddOn("automations"));
        Subscription sub = applied(null, b.quote(), "inv-a");

        Plan now = gold();
        now.setItems(List.of(new PlanItem("crm", bd("1200"))));
        now.setAddOns(List.of());
        now.setRevision(1);
        Quote q = ask(OrderKind.RENEWAL, sub, now, day(2026, 12, 20)).quote();
        assertThat(q.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("1080.00", "300.00");
        assertThat(q.target().getSegmentAfter().getPlanRevision()).isEqualTo(1);
        assertThat(q.target().getSegmentAfter().getTerms()).isEqualTo(PlanTerms.of(now));

        // a retired plan is renewed by staff only
        now.setStatus(PlanStatus.RETIRED);
        assertThat(ask(OrderKind.RENEWAL, sub, now, day(2026, 12, 20)).quote().total()).isPositive();
        PricingFixtures.Ask customer = ask(OrderKind.RENEWAL, sub, now, day(2026, 12, 20));
        customer.staff = false;
        refused(customer, "planCode", null);
    }

    @Test
    void aRenewalNeedsAPlanAndNoRenewalAlreadyWaiting() {
        refused(ask(OrderKind.RENEWAL, null, gold(), DEC_1), "kind", null);
        Subscription sub = afterA();
        Subscription renewed = applied(sub, ask(OrderKind.RENEWAL, sub, gold(), day(2026, 12, 20)).quote(), "inv-e");
        refused(ask(OrderKind.RENEWAL, renewed, gold(), day(2026, 12, 21)), "kind", null);
        assertThat(ACME.complete()).isTrue();
    }
}
