# Chatbot Settings Layers — Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared settings form used at three layers (platform defaults page, brand settings page, the editor's
Settings dialog), showing for each field whether it is set here or inherited and from where.

**Architecture:** A pure model (`settingsLayers.ts`) holds the nine fields, the per-field draft states and validation;
`LayeredSettingsForm` renders it for any layer; three thin hosts load and save through the backend plan's endpoints.

**Tech Stack:** React 18, PatternFly 6.5, react-router-dom 7, vitest + Testing Library.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-11-14-chatbot-settings-layers-design.md`
(backend contract: `…/plans/2026-11-14-chatbot-settings-layers-backend.md` Tasks 2–3 Interfaces).

## Global Constraints

- Work in `omnichannel-crm-frontend/.worktrees/chatbot-settings-layers`.
- Field names, ranges and the `null` = inherited / `""` = "send no message" rule exactly as the backend plan's Global
  Constraints. Optional-message "none" exists only for `endMessage` and `giveUpMessage`.
- Placeholder copy for an inherited field: `<value> — brand setting` or `<value> — platform default` (long messages
  cut to 40 characters + `…`). Switch helper line: `From the brand's settings` / `From the platform defaults`.
- Reminder conflict warning (not an error): `The reminder comes at or after the end, so it will never be sent.`
- Page copy: platform `Every brand uses these until it sets its own.`; brand `Your chatbots use these unless a
  chatbot sets its own. Empty fields follow the platform default.`
- Routes: `/platform/chatbot-settings` (requires `a.has('chatbot-defaults:manage')`), `/chatbots/settings`
  (requires `a.hasInWorkspace('chatbot:view')`; read-only without `chatbot:manage`).
- tsconfig targets es5: no `[...new Set()]`, use `Array.from`. Prettier only on touched files.

## Review Focus

1. Reset on a field whose inherited value is a long message — expected: field empties, placeholder shows the cut
   inherited text. (Task 2 test `resetShowsTheInheritedValue`.)
2. Ticking "Send no message" then unticking it — expected: back to inherited (null), not `""`. (Task 1 test
   `untickingNoneReturnsToInherited`.)
3. Typing a value then clearing the input — expected: inherited again, not a validation error. (Task 1 test
   `clearedInputIsInherited`.)
4. Brand page opened by a `chatbot:view`-only user — expected: everything disabled, no Save. (Task 4 test.)
5. `/chatbots/settings` must not be caught by `/chatbots/:id` — expected: the settings page, not an editor 404.
   (Task 4 test `settingsRouteIsNotAChatbotId`.)

---

### Task 1: API types and the layer model

**Files:**
- Modify: `src/app/api/chatbots.ts` (types + four functions)
- Create: `src/app/Chatbots/settings/settingsLayers.ts`
- Test: `src/app/Chatbots/settings/settingsLayers.test.ts`, `src/app/api/chatbotSettings.test.ts`

**Interfaces:**
- Produces (in `api/chatbots.ts`):
  - `interface SettingsLayer { reminderOn: boolean|null; reminderAfterMinutes: number|null; reminderMessage:
    string|null; endAfterMinutes: number|null; endMessage: string|null; invalidReplyRetries: number|null;
    retryMessage: string|null; giveUpMessage: string|null; stopOnAgentReply: boolean|null }`;
    `type SettingField = keyof SettingsLayer`; `type SettingSource = 'CHATBOT'|'BRAND'|'PLATFORM'`.
  - `Chatbot.settings: SettingsLayer`, plus `inheritedSettings: SettingsLayer`, `inheritedSources:
    Record<SettingField, 'BRAND'|'PLATFORM'>`; `SaveRequest.settings?: SettingsLayer`. Remove the old `ChatbotSettings`.
  - `getPlatformChatbotSettings(token)` → `{settings, version, lastUpdateTime, updatedBy}`;
    `savePlatformChatbotSettings(token, {settings, version})`; `getBrandChatbotSettings(token, ws)` → `{own,
    inherited, version}`; `saveBrandChatbotSettings(token, ws, {own, version})`. Paths `/api/platform/chatbot-settings`,
    `/api/workspaces/{ws}/chatbots/settings`.
