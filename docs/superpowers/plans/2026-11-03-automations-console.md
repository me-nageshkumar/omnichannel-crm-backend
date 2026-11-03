# Automations (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An "Automations" page where a manager lists, builds, tests step by step, publishes and follows rules (runs per rule, and an event history), plus the inbox showing automation sends and assignments.

**Architecture:** One API module `api/automations.ts` typed exactly as the backend plan's REST contract; a pure model `automationModel.ts` (field catalogue, operator words, condition tree helpers reused from the contact filter model as the segment model does) and `automationDisplay.ts`; pages under `src/app/Automations/` (list with a Rules tab and an Event history tab, editor, assign dialog, test page polling every 2 s, runs page). All choices in the editor come from `GET /automations/options`.

**Tech Stack:** React 18, TypeScript, PatternFly 6.5 (`@patternfly/react-core`, `@patternfly/react-table`), react-router, vitest + Testing Library (`src/test/renderApp`, `src/test/fetchMock`). Code goes in the frontend worktree `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend/.worktrees/automations` (new worktree: `npm ci --prefer-offline` first).

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/.worktrees/automations/docs/superpowers/specs/2026-11-03-automations-design.md` §5–§9. **API:** the "REST contract" section of `…/docs/superpowers/plans/2026-11-03-automations-backend.md` — types, paths, codes and field table are used verbatim; do not re-derive them.

## Global Constraints

- Checks per task: `npx vitest run <paths> --maxWorkers=4`; before the last commit `npx tsc --noEmit && npm run lint && npm run build`; Prettier only on touched files.
- Visibility: the page, its nav entry and every read view need `automation:view` (`useAccess().hasInWorkspace('automation:view')`); New rule, Save, Publish, Move to draft, Delete, Test need `automation:manage`. Nothing else is required (options come from `/automations/options`).
- Copy fixed by the spec: nav "Automations"; sections "When", "If", "Then"; "Draft" / "Published"; "Publish", "Move to draft"; "Chat is new" / "Chat is existing"; message types "All", "Text", "Photo", "Video", "Audio / voice note", "Document", "Sticker", "Location", "Contact card", "Poll"; operators "is", "is not", "is one of", "contains", "does not contain", "starts with", "is empty", "is not empty", "greater than", "less than", "between"; groups "All of these" / "Any of these"; assign choices "An agent", "A team, to its queue", "A team, round robin"; "Treat this chat as new"; "Test again", "Done"; inbox sender "Automation: <rule>" (from the server) and routed line "(by automation: <rule>)".
- `tsconfig` targets es5: no `[...new Set()]` (use `Array.from`), `class extends Error` needs `Object.setPrototypeOf`.
- Times shown with `shortTime` from `@app/Crm/segments/segmentModel` and `relative` from `@app/PhoneNumbers/PhoneNumberStatusLabel`.
- Phone input: `normaliseNumber` and `NUMBER_PATTERN` from `@app/api/phoneNumbers`.
- Commits (in the frontend repo) end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Plan calls (decisions the spec leaves to the builder)

- Plan call: the nav entry "Automations" is a top-level link in the first section, after "CRM".
- Plan call: routes `/automations` (list; `?tab=history` opens Event history), `/automations/new`, `/automations/:id` (editor; read-only when published), `/automations/:id/test`, `/automations/:id/runs`.
- Plan call: the Message type condition is a checkbox group; ticking "All" ticks every kind, and a message type condition with all nine kinds ticked is dropped from the tree on save (it would match everything).
- Plan call: the boolean fields are one select each — "Chat is new" / "Chat is existing", "Contact is in the CRM" / "Contact is not in the CRM" — stored as operator `is` with `true` / `false`.
- Plan call: lane choices list every lane as "<pipeline> › <lane>"; Lane type offers "Open", "Won", "Lost".
- Plan call: changing the event of a rule with conditions or an Add to CRM action asks first ("Changing the event clears the conditions and removes Add to CRM."), then clears them.
- Plan call: actions are reordered with "Move up" / "Move down" buttons (no drag), removed with a "Remove" button.
- Plan call: "Save draft" saves; "Publish" saves first, then publishes; "Test" from the editor saves first; publish errors (400 `fields`) are shown at the condition rows and action cards they name, others in the banner.
- Plan call: the Assign chat warning is computed from the options: an agent sees a number when `seesAllNumbers`, or the number has no owning team, or the agent's `teamIds` holds it; otherwise the editor warns "This rule applies to every number of the brand. <agent> cannot see <labels>; the chat will not be assigned on those numbers."
- Plan call: the test page polls `GET /tests/{id}` every 2 s while WAITING and stops otherwise; a 409 `AUTOMATION_TEST_RUNNING` on Start resumes the test when it is the user's own (the 409's `startedById` is the user's id), else offers "End that test" (`POST /tests/{testId}/end`) and Start again.
- Plan call: the Event history's contact filter is set from a row ("Only this contact") and shown as a removable chip; the rule filter is a select of the brand's rules; dates use two DatePickers with `dayStart` / `dayEnd` from `@app/HistoryImports/importForm`.
- Plan call: test runs show a "Test" label in Runs and Event history.

## Review Focus

1. A rule saved, then published with problems (archived template, missing agent value) must keep the user's edits on screen and show each message next to its action — test in Task 4 (`publishErrorsLandOnTheirAction`).
2. A published rule opened by URL must be read-only (no Save, inputs disabled) with "Move to draft"; after moving to draft the form becomes editable without a reload — test in Task 4 (`publishedRuleIsReadOnlyUntilMovedToDraft`).
3. A test page reloaded mid-test must pick the running test back up (`GET /rules/{id}/tests/active`) instead of offering Start — test in Task 6 (`reloadResumesTheActiveTest`).
4. A test that ended by itself (30 idle minutes) must say so and offer Start, not keep polling — test in Task 6 (`idleEndStopsPollingAndSaysWhy`).
5. A view-only user (automation:view without manage) must see list, rules, runs and history but no New rule, Save, Publish, Test or Delete — test in Task 3 (`viewOnlyUserSeesNoChanges`) and Task 4 (`viewOnlyEditorIsReadOnly`).

---

### Task 1: API module, fixtures, session codes

**Files:**
- Create: `src/app/api/automations.ts`, `src/app/api/automations.test.ts`, `src/test/automations.ts` (fixtures).
- Modify: `src/test/session.ts` — `BRAND_CODES` gains `'automation:view'`, `'automation:manage'` after `'campaign:settings'`.

**Interfaces:**
- Types exported exactly as the contract: `EventKind`, `ConditionNode`, `ConditionGroup = Extract<ConditionNode, { rules: unknown }>`, `ConditionLeaf = Extract<ConditionNode, { field: string }>`, `AutomationAction`, `RuleRequest`, `Rule`, `RuleSummary`, `AutomationOptions`, `EventData`, `StepView`, `Run`, `EventRecord`, `TestContact`, `TestStartRequest`, `TestStatus`, `AutomationTest`, `MessageKind`, `Page<T>` (re-export the one from `@app/api/historyImports`).
- Functions (all `apiFetch`, base `/api/workspaces/${ws}/automations`): `getAutomationOptions(token, ws)`, `listRules(token, ws)`, `getRule(token, ws, id)`, `createRule(token, ws, body: RuleRequest)`, `updateRule(token, ws, id, body: RuleRequest)`, `publishRule(token, ws, id)`, `unpublishRule(token, ws, id)`, `deleteRule(token, ws, id)`, `listRuns(token, ws, ruleId, page = 0)`, `listEvents(token, ws, f: { kind?: EventKind; ruleId?: string; contactId?: string; from?: string; to?: string; page?: number } = {})`, `searchTestContacts(token, ws, q)`, `startTest(token, ws, ruleId, body: TestStartRequest)`, `getActiveTest(token, ws, ruleId): Promise<AutomationTest | undefined>` (204 → undefined), `getTest(token, ws, testId)`, `checkNextCondition(token, ws, testId)`, `runTestAction(token, ws, testId, index)`, `skipTestAction(token, ws, testId, index)`, `testAgain(token, ws, testId)`, `endTest(token, ws, testId)`.
- Fixtures `src/test/automations.ts`: `OPTIONS: AutomationOptions` (numbers `n1` "Main line" owned by team `t1` "Support", `n2` "Sales line" no team; agents `u1` "Demo Agent" teams `[t1]`, `u2` "Demo Manager" `seesAllNumbers true`; templates `tp1` "Welcome" (`contact.name` CONTACT_FIELD fallback "there"), `tp2` "Order ready" (`order_id` AGENT required, label "Order id"); crm enabled, pipeline `p1` "Sales" lanes `s1` "New" OPEN, `s2` "Proposal sent" OPEN, `s3` "Won" WON, `s4` "Lost" LOST); `WELCOME_RULE: Rule` (DRAFT, MESSAGE_RECEIVED, `chat.new is true`, actions [SEND_TEMPLATE tp1, ASSIGN_CHAT TEAM_ROUND_ROBIN t1, ADD_TO_CRM]); `PUBLISHED_RULE`; `RULE_SUMMARIES`; `WAITING_TEST`, `CAPTURED_TEST`; `automationRoutes(overrides?)` returning `Record<string, MockRoute>` for `GET …/options`, `GET …/rules`.

- [ ] **Step 1: Write the failing tests** `automations.test.ts` (style of `historyImports.test.ts`): `createRulePostsTheRequest` (`POST /api/workspaces/w1/automations/rules`, body equals the request); `listEventsBuildsQuery` (`?kind=DEAL_LANE_CHANGED&ruleId=r1&contactId=c1&from=…&to=…&page=2`); `activeTestIsUndefinedOn204`; `testActionPaths` (`POST …/tests/x/actions/1/run`, `/skip`, `/check`, `/again`, `/end`); `deleteRuleSendsDelete`.
- [ ] **Step 2: Run** `npx vitest run src/app/api/automations.test.ts --maxWorkers=4` → fail (module missing).
- [ ] **Step 3: Implement** the module, fixtures and session codes.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(api): automations client`

