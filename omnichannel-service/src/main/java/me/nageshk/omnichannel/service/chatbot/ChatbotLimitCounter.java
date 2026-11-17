package me.nageshk.omnichannel.service.chatbot;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.security.entitlement.LimitCounter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** The customer's chatbots across its brands, drafts included. */
@Component
public class ChatbotLimitCounter implements LimitCounter {

    private final MongoTemplate mongo;

    public ChatbotLimitCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public LimitKey key() {
        return LimitKey.CHATBOTS;
    }

    @Override
    public long count(String customerId) {
        return mongo.count(new Query(where("customerId").is(customerId)), Chatbot.class);
    }
}
