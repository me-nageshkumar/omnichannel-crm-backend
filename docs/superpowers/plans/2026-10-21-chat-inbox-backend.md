# Chat Inbox (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every WhatsApp Web message per phone number as chats and messages, route and assign chats to teams and agents, expose a role-scoped inbox API with sending (text and templates), inbound media, live SSE updates and demo data.

**Architecture:** A new `inbox` package. A `WhatsAppEvent` listener (replacing `ChatContactListener`) upserts contact → chat → message and drives routing; services for scope/visibility, routing + assignment, sending (through the existing `RpcDispatcher`), media (GridFS `chatMedia`, lazy fetch through a new `downloadMedia` RPC) and an SSE broker; one controller under `/api/workspaces/{ws}/inbox`.

**Tech Stack:** Spring Boot 4.1.1, Spring Data Mongo (flapdoodle embedded in tests), Lombok, Jackson 3 (`tools.jackson.*`), `SseEmitter`, GridFS, JUnit 5 + MockMvc/TestRestTemplate + `FakeDevice`.

**Spec:** `docs/superpowers/specs/2026-10-21-chat-inbox-design.md` (read it first; §3 data model, §5 routing, §6 send, §8 visibility are normative).

## Global Constraints

- Package `me.nageshk.omnichannel.inbox`; collections `waWebChats`, `waWebMessages`, `waWebChatActions`, `inboxSettings`; GridFS bucket `chatMedia`.
- Lombok `@Getter/@Setter` on entities, records for views and requests. No hand-written getters/setters.
- Jackson 3: `tools.jackson.databind.*`; annotations stay `com.fasterxml.jackson.annotation`.
- Authorization through `@PreAuthorize("@access.has(#workspaceId, '<code>')")`; a chat or number outside the caller's scope is **404** (`NotFoundException`), never 403; a list `scope` the caller may not use is 403 (`ForbiddenException`).
- Errors use the existing `ApiExceptionHandler` shapes (`{code, message}`; VALIDATION with `fields`).
- Wording: "Enabled/Disabled", never "block". Source labels `CONTACT`, `CONSOLE`, `WHATSAPP_WEB`.
- Ingestion never throws out of the listener; log at WARN and continue (as `ChatContactListener` does today).
- Every task: `./mvnw -q test -Dtest=<Class>` for the task, then the whole suite `./mvnw -q test` before the completion line (currently 710 tests, 0 failures).
- Commits in this repo end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. An ack arriving before the `message_create` for the same message (extension replays) must not create a bodyless message; the ack is dropped and logged — test in Task 3.
2. A `message_create` for a message the console just sent (same `waMessageId`) must not reset `source`/`sentBy` to WHATSAPP_WEB — test in Task 7.
3. Round robin with every member disabled or removed from the workspace falls back to the team queue, never loops — test in Task 4.
4. An agent's open SSE stream must not receive a `message` for a chat that was just reassigned away; it receives `chat {visible:false}` — test in Task 5.
5. A template whose required agent value is missing must not reach the extension — test in Task 7 (no RPC frame on `FakeDevice`).

---

### Task 1: Authorities, entities, repositories

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/access/Authority.java` (append six entries)
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{WaWebChat,WaWebMessage,WaWebChatAction,InboxSetting}.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{ChatStatus,MessageDirection,MessageSource,MessageStatus,ActionType,RoutingMode}.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{WaWebChatRepository,WaWebMessageRepository,WaWebChatActionRepository,InboxSettingRepository}.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/MessageStatusTest.java`, `src/test/java/me/nageshk/omnichannel/inbox/InboxIndexesIT.java`, update `src/test/java/me/nageshk/omnichannel/access/RoleSeederIT.java` (or wherever default grants are asserted; find with `grep -rl "template:view" src/test`)

