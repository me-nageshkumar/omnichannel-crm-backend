package me.nageshk.omnichannel.db.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/** Enables {@code @CreatedDate} / {@code @LastModifiedDate} on documents. */
@Configuration
@EnableMongoAuditing
public class MongoConfig {}
