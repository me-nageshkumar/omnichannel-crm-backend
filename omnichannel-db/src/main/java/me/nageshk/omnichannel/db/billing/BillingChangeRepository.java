package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillingChangeRepository extends MongoRepository<BillingChange, String> {

    List<BillingChange> findByTargetTypeAndTargetIdOrderByAtDescIdDesc(String targetType, String targetId);
}
