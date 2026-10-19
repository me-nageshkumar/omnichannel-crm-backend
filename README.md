# omnichannel-crm-backend

Spring Boot backend for the omnichannel CRM. Today it serves the
[whatsapp-web-extension](https://github.com/me-nageshkumar/whatsapp-web-extension) bridge:
authenticates users and extensions, relays WhatsApp events, sends messages, and delivers the page
script. Design specs and implementation plans live in `docs/superpowers/`.

## Repository layout

Sibling checkouts in one directory:

```
<workspace>/
  whatsapp-web.js/           # upstream https://github.com/wwebjs/whatsapp-web.js
  whatsapp-web-extension/    # the Chrome extension; its `npm run build` writes
                             # src/main/resources/wabridge/scripts/ in this repo
  omnichannel-crm-frontend/  # the browser console (React + PatternFly)
  omnichannel-crm-backend/   # this repo (Java 21, Spring Boot 3.5, package me.nageshk.omnichannel)
```

This repo continues the history of `whatsapp-backend`, which it replaces.

## Run

Needs MongoDB (default `mongodb://localhost:27017/omnichannel_crm`, override with
`OMNICHANNEL_MONGODB_URI`). Users live in the `users` collection; two are seeded on first start
and never overwritten afterwards:

| User | Variables | Notes |
|---|---|---|
| Super admin (`SUPER_ADMIN`) | `OMNICHANNEL_SUPER_ADMIN_PASSWORD` (bcrypt hash, required until one exists), optional `_USERNAME` (default `nageshk`), `_NAME`, `_EMAIL`, `_MOBILE` | Seeded with two-factor off; enable it later from the console Profile page or `/api/users/me/two-factor/*` |
| Device user (`USER`) | `OMNICHANNEL_DEVICE_USER_USERNAME`, `OMNICHANNEL_DEVICE_USER_PASSWORD` (bcrypt hash) | For the Chrome extension; two-factor off; skipped when the username is blank |

```bash
# bcrypt hash for a password (no {bcrypt} prefix)
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'

export OMNICHANNEL_SUPER_ADMIN_PASSWORD='$2a$10$...'
export OMNICHANNEL_DEVICE_USER_USERNAME=wabridge
export OMNICHANNEL_DEVICE_USER_PASSWORD='$2a$10$...'
./mvnw spring-boot:run
```

Set `OMNICHANNEL_JWT_SECRET` to a random string of at least 32 bytes outside development.
Tests run against an embedded MongoDB (downloaded on first run) and seed their own users.

## Console

The browser console lives in the sibling repo `omnichannel-crm-frontend` (React + PatternFly).

- Development: run this backend, then `npm run start:dev` in the frontend (port 9000; it proxies
  `/api` and `/ws` here).
- Production, one port: `npm run build` in the frontend, then here
  `./mvnw -Pwith-frontend package`. The profile copies `../omnichannel-crm-frontend/dist` into the
  jar's `static/` (override with `-Dfrontend.dist=/path/to/dist`) and fails if `index.html` is
  missing. Deep links such as `/profile` are answered with `index.html`. Plain builds and tests
  never need the frontend.

## Login

Password first; users with two-factor on then present a time-based code from their authenticator app.

```bash
# step 1: password. Without two-factor the reply is {token, expiresAt}; with it, {twoFactorRequired, twoFactorToken}
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"nageshk","password":"..."}'

# step 2: the six-digit code, with the pending token as bearer (valid five minutes)
curl -s -X POST localhost:8080/api/auth/verify-otp -H "Authorization: Bearer $PENDING" \
  -H 'Content-Type: application/json' -d '{"otp":"123456"}'
```

Errors: `invalid_credentials`, `account_inactive`, `invalid_otp`, `invalid_token` (401),
`too_many_attempts` with `retryAfterSeconds` (429; five bad passwords lock for 30 min, three bad
codes for 10 min). Access tokens embed the user's `tokenVersion`; bump it in Mongo to log a user out
everywhere. Enrol or remove an authenticator as the logged-in user:

```bash
curl -s -X POST localhost:8080/api/users/me/two-factor/initiate -H "Authorization: Bearer $TOKEN"   # {secret, qrCodeDataUri, issuer, username}
curl -s -X POST localhost:8080/api/users/me/two-factor/confirm  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"secret":"...","otp":"123456"}'                      # 204
curl -s -X POST localhost:8080/api/users/me/two-factor/disable  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"otp":"123456"}'                                     # 204
curl -s localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN"   # {id, name, username, email, mobileNumber, role, twoFactorEnabled}
```

A wrong code on `confirm`/`disable` is 400 `INVALID_OTP` (not 401), so the session survives a typo.

Log out ends only the calling session: the token's id goes into `revoked_tokens` (TTL-indexed, cleared
when the token would have expired) and is refused from then on, over REST and the WebSocket. Tokens
issued before logout existed carry no id and simply run to expiry.

```bash
curl -s -X POST localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"   # 204; replaying $TOKEN is now 401
```

## API

```bash
# login as the device user (two-factor off, so one step)
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"wabridge","password":"..."}' | jq -r .token)

# status of this user's extension
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status

# send a text message
curl -s -X POST localhost:8080/api/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"919999999999@c.us","content":"hello from server"}'

# recent events (oldest first); poll with after=<last seq>
curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/events?limit=20'

# scripts delivered to the extension (manifest + code; ETag = sha256)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts/page -o page.js

# call any page method directly (diagnostic / generic pass-through)
curl -s -X POST localhost:8080/api/rpc -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"getState"}'
```

Error codes: `NO_DEVICE` / `WHATSAPP_NOT_READY` / `TWO_FACTOR_STATE` (409), `CHAT_NOT_FOUND` /
`PAGE_ERROR` / `VALIDATION` (400), `INVALID_OTP` (401), `TOO_MANY_ATTEMPTS` (429),
`DEVICE_DISCONNECTED` / `SEND_FAILED` (502), `TIMEOUT` (504).

## Page script delivery

The extension fetches `page.js` from this server on every connect when the hash in the `hello`
reply differs from its cache. Regenerate the resources with `cd ../whatsapp-web-extension && npm run build` and
commit `src/main/resources/wabridge/scripts/`; the server refuses to start if the manifest
hash does not match the file.

## Event delivery guarantees

Durable events (`message`, `message_create`, `message_ack`) arrive with `seq` (extension outbox
sequence) and `ts` (capture time). The server publishes them, then answers `{"type":"event_ack","seq":N}`
(cumulative). Replays after a reconnect are de-duplicated by `seq` per user in memory; after a server
restart that memory is empty, so a replayed burst can be published once more. `GET /api/events`
exposes `deviceSeq` and `capturedAt` for consumers that need to de-duplicate or order by capture time.

## WebSocket protocol (`/ws`)

First frame must be `{"type":"auth","token":"<jwt>"}` within 5 s, else close 4401. A newer
connection for the same user replaces the older one (closed with 4409). Frames:
`request{id,method,params}`, `response{id,result|error{code,message}}`, `event{name,payload}`,
`ping`/`pong`. Incoming events are logged by `LoggingEventListener`.
