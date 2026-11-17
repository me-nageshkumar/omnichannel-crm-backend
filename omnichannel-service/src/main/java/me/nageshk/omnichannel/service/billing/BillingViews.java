package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.LimitAddOn;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanStatus;

/** What the billing API takes and returns for feature groups and plans. */
public final class BillingViews {

    private BillingViews() {}

    public record GroupRequest(String code, String name, String description, GroupCategory category,
            Set<String> authorities, Set<String> requires, Integer sortOrder, Long version) {}

    /** @param planCodes the plans that include the group or offer it as an add-on */
    public record GroupView(String code, String name, String description, GroupCategory category,
            List<String> authorities, List<String> requires, boolean builtIn, int sortOrder, Long version,
            List<String> planCodes) {}

    public record UngroupedView(String code, String description, RoleLevel level) {}

    public record PlanRequest(String code, String name, String description, BillingPeriod period, Integer rank,
            List<PlanItem> items, List<PlanItem> addOns, Map<LimitKey, Integer> limits, List<LimitAddOn> limitAddOns,
            BigDecimal discountPercent, Map<UsageMeter, BigDecimal> rates, Boolean offered, Long version) {}

    /** @param customers how many customers are on the plan now or have renewed onto it */
    public record PlanView(String code, String name, String description, BillingPeriod period, int rank,
            List<PlanItem> items, List<PlanItem> addOns, Map<LimitKey, Integer> limits, List<LimitAddOn> limitAddOns,
            BigDecimal discountPercent, Map<UsageMeter, BigDecimal> rates, boolean offered, PlanStatus status,
            long revision, Long version, BigDecimal actualPrice, BigDecimal discountAmount, BigDecimal discountedPrice,
            long customers) {}

    public record PriceRequest(List<PlanItem> items, BigDecimal discountPercent) {}

    public record PriceView(BigDecimal actualPrice, BigDecimal discountAmount, BigDecimal discountedPrice) {}

    public record DuplicateRequest(String code, String name, BillingPeriod period) {}

    /** One row of a history: who changed what, when. */
    public record EventView(String id, String kind, String by, String byName, Instant at, String summary) {}
}
