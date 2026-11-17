package me.nageshk.omnichannel.db.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillingSettingsRepository extends MongoRepository<BillingSettings, String> {}
