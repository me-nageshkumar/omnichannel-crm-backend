package me.nageshk.omnichannel.db.cloud;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for media uploaded for Cloud API messages (the headers of interactive messages). */
@Configuration
public class CloudMediaConfig {

    public static final String BUCKET = "cloudMedia";

    @Bean
    public GridFsTemplate cloudMediaGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
