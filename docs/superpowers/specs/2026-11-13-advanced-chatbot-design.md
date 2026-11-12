# Advanced chatbot: a flow of nodes run per chat, started by automations

Design, 2026-11-13. The user asked for an advanced chatbot and chose "skip the questions"; everything they said is
marked as theirs, the builder's own calls are marked **(my call)** throughout, per project rule. Plans:
`../plans/2026-11-13-advanced-chatbot-backend.md`, `../plans/2026-11-13-advanced-chatbot-console.md`.

The reference is RedLava's advanced chatbot: engine `/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp/src/main/java/com/redlava/whatsapp/chatbot/`,
screens `/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp-frontend/src/Components/ChatBot/AdvanceChatbot/`. The user's
words: take it "as hint/help only".

## 1. Intent

A brand draws a conversation as a flow of nodes on a canvas: send a message, ask a question, branch on the answer,
update the contact, hand the chat to a person. The flow runs on its own for each customer, over WhatsApp, one chat at a
time, and the team can watch it run and take over. Today the project has automations (one event, conditions, a short
list of actions, spec `2026-11-03-automations-design.md`) and nothing that waits for the customer and continues.

What the user fixed:

1. **The builder uses the same flow library as the reference project**: React Flow, `@xyflow/react` 12. The rest of
   the screen stays PatternFly, like every other console page.
2. **The OpenAI nodes of the reference (`open_ai`, `open_ai_v2`) are left for later.**
3. **No start node.** The reference's flows begin with a trigger node (keywords, template button, payment, webhook).
   Here a chatbot has no trigger of its own: **automations start it**, through a new "Start chatbot" action on a rule,
   so an event plus conditions decide when a chatbot runs. The flow begins at its entry node (section 4.3).
4. **The backend validates the chatbot and every node.** Each node type's executor validates its own node; the
   chatbot as a whole has its own checks (section 6).

Success looks like (my reading): a manager builds "Lead qualifier" on the Chatbots page (ask the area, ask the budget,
branch on the budget, set two attributes, add to the CRM, assign the chat), tests it on a contact they name, publishes
it, and adds "Start chatbot: Lead qualifier" to the existing welcome rule. From then on every new contact is walked
through it, the inbox shows the chatbot running on the chat, an agent can stop it and take over, and the Runs page
shows every conversation and where it ended.

## 2. Goals, non-goals, anti-goals

**Goals (v1)**
- A Chatbots page with a canvas editor (React Flow), a node palette, per-node settings, Draft and Published, a test
  on a named contact, version history, a runs log, and a live view of a run on the canvas.
- Eleven node types (section 4.2): Send message, Send template, Ask a question, Condition, Update contact, Do not
  contact, Assign chat, Add to CRM, HTTP request, Wait, Jump to chatbot.
