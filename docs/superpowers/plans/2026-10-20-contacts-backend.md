# Contacts (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brand-scoped contacts created from WhatsApp chats, CSV import and by hand, with labels,
attributes, notes, structured filters, saved filters, CSV export, bulk actions, role-based access and
demo data.

**Architecture:** New package `me.nageshk.omnichannel.contact`: documents (`Contact`, `ContactLabel`,
`ContactAttribute`, `ContactNote`, `ContactFilter`), a `ContactQuery` that turns the structured
filter into a Mongo `Criteria` (always ANDed with `workspaceId`), services, brand-scoped controllers
under `/api/workspaces/{workspaceId}/contacts…`, a `ChatContactListener` on `WhatsAppEvent`, and a
`ContactDemoSeeder`.

**Tech Stack:** Spring Boot 3.5.6, Java 21, Spring Security method security (`@access.has`), Spring
Data MongoDB + `MongoTemplate` (embedded flapdoodle in tests), JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-20-contacts-design.md` (read the "Builder questions settled"
section — those are binding).

## Global Constraints

- Package `me.nageshk.omnichannel.contact`. Collections: `contacts`, `contactLabels`,
  `contactAttributes`, `contactNotes`, `contactFilters`.
- Every query, update, delete and export is scoped by `workspaceId`; anything of another brand is 404.
- Controllers: `@PreAuthorize("@access.has(#workspaceId, '<code>')")`, the existing idiom (so
  `AuthorityCheckScanner` finds them). Errors use existing exceptions/codes: `VALIDATION` (400,
  `fields`), `DUPLICATE` (409, `field`), `NOT_FOUND`, `FORBIDDEN`, `CONFLICT`.
- Phone normalisation: strip `+`, spaces, `-`, `(`, `)`; valid when `^[1-9][0-9]{7,14}$`.
- Label colours: `blue, teal, green, orange, purple, red, orangered, yellow, grey`.
- Lengths: label/attribute name 1–40 (trimmed, unique per brand case-insensitively), description
  ≤ 200, attribute value ≤ 500, note 1–2000, filter name 1–60, contact name ≤ 100.
- Limits: list page size 50 (0-based `page`), bulk ids ≤ 1000, export ≤ 100 000 rows, import ≤ 5 MB
  and ≤ 20 000 rows.
- Null fields are never written (Spring default); unique indexes are partial on `$type: "string"`.
- Style: plain getters/setters, no Lombok; records for views; one-line Javadoc per class. Commits end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Whole suite: `./mvnw test`.
- Extend `support/TestUsers.deleteAll()` to clear the five new collections.

## API contract (the frontend plan consumes exactly this)

Base `B = /api/workspaces/{workspaceId}`.

Views:
- `ContactView {id, name, phone, waId, numberHidden, labels: [labelId], attributes: {attributeId: value},
  source, lastMessageAt, creationTime, lastUpdateTime}`.
- `LabelView {id, name, color, description, contactCount}`; `AttributeView {id, name, description, contactCount}`.
- `NoteView {id, contactId, text, createdBy: {username, name}, creationTime, lastUpdateTime, mine}`.
- `FilterView {id, name, filter, createdBy, creationTime}`.
- `ContactFilterSpec {q, labelsAny, labelsAll, labelsNone, attributes: [{attributeId, op, value}], source: [..],
  numberHidden, createdFrom, createdTo, lastMessageFrom, lastMessageTo}` (all optional; dates ISO instants;
  `op` ∈ `EQUALS|CONTAINS|EXISTS|NOT_EXISTS`).
- `Selection {ids?: [contactId], filter?: ContactFilterSpec, filterId?: string}` — exactly one.

Endpoints (authority in brackets):
- `POST B/contacts/search {filter?, filterId?, sort?: LAST_MESSAGE|NAME|CREATED, page?}` → `{items, total, page}` [contact:view]
- `GET B/contacts/{id}` [view]; `POST B/contacts {name, phone, labels?, attributes?}` 201 [contact:edit];
  `PUT B/contacts/{id} {name?, attributes?}` [edit]; `DELETE B/contacts/{id}` 204 [contact:delete]
- `POST B/contacts/{id}/labels/{labelId}` / `DELETE …` → ContactView [contact:annotate]
- `GET B/contacts/{id}/notes` [view]; `POST B/contacts/{id}/notes {text}` 201 [annotate];
  `PUT B/contacts/{id}/notes/{noteId} {text}` (author only) [annotate];
  `DELETE …/notes/{noteId}` (author with annotate, or anyone with contact:delete) 204
- `GET B/contact-labels`, `GET B/contact-attributes` [view];
  `POST/PUT/DELETE B/contact-labels[/{id}]`, `B/contact-attributes[/{id}]` [contact-field:manage]
- `GET B/contact-filters` [view]; `POST/PUT/DELETE B/contact-filters[/{id}]` [contact:bulk]
- `POST B/contacts/count {selection}` → `{count}` [view]
- `POST B/contacts/bulk {selection, action: ADD_LABELS|REMOVE_LABELS|SET_ATTRIBUTE|CLEAR_ATTRIBUTE|DELETE,
  labelIds?, attributeId?, value?}` → `{matched, changed}` [contact:bulk; DELETE also contact:delete]
- `POST B/contacts/export {filter?, filterId?}` → `text/csv` attachment `contacts-<brand>-<yyyyMMdd>.csv` [contact:export];
  400 `TOO_MANY_ROWS` above 100 000.
- `POST B/contacts/import` multipart `file` → `{created, updated, unchanged, skipped, problems: [{row, reason}],
  unknownColumns: [..], unknownLabels: [..]}` [contact:import]

## Review Focus

1. **Cross-brand leaks** through filterId, label ids or attribute ids of another brand in a filter,
   bulk or import (must be ignored/404, never matched). (Task 5 `otherBrandsFilterIdIs404`,
   `foreignLabelIdMatchesNothing`.)
2. **Replayed / duplicated chat events** create one contact and never move `lastMessageAt`
   backwards; a failing upsert never throws out of the listener. (Task 3 `replayIsIdempotent`,
   `listenerSwallowsErrors`.)
3. **Two hidden-number contacts in one brand** both save (partial index). (Task 2 `twoHiddenContactsCoexist`.)
4. **Agent tries export/import/bulk/delete** → 403. (Task 7 `agentCannotExportImportBulkOrDelete`.)
5. **CSV with BOM, quoted commas, `+91 98…` phones, blank lines** imports correctly. (Task 6 `importHandlesMessyCsv`.)

---

### Task 1: Authorities and documents

**Files:** modify `access/Authority.java` (8 entries per spec Access table, group `contact` /
`contact-field`, BRAND level, defaults exactly as the table: view/annotate → CUSTOMER_ADMIN,
WORKSPACE_MANAGER, TEAM_LEAD, AGENT; edit/import/export/bulk → CUSTOMER_ADMIN, WORKSPACE_MANAGER,
TEAM_LEAD; delete/contact-field:manage → CUSTOMER_ADMIN, WORKSPACE_MANAGER); create the five
documents + repositories; `contact/Phones.java` (`normalise(String) → String`, `isValid(String)`).
Fix authority-count fixtures (`AuthorityTest`, `RoleSeederIT`: 16 → 24; role default lists).
Tests: `contact/PhonesTest`, `contact/ContactIndexesIT` (`twoHiddenContactsCoexist`,
`duplicatePhoneInBrandRejected`, `samePhoneInTwoBrandsAllowed`).
- [ ] Failing tests → run `./mvnw test -Dtest='PhonesTest,ContactIndexesIT'` (FAIL) → implement → PASS → `./mvnw test` → commit `feat: contact authorities and documents`.

### Task 2: Labels and attributes

**Files:** `ContactFieldService`, `ContactLabelController`, `ContactAttributeController`, views.
Validation per Global Constraints; duplicate name (case-insensitive) → `DUPLICATE` field `name`;
delete cascades `$pull` / `$unset attributes.<id>` on the brand's contacts; `contactCount` via count.
Tests `contact/ContactFieldsApiIT`: `managerCreatesLabelAndAttribute`, `duplicateNameIs409`,
`badColorIs400`, `deleteCascadesFromContacts`, `teamLeadCannotManageFields` (403), `otherBrandIs404`.
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact labels and attributes`.

