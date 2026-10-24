# CRM phase B (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add work management and customisation to the CRM module: custom fields on contacts, companies and deals; tasks with in-app reminders; notes with GridFS attachments; CSV export and import; and the daily rotting job.

**Architecture:** New feature packages in `omnichannel-crm` (`crm.field`, `crm.task`, `crm.note`, `crm.csv`, `crm.job`) on top of phase A's `CrmContext`, `ActivityLog`, counters and access rules. Attachments live in the GridFS bucket `crmNoteFiles`. Jobs are `@Scheduled` methods behind `omnichannel.crm.jobs.enabled`. No new port is needed except `ContactDirectory.ensure` (phase A) for imports.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB + GridFS, JUnit 5 + AssertJ + MockMvc, embedded Mongo in `*IT`.

**Spec:** `docs/superpowers/specs/2026-10-23-crm-port-design.md` (§5.3 tasks/notes/fields, §6.4–§6.8). **Depends on:** `2026-10-23-crm-a-backend.md` merged. Reference behaviour: `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/{field,task,note,export,importer,job}` and its phase 2 design `/Users/nagesh/RedLava/SCM/CRM/whatsapp/docs/superpowers/specs/2026-10-23-crm-phase2-design.md` (calls C7–C17) — port those rules where this plan is silent.

**Branch:** `crm-b` worktree at `.worktrees/crm-b`. Commands as in the phase A plan.

## Global Constraints

- Everything in phase A's Global Constraints applies (module boundary, document fields, version-guarded writes, errors, paging).
- New collections: `crmFieldDefinitions`, `crmTasks`, `crmNotes`; GridFS bucket `crmNoteFiles` (constant `CrmNoteFilesConfig.BUCKET`). Indexes exactly as §5.3.
- Field keys `^[a-z][a-zA-Z0-9_]{1,39}$`, immutable; labels ≤ 60; `maxCustomFields` counts **active** definitions **per entity type**.
- Custom field errors: 400 `VALIDATION` with `fields: {"customFields.<key>": message}`; definition immutables 400 `FIELD_IMMUTABLE`; limit 409 `LIMIT_REACHED {limit}`.
- Tasks: title ≤ 200, description ≤ 5000, priority `LOW|NORMAL|HIGH` (default NORMAL), `reminderAt ≤ dueAt` when both set.
- Notes: body ≤ 20000; ≤ 5 attachments per note; ≤ 10 MB each; content types: `image/*`, `application/pdf`, `text/plain`, `text/csv`, `application/msword`, `application/vnd.openxmlformats-officedocument.*`, `application/vnd.ms-excel`, `application/vnd.ms-powerpoint`; otherwise 400 `ATTACHMENT_REJECTED`.
- CSV: export UTF-8 with BOM, CRLF, RFC 4180 quoting, cells starting with `= + - @` prefixed with `'`, ≤ 50,000 rows, pages of 1,000, filename `crm-<entity>-<yyyyMMdd-HHmm>.csv`; import companies ≤ 2,000 rows, contacts ≤ 1,000 rows, else 400 `IMPORT_TOO_LARGE {limit}`; missing required header 400 `IMPORT_BAD_HEADER {missing}`.
- Jobs: reminders every 60 s (`fixedDelay`), ≤ 200 per run; rotting `0 15 2 * * *`; both skipped when `omnichannel.crm.jobs.enabled=false` (tests set it false and call the job methods directly).
- Commits: one per task, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A field definition deactivated while a record holds a value**: saving the record unchanged must succeed and keep the value; changing it is refused (Task 1 test `inactiveFieldValueKeptOnUnrelatedSave`).
2. **Reminder job running while a task is completed or its due time edited**: no reminder for a DONE task, and editing `reminderAt` after a reminder was sent re-arms it once (Task 3 test `editingReminderReArms`, `doneTaskNeverReminded`).
3. **"Today" bucket across time zones**: a task due 23:30 in `Asia/Kolkata` is "today" for `tz=Asia/Kolkata` and "upcoming"/"overdue" correctly for UTC (Task 2 test `todayBucketHonoursTimeZone`).
4. **Import file with BOM, CRLF, quoted commas and a blank trailing line**: every real row imported, the blank line ignored (Task 6 test `importToleratesBomCrlfAndQuotes`).
5. **Deleting a note or record that holds attachments**: GridFS files go too and a later download is 404 (Task 4 tests `deletingNoteRemovesFiles`, `deletingDealRemovesItsNotesAndFiles`).

