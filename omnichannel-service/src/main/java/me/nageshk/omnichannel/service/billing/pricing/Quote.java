package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.util.List;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderLine;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.TaxAmounts;

/**
 * A priced request: what is charged, what comes back as credit, what is left to pay, and what the subscription
 * becomes. An order stores exactly this.
 *
 * @param taxable the charges after discounts, before tax
 * @param creditFromNotes the total, tax included, of the credit notes the order would issue
 * @param accountCredit the account credit the customer holds now
 * @param accountCreditUsed how much credit (held and from the notes) goes against the total
 * @param plan the plan the lines are for; null for an add-on on the plan as sold
 */
public record Quote(OrderKind kind, List<OrderLine> lines, BigDecimal extraDiscountPercent, BigDecimal taxable,
        TaxAmounts tax, BigDecimal total, List<OrderCredit> credits, BigDecimal creditFromNotes,
        BigDecimal accountCredit, BigDecimal accountCreditUsed, BigDecimal payable, OrderTarget target, Plan plan) {}