- Produces (in `settingsLayers.ts`):
  - `FIELDS: SettingField[]`, `OPTIONAL_MESSAGES: SettingField[] = ['endMessage','giveUpMessage']`, `LIMITS` (moved
    from `SettingsModal`).
  - `type FieldDraft = { kind: 'inherit' } | { kind: 'set'; text: string } | { kind: 'none' }` (booleans use
    `text` `'true'|'false'`); `type LayerDraft = Record<SettingField, FieldDraft>`.
  - `toDraft(layer: SettingsLayer): LayerDraft`; `fromDraft(d: LayerDraft): SettingsLayer` (empty `set` text →
    `null`; `none` → `""`).
  - `effective(own: SettingsLayer, inherited: SettingsLayer): SettingsLayer` (`own ?? inherited` per field).
  - `layerProblems(d: LayerDraft, complete: boolean): Partial<Record<SettingField, string>>` — messages as the
    backend's: `must be 1 to 1440 minutes`, `must be 5 to 2880 minutes`, `must be 0 to 5`, `is required`,
    `must be at most 1024 characters`; `complete` requires every non-optional field to be `set`.
  - `reminderWarning(eff: SettingsLayer): string | null` (the Global Constraints copy when `reminderOn` and
    `reminderAfterMinutes >= endAfterMinutes`).

- [ ] **Step 1: Write the failing tests**: `roundTripsALayer` (null ↔ inherit, `""` ↔ none for optional messages,
  values ↔ set); `clearedInputIsInherited` (`{kind:'set', text:''}` → null, no problem); `untickingNoneReturnsToInherited`
  (none → inherit → `fromDraft` gives null); `rangesAndRequiredMessages` (each message above);
  `completeLayerNeedsEveryRequiredField`; `effectivePrefersOwn`; `reminderWarningOnlyWhenOnAndLate`;
  `apiPathsAndBodies` (fetch mock sees the four paths, methods and JSON bodies).
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/settings src/app/api/chatbotSettings.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces. `SettingsModal.tsx` keeps compiling by importing `LIMITS` from here
  until Task 3 replaces it.
- [ ] **Step 4: Run** the same command and `npx tsc --noEmit`. Expected: PASS (tsc may list editor errors fixed in
  Task 3 — if so, adapt `SettingsModal` minimally to the new type now so tsc is clean).
- [ ] **Step 5: Commit** `feat(chatbots): settings layer types and model`.

### Task 2: `LayeredSettingsForm`

**Files:**
- Create: `src/app/Chatbots/settings/LayeredSettingsForm.tsx`
- Test: `src/app/Chatbots/settings/layeredSettingsForm.test.tsx`

**Interfaces:**
- Consumes: Task 1's model.
- Produces: `LayeredSettingsForm: React.FC<{ value: LayerDraft; onChange(d: LayerDraft): void; inherited:
  SettingsLayer | null; sources: Partial<Record<SettingField, 'BRAND'|'PLATFORM'>>; readOnly: boolean; errors:
  Partial<Record<SettingField, string>> }>`. `inherited === null` means the platform layer (no inherit state, no
  Reset). The four groups and labels of today's `SettingsModal` (Inactivity reminder, Inactivity end, Invalid
  replies, Agent reply). The reminder-minutes and reminder-message fields show whenever the effective `reminderOn` is
  true. "Reset" is a link button with `aria-label="Reset <label>"`; "Send no message" is a checkbox per optional
  message; the warning from `reminderWarning(effective(...))` shows under the reminder group.

