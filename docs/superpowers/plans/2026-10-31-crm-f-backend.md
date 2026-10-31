# CRM phase F (backend): UX catch-up — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the console what the UX catch-up needs: redacted stage moves for restricted callers, a settings history with useful summaries, a deal's contacts as named rows with a channel-account filter on contact search and named contact-list changes on the timeline, a contact's labels and attributes through a new port method, and CSV import templates.

**Architecture:** No new collection or index. `crm.activity` gains a pure `StageMoveRedaction`; `TimelineService` applies it and answers the settings history; `CrmSettingsService` names what changed; `crm.deal` gains `DealContactView`, and `DealService` rewrites only the recorded contact-list diff; `crm.platform.ChannelContactQuery` gains `details`, implemented in `app.crm`; `CsvImportService` owns the import columns and builds the templates.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB, JUnit 5 + AssertJ + MockMvc, embedded Mongo in `*IT`.

**Spec:** `docs/superpowers/specs/2026-10-31-crm-ux-catch-up-design.md` (C3, C4, C7, C10, C13, C14, C16, C17, C19, C21, C22, §5). Reference where this plan is silent: `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/activity/CrmActivityServiceImpl.java` (`redactedFor`).

**Branch:** `crm-f` worktree at `.worktrees/crm-f` cut from `main` (both repos). Commands: module tests `./mvnw -q -pl omnichannel-crm -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; app ITs `./mvnw -q -pl omnichannel-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; all CRM tests `'-Dtest=**/crm/**/*'`; full `./mvnw -q verify`. Never run two Maven builds in one worktree at once. Absolute paths in every command.

## Global Constraints

- Phase A–E Global Constraints apply: module boundary (`CrmModuleBoundaryTest`, `ServiceBoundaryTest`), every query scoped by `workspaceId` first, errors through `CrmErrors`/`CodedException`, validation through `ValidationException` (wire `{code: "VALIDATION", fields}`).
- **Naming (spec D6):** nothing new says `phoneNumberId`. The new search filter is `channelAccountId`; it maps through `crm.segment.ChannelAccounts.FIELD`.
- **Exact strings.** Redacted summaries: from-side hidden `Moved into <stage> (<pipeline>) from another pipeline` (without a stage name `a stage`; without a pipeline name the parenthesis is dropped); to-side hidden `Moved from <stage> to another pipeline` (without a stage name `a stage`); both hidden `Moved within another pipeline`. Settings summary: `Updated CRM settings: <labels joined by ", ">` with the spec §5 labels in that order. Contact-list diff field: `contacts`, from and to joined by `, `, an unknown id as `a removed contact`.
- **Redacted keys:** from-side `fromPipelineId`, `fromStageId`, `fromStageName`, `fromStageEnteredAt`; to-side `toPipelineId`, `toStageId`, `toStageName`, `toPipelineName`.
- **Template columns:** contacts `WhatsApp number, First name, Last name, Email, Job title, Company, Lifecycle, Source`; companies `Name, Domain, Website, Industry, Size, Annual revenue, Phone, Email, City, Country, Description`; then `Label (key)` for each active custom field in the order `fields.list(ctx, entity, false)` gives. Files `crm-contacts-template.csv`, `crm-companies-template.csv`. Header row only, with `Csv.BOM`.
- Commits: one per task, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A deal's history from before it left a hidden pipeline:** `Placement.retag` makes those entries visible, through the deal and through its contact and company (Task 1 `historyInsideAHiddenPipelineIsRedacted`, `contactAndCompanyTimelinesAreRedactedToo`).
2. **A move between two pipelines the caller can see:** stays complete; redaction must not fire on every cross-pipeline entry (Task 1 `visibleMoveStaysComplete`).
3. **The diff list writes Mongo:** renaming the diff for the timeline must not store a `contacts` field or lose `crmContactIds` (Task 3 `contactListChangeIsNamedOnTheTimeline` asserts the stored document).
4. **A settings save that changes nothing:** no history entry, and the version rule is unchanged (Task 2 `saveWithoutChangesRecordsNothing`).
5. **The template and the import drifting apart:** every template column must be one the import reads, custom fields included (Task 5 `templateRoundTrips`).

---

### Task 1: Redact moves out of hidden pipelines

