package me.nageshk.omnichannel.access;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnownAuthoritiesRepository extends MongoRepository<KnownAuthorities, String> {}
