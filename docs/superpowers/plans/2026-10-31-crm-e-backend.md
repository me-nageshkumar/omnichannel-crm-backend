# CRM phase E (backend): task delegation, checklists and repeating tasks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `crm.task` with delegation (assigner fields, an assignee check through a new people port, work rights for the assignee, a Delegated list and count, assignment notices on the reminders poll), ordered checklists with an atomic tick endpoint, and repeating tasks (normalised rule, pure schedule maths, next occurrence on complete or skip, a skip endpoint); seed demo data and document it.

**Architecture:** Everything stays in `omnichannel-crm`'s `crm.task` package: `CrmTask` grows embedded `ChecklistItem` and `Recurrence`; `TaskAssigneeCheck` (new) builds the assignee's context through `CrmAccess.resolveFor` and a new port method `PeopleDirectory.authoritiesIn` (adapter in `app.crm.PeopleDirectoryAdapter` using `UserRepository` + `AccessService`); `Checklists` (pure merge/copy) and `TaskRecurrenceSchedule` (pure normalise/next-due) carry the logic that unit tests pin; `TaskService` grows `assertCanWork`, `delegated`, `tick`, `skip` and the next-occurrence step inside `complete`/`skip`; `ReminderService` returns two kinds of notice; `TaskErrors` holds the two coded 400s. No new collection.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB (`MongoTemplate`, `findAndModify`), JUnit 5 + AssertJ + MockMvc, embedded Mongo in `*IT`.

**Spec:** `docs/superpowers/specs/2026-10-31-crm-tasks-delegation-design.md` (C1–C14, §4, §5, §7). Reference behaviour where this plan is silent: `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/task/{CrmTaskServiceImpl, CrmTaskAssigneeCheck, CrmTaskRecurrenceSchedule, CrmTaskCustomRepositoryImpl}` translated per the port design's §3 table.

