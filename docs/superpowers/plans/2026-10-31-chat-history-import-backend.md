# Chat History Import (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A resumable, idempotent backend job that pulls a WhatsApp Web number's chat history (all chats, chosen contacts, or one chat; optional date range) through the extension into the inbox, with a REST API for the console.

**Architecture:** Two new collections (`waWebHistoryImports`, `waWebHistoryImportChats`) hold the job and its per-chat cursors, mirroring campaigns/recipients. A ticker on its own scheduler runs `HistoryImportRunner`, which calls the page methods `listChats` / `fetchChatHistory` through `RpcDispatcher` one page at a time and stores messages through a side-effect-free `InboxIngestService.importMessage`. A controller under `/api/workspaces/{ws}/inbox` exposes create/list/get/chats/cancel behind the new authority `inbox:import-history`.

**Tech Stack:** Java 21, Spring Boot 4 (Jackson 3 `tools.jackson`), Spring Data MongoDB, Lombok, JUnit 5 + AssertJ, embedded Mongo (flapdoodle) for `*IT` tests run by surefire. Repo: `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend`. Modules: `omnichannel-common`, `omnichannel-db`, `omnichannel-security`, `omnichannel-service`, `omnichannel-app`.

**Spec:** `docs/superpowers/specs/2026-10-31-chat-history-import-design.md` §4–§7, §9.

## Global Constraints

- Collections and GridFS buckets are camelCase (`waWebHistoryImports`, `waWebHistoryImportChats`), never snake_case.
- Every document carries `customerId` and `workspaceId`; every endpoint is workspace-scoped and guarded by `@PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")`.
- The import never publishes `ChatCreated`, `ChatReopened`, `CampaignChatFirstReply`, `MessageUpserted` or `ActionRecorded`; only `ChatChanged`, once per finished chat item.
- Imported rows: `statusTimes` null; existing rows: status up only, `statusTimes` untouched.
- Device waits run on `historyImportScheduler`, never on `taskScheduler`.
- Run tests with `./mvnw -q -pl omnichannel-app -am test -Dtest=<Class>` (surefire includes `*IT`); the full build is `./mvnw -q test`.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A second identical request while the first is still active must return the first (200), while a different range on the same number must queue a new one — tests in Task 4 and Task 9.
2. A message already stored by the live path with a higher status than the import sees must keep its status and its `statusTimes` — test in Task 3.
3. A `DEVICE_DISCONNECTED` in the middle of a chat must leave the item PENDING with its cursor and the import RUNNING with `waiting`, and the next tick must continue from the cursor — test in Task 7.
4. A chat the import creates must be CLOSED with `unreadCount 0` and must not be routed; an inbound message arriving later must reopen and route it — test in Task 3 (closed, no `ChatCreated`) and existing routing tests cover the reopen.
5. Cancelling mid-chat must leave the item PENDING and the import CANCELLED with `active=false`, so a new identical request may start — test in Task 7.

---

### Task 1: Configuration, authority and scheduler

