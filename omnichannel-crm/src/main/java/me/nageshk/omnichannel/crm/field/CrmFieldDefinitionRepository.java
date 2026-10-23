package me.nageshk.omnichannel.crm.field;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Finder-free repository; queries go through MongoTemplate with the brand in every criteria. */
public interface CrmFieldDefinitionRepository extends MongoRepository<CrmFieldDefinition, String> {}