---

### Task 2: Model and display helpers

**Files:**
- Create: `src/app/Automations/automationModel.ts`, `src/app/Automations/automationDisplay.ts`.
- Test: `src/app/Automations/automationModel.test.ts`, `src/app/Automations/automationDisplay.test.ts`.

**Interfaces:**
- `automationModel.ts`:
  - `type FieldType = 'boolean' | 'text' | 'choice' | 'number'`; `interface AutomationField { field: string; label: string; type: FieldType; operators: string[] }`; `FIELDS: Record<EventKind, AutomationField[]>` — the contract's table with labels "Chat", "Message text", "Message type", "Number", "Contact", "Contact name"; "Pipeline", "Moved into lane", "Moved from lane", "Lane type", "Deal amount", "Deal owner".
  - `OPERATOR_LABELS: Record<string, string>` (the Global Constraints words); `MESSAGE_KINDS: { kind: MessageKind; label: string }[]` in the spec's order; `BOOLEAN_CHOICES: Record<'chat.new' | 'contact.inCrm', [trueLabel, falseLabel]>` = `["Chat is new", "Chat is existing"]`, `["Contact is in the CRM", "Contact is not in the CRM"]`.
  - `choicesFor(field: string, o: AutomationOptions): { value: string; label: string }[]` (numbers by label, lanes as "Sales › Won", lane types, agents).
  - `newLeaf(event: EventKind): ConditionLeaf` (`chat.new is true` / `toLane is []`); `newGroup(): ConditionGroup` (`{combinator: 'and', not: false, rules: []}`).
  - Tree helpers re-typed from `@app/Contacts/Filters/filterModel` by cast, as `segmentModel.ts` does: `nodeAt`, `replaceAt`, `addTo`, `removeAt`, `rulePath`, `depth`, `countRules`; `MAX_DEPTH = 3`, `MAX_CONDITIONS = 30`.
  - `pruneConditions(root: ConditionGroup | null): ConditionGroup | null` — drops message-type leaves holding all nine kinds; an empty root becomes `null`.
  - `conditionErrors(fields: Record<string,string>): { byPath: Record<string,string>; byAction: Record<number, Record<string,string>>; other: string[] }` — strips `conditions.` and maps `actions[i].<key>`.
  - `conditionText(leaf: ConditionLeaf, o: AutomationOptions): string` (e.g. `"Message type is one of Text, Photo"`, `"Chat is new"`, `"Deal amount between 1000 and 5000"`).
  - `actionSummary(a: AutomationAction, o: AutomationOptions): string` — the backend's `actionsSummary` parts (`Send “Welcome”`, `Assign to Demo Agent`, `Queue in Support`, `Round robin in Support`, `Add to CRM (Sales › New)` / `Add to CRM (default pipeline)`).
  - `agentBlindNumbers(agentId: string, o: AutomationOptions): AutomationOptions['numbers']` — the numbers the agent cannot see (plan call).
  - `emptyRule(): RuleRequest` (`name ''`, `event 'MESSAGE_RECEIVED'`, `priority null`, `conditions null`, `actions []`).
