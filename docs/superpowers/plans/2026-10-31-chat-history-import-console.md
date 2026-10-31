# Chat History Import (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a manager start a chat history import from the phone numbers page or from a chat, and follow its progress.

**Architecture:** A new API module `api/historyImports.ts`; one modal `ImportHistoryModal` used in two modes (number: scope + dates; chat: dates only); a "History imports" page under Inbox listing imports with a 5 s poll while any is active, modelled on `useCampaignProgress`; a "Fetch history" button in the chat header that shows progress inline and reloads the thread when the import completes.

**Tech Stack:** React 18, TypeScript, PatternFly 6, react-router, vitest + Testing Library (`src/test/renderApp`, `src/test/fetchMock`). Repo: `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend`.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/docs/superpowers/specs/2026-10-31-chat-history-import-design.md` §7–§8.

## Global Constraints

- `npm run type-check`, `npm run lint`, `npm test` must pass.
- Visibility follows the authority `inbox:import-history` via `useAccess().hasInWorkspace(...)`; nothing of this feature renders without it.
- API paths: `/api/workspaces/{ws}/inbox/phone-numbers/{id}/history-imports`, `/inbox/chats/{chatId}/history-imports`, `/inbox/history-imports[...]` (spec §7).
- Copy (spec §8): "Import chat history", "All chats", "Specific contacts", "From", "To", "Start import", "Cancel import", "Fetch history", statuses "Queued" / "Running" / "Completed" / "Cancelled" / "Failed", waiting text "Waiting for the extension to connect".
- Phone numbers are normalised with `normaliseNumber` from `@app/api/phoneNumbers` and validated with `NUMBER_PATTERN`.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A 200 (identical active import returned) must be treated exactly like a 201: navigate to the imports page, no error — test in Task 3.
2. A "From" later than "To" must be refused in the modal before any request — test in Task 2.
3. The imports page must stop polling once no import is QUEUED or RUNNING, and resume when a new one appears — test in Task 4.
4. A chat-header import that ends FAILED must show the failure, not reload the thread forever — test in Task 5.
5. Pasting phones with spaces, plus signs and dashes, one per line, must yield clean digit strings and drop blank lines — test in Task 2.

---

### Task 1: API module

**Files:**
- Create: `src/app/api/historyImports.ts`.
- Test: `src/app/api/historyImports.test.ts` (style of `src/app/api/inbox.test.ts`: `mockFetch` and `requestTo`).

**Interfaces:**
- Types: `HistoryImportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'`; `HistoryScopeKind = 'ALL' | 'CHATS'`; `HistoryWaiting = 'NO_DEVICE' | 'WHATSAPP_NOT_READY' | null`; `HistoryChatStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED'`.
- `interface HistoryImportCounts { chatsTotal; chatsDone; chatsSkipped; chatsFailed; messagesSeen; messagesInserted; messagesUpdated }` (all `number`).
- `interface HistoryImportView { id; phoneNumber: { id; number; label: string | null }; scope: { kind: HistoryScopeKind; phones: string[]; chatCount: number }; range: { from: string | null; to: string | null }; status: HistoryImportStatus; waiting: HistoryWaiting; counts: HistoryImportCounts; requestedBy: { id: string; name: string } | null; creationTime: string; startedAt: string | null; finishedAt: string | null; progressPercent: number | null; error: string | null }`.
- `interface HistoryChatView { id; remoteId; requestedPhone: string | null; chatName: string | null; chatId: string | null; status: HistoryChatStatus; skipReason: 'NO_CHAT' | null; error: string | null; counts: { seen; inserted; updated }; capped: boolean; attemptedAt: string | null; finishedAt: string | null }`.
- `interface Page<T> { items: T[]; total: number; page: number }`.
- `interface NumberImportRequest { scope: { kind: 'ALL' } | { kind: 'CHATS'; phones: string[] }; from?: string | null; to?: string | null }`; `interface RangeRequest { from?: string | null; to?: string | null }`.
- Functions: `startNumberImport(token, ws, phoneNumberId, body: NumberImportRequest): Promise<HistoryImportView>`; `startChatImport(token, ws, chatId, body: RangeRequest)`; `listHistoryImports(token, ws, opts: { phoneNumberId?: string; status?: HistoryImportStatus; page?: number } = {}): Promise<Page<HistoryImportView>>`; `getHistoryImport(token, ws, id)`; `listHistoryImportChats(token, ws, id, opts: { status?: HistoryChatStatus; page?: number } = {})`; `cancelHistoryImport(token, ws, id)`.
- `export const ACTIVE: ReadonlySet<HistoryImportStatus> = new Set(['QUEUED', 'RUNNING'])`; `export const isActive = (s: HistoryImportStatus) => ACTIVE.has(s)`.

- [ ] **Step 1: Write the failing tests**: `startNumberImportPostsScopeAndRange` (body equals the request, URL as in Global Constraints), `listHistoryImportsBuildsQuery` (`?phoneNumberId=p1&status=RUNNING&page=2`), `cancelPosts` (`POST …/history-imports/h1/cancel`), `isActiveOnlyForQueuedAndRunning`.
- [ ] **Step 2: Run** `npx vitest run src/app/api/historyImports.test.ts` → fail (module missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(api): history imports client`