**Interfaces:**
- Produces: `Authority.INBOX_VIEW("inbox:view")`, `INBOX_VIEW_TEAM("inbox:view-team")`, `INBOX_VIEW_ALL("inbox:view-all")`, `INBOX_SEND("inbox:send")`, `INBOX_ASSIGN("inbox:assign")`, `INBOX_SETTINGS("inbox:settings")`, all `RoleLevel.BRAND`, default roles per spec §8, descriptions from the spec table.
- Produces: `enum MessageStatus { PENDING, SENT, DELIVERED, READ, PLAYED, FAILED; static MessageStatus ofAck(int ack); boolean isAtLeast(MessageStatus other) }` — `ofAck`: -1→FAILED, 0→PENDING, 1→SENT, 2→DELIVERED, 3→READ, 4→PLAYED, anything else→PENDING. Ordering for `isAtLeast`: PENDING < SENT < DELIVERED < READ < PLAYED; FAILED is reachable from any state and `isAtLeast` is false for it against everything but FAILED and PENDING.
- Produces entities exactly per spec §3 (field names verbatim), with `@Document`, `@CompoundIndex`es, `@Version` on `WaWebChat`, `@CreatedDate/@LastModifiedDate`. Embedded records: `WaWebChat.LastMessage(MessageDirection direction, String type, String text, MessageStatus status)`, `WaWebMessage.Media(String mimetype, String filename, Long size, String mediaId, Instant downloadedAt)`, `WaWebMessage.Location(Double latitude, Double longitude, String description)`, `WaWebMessage.StatusTimes` as a mutable class with Instant fields `sentAt, deliveredAt, readAt, playedAt, failedAt`, `InboxSetting.Routing(RoutingMode mode, String teamId, String userId)`.
- Repositories: `WaWebChatRepository.findByPhoneNumberIdAndRemoteId(String, String)`, `findByIdAndWorkspaceId(String, String)`, `deleteByPhoneNumberId(String)`; `WaWebMessageRepository.findByPhoneNumberIdAndWaMessageId(String, String)`, `findByIdAndChatId(String, String)`, `deleteByPhoneNumberId(String)`; `WaWebChatActionRepository.deleteByChatIdIn(Collection<String>)`; `InboxSettingRepository.findByPhoneNumberId(String)`, `deleteByPhoneNumberId(String)`.

- [ ] **Step 1: Write the failing tests**

```java
class MessageStatusTest {
    @Test void ackMapsToStatus() {
        assertThat(MessageStatus.ofAck(-1)).isEqualTo(MessageStatus.FAILED);
        assertThat(MessageStatus.ofAck(0)).isEqualTo(MessageStatus.PENDING);
        assertThat(MessageStatus.ofAck(1)).isEqualTo(MessageStatus.SENT);
        assertThat(MessageStatus.ofAck(2)).isEqualTo(MessageStatus.DELIVERED);
        assertThat(MessageStatus.ofAck(3)).isEqualTo(MessageStatus.READ);
        assertThat(MessageStatus.ofAck(4)).isEqualTo(MessageStatus.PLAYED);
        assertThat(MessageStatus.ofAck(9)).isEqualTo(MessageStatus.PENDING);
    }
    @Test void statusNeverGoesDown() {
        assertThat(MessageStatus.READ.isAtLeast(MessageStatus.SENT)).isTrue();
        assertThat(MessageStatus.SENT.isAtLeast(MessageStatus.READ)).isFalse();
        assertThat(MessageStatus.FAILED.isAtLeast(MessageStatus.READ)).isFalse();
    }
}
```
`InboxIndexesIT` (`@SpringBootTest`, autowire `MongoTemplate`): saving two `WaWebChat` with the same `(phoneNumberId, remoteId)` throws `DuplicateKeyException`; same for two `WaWebMessage` with the same `(phoneNumberId, waMessageId)`; `mongo.indexOps(WaWebChat.class).getIndexInfo()` contains an index whose name is `phone_last_message` and one named `phone_remote_unique`.
Role seeder test: the four brand roles hold `inbox:view` and `inbox:send`; AGENT lacks `inbox:view-team`, `inbox:view-all`, `inbox:assign`, `inbox:settings`; TEAM_LEAD holds `inbox:view-team` and `inbox:assign` but not `inbox:view-all`/`inbox:settings`.

- [ ] **Step 2: Run them to verify they fail** — `./mvnw -q test -Dtest='MessageStatusTest,InboxIndexesIT'` → compilation failure (classes missing).
- [ ] **Step 3: Implement** the enums, entities (index names: `phone_remote_unique`, `phone_last_message`, `phone_user_last_message`, `phone_team_last_message`, `workspace_contact` on chats; `phone_wamessage_unique`, `chat_timestamp` on messages; `chat_at` on actions; `phone_unique` on settings), repositories and the six `Authority` entries.
- [ ] **Step 4: Run the tests** → PASS; then `./mvnw -q test` → all green (the authority catalog scanner `AuthorityCheckScanner` may complain about unused authorities only once controllers exist — if it fails here, read its message and add the codes to whatever list it checks).
- [ ] **Step 5: Commit** — `feat(inbox): authorities, chat/message/action/setting entities`

---

