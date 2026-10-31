# CRM phase E (console): task delegation, checklists and repeating tasks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show who handed a task over and let the assignee work it; add a Delegated tab with counts; add steps (checklist) with on-row ticking and repeat rules to the task form; schedule-aware actions (skip, "next one scheduled"); assignment notices as toasts.

**Architecture:** `src/app/Crm/tasks/` gains `TaskRow.tsx` (one row for drawers and the Tasks page), `useTaskActions.ts` (complete/reopen/tick/skip/delete with the two confirms), `ChecklistEditor.tsx` and `RepeatFields.tsx` (form controls), and more helpers in `taskDisplay.ts`; `TaskList` and `TasksPage` render `TaskRow`; `TaskFormModal` renders the two controls; `CrmReminders` renders the ASSIGNED kind. Calls go through `src/app/api/crm.ts`. No new dependency.

**Tech Stack:** as phases A–D (React 18, TypeScript, PatternFly 6.5, vitest + Testing Library).

**Spec:** `docs/superpowers/specs/2026-10-31-crm-tasks-delegation-design.md` (§6, C3, C4, C10, C14–C21). **Contract:** "Contract for the console" in `2026-10-31-crm-e-backend.md` — the contract wins over this plan. **Depends on:** `2026-10-31-crm-e-backend.md` merged (or its branch at `omnichannel-crm-backend/.worktrees/crm-e`).

**Branch:** `crm-e` worktree at `omnichannel-crm-frontend/.worktrees/crm-e` cut from `main`. Commands (absolute paths): `npx vitest run <path> --maxWorkers=4`, `npx tsc --noEmit`, `npx eslint <files>` / `npm run lint`, `npm run build`. Prettier only on touched files. Plan and ledger live in the backend `crm-e` worktree.

## Global Constraints

- Phase A–D Global Constraints apply: CRM code under `src/app/Crm/`, PatternFly tokens only, sentence case, phone width (375 px), `apiFetch` + `useLoad`/`useAction`, `useRefetchOnFocus`, `useCrmCan`.
- **Copy, exactly:** tabs `Overdue (n)`, `Today (n)`, `Upcoming (n)`, `Delegated (n)`; switch `Show done`; empty states `Nothing overdue.`, `Nothing due today.`, `Nothing coming up.`, `You have no open tasks with other people.`, `None of the tasks you handed over are done yet.`; row marker `from <name>`; skipped label `Skipped`; progress label `<done>/<total>`; confirms `Mark the task done?` with body `<n> step is|steps are not ticked yet.` (button `Mark done`), `All steps done` with body `Mark the task done?` (button `Mark done`), `Skip this one?` with body `The next one is scheduled straight away.` (button `Skip`); toasts after skip `Skipped. Next one scheduled.` / `Skipped. That was the last one.`; assignment toast title `New task from <name>: <title>`; form labels `Steps`, `Add a step`, `At most 30 steps`, `Repeat`, `Every`, `On`, `Ends` with `Never` / `After` (`times`) / `On`; repeat options `Does not repeat`, `Daily`, `Weekly`, `Monthly`; units `day(s)`, `week(s)`, `month(s)`; hint `The next task is created when this one is done.`; errors `Set a due date to repeat.`, `Pick at least one day.`, `1 to 99`, `2 to 999`, `Pick the last date.`, `Must not be before the due date.`
- **Rights in the UI** (C3): `canWork = canEdit || task.assignee?.id === user.id` enables complete/reopen, tick and skip; edit and delete need `canEdit`. The server is the authority; the UI only hides what would 403.
- **Recurrence form ⇄ rule** (C17): `recurrenceFromForm(form, dueAt, browserZone, existing)` keeps `existing.timeZone` and, while monthly stays monthly, `existing.dayOfMonth`; weekly defaults `weekdays` to the due date's weekday when the user picks Weekly with none chosen; `recurrenceSummary` reads "Repeats daily" / "Repeats every 2 weeks on Mon, Thu" / "Repeats monthly on day 31" + " until 31 Dec 2026" + ", 6 times".
- **Field error keys** the form names in `useAction([...])`: the phase A list plus `checklist`, `recurrence`, `recurrence.frequency`, `recurrence.interval`, `recurrence.weekdays`, `recurrence.dayOfMonth`, `recurrence.timeZone`, `recurrence.endsAfter`, `recurrence.endsOn`; `checklist[i].text` errors are matched by prefix and shown on the step at index `i`.
- Commits: one per task, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. **A view-only assignee** (no `crm:edit`): sees the complete checkbox, tick boxes and Skip on their own task, nothing on others', and no Edit/Delete anywhere (Task 3 `viewOnlyAssigneeCanWorkOwnTaskOnly`).
2. **Ticking while a save is open elsewhere**: a 409 on save shows the existing "Someone else changed this task…" message, and the row shows the server's returned task after a tick (Task 3 `tickRendersServerTask`).
3. **Editing a monthly task opened on the 28th of a day-31 series**: saving without changes sends the stored `dayOfMonth` and `timeZone`, not the browser's (Task 2 `editKeepsStoredDayOfMonthAndZone`).
4. **A task both newly assigned and due**: one toast, titled "New task from …"; closing it clears the nav count by one (Task 4 `assignedToastShowsOncePerTask`).
5. **Delegated tab with the assignee picker set**: the list is the caller's hand-offs regardless of the picker, which is hidden on that tab (Task 4 `delegatedIgnoresAssigneePicker`).

