package me.nageshk.omnichannel.db.page;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PagesSettingsRepository extends MongoRepository<PagesSettings, String> {
}