### Task 2: Scope and visibility

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/InboxScope.java`, `src/main/java/me/nageshk/omnichannel/inbox/InboxScopeService.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxScopeTest.java`, `src/test/java/me/nageshk/omnichannel/inbox/InboxScopeServiceIT.java`

**Interfaces:**
- Consumes: `AccessService.inWorkspace(AuthenticatedUser, workspaceId)` (throws `NotFoundException` when no access), `TeamRepository.findByWorkspaceIdOrderByName`.
- Produces: `record InboxScope(Level level, String userId, Set<String> teamIds)` with `enum Level { ALL, TEAM, MINE }`, `Criteria criteria()` (Spring Data Mongo; ALL → empty criteria; TEAM → `or(assignedUserId=userId, assignedTeamId in teamIds, and(assignedUserId null, assignedTeamId null))`; MINE → `assignedUserId=userId`), `boolean covers(WaWebChat chat)` (same rule in memory), `boolean allows(ListScope requested)` where `enum ListScope { MINE, TEAM, UNASSIGNED, ALL }`: MINE always; TEAM/UNASSIGNED need level ≤ TEAM; ALL needs ALL. `Criteria criteriaFor(ListScope)`: MINE → `assignedUserId=userId`; TEAM → `or(assignedTeamId in teamIds, and(user null, team null))` for level TEAM, and for level ALL the same but with `teamIds` = every team of the workspace; UNASSIGNED → `and(user null, team null)`; ALL → empty.
- Produces: `InboxScopeService.scopeOf(AuthenticatedUser me, String workspaceId)` → `InboxScope` from the user's effective authorities and team memberships (`Team.memberIds` contains `me.userId()`); `ListScope defaultScope(InboxScope)` → widest allowed (ALL > TEAM > MINE).

- [ ] **Step 1: Write failing tests.** `InboxScopeTest` (pure): `mineCriteriaMatchesOnlyAssignedUser` (`criteria().getCriteriaObject()` equals `Document("assignedUserId", "u1")`); `teamCriteriaCoversOwnTeamsAndQueue` (`covers` true for a chat with `assignedTeamId=t1`, true for a chat with neither, false for a chat assigned to user u2 and team t9); `allAllowsEverything`; `agentMayNotRequestTeamScope` (`allows(ListScope.TEAM)` false for MINE level). `InboxScopeServiceIT`: a user with the AGENT role in a workspace gets level MINE; TEAM_LEAD who is a member of team "Support" gets level TEAM with that team id; WORKSPACE_MANAGER gets ALL; a user without access → `NotFoundException`.
- [ ] **Step 2: Run → fail** (`./mvnw -q test -Dtest='InboxScopeTest,InboxScopeServiceIT'`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): visibility scope per user`

---

