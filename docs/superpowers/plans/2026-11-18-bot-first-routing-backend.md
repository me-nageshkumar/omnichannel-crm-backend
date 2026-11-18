# Chatbot-first routing: backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task
> (the user's choice: inline, on Opus). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new incoming chat goes to a chatbot before any person; chats a chatbot holds get the status `BOT` and
their own inbox list; a chatbot node hands the chat to people.

**Architecture:** Ingestion stops routing and marks the chat (`routingPendingSince`); the automation worker settles
it after the message's rules ran (`RoutingGate`). A chatbot run that starts on an open, unheld chat takes it
(`BOT`); when the run ends the chat is closed or routed (`BotChats`). Every state change is a guarded
single-document update, so races resolve by "first writer wins" without transactions.

**Tech stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB standalone, embedded Mongo + `MutableClock` in tests.

**Spec:** `docs/superpowers/specs/2026-11-18-bot-first-routing-design.md` (read it first; section numbers below
refer to it). Console plan: `docs/superpowers/plans/2026-11-18-bot-first-routing-console.md`.

## Global constraints

- Read `docs/superpowers/project-rules.md` §1 and §2 before any git or build command. Work only in this worktree
  (`.worktrees/bot-first-routing`, branch `bot-first-routing`). No merge, push or deploy: the user asks for those.
- One Maven build at a time. While working run only the named tests:
  `./mvnw -q -o -pl omnichannel-app -am test -Dtest='<Names>' -Dsurefire.failIfNoSpecifiedTests=false`.
  The full `./mvnw -q -o verify` runs once, in Task 9. After any run `pgrep -f '/.embedmongo/' | wc -l` must be 0.
- MongoDB has no transactions here: guarded `updateFirst` / `findAndModify` only; every chat update made outside a
  repository `save` also does `.inc("version", 1)` (as `ChatPointer` does), so a stale full save fails.
- Collections camelCase. "Template" means Meta templates only. Never send a real message.
- Model split: you are Opus. Dispatch the final whole-branch reviewer with `model: "fable"`. Stop and say
  "Switch to Fable: `/model fable`" when (1) the way forward is a design decision or spec and plan conflict on what
  users get, (2) review findings need a design change, (3) the user asks for a spec, plan or design check, (4) the
  same failure survived two real debugging attempts. Ordinary plan gaps are rulings in the ledger.
- Commit trailer: as the session's reminder says (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` on Opus).

Paths below are relative to the module's `src/main/java/me/nageshk/omnichannel/` (tests:
`omnichannel-app/src/test/java/me/nageshk/omnichannel/app/`). New ITs extend `ChatbotTestBase` (which extends
`AutomationTestBase`: `brand`, `n1`, `support`, `manager`, `agent`, `agent2`, `incoming(n1, remote, "chat", body, name)`,
`rule(...)`, `createChatbot(name, flow)`, `node/edge/flow/textMessage`, `await(...)`, `clock`); see
`chatbot/StartChatbotActionIT` for publishing a chatbot and building a `START_CHATBOT` action, and
`automation/AutomationWorkerIT` for ticking the worker by hand (`worker.tick()`).

## Review focus

Inputs the spec implies and a person would hit; each has its test in the task named.

1. A customer's second message lands while the worker is settling the first (two `settle` calls, or `settle` racing
   a chatbot's take): the chat is routed at most once and never ends `BOT` with an agent. (Task 5)
2. A chatbot is deleted, or its plan stops allowing chatbots, while it holds a chat: the chat reaches the team, not
   limbo. (Task 4, Task 8)
3. A number with no owning team, or team routing `NONE`: a released chat is `OPEN` and unassigned, not lost and not
   an error. (Task 4)
4. A chat that already has an agent (kept assignment on close, sticky reopen) when a chatbot starts: it stays `OPEN`
   with its agent. (Task 4)
5. A flow published before this change with an edge after the assign node: it still publishes and runs, ending at
   the node. (Task 6)

---

### Task 1: Model, settings fields and the permission

**Files:**
- Modify: `omnichannel-db` `db/inbox/ChatStatus.java`, `db/inbox/WaWebChat.java`, `db/inbox/RoutedReason.java`,
  `db/automation/AssignMode.java`, `db/chatbot/ChatbotSettings.java`, `db/chatbot/ChatbotSettingsLayer.java`,
  `db/config/ChatbotSettingsMigration.java` (only if it builds a full settings map: add the two defaults)
