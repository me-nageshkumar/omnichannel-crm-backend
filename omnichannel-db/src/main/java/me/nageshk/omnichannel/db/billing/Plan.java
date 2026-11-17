package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * What is sold for one period: the paid feature groups it includes, the ones it offers as add-ons, limits, and
 * usage rates. A subscription keeps a snapshot of these terms, so editing a plan never changes a running term.
 */
@Document("plans")
@Getter
@Setter
public class Plan {

    @Id
    private String code;

    private String name;
    private String description;
    private BillingPeriod period;
    /** Higher is a higher plan; plans of one tier in different periods share a rank. */
    private int rank;
    private List<PlanItem> items = new ArrayList<>();
    private List<PlanItem> addOns = new ArrayList<>();
    /** A key that is absent is unlimited. */
    private Map<LimitKey, Integer> limits = new EnumMap<>(LimitKey.class);
    private List<LimitAddOn> limitAddOns = new ArrayList<>();

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    /** Rupees per unit; a meter that is absent or 0 is free. */
    private Map<UsageMeter, BigDecimal> rates = new EnumMap<>(UsageMeter.class);
    /** Whether customers may pick the plan themselves. */
    private boolean offered;
    private PlanStatus status = PlanStatus.DRAFT;
    /** Counts edits made while ACTIVE. */
    private long revision;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @Version
    private Long version;
}
