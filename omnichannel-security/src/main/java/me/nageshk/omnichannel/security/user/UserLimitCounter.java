package me.nageshk.omnichannel.security.user;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.entitlement.LimitCounter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** The customer's enabled users; a disabled user does not take a place. */
@Component
public class UserLimitCounter implements LimitCounter {

    private final MongoTemplate mongo;

    public UserLimitCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public LimitKey key() {
        return LimitKey.USERS;
    }

    @Override
    public long count(String customerId) {
        return mongo.count(new Query(where("customerId").is(customerId).and("active").is(true)), User.class);
    }
}
