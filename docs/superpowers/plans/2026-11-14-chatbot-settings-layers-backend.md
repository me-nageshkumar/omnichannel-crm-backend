# Chatbot Settings Layers — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chatbot settings resolved field by field from three layers (chatbot → brand → platform default), with
platform and brand endpoints, the chatbot layer on the existing save, and a one-time migration of today's chatbots.

**Architecture:** A nullable `ChatbotSettingsLayer` record is the stored shape at every layer; a pure merge turns
chatbot + brand + platform layers into today's resolved `ChatbotSettings` record, which the run path keeps using.
Brand and platform layers live in a new `chatbotSettings` collection behind `ChatbotSettingsService`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data Mongo, Jackson 3, JUnit 5 + AssertJ, Testcontainers ITs.

**Spec:** `docs/superpowers/specs/2026-11-14-chatbot-settings-layers-design.md`

## Global Constraints

- Work in `.worktrees/chatbot-settings-layers` (branch `chatbot-settings-layers`); one Maven build at a time.
- Collections camelCase: `chatbotSettings`, `dataMigrations`.
- Nine fields, exact names: `reminderOn, reminderAfterMinutes, reminderMessage, endAfterMinutes, endMessage,
  invalidReplyRetries, retryMessage, giveUpMessage, stopOnAgentReply`.
- Ranges: reminder 1–1440, end 5–2880, retries 0–5, every message ≤1024 characters.
- Code defaults: `false, 30, "Are you still there? Reply to carry on.", 120, none, 2, "Sorry, I didn't get that.
  Please choose one of the options.", none, true`.
- `null` = inherited; `""` = "send no message", allowed only for `endMessage` and `giveUpMessage`.
  `reminderMessage` and `retryMessage` can never be blank at any layer.
- New authority `chatbot-defaults:manage`, `RoleLevel.PLATFORM`, description "Set the chatbot settings every brand
  starts with", given to no built-in role (the Super Admin holds every PLATFORM code automatically).
- Brand settings: GET `chatbot:view`, PUT `chatbot:manage`. No change to any existing role.
- Field error keys: `settings.<field>` (platform), `own.<field>` (brand), `settings.<field>` (chatbot save).
- Test first; Prettier only on touched files; commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A chatbot saved by the old console (full old-shape `settings`, `reminderAfterMinutes: null`) after the migration
   ran — expected: accepted as a layer; `reminderAfterMinutes: null` just means inherited, nothing breaks. (Task 3
   test `oldShapeSaveIsAcceptedAsALayer`.)
2. Brand `PUT` with an empty `own` (all null) — expected: stored, every field follows the platform. (Task 2 test
   `emptyBrandLayerFollowsThePlatform`.)
3. Whitespace-only `endMessage` `"   "` at a layer — expected: treated as `""` (none), not as a message of spaces.
   (Task 1 test `blankOptionalMessageMeansNone`.)
4. A run waiting with `remindAt` set when the brand turns `reminderOn` off — expected: no reminder. (Task 3 test
   `reminderTurnedOffAfterSchedulingIsNotSent`.)
5. The migration on a database with no `chatbots` collection, and on a second start — expected: no error, nothing
   changed the second time even for a field later set explicitly to a default value. (Task 4 tests.)

---

### Task 1: Layer model, merge and validation (pure)

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/ChatbotSettingsLayer.java`
- Modify: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/ChatbotSettings.java`
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/settings/SettingsLayers.java`
- Modify (to keep the build green on code defaults): `omnichannel-db/.../db/chatbot/Chatbot.java` (`settings` becomes
  `ChatbotSettingsLayer`), `ChatbotService.java` (create stores `empty()`, save uses `SettingsLayers.check`, remove
  `checkSettings`), `ChatbotViews.java` (`ChatbotView.settings`, `SaveRequest.settings` become the layer),
  `run/ChatbotRunner.java:118`, `run/ChatbotTicker.java:92,140`, `run/ChatbotHandoverListener.java:41`,
  `run/ChatbotTimers.java:22`, `StartChatbotActionIT.java:366`, `ChatbotModelIT.java:48`, and any chatbot IT that turns
  the reminder on by sending `reminderAfterMinutes` (now also sends `reminderOn: true`)
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/settings/SettingsLayersTest.java`

