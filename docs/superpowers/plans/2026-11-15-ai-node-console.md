# AI node — console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The brand AI page (connections with write-only keys, profiles with provider-driven fields) and the `ai`
node in the chatbot editor.

**Architecture:** New `src/app/AI/` area (API module, page with two tabs, forms) beside the chatbot editor; the node is
one more `CATALOG` entry and `FIELDS.ai` form; the provider's capabilities, fetched once, decide which fields show.

**Tech Stack:** React 18, PatternFly 6.5, `@xyflow/react` 12, vitest + Testing Library, `src/test/fetchMock.ts`.

**Spec:** backend repo `docs/superpowers/specs/2026-11-15-ai-node-design.md` §5.8–§5.10; the wire contract is the
"Console contract" section at the end of `docs/superpowers/plans/2026-11-15-ai-node-backend.md`.

## Global Constraints

- Worktree `omnichannel-crm-frontend/.worktrees/ai-node`; `npm ci --prefer-offline` first.
- A key or MCP secret typed by the user is sent once and never shown again: the inputs are `type="password"`,
  `autoComplete="new-password"`, and cleared after a successful save; views show only the hint.
- `tsconfig` target es5: no `[...new Set()]` (use `Array.from`); `ApiError` checks as elsewhere.
- Node outputs and produced variables must equal the backend's exactly: SINGLE `next`|`outcome:<id>…`, `failure`;
  CONVERSATION `outcome:<id>…`, `exitWord`?, `exitCondition`?, `maxTurns`, `failure`.
- Prettier only on touched files; tests with `--maxWorkers=4`; console tests stub React Flow by importing
  `src/test/reactFlowStub.tsx` first.
- No "template" naming.

## Review Focus

1. Editing a connection's name without touching the key must not send `apiKey` at all (an empty string would be read
   as "replace with empty"). → test in Task 2.
2. Switching a profile's connection to a provider without hosted MCP while a server is set to "Provider" must show the
   problem on that row before save, not only the server's 400. → test in Task 3.
3. Renaming an outcome id in the node form must keep the edge if only the label changed, and drop the handle (edge
   shown as dangling problem, as the condition node does) when the id changed. → test in Task 4.
4. A chatbot editor without `ai:view` opening an AI node sees the profile name from chatbot options and no link to
   the AI page. → test in Task 4.
5. A 503 `secrets_unavailable` on save shows the server's message on the key field, not a generic error. → test in
   Task 2.

---

### Task 1: API module and fixtures

**Files:**
- Create: `src/app/api/ai.ts`, `src/test/ai.ts` (fixtures: `PROVIDERS` with `openai` and `scripted`, `CONNECTION`, `PROFILE`, `aiRoutes(overrides)` for `mockFetch`).
- Modify: `src/app/api/chatbots.ts` (`NodeType` + `'ai'`, `TaskKind` + `'AI_RESPONSE'`, `ChatbotOptions.aiProfiles: AiProfileRef[]`), `src/test/chatbots.ts` (`OPTIONS.aiProfiles`), `src/test/session.ts` (`BRAND_CODES` + `ai:view`, `ai:manage`).
- Test: `src/app/api/ai.test.ts`.

**Interfaces:**
- Produces types `AiCapabilities`, `AiProvider`, `AiConnection`, `AiConnectionSave`, `AiModel`, `AiVectorStore`, `AiProfile`, `AiProfileSave`, `McpServer`, `McpServerSave`, `AiProfileRef` (fields exactly as the console contract); functions `listAiProviders(token, ws)`, `listAiConnections`, `getAiConnection`, `createAiConnection(token, ws, save)`, `saveAiConnection(token, ws, id, save)`, `deleteAiConnection`, `verifyAiConnection`, `listAiModels(token, ws, connectionId)`, `listAiVectorStores`, `listAiProfiles`, `getAiProfile`, `createAiProfile`, `saveAiProfile`, `deleteAiProfile`, `listMcpTools(token, ws, profileId, serverId)`; base `/api/workspaces/${encodeURIComponent(ws)}/ai`.

