# Advanced Chatbot (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Chatbots page: a React Flow canvas editor with a node palette and per-node settings drawer, Draft/Published, test on a named contact with a live run overlay, versions, runs; the Start chatbot automation action; the inbox's running-chatbot chip.

**Architecture:** A pure `flowModel.ts` owns everything testable without layout: the node catalog (outputs, defaults, previews, variable schema), conversion between the saved flow and React Flow nodes/edges, naming, problem routing. `ChatbotEditorPage` is a lazy chunk that mounts `@xyflow/react`; node forms live one per type under `Chatbots/nodes/` and reuse the automations' field editors and the inbox's interactive model. Editor tests stub `@xyflow/react` with a list renderer so forms, saving and problems are tested in jsdom.

**Tech Stack:** React 18, TypeScript (target es5), PatternFly 6.5, `@xyflow/react` 12 (new), webpack, vitest + Testing Library, `mockFetch`/`requestTo`/`callsTo`.

**Spec:** `../specs/2026-11-13-advanced-chatbot-design.md` (backend repo). Backend plan: `2026-11-13-advanced-chatbot-backend.md`.

## Global Constraints

- Work in `.worktrees/advanced-chatbot` of the console repo (`npm ci --prefer-offline` first). Prettier only on touched files. Commit trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- `@xyflow/react` is the one new dependency, pinned exactly; `node_modules/@xyflow/react` added to `stylePaths.js`; the editor page is a `React.lazy` chunk (`Crm/routes.tsx` pattern).
- es5 target: `Array.from`, no `[...new Set()]`; `Object.setPrototypeOf` for Error subclasses.
- Node names `[A-Za-z0-9_]{1,40}`; node type codes `send_message, send_template, ask_question, ask_form, condition, update_contact, do_not_contact, assign_chat, add_to_crm, http_request, wait, jump`; outputs `next, other, noAnswer, otherwise, success, failure, reply:<id>, case:<id>`.
- Saved flow shape: `{nodes: [{id, type, position:{x,y}, data:{name, settings}}], edges: [{id, source, sourceHandle, target}], viewport:{x,y,zoom}}`. The server reads `id, type, data.name, data.settings, source, sourceHandle, target` and keeps the rest.
- Problems from the server: `{nodeId, nodeName, field, message, severity: 'ERROR'|'WARNING'}`; publish errors come as `VALIDATION` fields `nodes.<id>.<field>` and `flow.<field>`.
- Wording: "Chatbots", "New chatbot", "Draft"/"Published", "Move to draft", "Test", "Runs", "Versions", "Settings", "Start chatbot", "Chatbot is running", "Stop chatbot".
- Commands: `npx vitest run src/app/Chatbots src/app/api --maxWorkers=4`; before the merge `npx tsc --noEmit && npm run lint && npm run build`.

## Review Focus

1. A server problem names a node the canvas no longer has (deleted since the save): it lands in the editor's banner, not in a crash (Task 2, `strayProblemsGoToBanner`).
2. Renaming a node used in another node's text keeps the reference (Task 2, `renameRewritesReferences`).
3. Deleting a button from a Send message node removes its edge, and the next save sends no dangling `sourceHandle` (Task 2, `removedOutputDropsItsEdge`).
4. Closing the browser tab with unsaved changes asks first; navigating within the app asks first (Task 4, `leavingDirtyEditorAsks`).
5. The run overlay for a run whose node was since removed from the flow still lists the step in the drawer (Task 6, `overlayToleratesMissingNode`).

---

### Task 1: API module and fixtures

**Files:**
- Create: `src/app/api/chatbots.ts`, `src/app/api/chatbots.test.ts`, `src/test/chatbots.ts`
- Modify: `src/app/api/cloudMessages.ts` (+ `MediaObject`, `LocationObject`, `CloudMessage.image|video|audio|document|location`, `type` union gains the five), `src/app/api/automations.ts` (+ `'START_CHATBOT'` in `AutomationAction['type']`, `chatbotId?: string | null`, `ifRunning?: 'SKIP' | 'REPLACE' | null`; `AutomationOptions.chatbots: {id; name; status}[]`; `EventData.chat.chatbotRunning: boolean`), `src/app/api/inbox.ts` (+ `ChatView.chatbot: ActiveChatbot | null` with `{runId, chatbotId, name, nodeName, waitingFor: string | null}`; `MessageSource` gains `'CHATBOT'`; `stopChatbot(token, ws, chatId)` → `POST .../inbox/chats/{chatId}/chatbot/stop`)

