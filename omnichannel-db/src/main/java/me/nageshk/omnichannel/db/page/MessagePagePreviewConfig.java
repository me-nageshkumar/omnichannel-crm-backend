package me.nageshk.omnichannel.db.page;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for the link-preview images of message pages. */
@Configuration
public class MessagePagePreviewConfig {

    public static final String BUCKET = "messagePagePreviews";

    @Bean
    public GridFsTemplate messagePagePreviewsGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
