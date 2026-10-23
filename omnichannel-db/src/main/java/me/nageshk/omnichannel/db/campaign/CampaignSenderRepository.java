package me.nageshk.omnichannel.db.campaign;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CampaignSenderRepository extends MongoRepository<CampaignSender, String> {

    Optional<CampaignSender> findByPhoneNumberId(String phoneNumberId);
}