- Create: `omnichannel-db` `db/chatbot/AfterChatbot.java`
- Modify: `omnichannel-security` `security/access/Authority.java`
- Modify: `omnichannel-service` `service/chatbot/settings/SettingsLayers.java` (+ `ChatbotSettingsViews` /
  `ChatbotSettingsService` wherever the nine fields are listed), `service/automation/RuleShapeValidator.java`
- Test: `AuthorityTest` (security module), `access/RoleSeederIT`, `chatbot/ChatbotSettingsApiIT`,
  `automation/AutomationRulesApiIT`, `inbox/InboxIndexesIT`

**Interfaces — produces:**
- `ChatStatus { OPEN, BOT, CLOSED }`
- `WaWebChat`: `Instant routingPendingSince` with `@Indexed(sparse = true)`; `Instant handedOverAt`
- `RoutedReason`: `+ CHATBOT_HANDOVER, CHATBOT_ENDED`
- `AssignMode`: `+ NUMBER_ROUTING`
- `enum AfterChatbot { CLOSE, ROUTE }`
- `ChatbotSettings(..., boolean stopOnAgentReply, AfterChatbot whenCompleted, AfterChatbot whenAbandoned)`;
  `codeDefaults()` gives `CLOSE`, `ROUTE`. `ChatbotSettingsLayer` gets the same two as nullable, last.
  Field names on the wire: `whenCompleted`, `whenAbandoned`.
- `Authority.INBOX_VIEW_BOT("inbox:view-bot", RoleLevel.BRAND, "See the chats a chatbot is handling",
  CUSTOMER_ADMIN, WORKSPACE_MANAGER)`, declared after `INBOX_VIEW_ALL`.

- [ ] **Step 1: failing tests.** `AuthorityTest`: pin 59 → 60. `RoleSeederIT`: both 59 → 60; add
  `managerAndAdminGetViewBotAgentDoesNot` (seeded WORKSPACE_MANAGER and CUSTOMER_ADMIN contain `inbox:view-bot`,
  AGENT does not). `ChatbotSettingsApiIT`: `newFieldsDefaultToCloseAndRoute` (effective settings of a fresh
  chatbot: `whenCompleted == "CLOSE"`, `whenAbandoned == "ROUTE"`), `brandLayerOverridesWhenAbandoned` (brand layer
  `whenAbandoned: "CLOSE"` → chatbot resolves `CLOSE`; chatbot layer `ROUTE` wins over it),
  `storedLayerWithoutNewFieldsInherits` (insert a layer document lacking both keys; resolve succeeds with defaults).
  `AutomationRulesApiIT`: `assignActionRefusesNumberRouting` (a rule with action `ASSIGN_CHAT`,
  `mode: NUMBER_ROUTING` → 400 on save) and `existingAssignActionStillSaves` (mode `TEAM_QUEUE` with a team → 2xx).
  `InboxIndexesIT`: the sparse index on `routingPendingSince` exists.
- [ ] **Step 2: run them, expect FAIL** (compile errors count).
- [ ] **Step 3: implement** the model above. `RuleShapeValidator`: `NUMBER_ROUTING` on an `ASSIGN_CHAT` action is a
  field error on `mode` ("not available for a rule"). Fix every `switch` over `AssignMode` / `ChatStatus` that no
  longer compiles by keeping today's behaviour for the old values (`AssignChatHandler`: `NUMBER_ROUTING` →
  `ActionResult.failed("No assignment mode")`; the node gets its behaviour in Task 6).
- [ ] **Step 4: run them, expect PASS.** Also run `ChatbotSettings*IT,RoleSeederIT,AutomationRulesApiIT`.
- [ ] **Step 5: commit** `feat(inbox): BOT status, hand-over fields, after-chatbot settings, inbox:view-bot`.

### Task 2: Who sees `BOT` chats

**Files:**
- Modify: `omnichannel-service` `service/inbox/InboxScope.java`, `InboxScopeService.java`, `InboxQueryService.java`
- Test: `inbox/InboxScopeServiceIT`, `inbox/InboxApiIT`, `inbox/InboxStreamIT`

