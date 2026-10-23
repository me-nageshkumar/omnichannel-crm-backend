# Chat Assignment (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the inbox team-centred: teams own numbers, routing is a brand default with team overrides and a team-wide round robin, agents have an availability status, returning customers stick to their agent, agents claim from the queue and hand chats off, and one chat list/stream spans all of a user's numbers.

**Architecture:** Everything stays in the `inbox`, `phone`, `tenant`, `user` and `access` packages of the single Spring Boot jar. `PhoneNumber.teamId` is the owning team; `InboxSetting` is re-keyed to `{workspaceId, teamId|null}`; `ChatRoutingService` gains availability, sticky, exclusion and a team-wide cursor on `Team`; `InboxScope` gains a QUEUE level and the set of numbers the user's teams own; `InboxQueryService`/`InboxStreamBroker` work across numbers. Old per-number list/stream/settings endpoints are removed at the end (the console plan switches in the same release).

**Tech Stack:** Java 21, Spring Boot 3, Spring Data MongoDB (embedded flapdoodle in `*IT`), Lombok, JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-23-chat-assignment-design.md` (sections cited as §).

**Branch:** `chat-assignment` worktree at `.worktrees/chat-assignment`. Run tests with `./mvnw -q test -Dtest=<Class>` (ITs are plain JUnit classes named `*IT`; `./mvnw -q verify` runs everything).

## Global Constraints

- Mongo collections and fields are camelCase; no new collection except `inboxSettingsLegacy` (§3.4).
- Authority codes are `noun:verb`; new code `inbox:view-queue` (§3.5). `AuthorityCheckScanner` must find every code used in `@PreAuthorize` in the `Authority` enum or the `authorities` collection.
- Every list/stream query is restricted by `workspaceId`; a record outside the caller's visibility answers 404 `NotFoundException`, never 403, except where the spec names 403 (missing authority) or 422 (`ValidationException`).
- Errors use the existing `{code, message}` shape via `CodedException` subclasses (`api` package).
- Optimistic locking: every mutated entity keeps `@Version`; concurrent claim loses with 409 `CHAT_TAKEN` (`ChatTakenException`, existing).
- Default brand setting: `assignToTeam=true, roundRobin=false, userId=null, stickyDays=30, claimOnReply=true, keepAssignmentOnClose=false` (§3.4).
- Sticky window default 30 days, `0` = off (§3.3).
- Fixtures: `BuiltInRoles.TEAM_LEAD` no longer exists after Task 1; tests use `WORKSPACE_MANAGER` for team-level powers and `AGENT` otherwise.
- Commits: one per task, message in imperative mood, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A number with **no owning team** receives an inbound message: it must stay unassigned with no `ROUTED` action and not throw (Task 5 test `unownedNumberLeavesChatUnassigned`).
2. A **disabled or removed** member is the sticky agent / round-robin cursor: routing must skip them and never assign to them (Task 5 tests `stickySkipsIneligibleAgent`, `roundRobinRestartsWhenCursorLeftTheTeam`).
3. **Hand-back with only the caller available** must land in the queue, never back on the caller (Task 8 test `handBackWithNobodyElseQueues`).
4. A chat **transferred to a team that owns no number** (guest only): members must still see it in their list and stream (Task 7 test `guestNumberShowsOnlyCoveredChats`, Task 10 test `streamDeliversGuestChat`).
5. **Legacy settings migration** on a brand with numbers configured differently must pick the most common routing and never crash on a document with `mode=USER` whose user is gone (Task 4 test `migrationPicksMostCommonRoutingAndSurvivesStaleUsers`).

---

### Task 1: Remove the TEAM_LEAD role (§0)

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/access/Authority.java` (16 default lists), `BuiltInRoles.java`, `RoleSeeder.java:38`, `RoleService.java:30-32`
- Create: `src/main/java/me/nageshk/omnichannel/access/TeamLeadRoleMigration.java`
- Modify: `src/main/java/me/nageshk/omnichannel/phone/DemoSeeder.java:74`, `inbox/InboxDemoSeeder.java` (`Staff.LEAD` → `Staff.MANAGER` backed by `demo-manager`)
- Test: `src/test/java/me/nageshk/omnichannel/access/TeamLeadRoleMigrationIT.java` (new); update `AuthorityTest`, `RoleSeederIT`, `AssignmentPolicyTest`, `RolesApiIT`, `AccessServiceIT`, `InboxApiTestBase`, `InboxScopeServiceIT`, `ContactApiTestBase`, `TemplateApiTestBase`, `CampaignTestBase`, `TeamsApiIT`, `WorkspaceUsersApiIT`, `PhoneNumbersApiIT`, `DeviceConnectIT`, `DemoSeederIT`, `InboxDemoSeederIT`

