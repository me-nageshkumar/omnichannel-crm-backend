# Leads follow the chat + chatbot report — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A chat given to an agent fills the empty owner of that customer's CRM lead; every chatbot run records where its chat went and how far an AI conversation got; two report endpoints read it.

**Architecture:** A listener on `InboxEvents.ActionRecorded` calls a new `AutomationCrm.fillOwner` port, implemented in `omnichannel-app` over a new CRM service. `ChatbotRun` gains `ending`, `ChatbotStep` gains `progress`; the AI turn runner gains a `note_progress` tool whose calls are answered at the start of the next turn. A `ChatbotReportService` aggregates `chatbotRuns` on request.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB (MongoTemplate aggregations), JUnit 5 + the app's IT bases (`CrmTestBase`, `ChatbotTestBase`, `AiTestBase`).

**Spec:** `docs/superpowers/specs/2026-11-20-lead-owner-and-chatbot-report-design.md` (approved 2026-11-20). The console plan `2026-11-20-lead-owner-report-console.md` consumes the API contract in Task 7 and Task 8 and the `ending` / `progress` fields of Tasks 3–4.

## Global Constraints

- Java 21, Spring Boot 4.1, Jackson 3, MongoDB; collections camelCase; no new collection in this plan.
- No "template" naming for anything here.
- `omnichannel-service` reaches the CRM only through the `AutomationCrm` port; the adapter lives in `omnichannel-app` (`AutomationCrmAdapter`).
- Only an **empty** owner is ever filled; a set owner is never replaced (spec §6.1).
- The contact is filled only when the agent holds `crm:view` in the brand; a deal only when the agent's `PipelineAccess` allows its pipeline (spec §6.1).
- Owner activity: `ActivityType.FIELD_CHANGED`, `ActorType.SYSTEM`, actor id `"system"`, summary `Owner set by inbox routing`, payload `{changes: [{field: "ownerId", from: null, to: <userId>}]}`, written with `ActivityLog.append` (does not raise `lastActivityAt`).
- `note_progress` is the tool name; its output text is `noted`.
- Report: test runs (`test = true`) excluded; range ≤ 92 days; default the last 30 days; authority `chatbot:view`.
- Work in `.worktrees/lead-owner-report`; tests first; while fixing run only the tests that matter; full `./mvnw -q verify` once before merge; merge/push/deploy only when the user asks.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A chat assigned to an agent **without** `crm:view` (or without the deal's pipeline) leaves the contact/deal unowned and does not throw into the assignment — pinned in Task 1 (`skipsWhatTheAgentCannotSee`).
2. Two assignments racing for the same unowned lead (claim on reply + routing) end with exactly one owner and one activity per record — pinned in Task 1 (`secondFillIsANoOp`).
3. A conversation that goes silent mid-way keeps its progress on the WAITING step after the ticker ends the run — pinned in Task 4 (`waitingStepKeepsProgressWhenRunEnds`).
4. A model reply with text **and** `note_progress` makes exactly one provider call, and the next turn's first input is the owed `function_call_output` — pinned in Task 5 (`progressWithTextCostsNoExtraRound`, `owedProgressOutputLeadsNextTurn`).
5. Report counts and the runs list behind each count agree (same filters, test runs out, running runs never in "ended here") — pinned in Task 8 (`runsListMatchesReportCounts`).

---

### Task 1: CRM fills an empty owner

**Files:**
- Create: `omnichannel-crm/src/main/java/me/nageshk/omnichannel/crm/contact/LeadOwnerFill.java`
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/automation/AutomationCrm.java`
- Modify: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/crm/AutomationCrmAdapter.java`
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/LeadOwnerFillIT.java` (extends `CrmTestBase`)

**Interfaces:**
- Produces: `AutomationCrm.FillResult fillOwner(String workspaceId, String customerId, String contactId, String phoneNumberId, String userId)`; `record FillResult(String crmContactId, List<String> dealIds)` nested in `AutomationCrm` (both empty/null when nothing changed). Never throws for "nothing to do" (CRM off, no CRM contact, user not in brand).
- CRM side: `LeadOwnerFill.fill(String workspaceId, String customerId, String contactId, String phoneNumberId, String userId) -> LeadOwnerFill.Filled(String crmContactId, List<String> dealIds)`.

- [ ] **Step 1: Write the failing tests** in `LeadOwnerFillIT`:

```java
@Test void fillsUnownedContactAndOpenDeals()      // contact + 2 open deals ownerId null → all = agentId; Filled lists them;
                                                  // one FIELD_CHANGED activity per record, actorType SYSTEM, summary "Owner set by inbox routing"
@Test void keepsAnOwnerSetByHand()                // contact ownerId = "u-other" → unchanged; its unowned open deal still filled
@Test void leavesWonLostAndArchivedDeals()        // WON, LOST and archived deals stay ownerId null
@Test void skipsWhatTheAgentCannotSee()           // agent without crm:view → nothing changes;
                                                  // agent with crm:view but pipeline access {p1} → deal in p2 stays null, deal in p1 filled
@Test void secondFillIsANoOp()                    // fill twice (same or another user) → owner = first user, activities not duplicated
@Test void crmOffOrNoContactDoesNothing()         // brand with CRM disabled / no CrmContact on that number → empty result, no exception
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest=LeadOwnerFillIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`fillOwner` / `LeadOwnerFill` missing).

