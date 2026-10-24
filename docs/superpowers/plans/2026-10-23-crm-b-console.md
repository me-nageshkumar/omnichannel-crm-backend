# CRM phase B (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the console side of phase B: custom fields (settings, forms, drawers, tables, cards), the picklist editor, tasks (page, tabs, inbox follow-up), in-app reminders, notes with attachments, and CSV export and import.

**Architecture:** Extends `src/app/Crm/` from phase A. A generic field engine in `Crm/common/fields/` renders any definition list; record forms gain a "More fields" section; tables add `showInList` columns. Tasks get a nav entry and page; reminders are polled every 60 s by a `CrmReminders` component mounted inside `CrmStatusProvider`. Notes render markdown with `react-markdown` (no raw HTML).

**Tech Stack:** as phase A, plus `react-markdown` (new).

**Spec:** `docs/superpowers/specs/2026-10-23-crm-port-design.md` (§6.4–§6.7, §10). **Contract:** phase A's contract plus "Contract for the console" in `2026-10-23-crm-b-backend.md`; the contracts win over this plan. **Depends on:** `2026-10-23-crm-a-console.md` merged and the phase B backend merged (or its contract stable). Reference screens: `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm/{fields,Settings,Tasks,Notes,Contacts/CrmContactImportModal.jsx}`.

**Branch:** `crm-b` worktree at `omnichannel-crm-frontend/.worktrees/crm-b`. Commands as phase A.

## Global Constraints

- Phase A's Global Constraints apply (CRM-only folders, PatternFly tokens, sentence case, phone width, `apiFetch` + `useLoad`/`useAction`).
- Existing files touched beyond phase A's list: `src/app/routes.tsx` (Tasks nav link and its count), `package.json` (`react-markdown`, `--save-exact`).
- Field input rules mirror the server (`validateFieldValue` in Task 1) so errors show before saving; the server's `fields["customFields.<key>"]` errors map onto the same inputs.
- Markdown: `react-markdown` with default settings only (no `rehype-raw`, no `skipHtml=false` HTML); links open with `rel="noopener noreferrer"` and `target="_blank"`.
- Upload limits checked client-side before sending: ≤ 5 per note, ≤ 10 MB, the accepted types of the backend plan; the server remains the authority.
- Reminders poll only while signed in, CRM enabled and the tab visible; 60 s interval; no poll for brands without the CRM.
- Commits: one per task, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Definition list changes while a form is open** (an admin deactivates a field): the form keeps showing the stored value read-only and does not send it as a change (Task 3 test `inactiveFieldShownReadOnlyAndNotSent`).
2. **Reminder for a task the user can no longer see**: the toast's link opens the record's "not available" state without crashing, and the reminder is marked seen (Task 5 test `reminderLinkToHiddenRecordMarksSeen`).
3. **User's browser time zone** decides Today/Overdue (`tz` from `Intl.DateTimeFormat().resolvedOptions().timeZone`) (Task 4 test `tasksPageSendsBrowserTimeZone`).
4. **Note markdown with a `<script>` or `javascript:` link**: rendered inert (Task 6 test `markdownDoesNotRenderHtmlOrJsLinks`).
5. **Import file with a BOM and Windows line endings** previewed correctly (first row is data, header names trimmed) (Task 7 test `previewHandlesBomAndCrlf`).

---

### Task 1: Field engine and Settings → Fields (§6.5)

**Files:**
- Create: `src/app/Crm/common/fields/FieldInput.tsx`, `FieldValue.tsx`, `fieldColumns.tsx`, `validateFieldValue.ts`, `types.ts`, `fields.test.tsx`, `validateFieldValue.test.ts`; `src/app/Crm/settings/FieldsSettings.tsx`, `FieldFormModal.tsx`, `OptionsEditor.tsx`, `fieldsSettings.test.tsx`
- Modify: `src/app/api/crm.ts` (`FieldDefinition`, `FieldType`, `FieldOption`; `listFields`, `createField`, `updateField`, `deleteField`, `reorderFields`), `src/app/Crm/settings/CrmSettingsPage.tsx` (tab "Fields")