**Interfaces — produces:**
- `record InboxScope(Level level, String userId, Set<String> teamIds, Set<String> ownedNumberIds, boolean bots)`
- `ListScope { MINE, TEAM, UNASSIGNED, ALL, BOT }`; `allows(BOT) == bots`
- `covers(chat)`: status `BOT` → `bots && ownedNumberIds.contains(phoneNumberId)`; otherwise as today.
- `criteria()`: `(today's criteria AND status != BOT) OR (bots AND status == BOT AND phoneNumberId in owned)`.
- `criteriaFor(BOT)`: `status == BOT AND phoneNumberId in owned`; the other four add `status != BOT`.
- `InboxQueryService.chats`: for `ListScope.BOT` the `status` argument is ignored. `GET .../chats?scope=BOT`.

- [ ] **Step 1: failing tests.** `InboxScopeServiceIT`: `agentWithoutViewBotDoesNotCoverABotChatOnOwnNumber`,
  `managerCoversBotChatsOnlyOnOwnedNumbers` (use a TEAM-level user holding `inbox:view-bot` through a test role,
  two numbers, one owned), `visibleNumbersIgnoreBotChatsForAgents`. `InboxApiIT`: `botListNeedsThePermission`
  (agent → 403), `botListReturnsOnlyBotChats` (manager, one OPEN + one BOT + one CLOSED chat on `n1`; `scope=BOT`
  returns the BOT one whatever `status` is sent), `otherListsNeverReturnBotChats` (`scope=ALL&status=ALL` omits
  it), `agentGets404OpeningABotChat`. `InboxStreamIT`: `chatTurningBotLeavesTheAgentsStream` (agent subscribed;
  set the chat `BOT` and publish `ChatChanged`; the agent receives the removal the broker already sends when a
  chat stops being visible; the manager receives the chat with `status: "BOT"`).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.** `InboxScopeService.scopeOf` sets `bots` from `Authority.INBOX_VIEW_BOT`. Fix other
  constructors of `InboxScope` (tests included).
- [ ] **Step 4: run, expect PASS**; also `InboxApiIT,InboxStreamIT,InboxScopeServiceIT,NewChatIT`.
- [ ] **Step 5: commit** `feat(inbox): the Chatbot list and who may see BOT chats`.

### Task 3: `BotChats` and routing with a by-name

**Files:**
- Create: `omnichannel-service` `service/inbox/BotChats.java`
- Modify: `service/inbox/ChatRoutingService.java`
- Test: create `inbox/BotChatsIT.java`

**Interfaces — produces:**
- `ChatRoutingService.RoutingOptions(String excludeUserId, boolean sticky, RoutedReason reason, String byName,
  String reasonDetail)`; `DEFAULT` keeps nulls. New
  `WaWebChat route(WaWebChat chat, RoutingOptions options)` (owning team looked up; no team → chat returned
  untouched). A sticky match still records `STICKY`, now with the options' `byName` and `reasonDetail`.
- `BotChats` (`@Service`):
  - `boolean take(String chatId)`: guarded update `{_id, status: OPEN, assignedUserId: null}` → `status = BOT`,
    unset `assignedTeamId`, `routingPendingSince`, `handedOverAt`. Publishes `ChatChanged` when it matched.
  - `Optional<WaWebChat> open(String chatId)`: guarded `{_id, status: BOT}` → `status = OPEN`,
    `handedOverAt = now`. Returns the fresh chat when it matched. No event (the caller goes on to change the chat).
  - `void toPeople(String chatId, String runId, RoutedReason reason, String chatbotName)`: guarded
    `{_id, status: BOT, $or: [{chatbot: null}, {"chatbot.runId": runId}]}` (pass `runId == null` to skip the run
    condition) → `OPEN`, `handedOverAt = now`; then `routing.route(chat, new RoutingOptions(null, true, reason,
    "Chatbot: " + chatbotName, chatbotName))`; publishes `ChatChanged`. A routing failure is logged; the chat stays
    open and unassigned.
  - `void close(String chatId, String runId, String chatbotName)`: same guard → `status = CLOSED`; saves a
    `CLOSED` `WaWebChatAction` with `byName "Chatbot: " + chatbotName`; publishes `ActionRecorded` and `ChatChanged`.
  - `void markHandedOver(String chatId)`: sets `handedOverAt = now` (for a chat that was not `BOT`).

