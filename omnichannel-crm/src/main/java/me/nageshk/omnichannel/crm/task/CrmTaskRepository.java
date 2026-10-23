package me.nageshk.omnichannel.crm.task;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Finder-free repository; queries go through MongoTemplate with the brand in every criteria. */
public interface CrmTaskRepository extends MongoRepository<CrmTask, String> {}
