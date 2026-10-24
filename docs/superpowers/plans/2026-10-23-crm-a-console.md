# CRM phase A (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the console the CRM of phase A: a status gate and CRM nav group, the platform switch, settings (general, pipelines and lanes), pipeline assignment from the Users page, the pipeline board with drag and drop, deal/contact/company pages and drawers with the timeline, and the CRM panel in the inbox.

**Architecture:** React 18 + PatternFly 6 in `omnichannel-crm-frontend`. All CRM code lives under `src/app/Crm/` and reaches the server only through `src/app/api/crm.ts`. A `CrmStatusProvider` loads `/crm/status` once per brand switch; CRM pages are `React.lazy` chunks, and the nav group and inbox panel render only when the status says enabled. Reusable pieces (`KanbanBoard`, `Timeline`, `RecordDrawer`) import nothing from the CRM. No live updates: a Refresh action and refetch on tab focus (§10).

**Tech Stack:** React 18, PatternFly 6.5 (`react-core`, `react-table`, `react-icons`), react-router-dom 7, `@dnd-kit/core` + `@dnd-kit/sortable` + `@dnd-kit/utilities` (new), vitest + jsdom + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-23-crm-port-design.md` (backend repo). **Contract:** "Contract for the console" in `2026-10-23-crm-a-backend.md` — where this plan and the contract disagree, the contract wins. Reference screens for layout and copy questions: `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm/` (antd; translate to PatternFly, do not copy antd patterns).

**Branch:** `crm-a` worktree at `omnichannel-crm-frontend/.worktrees/crm-a`. Run `npm test -- --run <file>`; `npm run lint`; `npx tsc --noEmit`; `npm run build`.

## Global Constraints

- CRM code under `src/app/Crm/`; generic components under `src/app/Crm/common/` must not import from other `Crm/` folders or `api/crm.ts` (Task 5 test).
- Existing files touched only where named: `routes.tsx`, `AppShell.tsx`, `Inbox/InboxPage.tsx`, `Inbox/NewChatModal.tsx` (two optional props), `Users/UsersPage.tsx`, `Customers/CustomerDetailPage.tsx`, `test/session.ts`, `package.json`.
- PatternFly components and tokens only (`var(--pf-t--global--…)`); lane colours from a fixed token palette; light/dark/contrast must work; every page usable at 375px width (board falls back to a lane list under 768px).
- Copy is sentence case. Deal is the label for a pipeline card ("New deal", "Deals"). Empty and off states use the exact strings in the tasks.
- Data via `apiFetch` + `useLoad`/`useAction`; no new data library; no polling in phase A.
- A brand without the CRM (status `enabled:false`) makes exactly one CRM request, `GET /crm/status`, and never loads a CRM chunk (Task 1 test).
- Commits: one per task, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Brand switch while a CRM page is open**: the page reloads status and data for the new brand, and shows the off state if the new brand has no CRM (Task 1 test `brandSwitchReloadsStatus`).
2. **Drag dropped and the server refuses** (403/404/409): the card returns to its lane and an inline alert names the reason (Task 5 test `failedMoveRollsBack`, Task 6 test `moveErrorShowsAlert`).
3. **Agent with no pipeline**: Pipelines shows "No pipeline assigned to you. Ask your admin." and no New buttons; Contacts shows an empty table, not an error (Task 6 test `agentWithoutPipelinesSeesEmptyState`).
4. **Stale version** (two admins editing lanes): 409 `VERSION_CONFLICT` shows "Someone else changed this. Reload to see their changes." with a Reload button (Task 3 test `stageSaveConflictOffersReload`).
5. **Phone width**: board renders as a list of lanes with a stage picker per card instead of drag (Task 5 test `narrowViewportRendersLaneList`).

---

### Task 1: CRM API module, status provider, nav group and lazy routes

**Files:**
- Create: `src/app/api/crm.ts`, `src/app/api/crm.test.ts`, `src/app/Crm/status/CrmStatusContext.tsx`, `src/app/Crm/status/crmStatus.test.tsx`, `src/app/Crm/common/CrmOffState.tsx`, `src/app/Crm/routes.tsx` (lazy route list), `src/test/crm.ts` (fixtures)
- Modify: `src/app/AppShell.tsx` (wrap in `CrmStatusProvider` inside `SessionProvider`), `src/app/routes.tsx` (import CRM routes; `NAV_SECTIONS` CRM group after Inbox section; `useNavSections` drops the CRM group unless `useCrmStatus().status?.enabled`), `src/test/session.ts` (add `crm:view`, `crm:view-all`, `crm:edit`, `crm:delete`, `crm:settings`, `crm:export` to `BRAND_CODES`; `AGENT_IN_BRAND` += `crm:view`, `crm:edit`; `ACCOUNT_MANAGER` += `crm:enable`)

**Interfaces:**
- `api/crm.ts` exports types `CrmStatus`, `Picklist`, `PicklistItem`, `CrmSettings`, `Pipeline`, `Stage`, `StageType`, `Ref`, `CrmContact`, `Company`, `Deal`, `Board`, `Lane`, `Activity`, `Page<T>`, `CaptureResult`, `Communication`, `InboxSummary`, `Enablement` exactly as the contract; functions (all `(token, ws, …)` and returning `Promise`): `getStatus`, `getSettings`, `putSettings`, `listPipelines`, `getPipeline`, `createPipeline`, `updatePipeline`, `putStages`, `deletePipeline(token, ws, id, moveDealsToPipelineId?)`, `getUserPipelines`, `putUserPipelines`, `searchContacts`, `getContact`, `updateContact`, `captureChat`, `createContact`, `getCommunication`, `moveChannel`, `inboxSummary`, `searchCompanies`, `companyTypeahead`, `getCompany`, `companySummary`, `createCompany`, `updateCompany`, `deleteCompany`, `getBoard`, `searchDeals`, `getDeal`, `createDeal`, `updateDeal`, `moveDeal`, `closeDeal`, `deleteDeal`, `listActivities`, `logActivity`; platform `getCrmSwitch(token, customerId, ws)`, `putCrmSwitch(token, customerId, ws, body)`.
- `CrmStatusContext.tsx`: `CrmStatusProvider`; `useCrmStatus(): { status: CrmStatus | null; loading: boolean; reload(): void }` — loads when `workspace?.authorities` includes `crm:view`, reloads on workspace change, `null` otherwise or on any error; `useCrmCan(code: string): boolean` (`status.authorities` contains code).
- `Crm/routes.tsx`: `crmRoutes: IAppRoute[]` — `/crm` (PipelinesPage), `/crm/deals/:dealId` (PipelinesPage), `/crm/contacts`, `/crm/contacts/:id`, `/crm/companies`, `/crm/companies/:id`, `/crm/settings` — each `requires: (a) => a.hasInWorkspace('crm:view')`, element wrapped in `<Suspense>` + `<CrmGate>` (renders `CrmOffState` when status `enabled` is false; spinner while loading). Pages not built yet export a placeholder `PageShell` titled with the page name; later tasks replace them.
- NAV group `{ label: 'CRM', links: [Pipelines /crm, Contacts /crm/contacts, Companies /crm/companies, Settings /crm/settings] }`.
- `CrmOffState` copy: title "CRM is not switched on for this brand", body "Ask your account manager to switch it on."

- [ ] **Step 1: Failing tests.** `api/crm.test.ts`: `statusGetsBrandPath`, `boardCarriesQuery` (`/crm/deals/board?pipelineId=p1&perStageLimit=25`), `moveDealPostsNeighbours`, `deletePipelineAddsTarget`, `crmSwitchUsesCustomerPath`. `crmStatus.test.tsx`: `navShowsCrmGroupWhenEnabled`, `navHidesCrmWhenDisabled` (and `callsTo(fetch, 'GET /api/workspaces/w1/crm/status') === 1`, no other `/crm/` call), `noStatusCallWithoutCrmView`, `directLinkWhenOffShowsOffState`, `brandSwitchReloadsStatus`.
- [ ] **Step 2: Run** `npm test -- --run src/app/api/crm.test.ts src/app/Crm/status` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS; `npm test -- --run` (all) PASS; `npm run lint` clean.
- [ ] **Step 5: Commit** `Add the CRM API module, status gate and nav group`.

### Task 2: CRM switch on the platform customer page (§4.2)

**Files:**
- Create: `src/app/Crm/platform/CrmSwitchModal.tsx`, `src/app/Crm/platform/crmSwitch.test.tsx`
- Modify: `src/app/Customers/CustomerDetailPage.tsx` (per brand row: a "CRM" label "On"/"Off" and a kebab action "CRM settings…" when `useAccess().has('crm:enable')`)

**Interfaces:**
- `CrmSwitchModal({ customerId, workspace: Workspace, onClose, onSaved(e: Enablement) })`: loads `getCrmSwitch`; Switch "CRM on", number inputs "Max pipelines" and "Max custom fields per record type" (blank = unlimited, ≥ 1); Save → `putCrmSwitch`; field errors from `useAction`.

- [ ] **Step 1: Failing tests:** `staffSeesCrmColumnAndOpensModal`, `savingSendsLimitsAndUpdatesRow`, `blankLimitMeansUnlimited` (body `maxPipelines:null`), `noCrmActionWithoutAuthority` (`CUSTOMER_ADMIN` fixture).
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Switch the CRM on per brand from the customer page`.

