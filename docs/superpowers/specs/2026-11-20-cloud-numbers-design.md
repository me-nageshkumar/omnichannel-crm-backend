# Cloud API numbers: a brand's own Meta app, Meta templates, one send path — design

Design, 2026-11-20. Calls marked **(my call)** were made without asking (project rule: "skip the questions" for specs);
everything else the user decided in the brainstorm.

## 1. Intent

Bimal Institute uses Wati today. It will register a **new number** on **its own Meta app** (Meta's WhatsApp Cloud API,
no Wati, and we do not become a Tech Provider) and run it in our app the way the Main line runs over WhatsApp Web:
inbox, chatbot with the AI node, routing to counsellors, lead owners, CRM capture, rules. Counsellors follow up with
leads **days later**, which Meta allows only with **approved templates** outside the 24-hour customer service window,
so templates are in. The same setup serves every later customer that brings its own Meta app.

## 2. What the user decided

| # | Decision |
|---|---|
| D1 | A brand's own Meta app and system-user token, pasted by the brand's admin into our app. No Tech Provider, no Embedded Signup. |
| D2 | Templates are written and approved in Meta's WhatsApp Manager; our app **imports** them (route A). Our own template editor may come later on top of the same records. |
| D3 | Follow-ups **one at a time**: a counsellor from the inbox, and the rule action that sends a message. **Campaigns on Cloud numbers later.** |
| D4 | Deployment (a stable public HTTPS address) is a **separate project**; this one is walked through the ngrok address. |
| D5 | Approach A: **one switch inside the existing send path**; the same chats and messages; Meta's webhooks enter the existing event store and processor; Meta code in its own module; Meta templates in their own collection. |
| D6 | The playback was accepted as a whole: number form with a live check, a webhook address per connection with signature check, the same features as the Main line, the 24-hour window shown not discovered, template sync, media both ways, ticks from Meta statuses, Meta bills the brand directly. |

## 3. Goals, non-goals, anti-goals

Goals: add a Cloud number to a brand; receive every message, status and template update Meta sends; send every message
our app sends today (typed text, saved messages, Cloud messages with native buttons and lists, media, location) over
Meta's API; import and send Meta templates from the inbox and from rules; show and enforce the 24-hour window.

Non-goals (v1): campaigns on Cloud numbers (still excluded, as today); writing or submitting templates from our app;
Meta's Embedded Signup and coexistence; chatbots sending templates; template kinds beyond those in §5.6 (carousel,
flows, catalog, authentication/OTP, limited-time offer); Cloud calling; moving a Cloud number to another brand;
a stable deployment (D4); Meta billing data in our app.

Anti-goals (a failure even if it works):
- a token or app secret readable in any API response, page, log line, webhook event or flow JSON;
- a message Meta delivered to us and we lost (we store it before answering Meta), or one stored twice on Meta's retries;
- a webhook accepted without a valid signature, or routed to the wrong brand;
- a free-form send outside the 24-hour window reaching Meta (it would fail there, after charging our wallet);
- a WhatsApp Web number behaving differently after this change.

## 4. Facts the design rests on

From the code (paths under `omnichannel-service/.../service` unless shown):

- **One send path.** Console, chatbot (`chatbot/run/ChatbotSender`), rules (`automation/SendTemplateHandler`) and the
  Cloud-shaped API (`cloud/CloudSendService`, `inbox/NewChatService.startCloud`) all end in `inbox/InboxSendService`:
  `send` (typed text or a saved message) and `sendCloud` (a `CloudMessage`). Both call `InboxDevices.requireReady`
  (number in the workspace, not archived, not disabled, a CONNECTED device) and then `dispatch`, which calls the
  device's `sendMessage` RPC and records the result in `afterSend`/`record` keyed by `(phoneNumberId, waMessageId)`.
  `UsageCharger.charge` debits one `WHATSAPP_MESSAGE` per send; `definitelyNotSent` keys on `RpcException` codes.
- **Device-only places** that call `requireReady`/`isReady`/the registry and must skip or branch for a Cloud number:
  `NewChatService.start`/`startCloud` (and `NumberLookupService.lookup`, RPC `getNumberId`), `ChatMediaService.fetchIfNeeded`
  (RPC `downloadMedia`), `HistoryImportRunner`, `ContactIdResolver` (lid lookups), `CampaignRunner`/`CampaignSenderService`
  (already exclude Cloud), `AutomationTestService` (picks any ready number), and in the app module `MessagesController`
  and `RpcController`.