### Task 3: Ingestion listener (chats and messages from events)

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{WaWebInboxListener,InboxIngestService,MessagePayloads,InboxEvents}.java`
- Delete: `src/main/java/me/nageshk/omnichannel/contact/ChatContactListener.java`; move its tests into the new IT (keep every assertion of `ChatContactListenerIT`, they now run through the inbox listener).
- Test: `src/test/java/me/nageshk/omnichannel/inbox/WaWebInboxListenerIT.java`

**Interfaces:**
- Consumes: `WhatsAppEvent`, `ContactService.upsertFromChat(...)` (returns `Contact`, may return null for an invalid phone), `LoggingEventListener.wid(JsonNode)`, `MessageStatus.ofAck`.
- Produces: `MessagePayloads` static helpers over the whatsapp-web.js message JSON: `remote(p)`, `waMessageId(p)` (`id._serialized`, else built as `fromMe + "_" + remote + "_" + id.id`), `fromMe(p)`, `type(p)` (`type` text, default `"chat"`), `body(p)`, `timestamp(p, event)` (seconds → Instant, else event ts, else now), `ack(p)` (`ack` int, default 0), `hasMedia(p)`, `media(p)` (`mimetype/filename/size` top-level then `_data.*`), `location(p)` (`location.latitude/longitude/description`, else `_data.lat/lng/loc`), `senderName(p)`, `lidPhone(p)` (moved from `ChatContactListener`).
- Produces: `InboxIngestService.ingestMessage(WhatsAppEvent)` and `ingestAck(WhatsAppEvent)`, both returning `Optional<Ingested>` where `record Ingested(WaWebChat chat, WaWebMessage message, boolean chatCreated, boolean reopened, boolean messageInserted)`. Routing is a hook: `InboxIngestService` has a `setRouter(Consumer<WaWebChat>)`-free design — instead it publishes `InboxEvents.ChatCreated(chat)` / `ChatReopened(chat)` Spring events for Task 4 to route, and `InboxEvents.MessageUpserted(chat, message, inserted)` / `ChatChanged(chat)` for Task 5 to push. (`InboxEvents` is a final class of records, each `extends ApplicationEvent`-free plain records published through `ApplicationEventPublisher`.)
- `WaWebInboxListener.on(WhatsAppEvent)` handles `message_create` (ingestMessage) and `message_ack` (ingestAck); ignores `message`; catches `RuntimeException`, logs WARN.
- Preview text: `WaWebChat.LastMessage.text` = first 120 chars of `body`, or for an empty body a label by type: image "📷 Photo", video "🎥 Video", audio/ptt "🎤 Voice message", document "📄 " + filename, sticker "Sticker", location "📍 Location", vcard "👤 Contact card", poll_creation "📊 Poll", revoked "This message was deleted", else "Unsupported message".

- [ ] **Step 1: Write failing tests** in `WaWebInboxListenerIT` (`@SpringBootTest`; publish `WhatsAppEvent(this, "pn-1", WS, CUSTOMER, name, payload, seq, ts)` as `ChatContactListenerIT` does; `TestUsers.deleteAll()` plus `wa_web_*` cleanup before/after):
  - `inboundMessageCreatesContactChatAndMessage`: after `message_create` (fromMe=false, remote `919911111111@c.us`, body "hi", ack 0, timestamp T) → one contact with phone `919911111111`, one chat with `contactId`, status OPEN, `unreadCount=1`, `lastMessage.text="hi"`, `lastMessageAt=T`, `lastInboundAt=T`; one message IN/CONTACT/`chat`/status PENDING.
  - `outgoingFromPhoneIsWhatsappWebSource`: fromMe=true → message OUT, source WHATSAPP_WEB, `sentByUserId` null, chat `unreadCount=0`, `lastOutboundAt=T`.
  - `duplicateMessageCreateIsIdempotent`: same payload twice → one message, `unreadCount=1`.
  - `ackUpgradesStatusMonotonically`: create (ack 0) then `message_ack {message:{id:{_serialized:…}}, ack:3}` → status READ with `statusTimes.readAt` set; then ack 1 → still READ, `sentAt` untouched (null is fine).
  - `ackForUnknownMessageIsIgnored`: ack first, no message created.
  - `inboundOnClosedChatReopens`: chat set CLOSED by hand, inbound → OPEN and an `InboxEvents.ChatReopened` was published (capture with a `@TestConfiguration` `@EventListener` recorder bean).
  - `groupsAndStatusAreIgnored`: remote `123@g.us` → no chat.
  - `lidWithSenderPhoneLinksContact` (moved from `ChatContactListenerIT`): keep the moved tests verbatim, renamed as needed.
  - `mediaFieldsAreStored`: payload with `hasMedia:true, type:"image", mimetype:"image/jpeg", filename:null, size:1234` → `media.mimetype="image/jpeg"`, `media.mediaId` null, preview text "📷 Photo".
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.** Upsert order: contact → chat (`findByPhoneNumberIdAndRemoteId` else new, retry once on `DuplicateKeyException`) → message (`findByPhoneNumberIdAndWaMessageId`; insert sets everything; existing row only applies `applyAck`) → chat update (preview, counts, reopen) → publish events. Keep `ChatContactListener`'s "never throws" and single retry.
- [ ] **Step 4: Run → pass**; full suite green (the moved contact tests included).
- [ ] **Step 5: Commit** — `feat(inbox): persist chats and messages from WhatsApp Web events`

---

### Task 4: Routing, assignment, open/closed, settings

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{InboxSettingService,ChatRoutingService,ChatAssignmentService,ChatRoutingListener}.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/{ChatRoutingIT,ChatAssignmentIT}.java`

