package me.nageshk.omnichannel.db.template;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * GridFS bucket for template media, plus scheduling for the orphan sweep. Boot's task-scheduling auto-configuration
 * backs off because the {@code wsScheduler} {@code ScheduledExecutorService} bean exists, so a {@code taskScheduler}
 * is defined here; without it {@code @Scheduled} would run on {@code wsScheduler}, the single-thread executor that
 * times out WebSocket authentication. This scheduler is also the only {@code TaskExecutor} bean, so if
 * {@code @EnableAsync} is added later, define an {@code applicationTaskExecutor} too.
 */
@Configuration
@EnableScheduling
public class TemplateMediaConfig {

    public static final String BUCKET = "templateMedia";

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
