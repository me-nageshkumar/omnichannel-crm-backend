# Campaigns on Cloud API numbers — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A campaign can send one imported Meta template from Cloud API numbers, as fast as Meta allows inside the
sending hours, while WhatsApp Web campaigns behave exactly as today.

**Architecture:** `Campaign` gains `channel` and `cloudTemplate`. `CampaignRunner` keeps the shared tick (claim,
release, skips, stuck rows, completion, counts) and hands each sender to a sealed `CampaignChannel`:
`WebCampaignChannel` is today's per-sender code moved unchanged, `CloudCampaignChannel` is new. Cloud limits and
holds live in `CloudCampaignSenders`.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), MongoDB (`MongoTemplate`), JUnit 5 +
AssertJ, `@SpringBootTest` ITs with `GraphStub`.

**Spec:** `docs/superpowers/specs/2026-11-21-cloud-campaigns-design.md` (read it first; section numbers below refer
to it).

## Global Constraints

- Anti-goal: WhatsApp Web campaigns change in no way. Every existing test under
  `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/campaign/` passes **unchanged** (no edits to those files).
- A recipient is never messaged twice: an unknown outcome (`CloudSendException.Kind.UNKNOWN`) is FAILED
  `OUTCOME_UNKNOWN` and never retried.
- The brand never pays for a refused message: all Cloud sends go through `InboxSendService.sendCloudTemplate` (its
  `charged(...)` refunds refusals; later `failed` statuses refund through the existing `UsageCharger.stamp`).
- Every stop shows a reason: pause reasons `TEMPLATE_UNAVAILABLE`, `CLOUD_QUALITY_LOW`, `CLOUD_AUTH` (plus existing
  `SEND_REFUSED`, `WALLET_EMPTY`); recipient errors `MISSING_VALUE`, `OUTCOME_UNKNOWN`, or Meta's `"<code> <title>"`.
- `omnichannel-service` imports only `me.nageshk.omnichannel.meta.api.*` from the meta module (boundary test).
- Mongo collections camelCase; no new collection in this plan. No new `Template*` class names except for Meta
  templates (`CampaignCloudTemplate` is one).
- Test first; while fixing run only the tests that matter; the full `./mvnw -q verify` once before merge.
- Test commands run from the worktree root with `-am`:
  `./mvnw -q -pl omnichannel-app -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A Cloud campaign whose number belongs to a WABA that also sends inbox follow-ups: the 24 h count must include
   those, or the campaign overshoots Meta's limit (Task 1 test `usedCountsInboxTemplatesOfTheWaba`).
2. A Cloud recipient whose chat is closed or assigned: the chat is reopened and keeps its agent, exactly as on
   WhatsApp Web (Task 4 test `closedChatIsReopenedAndKeepsItsAgent`).
3. Pause or terminate while a Cloud number is mid-loop: no send after the campaign stopped running (Task 5 test
   `pausedMidLoopSendsNoMore`).
4. A Cloud campaign started at 17:59 with the window closing at 18:00: nothing sends after the end time (Task 5 test
   `windowClosingStopsTheLoop`).
5. Resume of a Cloud campaign whose template Meta paused: refused with the reason, not resumed into an immediate
   re-pause loop (Task 6 test `resumeRefusedWhileTemplateUnavailable`).

---

## Shared contract (the console plan consumes exactly this)

```jsonc
// CampaignRequest (create/update body) — new fields
{ "channel": "WHATSAPP_WEB" | "WHATSAPP_CLOUD_API",          // required on create; absent on update = keep
  "cloudTemplate": { "cloudTemplateId": "…", "headerMediaId": "…" | null } | null }
// CampaignView — new fields
{ "channel": "WHATSAPP_WEB" | "WHATSAPP_CLOUD_API",
  "cloudTemplate": { "cloudTemplateId", "name", "language", "status", "headerMediaId" } | null,
  "cloudSenders": CloudSenderView[] | null,   // set on single-campaign replies of Cloud campaigns; senders = [] then
  "pauseReason": … | "TEMPLATE_UNAVAILABLE" | "CLOUD_QUALITY_LOW" | "CLOUD_AUTH" }