- [ ] **Step 1: Write the failing test** `ai.test.ts`: each function hits its method + path; `saveAiConnection` with `apiKey: undefined` sends a body without the `apiKey` key (`requestTo(...)` body `not.toHaveProperty('apiKey')`); a 409 `ai_profile_in_use` surfaces as `ApiError` with `code`.
- [ ] **Step 2: Run** `npx vitest run src/app/api/ai.test.ts --maxWorkers=4` — Expected: FAIL (module missing).
- [ ] **Step 3: Implement** like `api/chatbots.ts`.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): console API module for AI connections and profiles`.

### Task 2: AI page — Connections tab

**Files:**
- Create: `src/app/AI/AiPage.tsx` (PageShell "AI", `Tabs` Connections | Profiles, tab in `?tab=`), `src/app/AI/ConnectionsTab.tsx`, `src/app/AI/ConnectionModal.tsx`, `src/app/AI/SecretField.tsx`, `src/app/AI/routes.tsx` (`aiRoutes`: `/ai`, `requires: (a) => a.hasInWorkspace('ai:view')`).
- Modify: `src/app/routes.tsx` (route list + nav link `{ label: 'AI', path: '/ai', requires: (a) => a.hasInWorkspace('ai:view') }` in the Brand setup section after Phone numbers).
- Test: `src/app/AI/aiConnections.test.tsx`, nav case in `src/app/routes.test.tsx`.

**Interfaces:**
- Produces: `SecretField({id, label, hint, setAt, value, onChange, isDisabled, error})` — with a hint it shows "`sk-…a91F` · set 12 Nov 2026" and a **Replace key** link that reveals the password input; without one, the input directly; show/hide eye toggle as `admin/PersonFields.tsx`. Task 3 reuses it for MCP secrets.

- [ ] **Step 1: Write the failing tests**:
  - `listsConnectionsWithStatus` (name, provider label, hint, "Verified" / "Key refused" label from `status`).
  - `createSendsKeyOnceAndClearsIt` (modal: provider select, name, key → POST body has `apiKey`; after success the modal closes and reopening shows the hint, an empty input).
  - `editNameOnlyOmitsKey` — Review Focus 1.
  - `replaceKeySendsNewKey`.
  - `verifyShowsProviderMessage` (`lastError` shown in a danger alert).
  - `secretsUnavailableOnKeyField` (503 `secrets_unavailable` message under the key field) — Review Focus 5.
  - `deleteInUseShowsNames` (409 message).
  - `viewerWithoutManageSeesNoButtons`.
  - `storedResponsesNote` (OpenAI provider shows "OpenAI keeps stored responses for 30 days in your OpenAI account.").
  - `routes.test.tsx`: AI link shown with `ai:view`, hidden without.
- [ ] **Step 2: Run** `npx vitest run src/app/AI src/app/routes.test.tsx --maxWorkers=4` — Expected: FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): AI page with connections and write-only keys`.

### Task 3: AI page — Profiles tab

**Files:**
- Create: `src/app/AI/ProfilesTab.tsx`, `src/app/AI/ProfileModal.tsx`, `src/app/AI/McpServersFields.tsx`, `src/app/AI/profileProblems.ts`.
- Test: `src/app/AI/aiProfiles.test.tsx`, `src/app/AI/profileProblems.test.ts`.

**Interfaces:**
- Consumes: Task 1 API, `SecretField`.
- Produces: `profileProblems(save: AiProfileSave, caps: AiCapabilities | null): Record<string, string>` keyed like the server (`mcpServers.0.connectBy`, `vectorStoreIds`, `temperature`, `reasoningEffort`, `webSearch`), copy identical to the server's messages ("This provider cannot connect to MCP servers itself; choose Our app").

- [ ] **Step 1: Write the failing tests**:
  - `profileProblems.test.ts`: one case per capability flag; none for OpenAI capabilities.
  - `aiProfiles.test.tsx`: `fieldsFollowCapabilities` (OpenAI connection shows Reasoning effort, Temperature, Web search, Knowledge, Provider option; `scripted` hides Knowledge, Web search, Temperature and disables "Provider" with the reason text); `modelListedWithFreeText` (models from `listAiModels`, typing a name not listed is allowed); `vectorStoresChecklistAndRefresh`; `mcpRowsAddRemove` with auth None/Bearer/Header (header name field only for Header); `loadToolsForAppServer` (lists tool names into the allowed-tools picker; button absent for Provider rows); `switchConnectionFlagsProviderRow` — Review Focus 2; `serverFieldErrorsLandOnRows` (400 `mcpServers.1.url`); `mcpSecretWriteOnly`; `deleteInUseShowsChatbotNames`; `leastPrivilegeNote` ("Tools run with this server's own access. Give it only what the bot needs, and list the allowed tools.").
