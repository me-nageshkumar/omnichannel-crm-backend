# Campaigns (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Server-side campaigns: per-number sender policy (warm-up caps, pacing, working window, automatic pauses, fleet rules), campaign drafts to a saved filter or a CSV, materialisation, a runner that looks up and sends one first message per sender per tick through the inbox send path, ack/reply/stop-word tracking, estimates and the read/lifecycle API.

**Architecture:** New package `me.nageshk.omnichannel.campaign` holding four documents (`campaign_settings`, `campaign_senders`, `campaigns`, `campaign_recipients`) and their services. Policy arithmetic (age, caps, gaps, window, estimate) is pure static code with unit tests; everything stateful goes through field-level Mongo updates. The inbox send path learns a `SendActor` so a campaign sends with `userId = null`, `source = CAMPAIGN` and `"Campaign: <name>"`; the runner ticks on its own `campaignScheduler`, returns a future per tick so ITs drive it with `MutableClock` and `FakeDevice`.

**Tech Stack:** Spring Boot 4.1, Spring Data MongoDB (auto-index-creation, GridFS), Lombok, Jackson 3 (`tools.jackson.*`), JUnit 5 + `TestRestTemplate` + `FakeDevice` (RANDOM_PORT), flapdoodle embedded Mongo 7.0.12.

**Spec:** `docs/superpowers/specs/2026-10-21-campaigns-design.md` §5, §6, §8, §9, §10, §11, plus `doNotContact` (§5.7) and the 10-digit `defaultCountryCode` rule (§4.2) for campaign CSV rows. The import mapping (§4) and the console (§7) are other plans; this plan's Task 1 provides `Phones.withDefaultCountryCode` and Task 2 provides `CampaignSettingsService` (`defaultCountryCode`) and `api/CodedException` for the import plan to consume.

## Deviations from the spec