- Variables: everything the flow knows (contact, chat, the event that started it, every node's output) usable in
  texts, conditions and requests as `{{path}}`.
- A new automation action "Start chatbot", and a new condition field "Chatbot is running" on the message event.
- The inbox shows a running chatbot on the chat and lets an agent stop it; an agent's reply stops it on its own.
- Validation on the server for every node and for the flow; the console marks the nodes with problems.

**Non-goals (v1)** (my call unless marked)
- OpenAI nodes (the user's: later). Also the reference's Script (GraalJS), Google Sheets, Email, Payment, Catalog /
  product set, Drip, Opt-in/out through WhatsApp's own block, webhook and integration triggers (IndiaMART, TradeIndia).
- A trigger node or keyword matching of any kind (the user's: events and actions start a chatbot). "A message
  containing *price* starts the price bot" is an automation rule with a text condition and a Start chatbot action.
- Insights dashboards and funnels: the list shows counts, the runs log shows everything else. A later task.
- Import and export of a chatbot as JSON, duplication, priorities between chatbots (there is no matching, so no
  priority), a global settings page (settings live on each chatbot), undo/redo and copy/paste on the canvas,
  auto-layout.
- Multi-user live editing over the socket. Saves carry a version number; a stale save is refused (409), as rules are.
- Page-tap tracking driving the flow: a customer who taps a button on a message page and then does not send the
  prefilled WhatsApp text has not answered (section 4.2, Send message).
- A Cloud API channel. Everything is shaped so one can be added (Cloud messages, Cloud webhook events), nothing
  transports to it.

**Anti-goals** (would make this a failure even if it works)
- A draft chatbot talking to a real customer.
- Two chatbots talking in one chat at once, or a chatbot and an agent talking over each other.
- A chatbot that never stops: every wait has an end (inactivity end, step cap, 30-day wait cap).
- A chatbot failure slowing or breaking message ingestion.
- A chatbot messaging a contact marked do-not-contact.
- A chatbot's own messages starting automations or another chatbot (section 5.5).

## 3. Starting a chatbot

### 3.1 The "Start chatbot" automation action (the user's: events and actions trigger a chatbot)
- A fourth action type on automation rules, `START_CHATBOT`, with the chatbot to start and **what to do when a
  chatbot is already running on that chat**: skip (default) or replace (my call on the choice; replacing ends the
  running one as Stopped, reason "replaced by <chatbot>").
- Offered on both events. The chat is the event's chat: the chat the message came in on, or the deal's chat on the
  deal's number (no chat: the action fails "no chat with this contact", like Send template).
- Only a **published** chatbot can be picked when the rule is published; the rule's publish check refuses a rule naming
  a draft or deleted chatbot. A published chatbot moved to draft later fails the action at run time ("chatbot is not
  published"), logged in the rule's run.
- A rule's test (section 7 of the automations spec) runs the action for real on the test contact, as Send template
  does: the chatbot really starts, on the test contact, marked as a test run.
- Guards, like Send template: never on a do-not-contact contact (skipped, logged); at most once per (event, rule)
  through the existing run key.
- The action starts the run and waits for it **at most the automation send timeout** (60 s) while it runs up to its
  first wait (my call: so the rule's run log usually shows whether the first message went out, without ever holding
  the event lease hostage). The step says "Started <chatbot>: waiting at <node>", "Started <chatbot>: completed",
  "Started <chatbot>: still running" when the bound is hit (the chatbot carries on by itself on its own pool), or the
  failure. The chatbot's own send retries (5.7) are the run's business, not the action's: the action never reports
  RETRYING.
- **Exactly one run per (event, rule)**: the run stores the event id and rule id with a unique index (test runs
  excluded), so when the worker re-claims an event (lease expired, or an earlier Send template step asked for a retry)
  the action finds the run it already started and reports its state instead of starting a second one (my call).

### 3.2 What the chatbot gets from the event
The event's data (automations spec §3) becomes the run's `event` variables: `event.message.text`, `event.message.type`,
`event.chat.new`, `event.number.label`, `event.deal.title`, `event.toLane.name` and the rest, exactly the fields the
rule's conditions can see. Plus `trigger.ruleId`, `trigger.ruleName`, `trigger.eventId`, `trigger.at` (my call,
after the reference's `_trigger`). The console's variable picker lists them per event kind.

### 3.3 Starting from the editor (the test)
"Test" on a draft starts the chatbot at once on a contact the person picks (section 7): no event needed. Its `event`
variables are empty and `trigger.ruleName` is "Test"; conditions on `event.*` see empty values. If that contact's chat
already has an active run of **another** chatbot (a published one, or another draft's test), the test is refused with
the running chatbot's name and a "Stop it and start the test" button (my call: a draft never silently ends a live
run). A second test of the same chatbot stops the first (section 7).

### 3.4 The "Chatbot is running" condition
A new yes/no field on the message event, `chat.chatbotRunning`, so a rule can say "if no chatbot is running" (my
call). Without it, every reply a customer sends to the chatbot is a message event that could start the welcome rule
again. The value is **captured when the event is written**, like `chat.new`: it means "this message arrived while a
chatbot was running on the chat" (my call). A live read at the rule's turn would race the chatbot pool: the customer's
last answer ends the run, and a rule reading "not running" a moment later would start it again, the very loop the
field exists to prevent. The action's own skip/replace check (3.1) is atomic instead: starting a run claims the chat's
active-run pointer only when it is empty (or replaces it, when told to), so two starts cannot both win.

## 4. The chatbot

### 4.1 What a chatbot is
- Name (unique in the brand, 1–60 characters), optional description.
- The **flow**: nodes and edges as the canvas draws them, kept verbatim (positions and viewport included) so the
  editor reopens as it was. Node ids are the canvas's ids; each node also has a **name** (`[A-Za-z0-9_]`, 1–40
  characters, unique in the flow) that is how variables refer to it: `{{AskBudget.answer}}`.
- **Settings** (per chatbot, with defaults; no global layer, my call):
  - Inactivity reminder: off, or after N minutes send a message (default off; 1–1440 minutes).
  - Inactivity end: after N minutes of silence the run ends (default 120; 5–2880 minutes), optionally sending a message.
  - Invalid replies: how many times to re-ask when the customer's reply does not match (default 2; 0–5), the re-ask
    message (default "Sorry, I didn't get that. Please choose one of the options.") and the give-up message (optional).
  - Stop when an agent replies (default on).
- **Status**: Draft or Published, as automation rules: a published chatbot cannot be edited, "Move to draft" stops new
  starts at once. Runs already in progress finish on the flow version they started with (my call; a run keeps its
  version number and reads that version's flow).
- Versions: every save of a draft whose nodes or edges changed appends a version; the newest 50 are kept (my call).
  Restore makes the restored flow the current one, saved as a new version.
- Belongs to a brand, applies to all its numbers (the number is the chat's).

### 4.2 Node types
Every node has one incoming side and one or more named **outputs** (edges leave from an output). An output with no
edge ends the run as Completed when the flow gets there. All texts accept variables (section 4.4). Each node writes
its output under its name in the run's variables, so later nodes can use it.

| Node | Settings | Waits? | Outputs | Writes |
|---|---|---|---|---|
| **Send message** | A WhatsApp Cloud API message object (my call, after the user's standing rule that outbound speaks Cloud): `type` text, image, video, audio, document, location, or interactive (button, list, cta_url, location_request_message). Media by `{id}` from a cloud media upload made through the chatbot's own upload endpoint (section 8.3), caption optional. Composed with the inbox's interactive composer form. | Only when the message has reply buttons or a list and the person connected at least one of their outputs. | `next`; for buttons one output per button, for a list one per row, plus `other` (any other reply, after the invalid-reply retries are used up). | `reply.id`, `reply.title`, `reply.text` |
| **Send template** | One of the brand's saved templates, with fixed values for its agent placeholders, as the automation's Send template action. | No. | `next` | nothing |
| **Ask a question** | The question (a text message), the **answer type**: text, number, email, phone, date (dd/mm/yyyy or yyyy-mm-dd, my call), location (a WhatsApp location), file (any, or image / video / audio / document), **save as** (variable key, defaults to the node name), retries (0–5, default 2), the re-ask message, optional give-up message. Optional: also save the answer into a contact attribute (my call: the most common reason to ask). | Yes. | `next` (a valid answer), `noAnswer` (retries used up; unconnected: the run ends). | `answer` (text, parsed number, the email, digits, ISO date, `{latitude, longitude}`, or `{messageId, mimeType, filename, size}` for a file) |
| **Condition** | Ordered cases; each case is a condition group over variables using the automations' tree and operators (is, is not, contains, starts with, greater than, between, is empty, …; text compares ignore case), joined all/any, groups nestable; the run's variables are flattened by path into the evaluator's map. | No. | One output per case plus `otherwise`. | `matched` (the case's label) |
| **Update contact** | Set attributes (attribute, value with variables), clear attributes, add labels, remove labels, set the name if blank. | No. | `next` | nothing (the `contact.*` variables are refreshed) |
| **Do not contact** | Mark, or clear, the contact's do-not-contact flag (reason `CHATBOT`). | No. | `next` | nothing |
| **Assign chat** | Exactly the automation action's choices: an agent (with the "when away" choice), a team's queue, a team round robin. | No. | `next` | `assignedTo` |
| **Add to CRM** | Exactly the automation action's settings: pipeline, lane, deal title pattern. Already in the CRM: nothing happens. | No. | `next` | `dealId`, `created` (true/false) |
| **HTTP request** | Method (GET, POST, PUT, PATCH, DELETE), URL, headers, body (text or JSON), all with variables; timeout 10 s, response capped at 1 MB (my call). | No. | `success` (2xx), `failure` (anything else, including a timeout). | `response.status`, `response.body` (parsed JSON when the response is JSON, else text), `response.headers` |
| **Wait** | A duration: minutes, hours or days, 1 minute to 30 days (my call). Customer messages during a wait are ordinary inbox messages; the chatbot ignores them. | Yes (a timer). | `next` | nothing |
| **Jump to chatbot** | Another chatbot of the brand (published; in a test run a draft is allowed, as the reference does). Variables carry over. | No. Ends this run as `Jumped`. | none | nothing |

Not nodes: there is no End node (an unconnected output ends the run) and no Start node (the user's).

**Send message, over the bridge.** The message goes out through the existing Cloud send path: text, media and location
natively; interactive messages as a link to a message page (spec `2026-11-12-cloud-messages-design.md`). The customer's
reply arrives as a normal WhatsApp text; the node matches it to a button or row title the way message pages do
(`bare(title) == bare(text)`), and over a Cloud channel later by `button_reply.id` / `list_reply.id`. A reply that
matches nothing is an invalid reply: the chatbot re-asks (the settings' message, then the message again) up to the
retries, then takes `other`; `other` unconnected ends the run as Completed with reason "no matching reply" (my call).
A cta_url or location_request message does not wait: its outputs are just `next`.

**Media and location Cloud types** are added to `CloudMessage` (`image`, `video`, `audio`, `document`, `location`, with
Meta's shapes: `{id, caption?, filename?}` and `{latitude, longitude, name?, address?}`) and sent natively by
`InboxSendService.sendCloud`, which today handles only `text` and `interactive` (my call: one message shape for the
node, the Cloud API endpoint gains the same types for free). `{link}` media stays rejected.

### 4.3 The entry node (no start node, the user's)
The flow begins at the one node that no edge points to (my call). Validation requires exactly one such node and that
every node is reachable from it; a flow whose nodes all have an incoming edge (a pure loop) has no entry and is refused.
The canvas marks the entry node with a small "Entry" tag so the person can see where the flow begins.

### 4.4 Variables
Mustache `{{path}}`, as templates and message pages already use (JMustache). What a run knows:
- `contact.name`, `contact.phone`, `contact.attributes.<key>`, `contact.labels` (names), `contact.doNotContact`.
- `chat.id`, `chat.status`, `chat.assignedUser`, `chat.assignedTeam`, `number.label`, `number.phone`.
- `event.*` and `trigger.*` (section 3.2).
- `<NodeName>.<field>` for every node that has run (table above). A node that runs twice (a loop) overwrites.
- `now` (ISO time) (my call, for HTTP bodies).
An unknown path renders as empty text (the renderer is lenient at run time); the validator warns about paths that
name no node and no known field (section 6). Text goes into WhatsApp messages as plain text (no HTML escaping);
into JSON bodies JSON-escaped, so a quote in an answer cannot break the request (the reference's `renderTemplateForJson`).
Renaming a node in the editor rewrites `{{OldName.` to `{{NewName.` across the flow (my call, after the reference).

## 5. Running

### 5.1 A run
One **run** per start: the chatbot and its version, the chat, the contact, the number, who started it (the rule and
event, or the tester), status, the current node, the variables, a list of **steps** (node, started, finished, outcome,
a one-line summary, the node's output, the error), the test flag, and timers (`remindAt`, `endAt`, `resumeAt`).
Statuses: Running (a node is executing), Waiting (for a reply), Sleeping (a Wait node), and the final ones: Completed,
Ended (inactivity), Stopped (by an agent, by Move to draft, or replaced), Handed over (an agent replied), Jumped,
Failed (a node error or the step cap). A version that an active run points to is never pruned by the newest-50 rule.

**One active run per chat** (anti-goal): starting while one is active follows the action's skip/replace choice; the
chat keeps a pointer to its active run so the inbox, the condition field and the listener find it in one read.

### 5.2 The loop
From the entry node, run node after node: a node that does not wait finishes and the loop follows its output's edge;
a node that waits stores the run as Waiting and returns. A reply (5.3) resumes the waiting node, which either finishes
(and the loop continues) or stays Waiting (re-ask). A Wait node stores Sleeping with `resumeAt`; the ticker resumes it.
Each step is appended as it finishes, with its output, so the log shows the path and the Runs page can replay it.
A node that throws fails the run (Failed, the error in the step) (my call: no silent skipping; the reference does the
same). Cap: 200 steps per run (my call), then Failed "too many steps".

Runs execute off the ingestion and request threads, on their own small pool (my call: the inbox must never wait on a
chatbot; a send over the bridge can take seconds). A run is claimed before it is touched (`findAndModify` Waiting →
Running with a lease, as automation events are), so two replies in the same instant, or a reply during a resume,
cannot run one node twice: the one that fails to claim leaves the message as a plain inbox message and the chatbot
ignores it (my call). A run left Running past its lease (the app stopped mid-node) is marked Failed "interrupted" by
the ticker: never re-run, because the node may have sent (the automations' Interrupted ruling).

### 5.3 Replies
A listener on the inbox's message event (IN, inserted, from the contact), beside the automation and campaign listeners:
if the chat has an active run in Waiting, hand the message to it. Replies are the automations' real message types
(text, media, sticker, location, contact card, poll) plus the `button` and `interactive` replies automations ignore;
reactions, edits, calls, poll votes and notices are neither replies nor invalid replies, they are ignored. Nothing
happens for a chat without a run, so the cost for every other chat is one field read on the chat.

### 5.4 Agents and the chatbot
- **An agent replies**: an outgoing message sent from the console (source Console) in a chat with an active run ends
  the run as Handed over, when the chatbot's "Stop when an agent replies" is on (default). Messages the chatbot itself
  sends, campaign sends and automation sends do not count. **A message typed on the phone does not count either** (my
  call): the device echoes every message the app itself sends as a WhatsApp Web message before the send result comes
  back and the row is re-labelled, so a listener on phone-typed messages would mistake the chatbot's own first message
  for a person and hand the chat over. Noted in known-limits; an agent on the phone can stop the bot from the inbox.
- **Stop chatbot**: the inbox chat header shows "Chatbot: <name> · waiting at <node>" while a run is active, with a
  Stop button (permission `inbox:send`, my call: whoever may write in the chat may stop the bot). Stopped runs say who
  stopped them.
- Assign chat and Add to CRM nodes act as the brand, with the reason "by chatbot: <name>", as automations do.
- Messages the chatbot sends are stored with a new message source **Chatbot** ("Chatbot: <name>" on the bubble, my
  call), beside Console, Campaign and Automation. They never claim or reopen the chat and never change its status,
  like an automation send.

### 5.5 No loops between features
Chatbot sends are outgoing and start no automation event. A reply to a chatbot *is* an incoming message and does start
the message event (that is the inbox's rule; the chatbot cannot hide it); the "Chatbot is running" condition (3.4) is
how a rule stays out of the way. A chatbot started by a rule can Jump to another chatbot but cannot start a rule.
Jumps are capped at 5 in a row for one chat (my call), then Failed "too many jumps".

### 5.6 Inactivity and timers
A ticker (every few seconds, like the automation worker) handles three timers on active runs: `remindAt` (send the
reminder once, then clear), `endAt` (end the run as Ended, send the end message if set), `resumeAt` (Wait nodes).
Every customer reply pushes `remindAt` and `endAt` forward by the settings' intervals. A run that is Sleeping has no
inactivity timers (the wait is the chatbot's choice). Timers are stored on the run, so a restart loses nothing.

### 5.7 Sending rules a chatbot follows
A chatbot sends 24x7 (automations do; the user set sending hours for campaigns only). It never messages a contact
marked do-not-contact: a run reaching a Send message or Send template node for such a contact ends as Stopped,
reason "do not contact" (my call). A send that never reached the extension (device offline, WhatsApp Web not ready)
is retried three times over a few minutes with the run kept Running under its lease (my call: the automations' retry
rule); still failing, the run fails with that reason. A send the extension got but did not answer is Interrupted
territory: the run fails, never re-sent.

## 6. Validation (the user's: on the server, per node in its executor, and for the whole chatbot)

- **Where**: each node type has a server-side executor; the executor implements `validate(node, flow, brand)` for its
  own settings, and a flow-level validator checks the graph. Both produce **problems**: `{nodeId, nodeName, field,
  message, severity}` with severity ERROR or WARNING (my call: warnings so a half-finished flow can be saved and the
  person still sees what is wrong).
- **When**: on every save, the response carries the problems and the console marks the nodes (saving is not refused
  for problems: a draft may be half built, my call; the reference refuses the save, which makes work-in-progress
  unsavable). A save is refused only for a flow the server cannot read: unknown node type, two nodes with one id, an
  edge to a missing node (400, standard validation advice). **Publish is refused while any ERROR exists**, with the
  same problems in the error body, and the console opens the first offending node.
- **Flow checks** (ERROR unless marked): at least one node; node ids unique; names well-formed and unique; edges from
  real outputs of their source (a button output that no longer exists on the message is an error) to real nodes; at
  most one edge per output; exactly one entry node; every node reachable from the entry (WARNING: an unreachable node
  never runs); a variable path naming no node and no known field (WARNING); a Jump to itself (ERROR).
- **Per node**, the executor's own rules, for example: Send message runs the Cloud message validator (Meta's limits,
  media id exists in the brand's cloud media, `{link}` rejected) and requires an output edge for at least one button
  when the message has buttons (WARNING otherwise: the bot would wait for nothing); Ask a question requires the text,
  a valid save-as key, retries 0–5, and an existing attribute when one is chosen; Condition requires at least one case
  with every rule complete and operators fitting the operand; Update contact requires at least one change and existing
  attributes and labels; Assign chat and Add to CRM reuse the automation publish checks (agent, team, pipeline, lane
  exist; CRM on); Send template requires an existing, unarchived template with every required agent placeholder
  valued; HTTP request requires `http(s)://`, a method, a JSON body that parses when the content type is JSON, headers
  without line breaks, and refuses URLs that resolve to private, loopback or link-local addresses at run time as well
  (my call, for the server's own safety); Wait requires 1 minute to 30 days; Jump requires an existing chatbot of the
  brand other than itself.
- **Live checks**: a published chatbot whose template is archived, attribute deleted or target chatbot moved to draft
  fails that node at run time with the reason in the step; nothing re-validates published chatbots on a timer (my
  call).

## 7. Testing a draft (my call, modelled on the automations test)
- "Test" in the editor opens a dialog: pick the test contact (the automation test's contact search, by name or
  number), then Start. The chatbot starts at once on that contact's chat on the chosen number, as a **test run**. A
  draft can be tested only after its save has no ERROR problems; a draft never runs on a live event.
- The canvas shows the run live: the current node pulses "Waiting", finished nodes show a tick, a failed node a cross
  with its error, and the run's variables appear in a side panel the person can copy paths from. **The editor polls the
  run every 2 seconds while the live view is open** (my call: the inbox stream is scoped to the chats the viewer may
  see and needs `inbox:view`; a chatbot manager may hold neither, and the reference polls too). The inbox's own chip
  (5.4) rides on the existing chat stream, since the chat carries its active-run pointer.
- The messages really go out, to the test contact only; the test contact's own replies drive the test. Published
  chatbots and rules are not paused for the test contact.
- One test run at a time per chatbot: starting another stops the first. Test runs are kept in the Runs page, marked
  Test. "Stop" ends it.
- Project rule: **never send a real message without the user naming the recipient.** The acceptance check needs the
  user to name the test contact.

## 8. Screens

### 8.1 Chatbots page (own entry in the side menu, after Automations)
- **List**: name, Draft / Published, runs in the last 7 days, active now, last run, updated; New chatbot (name
  dialog, then the editor), Edit, Runs, Delete (drafts only, with confirmation, my call as for rules).
- **Editor** (`/chatbots/:id`): the canvas fills the page under a header bar: the name (inline edit on a draft), the
  status badge, Save (and Ctrl/⌘+S), Publish or Move to draft, Test, Runs, Versions, Settings, and the problem count
  ("3 problems") that opens a list; clicking a problem centres the canvas on the node and opens its settings. The
  **palette** is a drawer (opened by a "+ Add node" button, or by dropping a connection on empty space, which adds
  the picked node there and connects it, after the reference) with a search box and the eleven nodes with one-line
  descriptions; click to add at the viewport centre, drag to place. **Node settings open in a right-hand drawer**
  (PatternFly Drawer, the CRM record drawer's shell; my call, over the reference's floating modals) with the node's
  form, and the variable picker (`{{` autocomplete and an "Insert variable" menu listing contact, chat, event, and
  upstream nodes' outputs) on every text field. Each node on the canvas shows its icon, name, a two-line preview of
  what it does, its outputs as labelled handles on the right (one edge per output; the handle refuses a second), a
  problem badge, and a kebab with Duplicate (a copy at +40/+40 with a unique name), Rename and Delete. Edges are
  deletable by selecting them and pressing Delete, or from a small × on hover. Minimap, zoom controls, snap to grid,
  fit view on open. A published chatbot opens read-only (nodes can be dragged to read the flow, nothing is saved) with
  the "Move to draft" alert the rules editor shows. Leaving with unsaved changes asks first.
- **Runs** (`/chatbots/:id/runs`): newest first: contact, number, status, started, ended, where it ended (last node),
  started by (rule or Test); filter by status and date; open a run: the steps in order with time, outcome and
  summary, the variables as a tree, and "Show on canvas" which opens the editor read-only with the run drawn on it
  (the same live view as a test, frozen).
- **Versions**: a drawer listing versions (number, saved when, by whom); Preview draws the version on the canvas
  read-only with a "Restore" or "Exit preview" bar.
- **Settings**: a modal with section 4.1's settings.

### 8.2 Elsewhere
- **Automations editor**: the "Start chatbot" action with a chatbot picker (published chatbots first, drafts greyed with
  "draft"); the "Chatbot is running" condition field on the message event. Run log steps show "Started <chatbot> …".
- **Inbox**: the chat header chip with Stop (5.4); a chatbot's bubbles say "Chatbot: <name>"; the chat list shows a
  small robot icon on chats with an active run (my call).

### 8.3 Media and previews for the editor (my call)
The editor has no number and no chat, and its users hold `chatbot:manage`, not necessarily `inbox:send`. So the chatbot
API has its own two helpers that call the same services as the inbox's: `POST /chatbots/{id}/media` (upload into the
brand's cloud media, same limits and MIME rules) and `POST /chatbots/{id}/preview` (the message page renderer's inert
preview HTML for a Cloud message). The cloud media sweeper, which today deletes files older than 24 h unless a message
page references them, also keeps every file referenced by a chatbot's current flow or any kept version.

## 9. Permissions (my call, after automations)
- `chatbot:view`: the Chatbots page, chatbots, runs and versions.
- `chatbot:manage`: create, edit, save, test, publish, move to draft, restore, delete, settings.
- Both default to Customer admin and Brand manager; any role can be given them on the Roles page. Stopping a run from
  the inbox needs `inbox:send` on that chat. A chatbot acts as the brand (assigns to any agent or team, sends on any
  of its numbers), as rules do.

## 10. Constraints (project rules)
- Read `docs/superpowers/project-rules.md` first. Work in `.worktrees/advanced-chatbot` in each repo, test first, a
  fresh reviewer on the whole branch, one fix pass, the full `./mvnw -q verify` before a merge. Merge and push only
  when asked.
- Mongo collections camelCase (`chatbots`, `chatbotVersions`, `chatbotRuns`). Channel-neutral names. Files in GridFS.
  The CRM module is reached through `crm.platform` ports only.
- Nothing new is called "Template" except the Send template node, which sends the project's saved templates.
- **Never send a real message without the user naming the recipient.**
- Console: `@xyflow/react` is the one new dependency (its stylesheet needs `node_modules/@xyflow/react` added to the
  webpack `stylePaths`); the editor is lazy-loaded so the canvas library stays out of the main bundle; pure models
  (flow model, node forms, problems routing) carry the unit tests, since jsdom has no layout.

## 11. Left to the builder
- Data model details (how the flow is stored, how typed node settings are parsed, indexes), the run claim's lease
  length, the ticker interval, the executor pool size, the HTTP client (Spring's `RestClient` is already on the
  classpath) and its address guard, how `bare()` matching and `button_reply.id` matching share one code path, how the
  chat's active-run pointer is kept consistent with the run's status.
- The console's flow model (React Flow nodes/edges ↔ the saved flow), how problems map onto nodes and fields (the
  automations editor's `conditionErrors` / `renderedPaths` routing is the pattern), the variable picker's static
  schema per node type and per event kind, the live-run overlay, and exact wording and icons.
- Whether the Send message node reuses the interactive composer's form component as is or a trimmed copy.
- How an agent's reply is detected (a listener on the outgoing `MessageUpserted` by source).