**Interfaces:**
- `common/fields/types.ts`: `FieldDef` = the generic shape the components need (`key, label, type, options, required, active, showInList, showInCard`), so `common` stays free of `api/crm`.
- `FieldInput({ def: FieldDef, value: unknown, onChange(v), error?, readOnly?, users?: Array<{id; name}> })`: TEXT `TextInput`; LONG_TEXT `TextArea`; NUMBER/CURRENCY `NumberInput`-style `TextInput type=number`; BOOLEAN `Switch`; DATE `DatePicker` (`yyyy-MM-dd`); DATETIME date + `TimePicker` → ISO string; SELECT `FormSelect` (active options, plus the stored inactive one marked "(inactive)"); MULTI_SELECT checkbox `Select`; URL/EMAIL/PHONE `TextInput` with matching `type`; USER `FormSelect` over `users`.
- `FieldValue({ def, value, users? })` — read-only rendering (option labels, dates via `toLocaleDateString`, booleans Yes/No, URL as link with `rel="noopener noreferrer"`).
- `fieldColumns(defs: FieldDef[]): Array<{ key: string; title: string; render(record: { customFields: Record<string, unknown> }): React.ReactNode }>` — `showInList && active` only, by `position`.
- `validateFieldValue(def, value): string | null` — the backend's Task 1 rules, same messages ("Must be a web address starting with http:// or https://", "Must be 6 to 15 digits", …).
- `FieldsSettings`: tabs Contact / Company / Deal; table Label, Key, Type, Required, In list, On card, Active switch, drag handle (dnd-kit, reorder → `reorderFields`); "Add field" disabled with tooltip at the limit, counter "n / N" from `status.limits.maxCustomFields` (active per entity type); `FieldFormModal`: label, key (auto-suggested from label on create, read-only on edit, hint "Letters, digits and _; starts with a lower-case letter"), type (read-only on edit), `OptionsEditor` for SELECT/MULTI_SELECT (add, rename, colour, reorder, deactivate; keys auto from labels, read-only once saved), required, default value (a `FieldInput`), show in list, show on card.

- [ ] **Step 1: Failing tests.** `validateFieldValue.test.ts`: one valid + one invalid case per type. `fields.test.tsx`: `inputRendersPerType` (table-driven), `inactiveOptionShownAsInactive`, `columnsOnlyShowInListActive`. `fieldsSettings.test.tsx`: `createsSelectFieldWithOptions`, `keyReadOnlyOnEdit`, `addDisabledAtLimit`, `reorderSendsIds`, `deactivateSendsDelete`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the CRM field engine and fields settings`.

### Task 2: Settings → Picklists (§5.1)

**Files:**
- Create: `src/app/Crm/settings/PicklistsSettings.tsx`, `PicklistEditor.tsx`, `picklists.test.tsx`
- Modify: `CrmSettingsPage.tsx` (tab "Picklists")

**Interfaces:**
- One `Card` per picklist: Lifecycle stages, Deal sources, Task types, Lost reasons, Industries. `PicklistEditor({ items, onChange, readOnly })`: rows with drag handle, colour, label, key (read-only once saved; auto from label for new rows), Active switch, Default radio; Add item. No delete for saved items (copy: "Items in use can't be removed; switch them off instead."). Save → `putSettings` with the whole settings and `version`; 409 conflict as phase A Review Focus 4.

- [ ] **Step 1: Failing tests:** `addItemDerivesKeyFromLabel`, `savedItemHasNoDelete`, `singleDefaultPerList`, `saveSendsVersion`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the CRM picklist editor`.

### Task 3: Custom fields on records (§6.5, §10)

**Files:**
- Create: `src/app/Crm/fields/useFieldDefinitions.ts`, `src/app/Crm/fields/MoreFieldsSection.tsx`, `src/app/Crm/fields/customFields.test.tsx`
- Modify: `deals/DealFormModal.tsx`, `contacts/CrmContactFormModal.tsx`, `companies/CompanyFormModal.tsx` (a `MoreFieldsSection`), `deals/DealDrawer.tsx`, `contacts/CrmContactDrawer.tsx`, `companies/CompanyDrawer.tsx` (`showInCard` fields in Details), `deals/DealTable.tsx`, `contacts/CrmContactsPage.tsx`, `companies/CompaniesPage.tsx` (`fieldColumns`), `deals/DealCard.tsx` (up to 3 `showInCard` values)

**Interfaces:**
- `useFieldDefinitions(entityType): { defs: FieldDefinition[]; loading }` — cached per brand and entity type for the session; `reload()` after Settings saves.
- `MoreFieldsSection({ defs, values, onChange, errors })` — expandable "More fields" (expanded when any required field or error); sends only changed keys plus required ones on create; inactive defs with a stored value render read-only and are never sent.
- Server field errors `fields["customFields.<key>"]` map to the matching input.