**Interfaces:**
- Produces (`api/chatbots.ts`): types `NodeType`, `ChatbotStatus = 'DRAFT'|'PUBLISHED'`, `SavedNode {id; type: NodeType; position:{x;y}; data:{name; settings: Record<string, unknown>}}`, `SavedEdge {id; source; sourceHandle: string | null; target}`, `SavedFlow {nodes; edges; viewport?}`, `ChatbotSettings {reminderAfterMinutes: number | null; reminderMessage: string | null; endAfterMinutes: number; endMessage: string | null; invalidReplyRetries: number; retryMessage: string; giveUpMessage: string | null; stopOnAgentReply: boolean}`, `Chatbot {id; name; description; status; flow: SavedFlow; settings; versionNumber; version; createdBy; creationTime; lastUpdateTime; publishedAt; publishedBy}`, `ChatbotSummary {id; name; status; runs7d; activeNow; lastRunAt; lastUpdateTime}`, `Problem {nodeId: string | null; nodeName: string | null; field: string; message: string; severity: 'ERROR'|'WARNING'}`, `SaveRequest {name?; description?; flow; settings; version}`, `SaveResult {chatbot; problems: Problem[]}`, `VersionView {versionNumber; savedAt; savedBy}`, `RunStatus = 'RUNNING'|'WAITING'|'COMPLETED'|'ENDED'|'STOPPED'|'HANDED_OVER'|'JUMPED'|'FAILED'`, `TaskKind = 'WHATSAPP_REPLY'|'FORM'|'TIMER'`, `Waiting {kind: TaskKind; since; expiresAt: string | null; prompt: string | null}`, `RunStep {index; nodeId; nodeName; type; startedAt; finishedAt; outcome: 'DONE'|'WAITING'|'FAILED'; output; summary; error; result; waitedFor: {kind: TaskKind; prompt: string | null} | null}`, `Run {id; chatbotId; chatbotName; versionNumber; chat: {id; remoteId} | null; contact; number: {id; label} | null; status; currentNodeId; currentNodeName; waiting: Waiting | null; test; startedBy; steps; variables; endReason; stoppedBy; createdAt; finishedAt}`, `RunPage {items: Run[]; total: number}`, `ChatbotOptions` (automation options + `attributes: {id; name; key}[]`, `labels: {id; name}[]`, `chatbots: {id; name; status}[]`).
- Functions, all `(token, ws, …)` over `base(ws) = /api/workspaces/${ws}/chatbots`: `listChatbots`, `createChatbot(name)`, `getChatbot(id)`, `saveChatbot(id, body: SaveRequest): Promise<SaveResult>`, `publishChatbot(id)`, `unpublishChatbot(id)`, `deleteChatbot(id)`, `listVersions(id)`, `getVersionFlow(id, n): Promise<SavedFlow>`, `restoreVersion(id, n): Promise<SaveResult>`, `startChatbotTest(id, body: {contactId; phoneNumberId?: string; stopRunning?: boolean}): Promise<Run>`, `listRuns(id, q: {status?; from?; to?; page?; size?}): Promise<RunPage>`, `getRun(id, runId)`, `stopRun(id, runId)`, `uploadChatbotMedia(token, ws, id, type, file): Promise<{id}>` (multipart via `apiFetchFile`), `previewChatbotMessage(token, ws, id, message: CloudMessage): Promise<string>`, `getChatbotOptions()`.
- Fixtures (`src/test/chatbots.ts`): `CHATBOTS_BASE`, `LEAD_QUALIFIER: Chatbot` (DRAFT, three nodes `Welcome` send_message with buttons `yes`/`no`, `AskBudget` ask_question NUMBER, `High` send_message; edges `Welcome reply:yes → AskBudget`, `AskBudget next → High`), `PUBLISHED_BOT`, `SUMMARIES`, `OPTIONS: ChatbotOptions`, `RUN_WAITING: Run` (`waiting: {kind:'WHATSAPP_REPLY', …}`), `RUN_FORM_WAITING` (`kind:'FORM'`), `RUN_DONE`, `chatbotRoutes(overrides)` returning the GET routes for list, get, options, versions, runs.

- [ ] **Step 1: Write the failing API tests**

`chatbots.test.ts`: `saveSendsFlowAndVersion` (`requestTo(f, 'PUT /api/workspaces/w1/chatbots/c1').body` equals the request), `startTestPostsContactAndNumber`, `listRunsBuildsQuery` (`GET …/runs?status=WAITING&page=0&size=25`), `uploadIsMultipart` (body is `FormData` with `file` and `type`), `previewReturnsHtml`, `stopChatbotPostsToInbox`.

- [ ] **Step 2: Run to verify they fail** (`npx vitest run src/app/api/chatbots.test.ts`), implement, run to verify they pass.

- [ ] **Step 3: Commit** `feat(chatbots): API module and fixtures`

---

### Task 2: The flow model

**Files:**
- Create: `src/app/Chatbots/flowModel.ts`, `src/app/Chatbots/flowModel.test.ts`, `src/app/Chatbots/nodeCatalog.ts`, `src/app/Chatbots/nodeCatalog.test.ts`, `src/app/Chatbots/variables.ts`, `src/app/Chatbots/variables.test.ts`