1. Link/type failures use §9's `TEMPLATE_HAS_LINK` / `TEMPLATE_NOT_TEXT` (body `{code, message, field}`), not §5.7's "400 `VALIDATION`"; unknown, archived or 0/6+ templates stay `VALIDATION` on `templateIds`.
2. Illegal transitions use §9's `CAMPAIGN_STATE` / `CAMPAIGN_NOT_EDITABLE` (409), not §6.2's "409 `CONFLICT`".
3. `skipReason` gains `INVALID_PHONE` (§6.2 skips invalid phones; §6.1's enum has no value for it).
4. The unique `(campaignId, phone)` index is partial on `phone` being a string; `NO_PHONE`/`INVALID_PHONE`/`DUPLICATE` rows store `phone = null` and the cell in `rawPhone` (else the duplicate row cannot be stored); the API shows `phone ?? rawPhone`.
5. Recipient `values` is `List<String>` aligned with `audience.csvHeaders`, not a Map: Mongo map keys cannot contain `.`, CSV headers may.
6. Index `(campaignId, status)` becomes `(campaignId, status, _id)` (serves the claim sort); `(chatId, sentAt)` is added.
7. Age 31+ with `lowReplyRate` applies both §5.2 rules literally: 75, then halved = 37 (ruled: keep).
8. CSV preview takes optional repeated `templateIds`; `suggested` = `{phone, placeholders: {<name>: <header>}}` (placeholder names need templates).

## Decisions where the spec is silent

- Do-not-contact endpoints: `PUT` and `DELETE /contacts/{id}/do-not-contact` (`contact:edit`), both 200 with the `ContactView`; marking keeps the first mark.
- Illegal sender actions: 409 `SENDER_STATE` "This number cannot be <rested|retired|reactivated|resumed> now." `REST` from WARMING/ACTIVE; `RETIRE` from any but RETIRED; `REACTIVATE` from RETIRED/RESTING/SUSPENDED; `RESUME` only while `pausedUntil > now`.
- `PUT /senders/{id}`: when `action` is null `runsOn` is set (blank clears); when `action` is set `runsOn` is ignored.
- Counts: `pending` = PENDING + SENDING; `sent`, `delivered`, `read` are cumulative (sent = SENT + DELIVERED + READ, delivered = DELIVERED + READ); recomputed by aggregation after every runner/listener transition and on `GET /{id}`.
- DO_NOT_CONTACT and RECENTLY_MESSAGED are checked again when a recipient is claimed (overlapping campaigns, manual marks); like a cached "not registered" these are free skips: no gap, no cap, up to 20 per sender per tick.
- Lookup failures and released claims count toward no cap; FAILED after 3 lookup failures has no `attemptedAt`.
- A FIXED text or fallback containing a link is `TEMPLATE_HAS_LINK` on `placeholders`; a rendered body containing a link (from a CSV cell or contact value) fails the recipient with `HAS_LINK` and sends nothing (anti-goal "links").
- `MAPPING_COLUMN_REUSED` applies when two placeholders map to the same CSV column; `phoneColumn` and `nameColumn` do not count (a placeholder may use either). A CSV campaign without `phoneColumn` is `PHONE_COLUMN_REQUIRED` on `audience.phoneColumn`.
- REACTIVATE and RESTING → WARMING also reset `sendingDays = 0`, `lastSendingDay = null` (age counts from `warmupStartedAt`).
- Logout = `change_state` with `UNPAIRED`/`UNPAIRED_IDLE` (§11): the extension forwards only `AppState change:state`; whatsapp-web.js's `disconnected "LOGOUT"` comes from Puppeteer navigation the extension does not have. `BridgeWebSocketHandler` publishes events only after the number is confirmed in that session, so a profile that never logged in cannot trigger it. A logout of a SUSPENDED sender is ignored.
- `GET /{id}` (and every endpoint returning one campaign) also returns `senders` (the campaign's sender views) for the per-sender table; the list returns `systemPaused`. `createdBy` is the username. Start re-runs the whole create validation.
- A campaign message's `MessageView.sentBy` is `{id: <campaignId>, name: "Campaign: <name>"}` (built from `campaignId`; `sentByUserId` stays null); `ActionView.by` becomes `{id: null, name}` when only a name is stored (today a null id hides the name).
- The list returns the same `CampaignView` as the detail with `estimate` and `senders` null; settings are one flat document with `version` (stale version → 409 `CONFLICT`); the console keeps its own copy of the defaults.

## Global Constraints

- Spring Boot 4.1; Spring Data Mongo with `auto-index-creation: true`; Jackson 3 (`tools.jackson.*`).
- Documents use Lombok `@Getter @Setter`; requests and views are records. No hand-written getters or setters.
- New code in `me.nageshk.omnichannel.campaign`, except: `contact` (Phones, do-not-contact), `inbox` (actor refactor), `template` (`renderForCampaign`), `access/Authority`, `api/CodedException`.
- Endpoints under `/api/workspaces/{workspaceId}/campaigns`, each with `@PreAuthorize("@access.has(#workspaceId, '<code>')")`.
- Authorities (BRAND): `campaign:view` (Customer Admin, Workspace Manager, Team Lead, Agent) "See campaigns, their recipients and the senders"; `campaign:manage` (CA, WM, TL) "Create, edit, start, pause, resume, terminate and delete draft campaigns"; `campaign:settings` (CA, WM) "Edit campaign settings, sender states and where each sender runs". `AuthorityCheckScannerIT` stays green.
- Error codes, verbatim (body `{code, message, field?}` via `CodedException`):

| code | status | field | message |
|---|---|---|---|
| `CAMPAIGN_NOT_EDITABLE` | 409 | – | `Only a draft campaign can be edited.` |
| `CAMPAIGN_STATE` | 409 | – | `This campaign cannot be <started\|paused\|resumed\|terminated> now.` |
| `TEMPLATE_HAS_LINK` | 400 | `templateIds` (or `placeholders`) | `Campaign messages cannot contain links.` |
| `TEMPLATE_NOT_TEXT` | 400 | `templateIds` | `Only text templates can be used in campaigns.` |
| `SENDER_NOT_AVAILABLE` | 400 | `senderIds` (body also has `number`, digits) | `+<number> cannot send campaigns right now.` |
| `PLACEHOLDER_UNMAPPED` | 400 | `placeholders` | `Every placeholder needs a value.` |
| `MAPPING_COLUMN_REUSED` | 400 | `placeholders` | `A CSV column can be used only once.` |
| `PHONE_COLUMN_REQUIRED` | 400 | `audience.phoneColumn` | `Choose the column that holds the phone number.` |
| `SENDER_STATE` | 409 | – | `This number cannot be <rested\|retired\|reactivated\|resumed> now.` |

- Settings defaults, verbatim: `timezone "Asia/Kolkata"`, `defaultCountryCode "91"`, `workingDays [MONDAY..SATURDAY]`, `startTime "10:00"`, `endTime "18:00"`, `gapMinSeconds 45`, `gapMaxSeconds 180`, `skipGapMinSeconds 15`, `skipGapMaxSeconds 45`, `hourlyCap 12`, `warmupCaps [{1,5},{4,10},{8,20},{15,35},{22,50}]`, `matureCap 75`, `healthyCap 100`, `restDays 7`, `recontactDays 7`, `stopWords ["stop","unsubscribe","remove me","do not message","don't message","no"]`. Fixed constants: ACTIVE from age 31; UNDELIVERED_RUN = last 5 sends ≥ 10 min old; LOW_REPLY_RATE = last 30 sends ≥ 24 h old, < 3 replied; two logouts within 7 days; lookup attempts 3; stuck SENDING = `omnichannel.rpc.timeout` + 2 min; free skips 20 per sender per tick; page 50; materialisation page 500.
- Time only through the `Clock` bean (`MutableClock` in tests). `JwtService` reads the same clock: mint tokens after moving the clock. Randomness only through `PacingRandom`.
- The tick runs on `ThreadPoolTaskScheduler` bean `campaignScheduler` (pool 2, prefix `campaign-`, daemon); `TemplateMediaConfig.taskScheduler` is untouched. Property `omnichannel.campaign.auto-tick` (default true; `false` in `src/test/resources/application-test.yml`), `omnichannel.campaign.tick` (default `10s`).
- Device RPC in ITs is faked with `FakeDevice` (RANDOM_PORT, `omnichannel.rpc.timeout=2s`) as in `NewChatIT`/`NumberLookupIT`.
- Writes to existing sender, campaign and recipient documents are field-level `MongoTemplate` updates, conditional on the expected status; a recipient update after a device call is conditional on `status = SENDING`.
- Spring event listeners in `campaign` catch and log every `RuntimeException`; they run on the ingest thread and must never abort it.
- Wording "Enabled/Disabled", never "block", in code, comments and messages.
- Per task: the named tests, then `./mvnw -q test` (whole suite, ~3 min) must exit 0.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- The device's `message_create` beating the `sendMessage` result of a campaign send: the chat must still get `campaignId`, the message `source = CAMPAIGN` and `"Campaign: <name>"`, the recipient SENT (Task 8 `campaignSendSurvivesTheEventComingFirst`).
- An ack that lands before the runner records `messageId` must not be lost (Task 9 `alreadyDeliveredResultMarksTheRecipientDelivered`).
- The same phone in two RUNNING campaigns, the second started before the first sent: one message within `recontactDays` (Task 9 `samePhoneInTwoRunningCampaignsIsMessagedOnce`).
- A device that goes away between lookup and send: the recipient returns to PENDING with no attempt, no cap cost, no FAILED (Task 9 `deviceGoneBeforeSendReleasesTheClaim`).
- One number reporting UNPAIRED repeatedly (page reloads on the QR screen) is one logout, not two (Task 6 `repeatedUnpairedIsOneLogout`).
- The stuck-SENDING check at application start runs from `CampaignTicker`, which tests disable (`auto-tick=false`); no test covers it. Accepted; review `CampaignTicker.atStartup` by hand (Task 9).

---

### Task 1: `Phones.withDefaultCountryCode`, `doNotContact` on contacts and in filters

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/contact/Phones.java`
- Create: `src/main/java/me/nageshk/omnichannel/contact/DoNotContact.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/Contact.java`, `ContactView.java`, `ContactFilterSpec.java`, `ContactQuery.java`, `ContactService.java`, `ContactController.java`
- Modify (constructor arity): `src/test/java/me/nageshk/omnichannel/contact/ContactImportExportIT.java:277`, `ContactBulkIT.java:98`, `ContactSearchIT.java:295`
- Test: `src/test/java/me/nageshk/omnichannel/contact/PhonesTest.java`, `ContactsApiIT.java`, `ContactSearchIT.java`

**Interfaces:**
- Produces:
  - `public static String withDefaultCountryCode(String digits, String countryCode)` in `Phones`: `countryCode + digits` when `digits` is exactly 10 digits and `countryCode` is non-blank; otherwise `digits` unchanged (null stays null). Callers normalise first, validate after.
  - `public record DoNotContact(Instant at, Reason reason, String campaignId) { public enum Reason { STOP_REPLY, MANUAL } }`; `Contact.doNotContact` (null = may be contacted).
  - `ContactView` gains last component `DoNotContact doNotContact`; `ContactFilterSpec` gains last component `Boolean doNotContact` (true = only marked, false = only unmarked, null = any).
  - `ContactService.markDoNotContact(String workspaceId, String id, DoNotContact.Reason reason, String campaignId) -> Contact` (keeps an existing mark, `at = clock.instant()`), `ContactService.clearDoNotContact(String workspaceId, String id) -> Contact`.
  - `PUT /api/workspaces/{ws}/contacts/{id}/do-not-contact` and `DELETE …/do-not-contact`, `contact:edit`, 200 `ContactView`.

- [ ] **Step 1: Write the failing tests**

`PhonesTest`:
```java
@Test void tenDigitNumberGetsTheCountryCode() { assertThat(Phones.withDefaultCountryCode("9812345678", "91")).isEqualTo("919812345678"); }
@Test void otherLengthsAreKept() {
    assertThat(Phones.withDefaultCountryCode("919812345678", "91")).isEqualTo("919812345678");
    assertThat(Phones.withDefaultCountryCode("14155550100", "91")).isEqualTo("14155550100");
    assertThat(Phones.withDefaultCountryCode("981234567", "91")).isEqualTo("981234567");
}
@Test void blankCountryCodeOrNullDigitsChangeNothing() {
    assertThat(Phones.withDefaultCountryCode("9812345678", "")).isEqualTo("9812345678");
    assertThat(Phones.withDefaultCountryCode(null, "91")).isNull();
}
```
`ContactsApiIT` (MockMvc, base-class tokens): `markAndClearDoNotContact` (PUT as `wmToken` → 200, `doNotContact.reason` `MANUAL`, `doNotContact.campaignId` null, `doNotContact.at` present; GET shows it; DELETE → 200 and `doNotContact` null), `markingTwiceKeepsTheFirstMark` (second PUT keeps the first `at`), `doNotContactNeedsContactEdit` (`agentToken` → 403), `doNotContactOfAnotherBrandIs404`.
`ContactSearchIT`: `filterByDoNotContact` — three contacts, one marked via the service; `{"filter":{"doNotContact":true}}` returns only it, `false` the other two, absent all three.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=PhonesTest,ContactsApiIT,ContactSearchIT`
Expected: compilation failure (`withDefaultCountryCode`, `DoNotContact` missing).

- [ ] **Step 3: Implement**

`ContactQuery.parts`: `doNotContact == TRUE → Criteria.where("doNotContact").ne(null)`, `FALSE → Criteria.where("doNotContact").is(null)`. The three test call sites append one `null`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=PhonesTest,ContactsApiIT,ContactSearchIT,ContactBulkIT,ContactImportExportIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact
git commit -m "feat(contact): do-not-contact mark and filter, 10-digit default country code helper"
```

---

### Task 2: Authorities, `CodedException`, campaign settings

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/access/Authority.java` (three entries after `INBOX_SETTINGS`)
- Modify: `src/test/java/me/nageshk/omnichannel/access/AuthorityTest.java:16`, `RoleSeederIT.java:111,130` (32 → 35)
- Create: `src/main/java/me/nageshk/omnichannel/api/CodedException.java`
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java`
- Create in `src/main/java/me/nageshk/omnichannel/campaign/`: `CampaignSettings.java`, `CampaignSettingsRepository.java`, `CampaignSettingsView.java`, `CampaignSettingsService.java`, `CampaignSettingsController.java`, `CampaignErrors.java`
- Test: `src/test/java/me/nageshk/omnichannel/api/ApiExceptionHandlerTest.java`, `src/test/java/me/nageshk/omnichannel/campaign/CampaignTestBase.java`, `CampaignSettingsIT.java`

**Interfaces:**
- Produces:
  - `Authority.CAMPAIGN_VIEW("campaign:view", …)`, `CAMPAIGN_MANAGE("campaign:manage", …)`, `CAMPAIGN_SETTINGS("campaign:settings", …)` with the Global Constraints descriptions and roles.
  - `public class CodedException extends RuntimeException { public CodedException(HttpStatus status, String code, String field, String message); public CodedException(HttpStatus status, String code, String field, String message, Map<String, String> extra); HttpStatus status(); String code(); String field(); Map<String, String> extra(); }` — handler returns `status` with `{code, message}` plus `field` when non-null plus every `extra` entry.
  - `final class CampaignErrors` static factories returning `CodedException` with the table's exact text: `notEditable()`, `state(String verb)` (`started|paused|resumed|terminated`), `templateHasLink(String field)`, `templateNotText()`, `senderNotAvailable(String number)` (message `"+" + number + " cannot send campaigns right now."`, `extra {number}`), `placeholderUnmapped()`, `columnReused()`, `phoneColumnRequired()`, `senderState(String verb)` (`rested|retired|reactivated|resumed`).
  - `@Document("campaign_settings") class CampaignSettings { @Id String id; @Indexed(unique = true) String workspaceId; String timezone; String defaultCountryCode; List<DayOfWeek> workingDays; String startTime; String endTime; int gapMinSeconds, gapMaxSeconds, skipGapMinSeconds, skipGapMaxSeconds, hourlyCap; List<WarmupStep> warmupCaps; int matureCap, healthyCap, restDays, recontactDays; List<String> stopWords; @LastModifiedDate Instant lastUpdateTime; @Version Long version; public record WarmupStep(int fromDay, int cap) {} }`.
  - `public record CampaignSettingsView(String timezone, String defaultCountryCode, List<DayOfWeek> workingDays, String startTime, String endTime, int gapMinSeconds, int gapMaxSeconds, int skipGapMinSeconds, int skipGapMaxSeconds, int hourlyCap, List<CampaignSettings.WarmupStep> warmupCaps, int matureCap, int healthyCap, int restDays, int recontactDays, List<String> stopWords, Long version) { static CampaignSettingsView of(CampaignSettings s); }`.
  - `@Service CampaignSettingsService { CampaignSettings get(String workspaceId); CampaignSettings update(String workspaceId, CampaignSettingsView body); /* body.version must equal the stored version, else OptimisticLockingFailureException → 409 CONFLICT */ static CampaignSettings defaults(String workspaceId); }` — `get` inserts defaults on first read (retry once on `DuplicateKeyException`).
  - `GET /campaigns/settings` and `PUT /campaigns/settings` (`campaign:settings`) → `CampaignSettingsView` (PUT takes and returns it).

PUT validation, each a `ValidationException(field, message)`, all fields collected into one map:

| field | rule | message |
|---|---|---|
| `timezone` | `ZoneId.of` succeeds | `is not a known time zone` |
| `defaultCountryCode` | `^[1-9][0-9]{0,2}$` | `must be 1 to 3 digits` |
| `workingDays` | non-empty | `needs at least one day` |
| `startTime`, `endTime` | `^([01][0-9]\|2[0-3]):[0-5][0-9]$` | `must be HH:mm` |
| `endTime` | after `startTime` | `must be after startTime` |
| each int field | ≥ 1 (`recontactDays` ≥ 0) | `must be at least 1` / `must be at least 0` |
| `gapMaxSeconds`, `skipGapMaxSeconds` | ≥ the min | `must be at least gapMinSeconds` / `… skipGapMinSeconds` |
| `warmupCaps` | non-empty, first `fromDay` 1, strictly increasing, every `fromDay` < 31, every `cap` ≥ 1 | `must start at day 1 and rise, below day 31, with caps of at least 1` |
| `stopWords` | 1–50 entries after trim + lower-case + dedupe, each 1–40 chars | `needs 1 to 50 words of at most 40 characters` |

`stopWords` are stored trimmed, lower-cased, de-duplicated.

- [ ] **Step 1: Authority and handler tests**

`AuthorityTest.codesAreUnique` and `RoleSeederIT` (two places) expect 35. `ApiExceptionHandlerTest`: `codedExceptionCarriesStatusCodeAndField` — `handler.coded(new CodedException(HttpStatus.BAD_REQUEST, "TEMPLATE_HAS_LINK", "templateIds", "Campaign messages cannot contain links."))` → 400, body `{code: TEMPLATE_HAS_LINK, message: …, field: templateIds}`; with a null field the body has no `field` key; `CampaignErrors.senderNotAvailable("919800000001")` → body also has `number: "919800000001"`.

- [ ] **Step 2: Write `CampaignTestBase` and the failing `CampaignSettingsIT`**

`CampaignTestBase` (package-private abstract, `campaign` test package):
```java
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
@Import(CampaignTestBase.Fixtures.class)
abstract class CampaignTestBase {
    /** Wednesday 2026-10-21, 11:00 in Asia/Kolkata. */
    static final Instant WED_11_IST = Instant.parse("2026-10-21T05:30:00Z");
    @TestConfiguration static class Fixtures { @Bean @Primary MutableClock testClock() { return new MutableClock(WED_11_IST); } }
    // fixtures: Customer acme; Workspace brand, other; PhoneNumber n1, n2 (brand), otherNumber (other);
    // User manager (WORKSPACE_MANAGER), lead (TEAM_LEAD), agent (AGENT), all in brand
    // String token(User u) mints a fresh token (the clock moves); String url(String path); HttpEntity<Object> auth(User who, Object body)
    // ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body)
    // path is relative to "/api/workspaces/" + brand.getId(); clean(): testUsers.deleteAll(), mongo.remove(new Query(), c) for every class in collections(), clock.set(WED_11_IST)
}
```
Add `public synchronized void set(Instant at)` to `src/test/java/me/nageshk/omnichannel/support/MutableClock.java`. `collections()` is a `List<Class<?>>` later tasks extend; here `CampaignSettings.class`.

`CampaignSettingsIT`:
```java
@Test void firstReadCreatesTheDefaults() {
    JsonNode body = api(GET, "/campaigns/settings", manager, null).getBody();
    assertThat(body.get("timezone").asString()).isEqualTo("Asia/Kolkata");
    assertThat(body.get("startTime").asString()).isEqualTo("10:00");
    assertThat(body.get("workingDays")).hasSize(6);
    assertThat(body.at("/warmupCaps/4/cap").asInt()).isEqualTo(50);
    assertThat(body.get("stopWords").toString()).contains("don't message");
    assertThat(body.get("version").isNumber()).isTrue();
    assertThat(settingsRepo.count()).isEqualTo(1);
}
@Test void putStoresAndReturnsTheSettings()              // GET, change hourlyCap 20, stopWords [" Nahi ","nahi","STOP"] → returns ["nahi","stop"], version moved; replaying the old version → 409 CONFLICT
@Test void putRejectsBadValuesFieldByField()             // timezone "Mars/Base", endTime "09:00", gapMaxSeconds 10, warmupCaps [{2,5}] → 400 VALIDATION, fields has exactly these four keys
@Test void settingsNeedCampaignSettings()                // lead GET → 403, agent PUT → 403
@Test void settingsAreScopedToTheWorkspace()             // PUT in brand, GET in other → defaults
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=AuthorityTest,RoleSeederIT,ApiExceptionHandlerTest,CampaignSettingsIT,AuthorityCheckScannerIT`
Expected: compilation failure.

- [ ] **Step 4: Implement** as under Interfaces.

- [ ] **Step 5: Run to verify it passes**

Run the Step 3 command → PASS; then `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/access/Authority.java src/main/java/me/nageshk/omnichannel/api src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/access src/test/java/me/nageshk/omnichannel/api src/test/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/support/MutableClock.java
git commit -m "feat(campaign): campaign authorities, coded errors and per-workspace campaign settings"
```

---

### Task 3: Senders — documents, policy arithmetic, `GET/PUT /senders`

**Files:**
- Create in `campaign/`: `SenderState.java` (`WARMING, ACTIVE, RESTING, SUSPENDED, RETIRED`), `SenderAction.java` (`REST, RETIRE, REACTIVATE, RESUME`), `CampaignSender.java`, `CampaignSenderRepository.java`, `RecipientStatus.java`, `SkipReason.java`, `CampaignRecipient.java`, `CampaignRecipientRepository.java`, `SenderPolicy.java`, `PacingRandom.java`, `CampaignSenderService.java`, `SenderView.java`, `SenderUpdate.java`, `CampaignSenderController.java`
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxDevices.java` (class `public`, add `isReady`)
- Test: `campaign/SenderPolicyTest.java` (unit), `campaign/CampaignSenderIT.java`, `campaign/CampaignIndexesIT.java`, `campaign/FixedRandom.java`, modify `CampaignTestBase`

**Interfaces:**
- Consumes: Task 2 `CampaignSettingsService.get`, `CampaignErrors.senderState`.
- Produces:
  - `@Document("campaign_senders") class CampaignSender { @Id String id; @Indexed(unique = true) String phoneNumberId; @Indexed String workspaceId; SenderState state; Instant warmupStartedAt; int sendingDays; LocalDate lastSendingDay; Instant restUntil; String runsOn; Instant nextSendAt; Instant pausedUntil; String pauseReason; Instant halfPaceUntil; boolean lowReplyRate; int dayStartOffsetMinutes; LocalDate dayStartFor; Instant logoutAt; Instant lastSendAt; @CreatedDate Instant creationTime; @Version Long version; }`.
  - `enum RecipientStatus { PENDING, SENDING, SENT, DELIVERED, READ, FAILED, SKIPPED }`; `enum SkipReason { NOT_ON_WHATSAPP, DO_NOT_CONTACT, RECENTLY_MESSAGED, NO_PHONE, INVALID_PHONE, DUPLICATE, CHAT_ON_OTHER_NUMBER, CANCELLED }`.
  - `@Document("campaign_recipients") class CampaignRecipient { @Id String id; String campaignId; String workspaceId; String phone; String rawPhone; String contactId; String templateId; List<String> values; RecipientStatus status; SkipReason skipReason; String preferredSenderId; String senderId; String chatId; String messageId; Instant attemptedAt, sentAt, deliveredAt, readAt, repliedAt; String error; int attempts; }` with `@CompoundIndexes`: `campaign_phone` `{campaignId:1, phone:1}` unique, `partialFilter = "{'phone': {$type: 'string'}}"`; `campaign_status` `{campaignId:1, status:1, _id:1}`; `ws_phone_sent` `{workspaceId:1, phone:1, sentAt:1}`; `sender_sent` `{senderId:1, sentAt:1}`; `sender_attempted` `{senderId:1, attemptedAt:1}`; `message` `{messageId:1}`; `chat_sent` `{chatId:1, sentAt:-1}`.
  - `final class SenderPolicy` (pure, all `static`):
    - `int ageDay(int sendingDays, LocalDate lastSendingDay, LocalDate today)` = `sendingDays - (today.equals(lastSendingDay) ? 1 : 0) + 1`.
    - `int cap(int ageDay, boolean lowReplyRate, CampaignSettings s)`: the last `warmupCaps` step with `fromDay <= ageDay` below 31; from 31 `lowReplyRate ? matureCap : healthyCap`; then `lowReplyRate ? max(1, base / 2) : base` (Deviation 7).
    - `int gapSeconds(boolean skip, boolean halfPace, CampaignSettings s, PacingRandom r)`: `r.seconds(min, max)` over the gap or skip-gap bounds, both doubled when `halfPace`.
    - `ZoneId zone(CampaignSettings s)`, `LocalDate today(CampaignSettings s, Instant now)`, `Instant endOfDay(CampaignSettings s, Instant now)` (next local midnight), `Instant startOfDay(CampaignSettings s, Instant now)`.
    - `boolean inWindow(CampaignSettings s, Instant now)`: local day in `workingDays` and `startTime <= local time < endTime`.
    - `Instant dayStartsAt(CampaignSettings s, LocalDate day, int offsetMinutes)`: `day` at `startTime + offsetMinutes` in the zone.
  - `@Component public class PacingRandom { public int dayStartOffsetMinutes() /* uniform 0..60 */; public int seconds(int minInclusive, int maxInclusive); }` (ThreadLocalRandom).
  - `@Service CampaignSenderService`:
    - `CampaignSender ensure(String workspaceId, String phoneNumberId)` — insert `{state WARMING, sendingDays 0}` if absent (retry on duplicate key).
    - `CampaignSender refresh(CampaignSender s, CampaignSettings settings, Instant now)` — RESTING with `restUntil <= now` → WARMING, `warmupStartedAt/restUntil/lastSendingDay = null`, `sendingDays = 0`; WARMING with `ageDay >= 31` → ACTIVE; `dayStartFor != today` → `dayStartOffsetMinutes = random.dayStartOffsetMinutes()`, `dayStartFor = today` (Task 10 adds the reply-rate recalculation here). Persists only changed fields; returns the updated document.
    - `int capToday(CampaignSender s, CampaignSettings settings, Instant now)`, `long usedToday(String phoneNumberId, CampaignSettings settings, Instant now)` (status in SENDING, SENT, DELIVERED, READ, FAILED and `attemptedAt >= startOfDay`), `long hourlyUsed(String phoneNumberId, Instant now)` (any status, `attemptedAt > now - 60 min`).
    - `List<SenderView> list(String workspaceId)` — every number of the workspace with `disabled == null` and `connectionType != WHATSAPP_CLOUD_API` (null counts as WhatsApp Web), ensured and refreshed, ordered by number.
    - `List<SenderView> views(String workspaceId, List<String> phoneNumberIds)` (same, restricted; Task 12).
    - `SenderView update(String workspaceId, String phoneNumberId, SenderUpdate req)` — 404 for a number not in the list above; actions per "Decisions" (`REST` sets `restUntil = now + restDays`; `REACTIVATE` resets as RESTING → WARMING does; `RESUME` clears `pausedUntil` and `pauseReason`); `runsOn` trimmed, blank → null, > 100 chars → `VALIDATION runsOn "must be at most 100 characters"`.
    - `void paced(String phoneNumberId, Instant nextSendAt)`; `void sendAttempted(String phoneNumberId, LocalDate today, Instant now)` (increments `sendingDays` and sets `lastSendingDay` only when `lastSendingDay != today`; sets `warmupStartedAt` when null; sets `lastSendAt`).
  - `public record SenderView(String phoneNumberId, String number, String label, SenderState state, int ageDay, int capToday, long usedToday, long hourlyUsed, PhoneNumberStatus.Status phoneStatus, boolean ready, Instant pausedUntil, String pauseReason, Instant halfPaceUntil, boolean lowReplyRate, Instant restUntil, String runsOn, Instant logoutAt, Instant lastSendAt, Instant nextSendAt, Instant warmupStartedAt)` — `pausedUntil`/`pauseReason` null unless `pausedUntil > now`; `phoneStatus` from `PhoneNumberService.views`; `ready` from `InboxDevices.isReady`.
  - `public record SenderUpdate(String runsOn, SenderAction action)`.
  - `GET /campaigns/senders` (`campaign:view`) → `List<SenderView>`; `PUT /campaigns/senders/{phoneNumberId}` (`campaign:settings`) → `SenderView`.
  - `InboxDevices`: `public class`; `public boolean isReady(String workspaceId, String phoneNumberId)` = `requireReady` succeeded (false on `NoDeviceException`/`NotFoundException`).

- [ ] **Step 1: Write the failing unit test `SenderPolicyTest`** (settings = `CampaignSettingsService.defaults("ws")`):
```java
@Test void ageCountsSendingDaysNotCalendarDays() {
    LocalDate wed = LocalDate.of(2026, 10, 21);
    assertThat(SenderPolicy.ageDay(0, null, wed)).isEqualTo(1);
    assertThat(SenderPolicy.ageDay(1, wed, wed)).isEqualTo(1);
    assertThat(SenderPolicy.ageDay(1, wed.minusDays(9), wed)).isEqualTo(2);
}
@Test void capFollowsTheWarmupTable() {   // age → cap
    Map.of(1, 5, 3, 5, 4, 10, 7, 10, 8, 20, 14, 20, 15, 35, 21, 35, 22, 50, 30, 50, 31, 100)
       .forEach((age, cap) -> assertThat(SenderPolicy.cap(age, false, s)).as("age %d", age).isEqualTo(cap));
}
@Test void lowReplyRateHalves() { assertThat(SenderPolicy.cap(1, true, s)).isEqualTo(2); assertThat(SenderPolicy.cap(22, true, s)).isEqualTo(25); assertThat(SenderPolicy.cap(31, true, s)).isEqualTo(37); }
@Test void gapsUseTheBoundsAndHalfPaceDoublesThem()  // FixedRandom returning min: 45, skip 15, halfPace 90 / 30; returning max: 180, 360
@Test void windowIsWorkingDaysAndHoursInTheZone() {
    assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T04:29:00Z"))).isFalse(); // 09:59 IST
    assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T04:30:00Z"))).isTrue();  // 10:00 IST
    assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T12:30:00Z"))).isFalse(); // 18:00 IST
    assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-25T05:30:00Z"))).isFalse(); // Sunday
}
@Test void dayBoundariesAreLocal() {
    assertThat(SenderPolicy.today(s, Instant.parse("2026-10-21T19:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(SenderPolicy.endOfDay(s, WED_11_IST)).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
    assertThat(SenderPolicy.dayStartsAt(s, LocalDate.of(2026, 10, 21), 45)).isEqualTo(Instant.parse("2026-10-21T05:15:00Z"));
}
```
`FixedRandom extends PacingRandom` (test source, `campaign` package): fields `int offset = 0; boolean max = false;` returning `offset` / `max ? maxInclusive : minInclusive`.

- [ ] **Step 2: Write the failing `CampaignSenderIT` and `CampaignIndexesIT`**

`CampaignTestBase.Fixtures` gains `@Bean @Primary FixedRandom fixedRandom()`; `@Autowired FixedRandom random` reset in `clean()`; `collections()` adds `CampaignSender`, `CampaignRecipient`; helpers `CampaignSender sender(PhoneNumber n)` (ensure, then load) and `void sender(PhoneNumber n, Consumer<CampaignSender> change)` (load, change, save).

`CampaignSenderIT`:
- `listShowsEveryEnabledWebNumberAsWarming` — n1, n2 listed as WARMING, `ageDay 1`, `capToday 5`, `usedToday 0`, `phoneStatus NOT_CONNECTED_YET`, `ready false`; a disabled number and a `WHATSAPP_CLOUD_API` number of brand are absent; `otherNumber` absent; two sender documents exist.
- `usageCountsAttemptsOfTodayOnly` — seed recipients for n1: SENT attempted 10:30 IST today, FAILED 10:40 today, SKIPPED (attempted) 10:50 today, SENT yesterday 17:00 IST → `usedToday 2`, `hourlyUsed 3` at 11:00 IST.
- `restRetireReactivateResume` — REST → RESTING with `restUntil = now + 7d`; REST again → 409 `SENDER_STATE` "This number cannot be rested now."; REACTIVATE → WARMING, `warmupStartedAt` null, `ageDay 1`; RETIRE → RETIRED; RESUME with no pause → 409 "This number cannot be resumed now."; with `pausedUntil` = now + 1h → 200, `pausedUntil` null.
- `restingReturnsToWarmingAtRestUntil` — REST, `clock.advance(7 days)`, GET → WARMING, `sendingDays 0`.
- `warmingBecomesActiveOnDay31` — sender with `sendingDays 30`, `lastSendingDay` yesterday → GET → ACTIVE, `capToday 100`.
- `runsOnIsSetWithoutAnAction` — `{"runsOn":" Office PC, profile 3 "}` → `"Office PC, profile 3"`; `{"runsOn":""}` → null; `{"runsOn":"x","action":"RETIRE"}` keeps runsOn; 101 chars → 400 `VALIDATION`.
- `sendersNeedTheirAuthorities` — agent GET 200, agent PUT 403, lead PUT 403, `otherNumber` PUT → 404.
- `dayStartOffsetIsDrawnOncePerDay` — `random.offset = 37`; two GETs → document `dayStartOffsetMinutes 37`, `dayStartFor 2026-10-21`; `random.offset = 5`, GET → still 37; advance one day → 5.

`CampaignIndexesIT` (style of `InboxIndexesIT`): `recipientPhoneIsUniquePerCampaignWhenSet` (two rows `(c1, "9198…")` → `DuplicateKeyException`; two rows `(c1, phone null)` both save), plus index-name assertions for the seven recipient indexes and `campaign_senders.phoneNumberId` unique.

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=SenderPolicyTest,CampaignSenderIT,CampaignIndexesIT`
Expected: compilation failure.

- [ ] **Step 4: Implement** as under Interfaces. `CampaignSenderService` reads numbers through `PhoneNumberRepository.findByWorkspaceIdOrderByNumber` and statuses through `PhoneNumberService.views`.

- [ ] **Step 5: Run to verify it passes**

Step 3 command → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/main/java/me/nageshk/omnichannel/inbox/InboxDevices.java src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): campaign senders with warm-up caps, pacing arithmetic and admin actions"
```

---

### Task 4: Campaign drafts to a saved filter — create, edit, delete, validation

**Files:**
- Create in `campaign/`: `Estimate.java`, `CampaignStatus.java` (`DRAFT, RUNNING, PAUSED, COMPLETED, TERMINATED`), `PausedBy.java` (`USER, SYSTEM`), `AudienceKind.java` (`FILTER, CSV`), `PlaceholderSourceKind.java` (`CONTACT_FIELD, CONTACT_ATTRIBUTE, CSV_COLUMN, FIXED`), `Campaign.java`, `CampaignRepository.java`, `CampaignRequest.java`, `CampaignLinks.java`, `CampaignValidator.java`, `CampaignService.java`, `CampaignView.java`, `CampaignViews.java`, `CampaignController.java`
- Test: `campaign/CampaignDraftIT.java`, modify `CampaignTestBase`, `CampaignIndexesIT`

**Interfaces:**
- Consumes: Task 3 `CampaignSenderService.ensure`, `SenderView`; Task 2 `CampaignErrors`.
- Produces:
  - `@Document("campaigns") @CompoundIndexes({ws_nameKey {workspaceId:1, nameKey:1} unique, ws_status {workspaceId:1, status:1}}) class Campaign { @Id String id; String workspaceId, customerId, name, nameKey; Audience audience; List<String> templateIds, templateNames; List<PlaceholderMapping> placeholders; List<String> senderIds; CampaignStatus status; PausedBy pausedBy; String pauseReason; Counts counts; String createdBy; Instant creationTime, startedAt, finishedAt; @LastModifiedDate Instant lastUpdateTime; @Version Long version; }` with nested records:
    - `Audience(AudienceKind kind, String filterId, String filterName, ContactFilterSpec filterSpec, String csvFileName, List<String> csvHeaders, Integer csvRows, String phoneColumn, String nameColumn, String csvFileId)`
    - `PlaceholderMapping(String name, PlaceholderSource source, String fallback)`, `PlaceholderSource(PlaceholderSourceKind kind, String field, String attributeId, String column, String text)` annotated `@JsonInclude(NON_NULL)` so it serialises as `{kind, field}` / `{kind, attributeId}` / `{kind, column}` / `{kind, text}`
    - `Counts(int total, int pending, int sent, int delivered, int read, int replied, int skipped, int failed)` (`Counts.ZERO`).
  - `public record CampaignRequest(String name, AudienceRequest audience, List<String> templateIds, List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) { public record AudienceRequest(AudienceKind kind, String filterId, String phoneColumn, String nameColumn) {} }`.
  - `public record CsvUpload(String fileName, byte[] bytes)` (nested in `CampaignService`; Task 5 uses it).
  - `final class CampaignLinks { static boolean containsLink(String text); }` — case-insensitive `http://`, `https://`, `www.`.
  - `@Component CampaignValidator { Validated validate(String workspaceId, CampaignRequest req, List<String> csvHeaders); record Validated(String name, List<MessageTemplate> templates, List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) {} }` — `csvHeaders` null for FILTER.
  - `@Service CampaignService { Campaign create(AuthenticatedUser me, String workspaceId, CampaignRequest req, CsvUpload csv); Campaign update(String workspaceId, String id, CampaignRequest req, CsvUpload csv); void delete(String workspaceId, String id); Campaign require(String workspaceId, String id); }` — `require` 404s outside the workspace.
  - `public record CampaignView(String id, String name, CampaignStatus status, PausedBy pausedBy, String pauseReason, AudienceView audience, List<String> templateIds, List<String> templateNames, List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds, Campaign.Counts counts, Estimate estimate, List<SenderView> senders, String createdBy, Instant creationTime, Instant startedAt, Instant finishedAt, Instant lastUpdateTime, Long version)`; `AudienceView` (nested) is `Audience` without `csvFileId`. `public record Estimate(int remaining, int perDay, Integer days, LocalDate finishBy)` in `Estimate.java`; `CampaignViews.detail(Campaign c)` returns `estimate = null`, `senders = List.of()` until Task 12.
  - `POST /campaigns` (`consumes = application/json`, 201), `PUT /campaigns/{id}` (JSON), `DELETE /campaigns/{id}` (204), all `campaign:manage`; `GET /campaigns/{id}` (`campaign:view`) → `CampaignView`.

Validation order and outcome (first failing group wins; within a group all fields are reported):
1. `name`: trimmed, 1–100 chars → `VALIDATION name`; another campaign of the workspace with the same `nameKey` (trim + lower-case) → `DuplicateException("name", "a campaign with this name already exists")`.
2. `audience`: FILTER needs `filterId` of a saved filter of the workspace (`VALIDATION audience.filterId "required"` / `"unknown filter"`); the filter's name and spec are snapshotted. CSV rules: Task 5.
3. `templateIds`: distinct, 1–5 (`VALIDATION templateIds "needs 1 to 5 templates"`), each in the workspace (`"unknown template"`) and ACTIVE (`"archived"`); then type TEXT (`TEMPLATE_NOT_TEXT`), then body without a link (`TEMPLATE_HAS_LINK`, field `templateIds`).
4. `placeholders`: the distinct placeholder names across the chosen templates, each resolved to a mapping. FILTER: CONTACT_FIELD/CONTACT_ATTRIBUTE placeholders without a mapping default to `{CONTACT_FIELD, field: "name"|"phone"}` / `{CONTACT_ATTRIBUTE, attributeId}`; AGENT placeholders need one; allowed source kinds CONTACT_FIELD (`field` `name` or `phone`), CONTACT_ATTRIBUTE (an attribute of the workspace), FIXED (non-blank `text` ≤ 1000). A missing or invalid mapping → `PLACEHOLDER_UNMAPPED`. FIXED `text` or any `fallback` with a link → `TEMPLATE_HAS_LINK` field `placeholders`. `fallback` ≤ 1000, null stored as `""`. Mappings for names no template uses are dropped.
5. `senderIds`: distinct, non-empty (`VALIDATION senderIds "required"`), each a number of the workspace (`"unknown number"`); a `WHATSAPP_CLOUD_API` or disabled number, or a sender (ensured) not WARMING/ACTIVE → `SENDER_NOT_AVAILABLE` naming the first such number.

`create` stores DRAFT, `counts = Counts.ZERO`, `createdBy = me.getUsername()`, `customerId` of the workspace. `update` and `delete` on a non-DRAFT → `CAMPAIGN_NOT_EDITABLE`.

- [ ] **Step 1: Extend `CampaignTestBase`** — `collections()` adds `Campaign`, `Contact`, `ContactFilter`, `ContactAttribute`, `MessageTemplate`; helpers `Campaign campaign(String id)`, `String template(String name, String body)` (POST `/templates` `{name, type: TEXT, content: {body}}` as manager, returns id), `String filter(String name, Map<String, Object> spec)` (POST `/contact-filters`), `Contact contact(String name, String phone)`, `Map<String, Object> filterCampaign(String name, String filterId, List<String> templateIds, List<Map<String, Object>> placeholders, List<String> senderIds)` (request body builder).

- [ ] **Step 2: Write the failing `CampaignDraftIT`**

```java
@Test void createsADraftWithDefaultContactMappings() {
    String t = template("Hello", "Hi {{contact.name}}, {{offer}}");
    String f = filter("Leads", Map.of());
    ResponseEntity<JsonNode> res = api(POST, "/campaigns", manager, filterCampaign("Diwali", f, List.of(t),
            List.of(Map.of("name", "offer", "source", Map.of("kind", "FIXED", "text", "10% off"), "fallback", "")),
            List.of(n1.getId())));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode c = res.getBody();
    assertThat(c.get("status").asString()).isEqualTo("DRAFT");
    assertThat(c.at("/audience/filterName").asString()).isEqualTo("Leads");
    assertThat(c.at("/templateNames/0").asString()).isEqualTo("Hello");
    assertThat(c.at("/placeholders/0/name").asString()).isEqualTo("contact.name");
    assertThat(c.at("/placeholders/0/source/kind").asString()).isEqualTo("CONTACT_FIELD");
    assertThat(c.at("/placeholders/0/source/field").asString()).isEqualTo("name");
    assertThat(c.at("/counts/total").asInt()).isZero();
    assertThat(c.get("createdBy").asString()).isEqualTo(manager.getUsername());
}
@Test void templateWithALinkIsRefused()        // body "See https://x.in" → 400 code TEMPLATE_HAS_LINK, field templateIds, message "Campaign messages cannot contain links."; "www.x.in" too, case-insensitive
@Test void nonTextTemplateIsRefused()          // POLL template → 400 TEMPLATE_NOT_TEXT "Only text templates can be used in campaigns."
@Test void archivedUnknownAndTooManyTemplatesAreValidation()  // archived → fields.templateIds "archived"; 6 ids → "needs 1 to 5 templates"
@Test void agentPlaceholderNeedsASource()      // no mapping for {{offer}} → 400 PLACEHOLDER_UNMAPPED field placeholders "Every placeholder needs a value."; CSV_COLUMN on a FILTER campaign → same
@Test void fixedTextWithALinkIsRefused()       // FIXED "www.shop.in" → 400 TEMPLATE_HAS_LINK field placeholders
@Test void unavailableSenderIsNamed()          // n2 RESTING → 400 SENDER_NOT_AVAILABLE, field senderIds, message "+" + n2.getNumber() + " cannot send campaigns right now.", number n2.getNumber()
@Test void disabledAndOtherBrandNumbers()      // disabled n2 → SENDER_NOT_AVAILABLE; otherNumber → 400 VALIDATION fields.senderIds "unknown number"; [] → "required"
@Test void duplicateNameIs409()                // "diwali " after "Diwali" → 409 DUPLICATE field name
@Test void draftCanBeEditedAndDeleted()        // PUT changes name and senders → 200; DELETE → 204, GET → 404
@Test void onlyDraftsAreEditable()             // status set to RUNNING in Mongo: PUT and DELETE → 409 CAMPAIGN_NOT_EDITABLE "Only a draft campaign can be edited."
@Test void draftsNeedCampaignManage()          // agent POST → 403; agent GET /{id} → 200; other brand's id → 404
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignDraftIT,CampaignIndexesIT`
Expected: compilation failure.

- [ ] **Step 4: Implement** as under Interfaces; add the two `campaigns` index assertions to `CampaignIndexesIT`.

- [ ] **Step 5: Run to verify it passes**

Step 3 command → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): campaign drafts to a saved contact filter with template, placeholder and sender checks"
```

---

### Task 5: CSV audience — upload, sample CSV, preview

**Files:**
- Create in `campaign/`: `CampaignCsvConfig.java`, `CampaignCsvStore.java`, `CampaignCsv.java`
- Modify: `CampaignValidator.java`, `CampaignService.java`, `CampaignController.java`
- Test: `campaign/CampaignCsvIT.java`, modify `CampaignTestBase`

**Interfaces:**
- Consumes: `ContactCsv.parse`, `ContactCsv.cell`, `ContactImportService.MAX_BYTES` / `MAX_ROWS`; Task 4 `CampaignService`, `CampaignValidator`.
- Produces:
  - `@Bean GridFsTemplate campaignCsvGridFs(...)`, bucket `campaign_csv` (`CampaignCsvConfig.BUCKET`).
  - `@Component CampaignCsvStore { String store(String workspaceId, String fileName, byte[] bytes); byte[] read(String fileId); void delete(String fileId); }` (`@Qualifier("campaignCsvGridFs")`).
  - `final class CampaignCsv { record Parsed(List<String> headers, List<List<String>> rows) {} static Parsed parse(byte[] bytes); }` — headers trimmed; rows padded to the header length; `VALIDATION file` with `"is larger than 5 MB"`, `"is empty; a header row is required"`, `"has more than 20000 rows"`.
  - CSV rules in `CampaignValidator.validate(..., csvHeaders)`: `phoneColumn` blank → `PHONE_COLUMN_REQUIRED`; not a header → `VALIDATION audience.phoneColumn "is not a column of the file"`; `nameColumn` the same check. Every placeholder needs CSV_COLUMN (a header) or FIXED; CONTACT_* kinds → `PLACEHOLDER_UNMAPPED`; two placeholders on one column → `MAPPING_COLUMN_REUSED` (field `placeholders`); using `phoneColumn` or `nameColumn` in a placeholder is allowed.
  - `POST /campaigns` and `PUT /campaigns/{id}` with `consumes = multipart/form-data`, parts `campaign` (JSON `CampaignRequest`) and `file`; the file is required on POST and optional on PUT (absent keeps the stored one and its headers). A CSV audience by JSON POST → `VALIDATION file "required"`. Replacing or deleting a CSV draft deletes the old GridFS file. `audience.csvFileName/csvHeaders/csvRows/csvFileId` are set from the upload.
  - `GET /campaigns/templates/{templateId}/sample-csv` (`campaign:view`) → `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="sample.csv"`, header `phone` + each placeholder name in template order, one row `919812345678` + each placeholder's `example` (blank when null), cells through `ContactCsv.cell`.
  - `POST /campaigns/csv/preview` (`campaign:manage`, multipart `file`, optional repeated `templateIds`) → `public record CsvPreview(List<String> headers, List<List<String>> sample, int rows, Suggested suggested) { public record Suggested(String phone, Map<String, String> placeholders) {} }` — `sample` the first 5 data rows; `suggested.phone` the header equal to `phone` (trimmed, case-insensitive) or null; `placeholders` maps each placeholder name of the given templates to the equal-named header.

- [ ] **Step 1: Extend `CampaignTestBase`** — `ResponseEntity<JsonNode> multipart(HttpMethod m, String path, User who, Object campaignJson, String csv)` (part `campaign` with `Content-Type: application/json`, part `file` named `leads.csv`; `csv == null` omits it); `clean()` also deletes every `campaign_csv` file.

- [ ] **Step 2: Write the failing `CampaignCsvIT`**

```java
@Test void csvDraftStoresTheFileAndHeaders() {
    String t = template("Hello", "Hi {{contact.name}} from {{city}}");
    JsonNode c = multipart(POST, "/campaigns", manager, csvCampaign("Leads Oct", "Phone", "Name", List.of(t),
            List.of(col("contact.name", "Name"), col("city", "City")), List.of(n1.getId())),
            "Phone,Name,City\n9812345678,Asha,Pune\n919811111111,,Delhi\n").getBody();
    assertThat(c.at("/audience/kind").asString()).isEqualTo("CSV");
    assertThat(c.at("/audience/csvHeaders").toString()).isEqualTo("[\"Phone\",\"Name\",\"City\"]");
    assertThat(c.at("/audience/csvRows").asInt()).isEqualTo(2);
    assertThat(c.at("/audience/csvFileName").asString()).isEqualTo("leads.csv");
    assertThat(c.at("/audience/csvFileId").isMissingNode()).isTrue();
    assertThat(gridFsFiles()).isEqualTo(1);
}
@Test void csvPlaceholdersNeedAColumnOrFixedText()   // contact.name left unmapped → 400 PLACEHOLDER_UNMAPPED; column "Town" not in file → PLACEHOLDER_UNMAPPED
@Test void phoneColumnRulesForCsv()                 // no phoneColumn → 400 PHONE_COLUMN_REQUIRED "Choose the column that holds the phone number."; {{contact.name}} and {{city}} both on "Name" → MAPPING_COLUMN_REUSED "A CSV column can be used only once." field placeholders; {{contact.name}} on the nameColumn → 201; unknown phoneColumn → VALIDATION audience.phoneColumn
@Test void contactPhoneMayUseThePhoneColumn()       // {{contact.phone}} → CSV_COLUMN "Phone" → 201
@Test void fileLimits()                             // 20001 data rows → 400 VALIDATION fields.file "has more than 20000 rows"; empty file → "is empty; a header row is required"; JSON POST of a CSV audience → fields.file "required"
@Test void putWithoutAFileKeepsTheStoredCsv()       // PUT renames, csvHeaders unchanged, 1 GridFS file; PUT with a file replaces it (still 1 file, new headers); DELETE → 0 files
@Test void sampleCsvListsPlaceholders() {
    // template "Hi {{contact.name}}, order {{order_id}}" with examples "Asha" and "A-17"
    ResponseEntity<String> res = rest.exchange(url("/campaigns/templates/" + t + "/sample-csv"), GET, auth(agent, null), String.class);
    assertThat(res.getHeaders().getContentType().toString()).startsWith("text/csv");
    assertThat(res.getBody()).isEqualTo("phone,contact.name,order_id\r\n919812345678,Asha,A-17\r\n");   // CRLF, as ContactExportController
}
@Test void previewSuggestsEqualNamedColumns()       // headers [" PHONE ","contact.name","City"], templateIds=t → headers trimmed, suggested.phone "PHONE", placeholders {"contact.name":"contact.name","city":"City"}; sample has 5 rows of 7; rows 7; without templateIds placeholders {}
@Test void previewNeedsCampaignManage()             // agent → 403
```
`csvCampaign(...)` and `col(name, column)` are local builders of the request map.

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignCsvIT`
Expected: FAIL (404/415 on multipart, endpoints missing).

- [ ] **Step 4: Implement** as under Interfaces.

- [ ] **Step 5: Run to verify it passes**

`./mvnw -q test -Dtest=CampaignCsvIT,CampaignDraftIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): CSV campaign drafts, sample CSV and CSV preview"
```

---

### Task 6: Device events — logout, reconnection, fleet rules

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/campaign/SenderEventsListener.java`
- Test: `src/test/java/me/nageshk/omnichannel/campaign/SenderEventsIT.java`

**Interfaces:**
- Consumes: `WhatsAppEvent`; Task 3 `CampaignSenderService.ensure`, `SenderPolicy.endOfDay`; Task 2 settings.
- Produces: `@Component SenderEventsListener { @EventListener public void on(WhatsAppEvent e); }`:
  - `change_state` with `payload.state` `UNPAIRED` or `UNPAIRED_IDLE`: ensure the sender; conditional update `{phoneNumberId, state ≠ SUSPENDED}` → `state SUSPENDED`, `logoutAt now`. Only when that update matched: every other sender of the workspace in WARMING/ACTIVE gets `halfPaceUntil = endOfDay`; then if at least two senders of the workspace have `logoutAt > now - 7 days`, every RUNNING campaign of the workspace → PAUSED, `pausedBy SYSTEM`, `pauseReason "TWO_LOGOUTS"` (one `updateMulti`).
  - `ready` on a SUSPENDED sender → `state RESTING`, `restUntil = now + restDays` (conditional on SUSPENDED).
  - Other events ignored; `RuntimeException` logged.

- [ ] **Step 1: Write the failing `SenderEventsIT`** (events published with `ApplicationEventPublisher`: `new WhatsAppEvent(this, n.getId(), brand.getId(), acme.getId(), "change_state", mapper.createObjectNode().put("state", "UNPAIRED"), null, null)`; RUNNING campaigns saved directly in Mongo)

```java
@Test void logoutSuspendsAndHalvesTheOthersForTheDay() {
    logout(n1);
    CampaignSender s1 = sender(n1), s2 = sender(n2);
    assertThat(s1.getState()).isEqualTo(SenderState.SUSPENDED);
    assertThat(s1.getLogoutAt()).isEqualTo(WED_11_IST);
    assertThat(s2.getHalfPaceUntil()).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
    assertThat(s1.getHalfPaceUntil()).isNull();
    assertThat(campaign(running).getStatus()).isEqualTo(CampaignStatus.RUNNING);
}
@Test void retiredSendersAreNotHalved()
@Test void reconnectionRestsThenWarmingAfterSevenDays()   // logout, ready → RESTING restUntil +7d; advance 7d; GET /senders → WARMING, warmupStartedAt null, ageDay 1
@Test void twoLogoutsWithinAWeekPauseRunningCampaigns() { // logout n1; advance 6 days; logout n2 → running PAUSED SYSTEM TWO_LOGOUTS; a DRAFT stays DRAFT; a USER-paused stays pausedBy USER; other brand's RUNNING campaign stays RUNNING
@Test void logoutsMoreThanAWeekApartPauseNothing()       // advance 8 days between
@Test void repeatedUnpairedIsOneLogout()                 // UNPAIRED, UNPAIRED_IDLE, UNPAIRED on n1 → logoutAt unchanged after the first, campaigns RUNNING
@Test void otherStatesChangeNothing()                     // change_state CONNECTED / OPENING; ready on a WARMING sender
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=SenderEventsIT`
Expected: FAIL (states unchanged).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes**

`./mvnw -q test -Dtest=SenderEventsIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign/SenderEventsListener.java src/test/java/me/nageshk/omnichannel/campaign/SenderEventsIT.java
git commit -m "feat(campaign): suspend senders on logout, rest on reconnection, fleet rules"
```

---

### Task 7: Start — materialisation of filter and CSV audiences

**Files:**
- Create in `campaign/`: `CampaignMaterialiser.java`, `CampaignCounter.java`, `CampaignLifecycleService.java`
- Modify: `CampaignController.java` (`POST /{id}/start`)
- Test: `campaign/CampaignStartIT.java`, modify `CampaignTestBase`

**Interfaces:**
- Consumes: `ContactQuery.build`, Task 5 `CampaignCsvStore`/`CampaignCsv`, Task 4 `CampaignValidator`, Task 1 `Phones.withDefaultCountryCode`, Task 2 settings.
- Produces:
  - `@Component CampaignMaterialiser { int materialise(Campaign c, CampaignSettings s, Instant now); }` — returns rows written; throws on failure after deleting the campaign's recipients.
  - `@Component CampaignCounter { Campaign.Counts refresh(String campaignId); }` — aggregates the campaign's recipients by status plus `repliedAt != null`, stores `counts` with a field update, returns them (count semantics in "Decisions").
  - `@Service CampaignLifecycleService { Campaign start(String workspaceId, String id); }` (Task 13 adds the others).
  - `POST /campaigns/{id}/start` (`campaign:manage`) → `CampaignView`.

Start: non-DRAFT → `CAMPAIGN_STATE("started")`; a second start of the same id while one runs (in-memory `Set<String> starting`) → the same. Re-run `CampaignValidator.validate` (with the stored `csvHeaders`). Materialise, then conditional update `{_id, status DRAFT}` → RUNNING, `startedAt now`; on no match delete the recipients and throw `CAMPAIGN_STATE("started")`. Delete the CSV file and `csvFileId` after the flip. Refresh counts.

Materialisation, per page of at most 500 source rows:
- FILTER source: `ContactQuery.build(ws, filterSpec)` AND `phone ≠ null` AND `_id > lastId`, sort `_id` asc, limit 500. CSV source: the stored rows in file order, 500 at a time; phone = `Phones.withDefaultCountryCode(Phones.normalise(cell.trim()), s.getDefaultCountryCode())`; `values` = the row's cells.
- Per page, one query each: contacts of the workspace by `phone in` (CSV only; FILTER has them); recipients of the workspace with `phone in`, status SENT/DELIVERED/READ, `sentAt >= now - recontactDays`; chats of the workspace with `contactId in` (`workspace_contact` index).
- Each row, first match wins: blank phone → SKIPPED `NO_PHONE`; `!Phones.isValid` → `INVALID_PHONE`; phone already seen in this campaign (in-memory set) → `DUPLICATE`; contact with `doNotContact` → `DO_NOT_CONTACT`; recent recipient → `RECENTLY_MESSAGED`; contact has chats but none on the campaign's senders → `CHAT_ON_OTHER_NUMBER`; else PENDING with `preferredSenderId` = the campaign sender whose chat with the contact has the latest `lastMessageAt` (null when no chat), `templateId = templateIds[k++ % n]`, `contactId` when known. Rows skipped as NO_PHONE/INVALID_PHONE/DUPLICATE store `phone = null`, `rawPhone` = the cell (or contact phone).
- One `insertAll` per page.

- [ ] **Step 1: Extend `CampaignTestBase`** — `collections()` adds `WaWebChat`, `WaWebMessage`, `WaWebChatAction`, `WaNumberLookup`, `InboxSetting`; helpers `CampaignRecipient recipient(String campaignId, String phone)`, `String startedFilterCampaign(String name, List<String> phones, PhoneNumber... senders)` (contacts named `Lead <i>`, empty-spec filter, template `"Hi {{contact.name}}"`, create + start, returns id) and `String startedCsvCampaign(String name, String csv, String nameColumn, PhoneNumber... senders)` (template `"Hi {{contact.name}}"` mapped to `nameColumn` or FIXED "there").

- [ ] **Step 2: Write the failing `CampaignStartIT`**

```java
@Test void filterIsMaterialisedInIdOrderAcrossPages() {
    // contacts.saveAll: 1,200 brand contacts with phones "9198" + 8 digits, plus one without a phone;
    // an empty-spec saved filter, a draft over it on n1, POST /start
    List<Contact> saved = …;
    String id = …;
    List<CampaignRecipient> rs = recipients.findAll(Sort.by("_id"));
    assertThat(rs).extracting(CampaignRecipient::getContactId).containsExactlyElementsOf(saved.stream().map(Contact::getId).toList());
    assertThat(rs).allMatch(r -> r.getStatus() == RecipientStatus.PENDING);
    assertThat(campaign(id).getCounts().total()).isEqualTo(1200);
    assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
}
@Test void templatesAreAssignedRoundRobin()             // 2 templates, 5 recipients → t1,t2,t1,t2,t1 in _id order
@Test void csvOf20000RowsWithDuplicatesBlanksAndBadPhones() {
    // 19,880 distinct 10-digit "98" + 8 digits, 100 repeats of earlier phones, 10 blank, 10 "12345" → start within 20 s (assertTimeout)
    // counts: total 20000, pending 19880, skipped 120; skipReason counts DUPLICATE 100, NO_PHONE 10, INVALID_PHONE 10
    // every PENDING phone starts with "91"; GridFS has 0 files; campaign.audience.csvFileId null
}
@Test void doNotContactAndRecentlyMessagedAreSkipped()   // contact marked DNC → DO_NOT_CONTACT; a SENT recipient of another campaign for phone P with sentAt now-6d → RECENTLY_MESSAGED; at now-8d → PENDING
@Test void existingChatPinsTheSender()                  // contact with a chat on n2, senders [n1, n2] → preferredSenderId n2; same contact, senders [n1] → SKIPPED CHAT_ON_OTHER_NUMBER; no chat → preferredSenderId null
@Test void startTwiceIs409AndStartRevalidates()         // second start → 409 CAMPAIGN_STATE "This campaign cannot be started now."; a DRAFT whose template was edited to contain "http://" → 400 TEMPLATE_HAS_LINK and the campaign stays DRAFT with no recipients
@Test void startNeedsCampaignManage()                   // agent → 403
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignStartIT`
Expected: FAIL (404 on `/start`).

- [ ] **Step 4: Implement** as under Interfaces.

- [ ] **Step 5: Run to verify it passes**

`./mvnw -q test -Dtest=CampaignStartIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): start a campaign by materialising its recipients in pages"
```

---

### Task 8: Inbox sends without a user — `SendActor`, `campaignId`, routing on first reply

**Files:**
- Create in `inbox/`: `SendActor.java`, `UserActor.java`, `CampaignActor.java`
- Modify in `inbox/`: `MessageSource.java` (+`CAMPAIGN`), `WaWebMessage.java` (+`String campaignId`), `WaWebChat.java` (+`String campaignId`), `InboxSendService.java`, `NewChatService.java`, `ChatAssignmentService.java`, `InboxViews.java`, `InboxEvents.java`, `InboxIngestService.java`, `ChatRoutingListener.java`, `WaWebMessageRepository.java`
- Modify: `src/main/java/me/nageshk/omnichannel/template/TemplateRenderer.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/CampaignChatIT.java`, `src/test/java/me/nageshk/omnichannel/template/TemplateRenderIT.java`; `InboxSendIT`, `NewChatIT`, `ChatRoutingIT`, `ChatAssignmentIT`, `WaWebInboxListenerIT` stay green unchanged

**Interfaces:**
- Produces:
  - `public sealed interface SendActor permits UserActor, CampaignActor { String userId(); String displayName(); MessageSource source(); String campaignId(); }`
  - `public record UserActor(AuthenticatedUser me, String displayName) implements SendActor` — `userId = me.userId()`, `source CONSOLE`, `campaignId null`.
  - `public record CampaignActor(String campaignId, String campaignName) implements SendActor` — `userId null`, `displayName "Campaign: " + campaignName`, `source CAMPAIGN`.
  - `InboxSendService.send(SendActor actor, String workspaceId, WaWebChat chat, SendRequest req, String typedPhone) -> CompletableFuture<MessageView>`; both `AuthenticatedUser` overloads build `new UserActor(me, users.findById(me.userId()).map(User::getName).orElse(me.getUsername()))` and delegate. Template rendering: `UserActor` → `renderer.render(me, …)` as today; `CampaignActor` → `renderer.renderForCampaign(workspaceId, templateId, req.values())`. `record` sets `source`, `sentByUserId`, `sentByName` and `campaignId` from the actor; when it adopts a row the ingest created first (`isOurOwnOutbound`) it also copies `sent.getCampaignId()`. `claimOnReply` only for `UserActor`.
  - `ChatAssignmentService.reopen(SendActor actor, WaWebChat chat) -> WaWebChat` (action `byUserId = actor.userId()`, `byName = actor.displayName()`); the `AuthenticatedUser` variant is unchanged.
  - `NewChatService.startForCampaign(CampaignActor actor, String workspaceId, String phoneNumberId, String phone, String remoteId, SendRequest message) -> CompletableFuture<MessageView>` — existing chat (contact's chat by phone, else `phone@c.us`, else `remoteId`) → reopen if CLOSED, keep its assignment, `sender.send(actor, ws, chat, message, null)`; else a fresh unsaved chat, unassigned, `campaignId = actor.campaignId()`, `contactId` of the contact with that phone, `sender.send(actor, ws, fresh, message, phone)`. No scope check, no lookup (the runner did it).
  - `TemplateRenderer.renderForCampaign(String workspaceId, String templateId, Map<String, String> values) -> RenderedTemplate` — no authority check, no contact: every placeholder, whatever its kind, takes `values.get(name)` stripped and cut to `MAX_AGENT_VALUE`, blank → the template placeholder's fallback; `missing` is always empty; overflow/empty body still `ValidationException`.
  - `InboxEvents.CampaignChatFirstReply(WaWebChat chat)`; `InboxIngestService` publishes it (before `MessageUpserted`, then re-reads the chat as for `ChatCreated`) for an inbound message folded into a chat with `campaignId != null`, `assignedUserId == null`, `lastInboundAt == null` before the fold, and not reopened by it. `ChatRoutingListener` routes it when `assignedUserId` and `assignedTeamId` are null.
  - `InboxViews`: message `sentBy` = `Ref(campaignId, sentByName)` when `campaignId != null && sentByUserId == null`, else `Ref(sentByUserId, sentByName)` when either is non-null; action `by` = `Ref(byUserId, byName)` when either is non-null.
  - `WaWebMessageRepository.findFirstByChatIdAndDirectionOrderByTimestampDesc(String chatId, MessageDirection direction) -> Optional<WaWebMessage>` (Task 11).

- [ ] **Step 1: Write the failing tests**

`TemplateRenderIT`:
- `campaignRenderTakesEveryPlaceholderFromValues` — template `"Hi {{contact.name}} from {{contact.attributes.city}}, {{offer}}"` → `renderForCampaign(ws, id, Map.of("contact.name", "Asha", "contact.attributes.city", "Pune", "offer", "10% off"))` body `"Hi Asha from Pune, 10% off"`, no security context set.
- `campaignRenderUsesFallbacksAndNeverReportsMissing` — required agent placeholder blank, fallback "friend" on `contact.name` → `"Hi friend …"`, `missing()` empty; a value of 1,200 chars is cut to 1,000.

`CampaignChatIT` (inbox package, extends `InboxApiTestBase`, RANDOM_PORT, `omnichannel.rpc.timeout=2s`, `FakeDevice` helpers copied from `NewChatIT`; `@Autowired NewChatService newChats`):
```java
private static final CampaignActor DIWALI = new CampaignActor("c-1", "Diwali");

@Test void freshCampaignChatIsUnassignedAndMarked() throws Exception {
    settingService.put(brand.getId(), number.getId(), new InboxSettingService.SettingsRequest(null, true, null)); // claimOnReply on
    Exchange ex = campaignSend("919811111111", "919811111111@c.us", templateId("Hi {{contact.name}}"), Map.of("contact.name", "Asha"));
    MessageView sent = ex.view();
    WaWebChat chat = chats.findById(sent.chatId()).orElseThrow();
    assertThat(chat.getCampaignId()).isEqualTo("c-1");
    assertThat(chat.getAssignedUserId()).isNull();
    WaWebMessage m = messages.findById(sent.id()).orElseThrow();
    assertThat(m.getSource()).isEqualTo(MessageSource.CAMPAIGN);
    assertThat(m.getCampaignId()).isEqualTo("c-1");
    assertThat(m.getSentByUserId()).isNull();
    assertThat(sent.sentBy().id()).isEqualTo("c-1");
    assertThat(sent.sentBy().name()).isEqualTo("Campaign: Diwali");
    assertThat(ex.request().params().get("content").asString()).isEqualTo("Hi Asha");
    assertThat(actions.count()).isZero();
    assertThat(contacts.findByWorkspaceIdAndPhone(brand.getId(), "919811111111")).isPresent();
}
@Test void existingAssignedChatKeepsItsAssignment()   // chat of contact 919811111111 assigned to agent2 → same chatId, still agent2, chat.campaignId stays null
@Test void closedChatIsReopenedByTheCampaign()        // CLOSED → OPEN, one REOPENED action with byUserId null, byName "Campaign: Diwali"
@Test void campaignSendSurvivesTheEventComingFirst()  // device publishes message_create (fromMe) before answering → one chat with campaignId "c-1", one message with source CAMPAIGN and sentByName "Campaign: Diwali"
@Test void firstReplyRoutesACampaignChat()            // routing USER agent; campaign chat saved unassigned; inbound message_create → assignedUserId agent, one ROUTED action; unassign, second inbound → still unassigned
@Test void firstReplyOnAPlainChatIsNotRouted()        // same with campaignId null → unassigned (existing ChatCreated rule unchanged)
```
`campaignSend(phone, remoteId, templateId, values)` connects a ready `FakeDevice`, calls `newChats.startForCampaign(DIWALI, brand.getId(), number.getId(), phone, remoteId, new SendRequest(null, templateId, values))` on another thread, answers `sendMessage` with `model(...)`, returns `record Exchange(MessageView view, Envelope request)`. Templates are created with `templateService.create` as `NewChatIT` does.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignChatIT,TemplateRenderIT`
Expected: compilation failure (`CampaignActor`, `renderForCampaign` missing).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=CampaignChatIT,TemplateRenderIT,InboxSendIT,NewChatIT,ChatRoutingIT,ChatAssignmentIT,WaWebInboxListenerIT,InboxViewsIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/inbox src/main/java/me/nageshk/omnichannel/template/TemplateRenderer.java src/test/java/me/nageshk/omnichannel/inbox/CampaignChatIT.java src/test/java/me/nageshk/omnichannel/template/TemplateRenderIT.java
git commit -m "feat(inbox): send as a campaign without a user, mark campaign chats and route them on first reply"
```

---

### Task 9: The runner tick

**Files:**
- Create in `campaign/`: `CampaignSchedulerConfig.java`, `CampaignTicker.java`, `CampaignRunner.java`, `PlaceholderValues.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactService.java` (`nameIfBlank`), `src/test/resources/application-test.yml` (`omnichannel.campaign.auto-tick: false`)
- Test: `campaign/CampaignRunnerIT.java`, `campaign/AutoDevice.java`, modify `CampaignTestBase`

**Interfaces:**
- Consumes: Task 8 `NewChatService.startForCampaign`, `CampaignActor`, `renderForCampaign`; `NumberLookupService.lookup`; Task 3 sender service and `SenderPolicy`; Task 7 `CampaignCounter`; `InboxDevices.isReady`; `OmnichannelProperties.rpc().timeout()`.
- Produces:
  - `@Bean ThreadPoolTaskScheduler campaignScheduler()` (pool 2, prefix `campaign-`, daemon).
  - `@Component @ConditionalOnProperty(name = "omnichannel.campaign.auto-tick", havingValue = "true", matchIfMissing = true) CampaignTicker` — `@Scheduled(fixedDelayString = "${omnichannel.campaign.tick:10s}", scheduler = "campaignScheduler") void tick()` calls `runner.tick()`; `@EventListener(ApplicationReadyEvent.class)` calls `runner.failStuck(clock.instant())`.
  - `@Component PlaceholderValues { Map<String, String> of(Campaign c, CampaignRecipient r, Contact contact); }` — per mapping: CONTACT_FIELD `name` → contact name, `phone` → `"+" + phone`; CONTACT_ATTRIBUTE → `contact.getAttributes().get(attributeId)`; CSV_COLUMN → the cell at the header's index (`"+" + r.getPhone()` when the column is `phoneColumn`); FIXED → text; blank → the mapping's fallback.
  - `ContactService.nameIfBlank(String workspaceId, String contactId, String name)` — sets `name` (and `nameEdited = true`) only when the contact's name is blank.
  - `@Service public class CampaignRunner { public CompletableFuture<Void> tick(); public int failStuck(Instant now); }`.

`tick()` (skip when another tick holds the lock; every recipient write after a device call is conditional on `status = SENDING`):
1. `failStuck(now)`: SENDING with `attemptedAt < now - (rpc timeout + 2 min)` → FAILED, `error "INTERRUPTED"`; refresh counts of affected campaigns.
2. RUNNING campaigns by `startedAt` asc, grouped by workspace. Skip a workspace outside `SenderPolicy.inWindow`.
3. For each sender id of those campaigns, skip if busy (in-memory set of in-flight senders); `ensure` + `refresh`; eligible when WARMING/ACTIVE, `isReady`, `pausedUntil` null or ≤ now, `now >= dayStartsAt(today, dayStartOffsetMinutes)`, `usedToday < capToday`, `hourlyUsed < hourlyCap`, `nextSendAt` null or ≤ now.
4. For an eligible sender start `process(sender, its RUNNING campaigns, freeSkips = 20)` asynchronously, mark it busy until the future completes:
   1. Claim: for each campaign oldest first, `findAndModify({campaignId, status PENDING, preferredSenderId in [null, senderId]}, sort _id, set status SENDING, senderId, attemptedAt now)`; none → done.
   2. Free skips (unset `attemptedAt`, no gap; then `process` again while `freeSkips-- > 0`): contact of the phone has `doNotContact` → `DO_NOT_CONTACT`; another recipient of the workspace with that phone, status SENT/DELIVERED/READ, `sentAt >= now - recontactDays` → `RECENTLY_MESSAGED`.
   3. `lookups.lookup(ws, senderId, phone, false)`: synchronous `NoDeviceException` → release (status PENDING, unset `senderId`, `attemptedAt`). Future failure → `attempts + 1`, `error "LOOKUP_FAILED"`, unset `attemptedAt` and `senderId`; PENDING again, or FAILED once `attempts == 3`; `paced(now + gap)`. Not registered and `cached` → free skip `NOT_ON_WHATSAPP`. Not registered from the device → SKIPPED `NOT_ON_WHATSAPP` (keeps `attemptedAt`), `paced(now + skip gap)`.
   4. Registered: `values = placeholderValues.of(...)`; `renderForCampaign` — an exception → FAILED `"RENDER_FAILED"`, a body with `CampaignLinks.containsLink` → FAILED `"HAS_LINK"`; neither costs a gap. Then `newChats.startForCampaign(new CampaignActor(c.getId(), c.getName()), ws, senderId, phone, lookup.remoteId(), new SendRequest(null, r.getTemplateId(), values))`; synchronous `NoDeviceException` → release.
   5. Success: `sendAttempted(senderId, today, now)`, `paced(now + gap)`; recipient SENT with `chatId`, `messageId`, `sentAt`, `contactId` (from the chat when null); then re-read the message and move the recipient to DELIVERED/READ if its status already is (PLAYED counts as READ); CSV with `nameColumn` → `nameIfBlank(contact of the chat, cell)`. Task 10 hooks its pause check here.
   6. Failure (`RpcException` → its `code()`, `TimeoutException` → `"TIMEOUT"`, other → simple class name): `sendAttempted`, `paced(now + gap)`, recipient FAILED with that `error`.
   7. After each outcome, `counter.refresh(campaignId)`. Gaps use `SenderPolicy.gapSeconds(skip, halfPaceUntil > now, settings, random)`; `now` is re-read from the clock when the outcome is handled.
5. Every RUNNING campaign (all workspaces) with no PENDING/SENDING recipient → conditional RUNNING → COMPLETED, `finishedAt now`.
6. Return `allOf` the started futures.

- [ ] **Step 1: Write `AutoDevice` and extend `CampaignTestBase`**

`AutoDevice` (test, `campaign` package) wraps a `FakeDevice` connected with `testDevices.redeem(n)` and sent `ready` (`{pushname, wid: number@c.us}`, then 200 ms); a daemon thread answers each request: `getNumberId` with `lookupAnswer.apply(phone)` (default `{"phone": phone@c.us, "lid": null}`; `null` → JSON null; a thrown exception → `Envelope.errorResponse(id, "PAGE_ERROR", "boom")`), `sendMessage` with `sendAnswer.apply(req)` (default a model like `NewChatIT.model` with `ack 1`). Records every request (`List<Envelope> requests()`, `List<Envelope> requests(String method)`). `onLookup(Runnable)` runs before answering a lookup.

`CampaignTestBase`: `AutoDevice device(PhoneNumber n)`; `void tickNow()` = `runner.tick().get(10, SECONDS)`; `void seedLookup(String phone, boolean registered)` (a fresh `WaNumberLookup` of brand).

- [ ] **Step 2: Write the failing `CampaignRunnerIT`** (clock at Wednesday 11:00 IST, `FixedRandom` at minima so gaps are 45 s / 15 s and day offset 0)

```java
@Test void looksUpThenSendsOneMessagePerTick() throws Exception {
    try (AutoDevice d = device(n1)) {
        String id = startedFilterCampaign("Diwali", List.of("919811100001", "919811100002"), n1);
        tickNow();
        assertThat(d.requests()).extracting(Envelope::method).containsExactly("getNumberId", "sendMessage");
        assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Hi Lead 0");
        CampaignRecipient r = recipient(id, "919811100001");
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(r.getSentAt()).isEqualTo(WED_11_IST);
        assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(45));
        assertThat(sender(n1).getSendingDays()).isEqualTo(1);
        tickNow();                                   // gap not elapsed
        assertThat(d.requests()).hasSize(2);
        clock.advance(Duration.ofSeconds(45)); tickNow();
        assertThat(d.requests("sendMessage")).hasSize(2);
        assertThat(campaign(id).getCounts().sent()).isEqualTo(2);
    }
}
@Test void warmupCapOfFiveOnDayOne()                  // 6 recipients, tick + advance 45 s × 8 → 5 sends, 1 PENDING; next day 10:00 IST → the sixth is sent, sendingDays 2
@Test void hourlyCapCountsSendsAndSkips()             // sendingDays 20 (cap 35); 14 recipients, 3 of them answered not registered; ticks every 45 s for 59 min → 12 device actions; at first attemptedAt + 60 min → the 13th
@Test void halfPaceDoublesTheGap()                     // halfPaceUntil end of day → nextSendAt now + 90 s
@Test void outsideTheWindowNothingHappens()            // 09:59 IST, 18:00 IST, Sunday 11:00 IST → no requests; settings timezone "UTC" at 05:30Z → no requests
@Test void dayStartOffsetDelaysTheFirstSend()          // random.offset 45 at 10:30 IST → nothing; 10:45 IST → sends
@Test void unregisteredNumberIsSkippedWithAShortGap()  // lookup null → SKIPPED NOT_ON_WHATSAPP, no sendMessage, nextSendAt now + 15 s, hourlyUsed 1, usedToday 0
@Test void cachedUnregisteredIsAFreeSkip()             // seedLookup(P1, false); P2 registered → one tick: P1 SKIPPED with attemptedAt null, P2 sent; only one getNumberId+sendMessage pair
@Test void lookupFailureRetriesThenFails()             // lookup errors three times (advance 45 s each) → attempts 1, 2 PENDING then FAILED error LOOKUP_FAILED, attemptedAt null
@Test void sendFailureFailsTheRecipientAndCostsTheSlot() // sendMessage error SEND_FAILED → FAILED error SEND_FAILED, usedToday 1, counts.failed 1, nextSendAt now + 45 s
@Test void deviceGoneBeforeSendReleasesTheClaim()      // onLookup disables n1 (numbers.save with Disabled) → recipient PENDING, senderId null, attemptedAt null, attempts 0; no sendMessage
@Test void alreadyDeliveredResultMarksTheRecipientDelivered() // sendMessage model with ack 2 → recipient DELIVERED, deliveredAt set
@Test void samePhoneInTwoRunningCampaignsIsMessagedOnce()     // A and B both started with P, then P1 only in B; tick → A's P sent; +45 s tick → B's P SKIPPED RECENTLY_MESSAGED and B's P1 sent in the same tick
@Test void doNotContactMarkedAfterStartIsSkipped()     // markDoNotContact after start → SKIPPED DO_NOT_CONTACT without device frames
@Test void preferredSenderIsWaitedFor()                // recipient preferred n2 (no device), n1 ready → n1 never claims it; n1 sends the others
@Test void notReadySenderIsSkippedWithoutStateChange() // n1 without device → nothing claimed, sender stays WARMING
@Test void stuckSendingIsInterruptedNotRetried()        // seeded SENDING attemptedAt now - 2 min 3 s → failStuck → FAILED error INTERRUPTED; attemptedAt now - 1 min → untouched
@Test void campaignCompletesWhenNothingIsLeft()         // 1 recipient sent → next tick status COMPLETED, finishedAt; a PAUSED campaign with nothing left stays PAUSED
@Test void csvCampaignRendersCellsAndNamesTheContact()  // CSV "Phone,Name,City\n9811100001,Asha,Pune", template "Hi {{contact.name}} from {{city}}" → content "Hi Asha from Pune", chatId remote 919811100001@c.us, created contact named Asha
@Test void renderedLinkFailsWithoutSending()            // CSV cell "www.x.in" mapped to {{city}} → FAILED error HAS_LINK, no sendMessage, nextSendAt unchanged
@Test void warmingBecomesActiveAfterThirtySendingDays() // sendingDays 30, lastSendingDay yesterday → after a tick state ACTIVE
@Test void tickRunsOnTheCampaignScheduler()             // context has beans "campaignScheduler" (pool 2, prefix "campaign-") and "taskScheduler" (pool 1); no CampaignTicker bean in tests
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignRunnerIT`
Expected: compilation failure (`CampaignRunner` missing).

- [ ] **Step 4: Implement** as under Interfaces.

- [ ] **Step 5: Run to verify it passes**

`./mvnw -q test -Dtest=CampaignRunnerIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/main/java/me/nageshk/omnichannel/contact/ContactService.java src/test/resources/application-test.yml src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): runner tick on its own scheduler: claim, look up, send, pace and complete"
```

---

### Task 10: Automatic pauses — undelivered run and low reply rate

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/campaign/SenderHealth.java`
- Modify: `CampaignRunner.java` (after a successful send), `CampaignSenderService.java` (`refresh` day roll)
- Test: `campaign/SenderHealthIT.java`