---

### Task 1: Custom field definitions and value validation (§5.3 `crmFieldDefinitions`, §6.5)

**Files:**
- Create in `crm/field/`: `CrmFieldDefinition.java`, `FieldType.java`, `FieldOption.java`, `CrmFieldDefinitionRepository.java`, `FieldDefinitionService.java`, `FieldValidator.java`, `CrmFieldController.java`, `dto/FieldDefinitionView.java`, `dto/FieldDefinitionRequest.java`, `dto/ReorderRequest.java`
- Modify: `CrmContactService.update`, `CrmCompanyService.create/update`, `DealService.create/update` (accept `customFields` and validate), their request DTOs (+`Map<String,Object> customFields`), `CaptureService` (capture/import skip `required`)
- Test: `omnichannel-crm/src/test/.../crm/field/FieldValidatorTest.java`; `omnichannel-app/src/test/.../app/crm/CustomFieldsApiIT.java`

**Interfaces:**
- `enum FieldType { TEXT, LONG_TEXT, NUMBER, CURRENCY, BOOLEAN, DATE, DATETIME, SELECT, MULTI_SELECT, URL, EMAIL, PHONE, USER }`; `record FieldOption(String key, String label, String color, int position, boolean active)`; `CrmFieldDefinition` per §5.3 with `TargetType entityType` limited to CONTACT/COMPANY/DEAL.
- `FieldValidator` (pure, takes a `Predicate<String> isBrandUser`): `Map<String,Object> validate(List<CrmFieldDefinition> defs, Map<String,Object> stored, Map<String,Object> incoming, boolean creating, boolean enforceRequired)` → normalised map to store, or throws `ValidationException` with `customFields.<key>` messages. Rules (§6.5): per-type normalisation (TEXT ≤ 1000, LONG_TEXT ≤ 10000, NUMBER finite, CURRENCY finite ≥ 0 → `BigDecimal`, BOOLEAN, DATE `yyyy-MM-dd` string, DATETIME ISO-8601 → `Date`, SELECT active option key, MULTI_SELECT distinct active keys, URL absolute http/https, EMAIL `^[^@\s]+@[^@\s]+\.[^@\s]+$`, PHONE `+?[0-9]{6,15}` after removing spaces and dashes, USER brand user id); blank/null clears; unknown or inactive key refused unless equal to stored; required checked on create and when the key is present on update (`enforceRequired=false` for capture/import); `defaultValue` applied on create when the key is absent.
- `FieldDefinitionService`: `list(CrmContext, TargetType entityType, boolean includeInactive)` ordered by `position`; `create(CrmContext, FieldDefinitionRequest)` (`crm:settings`; unique key per entity type → 409 `DUPLICATE`-style `CrmErrors.conflict("FIELD_KEY_TAKEN", …)`; limit 409 `LIMIT_REACHED`); `update(CrmContext, String id, FieldDefinitionRequest)` (key/entityType/type changes → 400 `FIELD_IMMUTABLE`; option keys may not disappear, deactivate instead); `delete(CrmContext, String id)` (soft: `active=false`); `reorder(CrmContext, ReorderRequest{entityType, ids})`. Each change records `SETTINGS_CHANGED`.
- API: `GET /crm/fields?entityType=&includeInactive=`, `POST /crm/fields`, `PUT /crm/fields/{id}`, `DELETE /crm/fields/{id}` (204), `PUT /crm/fields/reorder`. `FieldDefinitionView{id, entityType, key, label, type, options, required, defaultValue, position, showInList, showInCard, active, version}`. Contact/company/deal views already carry `customFields` (phase A); exports add them in Task 5.

