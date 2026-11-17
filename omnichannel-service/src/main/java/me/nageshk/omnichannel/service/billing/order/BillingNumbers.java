package me.nageshk.omnichannel.service.billing.order;

import me.nageshk.omnichannel.db.billing.BillingCounter;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Running numbers for orders and refunds. A number is never given twice; one may be skipped if its use fails. */
@Component
public class BillingNumbers {

    public static final String ORDERS = "orders";
    public static final String REFUNDS = "refunds";

    private final MongoTemplate mongo;

    public BillingNumbers(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public long next(String series) {
        BillingCounter counter = mongo.findAndModify(new Query(Criteria.where("_id").is(series)),
                new Update().inc("value", 1), FindAndModifyOptions.options().upsert(true).returnNew(true),
                BillingCounter.class);
        return counter == null ? 1 : counter.getValue();
    }

    public String order() {
        return String.format("ORD-%06d", next(ORDERS));
    }

    public String refund() {
        return String.format("RF-%06d", next(REFUNDS));
    }
}
