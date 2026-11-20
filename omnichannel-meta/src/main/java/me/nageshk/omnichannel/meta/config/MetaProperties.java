package me.nageshk.omnichannel.meta.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Meta's Graph API: where it is, which version we speak, and how long and how big a call may be. */
@ConfigurationProperties("omnichannel.meta")
public record MetaProperties(
        @DefaultValue("https://graph.facebook.com") String graphBaseUrl,
        @DefaultValue("v23.0") String graphVersion,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("3MB") DataSize webhookMaxBytes) {}
