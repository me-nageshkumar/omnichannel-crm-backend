# CRM phase E: task delegation, checklists and repeating tasks (design)

**Status:** written 2026-10-31 at the user's request ("Continue with task phase"), under the standing instruction from
phase D to skip the questions and write the spec. Every call in §3 is the designer's, taken from the reference's
tasks phase 2.1 (`/Users/nagesh/RedLava/SCM/CRM/redlava-crm` branch `crm-addon-phase-3`: `CrmTaskServiceImpl`,
`CrmTaskAssigneeCheck`, `CrmTaskRecurrenceSchedule`, `CrmTaskChecklistItem`, its `docs/curls/crm.md` §"Delegation,
checklists and recurring tasks"; console `src/Components/Crm/Tasks/{TaskList, TasksPage, TaskFormModal, RepeatFields,
ChecklistEditor, taskUtils}`) and translated to this host; flagged, open to change. Extends the port design
(`2026-10-23-crm-port-design.md` §6.4) and keeps phase D's naming rule (`2026-10-31-crm-segments-campaigns-design.md`
C1): nothing new says `phoneNumberId`. Where this disagrees with the port design, this one wins.

## 1. Intent

A manager hands a follow-up to an agent and wants to know it landed and whether it got done; the agent wants to see what
was handed to them and work it without needing edit rights on the whole CRM. A task that is really a small procedure
("send price list, collect GST number, book the demo") wants steps that can be ticked one by one. A standing routine
("call the distributor every Monday and Thursday") wants to repeat without someone re-creating it.

Phase E adds, to the existing tasks:

- **Delegation**: who assigned a task and when; a check that the assignee can actually see the record; a *Delegated*
  tab listing what I handed to others; an in-console notice to the assignee; the assignee may complete, reopen, skip and
  tick their own task with view rights only.
- **Checklists**: ordered steps on a task, ticked atomically on the row; progress shown everywhere the task is.
- **Repeating tasks**: daily / weekly / monthly rules; completing or skipping an occurrence schedules the next one.

What is different from the reference, and why (details in §3):

- The reference pushes a mobile notification through the main app on assignment. The host has no push; phase A put
  reminders behind a console poll (`GET /crm/reminders`). Assignment notices ride the same poll (C4).
- The reference's `POST /tasks/save` takes partial updates with `clearDueAt` / `clearRecurrence` flags. Our `PUT` is a
  full replace (null clears, as in phase A), so no flags: a `PUT` without `recurrence` stops the repeat, without
  `checklist` removes the steps (C6, C9).
- The reference reports assignee problems as coded 400s (`UNKNOWN_ASSIGNEE`…). Ours are field errors on `assigneeId`,
  which the console already shows under the Assignee field (C2).
- The reference lists tasks then filters by target access in memory; ours already filters in the query
  (`TaskAccess.visibleCriteria`), and the Delegated list does the same (C5).

## 2. Decisions carried from earlier phases (binding)

| # | Decision |
|---|---|
| D1 | Module boundary: `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; platform data through `crm.platform` ports with adapters in `app.crm` (`CrmModuleBoundaryTest`). |
| D2 | Every query scoped by `workspaceId` first; every new index leads with `workspaceId`; collections camelCase. |
| D3 | Access: `crm:view` opens the CRM; a task is visible to whoever may see its target (`TaskAccess`); `crm:edit` writes. Fail closed. |
| D4 | Errors `{code, message, ...extras}` through `CrmErrors`/`CodedException`; validation errors are a map of field → message (`ValidationException`), shown by the console under the field when the form renders it, else in the banner. |
| D5 | No live updates; screens refresh on action and on focus; reminders are polled every 60 s by `CrmReminders`. |
| D6 | Naming is channel-neutral in new code (phase D C1). Existing `CrmTask.phoneNumberId` stays (phase A name). |
| D7 | Console: React 18 + PatternFly 6.5 + TypeScript, vitest; CRM pages under `src/app/Crm/`, `useCrmCan`, `useLoad`/`useAction`. |
| D8 | Tasks today (phase A §6.4): `TaskRequest` full replace on `PUT` (null clears, except `assigneeId` and `target`); `TaskView`; buckets by the browser's zone; `TaskCounters.recompute` keeps `nextTaskDueAt`; timeline `TASK_CREATED`, `TASK_COMPLETED`, `TASK_UPDATED {action}`; optimistic `version` through `guarded()`. |