### Task 3: Settings — General and Pipelines (§10 Settings)

**Files:**
- Create: `src/app/Crm/settings/CrmSettingsPage.tsx` (tabs General, Pipelines; phase B adds Fields, Picklists), `GeneralSettings.tsx`, `PipelinesSettings.tsx`, `PipelineFormModal.tsx`, `StageEditor.tsx`, `StageRemovalModal.tsx`, `laneColors.ts`, `settings.test.tsx`, `stageEditor.test.tsx`
- Modify: `src/app/Crm/routes.tsx` (`/crm/settings` → `CrmSettingsPage`)

**Interfaces:**
- Read-only for users without `crm:settings` ("You can view these settings. Changing them needs the CRM settings permission.").
- `GeneralSettings`: currency (`TextInput`, 3 upper-case letters), default pipeline (`FormSelect` of active pipelines), rotting days, "Allow moving CRM contacts to another WhatsApp number", "Show WhatsApp messages on timelines"; Save → `putSettings` with `version`.
- `PipelinesSettings`: list of pipelines (name, Default label, Inactive label, lane count, deal count unknown → omit), actions Edit, Make default, Deactivate/Activate, Delete; "New pipeline" disabled with tooltip "Your plan allows N pipelines" at `limits.maxPipelines`; delete opens a confirm that, on 409 `PIPELINE_HAS_DEALS`, asks for a target pipeline and retries with `moveDealsToPipelineId`.
- `StageEditor({ stages, onChange, readOnly })`: rows with drag handle (dnd-kit sortable, keyboard sensor), colour picker from `laneColors.ts` (8 PF palette tokens), name, type select (Open/Won/Lost), probability 0–100, WIP limit; Add lane; remove marks a row. Save → `putStages`; on 409 `STAGE_HAS_DEALS` open `StageRemovalModal({ stageName, count, targets: Stage[] })` → retry with `moveToStageId` set on the removed stage; on 409 `STAGE_TYPE_HAS_DEALS` an inline alert "This lane still holds deals, so its type can't change."; on 409 `VERSION_CONFLICT` the Review Focus copy + Reload.
- `laneColors.ts`: `LANE_COLORS: string[]` (token names), `nextLaneColor(index): string`.

