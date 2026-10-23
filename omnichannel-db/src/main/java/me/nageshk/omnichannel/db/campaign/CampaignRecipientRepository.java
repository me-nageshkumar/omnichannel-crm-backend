package me.nageshk.omnichannel.db.campaign;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CampaignRecipientRepository extends MongoRepository<CampaignRecipient, String> {
}
