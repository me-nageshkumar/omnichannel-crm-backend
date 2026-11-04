# Click-to-WhatsApp ad leads — backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the ad context of a click-to-WhatsApp message, expose it in the inbox views, record it as the CRM origin `CTWA_AD`, and offer it as three Automations conditions.

**Architecture:** `MessagePayloads` parses `ctwaContext` into an `AdClick` stored on the message and, once, on the chat. The inbox views carry a trimmed `AdClickView`. The CRM sees the chat's ad through `crm.platform.PlatformChat.ad` (mapped in `app.crm`) and stores it in `Origin.ad`. Automations get `EventData.MessageData.ad` and three catalogue fields.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB; modules `omnichannel-db`, `omnichannel-service`, `omnichannel-crm`, `omnichannel-app`.

**Spec:** `docs/superpowers/specs/2026-11-04-ctwa-leads-design.md` (binding; every "my call" there is already decided).

## Global Constraints

- Work in `.worktrees/ctwa-leads` (branch `ctwa-leads`, off `main` 203a214). Tests: `./mvnw -q -pl omnichannel-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false` (`-am` required; unit tests in `omnichannel-service` run with `-pl omnichannel-service`). Full `./mvnw -q verify` once, in the last task.
- Module rule (`CrmModuleBoundaryTest`): `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the ad reaches it as `crm.platform.PlatformAdClick`, mapped in `omnichannel-app/.../app/crm/ChatDirectoryAdapter`. `omnichannel-service` never imports `omnichannel-crm`.
- Detection (spec §4.1): inbound only (`!MessagePayloads.fromMe(p)`), `ctwaContext` is an object, and at least one of `sourceUrl`, `sourceId`, `conversionSource` is non-blank. Text fields trimmed and cut at 500 characters. `headline` = `title`, else `headline`. `clickId` = `ctwaClid`, else `ctwa_clid`. `raw` = the object minus `thumbnail`, `thumbnailUrl`, `originalImageUrl`, `mediaUrl`, `conversionData`; dropped (null) when its JSON is over 4096 bytes.
- Chat: `adClick` set only when null (first ad), on live ingest and history import. Messages: set on insert only.
- No new collection, no migration; all new fields nullable. Collections camelCase.
- Summary wording (spec §4.5): `Message from <who> (from ad “<headline>”): <text>`; without a headline `(from an ad)`.
- Commits end with the trailer the session reminder names (`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` as of 2026-11-04).

## Plan calls (decisions the spec leaves to the builder)

- Plan call: `AdClick` lives at `omnichannel-db/.../db/inbox/AdClick.java`; the console-facing `AdClickView` at `omnichannel-service/.../service/inbox/AdClickView.java`.
- Plan call: `MessagePayloads.adClick(JsonNode p, Instant at)` takes the message timestamp so the record carries `at` without a second parse.
- Plan call: `AutomationCrmAdapter.addLead` loads the chat through `ChatDirectory.find(workspaceId, chatId)`; a missing chat means no ad (origin stays `INBOUND_CHAT`), not a failure.
- Plan call: `AutomationFields` puts `message.ad` as a boolean on every message event (false without an ad) so "Ad is no" matches ordinary messages; `message.ad.headline` and `message.ad.sourceId` are put only when present, so "is empty" works.

## Review Focus

1. An inbound message whose `ctwaContext` is present but empty (`{}`), or whose fields are all blank, must be an ordinary message (no `adClick`, `message.ad` false) — Task 1 `emptyContextIsNotAnAd`.
2. An outbound message with `ctwaContext` (the brand's own reply echoing context) must never mark the chat — Task 1 `outboundContextIsIgnored`.
3. A second ad message on an existing chat must keep the chat's first ad and still carry its own — Task 1 `chatKeepsItsFirstAd`.
4. Add to CRM on a chat with an ad must record `CTWA_AD` with the ad from both entry points, and a chat without one must stay `INBOUND_CHAT` — Task 3 `captureFromAdRecordsTheAdOrigin`, `plainCaptureStaysInboundChat`, `adChatGetsCtwaOrigin`.
5. A rule with `message.ad is true` must not match a message without an ad, and `message.ad.headline is empty` must match it — Task 4 `adConditionMatchesOnlyAdMessages`, `missingHeadlineIsEmpty`.

---

### Task 1: Parse and store the ad click

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/inbox/AdClick.java`
- Modify: `omnichannel-db/.../db/inbox/WaWebMessage.java` (field `AdClick adClick`), `omnichannel-db/.../db/inbox/WaWebChat.java` (field `AdClick adClick`), `omnichannel-service/.../service/inbox/MessagePayloads.java`, `omnichannel-service/.../service/inbox/InboxIngestService.java` (`storeMessage` ~274-300 and `newMessage`; `importMessage` ~180-235)
- Test: `omnichannel-service/src/test/.../service/inbox/MessagePayloadsTest.java` (append), create `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/AdClickIngestIT.java` (copy the setup and `message`/`create` helpers of `WaWebInboxListenerIT`)

