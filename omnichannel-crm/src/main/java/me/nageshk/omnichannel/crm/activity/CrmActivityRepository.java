package me.nageshk.omnichannel.crm.activity;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmActivityRepository extends MongoRepository<CrmActivity, String> {}
