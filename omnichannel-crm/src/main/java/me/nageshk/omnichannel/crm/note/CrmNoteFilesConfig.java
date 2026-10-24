package me.nageshk.omnichannel.crm.note;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** The GridFS bucket holding note attachments. */
@Configuration
public class CrmNoteFilesConfig {

    public static final String BUCKET = "crmNoteFiles";

    @Bean
    public GridFsTemplate crmNoteFilesGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
