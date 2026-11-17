package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One paid term of a subscription: the plan as sold, what is held on top, and the dates. */
@Getter
@Setter
public class TermSegment {

    private String planCode;
    private long planRevision;
    private PlanTerms terms;
    /** The plan's paid feature groups. */
    private Set<String> groupCodes = new HashSet<>();
    private List<SubscriptionAddOn> addOns = new ArrayList<>();
    /** 00:00 of the first day, in the billing zone. */
    private Instant termStart;
    /** 00:00 of the day after the last one, in the billing zone. */
    private Instant termEnd;

    /** What was paid for the plan for this term, before tax; 0 when it was given free. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal planPaid = BigDecimal.ZERO;
}