- [ ] **Step 1: Failing tests.** `FieldValidatorTest`: one test per type for a valid and an invalid value (`textTooLong`, `currencyNegative`, `dateWrongFormat`, `datetimeParsesIso`, `selectInactiveOptionRejected`, `multiSelectDuplicatesRejected`, `urlMustBeHttp`, `phoneStripsSpacesAndDashes`, `userMustBeBrandUser`), plus `blankClears`, `unknownKeyRejected`, `inactiveFieldValueKeptOnUnrelatedSave`, `requiredOnCreate`, `requiredSkippedWhenNotEnforced`, `defaultAppliedOnCreate`. `CustomFieldsApiIT`: `managerCreatesDealFieldAndDealSavesValue`, `limitPerEntityType` (max 2: two CONTACT fields ok, third 409 `LIMIT_REACHED`, a DEAL field still ok), `keyImmutable` (400 `FIELD_IMMUTABLE`), `duplicateKeyRejected`, `softDeleteHidesFromDefaultList`, `reorderPersists`, `agentCannotManageFields` (403), `invalidValueGivesFieldError` (`fields["customFields.tier"]`), `captureIgnoresRequiredField`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS, phase A ITs still PASS. **Step 5: Commit** `Add CRM custom fields`.

### Task 2: Tasks (§5.3 `crmTasks`, §6.4)