**Interfaces:**
- Produces:
  - `record ChatbotSettingsLayer(Boolean reminderOn, Integer reminderAfterMinutes, String reminderMessage,
    Integer endAfterMinutes, String endMessage, Integer invalidReplyRetries, String retryMessage,
    String giveUpMessage, Boolean stopOnAgentReply)` with `static ChatbotSettingsLayer empty()`.
  - `ChatbotSettings` becomes `(boolean reminderOn, int reminderAfterMinutes, String reminderMessage,
    int endAfterMinutes, String endMessage, int invalidReplyRetries, String retryMessage, String giveUpMessage,
    boolean stopOnAgentReply)`; `endMessage`/`giveUpMessage` are `null` when none. `static ChatbotSettings
    codeDefaults()` (values from Global Constraints) replaces `defaults()`; keep `DEFAULT_RETRY_MESSAGE`, add
    `DEFAULT_REMINDER_MESSAGE`. `static ChatbotSettingsLayer ChatbotSettings#asLayer()` (none → `""`).
  - `enum SettingSource { CHATBOT, BRAND, PLATFORM }` (in `service.chatbot.settings`).
  - `SettingsLayers.FIELDS: List<String>` (the nine names, in order).
  - `static ChatbotSettingsLayer SettingsLayers.over(ChatbotSettingsLayer top, ChatbotSettingsLayer below)` — field by
    field `top ?? below`; either may be null (treated as empty).
  - `static ChatbotSettings SettingsLayers.resolve(ChatbotSettingsLayer complete)` — requires every field non-null
    except the optional messages; blank optional message → `null`.
  - `static Map<String, SettingSource> SettingsLayers.sources(ChatbotSettingsLayer chatbot, ChatbotSettingsLayer brand)`
    — per field CHATBOT if set on chatbot, else BRAND if set on brand, else PLATFORM.
  - `static ChatbotSettingsLayer SettingsLayers.check(ChatbotSettingsLayer layer, String prefix, boolean complete)` —
    returns the normalised layer (optional messages trimmed; whitespace-only → `""`) or throws `ValidationException`
    with keys `prefix + "." + field`. `complete=true` (platform) additionally requires every field except the two
    optional messages.

- [ ] **Step 1: Write the failing tests** in `SettingsLayersTest`:
  - `chatbotWinsOverBrandWinsOverPlatform`: platform `codeDefaults().asLayer()`, brand sets `endAfterMinutes=60,
    retryMessage="B"`, chatbot sets `endAfterMinutes=30` → resolved `endAfterMinutes==30`, `retryMessage=="B"`,
    `invalidReplyRetries==2`; `sources` gives `endAfterMinutes=CHATBOT, retryMessage=BRAND, invalidReplyRetries=PLATFORM`.
  - `noneAtChatbotBeatsBrandMessage`: brand `endMessage="Bye"`, chatbot `endMessage=""` → resolved `endMessage` null.
  - `nullInheritsNone`: brand `endMessage=""`, chatbot null → null; brand null, platform `"P"` → `"P"`.
  - `blankOptionalMessageMeansNone`: `check(layer with endMessage="   ")` returns `endMessage==""`.
  - `rangesAreCheckedPerField`: `reminderAfterMinutes=0` → key `own.reminderAfterMinutes` "must be 1 to 1440 minutes";
    `endAfterMinutes=4` → "must be 5 to 2880 minutes"; `invalidReplyRetries=6` → "must be 0 to 5"; a 1025-char
    `giveUpMessage` → "must be at most 1024 characters".
  - `requiredMessagesCannotBeBlank`: `retryMessage=""` and `reminderMessage=" "` → "is required" on both keys.
  - `completeLayerNeedsEveryRequiredField`: `check(empty(), "settings", true)` errors on the seven required fields and
    not on `endMessage`/`giveUpMessage`.
  - `reminderBeforeEndIsNotASaveRule`: `reminderAfterMinutes=200, endAfterMinutes=100` passes `check`.
  - `codeDefaultsMatchTheSpec`: every value of `codeDefaults()` as in Global Constraints.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-db,omnichannel-service -am test -Dtest=SettingsLayersTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation FAIL (types missing).
- [ ] **Step 3: Implement** the records and `SettingsLayers` as in Interfaces, then rewire the callers listed under
  Files so behaviour equals today's: every place that built a `ChatbotSettings` from `chatbot.getSettings()` uses
  `SettingsLayers.resolve(SettingsLayers.over(chatbot.getSettings(), ChatbotSettings.codeDefaults().asLayer()))`
  (Task 3 swaps in the brand and platform layers); `ChatbotTimers.onWait` and the ticker's reminder check use
  `reminderOn()` instead of a null minutes value; the hand-over listener reads the resolved `stopOnAgentReply()`.
