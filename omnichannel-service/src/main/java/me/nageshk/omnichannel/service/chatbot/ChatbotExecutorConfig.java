package me.nageshk.omnichannel.service.chatbot;

import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The pool chatbot runs walk on (spec §5.2): never the ingestion or request threads. */
@Configuration
class ChatbotExecutorConfig {

    @Bean
    ThreadPoolTaskExecutor chatbotExecutor(OmnichannelProperties props) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(props.chatbot().poolSize());
        e.setMaxPoolSize(props.chatbot().poolSize());
        e.setQueueCapacity(1000);
        e.setThreadNamePrefix("chatbot-");
        e.setDaemon(true);
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(60);
        return e;
    }
}