- [ ] **Step 1: Write the failing tests**: `inheritedFieldShowsValueAndSource` (empty input, placeholder
  `120 — brand setting`); `typingSetsTheField`; `resetShowsTheInheritedValue` (long inherited message cut to 40 +
  `…`); `sendNoMessageTicks` (draft becomes `none`, input disabled); `inheritedSwitchShowsHelper` ("From the platform
  defaults"); `platformLayerHasNoResetOrPlaceholders`; `readOnlyDisablesEverything`; `warningShownWhenReminderLate`.
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/settings`. Expected: FAIL.
- [ ] **Step 3: Implement** with PatternFly `FormGroup`, `TextInput`, `TextArea`, `Switch`, `Checkbox`, `Button
  variant="link"`, `HelperText`. A switch for an inherited boolean shows the inherited state; toggling it sets the field.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(chatbots): layered settings form`.

### Task 3: Editor Settings dialog on the chatbot layer

**Files:**
- Modify: `src/app/Chatbots/editor/SettingsModal.tsx`, `src/app/Chatbots/editor/useEditorState.ts`,
  `src/app/Chatbots/ChatbotEditorPage.tsx:248-255`
- Test: `src/app/Chatbots/editor/settingsModal.test.tsx` (rewrite), `src/app/Chatbots/chatbotEditor.test.tsx` (fixtures)

**Interfaces:**
- Consumes: `LayeredSettingsForm`, `toDraft/fromDraft/layerProblems`, `Chatbot.inheritedSettings/inheritedSources`.
- Produces: `SettingsModal` props `{ settings: SettingsLayer; inherited: SettingsLayer; sources: …; readOnly; onSave(s:
  SettingsLayer); onClose }`; `useEditorState`'s settings setter takes a `SettingsLayer`.

- [ ] **Step 1: Write the failing tests**: `opensWithInheritedValues` (new chatbot fixture: all null, placeholders
  from `inheritedSettings`); `savesOnlyWhatWasSet` (type end 30 → `onSave` gets `{…all null, endAfterMinutes: 30}`);
  `resetReturnsAFieldToTheBrand`; `readOnlyDisablesEverything`; `rangesValidated` (kept from today).
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/editor src/app/Chatbots/chatbotEditor.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement**: the modal body is `LayeredSettingsForm` with `inherited`/`sources` from the chatbot;
  update fixtures in `chatbotEditor.test.tsx` to the new `Chatbot` shape.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(chatbots): chatbot settings inherit from the brand`.

### Task 4: Brand and platform pages, routes and nav

**Files:**
- Create: `src/app/Chatbots/ChatbotSettingsPage.tsx`, `src/app/Chatbots/ChatbotDefaultsPage.tsx` (no Platform folder
  exists; it sits with the other chatbot pages)
- Modify: `src/app/routes.tsx` (Chatbots becomes a group `All chatbots` / `Settings`; Platform group gains
  `Chatbot defaults`), `src/app/Chatbots/routes.tsx` if the settings route belongs there
- Test: `src/app/Chatbots/chatbotSettingsPage.test.tsx`, `src/app/Chatbots/chatbotDefaults.test.tsx`, nav cases in
  `src/app/routes.test.tsx`

**Interfaces:**
- Consumes: Task 1's API functions, Task 2's form.
- Produces: the two pages. Both: load, show the form, Save (disabled while there are problems or nothing changed),
  success toast `Settings saved`, server field errors (`own.<field>` / `settings.<field>`) mapped onto fields, a 409
  shows `Someone else changed these settings. Reload to see their version.` with a Reload button.

- [ ] **Step 1: Write the failing tests**: brand `showsInheritedPlatformValues`, `savesTheOwnLayer` (PUT body
  `{own, version}`), `readOnlyWithoutManage`, `serverFieldErrorLandsOnTheField`, `staleSaveOffersReload`; platform
  `savesEveryField`, `hiddenWithoutTheDefaultsRight` (route guard); nav `chatbotsIsAGroupWithSettings`,
  `platformShowsChatbotDefaultsOnlyWithTheRight`, `settingsRouteIsNotAChatbotId`.
- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots src/app/routes.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement** the pages (copy from Global Constraints) and routes.
- [ ] **Step 4: Run** the same command, then the whole check: `npx vitest run --maxWorkers=4 && npx tsc --noEmit &&
  npm run lint && npm run -s build`. Expected: all pass, build exit 0.
- [ ] **Step 5: Commit** `feat(chatbots): brand chatbot settings and platform defaults pages`.