**Interfaces:**
- Consumes: `InboxEvents.ChatCreated/ChatReopened` (Task 3), `TeamRepository`, `UserRepository`, `UserAdminService.withAccessTo(customerId, workspaceId)` (users with access, use it to test eligibility), `User.isActive()`, `User.getDisabled()`, `UserAdminService.brandDisabled(user, workspaceId)`.
- Produces: `InboxSettingService.get(workspaceId, phoneNumberId)` → `InboxSetting` (a transient default when none stored: mode NONE, claimOnReply true, keepAssignmentOnClose false); `put(workspaceId, phoneNumberId, SettingsRequest)` validating: TEAM/ROUND_ROBIN need a team of this workspace (`VALIDATION fields.teamId="required"` / `"unknown team"`), USER needs a user with access (`fields.userId`).
- Produces: `ChatRoutingService.route(WaWebChat chat)` → applies §5 routing, records `ROUTED` action (byUserId null) when something was set, saves, returns the chat. Round robin: order = `team.memberIds`, start after `roundRobinCursor`, eligible = active, not company-disabled, not brand-disabled, still has access to the workspace; none eligible → team queue only. `ChatRoutingListener` listens to `ChatCreated` (route only when the chat's first message is IN — pass the direction in the event) and `ChatReopened` (route only when both assignments null).
- Produces: `ChatAssignmentService` with `assign(me, chat, userId?, teamId?)` (validation: at least one; user must have access to the workspace; team must be of the workspace; team given and user not a member → user cleared; sets `assignedAt`; records `ASSIGNED` with to-user/to-team names), `unassign(me, chat)`, `close(me, chat)` (status CLOSED; clears assignment unless setting `keepAssignmentOnClose`; records `CLOSED`), `reopen(me, chat)` (`REOPENED`), `claimOnReply(me, chat)` (assigns to `me` when `assignedUserId == null` and the setting says so; records `ASSIGNED`). Each publishes `InboxEvents.ChatChanged(chat)` and `InboxEvents.ActionRecorded(chat, action)`.

- [ ] **Step 1: Write failing tests.** `ChatRoutingIT`: `noneLeavesUnassigned`; `teamModeQueuesOnTeam`; `userModeAssignsUser`; `roundRobinRotatesAndSkipsDisabled` (team members [a, b, c]; b disabled; three new chats → a, c, a; cursor persisted); `roundRobinWithNoEligibleMemberQueuesOnTeam`; `outboundFirstMessageIsNotRouted`; `reopenedUnassignedChatIsRouted`; `routedActionIsRecordedBySystem` (byUserId null). `ChatAssignmentIT`: `assignToUserRecordsAction`; `assignToTeamClearsNonMemberUser`; `assignRejectsUserWithoutAccess` (ValidationException `fields.userId`); `closeClearsAssignmentByDefault`; `closeKeepsAssignmentWhenSettingSaysSo`; `claimOnReplyAssignsUnassignedOnly` (assigned-to-other stays).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): routing, assignment, close/reopen, settings`

---

### Task 5: SSE broker and views

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{InboxViews,ChatView,MessageView,ActionView,InboxStreamBroker,InboxPushListener}.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxStreamBrokerTest.java`

**Interfaces:**
- Consumes: `InboxScope.covers(chat)`, `InboxScopeService.scopeOf`, `ContactRepository`, `UserRepository`, `TeamRepository`, `InboxEvents.*`.
- Produces: `ChatView`, `MessageView`, `ActionView` records exactly as spec §10; `InboxViews.chat(WaWebChat)`, `message(WaWebMessage)`, `action(WaWebChatAction)` (`contact.name` null-safe; `numberHidden = phone == null`; `media.available = mediaId != null`). Batch helper `InboxViews.chats(List<WaWebChat>)` resolving contacts/users/teams in three `findAllById` calls.
- Produces: `InboxStreamBroker` — `SseEmitter subscribe(String phoneNumberId, Supplier<InboxScope> scope)` (emitter timeout 0L; removes itself on completion/timeout/error), `void push(String phoneNumberId, WaWebChat chat, ChatView view)` (sends `chat` with `visible = scope.get().covers(chat)` to every subscriber of the number), `pushMessage(phoneNumberId, chat, MessageView)` and `pushAction(phoneNumberId, chat, ActionView)` (only to subscribers whose scope covers the chat), `@Scheduled(fixedRate = 25_000) void ping()` sending `SseEmitter.event().comment("ping")`, `int subscribers(phoneNumberId)`. A failed send removes the subscriber. Thread-safe (`ConcurrentHashMap<String, Set<Subscriber>>`).
- `InboxPushListener` maps `MessageUpserted` → `pushMessage` + `push(chat)`, `ChatChanged` → `push(chat)`, `ActionRecorded` → `pushAction`.

- [ ] **Step 1: Write failing tests** (`InboxStreamBrokerTest`, unit, with a subclass or spy capturing what each emitter sent — inject a `Function<Long, SseEmitter>` factory returning a recording `SseEmitter` subclass that stores `SseEventBuilder` payloads): `chatEventCarriesVisibleFalseForReassignedChat` (scope MINE u1, chat assigned u2 → one `chat` event, `visible=false`); `messageIsNotPushedOutsideScope` (no `message` event for the u1 subscriber); `messagePushedInsideScope`; `failedEmitterIsDropped` (emitter whose `send` throws → `subscribers()` goes to 0); `pingReachesAllSubscribers`.
- [ ] **Step 2: Run → fail.** **Step 3: Implement.** **Step 4: Run → pass**; suite green.
- [ ] **Step 5: Commit** — `feat(inbox): views and SSE broker`

---

