# Cloud API numbers — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A brand adds a number that lives on its own Meta app; Meta's webhooks reach the existing inbox pipeline, every sender in the app sends through Meta's API for that number, the 24-hour window is enforced, and approved Meta templates are imported and sent from the inbox and from rules.

**Architecture:** A new `omnichannel-meta` module owns the Graph API client, `metaConnections`, `cloudAccounts`, `cloudTemplates`, the public webhook endpoint and its signature check; it reaches the service module only through `meta.api` ports (`CloudWebhookSink`). In `omnichannel-service`, `InboxDevices.requireReady` becomes a channel check returning a sealed `SendChannel`, and `InboxSendService.dispatch` sends a `CloudChannel` through `meta.api.CloudApi`. Incoming Meta changes are stored in `channelWebhookEvents` and processed by the existing `WhatsAppWebhookProcessor`.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, `RestClient` on the JDK HTTP client (no Meta SDK), MongoDB + GridFS, JUnit 5 with the app's IT bases (`AutomationTestBase`, `ChatbotTestBase`) and a local `GraphStub` HTTP server.

**Spec:** `docs/superpowers/specs/2026-11-20-cloud-numbers-design.md` (approved 2026-11-20). The console plan `2026-11-20-cloud-numbers-console.md` consumes the **API contract** section below; change it only together with that plan.

## Global Constraints

- Java 21, Spring Boot 4.1, Jackson 3, MongoDB; GridFS never S3; collections camelCase: `metaConnections`, `cloudAccounts`, `cloudTemplates`.
- "Template" names only Meta templates. Do not reuse or extend `messageTemplates` / `MessageTemplate` / `Template*` classes for Cloud work; the new names are `CloudTemplate*`, `SEND_CLOUD_TEMPLATE`, `Template` (the Cloud `/messages` object).
- `omnichannel-meta` imports only `meta.`, `common.`, `security.access.`, `security.auth.`; other modules import only `meta.api.` from it (`MetaModuleBoundaryTest`, `ServiceBoundaryTest`).
- Secrets (`appSecret`, `accessToken`) are sealed with `common.secret.SecretBox`, write-only on the wire (views carry `*Hint` and `*SetAt` only), never logged; Graph error text is scrubbed of the token before logging; saving needs `SecretBox.ready()`, else the same 503 the AI module gives.
- A webhook POST is accepted only with a valid `X-Hub-Signature-256` over the raw bytes, compared in constant time; a failed event save answers 500; only newly stored events are processed.
- A free-form send on a Cloud number outside 24 h of `lastInboundAt` is refused **before** charging: `WindowClosedException` → 409 `{code: "WINDOW_CLOSED", closesAt}`; templates are exempt.
- No new authorities: connections, numbers, sync and fills need `phone-number:manage`; their reads `phone-number:view`; the template list for pickers and every send need `inbox:send` (rules: `automation:manage` as today).
- Properties: `omnichannel.meta.graph-base-url` (default `https://graph.facebook.com`), `omnichannel.meta.graph-version` (default the version the builder confirms in Task 1, e.g. `v23.0`), `omnichannel.meta.request-timeout` (20 s), `omnichannel.meta.webhook-max-bytes` (3 MB).
- WhatsApp Web behaviour unchanged: existing tests stay green **without edits** except where a task names the test it changes.
- Work in `.worktrees/cloud-number`; tests first; while fixing run only the tests that matter; full `./mvnw -q verify` once before merge; merge, push and deploy only when the user asks. No real Meta call in any test.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Two numbers on one Meta app: one webhook URL carries both numbers' changes; each change lands in its own number, and a change for a number not in our app is dropped without failing the others — pinned in Task 4 (`routesEachChangeToItsNumber`, `dropsUnknownNumberKeepsTheRest`).
2. Meta retries a delivery we already stored: nothing is stored or processed twice, and a database failure answers 500 so Meta retries — pinned in Task 4 (`retryIsProcessedOnce`, `failedSaveAnswers500`).
3. Meta's `sent`/`failed` status arrives before our send recorded the row: the tick or failure still lands — pinned in Task 6 (`statusBeforeRowIsApplied`), with the keeping half in Task 5 (`statusForUnknownCloudMessageIsKept`).
4. A token revoked at Meta: the first 401 marks the connection invalid, later sends fail fast with `CLOUD_NOT_READY`, and nothing is charged — pinned in Task 6 (`revokedTokenStopsSendsWithoutCharge`).
5. The window closes between opening the composer and sending, or a chatbot's timer fires after 24 h: refused before Meta and before the wallet, with the closing time — pinned in Task 8 (`freeTextAfterWindowRefusedBeforeCharge`, `chatbotSendAfterWindowTakesFailure`).

## API contract (consumed by the console plan)

All under `/api/workspaces/{ws}`. Times are ISO-8601 strings. Error bodies are the app's usual `{field, message}` / `{code, …}`.