- [ ] **Step 2: Run** `npx vitest run src/app/AI --maxWorkers=4` — Expected: FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): AI profiles with provider-driven fields and MCP servers`.

### Task 4: The AI node in the editor

**Files:**
- Modify: `src/app/Chatbots/nodeCatalog.ts` (`CATALOG.ai`, `PALETTE` after `http_request`, `WAIT_LABELS.AI_RESPONSE = 'Waits for the AI'`), `src/app/Chatbots/nodes/index.ts` (`FIELDS.ai = AiFields`), `src/app/Chatbots/nodes/ConditionFields.tsx` (extract the one-group rule editor as exported `ConditionGroupFields({group, onChange, vars, isDisabled, idPrefix})`, used by both).
- Create: `src/app/Chatbots/nodes/AiFields.tsx`, `src/app/Chatbots/nodes/aiOutputs.ts`.
- Test: `src/app/Chatbots/nodes/aiFields.test.tsx`, cases in `src/app/Chatbots/nodeCatalog.test.ts` and `src/app/Chatbots/variables.test.ts`.

**Interfaces:**
- Consumes: `ChatbotOptions.aiProfiles`, `listAiProviders` (capabilities for the selected profile's provider; fetched once per editor, failure → all fields shown), `getAiProfile` only when the user has `ai:view` (for inherited placeholders), Task 1 types.
- Produces: `aiOutputs(settings) -> NodeOutput[]` (labels: outcome label or id, "Exit word", "Exit condition", "Max turns", "Failure", "Next"); `CATALOG.ai`: label "AI", icon `RobotIcon` (fallback `MagicIcon` if not exported), description "Asks an AI model once, or lets it talk with the customer until an outcome.", `defaultSettings: {mode:'CONVERSATION', profileId:'', overrides:{}, outcomes:[], collect:[], exitWords:[], maxTurns:20, replyFormat:{type:'TEXT'}}`, `waitsFor`: CONVERSATION → `WHATSAPP_REPLY`, SINGLE → `AI_RESPONSE`, `produces`: `reply, outcome, turns, usage.inputTokens, usage.outputTokens, conversationId, errorCode` + `fields.<key>` per collect + `json` when JSON format; preview "<profile name> · Conversation" or "· One call".

Form sections in order: Profile (select from `aiProfiles`; empty list → "No AI profiles yet" with a link to `/ai` only when `ai:view`), Mode (toggle One call | Conversation), Messages (`VariableInput` user message, initial message for CONVERSATION, extra instructions; "Send the reply to the customer" checkbox for One call), Outcomes (rows id/label/description; id auto-slugged from the label while untouched), Collect (rows key/description/type), Reply format (Text | JSON with a JSON schema textarea validated as JSON and a message field select from the schema's string properties), Ending (CONVERSATION: exit words as chips, exit condition via `ConditionGroupFields` over `{{<ThisNode>.json.*}}`, max turns 1–100), Advanced (overrides model, instructions, reasoning effort, max output tokens, temperature — placeholders "`<value>` — profile" and a Reset link as `LayeredSettingsForm`; hidden per capabilities), Continue from (select of earlier AI node names).

- [ ] **Step 1: Write the failing tests**:
  - `nodeCatalog.test.ts`: `aiOutputsPerMode` (the three cases of the backend's `outputsPerMode`, byte-equal ids), `aiProducesPerSettings`, `aiWaitsForPerMode`.
  - `variables.test.ts`: an AI node named `Sales` with collect `size` offers `{{Sales.fields.size}}` and `{{Sales.reply}}` downstream.
  - `aiFields.test.tsx`: `profileFromOptions`; `noProfilesLinksOnlyWithAiView` and `editorWithoutAiViewSeesProfileName` — Review Focus 4; `modeTogglesSections`; `outcomeLabelSlugsId`; `outcomeIdChangeDropsHandle` and `labelChangeKeepsEdge` — Review Focus 3; `overridePlaceholderShowsProfileValueAndReset`; `capabilitiesHideTemperature` (scripted provider); `jsonSchemaInvalidShowsError`; `messageFieldFromSchema`; `exitConditionUsesGroupEditor`; `serverErrorsShowOnFields` (`errors['settings.outcomes.1.id']`).
  - `ConditionFields` existing tests still pass after the extraction.
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots --maxWorkers=4` — Expected: FAIL (new cases only).
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(chatbot): AI node in the editor`.

### Task 5: Run panel and the whole console check

**Files:**
- Modify: `src/app/Chatbots/editor/RunOverlayPanel.tsx` and the runs page step rendering (`ChatbotRunsPage.tsx`): an AI step shows, from `run.variables[<node name>]` (`turns, lastReply, outcome, usage`), "Turn n", the last reply (cut to 200 chars), the outcome and "in / out tokens"; a run waiting on `AI_RESPONSE` shows "The AI is thinking…".
- Test: cases in `src/app/Chatbots/editor/runOverlay.test.tsx`, `src/app/Chatbots/chatbotRuns.test.tsx`.

- [ ] **Step 1: Write the failing tests**: `aiStepShowsTurnReplyOutcomeTokens`, `waitingOnAiShowsThinking`.
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/editor src/app/Chatbots/chatbotRuns.test.tsx --maxWorkers=4` — Expected: FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** `npx vitest run --maxWorkers=4 && npx tsc --noEmit && npm run lint && npm run build` — Expected: all green (note by name any known flaky test, e.g. `automationTest.test.tsx`, if it fails, and re-run it alone).
- [ ] **Step 5: Commit** `feat(chatbot): AI steps in the run panel`.
