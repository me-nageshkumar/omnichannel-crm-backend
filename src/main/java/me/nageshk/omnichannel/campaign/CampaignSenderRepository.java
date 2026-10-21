package me.nageshk.omnichannel.campaign;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

interface CampaignSenderRepository extends MongoRepository<CampaignSender, String> {

    Optional<CampaignSender> findByPhoneNumberId(String phoneNumberId);
}
