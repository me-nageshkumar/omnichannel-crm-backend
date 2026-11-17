package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Something a customer buys or staff do to its subscription, with every amount fixed when it was raised. Staff
 * activate it by recording the payment; the steps that follow are listed in {@link #steps} as they finish, so an
 * interrupted activation can be finished by running them again.
 */
@Document("billingOrders")
@CompoundIndex(name = "customer_status", def = "{'customerId': 1, 'status': 1}")
@CompoundIndex(name = "status_raisedAt", def = "{'status': 1, 'raisedAt': 1}")
@Getter
@Setter
public class BillingOrder {

    public static final String STEP_APPLIED = "applied";
    public static final String STEP_CREDIT_NOTED = "creditNoted";
    public static final String STEP_INVOICED = "invoiced";

    @Id
    private String id;

    @Indexed(unique = true)
    private String number;

    private String customerId;
    private OrderKind kind;
    private OrderStatus status;
    private List<OrderLine> lines = new ArrayList<>();

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal extraDiscountPercent = BigDecimal.ZERO;
    private String extraDiscountReason;

    /** The charges after discounts, before tax. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal taxable = BigDecimal.ZERO;
    private TaxAmounts tax;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal total = BigDecimal.ZERO;

    private List<OrderCredit> credits = new ArrayList<>();
    /** The total, tax included, of the credit notes this order issues. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal creditFromNotes = BigDecimal.ZERO;
    /** The account credit the customer held when the order was raised. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal accountCredit = BigDecimal.ZERO;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal accountCreditUsed = BigDecimal.ZERO;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal payable = BigDecimal.ZERO;

    private String planCode;
    private String planName;
    private OrderTarget target;
    /** The subscription's revision and term revision the quote was made against. */
    private long quotedRevision;
    private long quotedTermRevision;
    private Instant validUntil;
    private String raisedBy;
    private boolean raisedByStaff;
    private Instant raisedAt;
    /** Why staff did it: mandatory for adjustments and cancellations. */
    private String reason;
    private OrderPayment payment;
    private Instant activatingSince;
    /** Reserved when the order is raised with something to charge; the invoice is inserted under this id. */
    private String invoiceId;
    private String rejectedReason;
    private Set<String> steps = new HashSet<>();

    @Version
    private Long version;
}