---

### Task 1: API types, fixtures and display helpers

**Files:**
- Modify: `src/app/api/crm.ts` (`TaskStatus` + `'CANCELLED'`; `Task` + `assignedBy`, `assignedAt`, `checklist`, `recurrence`, `seriesId`, `occurrence`, `recurringFromTaskId`, `nextOccurrenceId`; new `ChecklistItem`, `Recurrence`, `RecurrenceFrequency`, `Weekday`, `ReminderKind`; `TaskInput` + `checklist?: Array<{id?: string; text: string}>`, `recurrence?: Recurrence | null`; `TaskCounts.delegated`; `Reminder` + `kind`, `assignedBy`; calls `delegatedTasks(token, ws, q: {status: 'OPEN' | 'DONE'; limit?: number})`, `skipTask(token, ws, id): Promise<{skipped: Task; next: Task | null}>`, `tickChecklistItem(token, ws, id, itemId, done)`), `src/app/Crm/tasks/taskDisplay.ts` (+ helpers), `src/test/crm.ts` (`sampleTask` gains the new fields with defaults `assignedBy: null, assignedAt: null, checklist: [], recurrence: null, seriesId: null, occurrence: null, recurringFromTaskId: null, nextOccurrenceId: null`; `sampleRecurrence(overrides)`; `sampleChecklist()` = three items, first done)
- Test: `src/app/Crm/tasks/taskDisplay.test.ts` (new)

**Interfaces (`taskDisplay.ts`):**
- `checklistProgress(task: Pick<Task, 'checklist'>): {done: number; total: number}`
- `isDelegatedToMe(task: Pick<Task, 'assignedBy' | 'assignee'>, userId: string | undefined): boolean` — `assignedBy && assignedBy.id !== userId && assignee?.id === userId`
- `canWorkTask(task: Pick<Task, 'assignee'>, canEdit: boolean, userId: string | undefined): boolean`
- `WEEKDAYS: Array<{value: Weekday; label: 'Mon' | …}>`, `REPEAT_OPTIONS`, `weekdayOf(isoDate: string): Weekday` (local date)
- `RepeatForm = {frequency: 'NONE' | RecurrenceFrequency; interval: string; weekdays: Weekday[]; ends: 'NEVER' | 'AFTER' | 'ON'; count: string; until: string}`; `recurrenceToForm(r: Recurrence | null): RepeatForm`; `recurrenceFromForm(f: RepeatForm, dueAt: string | null, zone: string, existing: Recurrence | null): Recurrence | null`
- `recurrenceSummary(r: Recurrence | null): string` ('' when null)

- [ ] **Step 1: Failing tests** in `taskDisplay.test.ts`: `progressCountsDone`, `delegatedToMeNeedsAnotherAssigner`, `canWorkForAssigneeOrEditor`, `summaryReadsTheRule` (the three summaries of the Global Constraints, plus endsOn/endsAfter suffixes), `fromFormKeepsStoredZoneAndDayOfMonth`, `fromFormTakesDayOfMonthFromDueOnCreate`, `toFormRoundTrips`, `weekdayOfLocalDate`.
- [ ] **Step 2: Run** `npx vitest run src/app/Crm/tasks/taskDisplay.test.ts --maxWorkers=4` → FAIL.
- [ ] **Step 3: Implement**; `npx tsc --noEmit` clean (fixtures updated).
- [ ] **Step 4: Run** → PASS; `npx vitest run src/app/Crm --maxWorkers=4` still green.
- [ ] **Step 5: Commit** `Add task delegation, checklist and repeat types and helpers`.

