package me.nageshk.omnichannel.campaign;

import org.springframework.data.mongodb.repository.MongoRepository;

interface CampaignRecipientRepository extends MongoRepository<CampaignRecipient, String> {
}