- `automationDisplay.ts`: `statusLabel(s: 'DRAFT' | 'PUBLISHED')`, `statusColor(s)` (`grey`/`green`); `eventLabel(k)` ("A message comes in", "A deal moves to another lane"); `outcomeLabel(o)` ("Done", "Skipped", "Failed"); `outcomeColor(o)`; `runStatusLabel(s)` ("Running", "Testing", "Done", "Failed", "Interrupted"); `actualText(v: unknown): string` (`null` → "empty", booleans "yes"/"no", arrays joined); `waitingText(t: AutomationTest, o: AutomationOptions): string` — message: `Send a message from ${name ?? '+' + phone}'s phone to ${numbers labels joined " or "}.`; lane: `Move one of ${name}'s deals to another lane.`; `endedText(r)` — IDLE "The test ended: nothing happened for 30 minutes.", RULE_PUBLISHED "The test ended because the rule was published.", RULE_DELETED "The test ended because the rule was deleted.", DONE "The test is done.".

- [ ] **Step 1: Write the failing tests.** `automationModel.test.ts`: `fieldsFollowTheContract` (MESSAGE_RECEIVED has 6 fields, `message.type` operators `['is_one_of']`; DEAL_LANE_CHANGED `deal.amount` has `between`); `choicesListLanesWithPipeline` (`toLane` → `{value 's3', label 'Sales › Won'}`); `pruneDropsAllKinds` (a message-type leaf with all 9 → root `null`; with 2 kept); `conditionErrorsSplit` (`{"conditions.rules[0].value": "is required", "actions[1].templateId": "The template is archived", "name": "is required"}` → `byPath["rules[0]"]`, `byAction[1].templateId`, `other` holds the name line); `conditionTextReadsPlainly` (the three examples above); `actionSummaryMatchesServer`; `agentBlindNumbers` (`u1` sees `n1` (team t1) and `n2` (no team) → `[]`; with an `n3` owned by `t9` → `[n3]`; `u2` → `[]`). `automationDisplay.test.ts`: `waitingTextNamesContactAndNumbers` ("Send a message from Asha's phone to Main line or Sales line."), `waitingTextForTypedNumber` ("+919811100009"), `endedTextPerReason`, `actualTextFormats`.
- [ ] **Step 2: Run** both files → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automations): field catalogue, condition helpers and display text`

---

### Task 3: Nav, routes and the rules list

**Files:**
- Create: `src/app/Automations/AutomationsPage.tsx`, `src/app/Automations/RulesTab.tsx`.
- Modify: `src/app/routes.tsx` — nav `{ label: 'Automations', path: '/automations', requires: (a) => a.hasInWorkspace('automation:view') }` after the "CRM" group; route `/automations` (`requires: automation:view`). Tasks 4, 6 and 7 add `/automations/new` and `/automations/:id`, `/automations/:id/test`, `/automations/:id/runs` with their pages.
- Test: `src/app/Automations/automationsPage.test.tsx`, `src/app/routes.test.tsx` (append).

**Interfaces:**
- `AutomationsPage`: `PageShell title="Automations"`, actions "New rule" (manage only → `/automations/new`); PatternFly `Tabs` "Rules" (default) and "Event history" (Task 7; `?tab=history`).
- `RulesTab`: `useLoad(listRules)`; `Table` columns Name (link to `/automations/:id`, `actionsSummary` under it as small text) · When (`eventLabel`) · Status (`Label` `statusColor`) · Priority (`—` when null) · Last run (`relative`, "Never") · Runs (7 days) · Failures (7 days); kebab per row: "Edit" / "View", "Test" (drafts, manage → `/automations/:id/test`), "Runs" (`/automations/:id/runs`), "Publish" / "Move to draft" (manage), "Delete" (drafts, manage, `ConfirmModal` "Delete rule “<name>”?" "Its runs stay in the history." danger). Publish errors (400) open an `Alert` "<name> cannot be published" listing `fields` values with a link "Open the rule". Empty state "No rules yet. A rule sends a template, assigns the chat or adds the person to the CRM when a message comes in or a deal moves."

- [ ] **Step 1: Write the failing tests** `automationsPage.test.tsx` (`renderApp('/automations')`, `mockFetch({...meAs(WORKSPACE_MANAGER), ...automationRoutes()})`): `listsRulesWithStats` (row "Welcome" shows "Send “Welcome” · Round robin in Support · Add to CRM (default pipeline)", "Draft", "—", "Never", runs "3", failures "1"); `publishFromTheList` (kebab "Publish" → `POST …/rules/r1/publish`, row shows "Published"); `publishErrorsAreListed` (400 `fields {"actions[0].templateId":"The template is archived"}` → alert containing "The template is archived"); `deleteAsksFirst` (`ConfirmModal`, then `DELETE …/rules/r1`, row gone); `publishedRuleHasNoDelete`; `viewOnlyUserSeesNoChanges` (session with `automation:view` only: no "New rule", kebab has only "View" and "Runs"). `routes.test.tsx`: `automations open for a manager and are not found without automation:view` (as the history-imports case; `BRAND_VIEWER` → 404) and the nav shows "Automations" for `WORKSPACE_MANAGER`, not for `AGENT_IN_BRAND`.
- [ ] **Step 2: Run** `npx vitest run src/app/Automations/automationsPage.test.tsx src/app/routes.test.tsx --maxWorkers=4` → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automations): nav entry and rules list`

