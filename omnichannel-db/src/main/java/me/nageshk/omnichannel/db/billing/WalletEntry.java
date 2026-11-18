package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One line of a wallet's ledger. It is written first inside the wallet document, in the same update that moves the
 * balance, and copied here afterwards; kept for good.
 */
@Document("walletEntries")
@CompoundIndex(name = "customer_at", def = "{'customerId': 1, 'at': -1}")
@CompoundIndex(name = "customer_type_at", def = "{'customerId': 1, 'type': 1, 'at': 1}")
@Getter
@Setter
public class WalletEntry {

    @Id
    private String id;

    private String customerId;
    private WalletEntryType type;

    /** Signed: what was added to (or, negative, taken from) the balance. Four decimals. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal balanceAfter;

    private UsageMeter meter;
    private UsageSource source;
    private String workspaceId;
    /** The order's number, the campaign's id, or for a reversal the entry it gives back. */
    private String ref;
    private String note;
    private String by;
    private Instant at;
    /** A reversal: when the charge it gives back was taken, so usage is counted on the day of the charge. */
    private Instant usageAt;
}