---

### Task 2: `ImportHistoryModal`

**Files:**
- Create: `src/app/HistoryImports/ImportHistoryModal.tsx`, `src/app/HistoryImports/importForm.ts` (pure helpers).
- Test: `src/app/HistoryImports/importForm.test.ts`, `src/app/HistoryImports/importHistoryModal.test.tsx`.

**Interfaces:**
- `importForm.ts`: `parsePhones(text: string): { phones: string[]; invalid: string[] }` — split on newlines and commas, trim, drop blanks, `normaliseNumber`, de-duplicate, `invalid` = entries failing `NUMBER_PATTERN`; `dayStart(date: string /* YYYY-MM-DD */): string` → local midnight ISO; `dayEnd(date: string): string` → local 23:59:59.999 ISO; `validateRange(from: string, to: string): string | null` → `'"From" must not be after "To".'` when both set and `from > to` (string compare of YYYY-MM-DD), else null.
- `ImportHistoryModal` props: `{ target: { kind: 'number'; number: PhoneNumber } | { kind: 'chat'; chat: ChatView }; onStarted(imp: HistoryImportView): void; onCancel(): void }`. Title "Import chat history" (number mode adds ` for +<number>`; chat mode adds ` with <contactName(chat)>`). Number mode: `Radio` "All chats" (default) / "Specific contacts" with a `TextArea` (`aria-label="Phone numbers"`, placeholder "One number per line, country code first") shown only for "Specific contacts". Both modes: two `DatePicker`s labelled "From" and "To" (optional). Primary button "Start import" (`isLoading` while busy), link "Cancel". Validation before submit: range error shown as a `FormHelperText` under "To"; "Specific contacts" with no valid phone → "Enter at least one phone number." under the textarea; invalid entries → `Use 8 to 15 digits, country code first: <list>`. Submit calls `startNumberImport(token, ws, number.id, { scope, from: from ? dayStart(from) : null, to: to ? dayEnd(to) : null })` or `startChatImport(token, ws, chat.id, { from, to })` through `useAction()`; on success `onStarted(view)`.

- [ ] **Step 1: Write the failing tests**. `importForm.test.ts`: `parsePhonesNormalisesAndDrops` (`"+91 99280-88743\n\n9199280 88744, 12345"` → phones `['919928088743','919928088744']`, invalid `['12345']`), `validateRangeRejectsFromAfterTo`, `dayStartAndEndWrapTheDay`. `importHistoryModal.test.tsx` (render with `SessionProvider` as `thread.test.tsx` does, `mockFetch`): `startsAllChatsImport` (click "Start import" → POST body `{scope:{kind:'ALL'}, from:null, to:null}`, `onStarted` called with the response), `specificContactsRequiresPhones` (choose radio, submit → helper text, no request), `fromAfterToIsRefused`, `chatModeShowsOnlyDates` (no radios; POST to the chat path).
- [ ] **Step 2: Run** the two test files → fail.
- [ ] **Step 3: Implement** helpers and modal.
- [ ] **Step 4: Run** → PASS; `npm run type-check`.
- [ ] **Step 5: Commit** — `feat(history): ImportHistoryModal`

---

### Task 3: Phone numbers page action

