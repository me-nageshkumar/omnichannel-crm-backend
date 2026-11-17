package me.nageshk.omnichannel.db.billing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** GridFS bucket for the PDF files of invoices and credit notes. */
@Configuration
public class BillingDocumentsConfig {

    public static final String BUCKET = "billingDocuments";

    @Bean
    public GridFsTemplate billingDocumentsGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
