package in.redlava.wabridge.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept separate from WebSocketConfig so the handler (which needs the scheduler) has no cycle with it. */
@Configuration
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService wsScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-auth-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
