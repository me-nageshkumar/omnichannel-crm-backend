package me.nageshk.omnichannel.service.billing.pricing;

import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.BOLT;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.IST;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.applied;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.ask;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.bd;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.day;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.gold;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.groupAddOn;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.invoiceOf;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.limitAddOn;
import static me.nageshk.omnichannel.service.billing.pricing.PricingFixtures.platinum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.TermSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Steps B, C and D of the worked example, and the edges around them. */
class AddOnAndUpgradePricingTest {

    private static final LocalDate DEC_1 = day(2026, 12, 1);
    private static final LocalDate DEC_11 = day(2026, 12, 11);
    private static final LocalDate DEC_16 = day(2026, 12, 16);

    Map<String, Invoice> invoices;
    Subscription sub;

    private static Instant midnight(LocalDate d) {
        return d.atStartOfDay(IST).toInstant();
    }

    private PricingFixtures.Ask on(OrderKind kind, Plan plan, LocalDate today) {
        PricingFixtures.Ask a = ask(kind, sub, plan, today);
        a.invoices = invoices;
        return a;
    }

    private static void refused(PricingFixtures.Ask ask, String field, String message) {
        assertThatThrownBy(ask::quote).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.fields()).containsKey(field);
            if (message != null) assertThat(e.fields().get(field)).isEqualTo(message);
        });
    }

    /** Pays the quote's order: the subscription moves on and its invoice exists. */
    private Quote pay(PricingFixtures.Ask ask, String invoiceId, String number) {
        Quote q = ask.quote();
        sub = applied(sub, q, invoiceId);
        if (q.total().signum() > 0) invoices.put(invoiceId, invoiceOf(invoiceId, number, q));
        for (OrderCredit c : q.credits()) {
            Invoice credited = invoices.get(c.invoiceId());
            credited.setCredited(credited.getCredited().add(c.taxable()));
        }
        return q;
    }

    @BeforeEach
    void stepA() {
        invoices = new HashMap<>();
        sub = null;
        pay(on(OrderKind.NEW, gold(), DEC_1), "inv-a", "INV/26-27/000001");
    }

    private Quote stepB() {
        PricingFixtures.Ask b = on(OrderKind.ADDON, null, DEC_11);
        b.addOn = groupAddOn("automations");
        return pay(b, "inv-b", "INV/26-27/000002");
    }

    @Test
    void anAddOnCostsTheDaysThatAreLeft() {
        Quote b = stepB();
        assertThat(b.lines()).hasSize(1);
        assertThat(b.lines().get(0).description()).isEqualTo("Add-on: Automations (11 Dec 2026 – 31 Dec 2026)");
        assertThat(b.lines().get(0).listAmount()).isEqualByComparingTo("300.00");
        assertThat(b.taxable()).isEqualByComparingTo("203.23");
        assertThat(b.tax().cgst()).isEqualByComparingTo("18.29");
        assertThat(b.tax().sgst()).isEqualByComparingTo("18.29");
        assertThat(b.total()).isEqualByComparingTo("239.81");
        TermSegment seg = sub.getCurrent();
        assertThat(seg.getTermEnd()).isEqualTo(midnight(day(2027, 1, 1)));
        assertThat(seg.getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(seg.getAddOns()).hasSize(1);
        assertThat(seg.getAddOns().get(0).getPaid()).isEqualByComparingTo("203.23");
        assertThat(seg.getAddOns().get(0).getUnitPrice()).isEqualByComparingTo("300.00");
        assertThat(seg.getAddOns().get(0).getCoveredFrom()).isEqualTo(midnight(DEC_11));
        assertThat(seg.getAddOns().get(0).getInvoiceId()).isEqualTo("inv-b");
    }

    @Test
    void extraUnitsArePricedPerUnitForTheDaysLeft() {
        PricingFixtures.Ask c = on(OrderKind.ADDON, null, DEC_11);
        c.addOn = limitAddOn(LimitKey.USERS, 10);
        Quote q = c.quote();
        assertThat(q.taxable()).isEqualByComparingTo("135.48");
        assertThat(q.lines().get(0).description()).isEqualTo("Add-on: 10 extra users (11 Dec 2026 – 31 Dec 2026)");
        assertThat(q.target().getSegmentAfter().getAddOns().get(0).getQuantity()).isEqualTo(10);
        c.addOn = limitAddOn(LimitKey.USERS, 7);
        refused(c, "addOn", "Extra users are sold in units of 5");
        c.addOn = limitAddOn(LimitKey.BRANDS, 1);
        refused(c, "addOn", null);
    }

    @Test
    void anAddOnOnTheFirstAndTheLastDay() {
        PricingFixtures.Ask first = on(OrderKind.ADDON, null, DEC_1);
        first.addOn = groupAddOn("automations");
        assertThat(first.quote().taxable()).isEqualByComparingTo("300.00");
        PricingFixtures.Ask last = on(OrderKind.ADDON, null, day(2026, 12, 31));
        last.addOn = groupAddOn("automations");
        assertThat(last.quote().taxable()).isEqualByComparingTo("9.68");
    }

    @Test
    void addOnsAreBoughtWhileThePlanRunsAndOnlyOnce() {
        PricingFixtures.Ask grace = on(OrderKind.ADDON, null, day(2027, 1, 3));
        grace.addOn = groupAddOn("automations");
        refused(grace, "kind", "Add-ons can be bought while the plan is active");
        stepB();
        PricingFixtures.Ask again = on(OrderKind.ADDON, null, day(2026, 12, 12));
        again.addOn = groupAddOn("automations");
        refused(again, "addOn", null);
        PricingFixtures.Ask notOffered = on(OrderKind.ADDON, null, day(2026, 12, 12));
        notOffered.addOn = groupAddOn("campaigns");
        refused(notOffered, "addOn", null);
        sub = null;
        PricingFixtures.Ask noPlan = on(OrderKind.ADDON, null, DEC_11);
        noPlan.addOn = groupAddOn("automations");
        refused(noPlan, "kind", null);
    }

    @Test
    void anAddOnBoughtAfterAnEarlyRenewalCoversTheNextTermToo() {
        stepB();
        pay(on(OrderKind.RENEWAL, gold(), day(2026, 12, 20)), "inv-e", "INV/26-27/000003");
        PricingFixtures.Ask users = on(OrderKind.ADDON, null, day(2026, 12, 25));
        users.addOn = limitAddOn(LimitKey.USERS, 5);
        Quote q = users.quote();
        // 100 × 7/31 for the rest of December, and the whole of January
        assertThat(q.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("22.58", "100.00");
        assertThat(q.target().getSegmentAfter().getAddOns()).hasSize(2);
        assertThat(q.target().getNextAfter().getAddOns()).hasSize(2);
        assertThat(q.target().getNextAfter().getAddOns().get(1).getPaid()).isEqualByComparingTo("100.00");
        assertThat(q.target().getNextAfter().getAddOns().get(1).getCoveredFrom()).isEqualTo(midnight(day(2027, 1, 1)));
    }

    @Test
    void anUpgradeCreditsTheUnusedDaysOnTheInvoicesThatBilledThem() {
        stepB();
        Quote d = on(OrderKind.UPGRADE, platinum(), DEC_16).quote();

        assertThat(d.lines()).hasSize(1);
        assertThat(d.lines().get(0).description()).isEqualTo("Platinum, monthly (16 Dec 2026 – 15 Jan 2027)");
        assertThat(d.taxable()).isEqualByComparingTo("1500.00");
        assertThat(d.tax().total()).isEqualByComparingTo("270.00");
        assertThat(d.total()).isEqualByComparingTo("1770.00");

        assertThat(d.credits()).hasSize(2);
        OrderCredit onA = d.credits().get(0);
        assertThat(onA.invoiceId()).isEqualTo("inv-a");
        assertThat(onA.invoiceNumber()).isEqualTo("INV/26-27/000001");
        assertThat(onA.taxable()).isEqualByComparingTo("464.52");
        assertThat(onA.tax().cgst()).isEqualByComparingTo("41.81");
        assertThat(onA.tax().sgst()).isEqualByComparingTo("41.80");
        assertThat(onA.total()).isEqualByComparingTo("548.13");
        assertThat(onA.creditNoteId()).isNotBlank();
        OrderCredit onB = d.credits().get(1);
        assertThat(onB.invoiceId()).isEqualTo("inv-b");
        assertThat(onB.taxable()).isEqualByComparingTo("154.84");
        assertThat(onB.tax().cgst()).isEqualByComparingTo("13.94");
        assertThat(onB.tax().sgst()).isEqualByComparingTo("13.93");
        assertThat(onB.total()).isEqualByComparingTo("182.71");

        assertThat(d.creditFromNotes()).isEqualByComparingTo("730.84");
        assertThat(d.accountCreditUsed()).isEqualByComparingTo("730.84");
        assertThat(d.payable()).isEqualByComparingTo("1039.16");

        assertThat(d.target().getSegment()).isEqualTo(OrderTarget.CURRENT);
        assertThat(d.target().getAnchorDay()).isEqualTo(16);
        TermSegment seg = d.target().getSegmentAfter();
        assertThat(seg.getPlanCode()).isEqualTo("platinum-monthly");
        assertThat(seg.getTermStart()).isEqualTo(midnight(DEC_16));
        assertThat(seg.getTermEnd()).isEqualTo(midnight(day(2027, 1, 16)));
        assertThat(seg.getGroupCodes()).containsExactlyInAnyOrder("crm", "automations");
        // Platinum includes automations, so the add-on is not carried
        assertThat(seg.getAddOns()).isEmpty();
        assertThat(seg.getPlanPaid()).isEqualByComparingTo("1500.00");
    }

    @Test
    void aCreditCarriesTheTaxOfItsInvoice() {
        invoices = new HashMap<>();
        sub = null;
        PricingFixtures.Ask a = on(OrderKind.NEW, gold(), DEC_1);
        a.customer = BOLT;
        pay(a, "inv-f", "INV/26-27/000001");
        PricingFixtures.Ask d = on(OrderKind.UPGRADE, platinum(), DEC_16);
        d.customer = BOLT;
        // even if the rate has changed since, the credit is at the invoice's rate
        d.settings.setGstRatePercent(bd("28"));
        OrderCredit c = d.quote().credits().get(0);
        assertThat(c.tax().interState()).isTrue();
        assertThat(c.tax().igst()).isEqualByComparingTo("83.61");
        assertThat(c.tax().ratePercent()).isEqualByComparingTo("18");
        assertThat(c.total()).isEqualByComparingTo("548.13");
    }

    @Test
    void whatWasNotPaidForGivesNoCredit() {
        // a term given free (or assigned before orders existed) has no invoice
        sub.getCurrent().setPlanInvoiceId(null);
        Quote d = on(OrderKind.UPGRADE, platinum(), DEC_16).quote();
        assertThat(d.credits()).isEmpty();
        assertThat(d.payable()).isEqualByComparingTo("1770.00");
    }

    @Test
    void anUpgradeAfterAnEarlyRenewalCreditsTheWholeNextTerm() {
        stepB();
        pay(on(OrderKind.RENEWAL, gold(), DEC_16.minusDays(1)), "inv-e", "INV/26-27/000003");
        Quote d = on(OrderKind.UPGRADE, platinum(), DEC_16).quote();
        assertThat(d.credits()).extracting(c -> c.invoiceId() + " " + c.taxable().toPlainString())
                .containsExactly("inv-a 464.52", "inv-b 154.84", "inv-e 1200.00");
        // 548.13 + 182.71 + 1416.00 is more than the new total: nothing to pay, and the rest stays as credit
        assertThat(d.creditFromNotes()).isEqualByComparingTo("2146.84");
        assertThat(d.accountCreditUsed()).isEqualByComparingTo("1770.00");
        assertThat(d.payable()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditNeverExceedsWhatIsLeftOnItsInvoice() {
        invoices.get("inv-a").setCredited(bd("500.00"));
        Quote d = on(OrderKind.UPGRADE, platinum(), DEC_16).quote();
        assertThat(d.credits().get(0).taxable()).isEqualByComparingTo("400.00");
        invoices.get("inv-a").setCredited(bd("900.00"));
        assertThat(on(OrderKind.UPGRADE, platinum(), DEC_16).quote().credits()).isEmpty();
    }

    @Test
    void aCustomerMayOnlyGoUp() {
        pay(on(OrderKind.UPGRADE, platinum(), DEC_16), "inv-d", "INV/26-27/000002");
        PricingFixtures.Ask down = on(OrderKind.UPGRADE, gold(), day(2026, 12, 20));
        down.staff = false;
        refused(down, "planCode", "Only a higher plan can be requested");
        Plan hidden = platinum();
        hidden.setOffered(false);
        hidden.setRank(9);
        PricingFixtures.Ask notOffered = on(OrderKind.UPGRADE, hidden, day(2026, 12, 20));
        notOffered.staff = false;
        refused(notOffered, "planCode", null);

        // staff change it down: the credit is more than the new charge
        Quote change = on(OrderKind.CHANGE, gold(), day(2026, 12, 20)).quote();
        assertThat(change.total()).isEqualByComparingTo("1062.00");
        // 1500 × 27/31 = 1306.45 unused on invoice D
        assertThat(change.credits().get(0).taxable()).isEqualByComparingTo("1306.45");
        assertThat(change.payable()).isEqualByComparingTo("0");
    }

    @Test
    void aSameRankPlanWithALongerPeriodIsAnUpgrade() {
        Plan yearly = gold();
        yearly.setCode("gold-yearly");
        yearly.setPeriod(me.nageshk.omnichannel.common.billing.BillingPeriod.YEARLY);
        yearly.setItems(List.of(new PlanItem("crm", bd("10000"))));
        yearly.setAddOns(List.of(new PlanItem("automations", bd("3000"))));
        stepB();
        PricingFixtures.Ask up = on(OrderKind.UPGRADE, yearly, DEC_16);
        up.staff = false;
        Quote q = up.quote();
        // the add-on is carried for the new, yearly term at the yearly plan's price
        assertThat(q.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("9000.00", "3000.00");
        assertThat(q.target().getSegmentAfter().getTermEnd()).isEqualTo(midnight(day(2027, 12, 16)));
        assertThat(q.target().getSegmentAfter().getAddOns()).hasSize(1);
        assertThat(q.target().getSegmentAfter().getAddOns().get(0).getId()).isEqualTo(sub.getCurrent().getAddOns().get(0).getId());
    }

    @Test
    void aCarriedAddOnThePlanDoesNotOfferKeepsItsPriceScaledToThePeriod() {
        stepB();
        Plan yearly = gold();
        yearly.setCode("gold-yearly");
        yearly.setPeriod(me.nageshk.omnichannel.common.billing.BillingPeriod.YEARLY);
        yearly.setAddOns(List.of());
        Quote q = on(OrderKind.CHANGE, yearly, DEC_16).quote();
        // CHANGE with no add-ons named drops it
        assertThat(q.target().getSegmentAfter().getAddOns()).isEmpty();
        PricingFixtures.Ask keep = on(OrderKind.UPGRADE, yearly, DEC_16);
        assertThat(keep.quote().lines().get(1).amount()).isEqualByComparingTo("3600.00");
    }

    @Test
    void aChangeNamesTheAddOnsItKeepsAndTheyMustStillFit() {
        stepB();
        PricingFixtures.Ask keep = on(OrderKind.CHANGE, gold(), DEC_16);
        keep.addOns = List.of(groupAddOn("automations"));
        Quote q = keep.quote();
        assertThat(q.lines()).extracting(l -> l.amount().toPlainString()).containsExactly("900.00", "300.00");
        // both old components are credited whatever is kept
        assertThat(q.credits()).hasSize(2);

        PricingFixtures.Ask dropped = on(OrderKind.CHANGE, gold(), DEC_16);
        assertThat(dropped.quote().lines()).hasSize(1);
        assertThat(dropped.quote().credits()).hasSize(2);

        Plan withAi = gold();
        withAi.setAddOns(List.of(new PlanItem("ai", bd("200"))));
        PricingFixtures.Ask ai = on(OrderKind.CHANGE, withAi, DEC_16);
        ai.addOns = List.of(groupAddOn("ai"));
        refused(ai, "addOns", "AI needs Chatbots");
    }

    @Test
    void anUpgradeNeedsAPlanThatWasNotCancelled() {
        sub.setCancelled(true);
        refused(on(OrderKind.UPGRADE, platinum(), DEC_16), "kind", null);
        sub = null;
        refused(on(OrderKind.UPGRADE, platinum(), DEC_16), "kind", null);
    }
}