**Interfaces:**
- Produces: `@Component SenderHealth { void afterSend(String phoneNumberId, CampaignSettings s, Instant now); Boolean lowReplyRate(String phoneNumberId, Instant now); }`:
  - `afterSend`: the sender's 5 latest recipients with `sentAt <= now - 10 min` (status SENT/DELIVERED/READ); exactly 5 and all SENT → `pausedUntil = SenderPolicy.endOfDay`, `pauseReason "UNDELIVERED_RUN"`.
  - `lowReplyRate`: the 30 latest with `sentAt <= now - 24 h`; fewer than 30 → null (keep); else `count(repliedAt != null) < 3`.
  - `refresh` sets `lowReplyRate` from it on the day roll (when non-null).

- [ ] **Step 1: Write the failing `SenderHealthIT`** (recipients seeded directly for n1)

```java
@Test void fiveUndeliveredSendsPauseForTheDay()     // 5 SENT at now-11..15 min, then a runner send → pausedUntil 2026-10-21T18:30:00Z, pauseReason UNDELIVERED_RUN; GET /senders shows it; next tick sends nothing; next day 10:00 IST sends
@Test void oneDeliveredBreaksTheRun()               // 4 SENT + 1 DELIVERED → no pause
@Test void youngSendsDoNotCount()                   // 5 SENT at now-11..15 min plus 1 SENT at now-1 min: the young one is ignored and the five still pause
@Test void lowReplyRateHalvesFromTheNextDay()       // 30 sends ≥ 24 h old, 2 replied → after the day roll lowReplyRate true, capToday 2 (age 1)
@Test void enoughRepliesClearIt()                   // lowReplyRate true, 30 old sends with 3 replied → false on the next day roll
@Test void fewerThanThirtyKeepsTheFlag()            // 29 old sends, 0 replied → unchanged
@Test void resumeClearsAnActivePause()              // UNDELIVERED_RUN pause, PUT action RESUME → next tick sends
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=SenderHealthIT` → FAIL (no pause set).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q test -Dtest=SenderHealthIT,CampaignRunnerIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign/SenderHealthIT.java
git commit -m "feat(campaign): pause a sender after undelivered runs and halve it on low reply rates"
```

---

### Task 11: Acks, replies and stop words

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/campaign/CampaignMessageListener.java`
- Test: `campaign/CampaignRepliesIT.java`