---

### Task 4: Rule editor — When, If, Then

**Files:**
- Create: `src/app/Automations/AutomationEditorPage.tsx`, `ConditionTreeEditor.tsx`, `ConditionRow.tsx`, `ConditionValueInput.tsx`, `ActionList.tsx`, `SendTemplateFields.tsx`, `AddToCrmFields.tsx`, `useAutomationOptions.ts`.
- Modify: `src/app/routes.tsx` — routes `/automations/new`, `/automations/:id`.
- Test: `src/app/Automations/automationEditor.test.tsx`, `src/app/Automations/conditionTreeEditor.test.tsx`.

**Interfaces:**
- `useAutomationOptions(): Loaded<AutomationOptions>` (`useLoad(getAutomationOptions)`).
- `AutomationEditorPage`: loads the rule (`:id`) or `emptyRule()`; form fields "Name" (required), "Description", "Priority" (number input, empty = none, helper "Rules with a priority run first, largest first; the rest in the order they were made."); **When** select (`eventLabel`), with the plan call's confirm on change; **If** `ConditionTreeEditor`; **Then** `ActionList`. Footer: "Save draft" (create → navigate to `/automations/:id`; update sends `version`), "Publish" (save, then `publishRule`), "Test" (save, then `/automations/:id/test`), "Cancel". Published: `Alert variant="info"` "Published rules cannot be edited. Move the rule to draft to change it." with "Move to draft" (`unpublishRule`, then the form unlocks); every input disabled. Without `automation:manage` the form is always read-only and has no footer buttons. Errors: `useAction` with the shown field keys; `conditionErrors(fieldErrors)` feeds rows and action cards; a 409 `AUTOMATION_RULE_PUBLISHED` reloads the rule.
- `ConditionTreeEditor` props `{ event: EventKind; root: ConditionGroup | null; options: AutomationOptions; errors: Record<string,string>; isDisabled: boolean; onChange(next: ConditionGroup | null): void }` — "No conditions: every <event phrase> runs this rule." when empty; per group a `ToggleGroup` "All of these" / "Any of these", "Add condition", "Add group" (hidden at depth 3 or 30 conditions), "Remove group"; rows `ConditionRow`.
- `ConditionRow` props `{ event; leaf: ConditionLeaf; options; error?: string; isDisabled; onChange(leaf); onRemove() }` — field `FormSelect`, operator `FormSelect` (hidden for boolean fields, which show the `BOOLEAN_CHOICES` select instead), `ConditionValueInput`.
- `ConditionValueInput` props `{ field: AutomationField; operator: string; value; options; isDisabled; onChange(value) }` — text: `TextInput`; number: `TextInput type=number` (two for `between`, labelled "From" / "To"); choice + `is`/`is_not`: `FormSelect`; choice + `is_one_of`: checkbox group (`message.type` with the extra "All" box); `is_empty`/`is_not_empty`: nothing.
- `ActionList` props `{ event; actions: AutomationAction[]; options; errors: Record<number, Record<string,string>>; isDisabled; onChange(next) }` — one `Card` per action titled "1. Send template" / "Assign chat" / "Add to CRM" with "Move up", "Move down", "Remove"; "Add action" `Dropdown` ("Send template", "Assign chat", and "Add to CRM" for MESSAGE_RECEIVED only). Assign chat cards show `actionSummary` and "Choose…" / "Change…" opening Task 5's dialog (until Task 5, the card shows the summary only).
- `SendTemplateFields` props `{ action; options; errors; isDisabled; onChange }` — template `FormSelect` ("Choose a template"); for each AGENT placeholder a `TextInput` labelled `Value for “<label>”` (required marker when `required`, helper "Every send uses this value." and, with a fallback, "Empty: “<fallback>”"); helper under the select: "Contact details such as the name are filled from the contact."
- `AddToCrmFields` — CRM off: `Alert variant="warning"` "CRM is off for this brand. The rule cannot be published with this action."; else pipeline `FormSelect` ("Default pipeline" + pipelines), lane `FormSelect` ("First lane" + that pipeline's lanes), "Deal title" `TextInput` placeholder `{{name}} – WhatsApp`, helper "{{name}} is the contact's name, or their number when there is none. The owner is whoever the chat is assigned to when this runs."

- [ ] **Step 1: Write the failing tests.** `conditionTreeEditor.test.tsx`: `addsAConditionAndAGroup`; `booleanFieldShowsNewOrExisting` (choose "Chat", select "Chat is existing" → leaf `{field 'chat.new', operator 'is', value false}`); `messageTypeCheckboxes` (tick "Photo" and "Sticker" → `value ['PHOTO','STICKER']`; tick "All" → all nine); `betweenHasTwoInputs`; `depthLimitHidesAddGroup`; `rowShowsItsError`. `automationEditor.test.tsx` (`renderApp('/automations/new')` / `('/automations/r1')`): `buildsAndSavesTheWelcomeRule` (name "Welcome", condition Chat is new, actions Send template "Welcome", Add to CRM → `POST …/rules` body equals `{name 'Welcome', description null, event 'MESSAGE_RECEIVED', priority null, conditions {combinator 'and', not false, rules [{field 'chat.new', operator 'is', value true}]}, actions [{type 'SEND_TEMPLATE', templateId 'tp1', values {}}, {type 'ADD_TO_CRM', pipelineId null, stageId null, dealTitle null}]}`, then the URL is `/automations/<new id>`); `agentPlaceholderNeedsAValue` (template "Order ready" shows `Value for “Order id”`; typed "A-17" → `values {order_id 'A-17'}`); `addToCrmOnlyForMessages` (event "A deal moves to another lane" → the menu has no "Add to CRM"); `changingEventAsksAndClears`; `publishErrorsLandOnTheirAction` (publish → 400 `fields {"actions[0].values.order_id": "Order id needs a value"}` → the message is inside card 1, the typed name is still in the field); `publishedRuleIsReadOnlyUntilMovedToDraft` (`PUBLISHED_RULE`: inputs disabled, no "Save draft"; "Move to draft" → `POST …/unpublish` → inputs enabled); `viewOnlyEditorIsReadOnly`; `crmOffWarns` (options `crm.enabled false` → the warning text); `moveUpReorders`.
- [ ] **Step 2: Run** both files → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automations): rule editor with conditions and actions`

---

### Task 5: Assign chat dialog

**Files:**
- Create: `src/app/Automations/AssignChatModal.tsx`.
- Modify: `src/app/Automations/ActionList.tsx` — "Choose…" / "Change…" opens it; a new Assign chat action opens it at once.
- Test: `src/app/Automations/assignChatModal.test.tsx`.

**Interfaces:**
- `AssignChatModal` props `{ action: AutomationAction; options: AutomationOptions; onSave(a: AutomationAction): void; onCancel(): void }` — title "Assign chat"; `Radio`s "An agent", "A team, to its queue", "A team, round robin". Agent: `FormSelect` of agents, then "If the agent is away or cannot take chats:" radios "Leave the chat with them" (`KEEP_WITH_AGENT`, default) / "Put it in the queue of the team that owns the chat's number" (`OWNING_TEAM_QUEUE`), helper "When the number has no team, the chat stays with the agent."; the plan call's warning (`Alert variant="warning" isInline`) from `agentBlindNumbers`. Team modes: team `FormSelect`; round robin helper "The next available member in turn, sharing the team's turn with the inbox's routing. Nobody available: the team's queue." Footer "Save" (disabled until an agent or team is chosen), "Cancel". All modes note "This replaces whatever the inbox's routing did."
- Saved shapes: AGENT `{type 'ASSIGN_CHAT', mode 'AGENT', userId, teamId null, whenAway}`; TEAM_QUEUE / TEAM_ROUND_ROBIN `{…, mode, userId null, teamId, whenAway null}`.

- [ ] **Step 1: Write the failing tests** `assignChatModal.test.tsx`: `agentWithAwayFallback` (choose "Demo Agent", "Put it in the queue…" → `onSave` with `mode 'AGENT'`, `userId 'u1'`, `whenAway 'OWNING_TEAM_QUEUE'`); `teamRoundRobin`; `teamQueue`; `warnsWhenAgentCannotSeeANumber` (options with `n3` owned by `t9` → warning names "Demo Agent" and the `n3` label; `u2` → no warning); `saveDisabledUntilChosen`; `editorOpensDialogOnAdd` (in the editor, "Add action" → "Assign chat" opens the dialog; Save → card reads "Round robin in Support").
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx vitest run src/app/Automations --maxWorkers=4` → PASS.
- [ ] **Step 5: Commit** — `feat(automations): assign chat dialog`