**Files:**
- Modify: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/config/OmnichannelProperties.java` — add `History history` to the record.
- Modify: `omnichannel-app/src/main/resources/application.yml` — `omnichannel.history` block.
- Modify: `omnichannel-app/src/test/resources/application.yml` — `omnichannel.history.auto-tick: false`, `page-delay: 0ms`, `chat-delay: 0ms`.
- Modify: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/access/Authority.java` — add `INBOX_IMPORT_HISTORY` after `INBOX_SETTINGS`.
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/history/HistoryImportSchedulerConfig.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportConfigIT.java`.

**Interfaces:**
- Produces: `OmnichannelProperties.History(@DefaultValue("true") boolean autoTick, @DefaultValue("5s") Duration tick, @DefaultValue("100") int pageSize, @DefaultValue("20") int maxLoads, @DefaultValue("400ms") Duration pageDelay, @DefaultValue("1s") Duration chatDelay, @DefaultValue("5000") int maxMessagesPerChat, @DefaultValue("50") int maxEmptyPages, @DefaultValue("60s") Duration timeBudget)` reachable as `props.history()`.
- Produces: `Authority.INBOX_IMPORT_HISTORY("inbox:import-history", RoleLevel.BRAND, "Pull chat history from the phone into the inbox", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`. `RoleSeeder` hands it to those roles on the next start with no further change.
- Produces: bean `ThreadPoolTaskScheduler historyImportScheduler` (pool 2, prefix `history-`, daemon, `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(75)`), a copy of `CampaignSchedulerConfig`.

- [ ] **Step 1: Write the failing test** `HistoryImportConfigIT` (`@SpringBootTest`): `propertiesHaveDefaults` asserts `props.history().pageSize() == 100`, `maxMessagesPerChat() == 5000`, `autoTick()` is false (test yml), `pageDelay()` is `Duration.ZERO`; `authorityIsSeededToManagers` asserts `roles.findByName(BuiltInRoles.WORKSPACE_MANAGER).get().getAuthorities()` contains `"inbox:import-history"` and the AGENT role's does not; `schedulerBeanExists` asserts `context.getBean("historyImportScheduler", ThreadPoolTaskScheduler.class).getThreadNamePrefix()` equals `"history-"`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=HistoryImportConfigIT` → compilation fails (`history()` missing).
- [ ] **Step 3: Implement** the record, yml blocks (`omnichannel.history.auto-tick: true`, `tick: 5s`, `page-size: 100`, `max-loads: 20`, `page-delay: 400ms`, `chat-delay: 1s`, `max-messages-per-chat: 5000`, `max-empty-pages: 50`, `time-budget: 60s`), the enum constant, and the config class.
- [ ] **Step 4: Run** the test → PASS.
- [ ] **Step 5: Commit** — `feat(history): properties, inbox:import-history authority, scheduler`

---

### Task 2: Entities and repositories

**Files:**
- Create in `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/inbox/`: `WaWebHistoryImport.java`, `HistoryImportStatus.java`, `HistoryScopeKind.java`, `HistoryWaiting.java`, `WaWebHistoryImportRepository.java`, `WaWebHistoryImportChat.java`, `HistoryChatStatus.java`, `HistorySkipReason.java`, `WaWebHistoryImportChatRepository.java`.
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/InboxCleanup.java` — delete both by `phoneNumberId`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportIndexesIT.java`.

**Interfaces:**
- `enum HistoryImportStatus { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }`, `enum HistoryScopeKind { ALL, CHATS }`, `enum HistoryWaiting { NO_DEVICE, WHATSAPP_NOT_READY }`, `enum HistoryChatStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED }`, `enum HistorySkipReason { NO_CHAT }`.
- `@Document("waWebHistoryImports") @Getter @Setter class WaWebHistoryImport`: `@Id String id; String customerId, workspaceId, phoneNumberId; Scope scope; Range range; String scopeKey; boolean active; HistoryImportStatus status; HistoryWaiting waiting; Counts counts; String requestedByUserId, requestedByName, error; @CreatedDate Instant creationTime; Instant startedAt, finishedAt; @LastModifiedDate Instant lastUpdateTime; @Version Long version`. Nested `record Scope(HistoryScopeKind kind, List<String> remoteIds, List<String> phones)`, `record Range(Instant from, Instant to)`, `record Counts(int chatsTotal, int chatsDone, int chatsSkipped, int chatsFailed, long messagesSeen, long messagesInserted, long messagesUpdated) { static final Counts ZERO }`. Indexes: `phone_scope_active` `{'phoneNumberId':1,'scopeKey':1}` unique, `partialFilter = "{'active': true}"`; `phone_status` `{'phoneNumberId':1,'status':1}`; `ws_created` `{'workspaceId':1,'creationTime':-1}`.
- `@Document("waWebHistoryImportChats") @Getter @Setter class WaWebHistoryImportChat`: `@Id String id; String importId, workspaceId, phoneNumberId, remoteId, requestedPhone, chatName; HistoryChatStatus status; HistorySkipReason skipReason; String error; Cursor cursor; Counts counts; boolean capped; int emptyPages; Instant attemptedAt, finishedAt`. Nested `record Cursor(Long beforeT, List<String> excludeIds)`, `record Counts(long seen, long inserted, long updated) { static final Counts ZERO }`. Indexes: `import_remote` `{'importId':1,'remoteId':1}` unique; `import_status` `{'importId':1,'status':1,'_id':1}`; `status_attempted` `{'status':1,'attemptedAt':1}`.
- `WaWebHistoryImportRepository extends MongoRepository<WaWebHistoryImport,String>`: `Optional<WaWebHistoryImport> findByIdAndWorkspaceId(String id, String workspaceId)`, `Optional<WaWebHistoryImport> findByPhoneNumberIdAndScopeKeyAndActiveTrue(String phoneNumberId, String scopeKey)`, `List<WaWebHistoryImport> findByActiveTrueOrderByCreationTimeAsc()`, `void deleteByPhoneNumberId(String phoneNumberId)`.
- `WaWebHistoryImportChatRepository extends MongoRepository<WaWebHistoryImportChat,String>`: `boolean existsByImportIdAndStatusIn(String importId, Collection<HistoryChatStatus> statuses)`, `long countByImportId(String importId)`, `void deleteByPhoneNumberId(String phoneNumberId)`, `void deleteByImportId(String importId)`.

- [ ] **Step 1: Write the failing test** `HistoryImportIndexesIT` (`@SpringBootTest`, `MongoTemplate`): `secondActiveIdenticalImportIsRejected` saves two imports with the same `phoneNumberId`/`scopeKey`, both `active=true` → the second `save` throws `DuplicateKeyException`; `inactiveDuplicateIsAllowed` saves one `active=false` then one `active=true` with the same key → both saved; `chatItemsUniquePerImport` saves two items with the same `importId`/`remoteId` → `DuplicateKeyException`; `cleanupDeletesImports` saves one import and one item for phone `p1`, calls `InboxCleanup.deleteForNumber("p1")` → both collections empty for `p1`.
- [ ] **Step 2: Run** `-Dtest=HistoryImportIndexesIT` → compilation fails.
- [ ] **Step 3: Implement** the entities, enums, repositories, and the two `deleteByPhoneNumberId` calls in `InboxCleanup.deleteForNumber` (after `chats.deleteByPhoneNumberId`).
- [ ] **Step 4: Run** the test → PASS.
- [ ] **Step 5: Commit** — `feat(history): waWebHistoryImports and waWebHistoryImportChats`

---

### Task 3: `InboxIngestService.importMessage`

**Files:**
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/InboxIngestService.java` — add the import path; add `applyAck(message, ack, Instant now)` tolerance of `now == null` (no `statusTimes` stamping).
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/ImportMessageIT.java` (model on `WaWebInboxListenerIT`: `@SpringBootTest`, a `Recorder` `@TestConfiguration` bean that collects every `InboxEvents.*`).

**Interfaces:**
- Produces: `public record ImportContext(String customerId, String workspaceId, String phoneNumberId)`, `public enum ImportOutcome { INSERTED, UPDATED, UNCHANGED, SKIPPED }`, `public record ImportResult(ImportOutcome outcome, WaWebChat chat, WaWebMessage message)` (chat and message null when SKIPPED), all nested in `InboxIngestService`.
- Produces: `public ImportResult importMessage(ImportContext ctx, JsonNode payload)`. SKIPPED unless `MessagePayloads.remote(p)` ends in `@c.us` or `@lid` and `MessagePayloads.waMessageId(p)` is non-empty. Then, inside `retrying(...)` and translating `DataAccessException` to `IngestionFailedException("history_import", waMessageId, e)`: `contacts.upsertFromChat(ws, customer, remote, fromMe ? null : senderName, lid ? lidPhone : null, at)` (null contact → SKIPPED); find or create the chat (`status = CLOSED`, `unreadCount = 0`, `contactId`); look the message up by `(phoneNumberId, waMessageId)`: existing → `applyAck(message, ack, null)` → `UPDATED` if it moved (save; refresh `chat.lastMessage.status` via `isLast`/`withStatus`) else `UNCHANGED`; missing → `newMessage(...)` with `statusTimes = null`, `status = MessageStatus.ofAck(ack)`, `ack`, insert (a `DuplicateKeyException` is re-read as existing by `retrying`), fold `foldPreview(chat, message, at)` and `lastInboundAt`/`lastOutboundAt` via `later`, save chat → `INSERTED`. **No** event is published. `newMessage` needs the ids from `ctx`, not a `WhatsAppEvent`: extract a private `newMessage(ctx ids…, p, chat, waMessageId, fromMe, at)` both paths share.
- `applyAck(WaWebMessage, int ack, Instant now)`: when `now == null`, move `status`/`ack` as before but leave `statusTimes` as it is.

- [ ] **Step 1: Write the failing tests** `ImportMessageIT`. Payload helper `msg(String id, String remote, boolean fromMe, long t, int ack, String body)` builds `{id:{_serialized:id, remote:remote, fromMe}, fromMe, body, type:'chat', timestamp:t, ack}`. Context `ctx = new ImportContext("cust-1","ws-1","pn-1")`. Tests:
  - `insertsMessageAndCreatesClosedChat`: inbound message → `INSERTED`; chat `status CLOSED`, `unreadCount 0`, `lastMessage.text == body`, `lastInboundAt == t`; message `source CONTACT`, `direction IN`, `statusTimes null`, `status` from ack 0 → `PENDING`; a contact exists for the phone; `recorder.events` is empty.
  - `secondRunIsUnchanged`: same payload twice → second returns `UNCHANGED`; one message row; `recorder.events` empty.
  - `higherAckUpdatesStatusWithoutTimes`: outbound message ack 1 then same id ack 3 → `UPDATED`; `status READ`; `statusTimes null`.
  - `liveStatusIsKeptWhenImportSeesLower`: insert via the live path (`ingestMessage` of a `WhatsAppEvent` with ack 3), then `importMessage` with ack 1 → `UNCHANGED`; status still `READ`; `statusTimes.readAt` unchanged.
  - `existingOpenChatKeepsStatusAndUnread`: pre-save an OPEN chat with `unreadCount 4` for the remote; import an inbound message → chat still OPEN, `unreadCount 4`, `lastMessageAt == t`.
  - `olderMessageDoesNotOverwritePreview`: import t=200 "new" then t=100 "old" → `lastMessage.text == "new"`, `lastMessageAt == 200`.
  - `skipsGroupsAndMissingIds`: remote `…@g.us` → `SKIPPED`; payload without `id` → `SKIPPED`; nothing saved.
- [ ] **Step 2: Run** `-Dtest=ImportMessageIT` → compilation fails.
- [ ] **Step 3: Implement** per the Interfaces block, reusing `foldPreview`, `preview`, `later`, `isLast`, `withStatus`, `retrying`.
- [ ] **Step 4: Run** `-Dtest=ImportMessageIT,WaWebInboxListenerIT,InboxSendIT` → PASS (the live path is unchanged).
- [ ] **Step 5: Commit** — `feat(inbox): side-effect-free importMessage for history imports`

---

### Task 4: `HistoryImportService` (create, cancel, query) and views

**Files:**
- Create in `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/history/`: `HistoryImportRequest.java`, `HistoryImportService.java`, `HistoryImportViews.java`, `HistoryImportView.java`, `HistoryChatView.java`, `HistoryImportCounter.java`, `HistoryImportErrors.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportServiceIT.java` (`@SpringBootTest`; fixtures via `TestUsers` / `TestDevices` as `InboxApiTestBase` does).

**Interfaces:**
- `public record HistoryImportRequest(ScopeRequest scope, Instant from, Instant to) { public record ScopeRequest(HistoryScopeKind kind, List<String> phones, List<String> contactIds) {} }`.
- `public record HistoryImportView(String id, NumberRef phoneNumber, ScopeView scope, WaWebHistoryImport.Range range, HistoryImportStatus status, HistoryWaiting waiting, WaWebHistoryImport.Counts counts, Ref requestedBy, Instant creationTime, Instant startedAt, Instant finishedAt, Integer progressPercent, String error) { public record Ref(String id, String name) {} public record NumberRef(String id, String number, String label) {} public record ScopeView(HistoryScopeKind kind, List<String> phones, int chatCount) {} }`.
- `public record HistoryChatView(String id, String remoteId, String requestedPhone, String chatName, String chatId, HistoryChatStatus status, HistorySkipReason skipReason, String error, WaWebHistoryImportChat.Counts counts, boolean capped, Instant attemptedAt, Instant finishedAt)`; `chatId` is the `WaWebChat` id for `(phoneNumberId, remoteId)` when one exists.
- `HistoryImportViews` (`@Component`): `HistoryImportView view(WaWebHistoryImport i)` (loads the `PhoneNumber` for number/label; `progressPercent = chatsTotal == 0 ? null : (chatsDone+chatsSkipped+chatsFailed)*100/chatsTotal`), `List<HistoryChatView> chats(List<WaWebHistoryImportChat> items)`.
- `HistoryImportCounter` (`@Component`): `WaWebHistoryImport.Counts refresh(String importId)` — aggregate items grouped by `status` for `chatsDone/Skipped/Failed`, `chatsTotal = countByImportId`, sum `counts.seen/inserted/updated`; `updateFirst` the import's `counts`; returns them (model on `CampaignCounter`).
- `HistoryImportErrors`: `static CodedException state(String verb)` → `CONFLICT`, `"HISTORY_IMPORT_STATE"`, `"This import cannot be " + verb + " now."`.
- `HistoryImportService` (`@Service`):
  - `public record Created(WaWebHistoryImport imp, boolean created) {}`
  - `public Created create(AuthenticatedUser me, String workspaceId, String phoneNumberId, HistoryImportRequest req)`: number via `PhoneNumberRepository.findByIdAndWorkspaceId` else `NotFoundException("phone number not found")`; `ValidationException("from", "must not be after to")` when both set and `from.isAfter(to)`; scope `ALL` → `remoteIds = List.of()`, `phones = List.of()`; scope `CHATS`: each phone through `Phones.normalise`, invalid → `ValidationException("phones", Phones.MESSAGE)`; each contact id via `ContactRepository.findByIdAndWorkspaceId` (unknown → `ValidationException("contactIds", "unknown contact")`), contributing its `phone` and `waId`; remoteIds = distinct sorted `<digits>@c.us` for every phone plus the contact's `waId` found by `ContactRepository.findByWorkspaceIdAndPhone` for a given phone and the `waId` of a given contact; empty → `ValidationException("scope", "choose at least one contact")`; more than 500 → `ValidationException("scope", "at most 500 chats per import")`. `scopeKey = sha256Hex(phoneNumberId + "|" + kind + "|" + String.join(",", remoteIds) + "|" + from + "|" + to)`. `findByPhoneNumberIdAndScopeKeyAndActiveTrue` → `Created(existing, false)`; else insert QUEUED (`active true`, `counts ZERO`, `requestedByUserId = me.userId()`, `requestedByName = me.getUsername()`); on `DuplicateKeyException` re-find and return `Created(existing, false)`; for `CHATS` insert one PENDING item per remoteId (`requestedPhone` = the digits when it came from a phone) and set `counts.chatsTotal`.
  - `public Created createForChat(AuthenticatedUser me, String workspaceId, String chatId, Instant from, Instant to)`: `WaWebChatRepository.findByIdAndWorkspaceId` else `NotFoundException("chat not found")`; delegates with scope `CHATS`, `remoteIds = [chat.remoteId]` (bypassing phone parsing: add a private `create(me, number, kind, remoteIds, phones, from, to)` both public methods call).
  - `public WaWebHistoryImport cancel(String workspaceId, String id)`: `require`; status not QUEUED/RUNNING → `HistoryImportErrors.state("cancelled")`; `updateFirst` where `_id` and `status in (QUEUED, RUNNING)` set `status CANCELLED`, `active false`, `finishedAt now`, `waiting null`; items `RUNNING → PENDING` for the import; return the fresh row.
  - `public WaWebHistoryImport require(String workspaceId, String id)` → `NotFoundException("history import not found")`.
  - `public record ImportPage(List<HistoryImportView> items, long total, int page) {}`, `public record ChatPage(List<HistoryChatView> items, long total, int page) {}`; `ImportPage list(String workspaceId, String phoneNumberId, HistoryImportStatus status, int page)` (50 per page, `creationTime desc, _id desc`); `ChatPage chats(String workspaceId, String id, HistoryChatStatus status, int page)` (sorted `_id`).

- [ ] **Step 1: Write the failing tests** `HistoryImportServiceIT`: `allScopeCreatesQueuedImportWithoutItems`; `chatsScopeResolvesPhonesAndLidToItems` (contact with phone `919811100001` and `waId 1234@lid` → items `919811100001@c.us` and `1234@lid`, `chatsTotal 2`); `identicalActiveRequestIsReturned` (`created` false, same id); `differentRangeQueuesAnotherImport`; `completedImportDoesNotBlockANewOne` (set first `active=false, status COMPLETED` → new one created); `fromAfterToIsValidation`; `emptyChatsScopeIsValidation`; `invalidPhoneIsValidation`; `createForChatUsesTheChatRemoteId`; `cancelMarksInactiveAndReleasesRunningItems`; `cancelCompletedIsConflict` (`CodedException` code `HISTORY_IMPORT_STATE`); `listFiltersByNumberAndStatus`; `counterRefreshSumsItems` (items DONE `{seen 5, inserted 3, updated 1}`, SKIPPED, PENDING → counts `chatsTotal 3, chatsDone 1, chatsSkipped 1, messagesSeen 5, messagesInserted 3, messagesUpdated 1`); `viewProgressPercent` (`null` at total 0; `66` for 2 of 3 finished).
- [ ] **Step 2: Run** `-Dtest=HistoryImportServiceIT` → compilation fails.
- [ ] **Step 3: Implement** the classes per the Interfaces block.
- [ ] **Step 4: Run** the test → PASS.
- [ ] **Step 5: Commit** — `feat(history): HistoryImportService, views, counter`

---

### Task 5: `HistoryDeviceClient` — typed RPC wrapper

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/history/HistoryDeviceClient.java`.
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/inbox/history/HistoryDeviceClientTest.java` (unit; a stub `DeviceSession` is awkward, so test the parsing statics directly).

**Interfaces:**
- `public record ChatRow(String id, String name, Long t, int unreadCount) {}`
- `public record FetchRequest(String chatId, Long before, List<String> excludeIds, Long from, Long to, int limit, int maxLoads) {}`
- `public record HistoryPage(List<JsonNode> messages, Long oldestT, List<String> oldestIds, boolean hasMore) {}`
- `HistoryDeviceClient` (`@Component`, depends on `RpcDispatcher`, `ObjectMapper`): `CompletableFuture<List<ChatRow>> listChats(DeviceSession device)` → `dispatcher.call(device, "listChats", mapper.createObjectNode()).thenApply(HistoryDeviceClient::chatRows)`; `CompletableFuture<HistoryPage> fetchChatHistory(DeviceSession device, FetchRequest req)` → params `{chatId, before?, excludeIds, from?, to?, limit, maxLoads}` (nulls omitted) → `.thenApply(HistoryDeviceClient::page)`.
- `static List<ChatRow> chatRows(JsonNode result)` (non-array → empty; missing `name`/`t` → null; missing `unreadCount` → 0); `static HistoryPage page(JsonNode result)` (`messages` array in order; `oldest` null → `oldestT null, oldestIds []`; `hasMore` default false).

- [ ] **Step 1: Write the failing tests** `HistoryDeviceClientTest`: `chatRowsParsesAndDefaults`, `chatRowsOfNonArrayIsEmpty`, `pageParsesOldestAndHasMore`, `pageWithoutOldest`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service -am test -Dtest=HistoryDeviceClientTest` → compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(history): HistoryDeviceClient wrapping listChats and fetchChatHistory`

---

### Task 6: Test harness — `HistoryDevice` and `HistoryImportTestBase`

**Files:**
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryDevice.java` (model on `app/campaign/AutoDevice.java`).
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportTestBase.java` (model on `CampaignTestBase`: `RANDOM_PORT`, `TestRestTemplate`, `MutableClock`, `properties = {"omnichannel.rpc.timeout=2s"}`).

**Interfaces:**
- `HistoryDevice implements AutoCloseable`: `HistoryDevice(int port, TestDevices testDevices, PhoneNumber n)` connects a `FakeDevice` with `connectAndAuth`, sends `ready` with `wid = n.getNumber() + "@c.us"`, answers requests on its own thread. State: `final Map<String, List<ObjectNode>> chats = new ConcurrentHashMap<>()` (remoteId → messages, any order); `volatile Function<Envelope, Envelope> override = null` (when non-null, its reply is used for every request; return null to never answer). `listChats` → rows for each key `{id, name: null, t: max t, unreadCount: 0}`. `fetchChatHistory` → implements §3 exactly over the in-memory list (`keep`, ascending sort, `slice(-limit)`, `oldest`, `hasMore = kept.size() > limit`; `exhausted` is always true since everything is in memory); unknown chatId → `CHAT_NOT_FOUND`. Records every request (`List<Envelope> requests(String method)`). Helper `static ObjectNode msg(String remote, boolean fromMe, long t, int ack, String body)` with `id._serialized = (fromMe ? "true_" : "false_") + remote + "_" + hex counter`.
- `HistoryImportTestBase`: fields `acme, brand, other, n1, otherNumber, manager, agent`, tokens; `ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body)` against `/api/workspaces/{brand}/inbox`; `@Autowired HistoryImportRunner runner; HistoryImportService service; WaWebHistoryImportRepository imports; WaWebHistoryImportChatRepository items; WaWebMessageRepository messages; WaWebChatRepository chats; MutableClock clock; TestDevices testDevices; @LocalServerPort int port`. `clean()` removes `WaWebHistoryImport, WaWebHistoryImportChat, WaWebChat, WaWebMessage, WaWebChatAction, Contact` and `testUsers.deleteAll()`.

- [ ] **Step 1: Write a smoke test** `HistoryDeviceIT extends HistoryImportTestBase`: connect a `HistoryDevice` for `n1` with chat `919811100001@c.us` holding 3 messages; call `historyDeviceClient.listChats(registry.find(n1.getId()).get()).get(2, SECONDS)` → one row; `fetchChatHistory(..., new FetchRequest(remote, null, List.of(), null, null, 2, 20))` → 2 messages ascending, `hasMore true`, `oldestT` = the middle message's t.
- [ ] **Step 2: Run** → fails to compile.
- [ ] **Step 3: Implement** `HistoryDevice` and the base.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `test(history): HistoryDevice fake and HistoryImportTestBase`

---

### Task 7: `HistoryImportRunner` and `HistoryImportTicker`

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/history/HistoryImportRunner.java`, `HistoryImportTicker.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportRunnerIT.java`.

**Interfaces:**
- `HistoryImportTicker` (`@Component`, `@ConditionalOnProperty(name="omnichannel.history.auto-tick", havingValue="true", matchIfMissing=true)`): `@Scheduled(fixedDelayString="${omnichannel.history.tick:5s}", scheduler="historyImportScheduler") void tick()` → `runner.tick()`; `@EventListener(ApplicationReadyEvent.class) void atStartup()` → `runner.releaseStuck()`.
- `HistoryImportRunner` (`@Service`; deps `MongoTemplate`, `WaWebHistoryImportRepository`, `WaWebHistoryImportChatRepository`, `WaWebChatRepository`, `InboxDevices`, `HistoryDeviceClient`, `InboxIngestService`, `HistoryImportCounter`, `ApplicationEventPublisher`, `Clock`, `@Qualifier("historyImportScheduler") Executor`, `OmnichannelProperties`):
  - `public CompletableFuture<Void> tick()`: guarded by an `AtomicBoolean ticking`; `findByActiveTrueOrderByCreationTimeAsc()`, first import per `phoneNumberId` only; skip numbers in `Set<String> busy`; `runAsync(() -> process(imp), executor)` each; returns `allOf`.
  - `public int releaseStuck()`: `updateMulti` items `status RUNNING` → `PENDING`, unset `attemptedAt`; returns the count.
  - `void process(WaWebHistoryImport imp)`: `devices.requireReady(ws, phoneNumberId)` → on `NoDeviceException` set `waiting` (`NO_DEVICE` for code `NO_DEVICE`, else `WHATSAPP_NOT_READY`) via `updateFirst`, return; on `NotFoundException` (number deleted) → `FAILED`, `active false`, `error "NUMBER_GONE"`. Else `updateFirst` where `_id` and `status QUEUED` → `RUNNING`, `startedAt now`; clear `waiting`. `ALL` scope and `countByImportId == 0` → `client.listChats(device).get()` → insert items (PENDING, `chatName` from the row; ignore `DuplicateKeyException`), `counter.refresh`. Then loop until `deadline = now + timeBudget`: `claim(importId)` (`findAndModify` PENDING → RUNNING, `attemptedAt now`, sort `_id`, `returnNew`), null → break; `importChat(imp, item, device)`; after each item `counter.refresh(importId)`; if `importChat` reported a device failure → break; sleep `chatDelay`; if the import is no longer RUNNING (re-read) → break. After the loop: if no item PENDING/RUNNING (`existsByImportIdAndStatusIn`) and the import is still RUNNING → `COMPLETED`, `active false`, `finishedAt`, `counter.refresh`.
  - `boolean importChat(WaWebHistoryImport imp, WaWebHistoryImportChat item, DeviceSession device)` returns false when the device failed (caller stops). Loop: `FetchRequest(item.remoteId, cursor.beforeT, cursor.excludeIds, from epochSecond, to epochSecond, pageSize, maxLoads)` → `client.fetchChatHistory(...).get()`; for each message in `page.messages()` (ascending) `ingest.importMessage(ctx, m)`, tally `seen/inserted/updated`, remember the last non-null `chat`; new cursor `(page.oldestT, page.oldestIds)`; `emptyPages = page.messages().isEmpty() ? item.emptyPages + 1 : 0`; `whileRunning(item, set cursor, counts, emptyPages)` — false (someone cancelled/released it) → return true; stop conditions: `!page.hasMore()` → DONE; `counts.seen >= maxMessagesPerChat` → DONE with `capped true`; `emptyPages >= maxEmptyPages` → FAILED `"NO_PROGRESS"`; import no longer RUNNING → leave item RUNNING→PENDING (`release`) and return true; else sleep `pageDelay`, continue. Exceptions (unwrap `ExecutionException`/`CompletionException`): `RpcException CHAT_NOT_FOUND` → SKIPPED `NO_CHAT`; `RpcException DEVICE_DISCONNECTED` / `WHATSAPP_NOT_READY`, `TimeoutException`, `NoDeviceException` → `release(item)` (PENDING, cursor kept), set `imp.waiting` (`NO_DEVICE` for disconnect, else `WHATSAPP_NOT_READY`), return false; other `RpcException` → FAILED with `rpc.code()`; `IngestionFailedException` → FAILED `"DB_ERROR"`; any other `RuntimeException` → FAILED with the class simple name. On DONE/SKIPPED/FAILED: `finishedAt now`; if a `WaWebChat` exists for `(phoneNumberId, remoteId)` publish `InboxEvents.ChatChanged(chat)`.
  - `private boolean whileRunning(WaWebHistoryImportChat item, Update u)` → `updateFirst(where _id and status RUNNING)` matched; `private void release(item)` → `whileRunning(item, set status PENDING, unset attemptedAt)`.

- [ ] **Step 1: Write the failing tests** `HistoryImportRunnerIT extends HistoryImportTestBase` (each test opens a `HistoryDevice` for `n1` in a try-with-resources, seeds `device.chats`, creates the import through `service.create(...)`, then `runner.tick().get(10, SECONDS)` and asserts):
  - `importsAllChatsAndCompletes`: two chats with 3 and 2 messages → import `COMPLETED`, `active false`, 2 items DONE, 5 `waWebMessages`, chats CLOSED with `unreadCount 0`, `counts.messagesInserted 5`, `chatsTotal 2`.
  - `secondRunInsertsNothing`: run once, create an identical request (now allowed, the first is inactive) and tick → `messagesInserted 0`, `messagesSeen 5`, still 5 rows.
  - `pagesWithCursorWithoutLosingSameSecondMessages`: set `omnichannel.history.page-size=2` for this class (or a `@TestPropertySource` subclass) with 5 messages, three of them at the same `t` → 5 rows stored, `device.requests("fetchChatHistory").size() >= 3`, the second request's `before` equals the first response's `oldest.t` and its `excludeIds` equals `oldest.ids`.
  - `dateRangeIsPassedAndRespected`: messages at t=100,200,300; `from=150s, to=250s` → one row (t=200); the request params carry `from 150`, `to 250`.
  - `chatsScopeSkipsUnknownChat`: scope CHATS with phones `919811100001` (present) and `919811100002` (absent on the device) → items DONE and SKIPPED `NO_CHAT`; import COMPLETED; `counts.chatsSkipped 1`.
  - `capStopsAChat`: `max-messages-per-chat=3`, 5 messages → item DONE `capped true`, 3 rows.
  - `disconnectLeavesCursorAndWaits`: `device.override` answers the first `fetchChatHistory` normally then closes the fake socket; tick → item PENDING with a non-null `cursor.beforeT`, import RUNNING with `waiting NO_DEVICE`; reconnect a new `HistoryDevice`, tick → COMPLETED and every message stored once.
  - `noDeviceSetsWaiting`: no device connected → after the tick the import is still QUEUED (`requireReady` fails before the RUNNING transition) with `waiting NO_DEVICE`; connect a device and tick → COMPLETED, `waiting null`.
  - `cancelMidImportReleasesItem`: `device.override` never answers `fetchChatHistory` (null) with `rpc.timeout=2s`; start a tick asynchronously, `service.cancel(...)` after 300 ms, await the tick → import CANCELLED `active false`, item PENDING.
  - `oneImportPerNumberAtATime`: two imports on `n1` (different ranges) → after one tick the first is COMPLETED and the second still QUEUED; a second tick completes it.
  - `releaseStuckResetsRunningItems`: save an item RUNNING → `runner.releaseStuck() == 1`, item PENDING.
  - `finishedChatPublishesChatChangedOnce`: a `Recorder` bean counting `ChatChanged` → exactly one per finished item, zero `ChatCreated`/`MessageUpserted`.
- [ ] **Step 2: Run** `-Dtest=HistoryImportRunnerIT` → compilation fails.
- [ ] **Step 3: Implement** runner and ticker per the Interfaces block.
- [ ] **Step 4: Run** `-Dtest=HistoryImportRunnerIT` → PASS; then `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** — `feat(history): HistoryImportRunner and ticker`

---

### Task 8: Commit the rebuilt page script

**Files:**
- Modify (generated by the extension plan's `npm run build`): `omnichannel-service/src/main/resources/wabridge/scripts/page.js`, `manifest.json`.

- [ ] **Step 1:** In the extension repo run `npm run build`; confirm `git status --short omnichannel-service/src/main/resources/wabridge/scripts` here shows both files modified.
- [ ] **Step 2:** `grep -c "fetchChatHistory" omnichannel-service/src/main/resources/wabridge/scripts/page.js` → at least 1.
- [ ] **Step 3: Commit** — `chore(wabridge): page script with listChats and fetchChatHistory`

---

### Task 9: `HistoryImportController`

**Files:**
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/inbox/HistoryImportController.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/history/HistoryImportApiIT.java` (`extends InboxApiTestBase` for MockMvc, tokens and fixtures).

**Interfaces:**
- `@RestController @RequestMapping("/api/workspaces/{workspaceId}/inbox")`, every method `@PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")`:
  - `@PostMapping("/phone-numbers/{phoneNumberId}/history-imports") ResponseEntity<HistoryImportView> create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId, @PathVariable String phoneNumberId, @RequestBody HistoryImportRequest req)` → `201` when `Created.created()`, else `200`.
  - `@PostMapping("/chats/{chatId}/history-imports") ResponseEntity<HistoryImportView> createForChat(me, workspaceId, chatId, @RequestBody RangeRequest req)` with `public record RangeRequest(Instant from, Instant to) {}` → same status rule.
  - `@GetMapping("/history-imports") HistoryImportService.ImportPage list(workspaceId, @RequestParam(required=false) String phoneNumberId, @RequestParam(required=false) HistoryImportStatus status, @RequestParam(defaultValue="0") int page)`.
  - `@GetMapping("/history-imports/{id}") HistoryImportView get(workspaceId, id)` — `counter.refresh(id)` first.
  - `@GetMapping("/history-imports/{id}/chats") HistoryImportService.ChatPage chats(workspaceId, id, @RequestParam(required=false) HistoryChatStatus status, @RequestParam(defaultValue="0") int page)`.
  - `@PostMapping("/history-imports/{id}/cancel") HistoryImportView cancel(workspaceId, id)`.

- [ ] **Step 1: Write the failing tests** `HistoryImportApiIT`: `managerCreatesAllImport` (201, body `status QUEUED`, `scope.kind ALL`, `phoneNumber.number`, `progressPercent null`); `identicalRequestReturns200WithSameId`; `agentIsForbidden` (403 for POST and GET with `agentToken`); `otherWorkspaceNumberIs404`; `chatImportUsesRemoteId` (201; `GET …/{id}/chats` lists one item whose `remoteId` is the chat's); `invalidRangeIs400Validation` (`code VALIDATION`, `fields.from`); `listFiltersByStatus`; `cancelQueued` (200 `CANCELLED`) then `cancelAgainIs409` (`HISTORY_IMPORT_STATE`); `unknownIdIs404`.
- [ ] **Step 2: Run** `-Dtest=HistoryImportApiIT` → 404s / compilation failure.
- [ ] **Step 3: Implement** the controller.
- [ ] **Step 4: Run** → PASS; `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** — `feat(history): history import REST API`

---

### Task 10: Docs

**Files:**
- Modify: `docs/superpowers/specs/2026-10-31-chat-history-import-design.md` — record the Task 3 (extension plan) spike result under §3.
- Modify: `docs/superpowers/crm-pending.md` — one line each for the inherited limitations: `@c.us`/`@lid` duplicate chats, `console:<id>` duplicates.
- Modify: `README.md` — one paragraph under the inbox section: what "Import chat history" does, where it lives, the authority.

- [ ] **Step 1:** Write the three edits.
- [ ] **Step 2:** `./mvnw -q test` → PASS (nothing changed in code; confirms the branch is green).
- [ ] **Step 3: Commit** — `docs(history): spec notes, pending limitations, README`