**Files:**
- Modify: `src/app/PhoneNumbers/PhoneNumbersPage.tsx` — `Dialog` union gains `{ kind: 'history'; number: PhoneNumber }`; `actionsFor` gains `{ title: 'Import chat history…', onClick: open({ kind: 'history', number: n }) }` after "Get connect code", only when `useAccess().hasInWorkspace('inbox:import-history')` and `n.connectionType !== 'WHATSAPP_CLOUD_API'`; render `ImportHistoryModal` for that dialog with `onStarted={() => navigate('/inbox/history-imports')}`.
- Test: `src/app/PhoneNumbers/phoneNumbers.test.tsx` (append).

**Interfaces:**
- Consumes: `ImportHistoryModal` (Task 2), route `/inbox/history-imports` (Task 4; until then the navigation lands on NotFound, which the test tolerates by asserting the request only).

- [ ] **Step 1: Write the failing tests**: `importHistoryActionStartsImport` (session as `WORKSPACE_MANAGER` — confirm `src/test/session.ts` grants `inbox:import-history` for that role; if its authority list is explicit, add it there) — open the row kebab via `rowAction`, click "Import chat history…", click "Start import" → `requestTo(fetch, 'POST /api/workspaces/w1/inbox/phone-numbers/p1/history-imports')` has body `scope.kind 'ALL'`; `identicalImportReturns200AndStillNavigates` (route returns `200` → no error alert shown); `actionHiddenWithoutAuthority` (session as `BRAND_VIEWER` → kebab has no "Import chat history…").
- [ ] **Step 2: Run** `npx vitest run src/app/PhoneNumbers/phoneNumbers.test.tsx` → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(phone-numbers): Import chat history action`

---

### Task 4: History imports page with polling

**Files:**
- Create: `src/app/HistoryImports/HistoryImportsPage.tsx`, `src/app/HistoryImports/useHistoryImports.ts`, `src/app/HistoryImports/historyImportDisplay.ts`.
- Modify: `src/app/routes.tsx` — nav item `{ label: 'History imports', path: '/inbox/history-imports', requires: (a) => a.hasInWorkspace('inbox:import-history') }` under "Inbox" after "Routing"; route `{ element: <HistoryImportsPage />, path: '/inbox/history-imports', requires: (a) => a.hasInWorkspace('inbox:import-history') }` **before** the `/inbox/:phoneNumberId` redirect route so it wins.
- Test: `src/app/HistoryImports/historyImports.test.tsx`, `src/app/routes.test.tsx` (append one case).

**Interfaces:**
- `historyImportDisplay.ts`: `statusLabel(s: HistoryImportStatus): string` ("Queued" / "Running" / "Completed" / "Cancelled" / "Failed"); `statusColor(s): 'grey' | 'blue' | 'green' | 'orange' | 'red'`; `scopeText(v: HistoryImportView): string` → "All chats" or `"${chatCount} contact(s)"`; `rangeText(v): string` → "All time" or `"<from date> – <to date>"` using `toLocaleDateString()`, open ends shown as "…"; `waitingText(w: HistoryWaiting): string | null` → "Waiting for the extension to connect" (`NO_DEVICE`), "Waiting for WhatsApp Web to be ready" (`WHATSAPP_NOT_READY`), else null; `countsText(c: HistoryImportCounts): string` → `"${messagesInserted} new · ${messagesUpdated} updated · ${messagesSeen} seen"`.
- `useHistoryImports(filters: { phoneNumberId?: string; status?: HistoryImportStatus }): { page: Page<HistoryImportView> | undefined; error: string | null; fetching: boolean; refresh(): void; cancel(id: string): Promise<void> }` — fetches on mount and filter change; while any item `isActive`, refetches every `REFRESH_SECONDS = 5` (same `latest` ref / unmount guards as `useCampaignProgress`); stops when none is active; `cancel` calls `cancelHistoryImport` then `refresh`.
- `HistoryImportsPage`: `PageShell title="History imports"`; filter `FormSelect` by number (from `listInboxNumbers`) and by status; `Table` columns Number · Scope · Range · Status (Label + waiting text in `HelperText`) · Progress (`Progress` with `progressPercent`, "Listing chats…" when null and active) · Messages (`countsText`) · Started (`relative(creationTime)` from `PhoneNumberStatusLabel`) · actions (kebab "Cancel import" when `isActive`, through `ConfirmModal`). Each row expandable (`ExpandableRowContent`) loading `listHistoryImportChats` on first expand: Chat · Status · Seen/New/Updated · Note (skipReason "No chat on the phone" / error / "Capped"). Empty state "No history imports yet."

- [ ] **Step 1: Write the failing tests** `historyImports.test.tsx` (`renderApp('/inbox/history-imports')`, `vi.useFakeTimers()` where polling is asserted): `listsImportsWithStatusAndProgress` (RUNNING at 50% shows "Running", a progress bar at 50, waiting text when `waiting NO_DEVICE`); `pollsWhileActiveThenStops` (first GET returns RUNNING, second returns COMPLETED → after advancing 5 s one more GET, after another 5 s no further GET); `cancelAsksThenPosts`; `expandLoadsChatItems` (row expand → GET `…/h1/chats`, shows the remote id and "No chat on the phone"). `routes.test.tsx`: `/inbox/history-imports` renders the page for a manager and NotFound for `BRAND_VIEWER`.
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test`, `npm run type-check`, `npm run lint` → PASS.
- [ ] **Step 5: Commit** — `feat(history): History imports page with polling and cancel`

