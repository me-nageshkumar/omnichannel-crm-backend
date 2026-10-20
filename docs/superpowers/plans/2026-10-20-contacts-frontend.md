# Contacts (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contacts page (search, filters, saved filters, table, bulk toolbar, import, export, add),
contact detail (labels, attributes, notes, delete), and a Contact fields page (labels and attributes).

**Architecture:** `src/app/api/contacts.ts` client; `src/app/Contacts/` pages and modals built from
`useLoad`, `useAction`, `ConfirmModal`, `PageShell`, PatternFly 6 (`Table` with selectable rows,
`Toolbar`, `Drawer` or a detail route, `Label` chips, `FileUpload`).

**Tech Stack:** React 18, TypeScript strict, PatternFly 6.5.1, @patternfly/react-table, React Router 7,
Vitest + Testing Library, `mockFetch` route keys `"METHOD /path"`.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-contacts-design.md`. Consumes the
**API contract** in `omnichannel-crm-backend/docs/superpowers/plans/2026-10-20-contacts-backend.md` exactly.

## Global Constraints

- Repo `omnichannel-crm-frontend`, branch `feature/contacts` in the main checkout (no `.worktrees`).
- Nav (Brand group, after Phone numbers): **Contacts** `/contacts` `requires: a => a.hasInWorkspace('contact:view')`;
  **Contact fields** `/contact-fields` `requires: a => a.hasInWorkspace('contact-field:manage')`.
  Detail route `/contacts/:id` (same `requires` as Contacts, no nav label).
- Actions shown only with the authority (see the contract): annotate → label chips editable + note box;
  edit → edit name/attributes, Add contact; import/export/bulk → their buttons; delete → Delete.
- Copy: hidden number → "Number hidden"; phones shown as `+<digits>`; empty list → "No contacts match.";
  bulk confirm "Add labels to 1,234 contacts?" (count from `POST …/contacts/count`); delete confirm
  "Delete 12 contacts? Their notes are deleted too. This cannot be undone."
- Import result shows created / updated / unchanged / skipped and the first problems as a list; hint
  under the file picker: "CSV with a header row. phone is required (country code first); name, labels
  (separated by ;) and columns named after your attributes are optional."
- Export downloads the CSV (blob → object URL → `<a download>`), file name from `Content-Disposition`.
- Labels render as PatternFly `Label` with the label's colour.
- Test fixtures in `src/test/session.ts`: add the eight contact codes to `BRAND_CODES`; Team Lead and an
  `AGENT_IN_BRAND` fixture with the spec's defaults.
- Gate: `npm run ci-checks` and `npm run build`. Format only touched files with `npx prettier --write <files>`.
  Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Bulk on "all matching"** sends the filter, not the visible page's ids, and the confirm shows the
   server's count. (Task 3 `bulkAllMatchingSendsFilter`.)
2. **Selection cleared on filter/page change** so a bulk never hits rows the user can't see.
   (Task 3 `selectionClearsOnFilterChange`.)
3. **Agent sees no import/export/bulk/delete controls.** (Task 2 `agentSeesViewTagAndNotesOnly`.)
4. **Export error (400 TOO_MANY_ROWS)** shows a message instead of downloading an error page.
   (Task 4 `exportTooManyRowsShowsMessage`.)
5. **Number hidden** contacts render and sort correctly. (Task 2 `hiddenNumberShown`.)

---

### Task 1: API client and Contact fields page

`contacts.ts` (types and calls for the whole contract), `ContactFieldsPage` (Labels table with colour
chip, description, count; Attributes table; add/edit modals with name, colour select (labels),
description; delete confirm "Delete VIP? It is removed from 12 contacts."), route + nav, routes test.
Tests `contactFields.test.tsx`: lists, create label with colour, duplicate name field error, delete
shows count, hidden without `contact-field:manage`.
- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: contact fields page`.

### Task 2: Contacts list and detail

`ContactsPage` (SearchInput debounced 300 ms; table name/"Number hidden", phone, label chips, last
message relative, source; pagination 50; sort select), `ContactDetailPage` (name, phone, source,
dates; labels with add (select of brand labels) and remove; attributes list with edit; notes newest
first with add/edit own/delete; Delete contact), `AddContactModal`.
Tests `contacts.test.tsx`: `listsContacts`, `hiddenNumberShown`, `searchSendsQ`, `paging`,
`openDetailShowsLabelsAttributesNotes`, `agentTagsAndAddsNote`, `agentSeesViewTagAndNotesOnly`,
`teamLeadEditsAttribute`, `addContactValidatesPhone`, `deleteContact`.
- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: contacts list and detail`.

### Task 3: Filters, saved filters, bulk

Filter panel (labels any/all/none multi-selects, attribute rows with op + value, source checkboxes,
number hidden toggle, created and last-message date ranges), saved filter select + "Save filter" (name
modal) + delete; selectable rows with "Select all N matching"; bulk toolbar (Add labels, Remove labels,
Set attribute, Clear attribute, Delete) each with count confirm.
Tests: `filterPanelSendsFilter`, `savedFilterApplies`, `saveFilter`, `bulkOnSelectedIdsSendsIds`,
`bulkAllMatchingSendsFilter`, `selectionClearsOnFilterChange`, `bulkDeleteNeedsContactDelete`.
- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: contact filters and bulk actions`.

### Task 4: Import, export, README

`ImportContactsModal` (FileUpload, submit, result summary), Export button (current filter or saved
filter), README rows for both pages.
Tests: `importShowsSummary`, `importProblemsListed`, `exportDownloads` (stub `URL.createObjectURL`),
`exportTooManyRowsShowsMessage`.
- [ ] Failing tests → implement → `npm run ci-checks && npm run build` → commit `feat: contact import and export`.