### Task 3: Contacts from chats

**Files:** `ContactService.upsertFromChat(workspaceId, customerId, String remote, String senderName,
String lidPhone, Instant at)`, `ChatContactListener` (`@EventListener WhatsAppEvent`, names
`message`, `message_create`). Person = `payload.id.remote` (string or `{_serialized}` — reuse
`LoggingEventListener.wid`). Ignore `@g.us`, `@broadcast`, `@newsletter`, `status@broadcast`. Sender
name: for `fromMe == false` only, first non-blank of `payload.notifyName`, `payload._data.notifyName`,
`payload.pushname`. Lid phone: first `…@c.us` among `payload.senderPn`, `payload._data.senderPn`,
`payload.author` when remote is `@lid`. `@c.us` → find by phone (link waId) else by waId else create;
`@lid` → find by waId else create (fill phone if known and not used). `lastMessageAt = max`. Never
throws (catch, log WARN). Duplicate key → retry once.
Tests `contact/ChatContactListenerIT` (publish `WhatsAppEvent` directly): `incomingCreatesContactWithName`,
`outgoingCreatesContactWithoutName`, `groupIsIgnored`, `lidCreatesHiddenContact`, `lidWithSenderPnFillsPhone`,
`chatLinksImportedContactByPhone`, `editedNameIsNotOverwritten`, `replayIsIdempotent`,
`lastMessageAtNeverGoesBack`, `listenerSwallowsErrors` (e.g. event with unknown workspace / malformed payload).
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contacts from WhatsApp chats`.

### Task 4: Contact CRUD, labels on a contact, notes

**Files:** `ContactController`, `ContactNoteController`, `ContactService` methods, `ContactNoteService`.
Manual create needs a valid phone (409 `DUPLICATE` field `phone` if the brand has it) and sets
`source=MANUAL`, `nameEdited=true`; update of `name` sets `nameEdited`; attributes must be the
brand's ids (400 otherwise); label add/remove needs a brand label id (404 otherwise); delete removes
notes. Notes per spec (author edit; delete by author or `contact:delete`; `mine` flag).
Tests `contact/ContactsApiIT`: `teamLeadAddsContact`, `invalidOrDuplicatePhone`, `agentTagsAndNotes`,
`agentCannotEditAttributes`, `onlyAuthorEditsNote`, `managerDeletesAnyNote`, `deleteRemovesNotes`,
`otherBrandContactIs404`.
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact detail, tags and notes`.

