package me.nageshk.omnichannel.meta.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CloudAccountRepository extends MongoRepository<CloudAccount, String> {
    Optional<CloudAccount> findByPhoneNumberId(String phoneNumberId);
    Optional<CloudAccount> findByMetaPhoneNumberId(String metaPhoneNumberId);
    List<CloudAccount> findByConnectionId(String connectionId);
    void deleteByPhoneNumberId(String phoneNumberId);
}