- [ ] **Step 3: Implement `LeadOwnerFill`** (a `@Service` in the CRM module): CRM enabled check via `CrmSettingsService`; the agent's context via `CrmAccess.resolveFor(access.forSystem(ws, customerId), userId)` — empty or without `crm:view` → nothing. Contact: one `findAndModify` on `{workspaceId, contactId, channel.phoneNumberId, ownerId: null}` setting `ownerId`, `lastModifiedBy "system"`, `lastUpdateTime`, `$inc version`. Deals: `{workspaceId, primaryCrmContactId: <crm contact id>, status: OPEN, archived: false, ownerId: null}` (the contact found whether or not its own owner was empty), each deal updated by its own guarded `findAndModify` only when `PipelineAccess.allows(pipelineId)`. Activities as in Global Constraints (deals: `pipelineId` and `Placement.phoneOf(deal)`).

- [ ] **Step 4: Port and adapter.** Add `fillOwner` + `FillResult` to `AutomationCrm`; `AutomationCrmAdapter.fillOwner` delegates to `LeadOwnerFill` and maps the result. Any other `AutomationCrm` implementation in tests gets a no-op.

- [ ] **Step 5: Run the tests to verify they pass** (same command). Expected: PASS.

- [ ] **Step 6: Commit** — `feat(crm): fill an unowned lead's owner for an agent who can see it`

---

### Task 2: An assignment fills the lead's owner

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/automation/LeadOwnerListener.java`
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/LeadFollowsChatIT.java` (extends `ChatbotTestBase` or `CrmTestBase`, whichever already builds a brand with CRM, a number with an owning team and a chatbot)

**Interfaces:**
- Consumes: `AutomationCrm.fillOwner` (Task 1); `InboxEvents.ActionRecorded(WaWebChat chat, WaWebChatAction action)`.
- Produces: nothing other tasks call.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void handOverWithNumberRoutingGivesTheLeadToTheMember()
    // team routing USER(member A); chatbot: add_to_crm → assign_chat(NUMBER_ROUTING);
    // customer writes → run HANDED_OVER → CrmContact.ownerId == A and the deal's ownerId == A
@Test void roundRobinGivesTwoLeadsToTwoMembers()
    // routing ROUND_ROBIN over A,B (both available); two customers through the same chatbot → owners A and B
@Test void manualAssignAndClaimFillTheOwner()
    // lead added while BOT (no owner); ChatAssignmentService.assign(..., A) → owner A;
    // another chat: agent B replies with claimOnReply → owner B
@Test void closedByTheChatbotLeavesTheLeadUnowned()
    // whenCompleted CLOSE, flow add_to_crm → send_message (end) → CrmContact.ownerId null
@Test void unassignDoesNotClearTheOwner()
    // owner A filled, then UNASSIGNED → owner still A
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest=LeadFollowsChatIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — owners stay null.

- [ ] **Step 3: Implement `LeadOwnerListener`**: `@EventListener void on(InboxEvents.ActionRecorded e)`; acts only when `action.type ∈ {ROUTED, ASSIGNED, TRANSFERRED}`, `action.toUserId != null`, `chat.contactId != null`; calls `crm.fillOwner(chat.getWorkspaceId(), chat.getCustomerId(), chat.getContactId(), chat.getPhoneNumberId(), action.getToUserId())`; catches `RuntimeException` and logs at warn with chat id (never fails the assignment). Check every `ASSIGNED`/`ROUTED` publisher listed in spec §4 publishes `ActionRecorded` after its save (the claim-on-reply path in `ChatAssignmentService` included); if one does not, ledger a ruling and publish it there.