### Task 5: Search, saved filters, count

**Files:** `ContactQuery` (`Criteria build(String workspaceId, ContactFilterSpec spec)`), search
endpoint, `ContactFilterController`. `q` → regex-escaped case-insensitive name contains OR phone
contains digits; label/attribute ids not of this brand are dropped from the filter (so a foreign id
matches nothing in `labelsAny`/`labelsAll`, and is ignored in `labelsNone`); `filterId` of another
brand → 404. Sorts per contract (default LAST_MESSAGE desc, then `_id`).
Tests `contact/ContactSearchIT`: one per filter field and op, `combinedFiltersAnd`, `sortAndPaging`,
`savedFilterRoundTrip`, `agentCannotSaveFilter`, `otherBrandsFilterIdIs404`, `foreignLabelIdMatchesNothing`,
`regexCharactersInQAreLiteral`.
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact search and saved filters`.

### Task 6: Import and export

**Files:** `ContactCsv` (parsing with BOM strip, quoted fields, blank lines; writing with proper
quoting), `ContactImportService`, `ContactExportController` (streams via `StreamingResponseBody`,
`MongoTemplate.stream`). Use a small hand-written RFC 4180 reader/writer (no new dependency) unless
`commons-csv` is already on the classpath.
Tests `contact/ContactImportExportIT`: `importCreatesAndUpdates`, `importReportsUnknownColumnsAndLabels`,
`importSkipsBadPhonesWithRowNumbers`, `importHandlesMessyCsv`, `importRejectsTooLarge`,
`exportColumnsAndQuoting`, `exportRespectsFilter`, `exportTooManyRowsIs400` (lower the limit through a
package-visible setter or property `omnichannel.contacts.export-limit` for the test).
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact CSV import and export`.

### Task 7: Bulk actions and access

**Files:** `ContactBulkService`, bulk + count endpoints. One `updateMany`/`deleteMany` with
`ContactQuery` criteria (ids ANDed with `workspaceId`); DELETE also deletes the contacts' notes.
Tests `contact/ContactBulkIT`: each action on ids and on a filter, `idsOfOtherBrandUntouched`,
`tooManyIdsIs400`, `exactlyOneSelection`, `deleteNeedsContactDelete` (team lead 403),
`agentCannotExportImportBulkOrDelete`.
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact bulk actions`.

### Task 8: Demo data and docs

**Files:** `contact/ContactDemoSeeder` (`@Order(6)`, same `@ConditionalOnProperty` as `DemoSeeder`;
finds "Demo Fashion" in "Demo Retail"; skips when the brand has any label). Labels VIP purple, Lead
blue, Customer green, Follow-up orange; attributes City, Email, Preferred size; 40 contacts with
phones `91900000` + 4 digits, Indian names, sources mixed, 4 hidden (waId `9000000000000NN@lid`),
labels/attributes spread, 6 notes by `demo-manager`; `lastMessageAt` spread over 30 days.
README: Contacts section (model, identity, from chats, filters, import/export columns, bulk,
authorities table). Test `contact/ContactDemoSeederIT`: `seedsWhenBrandExists`, `isIdempotent`,
`skipsWithoutDemoBrand`.
- [ ] Failing tests → implement → `./mvnw test` → commit `feat: contact demo data; docs`.