---

### Task 6: Test page

**Files:**
- Create: `src/app/Automations/AutomationTestPage.tsx`, `useAutomationTest.ts`, `TestContactPicker.tsx`, `TestSteps.tsx`.
- Modify: `src/app/routes.tsx` — route `/automations/:id/test` (`automation:manage`).
- Test: `src/app/Automations/automationTest.test.tsx`.

**Interfaces:**
- `POLL_MS = 2000`. `useAutomationTest(ruleId: string): { test: AutomationTest | undefined; loading: boolean; error: string | null; start(body: TestStartRequest): Promise<void>; check(): Promise<void>; run(index: number): Promise<void>; skip(index: number): Promise<void>; again(): Promise<void>; end(): Promise<void> }` — mounts with `getActiveTest`; polls `getTest` every `POLL_MS` only while `status === 'WAITING'`; each step call replaces `test` with the response; `start` handles the plan call's 409, reading `testId`, `startedById`, `startedByName` from `ApiError.extra`.
- `AutomationTestPage`: `PageShell title="Test “<rule name>”"`; a published rule shows "Only drafts can be tested." with a link back. Sections in order:
  1. **Choose the test contact** (no active test): `TestContactPicker` — radios "An existing contact" (search box, `searchTestContacts` debounced 300 ms, results "Asha · +919811100001 · on Main line", hidden phone "Number hidden") / "A number that has never messaged you" (`TextInput` "Phone number, country code first", validated with `NUMBER_PATTERN` after `normaliseNumber`, helper "A number WhatsApp hides cannot be typed; choose the contact and switch on “Treat this chat as new”."); `Switch` "Treat this chat as new"; "Start test". Lane-change rules show only the contact search.
  2. **Waiting** (WAITING): `Spinner` + `waitingText`, "Only this contact's messages are taken; everyone else is ignored. Published rules keep running for this contact." and "Stop test" (`end`).
  3. **The event** (CAPTURED): `DescriptionList` of `captured.data` (message: Chat new/existing (with "treated as new" when `treatedAsNew`), Message, Type, Number, Contact, In the CRM; lane: Deal, Pipeline, From, To, Amount, Owner).
  4. **Conditions**: one row per checked condition `✔`/`✖` + `conditionText` + "saw <actualText>"; "Check next condition" while `conditions.length < conditionCount`; then "The rule matches." (success) or "The rule does not match: live, its actions would not run on this event." (warning); no conditions: "No conditions: the rule matches every event."
  5. **Actions** (`TestSteps`): one row per `actions[i]` with its `summary`, and for `i === nextAction` buttons "Run" (primary, helper "This really happens to the test contact.") and "Skip"; done rows show `outcomeLabel` and `reason`.
  - Footer while CAPTURED: "Test again" (`again`), "Done" (`end`, then back to `/automations/:id`). ENDED: `endedText` + "Start a new test".
