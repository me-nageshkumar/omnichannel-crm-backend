package me.nageshk.omnichannel.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import me.nageshk.omnichannel.auth.JwtAuthFilter;
import me.nageshk.omnichannel.auth.login.DelegatingAuthConverter;
import me.nageshk.omnichannel.auth.login.LoginFailureHandler;
import me.nageshk.omnichannel.auth.login.LoginSuccessHandler;
import me.nageshk.omnichannel.auth.login.OtpAuthenticationProvider;
import me.nageshk.omnichannel.auth.login.PasswordAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String AUTH_PATHS = "/api/auth/**";

    /**
     * {@code POST /api/auth/**} is handled by an {@link AuthenticationFilter}: the converter picks
     * login step one or two, the providers authenticate, and the handlers write the JSON reply.
     * Every other request authenticates through the bearer {@link JwtAuthFilter}.
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthFilter jwtFilter,
            AuthenticationManager loginManager,
            DelegatingAuthConverter converter,
            LoginSuccessHandler success,
            LoginFailureHandler failure) throws Exception {
        AuthenticationFilter loginFilter = new AuthenticationFilter(loginManager, converter);
        loginFilter.setRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, AUTH_PATHS));
        loginFilter.setSuccessHandler(success);
        loginFilter.setFailureHandler(failure);

        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AUTH_PATHS).permitAll()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Providers for the two login steps. */
    @Bean
    public AuthenticationManager loginManager(PasswordAuthenticationProvider password, OtpAuthenticationProvider otp) {
        return new ProviderManager(password, otp);
    }

    /** Allows the extension's service worker (origin chrome-extension://<id>) to call the REST API. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(OmnichannelProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(props.ws().allowedOrigins().split("\\s*,\\s*")));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Bare bcrypt: stored hashes carry no {@code {bcrypt}} prefix. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
