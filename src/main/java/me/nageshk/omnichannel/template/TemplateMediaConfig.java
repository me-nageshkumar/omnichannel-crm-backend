package me.nageshk.omnichannel.template;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * GridFS bucket for template media, plus scheduling for the orphan sweep. This Boot setup has no task-scheduling
 * auto-configuration, so a {@code taskScheduler} is defined here; without it {@code @Scheduled} would fall back to the
 * single {@code wsScheduler} executor that times out WebSocket authentication.
 */
@Configuration
@EnableScheduling
public class TemplateMediaConfig {

    public static final String BUCKET = "template_media";

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean
    public GridFsTemplate templateMediaGridFs(MongoDatabaseFactory factory, MongoConverter converter) {
        return new GridFsTemplate(factory, converter, BUCKET);
    }
}
