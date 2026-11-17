package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** The commercial terms of a plan as they were when a term was sold. */
public record PlanTerms(String planName, BillingPeriod period, int rank,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal discountPercent,
        List<PlanItem> items, List<PlanItem> addOns, List<LimitAddOn> limitAddOns,
        Map<LimitKey, Integer> limits, Map<UsageMeter, BigDecimal> rates) {

    public PlanTerms {
        items = items == null ? List.of() : List.copyOf(items);
        addOns = addOns == null ? List.of() : List.copyOf(addOns);
        limitAddOns = limitAddOns == null ? List.of() : List.copyOf(limitAddOns);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        rates = rates == null ? Map.of() : Map.copyOf(rates);
    }

    public static PlanTerms of(Plan plan) {
        return new PlanTerms(plan.getName(), plan.getPeriod(), plan.getRank(), plan.getDiscountPercent(),
                plan.getItems(), plan.getAddOns(), plan.getLimitAddOns(), plan.getLimits(), plan.getRates());
    }
}