- [ ] **Step 1: failing tests** in `BotChatsIT`: `takeTurnsAnOpenUnheldChatBotAndClearsItsTeam`;
  `takeLeavesAChatWithAnAgent` and `takeLeavesAClosedChat` (both return false, chat unchanged);
  `toPeopleRoutesByTheTeamsSetting` (support in round robin: chat `OPEN`, assigned, `handedOverAt` set, one
  `ROUTED` action with reason `CHATBOT_ENDED`, `byName "Chatbot: Welcome"`, `reasonDetail "Welcome"`);
  `toPeopleReturnsAStickyCustomerToTheirAgent` (reason `STICKY`, same `byName`);
  `toPeopleWithoutAnOwningTeamLeavesItOpenAndUnassigned` and `...WithRoutingNone...` (Review focus 3: no exception,
  `OPEN`, no `ROUTED` action); `toPeopleDoesNothingWhenAnotherRunHoldsTheChat` (pointer names a different run);
  `closeClosesWithAChatbotAction`; `takeBumpsTheVersionSoAStaleSaveFails` (load a copy, `take`, `chats.save(copy)`
  throws `OptimisticLockingFailureException`).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement** `BotChats` (constructor: `MongoTemplate`, `WaWebChatRepository`,
  `WaWebChatActionRepository`, `ChatRoutingService`, `ApplicationEventPublisher`, `Clock`) and the routing change.
  Update the three existing `new RoutingOptions(...)` call sites in `OwnershipRoutingListener` and
  `ChatAssignmentService` with two nulls.
- [ ] **Step 4: run, expect PASS**; also `ChatRoutingIT,OwnershipRoutingIT,ChatAssignmentIT`.
- [ ] **Step 5: commit** `feat(inbox): BotChats take/open/toPeople/close; routing records who asked`.

### Task 4: A run takes the chat and gives it back

**Files:**
- Modify: `service/chatbot/run/ChatbotRunService.java`, `ChatbotRunner.java`,
  `service/automation/StartChatbotHandler.java`, `service/inbox/ChatAssignmentService.java` (clear `handedOverAt`
  on close), and any other place that sets `ChatStatus.CLOSED` (`InboxIngestService` ~line 254: clear it there too)
- Test: create `chatbot/BotReleaseIT.java`; extend `chatbot/StartChatbotActionIT`

**Interfaces — consumes:** `BotChats` (Task 3), `ChatbotSettings.whenCompleted/whenAbandoned` (Task 1).
**Produces:**
- `ChatbotRunService.start(...)`: after `pointer.set`, `botChats.take(chatId)` when the run has a chat.
- `ChatbotRunner.ended(ChatbotRun saved)` releases, then clears the pointer; new
  `ChatbotRunner.ended(ChatbotRun saved, boolean replaced)` skips the release when `replaced`.
- `ChatbotRunService.stopForReplacement(ChatbotRun run, String reason, String byUserId)`: as `stop`, ending with
  `ended(run, true)`. Used by the `IfRunning.REPLACE` path in `start` and by both stops in
  `StartChatbotHandler.startForTest`.
- Release rule (private in the runner): by final status —

  | Status | Call |
  |---|---|
  | `COMPLETED` | `whenCompleted == CLOSE` → `botChats.close(chatId, runId, name)` else `toPeople(..., CHATBOT_ENDED, name)` |
  | `ENDED` | `whenAbandoned == CLOSE` → `close` else `toPeople` |
  | `FAILED`, `STOPPED` (not replaced) | `toPeople(..., CHATBOT_ENDED, name)` |
  | `HANDED_OVER`, `JUMPED`, replaced | nothing |

  Settings come from `settingsService.resolve(chatbot.getWorkspaceId(), chatbot.getSettings())`; a chatbot that no
  longer exists → `toPeople`. A release that throws is logged and must not stop the pointer from being cleared.
- `ChatbotRunner.jump`: when the next run did not start (`!next.started()`), and on each
  `finish(ended, FAILED, ...)` after the `JUMPED` finish, the chat is released by the `FAILED` rule (the guard
  `chatbot == null or chatbot.runId == runId` lets it through after the pointer was cleared).
- `StartChatbotHandler.run`: a live (not `ctx.test()`) start on a chat whose `handedOverAt != null` returns
  `ActionResult.skipped("The chat was handed over to the team")`, before the run is created.