- [ ] **Step 4: Run** the same command, then
  `./mvnw -q -pl omnichannel-app -am test -Dtest='Chatbot*IT,StartChatbotActionIT,NodeValidationIT' -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: PASS (old chatbot behaviour unchanged).
- [ ] **Step 5: Commit** `feat(chatbot): settings layers model, merge and checks`.

### Task 2: Platform and brand layers: storage, service, endpoints, authority

**Files:**
- Create: `omnichannel-db/.../db/chatbot/ChatbotSettingsDocument.java` (`@Document("chatbotSettings")`),
  `ChatbotSettingsScope.java` (`PLATFORM, BRAND`), `ChatbotSettingsDocumentRepository.java`
- Create: `omnichannel-service/.../service/chatbot/settings/ChatbotSettingsService.java`,
  `ChatbotSettingsViews.java`
- Create: `omnichannel-app/.../app/chatbot/PlatformChatbotSettingsController.java`,
  `BrandChatbotSettingsController.java`
- Modify: `omnichannel-security/.../security/access/Authority.java` (add `CHATBOT_DEFAULTS_MANAGE`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotSettingsApiIT.java`

**Interfaces:**
- Consumes: Task 1's `ChatbotSettingsLayer`, `SettingsLayers.check/over/resolve`, `ChatbotSettings.codeDefaults()`.
- Produces:
  - Document fields: `id, scope, workspaceId (null for PLATFORM), settings: ChatbotSettingsLayer, lastUpdateTime,
    updatedBy, @Version Long version`; unique compound index `{scope:1, workspaceId:1}`. Ruling: the nine fields are
    embedded as `settings` (same record as `Chatbot.settings`) rather than flat on the document — one mapping, same
    data.
  - `ChatbotSettingsService`:
    - `ChatbotSettingsLayer platformLayer()` — stored platform layer over `codeDefaults().asLayer()` (always complete).
    - `PlatformView platform()` / `PlatformView savePlatform(ChatbotSettingsLayer settings, Long version, String userId)`.
    - `BrandView brand(String workspaceId)` / `BrandView saveBrand(String workspaceId, ChatbotSettingsLayer own,
      Long version, String userId)`.
    - `ChatbotSettingsLayer inheritedFor(String workspaceId)` — brand over platform (complete).
    - `Map<String, SettingSource> inheritedSources(String workspaceId)` — BRAND or PLATFORM per field.
    - `ChatbotSettings resolve(String workspaceId, ChatbotSettingsLayer chatbotLayer)`.
    - Stale `version` → `OptimisticLockingFailureException` mapped to 409 as campaign settings are (check what
      `CampaignSettingsService` does and do the same).
  - Views (`ChatbotSettingsViews`): `PlatformView(ChatbotSettingsLayer settings, Long version, Instant lastUpdateTime,
    String updatedBy)`; `BrandView(ChatbotSettingsLayer own, ChatbotSettingsLayer inherited, Long version)` where
    `inherited` = `platformLayer()`; request records `PlatformSave(ChatbotSettingsLayer settings, Long version)`,
    `BrandSave(ChatbotSettingsLayer own, Long version)`.
  - `GET/PUT /api/platform/chatbot-settings` with `@PreAuthorize("hasAuthority('chatbot-defaults:manage')")`.
  - `GET /api/workspaces/{workspaceId}/chatbots/settings` (`@access.has(#workspaceId, 'chatbot:view')`), `PUT`
    (`chatbot:manage`).
  - Version semantics: first save with `version: null` creates; no document yet → GET returns `version: null`.