**Interfaces:**
- Consumes: `InboxEvents.MessageUpserted`, Task 8 `findFirstByChatIdAndDirectionOrderByTimestampDesc`, Task 1 `markDoNotContact`, Task 7 `CampaignCounter`.
- Produces: `@Component CampaignMessageListener { @EventListener void on(InboxEvents.MessageUpserted e); }`:
  - OUT with `campaignId`: recipient by `messageId`; message DELIVERED → recipient DELIVERED (from SENT) with `deliveredAt`; READ or PLAYED → READ (from SENT/DELIVERED) with `readAt` (and `deliveredAt` when null). Refresh counts.
  - IN and `inserted`: `campaignLast` = the chat's latest OUT message has `campaignId`. Neither `chat.campaignId` nor `campaignLast` → nothing. Else the chat's latest recipient (by `sentAt` desc) gets `repliedAt = now` when null; refresh counts. When `campaignLast` and the body stripped and lower-cased equals one of `stopWords`: `markDoNotContact(ws, chat.contactId, STOP_REPLY, that message's campaignId)`, then every PENDING recipient of the workspace with that phone (recipient phone, else contact phone) → SKIPPED `DO_NOT_CONTACT`; refresh those campaigns' counts. The chat is left as it is.

- [ ] **Step 1: Write the failing `CampaignRepliesIT`** — fixture `sent()`: a RUNNING campaign, a SENT recipient for `919811100001` with `chatId`/`messageId`, its `WaWebChat` (campaignId set, OPEN) and OUT `WaWebMessage` (`campaignId`, `waMessageId "true_919811100001@c.us_1"`, status SENT) saved directly; helpers `ack(String waMessageId, int ack)` and `inbound(String remoteId, String body)` publish `WhatsAppEvent`s for n1 like `WaWebInboxListenerIT` (`message_ack` with `message.id._serialized` + `ack`; `message_create` with `id.remote`, `fromMe false`, `body`, a fresh id).