**Interfaces:**
- `nodeCatalog.ts`: `interface NodeMeta { type: NodeType; label: string; description: string; icon: React.ComponentType; defaultSettings(): Record<string, unknown>; outputs(settings): {id: string; label: string}[]; preview(settings, options): string (two lines max); waits(settings): boolean; produces(settings): string[] (output variable fields, e.g. ['answer'], ['reply.id','reply.title','reply.text'], ['response.status','response.body'], ['matched'], ['assignedTo'], ['dealId','created'], and for ask_form 'form.<key>' per field plus 'form.submittedAt'); waitsFor(settings): TaskKind | null (the card's label: 'Waits for a WhatsApp reply' / 'Waits for the form' / 'Waits') }`; `CATALOG: Record<NodeType, NodeMeta>`; `PALETTE: NodeType[]` in spec order. Outputs per type exactly as the backend `Outputs`: send_message → buttons/rows as `reply:<id>` labelled by title plus `other`, or `next` when it has none (cta_url/location_request → `next`); ask_question → `next`, `noAnswer`; ask_form → `next`; condition → `case:<id>` per case plus `otherwise`; http_request → `success`, `failure`; jump → none; others → `next`.
- `flowModel.ts`:
  - `toCanvas(flow: SavedFlow): {nodes: Node<NodeData>[]; edges: Edge[]}` and `fromCanvas(nodes, edges, viewport): SavedFlow` (round-trip keeps `position`, drops `measured/selected/dragging/width/height`); `NodeData = {name; type; settings; problems: Problem[]; entry: boolean; run?: StepState}` with `type StepState = 'waiting' | 'done' | 'failed'`.
  - `uniqueName(type, nodes): string` (`SendMessage`, `SendMessage2`, …), `isValidName(s)`, `renameNode(flow, nodeId, newName): SavedFlow` (rewrites `{{Old.` → `{{New.` in every string of every node's settings), `duplicateNode(flow, nodeId): SavedFlow` (+40/+40, unique name, no edges), `removeNode`, `pruneEdges(flow): SavedFlow` (drops edges whose `sourceHandle` is not among `CATALOG[type].outputs(settings)` or whose target is gone), `entryNodeId(flow): string | null` (the one node with no incoming edge, else null), `canConnect(flow, source, sourceHandle): boolean` (no edge yet from that output), `signature(flow): string` (nodes `{id,x,y,data}` and edges `{id,source,sourceHandle,target}` sorted; the dirty check).
  - `problemsByNode(problems: Problem[], flow): {byNode: Record<string, Problem[]>; flow: Problem[]; stray: Problem[]}` (a problem naming an unknown node is stray); `publishErrorsToProblems(fields: Record<string,string>): Problem[]` (`nodes.<id>.<field>` → `{nodeId:id, field}`, `flow.<f>` → flow ERROR); `countErrors(problems)`.
  - `clientProblems(flow): Problem[]`: name pattern/uniqueness, entry node (exactly one), `send_message` with buttons and no `reply:*` edge (WARNING), jump to self; the same wording as the server where it has one (`"no entry node"`, `"two or more entry nodes"`).
- `variables.ts`: `variableSchema(flow, upstreamOf: string | null, event: 'MESSAGE_RECEIVED'|'DEAL_LANE_CHANGED'|'any'): VariableGroup[]` where `VariableGroup = {label: string; paths: {path: string; hint?: string}[]}`: Contact (`contact.name`, `contact.phone`, `contact.attributes.<key>` from options, `contact.labels`, `contact.doNotContact`), Chat (`chat.id`, `chat.status`, `chat.assignedUser`, `chat.assignedTeam`, `number.label`, `number.phone`), Event (the automations' `FIELDS` per event prefixed `event.`; for `'any'` both events' fields), Trigger (`trigger.ruleName`, `trigger.ruleId`, `trigger.eventId`, `trigger.at`), Now (`now`), and one group per other node (`<Name>.<field>` from `CATALOG[type].produces`); `upstreamOf` orders the nodes that reach it first but lists all. `completeAt(text, caret): {query: string; start: number} | null` (true when the caret follows an unclosed `{{`); `insertAt(text, start, end, path): {text; caret}`.

- [ ] **Step 1: Write the failing model tests**

`flowModel.test.ts`: `roundTripKeepsPositions`, `fromCanvasDropsMeasuredAndSelected`, `uniqueNameCounts`, `renameRewritesReferences` (Review Focus 2: `{{AskBudget.answer}}` in `High` becomes `{{Budget.answer}}`; `{{AskBudgetX.answer}}` untouched), `removedOutputDropsItsEdge` (Review Focus 3: delete button `no` → `pruneEdges` drops `reply:no` edge), `entryNodeIdIsTheOnlyRoot` (a pure 2-cycle → null), `canConnectRefusesSecondEdge`, `signatureIgnoresSelection`, `problemsByNodeBuckets`, `strayProblemsGoToBanner` (Review Focus 1), `publishErrorsParseKeys`, `clientProblemsEntryAndNames`.
`nodeCatalog.test.ts`: `sendMessageOutputsFollowButtons` (`reply:yes`, `reply:no`, `other`), `askFormProducesFieldPaths` (`form.city`, `form.budget`, `form.submittedAt`), `waitsForLabels`, `listOutputsFollowRows`, `ctaHasNextOnly`, `conditionOutputsPerCasePlusOtherwise`, `httpSuccessFailure`, `jumpHasNoOutputs`, `previewTwoLines`.
`variables.test.ts`: `schemaListsUpstreamNodesFirst`, `attributesComeFromOptions`, `completeAtDetectsOpenBraces` (`"Hi {{con"` caret at end → `{query:'con', start:3}`; `"Hi {{contact.name}} x"` → null), `insertAtClosesBraces`.

- [ ] **Step 2: Run to verify they fail, implement the three modules, run `npx vitest run src/app/Chatbots` to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbots): flow model, node catalog and variable schema`

---

### Task 3: Routes, nav, list page

**Files:**
- Modify: `src/app/routes.tsx` (nav `{ label: 'Chatbots', path: '/chatbots', requires: (a) => a.hasInWorkspace('chatbot:view') }` after Automations; routes `/chatbots` list, `/chatbots/:id` editor (lazy), `/chatbots/:id/runs`, with the same `NotFound`-for-`new` trick not needed since creation is a modal), `src/app/routes.test.tsx` (nav visibility), `src/test/session.ts` (`BRAND_CODES` + `chatbot:view`, `chatbot:manage`)
- Create: `src/app/Chatbots/ChatbotsPage.tsx`, `src/app/Chatbots/chatbotsPage.test.tsx`, `src/app/Chatbots/NewChatbotModal.tsx`, `src/app/Chatbots/chatbotDisplay.ts` (`statusLabel`, `statusColor`, `runStatusLabel`, `runStatusColor`, `relativeTime`), `src/app/Chatbots/routes.tsx` (lazy `ChatbotEditorPage` with `React.Suspense` + `Bullseye><Spinner`, spread into `routes`)

**Interfaces:**
- `ChatbotsPage`: `PageShell` "Chatbots" with "New chatbot" (needs `chatbot:manage`) → `NameModal`-style `NewChatbotModal` (`createChatbot` then `navigate('/chatbots/'+id)`); PF `Table` columns Name (link to editor), Status label, Runs (7 days), Active now, Last run, Updated; kebab: Edit/Open, Runs, Delete (drafts only, `ConfirmModal` variant danger). Empty state `EMPTY_TEXT = 'No chatbots yet'`. Loading/error/empty order as `RulesTab`.

- [ ] **Step 1: Write the failing tests**

`chatbotsPage.test.tsx` with `renderApp('/chatbots')` and `chatbotRoutes()`: `listsChatbots` (rows show `Lead qualifier`, `Draft`), `newChatbotCreatesAndOpensEditor` (POST body `{name:'Price bot'}`; `Location` output ends with `/chatbots/c9`), `deleteAsksThenDeletes` (`DELETE …/c1` called once), `viewerSeesNoNewButton` (a user without `chatbot:manage`), `routes.test.tsx`: `chatbotsNavNeedsView`.

- [ ] **Step 2: Run to verify they fail, implement, run to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbots): routes and list page`

