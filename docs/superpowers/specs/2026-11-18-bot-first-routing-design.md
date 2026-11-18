# Chatbot first, then people: routing order, the "With chatbot" status, and the hand-over node

Design, 2026-11-18. Branch `bot-first-routing` in the backend and console repos. Plans follow via writing-plans;
the user executes them with Opus.

## 1. Intent

Most brands will put a chatbot in front of their incoming chats. Today the inbox gives a new chat to a team or an
agent while the message is being stored, and the automation rules (which start chatbots) run up to two seconds later.
So an agent is assigned, and a round-robin turn is used, for a chat a chatbot is about to handle; and chats a chatbot
is handling sit in the same lists as chats waiting for a person.

The user wants:

1. chat assignment to run **after** the check for a chatbot;
2. the rule action "Assign chat" **removed from the action list** (the code stays; the decision is recorded);
3. a way to **filter the chats chatbots are handling**, with a new chat status and a defined set of users who may see them;
4. **one chatbot node that gives the chat to a human agent**.

The user asked for my recommendation on all of it and accepted the design in §4, with one change of theirs:
**a chat the customer abandons goes to the team by default** (D5).

## 2. Goals, non-goals, anti-goals

Goals: the four points above; a chat is never left with nobody (no chatbot and no route); a brand without chatbots
sees no change and no delay.

Non-goals: removing the `ASSIGN_CHAT` action code; showing a chat that an agent holds, with a chatbot running on it,
in the Chatbot list; an unread badge on the Chatbot list; per-team limits on who sees chatbot chats beyond the
numbers they may already open; changing what rules or chatbots may run on a chat an agent holds.

Anti-goals (a failure even if it works):
- a chat stuck in "With chatbot" with no run, or open with nobody and never routed, because a worker stopped;
- an agent assigned and then un-assigned a moment later (chats flashing through lists, round-robin turns burned);
- a chatbot and an agent both answering after a hand-over;
- an existing rule with "Assign chat", or an existing chatbot with the assign node, that stops loading or running.

## 3. How it works today (facts the design rests on)

- `InboxIngestService.publish` publishes `ChatCreated` / `ChatReopened` / `CampaignChatFirstReply`, then
  `MessageUpserted`. `ChatRoutingListener` routes on the first three, synchronously (`ChatRoutingService.route`).
- `AutomationMessageListener` queues a `MESSAGE_RECEIVED` event on `MessageUpserted` (new, inbound, from the contact,
  a real type). `AutomationWorker.tick` (every 2 s, one thread) runs every published rule in order
  (`RuleRunner.run`), then `finish`es the event; a step that must wait releases the event for a retry 2 minutes later.
- `StartChatbotHandler` starts a run through `ChatbotRunService.start`, which inserts the run, sets the chat's
  `chatbot` pointer (`ChatPointer.set`) and proceeds on the chatbot pool. `ChatbotRunner.finish` / `ended` clear the
  pointer. Final run statuses: `COMPLETED` (flow reached its end), `ENDED` (the customer did not answer within
  `endAfterMinutes`), `STOPPED` (a person, or a replacing run), `HANDED_OVER` (an agent replied from the console and
  `stopOnAgentReply` is on), `JUMPED` (went on in another chatbot), `FAILED`.
- `ChatStatus` is `OPEN | CLOSED`. The chat list defaults to `status=OPEN`. `InboxScope` decides what a user sees
  (`covers`, `criteria`, `criteriaFor(ListScope)`); the live stream uses `covers`.
- The chatbot node `assign_chat` (`AssignChatExecutor`) assigns by agent, team queue or team round robin and then
  **continues** the flow. The rule action `ASSIGN_CHAT` (`AssignChatHandler`) does the same for rules.
- Chatbot settings are layered (platform, brand, chatbot): `ChatbotSettings` / `ChatbotSettingsLayer`.
- A user actor may send only into an `OPEN` chat (`InboxSendService`); a console reply may claim the chat
  (`ChatAssignmentService.claimOnReply`).
- Demo data: no rule uses `ASSIGN_CHAT`; one chatbot has an `assign_chat` node.

## 4. Decisions

| # | Decision | Whose |
|---|---|---|
| D1 | Routing waits for the rules: ingestion marks the chat, the rule worker routes it once the message's rules have run and no chatbot took it. | mine, accepted |
| D2 | New chat status `BOT`, shown as "With chatbot". | mine, accepted |
| D3 | New permission `inbox:view-bot`; default for Customer admin and Workspace manager, not for agents. | mine, accepted |
| D4 | The chatbot node `assign_chat` becomes "Hand over to agent": new default mode "the number's normal routing", and it ends the chatbot. | mine, accepted |
| D5 | When a chatbot ends without a hand-over: finished normally → the chat is closed; **customer stopped answering → the chat goes to the team**; each is a layered chatbot setting. A failed chatbot always goes to the team. | default for abandoned chats is the user's |
| D6 | The rule action "Assign chat" is hidden from the add-action list; rules that have it keep working. | user |

