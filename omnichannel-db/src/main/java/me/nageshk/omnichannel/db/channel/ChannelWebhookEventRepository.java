package me.nageshk.omnichannel.db.channel;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChannelWebhookEventRepository extends MongoRepository<ChannelWebhookEvent, String> {

    void deleteByChannelAccountId(String channelAccountId);

    List<ChannelWebhookEvent> findByChannelAccountIdOrderByCreationTimeDesc(String channelAccountId, Pageable page);
}