---

### Task 5: "Fetch history" in the chat header

**Files:**
- Create: `src/app/HistoryImports/useChatHistoryImport.ts`.
- Modify: `src/app/Inbox/ChatHeader.tsx` — props gain `onHistoryImported?(): void`; a secondary `Button` "Fetch history" (shown with `inbox:import-history`) opens `ImportHistoryModal` in chat mode; while an import is active an inline `Alert variant="info" isInline isPlain` reads "Importing history… N messages so far" (N = `counts.messagesSeen`, with the waiting text appended when set); on COMPLETED it reads "History imported: N new messages" for 10 s and calls `onHistoryImported`; on FAILED/CANCELLED a `warning` alert with the status and `error`.
- Modify: `src/app/Inbox/InboxPage.tsx` — pass `onHistoryImported={thread.reload}` to `ChatHeader`.
- Test: `src/app/Inbox/chatHeader.test.tsx` (new; render as `thread.test.tsx` does with `SessionProvider`).

**Interfaces:**
- `useChatHistoryImport(): { current: HistoryImportView | null; start(imp: HistoryImportView): void; dismiss(): void }` — `start` stores the view and polls `getHistoryImport` every `REFRESH_SECONDS` until `!isActive(status)`; the final view stays in `current` until `dismiss` (auto-dismiss after 10 s on COMPLETED).
- Consumes: `ImportHistoryModal` (Task 2), `thread.reload()` from `useThread` (exists).

- [ ] **Step 1: Write the failing tests** `chatHeader.test.tsx`: `fetchHistoryStartsAndShowsProgress` (click "Fetch history", "Start import" → POST `…/chats/c1/history-imports`; GET returns RUNNING `messagesSeen 12` → "Importing history… 12 messages so far"); `completionReloadsThread` (next GET COMPLETED `messagesInserted 7` → "History imported: 7 new messages", `onHistoryImported` called once, no further GETs); `failureShowsErrorWithoutReload` (GET FAILED `error "NO_PROGRESS"` → warning alert containing "Failed", `onHistoryImported` not called); `buttonHiddenWithoutAuthority`.
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test`, `npm run type-check`, `npm run lint` → PASS.
- [ ] **Step 5: Commit** — `feat(inbox): Fetch history from the chat header`

---

### Task 6: End-to-end check against the running stack

- [ ] **Step 1:** Backend running with the Task 8 page script, extension connected on a test number, console `npm run start:dev`.
- [ ] **Step 2:** Phone numbers → kebab → "Import chat history…" → All chats, From = 7 days ago → Start import → the History imports page shows the row Running, progress moving, then Completed; counts plausible; no group chats in the inbox.
- [ ] **Step 3:** Repeat the same request → row completes quickly with "0 new".
- [ ] **Step 4:** Open a chat with little history → "Fetch history" → banner, then thread fills; chat stays CLOSED/OPEN as it was; `unreadCount` unchanged in the list.
- [ ] **Step 5:** Start an all-chats import, close the WhatsApp tab mid-way → row shows "Waiting for the extension to connect"; reopen → it resumes and completes.
- [ ] **Step 6:** Note anything surprising in `docs/superpowers/crm-pending.md` (backend repo) and commit there.