- [ ] **Step 1: Failing tests.** `settings.test.tsx`: `generalSavesWithVersion`, `readOnlyWithoutSettingsAuthority`, `newPipelineDisabledAtLimit`, `deleteWithDealsAsksForTarget`. `stageEditor.test.tsx`: `addLaneAppendsOpenStage`, `keyboardReorderMovesRow` (space, arrow down, space), `removingLaneWithDealsAsksWhereToMove` (409 `STAGE_HAS_DEALS` → modal → second PUT carries `moveToStageId`), `stageSaveConflictOffersReload`.
- [ ] **Step 2: Install** `npm install --save-exact @dnd-kit/core @dnd-kit/sortable @dnd-kit/utilities` and record the versions in the commit message.
- [ ] **Step 3–6:** FAIL → implement → PASS (+ lint) → commit `Add CRM general and pipeline settings`.

### Task 4: "CRM pipelines…" on the Users page (§10 Users page)

**Files:**
- Create: `src/app/Crm/access/UserPipelinesModal.tsx`, `src/app/Crm/access/userPipelines.test.tsx`
- Modify: `src/app/Users/UsersPage.tsx` (row action "CRM pipelines…" when `useCrmStatus().status?.enabled && useCrmCan('crm:settings')`; opens the modal; nothing else changes)

