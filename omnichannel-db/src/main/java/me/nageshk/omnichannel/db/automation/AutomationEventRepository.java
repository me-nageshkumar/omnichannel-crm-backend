package me.nageshk.omnichannel.db.automation;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AutomationEventRepository extends MongoRepository<AutomationEvent, String> {
}