- [ ] **Step 1: failing tests** in `BotReleaseIT` (start runs with `runService.start(...)` on a chat made by
  `incoming(...)`; a flow that waits is one `ask_question` node; one that completes is one `send_message` node with
  the test device connected as other chatbot ITs do):
  `startOnAnOpenUnheldChatMakesItBot`; `startOnAChatWithAnAgentLeavesItOpen` (Review focus 4);
  `startOnAClosedChatLeavesItClosed`; `completedClosesByDefault` (status `CLOSED`, a `CLOSED` action by
  `"Chatbot: <name>"`, no `ROUTED` action); `completedRoutesWhenTheChatbotSaysSo` (chatbot layer
  `whenCompleted: ROUTE`); `abandonedGoesToTheTeamByDefault` (advance `clock` past `endAfterMinutes`, run the
  ticker; chat `OPEN`, routed with `CHATBOT_ENDED`); `abandonedClosesWhenTheBrandSaysSo` (brand layer
  `whenAbandoned: CLOSE`); `failedGoesToTheTeam`; `stoppedByAPersonGoesToTheTeam`;
  `replaceKeepsTheChatWithTheNewRun` (start A, start B with `IfRunning.REPLACE`: status `BOT` throughout, zero
  `ROUTED` actions, pointer on B); `jumpKeepsTheChatWithTheNextRun`; `jumpToAnUnpublishedChatbotGoesToTheTeam`;
  `deletedChatbotStillReleasesToTheTeam` (delete the chatbot document, then stop the run; Review focus 2);
  `closingClearsTheHandOverMark`. In `StartChatbotActionIT`: `skipsAChatThatWasHandedOver` (set `handedOverAt`;
  the handler returns `SKIPPED` with the reason above and creates no run) and `startsAgainAfterTheChatWasClosed`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; then the whole chatbot package (`-Dtest='Chatbot*IT,StartChatbotActionIT,
  BotReleaseIT,NodeValidationIT'`). Existing tests that asserted a chat's status or assignment around a run are
  updated to the new behaviour only where the spec says the behaviour changed; ledger each as a ruling.
- [ ] **Step 5: commit** `feat(chatbot): a run takes an unheld chat and releases it when it ends`.

### Task 5: Routing waits for the rules

**Files:**
- Create: `service/inbox/RoutingGate.java`, `service/inbox/ChatbotStarts.java` (port),
  `service/automation/RuleChatbotStarts.java` (its implementation)
- Modify: `service/inbox/ChatRoutingListener.java`, `service/automation/AutomationWorker.java`,
  `AutomationMessageListener.java`, `AutomationEventWriter.java`,
  `omnichannel-common` `common/config/OmnichannelProperties.java`, `omnichannel-app` `application.yml`
- Test: create `inbox/BotFirstRoutingIT.java`

**Interfaces — consumes:** `BotChats.take`, `ChatRoutingService.route`. **Produces:**
- `interface ChatbotStarts { boolean mayStartFromMessage(String workspaceId); }` — true when the workspace has a
  `PUBLISHED` rule with `event == MESSAGE_RECEIVED` and an action of type `START_CHATBOT`, and
  `workGate.allows(workspaceId, WorkGate.CHATBOTS)`.
- `RoutingGate` (`@Service`):
  - `void arrive(WaWebChat chat)`: (1) chat has a `chatbot` pointer and no assigned user → `botChats.take`; return.
    (2) `starts.mayStartFromMessage` → guarded update sets `routingPendingSince = now`. (3) else
    `routing.route(chat)` exactly as the listener does today.
  - `boolean settle(String chatId)`: `findAndModify` `{_id, routingPendingSince: {$ne: null}}` unset it, return new.
    If it matched and the chat is `OPEN` with no user and no team: `routing.route(chat)` then publish
    `ChatChanged`; returns true when it routed. An `OptimisticLockingFailureException` from the route's save means
    something else just changed the chat (a take, a message): reload once and retry the same check; never throw.
  - `int settleOverdue(Instant now)`: settles every chat with `routingPendingSince < now - routingWait` (Task 8
    calls it).
- `OmnichannelProperties`: `omnichannel.inbox.routing-wait`, default `30s` (follow the existing record pattern;
  add an `Inbox` record if there is none).
- `AutomationEventWriter.messageReceived` returns `boolean` (true when an event row was written or already existed
  in the queue; false when the gate did not want it or the history already has it).
- `ChatRoutingListener`: its three handlers call `gate.arrive(chat)` where they call `route(chat)` today; the
  conditions (`firstDirection == IN`, unassigned) stay.
