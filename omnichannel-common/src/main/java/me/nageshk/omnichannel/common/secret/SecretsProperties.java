package me.nageshk.omnichannel.common.secret;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code omnichannel.secrets.keys}: entries of the form {@code id:base64-of-32-bytes}; the first one seals. */
@ConfigurationProperties("omnichannel.secrets")
public record SecretsProperties(List<String> keys) {}
