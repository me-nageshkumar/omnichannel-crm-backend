package me.nageshk.omnichannel.ai.knowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** The GridFS bucket holding our copy of every knowledge source (files, fetched pages and notes as Markdown). */
@Configuration
public class KnowledgeFilesConfig {

    public static final String BUCKET = "aiKnowledgeFiles";

    @Bean
    public GridFsTemplate aiKnowledgeFilesGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
