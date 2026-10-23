package me.nageshk.omnichannel.db.phone;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Unused connect codes. */
public interface ConnectCodeRepository extends MongoRepository<ConnectCode, String> {

    Optional<ConnectCode> findByCodeHash(String codeHash);

    void deleteByPhoneNumberId(String phoneNumberId);
}
