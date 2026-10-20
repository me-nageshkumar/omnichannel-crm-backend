package me.nageshk.omnichannel.inbox;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Routing settings by phone number. */
public interface InboxSettingRepository extends MongoRepository<InboxSetting, String> {

    Optional<InboxSetting> findByPhoneNumberId(String phoneNumberId);

    void deleteByPhoneNumberId(String phoneNumberId);
}
