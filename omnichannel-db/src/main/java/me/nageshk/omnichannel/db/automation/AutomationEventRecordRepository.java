package me.nageshk.omnichannel.db.automation;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AutomationEventRecordRepository extends MongoRepository<AutomationEventRecord, String> {
}
