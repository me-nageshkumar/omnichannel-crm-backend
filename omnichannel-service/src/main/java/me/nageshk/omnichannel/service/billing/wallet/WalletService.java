package me.nageshk.omnichannel.service.billing.wallet;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * The only way a wallet changes. Each operation is one update on the wallet document that moves the balance and
 * appends the complete ledger entry to its {@code pending} list, so the balance and the ledger cannot disagree; a
 * debit matches only while the balance covers it, so the balance cannot go below zero. Operations of an order carry
 * the order's id and match only while the wallet has not seen it, so an order driven again applies once.
 */
@Service
public class WalletService {

    public static final int SCALE = 4;
    private static final Decimal128 ZERO = new Decimal128(new BigDecimal("0.0000"));

    private final WalletRepository wallets;
    private final WalletMover mover;
    private final MongoTemplate mongo;
    private final Clock clock;

    public WalletService(WalletRepository wallets, WalletMover mover, MongoTemplate mongo, Clock clock) {
        this.wallets = wallets;
        this.mover = mover;
        this.mongo = mongo;
        this.clock = clock;
    }

    public static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public Optional<Wallet> find(String customerId) {
        return wallets.findByCustomerId(customerId);
    }

    /**
     * Takes {@code amount} from the wallet.
     *
     * @param draft the entry's type, meter, source, brand, reference, note and author; the rest is filled in here
     * @return the id of the ledger entry
     * @throws WalletEmptyException when the wallet holds less (or there is none); nothing is changed
     */
    public String debit(String customerId, BigDecimal amount, WalletEntry draft) {
        String id = change(customerId, scaled(amount).negate(), draft, null, false);
        if (id == null) throw new WalletEmptyException(customerId);
        return id;
    }

    /** Adds {@code amount} to the wallet, creating it on its first credit. Returns the entry's id. */
    public String credit(String customerId, BigDecimal amount, WalletEntry draft) {
        return change(customerId, scaled(amount), draft, null, true);
    }

    /** A credit made for an order: false, and nothing changed, when this order was credited before. */
    public boolean creditOnce(String orderId, String customerId, BigDecimal amount, WalletEntry draft) {
        return change(customerId, scaled(amount), draft, orderId, true) != null;
    }

    /**
     * A debit made for an order: false when this order was debited before.
     *
     * @throws WalletEmptyException when the wallet does not cover it
     */
    public boolean debitOnce(String orderId, String customerId, BigDecimal amount, WalletEntry draft) {
        if (change(customerId, scaled(amount).negate(), draft, orderId, false) != null) return true;
        boolean seen = wallets.findByCustomerId(customerId).map(w -> w.getOrderIds().contains(orderId)).orElse(false);
        if (!seen) throw new WalletEmptyException(customerId);
        return false;
    }

    /**
     * The one update. {@code signed} is added to the balance; when negative the wallet must cover it.
     *
     * @return the entry's id, or null when the update matched no wallet (not enough balance, or the order was seen)
     */
    private String change(String customerId, BigDecimal signed, WalletEntry draft, String orderId, boolean create) {
        String entryId = new ObjectId().toHexString();
        Decimal128 delta = new Decimal128(signed);
        Document filter = new Document("customerId", customerId);
        if (signed.signum() < 0) filter.append("balance", new Document("$gte", new Decimal128(signed.negate())));
        if (orderId != null) filter.append("orderIds", new Document("$ne", orderId));

        // stored as an ObjectId, as the mapping layer stores an entry's id everywhere else
        Document entry = new Document("_id", new ObjectId(entryId))
                .append("customerId", literal(customerId))
                .append("type", literal(draft.getType().name()))
                .append("amount", delta)
                // the balance as the first stage left it: the entry and the balance are written together
                .append("balanceAfter", "$balance")
                .append("meter", draft.getMeter() == null ? null : literal(draft.getMeter().name()))
                .append("source", draft.getSource() == null ? null : literal(draft.getSource().name()))
                .append("workspaceId", literal(draft.getWorkspaceId()))
                .append("ref", literal(draft.getRef()))
                .append("note", literal(draft.getNote()))
                .append("by", literal(draft.getBy()))
                .append("at", Date.from(clock.instant()));
        Document second = new Document("pending",
                new Document("$concatArrays", List.of(new Document("$ifNull", List.of("$pending", new ArrayList<>())), List.of(entry))))
                .append("version", new Document("$add", List.of(new Document("$ifNull", List.of("$version", 0L)), 1L)));
        if (orderId != null) {
            second.append("orderIds", new Document("$setUnion",
                    List.of(new Document("$ifNull", List.of("$orderIds", new ArrayList<>())), List.of(literal(orderId)))));
        }
        List<Document> pipeline = List.of(
                new Document("$set", new Document("balance",
                        new Document("$add", List.of(new Document("$ifNull", List.of("$balance", ZERO)), delta)))),
                new Document("$set", second));
        Document after;
        try {
            after = mongo.getCollection(mongo.getCollectionName(Wallet.class)).findOneAndUpdate(filter, pipeline,
                    new FindOneAndUpdateOptions().upsert(create).returnDocument(ReturnDocument.AFTER));
        } catch (MongoCommandException | MongoWriteException e) {
            // an upsert that lost to the wallet that is there (its order was seen, or it was created this instant)
            if (e.getCode() != 11000) throw e;
            if (orderId != null) return null;
            return change(customerId, signed, draft, null, false);
        }
        if (after == null) return null;
        mover.moveQuietly(customerId);
        return entryId;
    }

    /** A text as a value, never as a field path, whatever characters it starts with. */
    private static Object literal(String value) {
        return value == null ? null : new Document("$literal", value);
    }
}