- [ ] **Step 1: Failing tests:** `dealFormSendsOnlyChangedCustomFields`, `requiredFieldBlocksSaveClientSide`, `serverFieldErrorShownOnInput`, `inactiveFieldShownReadOnlyAndNotSent`, `contactsTableShowsListColumns`, `dealCardShowsCardFields`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Show and edit CRM custom fields on records`.

### Task 4: Tasks — page, record tabs, inbox follow-up (§6.4, §10 Tasks page)

**Files:**
- Create: `src/app/Crm/tasks/TasksPage.tsx`, `TaskFormModal.tsx`, `TaskList.tsx`, `NextTask.tsx`, `taskDisplay.ts`, `tasks.test.tsx`, `taskList.test.tsx`
- Modify: `src/app/api/crm.ts` (`Task`, `TaskCounts`; `listTasks`, `createTask`, `updateTask`, `completeTask`, `reopenTask`, `deleteTask`, `myTasks`, `myTaskCounts`), `src/app/Crm/routes.tsx` (`/crm/tasks`), `src/app/routes.tsx` (CRM group link "Tasks" after Pipelines), `deals/DealDrawer.tsx`, `contacts/CrmContactDrawer.tsx`, `companies/CompanyDrawer.tsx` (tab "Tasks" with `TaskList`), `inbox/CrmChatPanel.tsx` (`NextTask` + "Add follow-up")

**Interfaces:**
- `TasksPage`: tabs Overdue / Today / Upcoming with counts (`myTaskCounts`), list (`myTasks`, `tz` = browser zone), assignee `FormSelect` ("Me" + brand users) only with `crm:view-all`, row: checkbox complete, title, target link (deal → `/crm/deals/:id`, contact → `/crm/contacts/:id`, company → `/crm/companies/:id`), due (red when overdue), priority label; Refresh and refetch on focus.
- `TaskFormModal({ target, task?, onSaved })`: title, type (`taskTypes` active), priority, due (date + time), reminder (date + time, must be ≤ due), assignee (users), description.
- `TaskList({ target, canEdit })`: OPEN first; complete/reopen, edit, delete (confirm); "Add task".
- `NextTask({ task })`: title, due relative ("in 2 hours", "3 days overdue" via `Intl.RelativeTimeFormat`).
- `taskDisplay.ts`: `dueLabel(dueAt, now)`, `isOverdue(task, now)`.

- [ ] **Step 1: Failing tests.** `tasks.test.tsx`: `tasksPageSendsBrowserTimeZone`, `bucketTabsShowCounts`, `completeFromListMovesItOut`, `assigneePickerOnlyWithViewAll`, `navShowsTasksWhenCrmOn`. `taskList.test.tsx`: `reminderAfterDueBlocked`, `addTaskFromDealDrawer`, `reopenDoneTask`, `inboxPanelShowsNextTaskAndAddsFollowUp`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM tasks to the console`.

### Task 5: In-app reminders (§6.4 delivery)

**Files:**
- Create: `src/app/Crm/tasks/CrmReminders.tsx`, `reminders.test.tsx`
- Modify: `src/app/api/crm.ts` (`Reminder`; `listReminders`, `markReminderSeen`), `src/app/Crm/status/CrmStatusContext.tsx` (mount `<CrmReminders />` when enabled; expose `useCrmReminderCount(): number`), `src/app/routes.tsx` (`useNavSections` labels the Tasks link "Tasks (n)" when n > 0)

**Interfaces:**
- `CrmReminders`: every 60 s while `document.visibilityState === 'visible'` (and once on mount / tab focus) → `listReminders`; each new `taskId` shows a PF toast (`AlertGroup isToast`, `variant="info"`, title "Follow-up due: <title>", description "due <HH:mm>" when `dueAt`, action link "Open" → target route) — closing or opening marks it seen (`markReminderSeen`); toasts never repeat for a seen id in the session; at most 3 visible, the rest summarised "and N more".
- `useCrmReminderCount()` = unseen reminders count from the last poll.

