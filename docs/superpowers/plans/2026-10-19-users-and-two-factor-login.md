# Users in MongoDB and Two-Factor Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist users in MongoDB, seed the super admin and the extension's device user, and replace the one-step YAML login with the reference project's password-then-TOTP login.

**Architecture:** Spring Data MongoDB `User` document plus a `UserSeeder`. Spring Security `AuthenticationFilter` on `POST /api/auth/**` with two converters and two providers (password, OTP) and a JSON success handler, copied in shape from the reference. One `AccessTokenAuthenticator` validates bearer tokens (signature, token version, active) for both the HTTP filter and the WebSocket `auth` frame.

**Tech Stack:** Spring Boot 3.5.6, Java 21, Spring Security 6, Spring Data MongoDB, jjwt 0.12.6, `dev.samstevens.totp:totp:1.7.1`, flapdoodle embedded Mongo `spring3x` 4.20.0 (tests).

**Spec:** `docs/superpowers/specs/2026-10-19-users-and-two-factor-login-design.md`

## Global Constraints

- Package `me.nageshk.omnichannel`; no Lombok (project does not use it).
- Mongo database `omnichannel_crm`, collection `users`, `_class` = `me.nageshk.omnichannel.user.User`.
- Password stored as bare bcrypt hash; `BCryptPasswordEncoder`, no `{bcrypt}` prefix.
- Config prefix `omnichannel`; env `OMNICHANNEL_JWT_SECRET`, `OMNICHANNEL_MONGODB_URI`, `OMNICHANNEL_SUPER_ADMIN_*`, `OMNICHANNEL_DEVICE_USER_*`.
- Pending token: five minutes, claim `authState=TWO_FA_PENDING`. Access token claims: `userId`, `role`, `tokenVersion`, `twoFactorEnabled`; lifetime `omnichannel.jwt.ttl` default 12h.
- Lockout: 5 failed passwords → 30 min; 3 failed OTPs → 10 min; keyed by lowercase username.
- `POST /api/auth/login` for a user without 2FA still returns `{ token, expiresAt }`.
- Error bodies on auth endpoints: `{ "error": "<code>" }` (plus `retryAfterSeconds` on 429).
- Reference project at `/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp` is read-only.

## Review Focus

1. A pending (2FA) token presented as a bearer on any endpoint other than `verify-otp` must be rejected with 401. Test in Task 9.
2. A user deactivated after logging in must be rejected on the next bearer request, not only at login. Test in Task 9.
3. `OMNICHANNEL_SUPER_ADMIN_TOTP_SECRET` left blank must seed the super admin with 2FA off rather than fail or seed `enabled=true` with a null secret. Test in Task 3.
4. Login with a username that does not exist must return the same 401 `invalid_credentials` body as a wrong password, so the response does not reveal which usernames exist. Test in Task 7.
5. The WebSocket `auth` frame with a token whose `tokenVersion` is stale must be refused exactly like HTTP. Test in Task 9.

---

### Task 0: Commit the package rename and branch

**Files:** none new.

- [ ] **Step 1: Commit the pending rename on a feature branch**

```bash
cd whatsapp-backend
git checkout -b feat/users-and-two-factor
git add -A
git commit -m "refactor: rename package me.nageshk.whatsapp to me.nageshk.omnichannel"
```

- [ ] **Step 2: Verify clean tree**

Run: `git status --short`
Expected: no output.

---

