package me.nageshk.omnichannel.inbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for the media of inbox messages. */
@Configuration
public class ChatMediaConfig {

    public static final String BUCKET = "chat_media";

    @Bean
    public GridFsTemplate chatMediaGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
