package me.nageshk.omnichannel.db.billing;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SubscriptionRepository extends MongoRepository<Subscription, String> {

    Optional<Subscription> findByCustomerId(String customerId);
}