**Interfaces:**
- Produces: `public record AdClick(String sourceId, String sourceUrl, String sourceApp, String headline, String description, String conversionSource, String clickId, Instant at, Map<String, Object> raw)`; `static AdClick MessagePayloads.adClick(JsonNode p, Instant at)` (null when not an ad); `WaWebMessage.getAdClick()`, `WaWebChat.getAdClick()`.

- [ ] **Step 1: Write the failing unit tests** in `MessagePayloadsTest`: `adClickReadsTheWaJsShape` (object with `sourceUrl "https://fb.me/x"`, `sourceId "1200"`, `sourceApp "facebook"`, `title "Diwali sale"`, `description "20% off"`, `conversionSource "FB_Ads"`, `thumbnail "AAAA"` → every field, `raw` has `sourceUrl` and not `thumbnail`); `clickIdAliases` (`ctwa_clid` read when `ctwaClid` absent); `headlineFallsBackToHeadline`; `emptyContextIsNotAnAd` (`{}` and all-blank → null); `outboundContextIsIgnored` (`fromMe true` → null); `longTextIsCut` (600-char title → 500); `oversizeRawIsDropped` (a 5 KB `description`… → `raw` null, other fields kept).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service test -Dtest=MessagePayloadsTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (no method `adClick`).
- [ ] **Step 3: Implement** `AdClick`, the two fields, `MessagePayloads.adClick`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Write the failing ITs** in `AdClickIngestIT`: `adMessageMarksMessageAndChat` (inbound payload with `ctwaContext` → stored message `adClick.headline`, chat `adClick.sourceId`, `at` = message time); `chatKeepsItsFirstAd` (second inbound ad with another `sourceId` → chat still the first, message has the second); `plainMessageHasNoAd`; `historyImportSetsTheChatAd` (through `InboxIngestService.importMessage` with an `ImportContext`, as the history ITs do) ; `outboundContextIsIgnored` (fromMe → chat `adClick` null).
- [ ] **Step 6: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=AdClickIngestIT -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 7: Implement** in `InboxIngestService`: `newMessage` sets `adClick`; both `storeMessage` and `importMessage` set `chat.setAdClick(message.getAdClick())` when the chat's is null and the message is inbound with one, before the chat save.
- [ ] **Step 8: Run** → PASS; also `-Dtest=WaWebInboxListenerIT,HistoryImportApiIT` → PASS.
- [ ] **Step 9: Commit** — `feat(inbox): keep the click-to-WhatsApp ad context of a message`

### Task 2: Inbox views carry the ad

**Files:**
- Create: `omnichannel-service/.../service/inbox/AdClickView.java`
- Modify: `omnichannel-service/.../service/inbox/MessageView.java` (last component `AdClickView adClick`), `ChatView.java` (last component `AdClickView adClick`), `InboxViews.java` (:52 and :73 builders); every `new MessageView(`/`new ChatView(` site the compiler names.
- Test: `AdClickIngestIT` (append)

**Interfaces:**
- Consumes: `AdClick` (Task 1).
- Produces: `public record AdClickView(String sourceId, String sourceUrl, String sourceApp, String headline, String description) { static AdClickView of(AdClick a) }` (null for null); JSON `adClick` on messages and chats.

- [ ] **Step 1: Write the failing tests**: `threadShowsTheAd` (`GET /api/workspaces/{ws}/inbox/chats/{id}/thread` → the message item has `adClick.headline`, and no `raw`/`clickId` keys); `chatListShowsTheAd` (the chats listing → `adClick.sourceId` on the chat).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (also `-Dtest=InboxApiIT*` or the inbox ITs the compiler change touched).
- [ ] **Step 5: Commit** — `feat(inbox): ad context on message and chat views`

### Task 3: CRM origin `CTWA_AD`

**Files:**
- Create: `omnichannel-crm/.../crm/platform/PlatformAdClick.java`, `omnichannel-crm/.../crm/contact/AdOrigin.java`
- Modify: `omnichannel-crm/.../crm/platform/PlatformChat.java` (last component `PlatformAdClick ad`), `omnichannel-app/.../app/crm/ChatDirectoryAdapter.java:29-32`, `omnichannel-crm/.../crm/contact/Origin.java` (last component `AdOrigin ad`), `CaptureService.java:88` (+ `:104`, `:123` pass `null`), `omnichannel-crm/.../crm/csv/CsvImportService.java:208` (`null`), `omnichannel-app/.../app/crm/AutomationCrmAdapter.java:69` (needs a `ChatDirectory` dependency), `omnichannel-crm/.../crm/contact/dto/CrmContactView.java:17` (`OriginView` gains `AdOriginView ad`), `CrmContactService.java:276`.
- Test: `omnichannel-app/src/test/.../app/crm/CaptureApiIT.java` (append), `omnichannel-app/src/test/.../app/automation/AddToCrmActionIT.java` (append); `CrmModuleBoundaryTest` unchanged and green.