- [ ] **Step 1: Write the failing ITs** (`ChatbotSettingsApiIT`, extends `ChatbotTestBase`; super admin from
  `testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null)` as in `IsolationIT`):
  - `platformStartsWithCodeDefaults`: GET as super admin → `settings.endAfterMinutes==120`, `settings.reminderOn==false`,
    `settings.reminderAfterMinutes==30`, `settings.endMessage==""`, `version` null.
  - `platformSaveIsStoredAndValidated`: PUT `endAfterMinutes=90` → 200, GET shows 90; PUT with `retryMessage=""` → 400
    with key `settings.retryMessage`.
  - `platformNeedsTheDefaultsRight`: GET as a brand manager → 403; as a platform user with a role lacking the code → 403.
  - `brandStartsEmptyAndInheritsThePlatform`: GET as manager → every `own` field null, `inherited.endAfterMinutes==120`.
  - `brandSeesPlatformChangeInInherited`: super admin PUTs `endAfterMinutes=90` → brand GET `inherited.endAfterMinutes==90`.
  - `emptyBrandLayerFollowsThePlatform`: brand PUT `own: {}` → 200; `inheritedFor` resolves to platform values.
  - `brandPutNeedsManage`: a `chatbot:view`-only user (separate test role, never a shared one) → GET 200, PUT 403.
  - `brandSettingsAreScopedToTheWorkspace`: manager of another brand GETs this brand's path → 403/404 as the existing
    `@access.has` gives; brand A's save does not change brand B's `own`.
  - `staleSaveIs409`: two PUTs with the same `version` → second 409, at both layers.
  - `brandRejectsBadValuesByField`: `own.invalidReplyRetries=9` → 400 key `own.invalidReplyRetries`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotSettingsApiIT -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: FAIL (404s / compile errors).
- [ ] **Step 3: Implement** document, repository, service, views, both controllers and the authority enum entry
  (no role list). Register `ChatbotSettingsDocument` wherever the test base clears collections (`collections()`).
- [ ] **Step 4: Run** the same command plus `-Dtest=ChatbotSettingsApiIT,AuthorityCheckScannerIT,AuthoritiesApiIT`.
  Expected: PASS.
- [ ] **Step 5: Commit** `feat(chatbot): platform defaults and brand chatbot settings`.

### Task 3: Chatbot layer on the chatbot, and the run path resolving it

**Files:**
- Modify: `omnichannel-service/.../service/chatbot/ChatbotService.java` (view adds the inherited fields)
- Modify: `omnichannel-service/.../service/chatbot/ChatbotViews.java` (`ChatbotView`)
- Modify: `omnichannel-service/.../service/chatbot/run/ChatbotRunner.java`, `run/ChatbotTicker.java` (reminders,
  `contextFor`), `run/ChatbotHandoverListener.java` — the resolution points Task 1 made
- Test: `omnichannel-app/.../app/chatbot/ChatbotSettingsRunIT.java`; additions to `ChatbotApiIT`

**Interfaces:**
- Consumes: `ChatbotSettingsService.resolve/inheritedFor/inheritedSources`, `SettingsLayers.check`.
- Produces:
  - `ChatbotView` (whose `settings` is the own layer since Task 1) gains `ChatbotSettingsLayer inheritedSettings`
    and `Map<String, SettingSource> inheritedSources`, from `inheritedFor` / `inheritedSources`.
  - Run path: wherever a `ChatbotSettings` was built from `chatbot.getSettings()`, use
    `settingsService.resolve(chatbot.getWorkspaceId(), chatbot.getSettings())`. `ChatbotTimers.onWait` schedules a
    reminder only when `settings.reminderOn()`.
  - Ticker `reminders`: before sending, skip when `!ctx.settings().reminderOn()` or when the run's `endAt` is not
    after its `remindAt` (read both from the run found by the due query, before the claim unsets `remindAt`).
  - Hand-over listener: `if (!settingsService.resolve(...).stopOnAgentReply()) return;`.