### Task 2: Task form — steps and repeat

**Files:**
- Create: `src/app/Crm/tasks/ChecklistEditor.tsx`, `src/app/Crm/tasks/RepeatFields.tsx`
- Modify: `src/app/Crm/tasks/TaskFormModal.tsx`
- Test: `src/app/Crm/tasks/taskForm.test.tsx` (new; render `TaskFormModal` inside the usual providers — see `segmentEditorPage.test.tsx` for the `MemoryRouter + SessionProvider + mockFetch` wrapper)

**Interfaces:**
- `ChecklistEditor: FC<{value: Array<{id?: string; text: string; done?: boolean}>; onChange(items): void; errors?: Record<number, string>}>` — a list group: each step a `TextInput` (aria-label `Step <n>`) with `Move up`, `Move down`, `Remove step` plain buttons; ticked steps show a `done` label; below, an `Add a step` input (Enter adds) and `Add` button, disabled at 30 with placeholder `At most 30 steps`. Stable keys for unsaved items (a counter ref).
- `RepeatFields: FC<{value: RepeatForm; onChange(v: RepeatForm): void; dueAt: string | null; summary: string; errors: Partial<Record<keyof RepeatForm | 'repeat', string>>}>` — `FormSelect` `Repeat`; when not NONE: `Every` number input (1–99) with the unit text, weekday `Checkbox`es (`On`) for WEEKLY, `Ends` radios with the count input (`times`) or a `DatePicker`; summary paragraph + hint.
- `TaskFormModal`: state `steps`, `repeat: RepeatForm` (from the task on edit); client checks: repeat without due → `Set a due date to repeat.` under Repeat; weekly without days → `Pick at least one day.`; interval/count out of range → `1 to 99` / `2 to 999`; `until` empty → `Pick the last date.`, before due → `Must not be before the due date.`; body gains `checklist: steps.map(({id, text}) => id ? {id, text} : {text})` (blank steps dropped) and `recurrence: recurrenceFromForm(...)` (null when NONE). Server `fields` errors land per the Global Constraints.

- [ ] **Step 1: Failing tests** `taskForm.test.tsx`: `addsMovesAndRemovesSteps` (Enter adds; Move up reorders; Remove deletes; the POST body carries `checklist` in order without ids for new ones), `keepsIdsOfExistingSteps` (edit a task with `sampleChecklist()`; change one text; PUT body keeps its `id` and shows `done` on it), `repeatNeedsDueDate`, `weeklyDefaultsToDueWeekday` (set due on a Thursday, pick Weekly → `Thu` checked; summary "Repeats weekly on Thu"), `editKeepsStoredDayOfMonthAndZone` (task with `sampleRecurrence({frequency: 'MONTHLY', dayOfMonth: 31, timeZone: 'Asia/Kolkata'})`, save → PUT body `recurrence.dayOfMonth 31`, `timeZone 'Asia/Kolkata'`), `endsAfterOutOfRangeBlocked`, `serverRecurrenceErrorShowsOnField` (400 `fields['recurrence.interval']` → text under Every), `thirtyStepsCap`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `taskList.test.tsx` still green; `npx tsc --noEmit`; eslint on touched files.
- [ ] **Step 5: Commit** `Edit task steps and repeat rules in the task form`.

### Task 3: One task row, shared actions, drawers

**Files:**
- Create: `src/app/Crm/tasks/TaskRow.tsx`, `src/app/Crm/tasks/useTaskActions.ts`
- Modify: `src/app/Crm/tasks/TaskList.tsx` (render `TaskRow`; sort OPEN, then DONE/CANCELLED by due), `src/app/Crm/tasks/NextTask.tsx` (show the progress label when steps exist — optional, one line)
- Test: `src/app/Crm/tasks/taskRow.test.tsx` (new, through `TaskList` in a deal drawer route like `taskList.test.tsx`), `taskList.test.tsx` (adjust selectors if needed)