- **Incoming is Cloud-shaped already.** `common.channel.whatsapp.webhook` models Meta's webhook (`WebhookEvent`,
  `Entry`, `Change`, `Value` with `metadata{display_phone_number, phone_number_id}`, `contacts[]{profile, wa_id, user_id}`,
  `Message`, `Status` with `errors`, `pricing`, `conversation`). `channel/ChannelWebhookEventStore.save(ctx, event, raw,
  seq, capturedAt, ordinal)` dedupes on `eventKey` (`message:<id>`, `status:<id>:<state>`, …) and keeps 30 days; it
  never throws (a failed write returns null) and on a duplicate returns the existing document without saying so; the
  `Value` record models only `metadata`, `contacts`, `messages`, `message_echoes`, `statuses`, `errors`;
  `InboxIngestService.storeStatus` drops a status whose message row is not stored yet;
  `channel/whatsapp/WhatsAppWebhookProcessor.process(ctx, event)` is documented as "the entry point a Cloud controller
  would call". `ChannelContext(kind, customerId, workspaceId, channelAccountId, displayPhoneNumber)` with
  `ChannelKind.WHATSAPP_CLOUD` exists. A chat's `remoteId` is `from + "@c.us"` (`inbox/MessageFields.chatId`).
- **Outgoing is Cloud-shaped already.** `common.channel.whatsapp.message.CloudMessage` (text, interactive, image, video,
  audio, document, location, context) with `MessagingProduct.WHATSAPP = "whatsapp"`; `CloudMessageValidator` accepts
  both products, but `cloud/CloudSendService` (`send`, `sendToChat`, `validateForPreview`) refuses anything but
  `whatsapp-web` and always answers `whatsapp-web`. No `template` type exists. Interactive messages become message-page links in
  `InboxSendService.cloudOutgoing`. The chatbot's `ReplyMatcher` already matches `button.payload`,
  `interactive.button_reply.id` and `list_reply.id`, then the title.
- **Window data exists.** `WaWebChat.lastInboundAt` (set in `InboxIngestService.foldIntoChat`); no window logic yet.
- **Media.** Incoming bytes live in GridFS bucket `chatMedia` (`ChatMediaService.store(ws, phoneNumberId, messageId,
  mime, filename, bytes)`); uploaded Cloud media in bucket `cloudMedia` (`CloudMediaService`).
- **Secrets.** `common.secret.SecretBox` (`ready()`, `seal`, `open`, `hint`) and `SealedSecret`, used by
  `aiConnections` (`apiKey`, `keyHint`). The AI module is the precedent for a module with its own collections,
  controllers and `AiModuleBoundaryTest`.
- **Saved messages are not Meta templates.** `messageTemplates` (`MessageTemplate`, types TEXT … POLL, `channel`
  field) and the console's **Templates** page are WhatsApp Web saved messages. The user's rule: "template" means Meta's
  templates; Cloud work does not reuse or extend `messageTemplates`/`Template*`.
- **Phone numbers.** `PhoneNumberService.requireConnectionType` refuses `WHATSAPP_CLOUD_API`; API in
  `app/phone/BrandPhoneNumberController` (`phone-number:view|manage`); console `PhoneNumbers/PhoneNumbersPage.tsx`,
  `PhoneNumberModal.tsx`, `PhoneNumberStatusLabel.tsx`.
- **Public endpoints.** `SecurityConfig` permits GET `/**` and named POST paths (`/t/*/e`, `/f/*`,
  `/api/devices/connect`); everything else under `/api/**` needs a login.

From Meta (the builder confirms each against Meta's current reference before coding; see §9):

- A system user's token (permissions `whatsapp_business_messaging`, `whatsapp_business_management`) can call the Graph
  API for the brand's WABA and numbers; it does not expire when created with "Never".
- Webhooks are set **per Meta app**: one callback URL and verify token for the app, which receives the events of every
  WABA the app is subscribed to (`POST /{waba-id}/subscribed_apps`). Meta verifies the URL with a GET carrying
  `hub.mode=subscribe`, `hub.verify_token`, `hub.challenge`, and signs every POST with `X-Hub-Signature-256:
  sha256=<HMAC-SHA256 of the raw body with the app secret>`. The app's callback can be set with the app access token
  (`{app-id}|{app-secret}`) through `POST /{app-id}/subscriptions` (`object=whatsapp_business_account`), or by hand in
  the app dashboard. Meta retries failed deliveries.
- Sending: `POST /{phone-number-id}/messages` returns `messages[0].id` (a `wamid`). Read receipts: the same endpoint
  with `{status: "read", message_id}`. Media: upload `POST /{phone-number-id}/media` → media id; download `GET
  /{media-id}` → short-lived URL, then GET with the token.
- Free-form messages only within 24 hours of the customer's last message; otherwise error 131047 (re-engagement
  required) and only templates go through.