- `TestSteps` props `{ steps: StepView[]; nextAction: number | null; busy: boolean; onRun(i: number): void; onSkip(i: number): void }`.

- [ ] **Step 1: Write the failing tests** `automationTest.test.tsx` (`renderApp('/automations/r1/test')`, `vi.useFakeTimers({ shouldAdvanceTime: true })` where polling is asserted): `startsWithATypedNumber` (type "+91 98111 00009", switch off → `POST …/rules/r1/tests` body `{contactId null, phone '919811100009', treatAsNew false}`); `searchesExistingContacts` (type "ash" → `GET …/test-contacts?q=ash` → "Asha · +919811100001 · on Main line"; pick, switch on → body `{contactId 'c1', phone null, treatAsNew true}`); `waitingShowsWhatToDoAndPolls` (WAITING → "Send a message from Asha's phone to Main line or Sales line."; advance 2 s → one more `GET …/tests/x1`; response CAPTURED → event data shown, no further GET after another 2 s); `checksConditionsOneByOne` ("Check next condition" → `POST …/check` → row "✔ Chat is new · saw yes" and "The rule matches."); `runsAndSkipsActionsInOrder` (only action 1 has buttons; "Run" → `POST …/actions/0/run` → "Done · Sent “Welcome”"; "Skip" on action 2 → `…/actions/1/skip`); `notMatchingStillOffersActions` (matched false → the warning text, Run still shown); `testAgainAndDone`; `reloadResumesTheActiveTest` (`GET …/tests/active` returns CAPTURED → no picker, the event shown); `idleEndStopsPollingAndSaysWhy` (poll returns ENDED IDLE → "The test ended: nothing happened for 30 minutes.", "Start a new test", no further GET); `someoneElsesTestCanBeEnded` (start → 409 `AUTOMATION_TEST_RUNNING` with `testId 'x9'`, `startedById 'u9'`, `startedByName 'Demo Admin'` → "Demo Admin is testing this rule." + "End that test" → `POST …/tests/x9/end`, then Start again succeeds); `laneChangeTestNeedsAContact` (DEAL_LANE_CHANGED rule → no typed-number radio; waiting text "Move one of Asha's deals to another lane.").
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automations): step-by-step test page`

---

### Task 7: Runs page and event history

**Files:**
- Create: `src/app/Automations/AutomationRunsPage.tsx`, `EventHistoryTab.tsx`, `RunSteps.tsx`.
- Modify: `src/app/Automations/AutomationsPage.tsx` — the "Event history" tab; `src/app/routes.tsx` — route `/automations/:id/runs`.
- Test: `src/app/Automations/automationRuns.test.tsx`, `src/app/Automations/eventHistory.test.tsx`.

**Interfaces:**
- `RunSteps` props `{ steps: StepView[] }` — one line per step: `summary` · `Label` (`outcomeLabel`/`outcomeColor`) · `reason`.
- `AutomationRunsPage`: `PageShell title="Runs of “<rule name>”"`; `listRuns` paged ("Newer" / "Older" by `page`); `Table` When (`shortTime(startedAt)`) · Who (contact name or `+phone`, or the deal title) · Event (`summary`) · Outcome (`runStatusLabel`, plus a "Test" `Label` when `test`) and an expandable row with `RunSteps`. Empty "This rule has not run yet."
- `EventHistoryTab`: filters `ToolbarItem`s — kind `FormSelect` ("All events" + the two `eventLabel`s), rule `FormSelect` ("All rules" + `listRules`), From / To `DatePicker`s (`dayStart` / `dayEnd`), contact chip (plan call); `Table` When · Event (`summary`, "Test" label when `test`) · Who · Rules (one line per run: rule name + `runStatusLabel`; "No rule matched." when `runs` is empty) · Outcome (`FAILED` shows `error`); expandable row with each run's `RunSteps`; row action "Only this contact". Paging as the runs page.

- [ ] **Step 1: Write the failing tests.** `automationRuns.test.tsx`: `listsRunsNewestFirstWithSteps` (expand → "Send “Welcome” · Done · Sent “Welcome”" and "Assign chat · Failed · Demo Agent cannot see this number"); `testRunsAreLabelled`; `pagesOlder` (`total 51` → "Older" → `GET …/runs?page=1`). `eventHistory.test.tsx` (`renderApp('/automations?tab=history')`): `listsEventsWithMatchedRules`; `noRuleMatched`; `filtersByKindAndRule` (selects → `GET …/events?kind=DEAL_LANE_CHANGED&ruleId=r1&page=0`); `filtersByDates`; `onlyThisContact` (row action → `contactId=c1` in the query, chip "Asha" shown, removing it drops the param); `failedEventShowsError`.
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automations): runs page and event history`

