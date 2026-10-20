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
and never overwritten afterwards (roles are seeded first, see [Roles and access](#roles-and-access)):

| User | Variables | Notes |
|---|---|---|
| Super admin (role `SUPER_ADMIN`) | `OMNICHANNEL_SUPER_ADMIN_PASSWORD` (bcrypt hash, required until one exists), optional `_USERNAME` (default `nageshk`), `_NAME`, `_EMAIL`, `_MOBILE` | Seeded with two-factor off; enable it later from the console Profile page or `/api/users/me/two-factor/*` |
| Device user (role `WHATSAPP_DEVICE`) | `OMNICHANNEL_DEVICE_USER_USERNAME`, `OMNICHANNEL_DEVICE_USER_PASSWORD` (bcrypt hash) | For the Chrome extension; two-factor off; skipped when the username is blank |

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

A user created or reset by someone else has `mustChangePassword`; their login (and `verify-otp`) answers
`{passwordChangeRequired: true, passwordChangeToken}` instead of a token, and the new password goes to
`POST /api/auth/initial-password` (`{newPassword}`, at least 10 characters, bearer = the change token,
valid ten minutes) which returns `{token, expiresAt}`. Bad password: 400 `WEAK_PASSWORD`; bad or expired
change token: 401 `INVALID_TOKEN`.

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
curl -s localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN"   # {id, name, username, email, mobileNumber, role:{id,name}, superAdmin, customer, authorities, workspaces, mustChangePassword, twoFactorEnabled}
```

A wrong code on `confirm`/`disable` is 400 `INVALID_OTP` (not 401), so the session survives a typo.

Log out ends only the calling session: the token's id goes into `revoked_tokens` (TTL-indexed, cleared
when the token would have expired) and is refused from then on, over REST and the WebSocket. Tokens
issued before logout existed carry no id and simply run to expiry.

```bash
curl -s -X POST localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"   # 204; replaying $TOKEN is now 401
```

## Roles and access

Every logged-in user can use the console; what they see follows their authorities. The model:

- **Tenancy:** customer (company) -> workspaces (brands) -> teams. Platform users (Super Admin,
  Account Managers, the device user) belong to no customer; everyone else belongs to exactly one.
- **Authorities** are code-defined permissions named `thing:action` (for example `user:manage`), each
  with a level: PLATFORM, COMPANY or BRAND. `GET /api/authorities` lists them.
- **Roles** are named bundles of authorities stored in `roles`. A role may hold authorities of its own
  level or below (PLATFORM: platform only; COMPANY: company and brand; BRAND: brand only). Built-in:
  `SUPER_ADMIN` (every platform authority, cannot be edited or given away), `ACCOUNT_MANAGER`,
  `WHATSAPP_DEVICE`, `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`, `TEAM_LEAD`, `AGENT`. Only the Super Admin
  edits roles or creates custom ones; every change is recorded in `role_changes`. Edits apply on the
  user's next request (authorities are not in the JWT).
- **Per-workspace access:** a user has an optional company role (applies in every workspace of the
  customer, held by the Customer Admin) plus at most one brand role per workspace.
- **Strict-subset rule:** to give a role, the caller must hold the managing authority and the role's
  authorities must be a strict subset of the caller's own in that scope; to change an existing login
  they must pass that check in every workspace where the target has a role. So a Workspace Manager
  assigns Team Leads and Agents but not another Workspace Manager, and only the Customer Admin creates
  Workspace Managers. `GET .../assignable-roles` returns what the caller may give.
- **Isolation:** a customer, workspace, team or user the caller may not see answers 404 `NOT_FOUND`;
  one they see but may not change answers 403 `FORBIDDEN`. Super Admin and Account Managers have no
  access to brand pages. `IsolationIT` pins this for every brand endpoint.
- **`whatsapp:bridge`:** the WhatsApp REST endpoints (`/api/status`, `/api/messages`, `/api/events`,
  `/api/scripts`, `/api/rpc`) and the WebSocket `auth` frame require this authority, held by
  `WHATSAPP_DEVICE` and the Super Admin. A Customer Admin token is refused.
- **Startup:** the built-in roles are seeded (a catalogue authority the system has never seen is added
  to the built-in roles that default to it; authorities already seen are never re-added, so removals
  stick). Then users stored with the old `role` field are migrated, idempotently: `SUPER_ADMIN` and
  `USER` map to the built-in `SUPER_ADMIN` and `WHATSAPP_DEVICE` roles, any other value stops startup
  with a clear message, and the old field is removed.
- Usernames are stored lower-case and unique across the platform. Temporary and new passwords need at
  least 10 characters; a temporary password is shown only in the create or reset response.

### Authorities

Authorities live in Mongo and the Super Admin manages them on the Authorities page
(`/api/authorities`; `GET`, `POST`, `PUT /{code}`, `DELETE /{code}`, `GET /{code}/history`,
`GET /needs-creating`).

- **Workflow:** guard the method with `@PreAuthorize("hasAuthority('thing:action')")`, deploy, then create
  the code on the Authorities page (group, level, description) and add it to roles. Until it is created
  nobody holds it, so the method stays closed.
- **Statuses:** `ACTIVE` (created and checked in code at the right level), `NOT_CHECKED` (created, but no
  check for it was found in code, so a typo is possible), `WRONG_LEVEL` (the code's checks sit at a
  different level than the authority), and `NEEDS_CREATING` (checked in code but not yet created).
- **Built-ins:** the authorities the system ships with can change group and description but not level,
  and cannot be deleted. Deleting or re-levelling any authority a role still holds answers 409
  `AUTHORITY_IN_USE` with the role names.
- **Limit:** the scanner only finds literal codes inside `@PreAuthorize`. Codes built at runtime, and
  checks made outside `@PreAuthorize`, are not found and show as `NOT_CHECKED`.

| Endpoint | Needs |
|---|---|
| `GET/POST /api/platform-users`, `PUT .../{id}`, `POST .../{id}/deactivate\|reactivate\|reset-password`, `GET .../assignable-roles` | `platform-user:manage` |
| `GET/POST /api/roles`, `PUT/DELETE /api/roles/{id}`, `POST .../{id}/preview`, `GET .../{id}/changes`, `GET /api/authorities` | Super Admin |
| `GET /api/customers`, `GET /api/customers/{id}` | `customer:view` |
| `POST /api/customers`, `GET /api/customers/assignable-roles` | `customer:create` |
| `PUT /api/customers/{id}`, `POST .../deactivate\|reactivate`, `POST .../admins`, `POST .../admins/{userId}/reset-password\|deactivate\|reactivate` | `customer:manage` |
| `GET /api/workspaces` (mine), `POST /api/workspaces` | logged in / `workspace:create` |
| `PUT /api/workspaces/{workspaceId}` | `workspace:settings` |
| `GET /api/workspaces/{workspaceId}/users` | `user:view` |
| `POST /api/workspaces/{workspaceId}/users`, `PUT\|DELETE .../users/{userId}`, `POST .../users/{userId}/reset-password`, `GET .../assignable-roles` | `user:manage` + strict-subset rule |
| `GET /api/workspaces/{workspaceId}/teams` | `user:view` |
| `POST /api/workspaces/{workspaceId}/teams`, `PUT .../teams/{teamId}` | `team:manage` |
| `POST /api/users/{userId}/deactivate\|reactivate` | `company-user:manage` + strict-subset rule |
| `POST /api/auth/initial-password` | password-change token |
| `GET /api/users/me` | logged in |

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
