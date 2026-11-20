# Leads follow the chat, and a chatbot report of where customers stop

Design, 2026-11-20. Backend worktree `.worktrees/lead-owner-report` (branch `lead-owner-report`); the console gets a
worktree of the same name when its plan starts.

## 1. Intent

Organisations like Bimal Institute work a WhatsApp number in one of two ways:

1. **One dedicated agent per number.** Every lead a chatbot on that number produces should belong to that agent at
   once.
2. **A number shared by several agents.** Leads should go to the agents in turn, so each gets an equal share.

Both are already possible for **chats** (a number's owning team, routing "always assign to one member" or round
robin). They do not reach **CRM leads**: the chatbot's "Add to CRM" step gives the lead the chat's current assignee,
and since bot-first routing (spec 2026-11-18) a chat has no assignee while a chatbot runs, so every lead a chatbot
creates has no owner, and nothing fills it later.

Once leads get owners from the chat, the leads that never reach a person stay without one. The user wants to see
those: a **chatbot report** that shows where customers stopped, including how far an AI conversation got.

## 2. What the user decided

| # | Decision |
|---|---|
| D1 | **The lead follows the chat** (option B): a lead the chatbot creates starts without an owner and gets one when its chat is given to an agent. Not "pick the agent when the lead is created" (A), not separate turns for leads and chats (C). |
| D2 | Bimal's chatbot adds the lead **first**, so every enquiry is a lead even when the customer drops off. (Done 2026-11-20 as a data change, see §4.) |
| D3 | Bimal's Counsellors team routes **"always assign to one member"**. (Done 2026-11-20, see §4.) |
| D4 | A **Chatbots report page** shows where customers stopped, step by step. |
| D5 | For AI conversation steps the report also shows **how far the conversation got**: which of the step's collect fields the customer had given (option B, over "steps only"). |
| D6 | The user approved the playback of 2026-11-20, which included the calls marked **(mine)** below. |

## 3. Goals, non-goals, anti-goals

Goals: every way a chat gets an agent fills the empty owner of that customer's CRM contact and open deals on that
number; a report per chatbot with run totals, a step table, AI progress, and a click-through list of the runs
behind each number with links to the chat and the lead.

Non-goals: picking an agent when the lead is created; moving a lead's owner when the chat moves on (D6: only an empty
owner is filled); setting the deal's `teamId`; a report across all chatbots at once; charts over time; exporting the
report; the "Assign chat" rule action (hidden since 2026-11-18) gets no new behaviour beyond what its routing records.

Anti-goals (failures even if it works):
- an owner someone set by hand is replaced by the routing;
- a lead gets an owner who cannot see the number;
- the report counts test runs, or a run twice;
- the AI progress note costs an extra model round trip on an ordinary turn, or a customer sees anything of it;
- the report page is slow enough to time out on a few thousand runs.

## 4. Facts the design rests on

- **Assignments.** Every path that gives a chat to a person records a `WaWebChatAction` (`ROUTED`, `ASSIGNED`,
  `TRANSFERRED`) with `toUserId`, and publishes `InboxEvents.ActionRecorded(chat, action)`:
  `ChatRoutingService.routed` (number routing, sticky, round robin, rule and chatbot assigns) and
  `ChatAssignmentService` (assign, claim, claim on reply, hand off, transfer). A chat an agent starts
  (`NewChatService`) is assigned when created, so a lead added later already gets that agent.
- **Lead creation.** `AddToCrmExecutor` / `AddToCrmHandler` → `AutomationCrm.addLead(LeadRequest… ownerUserId =
  chat.getAssignedUserId())` → `AutomationCrmAdapter` (omnichannel-app) → `CaptureService.captureForAutomation`, which
  creates the `CrmContact` (unique per workspace, contact, `channel.phoneNumberId`) and a deal via
  `DealService.createCaptured(... explicitOwner = true)`. A `BOT` chat has no assignee, so both owners are null.
- **Module boundary.** `omnichannel-service` reaches the CRM only through ports (`AutomationCrm`) implemented in
  `omnichannel-app`.