### Task 1: Mongo dependency, `omnichannel` config prefix, embedded Mongo for tests

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Rename: `src/main/java/me/nageshk/omnichannel/config/WabridgeProperties.java` → `OmnichannelProperties.java`
- Rename: `src/main/java/me/nageshk/omnichannel/WabridgeApplication.java` → `OmnichannelApplication.java`
- Rename: `src/test/java/me/nageshk/omnichannel/WabridgeApplicationTests.java` → `OmnichannelApplicationTests.java`
- Modify: every file importing `WabridgeProperties` (`JwtService`, `SecurityConfig`, `WebSocketConfig`, `JwtServiceTest`, others found by grep); `MessagesApiIT`, `BridgeWebSocketIT` property strings `wabridge.rpc.*` → `omnichannel.rpc.*`
- Create: `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: `OmnichannelProperties(Jwt jwt, SuperAdmin superAdmin, DeviceUser deviceUser, TwoFactor twoFactor, Ws ws, Rpc rpc)` records, prefix `omnichannel`:
  - `Jwt(String secret, @DefaultValue("12h") Duration ttl)`
  - `SuperAdmin(@DefaultValue("Nagesh Kumar") String name, @DefaultValue("nageshk") String username, String password, String email, String mobileNumber, String totpSecret)`
  - `DeviceUser(@DefaultValue("WhatsApp Bridge") String name, String username, String password)`
  - `TwoFactor(@DefaultValue("Omnichannel CRM") String issuer)`
  - `Ws`, `Rpc` unchanged. The `users` list is removed.

- [ ] **Step 1: Add dependencies to `pom.xml`**

`spring-boot-starter-data-mongodb` (compile); `dev.samstevens.totp:totp:1.7.1` (compile, property `totp.version`); `de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x:4.20.0` (test, property `flapdoodle.version`). In the surefire plugin add `<systemPropertyVariables><spring.profiles.active>test</spring.profiles.active></systemPropertyVariables>`.

- [ ] **Step 2: Rewrite `application.yml`**

```yaml
server:
  port: 8080
