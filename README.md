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
`OMNICHANNEL_MONGODB_URI`). Users live in the `users` collection; the Super Admin is seeded on first start
and never overwritten afterwards (roles are seeded first, see [Roles and access](#roles-and-access)):

| User | Variables | Notes |
|---|---|---|
| Super admin (role `SUPER_ADMIN`) | `OMNICHANNEL_SUPER_ADMIN_PASSWORD` (bcrypt hash, required until one exists), optional `_USERNAME` (default `nageshk`), `_NAME`, `_EMAIL`, `_MOBILE` | Seeded with two-factor off; enable it later from the console Profile page or `/api/users/me/two-factor/*` |
| Demo data | `OMNICHANNEL_SEED_DEMO=true` | Off by default. Adds customer "Demo Retail" (owner `demo-admin`), brands "Demo Fashion" and "Demo Electronics", `demo-manager` / `demo-lead` / `demo-agent` in Demo Fashion, the number 919928088743 and (see [Contacts](#contacts)) sample contacts there. Temporary passwords are logged once at WARN and must be changed at first login. Skipped when the customer exists |

```bash
# bcrypt hash for a password (no {bcrypt} prefix)
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'

export OMNICHANNEL_SUPER_ADMIN_PASSWORD='$2a$10$...'
export OMNICHANNEL_SEED_DEMO=true   # optional
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
  Account Managers) belong to no customer; everyone else belongs to exactly one.
- **Authorities** are code-defined permissions named `thing:action` (for example `user:manage`), each
  with a level: PLATFORM, COMPANY or BRAND. `GET /api/authorities` lists them.
- **Roles** are named bundles of authorities stored in `roles`. A role may hold authorities of its own
  level or below (PLATFORM: platform only; COMPANY: company and brand; BRAND: brand only). Built-in:
  `SUPER_ADMIN` (every platform authority, cannot be edited or given away), `ACCOUNT_MANAGER`,
  `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`, `TEAM_LEAD`, `AGENT`. Only the Super Admin
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
- **Extension:** connects with a device key, never a user token (see [Phone numbers](#phone-numbers)).
  The test endpoints under `/api/phone-numbers/{id}/bridge/` are Super Admin only.
- **Startup:** the built-in roles are seeded (a catalogue authority the system has never seen is added
  to the built-in roles that default to it; authorities already seen are never re-added, so removals
  stick). Then the old shared extension login is removed (`DeviceAccountCleanup`: users of the
  `WHATSAPP_DEVICE` role or legacy `role: USER`, the role, and the `whatsapp:bridge` authority). Then
  users stored with the old `role` field are migrated, idempotently: `SUPER_ADMIN` maps to the built-in
  role, any other value stops startup with a clear message, and the old field is removed.

### Enable / disable

Numbers, brands, customers, logins and a person's access to one brand are enabled or disabled
(`POST .../disable` and `.../enable`). Each disable records `{by, level, at}` as `disabled` on the
thing (`null` when enabled); every change is kept in `enablement_changes`. The level is that of the
role the actor holds the authority through: platform staff act at PLATFORM, a Customer Admin at
COMPANY (even with brand authorities), a Workspace Manager at BRAND. **Anyone with the authority may
disable; enabling needs a level at least that of the stored disable**, else 403
`DISABLED_AT_HIGHER_LEVEL` with `level`. A lower disable never overwrites a higher one.

- A disabled customer: its users' tokens are refused until it is enabled; its numbers disconnect.
- A disabled brand: BRAND-role holders lose it (404); COMPANY-role holders still see it, with no
  authorities there except enabling it; its numbers disconnect.
- A disabled login is signed out everywhere (token version bump).
- A disabled brand access (`user:enable`, by a Workspace Manager or Customer Admin) removes that one
  brand for the person; it survives removing and re-adding them to the brand.
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
  check for it was found in code, so a typo is possible), `WRONG_LEVEL` (a BRAND authority checked only
  with `hasAuthority`, or a PLATFORM authority checked only with `@access.has`; a COMPANY authority fits
  both kinds of check and is never WRONG_LEVEL), and `NEEDS_CREATING` (checked in code but not yet created).
- **Built-ins:** the authorities the system ships with can change group and description but not level,
  and cannot be deleted (deleting or re-levelling one answers 403 `FORBIDDEN`). Deleting or re-levelling
  a console-created authority that a role still holds answers 409 `AUTHORITY_IN_USE` with the role names.
- **Limit:** the scanner only finds literal codes inside `@PreAuthorize`. Codes built at runtime, and
  checks made outside `@PreAuthorize`, are not found, so a console-created code checked only that way
  shows as `NOT_CHECKED` (built-ins always show `ACTIVE`). `@PreAuthorize` on inherited superclass methods
  or interface methods is not read, only the bean class's own declared methods and the class itself.

| Endpoint | Needs |
|---|---|
| `GET/POST /api/platform-users`, `PUT .../{id}`, `POST .../{id}/disable\|enable\|reset-password`, `GET .../assignable-roles` | `platform-user:manage` |
| `GET/POST /api/roles`, `PUT/DELETE /api/roles/{id}`, `POST .../{id}/preview`, `GET .../{id}/changes`, `GET /api/authorities` | Super Admin |
| `GET /api/customers`, `GET /api/customers/{id}`, `GET .../{id}/users` | `customer:view` |
| `POST /api/customers`, `GET /api/customers/assignable-roles` | `customer:create` |
| `PUT /api/customers/{id}`, `POST .../disable\|enable`, `POST .../admins`, `POST .../admins/{userId}/reset-password`, `POST .../users/{userId}/disable\|enable`, `POST .../workspaces/{workspaceId}/disable\|enable` | `customer:manage` (PLATFORM level) |
| `POST /api/workspaces/{workspaceId}/disable\|enable` | `workspace:enable` (own customer) |
| `POST /api/workspaces/{workspaceId}/users/{userId}/disable\|enable` | `user:enable` + may change the user there |
| `GET /api/workspaces/{workspaceId}/phone-numbers` | `phone-number:view` |
| `POST /api/workspaces/{workspaceId}/phone-numbers`, `PUT\|DELETE .../{id}`, `POST .../{id}/disable\|enable\|connect-code\|revoke-device` | `phone-number:manage` |
| `GET /api/phone-numbers?q=&status=&page=` | `phone-number:view-all` |
| `POST /api/phone-numbers/{id}/disable\|enable`, `DELETE /api/phone-numbers/{id}` | `phone-number:manage-all` |
| `POST /api/devices/connect` | none (rate-limited per IP) |
| `GET /api/scripts`, `/api/scripts/{name}` | device key or Super Admin |
| `/api/phone-numbers/{id}/bridge/status\|events\|messages\|rpc` | Super Admin |
| `GET /api/workspaces` (mine), `POST /api/workspaces` | logged in / `workspace:create` |
| `PUT /api/workspaces/{workspaceId}` | `workspace:settings` |
| `GET /api/workspaces/{workspaceId}/users` | `user:view` |
| `POST /api/workspaces/{workspaceId}/users`, `PUT\|DELETE .../users/{userId}`, `POST .../users/{userId}/reset-password`, `GET .../assignable-roles` | `user:manage` + strict-subset rule |
| `GET /api/workspaces/{workspaceId}/teams` | `user:view` |
| `POST /api/workspaces/{workspaceId}/teams`, `PUT .../teams/{teamId}` | `team:manage` |
| `POST /api/users/{userId}/disable\|enable` | `company-user:manage` + strict-subset rule (COMPANY level) |
| `POST /api/auth/initial-password` | password-change token |
| `GET /api/users/me` | logged in |

## API

## Phone numbers

A brand's WhatsApp numbers live in `phone_numbers` (digits only, country code first, 8–15 digits,
unique across the platform; a brand may have several). The extension connects for exactly one number:

1. A brand manager gets a connect code (`POST .../phone-numbers/{id}/connect-code` → `{code:
   "ABCD-EFGH", expiresAt}`): 8 characters, valid 15 minutes, single use; a new code cancels the
   unused one. Only its SHA-256 is stored.
2. The extension sends it to `POST /api/devices/connect {code}` and gets `{deviceKey, deviceKeyId,
   phoneNumberId, number, brandName}`. Wrong or expired: 400 `INVALID_CONNECT_CODE`; ten failures from
   one IP in 15 minutes: 429 `TOO_MANY_ATTEMPTS`; disabled: 403 `DISABLED`. The key (`dk_…`, hashed at
   rest) never expires on its own; a new code redeemed replaces it, "revoke device" deletes it.
   The per-IP limit uses the socket's remote address. Behind a reverse proxy every client shares the
   proxy's address, so configure trusted forwarded headers (`server.forward-headers-strategy` with the
   proxy's address only) before deploying, or one client's bad codes lock out every browser.
3. The WebSocket `auth` frame carries the key. The server forwards nothing until WhatsApp Web reports
   the onboarded number (`info.pnWid`, else a `…@c.us` `wid`; a `…@lid` id never confirms). Events
   before that are not acknowledged, so the extension replays them; an event whose `acct` is another
   number is acknowledged and dropped.

Close codes: 4401 unknown key or no auth in 5 s, 4403 number/brand/company disabled (also at auth, so
the extension keeps its key and retries), 4409 replaced by a
newer connection or key, 4410 device removed (revoked or number deleted), 4412 logged in to a different
number (recorded and shown in the console). Disabling, revoking or deleting closes the live socket in
the same request. Status shown per number: `DISABLED` (with `disabledBecause` NUMBER/BRAND/CUSTOMER),
`NOT_CONNECTED_YET`, `WAITING_FOR_LOGIN`, `CONNECTED`, `WRONG_NUMBER`, `DISCONNECTED`.

## Contacts

A brand's people, in `contacts` (one per brand and WhatsApp person; everything is scoped by
`workspaceId`, another brand's ids answer 404). Package `me.nageshk.omnichannel.contact`.

- **Model:** `waId` (`…@c.us` or `…@lid`), `phone` (digits, country code first, or none for a hidden
  number), `name` (the WhatsApp display name until a person edits it: `nameEdited`), `labels` (ids),
  `attributes` (attribute id to text, at most 500 characters), `source` (`CHAT`, `IMPORT`, `MANUAL`),
  `lastMessageAt`. Labels (name, colour, description) and attributes (name, description) are per-brand
  lists in `contact_labels` / `contact_attributes`; names are unique per brand ignoring case; deleting
  one removes it from every contact. Notes in `contact_notes`, saved filters in `contact_filters`.
- **Identity:** within a brand no two contacts share a `phone` or a `waId` (partial unique indexes, so
  any number of contacts may have neither). A `@c.us` chat finds the contact by phone (linking an
  imported contact to its chat) else by `waId`, else creates it. A `@lid` chat finds by `waId`; if the
  message carries the person's phone (`senderPn`, `_data.senderPn` or `author` ending `@c.us`) it is
  filled in unless another contact of the brand has it. Contacts are never merged.
- **From chats:** `ChatContactListener` handles `message` and `message_create`. Groups, broadcasts,
  status and newsletters are ignored. Only incoming messages set the name (`notifyName`,
  `_data.notifyName`, `pushname`); `lastMessageAt` only moves forward; replays change nothing; a failure
  is logged at WARN and never reaches the event ack.
- **Filters:** `POST .../contacts/search {filter?, filterId?, sort?, page?}` where a filter is
  `q` (name contains or phone digits contain), `labelsAny/All/None`, `attributes: [{attributeId, op:
  EQUALS|CONTAINS|EXISTS|NOT_EXISTS, value}]`, `source`, `numberHidden`, `createdFrom/To`,
  `lastMessageFrom/To`, all ANDed. Label and attribute ids of another brand are dropped (they match
  nothing). 50 per page (0-based), sort `LAST_MESSAGE` (default), `NAME`, `CREATED`; reply
  `{items, total, page}`.
- **Import:** `POST .../contacts/import` multipart `file` (UTF-8 CSV, at most 5 MB and 20 000 rows,
  header row required). Columns by header name, case-insensitive: `phone` (required; `+`, spaces,
  dashes and brackets are stripped; 8-15 digits, include the country code or it will not link to
  chats), `name`, `labels` (names separated by `;` or `,`) and any existing attribute name. Unknown
  columns and label names are reported, never created. An existing phone gets labels added, given
  attributes overwritten and a non-empty name replaces its name; nothing is removed. Reply:
  `{created, updated, unchanged, skipped, problems: [{row, reason}] (first 200), unknownColumns,
  unknownLabels}`; row 1 is the header.
- **Export:** `POST .../contacts/export {filter?, filterId?}` streams `text/csv` (UTF-8 with BOM, CRLF)
  named `contacts-<brand>-<yyyyMMdd>.csv`: `phone, name, number_hidden, source, labels` (names joined by
  `;`), one column per attribute, `created, last_message`. More than 100 000 rows (property
  `omnichannel.contacts.export-limit`) answers 400 `TOO_MANY_ROWS`. Cells that start with `= + - @` get a
  leading apostrophe so spreadsheets do not run them.
- **Bulk:** `POST .../contacts/count {selection}` and `.../contacts/bulk {selection, action,
  labelIds?, attributeId?, value?}` with `selection` exactly one of `ids` (at most 1000), `filter`,
  `filterId`; actions `ADD_LABELS`, `REMOVE_LABELS`, `SET_ATTRIBUTE`, `CLEAR_ATTRIBUTE`, `DELETE` (also
  needs `contact:delete`; removes the notes). Reply `{matched, changed}`; a filter matches at run time.
- **Notes:** anyone with `contact:annotate` adds; only the author edits; the author or anyone with
  `contact:delete` deletes. Deleting a contact deletes its notes.
- **Demo data:** with `OMNICHANNEL_SEED_DEMO=true`, `ContactDemoSeeder` adds 4 labels, 3 attributes,
  40 contacts (phones `91900000xxxx`, four hidden) and 6 notes to "Demo Fashion", unless that brand has
  a label already.

| Authority (BRAND) | Allows | Agent | Team Lead | Workspace Manager | Customer Admin |
|---|---|---|---|---|---|
| `contact:view` | list, search, count, view, notes, saved filters | yes | yes | yes | yes |
| `contact:annotate` | labels on a contact, add and edit own notes | yes | yes | yes | yes |
| `contact:edit` | add by hand, edit name and attributes | | yes | yes | yes |
| `contact:import` | CSV import | | yes | yes | yes |
| `contact:export` | CSV export | | yes | yes | yes |
| `contact:bulk` | bulk label and attribute actions, edit saved filters | | yes | yes | yes |
| `contact:delete` | delete contacts (one or bulk), others' notes | | | yes | yes |
| `contact-field:manage` | create, rename, delete labels and attributes | | | yes | yes |

Endpoints are under `/api/workspaces/{workspaceId}`: `contacts` (`POST` create, `GET|PUT|DELETE
/{id}`, `POST|DELETE /{id}/labels/{labelId}`, `GET|POST /{id}/notes`, `PUT|DELETE /{id}/notes/{noteId}`),
`contact-labels`, `contact-attributes` and `contact-filters` (`GET`, `POST`, `PUT|DELETE /{id}`).

## API

```bash
# login as the Super Admin; the bridge test endpoints take the phone number id
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"nageshk","password":"..."}' | jq -r .token)
N=localhost:8080/api/phone-numbers/<phoneNumberId>/bridge

# status of that number's extension
curl -s -H "Authorization: Bearer $TOKEN" $N/status

# send a text message
curl -s -X POST $N/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"919999999999@c.us","content":"hello from server"}'

# recent events (oldest first); poll with after=<last seq>
curl -s -H "Authorization: Bearer $TOKEN" "$N/events?limit=20"

# scripts delivered to the extension (manifest + code; ETag = sha256)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts/page -o page.js

# call any page method directly (diagnostic / generic pass-through)
curl -s -X POST $N/rpc -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
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

First frame must be `{"type":"auth","token":"<device key>"}` within 5 s, else close 4401. A newer
connection for the same number replaces the older one (closed with 4409); other close codes are listed
under [Phone numbers](#phone-numbers). Frames:
`request{id,method,params}`, `response{id,result|error{code,message}}`, `event{name,payload}`,
`ping`/`pong`. Incoming events are logged by `LoggingEventListener`.