- **Chatbot runs.** `chatbotRuns` documents carry `chatbotId, versionNumber, phoneNumberId, chatId, contactId,
  status (COMPLETED | ENDED | STOPPED | HANDED_OVER | JUMPED | FAILED, or RUNNING/WAITING), test, steps[]
  (ChatbotStep: nodeId, nodeName, type, outcome, output, result…), variables, createdAt, finishedAt, endReason`.
  Index `{workspaceId, chatbotId, createdAt}`. After a run, `ChatbotRunner.release` closes the chat or routes it
  (`AfterChatbot` CLOSE / ROUTE from the chatbot settings: completed defaults to CLOSE, went-silent to ROUTE;
  failed and stopped always ROUTE); a hand-over node routes it itself. **Nothing on the run records where the chat
  went.**
- **AI conversation step.** `AiConversationFlow` keeps per-step state (`turns`, `lastReply`…); `fields` exist only
  when the model calls the `finish` tool (`ai/turn/FinishTool`), so a conversation that is abandoned has no record of
  what the customer gave. `AiTurnRunner` loops over tool calls; app-side tools cost one more provider round trip each.
- **Existing runs page.** Console `Chatbots/ChatbotRunsPage.tsx`: one chatbot's runs filtered by status and dates,
  with "Where it ended".
