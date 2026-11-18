package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A customer's prepaid balance for usage charges. Every change is one update that moves {@link #balance} and pushes
 * its ledger entry onto {@link #pending}, so the two cannot disagree; the entries are then copied to
 * {@code walletEntries} and taken off the list.
 */
@Document("wallets")
@Getter
@Setter
public class Wallet {

    @Id
    private String id;

    @Indexed(unique = true)
    private String customerId;

    /** Rupees, four decimals, never negative. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Below this the customer is warned; null for no warning. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal lowBalanceAt;

    /** Ledger entries not yet copied to {@code walletEntries}. */
    private List<WalletEntry> pending = new ArrayList<>();
    /** The orders (top-ups, wallet refunds) already applied, so one re-driven applies once. */
    private Set<String> orderIds = new HashSet<>();

    @Version
    private Long version;
}