```java
@Test void acksMoveTheRecipient()                    // ack 2 → DELIVERED deliveredAt; ack 3 → READ readAt; counts delivered 1, read 1, sent 1
@Test void playedCountsAsRead()                      // ack 4 → READ
@Test void replyIsRecordedOnce()                      // inbound "hi" → repliedAt now, counts.replied 1; second inbound later → repliedAt unchanged
@Test void stopWordMarksTheContactAndSkipsPendingEverywhere() {
    // a second RUNNING campaign has a PENDING recipient for 919811100001
    inbound("919811100001@c.us", "  Don't Message ");
    Contact c = contacts.findByWorkspaceIdAndPhone(brand.getId(), "919811100001").orElseThrow();
    assertThat(c.getDoNotContact().reason()).isEqualTo(DoNotContact.Reason.STOP_REPLY);
    assertThat(c.getDoNotContact().campaignId()).isEqualTo(first.getId());
    assertThat(recipient(second, "919811100001").getSkipReason()).isEqualTo(SkipReason.DO_NOT_CONTACT);
    assertThat(campaign(second).getCounts().skipped()).isEqualTo(1);
    assertThat(chat().getStatus()).isEqualTo(ChatStatus.OPEN);
}
@Test void stopAfterAnAgentWroteIsAnAnswer()          // a later CONSOLE OUT message, then "no" → no mark, repliedAt still set
@Test void stopWordsComeFromSettings()                 // stopWords ["nahi"] → "Nahi" marks; "stop" does not
@Test void plainChatsAreIgnored()                      // chat without campaignId and a console last message → nothing written
@Test void listenerFailureDoesNotBreakIngestion()      // CampaignCounter made to throw via @MockitoSpyBean → the inbound message is still stored and ChatChanged is published
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=CampaignRepliesIT` → FAIL.

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q test -Dtest=CampaignRepliesIT,WaWebInboxListenerIT` → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign/CampaignMessageListener.java src/test/java/me/nageshk/omnichannel/campaign/CampaignRepliesIT.java
git commit -m "feat(campaign): track delivery, replies and stop words of campaign messages"
```