- `AutomationWorker`: for a `MESSAGE_RECEIVED` event with `data.chat().id()`, call `gate.settle(chatId)` after
  `finish(...)` (done and failed) and in `retryLater(...)`. A `settle` failure is logged, never thrown.
- `AutomationMessageListener`: for an inserted inbound message, when the type is not a real one, the source is not
  `CONTACT`, or `messageReceived` returned false → `gate.settle(chat.getId())`.

- [ ] **Step 1: failing tests** in `BotFirstRoutingIT` (support routes round robin; `worker` ticked by hand):
  `withoutAChatbotRuleANewChatIsRoutedAtOnce` (assigned before any tick; `routingPendingSince` null);
  `withAChatbotRuleTheChatWaitsThenTheChatbotTakesIt` (after `incoming`: unassigned, pending set; after
  `worker.tick()`: status `BOT`, no `ROUTED` action, pending null, `teams.findById(support).roundRobinCursor`
  unchanged); `aRuleThatDoesNotMatchRoutesAfterTheTick` (rule condition on another keyword);
  `anEventKeptForARetryStillRoutesTheChat` (first rule's send step answers RETRYING: after the tick the chat is
  routed and the event is still queued); `aMessageThatQueuesNoEventIsRoutedAtOnce` (first message is a type outside
  `MessageKinds.REAL_TYPES`); `aReopenedChatWaitsToo` (close, new inbound: pending, then `BOT`);
  `aChatbotThatWroteFirstTakesTheChatWhenTheCustomerAnswers` (run started on a `CLOSED` chat; inbound reopens:
  status `BOT` without a tick, no `ROUTED` action); `anAssignChatRuleActionStillAssigns` (rule with `ASSIGN_CHAT`
  to `agent2` plus a non-matching chatbot rule: after the tick assigned to `agent2`, exactly one `ROUTED` action);
  `settleTwiceRoutesOnce` (two threads call `settle`; one `ROUTED` action);
  `settleRacingATakeNeverLeavesABotChatWithAnAgent` (Review focus 1: run `settle` and `botChats.take` on two
  threads 50 times over fresh chats; each ends either `BOT` and unassigned, or `OPEN` and assigned);
  `aPlanWithoutChatbotsRoutesAtOnce`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; then `-Dtest='ChatRouting*IT,CampaignChatIT,AutomationWorkerIT,MessageEventIT,
  StartChatbotActionIT,AssignChatActionIT,InboxApiIT'`.
- [ ] **Step 5: commit** `feat(inbox): routing waits for the message's rules; a chatbot takes the chat first`.

### Task 6: The hand-over node

**Files:**
- Modify: `service/chatbot/node/StepResult.java`, `AssignChatExecutor.java`,
  `service/chatbot/run/ChatbotRunner.java`, `service/chatbot/FlowValidator.java`,
  `service/automation/AssignTargets.java`
- Test: `chatbot/ChatbotActionsNodesIT`, `chatbot/NodeValidationIT`

**Interfaces — consumes:** `BotChats.open`, `markHandedOver`, `RoutingOptions`. **Produces:**
- `StepResult.Kind.HAND_OVER`; `StepResult.handOver(Map<String, Object> result, String summary)`. Runner:
  `completeStep`, store `result` under the node's name, `finish(run, HANDED_OVER, summary)`.
- `AssignChatExecutor.outputs(...)` → `List.of()`. `run`: `botChats.open(chatId)` (else `markHandedOver`), reload
  the chat, then:
  - `NUMBER_ROUTING`, chat has an assigned user → summary `"Stays with <user name>"`;
  - `NUMBER_ROUTING`, no user → `routing.route(chat, new RoutingOptions(null, true, CHATBOT_HANDOVER,
    "Chatbot: " + name, name))` and publish `ChatChanged`; summary `"Assigned to <name>"`, `"Waiting in <team>'s
    queue"`, or `"Open, waiting for the team"` when nothing was assigned;
  - other modes: today's code and summaries.
  Every branch returns `StepResult.handOver(assigned(...), summary)`.
- `AssignTargets.check(workspaceId, NUMBER_ROUTING, null, null)` → no problems.
- `FlowValidator`: an edge whose source is an `ASSIGN_CHAT` node is a WARNING on that edge, "Nothing runs after a
  hand-over", instead of the "leaves an output the node does not have" ERROR. The published-version publish check
  passes with it.