**Interfaces:**
- `useTaskActions(onChanged: (task: Task) => void, onRemoved?: (id: string) => void)` → `{busyId, error, toggle(task), tick(task, itemId, done), skip(task), remove(task), dialogs: ReactNode}` — `toggle`: DONE → reopen; OPEN with unticked steps → confirm `Mark the task done?`; else complete. `tick` → `tickChecklistItem`, then if all done → confirm `All steps done`. `skip` → confirm `Skip this one?` then `skipTask`; toast via `onChanged(skipped)` plus an inline info alert text `Skipped. Next one scheduled.` / `Skipped. That was the last one.` exposed as `notice` for the caller to render. Confirms use `ConfirmModal`. Errors through `crmMessage`.
- `TaskRow: FC<{task: Task; canEdit: boolean; busy: boolean; onToggle; onTick; onSkip; onEdit?; onDelete?; extra?: ReactNode}>` — checkbox `Complete <title>` / `Reopen <title>` (disabled unless `canWork`, hidden for CANCELLED), title (struck through when not OPEN), small line: due label, `· <assignee>`, `· from <assignedBy name>` when `isDelegatedToMe`, a `SyncAltIcon` with `aria-label` = summary when repeating, a clickable `Label` `<done>/<total>` (`aria-expanded`) that toggles the steps list (checkboxes named by step text, disabled unless `canWork && task.status === 'OPEN'`), HIGH/LOW priority label, `Skipped` label; actions: `Skip <title>` (repeating, OPEN, canWork), `Edit <title>`, `Delete <title>` (canEdit).

- [ ] **Step 1: Failing tests** `taskRow.test.tsx`: `tickExpandsAndPostsThenRendersServerTask` (click `1/3`, tick `Mention the festive discount` → `POST …/checklist/<id>` body `{done: true}`; label becomes `2/3` from the response), `lastTickOffersToComplete` (response all done → confirm `All steps done`; `Mark done` → `POST …/complete`), `completeWithUntickedStepsAsks` (checkbox → `Mark the task done?` with `2 steps are not ticked yet.`), `skipConfirmsAndReportsNext` (`Skip Call back` → confirm → `POST …/skip` → `Skipped. Next one scheduled.`), `fromMarkerForDelegatedTask` (`assignedBy` u2, assignee = me → `from Priya Agent`; assignedBy = me → no marker), `viewOnlyAssigneeCanWorkOwnTaskOnly` (session authorities `['crm:view']`, two tasks: mine → checkbox enabled, Skip present, no Edit/Delete; other's → checkbox disabled), `cancelledTaskShowsSkipped`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `taskList.test.tsx`, `tasks.test.tsx` green; `tsc`; eslint.
- [ ] **Step 5: Commit** `One task row for drawers and the Tasks page, with ticking and skip`.

### Task 4: Tasks page Delegated tab, assignment toasts, build

**Files:**
- Modify: `src/app/Crm/tasks/TasksPage.tsx` (fourth tab, `Show done` switch, hide the picker on Delegated, rows via `TaskRow`, empty copy per tab, assignee shown on Delegated rows), `src/app/Crm/tasks/CrmReminders.tsx` (ASSIGNED title; `kind` ignored for keys — one entry per task per contract), `src/app/routes.tsx` (nav count unchanged — it already counts the list)
- Test: `tasks.test.tsx` (extend), `reminders.test.tsx` (extend)

- [ ] **Step 1: Failing tests.** `tasks.test.tsx`: `delegatedTabListsHandOffs` (counts `{…, delegated: 2}` → tab `Delegated (2)`; click → `GET …/tasks/delegated?status=OPEN`; rows show the assignee; `Show done` → `status=DONE`; empty → `None of the tasks you handed over are done yet.`), `delegatedIgnoresAssigneePicker` (view-all session, pick someone, go to Delegated → picker gone, the delegated call has no `assigneeId`), `emptyCopyPerTab`. `reminders.test.tsx`: `assignedToastShowsOncePerTask` (list with `kind: 'ASSIGNED', assignedBy: {id: 'u4', name: 'Meera Manager'}` → title `New task from Meera Manager: Call back`; close → `POST …/seen`, count drops to 0).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; then `npx vitest run src/app/Crm --maxWorkers=4`, `npx tsc --noEmit`, `npm run lint`, `npm run build`.
- [ ] **Step 5: Commit** `Delegated tasks tab and assignment notices`.

## Acceptance walk (after both plans, as `demo-manager` and `demo-agent`, app on :8089)

Spec §9, steps 1–10, in order. Record each step's outcome in the ledger.
