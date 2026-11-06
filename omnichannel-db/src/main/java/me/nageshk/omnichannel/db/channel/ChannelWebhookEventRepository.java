package me.nageshk.omnichannel.db.channel;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ChannelWebhookEventRepository extends MongoRepository<ChannelWebhookEvent, String> {

    @Query(value = "{'event.entry.changes.value.metadata.phone_number_id': ?0}", delete = true)
    long deleteByNumber(String phoneNumberId);
}