**Interfaces:**
- Produces: `BuiltInRoles` without `TEAM_LEAD`; `InboxApiTestBase.lead` renamed `manager2` with role WORKSPACE_MANAGER (the `support` team's member) so later tasks have a second manager and two agents.

- [ ] **Step 1: Write the failing migration test** `TeamLeadRoleMigrationIT`:
  - `teamLeadUsersBecomeAgentsAndTheRoleIsDeleted`: insert a `roles` document named `TEAM_LEAD` (builtIn=true, level BRAND) and a user with `roleId` = that id and another with `workspaceRoles[0].roleId` = that id; run `migration.migrate()`; assert both now reference `roles.findByName(AGENT)`, `roles.findByName("TEAM_LEAD")` is empty, one `RoleChange` with `roleName="TEAM_LEAD"`, `action=DELETED`, `changedBy="system"`.
  - `migrationIsIdempotent`: run twice; second run changes nothing (count of `roleChanges` stays 1).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=TeamLeadRoleMigrationIT` → FAIL (class missing).
- [ ] **Step 3: Implement** `@Component @Order(2) class TeamLeadRoleMigration implements ApplicationRunner` with `public void migrate()`: look up role by name `TEAM_LEAD`; if absent return; `MongoTemplate.updateMulti` on `users` setting `roleId` and `workspaceRoles.$[r].roleId` (arrayFilters `r.roleId == old`) to the AGENT role id; insert the `RoleChange`; `roles.delete(role)`; log migrated count. Remove `TEAM_LEAD` from `BuiltInRoles`, `RoleSeeder.BUILT_INS`, `RoleService.BUILT_IN_NAMES`, and every `Authority` default list. `RoleSeeder` test `seedsSevenBuiltInRoles` becomes six.
- [ ] **Step 4: Fix fixtures**: in `InboxApiTestBase` replace `lead` by `manager2 = brandUser("manager2", WORKSPACE_MANAGER)` and `leadToken` by `manager2Token`; `support = team("Support", manager2, agent)`. Replace `TEAM_LEAD` in the other listed tests by `WORKSPACE_MANAGER` where the test needs `inbox:view-team`/`contact:edit`-class powers, else `AGENT`; `InboxApiIT.leadSeesTeamQueueAndUnassignedAndOwn` becomes `managerSeesTeamQueueAndUnassignedAndOwn` using `manager2Token`. `DemoSeeder`: `person("Demo Lead", "demo-lead", BuiltInRoles.AGENT, …)`; `InboxDemoSeeder`: `enum Staff { AGENT, MANAGER }`, scripts that used `LEAD` use `MANAGER` (`demo-manager`).
- [ ] **Step 5: Run** `./mvnw -q verify` → all green.
- [ ] **Step 6: Commit** `Remove the TEAM_LEAD role`.

### Task 2: Owning team on a phone number (§3.1)

**Files:**
- Modify: `phone/PhoneNumber.java` (+`teamId`), `phone/PhoneNumberView.java` (+`TeamRef team`), `phone/PhoneNumberService.java` (+`setTeam`, views), `phone/BrandPhoneNumberController.java` (+`PUT /{id}/team`), `tenant/TeamController.java` (`TeamView` +`List<NumberRef> phoneNumbers`), `tenant/TeamService.java` (`list` resolves numbers)
- Create: `phone/PhoneNumberEvents.java` (`record TeamChanged(PhoneNumber number, String previousTeamId)`)
- Test: `phone/PhoneNumbersApiIT.java`, `tenant/TeamsApiIT.java`

**Interfaces:**
- Produces: `PhoneNumber.getTeamId()`; `PhoneNumberService.setTeam(PhoneNumber n, String teamId /*nullable*/) : PhoneNumber` (validates team in same workspace else `ValidationException("teamId","unknown team")`, publishes `PhoneNumberEvents.TeamChanged` when changed); `PhoneNumberRepository.findByWorkspaceIdAndTeamIdIn(String ws, Collection<String> teamIds)`; `PhoneNumberView.team : {id, name}|null`; `TeamController.TeamView.phoneNumbers : [{id, number, label}]`.
- API: `PUT /api/workspaces/{ws}/phone-numbers/{id}/team {teamId: string|null}` (`phone-number:manage`) → `PhoneNumberView`.

- [ ] **Step 1: Failing tests** in `PhoneNumbersApiIT`: `setTeamStoresAndShowsTheTeam` (PUT with a team of the brand → 200, `team.id` equals; GET list shows it), `setTeamRejectsTeamOfAnotherWorkspace` (→ 422 field `teamId`), `clearTeamWithNull` (→ `team` null). In `TeamsApiIT`: `teamListsItsNumbers` (after PUT, `GET /teams` item has `phoneNumbers[0].id`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=PhoneNumbersApiIT,TeamsApiIT` → FAIL.
- [ ] **Step 3: Implement** the fields, repository method, service, controller endpoint and `TeamService.list` join (`phoneNumbers.findByWorkspaceIdAndTeamIdIn`, grouped by team).
- [ ] **Step 4: Run** the two ITs → PASS. **Step 5: Commit** `Give phone numbers an owning team`.

### Task 3: Agent availability (§3.2)

**Files:**
- Modify: `user/User.java` (+`Availability availability`), `user/UserService.java` (`CurrentUser` +`availability`; `setAvailability`), `user/UserController.java` (+`PUT /availability`), `inbox/InboxController.java` (`Person` +`availability`)
- Create: `user/Availability.java` (`record Availability(Status status, Instant at) { enum Status { AVAILABLE, AWAY } }`), `user/UserEvents.java` (`record AvailabilityChanged(User user, Availability.Status previous)`)
- Test: `user/UserApiIT.java` (or existing me-endpoint IT), `inbox/InboxApiIT.assigneesListsEnabledUsersAndTeams`

**Interfaces:**
- Produces: `User.getAvailability()` (null ⇒ AVAILABLE; add `public Availability.Status availabilityStatus()` returning `AVAILABLE` when null); `UserService.setAvailability(String username, Availability.Status) : CurrentUser` (publishes `UserEvents.AvailabilityChanged` only on change).
- API: `PUT /api/users/me/availability {status: "AVAILABLE"|"AWAY"}` → `CurrentUser`; `GET /api/users/me` gains `availability: {status, at}`; `GET …/inbox/assignees` users gain `availability: "AVAILABLE"|"AWAY"`.

- [ ] **Step 1: Failing tests**: `meShowsAvailableByDefault` (GET me → `availability.status == "AVAILABLE"`), `putAvailabilityAwayThenMe` (PUT AWAY → 200 and GET shows AWAY with `at` set), `putAvailabilityRejectsUnknownStatus` (→ 400). `assigneesListsEnabledUsersAndTeams` asserts `users[*].availability` present.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS. **Step 5: Commit** `Add agent availability status`.

### Task 4: Routing settings as brand default + team override (§3.4)

**Files:**
- Modify: `inbox/InboxSetting.java` (re-key), `inbox/InboxSettingService.java`, `inbox/InboxSettingRepository.java`, `inbox/InboxCleanup.java` (drop `settings.deleteByPhoneNumberId`), `inbox/InboxController.java` (settings endpoints), `inbox/ChatAssignmentService.java` (`close`, `claimOnReply` use `settings.effectiveFor(chat)`)
- Create: `inbox/InboxSettingsMigration.java`, `inbox/RoutingSetting.java` (view/request record)
- Test: `inbox/InboxSettingServiceIT.java` (rewrite), `inbox/InboxSettingsMigrationIT.java` (new), `inbox/InboxApiIT.settingsRoundTrip` (rewrite)

**Interfaces:**
- `InboxSetting`: `@Document("inboxSettings") @CompoundIndex(name="workspace_team_unique", def="{'workspaceId':1,'teamId':1}", unique=true)`; fields `id, customerId, workspaceId, teamId (nullable), routing Routing(RoutingMode mode, String userId), int stickyDays = 30, boolean claimOnReply = true, boolean keepAssignmentOnClose = false`. `phoneNumberId`, `roundRobinCursor` removed (cursor moves to `Team` in Task 5).
- `RoutingMode` unchanged (`NONE|TEAM|USER|ROUND_ROBIN`). Mapping: `assignToTeam=false → NONE`; `true & roundRobin → ROUND_ROBIN`; `true & userId → USER`; `true → TEAM`. `roundRobin` and `userId` together → 422 `routing`.
- `record RoutingSetting(String teamId, boolean inherited, boolean assignToTeam, boolean roundRobin, String userId, int stickyDays, boolean claimOnReply, boolean keepAssignmentOnClose)` — the JSON for GET/PUT (PUT ignores `teamId`/`inherited`).
- `InboxSettingService`: `InboxSetting brandDefault(String ws)` (creates the default document if missing); `Optional<InboxSetting> override(String ws, String teamId)`; `InboxSetting effective(String ws, String teamId /*nullable*/)` (override else default); `InboxSetting effectiveFor(WaWebChat chat)` (owning team of `chat.phoneNumberId` via `PhoneNumberRepository`); `InboxSetting putDefault(String ws, RoutingSetting req)`; `InboxSetting putOverride(String ws, String teamId, RoutingSetting req)`; `void deleteOverride(String ws, String teamId)`. Validation: `userId` only on an override and must be a member of that team (`ValidationException("userId", "must be a member of the team")`), `stickyDays` 0..365, `USER` on default → `ValidationException("userId", "only a team override may name a member")`.
- API (`inbox:settings`): `GET /api/workspaces/{ws}/inbox/settings` → `RoutingSetting` (teamId null); `PUT` same path; `GET /inbox/settings/teams/{teamId}` → override, or the default with `inherited=true, teamId=teamId`; `PUT` creates/updates; `DELETE` removes (204). `GET /inbox/settings/teams` → list of `RoutingSetting` for every team (inherited flag each) — one call for the console page.
- `InboxSettingsMigration` (`@Component @Order(5) ApplicationRunner`): for every legacy document (one having `phoneNumberId`): copy to `inboxSettingsLegacy`, delete; group by `workspaceId`; the brand default's `routing.mode` = most common legacy mode among that brand's documents (`USER` counts as `TEAM`; tie → `TEAM`), `claimOnReply`/`keepAssignmentOnClose` from the first document; create the default only if none exists. Expose `Optional<Document> legacyFor(String phoneNumberId)` for Task 9.

- [ ] **Step 1: Failing tests** `InboxSettingServiceIT`: `brandDefaultIsCreatedLazilyWithSpecDefaults` (assignToTeam true, roundRobin false, stickyDays 30), `effectiveReturnsOverrideWhenPresentElseDefault`, `overrideUserMustBeMember`, `defaultRefusesUserMode`, `roundRobinAndUserTogetherRejected`, `stickyDaysOutOfRangeRejected`, `effectiveForChatUsesOwningTeam` (number with teamId → override applies; number without team → default). `InboxSettingsMigrationIT`: `legacyDocumentsMoveToLegacyCollectionAndSeedTheDefault`, `migrationPicksMostCommonRoutingAndSurvivesStaleUsers` (three numbers: TEAM, ROUND_ROBIN, USER with unknown user → default mode TEAM; no exception), `secondRunIsNoop`. `InboxApiIT.settingsRoundTrip`: PUT default `{assignToTeam:true, roundRobin:true, stickyDays:7}` → GET shows it; PUT team override with `userId` member → `GET /inbox/settings/teams/{id}` `inherited=false`; DELETE → `inherited=true`; `GET /inbox/settings/teams` lists both teams.
- [ ] **Step 2: Run** → FAIL (compile). **Step 3: Implement** entity, repository (`findByWorkspaceIdAndTeamId`, `findByWorkspaceId`, `deleteByWorkspaceIdAndTeamId`), service, migration, controller; delete `SettingsView`/`SettingsRequest` records; update `InboxCleanup`; `ChatAssignmentService.close/claimOnReply` → `settings.effectiveFor(chat)`. `ChatRoutingService`: resolve the team from `PhoneNumber.teamId` (Task 2) and the mode from `settings.effectiveFor(chat)`; the rest of its behaviour changes in Task 5.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=InboxSettingServiceIT,InboxSettingsMigrationIT,InboxApiIT,ChatAssignmentIT` → PASS; `ChatRoutingIT` may still reference `configure(mode, teamId, userId)` — adapt its helper to `settings.putDefault/putOverride` so it compiles and passes. **Step 5: Commit** `Re-key inbox settings to brand default and team overrides`.

### Task 5: Routing with owning team, availability, sticky, exclusion, team-wide cursor (§3.1–3.4)

**Files:**
- Modify: `inbox/ChatRoutingService.java` (rewrite), `inbox/ChatRoutingListener.java`, `tenant/Team.java` (+`roundRobinCursor`), `inbox/WaWebChat.java` (+`lastAgentUserId`, `lastAgentAt`), `inbox/WaWebChatAction.java` (+`RoutedReason reason`, `excludedUserId`, `fromTeamId`, `fromTeamName`), `inbox/ActionType.java` (+`TRANSFERRED`), `inbox/ActionView.java` (+`reason`, `fromTeam`), `inbox/InboxViews.java`, `inbox/InboxSendService.java` (set `lastAgent*` when a `UserActor` sends), `inbox/ChatAssignmentService.java` (set `lastAgent*` on assign to a user)
- Create: `inbox/RoutedReason.java` (`enum RoutedReason { STICKY, HANDED_BACK, OWNER_CHANGED, MEMBER_REMOVED }`)
- Test: `inbox/ChatRoutingIT.java` (rewrite), `inbox/ChatAssignmentIT.java` (+lastAgent), `inbox/InboxSendIT.java` (+lastAgent)

**Interfaces:**
- `ChatRoutingService`:
  - `WaWebChat route(WaWebChat chat)` — owning team of the chat's number; no team → return untouched (no action). Sticky first (unless disabled), then the effective setting.
  - `WaWebChat route(WaWebChat chat, Team team, RoutingOptions options)` with `record RoutingOptions(String excludeUserId, boolean sticky, RoutedReason reason)`; `RoutingOptions.DEFAULT = (null, true, null)`.
  - Eligibility `boolean eligible(User u, Team team, String workspaceId)` = active, not disabled, not brand-disabled, member of `team`, `availabilityStatus()==AVAILABLE`.
  - Round robin: next eligible member after `team.getRoundRobinCursor()` in `memberIds` order, skipping `excludeUserId`; cursor persisted with `mongo.updateFirst(query(where("_id").is(team.getId())), update("roundRobinCursor", id), Team.class)`; none eligible → team queue.
  - Sticky: `chat.lastAgentUserId != null && lastAgentAt >= now - stickyDays && eligible(agent)` → assign, reason STICKY. Skipped when `options.sticky == false` or `stickyDays == 0`.
  - `routed(...)` records `ROUTED` with `toUser/toTeam`, `reason`, `excludedUserId`; never writes `lastAgent*`.
- `ChatRoutingListener`: `ChatCreated(IN)` → `route`; `ChatReopened`, `CampaignChatFirstReply` → `route` when `assignedUserId == null && assignedTeamId == null` (as today; sticky inside `route`).
- `WaWebChat.lastAgentUserId/lastAgentAt` set in `InboxSendService` (user sends) and `ChatAssignmentService.assign/claimOnReply` (user assigned). `ChatView` gains `Ref lastAgent` (resolved in `InboxViews.chats` like `assignedUser`).

- [ ] **Step 1: Failing tests** (`ChatRoutingIT`, helpers: `owned(team)` sets `pn.teamId`; `configure(team, assignToTeam, roundRobin, userId)` writes the override; `available(user,false)` sets AWAY): `unownedNumberLeavesChatUnassigned` (no action), `teamOnlyQueuesOnOwningTeam`, `roundRobinRotatesAcrossTwoNumbersOfTheTeam` (A,B,A,B → P,R,P,R with Q AWAY), `roundRobinSkipsAwayAndDisabled`, `roundRobinWithNobodyAvailableQueues`, `roundRobinRestartsWhenCursorLeftTheTeam`, `oneMemberModeFallsBackToQueueWhenAway`, `stickyReturnsCustomerToLastAgentWithinWindow` (set `lastAgentUserId`, `lastAgentAt=now-5d`, close, inbound → assigned, action reason STICKY), `stickyExpiresAfterWindow` (`lastAgentAt=now-31d` → round robin), `stickySkipsIneligibleAgent` (AWAY → round robin; `lastAgentUserId` unchanged), `stickyOffWhenZeroDays`, `stickyAppliesOnCampaignFirstReply` (publish `CampaignChatFirstReply` on an unassigned chat), `excludeUserIdSkipsThatMember`, `outboundFirstMessageIsNotRouted`, `routedActionIsRecordedBySystem`. `ChatAssignmentIT.assignToUserSetsLastAgent`; `InboxSendIT.userSendSetsLastAgent`, `campaignSendDoesNotSetLastAgent`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ChatRoutingIT` → FAIL. **Step 3: Implement.** **Step 4: Run** `ChatRoutingIT,ChatAssignmentIT,InboxSendIT,CampaignChatIT` → PASS. **Step 5: Commit** `Route by owning team with availability, sticky agent and team-wide round robin`.

### Task 6: Re-route the queue when an agent becomes available (§3.2)

**Files:**
- Create: `inbox/AvailabilityRoutingListener.java`
- Modify: `inbox/WaWebChatRepository.java` (+`findByWorkspaceIdAndAssignedTeamIdAndAssignedUserIdIsNullAndStatusOrderByLastMessageAtAsc`)
- Test: `inbox/AvailabilityRoutingIT.java`

**Interfaces:**
- `@EventListener on(UserEvents.AvailabilityChanged e)`: only when `previous == AWAY && now AVAILABLE`; for each team the user belongs to (all workspaces they have access to), when `settings.effective(ws, team.id).routing.mode == ROUND_ROBIN`: queued OPEN chats of that team, oldest first → `routing.route(chat, team, RoutingOptions.DEFAULT)`.

- [ ] **Step 1: Failing tests**: `comingBackAvailableDrainsRoundRobinQueueAcrossMembers` (P AWAY, R AWAY, 3 queued chats; P → AVAILABLE with R still AWAY → all 3 to P; variant with R AVAILABLE → alternate P,R,P), `teamOnlySettingDoesNotAutoAssign`, `awayToAwayDoesNothing`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Re-route queued chats when an agent becomes available`.

### Task 7: QUEUE scope, number visibility, cross-number chat list (§3.5, §3.7)

**Files:**
- Modify: `access/Authority.java` (+`INBOX_VIEW_QUEUE("inbox:view-queue", BRAND, "Also see the unassigned chats of own teams' numbers and claim them", CUSTOMER_ADMIN, WORKSPACE_MANAGER, AGENT)`), `inbox/InboxScope.java`, `inbox/InboxScopeService.java`, `inbox/InboxQueryService.java`, `inbox/InboxController.java` (`GET /inbox/phone-numbers`, new `GET /inbox/chats`), `phone/PhoneNumberView.java` (+`Boolean guest`)
- Test: `inbox/InboxScopeTest.java`, `inbox/InboxScopeServiceIT.java`, `inbox/InboxApiIT.java`, `access/RoleSeederIT.inboxAuthoritiesFollowTheRoleLadder`

**Interfaces:**
- `InboxScope(Level level, String userId, Set<String> teamIds, Set<String> ownedNumberIds)`; `enum Level { ALL, TEAM, QUEUE, MINE }`.
  - `criteria()`: ALL → empty; TEAM → `assignedUserId=me` OR `assignedTeamId∈teamIds` OR (`unassigned` AND `phoneNumberId∈ownedNumberIds`); QUEUE → `assignedUserId=me` OR (`assignedUserId=null` AND `assignedTeamId∈teamIds`) OR (`unassigned` AND `phoneNumberId∈ownedNumberIds`); MINE → `assignedUserId=me`. `covers()` mirrors. `allows(ListScope)`: MINE always; UNASSIGNED from QUEUE; TEAM from TEAM; ALL only ALL. `criteriaFor(UNASSIGNED)` at QUEUE/TEAM = (`assignedUserId=null` AND (`assignedTeamId∈teamIds` OR (`assignedTeamId=null` AND `phoneNumberId∈owned`))).
- `InboxScopeService.scopeOf(me, ws)`: level from codes (`inbox:view-all` → ALL, `inbox:view-team` → TEAM, `inbox:view-queue` → QUEUE, else MINE); `teamIds` = own memberships for TEAM/QUEUE/MINE (MINE too, so hand-offs can check), all teams for ALL; `ownedNumberIds` = `phoneNumbers.findByWorkspaceIdAndTeamIdIn(ws, teamIds)` ids (ALL → every number).
- `InboxScopeService.visibleNumbers(me, ws) : List<VisibleNumber>` with `record VisibleNumber(PhoneNumber number, boolean guest)`: ALL → all, guest=false; else owned numbers (guest=false) ∪ distinct `phoneNumberId` of chats matching `scope.criteria()` outside owned (guest=true), via `mongo.findDistinct`.
- `InboxQueryService.chats(me, ws, Collection<String> phoneNumberIds /*empty = all visible*/, ListScope, status, q, unread, before, limit)`: requested ∩ visible; a requested id not visible → `NotFoundException("phone number not found")`; criteria adds `phoneNumberId ∈ selected`. `requireVisible(me, ws, chatId)`: chat in workspace, `scope.covers(chat)`, and `chat.phoneNumberId ∈ visible` (guest counts).
- API: `GET /api/workspaces/{ws}/inbox/phone-numbers` → `PhoneNumberView[]` with `team` and `guest`; `GET /api/workspaces/{ws}/inbox/chats?phoneNumberIds=a,b&scope=&status=&q=&unread=&before=&limit=` → `ChatsPage` (same shape as today). Old `GET /phone-numbers/{id}/chats` stays until Task 11.

- [ ] **Step 1: Failing tests** `InboxScopeTest`: `queueLevelCoversOwnTeamQueueAndUnownedUnassignedOnOwnedNumbers`, `queueLevelDoesNotCoverTeammatesAssignedChat`, `queueAllowsUnassignedListNotTeamList`. `InboxScopeServiceIT`: `agentGetsQueueWithOwnTeamsAndOwnedNumbers`, `managerGetsAllWithEveryNumber`. `InboxApiIT`: `agentListsChatsAcrossOwnedNumbersNewestFirst` (Sales owns A,B; chats on both; `GET /inbox/chats` returns both ordered), `phoneNumberFilterNarrowsToB`, `numberOutsideTeamsIs404OnListAndChat` (C owned by Support), `unassignedTabShowsQueueOfOwnTeam`, `agentCannotSeeTeammatesAssignedChat`, `guestNumberShowsOnlyCoveredChats` (chat on C assigned to agent → `GET /inbox/phone-numbers` lists C with `guest=true`; `GET /inbox/chats?phoneNumberIds=C` returns only that chat), `adminSeesUnownedNumber`. `RoleSeederIT.inboxAuthoritiesFollowTheRoleLadder`: AGENT has `inbox:view-queue`, not `inbox:view-team`.
- [ ] **Step 2–5:** FAIL → implement → `./mvnw -q test -Dtest=InboxScopeTest,InboxScopeServiceIT,InboxApiIT,RoleSeederIT,AuthorityTest` PASS → commit `Add queue scope, team-owned number visibility and a cross-number chat list`.

### Task 8: Claim, hand-offs and restricted assignees (§3.5, §3.6)

**Files:**
- Modify: `inbox/ChatAssignmentService.java` (+`claim`, `handoff`; `assign` assignee check), `inbox/InboxController.java` (+`POST /chats/{id}/claim`, `POST /chats/{id}/handoff`; `GET /assignees?phoneNumberId=`)
- Create: `inbox/HandoffRequest.java` (`record HandoffRequest(Kind kind, String userId, String teamId) { enum Kind { TO_USER, TO_TEAM } }`), `api/AssigneeCannotSeeNumberException.java` (422, code `ASSIGNEE_CANNOT_SEE_NUMBER`)
- Test: `inbox/ChatAssignmentIT.java`, `inbox/InboxApiIT.java`

**Interfaces:**
- `ChatAssignmentService.claim(AuthenticatedUser me, WaWebChat chat) : WaWebChat` — `assignedUserId != null` → `ChatTakenException`; sets me (team kept), `lastAgent*`, action `ASSIGNED` by me to me; `@Version` conflict → `ChatTakenException`.
- `ChatAssignmentService.handoff(AuthenticatedUser me, WaWebChat chat, HandoffRequest req, boolean manager) : WaWebChat` — unless `manager`, `chat.assignedUserId` must equal `me.userId()` else `NotFoundException("chat not found")`.
  - `TO_USER`: target must be member of `chat.assignedTeamId` (or, when the chat has no team, of the number's owning team) and eligible-to-see (member or `inbox:view-all`) else `ValidationException("userId", "not a member of the chat's team")`; availability not checked; action `ASSIGNED`.
  - `TO_TEAM` with `teamId == null` or `== chat.assignedTeamId`: clear user, `routing.route(chat, team, new RoutingOptions(me.userId(), false, HANDED_BACK))`.
  - `TO_TEAM` with another team of the workspace: record `TRANSFERRED` (`fromTeam`, `toTeam`, by me), set `assignedTeamId=target`, user null, then `routing.route(chat, target, RoutingOptions.DEFAULT)`. Unknown team → `ValidationException("teamId","unknown team")`.
- `assign(me, chat, userId, teamId)`: user must be able to see the chat's number (member of its owning team or holds `inbox:view-all` in the workspace) else `AssigneeCannotSeeNumberException`; team must be any team of the workspace (transfer by manager) — a user+team pair where the user isn't a member clears the user as today.
- API: `POST …/chats/{id}/claim` (`inbox:send`) → `ChatView`; `POST …/chats/{id}/handoff {kind, userId?, teamId?}` (`inbox:send`; `manager` = holds `inbox:assign`) → `ChatView`; `GET …/assignees?phoneNumberId=` (`inbox:view-queue` or `inbox:assign`): `users` = owning team's eligible-to-see members + `inbox:view-all` holders with `availability`, `teams` = all teams of the workspace.

- [ ] **Step 1: Failing tests** `ChatAssignmentIT`: `claimTakesUnassignedChatAndSetsLastAgent`, `claimOfTakenChatIs409`, `handoffToTeammateAssigns`, `handoffToNonMemberIs422`, `handBackRoutesToAnotherMember` (round robin, P hands back → R, action reason HANDED_BACK with `excludedUserId=P`), `handBackWithNobodyElseQueues`, `handBackSkipsSticky` (`lastAgentUserId=P`), `transferRecordsAndRoutesPerTargetSetting` (Support team-only → queue; Support round robin → S), `handoffOfSomeoneElsesChatIs404ForAgent`, `managerMayHandoffAnyVisibleChat`, `assignToUserWhoCannotSeeNumberIs422`. `InboxApiIT`: `claimEndpointNeedsOnlyInboxSend`, `handoffEndpointShapes`, `assigneesForNumberListsOwningTeamAndAdminsWithAvailability`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add claim and hand-off actions and restrict assignees to number viewers`.

### Task 9: Re-route on owner change and member removal; legacy override seeding (§3.1, §3.4)

**Files:**
- Create: `inbox/OwnershipRoutingListener.java`
- Modify: `tenant/TeamService.java` (`update` publishes `TeamEvents.MembersRemoved(Team team, Set<String> removedUserIds)`), create `tenant/TeamEvents.java`, `inbox/InboxSettingsMigration.java` (`legacyFor`), `inbox/WaWebChatRepository.java` (+`findByPhoneNumberIdAndStatusAndAssignedUserIdIn`, `findByPhoneNumberIdAndStatus`)
- Test: `inbox/OwnershipRoutingIT.java`

**Interfaces:**
- `on(PhoneNumberEvents.TeamChanged e)`: if a legacy document exists for the number with `routing.teamId == e.number().getTeamId()` and the team has no override → create override from it (mode, claimOnReply, keepAssignmentOnClose; USER mode keeps `userId` only if still a member). Then every OPEN chat on the number assigned to a user who is not a member of the new team (or any user when the new team is null) → unassign and `route(chat, newTeam, RoutingOptions(null, true, OWNER_CHANGED))` (null team → just unassign, action `UNASSIGNED` by system).
- `on(TeamEvents.MembersRemoved e)`: OPEN chats on the team's numbers assigned to a removed user → `route(chat, team, RoutingOptions(removedId, true, MEMBER_REMOVED))`.

- [ ] **Step 1: Failing tests**: `ownerChangeReroutesOpenChatsOfOldMembers`, `ownerChangeKeepsChatsOfUsersStillAbleToSee` (manager with `inbox:view-all`), `ownerClearedUnassignsOpenChats`, `memberRemovalReroutesTheirOpenChats`, `legacyRoutingSeedsTeamOverrideOnFirstOwnerAssignment`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Re-route chats when a number's owner or a team's members change`.

### Task 10: One stream per viewer across numbers (§3.7)

**Files:**
- Modify: `inbox/InboxStreamBroker.java` (key by `workspaceId`; `Subscriber(SseEmitter, Supplier<Viewer>)` with `record Viewer(InboxScope scope, Set<String> phoneNumberIds /*selected ∩ visible*/)`), `inbox/InboxPushListener.java` (`chat.getWorkspaceId()`), `inbox/InboxController.java` (+`GET /inbox/stream?phoneNumberIds=`)
- Test: `inbox/InboxStreamBrokerTest.java`, `inbox/InboxStreamIT.java`, `inbox/InboxApiIT.streamIsEventStream`

**Interfaces:**
- `InboxStreamBroker.subscribe(String workspaceId, Supplier<Viewer> viewer) : SseEmitter`; `push/pushMessage/pushAction(String workspaceId, WaWebChat chat, …)` deliver to subscribers whose `viewer.phoneNumberIds` contains `chat.phoneNumberId`; `chat` events carry `visible = scope.covers(chat)` (as today, so a chat leaving the viewer's scope is announced with `visible:false`); `message`/`action` only when visible. `subscribers(String workspaceId)`.
- Controller: `GET /api/workspaces/{ws}/inbox/stream?phoneNumberIds=a,b` (`inbox:view`): resolves `Viewer` on every push/ping from the token: `scopeOf` + `visibleNumbers` ∩ requested (empty = all visible); requested id not visible at subscribe time → 404.

- [ ] **Step 1: Failing tests** `InboxStreamBrokerTest`: `deliversOnlyForSelectedNumbers`, `chatLeavingScopeIsAnnouncedInvisible`. `InboxStreamIT`: `streamSpansTwoNumbers`, `streamDeliversGuestChat` (chat on Support's C assigned to Sales agent → event arrives), `streamRequestingForeignNumberIs404`, plus the existing drop-on-ping/push tests moved to the new URL.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Stream inbox events per viewer across numbers`.

### Task 11: Remove per-number endpoints, demo seed, contract (§4)

**Files:**
- Modify: `inbox/InboxController.java` (delete `GET /phone-numbers/{id}/chats`, `GET /phone-numbers/{id}/stream`, `GET|PUT /phone-numbers/{id}/settings`), `inbox/InboxDemoSeeder.java` (Demo Support owns the demo number; queued script uses team queue), `inbox/InboxIndexesIT.java` (settings index), docs
- Test: every `*IT` compiles against the new endpoints; `InboxDemoSeederIT`

- [ ] **Step 1:** Delete the old endpoints and `SettingsView`; fix compile errors in tests by switching to `/inbox/chats?phoneNumberIds=` and `/inbox/stream`.
- [ ] **Step 2:** `InboxDemoSeeder`: after creating team "Demo Support", `number.setTeamId(team.getId())` and save; `InboxDemoSeederIT.numberIsOwnedByDemoSupport`.
- [ ] **Step 3:** `./mvnw -q verify` → green. Build the jar `./mvnw -q -DskipTests package` to check the frontend bundle still serves (console plan handles the UI).
- [ ] **Step 4: Commit** `Remove per-number inbox endpoints and seed the demo number's team`.

---

## Contract for the console (JSON truth for `2026-10-23-chat-assignment-console.md`)

Base `B = /api/workspaces/{ws}`.

| Endpoint | Auth | Request | Response |
|---|---|---|---|
| `PUT B/phone-numbers/{id}/team` | `phone-number:manage` | `{teamId: string\|null}` | `PhoneNumberView` (+`team: {id,name}\|null`) |
| `GET B/teams` | `user:view` | — | `[{id, name, members:[…], phoneNumbers:[{id, number, label}]}]` |
| `GET /api/users/me` | any | — | `CurrentUser` + `availability: {status:"AVAILABLE"\|"AWAY", at}` |
| `PUT /api/users/me/availability` | any | `{status}` | `CurrentUser` |
| `GET B/inbox/phone-numbers` | `inbox:view` | — | `PhoneNumberView[]` + `team`, `guest: boolean` (visible numbers only) |
| `GET B/inbox/chats` | `inbox:view` | `?phoneNumberIds=a,b&scope=MINE\|UNASSIGNED\|TEAM\|ALL&status=OPEN\|CLOSED\|ALL&q=&unread=&before=&limit=` | `{items: ChatView[], hasMore}`; `ChatView` + `lastAgent: {id,name}\|null` |
| `GET B/inbox/stream?phoneNumberIds=` | `inbox:view` | SSE | events `chat {chat, visible}`, `message {chatId, message}`, `action {chatId, action}`; `action` + `reason`, `fromTeam` |
| `POST B/inbox/chats/{id}/claim` | `inbox:send` | — | `ChatView`; 409 `CHAT_TAKEN` |
| `POST B/inbox/chats/{id}/handoff` | `inbox:send` | `{kind:"TO_USER", userId}` \| `{kind:"TO_TEAM", teamId?}` | `ChatView`; 400 `VALIDATION` with `fields.userId`/`fields.teamId`; 404 when not the assignee (non-manager) |
| `GET B/inbox/assignees?phoneNumberId=` | `inbox:view-queue` or `inbox:assign` | — | `{users:[{id,name,username,availability}], teams:[{id,name}]}` |
| `POST B/inbox/chats/{id}/assign` | `inbox:assign` | `{userId?, teamId?}` | `ChatView`; 422 `ASSIGNEE_CANNOT_SEE_NUMBER` |
| `GET\|PUT B/inbox/settings` | `inbox:settings` | `RoutingSetting` | `RoutingSetting` (`teamId:null, inherited:false`) |
| `GET B/inbox/settings/teams` | `inbox:settings` | — | `RoutingSetting[]` one per team (`inherited` true when no override) |
| `GET\|PUT\|DELETE B/inbox/settings/teams/{teamId}` | `inbox:settings` | `RoutingSetting` | `RoutingSetting` / 204 |

`RoutingSetting = {teamId: string|null, inherited: boolean, assignToTeam: boolean, roundRobin: boolean, userId: string|null, stickyDays: number, claimOnReply: boolean, keepAssignmentOnClose: boolean}`.
`ActionType` adds `TRANSFERRED`; `ActionView` adds `reason: "STICKY"|"HANDED_BACK"|"OWNER_CHANGED"|"MEMBER_REMOVED"|null`, `fromTeam: {id,name}|null`.
Removed: `GET B/inbox/phone-numbers/{id}/chats`, `GET B/inbox/phone-numbers/{id}/stream`, `GET|PUT B/inbox/phone-numbers/{id}/settings`. Role `TEAM_LEAD` no longer exists; authority `inbox:view-queue` is new (AGENT default).
