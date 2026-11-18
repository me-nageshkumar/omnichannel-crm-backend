package me.nageshk.omnichannel.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("omnichannel.ai")
public record AiProperties(
        @DefaultValue("true") boolean autoTick,
        @DefaultValue("2s") Duration tick,
        @DefaultValue("8") int poolSize,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("120s") Duration turnTimeout,
        @DefaultValue("2") int attempts,
        @DefaultValue("8") int maxToolRounds,
        @DefaultValue("10s") Duration redeliverAfter,
        @DefaultValue OpenAi openai,
        @DefaultValue Mcp mcp,
        @DefaultValue Knowledge knowledge) {

    public record OpenAi(@DefaultValue("https://api.openai.com/v1") String baseUrl) {}

    public record Mcp(@DefaultValue("20s") Duration toolTimeout, @DefaultValue("65536") int maxResult,
            @DefaultValue("5m") Duration cacheFor) {}

    /** The knowledge worker: how often it ticks, its own pool, and how long a source may take. */
    public record Knowledge(@DefaultValue("5s") Duration tick, @DefaultValue("2") int poolSize,
            @DefaultValue("30m") Duration giveUpAfter, @DefaultValue("3") int attempts,
            @DefaultValue("2m") Duration lease) {

        public static Knowledge defaults() {
            return new Knowledge(Duration.ofSeconds(5), 2, Duration.ofMinutes(30), 3, Duration.ofMinutes(2));
        }
    }
}
