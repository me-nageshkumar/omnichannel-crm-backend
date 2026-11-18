package me.nageshk.omnichannel.db.billing;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletRepository extends MongoRepository<Wallet, String> {

    Optional<Wallet> findByCustomerId(String customerId);
}
