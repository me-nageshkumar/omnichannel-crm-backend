package me.nageshk.omnichannel.service.automation;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.security.entitlement.LimitCounter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** The customer's automation rules across its brands, drafts included. */
@Component
public class AutomationRuleLimitCounter implements LimitCounter {

    private final MongoTemplate mongo;

    public AutomationRuleLimitCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public LimitKey key() {
        return LimitKey.AUTOMATION_RULES;
    }

    @Override
    public long count(String customerId) {
        return mongo.count(new Query(where("customerId").is(customerId)), AutomationRule.class);
    }
}