spring:
  data:
    mongodb:
      uri: ${OMNICHANNEL_MONGODB_URI:mongodb://localhost:27017/omnichannel_crm}
      auto-index-creation: true
logging:
  level:
    me.nageshk.omnichannel: INFO
omnichannel:
  jwt:
    secret: ${OMNICHANNEL_JWT_SECRET:dev-only-secret-please-change-me-0123456789}
    ttl: 12h
  super-admin:
    name: ${OMNICHANNEL_SUPER_ADMIN_NAME:Nagesh Kumar}
    username: ${OMNICHANNEL_SUPER_ADMIN_USERNAME:nageshk}
    password: ${OMNICHANNEL_SUPER_ADMIN_PASSWORD:}
    email: ${OMNICHANNEL_SUPER_ADMIN_EMAIL:}
    mobile-number: ${OMNICHANNEL_SUPER_ADMIN_MOBILE:}
    totp-secret: ${OMNICHANNEL_SUPER_ADMIN_TOTP_SECRET:}
  device-user:
    name: ${OMNICHANNEL_DEVICE_USER_NAME:WhatsApp Bridge}
    username: ${OMNICHANNEL_DEVICE_USER_USERNAME:}
    password: ${OMNICHANNEL_DEVICE_USER_PASSWORD:}
  two-factor:
    issuer: Omnichannel CRM
  ws:
    allowed-origins: "chrome-extension://*"
  rpc:
    timeout: 30s
    auth-timeout: 5s
```

- [ ] **Step 3: Create `src/test/resources/application-test.yml`**

```yaml
de:
  flapdoodle:
    mongodb:
      embedded:
        version: 7.0.12
omnichannel:
  super-admin:
    password: ""
```

- [ ] **Step 4: Rename the properties record, application class and its test; fix all imports and property strings**

`OmnichannelApplicationTests` keeps only a `contextLoads` test (the `demo` assertion goes). `SecurityConfig.userDetailsService` temporarily returns an empty `InMemoryUserDetailsManager` so the context still starts; Task 4 replaces it. `JwtServiceTest` builds `OmnichannelProperties` with `null` for the new records.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`; the first run downloads the embedded mongod binary (needs network). `AuthControllerTest` login tests may fail now because `demo` is gone: mark them `@Disabled("replaced in Task 7")` for this task only.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "build: add MongoDB, TOTP and embedded-mongo deps; rename config prefix to omnichannel"
```

---

### Task 2: `User` document, `UserRole`, `TwoFactor`, `UserRepository`, auditing

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/user/User.java`
- Create: `src/main/java/me/nageshk/omnichannel/user/UserRole.java`
- Create: `src/main/java/me/nageshk/omnichannel/user/TwoFactor.java`
- Create: `src/main/java/me/nageshk/omnichannel/user/UserRepository.java`
- Create: `src/main/java/me/nageshk/omnichannel/config/MongoConfig.java`
- Test: `src/test/java/me/nageshk/omnichannel/user/UserRepositoryIT.java`

**Interfaces:**
- Produces:
  - `enum UserRole { SUPER_ADMIN, APP_ADMIN, ADMIN, USER }` with `String authority()` returning `"ROLE_" + name()`.
  - `record TwoFactor(boolean enabled, String totpSecret)` with `static TwoFactor disabled()` and `static TwoFactor enabledWith(String secret)`.
  - `@Document("users") class User` with `@Id String id; String name; @Indexed(unique = true) String username; String password; String email; String mobileNumber; UserRole role; boolean active; TwoFactor twoFactor; long tokenVersion; @CreatedDate Instant creationTime; @LastModifiedDate Instant lastUpdateTime;` plain getters/setters, no-arg constructor.
  - `interface UserRepository extends MongoRepository<User, String>` with `Optional<User> findByUsername(String username)`, `boolean existsByUsername(String username)`, `boolean existsByRole(UserRole role)`.
  - `MongoConfig` annotated `@Configuration @EnableMongoAuditing`.

- [ ] **Step 1: Write the failing test `UserRepositoryIT`**

`@SpringBootTest`, `@Autowired UserRepository repo; @Autowired MongoTemplate template;` `@BeforeEach` deletes all users.

```java
@Test void saveSetsAuditFieldsAndClass() {
    User u = newUser("alice"); repo.save(u);
    Document raw = template.getCollection("users").find().first();
    assertThat(u.getCreationTime()).isNotNull();
    assertThat(u.getLastUpdateTime()).isNotNull();
    assertThat(raw.getString("_class")).isEqualTo("me.nageshk.omnichannel.user.User");
    assertThat(raw.get("tokenVersion")).isInstanceOf(Long.class);
    assertThat(raw.getString("role")).isEqualTo("USER");
}
@Test void usernameIsUnique() {
    repo.save(newUser("bob"));
    assertThatThrownBy(() -> repo.save(newUser("bob"))).isInstanceOf(DuplicateKeyException.class);
}
@Test void findByUsernameAndExistsByRole() {
    repo.save(newUser("carol"));
    assertThat(repo.findByUsername("carol")).isPresent();
    assertThat(repo.findByUsername("CAROL")).isEmpty();
    assertThat(repo.existsByRole(UserRole.USER)).isTrue();
    assertThat(repo.existsByRole(UserRole.SUPER_ADMIN)).isFalse();
}
```

`newUser(username)` sets name, username, password `"x"`, role `USER`, active true, `TwoFactor.disabled()`.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=UserRepositoryIT`
Expected: compilation failure, `User` not found.

- [ ] **Step 3: Implement the four classes and `MongoConfig`**

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=UserRepositoryIT`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(user): User document, role enum, repository and Mongo auditing"
```

---

### Task 3: `UserSeeder`

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/user/UserSeeder.java`
- Test: `src/test/java/me/nageshk/omnichannel/user/UserSeederTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `OmnichannelProperties.SuperAdmin`, `OmnichannelProperties.DeviceUser`.
- Produces: `@Component class UserSeeder implements ApplicationRunner` with constructor `(UserRepository repo, OmnichannelProperties props)` and `void run(ApplicationArguments args)`. Throws `IllegalStateException("omnichannel.super-admin.password (bcrypt hash) is required to seed the first super admin")` when no `SUPER_ADMIN` exists and the hash is blank.

- [ ] **Step 1: Write the failing unit test with a Mockito `UserRepository`**

```java
@Test void seedsSuperAdminWithTwoFactorWhenSecretGiven()  // existsByRole false → save(User) with role SUPER_ADMIN, password == hash verbatim, twoFactor enabled, secret, tokenVersion 0, active true
@Test void seedsSuperAdminWithTwoFactorOffWhenSecretBlank() // totpSecret "" → TwoFactor.disabled()
@Test void skipsSuperAdminWhenOneExists()                   // existsByRole true → never save
@Test void failsWhenNoSuperAdminAndNoPasswordHash()         // existsByRole false, password "" → IllegalStateException
@Test void seedsDeviceUserWhenUsernameSet()                 // existsByUsername false → save role USER, 2FA disabled
@Test void skipsDeviceUserWhenUsernameBlank()               // username "" → no existsByUsername call, no save
@Test void skipsDeviceUserWhenExists()                      // existsByUsername true → no save
@Test void failsWhenDeviceUsernameSetButPasswordBlank()     // IllegalStateException
```

Use `ArgumentCaptor<User>` to assert the saved document.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=UserSeederTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `UserSeeder`**

Log at INFO `seeded super admin '{}'` / `seeded device user '{}'`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=UserSeederTest`
Expected: 8 tests pass. Then `./mvnw -q test` must still be green: the test profile sets the super admin password blank, and no super admin exists in the embedded DB, so the seeder must **not** run under the `test` profile. Annotate `UserSeeder` with `@Profile("!test")`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(user): seed super admin and device user on startup"
```

---

### Task 4: `AuthenticatedUser`, `MongoUserDetailsService`, bcrypt encoder, test user helper

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/auth/AuthenticatedUser.java`
- Create: `src/main/java/me/nageshk/omnichannel/auth/MongoUserDetailsService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/config/SecurityConfig.java` (encoder → `new BCryptPasswordEncoder()`, remove in-memory `userDetailsService` bean and the `AuthenticationManager` bean)
- Create: `src/test/java/me/nageshk/omnichannel/support/TestUsers.java`
- Test: `src/test/java/me/nageshk/omnichannel/auth/MongoUserDetailsServiceIT.java`

**Interfaces:**
- Produces:
  - `class AuthenticatedUser implements UserDetails` with constructor `(User user)` and getters `userId()`, `role()`, `twoFactorEnabled()`, `totpSecret()`, `tokenVersion()`; `getAuthorities()` = single `SimpleGrantedAuthority(role.authority())`; `isEnabled()` = `user.isActive()`; other `is*` return true.
  - `@Service class MongoUserDetailsService implements UserDetailsService`: `loadUserByUsername` throws `UsernameNotFoundException` when absent.
  - `@Component @Profile("test") class TestUsers` (test source) with `User create(String username, String rawPassword, UserRole role, String totpSecret)` (null secret → 2FA off), `void deleteAll()`, and `String TEST_SECRET = "JBSWY3DPEHPK3PXP"`.

- [ ] **Step 1: Write the failing test `MongoUserDetailsServiceIT`**

```java
@Test void loadsActiveUserWithRoleAuthority()       // authorities contain "ROLE_USER", isEnabled true, tokenVersion 0
@Test void inactiveUserIsDisabled()                 // active=false → isEnabled false
@Test void unknownUsernameThrows()                  // UsernameNotFoundException
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=MongoUserDetailsServiceIT`
Expected: compilation failure.

- [ ] **Step 3: Implement the classes, wire the encoder, create `TestUsers`**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS` (the disabled login tests stay disabled until Task 7).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): load users from MongoDB with bcrypt passwords"
```

---

### Task 5: `JwtService` with access and pending tokens

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/auth/JwtService.java`
- Modify: `src/test/java/me/nageshk/omnichannel/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: `AuthenticatedUser` (Task 4).
- Produces:
  - `record IssuedToken(String token, Instant expiresAt)` (unchanged).
  - `IssuedToken issueAccessToken(AuthenticatedUser user)` — claims `userId`, `role`, `tokenVersion` (long), `twoFactorEnabled` (boolean); subject username.
  - `String issuePendingToken(String username)` — claim `authState = "TWO_FA_PENDING"`, expires `now + 5 min`.
  - `record AccessClaims(String username, long tokenVersion)`.
  - `Optional<AccessClaims> verifyAccessToken(String token)` — empty on bad signature, expiry, or when `authState` claim is present.
  - `Optional<String> verifyPendingToken(String token)` — username only when `authState == TWO_FA_PENDING` and not expired.
  - Constants `public static final String CLAIM_AUTH_STATE = "authState"`, `TWO_FA_PENDING = "TWO_FA_PENDING"`, `Duration PENDING_TTL = Duration.ofMinutes(5)`.
- Removes `issue(String)` and `verify(String)`. Callers (`AuthController`, `JwtAuthFilter`, `BridgeWebSocketHandler`, tests) are updated in Tasks 7–9; until then they will not compile, so this task updates them **minimally**: `AuthController.login` and `JwtAuthFilter` use `verifyAccessToken`/`issueAccessToken` via `MongoUserDetailsService`; the WS handler uses `verifyAccessToken(...).map(AccessClaims::username)`; ITs replace `jwt.issue("demo").token()` with `testUsers.accessToken(user)` where `TestUsers` gains `String accessToken(User user)` calling `jwt.issueAccessToken(new AuthenticatedUser(user))`.

- [ ] **Step 1: Rewrite `JwtServiceTest`**

```java
@Test void accessTokenCarriesClaimsAndExpiry()      // expiresAt == NOW+1h; verifyAccessToken → username, tokenVersion 3 (user created with tokenVersion 3)
@Test void accessTokenRejectedWhenExpired()
@Test void accessTokenRejectedWhenTampered()
@Test void pendingTokenExpiresInFiveMinutes()       // verifyPendingToken ok at NOW+4m, empty at NOW+6m
@Test void pendingTokenIsNotAnAccessToken()         // verifyAccessToken(pending) empty
@Test void accessTokenIsNotAPendingToken()          // verifyPendingToken(access) empty
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=JwtServiceTest`
Expected: compilation failure.

- [ ] **Step 3: Implement, then patch callers minimally as listed under Interfaces**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): access tokens with claims and five-minute pending tokens"
```

---

### Task 6: `LoginAttemptService` and `TotpService`

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/auth/LoginAttemptService.java`
- Create: `src/main/java/me/nageshk/omnichannel/auth/AccountLockedException.java`
- Create: `src/main/java/me/nageshk/omnichannel/auth/TotpService.java`
- Create: `src/main/java/me/nageshk/omnichannel/config/TotpConfig.java`
- Test: `src/test/java/me/nageshk/omnichannel/auth/LoginAttemptServiceTest.java`, `TotpServiceTest.java`

