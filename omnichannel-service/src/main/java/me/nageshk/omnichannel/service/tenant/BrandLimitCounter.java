package me.nageshk.omnichannel.service.tenant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.security.entitlement.LimitCounter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** The customer's brands, disabled ones included. */
@Component
public class BrandLimitCounter implements LimitCounter {

    private final MongoTemplate mongo;

    public BrandLimitCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public LimitKey key() {
        return LimitKey.BRANDS;
    }

    @Override
    public long count(String customerId) {
        return mongo.count(new Query(where("customerId").is(customerId)), Workspace.class);
    }
}
