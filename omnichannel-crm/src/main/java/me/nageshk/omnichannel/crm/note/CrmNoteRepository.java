package me.nageshk.omnichannel.crm.note;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Finder-free repository; queries go through MongoTemplate with the brand in every criteria. */
public interface CrmNoteRepository extends MongoRepository<CrmNote, String> {}