**Interfaces:**
- Produces:
  - `@Service class LoginAttemptService` constructor `(Clock clock)`; methods `boolean isLoginBlocked(String u)`, `void recordFailedLogin(String u)`, `void clearLogin(String u)`, `long loginBlockRemainingSeconds(String u)`, and the four `Otp` twins. Constants `MAX_LOGIN_ATTEMPTS = 5`, `LOGIN_BLOCK = Duration.ofMinutes(30)`, `MAX_OTP_ATTEMPTS = 3`, `OTP_BLOCK = Duration.ofMinutes(10)`. Backed by `ConcurrentHashMap<String, Attempt(int count, Instant expiresAt)>`; the first failure sets `expiresAt = now + block`; an expired entry counts as zero. Keys lowercased.
  - `class AccountLockedException extends AuthenticationException` with `long retryAfterSeconds()`.
  - `@Service class TotpService` constructor `(OmnichannelProperties props, TimeProvider timeProvider)`; `String generateSecret()`, `String qrCodeDataUri(String secret, String username)` (`data:image/png;base64,...`, issuer from props), `boolean verify(String secret, String code)`.
  - `TotpConfig` exposes `@Bean TimeProvider timeProvider()` = `new SystemTimeProvider()`.

- [ ] **Step 1: Write the failing tests**