**Files:**
- Create: `omnichannel-crm/.../crm/activity/StageMoveRedaction.java`
- Modify: `omnichannel-crm/.../crm/activity/TimelineService.java`
- Test: `omnichannel-crm/src/test/.../crm/activity/StageMoveRedactionTest.java`, `omnichannel-app/src/test/.../app/crm/TimelineApiIT.java` (extend)

**Interfaces:**
- `StageMoveRedaction.redact(ActivityView a, PipelineAccess access, Set<String> hiddenStageIds): ActivityView` — pure. Returns `a` itself unless the type is `STAGE_CHANGED`, the payload is non-null and a side is hidden (spec C19). Otherwise a copy with a new `LinkedHashMap` payload lacking that side's keys and the summary of the Global Constraints.
- `TimelineService.hiddenStageIds(CrmContext ctx): Set<String>` — empty when `ctx.pipelines().all()`; else, from `crmPipelines` of the workspace, the stage ids of pipelines outside the access minus those of pipelines inside it. Read once per `timeline` call, and only when the page holds a `STAGE_CHANGED` entry.
- `TimelineService.timeline` wraps `view(a, persons)` with `redact`. `logActivity` is untouched.

- [ ] **Step 1: Failing tests.** `StageMoveRedactionTest`: `hiddenSourceIsRedacted` (four from-keys gone, to-keys kept, exact summary), `hiddenDestinationIsRedacted` (four to-keys gone, from-keys kept, exact summary), `bothSidesHiddenByPipelineIds`, `bothSidesHiddenByStageIds` (no pipeline ids in the payload, `Moved within another pipeline`), `visibleEntryIsUntouched` (same instance), `unrestrictedCallerIsUntouched`, `otherActivityTypesAreUntouched`, `missingNamesReadPlainly`, `storedPayloadIsNotMutated`. `TimelineApiIT`: `moveOutOfHiddenPipelineIsRedacted` (deal in a second pipeline, `assignPipelines(agent, salesId())`, manager moves it to Sales with `PUT /deals/{id}` and `pipelineId`; agent's `GET /activities?targetType=DEAL` entry has the redacted summary and no `fromStageName`; manager's names both sides), `contactAndCompanyTimelinesAreRedactedToo` (same deal with a company; agent reads CONTACT and COMPANY timelines; serialised response contains neither the hidden pipeline's name nor its stage name), `historyInsideAHiddenPipelineIsRedacted` (two stage moves in the hidden pipeline, then the move to Sales; the agent reads `Moved within another pipeline` then the from-side summary), `moveThroughAHiddenPipelineIsRedacted` (Sales to hidden and back: the outward entry reads `Moved from <stage> to another pipeline`), `visibleMoveStaysComplete` (agent has both pipelines; entry keeps `fromStageName`), `stageRemovedFromAVisiblePipelineKeepsItsHistory`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-crm -am test -Dtest=StageMoveRedactionTest -Dsurefire.failIfNoSpecifiedTests=false` and `./mvnw -q -pl omnichannel-app -am test -Dtest=TimelineApiIT -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the same commands → PASS.
- [ ] **Step 5: Commit** `Redact moves out of pipelines the caller cannot see`.

### Task 2: Settings history

**Files:**
- Modify: `omnichannel-crm/.../crm/settings/CrmSettingsService.java`, `.../crm/settings/CrmSettingsController.java`, `.../crm/activity/TimelineService.java`
- Create: `omnichannel-crm/.../crm/settings/SettingsChanges.java`
- Test: `omnichannel-crm/src/test/.../crm/settings/SettingsChangesTest.java`, `omnichannel-app/src/test/.../app/crm/SettingsHistoryIT.java`, `ActivityLogIT.java` (adjust if it pins the old summary)

**Interfaces:**
- `SettingsChanges.between(CrmSettings current, SettingsRequest req): List<String>` — changed keys in the spec §5 order, compared with `Objects.equals`, a null picklist equal to an empty one. `SettingsChanges.summary(List<String> keys): String` — the exact summary.
- `CrmSettingsService.update`: after a successful write, when the list is non-empty, records `SETTINGS_CHANGED` with that summary and `payload {changed: keys}`; when empty, records nothing. The save itself still bumps `version`, as today.
- `TimelineService.settingsHistory(CrmContext ctx, int page, int size): CrmPage<ActivityView>` — `ctx.require("crm:settings")`; same paging validation as `timeline`; filter `{workspaceId, type: SETTINGS_CHANGED}`, newest first; a `payload.userId` naming a known person is replaced by their name in the summary.
- `CrmSettingsController`: `@GetMapping("/history")`, `@PreAuthorize` of `crm:view` and `crm:settings`, `page` default 0, `size` default 50.

- [ ] **Step 1: Failing tests.** `SettingsChangesTest`: `listsChangedKeysInOrder`, `nullAndEmptyPicklistAreEqual`, `summaryUsesLabels`. `SettingsHistoryIT`: `historyListsChangesNewestFirst`, `summaryNamesWhatChanged`, `saveWithoutChangesRecordsNothing`, `pipelineChangeNamesThePerson` (assign a pipeline to the agent; the entry reads `Changed CRM pipelines of <agent name>`), `olderEntryStillReads` (raw entry with the old summary), `agentGets403`, `otherBrandIsNotListed`, `pagesAndCaps`.
- [ ] **Step 2: Run** `-Dtest=SettingsChangesTest` (module) and `-Dtest='SettingsHistoryIT,ActivityLogIT'` (app) → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `CrmSettingsApiIT`.
- [ ] **Step 5: Commit** `Read the CRM settings history, with summaries that say what changed`.

### Task 3: A deal's contacts

**Files:**
- Create: `omnichannel-crm/.../crm/deal/dto/DealContactView.java`
- Modify: `.../crm/deal/DealService.java`, `.../crm/deal/CrmDealController.java`, `.../crm/contact/dto/ContactSearchRequest.java`, `.../crm/contact/CrmContactService.java`, `.../crm/contact/CrmContactController.java`, `.../crm/csv/CrmExportController.java`
- Test: `omnichannel-app/src/test/.../app/crm/CrmDealsApiIT.java`, `CrmContactsApiIT.java` (extend)

**Interfaces:**
- `record DealContactView(String id, String name, String phone, String jobTitle, boolean primary)`.
- `DealService.contacts(CrmContext ctx, String dealId): List<DealContactView>` — `load` for access; ids = primary then `crmContactIds` without repeats; one `mongo.find` on `crmContacts` by id and workspace; `contactService.views` for names; primary first, then list order; missing contacts left out.
- `GET /api/workspaces/{ws}/crm/deals/{id}/contacts`, `crm:view`.
- `ContactSearchRequest` gains `String channelAccountId` as the last component; `CrmContactService.search` adds `Criteria.where(ChannelAccounts.FIELD).is(...)` when present; the two `new ContactSearchRequest(...)` calls gain a null.
- `DealService.update`: the Mongo write keeps using `diffs`; the recorded activity uses `recorded(ctx, diffs)`, which replaces a `crmContactIds` diff with `new FieldDiff("contacts", names(from), names(to))`.

- [ ] **Step 1: Failing tests.** `CrmDealsApiIT`: `dealContactsListPrimaryFirst`, `dealContactsFollowDealAccess` (404 for an agent outside the pipeline), `linkedContactIsListedForARestrictedUser`, `removedLinkedContactIsLeftOut`, `contactListChangeIsNamedOnTheTimeline` (the `FIELD_CHANGED` entry has field `contacts` with names; the stored deal still has `crmContactIds` and no `contacts` key), `putWithoutContactIdsKeepsThem`, `contactOnAnotherChannelAccountStillConflicts`. `CrmContactsApiIT`: `searchFiltersByChannelAccount`.
- [ ] **Step 2: Run** `-Dtest='CrmDealsApiIT,CrmContactsApiIT'` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `CrmExportIT`, `CrmModuleBoundaryTest`.
- [ ] **Step 5: Commit** `List a deal's contacts and name contact-list changes on the timeline`.

### Task 4: A contact's labels and attributes

**Files:**
- Create: `omnichannel-crm/.../crm/platform/ChannelContactDetails.java`, `.../crm/platform/ChannelAttributeValue.java`
- Modify: `.../crm/platform/ChannelContactQuery.java`, `.../crm/contact/CrmContactService.java`, `.../crm/contact/CrmContactController.java`, `omnichannel-app/.../app/crm/ChannelContactQueryAdapter.java`
- Test: `omnichannel-app/src/test/.../app/crm/ChannelContactQueryAdapterIT.java`, `CrmContactsApiIT.java` (extend)

**Interfaces:**
- `record ChannelAttributeValue(String name, String value)`; `record ChannelContactDetails(List<ChannelLabel> labels, List<ChannelAttributeValue> attributes)`.
- `ChannelContactQuery.details(String workspaceId, String contactId): ChannelContactDetails` — the brand's contact; label ids and attribute ids resolved through the existing `labels` and `attributes` repositories; unknown ids dropped; both lists sorted by name; a missing contact gives two empty lists.
- `CrmContactService.channelDetails(CrmContext ctx, String id): ChannelContactDetails` — `load` for access, then the port with `getContactId()`. `GET /contacts/{id}/channel-details`, `crm:view`.

- [ ] **Step 1: Failing tests.** `ChannelContactQueryAdapterIT`: `detailsNameLabelsAndAttributes`, `detailsDropUnknownIds`, `detailsOfAMissingContactAreEmpty`, `detailsAreBrandScoped` (a contact id of the other brand gives empty lists). `CrmContactsApiIT`: `channelDetailsFollowContactAccess` (manager 200, agent outside its pipelines 404).
- [ ] **Step 2: Run** `-Dtest='ChannelContactQueryAdapterIT,CrmContactsApiIT'` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; also `CrmModuleBoundaryTest`.
- [ ] **Step 5: Commit** `Show a contact's channel labels and attributes through the CRM`.

### Task 5: Import templates, docs, full build

**Files:**
- Modify: `.../crm/csv/CsvImportService.java`, `.../crm/csv/CrmImportController.java`, `README.md`
- Test: `omnichannel-app/src/test/.../app/crm/CrmImportIT.java` (extend)

**Interfaces:**
- `CsvImportService.template(CrmContext ctx, TargetType entity): String` — `ctx.require("crm:edit")`; `Csv.BOM + Csv.row(columns)`; `CONTACT_COLUMNS` and `COMPANY_COLUMNS` as constants beside the import, plus `Label (key)` per active custom field.
- `CrmImportController`: `@GetMapping("/contacts/import/template")`, `@GetMapping("/companies/import/template")`, `crm:view` and `crm:edit`, `text/csv;charset=UTF-8`, `Content-Disposition: attachment; filename="crm-<entity>-template.csv"`.

- [ ] **Step 1: Failing tests.** `CrmImportIT`: `templateListsColumnsAndActiveFields` (exact header with one custom field; an inactive field absent; BOM first; one line), `templateRoundTrips` (for each entity take the template header, append one row filling every column including a custom field, import it, assert `created == 1`, no errors, and every value stored), `templateNeedsEdit` (a `crm:view`-only user 403).
- [ ] **Step 2: Run** `-Dtest=CrmImportIT` → FAIL.
- [ ] **Step 3: Implement** and update the README: API table rows for the six calls of spec §5, a redaction line under the timeline notes, and the template under CSV.
- [ ] **Step 4: Run** `-Dtest=CrmImportIT` → PASS; then `./mvnw -q verify` → BUILD SUCCESS; name any pre-existing flake (`CrmExportIT.exportFollowsFilters`, `CampaignAdvancedTimeoutIT`).
- [ ] **Step 5: Commit** `Generate CSV import templates; document phase F`.

## Contract for the console (additions to phases A–E)

All under `/api/workspaces/{ws}/crm`.

| Call | Rights | Request | Response |
|---|---|---|---|
| `GET /deals/{id}/contacts` | `crm:view` | — | `Array<{id, name: string \| null, phone: string \| null, jobTitle: string \| null, primary: boolean}>`, primary first; 404 when the deal is hidden |
| `POST /contacts/search` | `crm:view` | gains `channelAccountId?: string` | unchanged |
| `GET /contacts/{id}/channel-details` | `crm:view` | — | `{labels: Array<{id, name, color: string \| null}>, attributes: Array<{name, value}>}` |
| `GET /contacts/import/template`, `GET /companies/import/template` | `crm:view`, `crm:edit` | — | CSV file |
| `GET /settings/history?page=&size=` | `crm:view`, `crm:settings` | — | `Page<Activity>`; 403 without `crm:settings` |

A `FIELD_CHANGED` entry may hold `{field: "contacts", from: "Asha", to: "Asha, Ravi"}`. A redacted `STAGE_CHANGED` entry has no `fromStageId` or `fromStageName`, and its `summary` is complete.
