package me.nageshk.omnichannel.access;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link AuthorityDefinition}, keyed by authority code. */
public interface AuthorityDefinitionRepository extends MongoRepository<AuthorityDefinition, String> {}