---

### Task 8: Inbox shows automation sends and assignments

**Files:**
- Modify: `src/app/api/inbox.ts:10,16,114-125` — `MessageSource` gains `'AUTOMATION'`; `RoutedReason` gains `'AUTOMATION'`; `ActionView` gains `reasonDetail: string | null`.
- Modify: `src/app/Inbox/MessageBubble.tsx:76-81` — `AUTOMATION` → `message.sentBy?.name ?? 'Automation'`.
- Modify: `src/app/Inbox/Thread.tsx:64-79` — `case 'AUTOMATION': return action.reasonDetail ? \` (by automation: ${action.reasonDetail})\` : ' (by automation)';`.
- Modify: `src/test/inbox.ts` fixtures that build `ActionView` — `reasonDetail: null`.
- Test: `src/app/Inbox/thread.test.tsx` (append).

- [ ] **Step 1: Write the failing tests**: `showsTheAutomationAsTheSender` (message `source 'AUTOMATION'`, `sentBy {id null, name 'Automation: Welcome'}` → "Automation: Welcome" in the bubble); `routedByAutomationLine` (`actionText({type 'ROUTED', toTeam {name 'Support'}, reason 'AUTOMATION', reasonDetail 'Welcome', …})` → "Routed to Support (by automation: Welcome)").
- [ ] **Step 2: Run** `npx vitest run src/app/Inbox/thread.test.tsx --maxWorkers=4` → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx vitest run --maxWorkers=4`, then `npx tsc --noEmit && npm run lint && npm run build` → PASS.
- [ ] **Step 5: Commit** — `feat(inbox): automation sender and routed reason`

---

### Task 9: End-to-end check against the running stack

Needs the backend branch deployed on 8089 (project rules §2) and **the user naming the test contact and their phone** — never message anyone else.

- [ ] **Step 1:** As `demo-manager` (password from the user) open Automations → New rule "Welcome (test)": When "A message comes in", If "Chat is new", Then Send template (the user's choice), Assign chat "A team, round robin" (the number's team), Add to CRM (default pipeline). Save draft.
- [ ] **Step 2:** Test → the contact the user named (or their typed spare number) → Start; the user sends a message from that phone; the event appears; check the condition; Run each action; confirm the template arrived on the user's phone, the chat's Routed line reads "(by automation: Welcome (test))", and the CRM contact and deal exist. Done.
- [ ] **Step 3:** Publish; confirm "Move to draft" makes it read-only-off again and that the list shows runs and failures; open Runs and Event history (the test run is labelled "Test").
- [ ] **Step 4:** Move the rule back to draft (it must not stay live on the demo brand) and note anything surprising in `docs/superpowers/crm-pending.md` in the backend repo; commit there.
