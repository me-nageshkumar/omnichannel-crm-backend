package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A customer's plan. Whether it is active, in grace or expired is worked out from the dates each time it is read,
 * so nothing has to run for a customer to change state.
 */
@Document("subscriptions")
@CompoundIndex(name = "next_termStart", def = "{'next.termStart': 1}", sparse = true)
@Getter
@Setter
public class Subscription {

    @Id
    private String id;

    @Indexed(unique = true)
    private String customerId;

    private TermSegment current;
    /** A renewal paid before the current term ended; in force from its own start. */
    private TermSegment next;
    /** The day of month terms end on. */
    private int anchorDay;

    /** Money the platform owes the customer, tax included. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    /** Counts changes to a segment's plan or dates. */
    private long termRevision;
    /** Counts every applied change. */
    private long revision;
    private Set<String> appliedOrderIds = new HashSet<>();
    /** Orders whose credit notes have been added to, and whose use of credit has been taken from, the balance. */
    private Set<String> creditedOrderIds = new HashSet<>();
    private Set<String> debitedOrderIds = new HashSet<>();
    private Set<String> refundIds = new HashSet<>();
    /** The plan was cancelled: read-only from the term's end, with no grace, until a new plan is bought. */
    private boolean cancelled;

    @Version
    private Long version;
}