### Task 6: Read API (numbers, chats, thread, read, assignees, settings, assign/close)

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{InboxController,InboxQueryService,ChatsPage,ThreadPage}.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/{InboxApiTestBase,InboxApiIT}.java`

**Interfaces:**
- Consumes: everything above; `PhoneNumberService.listForWorkspace`, `PhoneNumberService.views`, `PhoneNumberService.requireInWorkspace`, `AccessService.isWorkspaceUsable`, `UserAdminService.withAccessTo`, `TeamService.list`.
- Produces endpoints per spec §10 with these signatures:
  - `GET /api/workspaces/{workspaceId}/inbox/phone-numbers` → `List<PhoneNumberView>` (`inbox:view`).
  - `GET …/phone-numbers/{phoneNumberId}/chats` params `scope` (optional; default `InboxScopeService.defaultScope`), `status` (`OPEN` default, `CLOSED`, `ALL`), `q`, `unread` (boolean), `before` (ISO instant), `limit` (1..100, default 50) → `record ChatsPage(List<ChatView> items, boolean hasMore)`. `q`: case-insensitive prefix on contact name or phone → resolve matching contact ids first (`contacts` query limited to 200), then `contactId in`. Sort `lastMessageAt desc`, `before` = `lastMessageAt lt`. Fetch `limit + 1` to compute `hasMore`.
  - `GET …/phone-numbers/{phoneNumberId}/stream` → `SseEmitter` (`produces = text/event-stream`), subscribing with `() -> scopeService.scopeOf(me, workspaceId)`.
  - `GET`/`PUT …/phone-numbers/{phoneNumberId}/settings` (`inbox:settings`) body `record SettingsRequest(RoutingMode mode, String teamId, String userId, Boolean claimOnReply, Boolean keepAssignmentOnClose)` → `record SettingsView(RoutingMode mode, String teamId, String userId, boolean claimOnReply, boolean keepAssignmentOnClose)`.
  - `GET …/inbox/assignees` (`inbox:assign`) → `record Assignees(List<Person> users, List<TeamRef> teams)` with `Person(id, name, username)`, `TeamRef(id, name)`; users = `withAccessTo` filtered to active and not disabled in the brand.
  - `GET …/inbox/chats/{chatId}` → `ChatView`; `GET …/chats/{chatId}/thread?before=&limit=` → `record ThreadPage(List<ThreadItem> items, boolean hasMore)` with `record ThreadItem(String kind, MessageView message, ActionView action, Instant at)`, oldest-first within the page (query newest-first with `limit + 1`, reverse); `POST …/chats/{chatId}/read` → `ChatView`.
  - `POST …/chats/{chatId}/assign` body `record AssignRequest(String userId, String teamId)`, `POST …/unassign`, `POST …/close`, `POST …/reopen` → `ChatView`.
  - Every chat lookup: `InboxQueryService.requireVisible(me, workspaceId, chatId)` → 404 when missing, other workspace, or outside `scopeOf(me, ws)`.
- `InboxApiTestBase`: customer + brand + number (via `TestDevices.number`) + users `manager` (WORKSPACE_MANAGER), `lead` (TEAM_LEAD, member of team "Support"), `agent` (AGENT), `agent2`; helpers to insert chats/messages directly through repositories; MockMvc with `TestUsers.accessToken`.

- [ ] **Step 1: Write failing tests** in `InboxApiIT`: `agentListsOnlyOwnChats` (3 chats: agent, agent2, unassigned → agent sees 1); `agentCannotRequestTeamScope` (403); `leadSeesTeamQueueAndUnassignedAndOwn` (chat on team Support + unassigned + own → 3; chat on agent2 excluded); `managerSeesAll`; `chatOutsideScopeIs404` (agent GETs agent2's chat); `searchByNamePrefix`; `closedFilter`; `cursorPaging` (limit 2 over 3 chats → hasMore then a second page); `threadIsOldestFirstWithActions`; `readZeroesUnread`; `assignNeedsAuthority` (agent → 403; lead → 200 with `assignedUser.name`); `closeClearsAssignment`; `settingsRoundTrip` (manager PUT then GET; lead PUT → 403); `assigneesListsEnabledUsersAndTeams`; `numbersListNeedsOnlyInboxView` (agent 200); `streamIsEventStream` (`MockMvc` GET with `Accept: text/event-stream` → status 200, content type starts with `text/event-stream`; use `asyncDispatch` is unnecessary — assert on `mvcResult.getResponse().getContentType()` after `request().asyncStarted()`).
- [ ] **Step 2: Run → fail.** **Step 3: Implement.** **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): read API, assignment and settings endpoints`

---

### Task 7: Sending (text and templates)

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{InboxSendService,SendRequest,ChatClosedException}.java`; add `POST …/chats/{chatId}/messages` to `InboxController`; add `ChatClosedException` → 409 `CHAT_CLOSED` in `ApiExceptionHandler`.
- Modify: `src/main/java/me/nageshk/omnichannel/template/TemplateService.java` — add `markUsed(String templateId, Instant at)` if no such method exists (sets `lastUsedAt`).
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxSendIT.java`

