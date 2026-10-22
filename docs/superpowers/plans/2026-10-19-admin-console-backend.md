# Admin Console — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backend support for the first console slice: super admin seeded with two-factor off, `GET /api/users/me`, wrong-code 400 on self-service two-factor, server-side logout, and serving the React build from the jar.

**Architecture:** Small changes to the existing `user/`, `auth/`, `api/` and `config/` packages, plus one new Mongo collection `revokedTokens` (TTL-indexed) checked by `AccessTokenAuthenticator`. A forwarding controller serves `index.html` for SPA routes, and a Maven profile copies the frontend's `dist/` into `static/`.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Security 6, Spring Data MongoDB, jjwt 0.12.6, JUnit 5 + MockMvc + embedded Mongo (flapdoodle, Mongo 7.0.12).

**Spec:** `docs/superpowers/specs/2026-10-19-admin-console-design.md` (backend sections). Builds on `docs/superpowers/specs/2026-10-19-users-and-two-factor-login-design.md`.

## Global Constraints

- Package root `me.nageshk.omnichannel`; no Lombok; plain getters/setters and records, matching existing code.
- Extension contract unchanged: `POST /api/auth/login` and `POST /api/auth/verify-otp` request/response shapes, including `{ error: "..." }` login errors, stay as they are.
- Non-auth API errors use the existing `ApiExceptionHandler` shape `{ code, message }`.
- No response ever contains a password hash, `tokenVersion`, or a stored TOTP secret.
- `mvn test` (no profile flags) must pass without the frontend repo present.
- Every task ends with the whole suite green: `./mvnw test` → `Tests run: N, Failures: 0, Errors: 0`.

## Review Focus

1. A token issued before this change (no `jti` claim) must still authenticate until it expires — test in Task 4.
2. Logging out twice with the same token: the second call is 401, not 500 or 204 — test in Task 4.
3. A revoked token on the WebSocket `auth` frame must be refused like a stale one — test in Task 4.
4. Unknown `/api/**` paths must keep returning a 401/404, never `index.html` — test in Task 5.
5. Narrowing the login filter must not change login/verify-otp behaviour or make `/api/auth/logout` anonymous — existing `LoginIT`/`VerifyOtpIT` plus a Task 4 test.

---