- [ ] **Step 1: failing tests.** `ChatbotActionsNodesIT`: `handOverByNumberRoutingEndsTheRunAndRoutes` (run
  `HANDED_OVER`; chat `OPEN`, `handedOverAt` set, `ROUTED` action reason `CHATBOT_HANDOVER`);
  `handOverKeepsAnAgentWhoHoldsTheChat` ("Stays with"); `handOverToATeamQueue`, `...ToAnAgent`, `...RoundRobin`
  (existing assign tests, now also asserting `HANDED_OVER` and `OPEN`); `handOverWithNoTeamLeavesItOpen`
  ("Open, waiting for the team"); `nothingRunsAfterAHandOver` (flow: hand-over → send_message; the send never
  happens, the run ends at the node; Review focus 5). `NodeValidationIT`: `edgeAfterAHandOverIsAWarning` and
  `aFlowWithThatWarningStillPublishes`; `numberRoutingNeedsNoTarget`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `Chatbot*IT`.
- [ ] **Step 5: commit** `feat(chatbot): the assign node hands over and ends the chatbot`.

### Task 7: A person acts on a `BOT` chat

**Files:**
- Modify: `service/inbox/ChatAssignmentService.java`, `InboxSendService.java`, `NewChatService.java`,
  `omnichannel-app` `app/inbox/InboxController.java`
- Test: create `inbox/BotTakeOverIT.java`

**Interfaces — produces:**
- `ChatAssignmentService.takeOver(AuthenticatedUser me, WaWebChat chat)`: for a `BOT` chat: `status = OPEN`,
  `handedOverAt = now`, assigned to `me`, `lastAgent`, one `ASSIGNED` action, `commit`. Any other chat is returned
  untouched.
- `assign` and `handoff` on a `BOT` chat also set `status = OPEN` and `handedOverAt = now`. `claim` on a `BOT` chat
  behaves as `takeOver`. `reopen(...)` (both overloads) acts only on `CLOSED`. `unassign` on a `BOT` chat does
  nothing.
- `InboxSendService.send` / `sendCloud`: a `UserActor` sending into a `BOT` chat first calls `takeOver`; the
  closed check becomes `status == CLOSED` for actors other than automation and chatbot (so a campaign may send
  into a `BOT` chat). The existing `ChatbotHandoverListener` then ends the run.
- `NewChatService.claimed`: a `BOT` chat → `takeOver`.
- `InboxController` close endpoint: when the chat was `BOT`, after closing, stop its active run
  (`chatbotRuns.stop(run, "the chat was closed by " + username, userId)`); the release finds the chat `CLOSED`
  and does nothing.

- [ ] **Step 1: failing tests** in `BotTakeOverIT` (manager holds `inbox:view-bot`; HTTP through `api(...)` as the
  other inbox ITs do): `aReplyTakesTheChatOverAndEndsTheChatbot` (200; chat `OPEN`, assigned to the manager,
  `handedOverAt` set; run `HANDED_OVER`; no `ROUTED` action);
  `aReplyTakesItEvenWhenClaimOnReplyIsOff`; `withStopOnAgentReplyOffTheRunGoesOn` (chat `OPEN` and assigned, run
  still `WAITING`); `anAgentWithoutThePermissionCannotReply` (404); `assigningToATeamOpensIt` (`OPEN`, team set,
  run still active); `closingStopsTheChatbot` (chat `CLOSED`, run `STOPPED`, no `ROUTED` action);
  `stoppingTheChatbotSendsTheChatToTheTeam` (existing stop endpoint → `OPEN`, routed `CHATBOT_ENDED`);
  `claimTakesItOver`; `aCampaignMaySendIntoABotChat`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `InboxSendIT,ChatAssignmentIT,NewChatIT,ChatbotHandoverIT,CampaignChatIT`.
- [ ] **Step 5: commit** `feat(inbox): replying, assigning and closing a chat a chatbot holds`.

### Task 8: The sweep

**Files:**
- Modify: `service/chatbot/run/ChatbotTicker.java`, `service/inbox/BotChats.java`
- Test: create `inbox/BotSweepIT.java`

**Interfaces — consumes:** `RoutingGate.settleOverdue(Instant)`. **Produces:**
- `int BotChats.releaseOrphans(Instant now, Duration wait)`: for each chat with `status == BOT`: no `ChatbotRun`
  of that chat is `RUNNING` or `WAITING`, and its newest run's `finishedAt` (or, with no run at all, the chat's
  `lastMessageAt`) is older than `now - wait` → `toPeople(chatId, null, CHATBOT_ENDED, <chatbot name of the newest
  run, or "chatbot">)`.