- Templates: `GET /{waba-id}/message_templates` (name, language, status, category, components, parameter format, id);
  webhook field `message_template_status_update` (APPROVED, REJECTED, PAUSED, DISABLED, …).
- A number added in the app dashboard may still need `POST /{phone-number-id}/register` with a 6-digit PIN before it
  can send.

## 5. Design

### 5.1 Module and boundaries

New module **`omnichannel-meta`**, package `me.nageshk.omnichannel.meta` (named for Meta, not WhatsApp, since
Messenger and Instagram would join it) **(my call)**, after `ai` and before `service` in the root pom. Depends on
`common` and `security`; holds the Graph API client, the Meta connections and Cloud accounts, template import, the
webhook endpoint and signature check. Only `meta.api` is imported by other modules; `MetaModuleBoundaryTest` (as
`AiModuleBoundaryTest`) and `ServiceBoundaryTest` extended to allow only `meta.api.` from it. The send-path switch,
window rules and ingest stay in `omnichannel-service`.

### 5.2 Data (camelCase collections, workspace-scoped)

- **`metaConnections`** — one per brand and Meta app: `workspaceId, customerId, name, appId, appSecret: SealedSecret,
  appSecretHint, accessToken: SealedSecret, tokenHint, webhookKey (random, URL-safe, unique), verifyToken (random,
  shown to the admin, not a secret), webhookSetup (AUTO | OVERRIDE | MANUAL), status (UNVERIFIED | OK | INVALID), checkedAt,
  lastError, lastWebhookAt, createdAt, updatedBy, @Version`. Unique `{appId}` across the platform and `{webhookKey}`:
  Meta keeps one callback per app, so one app belongs to one brand; a second brand saving the same app ID gets 409
  "This Meta app is connected to another brand" **(my call)**.
  A connection holds the token because one system-user token serves all the app's numbers **(my call)**; several
  numbers on one app share one connection, one webhook address and one signature secret (§5.3).
- **`cloudAccounts`** — one per Cloud number: `workspaceId, phoneNumberId (ours), connectionId, wabaId,
  metaPhoneNumberId (unique), displayPhoneNumber, verifiedName, nameStatus, qualityRating, messagingLimitTier,
  platformType, registered, checkedAt, lastError, @Version`. Kept beside `PhoneNumber` instead of inside it, so the
  `db` module's `PhoneNumber` only gains nothing but `connectionType = WHATSAPP_CLOUD_API` **(my call)**.
- **`cloudTemplates`** — Meta templates as imported: `workspaceId, connectionId, wabaId, metaTemplateId, name,
  language, category, status, rejectedReason, parameterFormat (POSITIONAL | NAMED), components (Meta's JSON, kept as
  it came), variables[] (derived: `{key, component (HEADER | BODY | BUTTON), index, example}`), fills (admin's
  per-variable source, §5.6), supported (bool) + unsupportedReason, syncedAt, lastUsedAt`. Unique
  `{workspaceId, wabaId, metaTemplateId}`. Kept, no TTL. A template removed at Meta is marked `status = DELETED`, not
  removed, so old messages still show what was sent **(my call)**.
- `WaWebMessage` gains `failure {code, title, details}` from Meta's failed status, and `cloudTemplateId` for a
  template send. `ChannelWebhookEventStore` unchanged.
- Secrets: as the AI keys — write-only on the wire (a request carries `appSecret`/`accessToken` only to set or replace
  them, views carry hints and `setAt`), never logged, Graph error bodies scrubbed; saving needs `SecretBox.ready()`,
  else 503 with the AI module's message.

### 5.3 Receiving (webhook)

