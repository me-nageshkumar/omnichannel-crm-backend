package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RefundRepository extends MongoRepository<Refund, String> {

    List<Refund> findByCustomerIdOrderByRecordedAtDescIdDesc(String customerId);
}