// GET /api/workspaces/{ws}/campaigns/senders?channel=WHATSAPP_CLOUD_API  → CloudSenderView[]
// (no param or channel=WHATSAPP_WEB → today's SenderView[], unchanged)
CloudSenderView { "phoneNumberId", "number", "label", "wabaId", "canSend": bool,
  "qualityRating": "GREEN"|"YELLOW"|"RED"|null, "messagingLimitTier": "TIER_250"|…|null,
  "dailyLimit": int | null /* null = unlimited */, "usedLast24h": int, "heldUntil": ISO-instant | null }
// Recipient "error" values: "MISSING_VALUE" | "OUTCOME_UNKNOWN" | "<metaCode> <title>" (e.g. "131026 Message undeliverable")
```

---

### Task 1: Cloud limits and the Cloud senders list

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/campaign/CloudLimits.java`
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/campaign/CloudCampaignSenders.java`
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/campaign/CloudSenderView.java`
- Modify: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/campaign/CampaignSenderController.java` (GET gains
  `@RequestParam(defaultValue = "WHATSAPP_WEB") ConnectionType channel`)
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/campaign/CloudLimitsTest.java`
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignTestBase.java`,
  `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignSendersIT.java`

**Interfaces:**
- Produces: `CloudLimits.dailyLimit(String tier): Integer` (null = unlimited);
  `CloudCampaignSenders` (`@Component`, public):
  - `List<CloudSenderView> list(String ws)` — non-disabled Cloud numbers of the workspace, ordered by label.
  - `boolean canSend(String phoneNumberId)` — `CloudAccounts.find(id)` present and `state().canSend()`.
  - `Optional<String> wabaOf(String phoneNumberId)`.
  - `int usedLast24h(String wabaId, Instant now)`; `Integer dailyLimit(String wabaId)` (highest tier among the
    WABA's accounts; unknown tier → 250).
  - `boolean atLimit(String wabaId, Instant now)`.
  - `void holdWaba(String wabaId, Instant until)`, `void holdNumber(String phoneNumberId, Instant until)`,
    `Instant heldUntil(String phoneNumberId, Instant now)` (null when free; the later of its own and its WABA's hold).
- Produces (test base): `CloudCampaignTestBase extends MetaTestBase` with `connectionId`, `numberId` (Cloud number
  "333" in WABA "222", added in `@BeforeEach`), `@Autowired CampaignRunner runner`, and helpers
  `String syncTemplate(String json)` (returns the stored id), `String cloudCampaign(String name, String templateId,
  List<String> phones, Map<String,Object> placeholders, String... senderIds)` (filter audience, creates the contacts,
  returns the draft id), `void start(String id)`, `void tickNow()`, `List<CampaignRecipient> recipients(String id)`,
  `static String sent(String wamid)`; it removes `campaigns`, `campaignRecipients`, `campaignSenders`, `contacts`,
  `contactFilters` in `@BeforeEach` and sets the clock to `2026-11-03T05:30:00Z` (Tue 11:00 IST).

- [ ] **Step 1: Write the failing tests**

`CloudLimitsTest`: `tiers()` — `dailyLimit("TIER_50")==50`, `"TIER_250"→250`, `"TIER_1K"→1000`, `"TIER_2K"→2000`,
`"TIER_10K"→10000`, `"TIER_100K"→100000`, `"TIER_UNLIMITED"→null`, `null→250`, `"SOMETHING"→250`.

`CloudCampaignSendersIT`:
- `listsCloudNumbersWithLimit()` — `GET /campaigns/senders?channel=WHATSAPP_CLOUD_API` returns one row for `numberId`
  with `wabaId "222"`, `canSend true`, `qualityRating "GREEN"`, `messagingLimitTier "TIER_250"`, `dailyLimit 250`,
  `usedLast24h 0`, `heldUntil null`.
- `webListUnchanged()` — `GET /campaigns/senders` (no param) has no row for `numberId`.
- `usedCountsInboxTemplatesOfTheWaba()` — insert three OUTBOUND `WaWebMessage`s with `cloudTemplateId` set on
  `numberId`: two chats within the last 24 h (one chat twice), one chat 25 h ago, plus one with `failure` set → the
  row's `usedLast24h == 2`.
- `heldNumberShowsHeldUntil()` — `holdWaba("222", now+1h)` → `heldUntil == now+1h`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='CloudLimitsTest,CloudCampaignSendersIT' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile errors (classes missing).

- [ ] **Step 3: Implement**

`usedLast24h`: distinct `chatId` of `WaWebMessage` with `phoneNumberId ∈ numbers of the WABA`,
`direction OUTBOUND`, `cloudTemplateId != null`, `failure == null`, `timestamp >= now − 24h`. Holds are two
`ConcurrentHashMap<String, Instant>` (in memory, by design §5.3). The controller returns `List<?>`: the Cloud list
for `WHATSAPP_CLOUD_API`, today's `senders.list(ws)` otherwise.

- [ ] **Step 4: Run them to see them pass** (same command). Expected: PASS.

- [ ] **Step 5: Commit** — `feat(campaigns): Cloud limits and Cloud senders list`.

---

### Task 2: Campaign data, validation and views for Cloud

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/campaign/CampaignCloudTemplate.java`
  (`record CampaignCloudTemplate(String cloudTemplateId, String name, String language, String headerMediaId)`)