- Endpoint `GET|POST /hooks/meta/{webhookKey}` in `omnichannel-meta`. The module cannot call the service module, so it
  hands each verified change to a `meta.api.CloudWebhookSink` port implemented in `omnichannel-service` (as
  `ai.api.AiTurnListener`) **(my call)**. The endpoint is permitted without login in `SecurityConfig`
  (POST added beside `/t/*/e`) **(my call: path)**. Unknown key → 404. Body cap 3 MB.
- GET: `hub.mode == subscribe` and `hub.verify_token` equal to the connection's → 200 with `hub.challenge` as plain
  text; else 403.
- POST: the signature is checked over the **raw bytes** with the connection's app secret in constant time before
  anything is parsed; a bad or missing signature → 401, nothing stored, logged without the body. Then
  `lastWebhookAt` is stamped.
- Routing: each change's `value.metadata.phone_number_id` must belong to a `cloudAccounts` row **of this connection**;
  else the change is dropped, counted on the connection (`ignoredChanges`, `lastIgnoredNumber`) and logged once per
  number per hour, not per call (a number on the brand's app that is not in our app, e.g. a provider's number in a
  shared WABA) **(my call)**. Template and account
  changes route by `entry.id` (WABA id) to the connection's accounts.
- Store, then answer: each message and each status becomes one stored event (one change, as the bridge stores them),
  through `ChannelWebhookEventStore.save` with a `ChannelContext(WHATSAPP_CLOUD, …, ourPhoneNumberId, display)`;
  `raw` holds that change's JSON exactly as Meta sent it (so fields our model does not type survive). The store gains
  a result that says **stored new / duplicate / failed**: any failed save → 500 (Meta retries; nothing is lost);
  otherwise 200. Only new events are processed, so Meta's retries are stored once and processed once (the WhatsApp
  Web path keeps its behaviour). Processing (`WhatsAppWebhookProcessor.process`) runs after the reply on a small
  executor **(my call)**; a processing failure is logged and the stored event remains.
- `Value` gains Meta's typed fields for the template and number changes we act on (`event`, `message_template_id`,
  `message_template_name`, `message_template_language`, `reason`, quality and name fields), Meta's names exactly.
- **Status before row**: on Cloud a `sent`/`failed` status can arrive before `afterSend` records the row. A Cloud
  status for an unknown message is kept (it is already in the event store) and `record` applies any stored statuses
  for the new `wamid` right after writing the row **(my call)**.