- [ ] **Step 1: Write the failing tests**:
  - `ChatbotApiIT.newChatbotInheritsEverything`: create → `settings` all null, `inheritedSettings.endAfterMinutes==120`,
    `inheritedSources.endAfterMinutes=="PLATFORM"`; after a brand PUT of `endAfterMinutes=60` → GET shows
    `inheritedSettings.endAfterMinutes==60`, source `BRAND`.
  - `ChatbotApiIT.chatbotLayerIsSavedAndChecked`: save `settings.endAfterMinutes=30` → view `settings.endAfterMinutes==30`;
    `settings.retryMessage=""` → 400 key `settings.retryMessage`.
  - `ChatbotApiIT.oldShapeSaveIsAcceptedAsALayer`: save with the old full object (`reminderAfterMinutes: null,
    endAfterMinutes:120, invalidReplyRetries:2, retryMessage: DEFAULT_RETRY_MESSAGE, stopOnAgentReply:true`) → 200.
  - `ChatbotSettingsRunIT.brandRetryMessageReachesARunningChatbot`: a published chatbot with a button question,
    started on the fake device; brand PUTs `retryMessage="Brand retry"`; the contact sends a non-matching reply → the
    outgoing retry text is "Brand retry".
  - `ChatbotSettingsRunIT.chatbotOverrideWinsOverBrand`: chatbot `retryMessage="Bot retry"`, brand "Brand retry" →
    "Bot retry".
  - `ChatbotSettingsRunIT.brandCanTurnOffStopOnAgentReply`: brand `stopOnAgentReply=false` → an agent console reply
    leaves the run WAITING (mirror `ChatbotHandoverIT`'s setup).
  - `ChatbotSettingsRunIT.reminderTurnedOffAfterSchedulingIsNotSent`: brand `reminderOn=true, reminderAfterMinutes=1`;
    run waits (remindAt set); brand PUTs `reminderOn=false`; advance the `MutableClock` past remindAt and tick → no
    reminder sent, `reminderSent` may be true.
  - `ChatbotSettingsRunIT.reminderAtOrAfterTheEndIsNotSent`: brand `reminderOn=true, reminderAfterMinutes=10`, chatbot
    `endAfterMinutes=10` → after advancing 10 minutes and ticking, no reminder text; run ENDED.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='ChatbotApiIT,ChatbotSettingsRunIT' -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces: replace Task 1's code-defaults resolution with
  `settingsService.resolve(...)` at every point, and add the skip conditions to the ticker.
- [ ] **Step 4: Run** the same command, then the chatbot package:
  `./mvnw -q -pl omnichannel-app -am test -Dtest='Chatbot*IT,StartChatbotActionIT,NodeValidationIT' -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: PASS.
- [ ] **Step 5: Commit** `feat(chatbot): chatbots inherit brand and platform settings`.

### Task 4: One-time migration of today's chatbot settings

**Files:**
- Create: `omnichannel-db/.../db/config/ChatbotSettingsMigration.java`
- Modify: `omnichannel-db/.../db/config/CollectionRenames.java` (call it last in `afterSingletonsInstantiated`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotSettingsMigrationIT.java`

**Interfaces:**
- Produces: `ChatbotSettingsMigration.migrate() -> long` (chatbots changed). Runs once: guarded by a marker document
  `{_id: "chatbotSettingsLayers", appliedAt}` in collection `dataMigrations`, inserted after the pass. Ruling: a marker
  rather than shape detection, because after the change a chatbot may legitimately set a field to a default value.
- Rules per chatbot document (raw `Document`, not the mapped class): for each of `endAfterMinutes=120,
  invalidReplyRetries=2, retryMessage=DEFAULT_RETRY_MESSAGE, stopOnAgentReply=true` equal to the old default → unset;
  `endMessage`/`giveUpMessage` null → unset; `reminderAfterMinutes` null → unset it and `reminderMessage`;
  non-null → set `settings.reminderOn=true`, keep minutes and message. Other values stay.

- [ ] **Step 1: Write the failing ITs**:
  - `defaultsBecomeInherited`: insert a raw chatbot with the old default settings → after `migrate()` the stored
    `settings` has no keys; the API view shows `inheritedSources` all PLATFORM.
  - `customValuesStayOnTheChatbot`: old settings with `endAfterMinutes=45, retryMessage="Custom"` → those two stay,
    the rest unset.
  - `reminderIsSplit`: `reminderAfterMinutes=20, reminderMessage="Ping"` → `reminderOn=true, reminderAfterMinutes=20,
    reminderMessage="Ping"`.
  - `runsOnlyOnce`: migrate, then set a chatbot's `settings.endAfterMinutes=120` explicitly, migrate again → it stays,
    and the second call returns 0.
  - `noChatbotsCollectionIsFine`: drop `chatbots` → `migrate()` returns 0, marker written.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotSettingsMigrationIT -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: FAIL.
- [ ] **Step 3: Implement** the migration; log the count as `MessageTypeMigration` does.
- [ ] **Step 4: Run** the same command. Expected: PASS. Then the whole build once: `./mvnw -q verify > $LOG 2>&1;
  echo exit $?` and check for no `Failures: [1-9]|Errors: [1-9]` lines. Expected: exit 0.
- [ ] **Step 5: Commit** `feat(chatbot): migrate stored chatbot settings to layers`; add a known-limits/project note
  only if behaviour differs from the spec.