### Task 1: Super admin seeded with two-factor off

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/user/UserSeeder.java`
- Modify: `src/main/java/me/nageshk/omnichannel/config/OmnichannelProperties.java` (drop `SuperAdmin.totpSecret`)
- Modify: `src/main/resources/application.yml` (drop `super-admin.totp-secret`)
- Modify: `src/test/java/me/nageshk/omnichannel/user/UserSeederTest.java`
- Modify: `README.md` (Run table and example: remove `_TOTP_SECRET`; say two-factor is enabled later from the console Profile page or `/api/users/me/two-factor/*`)

**Interfaces:**
- Produces: `OmnichannelProperties.SuperAdmin(String name, String username, String password, String email, String mobileNumber)` (5 components).

- [ ] **Step 1: Update `UserSeederTest`.** Change the `SuperAdmin` factory to the 5-argument constructor. Replace any test that expected two-factor on from a secret with:

```java
@Test
void superAdminIsSeededWithTwoFactorOff() {
    // given no SUPER_ADMIN exists, run the seeder
    // then the saved user has getTwoFactor().enabled() == false and totpSecret() == null
}
```
Keep the existing idempotence, missing-hash and device-user tests.

- [ ] **Step 2: Run** `./mvnw test -Dtest=UserSeederTest` — Expected: compilation FAILURE (`SuperAdmin` constructor arity).
- [ ] **Step 3: Implement.** Remove `totpSecret` from the record and YAML; seeder always sets `TwoFactor.disabled()`; log line keeps saying "two-factor off". Update README.
- [ ] **Step 4: Run** `./mvnw test -Dtest=UserSeederTest` — Expected: PASS. Then `./mvnw test` — Expected: all green.
- [ ] **Step 5: Commit** `feat(user): seed super admin with two-factor off`

### Task 2: `GET /api/users/me`

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/user/UserController.java` (class mapping becomes `/api/users/me`; existing endpoints move to `/two-factor/initiate|confirm|disable` under it — URLs unchanged)
- Modify: `src/main/java/me/nageshk/omnichannel/user/UserService.java`
- Create: `src/test/java/me/nageshk/omnichannel/user/CurrentUserIT.java`

**Interfaces:**
- Produces: `record UserService.CurrentUser(String id, String name, String username, String email, String mobileNumber, UserRole role, boolean twoFactorEnabled)`; `UserService.CurrentUser currentUser(String username)`; `GET /api/users/me` → 200 JSON of that record.

- [ ] **Step 1: Write `CurrentUserIT`** (`@SpringBootTest @AutoConfigureMockMvc`, `testUsers.deleteAll()` in `@BeforeEach`):
  - `returnsProfileOfCaller`: create `nageshk` SUPER_ADMIN with two-factor on (`TEST_SECRET`), set email `me@example.in`, mobile `9928088743`, name `Nagesh Kumar`; GET with its access token → 200, `$.username=="nageshk"`, `$.name=="Nagesh Kumar"`, `$.email`, `$.mobileNumber`, `$.role=="SUPER_ADMIN"`, `$.twoFactorEnabled==true`, `$.id` equals saved id.
  - `neverExposesSecrets`: same call; `$.password`, `$.tokenVersion`, `$.twoFactor`, `$.totpSecret` do not exist.
  - `reflectsTwoFactorChangeWithoutNewToken`: user with two-factor off, issue token, then set `TwoFactor.enabledWith(TEST_SECRET)` in the repo; GET with the old token → `$.twoFactorEnabled==true`.
  - `requiresToken`: no Authorization → 401.
- [ ] **Step 2: Run** `./mvnw test -Dtest=CurrentUserIT` — Expected: FAIL (404/compile error on `CurrentUser`).
- [ ] **Step 3: Implement** `currentUser` (uses existing `require`) and `@GetMapping` on `UserController` taking `Principal`.
- [ ] **Step 4: Run** `./mvnw test -Dtest=CurrentUserIT,TwoFactorEnrolmentIT` — Expected: PASS. Then `./mvnw test` green.
- [ ] **Step 5: Commit** `feat(user): GET /api/users/me`

### Task 3: Wrong code on self-service two-factor returns 400

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/user/InvalidOtpException.java` (`RuntimeException`, `CODE = "INVALID_OTP"`)
- Modify: `src/main/java/me/nageshk/omnichannel/user/UserService.java` (`verifyWithLockout` throws `InvalidOtpException` instead of `BadCredentialsException`)
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java` (add `InvalidOtpException` → 400 `{code:"INVALID_OTP", message:"the one-time code is not valid"}`; **remove** the global `BadCredentialsException` handler)
- Modify: `src/test/java/me/nageshk/omnichannel/user/TwoFactorEnrolmentIT.java`

- [ ] **Step 1: Update tests.** Rename `confirmWithWrongCodeReturns401` → `confirmWithWrongCodeReturns400`, expecting `status().isBadRequest()` and `$.code=="INVALID_OTP"`. Add `disableWithWrongCodeReturns400` (two-factor on user, disable with `"000000"` → 400 `INVALID_OTP`, user still enabled in repo). Add `thirdWrongCodeLocksWith429` if no lockout test exists there (3 wrong confirms → 4th call 429 `TOO_MANY_ATTEMPTS`).
- [ ] **Step 2: Run** `./mvnw test -Dtest=TwoFactorEnrolmentIT` — Expected: FAIL (401 ≠ 400).
- [ ] **Step 3: Implement** as listed under Files.
- [ ] **Step 4: Run** `./mvnw test -Dtest=TwoFactorEnrolmentIT,VerifyOtpIT,LoginIT` — Expected: PASS (login/verify-otp errors still come from `LoginFailureHandler`, unaffected). Then `./mvnw test` green.
- [ ] **Step 5: Commit** `fix(user): wrong two-factor code on self-service endpoints is 400, not 401`

### Task 4: Server-side logout

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/auth/JwtService.java`
- Create: `src/main/java/me/nageshk/omnichannel/auth/RevokedToken.java` (`@Document("revokedTokens")`: `@Id String jti`, `String username`, `@Indexed(expireAfter = "0s") Instant expiresAt`)
- Create: `src/main/java/me/nageshk/omnichannel/auth/RevokedTokenRepository.java` (`MongoRepository<RevokedToken, String>`)
- Create: `src/main/java/me/nageshk/omnichannel/auth/LogoutController.java`
- Modify: `src/main/java/me/nageshk/omnichannel/auth/AccessTokenAuthenticator.java`
- Modify: `src/main/java/me/nageshk/omnichannel/config/SecurityConfig.java`
- Modify: `src/test/java/me/nageshk/omnichannel/auth/JwtServiceTest.java`
- Create: `src/test/java/me/nageshk/omnichannel/auth/LogoutIT.java`
- Modify: `src/test/java/me/nageshk/omnichannel/ws/BridgeWebSocketIT.java`
- Modify: `README.md` (Login section: logout endpoint)

**Interfaces:**
- Produces:
  - `JwtService.CLAIM_JTI = "jti"` (use jjwt's `.id(...)` / `Claims.getId()`); access tokens get a random UUID id; pending tokens do not.
  - `record JwtService.AccessClaims(String username, long tokenVersion, String jti, Instant expiresAt)` — `jti` is `null` for tokens without one.
  - `POST /api/auth/logout`, Bearer access token → 204.
  - `SecurityConfig.LOGIN_PATH = "/api/auth/login"`, `SecurityConfig.VERIFY_OTP_PATH = "/api/auth/verify-otp"`; `AUTH_PATHS` removed.

- [ ] **Step 1: Write failing tests.**
  - `JwtServiceTest.accessTokenCarriesUniqueJti`: two tokens for the same user → both `verifyAccessToken(...).jti()` non-null and different; `expiresAt()` equals `NOW + 1h`.
  - `JwtServiceTest.pendingTokenHasNoJti`: `parse(issuePendingToken("alice")).getId()` is null.
  - `LogoutIT` (`@SpringBootTest @AutoConfigureMockMvc`; clear users and `RevokedTokenRepository` in `@BeforeEach`; user `dev` USER, two-factor off):
    - `logoutRevokesThatToken`: POST `/api/auth/logout` with token A → 204; GET `/api/status` with A → 401.
    - `otherTokensOfSameUserKeepWorking`: tokens A and B; logout A; GET `/api/status` with B → 200.
    - `revocationEntryExpiresWithToken`: after logout, the `revokedTokens` document for A's jti has `username=="dev"` and `expiresAt` equal to A's `expiresAt`; `mongoTemplate.indexOps(RevokedToken.class).getIndexInfo()` contains an index on `expiresAt` with `expireAfter` of 0 seconds.
    - `logoutTwiceIsUnauthorized`: logout A → 204; logout A again → 401.
    - `logoutWithoutTokenIsUnauthorized`: POST `/api/auth/logout` with no header → 401.
    - `logoutWithPendingTokenIsUnauthorized`: Bearer `jwt.issuePendingToken("dev")` → 401.
    - `tokenWithoutJtiStillAccepted`: build an access token with `Jwts.builder()` signed with `props.jwt().secret()`, subject `dev`, claims `tokenVersion=0`, `role`, `userId`, no id, 1h expiry → GET `/api/status` 200.
    - `loginStillWorks`: POST `/api/auth/login` `{dev, pw}` → 200 with `$.token` (guards the narrowed filter).
  - `BridgeWebSocketIT.authWithRevokedTokenIsRefused`: device token, POST logout with it, then WS `auth` frame with that token → closed with 4401 (mirror `authWithStaleTokenVersionIsRefused`).
- [ ] **Step 2: Run** `./mvnw test -Dtest=JwtServiceTest,LogoutIT,BridgeWebSocketIT` — Expected: FAIL (compile errors on `jti()`/`RevokedTokenRepository`, then 401/404 assertions).
- [ ] **Step 3: Implement.**
  - `JwtService`: add `.id(UUID.randomUUID().toString())` to access tokens; `verifyAccessToken` maps to the 4-arg `AccessClaims` (`c.getId()`, `c.getExpiration().toInstant()`).
  - `AccessTokenAuthenticator.authenticate`: after verifying claims, reject when `claims.jti() != null && revoked.existsById(claims.jti())`.
  - `LogoutController` (`@RestController`, `@PostMapping("/api/auth/logout")`, `@ResponseStatus(NO_CONTENT)`): read the bearer token from `Authorization`, `jwt.verifyAccessToken`, and if `jti` is non-null save `RevokedToken(jti, username, expiresAt)`. A token without `jti` can't be revoked — return 204 anyway (it expires naturally; documented in README).
  - `SecurityConfig`: login filter matcher = `POST LOGIN_PATH` or `POST VERIFY_OTP_PATH` (an `OrRequestMatcher` of two `PathPatternRequestMatcher`s); `permitAll` only for those two POSTs; `/api/auth/logout` falls under `authenticated()`.
- [ ] **Step 4: Run** `./mvnw test -Dtest=JwtServiceTest,LogoutIT,BridgeWebSocketIT,LoginIT,VerifyOtpIT,BearerAuthIT` — Expected: PASS. Then `./mvnw test` green.
- [ ] **Step 5: Commit** `feat(auth): server-side logout via revoked token ids`

### Task 5: Serve the SPA from the jar

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/web/SpaForwardController.java`
- Modify: `src/main/java/me/nageshk/omnichannel/config/SecurityConfig.java`
- Create: `src/test/resources/static/index.html` (minimal `<!doctype html><title>test</title>`, test classpath only)
- Create: `src/test/java/me/nageshk/omnichannel/web/SpaForwardIT.java`

**Interfaces:**
- Produces: any `GET` whose path is not under `/api` or `/ws` and whose last segment has no `.` forwards to `/index.html`. Implementation choice (controller with path patterns, or a `ResourceResolver`) is the builder's, within these tests.

- [ ] **Step 1: Write `SpaForwardIT`** (`@SpringBootTest @AutoConfigureMockMvc`, no auth header anywhere):
  - `rootServesIndex`: GET `/` → 200 and either `forwardedUrl("/index.html")` or content containing `<title>test</title>`.
  - `deepLinksForwardToIndex`: GET `/profile` and GET `/login/verify` → 200, `forwardedUrl("/index.html")`.
  - `staticFileIsNotForwarded`: GET `/index.html` → 200 with content `<title>test</title>`; GET `/missing.js` → 404 (not forwarded).
  - `unknownApiPathIsNotTheSpa`: GET `/api/nope` without token → 401; with a valid token → 404 and `forwardedUrl` is null.
  - `wsPathNotForwarded`: GET `/ws/anything` → `forwardedUrl` is null.
  - `postToSpaPathIsNotPermitted`: POST `/profile` without token → 401.
- [ ] **Step 2: Run** `./mvnw test -Dtest=SpaForwardIT` — Expected: FAIL (401 on `/profile`).
- [ ] **Step 3: Implement.** `SecurityConfig.authorizeHttpRequests` order: POST login + verify-otp `permitAll`; `/ws`, `/ws/**` `permitAll`; `/api/**` `authenticated`; `GET /**` `permitAll`; `anyRequest().authenticated()`. Add the forwarder.
- [ ] **Step 4: Run** `./mvnw test -Dtest=SpaForwardIT,BearerAuthIT,LogoutIT` — Expected: PASS. Then `./mvnw test` green.
- [ ] **Step 5: Commit** `feat(web): serve the console SPA with deep-link forwarding`

### Task 6: `with-frontend` Maven profile

**Files:**
- Modify: `pom.xml`
- Modify: `README.md` (new "Console" section: dev with `npm run start:dev` on 9000 proxying to the backend; production `npm run build` then `./mvnw -Pwith-frontend package`; `-Dfrontend.dist=...` override)

**Interfaces:**
- Produces: profile id `with-frontend`; property `frontend.dist` default `${project.basedir}/../omnichannel_crm_frontend/dist`.

Config-only task (no JUnit test); verified by commands.

- [ ] **Step 1: Add the profile.** `maven-enforcer-plugin` `requireFilesExist` on `${frontend.dist}/index.html` (phase `validate`, message "frontend build not found at ${frontend.dist}; run npm run build in omnichannel_crm_frontend"), and `maven-resources-plugin` `copy-resources` from `${frontend.dist}` to `${project.build.outputDirectory}/static` in `process-resources`.
- [ ] **Step 2: Verify missing dist fails.** Run `./mvnw -q -Pwith-frontend -Dfrontend.dist=/nonexistent -DskipTests package` — Expected: BUILD FAILURE containing "frontend build not found".
- [ ] **Step 3: Verify copy.** Create `<scratch>/dist/index.html` and `<scratch>/dist/app.js`; run `./mvnw -q -Pwith-frontend -Dfrontend.dist=<scratch>/dist -DskipTests package` then `unzip -l target/whatsapp-backend-*.jar | grep BOOT-INF/classes/static/` — Expected: both files listed.
- [ ] **Step 4: Verify default build unaffected.** `./mvnw clean test` — Expected: all green, no `static/app.js` in `target/classes`.
- [ ] **Step 5: Commit** `build: with-frontend profile packages the console into the jar`

### Task 7: Local reseed and smoke test (manual, no commit)

- [ ] Drop local `omnichannel_crm.users` (holds throwaway smoke-test users), start the app on a free port (8080 may be taken) with `OMNICHANNEL_SUPER_ADMIN_PASSWORD` and device user env set.
- [ ] `curl` login as super admin → `{token, expiresAt}` (no two-factor). `GET /api/users/me` → profile, `twoFactorEnabled:false`. `POST /api/auth/logout` → 204; replay `GET /api/users/me` → 401. Device user login → `{token, expiresAt}`.
- [ ] Stop the server. Report the reseeded state to the user.