- Modify: `omnichannel-db/.../db/campaign/Campaign.java` (fields `ConnectionType channel`, `CampaignCloudTemplate
  cloudTemplate`; `getChannel()` returns `WHATSAPP_WEB` when null)
- Modify: `omnichannel-service/.../campaign/CampaignRequest.java` (add `ConnectionType channel`,
  `CloudTemplateChoice cloudTemplate` with `record CloudTemplateChoice(String cloudTemplateId, String headerMediaId)`)
- Modify: `CampaignValidator.java` (`Validated` gains `channel`, `cloudTemplate`), `CampaignService.java` (`apply`),
  `CampaignLifecycleService.requestOf`, `CampaignView.java`/`CampaignViews.java`, `CampaignMaterialiser.java`
  (`rec.setTemplateId` only when `templateIds` is non-empty), `CampaignEstimates` call site in `CampaignViews`
- Modify: `CampaignSenderService.java:93,225`, `CampaignValidator.java:246` only as far as the Web path needs
  (unchanged behaviour for Web)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignDraftIT.java`

**Interfaces:**
- Consumes: Task 1 `CloudCampaignSenders.canSend`, `wabaOf`, `dailyLimit`, `usedLast24h`; `CloudTemplates.get`.
- Produces: `Campaign.getChannel()`, `Campaign.getCloudTemplate()`; `CampaignValidator.Validated(…, ConnectionType
  channel, CampaignCloudTemplate cloudTemplate)`; `CampaignView` fields per the shared contract (`cloudSenders` =
  `CloudCampaignSenders.list(ws)` filtered to the campaign's `senderIds`, `senders` = `[]` for Cloud); estimate for Cloud:
  `perDay = dailyLimit(waba)` (unlimited → remaining), via the existing `CampaignEstimates.of(remaining, perDay, s,
  now)`.

- [ ] **Step 1: Write the failing tests** (`CloudCampaignDraftIT extends CloudCampaignTestBase`; templates from
  `CloudTemplatesIT`'s JSON shapes: `FOLLOW_UP` with body `{{1}}`, a media-header one, a `PENDING` one)
- `createsCloudDraft()` — POST `/campaigns` with `channel WHATSAPP_CLOUD_API`, `cloudTemplate{cloudTemplateId}`,
  placeholder `body:1` from `CONTACT_FIELD name` with fallback `"there"`, `senderIds [numberId]` → 201; the view has
  `channel`, `cloudTemplate.name "course_follow_up"`, `language "en"`, `status "APPROVED"`, `templateIds []`.
- `oldCampaignReadsAsWeb()` — a `campaigns` doc inserted without `channel` → GET shows `channel "WHATSAPP_WEB"`.
- `refusesMixedKinds()` — Cloud channel with a WhatsApp Web number in `senderIds` → 400 on `senderIds`.
- `refusesTwoWabas()` — a second connection/number in WABA "999" → 400 `senderIds` "one WhatsApp account".
- `refusesUnapprovedTemplate()` → 400 `cloudTemplate.cloudTemplateId`.
- `refusesUnmappedVariable()` — no mapping, template fill `AGENT` for `body:1` → 400 `placeholders.body:1`.
- `refusesMissingHeaderFile()` — media-header template, no `headerMediaId` → 400 `cloudTemplate.headerMediaId`.
- `refusesTemplateIdsOnCloud()` and `refusesCloudTemplateOnWeb()` → 400 on `templateIds` / `cloudTemplate`.
- `estimateUsesDailyLimit()` — started Cloud campaign of 300 phones on TIER_250 → `estimate.perDay 250`,
  `days 2`.
- `materialisedRecipientsHaveNoTemplateId()` — after start every recipient has `templateId == null`, status PENDING.

- [ ] **Step 2: Run to see them fail** — `-Dtest='CloudCampaignDraftIT'`. Expected: FAIL (unknown fields, 400s).

- [ ] **Step 3: Implement** the branches in `CampaignValidator.validate`: Web keeps today's groups exactly and adds
  "`cloudTemplate` must be null"; Cloud runs name, audience, then senders (all `canSend`, one WABA), template
  (`sendable`, same WABA), placeholders (one per `CloudTemplateInfo.Variable.key`, CSV/attribute/field sources
  checked as today; unmapped + fill `AGENT` or no fill → required), header. Field names as in the tests.

- [ ] **Step 4: Run** `-Dtest='CloudCampaignDraftIT,CampaignDraftIT,CampaignStartIT,CampaignReadIT'`. Expected: PASS.

- [ ] **Step 5: Commit** — `feat(campaigns): Cloud campaign data, validation and views`.

---

### Task 3: Move the WhatsApp Web sender code behind `CampaignChannel` (no behaviour change)

**Files:**
- Create: `omnichannel-service/.../campaign/CampaignChannel.java`, `CampaignClaim.java`, `RecipientClaims.java`,
  `WebCampaignChannel.java`
- Modify: `CampaignRunner.java` (keeps `tick`, `failStuck`, `skipUnusablePreferred`, `completeFinished`, `busy`,
  grouping by channel)

**Interfaces:**
- Produces:
  ```java
  record CampaignClaim(Campaign campaign, CampaignRecipient recipient, String senderId, CampaignSettings settings) {}
  sealed interface CampaignChannel permits WebCampaignChannel, CloudCampaignChannel {
    boolean eligible(String ws, String senderId, CampaignSettings s, Instant now);
    /** Works this sender for one tick: claims and handles recipients of {@code campaigns} (all of this channel). */
    void process(String ws, String senderId, List<Campaign> campaigns, CampaignSettings s);
  }
  ```
  `RecipientClaims` (package-private `@Component`): `CampaignClaim claim(String senderId, List<Campaign>,
  CampaignSettings)`, `void release(CampaignClaim)`, `boolean freeSkip(CampaignClaim, SkipReason)`,
  `void failed(CampaignClaim, String error)`, `boolean whileSending(CampaignClaim, Update)`,
  `void pause(String campaignId, String reason)`, `boolean isRunning(Campaign)`, `boolean enterPhone(CampaignClaim)` /
  `void leavePhone(CampaignClaim)` (the `phonesInFlight` set), `void succeeded(CampaignClaim, MessageView, Instant)`
  (the shared part of today's `succeeded`: SENT, chat/message/contact ids, `alreadyAcknowledged`, observer, CSV name).
- `CloudCampaignChannel` is declared in Task 5; until then the `permits` clause lists only `WebCampaignChannel`
  (Task 5 adds it).

- [ ] **Step 1: Baseline** — run the campaign ITs before touching code:
  `./mvnw -q -pl omnichannel-app -am test -Dtest='Campaign*IT,SenderEventsIT,SenderHealthIT,SegmentCampaignIT,FilterInUseIT' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS (record the count).
