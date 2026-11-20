# Leads follow the chat + chatbot report — Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Chatbots → Report page showing where a chatbot's customers stop (totals, step table, AI progress), every count opening the runs behind it, and the run panel showing what an AI step collected.

**Architecture:** New API functions in `src/app/api/chatbots.ts` for the two report endpoints; a `ChatbotReportPage` with a `ReportRunsDrawer`; the run panel reads the new `progress` and `ending` fields. Pure helpers for formatting live in `chatbotDisplay.ts` so they are unit-tested without rendering.

**Tech Stack:** React 18, PatternFly 6.5, react-router, vitest + Testing Library (existing `renderApp` helper in tests).

**Spec:** `docs/superpowers/specs/2026-11-20-lead-owner-and-chatbot-report-design.md` (backend repo). The API contract is the backend plan's Task 3 (`ending`), Task 4 (`progress`), Task 7 (`ChatbotReport`) and Task 8 (`RunRowPage`) in `docs/superpowers/plans/2026-11-20-lead-owner-report-backend.md`.

## Global Constraints

- Work in `omnichannel-crm-frontend/.worktrees/lead-owner-report` (branch `lead-owner-report`; `npm ci --prefer-offline` first). Tests first. Prettier on touched files only.
- Checks: `npx tsc --noEmit && npm run lint && npm run build` and `npx vitest run` on touched suites; the full suite once before merge.
- Route `/chatbots/report`, authority `chatbot:view` (`a.hasInWorkspace('chatbot:view')`).
- Copy (exact): nav label `Report`; page title `Chatbot report`; totals `Started`, `Finished`, `Went silent`, `Handed over`, `Stopped`, `Failed`, `Running`; chat line `To an agent`, `Waiting in a queue`, `Closed by the chatbot`, `Taken by someone else`, `Not recorded (earlier runs)`; table headers `Step`, `Reached`, `Ended here`; section `Removed steps`; owner placeholder `No owner`; run panel `Collected: …`.
- Date filter sends the viewer's local-day boundaries as ISO Instants (`from` = start of first day, `to` = end of last day); default the last 30 days; the UI refuses ranges over 92 days before calling.
- Links: chat `/inbox/chats/:chatId`; deal `/crm/deals/:dealId?pipelineId=:pipelineId`; run `/chatbots/:id/runs?run=:runId` (whatever the runs page already uses to open a run panel — reuse it).
- No "template" naming. Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A chatbot with no runs in range, or never published, shows an empty state, not a table of zeros or an error — pinned in Task 2 (`showsEmptyStateWithoutRuns`).
2. A report with a removed step and an AI step whose collect list changed between versions renders without crashing (unknown keys shown by key) — pinned in Task 2 (`unknownProgressKeysShowByKey`).
3. Changing a filter while a request is in flight never shows the older response — pinned in Task 2 (`staleResponseIsIgnored`).
4. Drawer rows with no deal (lead never added) show no lead link and no owner cell text, not `No owner` — pinned in Task 3 (`noDealNoLeadLink`).
5. The run panel shows `Collected:` while the step is still WAITING, and nothing for steps without progress — pinned in Task 4.

---

### Task 1: API types and functions

**Files:**
- Modify: `src/app/api/chatbots.ts`
- Test: `src/app/api/chatbotReport.test.ts`

**Interfaces:**
- Produces (exact names):
  - `RunStep.progress: Record<string, unknown> | null`; `Run.ending: RunEnding | null` with `interface RunEnding { chat: 'HANDED_OVER' | 'ROUTED' | 'CLOSED' | 'LEFT'; agent: Ref | null; team: Ref | null; at: string }`.
  - `interface ChatbotReport { totals: ReportTotals; steps: ReportStep[]; progress: ReportProgress[] }` mirroring backend Task 7 (`ReportTotals`, `ReportChatTotals`, `ReportStep {nodeId, name, type, removed, reached, endedHere: Partial<Record<RunStatus, number>>}`, `ReportProgress {nodeId, fields: {key, label, given}[], byCount: number[]}`).
  - `interface ReportQuery { from?: string; to?: string; phoneNumberId?: string }`; `interface ReportRunsQuery extends ReportQuery { nodeId?: string; reached?: boolean; status?: RunStatus; chat?: 'TO_AGENT' | 'TO_QUEUE' | 'CLOSED' | 'LEFT' | 'NOT_RECORDED'; given?: number; page?: number; size?: number }`.
  - `interface ReportRunRow` mirroring backend Task 8 `RunRow`; `interface ReportRunPage { items: ReportRunRow[]; total: number; page: number; size: number }`.
  - `getChatbotReport(token, ws, id, q: ReportQuery): Promise<ChatbotReport>` → `GET …/chatbots/{id}/report`; `listReportRuns(token, ws, id, q: ReportRunsQuery): Promise<ReportRunPage>` → `GET …/chatbots/{id}/report/runs`. Undefined params are omitted from the query string.