---

### Task 4: Editor shell and canvas

**Files:**
- Modify: `package.json` (`@xyflow/react`, exact 12.x version), `stylePaths.js`, `src/app/app.css` (`.omni-flow` full-height canvas under the header bar; node card styles; `.omni-flow--readonly`)
- Create: `src/app/Chatbots/ChatbotEditorPage.tsx`, `src/app/Chatbots/editor/{EditorHeader.tsx,FlowCanvas.tsx,FlowNodeCard.tsx,FlowEdge.tsx,NodePalette.tsx,ProblemsPopover.tsx,useEditorState.ts,useUnsavedGuard.ts}`, `src/test/reactFlowStub.tsx`, `src/app/Chatbots/chatbotEditor.test.tsx`

**Interfaces:**
- `useEditorState(chatbot): {flow; setFlow; dirty; problems; setServerProblems; selectedId; select; save(): Promise<void>; publish(); unpublish(); saving; error}`: `flow` is the `SavedFlow` source of truth; `signature` compares to the last saved; `save` → `saveChatbot(id, {flow: pruneEdges(flow), settings, version})` → stores `problems` and the new `version`; `publish` → on `VALIDATION` converts fields with `publishErrorsToProblems`, selects the first ERROR node and opens its drawer; stale 409 → banner "Someone else saved this chatbot; reload".
- `FlowCanvas` props `{flow; readOnly; problemsByNode; runByNode?; onFlowChange(next: SavedFlow); onSelect(id | null); onAddAt(position, connectFrom?: {source; sourceHandle})}`: `ReactFlowProvider` → `ReactFlow` with `nodeTypes={{ chatbot: FlowNodeCard }}`, `edgeTypes={{ chatbot: FlowEdge }}`, `snapToGrid snapGrid={[20,20]}`, `fitView` on first mount, `minZoom 0.2`, `deleteKeyCode={readOnly ? null : ['Backspace','Delete']}`, `isValidConnection` = `canConnect` (one edge per output) and no self-loop, `onConnectEnd` on the pane → `onAddAt(screenToFlowPosition, connectionState.fromNode/fromHandle)`, `MiniMap`, `Controls`, `Background`; `onNodesChange`/`onEdgesChange` applied then `onFlowChange(fromCanvas(...))`. Drag positions are applied in read-only mode but `onFlowChange` is not called.
- `FlowNodeCard`: icon, name, `CATALOG[type].preview`, the "Entry" tag when `data.entry`, a problem badge (count, red for ERROR, gold for WARNING), run state ring (`waiting` pulse, `done` tick, `failed` cross), one `Handle type="target"` on the left, one `Handle type="source" id={output.id}` per output on the right with its label, a kebab (Duplicate, Rename, Delete) hidden when read-only. `FlowEdge`: bezier with a small × at the midpoint (hover) that removes the edge.
- `NodePalette` (a right-side PF `Drawer` panel, `width_33`): `SearchInput`, one `Card isClickable` per `PALETTE` type with icon, label, description; `onPick(type)`; `draggable` cards carry `dataTransfer 'application/x-chatbot-node'`, the canvas `onDrop` adds at the drop point.
- `EditorHeader`: back link, inline name edit (draft), status `Label`, `Save` (disabled when not dirty; `Ctrl/⌘+S` via a `keydown` listener), `Publish` / `Move to draft`, `Test`, `Runs` (link), `Versions`, `Settings`, `ProblemsPopover` button "`n` problems" listing problems (click → `onSelect(nodeId)`), "+ Add node".
- `useUnsavedGuard(dirty)`: `beforeunload` plus `useBlocker` from react-router (v7) with a `ConfirmModal` "Leave without saving?".
- `src/test/reactFlowStub.tsx`: `vi.mock('@xyflow/react', …)` factory exporting `ReactFlow` (renders `nodes.map(n => <div role="listitem" aria-label={n.data.name}><NodeType …/></div>)`, calls `onNodeClick` on click), `ReactFlowProvider`, `Handle`, `Position`, `MiniMap`, `Controls`, `Background`, `applyNodeChanges`, `applyEdgeChanges`, `addEdge`, `useReactFlow` (returns `{fitView(){}, screenToFlowPosition: p => p}`), `BaseEdge`, `getBezierPath`, `EdgeLabelRenderer`, `useNodeConnections`. Imported by editor tests before the page.