**Interfaces:**
- Consumes: `DeviceRegistry.find`, `DeviceSession.confirmed()/waState()`, `RpcDispatcher.call(device, "sendMessage", params)` (returns `CompletableFuture<JsonNode>`), `NoDeviceException` (copy `requireReadyDevice` from `api/MessagesController`), `TemplateRenderer.render(me, ws, templateId, new RenderRequest(contactId, values))` → `RenderedTemplate`, `TemplateMediaService.open(ws, mediaId)` → `MediaFile(fileName, mimeType, size, resource)`, `ChatMediaService.store(...)` (Task 8 — in this task store nothing for media; leave a `// media copy in Task 8` TODO and set `media` on the message from the template's `MediaView` with `mediaId` null), `ChatAssignmentService.claimOnReply`, `AccessService.isWorkspaceUsable`.
- Produces: `record SendRequest(String text, String templateId, Map<String, String> values)`; `InboxSendService.send(AuthenticatedUser me, String workspaceId, WaWebChat chat, SendRequest req)` → `CompletableFuture<MessageView>`; the controller returns that future. Rules per spec §6: exactly one of `text`/`templateId` (VALIDATION `fields.text="required"`), text 1–4096 trimmed, chat OPEN (else `ChatClosedException`), number usable + device ready, template `missing` → VALIDATION `fields.<name>="required"`, CONTACT_CARD → VALIDATION `fields.templateId`. RPC params: `chatId = chat.remoteId`, `content`, `options` (ObjectNode per type), `sendSeen = true`. On result: upsert message by `waMessageId` (null result → synthetic `"console:" + new ObjectId()` id, status PENDING), `source=CONSOLE`, `sentByUserId/sentByName`, `templateId`, `type` from the result (`chat` for text; template type mapping IMAGE→image, VIDEO→video, DOCUMENT→document, AUDIO→`ptt` when voice note else `audio`, LOCATION→location, POLL→poll_creation), status `ofAck(result.ack)`; chat preview + `lastOutboundAt`; `claimOnReply`; `templates.markUsed`; publish `MessageUpserted`.

- [ ] **Step 1: Write failing tests** (`InboxSendIT`, `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate` and `FakeDevice` exactly like `MessagesApiIT`: connect, send `ready`, then answer the `sendMessage` request frame): `textSendGoesThroughDeviceAndIsStoredAsConsole` (request frame `method=sendMessage`, `params.chatId` = remoteId, `params.sendSeen=true`; answer with a message model `{id:{_serialized:"true_X_1", fromMe:true}, body:"hello", type:"chat", ack:1, timestamp:…}` → 200, stored OUT/CONSOLE/SENT with `sentByName`, chat `lastMessage.text="hello"`); `laterMessageCreateKeepsConsoleSource` (then publish a `message_create` with the same `_serialized` → still CONSOLE, `sentByUserId` kept); `closedChatIs409`; `noDeviceIs409`; `claimOnReplyAssignsSender` (unassigned chat → `assignedUser.id` = agent); `templateTextRendersContactName` (template "Hi {{contact.name}}" → frame `content="Hi Aarav"`); `templateMissingValueNeverReachesDevice` (400 VALIDATION `fields.order_id`; `device.next()` with a 1 s poll returns nothing — add `FakeDevice.poll(Duration)` if absent); `templateImageSendsMediaOptions` (IMAGE template with a 1×1 PNG uploaded via `TemplateMediaService.upload` → `params.options.media.mimetype="image/png"`, `data` is the base64 of the bytes, `content` = caption; stored message `type="image"`); `contactCardTemplateIsRejected` (400); `templateMarksLastUsed`.
- [ ] **Step 2: Run → fail.** **Step 3: Implement.** **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): send text and templates from the console`

---

### Task 8: Chat media (GridFS, lazy download, cleanup on number delete)

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/{ChatMediaConfig,ChatMediaService,MediaUnavailableException,InboxCleanup}.java`; add `GET …/chats/{chatId}/messages/{messageId}/media` to `InboxController`; `MediaUnavailableException` → 410 `MEDIA_UNAVAILABLE` in `ApiExceptionHandler`; `RpcException` with code `MEDIA_UNAVAILABLE` maps to the same 410.
- Modify: `InboxSendService` — copy template media bytes into `chatMedia` and set `media.mediaId/downloadedAt` on the stored message (replaces the Task 7 TODO).
- Modify: `src/main/java/me/nageshk/omnichannel/phone/PhoneNumberService.java:115` `delete(PhoneNumber)` — call `InboxCleanup.deleteForNumber(phoneNumberId)` (chats, messages, actions, settings, media) before deleting the number.
- Modify: `src/main/resources/wabridge/scripts/{page.js,manifest.json}` — commit the bundle rebuilt by the extension plan (`npm run build` in `whatsapp-web-extension` writes it here). Do this only after the extension plan's task is complete; if the bundle is unchanged, note it in the ledger.
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxMediaIT.java`

**Interfaces:**
- Produces: `ChatMediaConfig` with `@Bean GridFsTemplate chatMediaGridFs(...)` for bucket `chatMedia` (same shape as `TemplateMediaConfig`, without a second `taskScheduler`); `ChatMediaService.store(workspaceId, phoneNumberId, messageId, mimetype, filename, byte[])` → mediaId; `open(workspaceId, mediaId)` → `TemplateMediaService.MediaFile`-like record `MediaFile(fileName, mimeType, size, GridFsResource)`; `deleteByPhoneNumber(phoneNumberId)`; `CompletableFuture<MediaFile> fetchIfNeeded(WaWebChat chat, WaWebMessage message)`: cached → immediate; else RPC `downloadMedia {messageId: waMessageId}` → result `{data, mimetype, filename, filesize}` base64-decoded (reject > 16 MB with `MediaUnavailableException`), stored, message updated, then opened. Null/absent `media` on the message → 404.
- Controller: returns `CompletableFuture<ResponseEntity<Resource>>` with the same headers as `TemplateMediaController.download` (inline for `image/*`, `video/*`, `audio/*`; attachment otherwise; `X-Content-Type-Options: nosniff`).

- [ ] **Step 1: Write failing tests** (`InboxMediaIT`, `FakeDevice` like Task 7): `firstDownloadFetchesFromDeviceAndCaches` (message with `media.mimetype=image/png`, no `mediaId`; GET → device gets `downloadMedia` frame with `params.messageId` = waMessageId; answer `{data: base64(png), mimetype:"image/png", filename:"a.png", filesize: n}` → 200, body bytes equal, `Content-Disposition` inline, `X-Content-Type-Options: nosniff`; message now has `mediaId`); `secondDownloadNeedsNoDevice` (close the FakeDevice; GET → 200 from cache); `unavailableMediaIs410` (device answers error `MEDIA_UNAVAILABLE`); `noDeviceIs409`; `messageWithoutMediaIs404`; `outsideScopeIs404`; `templateImageSendCachesMedia` (send an IMAGE template as in Task 7 → stored message `media.available` true, GET works without any `downloadMedia` frame); `deletingNumberRemovesInboxData` (`PhoneNumberService.delete` → zero chats/messages/actions/settings and the GridFS file gone).
- [ ] **Step 2: Run → fail.** **Step 3: Implement.** **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): inbound media on demand, media cache, cleanup on number delete`

---

### Task 9: Demo seed

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/InboxDemoSeeder.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxDemoSeederIT.java`

**Interfaces:**
- Consumes: `DemoSeeder.CUSTOMER`, `DemoSeeder.NUMBER`, `ContactDemoSeeder` contacts (look up by name in brand "Demo Fashion": "Aarav Sharma", "Ananya Patel", "Rohan Malhotra", "Diya Desai", "Kabir Khanna", "Meera Das"), users `demo-agent`, `demo-lead`, `TeamService.create/update`, `ChatMediaService.store`, the bundled demo PNG the template seeder uses (find its classpath path in `TemplateDemoSeeder`).
- Produces: `@Order(9)`, `@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")`; skipped when the demo number is missing or already has a chat; creates team "Demo Support" with demo-lead and demo-agent when missing; six chats per spec §12 with 4–8 messages each (timestamps spread over the last three days, `waMessageId` = `"demo_" + chat.remoteId + "_" + n`), statuses on console replies SENT/DELIVERED/READ, one WHATSAPP_WEB reply, one inbound `image` with cached bytes, actions (`ROUTED`/`ASSIGNED`/`CLOSED`) matching the state; chat `unreadCount` = inbound messages after the last console reply.

- [ ] **Step 1: Write failing tests** (`@SpringBootTest(properties = "omnichannel.seed.demo=true")` like `ContactDemoSeederIT`): `seedsSixChatsOnDemoNumber` (counts, two assigned to demo-agent, one queued on "Demo Support", one CLOSED, image message `media.available`), `secondRunIsNoop` (run the seeder twice → still six).
- [ ] **Step 2: Run → fail.** **Step 3: Implement.** **Step 4: Run → pass**; full suite green.
- [ ] **Step 5: Commit** — `feat(inbox): demo chats and messages`

---

## Self-review notes
- Spec coverage: §3 → T1; §8 → T1, T2, T6; §4 → T3; §5 → T4; §7 streams → T5/T6, media → T8; §6 → T7; §9 bundle → T8 (after the extension plan); §12 → T9. Deletion of inbox data on number delete → T8.
- Types: `InboxScope`, `ListScope`, `ChatView`, `MessageView`, `ActionView`, `InboxEvents.*`, `SendRequest`, `ChatsPage`, `ThreadPage` are named once and reused by name in later tasks.
- Review Focus items are pinned to T3, T7, T4, T5, T7.
