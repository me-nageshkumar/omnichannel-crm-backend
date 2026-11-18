package me.nageshk.omnichannel.service.phone;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.security.entitlement.LimitCounter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** The customer's WhatsApp numbers across its brands, disabled ones included; archives of moved numbers are not counted. */
@Component
public class PhoneNumberLimitCounter implements LimitCounter {

    private final MongoTemplate mongo;

    public PhoneNumberLimitCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public LimitKey key() {
        return LimitKey.PHONE_NUMBERS;
    }

    @Override
    public long count(String customerId) {
        return mongo.count(new Query(where("customerId").is(customerId).and("archiveOf").exists(false)), PhoneNumber.class);
    }
}