- [ ] **Step 1: Install and wire the dependency**

```bash
npm install --save-exact @xyflow/react@12
```
Add `path.resolve(__dirname, 'node_modules/@xyflow/react')` to `stylePaths.js`; `import '@xyflow/react/dist/style.css'` in `FlowCanvas.tsx`; `npm run build` must pass before the first test (Expected: a `chatbots-editor` chunk in `dist/`).

- [ ] **Step 2: Write the failing editor tests**

`chatbotEditor.test.tsx` (stub imported first; `renderApp('/chatbots/c1')`, `chatbotRoutes()`): `rendersNodesAndEntryTag` (three list items; `Welcome` shows "Entry"), `saveSendsPrunedFlowAndShowsProblems` (press `Save` after a change → `PUT` body `flow.nodes.length === 3`, response problems → "1 problem" button), `ctrlSSaves`, `publishErrorSelectsNode` (`POST …/publish` → 400 `{code:'VALIDATION', fields:{'nodes.n2.settings.text':'is required'}}` → the `AskBudget` drawer opens with the message), `publishedIsReadOnly` (`PUBLISHED_BOT`: no Save, "Move to draft" alert), `addNodeFromPalette` (open palette, pick "Ask a question" → a fourth list item `AskQuestion`), `leavingDirtyEditorAsks` (Review Focus 4: make a change, click the back link → confirm modal; `beforeunload` handler registered: `window.dispatchEvent(new Event('beforeunload'))` sets `returnValue`), `staleSaveShowsBanner` (409 `STALE`).

- [ ] **Step 3: Run to verify they fail, implement the shell, canvas, card, edge, palette, header and guard**

- [ ] **Step 4: Run the tests; then `npx tsc --noEmit && npm run lint`; open the dev server and check the real canvas by hand (drag, connect, one edge per output, × on edge, drop-on-pane adds a node, minimap)**

- [ ] **Step 5: Commit** `feat(chatbots): editor shell and React Flow canvas`

---

### Task 5: Node settings drawer and the eleven forms

