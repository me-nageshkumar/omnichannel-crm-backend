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
        @DefaultValue Mcp mcp) {

    public record OpenAi(@DefaultValue("https://api.openai.com/v1") String baseUrl) {}

    public record Mcp(@DefaultValue("20s") Duration toolTimeout, @DefaultValue("65536") int maxResult,
            @DefaultValue("5m") Duration cacheFor) {}
}
