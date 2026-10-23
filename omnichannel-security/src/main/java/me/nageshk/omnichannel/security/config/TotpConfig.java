package me.nageshk.omnichannel.security.config;

import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TotpConfig {

    @Bean
    public TimeProvider timeProvider() {
        return new SystemTimeProvider();
    }
}
