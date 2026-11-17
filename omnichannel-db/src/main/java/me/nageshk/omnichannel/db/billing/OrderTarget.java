package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** What activating an order does to the subscription, worked out when the order was raised. */
@Getter
@Setter
public class OrderTarget {

    public static final String CURRENT = "CURRENT";
    public static final String NEXT = "NEXT";

    /** Which segment {@link #segmentAfter} becomes: CURRENT (replacing it and dropping any next one) or NEXT. */
    private String segment;
    /** The segment as it is after the order; for an add-on, the current segment with the add-on in it. */
    private TermSegment segmentAfter;
    /** For an add-on bought while a renewal is waiting: that next segment with the add-on in it. */
    private TermSegment nextAfter;
    private Integer anchorDay;
    /** CANCEL_ADDON: the add-on to take out. */
    private String removeAddOnId;
    /** ADJUST: the new end of the last paid segment. */
    private Instant newTermEnd;
    /** ADJUST: an amount added to (or, when negative, taken from) the account credit. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal creditPosting;
    /** CANCEL_PLAN: the plan ends today and the customer turns read-only at once. */
    private boolean cancelPlan;
}
