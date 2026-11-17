package me.nageshk.omnichannel.app.billing;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts read-only mode in front of every API handler. */
@Configuration
public class BillingWebConfig implements WebMvcConfigurer {

    private final ReadOnlyInterceptor readOnly;

    public BillingWebConfig(ReadOnlyInterceptor readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(readOnly).addPathPatterns("/api/**");
    }
}