### Approaches considered for D1

- **A. Route after the rules (chosen).** Ingestion marks the chat as waiting; the worker that runs the rules settles
  it. No agent sees a chat a chatbot takes; costs up to one worker tick (2 s) before a person is assigned, only for
  brands that have a chatbot rule.
- B. Predict in the ingestion whether a rule will start a chatbot, by evaluating rule conditions there. Rejected:
  duplicates the rule engine on the ingesting thread, and earlier actions of a rule can change what later ones see.
- C. Route as today and un-assign when a chatbot starts. Rejected: the anti-goal (chats flash through agents' lists,
  round-robin turns and sticky agents are spent on chats no person handles).

## 5. Design

### 5.1 Data

- `ChatStatus`: `OPEN, BOT, CLOSED`.
- `WaWebChat.routingPendingSince` (`Instant`, null when not waiting), with a sparse index for the sweep.
- `WaWebChat.handedOverAt` (`Instant`): set when a chatbot gives the chat to people (the hand-over node, a release
  as `ROUTE`, an agent taking a `BOT` chat over); cleared when the chat is closed and when a chatbot takes it
  again. While it is set, a rule does not start a chatbot on the chat (§5.3).
- `RoutedReason`: add `CHATBOT_HANDOVER` (the hand-over node used the number's routing) and `CHATBOT_ENDED` (a
  chatbot ended and the chat went to the team). The action's `reasonDetail` is the chatbot's name, `byName`
  `"Chatbot: <name>"`, as `assignByChatbot` does today.
- `AssignMode`: add `NUMBER_ROUTING`. It is valid only on the chatbot node; the rule validators refuse it on a rule
  action.
- `ChatbotSettings` / `ChatbotSettingsLayer`: two new fields of a new enum `AfterChatbot { CLOSE, ROUTE }`:
  `whenCompleted` (code default `CLOSE`) and `whenAbandoned` (code default `ROUTE`). Null in a layer inherits.
  Stored layers without the fields read as null (inherit), so no migration.
- `Authority.INBOX_VIEW_BOT("inbox:view-bot", BRAND, "See the chats a chatbot is handling")`, in the default
  authorities of `CUSTOMER_ADMIN` and `WORKSPACE_MANAGER`. Existing built-in roles get it the way the last added
  authorities reached them (`ai:view` is the precedent; the builder follows the same path). The pinned authority
  counts in `AuthorityTest` and `RoleSeederIT` go up by one.

