# Design: Users in MongoDB and two-factor login

Status: draft 2026-10-19. First step of moving the backend from proof of concept to production.
Supersedes the YAML user list in `application.yml` and the in-memory user store in `SecurityConfig`.

## Context

The backend (`me.nageshk.omnichannel`, Spring Boot 3.5, Java 21) is becoming the server for an
omnichannel CRM: WhatsApp today, then Instagram, Shopify, Facebook Leads, IndiaMART. Users are the
first thing to persist. Today users live in `application.yml`, login is one step, and the JWT
carries only the username.

The reference implementation is the RedLava WhatsApp platform at
`/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp` (read only). Its two-step login, user document and
TOTP handling are copied in shape, re-named for this project, and trimmed to what this project
needs now.

## Goals

- Persist users in MongoDB database `omnichannel_crm`, collection `users`.
- Seed the first user, the super admin, on startup from environment variables.
- Two-step login: password, then a time-based one-time password (TOTP) for users who have
  two-factor enabled. One step for users who do not.
- Let a logged-in user enrol or disable their own two-factor authenticator.
- Keep the Chrome extension working unchanged. Its device user has two-factor off.

## Non-goals (this round)

- Creating users through the API. Role hierarchy (`SUPER_ADMIN` creates `APP_ADMIN`, who creates
  customer `ADMIN`s, who create their `USER`s) is recorded here but only `SUPER_ADMIN` is seeded
  and no create-user endpoint exists yet.
- Customers, permissions beyond the role, password reset, refresh tokens, API keys.
- Frontend. Backend security first.

## Anti-goals

- Secrets in git. The seeded password hash and TOTP secret come only from environment variables.
- Changing the extension's login contract. `POST /api/auth/login` keeps returning
  `{ token, expiresAt }` for a user without two-factor.
- Redis or any new infrastructure beyond the local MongoDB.

## Data model

Collection `users`, mapped by class `me.nageshk.omnichannel.user.User`:

```
{
  _id:            ObjectId,
  name:           'Nagesh Kumar',
  username:       'nageshk',              // unique index, case-sensitive as stored
  password:       '$2a$10$...',           // bcrypt, stored WITHOUT Spring's {bcrypt} prefix
  email:          'me.nageshkumar@gmail.in',
  mobileNumber:   '9928088743',
  role:           'SUPER_ADMIN',          // enum UserRole, stored as its name
  active:         true,
  twoFactor:      { enabled: true, totpSecret: '5K5S...' },   // totpSecret null when disabled
  tokenVersion:   NumberLong(0),
  creationTime:   ISODate,                // @CreatedDate, Mongo auditing enabled
  lastUpdateTime: ISODate,                // @LastModifiedDate
  _class:         'me.nageshk.omnichannel.user.User'
}
```

- `UserRole` enum: `SUPER_ADMIN`, `APP_ADMIN`, `ADMIN`, `USER`. Spring authority is `ROLE_<name>`.
- `TwoFactor` is an embedded value object, never null on a stored user (`enabled=false,
  totpSecret=null` when off).
- Field names are stable API: the JWT claims and the JSON responses below use the same names.
- More fields will be added later; nothing here should assume the document is closed.

## Seeding the super admin

On startup, if no user with role `SUPER_ADMIN` exists, insert one from configuration:

```yaml
omnichannel:
  super-admin:
    name:        ${OMNICHANNEL_SUPER_ADMIN_NAME:Nagesh Kumar}
    username:    ${OMNICHANNEL_SUPER_ADMIN_USERNAME:nageshk}
    password:    ${OMNICHANNEL_SUPER_ADMIN_PASSWORD}          # bcrypt hash, required
    email:       ${OMNICHANNEL_SUPER_ADMIN_EMAIL:}
    mobile-number: ${OMNICHANNEL_SUPER_ADMIN_MOBILE:}
    totp-secret: ${OMNICHANNEL_SUPER_ADMIN_TOTP_SECRET:}      # optional; 2FA enabled iff present
```

- Seeding runs once; an existing super admin is never overwritten, so later edits in Mongo stick.
- The password hash is validated only when seeding actually has to run: if no super admin exists
  and the hash is missing, startup fails with a clear message. Once a super admin is in Mongo the
  variable may be dropped from the environment, and test contexts that seed through the
  repository do not need it. (Builder-check call.)
- The dev-only `demo` user is gone. Integration tests seed their own users directly through the
  repository.

### Device user for the Chrome extension

The extension logs in unattended, so it needs a user with two-factor off. Startup also seeds one
device user, with the same never-overwrite rule, keyed on its username:

```yaml
omnichannel:
  device-user:
    name:     ${OMNICHANNEL_DEVICE_USER_NAME:WhatsApp Bridge}
    username: ${OMNICHANNEL_DEVICE_USER_USERNAME:}      # seeding skipped when blank
    password: ${OMNICHANNEL_DEVICE_USER_PASSWORD:}      # bcrypt hash; required when username set
```

- Role `USER`, `active=true`, `twoFactor={enabled:false, totpSecret:null}`.
- Seeding is skipped entirely when the username is blank, so a deployment that provisions device
  users another way is not forced to set it.
- The same user record works for the extension's REST login and its WebSocket `auth` frame.

## Login flow

All auth endpoints are under `/api/auth`. The authentication `Filter`, `Converter`, `Provider`
and success handler layout follows the reference project.

