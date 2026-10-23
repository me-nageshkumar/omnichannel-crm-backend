package me.nageshk.omnichannel.db.template;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for template media. */
@Configuration
public class TemplateMediaConfig {

    public static final String BUCKET = "templateMedia";

    @Bean
    public GridFsTemplate templateMediaGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
