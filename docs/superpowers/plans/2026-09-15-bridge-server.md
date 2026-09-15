# WhatsApp Bridge Server (Spring Boot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot backend that authenticates Chrome-extension "devices" with username/password + JWT, holds one WebSocket per user, logs incoming WhatsApp events, and lets REST callers send WhatsApp messages through the connected extension.

**Architecture:** Stateless Spring Security with a JWT filter for REST; a plain `TextWebSocketHandler` at `/ws` that authenticates on the first frame and registers the session in a `DeviceRegistry`; an `RpcDispatcher` that maps outgoing `request` frames to `CompletableFuture`s completed by matching `response` frames; incoming `event` frames become Spring `ApplicationEvent`s consumed by a logging listener. No database.

**Tech Stack:** Java 21, Spring Boot 3.5.x (spring-boot-starter-web, -websocket, -security, -validation), jjwt 0.12.x, JUnit 5, Mockito, Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md`

## Global Constraints

- Java 21, Spring Boot 3.x (use `3.5.6`; if Maven cannot resolve it, use the latest available `3.5.x`), Maven wrapper committed.
- Package root: `me.nageshk.whatsapp`.
- Wire protocol is exactly the envelope in the spec: `type` ∈ `auth|request|response|event|ping|pong`; fields `id, token, method, params, result, error{code,message}, name, payload`.
- First WS frame must be `auth` within the auth timeout (default 5 s) or the server closes with code `4401`. A second device for the same user replaces the first; the old socket is closed with `4409`.
- RPC timeout default 30 s. No token in URLs.
- Event names reuse `src/util/Constants.js` `Events` values (`ready`, `message`, `message_create`, `message_ack`, `change_state`, `disconnected`).
- Commit messages follow Conventional Commits (the repo has commitlint), end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Work on branch `feat/chrome-extension-bridge` (already created; spec is committed there).
- All server commands run from `whatsapp-backend/` using `./mvnw`.

---

## File Structure

```
whatsapp-backend/
  pom.xml
  mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
  README.md
  src/main/resources/application.yml
  src/main/java/me/nageshk/whatsapp/
    WabridgeApplication.java              # @SpringBootApplication, @ConfigurationPropertiesScan
    config/WabridgeProperties.java        # typed config: jwt, users, ws, rpc
    config/ClockConfig.java               # Clock bean (injectable time for tests)
    config/SecurityConfig.java            # stateless chain, JWT filter, users, CORS
    config/WebSocketConfig.java           # /ws handler registration
    config/SchedulerConfig.java           # wsScheduler bean (separate class avoids a bean cycle with the handler)
    auth/JwtService.java                  # issue/verify
    auth/JwtAuthFilter.java               # Bearer → SecurityContext
    auth/AuthController.java              # POST /api/auth/login
    rpc/Envelope.java                     # protocol record + factories
    rpc/RpcException.java
    rpc/RpcDispatcher.java
    ws/DeviceSession.java                 # wraps WebSocketSession + WA state
    ws/DeviceRegistry.java
    ws/BridgeWebSocketHandler.java
    events/WhatsAppEvent.java
    events/LoggingEventListener.java
    api/NoDeviceException.java
    api/MessagesController.java           # POST /api/messages
    api/StatusController.java             # GET /api/status
    api/ApiExceptionHandler.java
  src/test/java/me/nageshk/whatsapp/
    WabridgeApplicationTests.java
    auth/JwtServiceTest.java
    auth/AuthControllerTest.java
    rpc/EnvelopeTest.java
    rpc/RpcDispatcherTest.java
    ws/BridgeWebSocketIT.java             # real WS client against RANDOM_PORT
    api/MessagesApiIT.java                # REST ↔ fake device end-to-end
    support/FakeDevice.java               # test WS client helper
```

Root-level modifications (Task 1): `.gitignore`, `.prettierignore`, `eslint.config.mjs`.

---

### Task 1: Project scaffold and repo hygiene

**Files:**
- Modify: `.gitignore`, `.prettierignore`, `eslint.config.mjs` (ignores list at lines 57-64)
- Create: `whatsapp-backend/pom.xml`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/WabridgeApplication.java`, `whatsapp-backend/src/main/resources/application.yml`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/WabridgeProperties.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/ClockConfig.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/WabridgeApplicationTests.java`

**Interfaces:**
- Produces: `WabridgeProperties` record (below) used by every later task; `Clock` bean.

- [ ] **Step 1: Ignore build output at repo root**

Append to `.gitignore`:

```
# bridge server / extension
server/target/
extension/dist/
extension/node_modules/
```

Append to `.prettierignore`:

```
server
extension/dist
```

In `eslint.config.mjs`, add `'server'` and `'extension/dist'` to the `ignores` array (after `'.wwebjs_cache',`).

- [ ] **Step 2: Create the Maven POM**

`whatsapp-backend/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.6</version>
    <relativePath/>
  </parent>

  <groupId>in.redlava</groupId>
  <artifactId>wabridge</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>wabridge</name>
  <description>WhatsApp Web bridge server for the Chrome extension</description>

  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Generate the Maven wrapper**

Run: `cd whatsapp-backend && mvn -q wrapper:wrapper -Dmaven=3.9.15`
Expected: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` created. Run `./mvnw -v` and confirm it prints Maven 3.9.15.

- [ ] **Step 4: Application class, properties, clock**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/WabridgeApplication.java`:

```java
package me.nageshk.whatsapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WabridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(WabridgeApplication.class, args);
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/WabridgeProperties.java`:

```java
package me.nageshk.whatsapp.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "wabridge")
public record WabridgeProperties(Jwt jwt, List<User> users, Ws ws, Rpc rpc) {

    public record Jwt(String secret, @DefaultValue("12h") Duration ttl) {}

    /** password uses Spring's DelegatingPasswordEncoder ids: {bcrypt}..., {noop}... */
    public record User(String username, String password) {}

    public record Ws(@DefaultValue("chrome-extension://*") String allowedOrigins) {}

    public record Rpc(
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("5s") Duration authTimeout) {}
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/ClockConfig.java`:

```java
package me.nageshk.whatsapp.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

`whatsapp-backend/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

logging:
  level:
    me.nageshk.whatsapp: INFO

wabridge:
  jwt:
    # Must be at least 32 bytes. Override in production: WABRIDGE_JWT_SECRET=...
    secret: ${WABRIDGE_JWT_SECRET:dev-only-secret-please-change-me-0123456789}
    ttl: 12h
  users:
    # Dev/PoC user. For real deployments use {bcrypt}<hash>; generate with:
    #   htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n' | sed 's/^/{bcrypt}/'
    - username: demo
      password: "{noop}demo123"
  ws:
    allowed-origins: "chrome-extension://*"
  rpc:
    timeout: 30s
    auth-timeout: 5s
```

- [ ] **Step 5: Context-loads test**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/WabridgeApplicationTests.java`:

```java
package me.nageshk.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.whatsapp.config.WabridgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WabridgeApplicationTests {

    @Autowired WabridgeProperties props;

    @Test
    void contextLoadsAndBindsProperties() {
        assertThat(props.users()).extracting(WabridgeProperties.User::username).contains("demo");
        assertThat(props.rpc().timeout().toSeconds()).isEqualTo(30);
        assertThat(props.jwt().secret().getBytes().length).isGreaterThanOrEqualTo(32);
    }
}
```

- [ ] **Step 6: Run the test**

Run: `cd whatsapp-backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 1 test passes. (Spring Security auto-config will log a generated password; that is fine for now and disappears in Task 3.)

- [ ] **Step 7: Commit**

```bash
git add .gitignore .prettierignore eslint.config.mjs server
git commit -m "feat(server): scaffold wabridge spring boot project

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: JwtService

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/JwtService.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: `WabridgeProperties.jwt()`, `Clock`.
- Produces:
  - `record IssuedToken(String token, Instant expiresAt)`
  - `IssuedToken JwtService.issue(String username)`
  - `Optional<String> JwtService.verify(String token)` → subject (username) or empty on any failure.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/auth/JwtServiceTest.java`:

```java
package me.nageshk.whatsapp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.whatsapp.config.WabridgeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private JwtService serviceAt(Instant now) {
        var props = new WabridgeProperties(
                new WabridgeProperties.Jwt(SECRET, Duration.ofHours(1)),
                List.of(),
                new WabridgeProperties.Ws("*"),
                new WabridgeProperties.Rpc(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        return new JwtService(props, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueThenVerifyReturnsUsername() {
        var svc = serviceAt(NOW);
        var issued = svc.issue("demo");

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(svc.verify(issued.token())).contains("demo");
    }

    @Test
    void verifyRejectsExpiredToken() {
        var issued = serviceAt(NOW).issue("demo");
        var later = serviceAt(NOW.plus(Duration.ofHours(2)));

        assertThat(later.verify(issued.token())).isEmpty();
    }

    @Test
    void verifyRejectsTamperedToken() {
        var svc = serviceAt(NOW);
        var token = svc.issue("demo").token();
        var tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(svc.verify(tampered)).isEmpty();
        assertThat(svc.verify("not-a-jwt")).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=JwtServiceTest`
Expected: compilation error, `JwtService` does not exist.

- [ ] **Step 3: Implement JwtService**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/JwtService.java`:

```java
package me.nageshk.whatsapp.auth;

import me.nageshk.whatsapp.config.WabridgeProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public record IssuedToken(String token, Instant expiresAt) {}

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(WabridgeProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().ttl();
        this.clock = clock;
    }

    public IssuedToken issue(String username) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new IssuedToken(token, exp);
    }

    public Optional<String> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.ofNullable(subject).filter(s -> !s.isBlank());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=JwtServiceTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src
git commit -m "feat(server): add JwtService for issuing and verifying tokens

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Security configuration and login endpoint

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/SecurityConfig.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/JwtAuthFilter.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/AuthController.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: `JwtService.issue/verify`, `WabridgeProperties.users()`.
- Produces: `POST /api/auth/login` `{username,password}` → `200 {token, expiresAt}` or `401 {error:"invalid_credentials"}`. Any other `/api/**` requires `Authorization: Bearer <jwt>`; `/ws` is open at HTTP level. Authenticated principal name = username.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/auth/AuthControllerTest.java`:

```java
package me.nageshk.whatsapp.auth;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.expiresAt", not(emptyString())));
    }

    @Test
    void loginWithBadPasswordReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithMissingFieldsReturns400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithTokenIsNotRejectedByAuth() throws Exception {
        String token = jwt.issue("demo").token();
        // /api/status does not exist yet; 404 proves the filter accepted the token (401 would mean rejected)
        mvc.perform(get("/api/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=AuthControllerTest`
Expected: login tests fail (no controller → 401/403 from default security); protected-with-token test fails.

- [ ] **Step 3: Implement the JWT filter**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/JwtAuthFilter.java`:

```java
package me.nageshk.whatsapp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    /**
     * Controllers return CompletableFuture; Spring completes them via an ASYNC dispatch that re-runs the
     * security chain. Run on that dispatch too so the Bearer header is re-read and authorization passes.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            jwt.verify(header.substring(BEARER.length())).ifPresent(username -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Implement SecurityConfig**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/SecurityConfig.java`:

```java
package me.nageshk.whatsapp.config;

import me.nageshk.whatsapp.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/ws", "/ws/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Allows the extension's service worker (origin chrome-extension://<id>) to call the REST API. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(WabridgeProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(props.ws().allowedOrigins().split("\\s*,\\s*")));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(WabridgeProperties props) {
        var manager = new InMemoryUserDetailsManager();
        for (WabridgeProperties.User u : props.users()) {
            manager.createUser(User.withUsername(u.username()).password(u.password()).roles("USER").build());
        }
        return manager;
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
```

- [ ] **Step 5: Implement AuthController**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/auth/AuthController.java`:

```java
package me.nageshk.whatsapp.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, Instant expiresAt) {}

    private final AuthenticationManager authManager;
    private final JwtService jwt;

    public AuthController(AuthenticationManager authManager, JwtService jwt) {
        this.authManager = authManager;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        JwtService.IssuedToken issued = jwt.issue(req.username());
        return new LoginResponse(issued.token(), issued.expiresAt());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> badCredentials() {
        return Map.of("error", "invalid_credentials");
    }
}
```

- [ ] **Step 6: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=AuthControllerTest`
Expected: 5 tests pass. If `loginWithMissingFieldsReturns400` fails with 401, the validation error is being caught by the security entry point; confirm `@Valid` is present and that `spring-boot-starter-validation` is on the classpath.

- [ ] **Step 7: Commit**

```bash
git add server/src
git commit -m "feat(server): stateless JWT security and login endpoint

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Protocol envelope

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/Envelope.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/RpcException.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/rpc/EnvelopeTest.java`

**Interfaces:**
- Produces:
  - `record Envelope(String type, String id, String token, String method, JsonNode params, JsonNode result, RpcError error, String name, JsonNode payload)` with nested `record RpcError(String code, String message)`.
  - Static factories: `auth(token)`, `request(id, method, params)`, `response(id, result)`, `errorResponse(id, code, message)`, `event(name, payload)`, `ping()`, `pong()`.
  - Constants: `Envelope.AUTH, REQUEST, RESPONSE, EVENT, PING, PONG` (the `type` strings).
  - `class RpcException extends RuntimeException { String code(); }` with codes used across the server: `METHOD_NOT_FOUND`, `PAGE_ERROR`, `CHAT_NOT_FOUND`, `WHATSAPP_NOT_READY`, `DEVICE_DISCONNECTED`, `SEND_FAILED`.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/rpc/EnvelopeTest.java`:

```java
package me.nageshk.whatsapp.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRequestFrame() throws Exception {
        String json = "{\"type\":\"request\",\"id\":\"abc\",\"method\":\"sendMessage\",\"params\":{\"chatId\":\"1@c.us\"}}";
        Envelope env = mapper.readValue(json, Envelope.class);

        assertThat(env.type()).isEqualTo(Envelope.REQUEST);
        assertThat(env.id()).isEqualTo("abc");
        assertThat(env.method()).isEqualTo("sendMessage");
        assertThat(env.params().get("chatId").asText()).isEqualTo("1@c.us");
    }

    @Test
    void parsesErrorResponseAndIgnoresUnknownFields() throws Exception {
        String json = "{\"type\":\"response\",\"id\":\"abc\",\"error\":{\"code\":\"PAGE_ERROR\",\"message\":\"boom\"},\"extra\":1}";
        Envelope env = mapper.readValue(json, Envelope.class);

        assertThat(env.error().code()).isEqualTo("PAGE_ERROR");
        assertThat(env.error().message()).isEqualTo("boom");
        assertThat(env.result()).isNull();
    }

    @Test
    void serializesWithoutNullFields() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("chatId", "1@c.us");
        String json = mapper.writeValueAsString(Envelope.request("id1", "sendMessage", params));

        assertThat(json).contains("\"type\":\"request\"").contains("\"method\":\"sendMessage\"");
        assertThat(json).doesNotContain("token").doesNotContain("error").doesNotContain("payload");
    }

    @Test
    void factoriesProduceExpectedTypes() {
        assertThat(Envelope.auth("t").type()).isEqualTo(Envelope.AUTH);
        assertThat(Envelope.auth("t").token()).isEqualTo("t");
        assertThat(Envelope.ping().type()).isEqualTo(Envelope.PING);
        assertThat(Envelope.pong().type()).isEqualTo(Envelope.PONG);
        assertThat(Envelope.event("message", null).name()).isEqualTo("message");
        assertThat(Envelope.errorResponse("x", "CHAT_NOT_FOUND", "nope").error().code()).isEqualTo("CHAT_NOT_FOUND");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=EnvelopeTest`
Expected: compilation error, `Envelope` missing.

- [ ] **Step 3: Implement Envelope and RpcException**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/Envelope.java`:

```java
package me.nageshk.whatsapp.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** One frame of the extension <-> server protocol. Unused fields are null and omitted on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Envelope(
        String type,
        String id,
        String token,
        String method,
        JsonNode params,
        JsonNode result,
        RpcError error,
        String name,
        JsonNode payload) {

    public static final String AUTH = "auth";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String EVENT = "event";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcError(String code, String message) {}

    public static Envelope auth(String token) {
        return new Envelope(AUTH, null, token, null, null, null, null, null, null);
    }

    public static Envelope request(String id, String method, JsonNode params) {
        return new Envelope(REQUEST, id, null, method, params, null, null, null, null);
    }

    public static Envelope response(String id, JsonNode result) {
        return new Envelope(RESPONSE, id, null, null, null, result, null, null, null);
    }

    public static Envelope errorResponse(String id, String code, String message) {
        return new Envelope(RESPONSE, id, null, null, null, null, new RpcError(code, message), null, null);
    }

    public static Envelope event(String name, JsonNode payload) {
        return new Envelope(EVENT, null, null, null, null, null, null, name, payload);
    }

    public static Envelope ping() {
        return new Envelope(PING, null, null, null, null, null, null, null, null);
    }

    public static Envelope pong() {
        return new Envelope(PONG, null, null, null, null, null, null, null, null);
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/RpcException.java`:

```java
package me.nageshk.whatsapp.rpc;

/** An error reported by the extension/page for an RPC, or a transport failure while performing one. */
public class RpcException extends RuntimeException {

    public static final String METHOD_NOT_FOUND = "METHOD_NOT_FOUND";
    public static final String PAGE_ERROR = "PAGE_ERROR";
    public static final String CHAT_NOT_FOUND = "CHAT_NOT_FOUND";
    public static final String WHATSAPP_NOT_READY = "WHATSAPP_NOT_READY";
    public static final String DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED";
    public static final String SEND_FAILED = "SEND_FAILED";

    private final String code;

    public RpcException(String code, String message) {
        super(message == null ? code : message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=EnvelopeTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src
git commit -m "feat(server): add protocol Envelope record and RpcException

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: DeviceSession and RpcDispatcher

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/DeviceSession.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/RpcDispatcher.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/rpc/RpcDispatcherTest.java`

**Interfaces:**
- Consumes: `Envelope`, `RpcException`, `WabridgeProperties.rpc().timeout()`.
- Produces:
  - `DeviceSession(String username, WebSocketSession session, ObjectMapper mapper)`: `username()`, `sessionId()`, `waState()` (default `"UNKNOWN"`), `setWaState(String)`, `info()` (JsonNode, default empty object), `setInfo(JsonNode)`, `send(Envelope) throws IOException` (thread-safe), `close(int code, String reason)`.
  - `RpcDispatcher`: `CompletableFuture<JsonNode> call(DeviceSession device, String method, JsonNode params)`; `boolean complete(Envelope response)`; `int failAll(String username, String code)`; package-private ctor `RpcDispatcher(Duration timeout, Supplier<String> idSupplier)` for tests.
  - Timeouts surface as `java.util.concurrent.TimeoutException` on the future.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/rpc/RpcDispatcherTest.java`:

```java
package me.nageshk.whatsapp.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.ws.DeviceSession;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class RpcDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocketSession wsSession;
    private DeviceSession device;
    private RpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        wsSession = mock(WebSocketSession.class);
        when(wsSession.getId()).thenReturn("s1");
        when(wsSession.isOpen()).thenReturn(true);
        device = new DeviceSession("demo", wsSession, mapper);
        Iterator<String> ids = List.of("id-1", "id-2", "id-3").iterator();
        dispatcher = new RpcDispatcher(Duration.ofSeconds(5), ids::next);
    }

    @Test
    void callSendsRequestFrameAndCompletesOnMatchingResponse() throws Exception {
        JsonNode params = mapper.createObjectNode().put("chatId", "1@c.us");
        CompletableFuture<JsonNode> future = dispatcher.call(device, "sendMessage", params);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(sent.capture());
        Envelope frame = mapper.readValue(sent.getValue().getPayload(), Envelope.class);
        assertThat(frame.type()).isEqualTo(Envelope.REQUEST);
        assertThat(frame.id()).isEqualTo("id-1");
        assertThat(frame.method()).isEqualTo("sendMessage");
        assertThat(frame.params().get("chatId").asText()).isEqualTo("1@c.us");
        assertThat(future).isNotDone();

        JsonNode result = mapper.createObjectNode().put("ok", true);
        boolean matched = dispatcher.complete(Envelope.response("id-1", result));

        assertThat(matched).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS).get("ok").asBoolean()).isTrue();
    }

    @Test
    void errorResponseCompletesExceptionallyWithRpcException() {
        CompletableFuture<JsonNode> future = dispatcher.call(device, "sendMessage", null);
        dispatcher.complete(Envelope.errorResponse("id-1", RpcException.CHAT_NOT_FOUND, "no chat"));

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.CHAT_NOT_FOUND));
    }

    @Test
    void unknownResponseIdIsIgnored() {
        assertThat(dispatcher.complete(Envelope.response("nope", null))).isFalse();
    }

    @Test
    void callTimesOut() {
        RpcDispatcher fast = new RpcDispatcher(Duration.ofMillis(50), () -> "t1");
        CompletableFuture<JsonNode> future = fast.call(device, "getState", null);

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(TimeoutException.class);
        assertThat(fast.complete(Envelope.response("t1", null))).isFalse();
    }

    @Test
    void failAllFailsPendingCallsForUser() {
        CompletableFuture<JsonNode> f1 = dispatcher.call(device, "a", null);
        CompletableFuture<JsonNode> f2 = dispatcher.call(device, "b", null);

        int failed = dispatcher.failAll("demo", RpcException.DEVICE_DISCONNECTED);

        assertThat(failed).isEqualTo(2);
        assertThat(f1).isCompletedExceptionally();
        assertThat(f2).isCompletedExceptionally();
        assertThatThrownBy(f1::join).cause().isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.DEVICE_DISCONNECTED));
    }

    @Test
    void sendFailureCompletesExceptionally() throws IOException {
        doThrow(new IOException("closed")).when(wsSession).sendMessage(any());
        CompletableFuture<JsonNode> future = dispatcher.call(device, "a", null);

        assertThatThrownBy(future::join).cause().isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.SEND_FAILED));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=RpcDispatcherTest`
Expected: compilation errors for `DeviceSession` and `RpcDispatcher`.

- [ ] **Step 3: Implement DeviceSession**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/DeviceSession.java`:

```java
package me.nageshk.whatsapp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.rpc.Envelope;
import java.io.IOException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** One authenticated extension connection for one user, plus what we know about its WhatsApp state. */
public final class DeviceSession {

    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_CONNECTED = "CONNECTED";

    private final String username;
    private final WebSocketSession session;
    private final ObjectMapper mapper;
    private volatile String waState = STATE_UNKNOWN;
    private volatile JsonNode info;

    public DeviceSession(String username, WebSocketSession session, ObjectMapper mapper) {
        this.username = username;
        this.session = session;
        this.mapper = mapper;
        this.info = mapper.createObjectNode();
    }

    public String username() {
        return username;
    }

    public String sessionId() {
        return session.getId();
    }

    public String waState() {
        return waState;
    }

    public void setWaState(String waState) {
        this.waState = waState == null ? STATE_UNKNOWN : waState;
    }

    public JsonNode info() {
        return info;
    }

    public void setInfo(JsonNode info) {
        if (info != null && !info.isNull()) {
            this.info = info;
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    /** Serializes and sends one frame. Synchronized because the standard WebSocketSession is not thread-safe. */
    public void send(Envelope frame) throws IOException {
        String json = mapper.writeValueAsString(frame);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    public void close(int code, String reason) {
        try {
            synchronized (session) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (IOException ignoredError) {
            // already closed
        }
    }
}
```

- [ ] **Step 4: Implement RpcDispatcher**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/rpc/RpcDispatcher.java`:

```java
package me.nageshk.whatsapp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import me.nageshk.whatsapp.config.WabridgeProperties;
import me.nageshk.whatsapp.ws.DeviceSession;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Sends request frames to a device and completes the matching future when its response frame arrives. */
@Component
public class RpcDispatcher {

    private record Pending(String username, CompletableFuture<JsonNode> future) {}

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Supplier<String> idSupplier;

    @Autowired
    public RpcDispatcher(WabridgeProperties props) {
        this(props.rpc().timeout(), () -> UUID.randomUUID().toString());
    }

    RpcDispatcher(Duration timeout, Supplier<String> idSupplier) {
        this.timeout = timeout;
        this.idSupplier = idSupplier;
    }

    public CompletableFuture<JsonNode> call(DeviceSession device, String method, JsonNode params) {
        String id = idSupplier.get();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, new Pending(device.username(), future));
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(id));
        try {
            device.send(Envelope.request(id, method, params));
        } catch (IOException e) {
            future.completeExceptionally(new RpcException(RpcException.SEND_FAILED, e.getMessage()));
        }
        return future;
    }

    /** @return true if a pending call matched this response id. */
    public boolean complete(Envelope response) {
        if (response.id() == null) {
            return false;
        }
        Pending p = pending.remove(response.id());
        if (p == null) {
            return false;
        }
        if (response.error() != null) {
            p.future().completeExceptionally(new RpcException(response.error().code(), response.error().message()));
        } else {
            p.future().complete(response.result());
        }
        return true;
    }

    /** Fails every pending call for a user, e.g. when its device disconnects. @return number failed. */
    public int failAll(String username, String code) {
        int count = 0;
        for (var entry : pending.entrySet()) {
            if (entry.getValue().username().equals(username) && pending.remove(entry.getKey()) != null) {
                entry.getValue().future().completeExceptionally(new RpcException(code, "device " + username + ": " + code));
                count++;
            }
        }
        return count;
    }

    public int pendingCount() {
        return pending.size();
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=RpcDispatcherTest`
Expected: 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add server/src
git commit -m "feat(server): add DeviceSession and RpcDispatcher

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: WebSocket handler, device registry, event publishing

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/DeviceRegistry.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/BridgeWebSocketHandler.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/WebSocketConfig.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/events/WhatsAppEvent.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/events/LoggingEventListener.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/support/FakeDevice.java`, `whatsapp-backend/src/test/java/me/nageshk/whatsapp/ws/BridgeWebSocketIT.java`

**Interfaces:**
- Consumes: `JwtService.verify`, `DeviceSession`, `RpcDispatcher.complete/failAll`, `Envelope`.
- Produces:
  - `DeviceRegistry`: `Optional<DeviceSession> register(DeviceSession)` (returns the replaced session for the same user, if any), `Optional<DeviceSession> unregister(String sessionId)`, `Optional<DeviceSession> find(String username)`, `Optional<DeviceSession> findBySessionId(String sessionId)`.
  - `WhatsAppEvent extends ApplicationEvent`: `username()`, `name()`, `payload()`.
  - WS behaviour: `/ws`; first frame `auth` → reply `{type:"response", id:"auth", result:{"ok":true,"username":...}}`; invalid/missing auth → close `4401`; replaced → old closed `4409`; `ping` → `pong`; `request hello` → stores `params.waState`/`params.info`, replies `{ok:true}`; other `request` → `METHOD_NOT_FOUND` error response; `event` → publishes `WhatsAppEvent` and updates `waState` for `ready` (→ `CONNECTED`, info = payload), `change_state` (→ `payload.state`), `disconnected` (→ `DISCONNECTED`).
  - `FakeDevice` test helper (below) reused by Task 7.

- [ ] **Step 1: Write the test helper**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/support/FakeDevice.java`:

```java
package me.nageshk.whatsapp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.rpc.Envelope;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** A minimal stand-in for the Chrome extension: connects, sends frames, queues received frames. */
public class FakeDevice extends TextWebSocketHandler implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private WebSocketSession session;

    public static FakeDevice connect(int port) throws Exception {
        FakeDevice device = new FakeDevice();
        device.session = new StandardWebSocketClient()
                .execute(device, URI.create("ws://localhost:" + port + "/ws").toString())
                .get(5, TimeUnit.SECONDS);
        return device;
    }

    /** Connects and completes the auth handshake. */
    public static FakeDevice connectAndAuth(int port, String token) throws Exception {
        FakeDevice device = connect(port);
        device.send(Envelope.auth(token));
        Envelope reply = device.next();
        if (!"auth".equals(reply.id()) || reply.error() != null) {
            throw new IllegalStateException("auth failed: " + reply);
        }
        return device;
    }

    public void send(Envelope frame) throws Exception {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** Next received frame, waiting up to 5 s. */
    public Envelope next() throws InterruptedException {
        Envelope e = inbox.poll(5, TimeUnit.SECONDS);
        if (e == null) throw new AssertionError("no frame received within 5s");
        return e;
    }

    public CloseStatus awaitClose() throws Exception {
        return closed.get(10, TimeUnit.SECONDS);
    }

    public boolean isOpen() {
        return session != null && session.isOpen();
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
        inbox.add(mapper.readValue(message.getPayload(), Envelope.class));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closed.complete(status);
    }

    /** Closes and waits for the close handshake so the server has unregistered us before the next test. */
    @Override
    public void close() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
            awaitClose();
        }
    }
}
```

- [ ] **Step 2: Write the failing integration tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/ws/BridgeWebSocketIT.java`:

```java
package me.nageshk.whatsapp.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.auth.JwtService;
import me.nageshk.whatsapp.events.WhatsAppEvent;
import me.nageshk.whatsapp.rpc.Envelope;
import me.nageshk.whatsapp.support.FakeDevice;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.CloseStatus;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wabridge.rpc.auth-timeout=1s")
class BridgeWebSocketIT {

    /** Nested @TestConfiguration classes are registered as beans automatically; no @Bean needed. */
    @TestConfiguration
    static class Capture {
        final BlockingQueue<WhatsAppEvent> events = new LinkedBlockingQueue<>();

        @EventListener
        void on(WhatsAppEvent e) {
            events.add(e);
        }
    }

    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired DeviceRegistry registry;
    @Autowired Capture capture;
    @Autowired ObjectMapper mapper;

    @Test
    void authenticatesAndRegistersDevice() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, jwt.issue("demo").token())) {
            assertThat(registry.find("demo")).isPresent();
            assertThat(registry.find("demo").get().waState()).isEqualTo(DeviceSession.STATE_UNKNOWN);
        }
    }

    @Test
    void closesWith4401WhenNoAuthFrameArrivesInTime() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        CloseStatus status = device.awaitClose();
        assertThat(status.getCode()).isEqualTo(4401);
    }

    @Test
    void closesWith4401OnInvalidToken() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth("garbage"));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void secondDeviceReplacesFirstWith4409() throws Exception {
        String token = jwt.issue("demo").token();
        FakeDevice first = FakeDevice.connectAndAuth(port, token);
        try (FakeDevice second = FakeDevice.connectAndAuth(port, token)) {
            assertThat(first.awaitClose().getCode()).isEqualTo(4409);
            assertThat(registry.find("demo")).isPresent();
            assertThat(second.isOpen()).isTrue();
        }
    }

    @Test
    void pingGetsPong() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, jwt.issue("demo").token())) {
            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG);
        }
    }

    @Test
    void helloUpdatesStateAndUnknownMethodIsRejected() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, jwt.issue("demo").token())) {
            var params = mapper.createObjectNode().put("waState", "CONNECTED");
            params.set("info", mapper.createObjectNode().put("pushname", "Nagesh"));
            device.send(Envelope.request("h1", "hello", params));
            Envelope reply = device.next();
            assertThat(reply.id()).isEqualTo("h1");
            assertThat(reply.result().get("ok").asBoolean()).isTrue();

            DeviceSession session = registry.find("demo").orElseThrow();
            assertThat(session.waState()).isEqualTo("CONNECTED");
            assertThat(session.info().get("pushname").asText()).isEqualTo("Nagesh");

            device.send(Envelope.request("h2", "nope", null));
            Envelope err = device.next();
            assertThat(err.error().code()).isEqualTo("METHOD_NOT_FOUND");
        }
    }

    @Test
    void eventsArePublishedAndStateTracked() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, jwt.issue("demo").token())) {
            capture.events.clear();

            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N").put("waVersion", "2.3000.1")));
            WhatsAppEvent ready = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(ready).isNotNull();
            assertThat(ready.username()).isEqualTo("demo");
            assertThat(ready.name()).isEqualTo("ready");
            assertThat(registry.find("demo").orElseThrow().waState()).isEqualTo("CONNECTED");

            device.send(Envelope.event("message", mapper.createObjectNode().put("body", "hi").put("from", "1@c.us")));
            WhatsAppEvent msg = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(msg.name()).isEqualTo("message");
            assertThat(msg.payload().get("body").asText()).isEqualTo("hi");

            device.send(Envelope.event("change_state", mapper.createObjectNode().put("state", "TIMEOUT")));
            capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(registry.find("demo").orElseThrow().waState()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void disconnectUnregistersDevice() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, jwt.issue("demo").token());
        device.close();
        Thread.sleep(200); // afterConnectionClosed runs on the container thread right after the handshake
        assertThat(registry.find("demo")).isEmpty();
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=BridgeWebSocketIT`
Expected: compilation errors (`DeviceRegistry`, `WhatsAppEvent` missing).

- [ ] **Step 4: Implement DeviceRegistry**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/DeviceRegistry.java`:

```java
package me.nageshk.whatsapp.ws;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Tracks the single live extension connection per user. */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, DeviceSession> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceSession> bySessionId = new ConcurrentHashMap<>();

    /** @return the previous session for this user, if one was replaced. */
    public synchronized Optional<DeviceSession> register(DeviceSession session) {
        DeviceSession previous = byUser.put(session.username(), session);
        bySessionId.put(session.sessionId(), session);
        if (previous != null) {
            bySessionId.remove(previous.sessionId());
        }
        return Optional.ofNullable(previous);
    }

    /** Removes by socket id. Only clears the user mapping if it still points at this same session. */
    public synchronized Optional<DeviceSession> unregister(String sessionId) {
        DeviceSession removed = bySessionId.remove(sessionId);
        if (removed != null) {
            byUser.remove(removed.username(), removed);
        }
        return Optional.ofNullable(removed);
    }

    public Optional<DeviceSession> find(String username) {
        return Optional.ofNullable(byUser.get(username));
    }

    public Optional<DeviceSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }
}
```

- [ ] **Step 5: Implement WhatsAppEvent and LoggingEventListener**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/events/WhatsAppEvent.java`:

```java
package me.nageshk.whatsapp.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEvent;

/** An event emitted by the WhatsApp page of one user's extension (name = whatsapp-web.js event name). */
public class WhatsAppEvent extends ApplicationEvent {

    private final String username;
    private final String name;
    private final JsonNode payload;

    public WhatsAppEvent(Object source, String username, String name, JsonNode payload) {
        super(source);
        this.username = username;
        this.name = name;
        this.payload = payload;
    }

    public String username() {
        return username;
    }

    public String name() {
        return name;
    }

    public JsonNode payload() {
        return payload;
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/events/LoggingEventListener.java`:

```java
package me.nageshk.whatsapp.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** PoC consumer: logs every WhatsApp event. Later consumers (DB, webhooks) subscribe the same way. */
@Component
public class LoggingEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    @EventListener
    public void on(WhatsAppEvent event) {
        JsonNode p = event.payload();
        switch (event.name()) {
            case "message", "message_create" -> log.info(
                    "[{}] {} id={} chat={} from={} type={} body={}",
                    event.username(), event.name(),
                    p.path("id").path("_serialized").asText(),
                    p.path("id").path("remote").asText(),
                    p.path("from").asText(),
                    p.path("type").asText(),
                    p.path("body").asText());
            case "message_ack" -> log.info(
                    "[{}] message_ack id={} ack={}",
                    event.username(),
                    p.path("message").path("id").path("_serialized").asText(),
                    p.path("ack").asInt());
            case "ready" -> log.info(
                    "[{}] ready wid={} pushname={} waVersion={}",
                    event.username(),
                    p.path("wid").path("_serialized").asText(),
                    p.path("pushname").asText(),
                    p.path("waVersion").asText());
            default -> log.info("[{}] {} {}", event.username(), event.name(), p);
        }
    }
}
```

- [ ] **Step 6: Implement BridgeWebSocketHandler**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/BridgeWebSocketHandler.java`:

```java
package me.nageshk.whatsapp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nageshk.whatsapp.auth.JwtService;
import me.nageshk.whatsapp.config.WabridgeProperties;
import me.nageshk.whatsapp.events.WhatsAppEvent;
import me.nageshk.whatsapp.rpc.Envelope;
import me.nageshk.whatsapp.rpc.RpcDispatcher;
import me.nageshk.whatsapp.rpc.RpcException;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class BridgeWebSocketHandler extends TextWebSocketHandler {

    public static final int CLOSE_UNAUTHORIZED = 4401;
    public static final int CLOSE_REPLACED = 4409;

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final JwtService jwt;
    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;
    private final ApplicationEventPublisher publisher;
    private final ScheduledExecutorService scheduler;
    private final WabridgeProperties props;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> authTimers = new ConcurrentHashMap<>();

    public BridgeWebSocketHandler(
            ObjectMapper mapper,
            JwtService jwt,
            DeviceRegistry registry,
            RpcDispatcher dispatcher,
            ApplicationEventPublisher publisher,
            ScheduledExecutorService wsScheduler,
            WabridgeProperties props) {
        this.mapper = mapper;
        this.jwt = jwt;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.publisher = publisher;
        this.scheduler = wsScheduler;
        this.props = props;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long millis = props.rpc().authTimeout().toMillis();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (registry.findBySessionId(session.getId()).isEmpty()) {
                closeQuietly(session, CLOSE_UNAUTHORIZED, "auth timeout");
            }
        }, millis, TimeUnit.MILLISECONDS);
        authTimers.put(session.getId(), timer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Envelope frame;
        try {
            frame = mapper.readValue(message.getPayload(), Envelope.class);
        } catch (IOException e) {
            log.warn("Unparseable frame from {}: {}", session.getId(), e.getMessage());
            return;
        }

        Optional<DeviceSession> maybeDevice = registry.findBySessionId(session.getId());
        if (maybeDevice.isEmpty()) {
            handleUnauthenticated(session, frame);
            return;
        }
        DeviceSession device = maybeDevice.get();

        switch (frame.type() == null ? "" : frame.type()) {
            case Envelope.REQUEST -> handleRequest(device, frame);
            case Envelope.RESPONSE -> {
                if (!dispatcher.complete(frame)) {
                    log.debug("[{}] response for unknown id {}", device.username(), frame.id());
                }
            }
            case Envelope.EVENT -> handleEvent(device, frame);
            case Envelope.PING -> device.send(Envelope.pong());
            case Envelope.PONG -> { /* keepalive reply, nothing to do */ }
            default -> log.warn("[{}] unknown frame type {}", device.username(), frame.type());
        }
    }

    private void handleUnauthenticated(WebSocketSession session, Envelope frame) throws IOException {
        if (!Envelope.AUTH.equals(frame.type()) || frame.token() == null) {
            closeQuietly(session, CLOSE_UNAUTHORIZED, "auth required");
            return;
        }
        Optional<String> username = jwt.verify(frame.token());
        if (username.isEmpty()) {
            closeQuietly(session, CLOSE_UNAUTHORIZED, "invalid token");
            return;
        }
        cancelAuthTimer(session.getId());
        DeviceSession device = new DeviceSession(username.get(), session, mapper);
        registry.register(device).ifPresent(old -> {
            log.info("[{}] replacing device {} with {}", old.username(), old.sessionId(), device.sessionId());
            old.close(CLOSE_REPLACED, "replaced by newer connection");
        });
        ObjectNode ok = mapper.createObjectNode().put("ok", true).put("username", username.get());
        device.send(Envelope.response("auth", ok));
        log.info("[{}] device connected ({})", username.get(), session.getId());
    }

    private void handleRequest(DeviceSession device, Envelope frame) throws IOException {
        if ("hello".equals(frame.method())) {
            JsonNode params = frame.params();
            if (params != null) {
                if (params.hasNonNull("waState")) device.setWaState(params.get("waState").asText());
                if (params.hasNonNull("info")) device.setInfo(params.get("info"));
            }
            device.send(Envelope.response(frame.id(), mapper.createObjectNode().put("ok", true)));
            return;
        }
        device.send(Envelope.errorResponse(frame.id(), RpcException.METHOD_NOT_FOUND,
                "unknown server method: " + frame.method()));
    }

    private void handleEvent(DeviceSession device, Envelope frame) {
        JsonNode payload = frame.payload() == null ? mapper.nullNode() : frame.payload();
        switch (frame.name() == null ? "" : frame.name()) {
            case "ready" -> {
                device.setWaState(DeviceSession.STATE_CONNECTED);
                device.setInfo(payload);
            }
            case "change_state" -> device.setWaState(payload.path("state").asText(DeviceSession.STATE_UNKNOWN));
            case "disconnected" -> device.setWaState("DISCONNECTED");
            default -> { /* no state change */ }
        }
        publisher.publishEvent(new WhatsAppEvent(this, device.username(), frame.name(), payload));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelAuthTimer(session.getId());
        registry.unregister(session.getId()).ifPresent(device -> {
            int failed = dispatcher.failAll(device.username(), RpcException.DEVICE_DISCONNECTED);
            log.info("[{}] device disconnected ({}), status={}, failedCalls={}",
                    device.username(), session.getId(), status.getCode(), failed);
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("transport error on {}: {}", session.getId(), exception.toString());
    }

    private void cancelAuthTimer(String sessionId) {
        ScheduledFuture<?> timer = authTimers.remove(sessionId);
        if (timer != null) timer.cancel(false);
    }

    private static void closeQuietly(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) session.close(new CloseStatus(code, reason));
        } catch (IOException ignoredError) {
            // already closed
        }
    }
}
```

- [ ] **Step 7: Implement WebSocketConfig**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/config/WebSocketConfig.java`:

```java
package me.nageshk.whatsapp.config;

import me.nageshk.whatsapp.ws.BridgeWebSocketHandler;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BridgeWebSocketHandler handler;
    private final WabridgeProperties props;

    public WebSocketConfig(BridgeWebSocketHandler handler, WabridgeProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // TODO(security hardening, post-PoC): pin to chrome-extension://<published extension id>
        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(props.ws().allowedOrigins().split("\\s*,\\s*"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService wsScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-auth-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
```

Note: the `TODO` above is an intentional, tracked hardening item from the spec's roadmap, not an unfinished step.

- [ ] **Step 8: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=BridgeWebSocketIT`
Expected: 8 tests pass. If `closesWith4401WhenNoAuthFrameArrivesInTime` is flaky, confirm the `wabridge.rpc.auth-timeout=1s` property is applied (the record field is `authTimeout`, bound from `auth-timeout`).

- [ ] **Step 9: Run the whole suite**

Run: `cd whatsapp-backend && ./mvnw -q test`
Expected: all tests pass (context tests, JWT, auth controller, envelope, dispatcher, WS IT).

- [ ] **Step 10: Commit**

```bash
git add server/src
git commit -m "feat(server): websocket device handler, registry and event publishing

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: REST API for sending messages and status

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/NoDeviceException.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/MessagesController.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/StatusController.java`, `whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/ApiExceptionHandler.java`, `whatsapp-backend/README.md`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/api/MessagesApiIT.java`

**Interfaces:**
- Consumes: `DeviceRegistry.find`, `RpcDispatcher.call`, `DeviceSession.waState/info`, `FakeDevice`.
- Produces:
  - `POST /api/messages` body `{chatId, content, options?, sendSeen?}` → `200` with the page's message DTO; `409 {code:"NO_DEVICE"}` when the user has no device; `409 {code:"WHATSAPP_NOT_READY"}` when `waState != CONNECTED`; `400 {code, message}` for `PAGE_ERROR|CHAT_NOT_FOUND|METHOD_NOT_FOUND|VALIDATION`; `502 {code:"DEVICE_DISCONNECTED"|"SEND_FAILED"}`; `504 {code:"TIMEOUT"}`.
  - `GET /api/status` → `{deviceConnected, waState, info}`.
  - Page-side method contract sent to the extension: `request` `method:"sendMessage"`, `params:{chatId, content, options, sendSeen}`.

- [ ] **Step 1: Write the failing integration tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/api/MessagesApiIT.java`:

```java
package me.nageshk.whatsapp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.auth.JwtService;
import me.nageshk.whatsapp.rpc.Envelope;
import me.nageshk.whatsapp.support.FakeDevice;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wabridge.rpc.timeout=2s")
class MessagesApiIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<JsonNode> postMessage(String token, String body) {
        return rest.exchange("/api/messages", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), JsonNode.class);
    }

    @Test
    void sendWithoutDeviceReturns409() {
        ResponseEntity<JsonNode> res = postMessage(jwt.issue("demo").token(),
                "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NO_DEVICE");
    }

    @Test
    void sendWhenWhatsAppNotReadyReturns409() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("WHATSAPP_NOT_READY");
        }
    }

    @Test
    void sendRoundTripsThroughDevice() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N")));
            Thread.sleep(200);

            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"hello\",\"options\":{\"linkPreview\":false}}"));

            Envelope req = device.next();
            assertThat(req.type()).isEqualTo(Envelope.REQUEST);
            assertThat(req.method()).isEqualTo("sendMessage");
            assertThat(req.params().get("chatId").asText()).isEqualTo("1@c.us");
            assertThat(req.params().get("content").asText()).isEqualTo("hello");
            assertThat(req.params().get("options").get("linkPreview").asBoolean()).isFalse();
            assertThat(req.params().get("sendSeen").asBoolean()).isFalse();

            var result = mapper.createObjectNode().put("body", "hello");
            result.set("id", mapper.createObjectNode().put("_serialized", "true_1@c.us_ABC").put("fromMe", true));
            device.send(Envelope.response(req.id(), result));

            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("id").get("_serialized").asText()).isEqualTo("true_1@c.us_ABC");
        }
    }

    @Test
    void pageErrorMapsTo400() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode()));
            Thread.sleep(200);

            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    postMessage(token, "{\"chatId\":\"bad\",\"content\":\"x\"}"));
            Envelope req = device.next();
            device.send(Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", "Chat not found: bad"));

            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("code").asText()).isEqualTo("CHAT_NOT_FOUND");
            assertThat(res.getBody().get("message").asText()).isEqualTo("Chat not found: bad");
        }
    }

    @Test
    void noResponseMapsTo504() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"x\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void validationErrorMapsTo400() {
        ResponseEntity<JsonNode> res = postMessage(jwt.issue("demo").token(), "{\"content\":\"x\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void statusReflectsDevice() throws Exception {
        String token = jwt.issue("demo").token();
        ResponseEntity<JsonNode> before = rest.exchange("/api/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(before.getBody().get("deviceConnected").asBoolean()).isFalse();

        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N")));
            Thread.sleep(200);
            ResponseEntity<JsonNode> after = rest.exchange("/api/status", HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(after.getBody().get("deviceConnected").asBoolean()).isTrue();
            assertThat(after.getBody().get("waState").asText()).isEqualTo("CONNECTED");
            assertThat(after.getBody().get("info").get("pushname").asText()).isEqualTo("N");
        }
    }

    @Test
    void statusWithoutTokenIs401() {
        ResponseEntity<Map> res = rest.getForEntity("/api/status", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=MessagesApiIT`
Expected: tests fail with 404 responses (controllers missing).

- [ ] **Step 3: Implement NoDeviceException and controllers**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/NoDeviceException.java`:

```java
package me.nageshk.whatsapp.api;

/** The current user has no connected extension, or its WhatsApp is not in CONNECTED state. */
public class NoDeviceException extends RuntimeException {

    public static final String NO_DEVICE = "NO_DEVICE";
    public static final String WHATSAPP_NOT_READY = "WHATSAPP_NOT_READY";

    private final String code;

    public NoDeviceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/MessagesController.java`:

```java
package me.nageshk.whatsapp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nageshk.whatsapp.rpc.RpcDispatcher;
import me.nageshk.whatsapp.ws.DeviceRegistry;
import me.nageshk.whatsapp.ws.DeviceSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MessagesController {

    public record SendMessageRequest(
            @NotBlank String chatId,
            @NotBlank String content,
            JsonNode options,
            Boolean sendSeen) {}

    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;

    public MessagesController(DeviceRegistry registry, RpcDispatcher dispatcher, ObjectMapper mapper) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @PostMapping("/messages")
    public CompletableFuture<JsonNode> send(@Valid @RequestBody SendMessageRequest req, Authentication auth) {
        DeviceSession device = requireReadyDevice(auth.getName());
        ObjectNode params = mapper.createObjectNode();
        params.put("chatId", req.chatId());
        params.put("content", req.content());
        params.set("options", req.options() == null || req.options().isNull() ? mapper.createObjectNode() : req.options());
        params.put("sendSeen", Boolean.TRUE.equals(req.sendSeen()));
        return dispatcher.call(device, "sendMessage", params);
    }

    private DeviceSession requireReadyDevice(String username) {
        DeviceSession device = registry.find(username)
                .orElseThrow(() -> new NoDeviceException(NoDeviceException.NO_DEVICE,
                        "no extension connected for user " + username));
        if (!DeviceSession.STATE_CONNECTED.equals(device.waState())) {
            throw new NoDeviceException(NoDeviceException.WHATSAPP_NOT_READY,
                    "WhatsApp state is " + device.waState());
        }
        return device;
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/StatusController.java`:

```java
package me.nageshk.whatsapp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.whatsapp.ws.DeviceRegistry;
import me.nageshk.whatsapp.ws.DeviceSession;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    public record StatusResponse(boolean deviceConnected, String waState, JsonNode info) {}

    private final DeviceRegistry registry;
    private final ObjectMapper mapper;

    public StatusController(DeviceRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public StatusResponse status(Authentication auth) {
        return registry.find(auth.getName())
                .map(d -> new StatusResponse(true, d.waState(), d.info()))
                .orElseGet(() -> new StatusResponse(false, DeviceSession.STATE_UNKNOWN, mapper.createObjectNode()));
    }
}
```

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/ApiExceptionHandler.java`:

```java
package me.nageshk.whatsapp.api;

import me.nageshk.whatsapp.rpc.RpcException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }

    @ExceptionHandler(NoDeviceException.class)
    public ResponseEntity<Map<String, String>> noDevice(NoDeviceException e) {
        return body(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", message);
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Map<String, String>> rpc(RpcException e) {
        HttpStatus status = switch (e.code()) {
            case RpcException.DEVICE_DISCONNECTED, RpcException.SEND_FAILED -> HttpStatus.BAD_GATEWAY;
            case RpcException.WHATSAPP_NOT_READY -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return body(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, String>> timeout(TimeoutException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "TIMEOUT", "extension did not respond in time");
    }

    /** Async controller results may surface wrapped; unwrap and re-dispatch. */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Map<String, String>> completion(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RpcException rpc) return rpc(rpc);
        if (cause instanceof TimeoutException t) return timeout(t);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", String.valueOf(cause));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=MessagesApiIT`
Expected: 8 tests pass. If `noResponseMapsTo504` returns 500 with `TimeoutException` in the body, the exception reached the handler unwrapped from a type not covered; add an `@ExceptionHandler(ExecutionException.class)` mirroring the `CompletionException` one.

- [ ] **Step 4b: Update the Task 3 probe test**

`/api/status` now exists, so in `AuthControllerTest.protectedEndpointWithTokenIsNotRejectedByAuth` replace
`status().isNotFound()` with `status().isOk()` and drop the "does not exist yet" comment.

- [ ] **Step 5: Write server README**

`whatsapp-backend/README.md`:

````markdown
# wabridge server

Spring Boot backend for the WhatsApp Web Chrome extension bridge. See
`docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md`.

## Run

```bash
./mvnw spring-boot:run
```

Default dev user: `demo` / `demo123` (see `src/main/resources/application.yml`). Set
`WABRIDGE_JWT_SECRET` to a random string of at least 32 bytes outside development.

## API

```bash
# login
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | jq -r .token)

# status of this user's extension
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status

# send a text message
curl -s -X POST localhost:8080/api/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"919999999999@c.us","content":"hello from server"}'
```

Error codes: `NO_DEVICE` / `WHATSAPP_NOT_READY` (409), `CHAT_NOT_FOUND` / `PAGE_ERROR` /
`VALIDATION` (400), `DEVICE_DISCONNECTED` / `SEND_FAILED` (502), `TIMEOUT` (504).

## WebSocket protocol (`/ws`)

First frame must be `{"type":"auth","token":"<jwt>"}` within 5 s, else close 4401. A newer
connection for the same user replaces the older one (closed with 4409). Frames:
`request{id,method,params}`, `response{id,result|error{code,message}}`, `event{name,payload}`,
`ping`/`pong`. Incoming events are logged by `LoggingEventListener`.
````

- [ ] **Step 6: Full suite and manual smoke**

Run: `cd whatsapp-backend && ./mvnw -q test`
Expected: all tests pass.

Run: `cd whatsapp-backend && ./mvnw -q spring-boot:run` in one terminal, then in another:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"demo123"}' | jq -r .token)
curl -s -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/status
curl -s -i -X POST localhost:8080/api/messages -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"chatId":"1@c.us","content":"x"}'
```

Expected: status `200 {"deviceConnected":false,...}`; send `409 {"code":"NO_DEVICE",...}`. Stop the server.

- [ ] **Step 7: Commit**

```bash
git add server
git commit -m "feat(server): REST endpoints to send messages and read status

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- Spec coverage: login/JWT (T2, T3), first-frame auth + 4401/4409 (T6), registry one-device-per-user (T6), dispatcher with 30 s timeout (T5), `hello` handling and event → ApplicationEvent → logging (T6), REST send/status with 409/400/502/504 mapping (T7), CORS for extension origin (T3), config in `application.yml` (T1). Client-side pieces are in the extension plan.
- Naming: the spec's `UsersProperties` became `WabridgeProperties` (covers jwt/users/ws/rpc in one record). `DeviceSession.STATE_CONNECTED` = `"CONNECTED"` matches WhatsApp's `Socket.state` value used by the page.
- Type consistency: `Envelope` factories, `RpcException` codes, `DeviceSession` accessors, and `DeviceRegistry` method names are identical across Tasks 4-7 and `FakeDevice`.
