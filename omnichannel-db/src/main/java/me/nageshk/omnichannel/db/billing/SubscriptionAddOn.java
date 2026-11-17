package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.billing.LimitKey;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Something a customer holds on top of its plan for the current term. */
@Getter
@Setter
public class SubscriptionAddOn {

    private String id;
    private AddOnKind kind;
    /** For a GROUP add-on. */
    private String groupCode;
    /** For a LIMIT add-on. */
    private LimitKey limitKey;
    /** For a LIMIT add-on: how many more are allowed. */
    private int quantity;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** What was paid for it for this term, before tax; 0 when it was given free. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal paid = BigDecimal.ZERO;

    private Instant coveredFrom;
    private String orderId;
    private String invoiceId;
}