## 3. Calls made by the designer (flagged; change if wrong)

### Delegation

| # | Call | Why |
|---|---|---|
| C1 | **Assigner on the task.** `CrmTask` gains `assignedBy` (user id) and `assignedAt`. On create both are the creator and now (also when the creator assigns themself). On update, when `assigneeId` changes, both become the caller and now; otherwise untouched. A task is *delegated* when `assignedBy != assigneeId`. | Reference C1–C3; lets both "from whom" and the Delegated list be plain queries. |
| C2 | **The assignee must be able to work the task.** New `TaskAssigneeCheck.assertAssignable(ctx, assigneeId, target)` runs on create and on an update that changes the assignee (an unchanged assignee is not re-checked, so a task stays editable after its assignee lost access). Assigning yourself is never checked. Otherwise, in order: the assignee must be a user of the brand with access to it (else field error `assigneeId` "is not a user of this brand"), must hold `crm:view` there (else "cannot use the CRM"), and must be able to see the target under **their own** pipeline access (else "cannot see this record"). The last check builds the assignee's `CrmContext` through a new `CrmAccess.resolveFor(callerCtx, userId)` and calls `TaskAccess.assertVisible` with it. Authorities of another user come through a new port method `PeopleDirectory.authoritiesIn(workspaceId, userId): Optional<Set<String>>` (empty when the user is missing, disabled, or has no access to the brand), implemented in `app.crm` with `UserRepository` + `AccessService.load/inWorkspace`. | Reference `CrmTaskAssigneeCheck`; field errors instead of codes because the console's task form already renders `fieldErrors.assigneeId`. |
| C3 | **Work rights.** The assignee may complete, reopen, skip and tick their own task with `crm:view` alone; everyone else needs `crm:edit`. `TaskService.assertCanWork(ctx, task)`: `ctx.can("crm:edit") || task.assigneeId == ctx.userId()` else 403 "requires crm:edit or being the assignee". Controller `@PreAuthorize` on complete / reopen / skip / checklist tick drops to `crm:view`. Edit, reassign and delete keep `crm:edit`. | Reference 2.1 ("The assignee may complete, reopen, skip and tick their own task with view access only"). |
| C4 | **Assignment notice through the reminders poll.** `CrmTask` gains `assignmentSeenAt`. `GET /crm/reminders` returns, besides due reminders, every OPEN task where `assigneeId == me`, `assignedBy != me` and `assignmentSeenAt == null`, as `ReminderView {kind: "ASSIGNED", assignedBy: Ref}`; due reminders carry `kind: "DUE"`. **One entry per task**: a task that is both newly assigned and due is listed once, as ASSIGNED (the hand-off is the news; the due time is on the toast); once the notice is seen it shows as DUE if still due. Together at most 20, newest first (`assignedAt` / `reminderSentAt`). `POST /reminders/{taskId}/seen` sets `assignmentSeenAt` (if null) **and** `reminderSeenAt` (if `reminderSentAt` is set). Reassigning to someone else unsets `assignmentSeenAt` and re-arms the due reminder (unsets `reminderSentAt`, `reminderSeenAt`), so the new assignee gets both notices in turn and the old one's dismissal does not carry over. The console toasts "New task from <name>: <title>" with *Open*; the Tasks nav count includes these. The next occurrence of a repeating task copies `assignedBy`/`assignedAt` and sets `assignmentSeenAt = creation time` (no notice: the assignee did not change — reference). | Host has no push; the poll exists and the toast pattern is shipped. |
| C5 | **Delegated list and count.** `GET /crm/tasks/delegated?status=OPEN|DONE&limit=` lists tasks with `assignedBy == me`, `assigneeId != me`, the given status, within `TaskAccess.visibleCriteria(ctx)`: OPEN by `dueAt` ascending (undated last), DONE by `completedAt` descending; `limit` 1–200, default 50; any other status → field error `status` "must be OPEN or DONE". `GET /tasks/mine/counts` gains `delegated` = the caller's open hand-offs — **always the caller's**, even with `?assigneeId=` (a manager looking at someone else's buckets still sees their own Delegated count). | Reference C5 and its later fix (`32ef7e0`). |
| C6 | **Timeline.** Reassignment records `TASK_UPDATED {action: "REASSIGNED", fromAssigneeId, toAssigneeId}` with summary "Task reassigned to <assignee name>: <title>"; creating a task for someone else records the usual `TASK_CREATED` with `payload.assigneeId`. | Reference; the console's timeline falls back to `summary`. |

### Checklists

| # | Call | Why |
|---|---|---|
| C7 | **Model.** `CrmTask.checklist: List<ChecklistItem{id, text, done, doneAt, doneBy}>` embedded, order = list order; `id` a 24-hex ObjectId string assigned by the server. At most **30** items (`checklist` "at most 30 items"); `text` trimmed, 1–200 chars (`checklist[i].text` "must be 1 to 200 characters"). | Reference C6. |
| C8 | **Save merges by id.** `TaskRequest.checklist: List<{id?, text}>`. On create and update the list is replaced in the order sent: an item whose `id` is already on the task keeps `done/doneAt/doneBy`; anything else (no id, unknown id, an id sent twice) is a new, undone item with a fresh id; items not sent are dropped. `done` flags in the request are ignored. `PUT` without `checklist` (null) removes every step, like every other nullable field on `PUT`. Saving the list is a normal update (version-guarded, `TASK_UPDATED {action: "UPDATED"}`). | Reference `mergeChecklist`; the console sends the whole form on save. |
| C9 | **Tick atomically.** `POST /crm/tasks/{id}/checklist/{itemId}` body `{done: boolean}` → `TaskView`. One `findAndModify` matching `{_id, workspaceId, status: OPEN, "checklist.id": itemId}` setting `checklist.$.done/doneAt/doneBy`, `lastUpdateTime`, `lastModifiedBy`, and `$inc version` (so a stale full save then fails with `VERSION_CONFLICT`). No `version` in the request. Work rights as C3. 400 `{code: "TASK_NOT_OPEN"}` when the task is DONE or CANCELLED (also when it closed between the read and the write); 404 for an unknown task or item. **No timeline entry, no counter recompute** (the due time did not move). | Reference C6, C8. |
| C10 | **Console behaviour.** Ticking happens on the task row (drawer tabs, Tasks page), never in the form: the row shows a "3/5" progress label that expands the steps as checkboxes. Completing a task with unticked steps asks "Mark the task done? 2 steps are not ticked yet." Ticking the last step offers "All steps done. Mark the task done?" | Reference console. |

### Repeating tasks

| # | Call | Why |
|---|---|---|
| C11 | **Rule.** `CrmTask.recurrence: Recurrence{frequency: DAILY|WEEKLY|MONTHLY, interval: 1–99 (default 1), weekdays: List<DayOfWeek> (WEEKLY only, ≥ 1, sorted Monday first), dayOfMonth: 1–31 (MONTHLY only; default = the first due date's day in the zone; clamped to short months when stepping), timeZone: IANA id (default UTC), endsAfter: 2–999 (occurrences counting the first) | endsOn: yyyy-MM-dd (not before the first due date)}`; `endsAfter` and `endsOn` are exclusive; neither = forever. Series fields on the task: `seriesId` (the first task's id; kept when the repeat is removed), `occurrence` (1-based), `recurringFromTaskId`, `nextOccurrenceId` (set once the next one was created). A repeating task needs a due date. Validation errors are field errors: `recurrence` "needs a due date" / "set endsAfter or endsOn, not both"; `recurrence.frequency` "must be DAILY, WEEKLY or MONTHLY"; `recurrence.interval` "must be 1 to 99"; `recurrence.weekdays` "pick at least one weekday"; `recurrence.dayOfMonth` "must be 1 to 31"; `recurrence.timeZone` "must be an IANA time zone"; `recurrence.endsAfter` "must be 2 to 999"; `recurrence.endsOn` "must be yyyy-mm-dd" / "must not be before the due date". The stored rule is the normalised copy (`TaskRecurrenceSchedule.normalize`). | Reference C9 and `CrmTaskRecurrenceSchedule`; field errors per D4. |
| C12 | **Next due date.** `TaskRecurrenceSchedule.nextDue(rule, currentDue, currentOccurrence, now): Optional<Instant>`: step from the current due date in the rule's zone on the local date-time (the wall-clock time survives DST; a time inside a DST gap moves forward), skipping any step that is not strictly after `now` (missed occurrences are dropped, not counted), until one is in the future; empty when `occurrence + 1 > endsAfter`, when the date is after `endsOn`, or after 100,000 steps. DAILY: `+interval days`. WEEKLY: the next listed weekday later in the same Monday-start week, else the first listed weekday `interval` weeks on. MONTHLY: `+interval months` on `min(dayOfMonth, length of month)`. Pure, unit-tested with the reference's cases (every-2-days; Mon/Thu within the week then jumps; from an unlisted day; month-end clamp 31 → 28 → 31; leap year; DST wall-clock; missed occurrences skipped; endsAfter; endsOn). | Reference C10. |
| C13 | **Scheduling the next one.** When a repeating OPEN task is completed or skipped and it has a due date, no `nextOccurrenceId` and `nextDue` is present: a new task is inserted with the same title, description, type, priority, target, `assigneeId`, `assignedBy`, `assignedAt`, `recurrence`, `seriesId` (= the finished task's `seriesId`, or its id), `occurrence + 1`, `recurringFromTaskId` = the finished task's id, `assignmentSeenAt` = now, `pipelineId`/`phoneNumberId` re-read from the target now (it may have moved), the checklist copied as fresh undone items, and `reminderAt` = next due − (old due − old reminder) unless that is already past (then none). The finished task gets `nextOccurrenceId` **in the same version-guarded write** that closes it, so a racing second completion fails on the version before it could create a second occurrence; reopening and completing again creates nothing. The new task records `TASK_CREATED` with summary "Next task scheduled: <title>" and `payload.recurringFromTaskId`; `TaskCounters.recompute` runs for the target. The response of `complete` is the finished task (with `nextOccurrenceId`). Deleting a task ends its series (nothing is scheduled from a deleted task); a `PUT` without `recurrence` makes the task a one-off and keeps `seriesId`. | Reference `prepareNextOccurrence`/`insertNextOccurrence`. |
| C14 | **Skip.** `POST /crm/tasks/{id}/skip` → `{skipped: TaskView, next: TaskView | null}`: the task becomes CANCELLED (`TASK_UPDATED {action: "SKIPPED"}`, summary "Task skipped: <title>") and the next occurrence is created as in C13. 400 `{code: "TASK_NOT_OPEN"}` unless OPEN; 400 `{code: "NOT_RECURRING"}` for a one-off. Work rights as C3. CANCELLED tasks appear in a record's task list (after open ones, struck through with a "Skipped" label), never in buckets, counts or reminders. `TaskStatus` already has `CANCELLED`; the console's `TaskStatus` type gains it. | Reference C11. |

### Console

| # | Call | Why |
|---|---|---|
| C15 | **One task row everywhere.** A shared `TaskRow` (complete/reopen checkbox, title, due label, assignee, "from <name>" when delegated to me, repeat icon with the rule's summary as tooltip, checklist progress label that expands to tick, HIGH/LOW priority label, "Skipped" label, skip / edit / delete actions by rights) replaces the ad-hoc rows in `TaskList` (drawers) and the table in `TasksPage`. The Tasks page keeps its table layout only for the record link column, i.e. the row renders inside the table cell set; implementers may keep the table and put the row's parts in cells — the acceptance walk cares about names and behaviour, not markup. | Reference `TaskRow`; two copies of the same row already drifted in phase A. |
| C16 | **Tasks page.** Tabs *Overdue (n) · Today (n) · Upcoming (n) · Delegated (n)*. Delegated lists `GET /tasks/delegated` with a *Show done* switch (OPEN → DONE), ignores the assignee picker (hidden on that tab), shows the assignee on every row, empty copy "You have no open tasks with other people." / "None of the tasks you handed over are done yet." The other tabs get "Nothing overdue." / "Nothing due today." / "Nothing coming up." | Reference `TasksPage`. |
| C17 | **Task form.** Below Description: *Steps* (checklist editor: text inputs in order, move up/down, remove, "Add a step" input + button, disabled at 30 with "At most 30 steps"; ticked items show "done" and keep their id) and *Repeat* (select: Does not repeat / Daily / Weekly / Monthly; *Every* N day(s)/week(s)/month(s); *On* weekday checkboxes for weekly, defaulting to the due date's weekday; *Ends* radio Never / After N times / On date; a summary line "Repeats every 2 weeks on Mon, Thu until 31 Dec 2026. The next task is created when this one is done."). Repeat needs a due date ("Set a due date to repeat."). The rule's `timeZone` is the browser's on create and the stored one on edit; `dayOfMonth` is kept from the stored rule while it stays monthly. Server field errors under `recurrence.*` and `checklist*` show under the matching control (the form's `useAction` field list names them). | Reference `RepeatFields`, `ChecklistEditor`, `taskUtils.recurrenceFromForm`. |
| C18 | **Notices.** `CrmReminders` renders `kind: "ASSIGNED"` as "New task from <name>: <title>" (info toast, *Open* goes to the record); `DUE` as today. The nav count counts both. | C4. |
| C19 | **Timeline.** `CrmTimeline` keeps the fallback (summary) for `TASK_UPDATED` REASSIGNED / SKIPPED and the "Next task scheduled" `TASK_CREATED`; no new rendering. | The summaries already read well. |

### Settled after the builder check (a fresh reviewer read the draft; these are the designer's answers, flagged)

| # | Call |
|---|---|
| C20 | One reminders entry per task; ASSIGNED wins over DUE until the hand-off is seen (C4). The console keeps keying by `taskId`. |
| C21 | Reassignment re-arms the due reminder for the new assignee (C4). |
| C22 | The demo changes are an idempotent step that also upgrades databases seeded before phase E (§7). |
| C23 | C1 stands as written: whoever changes the assignee becomes `assignedBy`; the acceptance walk has the manager do the reassignment. A delegator who hands a task on keeps nothing — that is a new hand-off by them. |

## 4. Data model (changes to `crmTasks`)

```
CrmTask (+)
  assignedBy: String            // user id; the creator, or whoever last changed the assignee
  assignedAt: Instant
  assignmentSeenAt: Instant     // null until the assignee opened/closed the notice; unset on reassignment
  checklist: [ { id: String (24-hex), text: String, done: boolean, doneAt: Instant, doneBy: String } ]
  recurrence: { frequency, interval, weekdays, dayOfMonth, timeZone, endsAfter, endsOn }   // normalised, C11
  seriesId: String              // the first task's id; kept when the repeat is removed
  occurrence: Integer           // 1-based
  recurringFromTaskId: String
  nextOccurrenceId: String      // set once, in the write that completes or skips the task
indexes (+)
  ws_assignedBy_status_due  {workspaceId: 1, assignedBy: 1, status: 1, dueAt: 1}
```

Existing tasks have none of the new fields: `assignedBy` null reads as "not delegated" (never shown as "from"), and
they never appear in anyone's Delegated list. No migration.

## 5. API (`/api/workspaces/{ws}/crm/tasks`; JSON; `crm:view`, plus `crm:edit` where marked)

| Call | Rights | Request | Response |
|---|---|---|---|
| `POST /` | edit | `TaskRequest` + `checklist?: [{id?, text}]`, `recurrence?: Recurrence` | `TaskView` |
| `PUT /{id}` | edit | same, full replace, `version` required | `TaskView`; 409 `VERSION_CONFLICT` |
| `POST /{id}/complete` | view + C3 | — | `TaskView` (with `nextOccurrenceId` when one was scheduled) |
| `POST /{id}/reopen` | view + C3 | — | `TaskView` |
| `POST /{id}/skip` | view + C3 | — | `{skipped: TaskView, next: TaskView \| null}`; 400 `TASK_NOT_OPEN`, `NOT_RECURRING` |
| `POST /{id}/checklist/{itemId}` | view + C3 | `{done: boolean}` | `TaskView`; 400 `TASK_NOT_OPEN`; 404 |
| `DELETE /{id}` | edit | — | 204 |
| `GET /delegated?status=OPEN\|DONE&limit=` | view | — | `TaskView[]` |
| `GET /mine/counts?tz=&assigneeId=` | view | — | `{overdue, today, upcoming, delegated}` |
| `GET …/crm/reminders` | view | — | `ReminderView[]` with `kind: "DUE" \| "ASSIGNED"`, `assignedBy: Ref \| null` |
| `POST …/crm/reminders/{taskId}/seen` | view | — | 204 (marks both notices, C4) |

`TaskView` (+): `assignedBy: Ref | null`, `assignedAt`, `checklist: [{id, text, done, doneAt, doneBy: Ref | null}]`
(empty list when none), `recurrence: Recurrence | null`, `seriesId`, `occurrence`, `recurringFromTaskId`,
`nextOccurrenceId`. `status` may be `CANCELLED`. Validation errors: 400 `{code: "VALIDATION", fields: {field:
message}}` in the existing shape, with the field keys of C2, C7, C11 and C5.

## 6. Console (`src/app/Crm/tasks/`, plus small changes elsewhere)

- `src/app/api/crm.ts`: `TaskStatus` + `'CANCELLED'`; `Task` + the §5 fields; `TaskInput` + `checklist`, `recurrence`;
  `Recurrence`, `ChecklistItem` types; `TaskCounts.delegated`; `Reminder.kind`, `Reminder.assignedBy`;
  `delegatedTasks(token, ws, {status, limit})`, `skipTask`, `tickChecklistItem`.
- `taskDisplay.ts` (+): `checklistProgress(task)`, `recurrenceSummary(rule)`, `isDelegatedToMe(task, userId)`,
  `canWorkTask(task, canEdit, userId)`, weekday/frequency option lists, `recurrenceFromForm` / `recurrenceToForm`.
- New `TaskRow.tsx` (C15), `ChecklistEditor.tsx`, `RepeatFields.tsx`; `TaskFormModal` uses the last two; `TaskList` and
  `TasksPage` use `TaskRow`; a shared `useTaskActions` hook (complete with the unticked-steps confirm, reopen, tick with
  the all-done offer, skip, delete) so both screens behave alike.
- `TasksPage`: the Delegated tab, counts, *Show done*, empty copy (C16).
- `CrmReminders`: the ASSIGNED toast (C18). `CrmTimeline`: unchanged (C19).
- Tests: `taskDisplay.test.ts` (summary, progress, next-weekday default, form round trip), `taskRow.test.tsx` (tick
  expands and posts, skip, from-marker, rights), `taskForm.test.tsx` (steps editor, repeat fields, field errors),
  `tasks.test.tsx` (+ Delegated tab and show-done, counts), `reminders.test.tsx` (+ ASSIGNED toast and count).

## 7. Demo data (`CrmDemoSeeder`)

The three seeded `demo-agent` tasks are handed over by `demo-manager`: after creating them the seeder sets `assignedBy`
= the demo manager's id, `assignedAt` = creation time (by collection name, no version bump), so the manager's Delegated
tab shows 3 and the agent's rows read "from Meera Manager" (or whatever the seeded manager is called) and the agent gets
three ASSIGNED notices on first sign-in. This runs as its own idempotent step (`seedTaskExtras`, like phase D's `seedSegments`): on a database seeded before phase E it finds the three tasks by title in the demo brand and applies the same changes when `assignedBy` is still null; a second run changes nothing. "Send the festive price list" gets the steps *Attach the catalogue* (ticked by
the manager), *Mention the festive discount*, *Ask about quantities*. "Call about uniform sizes" repeats weekly on its
due weekday in the brand's zone, forever. `CrmDemoSeederIT` asserts the three facts.

## 8. Out of scope (explicit)

- Mobile or e-mail push on assignment (the host has none); the notice is the console poll.
- Editing a whole series at once: each occurrence is its own task, as in the reference; changing the rule on one
  occurrence changes what *its* next occurrence inherits.
- Cancelling a one-off task (no `status` on `PUT`; delete it). Skip is the only path to CANCELLED.
- Delegating to a team, due-date shifting of the whole series, a calendar view, reminders for delegators.
- A distributed lock for the reminder job (single instance today, as before).

## 9. Acceptance (end to end, as `demo-manager` and `demo-agent` on the seeded brand)

1. Tasks page as the manager: four tabs with counts; *Delegated (3)* lists the three agent tasks with the assignee shown;
   *Show done* flips to an empty "None of the tasks you handed over are done yet."
2. As the agent: the Tasks nav shows a count; three "New task from … " toasts; *Open* goes to the deal and the count
   drops; the rows read "from <manager>".
3. On "Send the festive price list" the agent (view-only on nothing — the demo agent has `crm:edit`, so also try with a
   role without it) expands *1/3*, ticks a step (the label becomes 2/3 at once, no page reload), ticks the last one and
   accepts "Mark the task done?": the task leaves *Overdue* (the seed dates it yesterday); the deal's timeline shows "Task done".
4. Open the deal drawer's Tasks tab: the done task is struck through with its 3/3 label; *Edit* on "Call about uniform
   sizes" shows *Repeat: Weekly, every 1 week, on <weekday>, Ends: Never* and the summary line; save without changes
   succeeds (no spurious error on the kept `dayOfMonth`/`timeZone`).
5. Complete "Call about uniform sizes": a new task with the same title appears, due next week on the same weekday and
   time; the timeline shows "Next task scheduled". Reopen the first and complete it again: nothing new appears.
6. On the new occurrence click *Skip*, confirm: it shows as *Skipped* in the drawer, and a further occurrence exists;
   the Tasks page buckets never list the skipped one.
7. New task on a contact with *Repeat: Daily* and no due date → "Set a due date to repeat." under Repeat; set a due
   date, *Ends after 1* → "must be 2 to 999" under the count after save.
8. New task assigned to a user who has no CRM role in the brand → "cannot use the CRM" under Assignee; assigned to the
   agent on a deal in a pipeline the agent is not assigned to → "cannot see this record".
9. As the manager, reassign one of the three tasks from the agent to the manager: the deal timeline shows "Task reassigned to <manager>: <title>"; the
   agent's Delegated count is unchanged (they never assigned it); the manager's drops by one.
10. Tick a step from one tab, then save the task's form from a second tab opened earlier → "Someone else changed this
    task. Close this form and open it again." (the tick bumped the version).