**Interfaces:**
- Produces: `record PlatformAdClick(String sourceId, String sourceUrl, String sourceApp, String headline, String clickId)`; `record AdOrigin(String sourceId, String sourceUrl, String sourceApp, String headline, String clickId)`; `Origin(OriginKind kind, String phoneNumberId, String chatId, Instant firstMessageAt, String capturedById, AdOrigin ad)`; `CrmContactView.OriginView(…, AdOriginView ad)` with `record AdOriginView(String sourceId, String sourceUrl, String headline)`.

- [ ] **Step 1: Write the failing tests**: `CaptureApiIT.captureFromAdRecordsTheAdOrigin` (a chat whose `WaWebChat.adClick` is set directly → capture → `origin.kind CTWA_AD`, `origin.ad.headline`, `origin.ad.sourceUrl`); `CaptureApiIT.plainCaptureStaysInboundChat`; `AddToCrmActionIT.adChatGetsCtwaOrigin` (the rule's Add to CRM on an ad chat → CRM contact origin `CTWA_AD` with `ad.sourceId`).
- [ ] **Step 2: Run** `-Dtest=CaptureApiIT,AddToCrmActionIT` → FAIL.
- [ ] **Step 3: Implement**: kind = `chat.ad() != null ? CTWA_AD : INBOUND_CHAT` in `capture`; the adapter resolves the chat via `ChatDirectory.find(r.workspaceId(), r.chatId())` and applies the same rule; view mapping.
- [ ] **Step 4: Run** → PASS; `-Dtest=CrmModuleBoundaryTest,CrmContactsApiIT,CsvImport*IT` → PASS.
- [ ] **Step 5: Commit** — `feat(crm): click-to-WhatsApp ad origin on capture`

### Task 4: Automation conditions on the ad

**Files:**
- Modify: `omnichannel-db/.../db/automation/EventData.java:19` (`MessageData(String id, String text, String type, AdData ad)`, `record AdData(String sourceId, String sourceUrl, String headline)`), `omnichannel-service/.../service/automation/AutomationEventWriter.java:72`, `ConditionCatalog.java` (MESSAGE map + 3 entries), `AutomationFields.java` (after `message.type`), `EventSummaries.java:32`; constructor sites `ConditionEvaluatorTest.java:142`, `EventSummariesTest.java:12`, `AutomationHistoryApiIT.java:137` (pass `null`).
- Test: `ConditionEvaluatorTest` (append), `RuleShapeValidatorTest` (append), `EventSummariesTest` (append), `MessageEventIT` (append), `AutomationRulesApiIT` (append one catalogue case if it has a fields test; else skip).

**Interfaces:**
- Produces catalogue keys `message.ad` (BOOLEAN, `IS`), `message.ad.headline` (TEXT, `TEXT_OPS`), `message.ad.sourceId` (TEXT, `TEXT_OPS`); values map: `message.ad` always a boolean on message events; the two texts only when non-null. The console plan consumes these keys and the `ad` object under `event.data.message`.

- [ ] **Step 1: Write the failing tests**: `ConditionEvaluatorTest.adConditionMatchesOnlyAdMessages` (`message.ad is true` matches a message with `AdData`, not one without) and `missingHeadlineIsEmpty` (`message.ad.headline is_empty` matches the plain message; `contains "sale"` matches the ad one); `RuleShapeValidatorTest.adFieldsAcceptTheirOperators` (`message.ad` with `contains` → refused, with `is` → accepted; `message.ad.sourceId` with `greater_than` → refused, with `is` → accepted); `EventSummariesTest.adMessageNamesTheAd` (→ `Message from Asha (from ad “Diwali sale”): Hi`) and `adWithoutHeadline` (`(from an ad)`); `MessageEventIT.adMessageCarriesTheAdInTheEvent` (ingest an ad payload with a published rule → queued event `data.message.ad.sourceId`; a published rule with condition `message.ad is true` runs for it and not for a plain message — assert via the runs as `MessageEventIT` does).
- [ ] **Step 2: Run** `-pl omnichannel-service -Dtest=ConditionEvaluatorTest,RuleShapeValidatorTest,EventSummariesTest` and `-pl omnichannel-app -am -Dtest=MessageEventIT` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS; `-Dtest=Automation*IT,*ActionIT,DealEventIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): conditions on the click-to-WhatsApp ad`

### Task 5: Docs and full verify

**Files:**
- Modify: `README.md` (Automations section: the three ad conditions, one sentence), `docs/superpowers/crm-pending.md` (the lead-conversion item: CTWA done, remaining sources listed), `docs/superpowers/crm-rulings.md` (one line per plan call above).

- [ ] **Step 1: Edit the three docs.**
- [ ] **Step 2: Run** `./mvnw -q verify` → all green (about 5 minutes, 4 forks).
- [ ] **Step 3: Commit** — `docs: click-to-WhatsApp ad leads`