**Files:**
- Create in `crm/task/`: `CrmTask.java`, `TaskStatus.java`, `TaskPriority.java`, `CrmTaskRepository.java`, `TaskService.java`, `TaskAccess.java`, `TaskCounters.java`, `CrmTaskController.java`, `dto/TaskView.java`, `dto/TaskRequest.java`, `dto/TaskCounts.java`
- Modify: `DealService.delete` and `CrmCompanyService.delete` (remove the target's tasks), `ChannelService.move` (re-point `phoneNumberId` of the contact's and its deals' tasks), `ContactCounters`/deal recompute (`nextTaskDueAt`), `InboxSummaryService` (`nextTask`), `CrmTestBase` (`task(...)` helper)
- Test: `omnichannel-crm/src/test/.../crm/task/BucketsTest.java`; `omnichannel-app/src/test/.../app/crm/TasksApiIT.java`

**Interfaces:**
- `CrmTask` per §5.3 (`assigneeId`, `target`, `pipelineId`, `phoneNumberId`, `reminderSentAt`, `reminderSeenAt`).
- `TaskAccess`: `void assertVisible(CrmContext, CrmTarget)` (deal: pipeline access; contact: visible contact; company: always) — 404 when not; `Criteria visibleCriteria(CrmContext)` (restricted: `pipelineId ∈ access` for deal tasks, contact tasks whose contact's `pipelineIds` intersect access, all company tasks).
- `TaskService`: `TaskView create(CrmContext, TaskRequest)` (`crm:edit`; `type` must be an active `taskTypes` key when given; assignee defaults to caller, must be a brand user; copies `pipelineId`/`phoneNumberId` from the target; `TASK_CREATED` on the target); `update(CrmContext, String id, TaskRequest)` (`version`; changing `reminderAt` clears `reminderSentAt` and `reminderSeenAt`; `TASK_UPDATED {action: UPDATED}`); `complete` (`DONE`, `completedAt`, `completedBy`; `TASK_COMPLETED`), `reopen` (`OPEN`; `TASK_UPDATED {action: REOPENED}`), `delete` (`TASK_UPDATED {action: DELETED}`); `CrmPage<TaskView> forTarget(CrmContext, CrmTarget, String status?)` (OPEN first, then `dueAt` asc, nulls last); `List<TaskView> mine(CrmContext, Bucket bucket, ZoneId tz, String assigneeId?, int limit /*default 50, max 200*/)`; `TaskCounts counts(CrmContext, ZoneId tz, String assigneeId?)`. Another `assigneeId` than the caller needs `crm:view-all` else 403. After every write `TaskCounters.recompute(target)`: earliest OPEN `dueAt` → `nextTaskDueAt` on the deal or contact (company: none).
- Buckets (pure, `Buckets.of(Instant now, ZoneId tz)` → `{todayStart, tomorrowStart}`): overdue = OPEN, `dueAt < now`; today = OPEN, `now ≤ dueAt < tomorrowStart`; upcoming = OPEN, `dueAt ≥ tomorrowStart` or no `dueAt`. Unknown `tz` → 400 VALIDATION `tz`.
- `TaskView{id, title, description, type, priority, dueAt, reminderAt, status, completedAt, completedBy: Ref|null, assignee: Ref, target{type, id, name}, overdue, version}` (`target.name` = deal title / contact name / company name).
- API: `GET /crm/tasks?targetType=&targetId=&status=&page=&size=`, `POST /crm/tasks`, `PUT /crm/tasks/{id}`, `POST /crm/tasks/{id}/complete`, `POST /crm/tasks/{id}/reopen`, `DELETE /crm/tasks/{id}` (204), `GET /crm/tasks/mine?bucket=overdue|today|upcoming&tz=&assigneeId=&limit=`, `GET /crm/tasks/mine/counts?tz=&assigneeId=` → `{overdue, today, upcoming}`.

- [ ] **Step 1: Failing tests.** `BucketsTest`: `todayBucketHonoursTimeZone`, `noDueDateIsUpcoming`. `TasksApiIT`: `createOnDealSetsNextTaskDueAtAndTimeline`, `completeClearsNextTaskDueAt`, `reopenRecordsAction`, `restrictedAgentCannotSeeTaskOnInaccessibleDeal` (404 and absent from `mine`), `companyTasksVisibleToAll`, `mineOtherAssigneeNeedsViewAll` (agent 403, manager 200), `countsMatchBuckets`, `unknownTaskTypeRejected`, `reminderAfterDueRejected`, `deletingDealRemovesItsTasks`, `inboxSummaryShowsNextTask`, `movingChannelRePointsTasks`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM tasks`.

### Task 3: Reminder job and in-app reminders (§6.4)

**Files:**
- Create in `crm/job/`: `CrmJobsProperties.java` (`omnichannel.crm.jobs.enabled`, default true), `TaskReminderJob.java`; in `crm/task/`: `ReminderService.java`, `CrmReminderController.java`, `dto/ReminderView.java`
- Modify: `omnichannel-app/src/test/resources/application.yml` (`omnichannel.crm.jobs.enabled: false`)
- Test: `omnichannel-app/src/test/.../app/crm/RemindersIT.java`

**Interfaces:**
- `TaskReminderJob.run()` (`@Scheduled(fixedDelay = 60_000)`, returns `int claimed`): up to 200 times `findAndModify(query(status OPEN, reminderAt ≤ now, reminderSentAt null, assigneeId exists), set reminderSentAt=now)`; no other side effect (delivery is the poll).
- `ReminderService`: `List<ReminderView> due(CrmContext)` — caller's tasks with `reminderSentAt` set, `reminderSeenAt` null, status OPEN, visible (TaskAccess), newest first, ≤ 20; `void seen(CrmContext, String taskId)` (own task only, else 404).
- `ReminderView{taskId, title, dueAt, target{type, id, name}}`.
- API: `GET /crm/reminders`, `POST /crm/reminders/{taskId}/seen` (204).

- [ ] **Step 1: Failing tests:** `dueReminderClaimedOnce` (run twice → claimed 1 then 0), `doneTaskNeverReminded`, `noAssigneeNoReminder`, `editingReminderReArms` (claimed, PUT new `reminderAt` in the past, run → claimed again), `remindersListsOnlyOwnUnseen`, `seenRemovesFromList`, `othersTaskSeenIs404`, `jobDisabledByProperty` (bean method guarded: with property false, the scheduled wrapper returns without claiming — test the wrapper directly).
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM task reminders`.

### Task 4: Notes and attachments in GridFS (§5.3 `crmNotes`, §6.6)

**Files:**
- Create in `crm/note/`: `CrmNote.java`, `NoteAttachment.java`, `CrmNoteRepository.java`, `CrmNoteFilesConfig.java` (`BUCKET = "crmNoteFiles"`, `@Bean GridFsTemplate crmNoteFilesGridFs`), `NoteService.java`, `AttachmentService.java`, `CrmNoteController.java`, `dto/NoteView.java`, `dto/NoteRequest.java`
- Modify: `DealService.delete`, `CrmCompanyService.delete` (remove the target's notes and files), `ChannelService.move` (re-point notes' `phoneNumberId`); `omnichannel-app/src/test/.../app/config/CollectionRenamesIT.java` (add `CrmNoteFilesConfig.BUCKET` to the bucket lists)
- Test: `omnichannel-app/src/test/.../app/crm/NotesApiIT.java`

**Interfaces:**
- `CrmNote` per §5.3; `record NoteAttachment(String id, String fileName, String contentType, long size, String fileId, String uploadedBy, Instant uploadedAt)`.
- `NoteService`: `create(CrmContext, NoteRequest{target, body, pinned?})` (`crm:edit`; target visible; `NOTE_ADDED {noteId, attachmentCount: 0}`), `update(CrmContext, String id, NoteRequest + version)` (author or `crm:delete`), `list(CrmContext, CrmTarget, int page, int size)` (pinned first, then newest), `pin(CrmContext, String id, boolean pinned)`, `delete(CrmContext, String id)` (author or `crm:delete`; deletes GridFS files).
- `AttachmentService`: `NoteView add(CrmContext, String noteId, MultipartFile file)` (limits and types of Global Constraints; stores with metadata `{workspaceId, noteId}`; file name sanitised: path parts stripped, ≤ 200 chars), `NoteView remove(CrmContext, String noteId, String attachmentId)`, `GridFsResource open(CrmContext, String noteId, String attachmentId)` (target access applies; 404 when missing). Multipart max 10 MB: set `spring.servlet.multipart.max-file-size` only if the current app value is lower — check `application.yml` first and leave larger values alone.
- `NoteView{id, target, body, pinned, attachments[{id, fileName, contentType, size}], author: Ref, creationTime, lastUpdateTime, version}`.
- API: `GET /crm/notes?targetType=&targetId=&page=&size=`, `POST /crm/notes`, `PUT /crm/notes/{id}`, `DELETE /crm/notes/{id}`, `POST /crm/notes/{id}/pin {pinned}`, `POST /crm/notes/{id}/attachments` (multipart `file`), `DELETE /crm/notes/{id}/attachments/{attachmentId}`, `GET /crm/notes/{id}/attachments/{attachmentId}` (`Content-Disposition: attachment; filename*=UTF-8''…`, `X-Content-Type-Options: nosniff`).

- [ ] **Step 1: Failing tests:** `createListPinnedFirst`, `noteOnInaccessibleDealIs404`, `uploadDownloadRoundTrip` (bytes equal, headers set), `sixthAttachmentRejected`, `executableRejected` (`application/x-msdownload` → 400 `ATTACHMENT_REJECTED`), `oversizeRejected`, `deletingNoteRemovesFiles`, `deletingDealRemovesItsNotesAndFiles`, `onlyAuthorOrDeleterEdits`. `CollectionRenamesIT.everyMappedCollectionAndBucketIsCamelCase` passes with the new bucket.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM notes with attachments`.

### Task 5: CSV export (§6.7)

**Files:**
- Create in `crm/csv/`: `Csv.java` (writer + parser), `CsvExportService.java`, `CrmExportController.java`
- Test: `omnichannel-crm/src/test/.../crm/csv/CsvTest.java`; `omnichannel-app/src/test/.../app/crm/CrmExportIT.java`

**Interfaces:**
- `Csv`: `static String cell(String v)` (quote when needed, double quotes, formula guard), `static String row(List<String> cells)` (joined with `,`, ends `\r\n`), `static final String BOM = "﻿"`, `static List<List<String>> parse(String text)` (strips BOM, CRLF/LF, quoted fields with commas/newlines/escaped quotes, drops fully blank lines).
- `CsvExportService.export(CrmContext, TargetType entity, Object searchRequest, OutputStream out)` — uses the same search services with the request's filters and sort, pages of 1,000, stops at 50,000 rows; restricted users get only accessible records. Columns:
  - contacts: `First name, Last name, WhatsApp number, Channel, Email, Job title, Company, Owner, Lifecycle, Source, Open deals, Open deal amount, Created, Last activity` then `<label> (<key>)` per active CONTACT field;
  - companies: `Name, Domain, Website, Industry, Size, Annual revenue, Phone, Email, City, Country, Owner, Contacts, Open deal amount, Created` + COMPANY fields;
  - deals: `Title, Pipeline, Stage, Status, Amount, Currency, Probability, Expected close, Contact, WhatsApp number, Company, Owner, Source, Lost reason, Created, Closed, Last activity` + DEAL fields.
- API (`crm:export`): `POST /crm/contacts/export`, `POST /crm/companies/export`, `POST /crm/deals/export` with the search body → `StreamingResponseBody`, `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="crm-<entity>-<yyyyMMdd-HHmm>.csv"`.

- [ ] **Step 1: Failing tests.** `CsvTest`: `quotesCommasAndQuotes`, `formulaCellsGuarded`, `parseHandlesBomCrlfQuotedNewlines`, `blankLinesDropped`. `CrmExportIT`: `contactsExportHasBomHeaderAndCustomFieldColumns`, `exportFollowsFilters`, `restrictedExportOnlyAccessibleDeals`, `agentWithoutExportIs403`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Export CRM contacts, companies and deals as CSV`.

### Task 6: CSV import (§6.7)

**Files:**
- Create in `crm/csv/`: `CsvImportService.java`, `CrmImportController.java`, `dto/ImportResult.java`
- Test: `omnichannel-app/src/test/.../app/crm/CrmImportIT.java`

**Interfaces:**
- `ImportResult{total, created, updated, skipped, errors:[{row, message}]}` (`row` is the 1-based data row number).
- `CsvImportService.importCompanies(CrmContext, MultipartFile)` — required header `Name`; optional `Domain, Website, Industry, Size, Annual revenue, Phone, Email, City, Country, Description` and `<label> (<key>)` or `<key>` for COMPANY fields; upsert by case-insensitive name, then by domain; blank cells never overwrite; invalid row → error entry, continue.
- `CsvImportService.importContacts(CrmContext, MultipartFile, String pipelineId, String phoneNumberId)` — `crm:edit`; pipeline accessible and active; number in `ctx.chattable()` else 404; required header `WhatsApp number`; optional `First name, Last name, Email, Job title, Company` (matched by name, created when missing), `Lifecycle` (label or key), `Source`, contact custom fields; each row → `ContactDirectory.ensure` then phase A `CaptureService.findOrCreate` with origin `IMPORT`; an existing CRM contact is filled where blank (`updated`), gets a deal only if none open in the pipeline.
- API: `POST /crm/companies/import` (multipart `file`), `POST /crm/contacts/import?pipelineId=&phoneNumberId=` (multipart `file`) → `ImportResult`.

- [ ] **Step 1: Failing tests:** `importsContactsIntoPipelineAndChannel` (origin IMPORT, deals in first lane), `secondImportUpdatesBlankFieldsOnly`, `badPhoneRowReportedNotFatal`, `missingHeaderIs400` (`missing: "WhatsApp number"`), `tooManyRowsIs400`, `importToleratesBomCrlfAndQuotes`, `companiesUpsertByNameThenDomain`, `agentCannotImportOnNumberTheyDontWork` (404).
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Import CRM companies and contacts from CSV`.

### Task 7: Deal rotting job (§6.8)

**Files:**
- Create: `crm/job/DealRottingJob.java`
- Test: `omnichannel-app/src/test/.../app/crm/DealRottingIT.java`

**Interfaces:**
- `DealRottingJob.run()` (`@Scheduled(cron = "0 15 2 * * *")`, returns `int flagged`): for each brand in `BrandDirectory.allBrandIds()` with CRM enabled, for each pipeline: effective days = `pipeline.rottingDays` else `settings.dealRottingDays`; none → set `rotting=false` on its OPEN deals; else `updateMulti` OPEN deals with `coalesce(lastActivityAt, stageEnteredAt, creationTime) < now − days` → `rotting=true`, the other OPEN deals → `false`; closed deals untouched; no version bump, no activity. Use two `updateMulti` calls per pipeline with `$or` over the three timestamps.

- [ ] **Step 1: Failing tests:** `quietOpenDealFlagged`, `recentActivityUnflags`, `pipelineDaysOverrideSettings`, `closedDealsNeverFlagged`, `noDaysMeansNeverRotting`, `brandWithCrmOffSkipped`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Flag rotting CRM deals daily`.

### Task 8: Demo data, isolation and README for phase B

**Files:**
- Modify: `crm/demo/CrmDemoSeeder.java` (two DEAL custom fields — `tier` SELECT gold/silver, `budget` CURRENCY — one overdue task and one task due today for `demo-agent`, one with a reminder in the past, a pinned note), `README.md` (CRM section: tasks, reminders, notes, CSV, jobs and `omnichannel.crm.jobs.enabled`), `CrmIndexIsolationIT` (exercise tasks, notes with an attachment, export, import)
- Test: `CrmDemoSeederIT` (new counts), `CrmIndexIsolationIT`

- [ ] **Step 1:** Extend the two ITs → FAIL. **Step 2:** Implement. **Step 3: Run** `./mvnw -q verify` → green; `./mvnw -q -Pwith-frontend -DskipTests package` builds. **Step 4: Commit** `Seed phase B CRM demo data and document it`.

---

## Contract for the console (additions to phase A's contract)

| Call | Request | Response |
|---|---|---|
| `GET /fields?entityType=CONTACT\|COMPANY\|DEAL&includeInactive=` | — | `FieldDefinition[] = {id, entityType, key, label, type, options:[{key,label,color,position,active}], required, defaultValue, position, showInList, showInCard, active, version}` |
| `POST /fields`, `PUT /fields/{id}`, `DELETE /fields/{id}`, `PUT /fields/reorder` | definition fields (+`version` on PUT); reorder `{entityType, ids}` | definition / 204; 400 `FIELD_IMMUTABLE`; 409 `LIMIT_REACHED {limit}`, `FIELD_KEY_TAKEN` |
| contact/company/deal create & update | + `customFields: {key: value}` | views carry `customFields`; errors `fields["customFields.<key>"]` |
| `GET /tasks?targetType=&targetId=&status=&page=&size=` | — | page of `Task = {id, title, description, type, priority, dueAt, reminderAt, status, completedAt, completedBy, assignee:Ref, target:{type,id,name}, overdue, version}` |
| `POST /tasks`, `PUT /tasks/{id}` | `{title, description?, type?, priority?, dueAt?, reminderAt?, assigneeId?, target:{type,id}, version (PUT)}` | `Task` |
| `POST /tasks/{id}/complete`, `/reopen`; `DELETE /tasks/{id}` | — | `Task` / 204 |
| `GET /tasks/mine?bucket=&tz=&assigneeId=&limit=` | — | `Task[]` |
| `GET /tasks/mine/counts?tz=&assigneeId=` | — | `{overdue, today, upcoming}` |
| `GET /reminders`; `POST /reminders/{taskId}/seen` | — | `[{taskId, title, dueAt, target:{type,id,name}}]` / 204 |
| `GET /notes?targetType=&targetId=&page=&size=` | — | page of `Note = {id, target, body, pinned, attachments:[{id,fileName,contentType,size}], author:Ref, creationTime, lastUpdateTime, version}` |
| `POST /notes`, `PUT /notes/{id}`, `DELETE /notes/{id}`, `POST /notes/{id}/pin` | `{target, body, pinned?, version (PUT)}`; pin `{pinned}` | `Note` / 204 |
| `POST /notes/{id}/attachments` (multipart `file`); `DELETE /notes/{id}/attachments/{aid}`; `GET` same path | — | `Note` / file stream; 400 `ATTACHMENT_REJECTED` |
| `POST /{contacts\|companies\|deals}/export` | the search body | `text/csv` stream |
| `POST /companies/import`; `POST /contacts/import?pipelineId=&phoneNumberId=` | multipart `file` | `{total, created, updated, skipped, errors:[{row,message}]}`; 400 `IMPORT_TOO_LARGE {limit}`, `IMPORT_BAD_HEADER {missing}` |