```
POST /api/auth/login        { username, password }
  user without 2FA   ─► 200 { token, expiresAt }                       (unchanged contract)
  user with 2FA      ─► 200 { twoFactorRequired: true, twoFactorToken }
  bad credentials    ─► 401 { error: "invalid_credentials" }
  inactive user      ─► 401 { error: "account_inactive" }
  locked out         ─► 429 { error: "too_many_attempts", retryAfterSeconds }

POST /api/auth/verify-otp   Authorization: Bearer <twoFactorToken>   { otp }
  valid code         ─► 200 { token, expiresAt }
  wrong code         ─► 401 { error: "invalid_otp" }
  pending token expired/invalid ─► 401 { error: "invalid_token" }
  locked out         ─► 429 { error: "too_many_attempts", retryAfterSeconds }
```

- `twoFactorToken` is a JWT with subject = username, claim `authState = TWO_FA_PENDING`, lifetime
  five minutes. It carries no role and is accepted only by `verify-otp`.
- The access token (`token`) is a JWT with subject = username and claims `userId`, `role`,
  `tokenVersion`, `twoFactorEnabled`. Lifetime stays `omnichannel.jwt.ttl` (12h default).
- On every bearer-authenticated request the user is loaded from Mongo and `tokenVersion` in the
  token must equal the stored value. Bumping `tokenVersion` therefore logs the user out everywhere.
  Inactive users are rejected at this point too.
- The WebSocket `auth` frame keeps sending the same access token; the handler verifies it with the
  same rules.

## Brute-force protection

Same thresholds as the reference: five failed passwords lock login for 30 minutes, three failed OTP
codes lock verification for 10 minutes, per lowercase username. Counters live in memory (a
Caffeine-free `ConcurrentHashMap` with expiry timestamps) since there is no Redis. A restart clears
them, which is acceptable for a single instance.

## Two-factor enrolment (for a logged-in user)

```
POST /api/users/me/two-factor/initiate   ─► 200 { secret, qrCodeDataUri, issuer, username }
POST /api/users/me/two-factor/confirm    { secret, otp }   ─► 204   (stores secret, enabled=true)
POST /api/users/me/two-factor/disable    { otp }           ─► 204   (clears secret, enabled=false)
```

- Initiate and confirm reject with 409 when already enabled; disable rejects with 409 when already
  off. Confirm and disable use the same OTP rate limit as login.
- Issuer shown in the authenticator app is `omnichannel.two-factor.issuer` (default
  `Omnichannel CRM`).
- TOTP library: `dev.samstevens.totp:totp:1.7.1`, as in the reference. SHA1, six digits, 30 s.

## Configuration changes

- `pom.xml`: add `spring-boot-starter-data-mongodb`, `dev.samstevens.totp:totp`, and
  `de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x` (test scope) so integration tests do
  not need a running Mongo.
- `application.yml`: `spring.data.mongodb.uri: ${OMNICHANNEL_MONGODB_URI:mongodb://localhost:27017/omnichannel_crm}`.
- Config prefix renamed from `wabridge` to `omnichannel`; `WabridgeProperties` becomes
  `OmnichannelProperties`; `WABRIDGE_JWT_SECRET` becomes `OMNICHANNEL_JWT_SECRET`. The `users`
  list is removed. `WabridgeApplication` becomes `OmnichannelApplication`.
- The page-script resource folder `wabridge/scripts` and the extension's own names are unchanged:
  they are WhatsApp-channel specific.

## Package layout

```
me.nageshk.omnichannel
  user/           User, TwoFactor, UserRole, UserRepository, UserService, UserController,
                  UserSeeder
  auth/           AuthController (verify stays filter-based; controller only for error shape),
                  login/ PasswordAuthentication(+Converter, +Provider),
                         OtpAuthentication(+Converter, +Provider), LoginSuccessHandler
                  jwt/   JwtService, JwtAuthenticationProvider
                  AuthenticatedUser (UserDetails), LoginAttemptService, TotpService
  config/         SecurityConfig, MongoConfig (auditing), OmnichannelProperties
```

## Testing

- Unit: `JwtService` claims and expiry, `LoginAttemptService` thresholds, `TotpService` verify
  against a known secret and clock, `UserSeeder` idempotence for both the super admin and the
  device user, and skip-when-blank for the device user.
- Integration (embedded Mongo): login without 2FA returns a token; login with 2FA returns a pending
  token and `verify-otp` with a code generated from the stored secret returns an access token;
  wrong OTP is 401; pending token rejected on any other endpoint; token-version bump invalidates
  an issued token; inactive user rejected; existing WebSocket and messages tests still pass with a
  seeded test user.

## Settled details (builder-check calls)

- Password encoder is a plain `BCryptPasswordEncoder`, matching the prefix-free hash in the
  document. Login lookup is exact-case; lockout keys are lowercased.
- Confirming or disabling two-factor does not bump `tokenVersion`; the `twoFactorEnabled` claim is
  stale until the next login, as in the reference.
- The pending token is reusable within its five minutes.
- Existing endpoints stay `anyRequest().authenticated()`; no role gating this round.
- `spring.data.mongodb.auto-index-creation: true` so the unique `username` index is created.
- Embedded Mongo for tests: pin the flapdoodle artifact version and set
  `de.flapdoodle.mongodb.embedded.version` to match the local 7.0 server.

## Left to the builder

- Exact class names inside `auth/login` and `auth/jwt` as long as they follow the names above.
- Whether `verify-otp` is a filter converter (as in the reference) or a plain controller method
  calling the `AuthenticationManager`; both satisfy the contract.
- Error body format for the 409s on enrolment endpoints, consistent with `ApiExceptionHandler`.
- Caffeine or hand-rolled map for the attempt counters.