**Meta connections** (`meta/connections`)
- `ConnectionView`: `{id, name, appId, appSecretHint, appSecretSetAt, tokenHint, tokenSetAt, status: "UNVERIFIED"|"OK"|"INVALID", checkedAt, lastError, webhook: {url, verifyToken, setup: "AUTO"|"OVERRIDE"|"MANUAL", error, lastWebhookAt}, numbers: [{phoneNumberId, number, label}], ignoredChanges, lastIgnoredNumber}`.
- `GET meta/connections` → `ConnectionView[]`; `GET meta/connections/{id}` → `ConnectionView`.
- `POST meta/connections` `{name, appId, appSecret, accessToken}` → 201 `ConnectionView` (runs the check and the webhook setup; a failed check still saves, with `status INVALID` and `lastError`). 409 `{field: "appId"}` when the app is connected anywhere else.
- `PUT meta/connections/{id}` `{name, appSecret?, accessToken?}` → `ConnectionView` (absent secret = keep; `appId` cannot change).
- `POST meta/connections/{id}/check` (optional body `{wabaId}`: Meta may leave a token's WABA ids out, so the admin can type one; 400 field `wabaId` if the token cannot read it) → `{connection: ConnectionView, accounts: [{wabaId, wabaName, numbers: [{metaPhoneNumberId, displayPhoneNumber, verifiedName, nameStatus, qualityRating, platformType, phoneNumberId}]}]}` (`phoneNumberId` = ours when already added, else null).
- `POST meta/connections/{id}/webhook` → `ConnectionView` (tries the setup again).
- `DELETE meta/connections/{id}` → 204; 409 `{field: "numbers", message: "Used by: Admissions, …"}`.

**Cloud numbers** (existing `phone-numbers`)
- `POST phone-numbers` `{label, connectionType: "WHATSAPP_CLOUD_API", cloud: {connectionId, wabaId, metaPhoneNumberId}}` → 201 `PhoneNumberView` (`number` is ignored and taken from Meta). 400 `{field: "cloud", message: <Meta's message or ours>}`; 409 duplicate number as today.
- `PhoneNumberView` gains `cloud: null | {connectionId, connectionName, wabaId, metaPhoneNumberId, verifiedName, nameStatus, qualityRating, messagingLimitTier, registered, state: "READY"|"NOT_REGISTERED"|"WAITING_FOR_WEBHOOK"|"TOKEN_INVALID", lastError}`.
- `POST phone-numbers/{id}/cloud/register` `{pin}` (6 digits) → `PhoneNumberView`.
- On a Cloud number `connect-code`, `revoke-device`, history imports and the platform move → 409 `{code: "CLOUD_NUMBER"}`.

**Meta templates** (`meta/templates`)
- `CloudTemplateView`: `{id, wabaId, name, language, category, status, rejectedReason, parameterFormat: "POSITIONAL"|"NAMED", components, header: {format: "NONE"|"TEXT"|"IMAGE"|"VIDEO"|"DOCUMENT"}, variables: [{key, component: "HEADER"|"BODY"|"BUTTON", index, example}], fills: {[key]: {kind: "CONTACT_FIELD"|"CONTACT_ATTRIBUTE"|"AGENT", attributeKey, fallback}}, supported, unsupportedReason, sendable, syncedAt, lastUsedAt}`. `components` is Meta's JSON as imported. A variable's `key` is `"header.1"`, `"body.1"`/`"body.first_name"`, `"button.<index>"`.
- `GET meta/templates?phoneNumberId=&sendable=` → `CloudTemplateView[]` (by the number's WABA; `inbox:send` or `phone-number:view`).
- `POST meta/templates/sync` `{connectionId?}` → `{added, updated, removed}`.
- `PUT meta/templates/{id}/fills` `{fills}` → `CloudTemplateView`.

**Inbox**
- `ChatView` gains `window: null | {open, closesAt}` (null for WhatsApp Web chats) and `channel: "WHATSAPP_WEB"|"WHATSAPP_CLOUD"`.
- `MessageView` gains `failure: null | {code, title, details}` and `template: null | {name, language, header: {format, text, mediaId}, body, footer, buttons: [{type, text}]}`.
- `POST inbox/chats/{chatId}/cloud-templates` `{cloudTemplateId, values: {[key]: string}, headerMediaId?}` → `MessageView` (`headerMediaId` is a `cloudMedia` id from the existing upload).
- `POST inbox/phone-numbers/{phoneNumberId}/chats` (existing `NewChatRequest`) gains `cloudTemplate: {cloudTemplateId, values, headerMediaId?}`; on a Cloud number it is the only accepted first message.
- 409 `{code: "WINDOW_CLOSED", closesAt}` for free-form outside the window; 409 `{code: "CLOUD_NOT_READY", message}` when the number cannot send.

**Rules**
- Action `{type: "SEND_CLOUD_TEMPLATE", cloudTemplateId, values: {[key]: string}}`; values may use the rule variables `SendTemplateHandler` already resolves. The automation options endpoint lists sendable Cloud templates as `cloudTemplates: [{id, name, language, phoneNumberIds, variables}]`.

---

### Task 1: The `omnichannel-meta` module, Graph client and Meta connections

**Files:**
- Create: `omnichannel-meta/pom.xml` (as `omnichannel-ai/pom.xml`: common, security, webmvc, data-mongodb, validation, test); root `pom.xml` module after `omnichannel-ai`; `omnichannel-service/pom.xml` and `omnichannel-app/pom.xml` depend on it.
- Create: `omnichannel-meta/src/main/java/me/nageshk/omnichannel/meta/config/MetaProperties.java` (`@ConfigurationProperties("omnichannel.meta")`: `graphBaseUrl`, `graphVersion`, `requestTimeout`, `webhookMaxBytes`).
- Create: `meta/graph/GraphClient.java`, `meta/graph/GraphException.java`, `meta/graph/GraphErrors.java`.
- Create: `meta/connection/MetaConnection.java`, `MetaConnectionRepository.java`, `MetaConnectionService.java`, `MetaConnectionController.java`, `ConnectionView.java`, `ConnectionStatus.java`, `WebhookSetup.java`.
- Create: `meta/api/MetaConnections.java` (lookup port for the service module).
- Test: `omnichannel-meta/src/test/java/me/nageshk/omnichannel/meta/MetaModuleBoundaryTest.java`, `meta/graph/GraphErrorsTest.java`; `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/support/GraphStub.java`; `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/MetaTestBase.java`, `MetaConnectionsApiIT.java`. Modify `ServiceBoundaryTest`.

**Interfaces:**
- Produces: `GraphClient.get(String token, String path, Map<String,String> query) -> JsonNode`, `post(String token, String path, Object body) -> JsonNode`, `postMultipart(String token, String path, Map<String,Object> parts) -> JsonNode`, `download(String token, String absoluteUrl) -> byte[]`; paths are relative to `<base>/<version>/`.
- Produces: `GraphException(Kind kind, int httpStatus, Integer metaCode, String title, String details, Duration retryAfter)` with `enum Kind { AUTH, WINDOW_CLOSED, RATE_LIMIT, BAD_REQUEST, NOT_FOUND, UNAVAILABLE, TIMEOUT }`; `GraphErrors.of(int status, JsonNode body)`.
- Produces: `MetaConnection` (fields of spec §5.2 plus `ignoredChanges`, `lastIgnoredNumber`), `MetaConnectionService.open(MetaConnection c) -> Secrets(String appSecret, String accessToken)` (package-private use inside `meta`), `MetaConnectionService.markInvalid(String connectionId, String error)`, `findByWebhookKey(String key) -> Optional<MetaConnection>`.
- Produces: `meta.api.MetaConnections.status(String connectionId) -> ConnectionStatus` for the service module.
- Produces: test support `GraphStub` (same contract as `OpenAiStub`: `baseUrl()`, `enqueue(int status, String body)`, `enqueue(String pathPrefix, int status, String body)` for path-matched answers, `recorded()`, `reset()`), `MetaTestBase extends ChatbotTestBase` registering `omnichannel.meta.graph-base-url`, with helpers `meta(HttpMethod, path, User, body)` and `connect(String appId) -> JsonNode` (creates a connection with stubbed checks).

- [ ] **Step 1: Confirm Meta's reference.** Read Meta's current docs for: `debug_token` (granular scopes), `/{waba-id}` and `/{waba-id}/phone_numbers` fields, `/{phone-number-id}` fields, `/{app-id}/subscriptions`, `/{waba-id}/subscribed_apps` with `override_callback_uri`, `/{phone-number-id}/messages` (text, interactive, media, template, read receipt, user-id recipients), `/{phone-number-id}/media`, `/{media-id}`, `/{phone-number-id}/register`, `/{waba-id}/message_templates`, webhook payloads for `messages`, statuses with `errors`, `message_template_status_update`, `phone_number_quality_update`, `phone_number_name_update`, `account_update`, the signature header, and error codes 190, 131047, 130429, 131056, 131026. Write the confirmed version, field names and any difference from the spec into the ledger as `Ruling:` lines; later tasks use those names.

- [ ] **Step 2: Write the failing tests.**
  - `MetaModuleBoundaryTest.metaImportsOnlyCommonAndSecurity` — same scanner as `AiModuleBoundaryTest`, allowed prefixes `meta.`, `common.`, `security.access.`, `security.auth.`.
  - `ServiceBoundaryTest`: service imports only `meta.api.` from `meta`.
  - `GraphErrorsTest`: `401` or `{"error":{"code":190}}` → `AUTH`; `{"error":{"code":131047}}` → `WINDOW_CLOSED`; codes `130429`, `131056`, `4`, `80007` → `RATE_LIMIT`; `5xx` → `UNAVAILABLE`; `400 {"error":{"code":100,"message":"x","error_data":{"details":"y"}}}` → `BAD_REQUEST` with title `"x"`, details `"y"`.
  - `MetaConnectionsApiIT`:
    - `createSealsSecretsAndShowsHintsOnly` — POST `{name:"Bimal app", appId:"111", appSecret:"s3cr3t-app-secret", accessToken:"EAAG-token-abcdef1234"}` with the stub answering `debug_token` valid; response has `tokenHint "EAA…1234"`, no `accessToken`/`appSecret` anywhere in the JSON text; Mongo document holds `SealedSecret`s, not the clear text; the app log captured by the test contains neither value.
    - `putWithoutSecretsKeepsThem` and `putWithTokenReplacesItAndRechecks`.
    - `sameAppInAnotherBrandIs409` — `appId "111"` in brand B → 409 field `appId`.
    - `invalidTokenSavesAsInvalid` — stub `debug_token` `is_valid:false` → 201, `status INVALID`, `lastError` set.
    - `checkListsWabasAndNumbers` — stub granular scopes with WABA `222`, `/222` name, `/222/phone_numbers` with one number → `accounts[0].numbers[0].displayPhoneNumber`.
    - `deleteUnusedIs204`; `agentCannotManage` (403 for `demo-agent`); `noSecretsKeyIs503` (as the AI IT does).

- [ ] **Step 3: Run them to see them fail.**
  Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='MetaConnectionsApiIT,GraphErrorsTest,MetaModuleBoundaryTest,ServiceBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation fails (module and classes missing).

- [ ] **Step 4: Implement** the module, `GraphClient` (`RestClient` with `JdkClientHttpRequestFactory`, timeout from properties, `Authorization: Bearer`, errors through `GraphErrors.of`; never logs the token or request bodies), the connection document (unique indexes `{appId}` and `{webhookKey}`; `webhookKey` 32 random URL-safe chars; `verifyToken` 24 random chars), the service (check = `GET debug_token?input_token=<token>` with the app token `appId|appSecret`, requires `is_valid`, `app_id == appId`, both WhatsApp scopes; lists WABA ids from the scopes' `target_ids`), and the controller of the API contract. Webhook setup is Task 2; here `webhook.setup` is `MANUAL` and `url` is `<pages base>/hooks/meta/<webhookKey>` (pages base from the existing pages-address service).

- [ ] **Step 5: Run the tests.** Same command. Expected: PASS.

- [ ] **Step 6: Commit** `feat(meta): module, Graph client and Meta connections`.

### Task 2: Automatic webhook setup that never overwrites

**Files:**
- Create: `meta/connection/WebhookSetupService.java`
- Modify: `MetaConnectionService` (call on create, on token/secret replace, and from `POST connections/{id}/webhook`).
- Test: `omnichannel-app/.../app/meta/WebhookSetupIT.java`

**Interfaces:**
- Consumes: `GraphClient`, `MetaConnection`.
- Produces: `WebhookSetupService.setUp(MetaConnection c, List<String> wabaIds) -> WebhookSetup` and the list of fields `WebhookSetupService.FIELDS = List.of("messages", "message_template_status_update", "phone_number_quality_update", "phone_number_name_update", "account_update")`.

- [ ] **Step 1: Write the failing tests** in `WebhookSetupIT`:
  - `noCallbackSetsOursAuto` — stub `GET /111/subscriptions` → `{"data":[]}`; then `POST /111/subscriptions` recorded with `object=whatsapp_business_account`, `callback_url` ending `/hooks/meta/<webhookKey>`, our `verify_token`, `fields` = the five fields; `POST /222/subscribed_apps` recorded; view `setup AUTO`.
  - `ourCallbackAlreadyThereIsAuto` — current callback equals ours → no POST to `/111/subscriptions`, `AUTO`.
  - `otherCallbackIsLeftAndWabaOverridden` — current callback `https://other.example/hook` → no POST to `/111/subscriptions`; `POST /222/subscribed_apps` with `override_callback_uri` = ours and `verify_token`; `OVERRIDE`.
  - `failureIsManualWithError` — subscriptions call 400 → `MANUAL`, `webhook.error` = Meta's message; connection still saved.
  - `retryEndpointRunsAgain` — `POST connections/{id}/webhook` after a failure, stub now OK → `AUTO`.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=WebhookSetupIT -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement** with the app access token `appId|appSecret` for `/{appId}/subscriptions` and the system token for `subscribed_apps`; the order and rules of spec §5.7.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(meta): webhook setup that never overwrites another callback`.

### Task 3: Adding a Cloud number

**Files:**
- Create: `meta/account/CloudAccount.java`, `CloudAccountRepository.java`, `CloudAccountService.java`; `meta/api/CloudAccounts.java` (port), `meta/api/CloudAccountInfo.java`, `meta/api/CloudNumberRequest.java`.
- Modify: `omnichannel-service/.../service/phone/PhoneNumberService.java` (accept `WHATSAPP_CLOUD_API` with a `CloudNumberRequest`, refuse device actions and moves), `PhoneNumberView` (or its builder) to carry `cloud`, `app/phone/BrandPhoneNumberController.java` (`AddRequest` gains `CloudNumberRequest cloud`; `POST /{id}/cloud/register`), `PlatformPhoneNumberController` move endpoints and `HistoryImportController` (409 `CLOUD_NUMBER`), number delete (removes the `cloudAccounts` row).
- Create: `omnichannel-common/.../common/api/CloudNumberException.java` (409 `{code: "CLOUD_NUMBER", message}`) and its mapping in `ApiExceptionHandler`.
- Test: `omnichannel-app/.../app/meta/CloudNumbersApiIT.java`; extend `PhoneNumbersApiIT` only by the one test that asserted Cloud is refused (`cloudConnectionTypeIsRefused` → delete it; its replacement lives in the new IT).

**Interfaces:**
- Consumes: `MetaConnectionService`, `GraphClient`.
- Produces: `meta.api.CloudAccounts.create(String ws, String customerId, String phoneNumberId, CloudNumberRequest req) -> CloudAccountInfo`, `preview(String ws, CloudNumberRequest req) -> CloudAccountInfo` (Graph read + "in the WABA" check, no write), `find(String phoneNumberId) -> Optional<CloudAccountInfo>`, `findByMetaPhoneNumberId(String metaId) -> Optional<CloudAccountInfo>`, `register(String phoneNumberId, String pin) -> CloudAccountInfo`, `delete(String phoneNumberId)`.
- Produces: `record CloudNumberRequest(String connectionId, String wabaId, String metaPhoneNumberId)`, `record CloudAccountInfo(String phoneNumberId, String workspaceId, String connectionId, String connectionName, String wabaId, String metaPhoneNumberId, String displayPhoneNumber, String verifiedName, String nameStatus, String qualityRating, String messagingLimitTier, boolean registered, CloudState state, String lastError)` with `enum CloudState { READY, NOT_REGISTERED, WAITING_FOR_WEBHOOK, TOKEN_INVALID }` (`state` derived: `TOKEN_INVALID` if the connection is `INVALID`, else `NOT_REGISTERED` if not registered, else `WAITING_FOR_WEBHOOK` if the connection has no `lastWebhookAt`, else `READY`).

- [ ] **Step 1: Write the failing tests** in `CloudNumbersApiIT`:
  - `addsCloudNumberWithMetaDetails` — connection from `connect("111")`; stub `/333?fields=…` → `display_phone_number "+91 98765 43210"`, `verified_name "Bimal Institute"`, `platform_type "CLOUD_API"`; `/222/phone_numbers` lists `333`; POST → 201, `number "919876543210"`, `connectionType "WHATSAPP_CLOUD_API"`, `cloud.verifiedName "Bimal Institute"`, `cloud.state "WAITING_FOR_WEBHOOK"`; `/222/subscribed_apps` was called.
  - `numberNotInWabaIs400` (field `cloud`); `metaErrorIs400WithMetaMessage`; `duplicateNumberAcrossBrandsIs409` (the existing unique rule).
  - `notRegisteredNumberShowsNotRegistered` (`platform_type "NOT_APPLICABLE"`) and `registerSendsPinAndFlipsState` — `POST /333/register` recorded with `{"messaging_product":"whatsapp","pin":"123456"}`; the PIN is in no stored document.
  - `deviceActionsAreRefused` — `connect-code`, `revoke-device`, history import, platform move → 409 `CLOUD_NUMBER`.
  - `deleteRemovesAccount`; `connectionWithNumbersCannotBeDeleted` (409 field `numbers`, message names the label).
  - `planLimitStillApplies` — the brand at its `PHONE_NUMBERS` limit → the same error as for a WhatsApp Web number.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='CloudNumbersApiIT,PhoneNumbersApiIT' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement.** `PhoneNumberService.create` for Cloud: `preview` first (so a Meta failure writes nothing), then insert the `PhoneNumber` (number = digits of `display_phone_number`), then `CloudAccounts.create`; if that fails, the number insert is removed. `cloudAccounts` unique on `{metaPhoneNumberId}` and `{phoneNumberId}`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(meta): add, register and remove Cloud numbers`.

### Task 4: The webhook endpoint

**Files:**
- Create: `meta/webhook/MetaWebhookController.java` (`GET|POST /hooks/meta/{webhookKey}`), `meta/webhook/WebhookSignature.java`, `meta/webhook/WebhookRouter.java`; `meta/api/CloudWebhookSink.java`, `meta/api/CloudChange.java`.
- Create: `omnichannel-service/.../service/channel/cloud/CloudWebhookIngest.java` (implements `CloudWebhookSink`).
- Modify: `omnichannel-security/.../SecurityConfig.java` (permit POST `/hooks/meta/*`; GET is already open), `ChannelWebhookEventStore` (new `saveResult(...) -> SaveResult` with `enum Outcome { STORED, DUPLICATE, FAILED }` and the document; the old `save` keeps its contract by delegating), `common.channel.whatsapp.webhook.Value` (Meta's template and number change fields, Meta's names), `WaWebWebhookListener` untouched.
- Test: `meta/webhook/WebhookSignatureTest.java`; `omnichannel-app/.../app/meta/MetaWebhookIT.java`.

**Interfaces:**
- Consumes: `MetaConnectionService.findByWebhookKey`, `CloudAccounts.findByMetaPhoneNumberId`.
- Produces: `WebhookSignature.valid(byte[] body, String header, String appSecret) -> boolean`.
- Produces: `record CloudChange(String connectionId, String phoneNumberId /*ours, null for WABA-level*/, String wabaId, String field, JsonNode value, Instant receivedAt)`; `interface CloudWebhookSink { Accepted accept(CloudChange change); }` with `enum Accepted { STORED, DUPLICATE, FAILED }`. The controller answers 500 if any change is `FAILED`, else 200; it calls `processAfterReply(List<CloudChange> stored)` on the sink for the `STORED` ones on an executor (`metaWebhookExecutor`, 4 threads).
- Produces: `ChannelWebhookEventStore.saveResult(ChannelContext ctx, WebhookEvent event, JsonNode raw, Long seq, Instant capturedAt, int ordinal) -> SaveResult(Outcome outcome, ChannelWebhookEvent doc)`.
- Produces: `meta.api.CloudAccounts.applyChange(CloudChange change)` (updates quality, name status, limit tier from Meta's number and account changes) and `MetaConnectionService.noteWebhook(String connectionId, Instant at)` / `noteIgnored(String connectionId, String metaPhoneNumberId)`.
- Account and template changes: `CloudWebhookIngest` hands `phone_number_quality_update`, `phone_number_name_update`, `account_update` to `CloudAccounts.applyChange(CloudChange)` and `message_template_status_update` to `CloudTemplates.applyChange(CloudChange)` (Task 7; until then stored and ignored).

- [ ] **Step 1: Write the failing tests.**
  - `WebhookSignatureTest`: `valid` for `sha256=` + hex HMAC-SHA256 of the body with the secret; false for a missing header, wrong prefix, wrong secret, one byte changed, uppercase hex accepted.
  - `MetaWebhookIT` (signed helper `post(key, json)` builds the header with the connection's app secret):
    - `verifyEchoesChallenge` — GET `?hub.mode=subscribe&hub.verify_token=<ours>&hub.challenge=abc` → 200 body `abc`; wrong token → 403; unknown key → 404.
    - `badSignatureIs401AndStoresNothing`; `oversizeBodyIs413`.
    - `incomingTextCreatesChatAndStartsRules` — Meta's text sample from `+919799750743`, `metadata.phone_number_id "333"` → a chat `919799750743@c.us` on our number with the message, `lastInboundAt` set, a rule on `message received` runs; connection `lastWebhookAt` set; number `cloud.state "READY"`.
    - `routesEachChangeToItsNumber` — one POST, two entries for numbers `333` and `444` of the same connection → one message on each number.
    - `dropsUnknownNumberKeepsTheRest` — entries for `333` and `999` (not ours) → 200, the `333` message stored, `ignoredChanges 1`, `lastIgnoredNumber "999"`.
    - `numberOfAnotherConnectionIsDropped` — signed with connection A's secret, `phone_number_id` of connection B's number → dropped.
    - `retryIsProcessedOnce` — the same POST twice → one stored event, one message, the rule ran once.
    - `failedSaveAnswers500` — the store's Mongo write made to fail (as the existing store test does) → 500.
    - `qualityUpdateRefreshesAccount` — `phone_number_quality_update` → `cloud.qualityRating` changed.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='MetaWebhookIT,WebhookSignatureTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement.** The controller reads the raw body (`byte[]`, capped), checks the signature before parsing, parses `WebhookEvent`, routes each `entry.changes[]` (spec §5.3), and the sink stores each message and each status as its own one-change `WebhookEvent` (`object "whatsapp_business_account"`, `entry.id` = WABA id) with `raw` = that change's JSON, under `ChannelContext(WHATSAPP_CLOUD, customerId, workspaceId, ourPhoneNumberId, displayDigits)`. `processAfterReply` calls `WhatsAppWebhookProcessor.process(ctx, event)` for each stored event. Ignored-number logging: once per number per hour.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(meta): signed webhook endpoint into the inbox pipeline`.

### Task 5: Ingest details for Cloud: status before row, senders without a phone, media

**Files:**
- Modify: `service/inbox/InboxIngestService.java` (`storeStatus`: for `ChannelKind.WHATSAPP_CLOUD` keep quiet on an unknown message, it stays in the event store; expose `applyStoredStatuses(ChannelContext ctx, String waMessageId)` for Task 6), `service/inbox/MessageFields.java` (`chatId`: Cloud message with no `from` and a `from_user_id` → `<user_id>@user`; `isPersonChat` accepts `@user`), `service/inbox/ChatMediaService.java` (`fetchIfNeeded` for a Cloud number downloads through `meta.api.CloudMedia`), `ContactIdResolver` (never asks a device for a Cloud number's chats), `CloudWebhookIngest` (schedules the media download right after storing an inbound media message).
- Create: `meta/api/CloudMedia.java` + `meta/media/CloudMediaClient.java`.
- Modify: `ChannelWebhookEventStore` (`statusesFor(String phoneNumberId, String waMessageId) -> List<WebhookEvent>`).
- Test: `omnichannel-app/.../app/meta/CloudIngestIT.java`.

**Interfaces:**
- Produces: `meta.api.CloudMedia.download(String phoneNumberId, String metaMediaId) -> Downloaded(byte[] bytes, String mimeType, String filename)`, `upload(String phoneNumberId, byte[] bytes, String mimeType, String filename) -> String metaMediaId` (used in Task 6).
- `WaWebMessage.Media` keeps Meta's media id in the existing `Media.id` path the converter already uses for WhatsApp Web (`MessageFields.mediaOf` stores it; Cloud media is always `mediaAvailable`).

- [ ] **Step 1: Write the failing tests** in `CloudIngestIT`:
  - `statusForUnknownCloudMessageIsKept` — a signed `delivered` status for `wamid.X` with no row → 200, the event is in `channelWebhookEvents` and `ChannelWebhookEventStore.statusesFor(ourNumberId, "wamid.X")` returns it (Task 6 applies it when the row is recorded).
  - `userIdSenderGetsUserChat` — a message with `from_user_id "US.123"` and no `from` → chat `US.123@user`, contact without phone; a later message with `from "919799750743"` and the same `user_id` in `contacts[]` links the phone to that contact.
  - `incomingImageIsDownloadedToChatMedia` — stub `GET /media-1` → `{url: <stub>/files/1, mime_type: "image/jpeg"}`, the file bytes → the message's media is in GridFS `chatMedia` within the await window; the media endpoint serves it without any device.
  - `noDeviceCallsForCloudChats` — the whole IT runs with no device registered for the Cloud number; no RPC is attempted (the fake device registry records none).
  - `failedStatusStoresMetaReason` — `failed` status with `errors[0] {code: 131026, title: "Message undeliverable"}` → `statusTimes.failed` and `failure.title "Message undeliverable"` on the row.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=CloudIngestIT -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement** (`WaWebMessage` gains `Failure failure` record `{code, title, details}`; `MessageView` maps it).
- [ ] **Step 4: Run** the same command plus `-Dtest='CloudIngestIT,InboxIngest*IT,MessageFields*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(inbox): Cloud statuses, user-id senders and Meta media`.

### Task 6: The send switch

**Files:**
- Create: `service/inbox/SendChannel.java` (sealed: `DeviceChannel(DeviceSession device)`, `CloudChannel(CloudAccountInfo account)`); `meta/api/CloudApi.java` + `meta/send/CloudApiClient.java`.
- Modify: `service/inbox/InboxDevices.java` (`requireChannel(String ws, WaWebChat) -> SendChannel`, `requireChannel(String ws, String phoneNumberId)`, `isReady` true for a sendable Cloud number; `requireReady` unchanged for device-only callers), `InboxSendService` (`send`/`sendCloud` use `requireChannel`; `dispatch` branches; Cloud outgoing mapping; read receipt; error mapping), `NewChatService` (no number lookup on Cloud), `CloudSendService` (product per number, response product), `billing/wallet/UsageCharger.java` (`definitelyNotSent` true for `GraphException` with status 4xx; new `giveBackForMessage(String messageId, String why)` for a later `failed` status of a row with `usageEntryId`), `InboxIngestService.storeStatus` (calls it on a Cloud `failed`), `NoDeviceException` (code `CLOUD_NOT_READY`), `AutomationTestService` (Cloud numbers count as ready), `app/.../MessagesController` and `RpcController` (409 `CLOUD_NUMBER`).
- Test: `meta/send/CloudApiClientTest.java` (golden JSON); `omnichannel-app/.../app/meta/CloudSendIT.java`.

**Interfaces:**
- Consumes: `CloudAccounts`, `CloudMedia.upload`, `MetaConnections`.
- Produces: `meta.api.CloudApi.send(String phoneNumberId, CloudMessage message) -> String wamid` (throws `GraphException`; on `AUTH` marks the connection invalid first), `markRead(String phoneNumberId, String waMessageId)`.
- Produces: `record SendChannel.CloudChannel(CloudAccountInfo account)`; `InboxDevices.requireChannel` throws `NoDeviceException("CLOUD_NOT_READY", reason)` when the account state is not `READY`/`WAITING_FOR_WEBHOOK` (a number waiting for its first webhook may send) or the number is disabled/archived/the workspace unusable.

- [ ] **Step 1: Write the failing tests.**
  - `CloudApiClientTest` golden bodies: typed text → `{"messaging_product":"whatsapp","recipient_type":"individual","to":"919799750743","type":"text","text":{"preview_url":true,"body":"Hi"}}`; interactive buttons unchanged except the product; image by id `{"image":{"id":"m1","caption":"c"}}`; reply `context.message_id`; read receipt `{"messaging_product":"whatsapp","status":"read","message_id":"wamid.A"}`; a `@user` chat → the user-id recipient field confirmed in Task 1 instead of `to`.
  - `CloudSendIT` (a Cloud number with an open window: an inbound message 1 h ago):
    - `typedReplyGoesToMeta` — POST `inbox/chats/{id}/messages {text:"Hi"}` → stub `POST /333/messages` recorded with the golden text body and `Bearer` token; stored row `waMessageId` = stub's `wamid.A`; `POST /333/messages` read receipt for the last inbound recorded.
    - `chatbotButtonsGoNative` — a chatbot Ask node with two buttons on the Cloud number → `type "interactive"` body recorded, no `messagePages` row; a signed `button_reply` webhook advances the run.
    - `savedImageIsUploadedThenSent` — saved message of type IMAGE → `POST /333/media` multipart then `/333/messages` with `image.id`.
    - `pollSavedMessageRefusedOnCloud` — 400 "not available on Cloud API numbers"; nothing charged.
    - `statusBeforeRowIsApplied` — signed `sent` and `delivered` statuses for `wamid.A` arrive (stub delays its answer with `beforeNext`) before the send's row is recorded → after the send the row has `statusTimes.sent` and `delivered` (`record` calls `InboxIngestService.applyStoredStatuses` for Cloud rows).
    - `revokedTokenStopsSendsWithoutCharge` — stub 401 `{"error":{"code":190}}` → the send fails 409 `CLOUD_NOT_READY`, the wallet charge given back, the connection `INVALID`; a second send fails before any Graph call.
    - `rateLimitIsRetryableForChatbot` — 429/130429 → the chatbot step is retried later as for `NoDeviceException`.
    - `laterFailedStatusRefunds` — charged send, then a signed `failed` status → wallet entry given back once (a second `failed` delivery gives nothing).
    - `cloudShapedApiAcceptsWhatsappProduct` — `POST /api/workspaces/{ws}/channel-accounts/{id}/messages` (`CloudMessageController`) with `messaging_product "whatsapp"` on the Cloud number → 200 with `messaging_product "whatsapp"`; `whatsapp-web` on it → 400 "channel not available"; and the reverse on a WhatsApp Web number.
    - `whatsAppWebNumberUnchanged` — a send on the Main-line-style test number still calls the device RPC (no Graph request recorded).

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='CloudSendIT,CloudApiClientTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement** per spec §5.4. The Cloud row is recorded through the existing `record` with `waMessageId = wamid`, `ack` 0, no echo; `afterSend`'s lid logic is skipped for Cloud.
- [ ] **Step 4: Run** the same command, then the inbox, chatbot and automation suites: `-Dtest='CloudSendIT,CloudApiClientTest,Inbox*IT,Chatbot*IT,Automation*IT,NewChat*IT,CloudSend*IT'`. Expected: PASS, no existing test edited.
- [ ] **Step 5: Commit** `feat(inbox): Cloud numbers send through Meta's API`.

### Task 7: Meta templates: import, fills, send

**Files:**
- Modify: `omnichannel-common/.../channel/whatsapp/message/CloudMessage.java` (component `Template template`, constant `TEMPLATE = "template"`, added to `TYPES`), create `.../message/Template.java` (`name`, `language {code}`, `components [{type, sub_type, index, parameters [{type, text, parameter_name, image|video|document {id}, payload, coupon_code}]}]`, Meta's names via snake_case), `CloudMessageValidator` (template only on product `whatsapp`; bridge → "channel not available").
- Create: `meta/template/CloudTemplate.java`, `CloudTemplateRepository.java`, `CloudTemplateService.java` (sync, applyChange, fills, `build(...)`), `CloudTemplateController.java`, `TemplateVariables.java` (derive variables + supported), `meta/api/CloudTemplates.java`, `meta/api/CloudTemplateInfo.java`, `meta/api/BuiltTemplate.java`.
- Modify: `InboxSendService` (`sendCloudTemplate(SendActor, String ws, WaWebChat, String cloudTemplateId, Map<String,String> values, String headerMediaId) -> CompletableFuture<MessageView>`), `NewChatService` (`cloudTemplate` first message), `app/inbox/InboxController` (`POST chats/{chatId}/cloud-templates`), `WaWebMessage` (`cloudTemplateId`, `TemplateSnapshot template` for the view), `MessageView` (`template`), `CloudWebhookIngest` (template changes → `CloudTemplates.applyChange`), `MetaConnectionService` (sync after create).
- Test: `meta/template/TemplateVariablesTest.java`; `omnichannel-app/.../app/meta/CloudTemplatesIT.java`.

**Interfaces:**
- Produces: `meta.api.CloudTemplates.forNumber(String ws, String phoneNumberId, boolean sendableOnly) -> List<CloudTemplateInfo>`, `get(String ws, String id) -> CloudTemplateInfo`, `build(String ws, String id, Map<String,String> values, String headerMetaMediaId) -> BuiltTemplate(Template template, String renderedBody, TemplateSnapshot snapshot)`, `markUsed(String id)`, `applyChange(CloudChange)`; `CloudTemplateInfo` mirrors `CloudTemplateView` of the contract.
- Fill resolution happens in the service module (it owns contacts): `CloudTemplateFills.resolve(CloudTemplateInfo t, WaWebChat chat, SendActor actor, Map<String,String> given) -> Map<String,String>` in `service/cloud/`, using the same contact-field / attribute / agent lookups `TemplateRenderer` uses for saved-message placeholders (call those lookups; do not route through `MessageTemplate`).

- [ ] **Step 1: Write the failing tests.**
  - `TemplateVariablesTest`: body `"Hi {{1}}, your {{2}}"` → keys `body.1`, `body.2` with Meta's examples; named format `"Hi {{first_name}}"` → `body.first_name`; header TEXT `"Hello {{1}}"` → `header.1`; URL button `"https://x.in/{{1}}"` at index 0 → `button.0`; header IMAGE → `header.format IMAGE`, no text variable; a CAROUSEL, FLOW, CATALOG, LIMITED_TIME_OFFER component or category AUTHENTICATION → `supported false` with the reason naming it.
  - `CloudTemplatesIT`:
    - `syncImportsAllPages` — stub `/222/message_templates` two pages (`paging.next`) → 3 rows; a second sync with one template gone → that row `status "DELETED"`.
    - `statusWebhookUpdatesRow` — signed `message_template_status_update` `{event:"REJECTED", message_template_id, reason}` → row `REJECTED`, `rejectedReason`; an unknown template id triggers a sync (stub called).
    - `onlyApprovedSupportedAreSendable` — `GET meta/templates?phoneNumberId=&sendable=true` lists APPROVED supported only; other brands' templates never listed.
    - `fillsPreFillFromContact` — fills `{body.1: {kind: CONTACT_FIELD, attributeKey: "name"}}`, contact "Veenu" → send with `values {}` sends `parameters[0].text "Veenu"`; a given value wins.
    - `missingValueIs400` — no fill and no value for `body.2` → 400 field `values.body.2`.
    - `sendTemplateFromInboxAfterWindow` — last inbound 3 days ago → `POST chats/{id}/cloud-templates` → stub body `type "template"`, `template.name`, `language.code`, components with values; row text = rendered body, `template` snapshot, `cloudTemplateId`; `lastUsedAt` set.
    - `headerImageTemplateNeedsFile` — header IMAGE without `headerMediaId` → 400 field `headerMediaId`; with a `cloudMedia` id → upload then `header` parameter `image.id`.
    - `newChatOnCloudIsTemplateOnly` — `POST inbox/phone-numbers/{id}/chats` with text → 409 `WINDOW_CLOSED`; with `cloudTemplate` → chat created and template sent, no number-lookup call.
    - `bridgeRefusesTemplateType` — Cloud-shaped API with `type "template"` on a WhatsApp Web number → 400 "channel not available".
    - `namedParametersCarryParameterName` — NAMED template → each body parameter has `parameter_name`.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='CloudTemplatesIT,TemplateVariablesTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement** per spec §5.6 and the contract.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(meta): import and send approved Meta templates`.

### Task 8: The 24-hour window

**Files:**
- Create: `service/inbox/ServiceWindow.java` (`static ChatWindow of(WaWebChat chat, Instant now)`; `record ChatWindow(boolean open, Instant closesAt)`; `Duration LENGTH = Duration.ofHours(24)`), `omnichannel-common/.../common/api/WindowClosedException.java` (409 `{code: "WINDOW_CLOSED", closesAt}`) + mapping.
- Modify: `InboxSendService` (`send`/`sendCloud` on a `CloudChannel` check the window before `charged`; `sendCloudTemplate` exempt; Meta `WINDOW_CLOSED` from Graph mapped to the same exception), `ChatView` (`window`, `channel`), `ChatbotSender` / the chatbot send node (a `WindowClosedException` takes the node's failure output with the reason, not a retry), `SendTemplateHandler` (records the reason on the rule run, no retry).
- Test: `service/inbox/ServiceWindowTest.java`; `omnichannel-app/.../app/meta/ServiceWindowIT.java`.

**Interfaces:**
- Produces: `ServiceWindow.of(chat, now)`; `ChatView.window()` null for WhatsApp Web chats.

- [ ] **Step 1: Write the failing tests.**
  - `ServiceWindowTest`: `lastInboundAt` 23 h 59 m ago → open, `closesAt = lastInboundAt + 24h`; exactly 24 h → closed; null → closed with `closesAt` null.
  - `ServiceWindowIT`:
    - `chatViewShowsWindowForCloudOnly`.
    - `freeTextAfterWindowRefusedBeforeCharge` — last inbound 25 h ago → `POST messages {text}` → 409 `WINDOW_CLOSED` with `closesAt`; no Graph call; no wallet entry.
    - `metaWindowErrorMapsToWindowClosed` — window open by our clock, stub answers 131047 → 409 `WINDOW_CLOSED`, charge given back.
    - `chatbotSendAfterWindowTakesFailure` — a chatbot waiting on a 25-hour timer, then a message node → the run takes the node's failure output, step records "24-hour window closed".
    - `ruleSavedMessageAfterWindowRecordsReason` — `SEND_TEMPLATE` (saved message) on a Cloud chat after the window → rule run step failed with the reason, not retried.
    - `templateAfterWindowAllowed` (covered by Task 7; assert again here through the inbox endpoint with the window closed).

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='ServiceWindowIT,ServiceWindowTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command plus `-Dtest='ServiceWindowIT,ServiceWindowTest,Chatbot*IT,Automation*IT'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(inbox): enforce and show the 24-hour window on Cloud numbers`.

### Task 9: Rule action "Send WhatsApp template"

**Files:**
- Modify: `db/.../automation/AutomationActionType.java` (`SEND_CLOUD_TEMPLATE`), `AutomationAction` (field `cloudTemplateId`), the automation validator (template exists in the brand and is sendable; values keys are the template's variables), automation options view (`cloudTemplates` of the contract).
- Create: `service/automation/SendCloudTemplateHandler.java` (as `SendTemplateHandler`: resolves the rule's variables in `values`, calls `InboxSendService.sendCloudTemplate` with `AutomationActor`; `NoDeviceException` → retry later; `WindowClosedException` cannot happen; a WhatsApp Web chat → step failed "channel not available").
- Test: `omnichannel-app/.../app/meta/SendCloudTemplateRuleIT.java`; rule hover summary text if the summary builder lists action types (`automationDisplay` server side, if present).

**Interfaces:**
- Consumes: `InboxSendService.sendCloudTemplate`, `CloudTemplates.get`.

- [ ] **Step 1: Write the failing tests:** `followUpRuleSendsTemplate` (a rule "2 days after message received, no reply" or the simplest existing trigger with a delay → the stub records a template send with the rule's variable resolved); `unknownTemplateRefusedOnSave` (400 field `actions[0].cloudTemplateId`); `wrongValueKeyRefusedOnSave`; `whatsAppWebChatFailsStep`; `optionsListSendableCloudTemplates`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=SendCloudTemplateRuleIT -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(automation): send WhatsApp template action`.

### Task 10: Docs

**Files:**
- Modify: `docs/superpowers/known-limits.md` (section "Cloud API numbers: known limits": no campaigns, no template editor, no Embedded Signup/coexistence, unsupported template kinds, chatbots send no templates, no number move, ngrok webhook address until deployment, media uploaded every send), `docs/superpowers/project-rules.md` §2 (how to walk a Cloud number on 8089 with Meta's test number; `/hooks/meta/<key>` needs the tunnel; re-run "Retry webhook setup" after a new tunnel).

- [ ] **Step 1:** Write both sections.
- [ ] **Step 2: Run** the full suite once: `./mvnw -q verify > <workspace>/verify.log 2>&1; tail -40 <workspace>/verify.log`. Expected: BUILD SUCCESS, 0 failures.
- [ ] **Step 3: Commit** `docs: Cloud API numbers limits and walk notes`.
