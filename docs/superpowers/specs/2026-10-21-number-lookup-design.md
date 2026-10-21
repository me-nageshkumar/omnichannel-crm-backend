# Number lookup before sending — design

Addendum to `2026-10-21-new-chat-design.md`. Checks whether a phone number is on WhatsApp before
the first message goes out, from the inbox today and from campaigns next.

## 1. Intent

Leads bought from marketplaces carry wrong, dead or reformatted numbers. The digit rule catches the
malformed ones; it cannot tell `9928088743` (no country code, reads as Tajikistan) from a real
number, so today such a send either fails late on the device or leaves a chat that never delivers.
WhatsApp Web's own search box answers this by an existence query, which whatsapp-web.js wraps as
`Client.getNumberId` (`WAWebQueryExistsJob.queryWidExists`). This design exposes that query through
the extension and the backend, caches the answer, and uses it in the New chat flow.

### Goals
- Refuse a new chat to a number that is not on WhatsApp, with a clear message, before anything is
  sent or stored.
- Tell the console user early (while typing the number) whether it is on WhatsApp.
- Resolve the number's real WhatsApp id (`…@lid` or `phone@c.us`) up front, so a new chat is created
  under it instead of being renamed after the first reply from the device.
- One reusable service (`NumberLookupService`) that campaigns call per lead, with a cache so a
  number is queried once, not per message.

### Non-goals
- Fixing numbers (adding a default country code, trying variants). A workspace default country
  code is a separate, later feature; this one only reports.
- Bulk lookup endpoints, lookup pacing and lead flagging: campaign concerns, built with campaigns on
  top of `NumberLookupService`.
- Touching contacts from a lookup. Contacts keep getting their `waId` the way they do now (ingest and
  the send path); the lookup cache is its own collection.
- Cloud API numbers (no device; they report `NO_DEVICE` as today).

### Anti-goals
- Hammering WhatsApp with existence queries (accounts get throttled or flagged). Every lookup goes
  through the cache first; nothing retries a failed query on its own.
- A lookup outage that silently lets unverified sends through, or one that blocks the inbox for
  numbers already known. Hence: a failed query is an error the caller sees, and a cached answer is
  trusted for its lifetime.

## 2. Extension

New page method `getNumberId` in `src/page/methods.js`, next to `sendMessage`:

- Params `{ phone }`: digits only, country code first (already normalised by the backend). The
  method appends `@c.us`, builds the wid with `WAWebWidFactory.createWid`, calls
  `WAWebQueryExistsJob.queryWidExists(wid)` exactly as `Client.getNumberId` does
  (`whatsapp-web.js/src/Client.js:2284`). That query only answers existence: its `wid` is the
  phone wid. The lid is a second, local call, `WAWebApiContact.getCurrentLid(wid)`, the way
  `WWebJS.enforceLidAndPnRetrieval` does it (`src/util/Injected/Utils.js:1751`).
- Result `{ phone: string, lid: string | null } | null`: `null` when the number is not registered;
  otherwise `phone` is `result.wid._serialized` (`<digits>@c.us`, digits as WhatsApp normalised
  them) and `lid` is `getCurrentLid(wid)?._serialized ?? null`.
- Errors: `WHATSAPP_NOT_READY` when the WA modules are not loaded (same check as `sendMessage`);
  `PAGE_ERROR` with the query's message otherwise. Missing or non-string `phone` is `PAGE_ERROR`.
- No throttling in the extension, and none in the bridge either: `bridge.js` dispatches each
  request frame without waiting for the previous one, so lookups that arrive together run together
  in the page. At console scale (one agent, one click) that is fine; campaigns pace their own
  lookups in the backend (§5).
- An extension older than this method answers `METHOD_NOT_FOUND`; the backend turns that into
  `LOOKUP_FAILED` with the message "The WhatsApp extension needs an update to check numbers" so the
  person knows what to do (§3.2).

Tests (`tests/router.test.js` or a new `tests/methods.test.js`, following the existing method tests
with a stubbed `window.require`): registered number returns `{phone, lid}`, registered without a
known lid returns `{phone, lid: null}`, unregistered returns `null`, not-ready throws
`WHATSAPP_NOT_READY`.

## 3. Backend

### 3.1 Cache

Collection `wa_number_lookups`, document `WaNumberLookup`:

| field | meaning |
|---|---|
| `workspaceId`, `phone` | unique index `ws_phone`; `phone` is the typed, normalised number (the cache key) |
| `registered` | whether WhatsApp knows the number |
| `waPhone` | `<digits>@c.us` as WhatsApp returned it (may differ from `phone` where WhatsApp normalises, e.g. Brazil's ninth digit); null when not registered |
| `lid` | `…@lid` when WhatsApp knows one, else null |
| `checkedAt` | when the device answered |
| `phoneNumberId` | which number asked (informational; the answer is account-independent) |

A cached answer is **fresh** for 30 days when registered and 7 days when not (people install
WhatsApp; businesses change numbers). `lookup(..., force = true)` ignores the cache.

### 3.2 Service

`NumberLookupService.lookup(workspaceId, phoneNumberId, phone, boolean force)` →
`CompletableFuture<NumberLookup>` where `NumberLookup(String phone, boolean registered, String
remoteId, Instant checkedAt, boolean cached)` and `remoteId` is `lid` when known, else `waPhone`,
else null:

1. `phone` is already normalised and valid (callers validate; the service asserts).
2. Fresh cache hit → returned with `cached = true`, no device call.
3. Otherwise `devices.requireReady(workspaceId, phoneNumberId)` (409 `NO_DEVICE` /
   `WHATSAPP_NOT_READY` as a send, thrown synchronously), then
   `dispatcher.call(device, "getNumberId", {phone})`.
4. The answer is upserted into the cache (retrying once on a duplicate key, like ingest) and
   returned with `cached = false`.
5. Any `RpcException` or timeout from the device is wrapped as `LookupFailedException`
   (`CODE = "LOOKUP_FAILED"`, "Could not check this number on WhatsApp, try again"; for
   `METHOD_NOT_FOUND`, "The WhatsApp extension needs an update to check numbers"), mapped to 502
   in `ApiExceptionHandler`. Without the wrap a page-side failure would surface as the generic 400
   that `PAGE_ERROR` maps to today and look like a client mistake. Nothing is cached and nothing is
   retried.

### 3.3 Endpoint

`GET /api/workspaces/{ws}/inbox/phone-numbers/{pn}/number-lookup?phone=…[&force=true]`,
`inbox:send`. Returns `NumberLookup` as JSON. 400 `VALIDATION` `fields.phone` with the digit-rule
message for a bad `phone`; 404 for a number outside the workspace; device and RPC errors as in 3.2.
No side effect beyond the cache, so GET.

### 3.4 New chat

`NewChatService.start` keeps its synchronous checks (phone rule, number in workspace, device
ready) and the existing-chat path, and looks the number up only when no chat is known yet:

1. `existingChat` as today (the contact's chat by typed phone, else the chat under `phone@c.us`).
   Found → no lookup at all: `CHAT_TAKEN`, reopen, claim and send exactly as now. A chat that
   exists is proof enough that the number is on WhatsApp, costs no device query, and is never
   refused by a stale "not registered" answer (the person may have installed WhatsApp and written
   first since the cache entry was made).
2. Not found → `lookups.lookup(ws, pn, phone, false)` is awaited on the request thread (`join`,
   unwrapping the `CompletionException` so the cause reaches `ApiExceptionHandler` as a plain
   exception). `start` runs on the HTTP thread, never on the device's socket thread that completes
   RPC futures, so the wait is safe and bounded by `omnichannel.rpc.timeout`. Composing futures
   instead would push every later check (`CHAT_TAKEN`, closed chat, body validation, device
   readiness) inside the future, where the handler unwraps only RPC, media and timeout causes and
   everything else becomes a 500. `wrapped` still learns `NotOnWhatsAppException` and
   `LookupFailedException`, so a caller that does compose later is not surprised.
3. Not registered → 409 `NOT_ON_WHATSAPP` "This number is not on WhatsApp"
   (`NotOnWhatsAppException`, handled like `CHAT_TAKEN`). Nothing sent, nothing stored.
4. Registered → `remoteId = lookup.remoteId()`. If a chat exists under it (a lid-only chat whose
   contact never got the phone), it takes the existing-chat path of step 1, now inside the future.
5. Else the fresh chat is built with that `remoteId`. The typed phone travels with the send:
   `InboxSendService.send(me, ws, chat, message, phone)` gains the `phone` parameter (null for
   saved chats; the four-argument overload stays for existing callers). In `record`, an unsaved
   chat with a non-null `phone` links contact and chat with `contacts.linkLidToPhone(ws, customer,
   chat.getRemoteId(), phone, now)` when the remote id is a lid, and `upsertFromChat` as today when
   it is a `@c.us` id. The existing "reported remote id differs from the addressed one" adoption
   stays as the fallback for the case the device reports yet another id.
6. A `LOOKUP_FAILED` fails the start with that error. No fallback to an unchecked send: the user
   retries, and a device that cannot answer a lookup cannot send either.

Precedence calls:
- The lookup's current id wins over a contact's stored `waId` when they disagree (a lid can
  change; the lookup is at most 30 days old, the contact field may be years old). `linkLidToPhone`
  already moves the contact to the new lid, folding a nameless hidden contact that holds it and
  leaving a named one alone.
- When WhatsApp returns `@c.us` digits that differ from the typed phone, the contact is created
  from the returned digits (`upsertFromChat` from the remote id, as today); the typed phone is only
  the cache key. A contact already known by the typed digits is left as is. Rare (ninth-digit
  countries) and deferred.

### 3.5 Tests
- `NumberLookupIT`: registered (with and without lid) and unregistered answers; cache hit skips the
  device; stale cache re-queries (30 / 7 days); `force` re-queries; device error → 502
  `LOOKUP_FAILED`, nothing cached; `inbox:send` required; bad phone 400; unknown number 404.
- `NewChatIT` additions: unregistered number → 409 `NOT_ON_WHATSAPP`, no chat, no contact, no send
  RPC; registered number creates the chat under the lid with the contact holding the typed phone and
  the lid (both when the event beats the reply and when it does not); registered without a lid
  creates it under `@c.us`; an existing chat (by contact or `@c.us`) sends without any lookup RPC
  even when the cache says not registered; a lid-only chat is found through the lookup and
  `CHAT_TAKEN` still applies to it; `LOOKUP_FAILED` → 502, no send. The existing fake device answers
  `getNumberId` in every current test so they keep passing.
- `InboxIndexesIT`: `ws_phone` on `wa_number_lookups`.

## 4. Console

New chat modal (`NewChatModal.tsx`):

- `lookupNumber(token, ws, pn, phone)` in `src/app/api/inbox.ts` → GET above.
- When the phone field loses focus with a valid cleaned number (or Send / Template… is pressed with
  a number not yet checked), the modal calls the lookup for that cleaned value and keeps the
  result keyed by it, so a changed number clears the state and a late answer for an old value is
  ignored.
- Helper text under the field: "Checking WhatsApp…" while pending; "On WhatsApp" (success
  variant) when registered; "This number is not on WhatsApp" (error variant, Send and Template…
  disabled) followed by a **Check again** link button that repeats the lookup with `force=true`
  (so a stale cached "no" never traps the user) when not; "Could not check this number" (warning
  variant, Send stays enabled; the server checks again) when the lookup call failed with anything
  other than a 2xx or `NOT_ON_WHATSAPP`.
- Send and Template… wait for a pending check of the current value before acting; a not-registered
  answer cancels the action.
- Server `NOT_ON_WHATSAPP` from the start call lands under the phone field with the same text;
  `messageFor` gets `not_on_whatsapp: 'This number is not on WhatsApp.'` for any other surface.
- `messageFor` codes are lower-cased like the rest: `not_on_whatsapp`, `lookup_failed`.
- Tests: on-blur lookup renders each of the four states; not-registered disables Send; Check again
  calls the lookup with `force=true` and re-enables Send on a registered answer; stale answer
  ignored; Send with an unchecked number looks up first and proceeds when registered; the start
  call's `NOT_ON_WHATSAPP` lands under the field.

## 5. Campaigns (how this is meant to be reused)

A campaign run validates each lead in two steps: the free digit rule (`Phones.isValid`, leads
failing it are marked invalid without a device call), then `NumberLookupService.lookup` with
`force = false`, so a lead checked in the last 30 days costs nothing. Pacing (how many lookups per
minute, pauses on errors) and what happens to a lead that is not on WhatsApp (flag it, route it to
an agent) are decided in the campaign design; the service gives them `cached` so they can tell a
device call from a cache hit when pacing.

## 6. Calls made without asking

- Cache lifetimes 30 days (registered) / 7 days (not registered); `force` to bypass.
- The lookup never writes contacts; linking stays on the send path.
- An existing chat is never looked up: a known chat sends as today, whatever the cache says.
- A lookup failure is its own 502 `LOOKUP_FAILED` and fails the start rather than falling back to
  an unchecked send.
- The lookup's id wins over a contact's stored `waId`; returned `@c.us` digits win over typed ones.
- Lookup on blur in the modal, not on every keystroke, to spare the account; Check again forces.
- A number that is not on WhatsApp is a 409 `NOT_ON_WHATSAPP`, shown under the phone field.
- Own business number: the lookup reports it registered; sending to oneself stays allowed as today.