**Interfaces:**
- `UserPipelinesModal({ user: {id, name}, onClose })`: loads `listPipelines` + `getUserPipelines`; checkbox list of pipelines (inactive ones labelled "Inactive"); hint "Users with 'see every pipeline' access see all pipelines anyway." when the target holds `crm:view-all` is unknown here — show the hint always as helper text; Save → `putUserPipelines`.

- [ ] **Step 1: Failing tests:** `actionHiddenWhenCrmOff`, `actionHiddenWithoutSettingsAuthority`, `modalLoadsAndSavesSelection`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Assign CRM pipelines from the Users page`.

### Task 5: Generic `KanbanBoard` (§10 reusable components)

**Files:**
- Create: `src/app/Crm/common/KanbanBoard.tsx`, `KanbanLane.tsx`, `KanbanCard.tsx`, `kanban.ts`, `kanbanBoard.test.tsx`, `kanban.test.ts`, `commonImports.test.ts`

**Interfaces:**
- `kanban.ts` (pure): `interface KanbanLaneData<C> { key: string; title: string; color?: string; total: number; footer?: React.ReactNode; badge?: React.ReactNode; cards: C[] }`; `moveCard<C>(lanes, cardId, toLaneKey, index, idOf: (c: C) => string): { lanes; beforeId: string | null; afterId: string | null }` (pure, returns new lanes and the neighbours at the drop index).
- `KanbanBoard<C>({ lanes, idOf, renderCard, onMove(cardId, toLaneKey, beforeId, afterId): Promise<void>, onLoadMore?(laneKey), renderLaneHeaderExtra?, narrow?: boolean, ariaLabel })`: dnd-kit `DndContext` with pointer + keyboard sensors; horizontal scroll container; lanes 280px; optimistic move via `moveCard`, rollback to the previous lanes when `onMove` rejects; while a move is pending, an incoming `lanes` prop change keeps the pending card where it was dropped; "Load more" button per lane when `cards.length < total`. `narrow` (or `window.matchMedia('(max-width: 767px)')`) renders a stacked list of lanes; each card gets a "Move to…" select instead of drag, calling `onMove(cardId, laneKey, lastIdOfTarget, null)`.
- `commonImports.test.ts`: reads every file in `src/app/Crm/common/` and asserts no import of `@app/api/crm` or `@app/Crm/` outside `common`.

- [ ] **Step 1: Failing tests.** `kanban.test.ts`: `moveWithinLaneReturnsNeighbours`, `moveToEmptyLaneHasNoNeighbours`, `moveToTopHasOnlyAfter`. `kanbanBoard.test.tsx`: `rendersLanesWithTotals`, `keyboardMoveCallsOnMoveWithNeighbours`, `failedMoveRollsBack`, `pendingMoveSurvivesPropRefresh`, `loadMoreShownWhenTotalExceedsCards`, `narrowViewportRendersLaneList`. `commonImports.test.ts`: `commonComponentsAreCrmFree`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add a generic kanban board`.

### Task 6: Pipelines page — board, table, new contact, new deal (§10 Pipelines page)

**Files:**
- Create: `src/app/Crm/deals/PipelinesPage.tsx`, `DealCard.tsx`, `DealTable.tsx`, `NewContactModal.tsx`, `DealFormModal.tsx`, `ContactPicker.tsx`, `useRefetchOnFocus.ts` (in `Crm/common/`), `money.ts` (in `Crm/common/`), `pipelines.test.tsx`, `newContact.test.tsx`, `dealForm.test.tsx`
- Modify: `src/app/Crm/routes.tsx`

