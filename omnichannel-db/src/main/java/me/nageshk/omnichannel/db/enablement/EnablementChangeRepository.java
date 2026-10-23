package me.nageshk.omnichannel.db.enablement;

import org.springframework.data.mongodb.repository.MongoRepository;

/** The enable/disable audit trail. */
public interface EnablementChangeRepository extends MongoRepository<EnablementChange, String> {}