- **Bimal data changes done 2026-11-20** (dump `backups/omnichannel_crm-before-bimal-routing-2026-11-20`):
  Counsellors team override routing `USER` → Demo Admin (the team's only member); chatbot "Course enquiry" version 2
  published: `AddLead` is the entry (→ `Counsellor`), deal title `{{contact.name}} – Course enquiry`, `SaveDetails` →
  `HandToCounsellors`, hand-over mode `NUMBER_ROUTING`.

## 5. Approaches

**Lead ownership** (D1 chose B):
- A. Pick the agent when the lead is created and make the hand-over use the same agent. Rejected by the user.
- **B. The lead follows the chat (chosen).** A listener on assignments fills empty owners. Simple and covers every
  path that assigns; a lead whose chat never reaches an agent stays unowned, which the report surfaces.
- C. Separate turns for leads and chats. Rejected: the owner and the person chatting could differ.

**Where the hook sits (mine):** on `ActionRecorded` with a `toUserId`, not on `ChatChanged` (fires on every message)
and not inside each assignment method (many paths, easy to miss one).

**Report data** (D5 chose progress):
- A. Steps only, from the runs as stored. No AI progress.
- **B. Steps plus AI progress (chosen).** Needs a progress note from the model during the conversation.

**How the AI notes progress (mine):** a `note_progress` function tool the model calls alongside its reply; its
output is owed and sent at the start of the next turn, so an ordinary turn costs no extra round trip. Rejected: a
second extraction call per turn (doubles calls and latency); requiring JSON replies (changes every node's reply
format and the brand's prompts).

**How the report is computed (mine):** aggregate `chatbotRuns` on request (date range capped), no pre-computed
counters. Rejected: counters updated as runs move (a second source of truth to keep right, and no drill-down).

## 6. Design

### 6.1 The lead follows the chat

- `omnichannel-service`: `service/automation/LeadOwnerListener` (or in `service/crm`; builder's choice of package)
  handles `InboxEvents.ActionRecorded` for `ROUTED`, `ASSIGNED`, `TRANSFERRED` with a non-null `toUserId`, after the
  transaction that recorded it, and calls the port.
- Port: `AutomationCrm.fillOwner(String workspaceId, String customerId, String contactId, String phoneNumberId,
  String userId)` returning what it changed (`crmContactId` or null, list of deal ids).
- Adapter (`omnichannel-app`) → CRM (`omnichannel-crm`), one guarded update each, never a read-modify-write:
  - the `CrmContact` for `{workspaceId, contactId, channel.phoneNumberId}` **where `ownerId` is null** gets `ownerId
    = userId`;
  - that contact's **open, not archived** deals (`primaryCrmContactId` = it, `status OPEN`) **where `ownerId` is
    null** get `ownerId = userId`.
  - Each change records the activity and fires the deal/contact events a manual owner change records today (so
    timelines and automation events stay consistent), with the actor shown as **"Inbox routing"** (mine; the
    builder finds the existing owner-change activity and event and reuses them).
- No CRM contact on that number, CRM disabled for the brand, or owners already set: nothing happens, nothing logged
  above debug. A failure is logged and never fails the assignment.
- **Only what the agent can see (mine, from the builder check):** the contact is filled only when the agent can use
  the brand's CRM; a deal is filled only when the agent can open its pipeline (the CRM's per-user pipeline access).
  Anything else stays without an owner and shows as "No owner" in the report, so a manager notices it. The number
  itself is not re-checked: the assignment already required that the agent can see it.
- **No backfill (mine):** leads created before this ships, whose chats are already assigned, get an owner on their
  chat's next assignment only. Bimal's chatbot has added leads first only since 2026-11-20, and only with the test
  phone, so there is nothing worth migrating.

### 6.2 Where a run's chat went

New field on `ChatbotRun`: `ending {chat: HANDED_OVER | ROUTED | CLOSED | LEFT, agentUserId, teamId, at}`, set once
when the run ends:
- hand-over node (`AssignChatExecutor`, run status `HANDED_OVER`): `HANDED_OVER`, with the agent and team the
  assignment produced (null agent = waiting in a queue);
- `release` with `ROUTE`: `ROUTED` with what `BotChats.toPeople` produced; with `CLOSE`: `CLOSED`;
- jumped, replaced, or a chat no longer in `BOT` under this run: `LEFT` (someone else has it).

`BotChats.toPeople` returns the routed chat so the runner can record it. Runs that ended before this field existed
have no `ending`; the report counts them by status only and says so ("12 earlier runs: where the chat went was not
recorded").

### 6.3 AI progress in conversations

- `ai.api.TurnSpec` gains `progressFields: List<CollectField>` (the node's collect keys and descriptions). The AI node
  sets it in **CONVERSATION** mode when `collect` is non-empty; SINGLE mode never.
- `ai/turn/ProgressTool` (beside `FinishTool`): function `note_progress`, parameters = one nullable property per
  collect key (strict schema, every key required, `null` = not given yet), description: call it, together with your
  reply, whenever the customer has given one of these details; never mention it to the customer.
- `AiTurnRunner`:
  - a response with reply text and **only** `note_progress` calls: the turn ends there (no extra round trip). The
    arguments become `TurnOutcome.progress` (non-null values only). The calls' outputs (`"noted"`) are **owed**:
    stored in the conversation state and sent first in the next turn's input (OpenAI: `function_call_output` items
    with `previous_response_id`; transcript providers: in the transcript).
  - a response with **only** `note_progress` calls and no text: answer them and call again, counted in
    `maxToolRounds`.
  - `note_progress` alongside other tool calls or `finish`: handled with them in the same round; `finish` still ends
    the turn.
  - a superseded turn (spec 2026-11-15 §5.5): its progress is kept (the customer did say it); its owed outputs are
    dropped with the discarded response.
- `AiConversationFlow` merges each turn's progress into the node's state (later values win; `finish` fields are
  merged in too). **Where it is stored (mine, from the builder check):** a waiting or failed step today has
  `result = null`, and a run that goes silent ends with its last step still WAITING, so the report could not read a
  result. The step therefore gets a field of its own, `ChatbotStep.progress: Map<String, Object>`, written on the
  current step **after every turn** (by the waiting write as well as the completing and failing ones) and kept when
  the step completes or fails. The report reads `steps.progress` by `nodeId`, so renames and versions do not matter;
  a node visited twice in one run keeps one step per visit and the report reads the last visit's. `fields` keeps today's meaning
  (set by `finish` only), so existing `{{Node.fields.x}}` users see no change.
- The run panel's AI step shows "Collected: name, city" (console), during the conversation as well as after it.

### 6.4 Report API

Under the chatbot API, authority `chatbot:view`. Test runs (`test = true`, from the editor's and rule test page's
Test) are **left out**. A jump starts a new run of the target chatbot, which counts as a normal run of that chatbot.

`GET /api/workspaces/{ws}/chatbots/{id}/report?from=YYYY-MM-DD&to=YYYY-MM-DD&phoneNumberId=` (dates in the brand's
time zone, `to` inclusive, range ≤ 92 days, default the last 30 days):

```
{ totals: { started, running, completed, wentSilent, stopped, failed, handedOver, jumped,
            chat: { toAgent, toQueue, closed, left, notRecorded } },
  steps: [ { nodeId, name, type, removed, reached, endedHere: { <runStatus>: n } } ],
  progress: [ { nodeId, fields: [ { key, given } ], byCount: [ n0, n1, … nK ] } ] }
```

- `steps` in flow order of the chatbot's **current** flow (from the entry, breadth first, following edges in
  handle order); nodes seen in runs but absent from the current flow follow with `removed: true` and the last name a
  step recorded. Steps are matched by `nodeId` across versions.
- `reached`: runs with at least one step on that node. `endedHere`: finished runs whose **last** step is that node,
  by run status. Running and waiting runs count in `reached` and in `totals.running`, never in `endedHere`.
- `progress`: one entry per AI conversation node with collect fields; over runs that **ended at that node**,
  `fields[].given` = runs whose progress has the key, `byCount[k]` = runs that gave exactly k of the K keys.
- One aggregation per request over `{workspaceId, chatbotId, createdAt}` (+ `phoneNumberId`); `$unwind` of steps
  projected to `nodeId` only. Target: under 1 s for 10 000 runs in range; the builder adds an index only if the IT
  shows the need.

`GET /api/workspaces/{ws}/chatbots/{id}/report/runs?from&to&phoneNumberId&nodeId&status&chat&given=<k>&page&size`
→ the runs behind any number on the page, newest first, 25 per page: `{runId, contactName, phone, numberLabel,
startedAt, finishedAt, status, ending, progress, chatId, dealId (from the run's add-to-CRM step result, else null),
leadOwnerName}`.

### 6.5 Console

- **Chatbots → Report** in the side nav (`/chatbots/report`, `chatbot:view`); the editor header gets **Report** next
  to Runs, opening the page with that chatbot chosen.
- Filters: chatbot, dates (default last 30 days), number (all by default).
- Totals line: Started · Finished · Went silent · Handed over · Stopped · Failed · Running; second line: where the
  chats went: to an agent · waiting in a queue · closed by the chatbot · taken by someone else (+ "not recorded" for
  older runs).
- Step table: Step · Reached · Ended here (with a tooltip by status). AI conversation rows expand to "gave nothing 12
  · 1 detail 9 · 2 details 11 …" and per field "name 29 · city 20 …". Removed steps under a "Removed steps" heading.
- Every count is a link that opens a drawer with the runs behind it (the runs API); each row links to the chat in the
  inbox, the lead (deal) in the CRM when there is one, and the run panel. The lead owner column shows "No owner" in
  warning colour.
- Empty states: no runs in range; chatbot never published.

## 7. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB; collections camelCase; React 18 + PatternFly 6.5. No "template" naming.
Work in `.worktrees/lead-owner-report` in both repos, tests first, Prettier on touched files only, full
`./mvnw -q verify` once before merge; merge, push and deploy only when the user asks. Real WhatsApp sends only to a
recipient the user names.

## 8. Left to the builder

Package of the listener; the exact owner-change activity and events to reuse; aggregation shape and whether an index
is needed; how the runner gets the routed chat back from `BotChats`; the drawer component split; wording of the
`note_progress` description beyond §6.3.

## 9. Verification

- Backend ITs: a chatbot adds a lead (no owner) → hand-over with number routing `USER` → contact and deal owned by
  that member; round robin gives two customers' leads to two members in turn; a lead with an owner set by hand keeps
  it; a closed-by-bot chat leaves the lead unowned; a chat on a brand without CRM does nothing; `ending` recorded for
  hand-over, routed, closed and jumped runs.
- AI: golden request JSON with `note_progress`; a turn with text + progress makes one provider call and the next turn
  starts with the owed output; a progress-only response loops once; supersede keeps progress; `finish` still ends the
  turn; SINGLE mode has no progress tool.
- Report: fixtures with runs across two versions (a removed node), test runs excluded, running runs, progress
  buckets, number filter, range limit; the runs API returns the same counts as the report for each link.
- Console: vitest for the report page (totals, step order, AI expansion, drawer links) and the run panel's
  "Collected"; `tsc`, lint, build.
- Manual on 8089 with the user's test phone (Veenu): message the Main line, stop after giving name and city, let the
  chatbot end; the report shows the run at Counsellor with 2 details; the lead has no owner. Then a second run handed
  over: the lead belongs to the Counsellors member.

## 10. Risks

- The model may not call `note_progress` reliably; the report then under-counts progress. The description and the
  node's instructions carry it; the run panel shows what was noted, so a brand can tune its prompt.
- Owner filling fires CRM events that automation rules may react to (e.g. "deal owner changed"); this is wanted
  behaviour, but it is new traffic on every assignment of a chat whose lead has no owner.
- Aggregating on request is fine at current scale; a brand with heavy traffic may need pre-computed counters later.
