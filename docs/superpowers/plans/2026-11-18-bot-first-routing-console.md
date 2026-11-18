# Chatbot-first routing: console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task
> (the user's choice: inline, on Opus). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The console shows chats a chatbot holds in their own "Chatbot" list, offers the hand-over node and the two
new chatbot settings, and no longer offers "Assign chat" as a new rule action.

**Architecture:** Type and display changes on existing pages; no new page. The server decides everything (who sees
what, what a reply does); the console only shows it.

**Tech stack:** React 18, TypeScript (es5 target), PatternFly 6.5, vitest (`mockFetch`, `renderApp`, `requestTo`).

**Spec:** `docs/superpowers/specs/2026-11-18-bot-first-routing-design.md` §5.7, §5.8 (in the backend repo, branch
`bot-first-routing`). Server contract: the last section of
`docs/superpowers/plans/2026-11-18-bot-first-routing-backend.md`.

## Global constraints

- Work only in `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend/.worktrees/bot-first-routing`
  (branch `bot-first-routing`); run `npm ci --prefer-offline` there first. No merge, push or deploy.
- Tests: `npx vitest run <path> --maxWorkers=4`. Do not run the whole vitest suite while a Maven build runs.
  Prettier only on touched files. `mockFetch` fails on an unmocked request: mock every route a page calls.
- No "template" naming. Copy fixed below is used verbatim.
- Model split and commit trailer: as in the backend plan's Global constraints.

Paths are under `src/app/` unless they start with `src/`.

## Review focus

1. A user whose remembered list is "Chatbot" loses the permission: the list falls back to their default, not an
   error page. (Task 1)
2. The filter's status must never be `BOT` (the type gains it for chats only): the Open / Closed switch and the
   stored filter keep two values. (Task 1)
3. A chat open on screen turns `BOT` or leaves it through the live stream: header and composer follow without a
   reload. (Task 2)
4. A saved flow with an edge after the hand-over node opens in the editor without crashing, and saving drops that
   edge. (Task 4)
5. A layer saved before this change has no `whenCompleted` / `whenAbandoned` keys: the form shows them as
   inherited. (Task 5)

---

### Task 1: Types and the Chatbot list

**Files:**
- Modify: `api/inbox.ts`, `Inbox/chatDisplay.ts`, `Inbox/ChatList.tsx`, `Inbox/inboxFilterStore.ts`,
  `src/test/session.ts`
- Test: `Inbox/chatList.test.tsx`, `Inbox/inboxFilterStore.test.ts`

**Interfaces — produces:**
- `ChatStatus = 'OPEN' | 'BOT' | 'CLOSED'`; `ListScope = 'MINE' | 'TEAM' | 'UNASSIGNED' | 'ALL' | 'BOT'`;
  `RoutedReason` `+ 'CHATBOT_HANDOVER' | 'CHATBOT_ENDED'`.
- `ChatListFilters.status: 'OPEN' | 'CLOSED'` (its own type, no longer `ChatStatus`).
- `SCOPE_TEXT.BOT = 'Chatbot'`; `allowedScopes` appends `'BOT'` last when
  `access.hasInWorkspace('inbox:view-bot')`; `defaultScope` is unchanged (never `BOT`).
- `src/test/session.ts`: `BRAND_CODES` gains `'inbox:view-bot'` after `'inbox:view-all'` (the server gives it to
  the roles those fixtures stand for); `BRAND_VIEWER` and `AGENT_IN_BRAND` do not get it.

- [ ] **Step 1: failing tests.** `chatList.test.tsx`: `shows the Chatbot list only with inbox:view-bot` (manager
  sees a "Chatbot" toggle item; `AGENT_IN_BRAND` does not); `the Chatbot list asks for scope=BOT and hides the
  status switch` (click "Chatbot": the chats request has `scope=BOT` and no `status` parameter; the "Status" toggle
  group is not in the document; "Unread only" still is); `leaving the Chatbot list brings the status switch back`.
  `inboxFilterStore.test.ts`: `a remembered Chatbot list falls back when the permission is gone` (stored scope
  `BOT`, access without the code → the default scope); `a stored status other than OPEN or CLOSED reads as OPEN`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.** In `api/inbox.ts` the chats query omits `status` when `scope === 'BOT'`.
- [ ] **Step 4: run, expect PASS**; also `npx vitest run src/app/Inbox --maxWorkers=4` and fix tests that counted
  scope toggle items for the manager fixtures.
- [ ] **Step 5: commit** `feat(inbox): the Chatbot list`.

### Task 2: A `BOT` chat on screen

**Files:**
- Modify: `Inbox/ChatHeader.tsx`, `Inbox/Composer.tsx`, `Inbox/Thread.tsx`
- Test: `Inbox/chatHeader.test.tsx`, `Inbox/composer.test.tsx`, `Inbox/thread.test.tsx`

**Produces (copy, verbatim):**
- Header: a label "With chatbot" beside the contact's name while `chat.status === 'BOT'`. Close stays offered;
  Reopen is offered only for `CLOSED`; the claim button shows as for an open unassigned chat.
- Composer, `chat.status === 'BOT'`: enabled, with the hint "Replying takes this chat over from the chatbot" under
  the text box (helper text, not an error).
- Thread, `routedReason`: `CHATBOT_HANDOVER` → ` (handed over by chatbot: <reasonDetail>)`, or
  ` (handed over by a chatbot)` without a detail; `CHATBOT_ENDED` → ` (after chatbot <reasonDetail> ended)`, or
  ` (after a chatbot ended)`. A `CLOSED` action with no user and a `byName` reads "Closed by <byName>" (check how
  the existing `CLOSED` case names its actor and reuse it).

- [ ] **Step 1: failing tests.** Header: `shows With chatbot on a BOT chat and offers Close, not Reopen`;
  `the label goes when the stream says the chat is OPEN` (re-render with the updated chat; Review focus 3).
  Composer: `a BOT chat can be replied to and says the reply takes it over` (send works; the POST goes out);
  `an OPEN chat shows no such hint`. Thread: one test per new reason text, with and without `reasonDetail`;
  `closed by a chatbot names the chatbot`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(inbox): a chat held by a chatbot in the header, composer and trail`.

### Task 3: "Assign chat" leaves the add-action menu

**Files:**
- Modify: `Automations/ActionList.tsx`
- Test: `Automations/automationEditor.test.tsx`

**Produces:** `AddActionMenu` offers every action in `ACTIONS` except `ASSIGN_CHAT`. Everything else that handles
`ASSIGN_CHAT` (card, modal, model, display, validation) is untouched.

- [ ] **Step 1: failing tests:** `Add action does not offer Assign chat` (menu items: Send template, Add to CRM on
  a message rule, Start chatbot; no "Assign chat"); `a rule that has Assign chat shows it and its dialog still
  edits it` (load a rule with the action; open its dialog, change the team, save: the PUT carries the changed
  `ASSIGN_CHAT` action); `removing it leaves no way to add it back`.
- [ ] **Step 2: run, expect FAIL** (the first one).
- [ ] **Step 3: implement:** filter in `AddActionMenu`'s `offered`.
- [ ] **Step 4: run, expect PASS**; `npx vitest run src/app/Automations --maxWorkers=4`, fixing tests that added
  an Assign chat action through the menu by starting them from a rule that already has one.
- [ ] **Step 5: commit** `feat(automations): Assign chat is no longer offered as a new action`.

### Task 4: The hand-over node

**Files:**
- Modify: `api/chatbots.ts` (the assign mode type, wherever it is declared), `Chatbots/nodeCatalog.ts`,
  `Chatbots/nodes/SmallFields.tsx` (`AssignChatFields`), `Chatbots/flowModel.ts` (only if edges are not already
  pruned to existing outputs on load or save)
- Test: `Chatbots/nodeCatalog.test.ts`, `Chatbots/flowModel.test.ts`, `Chatbots/chatbotEditor.test.tsx`

**Produces:**
- Catalog entry `assign_chat`: `label: 'Hand over to agent'`; `description: 'Gives the chat to a person and ends
  the chatbot.'`; `defaultSettings: () => ({ mode: 'NUMBER_ROUTING', userId: null, teamId: null, whenAway: null })`;
  `outputs: () => []`; `preview`: `NUMBER_ROUTING` → `"By the number's routing"`, the other modes as today;
  `produces` unchanged.
- `AssignChatFields`: the mode select lists, in this order, "The number's normal routing" (`NUMBER_ROUTING`),
  "An agent", "A team's queue", "Round robin in a team" (keep today's wording for the last three); with
  `NUMBER_ROUTING` no agent, team or when-away field is shown and those settings are saved as null. A line of
  helper text under the select: "The chatbot ends here. To tell the customer first, put a Send message node before
  this one."
- An edge leaving an `assign_chat` node is dropped when the flow is saved.

- [ ] **Step 1: failing tests.** `nodeCatalog.test.ts`: `hand over has no outputs and defaults to the number's
  routing` (and the server-parity fixture for `assign_chat` outputs becomes `[]`). `flowModel.test.ts`:
  `saving drops an edge that leaves a hand-over node` (Review focus 4). `chatbotEditor.test.tsx`: `a flow with an
  edge after the hand-over opens` (no crash, the node renders without an output handle); `choosing the number's
  routing hides the target fields and saves nulls`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; `npx vitest run src/app/Chatbots --maxWorkers=4`.
- [ ] **Step 5: commit** `feat(chatbots): Hand over to agent ends the chatbot`.

### Task 5: The two settings

**Files:**
- Modify: `api/chatbots.ts` (`SettingsLayer`, the effective-settings type), `Chatbots/settings/settingsLayers.ts`,
  `Chatbots/settings/LayeredSettingsForm.tsx`, test fixtures that build a full settings object (`PLATFORM` etc.)
- Test: `Chatbots/settings/settingsLayers.test.ts`, `Chatbots/chatbotSettingsPage.test.tsx`,
  `Chatbots/chatbotDefaults.test.tsx`

**Produces:**
- `SettingsLayer` `+ whenCompleted: 'CLOSE' | 'ROUTE' | null; whenAbandoned: 'CLOSE' | 'ROUTE' | null`.
- `FIELDS` has eleven entries, the two new ones last, in that order; a new group `CHOICES: SettingField[] =
  ['whenCompleted', 'whenAbandoned']` whose draft text is the enum value and whose saved value is that string.
- `LayeredSettingsForm`: a form group "When the chatbot ends" after "Agent reply", with two selects that follow the
  form's existing inherit / set pattern (the inherited value and its source shown as for the other fields):
  - "When the chatbot finishes": options "Close the chat" (`CLOSE`), "Send to the team" (`ROUTE`);
  - "When the customer stops answering": options "Send to the team" (`ROUTE`), "Close the chat" (`CLOSE`).
  Helper text under the group: "A chatbot that fails always sends the chat to the team."

- [ ] **Step 1: failing tests.** `settingsLayers.test.ts`: `FIELDS` length 11; `a layer without the new keys reads
  as inherited` (Review focus 5); `a chosen value round-trips to the layer`. `chatbotSettingsPage.test.tsx`:
  `shows the inherited choice and its source for both`; `choosing Close for abandoned chats saves whenAbandoned:
  CLOSE and leaves the rest inherited`. `chatbotDefaults.test.tsx`: `the platform page saves both choices`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; `npx vitest run src/app/Chatbots --maxWorkers=4`.
- [ ] **Step 5: commit** `feat(chatbots): what happens to the chat when a chatbot ends`.

### Task 6: Whole-console check

- [ ] **Step 1:** `npx tsc --noEmit && npm run lint` → no errors (fix every `switch` or `Record<ChatStatus, …>` /
  `Record<ListScope, …>` the new union members broke).
- [ ] **Step 2:** `npx vitest run --maxWorkers=4` (no Maven build running) → all pass.
- [ ] **Step 3:** `npm run -s build` → succeeds.
- [ ] **Step 4: commit** anything Steps 1–3 changed: `chore(console): types and tests after chatbot-first routing`.
