package me.nageshk.omnichannel.campaign;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for the CSV files of campaign audiences. */
@Configuration
public class CampaignCsvConfig {

    public static final String BUCKET = "campaign_csv";

    @Bean
    public GridFsTemplate campaignCsvGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