- [ ] **Step 4: Run to verify they pass** (same command). Expected: PASS.

- [ ] **Step 5: Commit** — `feat(inbox): a chat given to an agent fills its lead's empty owner`

---

### Task 3: A finished run records where its chat went

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/RunEnding.java`
- Modify: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/ChatbotRun.java` (field `ending`)
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/BotChats.java` (`toPeople`, `close` return the chat)
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/ChatbotRunner.java` (`ended`, `release`)
- Modify: the run view in `ChatbotViews` / `RunView` (expose `ending`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/RunEndingIT.java` (extends `ChatbotTestBase`)

**Interfaces:**
- Produces: `public record RunEnding(Chat chat, String agentUserId, String teamId, Instant at) { public enum Chat { HANDED_OVER, ROUTED, CLOSED, LEFT } }`; `ChatbotRun.getEnding()/setEnding(RunEnding)`; `RunView.ending` serialized as `{chat, agent: {id, name} | null, team: {id, name} | null, at}`.
- `BotChats.toPeople(...) -> Optional<WaWebChat>` (empty when the guard did not match), `BotChats.close(...) -> Optional<WaWebChat>`; existing callers adapt.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void handOverRecordsTheAgent()          // routing USER(A), hand-over NUMBER_ROUTING → ending {HANDED_OVER, A, team}
@Test void handOverToAQueueRecordsNoAgent()   // hand-over TEAM_QUEUE → ending {HANDED_OVER, null, team}
@Test void completedAndClosedRecordsClosed()  // whenCompleted CLOSE → ending.chat CLOSED
@Test void silentAndRoutedRecordsRouted()     // customer silent past endAfter, whenAbandoned ROUTE, routing USER(A) → {ROUTED, A}
@Test void jumpedOrReplacedRecordsLeft()      // jump to another chatbot → first run ending.chat LEFT
@Test void agentTookTheChatRecordsLeft()      // an agent assigns the chat mid-run, then the run completes → LEFT
@Test void runViewShowsEnding()               // GET /chatbots/{id}/runs/{runId} → ending.chat, ending.agent.name
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest=RunEndingIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`RunEnding` missing).

- [ ] **Step 3: Implement.** In `ChatbotRunner.ended(saved, replaced)`: compute the ending — replaced or `JUMPED` → `LEFT`; `HANDED_OVER` → re-read the chat, agent = `assignedUserId`, team = `assignedTeamId`; otherwise from `release`: `CLOSE` → `CLOSED` when `close` returned a chat else `LEFT`; `ROUTE` → `ROUTED` with the returned chat's assignee/team, else `LEFT`. Write it with one `updateFirst` on `{_id: run.id}` (`$set ending`, `$inc version`) and mirror it on `saved` (ending + version + 1) before `ChatbotRunChanged` is published. A failure to record is logged, never thrown.

- [ ] **Step 4: Run to verify they pass** (same command), then `-Dtest='BotReleaseIT,ChatbotHandoverIT'`. Expected: PASS.

- [ ] **Step 5: Commit** — `feat(chatbot): a finished run records where its chat went`

---

### Task 4: Steps carry progress

**Files:**
- Modify: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/ChatbotStep.java` (last component `Map<String, Object> progress`)
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/StepResult.java`
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/ChatbotRunner.java` (`recordWaiting`, `completeStep`, `recordFailed`)
- Modify: the run view's step (`progress` exposed)
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/run/StepProgressTest.java` and one case in `ChatbotTimersIT`

**Interfaces:**
- Produces: `ChatbotStep(..., Waiting waitedFor, Map<String, Object> progress)`; `StepResult.progress()` (nullable) and `StepResult withProgress(Map<String, Object> progress)` (copy with progress); step view field `progress` (`null` when none).
- Rule for all three record methods: the step's progress = the result's progress when non-null, else the progress the same WAITING step already had, else null. `recordFailed` (no StepResult) keeps the existing step's progress.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void waitingStepTakesProgress()                 // wait result withProgress({name:"Asha"}) → step.progress == {name:"Asha"}
@Test void reAskKeepsEarlierProgressWhenNoneGiven()   // second wait on same node with null progress → progress kept
@Test void completingStepKeepsProgress()              // wait with progress, then next() without → DONE step keeps it
@Test void failingStepKeepsProgress()                 // wait with progress, then NodeFailed → FAILED step keeps it
// ChatbotTimersIT:
@Test void waitingStepKeepsProgressWhenRunEnds()      // ticker ends a silent run (ENDED) → last step WAITING with its progress
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-service -am test -Dtest=StepProgressTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`withProgress` missing).

- [ ] **Step 3: Implement** the component, `withProgress`, and the rule above. Existing `ChatbotStep` constructions pass `null`; stored runs without the field read as `null`.

- [ ] **Step 4: Run to verify they pass**, plus `-pl omnichannel-app -Dtest=ChatbotTimersIT`. Expected: PASS.

- [ ] **Step 5: Commit** — `feat(chatbot): steps carry what a conversation has collected`

---

### Task 5: The AI notes progress alongside its reply

**Files:**
- Create: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/turn/ProgressTool.java`
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/api/TurnRequest.java` (add `boolean trackProgress` after `forceFinish`)
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/api/TurnResult.java` (add `Map<String, Object> progress` after `finish`)
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/call/AiCall.java` (+ `MongoAiTurnQueue` update) — field `progress`
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/turn/AiTurnRunner.java`
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/turn/ProgressToolTest.java`, cases in `AiTurnRunnerTest` (uses `ScriptedProvider`)

**Interfaces:**
- Consumes: `TurnRequest.collect()` (`List<AiField>`: key, description, type).
- Produces: `ProgressTool.NAME = "note_progress"`; `static Optional<AiTool.FunctionTool> of(List<AiField> collect)` (empty when collect is empty); `static Map<String, Object> parse(String argumentsJson, List<AiField> collect)` returning only non-null known keys; `TurnResult.progress()` (empty map when none; merged over every `note_progress` call of the turn, later wins).
- Schema: object, `additionalProperties: false`, every collect key required, each typed as the field's type or `null` (strict, via `StrictSchemas`). Description: `Call this together with your reply whenever the customer has given one of these details. Use null for anything not given yet. Never mention it to the customer.`

- [ ] **Step 1: Write the failing tests:**

```java
// ProgressToolTest
@Test void schemaIsStrictWithNullableKeys()
@Test void parseKeepsKnownNonNullKeys()         // {"name":"Asha","city":null,"x":1} → {name:"Asha"}
@Test void noCollectNoTool()
// AiTurnRunnerTest
@Test void progressToolOnlyWhenTracked()                // trackProgress false → no note_progress in request tools
@Test void progressWithTextCostsNoExtraRound()          // scripted reply: text + note_progress → 1 provider call, result.progress {name:"Asha"}, text sent
@Test void owedProgressOutputLeadsNextTurn()            // next turn's first input item = ToolResultItem(callId,"noted"), then the user text
@Test void progressOnlyReplyLoopsOnce()                 // reply: only note_progress, then text → 2 calls; counted in maxToolRounds
@Test void progressBesideFinishStillFinishes()          // note_progress + finish → finish set, progress set
@Test void supersededTurnKeepsProgressDropsOwedCalls()  // superseding turn: base open calls used, progress of the discarded turn still in its AiCall
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-ai -am test -Dtest='ProgressToolTest,AiTurnRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement.** In the tool loop: `note_progress` calls are parsed into the turn's progress and answered with `ToolResultItem(id, "noted")` added to the transcript (like app tools). When the asked calls are **only** `note_progress` and the text is not blank: put their ids in `open` and break (no extra round; the existing open-call mechanism sends the stored results first next turn). Only `note_progress` and blank text: send the results and loop (`rounds++`). Mixed with app tools or `finish`: handled in the same round as those. Store progress on the `AiCall` and return it in `TurnResult`. Name collisions: `note_progress` joins `finish` in the reserved names when app tools are named.

- [ ] **Step 4: Run to verify they pass** (same command). Expected: PASS.

- [ ] **Step 5: Commit** — `feat(ai): conversations note collected details without an extra round`

---

### Task 6: The AI step keeps its progress

**Files:**
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/AiExecutor.java` (submit with `trackProgress`; wrap conversation results)
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/AiConversationFlow.java` (merge into state)
- Test: cases in `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotAiConversationIT.java`

**Interfaces:**
- Consumes: `TurnRequest.trackProgress`, `TurnResult.progress()` (Task 5); `StepResult.withProgress` (Task 4).
- Produces: node state key `progress` (`Map<String, Object>`); every `StepResult` the conversation returns after an AI answer carries `withProgress(<merged progress>)`; `finish` fields are merged into it; the result map's `fields` key is unchanged.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void conversationTracksProgressWhenItCollects()   // CONVERSATION + collect [name, city] → request has note_progress
@Test void singleModeNeverTracks()                       // SINGLE with collect → no note_progress
@Test void progressAccumulatesAcrossTurns()              // turn 1 {name}, turn 2 {city} → waiting step progress {name, city}
@Test void finishFieldsJoinProgress()                    // finish fields {course} → completed step progress {name, city, course}; result.fields == {course}
@Test void failedTurnKeepsProgress()                     // turn 2 fails → FAILED step keeps {name}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotAiConversationIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the new cases.

- [ ] **Step 3: Implement**: `trackProgress = mode == CONVERSATION && !collect.isEmpty()`; in the AI-answer path merge `r.progress()` (and `finish.fields()`) into `st.progress` before deciding, and return `flow.onAi(...).withProgress(progress)`.

- [ ] **Step 4: Run to verify they pass**, plus `-Dtest='ChatbotAiSingleIT,ChatbotAiPlanIT'`. Expected: PASS.

- [ ] **Step 5: Commit** — `feat(chatbot): the AI step keeps what the customer has given`

---

### Task 7: Report endpoint

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/report/ChatbotReportService.java`
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/report/ChatbotReport.java` (records below)
- Modify: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/chatbot/ChatbotController.java`
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotReportApiIT.java` (seeds `chatbotRuns` documents directly)

**Interfaces:**
- Consumes: `ChatbotRun.ending` (Task 3), `ChatbotStep.progress` (Task 4), `FlowParser`/`Flow` (`entryCandidates`, `from(nodeId)`), node executors' `outputs()` order.
- Produces: `GET /api/workspaces/{ws}/chatbots/{id}/report?from=<Instant>&to=<Instant>&phoneNumberId=` (`@PreAuthorize chatbot:view`), body:

```java
record ChatbotReport(Totals totals, List<StepRow> steps, List<ProgressRow> progress) {
  record Totals(long started, long running, long completed, long wentSilent, long stopped, long failed,
                long handedOver, long jumped, ChatTotals chat) {}
  record ChatTotals(long toAgent, long toQueue, long closed, long left, long notRecorded) {}
  record StepRow(String nodeId, String name, String type, boolean removed, long reached, Map<String, Long> endedHere) {}
  record ProgressRow(String nodeId, List<FieldCount> fields, List<Long> byCount) {}
  record FieldCount(String key, String label, long given) {}
}
```

- Mapping: `completed`=COMPLETED, `wentSilent`=ENDED, `stopped`=STOPPED, `failed`=FAILED, `handedOver`=HANDED_OVER, `jumped`=JUMPED, `running`=RUNNING+WAITING. `chat.toAgent` = ending with agent; `toQueue` = ending HANDED_OVER/ROUTED without agent; `closed`=CLOSED; `left`=LEFT; `notRecorded` = finished runs without `ending`.
- `from`/`to` are Instants (as the existing runs API); defaults `to = now`, `from = to − 30 days`; `to − from > 92 days` → 400 `{"from": "The range can be at most 92 days"}`. **Ruling carried from planning:** the spec said dates in the brand's time zone; the console sends the viewer's local-day boundaries as Instants, matching `/runs`.
- `steps`: current flow (the chatbot's stored flow) in breadth-first order from the entry, following each node's outputs in `outputs()` order; then nodes seen only in runs, `removed: true`, name = last recorded `nodeName`. `reached` = distinct runs with a step on that node; `endedHere` = finished runs whose last step is that node, keyed by run status name.
- `progress`: one row per current AI node in CONVERSATION mode with collect fields; over runs that ended at that node, `given` per key = runs whose last step on that node has the key in `progress`; `byCount[k]` (k = 0..K) = runs that gave exactly k keys; `label` = the collect field's description truncated to 40 chars, else the key.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void totalsByStatusAndChat()            // seeded runs of every status + endings → exact totals
@Test void testRunsAreLeftOut()
@Test void stepsInFlowOrderWithRemovedLast()  // v1 run visited a node removed in v2 → last row removed=true with old name
@Test void reachedAndEndedHere()              // run A ends at Counsellor (ENDED), run B passes it → reached 2, endedHere {ENDED:1}
@Test void runningRunsNeverEndHere()
@Test void progressBuckets()                  // collect [name, city, experience]; ended runs with {}, {name}, {name,city} → byCount [1,1,1,0], given name 2 city 1
@Test void numberFilter()
@Test void rangeOver92DaysIs400()
@Test void needsChatbotView()                 // user without chatbot:view → 403
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotReportApiIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 404 / compilation failure.

- [ ] **Step 3: Implement** with two `MongoTemplate` aggregations over `{workspaceId, chatbotId, createdAt}` (+ `phoneNumberId`, `test: false`): one `$group` by status/ending for totals, one projecting `steps.nodeId`, `steps.nodeName`, the last step and its `progress` for the rest; ordering and labels in Java. Add an index only if `reportOf10kRunsUnderOneSecond` (below) needs it.

```java
@Test void reportOf10kRunsUnderOneSecond()   // 10 000 seeded runs × 8 steps → report < 1 s (warm, second call)
```

- [ ] **Step 4: Run to verify they pass** (same command). Expected: PASS.

- [ ] **Step 5: Commit** — `feat(chatbot): report of where a chatbot's customers stop`

---

### Task 8: Runs behind a report number

**Files:**
- Modify: `ChatbotReportService.java`, `ChatbotReport.java`, `ChatbotController.java`
- Test: cases in `ChatbotReportApiIT.java`

**Interfaces:**
- Consumes: Task 7's filters and mapping (reuse the same criteria builder so counts and lists cannot drift).
- Produces: `GET /api/workspaces/{ws}/chatbots/{id}/report/runs?from&to&phoneNumberId&nodeId&reached&status&chat&given&page=0&size=25` (`chatbot:view`; `size` ≤ 100), each filter optional: `nodeId` = ended at that node, or with `reached=true` = visited it, `status` = run status, `chat` = `TO_AGENT|TO_QUEUE|CLOSED|LEFT|NOT_RECORDED`, `given` = exact count of progress keys on that node (needs `nodeId`). Body `RunRowPage(List<RunRow> items, long total, int page, int size)`:

```java
record RunRow(String runId, String contactName, String phone, String numberLabel, Instant startedAt,
              Instant finishedAt, String status, RunEnding ending, String agentName, String teamName, Map<String, Object> progress,
              String chatId, String dealId, String dealPipelineId, String leadOwnerName) {}
```

- `dealId` from the run's `add_to_crm` step result (`result.dealId`), `dealPipelineId` and `leadOwnerName` looked up through a new `AutomationCrm.dealInfo(String workspaceId, List<String> dealIds) -> Map<String, DealInfo>` (`record DealInfo(String pipelineId, String ownerName)`) — one call per page; `progress` = the last step's on the node filtered (or the run's last step).

- [ ] **Step 1: Write the failing tests:**

```java
@Test void runsListMatchesReportCounts()   // for each StepRow.endedHere and each Totals bucket, report/runs?…&size=100 total == the count
@Test void givenFiltersByProgressCount()
@Test void rowCarriesChatDealAndOwner()    // run with add_to_crm result → dealId, dealPipelineId, leadOwnerName ("No owner" is the console's job: null here)
@Test void newestFirstAndPaged()
```

- [ ] **Step 2: Run to verify they fail** (Task 7's command). Expected: 404 on `report/runs`.

- [ ] **Step 3: Implement** as specified; `dealInfo` in `AutomationCrmAdapter` reads `CrmDeal` by ids in the workspace and resolves owner names through the CRM's people directory.

- [ ] **Step 4: Run to verify they pass.** Expected: PASS.

- [ ] **Step 5: Commit** — `feat(chatbot): list the runs behind each report number`

---

### Task 9: Whole-module check and docs

**Files:**
- Modify: `docs/superpowers/known-limits.md` (one line: leads created before 2026-11-20 get an owner only on their chat's next assignment; runs before this release show "not recorded")
- Modify: `docs/superpowers/project-rules.md` §1 only if a new trap was hit.

- [ ] **Step 1:** Run boundary tests: `./mvnw -q -pl omnichannel-app -am test -Dtest='*BoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: PASS (service imports only `ai.api.` from ai; crm reached only through the port).
- [ ] **Step 2:** Run `./mvnw -q verify` once, output to the workspace. Expected: exit 0.
- [ ] **Step 3: Commit** — `docs: limits of lead owners and chatbot report history`
