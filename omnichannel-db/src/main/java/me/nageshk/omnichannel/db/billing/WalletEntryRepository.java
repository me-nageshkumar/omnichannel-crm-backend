package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletEntryRepository extends MongoRepository<WalletEntry, String> {

    List<WalletEntry> findByCustomerIdOrderByAtDescIdDesc(String customerId);
}