- `ChatbotTicker` tick: `gate.settleOverdue(now)` and `botChats.releaseOrphans(now, routingWait)`, each in its own
  try/catch, before the existing work.

- [ ] **Step 1: failing tests:** `aChatLeftWaitingIsRoutedAfterTheWait` (chatbot rule present, worker never
  ticked: at +29 s still pending, at +31 s routed after the ticker runs); `aBotChatWithoutARunGoesToTheTeam`
  (set a chat `BOT` with a pointer to a run that is `COMPLETED` 31 s ago; Review focus 2);
  `aBotChatWhoseRunJustEndedIsLeftAlone` (finished 5 s ago: untouched, the jump gap);
  `aBotChatWithAnActiveRunIsLeftAlone`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `ChatbotTimersIT`.
- [ ] **Step 5: commit** `feat(inbox): sweep for chats left waiting and BOT chats without a run`.

### Task 9: Remaining readers, the record of the decision, full run

**Files:**
- Modify: every remaining reader of `ChatStatus` found by
  `grep -rn "ChatStatus\." --include='*.java' omnichannel-*/src/main` that Tasks 1–8 did not touch
  (`service/inbox/history/FetchedChatOpener.java`, `app/crm/ChatDirectoryAdapter.java`, `InboxViews`,
  `EventData.ChatData` users, `ConditionCatalog` if it lists status values): `BOT` is treated as "not closed"; a
  rule condition list that offers chat statuses gains "With chatbot".
- Modify: `docs/superpowers/project-rules.md`, `README.md`, `docs/superpowers/known-limits.md`
- Test: `inbox/InboxViewsIT` (`chatViewCarriesBotStatus`), `automation/AssignChatActionIT`
  (`anExistingAssignRuleStillPublishesAndRuns`: unchanged behaviour, added only if no existing test already proves
  publish + run)

- [ ] **Step 1: failing test** `chatViewCarriesBotStatus` (`ChatView.status == BOT` for a taken chat, with its
  `chatbot` view); run, expect FAIL or, if it passes already, note it in the ledger and keep it.
- [ ] **Step 2: implement** the readers.
- [ ] **Step 3: docs.** `project-rules.md` §1, a new bullet, verbatim: "Assign chat is hidden from the rule actions
  since 2026-11-18: chats reach people through the number's routing or a chatbot's hand-over node. The code
  (`ASSIGN_CHAT`, `AssignChatHandler`) stays and existing rules keep working; do not delete it without asking."
  `README.md`: a section "Chatbot first" under the inbox part: the order (rules, then routing; 30 s fallback), the
  `BOT` status and the `inbox:view-bot` permission, the release table of spec §5.3, the hand-over node, the two
  settings. `known-limits.md`: the three risks of spec §9 plus "a chat an agent holds with a chatbot running is
  not in the Chatbot list".
- [ ] **Step 4: full run, alone:** `./mvnw -q -o verify > <workspace>/verify.log 2>&1; echo exit=$?; tail -40
  <workspace>/verify.log`. Expected: `exit=0`. Then `pgrep -f '/.embedmongo/' | wc -l` → `0`.
- [ ] **Step 5: commit** `docs: chatbot-first routing; Assign chat hidden from rule actions`.

## Contract for the console

- `ChatView.status`: `"OPEN" | "BOT" | "CLOSED"`.
- `GET /api/workspaces/{ws}/inbox/chats?scope=BOT` (403 without `inbox:view-bot`); `status` is ignored for it.
- `ROUTED` action `reason`: `+ "CHATBOT_HANDOVER" | "CHATBOT_ENDED"`, with `reasonDetail` = the chatbot's name.
  A `CLOSED` action may have `byName` `"Chatbot: <name>"` and no user.
- Chatbot settings layer and effective settings: `whenCompleted`, `whenAbandoned`: `"CLOSE" | "ROUTE"` (null in a
  layer inherits).
- Node `assign_chat`: settings `mode` `+ "NUMBER_ROUTING"` (no `userId`/`teamId`/`whenAway`); outputs `[]`.
- Authority code `inbox:view-bot`.
- Replying, claiming or assigning on a `BOT` chat answers as on an open chat; the returned chat is `OPEN`.