- [ ] **Step 1: Failing tests** (fake timers): `pollsEverySixtySecondsWhileVisible`, `pausesWhenHidden`, `toastShowsAndMarksSeenOnClose`, `noPollWhenCrmOff`, `navLabelShowsCount`, `reminderLinkToHiddenRecordMarksSeen`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Show CRM task reminders in the console`.

### Task 6: Notes with attachments (§6.6)

**Files:**
- Create: `src/app/Crm/notes/CrmNotes.tsx`, `NoteItem.tsx`, `NoteComposer.tsx`, `attachmentRules.ts`, `notes.test.tsx`
- Modify: `src/app/api/crm.ts` (`Note`, `NoteAttachment`; `listNotes`, `createNote`, `updateNote`, `deleteNote`, `pinNote`, `uploadAttachment` (via `apiFetchFile` + `FormData`), `deleteAttachment`, `downloadAttachment` (via `apiFetchBlob`)), drawers (tab "Notes"), `package.json` (`npm install --save-exact react-markdown`)

**Interfaces:**
- `CrmNotes({ target, canEdit })`: composer (`TextArea`, Ctrl/Cmd+Enter saves, "Markdown supported" helper), list pinned first then newest, load more.
- `NoteItem`: rendered markdown, author and time, pin/unpin, edit (author or `crm:delete`), delete (confirm), attachment chips (name + size) → `downloadAttachment` then `saveFile` (from `api/contacts`); "Attach file" (`FileUpload` simple mode) after the note exists.
- `attachmentRules.ts`: `MAX_FILES = 5`, `MAX_BYTES = 10 * 1024 * 1024`, `ACCEPT` (the backend list), `rejectReason(file, currentCount): string | null`.

- [ ] **Step 1: Failing tests:** `composerSavesOnCtrlEnter`, `pinnedNotesFirst`, `markdownDoesNotRenderHtmlOrJsLinks` (body `<script>x</script> [a](javascript:alert(1))` → no `script` element, link without `javascript:` href), `oversizeFileRejectedBeforeUpload`, `sixthFileRejected`, `downloadSavesFile`, `deleteAsksConfirmation`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add CRM notes with attachments to the console`.

### Task 7: CSV export and import (§6.7)

**Files:**
- Create: `src/app/Crm/csv/ExportButton.tsx`, `ContactImportModal.tsx`, `CompanyImportModal.tsx`, `csvPreview.ts`, `csv.test.tsx`, `csvPreview.test.ts`
- Modify: `src/app/api/crm.ts` (`exportRecords(token, ws, entity, search)` → `{blob, fileName}` with `fileNameOf`; `importContacts(token, ws, file, pipelineId, phoneNumberId)`, `importCompanies(token, ws, file)` → `ImportResult`), `contacts/CrmContactsPage.tsx` (header menu: Export CSV with `crm:export`, Import CSV with `crm:edit`), `companies/CompaniesPage.tsx` (same), `deals/PipelinesPage.tsx` (table view: Export CSV)

**Interfaces:**
- `ExportButton({ entity, search, disabled? })` — exports with the page's current filters; inline error on failure.
- `csvPreview.ts`: `previewCsv(text: string, rows = 5): { headers: string[]; rows: string[][] }` (strips BOM, CRLF, quoted fields; headers trimmed).
- `ContactImportModal`: file (≤ 1,000 rows warned client-side from the preview line count), pipeline (accessible active), channel number (`canChat`), preview table of the first 5 rows with a check that "WhatsApp number" is a header; Import → result table (created / updated / skipped, and errors with row numbers); 400 `IMPORT_BAD_HEADER` → "The file needs these columns: <missing>"; `IMPORT_TOO_LARGE` → "Up to <limit> rows per file."
- `CompanyImportModal`: file, preview, result (no pipeline/number).

- [ ] **Step 1: Failing tests.** `csvPreview.test.ts`: `previewHandlesBomAndCrlf`, `quotedCommaStaysInCell`. `csv.test.tsx`: `exportSendsCurrentFiltersAndSaves`, `exportHiddenWithoutAuthority`, `contactImportNeedsPipelineAndNumber`, `missingHeaderMessage`, `resultTableShowsRowErrors`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `npm test -- --run`, `npm run lint`, `npx tsc --noEmit`, `npm run build` → green.
- [ ] **Step 5: Commit** `Export and import CRM records as CSV`.
- [ ] **Step 6: Acceptance walk.** Rebuild the jar with the console, run with demo data, and check as `demo-agent`: the overdue and today tasks show in Tasks with counts; the past reminder toasts once; a note with a PDF uploads and downloads; contacts export opens in a spreadsheet with custom field columns; a 3-row contact import lands in the chosen pipeline. Light, dark and 375px width.