**Branch:** `crm-e` worktree at `.worktrees/crm-e` cut from `main` (both repos). Commands: module tests `./mvnw -q -pl omnichannel-crm -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; app ITs `./mvnw -q -pl omnichannel-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; all CRM tests `'-Dtest=**/crm/**/*'`; full `./mvnw -q verify`. Never run two Maven builds in one worktree at once. Absolute paths in every command (the shell's cwd drifts).

## Global Constraints

- Phase A–D Global Constraints apply: module boundary (`CrmModuleBoundaryTest`, `ServiceBoundaryTest`), every query scoped by `workspaceId` first, the new index leads with `workspaceId` (`CrmIndexIsolationIT`), errors `{code, message, ...extras}` through `CrmErrors`/`CodedException`, validation errors through `ValidationException` (wire shape `{code: "VALIDATION", fields: {key: message}}`), writes that must not bump a record's `@Version` go by collection name.
- **Naming (spec D6):** nothing new says `phoneNumberId`; the existing `CrmTask.phoneNumberId` is copied as is.
- **Field error keys and messages, exactly (C2, C5, C7, C11):** `assigneeId` → "is not a user of this brand" | "cannot use the CRM" | "cannot see this record"; `status` → "must be OPEN or DONE"; `checklist` → "at most 30 items"; `checklist[i].text` → "must be 1 to 200 characters"; `recurrence` → "needs a due date" | "set endsAfter or endsOn, not both"; `recurrence.frequency` → "must be DAILY, WEEKLY or MONTHLY"; `recurrence.interval` → "must be 1 to 99"; `recurrence.weekdays` → "pick at least one weekday"; `recurrence.dayOfMonth` → "must be 1 to 31"; `recurrence.timeZone` → "must be an IANA time zone"; `recurrence.endsAfter` → "must be 2 to 999"; `recurrence.endsOn` → "must be yyyy-mm-dd" | "must not be before the due date".
- **Coded errors:** `TaskErrors.notOpen()` → 400 `TASK_NOT_OPEN` "Only an open task can be changed this way"; `TaskErrors.notRecurring()` → 400 `NOT_RECURRING` "Only a repeating task can be skipped"; work rights → `ForbiddenException("requires crm:edit or being the assignee")` (403).
- **Limits:** checklist ≤ 30 items, text 1–200 chars; interval 1–99; endsAfter 2–999; schedule stepping stops after 100,000 steps.
- **Timeline summaries, exactly:** reassignment `TASK_UPDATED {action: "REASSIGNED", fromAssigneeId, toAssigneeId}` "Task reassigned to <name>: <title>" (name from `PeopleDirectory.people`, else the id); skip `TASK_UPDATED {action: "SKIPPED"}` "Task skipped: <title>"; next occurrence `TASK_CREATED {taskId, recurringFromTaskId}` "Next task scheduled: <title>". Ticking writes nothing.
- `PUT` stays a full replace: `checklist` null → no steps; `recurrence` null → one-off (keep `seriesId`); `assigneeId` null → unchanged (phase A).
- Commits: one per task, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. **An assignee who lost pipeline access after the hand-off**: the manager can still edit the task's title without touching the assignee (Task 1 `unchangedAssigneeNotRechecked`).
2. **A tick racing a full save**: the tick bumps the version, the stale `PUT` gets 409 and the tick is not lost (Task 3 `tickBumpsVersionSoStaleSaveConflicts`).
3. **Two completions of the same repeating occurrence at once**: exactly one next occurrence exists and the loser gets 409 (Task 5 `racingCompletionsScheduleOneOccurrence`).
4. **A manager looking at an agent's buckets** (`?assigneeId=`): `delegated` is still the manager's own hand-offs (Task 2 `delegatedCountIsAlwaysTheCallers`).
5. **A late completion of a repeating task whose reminder offset is longer than the gap to the next due date**: the next occurrence has no reminder instead of one in the past (Task 5 `pastReminderOffsetIsDropped`).

---

### Task 1: Delegation fields, assignee check through the people port

**Files:**
- Modify: `omnichannel-crm/.../crm/task/CrmTask.java` (+ `assignedBy`, `assignedAt`, `assignmentSeenAt`, `checklist`, `recurrence`, `seriesId`, `occurrence`, `recurringFromTaskId`, `nextOccurrenceId`; `@CompoundIndex(name = "ws_assignedBy_status_due", def = "{'workspaceId': 1, 'assignedBy': 1, 'status': 1, 'dueAt': 1}")`), `dto/TaskRequest.java` (+ `List<ChecklistItemRequest> checklist`, `Recurrence recurrence` appended), `dto/TaskView.java` (+ fields per spec §5; nested `ChecklistItemView(String id, String text, boolean done, Instant doneAt, Ref doneBy)`), `TaskService.java` (create/update set `assignedBy`/`assignedAt`, call the check, REASSIGNED timeline, unset `assignmentSeenAt` on reassignment; `views` resolves `assignedBy` and `doneBy` names), `crm/platform/PeopleDirectory.java` (+ `authoritiesIn`), `crm/access/CrmAccess.java` (+ `resolveFor`), `omnichannel-app/.../app/crm/PeopleDirectoryAdapter.java` (implement `authoritiesIn`), `crm/demo/CrmDemoSeeder.java` (the three `new TaskRequest(...)` calls gain `null, null`)
- Create: `crm/task/ChecklistItem.java` (`@Getter @Setter` class: `id`, `text`, `done`, `doneAt`, `doneBy`), `crm/task/Recurrence.java` (record `Recurrence(RecurrenceFrequency frequency, Integer interval, List<DayOfWeek> weekdays, Integer dayOfMonth, String timeZone, Integer endsAfter, String endsOn)`), `crm/task/RecurrenceFrequency.java` (`DAILY, WEEKLY, MONTHLY`), `crm/task/dto/ChecklistItemRequest.java` (record `(String id, String text)`), `crm/task/TaskAssigneeCheck.java`
- Test: `omnichannel-app/src/test/.../app/crm/TaskDelegationIT.java` (new, extends `CrmTestBase`), `PlatformAdaptersIT.java` (extend)

**Interfaces:**
- `PeopleDirectory.authoritiesIn(String workspaceId, String userId): Optional<Set<String>>` — empty when the user does not exist, is inactive/disabled, is brand-disabled there, or `AccessService.inWorkspace` throws `NotFoundException`; else the effective authorities. Adapter: `users.findById(userId)` → `access.load(user)` → `access.inWorkspace(principal, workspaceId)`.
- `CrmAccess.resolveFor(CrmContext caller, String userId): Optional<CrmContext>` — the other user's context in the caller's brand: authorities from `authoritiesIn` (empty → empty), `settings` = `caller.settings()`, numbers/chattable = `caller.numbers()`/`Set.of()`, pipelines = `ALL` with `crm:view-all` else `pipelineIdsOf(ws, userId) ∩ existing`. Does **not** require `crm:view` (the check decides what to say). Never used for a request's own context.
- `TaskAssigneeCheck(CrmAccess, TaskAccess)`; `assertAssignable(CrmContext ctx, String assigneeId, CrmTarget target): void` — returns at once when `assigneeId == null || assigneeId.equals(ctx.userId())`; else `ValidationException("assigneeId", …)` with the three messages in the Global Constraints order; "cannot see this record" when `TaskAccess.assertVisible(assigneeCtx, target)` throws `NotFoundException`.
- `TaskService.create`: `assignedBy = ctx.userId()`, `assignedAt = now`, `assignmentSeenAt = (assignee == creator) ? now : null`; check runs after `fields()` validation, before the insert. `update`: when the resolved assignee differs from `current.getAssigneeId()`: check, set `assignedBy/assignedAt`, `unset assignmentSeenAt, reminderSentAt, reminderSeenAt` (the reminder re-arms for the new assignee, spec C21), timeline REASSIGNED (instead of UPDATED). `TaskService.fields` keeps the existing `people.isBrandUser` check out: the new check replaces it (the first message covers it).
- `TaskView` gains `Ref assignedBy, Instant assignedAt, List<ChecklistItemView> checklist, Recurrence recurrence, String seriesId, Integer occurrence, String recurringFromTaskId, String nextOccurrenceId` — mapped in `views()`; `checklist` is `List.of()` when null. Checklist and recurrence are **not yet written** by this task (requests carrying them are ignored until Tasks 3–4).

- [ ] **Step 1: Failing tests.** `TaskDelegationIT`: `createRecordsAssigner` (manager creates for agent: `assignedBy.id` = manager, `assignedAt` set; raw doc `assignmentSeenAt` null; a self-assigned task has `assignmentSeenAt` set), `reassignRecordsTimelineAndAssigner` (manager2 reassigns agent → agent2: `assignedBy` = manager2, activity `TASK_UPDATED` with `payload.action == "REASSIGNED"`, `fromAssigneeId`, `toAssigneeId`, summary starts "Task reassigned to "), `assigneeMustBeBrandUser` (assign to `staff` → 400 `$.fields.assigneeId` = "is not a user of this brand"), `assigneeNeedsCrmView` (a brand user on a role created in the test with `RoleRepository`: name `NO_CRM`, level BRAND, authorities `{"inbox:view"}` → "cannot use the CRM"), `assigneeMustSeeTheRecord` (agent assigned to a second pipeline, task on a Sales deal → "cannot see this record"; company target → ok), `unchangedAssigneeNotRechecked` (assign agent while they see Sales, then `assignPipelines(agent, second)`, manager `PUT`s a new title with the same `assigneeId` → 200). `PlatformAdaptersIT.authoritiesInReflectsAccess` (manager → contains `crm:edit`; staff → empty; a disabled user → empty).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='TaskDelegationIT,PlatformAdaptersIT' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (compile: new fields/methods).
- [ ] **Step 3: Implement** per Interfaces. `TaskService` needs `TaskAssigneeCheck` injected (constructor); `CrmTestBase` is untouched (the test autowires `RoleRepository` itself).
- [ ] **Step 4: Run** → PASS; then `TasksApiIT`, `CrmDemoSeederIT`, `CrmModuleBoundaryTest`, `CrmIndexIsolationIT`.
- [ ] **Step 5: Commit** `Record who assigned a task and check the assignee can work it`.

### Task 2: Work rights, Delegated list and count, assignment notices

**Files:**
- Modify: `CrmTaskController.java` (`complete`, `reopen` → `@PreAuthorize(VIEW)`; + `GET /delegated`), `TaskService.java` (+ `assertCanWork`, `delegated`; `counts` + `delegated`), `dto/TaskCounts.java` (+ `long delegated`), `ReminderService.java` (ASSIGNED notices; `seen` marks both), `dto/ReminderView.java` (+ `ReminderKind kind`, `Ref assignedBy`), `CrmReminderController.java` (unchanged signatures)
- Create: `crm/task/ReminderKind.java` (`DUE, ASSIGNED`)
- Test: `TaskDelegationIT.java` (extend), `RemindersIT.java` (extend), `TasksApiIT.countsMatchBuckets` (assert `delegated`)

**Interfaces:**
- `TaskService.assertCanWork(CrmContext ctx, CrmTask t)`: `ctx.can("crm:edit") || ctx.userId().equals(t.getAssigneeId())` else `ForbiddenException`. Called by `complete`, `reopen` (replacing `ctx.require("crm:edit")`), and by Task 3's `tick` and Task 5's `skip`.
- `TaskService.delegated(CrmContext ctx, TaskStatus status, Integer limit): List<TaskView>` — `status` OPEN or DONE else `ValidationException("status", "must be OPEN or DONE")`; criteria `{workspaceId, assignedBy: me, assigneeId: {$ne: me}, status} ∧ visibleCriteria`; OPEN sorted like `mine` (dated ascending then undated), DONE `completedAt` desc; limit default 50, max 200.
- Controller: `@GetMapping("/delegated") @PreAuthorize(VIEW) delegated(me, workspaceId, @RequestParam(defaultValue = "OPEN") String status, @RequestParam(required = false) Integer limit)`.
- `counts` → `new TaskCounts(overdue, today, upcoming, delegated)` where `delegated` = count of `{assignedBy: ctx.userId(), assigneeId ≠ ctx.userId(), status OPEN} ∧ visibleCriteria` — the caller's, whatever `assigneeId` was asked for.
- `ReminderService.due`: DUE as today plus ASSIGNED = `{workspaceId, assigneeId: me, status OPEN, assignedBy: {$ne: me}, assignmentSeenAt: null} ∧ visibleCriteria` sorted by `assignedAt` desc; merge **one entry per task** (ASSIGNED wins when the task qualifies for both, spec C20), sort by notice time desc (`reminderSentAt` / `assignedAt`), take 20. `ReminderView(String taskId, String title, Instant dueAt, TaskView.Target target, ReminderKind kind, Ref assignedBy)`.
- `ReminderService.seen`: one `updateFirst` by collection name on `{_id, workspaceId, assigneeId: me}`: `set assignmentSeenAt = now` when null (use two updates or `$set` unconditionally — spec: set if null; implement as `updateFirst(... and("assignmentSeenAt").is(null), set)` then `updateFirst(... and("reminderSentAt").ne(null), set reminderSeenAt)`; 404 only when the task is not the caller's).

- [ ] **Step 1: Failing tests.** `TaskDelegationIT`: `assigneeCompletesAndReopensWithViewOnly` (a `CRM_VIEWER` role user — authorities `{"crm:view"}`, made like Task 1's `NO_CRM` — assigned by the manager completes → 200, reopens → 200; `agent2` (has `crm:edit`) completes a colleague's task → 200; a second `CRM_VIEWER` user → 403), `delegatedListsOpenAndDone` (manager hands two tasks to agent, one self-assigned; `GET /delegated` → the two, dated first; complete one; `?status=DONE` → it; `?status=CANCELLED` → 400 `$.fields.status`), `delegatedHidesInaccessibleTargets` (manager hands a task on a deal in a pipeline manager2 cannot see… use agent as the *delegator*: agent assigned to Sales hands a Sales-deal task to manager, then `assignPipelines(agent, second)` → agent's `/delegated` is empty), `delegatedCountIsAlwaysTheCallers` (manager hands 2 to agent; `GET /mine/counts?assigneeId=<agent>` → `delegated` 2; as agent `/mine/counts` → `delegated` 0). `RemindersIT`: `assignmentNoticeListedUntilSeen` (manager assigns agent; agent's `GET /reminders` has one item `kind ASSIGNED`, `assignedBy.id` manager; `POST …/seen` → gone; manager's own list has none), `selfAssignedTaskHasNoNotice`, `reassignmentReArmsNotice` (agent saw it; manager reassigns to agent2 → agent2 has a notice, agent none), `seenMarksBothNotices` (a task with a claimed reminder and an unseen assignment: listed **once** as ASSIGNED; `seen` once → list empty), `reassignmentReArmsReminder` (claimed reminder, reassign → raw `reminderSentAt` null; job run → claimed again for the new assignee).
- [ ] **Step 2: Run** `-Dtest='TaskDelegationIT,RemindersIT,TasksApiIT'` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `'-Dtest=**/crm/**/*'` for the module boundary and index tests.
- [ ] **Step 5: Commit** `Let assignees work their tasks, list delegated tasks and notify assignees in the console`.

### Task 3: Checklists with an atomic tick

**Files:**
- Create: `crm/task/Checklists.java` (pure), `crm/task/TaskErrors.java`, `crm/task/dto/ChecklistTickRequest.java` (record `(Boolean done)`)
- Modify: `TaskService.java` (`fields()` merges the checklist; + `tick`), `CrmTaskController.java` (+ `POST /{id}/checklist/{itemId}` with `@PreAuthorize(VIEW)`)
- Test: `omnichannel-crm/src/test/.../crm/task/ChecklistsTest.java`, `omnichannel-app/src/test/.../app/crm/TaskChecklistIT.java`

**Interfaces:**
- `Checklists.merge(List<ChecklistItem> current, List<ChecklistItemRequest> incoming, Map<String,String> errors): List<ChecklistItem>` — spec C8: order as sent; a known, not-yet-used id keeps `done/doneAt/doneBy`; else a new item with `new ObjectId().toHexString()`, `done=false`; `incoming == null` → `List.of()`; errors `checklist` "at most 30 items" (then return current unchanged), `checklist[i].text` "must be 1 to 200 characters" (trimmed). `Checklists.copyUndone(List<ChecklistItem>): List<ChecklistItem>` — fresh ids, text only (used by Task 5).
- `TaskErrors.notOpen()`, `TaskErrors.notRecurring()` → `CrmErrors.badRequest(code, message)`.
- `TaskService.tick(CrmContext ctx, String taskId, String itemId, boolean done): TaskView` — `load` (404 / access), `assertCanWork`, `status != OPEN` → `TaskErrors.notOpen()`, item id not on the task → `NotFoundException("Checklist item not found")`; then one `findAndModify` on `{_id, workspaceId, status: OPEN, "checklist.id": itemId}` with `set checklist.$.done`, `checklist.$.doneAt` (now | null), `checklist.$.doneBy` (userId | null), `lastUpdateTime`, `lastModifiedBy`, `inc version 1`, `returnNew`; null result → `TaskErrors.notOpen()` (closed meanwhile). No `counters.recompute`, no `record`.
- Controller: `tick(me, workspaceId, @PathVariable id, @PathVariable itemId, @RequestBody ChecklistTickRequest body)` → `body.done() == null` → `ValidationException("done", "is required")`.

- [ ] **Step 1: Failing tests.** `ChecklistsTest`: `keepsTicksOfKnownIdsInSentOrder`, `unknownOrRepeatedIdsBecomeNewItems`, `missingItemsAreDropped`, `nullMeansNoSteps`, `rejectsOver30`, `rejectsBlankAndLongText` (keys `checklist[1].text`), `copyUndoneResetsTicksWithFreshIds`. `TaskChecklistIT`: `saveMergesByIdAndIgnoresDoneFlags` (create with 2 steps; tick one; `PUT` with `[ {id of ticked, text changed}, {text new} ]` plus `done: false` on the ticked → ticked stays done with new text, new item undone, dropped one gone), `tickBumpsVersionSoStaleSaveConflicts` (read task v; tick → response `version` v+1, item `done`, `doneBy.id`; `PUT` with v → 409 `VERSION_CONFLICT`), `tickOnDoneTaskIs400` (`TASK_NOT_OPEN`), `unknownItemIs404`, `assigneeTicksWithViewOnly` (the `CRM_VIEWER` pattern: assignee 200, other viewer 403), `tickWritesNoTimelineAndKeepsCounters` (activity count unchanged; deal `nextTaskDueAt` unchanged), `over30StepsRejected` (`$.fields.checklist`).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-crm -am test -Dtest=ChecklistsTest -Dsurefire.failIfNoSpecifiedTests=false` and `-pl omnichannel-app … -Dtest=TaskChecklistIT` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `TasksApiIT`, `TaskDelegationIT`.
- [ ] **Step 5: Commit** `Add task checklists with an atomic tick`.

### Task 4: Recurrence rule normalisation and next-due maths

**Files:**
- Create: `crm/task/TaskRecurrenceSchedule.java` (final, static)
- Modify: `TaskService.java` (`fields()` normalises `recurrence` against `dueAt`; create pre-generates the id and sets `seriesId = id`, `occurrence = 1` when repeating; update sets `seriesId = id`, `occurrence = occurrence ?? 1` when a repeat is first added; `recurrence` null on `PUT` → `unset recurrence`, keep `seriesId`)
- Test: `omnichannel-crm/src/test/.../crm/task/TaskRecurrenceScheduleTest.java`, `omnichannel-app/src/test/.../app/crm/TaskRecurrenceIT.java` (new; Task 5 extends it)

**Interfaces:**
- `TaskRecurrenceSchedule.normalize(Recurrence rule, Instant firstDue, Map<String,String> errors): Recurrence` — spec C11 exactly; `firstDue == null` → `recurrence` "needs a due date" and return null; collects every error into `errors` under the Global Constraints keys; returns the clean copy (`interval` defaulted, `weekdays` distinct and sorted, `dayOfMonth` from `firstDue` in the zone for MONTHLY, `timeZone` normalised to the `ZoneId` id, `endsOn` as `LocalDate.toString()`, the frequency-irrelevant fields null). Constants `MAX_INTERVAL = 99`, `MIN_ENDS_AFTER = 2`, `MAX_ENDS_AFTER = 999`, `MAX_STEPS = 100_000`.
- `TaskRecurrenceSchedule.nextDue(Recurrence rule, Instant currentDue, int currentOccurrence, Instant now): Optional<Instant>` — spec C12; the reference's `step`/`nextWeekly` algorithm.
- `TaskService.fields` → `f.recurrence = req.recurrence() == null ? null : TaskRecurrenceSchedule.normalize(req.recurrence(), f.dueAt, errors)`; `Fields.applyTo` sets it; `update`'s `Update` sets `recurrence` (or unsets) and, when `current.getRecurrence() == null && f.recurrence != null`, sets `seriesId = id`, `occurrence = current.getOccurrence() == null ? 1 : it`.

- [ ] **Step 1: Failing tests.** `TaskRecurrenceScheduleTest` (times as in the reference test: `NOV_1 = 2026-11-01T00:00:00Z`, rules in UTC unless said): `dailyEveryTwoDays`, `weeklyMonThuWithinWeekThenJumps`, `weeklyFromUnlistedDayGoesToNextListed`, `monthlyClampsToMonthEnd` (31 Jan → 28 Feb → 31 Mar 2027), `monthlyLeapYear` (31 Jan 2028 → 29 Feb), `keepsWallClockAcrossDst` (`Europe/London`, daily, due 2027-03-27T10:00Z → 2027-03-28T09:00Z), `skipsMissedOccurrences` (daily from 2 Oct, now 1 Nov 10:30 → 2 Nov 10:00), `endsAfterStopsSeries` (`endsAfter 3`: occurrence 2 → present, 3 → empty), `endsOnStopsSeries`, `normalizeCollectsErrors` (interval 0 and 100, missing frequency, weekly without days, both ends, `Mars/Olympus`, endsAfter 1 and 1000, `01/12/2026`, endsOn before due, dayOfMonth 32 — each under its key), `normalizeNeedsDue`, `normalizeTakesDayOfMonthFromFirstDue` (due 31 Jan in `Asia/Kolkata` → 31), `normalizeCleansWeekdaysAndDefaults` (`[THURSDAY, MONDAY, MONDAY]` → `[MONDAY, THURSDAY]`, blank zone → `UTC`, daily rule has null weekdays/dayOfMonth). `TaskRecurrenceIT`: `createStartsASeries` (response `recurrence` normalised, `seriesId == id`, `occurrence 1`), `recurrenceNeedsDueDate` (`$.fields.recurrence`), `badRuleReportsEveryField` (`$.fields['recurrence.interval']`, `['recurrence.weekdays']`), `removingRecurrenceKeepsSeriesId` (`PUT` without `recurrence` → `recurrence` null, `seriesId` kept), `addingRecurrenceLaterStartsSeriesAtThisTask`.
- [ ] **Step 2: Run** module test and `-Dtest=TaskRecurrenceIT` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `TasksApiIT`, `TaskChecklistIT`.
- [ ] **Step 5: Commit** `Validate repeat rules and compute the next due date`.

### Task 5: Next occurrence on complete, and skip

**Files:**
- Modify: `TaskService.java` (`complete` schedules the next occurrence; + `skip`; `forTarget` keeps CANCELLED after OPEN — already sorted by `status != OPEN`), `CrmTaskController.java` (+ `POST /{id}/skip` `@PreAuthorize(VIEW)`)
- Create: `crm/task/dto/SkipResult.java` (record `(TaskView skipped, TaskView next)`)
- Test: `TaskRecurrenceIT.java` (extend)

**Interfaces:**
- `TaskService.nextOccurrence(CrmTask finished, Instant now): CrmTask | null` (private) — spec C13: null when no `recurrence`, `dueAt` null, `nextOccurrenceId` set, or `nextDue` empty; else the new `CrmTask` with a pre-generated id, copied fields, `TaskAccess.TargetInfo` re-read for `pipelineId`/`phoneNumberId` (via `access.assertVisible(ctx, target)` — the caller can see it, they just worked the task), `Checklists.copyUndone`, reminder offset rule, `assignmentSeenAt = now`, `createdBy/lastModifiedBy = ctx.userId()`.
- `complete`: `assertCanWork`; DONE → return as is; compute `next`; `guarded(ctx, id, current.getVersion(), set status DONE, completedAt, completedBy [+ set nextOccurrenceId when next != null])`; then `mongo.insert(next)`, `counters.recompute`, `record(TASK_CREATED, next, "Next task scheduled: " + title, payload recurringFromTaskId)`; return the finished task's view.
- `skip(ctx, id): SkipResult` — `assertCanWork`; `status != OPEN` → `notOpen()`; `recurrence == null` → `notRecurring()`; `next`; `guarded(set status CANCELLED [+ nextOccurrenceId])`; `record(TASK_UPDATED, "Task skipped: …", "SKIPPED")`; insert next as above; `new SkipResult(view(skipped), next == null ? null : view(next))`.
- `mine`/`counts`/reminders already filter `status OPEN`; `forTarget` lists CANCELLED (the console shows them struck through).

- [ ] **Step 1: Failing tests.** `TaskRecurrenceIT`: `completeSchedulesNextOccurrence` (weekly task with 2 steps (1 ticked), reminder 1 h before due, assigned by manager to agent; agent completes → response `nextOccurrenceId`; the next task: `occurrence 2`, `seriesId`, `recurringFromTaskId`, same assignee and `assignedBy`, `dueAt` = schedule's next, `reminderAt` = due − 1 h, 2 undone steps with new ids, raw `assignmentSeenAt` set; activity "Next task scheduled: …" with `payload.recurringFromTaskId`; deal `nextTaskDueAt` = the next due), `pastReminderOffsetIsDropped` (daily task due 2 days ago with reminder 3 days before due; complete → next has `reminderAt` null), `reopenAndCompleteCreatesNothing` (task count for the target stays 2), `endsAfterStopsTheSeries` (`endsAfter 2`: completing occurrence 2 → `nextOccurrenceId` null, no new task), `skipCancelsAndSchedules` (`$.skipped.status CANCELLED`, `$.next.occurrence`; activity `SKIPPED`), `skipOneOffIs400` (`NOT_RECURRING`), `skipDoneIs400` (`TASK_NOT_OPEN`), `cancelledIsListedOnTheRecordButNotInBuckets` (`GET /tasks?targetType…` includes it after the open ones; `/mine` and counts exclude it; `GET /reminders` never lists it), `racingCompletionsScheduleOneOccurrence` (two `complete` calls: the first 200, then raw-reset the task's status to OPEN keeping the version bumped? — no: simulate the race by calling `complete` twice where the second uses a stale read: call `service.complete` directly? Keep it at the API: complete once → 200; a second `POST /complete` → 200 idempotent with the **same** `nextOccurrenceId`, and exactly one task has `recurringFromTaskId == id`), `agentWithoutEditCanSkipOwnTask` (the `CRM_VIEWER` pattern).
- [ ] **Step 2: Run** `-Dtest=TaskRecurrenceIT` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; then `'-Dtest=**/crm/**/*'`.
- [ ] **Step 5: Commit** `Schedule the next occurrence when a repeating task is completed or skipped`.

### Task 6: Demo data, docs, full build

**Files:**
- Modify: `crm/demo/CrmDemoSeeder.java` (spec §7), `README.md` (CRM section: tasks and reminders paragraph, API table rows for `/tasks/delegated`, `/skip`, `/checklist/{itemId}`, `/reminders` kinds, demo data paragraph), `omnichannel-app/src/test/.../app/crm/CrmDemoSeederIT.java` (+ `seedsDelegatedTasksWithStepsAndRepeat`)

**Interfaces:**
- Seeder: a new idempotent step `seedTaskExtras(ctx, managerId)` runs after the fresh seed **and** on every start for an already-seeded demo brand (like `seedSegments`): it loads the demo brand's tasks titled "Send the festive price list", "Call about uniform sizes", "Confirm the delivery date" whose `assignedBy` is null and, by collection name `crmTasks`, sets `assignedBy = managerId`, `assignedAt = now`; on the festive task sets `checklist` to three `ChecklistItem`s (`Attach the catalogue` done by `managerId` now, `Mention the festive discount`, `Ask about quantities`); on the uniform task sets `recurrence` = `TaskRecurrenceSchedule.normalize(new Recurrence(WEEKLY, 1, [due weekday in the brand zone], null, brandZone, null, null), dueAt, errors)`, `seriesId = id`, `occurrence = 1`. `managerId` is the demo manager's user id (reuse the seeder's existing demo-user lookup); `brandZone` from `BrandDirectory.zoneOf(ws)`. A task with `assignedBy` set is skipped, so a second run changes nothing.
- `CrmDemoSeederIT.seedsDelegatedTasksWithStepsAndRepeat`: all three tasks have `assignedBy` = the demo manager's id; the festive task has 3 steps with the first done; the uniform task has a WEEKLY rule with one weekday, `seriesId == id`, `occurrence 1`; running the seeder again leaves `assignedAt` and the step ids unchanged. `upgradesAnEarlierSeed`: wipe the new fields from the three tasks, run again → applied.

- [ ] **Step 1: Failing test** as above; run `-Dtest=CrmDemoSeederIT` → FAIL.
- [ ] **Step 2: Implement** seeder and README.
- [ ] **Step 3: Run** `-Dtest=CrmDemoSeederIT` → PASS; then `./mvnw -q verify` (full) → BUILD SUCCESS; note any pre-existing flake (`CampaignAdvancedTimeoutIT`) by name.
- [ ] **Step 4: Commit** `Seed delegated, stepped and repeating demo tasks; document phase E`.

## Contract for the console (additions to phase A–D's contract)

All under `/api/workspaces/{ws}/crm`, JSON, `crm:view`; `crm:edit` where marked.

| Call | Rights | Request | Response |
|---|---|---|---|
| `POST /tasks` | edit | `TaskInput & {checklist?: Array<{id?: string, text: string}>, recurrence?: Recurrence}` | `Task` |
| `PUT /tasks/{id}` | edit | same + `version`; **full replace**: no `checklist` → no steps, no `recurrence` → one-off | `Task`; 409 `VERSION_CONFLICT` |
| `POST /tasks/{id}/complete`, `/reopen` | view; 403 unless `crm:edit` or the assignee | — | `Task` (`nextOccurrenceId` set when one was scheduled) |
| `POST /tasks/{id}/skip` | view; same 403 rule | — | `{skipped: Task, next: Task \| null}`; 400 `{code: "TASK_NOT_OPEN"}`, `{code: "NOT_RECURRING"}` |
| `POST /tasks/{id}/checklist/{itemId}` | view; same 403 rule | `{done: boolean}` | `Task` (version bumped); 400 `TASK_NOT_OPEN`; 404 |
| `GET /tasks/delegated?status=OPEN\|DONE&limit=` | view | — | `Task[]` (OPEN by due, DONE by completion desc) |
| `GET /tasks/mine/counts?tz=&assigneeId=` | view | — | `{overdue, today, upcoming, delegated}` — `delegated` is always the caller's |
| `GET /reminders` | view | — | `Array<{taskId, title, dueAt, target, kind: "DUE" \| "ASSIGNED", assignedBy: Ref \| null}>` (a task may appear once per kind) |
| `POST /reminders/{taskId}/seen` | view | — | 204; marks the assignment notice and, if claimed, the due reminder |

`Task` (+): `assignedBy: Ref | null`, `assignedAt: string | null`, `checklist: Array<{id, text, done: boolean, doneAt: string | null, doneBy: Ref | null}>` (`[]` when none), `recurrence: Recurrence | null`, `seriesId`, `occurrence: number | null`, `recurringFromTaskId`, `nextOccurrenceId` (all `string | null`); `status` adds `"CANCELLED"` (listed on records, never in buckets or reminders).

`Recurrence = {frequency: "DAILY" | "WEEKLY" | "MONTHLY", interval: number, weekdays: Array<"MONDAY" | … | "SUNDAY"> | null, dayOfMonth: number | null, timeZone: string, endsAfter: number | null, endsOn: string | null}` — the server returns the normalised copy; send `interval` (1–99), `weekdays` for WEEKLY, `timeZone` (the browser's on create, the stored one on edit), `dayOfMonth` only to keep a stored one, and at most one of `endsAfter` (2–999) / `endsOn` (`yyyy-MM-dd`).

Validation: 400 `{code: "VALIDATION", fields: {…}}` with keys `assigneeId`, `status`, `checklist`, `checklist[i].text`, `recurrence`, `recurrence.frequency|interval|weekdays|dayOfMonth|timeZone|endsAfter|endsOn` and the messages listed in this plan's Global Constraints.