- [ ] **Step 1: Write the failing tests:** `getChatbotReport` builds `…/report?from=…&to=…` without `phoneNumberId` when absent; `listReportRuns` serialises `reached=true`, `given=2`, `page`, `size`; both send the token (mock `apiFetch` the way `chatbotRuns.test.tsx` does).
- [ ] **Step 2: Run** `npx vitest run src/app/api/chatbotReport.test.ts` — Expected: FAIL (functions missing).
- [ ] **Step 3: Implement** the types and the two functions next to `listRuns`.
- [ ] **Step 4: Run** the same — Expected: PASS; `npx tsc --noEmit` passes.
- [ ] **Step 5: Commit** — `feat(console): chatbot report API`

---

### Task 2: Report page

**Files:**
- Create: `src/app/Chatbots/ChatbotReportPage.tsx`
- Modify: `src/app/Chatbots/chatbotDisplay.ts` (helpers below)
- Modify: `src/app/Chatbots/routes.tsx` (route, before `/chatbots/:id`), `src/app/routes.tsx` (nav link `Report` under Chatbots between `All chatbots` and `Settings`), `src/app/Chatbots/editor/EditorHeader.tsx` (+ `ChatbotEditorPage.tsx`: `Report` link button next to `Runs`, to `/chatbots/report?chatbot=<id>`)
- Test: `src/app/Chatbots/chatbotReport.test.tsx`, helper cases in an existing `chatbotDisplay` test or a new `chatbotDisplay.test.ts`

**Interfaces:**
- Consumes: Task 1 functions and types; `listChatbots`, the brand's numbers list the runs page already uses.
- Produces: `ChatbotReportPage` reading/writing URL params `chatbot`, `from`, `to` (`YYYY-MM-DD`, local), `number`; prop-less. Calls `onOpenRuns(q: ReportRunsQuery, title: string)` internally to open Task 3's drawer.
- Helpers in `chatbotDisplay.ts`: `localDayRange(from: string, to: string): { from: string; to: string }` (ISO Instants); `rangeTooLong(from: string, to: string): boolean` (> 92 days); `progressSummary(byCount: number[]): string` → `"gave nothing 12 · 1 detail 9 · 2 details 11"` (zero buckets omitted, `1 detail` singular); `fieldsSummary(fields): string` → `"name 29 · city 20"` (label else key).

- [ ] **Step 1: Write the failing tests:**

```ts
test('localDayRange spans whole local days')          // ('2026-11-01','2026-11-20') → from at local 00:00, to at local 23:59:59.999
test('rangeTooLong over 92 days')                      // 93 days true, 92 false
test('progressSummary omits empty buckets')            // [12,9,11,0] → 'gave nothing 12 · 1 detail 9 · 2 details 11'
test('shows totals and the chat line')                 // mocked report → 'Started 120', 'To an agent 74'…
test('steps in server order, removed under their heading')
test('AI row expands to progress and fields')          // click Counsellor → 'gave nothing 12 …', 'name 29 · city 20'
test('unknownProgressKeysShowByKey')                   // field without label → key shown
test('showsEmptyStateWithoutRuns')                     // totals.started 0 → 'No runs in this period'
test('staleResponseIsIgnored')                         // first request resolves after the second → second's numbers shown
test('blocks ranges over 92 days without calling')     // error text 'Choose at most 92 days'
test('nav shows Report for chatbot:view')              // routes.test pattern
test('editor header links to the report')              // href '/chatbots/report?chatbot=c1'
```

- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/chatbotReport.test.tsx src/app/routes.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement** the page: filter toolbar (chatbot select, from/to date inputs, number select with `All numbers`), totals as compact label groups (two lines, copy per Global Constraints), a `Table` (`Step`, `Reached`, `Ended here` with a tooltip listing `endedHere` by `runStatusLabel`), AI rows (type `ai` with a `progress` entry) expandable; every non-zero count is a link button that opens the drawer with the matching query (`Reached` → `{nodeId, reached: true}`; `Ended here` → `{nodeId}`; a bucket → `{nodeId, given: k}`; a totals item → `{status}` or `{chat}`). Requests carry a sequence id; only the latest updates state.
- [ ] **Step 4: Run** the same tests, then `npx tsc --noEmit && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(console): chatbot report page`

---

### Task 3: Runs drawer

**Files:**
- Create: `src/app/Chatbots/ReportRunsDrawer.tsx`
- Modify: `src/app/Chatbots/ChatbotReportPage.tsx` (open/close)
- Test: `src/app/Chatbots/reportRunsDrawer.test.tsx`

**Interfaces:**
- Consumes: `listReportRuns` (Task 1); the page's current `ReportQuery`.
- Produces: `ReportRunsDrawer({ chatbotId, query, title, onClose })` — a PatternFly `Drawer` panel (or `Modal` if the page has no drawer layout; implementer's choice, ledger it) with a table: `Contact` (name, phone), `Number`, `Started`, `Ended`, `How it ended` (`runStatusLabel` + ending text, e.g. `Handed over · Asha`), `Collected` (keys joined), `Lead owner`, and row links `Chat`, `Lead`, `Run`. 25 per page with `Pagination`.

- [ ] **Step 1: Write the failing tests:**

```ts
test('lists the runs for the clicked number')          // query passed through incl. page 0, size 25
test('row links to chat, lead and run')                // hrefs '/inbox/chats/ch1', '/crm/deals/d1?pipelineId=p1', run link
test('unowned lead shows No owner')                    // dealId set, leadOwnerName null → 'No owner' in warning colour
test('noDealNoLeadLink')                               // dealId null → no 'Lead' link, owner cell empty
test('pages through results')                          // total 60 → page 2 request with page=1
```

- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/reportRunsDrawer.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement** as specified; ending text: `HANDED_OVER`/`ROUTED` with agent → `· <agentName>`, without agent → `· waiting in <teamName>` (row fields; the run panel uses `ending.agent.name` / `ending.team.name`); `CLOSED` → `· closed`; `LEFT` → `· taken by someone else`.
- [ ] **Step 4: Run** the same, then `npx tsc --noEmit && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(console): runs behind each report number`

---

### Task 4: Run panel shows what was collected and where the chat went

**Files:**
- Modify: `src/app/Chatbots/editor/RunOverlayPanel.tsx`, `src/app/Chatbots/chatbotDisplay.ts`
- Test: `src/app/Chatbots/editor/runOverlay.test.tsx`

**Interfaces:**
- Consumes: `RunStep.progress`, `Run.ending` (Task 1).
- Produces: `collectedText(progress: Record<string, unknown> | null): string | null` → `"Collected: name, city"` (keys with non-empty values, in object order; null when none); `endingText(e: RunEnding | null): string | null` (same wording as Task 3's ending text, prefixed `Chat: `).

- [ ] **Step 1: Write the failing tests:**

```ts
test('waiting AI step shows Collected')     // step outcome WAITING, progress {name:'Asha', city:'Indore'} → 'Collected: name, city'
test('step without progress shows nothing extra')
test('finished run shows where the chat went') // ending {chat:'HANDED_OVER', agent:{name:'Asha'}} → 'Chat: Handed over · Asha'
```

- [ ] **Step 2: Run** `npx vitest run src/app/Chatbots/editor/runOverlay.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement**: render `collectedText(s.progress)` in the step's detail line (after `aiStepDetail`), and `endingText(run.ending)` under `endReason`.
- [ ] **Step 4: Run** the same — Expected: PASS. Then the full checks: `npx tsc --noEmit && npm run lint && npm run build` and `npx vitest run`. Expected: all pass.
- [ ] **Step 5: Commit** — `feat(console): run panel shows collected details and where the chat went`
