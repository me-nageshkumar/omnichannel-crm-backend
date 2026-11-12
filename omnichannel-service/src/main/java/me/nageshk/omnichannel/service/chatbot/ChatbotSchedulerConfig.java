package me.nageshk.omnichannel.service.chatbot;

import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** The chatbot ticker's own thread, so a slow tick never holds up the other schedulers. */
@Configuration
class ChatbotSchedulerConfig {

    @Bean
    ThreadPoolTaskScheduler chatbotScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("chatbot-tick-");
        s.setDaemon(true);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /** Ticks on a schedule; tests switch it off and tick by hand. */
    @Component
    @ConditionalOnProperty(name = "omnichannel.chatbot.auto-tick", havingValue = "true", matchIfMissing = true)
    static class Schedule {
        private static final Logger log = LoggerFactory.getLogger(Schedule.class);
        private final ChatbotTicker ticker;

        Schedule(ChatbotTicker ticker) {
            this.ticker = ticker;
        }

        @Scheduled(fixedDelayString = "${omnichannel.chatbot.tick:2s}", scheduler = "chatbotScheduler")
        void tick() {
            try {
                ticker.tick();
            } catch (RuntimeException e) {
                log.warn("chatbot tick failed", e);
            }
        }
    }
}