`LoginAttemptServiceTest` with a mutable fixed clock:
```java
@Test void blocksAfterFiveFailedLogins()           // 4 fails → not blocked; 5th → blocked, remaining 1800
@Test void loginBlockExpiresAfterThirtyMinutes()   // advance 31m → not blocked, counter restarts
@Test void clearLoginResetsCounter()
@Test void blocksAfterThreeFailedOtps()
@Test void keysAreCaseInsensitive()                // fail "Alice" ×5 → isLoginBlocked("alice") true
```
`TotpServiceTest` with `TimeProvider` fixed at `1_700_000_000L` seconds and secret `JBSWY3DPEHPK3PXP`:
```java
@Test void verifiesCodeGeneratedForSameWindow()    // code from new DefaultCodeGenerator().generate(secret, 1_700_000_000L/30) → true
@Test void rejectsWrongCode()                      // "000000" → false
@Test void generatedSecretIsBase32OfLength32()
@Test void qrCodeIsPngDataUri()                    // startsWith("data:image/png;base64,")
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='LoginAttemptServiceTest,TotpServiceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -q test -Dtest='LoginAttemptServiceTest,TotpServiceTest'`
Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): in-memory login/OTP lockout and TOTP service"
```

---

### Task 7: Login step one (password)

**Files:**
- Create under `src/main/java/me/nageshk/omnichannel/auth/login/`: `PasswordAuthentication.java`, `PasswordAuthConverter.java`, `PasswordAuthenticationProvider.java`, `DelegatingAuthConverter.java`, `LoginSuccessHandler.java`, `LoginFailureHandler.java`, `LoginRequest.java`
- Modify: `src/main/java/me/nageshk/omnichannel/config/SecurityConfig.java`
- Delete: `src/main/java/me/nageshk/omnichannel/auth/AuthController.java`
- Modify: `src/test/java/me/nageshk/omnichannel/auth/AuthControllerTest.java` → rename `LoginIT.java`

**Interfaces:**
- Consumes: `MongoUserDetailsService`, `PasswordEncoder`, `LoginAttemptService`, `AccountLockedException`, `JwtService.issueAccessToken/issuePendingToken`.
- Produces:
  - `record LoginRequest(String username, String password, String otp)`.
  - `class PasswordAuthentication implements Authentication` as in the reference (principal username, credentials password, details `AuthenticatedUser` after success).
  - `PasswordAuthConverter implements AuthenticationConverter`: returns `PasswordAuthentication` only for `POST /api/auth/login`; `null` otherwise; `AuthenticationServiceException` on malformed JSON; `BadCredentialsException` when username or password blank.
  - `PasswordAuthenticationProvider`: lockout check → load user → disabled → password match (failure recorded) → clear attempts.
  - `DelegatingAuthConverter`: tries password converter then OTP converter (OTP converter arrives in Task 8; this task wires only password).
  - `LoginSuccessHandler implements AuthenticationSuccessHandler` writes JSON: 2FA on → `{twoFactorRequired:true, twoFactorToken}`; off → `{token, expiresAt}`.
  - `LoginFailureHandler implements AuthenticationFailureHandler`: `AccountLockedException` → 429 `{error:"too_many_attempts", retryAfterSeconds}`; `DisabledException` → 401 `account_inactive`; `CredentialsExpiredException` → 401 `invalid_token`; `BadCredentialsException` with message `invalid_otp` → 401 `invalid_otp`; any other → 401 `invalid_credentials`.
  - `SecurityConfig`: `AuthenticationFilter(ProviderManager(password provider), DelegatingAuthConverter)` with request matcher `POST /api/auth/**`, success and failure handlers; placed `addFilterAt(..., UsernamePasswordAuthenticationFilter.class)`; `/api/auth/login` and `/api/auth/verify-otp` `permitAll()` (the filter itself authenticates them).

- [ ] **Step 1: Write the failing `LoginIT`** (`@SpringBootTest @AutoConfigureMockMvc`, `TestUsers`)

```java
@Test void loginWithoutTwoFactorReturnsAccessToken()      // user "dev" pw "pw" → 200, $.token, $.expiresAt
@Test void loginWithTwoFactorReturnsPendingToken()        // user with TEST_SECRET → 200, $.twoFactorRequired true, $.twoFactorToken, no $.token
@Test void wrongPasswordReturns401InvalidCredentials()    // $.error == "invalid_credentials"
@Test void unknownUsernameReturns401InvalidCredentials()  // same body as wrong password
@Test void inactiveUserReturns401AccountInactive()
@Test void missingPasswordReturns401()                    // body {"username":"dev"}
@Test void sixthFailureReturns429()                       // 5× wrong → 6th 429, $.error "too_many_attempts", $.retryAfterSeconds > 0; then correct password also 429
@Test void protectedEndpointWithoutTokenReturns401()
@Test void protectedEndpointWithTokenIsOk()               // testUsers.accessToken(user) on GET /api/status
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=LoginIT`
Expected: failures (old controller returns a token with no 2FA branch, no 429).

- [ ] **Step 3: Implement the login package, rewire `SecurityConfig`, delete `AuthController`**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): password login with lockout and pending two-factor token"
```

---

### Task 8: Login step two (OTP)

**Files:**
- Create under `auth/login/`: `OtpAuthentication.java`, `OtpAuthConverter.java`, `OtpAuthenticationProvider.java`
- Modify: `DelegatingAuthConverter.java`, `SecurityConfig.java` (add provider)
- Test: `src/test/java/me/nageshk/omnichannel/auth/VerifyOtpIT.java`

**Interfaces:**
- Consumes: `JwtService.verifyPendingToken`, `TotpService.verify`, `LoginAttemptService` OTP methods, `MongoUserDetailsService`.
- Produces:
  - `record OtpAuthentication(String principalName, String otp, AuthenticatedUser details) implements Authentication`; `isAuthenticated()` = `details != null`.
  - `OtpAuthConverter`: for `POST /api/auth/verify-otp` reads bearer header and body `{otp}`; missing bearer → `BadCredentialsException("invalid_token")`.
  - `OtpAuthenticationProvider`: pending token → username (`CredentialsExpiredException` when empty); OTP lockout check; `TotpService.verify` (failure → record, `BadCredentialsException("invalid_otp")`); success → clear, return authenticated `OtpAuthentication`.
  - `LoginSuccessHandler` handles `OtpAuthentication` → `{token, expiresAt}`.

- [ ] **Step 1: Write the failing `VerifyOtpIT`**

Helper `currentCode()` = `new DefaultCodeGenerator().generate(TEST_SECRET, Instant.now().getEpochSecond()/30)`.
```java
@Test void validOtpReturnsAccessToken()                 // login → pending; verify-otp with currentCode() → 200 $.token
@Test void wrongOtpReturns401InvalidOtp()
@Test void fourthWrongOtpReturns429()
@Test void verifyOtpWithoutBearerReturns401InvalidToken()
@Test void verifyOtpWithAccessTokenAsBearerReturns401()  // an access token is not a pending token
@Test void pendingTokenIsReusableWithinWindow()         // two successful verifies with same pending token
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=VerifyOtpIT`
Expected: 401 or 404 on `verify-otp`.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): verify-otp completes two-factor login"
```

---

### Task 9: `AccessTokenAuthenticator` for HTTP bearer and WebSocket

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/auth/AccessTokenAuthenticator.java`
- Modify: `src/main/java/me/nageshk/omnichannel/auth/JwtAuthFilter.java`
- Modify: `src/main/java/me/nageshk/omnichannel/ws/BridgeWebSocketHandler.java:110-130` (`handleAuth`)
- Test: `src/test/java/me/nageshk/omnichannel/auth/BearerAuthIT.java`; add two tests to `BridgeWebSocketIT`

**Interfaces:**
- Produces: `@Service class AccessTokenAuthenticator` constructor `(JwtService jwt, UserRepository users)`; `Optional<AuthenticatedUser> authenticate(String token)`: empty when signature/expiry fails, when the user is missing, when `!active`, or when `tokenVersion` differs.
- `JwtAuthFilter` sets `UsernamePasswordAuthenticationToken(authenticatedUser, null, authorities)`; principal name is the username so `Principal.getName()` keeps working for existing controllers.
- WS handler `handleAuth` uses the authenticator; `DeviceSession` keeps `username()`.

- [ ] **Step 1: Write the failing tests**

`BearerAuthIT`:
```java
@Test void pendingTokenRejectedOnProtectedEndpoint()      // jwt.issuePendingToken(u) on GET /api/status → 401
@Test void staleTokenVersionRejected()                    // token, then user.tokenVersion=1 saved → 401
@Test void deactivatedUserRejectedWithValidToken()        // token, then active=false saved → 401
@Test void deletedUserRejected()
```
`BridgeWebSocketIT` additions:
```java
@Test void authWithStaleTokenVersionIsRefused()           // expect the same refusal frame as a bad token
@Test void authWithPendingTokenIsRefused()
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='BearerAuthIT,BridgeWebSocketIT'`
Expected: stale/pending cases pass authentication today → failures.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(auth): bearer and WebSocket auth check token version and active flag"
```

---

### Task 10: Two-factor enrolment endpoints

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/user/UserService.java`, `TwoFactorStateException.java`, `UserController.java`
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java` (409 for `TwoFactorStateException`, code `TWO_FACTOR_STATE`; 401 `{code:"INVALID_OTP"}` for `BadCredentialsException`; 429 for `AccountLockedException` with `retryAfterSeconds`)
- Test: `src/test/java/me/nageshk/omnichannel/user/TwoFactorEnrolmentIT.java`

**Interfaces:**
- Consumes: `TotpService`, `LoginAttemptService`, `UserRepository`.
- Produces:
  - `@Service UserService`: `TwoFactorSetup initiateTwoFactor(String username)` → `record TwoFactorSetup(String secret, String qrCodeDataUri, String issuer, String username)`; `void confirmTwoFactor(String username, String secret, String otp)`; `void disableTwoFactor(String username, String otp)`. State conflicts throw `TwoFactorStateException`.
  - `UserController` `@RequestMapping("/api/users/me/two-factor")`: `POST /initiate` → 200 setup; `POST /confirm` body `{secret, otp}` → 204; `POST /disable` body `{otp}` → 204. Username from `Principal`.

- [ ] **Step 1: Write the failing `TwoFactorEnrolmentIT`** (MockMvc with `testUsers.accessToken`)

```java
@Test void initiateReturnsSecretAndQr()                  // $.secret length 32, $.qrCodeDataUri startsWith data:image/png, $.issuer "Omnichannel CRM"
@Test void confirmEnablesTwoFactor()                     // initiate → confirm with code for returned secret → 204; repo user twoFactor enabled with that secret; next login returns pending token
@Test void confirmWithWrongCodeReturns401()
@Test void initiateWhenAlreadyEnabledReturns409()
@Test void disableWithValidCodeClearsSecret()            // 204; repo twoFactor == disabled(); next login returns token
@Test void disableWhenAlreadyOffReturns409()
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TwoFactorEnrolmentIT`
Expected: 404s.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw -q test 2>&1 | grep -E "Tests run:.*Fail|BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(user): self-service two-factor enrolment endpoints"
```

---

### Task 11: README, manual smoke against local Mongo

**Files:**
- Modify: `README.md` (Run section: required env vars, how to make a bcrypt hash and a TOTP secret, the two-step login curl, device user for the extension; API section: `verify-otp` and two-factor endpoints; remove `demo`)

- [ ] **Step 1: Update `README.md`**

Hash recipe: `htpasswd -bnBC 10 "" 'pw' | tr -d ':\n'`. Secret recipe: any Base32 string of 32 chars, or use `POST /api/users/me/two-factor/initiate` after first login with 2FA off.

- [ ] **Step 2: Smoke test against the local server**

```bash
export OMNICHANNEL_SUPER_ADMIN_PASSWORD='<bcrypt hash>' OMNICHANNEL_SUPER_ADMIN_TOTP_SECRET='<base32>'
export OMNICHANNEL_DEVICE_USER_USERNAME=wabridge OMNICHANNEL_DEVICE_USER_PASSWORD='<bcrypt hash>'
./mvnw spring-boot:run
```
Then: login as device user returns `token`; login as `nageshk` returns `twoFactorToken`; `verify-otp` with the authenticator code returns `token`; `mongosh omnichannel_crm --eval 'db.users.find({}, {password:0, "twoFactor.totpSecret":0})'` shows both users with `_class` `me.nageshk.omnichannel.user.User`.
Expected: all four observations as stated; a restart does not create duplicates.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: users, seeding and two-factor login"
```
