# Automations: events, rules and actions

Written 2026-11-03 from a brainstorming session with the user. What the user said is marked as theirs; my calls are
marked **(my call)**. The reference is RedLava's automation engine:
`/Users/nagesh/RedLava/SCM/CRM/whatsapp/docs/superpowers/specs/2026-08-13-automation-event-action-engine-design.md`
(event, then conditions on the event's data, then ordered actions) and the CRM's deal events
(`redlava-crm/.../automation/`, payload `CrmDealEventPayload`).

## 1. Intent

The user's words: it is "events, rules and actions", not "deal automation events". A brand sets up rules so that things
happen without anyone on the team touching them. The moment that drives it (the user's): **a new contact messages, they
get the welcome template, and they are added to the CRM as a lead.** The second (my suggestion, the user agreed): **a
deal moves into a chosen lane, and its contact gets a chosen template**, for example a thank-you on "Won" and a follow-up
on "Proposal sent".

Success: a manager builds the welcome rule on the Automations page, tests it on their own phone without touching live
chats, publishes it, and from then on every first-time contact is welcomed, assigned and in the CRM, with a run log
showing what happened.

## 2. Goals, non-goals, anti-goals

**Goals (v1)**
- Two events: a live incoming WhatsApp Web message; a CRM deal moving to another lane.
- Rules on those events with conditions, following the reference (groups of conditions joined by all/any).
- Three actions: send a template; assign the chat; add the person to the CRM as a lead.
- Draft and Published, a step-by-step test for drafts, a run log, permissions.

**Non-goals (v1)** (my call unless marked)
- Kafka or any other message broker (the user's choice, 2026-11-03: not deployed yet, to run on AWS with very little
  spend). Events go through a queue in MongoDB (section 4.3); it sits behind one interface so Amazon SQS or Kafka can
  replace it later without touching the rules.
- Other events (contact created, lead intake, campaign events), other actions (chatbots, labels, attributes, schedule
  a message, wait or delay steps, webhooks).
- Free-text messages: templates only (the user's).
- Sending hours for automations (the user's: 24x7; campaigns keep their hours).
- Queueing sends while the extension is offline for long. (A send that failed because the extension was offline,
  WhatsApp Web was not ready or the extension did not answer **is** retried a bounded number of times: the user's,
  2026-11-03, after a look at the reference. Any other failure is not retried. Narrowed the same day by a controller
  ruling: only a send that never reached the extension is retried; one the extension got but did not answer is
  Interrupted, see 5.1.)
- Merging a person's two chats (`@c.us` and `@lid`): the user will take it after this task.
- Rule version history.

**Anti-goals** (would make this a failure even if it works)
- A draft rule acting on live traffic.
- One rule messaging a person twice for one event, or rules triggering each other in a loop. (Two different rules
  matching the same event may each send their template: the user's choice, section 4.4.)
- Messaging a contact marked do-not-contact.
- An automation failure slowing or breaking message ingestion, routing, or a deal move.
- "Fetch history" starting rules: a fetch must never welcome old contacts.

## 3. Events

Both events are per brand (workspace). **Rules apply to all numbers of the brand** (the user's).

### 3.1 A message comes in on WhatsApp Web
- Live incoming messages only: direction IN, from `message_create`. Messages we send (console, campaign, phone,
  automation) never start it, and neither does anything stored by "Fetch history" (that path publishes no events today).
- **Only real messages count** (the user's): text, photo, video, audio and voice notes, documents, stickers, location,
  contact cards, polls. Calls (`call_log`), reactions, poll votes, edits, deleted-message notices, WhatsApp's own notices
  and undecryptable (`ciphertext`) messages never start a rule.
- It runs after the inbox has finished with the message, routing included, so an Assign chat action sees, and replaces,
  what routing did.
- **Event data** (what conditions can test, and what the run log and the test page show):
  - `chat.new`: true when this is **the first real message** this person's number has ever sent this WhatsApp number of
    the brand (the user's definition). A chat that already holds a real message from them, for any reason (open,
    closed, or brought in by a fetch), is not new. A chat that so far holds only a call, a reaction or a notice from
    them is still new: their first real message makes `chat.new` true (the user's, answer 3a). How "already holds a real
    message" is known is the builder's (a stored marker on the chat is likely cheaper than a query).
  - `chat.status`, `chat.assignedTo` (as it stands after routing).
  - `message.text` (body or caption), `message.type` (text, image, …).
  - `number` (which of the brand's WhatsApp numbers received it: id and label).
  - `contact.name`, `contact.phone` (null when WhatsApp hides it), `contact.inCrm` (has a CRM contact on this number),
    `contact.doNotContact`.
- Known limit (user agreed): the same person can be two chats (`@c.us` and `@lid`), so they could count as new twice.

### 3.2 A deal moves to another lane
- Fires when a deal's stage changes: dragged on the board, changed in the deal form, or closed as won or lost (Won and
  Lost are lanes, `StageType`). Also when a deal moves to another pipeline (its lane changes).
- **Not** fired (my call): bulk moves the CRM makes when an admin deletes a stage or a pipeline
  (`DealPlacement.relocateStage` / `relocatePipeline`), deals created in a lane (creation is not a move), CSV imports.
- **Event data**, after the reference `CrmDealEventPayload`: `deal.id`, `deal.title`, `pipeline` (id, name),
  `fromLane` and `toLane` (id, name, type OPEN/WON/LOST), `deal.status`, `deal.amount`, `deal.currency`,
  `deal.owner` (id, name), `deal.lostReason`, and the deal's contact: `contact.name`, `contact.phone`,
  `contact.doNotContact`, and its chat on the deal's number (`chat.id`, or none).
- The CRM module may not import the host (project rule): it reports the move through a new `crm.platform` port, for
  example `DealObserver.laneChanged(...)`, with its adapter in `app.crm`. The port is called after the move is saved
  and never fails the move.

## 4. Rules

### 4.1 What a rule is (after the reference)
- Name, optional description.
- **Event**: one of the two above.
- **Conditions**: a group joined by "all of these" or "any of these"; groups can hold groups (reference
  `ConditionGroup`). No conditions means every event of that kind matches.
- **Actions**: an ordered list (section 5).
- **Status**: Draft or Published (section 6).
- **Priority**: optional whole number (the user's).
- Belongs to a brand; applies to all its numbers.

### 4.2 Conditions
- A condition is a field from the event data, an operator, and a value.
- Fields offered per event (my call, from section 3):
  - Message event: Chat is new / is existing; Message text; Message type; Number; Contact is in the CRM / is not;
    Contact name.
  - **Message type** is picked with checkboxes (the user's): All, or any of Text, Photo, Video, Audio / voice note,
    Document, Sticker, Location, Contact card, Poll. Stored as "is one of" over those kinds.
  - Lane change: Pipeline; Moved into lane; Moved from lane; Lane type (Won, Lost, open); Deal amount; Deal owner.
- Operators, from the reference vocabulary (`equal_to`, `contains`, `startsWith`, `between`, …), in plain words in the
  screen: is, is not, is one of, contains, does not contain, starts with, ends with, is empty, is not empty, greater
  than, less than, at least, at most, between (ends with, at least and at most added at the user's choice on
  2026-11-03; the reference's regex "matches" is left out as a trap for non-technical users). Text comparisons ignore
  case (my call).
- Every event, field and action in the editor carries a one-line description and an example value, as the reference's
  pickers do (the user's, 2026-11-03).
- Chat is new and Contact is in the CRM are yes/no fields; the screen shows them as "Chat is new" / "Chat is existing".

### 4.3 The event queue (the user's choice: a MongoDB collection, not Kafka)
- When a live message comes in (section 3.1) or a deal moves (section 3.2), the app writes one **event document** to
  `automationEvents`: brand, event kind, the event data as it was then, status PENDING, created time. Only brands with
  at least one published rule of that event get every event written; a brand with only a test waiting gets the test
  contact's events (my call; keeps the queue and the history empty for everyone else).
- A worker inside the app claims the oldest PENDING event with a time-limited lock (`findAndModify` to RUNNING with a
  lease, as campaign recipients and history import chats are claimed today), runs the rules (section 4.4), and marks it
  DONE, or FAILED after a few attempts. An event whose lease ran out (the app stopped mid-way) is claimed again.
- **No double sends**: each (event, rule) run is stored once under a unique key in the run log, so an event processed
  twice runs each rule once.
- **The queue stays small and nothing is forgotten** (the user's: "I want to keep track what when happened"). A
  processed event (DONE, or FAILED after its last attempt) is **moved** to `automationEventHistory`: inserted there
  with its outcome and finish time, then deleted from `automationEvents`. The move is safe to repeat: the history
  document keeps the event's id, so a crash between the two writes leaves at most a duplicate insert that the unique id
  refuses, and the next pass finishes the delete. `automationEvents` therefore holds only PENDING and RUNNING events.
- **Nothing expires** (the user's): neither the event history nor the run log has a TTL. Both are small documents (an
  event is the data in section 3, a run is a line per action); a busy brand with a few hundred events a day adds a few
  MB a year. A retention setting can come later if a customer needs one.
- History is indexed by brand and time, and by rule, so the Runs page and "what happened to this contact" can read it.
- Writing the event comes after the message or the move is saved and never fails it. A crash in the instant between
  the two loses that one event (my call: accepted for v1; noted for the reviewer).
- The worker runs off the ingestion and request threads, so a slow rule never slows the inbox or a deal move. Several
  app servers later can share the queue: the lease stops two of them taking the same event.

### 4.4 Which rules run, and in what order
- On every event, **every published rule of the brand for that event whose conditions match runs** (the user's), and
  each sends its own template: there is no cap of one template per event across rules (the user's, answer 2b). Avoiding
  overlapping rules is up to whoever writes them.
- Order (the user's, my reading): rules with a priority first, largest number first; then rules without one, in list
  order. Equal priorities run in list order. **List order is creation order** (my call; no drag to reorder in v1).
- Each rule's actions run in their listed order. A failed action is logged and the rule goes on to its next action
  (my call; the user said "looks right" to it).
- Rules run one after another for an event, so a later rule sees what an earlier one did. **Conditions are checked on
  the state as it is when the rule's turn comes** (my call): before each rule the chat's assignment and whether the
  contact is in the CRM are read again; the event's own data (the message, `chat.new`, the lanes) stays as it was.

## 5. Actions

### 5.1 Send template
- Sends one template from the Templates page (**templates only**, the user's), its placeholders filled (my call on
  how, the user asked for the name filled in):
  - Contact placeholders (`{{contact.name}}` and the other contact fields and attributes) from the contact, as a
    console send fills them, else their fallback.
  - Placeholders an agent types at send time (kind AGENT, for example a plain `{{name}}` or `{{order_id}}`) get a
    **fixed value set in the rule**, as campaigns do (`renderForCampaign`), else their fallback.
  - Publishing is refused while a required placeholder has neither a value nor a fallback; one still empty at run time
    (a contact field with no value and no fallback) fails the action with that reason.
  - Contact card templates are not offered (the console cannot send them either).
- Goes to the event's chat: the chat the message came in on, or the deal's chat on the deal's number. A deal with no
  chat fails the action ("no chat with this contact").
- **24x7**, no sending hours (the user's). Guards (the user's):
  - Never to a contact marked do-not-contact: the action is skipped and logged as such.
  - At most once per event per rule: each event has an id, and the run of (event, rule) is stored with a unique key, so
    a repeated or replayed event cannot send twice (my call on how).
- The stored message shows who sent it as **"Automation: <rule name>"** (a new message source next to Console and
  Campaign). It does not claim or reopen the chat for anyone and does not change its status (my call, like a campaign
  send).
- The device must be ready. The send is tried again after a short wait only when it never reached the extension
  (extension offline or number disabled, WhatsApp Web not ready, or the request could not be handed over), up to three
  attempts in all; still failing, the action fails with that reason. When the extension got the request but did not
  answer in time, or disconnected during it, the message may have gone, so the step is marked Interrupted and is never
  sent again (a controller ruling of 2026-11-03: never messaging twice outranks the retry). Other failures (an archived
  template, a placeholder without a value, WhatsApp refusing the message) are not retried. While a send waits for its
  retry the rule's later actions wait too, so the actions keep their order.

### 5.2 Assign chat
- The dialog the user asked for, three choices:
  - **An agent.** If the agent is away or cannot take chats when it runs: either leave the chat with them, or put it
    in the queue of **the team that owns the chat's number** (leave it with them when the number has no owning team)
    (the user saw this choice in the mockup; my addition and my call on which team).
  - An agent who cannot see the chat's number (not in its owning team and without `inbox:view_all`) cannot be given
    the chat: the action fails with that reason (my call). The editor warns that a rule naming one agent applies to all
    numbers.
  - **A team, to its queue**: the chat waits unassigned in the team until someone takes it.
  - **A team, round robin**: the next available member in turn, sharing the team's round-robin turn with the inbox's
    own routing; nobody available, the team's queue.
- **The rule's choice wins over the inbox's automatic routing** (the user's): whatever routing did is replaced.
- Recorded on the chat like routing is, with the reason "by automation: <rule name>", so the chat's Routed line says so.
- Offered on both events (my call): on a lane change it assigns the deal's chat.

### 5.3 Add to CRM
- Adds the person as a CRM contact with a deal, like the inbox's "Add to CRM" button, without anyone clicking.
- Settings (my suggestion, accepted): a **pipeline** and a **lane** (empty: the brand's default pipeline and its first
  lane); a **deal title** pattern, for example `{{name}} – WhatsApp`, the number when there is no name.
- **Owner**: whoever the chat is assigned to when the action runs, else none (an unowned deal on the board). Put Assign
  chat before it and the owner follows the assignment.
- Already in the CRM on this number: does nothing (logged as "already in the CRM"), so a rule never makes two deals for
  one person.
- Origin "Incoming chat" with the chat and number, captured by the automation.
- The brand's CRM switched off: the action fails ("CRM is off for this brand").
- Offered on the message event only (my call: on a lane change the person is already in the CRM).

### 5.4 No loops
Actions start no events (my call): a sent template is outgoing, an assignment is not a message, and a deal created by
Add to CRM is created, not moved. So no rule can trigger another.

## 6. Draft and Published (the user's)
- A new rule starts as a **Draft**. A draft never runs on live events; only a test runs it (section 7).
- **Publish** makes it live. **Move to draft** stops it at once. Both directions are allowed at any time.
- **A published rule cannot be edited**: move it to draft, edit, test, publish again (the user's).
- Publishing checks that the rule is whole (my call): a name, at least one action, every template, agent, team,
  pipeline and lane it names still exists. A published rule whose template is archived later fails that action at run
  time, logged.
- Deleting: drafts only (my call), with a confirmation.

## 7. Testing a draft (the user's flow)
1. **Choose the test contact.** Either (the user's, answer 1c):
   - **an existing contact**: search the brand's contacts by name or number; each result shows the number it is on; a
     contact whose number WhatsApp hides is found by name; or
   - **a phone number that has never messaged you** (for example a spare phone), typed in: the test waits for that
     number's first message, so "Chat is new" is really new. A number WhatsApp hides cannot be typed; use the switch
     below instead.
   - A **"Treat this chat as new"** switch, so an existing contact can stand in for a new one: the test sees
     `chat.new` as true.
2. **Wait for the event.** The page says what to do: for the message event, "send a message from the test contact's
   phone to <number>"; for a lane change, "move one of <contact>'s deals to another lane". Only that contact's events
   are taken; everyone else's are ignored by the test.
3. **Show the event's data**, then check the conditions **one at a time** on a button press, each with ✔ or ✖ and the
   actual value it saw, then whether the rule matches.
4. **Run the actions one at a time**: each has Run and Skip and shows its result. They really happen, to the test
   contact (the template is really sent).
- **Before Start, a preflight** (from the reference): the page lists what would stop the test from working at all,
  such as no connected extension on the brand's numbers, an archived template, an agent who left, CRM off for Add to
  CRM (blockers, which disable Start), and what will really happen to the test contact (warnings).
- Then "Test again" (listen for the next event), "Replay this event" (run the same captured event again from its first
  condition, without another message; the actions really run again, to the test contact) or "Done".
- **Published rules are not paused** for the test contact during a test (the user's, "for now"): they run as usual.
- Only drafts can be tested (the user's). One test at a time per rule; a test ends when nothing has happened for 30
  minutes, where the open test page counts as something happening (the page polls, as the reference's console
  heartbeats): a test is cut off only once nobody is watching it (my call). Test runs are kept in the run log and the event history, marked as tests.

## 8. The Automations page
- Its **own entry in the side menu**, "Automations" (the user's).
- **List**: name and a one-line summary of the actions, the event, Draft or Published, priority, last run, runs and
  failures in the last 7 days; New rule; Edit, Test, Runs per rule.
- **Editor**: When (the event), If (conditions, groups like the segment editor's), Then (actions in order, each with
  its own settings; Assign chat has the dialog above).
- **Runs**: per rule, newest first: when, who (contact or deal), the event in short, and each action's outcome (done,
  skipped and why, failed and why). Kept for good, like the event history (the user's).
- **Event history** (my call, from the user's "keep track what when happened"): a tab on the Automations page listing
  every event, newest first, with what it was, when, which rules matched and what each did; filter by event kind, rule,
  contact and date.
- Mockups the user saw: `.superpowers/brainstorm/687-1793683211/content/rules-screen.html` and
  `assign-and-test.html` in the backend repo (git-ignored).

## 9. Permissions
- "Anyone with the permissions" (the user's). Two new authorities (my call):
  - `automation:view`: the page, rules and run logs.
  - `automation:manage`: create, edit, test, publish, move to draft, delete.
- Both default to Customer admin and Brand manager; any role can be given them on the Roles page.
- A rule acts as the brand, not as the person who wrote it: it can assign to any agent or team of the brand and send
  on any of its numbers. Only people with `automation:manage` can set that up.

## 10. Constraints (project rules)
- Read `docs/superpowers/project-rules.md` first. Work in `.worktrees/<branch>`, test first, a fresh reviewer on the
  whole branch, one fix pass, the full `./mvnw -q verify` before a merge. Merge and push only when asked.
- Mongo collections camelCase. Channel-neutral names for new things. The CRM module reaches the host only through
  `crm.platform` ports.
- **Never send a real message without the user naming the recipient.** Testing on the real number needs the user to
  name the test contact.
- The demo password is not recorded; ask the user.

## 11. Left to the builder
- The worker's polling interval, lease length, attempt count and how a test waiting on an event is woken (section
  4.3 fixes the queue itself).
- Where the incoming-message event is published (today `ChatCreated` and `MessageUpserted` exist; a new event carrying
  "this message created the chat" may be simpler).
- How a send and an assignment run without a person (today they take an `AuthenticatedUser`; a system actor variant).
- Data model and API shape, caching of published rules, the run log's storage, how the test page waits (the inbox's
  event stream, or polling).
- Exact wording on screens.