**Files:**
- Create: `src/app/Chatbots/editor/NodeDrawer.tsx`, `src/app/Chatbots/editor/VariableInput.tsx`, `src/app/Chatbots/editor/variableInput.test.tsx`, `src/app/Chatbots/nodes/{SendMessageFields,SendTemplateFields,AskQuestionFields,AskFormFields,ConditionFields,CaseEditor,UpdateContactFields,DoNotContactFields,AssignChatFields,AddToCrmFields,HttpRequestFields,WaitFields,JumpFields}.tsx`, `src/app/Chatbots/nodes/nodeFields.test.tsx`, `src/app/Chatbots/nodes/messageDraft.ts` (+ test)
- Modify: `src/app/Inbox/interactiveModel.ts` only if a helper must be exported (`slug`, `withTitle`, `problems`, `toCloudMessage`, `LIMITS` are already exported); `src/app/Automations/{SendTemplateFields,AddToCrmFields,AssignChatModal}.tsx` export their inner field components if they are not already reusable without an action index (keep the automations' tests green)

**Interfaces:**
- `NodeDrawer` props `{node: SavedNode; flow; options: ChatbotOptions; problems: Problem[]; readOnly; onChange(settings); onRename(name); onClose}`: PF `Drawer` panel (`width_50`, `lg: width_33` like `RecordDrawer`), header = icon + name (inline `TextInput` validated by `isValidName`, uniqueness), body = the type's `*Fields` with `{settings, onChange, options, errors: Record<string,string>, isDisabled, variables: VariableGroup[]}` where `errors` maps `problem.field` → message for this node (same `FieldError` pattern as the automations editor).
- `VariableInput` props `{id; value; onChange; variables: VariableGroup[]; isDisabled; multiline?; validated?}`: a `TextArea`/`TextInput` with (a) an "Insert variable" `Dropdown` (groups → items) inserting `{{path}}` at the caret (`insertAt`), (b) a `Menu` popover under the field when `completeAt(value, caret)` is non-null, filtered by `query`, Enter/click inserts.
- `messageDraft.ts`: `MessageDraft = {kind: 'text'|'image'|'video'|'audio'|'document'|'location'|'interactive'; text; media: {id; caption; filename; uploadedName}; location: {latitude; longitude; name; address}; interactive: Draft (from interactiveModel)}`; `toCloudMessage(d): CloudMessage` (no `to`, `messaging_product: 'whatsapp-web'`, `recipient_type: 'individual'`), `fromCloudMessage(m): MessageDraft`, `problems(d): Record<string,string>` (text ≤4096 required; media id required; location coordinates required; interactive → `interactiveModel.problems`), `renderedPaths(d)`.
- Forms (what each exposes, exact setting keys from the backend plan Task 3):
  - `SendMessageFields`: kind `ToggleGroup` (Text, Image, Video, Audio, Document, Location, Interactive); text → `VariableInput multiline`; media → `FileUpload` through `uploadChatbotMedia` + caption `VariableInput` (not for audio) + filename (document); location → four fields; interactive → the interactive composer's form (extract the left column of `InteractiveComposer` into `InteractiveForm.tsx` with props `{draft; onChange; errors; isDisabled; upload(file, type)}` and use it in both places; body/footer/header text become `VariableInput`); a "Preview" `ExpandableSection` with a sandboxed iframe from `previewChatbotMessage`, debounced 400 ms. Shows the outputs it will have ("Replies: Yes, No, Other").
  - `SendTemplateFields`: `TemplateSearchList` pick + agent placeholder values as `VariableInput`s (reuse the automations' component by exporting a `TemplateValuesFields`).
  - `AskQuestionFields`: `text` (VariableInput), `answerType` `Select` (Text, Number, Email, Phone, Date, Location, File (any), Image, Video, Audio, Document), `saveAs` (default placeholder = node name), `attributeId` `Select` (none + `options.attributes`), `retries` `NumberInput` 0–5, `retryMessage`, `giveUpMessage`.
  - `AskFormFields`: `title`, `intro` (VariableInput), fields rows (label, key auto-slugged from the label until edited as `interactiveModel.withTitle` does, type `Select` Text / Long text / Number / Email / Phone / Date / Choice, required switch, options textarea for Choice, remove, move up/down; "Add field", 1–20), `submitText`, `thanksText`, `linkMessage` (VariableInput, default `Please fill in this short form: {{form.url}}`; error when it lacks `{{form.url}}`). Shows "Answers are available as `{{<Name>.form.<key>}}`".
  - `ConditionFields` / `CaseEditor`: a list of cases (label, remove, move up/down; "Add case"); each case is a condition group editor built from the automations' tree helpers (`nodeAt/replaceAt/addTo/removeAt` from `automationModel`) where the field of a leaf is a `VariableInput` (single line) and the operator `Select` lists all `OPERATOR_LABELS`; `is_empty`/`is_not_empty` hide the value; `between` shows two values.
  - `UpdateContactFields`: set attribute rows (attribute `Select` + `VariableInput`), clear attributes multi-select, add/remove labels multi-selects, `nameIfBlank` `VariableInput`.
  - `DoNotContactFields`: radio Mark / Clear.
  - `AssignChatFields`: the automations' assign form inline (mode radio, agent/team selects, when-away), reusing `AssignChatModal`'s body.
  - `AddToCrmFields`: reuse the automations' component (pipeline, lane, deal title as `VariableInput`).
  - `HttpRequestFields`: method `Select`, url `VariableInput`, headers rows (name, `VariableInput` value), content type `Select` (`application/json`, `application/x-www-form-urlencoded`, `text/plain`), body `VariableInput multiline` (monospace), a note "Response is available as `{{<Name>.response.body…}}`".
  - `WaitFields`: amount `NumberInput` + unit `Select` (minutes, hours, days); shows "1 minute to 30 days".
  - `JumpFields`: `Select` of `options.chatbots` minus self, drafts marked "(draft)".

- [ ] **Step 1: Write the failing tests**

`variableInput.test.tsx`: `insertMenuInsertsAtCaret`, `typingBracesOpensSuggestions` (type `{{con` → option `contact.name`; Enter → `{{contact.name}}`).
`messageDraft.test.ts`: `textToCloud`, `imageToCloud` (`{type:'image', image:{id, caption}}`), `locationToCloud`, `interactiveDelegates`, `fromCloudRoundTrips`, `problemsRequireMediaId`.
`nodeFields.test.tsx` (render each form with `OPTIONS` and a spy `onChange`): `askQuestionEditsSettings` (change answer type → `onChange` called with `answerType: 'NUMBER'`), `askFormAddsFieldAndSlugsKey` (label "Monthly budget" → key `monthly_budget`; `linkMessage` without the placeholder shows the error), `conditionAddsCaseAndRule` (`cases.length === 1`, leaf `{field:'{{AskBudget.answer}}', operator:'at_least', value:'5000'}`), `updateContactRow`, `httpHeadersRow`, `waitUnit`, `jumpExcludesSelf`, `sendMessageKindSwitchKeepsInteractiveDraft`, `errorsShowUnderFields` (`errors['settings.text']` renders as helper text), `readOnlyDisablesInputs`, `renameRefusesBadName` ("Ask budget" → error text, `onRename` not called).

- [ ] **Step 2: Run to verify they fail, extract `InteractiveForm` from `InteractiveComposer` (keep `interactiveComposer.test.tsx` green), implement drawer, input and forms**

- [ ] **Step 3: Run `npx vitest run src/app/Chatbots src/app/Inbox src/app/Automations` to verify they pass**

- [ ] **Step 4: Commit** `feat(chatbots): node settings drawer and forms`

---

### Task 6: Test dialog and live run overlay

**Files:**
- Create: `src/app/Chatbots/editor/{TestDialog.tsx,RunOverlayPanel.tsx,useRunPolling.ts}`, `src/app/Chatbots/editor/testDialog.test.tsx`, `src/app/Chatbots/editor/runOverlay.test.tsx`
- Modify: `src/app/Automations/TestContactPicker.tsx` → export the contact search part as `ContactSearchField` (`{onPick(contact: {id; name; phone; numbers: {id; label}[]})}`) if not already separable

**Interfaces:**
- `TestDialog` props `{chatbot; canStart: boolean (no ERROR problems and not dirty); onStarted(run: Run); onClose}`: contact search (`ContactSearchField`), number `Select` when the contact has chats on several numbers, "Start test"; 409 `CHATBOT_RUNNING` → shows "Chatbot “<name>” is running on this chat" with a "Stop it and start the test" button that retries with `stopRunning: true`; dirty editor → "Save first". The project rule is shown as a hint: "Messages really go to this contact."
- `useRunPolling(chatbotId, runId | null): {run: Run | null; error}` polls `getRun` every 2 000 ms while `run.status` is `RUNNING|WAITING`, stops otherwise, cleans up on unmount.
- `RunOverlayPanel` props `{run; flow; onStop(); onClose()}`: a right-hand PF `Drawer` panel: status label, "waiting at <node> for a WhatsApp reply / for the form / until <time>" from `run.waiting` (`waitingLabel(waiting)` in `chatbotDisplay.ts`), `Stop` (active runs), steps list (node name, outcome icon, summary, time; a step whose node is missing from the flow shows its stored `nodeName` with "(removed)"), a "Variables" `ExpandableSection` with a `TreeView` of `run.variables` where each leaf has a copy button for its `{{path}}`. The page maps `run.steps`/`run.currentNodeId` to `runByNode` for `FlowCanvas` (`waiting` for `currentNodeId` when WAITING, `done`/`failed` per step).

- [ ] **Step 1: Write the failing tests**

`testDialog.test.tsx`: `startsTestWithContactAndNumber` (POST body `{contactId:'k1', phoneNumberId:'n1'}`), `runningConflictOffersStopAndStart` (first POST 409 `{code:'CHATBOT_RUNNING', extra:{chatbotName:'Price bot'}}` → text, click → second POST body has `stopRunning: true`), `disabledWhenErrors`.
`runOverlay.test.tsx` (fake timers): `pollsEverySecondsWhileActive` (`RUN_WAITING` then `RUN_DONE`: `callsTo(f, 'GET …/runs/r1')` is 2 after 2 s, no third call after 4 s), `showsStepsAndWaitingNode` (text contains "for a WhatsApp reply"; with `RUN_FORM_WAITING` "for the form"), `overlayToleratesMissingNode` (Review Focus 5: a step with `nodeId:'gone'` renders "(removed)"), `copyVariablePath` (`navigator.clipboard.writeText` called with `{{AskBudget.answer}}`), `stopCallsApi`.

- [ ] **Step 2: Run to verify they fail, implement, run to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbots): test dialog and live run overlay`

---

### Task 7: Runs page, versions drawer, settings modal

**Files:**
- Create: `src/app/Chatbots/ChatbotRunsPage.tsx`, `src/app/Chatbots/chatbotRuns.test.tsx`, `src/app/Chatbots/editor/{VersionsDrawer.tsx,SettingsModal.tsx}`, `src/app/Chatbots/editor/versionsDrawer.test.tsx`, `src/app/Chatbots/editor/settingsModal.test.tsx`

**Interfaces:**
- `ChatbotRunsPage` (`/chatbots/:id/runs`): `PageShell` "<name> · Runs"; toolbar: status `Select` (All + the nine), from/to `DatePicker`s; table: Contact (name or phone), Number (or "—"), Status label (a WAITING run adds "for a WhatsApp reply" / "for the form" / "until <time>"), Started, Ended, Where it ended (`currentNodeName`), Started by (`startedBy.ruleName` or "Test"); row click opens `RunOverlayPanel` (read-only variant: no polling for final runs) with a "Show on canvas" link `/chatbots/:id?run=<runId>` which the editor opens read-only with the overlay. Pager by `page`/`size` 25.
- `VersionsDrawer` props `{chatbot; readOnly; onPreview(flow | null); onRestored(result: SaveResult)}`: list of versions (number, `savedAt`, `savedBy.name`), "Preview" draws that flow on the canvas read-only with a top `Alert` "Previewing version n" + "Restore" (draft only, `ConfirmModal`) + "Exit preview".
- `SettingsModal` props `{settings; readOnly; onSave(settings)}`: Inactivity reminder (switch + minutes 1–1440 + message), Inactivity end (minutes 5–2880 + optional message), Invalid replies (retries 0–5, retry message, give-up message), "Stop when an agent replies" switch; local validation of ranges; saving goes through the editor's `save` (settings travel with the flow).

- [ ] **Step 1: Write the failing tests**

`chatbotRuns.test.tsx`: `listsRunsAndFilters` (status select → `GET …/runs?status=FAILED…`), `openRunShowsSteps`, `showOnCanvasLinks`.
`versionsDrawer.test.tsx`: `listsVersions`, `previewThenExit` (`onPreview` called with the version's flow then `null`), `restoreConfirmsAndCalls` (`POST …/versions/2/restore`).
`settingsModal.test.tsx`: `rangesValidated` (end 3 → error), `savesSettings` (`onSave` with `endAfterMinutes: 90`, `stopOnAgentReply: false`).

- [ ] **Step 2: Run to verify they fail, implement, wire the editor's `?run=` and Versions/Settings buttons, run to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbots): runs page, versions and settings`

---

### Task 8: Automations and inbox integration

**Files:**
- Modify: `src/app/Automations/{automationModel.ts,ActionList.tsx}` (+ `ACTIONS` entry `{type:'START_CHATBOT', label:'Start chatbot', description:'Starts a chatbot on the chat; skips or replaces one already running'}`, `newAction('START_CHATBOT')` → `{type, chatbotId: null, ifRunning: 'SKIP'}`, `actionSummary` → `Start “<name>”`; `FIELDS.MESSAGE_RECEIVED` + `{key:'chat.chatbotRunning', label:'Chatbot is running', type:'boolean', …}` with `BOOLEAN_CHOICES['chat.chatbotRunning'] = ['Running', 'Not running']`), create `src/app/Automations/StartChatbotFields.tsx` (chatbot `Select` from `options.chatbots`, published first, drafts "(draft)"; `ifRunning` radio "Skip" / "Replace the running chatbot"; `startChatbotShows(key)`), `src/test/automations.ts` (`OPTIONS.chatbots`)
- Modify: `src/app/Inbox/ChatHeader.tsx` (+ chip `Label icon={RobotIcon}` "Chatbot: <name> · waiting at <node> for <waitingFor>" with a `Stop chatbot` button when `access.hasInWorkspace('inbox:send')`, calling `stopChatbot` then `onChange(chat)` from the response/refetch), `src/app/Inbox/MessageBubble.tsx` (source `'CHATBOT'` → `sentBy?.name ?? 'Chatbot'`), `src/app/Inbox/ChatList.tsx` (a small robot icon next to the name when `chat.chatbot`), `src/app/Inbox/chatDisplay.ts` if the list helper lives there
- Test: `src/app/Automations/automationEditor.test.tsx` (+ `startChatbotActionEdits`, `chatbotRunningCondition`), `src/app/Inbox/chatHeader.test.tsx` (+ `showsRunningChatbotAndStops`), `src/app/Inbox/thread.test.tsx` (+ `chatbotBubbleLabel`), `src/app/Inbox/chatList.test.tsx` (+ `robotIconForRunningChatbot`)

- [ ] **Step 1: Write the failing tests**

`startChatbotActionEdits`: add action "Start chatbot", pick "Lead qualifier", choose Replace → saved body `actions[0] == {type:'START_CHATBOT', chatbotId:'c1', ifRunning:'REPLACE'}`; `chatbotRunningCondition`: field select offers "Chatbot is running", value choices Running/Not running. `showsRunningChatbotAndStops`: chat with `chatbot:{runId:'r1', chatbotId:'c1', name:'Lead qualifier', nodeName:'AskBudget', waitingFor:'WhatsApp reply'}` → chip text contains "for WhatsApp reply"; click Stop → `POST …/inbox/chats/ch1/chatbot/stop` once. `chatbotBubbleLabel`: an OUT message `source:'CHATBOT', sentBy:{id:null, name:'Chatbot: Lead qualifier'}` → small text. `robotIconForRunningChatbot`.

- [ ] **Step 2: Run to verify they fail, implement, run `npx vitest run src/app/Automations src/app/Inbox` to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbots): Start chatbot action and inbox chip`

---

### Task 9: Verification and docs

- [ ] **Step 1: Run the whole console check**

```bash
npx vitest run --maxWorkers=4 && npx tsc --noEmit && npm run lint && npm run build
```
Expected: all green; `dist/` has a separate editor chunk; `npm run bundle-profile:analyze` shows `@xyflow/react` only in that chunk.

- [ ] **Step 2: Manual check on the dev server against the backend worktree** (`npm run start:dev` with `BACKEND_URL`): build "Lead qualifier" with one node of each type (an Ask with a form among them), connect, save, see problems, publish, move to draft; open Runs; open a form link in a phone-width window and submit it; the inbox chip appears on a chat with a run (use the backend's test start on a contact the user names, only within sending hours and only when the user names the recipient).

- [ ] **Step 3: Commit** `chore(chatbots): verification notes`
