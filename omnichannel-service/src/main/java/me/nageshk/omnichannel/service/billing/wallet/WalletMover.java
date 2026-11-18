package me.nageshk.omnichannel.service.billing.wallet;

import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Copies a wallet's pending ledger entries into {@code walletEntries} and takes them off the wallet. An entry is
 * written under its own id before it is removed, so stopping anywhere, or running twice, leaves one row.
 */
@Component
public class WalletMover {

    private static final Logger log = LoggerFactory.getLogger(WalletMover.class);

    private final WalletRepository wallets;
    private final MongoTemplate mongo;

    public WalletMover(WalletRepository wallets, MongoTemplate mongo) {
        this.wallets = wallets;
        this.mongo = mongo;
    }

    /** @throws RuntimeException if the database fails; what was not moved stays pending */
    public void move(String customerId) {
        Wallet wallet = wallets.findByCustomerId(customerId).orElse(null);
        if (wallet == null) return;
        for (WalletEntry entry : wallet.getPending()) {
            mongo.save(entry);
            mongo.getCollection(mongo.getCollectionName(Wallet.class)).updateOne(
                    new Document("customerId", customerId),
                    new Document("$pull", new Document("pending", new Document("_id", new ObjectId(entry.getId())))));
        }
    }

    /** As {@link #move}, for callers whose own work is done and must not fail because of this. */
    public void moveQuietly(String customerId) {
        try {
            move(customerId);
        } catch (RuntimeException e) {
            log.warn("could not move the wallet entries of customer {}; they stay pending", customerId, e);
        }
    }
}