**Interfaces:**
- URL state: `/crm?pipelineId=&view=board|table&owner=&q=`; pipeline defaults to the brand default if accessible, else the first accessible; selection kept in `localStorage` key `crm.pipeline.<userId>.<ws>` (try/catch).
- Header: pipeline `FormSelect` (accessible pipelines), Board/Table `ToggleGroup`, search, owner filter ("Anyone", "Me", users from deals' owners), Refresh button; primary "New contact" (`crm:edit`); secondary "New deal" (`crm:edit` and `pipelineAccess === 'ALL'`).
- Board: `getBoard(perStageLimit 25)` → `KanbanBoard` lanes keyed by stage id, title `stage.name`, total, footer `sumAmount` formatted by `money.ts` `formatMoney(amount, currency)` (`Intl.NumberFormat`), WIP badge "Over the WIP limit of N" when `total > wipLimit`; `onMove` → `moveDeal`, then replace the card with the answer; load more → `searchDeals({pipelineId, stageId, sort:'position,asc', page})`. Move errors: 404 "This deal is no longer in a pipeline you can see.", 409 `VERSION_CONFLICT` "Someone else moved this deal. Refresh to see the board.", others the server message.
- `DealCard`: title, contact name, amount, owner initials, labels "Rotting" (orange) when `rotting`, "Overdue" (red) when `overdue`, a `ChannelChip` (number label) when status lists more than one number; click → `/crm/deals/:id?pipelineId=…` (drawer in Task 7).
- Table: `searchDeals` with `pipelineId`, columns Title, Contact, Stage, Amount, Owner, Expected close, Last activity; sortable on title, amount, lastActivityAt; paging.
- `useRefetchOnFocus(reload)`: calls `reload` on `visibilitychange` to visible, at most once per 5 s.
- `NewContactModal({ pipelineId, numbers: status.phones (canChat only), onCreated })`: WhatsApp number (required), first name, last name, channel number (`FormSelect`, default the only/first), deal title; → `createContact`; field errors by name.
- `DealFormModal({ deal?, pipeline, onSaved })`: title, primary contact (`ContactPicker` over `searchContacts({q})`), amount, currency, expected close (DatePicker), owner, source (settings `dealSources` active), company (typeahead from Task 9 — until then a plain select over `searchCompanies`; Task 9 swaps in `CompanyTypeahead`); create → `createDeal`, edit → `updateDeal` with `version`.
- Empty states: no pipeline accessible → "No pipeline assigned to you. Ask your admin." (no New buttons); pipeline with no deals → "No deals in this pipeline yet".

- [ ] **Step 1: Failing tests.** `pipelines.test.tsx`: `boardLoadsDefaultPipeline`, `switchingPipelineUpdatesUrlAndStorage`, `tableViewSearchesDeals`, `agentWithoutPipelinesSeesEmptyState`, `newDealHiddenForRestrictedAgent`, `moveErrorShowsAlert` (move answers 404 → card back + alert copy), `refreshButtonReloadsBoard`, `focusRefetchesAtMostEveryFiveSeconds`, `rottingAndOverdueLabelsShow`. `newContact.test.tsx`: `onlyChattableNumbersOffered`, `createsAndReloadsBoard`, `phoneErrorShownOnField`. `dealForm.test.tsx`: `createSendsPrimaryContactAndPipeline`, `editSendsVersion`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the CRM pipelines board and table`.

### Task 7: Deal drawer, timeline and closing (§6.2, §6.3)

**Files:**
- Create: `src/app/Crm/common/RecordDrawer.tsx`, `src/app/Crm/common/Timeline.tsx`, `src/app/Crm/activity/CrmTimeline.tsx` (loads + log form), `src/app/Crm/activity/LogActivityForm.tsx`, `src/app/Crm/deals/DealDrawer.tsx`, `CloseDealModal.tsx`, `useOpenChat.ts`, `src/app/Crm/common/ChannelChip.tsx`, `dealDrawer.test.tsx`, `timeline.test.tsx`
- Modify: `PipelinesPage.tsx` (render `DealDrawer` when `:dealId`)

**Interfaces:**
- `RecordDrawer({ title, subtitle?, actions?, tabs: Array<{key; title; content: React.ReactNode}>, onClose })` — PatternFly `Drawer` panel (full width under 768px).
- `Timeline({ items: Array<{id; icon; title; body?; at: string; by?: string}>, onLoadMore?, hasMore })` — generic list grouped by day.
- `CrmTimeline({ target: {type, id}, canLog })`: `listActivities` (page size 50), maps types to titles ("Stage changed from X to Y" using `payload.from/to` stage names, "Won", "Lost — <reason>", "Call", "Meeting", "Email", "Note (from Contacts)" for `LEGACY_NOTE`, "Changed <field>" per diff); log form with type (Call/Meeting/Email), summary (≤ 2000, counter), "When it happened" (default now, not in the future).
- `DealDrawer({ dealId, pipeline, onChanged, onClose })` tabs Details (fields, stage select → `moveDeal` to end of lane, contact link, company link, channel chip), Timeline; actions Edit (`DealFormModal`), Open chat, Close (Won/Lost), Delete (`crm:delete`, confirm). 404 → "This deal is not available. It may have been deleted or moved to a pipeline you can't see."
- `CloseDealModal({ deal, lostReasons, onClosed })`: Won/Lost radio, lost reason select (active `lostReasons`), → `closeDeal`.
- `useOpenChat()` → `openChat(crmContactId)`: `getCommunication`; `chatId` → navigate `/inbox/chats/:chatId`; no chat and `canChat` → navigate `/inbox?newChat=<phoneNumberId>&phone=<phone>`; `canChat` false → an alert "You don't work this WhatsApp number, so the inbox won't open this chat." (The inbox reading `newChat`/`phone` query params to open `NewChatModal` pre-filled is part of Task 10.)
- `ChannelChip({ label })` compact PF `Label` with a WhatsApp-number icon.

- [ ] **Step 1: Failing tests.** `timeline.test.tsx`: `rendersStageChangeWithStageNames`, `legacyNotesShownAsContactNotes`, `logCallPostsAndPrepends`, `futureTimeRejectedClientSide`. `dealDrawer.test.tsx`: `opensFromUrlAndShowsDetails`, `closeLostSendsReason`, `openChatNavigatesToInboxChat`, `openChatWithoutChatOpensNewChat`, `deleteNeedsAuthority`, `missingDealShowsUnavailable`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the deal drawer and CRM timeline`.

### Task 8: Contacts page and contact drawer (§6.2, §4.4)

**Files:**
- Create: `src/app/Crm/contacts/CrmContactsPage.tsx`, `CrmContactDrawer.tsx`, `CrmContactFormModal.tsx`, `MoveChannelModal.tsx`, `contactColumns.ts`, `contacts.test.tsx`, `contactDrawer.test.tsx`
- Modify: `src/app/Crm/routes.tsx`

**Interfaces:**
- Page: search, filters lifecycle (`lifecycleStatuses`), owner, pipeline (accessible); table columns Name, WhatsApp number, Channel (chip, when > 1 number), Lifecycle, Company, Owner, Open deals (count · amount), Last activity; paging 50; row → `/crm/contacts/:id`; Refresh; empty state "No CRM contacts yet. Capture one from the inbox or add one from Pipelines."
- `CrmContactDrawer` tabs Details (name parts, email, job title, company link, owner, lifecycle `FormSelect` saving immediately via `updateContact`, source, origin "Captured from a chat by X on <date>" / "Added by hand" / "Imported", channel history list), Deals (accessible deals of the contact via `searchDeals({crmContactId})`, link to each), Timeline (`CrmTimeline`); actions Edit, Open chat, "Move channel…" (`crm:settings` and `settings.allowChannelMove`). `contactDeleted` → info alert "This person was deleted from Contacts; the CRM keeps what it knew."
- `MoveChannelModal({ contact, numbers, onMoved })`: target number select excluding the current, reason; 409 `CHANNEL_TAKEN` → "This person already has a CRM contact on that number."

- [ ] **Step 1: Failing tests.** `contacts.test.tsx`: `listsAndFiltersByLifecycle`, `searchDebounced`, `emptyStateCopy`. `contactDrawer.test.tsx`: `lifecycleChangeSavesWithVersion`, `moveChannelHiddenWithoutSettings`, `moveChannelTakenShowsMessage`, `deletedContactShowsNotice`, `dealsTabListsContactDeals`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the CRM contacts page and drawer`.

### Task 9: Companies page, drawer and typeahead (§6.2)

**Files:**
- Create: `src/app/Crm/companies/CompaniesPage.tsx`, `CompanyDrawer.tsx`, `CompanyFormModal.tsx`, `CompanyTypeahead.tsx`, `companies.test.tsx`
- Modify: `src/app/Crm/routes.tsx`, `src/app/Crm/deals/DealFormModal.tsx` and `CrmContactFormModal.tsx` (use `CompanyTypeahead`)

**Interfaces:**
- `CompanyTypeahead({ value: Ref | null, onChange(ref | null), canCreate })`: PF typeahead `Select`; 250 ms debounce on `companyTypeahead(q)`; last option `Create "<q>"` (when `canCreate`) → `createCompany({name: q})` then selects it.
- Page: table Name, Industry, Contacts, Open deal amount, Owner; drawer tabs Details, Contacts, Deals (from `companySummary`), Timeline; Delete (`crm:delete`) — 409 `COMPANY_HAS_OPEN_DEALS` → "This company still has open deals. Close or move them first."
- `CompanyFormModal`: name (required), domain, website, industry (`industries` active), size, annual revenue, phone, email, address fields, description, owner.

- [ ] **Step 1: Failing tests:** `typeaheadCreatesInline`, `deleteWithOpenDealsShowsMessage`, `drawerShowsSummaryCounts`, `industryOptionsComeFromSettings`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM companies`.

### Task 10: CRM panel in the inbox (§10 Inbox panel)

**Files:**
- Create: `src/app/Crm/inbox/CrmChatPanel.tsx`, `CapturePopover.tsx`, `crmChatPanel.test.tsx`
- Modify: `src/app/Inbox/InboxPage.tsx` (a third column, 320px, after the thread column, rendering `<React.Suspense><CrmChatPanelLazy chatId={open.id} /></React.Suspense>` only when `useCrmStatus().status?.enabled`; a header toggle "CRM" collapses it, state in `localStorage` `crm.panel.<userId>`; under 768px the panel opens as a drawer from the toggle; read `?newChat=<phoneNumberId>&phone=` to open `NewChatModal` with that number and phone pre-filled — add `initialNumberId`/`initialPhone` props to `NewChatModal`)

**Interfaces:**
- `CrmChatPanel({ chatId })`: `inboxSummary(chatId)` on mount and whenever `chatId` changes (abort stale answers).
  - Not captured, `canCapture`: button "Capture to CRM" → `CapturePopover` (pipeline select when the user has more than one accessible pipeline, default the brand default if accessible; optional deal title) → `captureChat` → show the captured state.
  - Not captured, blocked: disabled button with helper text — `CHAT_NOT_ASSIGNED` "Assign this chat to yourself or your team to capture it.", `NO_PIPELINE_ACCESS` "No pipeline is assigned to you yet.", `NO_EDIT` "You can view the CRM but not add to it."
  - Captured: name (link to `/crm/contacts/:id`), lifecycle select (saves), open deals (title, stage, amount; link to the deal), "Log call" (inline `LogActivityForm` with type preset), "Open in CRM".
  - Request errors: a compact inline alert with Retry; the chat UI is never blocked.

- [ ] **Step 1: Failing tests.** `crmChatPanel.test.tsx`: `noPanelAndNoRequestWhenCrmOff` (only `status` called), `captureWithSinglePipelineSkipsPicker`, `captureWithTwoPipelinesShowsPicker`, `blockedReasonCopy` (each of the three), `capturedShowsDealsAndLifecycleSaves`, `switchingChatReloadsSummary`, `staleAnswerIgnored` (use `gate` helper), `newChatQueryOpensPrefilledModal`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `npm test -- --run` (all), `npm run lint`, `npx tsc --noEmit`, `npm run build` → green; the build output has separate CRM chunks (check `dist/` lists a chunk containing `PipelinesPage`).
- [ ] **Step 5: Commit** `Add the CRM panel to the inbox`.
- [ ] **Step 6: Acceptance walk.** With the phase A backend merged, build and run as spec §14 says, sign in as the demo users and walk checks 1–12 of spec §14 in light, dark and 375px width; note any failure as a follow-up task rather than fixing it inside this plan.