Invariant: **a `BOT` chat has its `chatbot` pointer set and no assigned user.** The sweep (§5.5) repairs a `BOT`
chat that lost its run. The reverse is not required: an `OPEN` chat may have a running chatbot and no user (a chat
in a team's queue that a person unassigned, handed back or gave to a team while the chatbot runs); it stays `OPEN`
with the chip, as today.

### 5.2 Routing waits for the rules

A new service in `service.inbox`, `RoutingGate`, owns the waiting:

- `arrive(chat)`: called by `ChatRoutingListener` where it calls `routing.route` today (same three events, same
  conditions). First, if the chat already has a chatbot pointer and no assigned user (a chatbot wrote first into a
  closed chat and the customer has now answered), it becomes `BOT` and nothing else happens. Otherwise, if the
  brand **can start a chatbot from a message** it sets `routingPendingSince = now` (an update that bumps the chat's
  version, as `ChatPointer` does); otherwise it routes at once, exactly as today.
  "Can start a chatbot from a message" = the workspace has a `PUBLISHED` rule on `MESSAGE_RECEIVED` with a
  `START_CHATBOT` action, and `WorkGate` allows chatbots. The gate reaches the rules through a small port in
  `service.inbox` implemented in `service.automation`, so the inbox package does not import automation classes
  (builder: follow whatever the existing package-direction tests require).
- `settle(chatId)`: clears `routingPendingSince` with a `findAndModify` that matches only a waiting chat, so two
  callers cannot both route. If it cleared it and the chat is `OPEN` with no user and no team, it routes
  (`ChatRoutingService.route(chat)`, then `ChatChanged`). A chat that is `BOT`, closed, or already assigned is left.
- Callers of `settle`:
  1. `AutomationWorker.process`, whenever it lets go of a `MESSAGE_RECEIVED` event with a chat: after `finish`
     (done or failed) **and** when it releases the event for a retry (a rule step waiting two minutes must not hold
     the chat back).
  2. `AutomationMessageListener`, when it stores an inbound message but queues no event for it (not a real type, or
     the writer declined): nothing will run rules for it. `AutomationEventWriter.messageReceived` returns void
     today; it changes to say whether it queued an event.
  3. The sweep (§5.5), for a chat waiting longer than 30 s (`omnichannel.inbox.routing-wait`, default `30s`).

An `ASSIGN_CHAT` rule action that runs meanwhile assigns the chat as it does today; `settle` then finds it assigned
and leaves it.

### 5.3 A chatbot takes the chat, and gives it back

**Take.** Where a run's pointer is first set on a chat (`ChatbotRunService.start` → `ChatPointer.set`), in the same
update: if the chat is **`OPEN` with no assigned user**, set `status = BOT`, clear `assignedTeamId`,
`routingPendingSince` and `handedOverAt`. A chat an agent holds keeps its status and assignment and shows the chip,
as today. A `CLOSED` chat stays closed while the chatbot writes into it, as today (a deal rule or a manual start on
a closed chat must not open it); it becomes `BOT` when the customer answers (`arrive`, §5.2). This holds for every
start that has a chat: a rule, a manual start, a jump's next run, and test runs.

**No second chatbot after a hand-over.** Rules run on every message, so without a guard the customer's next message
in a team's queue would start the chatbot again and pull the chat back out of the queue. While `handedOverAt` is
set, `StartChatbotHandler` skips a live (not test) start with the reason "The chat was handed over to the team".
A manual start by a person and a test are still allowed. Closing the chat clears the mark, so the customer's next
conversation starts with the chatbot again.

**Rule tests.** A test only captures the event; the tester runs the actions later, by which time the chat has been
routed. So in a test the chat turns `BOT` only if no agent holds it by then (team-queue routing or none); under
round robin it stays with its agent and shows the chip. The end-to-end check of this feature uses a published rule.

**Give back ("release").** When a run reaches a final status and the chat is `BOT` with its pointer on that run
(`ChatbotRunner.finish` / `ended`, and the external ends in `ChatbotRunService`), one of:

| Run ended as | What happens to the chat |
|---|---|
| hand-over node (§5.4) | the node has already set it `OPEN` and assigned it |
| `HANDED_OVER` by an agent's console reply | already taken over by the reply (below) |
| `COMPLETED` | the resolved setting `whenCompleted`: `CLOSE` or `ROUTE` |
| `ENDED` (customer silent) | the resolved setting `whenAbandoned`: `ROUTE` or `CLOSE` |
| `FAILED` | `ROUTE`, always |
| `STOPPED` by a person | `ROUTE` |
| `JUMPED`, or `STOPPED` because another run replaces it | nothing: the next run holds the chat (no flap through `OPEN`) |

- `ROUTE`: `status = OPEN`, `handedOverAt = now`, then `ChatRoutingService.route` with reason `CHATBOT_ENDED` and
  the chatbot's name as `reasonDetail` and in `byName`. `RoutingOptions` gains the detail and the by-name
  (`route(chat, reason)` cannot carry them today). A sticky return still records `STICKY` as its reason, as today,
  with the same `byName`, so the trail reads "returned to Asha · Chatbot: Welcome". With no owning team, or routing mode `NONE`, the chat stays open and unassigned (the owning team's
  Unassigned list shows it), as any unrouted chat today.
- `CLOSE`: `status = CLOSED` and a `CLOSED` action with `byName "Chatbot: <name>"`. The next inbound message reopens
  it through the ingestion as today, which calls `arrive` again.
- If the "replaced" run's replacement fails to start, the sweep finds a `BOT` chat without a run and routes it.

**An agent takes over.** A user who can see a `BOT` chat (§5.6) may act on it:
- Reply (`inbox:send`): allowed although the chat is not `OPEN`. The send first sets `status = OPEN`,
  `handedOverAt = now` and assigns the chat to the sender (an explicit claim, whatever `claimOnReply` says: otherwise it would be open with nobody). The
  existing `ChatbotHandoverListener` then ends the run when `stopOnAgentReply` is on.
- Assign / hand off (`inbox:assign`): sets `status = OPEN` and `handedOverAt = now` with the chosen assignment (a
  user, or a team's queue); the run goes on with its chip until an agent replies, as on any held chat.
- Close: `status = CLOSED` and the run is stopped (no release).
- Stop chatbot (existing button): `STOPPED` by a person → `ROUTE`.
- `NewChatService` treats an existing `BOT` chat like a closed one being reopened by the agent: it becomes `OPEN` and
  theirs.

Non-user actors (automation, chatbot, campaign) may send into a `BOT` chat wherever they may send into an `OPEN` one.
An inbound message leaves a `BOT` chat `BOT` (the ingestion only reopens `CLOSED`).

Code that selects `OPEN` chats for re-routing (`OwnershipRoutingListener`, `AvailabilityRoutingListener`) keeps
selecting only `OPEN`: `BOT` chats have no holder to move. The builder checks every reader of `ChatStatus`
(history fetch opener, CRM chat directory, views) and treats `BOT` as "not closed" unless stated here.

### 5.4 The hand-over node

The node keeps its type code `assign_chat` (stored flows keep loading) and is shown as **"Hand over to agent"**.

- Settings (`AssignChatSettings`): `mode` gains `NUMBER_ROUTING`, the default for a new node; `AGENT`, `TEAM_QUEUE`,
  `TEAM_ROUND_ROBIN` with `userId` / `teamId` / `whenAway` stay as they are. `NUMBER_ROUTING` needs no other field.
- Behaviour: do the assignment, set `status = OPEN` and `handedOverAt = now`, and **end the run as `HANDED_OVER`** (a new terminal
  `StepResult`; summary as today, e.g. "Assigned to Asha" / "Waiting in Sales's queue"). The node has **no outputs**.
  - `NUMBER_ROUTING` on a chat nobody holds: `ChatRoutingService.route(chat, CHATBOT_HANDOVER)` (sticky agent and
    the team's mode apply). With no owning team or mode `NONE`: open and unassigned; the summary says
    "Open, waiting for the team".
  - `NUMBER_ROUTING` on a chat an agent already holds: the holder keeps it; summary "Stays with <name>".
  - The other modes: as `AssignChatExecutor` does today.
  - A run without a chat fails the node as today.
- Existing flows that connect something after the node: the runner ends at the node and never follows the edge.
  `FlowValidator` reports a WARNING on such an edge ("Nothing runs after a hand-over"), so a published version keeps
  running and an editor can clean up. The console drops the node's output handle.
- To tell the customer first, a Send message node goes before it (no message field on this node).

### 5.5 The sweep

`ChatbotTicker` (its own scheduler, not the automation thread, which can sit in a send) gains two repairs per tick:
- chats with `routingPendingSince` older than the routing wait → `RoutingGate.settle`;
- chats with `status = BOT` that have no active run and had none within the routing wait → release as `ROUTE`.
  The check must not fire in the moment between a jump's old run ending and its next run starting, and
  `lastUpdateTime` cannot be used for it (`ChatPointer` updates do not touch it): judge by the chat's runs (none
  active, the newest ended longer ago than the wait) or by an explicit timestamp.

Both are idempotent and safe on more than one instance (the `findAndModify` in `settle`; a status-guarded update for
the release).

### 5.6 Who sees chatbot chats

- `InboxScope` gains `boolean bots` (the user holds `inbox:view-bot`).
- `covers(chat)`: a `BOT` chat is covered only when `bots` and the chat's number is one the scope owns
  (`ownedNumberIds`, which is every number at level `ALL`). Every other chat: as today.
- `criteria()`: (today's criteria AND status ≠ `BOT`) OR (`bots` AND status = `BOT` AND number owned).
- `ListScope` gains `BOT`: allowed only with `bots`; its criteria are status = `BOT` on owned numbers, and the
  request's `status` parameter is ignored for it. The other four lists never return `BOT` chats, whatever `status`
  is asked.
- The live stream needs no change beyond `covers`: a chat that turns `BOT` leaves the agents' lists and enters the
  Chatbot list of those who may see it, by the existing visible / not-visible handling.
- `ChatView.status` carries `BOT`.

### 5.7 The rule action leaves the list

- Console: the rule editor's "Add action" menu no longer offers "Assign chat". A rule that already has the action
  shows it, can edit it, reorder it and remove it; once removed it cannot be added back.
- Server: nothing is removed. `AssignChatHandler`, the validators and the views keep handling `ASSIGN_CHAT`.
- Record: a line under §1 of `docs/superpowers/project-rules.md` ("Assign chat is hidden from the rule actions since
  2026-11-18: chats reach people through the number's routing or a chatbot's hand-over node; the code stays"), and a
  short section in the README's inbox part describing the order (chatbot first), the status and the permission.

### 5.8 Console

- `api/inbox.ts`: `ChatStatus` gains `'BOT'`, `ListScope` gains `'BOT'`; `RoutedReason` texts for the two new reasons
  ("handed over by chatbot <name>", "after chatbot <name> ended").
- Chat list: a **Chatbot** tab beside Mine / Team / Unassigned / All for users with `inbox:view-bot`; the
  Open / Closed switch is hidden on it. Rows show the chatbot chip as today.
- Chat header: status label "With chatbot". Composer on a `BOT` chat: a one-line hint, "Replying takes this chat
  over from the chatbot".
- Chatbot editor: node label "Hand over to agent", mode select with "The number's normal routing" first, no output
  handle; the palette description says it ends the chatbot.
- Chatbot settings (platform defaults, brand, chatbot pages, through `LayeredSettingsForm`): two selects,
  "When the chatbot finishes" (Close the chat / Send to the team) and "When the customer stops answering"
  (Send to the team / Close the chat).
- Roles page: the new permission appears from the server's catalogue, with its description.

## 6. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB standalone (no transactions: single-document guarded updates and
`findAndModify`), React 18, PatternFly 6.5. Collections camelCase. "Template" means Meta templates only. Work in
`.worktrees/bot-first-routing` in both repos; test first; Prettier on touched files only; one Maven build at a time;
the full `./mvnw -q verify` once before a merge. No real message is sent without the user naming the recipient. Do
not change a shared role's permissions by hand on the demo: the new default reaches roles only through the seeder.

## 7. Left to the builder

Names of the port between inbox and automation; whether the release lives in `ChatPointer` or a new class beside it;
index names; how "replaced by another run" is passed to the release (a flag or a reason); the exact WARNING wiring in
`FlowValidator`; console component split and copy beyond the strings fixed above.

## 8. Verification

Backend, with embedded Mongo and `MutableClock`:
- no chatbot rule in the brand → a new inbound chat is routed during ingestion, as today (existing routing ITs pass
  unchanged);
- a chatbot rule that matches → the chat is `BOT`, unassigned, no `ROUTED` action, the team's round-robin cursor
  unmoved; a rule that does not match → routed after the worker's tick; an event released for a retry → routed then;
- a message that queues no event → routed at once; a worker that never runs → routed by the sweep after 30 s;
- two concurrent `settle` calls route once;
- release table of §5.3, one test per row, including jump and Replace (no `ROUTED` action in between) and the
  settings resolved through the three layers;
- hand-over node: each mode; ends the run `HANDED_OVER`; an edge after it is not followed and validates as a WARNING;
  `NUMBER_ROUTING` on a held chat keeps the holder; `NUMBER_ROUTING` refused on a rule action;
- after a hand-over (node, release or take-over) the customer's next message starts no chatbot and the chat keeps
  its place; after the chat is closed and the customer writes again, the chatbot starts;
- a chatbot started on a `CLOSED` chat leaves it closed; the customer's answer makes it `BOT`, not routed;
- an agent's reply into a `BOT` chat opens it, assigns it to them and ends the run; with `stopOnAgentReply` off the
  run goes on; close stops the run;
- scope: an agent without the permission cannot list, open or hear a `BOT` chat on their own number; with it they
  can, only on numbers they own; the four old lists never return `BOT` chats; the stream removes a chat from an
  agent when it turns `BOT`;
- `BOT` chat with no run → routed by the sweep;
- an existing rule with `ASSIGN_CHAT` still saves, publishes and runs; authority pins.

Console (vitest): Chatbot tab shown only with the permission and sends `scope=BOT`; status label and composer hint;
"Assign chat" absent from the add menu but an existing one is editable; node form, default mode and no outputs
(parity with the server's outputs fixture); the two settings in the layered form; `tsc`, lint, build.

Manual on 8089 (only when the user asks, and with a recipient they name): publish a rule that starts a chatbot, send
from the test phone, see the chat only under Chatbot, reach the hand-over node and see it routed; let one go silent
and see it reach the team.

## 9. Risks

- A person is assigned up to one tick (2 s) later than today in brands that have a chatbot rule; 30 s if the rule
  worker is down.
- With `whenCompleted = CLOSE`, a chat a chatbot finished is seen by nobody unless they look in Chatbot or Closed;
  brands that want a person to follow up end the flow with the hand-over node or set "Send to the team".
- Test runs move real chats into "With chatbot" (when no agent holds them) and release them by the same settings;
  a tester's abandoned test reaches the team's queue.
- After a hand-over the chatbot does not start again from a rule until the chat is closed. A brand whose agents
  never close chats gets the chatbot once per customer; closing chats is what starts a new conversation.