- **Senders without a phone**: when Meta's business-scoped user ids are live, a message may carry `from_user_id`
  (`user_id`) and no `from`. Such a chat is keyed `<user_id>@user` (a new suffix, not WhatsApp Web's `@lid`);
  `isPersonChat` accepts it; replies use Meta's field for user-id recipients as the reference names it; if Meta
  refuses, the send fails with Meta's message **(my call)**. A later message from the same person that carries the
  phone links the contact by phone, as lid chats do today.
- Fields subscribed: `messages`, `message_template_status_update`, `phone_number_quality_update`,
  `phone_number_name_update`, `account_update` **(my call)**; any other field is stored and ignored.
- Template and number fields update `cloudTemplates` and `cloudAccounts` (an unknown template id triggers a sync).
- Incoming media: the `image|video|audio|document|sticker.id` is kept as the media's Meta id; a background download
  (Graph `GET /{media-id}` then the URL) stores the bytes in `chatMedia` right away, since Meta's URL is short-lived.
  `ChatMediaService.fetchIfNeeded` for a Cloud message downloads from Meta instead of the device RPC.
- Everything downstream (contacts, chats, routing, chatbot, AI node, lead owner, CRM capture, CTWA `referral`, rules)
  is the existing ingest. Things that exist only for WhatsApp Web do not happen for Cloud numbers: echoes, history
  import, lid resolution, number lookup.

### 5.4 Sending (the switch)

- `InboxDevices.requireReady` becomes the channel check, returning a **`SendChannel`** (sealed: `DeviceChannel` with
  today's `DeviceSession`, `CloudChannel` with the account) **(my call: names)**. For a Cloud number: number in the
  workspace, not archived, not disabled, workspace usable, account `registered`, connection not `INVALID`; else
  `NoDeviceException` with new code `CLOUD_NOT_READY` (409) and a reason.
- `dispatch` keeps one entry; a `DeviceChannel` goes to the RPC exactly as today, a `CloudChannel` to
  `meta.api.CloudApi.send(account, CloudMessage) -> sent wamid`. The stored row is recorded as today with
  `waMessageId = wamid`, so Meta's statuses (`Status.id`) find it through the same key. No echo exists on Cloud.
- What goes to Meta, by what the caller sent:
  - typed text → Cloud `text` (`preview_url` true, as the bridge's link preview);
  - a `CloudMessage` → itself, with `messaging_product = "whatsapp"`; **interactive goes natively** (buttons, lists,
    `cta_url`, `location_request_message`) — no message page;
  - a saved message (`messageTemplates`): TEXT, IMAGE, VIDEO, DOCUMENT, AUDIO, LOCATION map to Cloud messages;
    POLL and CONTACT_CARD are refused on a Cloud number ("not available on Cloud API numbers") **(my call)**;
  - media: the bytes are uploaded to Meta (`/media`) and sent by id, every time **(my call: no reuse cache in v1)**;
  - a Meta template (§5.6) → Cloud `template`.
- Read receipt: after a successful reply, the chat's latest incoming message is marked read at Meta (the bridge's
  `sendSeen`) **(my call)**.
- Errors: Graph errors map to clear failures; `190`/HTTP 401 → connection `INVALID` with the time (every Cloud number
  of it stops with `CLOUD_NOT_READY` until the token is replaced or re-checked); 131047 → `WINDOW_CLOSED`; rate limits
  → retryable (the chatbot and rules already retry `NoDeviceException`-style failures later). `UsageCharger` gives
  the charge back when Meta refused the request (4xx), keeps it when the outcome is unknown (timeout), and also gives
  it back when a later `failed` status arrives for a charged row (`usageEntryId`) **(my call: the brand pays only for
  messages Meta accepted for delivery)**.
- Later failures arrive as `failed` statuses with `errors[]`; the row gets `statusTimes.failed` and `failure`, and the
  thread shows Meta's title.
- The Cloud-shaped API (`CloudSendService`) accepts `messaging_product: "whatsapp"` (including `type: template`) for a
  Cloud number and `whatsapp-web` for a bridge number, refusing the other with "channel not available"; its response
  carries the number's product and, as today, our message id **(my call)**.
- Device-only callers (§4): new chats to a phone skip the number lookup on Cloud; `AutomationTestService` counts a
  ready Cloud number as ready; `MessagesController`/`RpcController` and history import refuse Cloud numbers with 409;
  campaigns keep excluding them.

### 5.5 The 24-hour window

- Open while `lastInboundAt > now − 24h` (Meta's customer service window). Computed, not stored. Exposed on the chat
  view as `window {open, closesAt}` for Cloud chats only.
- Server rule, before charging: on a Cloud number, anything that is not a template is refused outside the window with
  `ValidationException("window", "WINDOW_CLOSED")` (409) and the closing time; a new chat to a phone on a Cloud number
  is therefore template-only. Meta's 131047 maps to the same code if the clocks disagree.
- Chatbot steps and rules that send free-form outside the window fail with that reason (the chatbot node takes its
  failure path; the rule's run records it). Chatbots do not send templates in v1.

### 5.6 Meta templates

- **Sync**: on connection save, on a **Sync** button, and on every status webhook, `GET /{waba-id}/message_templates`
  (all pages) upserts `cloudTemplates`; templates missing from Meta's list become `DELETED`.
- **Supported in v1**: header none/TEXT (≤1 variable)/IMAGE/VIDEO/DOCUMENT; body with variables (positional `{{1}}` or
  named `{{first_name}}`); footer; buttons QUICK_REPLY, URL (static or one variable), PHONE_NUMBER, COPY_CODE (one
  value). Anything else marks the template `supported = false` with the reason, shown but not sendable **(my call)**.
- **Sendable** = `status APPROVED` and `supported`.
- **Fills** **(my call)**: on the template page an admin may set a source per variable from the sources saved messages
  already use (contact name, phone, custom attributes); the send dialog pre-fills from them and the counsellor can
  change any value; an empty required value blocks the send. A header media template asks for a file (or reuses one
  from `cloudMedia`).
- **Send**: a Cloud `template {name, language: {code}, components: [{type, parameters}]}` built from the values;
  validated against the template's variables (count, named keys, Meta's limits). `CloudMessage` gains `TEMPLATE` and
  a `Template` record in `common.channel.whatsapp.message`, Meta's field names exactly; the bridge refuses it
  ("channel not available"). The stored row holds the rendered body as its text (so the thread, search and previews
  show what the customer saw), header and buttons for the thread to draw, `cloudTemplateId`, and the Cloud request.
- **Rules**: a new action **`SEND_CLOUD_TEMPLATE`** (`{cloudTemplateId, values}`, values may use the rule's existing
  variables) with its handler, offered in every rule; on a WhatsApp Web number it fails with "channel not available"
  **(my call)**. `SEND_TEMPLATE` stays the saved-message action.
- Quick-reply taps arrive as `button` messages with the payload; the existing ingest, rules and `ReplyMatcher` see them.

### 5.7 Adding a Cloud number (API and console)

API (`omnichannel-meta`, `/api/workspaces/{ws}/meta/...`, authority `phone-number:manage`, view with
`phone-number:view`) **(my call: no new authorities)**:
- `GET/POST connections`, `GET/PUT/DELETE connections/{id}` (PUT without secrets keeps them), `POST
  connections/{id}/check` (token and app secret; lists the WABA's numbers), `POST connections/{id}/webhook` (try the
  automatic setup again). DELETE with numbers → 409 with their names.
- Adding the number goes through the existing `POST phone-numbers` with `connectionType: WHATSAPP_CLOUD_API` and
  `cloud {connectionId, wabaId, metaPhoneNumberId}`. Before saving: Graph `GET /{metaPhoneNumberId}` (display number,
  verified name, name status, quality, limit tier, platform type), and the number must be in the WABA's list; our
  `number` is the display number's digits (the existing unique rule across brands applies); `POST
  /{waba-id}/subscribed_apps`. A Graph failure → 422 with Meta's message.
- `POST phone-numbers/{id}/cloud/register {pin}` when the number is not registered yet; the PIN is not stored.
- `GET/POST /api/workspaces/{ws}/meta/templates` (list, `?sendable=true` for the pickers, readable by anyone who may
  send in the brand), `POST templates/sync`, `PUT templates/{id}/fills`.
- Webhook setup on connection save, never overwriting someone else's callback **(my call)**: read the app's current
  subscription (`GET /{appId}/subscriptions` with the app access token). None, or ours → set it with `POST
  /{appId}/subscriptions` (callback `<platform pages address>/hooks/meta/{webhookKey}`, verify token, the fields of
  §5.3) → `AUTO`. A different callback already set → leave it, and point only this WABA at us with
  `override_callback_uri` + `verify_token` on `POST /{waba-id}/subscribed_apps` → `OVERRIDE`. Any failure → `MANUAL`:
  the page shows the address and verify token to paste, with the steps and a warning if the app already sends its
  events elsewhere. The platform's pages address is the public origin **(my call)**.
- Cloud numbers refuse: connect code, revoke device, move to another brand, history fetch (409 with a reason).

Console (`omnichannel-crm-frontend`):
- **Phone numbers → Add number** asks WhatsApp Web or WhatsApp Cloud API. Cloud: choose or add a Meta connection (name,
  app ID, app secret, access token as write-only fields), then pick the number from the WABA's list the check
  returned (or type WABA ID and phone number ID); shows verified name, name status, quality and limit tier before
  saving. A link-out list of the Meta-side steps (app, system user, token, Live mode, payment method) sits beside the
  form.
- Number row and status label: Cloud shows **Ready**, **Not registered** (with Register + PIN), **Waiting for first
  webhook**, or **Token invalid** (with Replace token); the WhatsApp Web device actions are hidden.
- **Meta connections** list on the same page: hints, last check, webhook address + verify token with copy buttons,
  last webhook time, Check and Retry webhook setup.
- **WhatsApp templates** page (Brand setup, `phone-number:view`): list with status, category, language, supported
  flag, preview; Sync; per-variable fills. Named so it is not confused with the existing **Templates** page of saved
  messages **(my call)**.
- **Inbox**: on a Cloud chat, a line above the composer: "Replies close in 3 h" / "24-hour window closed — send a
  WhatsApp template". When closed, the text box is disabled and **WhatsApp template** opens the picker; the picker
  fills variables (pre-filled), shows the rendered preview, and asks for a header file when needed. The saved-message
  picker on a Cloud chat hides polls and contact cards. Native interactive messages are previewed as they are today.
  Failed messages show Meta's title. New chat on a Cloud number goes straight to the template picker.
- **Rules editor**: the "Send WhatsApp template" action with a template picker and variable values.
- **Run panel / thread**: a template message draws header, body, footer and buttons.

## 6. Constraints

Java 21, Spring Boot 4.1, Jackson 3 (no Meta SDK; `RestClient` on the JDK client, as the AI adapter), MongoDB,
GridFS never S3, camelCase collections, channel-neutral names for new shared things, no "template" naming for anything
that is not a Meta template, React 18 + PatternFly 6.5. Graph API version and base URL are properties
(`omnichannel.meta.graph-version`, `omnichannel.meta.graph-base-url`, the latter for the test stub). Work in
`.worktrees/cloud-number` in both repos, test first, Prettier on touched files only, the full `./mvnw -q verify` once
before merge. Real sends only to a recipient the user names; sending hours apply to messages the app sends first.

## 7. Approaches considered

- **A. One switch inside the send path (chosen).** Every sender keeps working; incoming reuses the event store and
  processor built for this; Meta code isolated in a module. Cost: the send path learns the window and templates.
- **B. Make the Cloud number pretend to be a WhatsApp Web device** (answer the `sendMessage` RPC with Graph calls).
  Fewest files, but the RPC carries WhatsApp Web shapes (base64 media, page links), so we would convert back, lose
  native buttons, and have nowhere for the window and template rules. Rejected.
- **C. A separate inbox stack for Cloud numbers.** Clean names, but duplicates the inbox, chatbot, routing, CRM capture
  and lead owners, all built on today's chats. Rejected.
- Templates: own `cloudTemplates` collection rather than `messageTemplates` with `channel = CLOUD` (the user's naming
  rule; saved messages and Meta templates differ in shape and lifecycle).

## 8. Left to the builder, and open points

Left to the builder: package layout inside the module; the Graph client's retry numbers; the webhook executor size;
how the thread draws template buttons; index names; how `SendChannel` is threaded through `InboxSendService` without
changing WhatsApp Web behaviour (proved by the existing tests staying green untouched). The console needs its own
`.worktrees/cloud-number` before its plan starts.

## 9. Verification

- Backend unit tests: signature check (valid, bad, missing, wrong secret, body changed by one byte), verify GET, Graph
  request mapping (golden JSON for text, interactive, media by id, template with header media, named and positional
  variables, read receipt), Graph error mapping (190, 131047, rate limit, 5xx, timeout), template import (variables
  derived, unsupported kinds flagged), window computation.
- ITs with a local HTTP stub for the Graph API: connection save with automatic and manual webhook setup; secrets never
  in any response or log; adding a number (display number becomes `number`, duplicate across brands refused, number
  not in the WABA refused); a signed incoming text creates contact, chat and message and starts the chatbot; Meta's
  retry of the same event stores nothing new; a change for a number of another connection is dropped; incoming image
  downloaded to `chatMedia`; reply goes out with `messaging_product whatsapp` and the status webhook sets ticks; a
  native button reply advances the chatbot; free text outside the window refused before charging; template send from
  the inbox and from a rule; 401 from Graph marks the connection invalid and the next send is `CLOUD_NOT_READY`;
  WhatsApp Web number tests unchanged; boundary tests.
- Console: vitest for the add-number flow, connections (write-only secrets), status labels, window line and disabled
  composer, template picker with fills and header file, rules action; `tsc`, lint, build.
- **Before coding**, the builder reads Meta's current reference for every endpoint and payload in §4 and records any
  difference in the plan (field names, the subscriptions call, template components).
- Manual on 8089 through ngrok: the user (or Bimal's admin) makes a Meta app with Meta's **test number** from the app
  dashboard (it can message up to five verified recipients), adds the connection, receives a message from a phone the
  user names, replies, lets the chatbot run, and sends an approved template (Meta's sample `hello_world`) after the
  window; then Bimal's real number.

## 10. Risks

- The webhook address is the ngrok tunnel until deployment (D4); a new tunnel means re-running the webhook setup.
- One system-user token per connection: if Bimal's admin revokes it, every number of that app stops until replaced; the
  status label says so.
- Meta's API changes versions often; the version is a property and the stub tests pin our assumptions.
- If Bimal puts the new number in the WABA that holds Wati's number, the sync imports Wati's templates too (usable,
  since they are the same WABA's) and the Wati number's events reach us and are ignored (§5.3); quality and limits
  are shared. A new WABA (the default when Bimal makes its own app) starts with no approved templates: the follow-up
  templates must be submitted and approved there before the walk.
- Template content is Meta's JSON kept as is; new component kinds appear as "not supported yet" rather than breaking.