---

### Task 12: Estimate, list, detail, recipients

**Files:**
- Create in `campaign/`: `CampaignEstimates.java`, `CampaignQueryService.java` (with nested `CampaignPage`, `RecipientPage`), `RecipientView.java`
- Modify: `CampaignViews.java`, `CampaignController.java`
- Test: `campaign/CampaignEstimatesTest.java` (unit), `campaign/CampaignReadIT.java`

**Interfaces:**
- Produces:
  - `final class CampaignEstimates { static Estimate of(int remaining, int perDay, CampaignSettings s, Instant now); }` — `remaining == 0` → `days 0`, `finishBy null`; `perDay == 0` → `days null`, `finishBy null`; else `days = ceil(remaining / perDay)` and `finishBy` the `days`-th working day, counting today when it is a working day and the local time is before `endTime`.
  - `CampaignViews.detail(Campaign c)` fills `estimate` (`remaining = counts.pending`, `perDay` = Σ `capToday` of the campaign's senders in WARMING/ACTIVE) and `senders = senderService.views(ws, c.getSenderIds())`; every endpoint that returns one campaign returns it.
  - Nested in `CampaignQueryService`: `public record CampaignPage(List<CampaignView> items, long total, int page, boolean systemPaused)` — items from `CampaignViews.summary(Campaign c)` (the `CampaignView` with `estimate` and `senders` null, maintained counts).
  - `public record RecipientView(String id, String phone, String contactId, String contactName, RecipientStatus status, SkipReason skipReason, String senderId, String senderNumber, String templateId, Instant attemptedAt, Instant sentAt, Instant deliveredAt, Instant readAt, Instant repliedAt, String error, int attempts)` — `phone = phone ?? rawPhone`; names and numbers loaded once per page; `public record RecipientPage(List<RecipientView> items, long total, int page)`.
  - `GET /campaigns?status=&page=` (`campaign:view`) → `CampaignPage`, 50 per page, `creationTime` desc; `systemPaused` = any campaign of the workspace PAUSED by SYSTEM.
  - `GET /campaigns/{id}` → detail, counts refreshed first.
  - `GET /campaigns/{id}/recipients?status=&page=` (`campaign:view`) → `RecipientPage`, 50 per page, `_id` asc.

- [ ] **Step 1: Write the failing tests**

`CampaignEstimatesTest` (default settings):
```java
@Test void countsTodayWhileTheDayIsOpen() { assertThat(CampaignEstimates.of(100, 35, s, WED_11_IST)).isEqualTo(new Estimate(100, 35, 3, LocalDate.of(2026, 10, 23))); }
@Test void afterHoursStartsTomorrow()      { assertThat(CampaignEstimates.of(100, 35, s, Instant.parse("2026-10-21T13:30:00Z")).finishBy()).isEqualTo(LocalDate.of(2026, 10, 24)); }
@Test void sundayIsSkipped()               { assertThat(CampaignEstimates.of(10, 5, s, Instant.parse("2026-10-24T13:30:00Z")).finishBy()).isEqualTo(LocalDate.of(2026, 10, 27)); }
@Test void noSenderMeansNoDays()           { assertThat(CampaignEstimates.of(10, 0, s, WED_11_IST).days()).isNull(); }
@Test void nothingLeftIsZeroDays()         { assertThat(CampaignEstimates.of(0, 0, s, WED_11_IST).days()).isZero(); }
```
`CampaignReadIT`:
- `detailCarriesCountsEstimateAndSenders` — started campaign of 12 on n1 (WARMING, cap 5) and n2 (set RESTING after the start) → `estimate.perDay 5`, `days 3`, `finishBy "2026-10-23"`, `senders` has both with `state`; counts recomputed after a recipient is changed directly in Mongo.
- `listIsNewestFirstAndFiltersByStatus` — three campaigns → order by creation desc; `?status=DRAFT` → only drafts; 51 campaigns → page 0 has 50, `total 51`, page 1 has 1.
- `systemPausedBanner` — one PAUSED by SYSTEM → `systemPaused true`.
- `recipientsShowNamesSendersAndSkips` — `?status=SKIPPED` lists a DUPLICATE row with the raw phone; a SENT row shows `contactName`, `senderNumber`; paged by 50.
- `readsNeedCampaignView` — a role without `campaign:view` → 403 (custom role as in `NumberLookupIT.requiresInboxSend`); agent → 200.

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=CampaignEstimatesTest,CampaignReadIT` → compilation failure.

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes** — same command → PASS; `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): campaign list, detail with estimate and senders, recipients"
```

---

### Task 13: Pause, resume, terminate

**Files:**
- Modify: `CampaignLifecycleService.java`, `CampaignController.java`
- Test: `campaign/CampaignLifecycleIT.java`

**Interfaces:**
- Produces: `CampaignLifecycleService.pause|resume|terminate(String workspaceId, String id) -> Campaign` and `POST /campaigns/{id}/pause|resume|terminate` (`campaign:manage`) → detail `CampaignView`. Each is one conditional update; no match → `CAMPAIGN_STATE("paused"|"resumed"|"terminated")`:
  - pause: RUNNING → PAUSED, `pausedBy USER`, `pauseReason null`.
  - resume: PAUSED (USER or SYSTEM) → RUNNING, unset `pausedBy`, `pauseReason`.
  - terminate: RUNNING or PAUSED → TERMINATED, `finishedAt now`; then every PENDING recipient → SKIPPED `CANCELLED`; SENDING ones finish; refresh counts.

- [ ] **Step 1: Write the failing `CampaignLifecycleIT`**

```java
@Test void pauseStopsClaimsAndResumeContinues()    // with AutoDevice: pause → tick sends nothing; resume → tick sends; pausedBy null after resume
@Test void resumeAcknowledgesASystemPause()        // PAUSED SYSTEM TWO_LOGOUTS → resume → RUNNING, pausedBy/pauseReason null
@Test void terminateCancelsWhatIsPending()          // 3 PENDING + 1 SENDING → TERMINATED, 3 SKIPPED CANCELLED, SENDING untouched, counts.skipped 3, finishedAt set; terminate of a PAUSED works too
@Test void illegalTransitionsAre409() {             // each → 409 CAMPAIGN_STATE with exact message
    assertState(api(POST, "/campaigns/" + draft + "/pause", manager, null), "This campaign cannot be paused now.");
    assertState(api(POST, "/campaigns/" + running + "/resume", manager, null), "This campaign cannot be resumed now.");
    assertState(api(POST, "/campaigns/" + completed + "/terminate", manager, null), "This campaign cannot be terminated now.");
    assertState(api(POST, "/campaigns/" + running + "/start", manager, null), "This campaign cannot be started now.");
}
@Test void lifecycleNeedsCampaignManage()           // agent → 403; another brand's campaign → 404
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=CampaignLifecycleIT` → FAIL (404).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q test -Dtest=CampaignLifecycleIT` → PASS; `./mvnw -q test` → exit 0 (includes `AuthorityCheckScannerIT`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign/CampaignLifecycleIT.java
git commit -m "feat(campaign): pause, resume and terminate campaigns"
```

---

## Contract for the console

The single truth for both plans. Paths under `/api/workspaces/{ws}`. Pages are 0-based (`page` defaults to 0 when absent; the console always sends it), 50 per page. Instants are ISO-8601 strings (`"2026-10-21T05:30:00Z"`), dates `"YYYY-MM-DD"`. Fields marked *(extra)* are additions the console may ignore.

**Errors.** `{code, message, field?}` for the coded errors of Global Constraints; `SENDER_NOT_AVAILABLE` also carries `number` (digits of the first unavailable sender). `VALIDATION` `{code, message, fields: {<field>: <text>}}`; `DUPLICATE` `{code, message, field}`; 403 `FORBIDDEN`; 404 `NOT_FOUND`; 409 `CONFLICT` for a stale settings `version`.

**Contacts (Task 1)**
- `ContactView.doNotContact: {at: string, reason: "STOP_REPLY"|"MANUAL", campaignId: string|null} | null`.
- `ContactFilterSpec.doNotContact?: boolean|null` (true only marked, false only unmarked).
- `PUT /contacts/{id}/do-not-contact` (no body) marks MANUAL → 200 `ContactView`; `DELETE /contacts/{id}/do-not-contact` clears → 200 `ContactView`. Both `contact:edit`.

**Inbox (Task 8).** `MessageSource` gains `"CAMPAIGN"`; a campaign message has `sentBy: {id: <campaignId>, name: "Campaign: <name>"}`. Chat actions by a campaign (REOPENED) have `by: {id: null, name: "Campaign: <name>"}`.

**Settings (Task 2)** — `campaign:settings`
```
CampaignSettings = { timezone: string, defaultCountryCode: string, workingDays: ("MONDAY"|…|"SUNDAY")[], startTime: "HH:mm", endTime: "HH:mm",
                     gapMinSeconds: number, gapMaxSeconds: number, skipGapMinSeconds: number, skipGapMaxSeconds: number, hourlyCap: number,
                     warmupCaps: {fromDay: number, cap: number}[], matureCap: number, healthyCap: number, restDays: number,
                     recontactDays: number, stopWords: string[], version: number }
GET /campaigns/settings                    → CampaignSettings   (created with defaults on first read)
PUT /campaigns/settings  CampaignSettings  → CampaignSettings   (VALIDATION `fields` keys are these names)
```

**Senders (Task 3)**
```
SenderView = { phoneNumberId, number, label: string|null,
               phoneStatus: "DISABLED"|"NOT_CONNECTED_YET"|"WAITING_FOR_LOGIN"|"CONNECTED"|"WRONG_NUMBER"|"DISCONNECTED",
               state: "WARMING"|"ACTIVE"|"RESTING"|"SUSPENDED"|"RETIRED", ageDay: number, capToday: number (already halved when lowReplyRate),
               usedToday: number, hourlyUsed: number, restUntil: string|null, pausedUntil: string|null, pauseReason: "UNDELIVERED_RUN"|null,
               lowReplyRate: boolean, halfPaceUntil: string|null, runsOn: string|null,
               ready: boolean, logoutAt, lastSendAt, nextSendAt, warmupStartedAt: string|null   (extra) }
GET /campaigns/senders                                                       → SenderView[]   (campaign:view)
PUT /campaigns/senders/{phoneNumberId} { runsOn?: string|null, action?: "REST"|"RETIRE"|"REACTIVATE"|"RESUME" } → SenderView  (campaign:settings)
    without action: runsOn is set (blank clears); with action: runsOn is ignored
```

**Campaigns (Tasks 4, 5, 7, 12, 13)**
```
PlaceholderSource = { kind: "CONTACT_FIELD", field: "name"|"phone" } | { kind: "CONTACT_ATTRIBUTE", attributeId: string }
                  | { kind: "CSV_COLUMN", column: string } | { kind: "FIXED", text: string }
CampaignPlaceholder = { name: string, source: PlaceholderSource, fallback: string }      ("" for none)
CampaignRequest = { name: string, audience: { kind: "FILTER", filterId: string } | { kind: "CSV", phoneColumn: string, nameColumn: string|null },
                    templateIds: string[], placeholders: CampaignPlaceholder[], senderIds: string[] }
CampaignCounts = { total, pending, sent, delivered, read, replied, skipped, failed: number }
                 pending = PENDING+SENDING; sent = SENT+DELIVERED+READ; delivered = DELIVERED+READ; read = READ; replied = recipients with repliedAt
CampaignView = { id, name, status: "DRAFT"|"RUNNING"|"PAUSED"|"COMPLETED"|"TERMINATED", pausedBy: "USER"|"SYSTEM"|null,
                 pauseReason: "TWO_LOGOUTS"|null,
                 audience: { kind: "FILTER"|"CSV", filterId: string|null, filterName: string|null, csvFileName: string|null,
                             csvHeaders: string[]|null, csvRows: number|null, phoneColumn: string|null, nameColumn: string|null,
                             filterSpec: ContactFilterSpec|null (extra) },
                 templateIds: string[], templateNames: string[], placeholders: CampaignPlaceholder[], senderIds: string[],
                 counts: CampaignCounts, creationTime: string, startedAt: string|null, finishedAt: string|null, version: number,
                 createdBy: string, lastUpdateTime: string (extra),
                 estimate: CampaignEstimate|null, senders: SenderView[]|null }   (both null in list items; set on every single-campaign reply)
CampaignEstimate = { remaining: number, perDay: number, days: number|null, finishBy: "YYYY-MM-DD"|null }
RecipientView = { id, phone: string, contactId: string|null, contactName: string|null,
                  status: "PENDING"|"SENDING"|"SENT"|"DELIVERED"|"READ"|"FAILED"|"SKIPPED",
                  skipReason: "NOT_ON_WHATSAPP"|"DO_NOT_CONTACT"|"RECENTLY_MESSAGED"|"NO_PHONE"|"INVALID_PHONE"|"DUPLICATE"|"CHAT_ON_OTHER_NUMBER"|"CANCELLED"|null,
                  senderId: string|null, senderNumber: string|null, sentAt: string|null, repliedAt: string|null, error: string|null,
                  templateId, attemptedAt, deliveredAt, readAt: string|null, attempts: number (extra) }

GET    /campaigns?status=&page=0          → { items: CampaignView[], total: number, page: number, systemPaused: boolean }   (campaign:view)
       systemPaused: any campaign of the workspace (not only this page) is PAUSED with pausedBy SYSTEM
POST   /campaigns  application/json CampaignRequest (FILTER)                                     → 201 CampaignView (campaign:manage)
POST   /campaigns  multipart: campaign (Blob, application/json CampaignRequest) + file (CSV)     → 201 CampaignView
GET    /campaigns/{id}                                                                           → CampaignView     (campaign:view)
PUT    /campaigns/{id}  same two forms, multipart `file` optional (DRAFT only)                   → CampaignView     (campaign:manage)
DELETE /campaigns/{id}  (DRAFT only)                                                             → 204              (campaign:manage)
POST   /campaigns/{id}/start | /pause | /resume | /terminate                                      → CampaignView     (campaign:manage)
GET    /campaigns/{id}/recipients?status=&page=0 → { items: RecipientView[], total: number, page: number }        (campaign:view)
GET    /campaigns/templates/{templateId}/sample-csv → text/csv; charset=UTF-8 (attachment "sample.csv")            (campaign:view)
POST   /campaigns/csv/preview  multipart file [, templateIds=…]
       → { headers: string[], sample: string[][], rows: number, suggested: { phone: string|null, placeholders: {[name: string]: string} (extra) } }  (campaign:manage)
```
Validation the console mirrors: `MAPPING_COLUMN_REUSED` only when two placeholders share a CSV column (`phoneColumn`/`nameColumn` do not count); `TEMPLATE_HAS_LINK` has `field: "templateIds"` (or `"placeholders"` for a FIXED text/fallback with a link).

## Console must change

None. Every assumption in the console plan's "Contract assumptions" (1–16) is adopted; the backend only adds fields marked *(extra)*, a `PUT /campaigns/{id}` the console does not call, and the optional `templateIds` on the CSV preview.