- [ ] **Step 2: Move** the Web per-sender code (`eligible`, `process`, `handle`, lookups, `send`, `notOnWhatsApp`,
  `lookupFailed`, `sendRefused`, `sendFailed`, pacing, `health.afterSend`) into `WebCampaignChannel` and the shared
  claim/outcome helpers into `RecipientClaims`, text unchanged apart from the `CampaignClaim` type and a
  `CampaignSender` looked up where Web needs it. `CampaignRunner.startSenders` picks the channel from the sender's
  campaigns (`Campaign.getChannel()`), calls `senders.ensure`/`refresh` only for Web, and keeps `busy` per sender.
- [ ] **Step 3: Run** the Step 1 command. Expected: PASS, same count; `git diff --stat` shows no file under
  `src/test` changed.
- [ ] **Step 4: Commit** — `refactor(campaigns): WhatsApp Web sending behind CampaignChannel`.

---

### Task 4: The Cloud campaign send in the inbox layer

**Files:**
- Modify: `omnichannel-service/.../inbox/NewChatService.java` (new `startCloudTemplateForCampaign`)
- Create: `omnichannel-service/.../inbox/CloudHeaderUploads.java`
- Modify: `omnichannel-service/.../inbox/InboxSendService.java` (`sendCloudTemplate`: for a `CampaignActor`, the
  header upload goes through `CloudHeaderUploads`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignSendIT.java`

**Interfaces:**
- Produces: `CompletableFuture<MessageView> startCloudTemplateForCampaign(CampaignActor actor, String ws,
  String phoneNumberId, String phone, CloudTemplateRequest req)` — chat resolution and marking exactly as
  `startForCampaign` (existing chat by phone or `phone + "@c.us"`; CLOSED → `assignment.reopen`; unassigned →
  `campaignId`; fresh chat OPEN with `campaignId`, contact by phone), then `inboxSendService.sendCloudTemplate(actor,
  ws, chat, req, freshChat ? phone : null)`.
- Produces: `CloudHeaderUploads` (`@Component`): `String metaMediaId(String phoneNumberId, String headerMediaId,
  Supplier<String> upload)` — caches per `(phoneNumberId, headerMediaId)` for **29 days** (inside Meta's 30), so an
  expired id is never sent.
- Ruling carried from the spec §5.3: the "expired media → upload again once" path is replaced by the 29-day cache;
  a restart only costs one more upload.

- [ ] **Step 1: Write the failing tests** (call the service bean directly with `new CampaignActor("c1", "Spring")`)
- `freshChatIsMarkedWithTheCampaign()` — phone with no chat → one `WaWebChat` on `numberId`, `remoteId
  "<phone>@c.us"`, status OPEN, `campaignId "c1"`, no assigned user; Graph got one `POST /333/messages` of type
  `template`.
- `closedChatIsReopenedAndKeepsItsAgent()` — existing CLOSED chat assigned to `agent` → after the send status OPEN,
  assigned user still `agent`, `campaignId` not set.
- `headerUploadedOncePerNumber()` — media-header template, two sends to two phones with the same `headerMediaId` →
  exactly one `POST /333/media` recorded; both message bodies carry the same media id.
- `blankValueRefusedBeforeCharge()` — values missing `body:1` → `ValidationException` on `values.body:1`, no
  `/messages` call, no usage entry.

- [ ] **Step 2: Run to see them fail** — `-Dtest='CloudCampaignSendIT'`. Expected: compile error.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `-Dtest='CloudCampaignSendIT,CloudTemplatesIT,CloudSendIT'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(campaigns): Cloud template send for campaigns`.

---

### Task 5: `CloudCampaignChannel` — sending, limits and outcomes

**Files:**
- Create: `omnichannel-service/.../campaign/CloudCampaignChannel.java` (add to `CampaignChannel`'s `permits`)
- Modify: `CampaignRunner.java` (route Cloud senders to it; Cloud senders never get `campaignSenders` rows)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignRunIT.java`

**Interfaces:**
- Consumes: Task 1 (`canSend`, `wabaOf`, `atLimit`, holds), Task 3 (`RecipientClaims`, `CampaignClaim`), Task 4
  (`startCloudTemplateForCampaign`), `PlaceholderValues.of(c, r, contact)`, `RecipientSkips`
  (`isDoNotContact`, `isRecentlyMessaged`), `WorkGate.allows(ws, WorkGate.CAMPAIGNS)`.
- Produces: `static final Duration TICK_BUDGET = Duration.ofSeconds(8)`; error constants `MISSING_VALUE`,
  `OUTCOME_UNKNOWN`.

Behaviour (spec §5.3–5.4): `eligible` = number not disabled, `canSend`, `workGate`, window open, `heldUntil == null`,
not `atLimit(waba)`. `process` loops claim → handle until no claim, not eligible any more (re-checked after each
send), campaign not running, window closed, or `TICK_BUDGET` used since the loop began. `handle`: do-not-contact and
recently-messaged free skips as Web; values from `PlaceholderValues` — a blank one → `failed(MISSING_VALUE)`; then
`whileSending(dispatchedAt)` and the send; outcomes:

| Result | Recipient | Other |
|---|---|---|
| accepted | `succeeded(...)` | — |
| `ValidationException` on `values.*` | FAILED `MISSING_VALUE` | — |
| `CloudSendException` REFUSED | FAILED `"<metaCode> <title>"` | — |
| RATE_LIMITED, `metaCode == 131048` or the title names the messaging limit | release | `holdWaba(waba, now+1h)`, stop loop |
| RATE_LIMITED, other | release | `holdNumber(id, now + retryAfter ?: 60 s)`, stop loop |
| TOKEN_REFUSED | release | pause `CLOUD_AUTH`, stop |
| `ValidationException` on `cloudTemplateId` (not sendable) | release | pause `TEMPLATE_UNAVAILABLE`, stop |
| `WalletEmptyException` | release | pause `WALLET_EMPTY` |
| `NoDeviceException`/`SubscriptionInactiveException` | release | stop |
| UNKNOWN or timeout | FAILED `OUTCOME_UNKNOWN` | never retried |
| any other `RuntimeException` | release | pause `SEND_REFUSED` (as Web) |

- [ ] **Step 1: Write the failing tests** (`CloudCampaignRunIT extends CloudCampaignTestBase`; enqueue
  `POST /333/messages` answers with `sent("wamid.N")`)
- `sendsEveryRecipientInOneTick()` — 5 phones → after one `tickNow()` all SENT, 5 `/messages` calls, `counts.sent 5`,
  no `campaignSenders` row for `numberId`.
- `stopsAtTheDailyLimitAndGoesOnWhenItRolls()` — tier TIER_50 (stub `GET /222` before adding the number), 60 phones
  → 50 SENT, 10 PENDING; advance clock 24 h + 1 min (still in window: Wed 11:01) → after a tick 60 SENT.
- `limitRefusalHoldsTheWaba()` — first send answered 400 `{"error":{"code":131048,…}}` → recipient PENDING,
  `heldUntil ≈ now+1h`, one `/messages` call in the tick.
- `rateLimitHoldsOnlyRetryAfter()` — 130429 with retry after → recipient PENDING; after clock + 61 s a tick sends it.
- `refusalFailsOnlyThatRecipient()` — first 400 code 131008 "Required parameter is missing" → that recipient FAILED
  `"131008 Required parameter is missing"`, the others SENT, campaign RUNNING, no usage charge kept for the first.
- `blankValueFailsRecipientNotCampaign()` — CSV audience, one row with empty `name` and no fallback → that recipient
  FAILED `MISSING_VALUE`, others SENT, campaign RUNNING.
- `unknownOutcomeIsNeverRetried()` — Graph stub delays past the client timeout once → FAILED `OUTCOME_UNKNOWN`; a
  later tick makes no second call to that phone.
- `pausedMidLoopSendsNoMore()` — `GRAPH.before("POST /333/messages", pause the campaign via API)` on the 2nd send →
  exactly 2 calls, the rest PENDING.
- `windowClosingStopsTheLoop()` — clock 17:59:59 IST, settings end 18:00, the hook advances the clock 2 s on the
  first send → 1 SENT, rest PENDING.
- `outsideWindowNothingSends()` — clock Sunday 11:00 IST → no `/messages` call.
- `webCampaignOnTheSameTickUnchanged()` — a Web campaign (as `CampaignRunnerIT` builds one) on the same tick still
  sends one recipient per tick.

- [ ] **Step 2: Run to see them fail** — `-Dtest='CloudCampaignRunIT'`. Expected: FAIL.
- [ ] **Step 3: Implement** per the table.
- [ ] **Step 4: Run** `-Dtest='CloudCampaignRunIT,CampaignRunnerIT,CampaignWalletIT,CampaignAdvancedTimeoutIT'`.
  Expected: PASS.
- [ ] **Step 5: Commit** — `feat(campaigns): send Cloud campaigns as fast as Meta allows`.

---

### Task 6: Pauses from Meta's side and Cloud resume

**Files:**
- Modify: `CloudCampaignChannel.java` (per-tick checks), `CampaignRunner.java` (call them for RUNNING Cloud
  campaigns before senders start), `CampaignLifecycleService.resume` (Cloud check)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignPausesIT.java`

**Interfaces:**
- Produces: `void CloudCampaignChannel.checkMeta(Campaign c)` — pauses (system, via `RecipientClaims.pause`) with
  `TEMPLATE_UNAVAILABLE` when `CloudTemplates.get(...).sendable()` is false or the template is gone, with
  `CLOUD_QUALITY_LOW` when any of its numbers' `qualityRating` is `RED`. Ruling (plan): checked on each tick instead
  of a webhook event, because the meta module publishes no such events; a campaign pauses within one tick (10 s).
- Produces: `CampaignLifecycleService.resume` for a Cloud campaign throws `CampaignErrors.state(...)`-style 409 with
  message "The WhatsApp template is not approved" / "A number's quality is red" / "A number cannot send" when the
  same checks fail; Web resume unchanged.

- [ ] **Step 1: Write the failing tests**
- `pausedTemplatePausesTheCampaign()` — RUNNING Cloud campaign; webhook `message_template_status_update` event
  `PAUSED` for the template → after a tick `status PAUSED`, `pausedBy SYSTEM`, `pauseReason TEMPLATE_UNAVAILABLE`,
  no `/messages` call.
- `redQualityPausesTheCampaign()` — `phone_number_quality_update` webhook to RED → paused `CLOUD_QUALITY_LOW`.
- `revokedTokenPausesWithCloudAuth()` — send answered 401 OAuth → recipient PENDING, paused `CLOUD_AUTH`.
- `resumeRefusedWhileTemplateUnavailable()` — paused as above → `POST /campaigns/{id}/resume` 409 with the template
  message; after a status update back to APPROVED, resume 200 and the next tick sends.
- `webResumeUnchanged()` — a paused Web campaign resumes as before (200) with no Cloud check.

- [ ] **Step 2: Run to see them fail** — `-Dtest='CloudCampaignPausesIT'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `-Dtest='CloudCampaignPausesIT,CampaignLifecycleIT'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(campaigns): Meta-side pauses and Cloud resume checks`.

---

### Task 7: Cloud statuses and replies on campaign recipients

**Files:**
- Modify: `omnichannel-service/.../campaign/CampaignMessageListener.java` (`acked`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudCampaignRepliesIT.java`

**Interfaces:**
- Consumes: the `MessageUpserted` events the Cloud webhook ingest already raises (verify in Step 2; if a status
  update raises none, raise it from `CloudStoredStatuses` the way WhatsApp Web acks do).
- Produces: a message with `failure != null` whose recipient's campaign is Cloud moves SENT/DELIVERED → FAILED with
  `error "<code> <title>"` and `counts` refreshed. Web recipients are never moved by this branch.

- [ ] **Step 1: Write the failing tests**
- `deliveredAndReadCount()` — statuses webhook `delivered` then `read` for `wamid.1` → recipient READ, counts.
- `failedStatusFailsTheRecipient()` — `failed` with errors `[{code:131026,title:"Message undeliverable"}]` → FAILED
  `"131026 Message undeliverable"`, `counts.sent` down by one, usage refunded (existing).
- `heldBackMarketingFails()` — code 131049 → FAILED `"131049 …"`; the number is not held (Task 1 `heldUntil` null).
- `replyCountsAndStopWordStops()` — inbound text "Interested" from the phone → `counts.replied 1`; inbound "STOP"
  (a stop word) → contact do-not-contact, as `CampaignRepliesIT` checks for Web.
- `quickReplyButtonCountsAsReply()` — inbound `button` message → replied.

- [ ] **Step 2: Run to see them fail** — `-Dtest='CloudCampaignRepliesIT'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `-Dtest='CloudCampaignRepliesIT,CampaignRepliesIT'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(campaigns): Cloud statuses and replies on recipients`.

---

### Task 8: Known limits and full verify

**Files:**
- Modify: `docs/superpowers/known-limits.md` (replace "No campaigns on Cloud numbers yet" with: one kind per campaign;
  sequential ~3/s per number; limit counted per WABA, portfolio sends invisible; holds in memory; one Meta template
  per campaign; header file shared by all recipients)

- [ ] **Step 1:** Edit the file.
- [ ] **Step 2:** Run the full suite in this worktree: `./mvnw -q verify > .superpowers/verify.log 2>&1; echo exit=$?`.
  Expected: `exit=0`.
- [ ] **Step 3: Commit** — `docs: Cloud campaign known limits`.
