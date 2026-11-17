package me.nageshk.omnichannel.service.billing.document;

import java.util.function.IntFunction;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Numbers invoices and credit notes 1, 2, 3 within a financial year with no gap: a number exists only as part of
 * the document that carries it. The next number is the highest stored one plus one; the unique index on
 * {@code {financialYear, sequence}} refuses a second document with the same number, and the loser tries the next.
 */
@Component
public class DocumentNumbers {

    private static final int ATTEMPTS = 50;

    private final MongoTemplate mongo;

    public DocumentNumbers(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public static String number(String prefix, String financialYear, int sequence) {
        return String.format("%s/%s/%06d", prefix, financialYear, sequence);
    }

    /**
     * Inserts the document {@code withSequence} builds, under the next number of the year. A document with this id
     * that is there already is returned as it is, so issuing can be repeated.
     *
     * @param type Invoice or CreditNote: a document with {@code financialYear} and {@code sequence}
     * @param id the id the built document carries
     */
    public <T> T insertNumbered(Class<T> type, String id, String financialYear, IntFunction<T> withSequence) {
        DuplicateKeyException last = null;
        for (int i = 0; i < ATTEMPTS; i++) {
            T existing = mongo.findById(id, type);
            if (existing != null) return existing;
            org.bson.Document top = mongo.getCollection(mongo.getCollectionName(type))
                    .find(new org.bson.Document("financialYear", financialYear))
                    .projection(new org.bson.Document("sequence", 1))
                    .sort(new org.bson.Document("sequence", -1)).limit(1).first();
            int next = top == null ? 1 : top.getInteger("sequence") + 1;
            try {
                return mongo.insert(withSequence.apply(next));
            } catch (DuplicateKeyException taken) {
                last = taken;
            }
        }
        throw new IllegalStateException("could not number a " + type.getSimpleName() + " after " + ATTEMPTS + " attempts", last);
    }
}
