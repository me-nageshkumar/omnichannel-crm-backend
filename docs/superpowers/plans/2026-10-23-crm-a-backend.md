# CRM phase A (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the CRM foundation and pipeline core as a new Maven module `omnichannel-crm`: per-brand switch, pipeline access, settings, pipelines and lanes, CRM contacts (capture from a chat, create from the board, move channel), companies, deals on a board with moves and won/lost, the activity timeline, and the inbox summary.

**Architecture:** `omnichannel-crm` is a vertical module (documents, repositories, services, controllers) depending only on `omnichannel-common` and `omnichannel-security`; it reaches platform data through `crm.platform` port interfaces implemented in `omnichannel-app` (`app.crm`). Every request builds one `CrmContext` (brand, switch, chattable numbers, pipeline access) and every query takes it. Writes go through `MongoTemplate` updates guarded by the record's `version`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB (embedded flapdoodle 7.0 in `*IT`), Lombok, JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-23-crm-port-design.md` (sections cited as §). Reference code for behaviour questions: `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/` (branch `crm-addon-phase-2`) — when this plan is silent on a rule, port the reference's rule.

**Branch:** `crm-a` worktree at `.worktrees/crm-a`. Module tests: `./mvnw -q -pl omnichannel-crm -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; app ITs: `./mvnw -q -pl omnichannel-app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; everything: `./mvnw -q verify`.

## Global Constraints

- Packages `me.nageshk.omnichannel.crm.<feature>` in `omnichannel-crm`; adapters in `me.nageshk.omnichannel.app.crm`. CRM code imports nothing from `me.nageshk.omnichannel.db..`, `..service..`, `..app..` (Task 1 test).
- Collections (camelCase plural, §5): `crmSettings`, `crmPipelines`, `crmPipelineAccess`, `crmContacts`, `crmCompanies`, `crmDeals`, `crmActivities`. No index on any existing collection.
- Every CRM document: `id`, `workspaceId`, `customerId`, `creationTime` (`@CreatedDate`), `lastUpdateTime` (`@LastModifiedDate`), `createdBy`, `lastModifiedBy` (user ids set by services), `@Version Long version`. Every index leads with `workspaceId`. Money fields `BigDecimal` with `@Field(targetType = FieldType.DECIMAL128)`.
- URLs: brand `/api/workspaces/{workspaceId}/crm/**`; platform `/api/customers/{customerId}/workspaces/{workspaceId}/crm`.
- Guards: brand endpoints `@PreAuthorize("@access.has(#workspaceId, 'crm:view')")` (plus the write authority where named); the service then calls `CrmAccess.resolve`, which throws 402 when the switch is off. Platform endpoint `@PreAuthorize("hasAuthority('crm:enable')")`.
- Errors (§7): 402 `CRM_NOT_ENABLED`; 403 `FORBIDDEN` / `CHAT_NOT_ASSIGNED` / `NO_PIPELINE_ACCESS`; 404 `NOT_FOUND` (other brand, inaccessible pipeline or record); 409 `STAGE_HAS_DEALS {stageId, count}`, `STAGE_TYPE_HAS_DEALS`, `PIPELINE_HAS_DEALS {count}`, `CHANNEL_MISMATCH`, `CHANNEL_TAKEN`, `COMPANY_HAS_OPEN_DEALS`, `LIMIT_REACHED {limit}`, `VERSION_CONFLICT`; 400 `VALIDATION {fields}` (`ValidationException`), `PIPELINE_INACTIVE`. All are `CodedException` (status carried) or the existing `NotFoundException`/`ValidationException`/`ForbiddenException`. `CodedException.extra` is `Map<String,String>`, so extras are strings (`count: "3"`; lists comma-joined).
- Optimistic writes: update requests carry `version`; services apply `findAndModify(query(_id, workspaceId, version), update(...).inc("version", 1))`; no match → 404 if the record is gone, else 409 `VERSION_CONFLICT`. Derived fields (counters, `pipelineIds`, `lastActivityAt`) are written with plain `updateFirst` and never bump `version`.
- Paging: `page` from 0, `size` default 50, cap 200; sort fields whitelisted per entity; unknown → 400 `VALIDATION`.
- Lengths: names/titles ≤ 200, email ≤ 254, jobTitle ≤ 100, description ≤ 5000, summary ≤ 2000.
- Commits: one per task, imperative mood, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Stale console after the switch goes off**: any brand CRM call returns 402, `status` still 200 with `enabled:false` (Task 4 test `everyEndpointButStatusAnswers402WhenOff`).
2. **Agent removed from a pipeline between two requests**: the next read of a deal in it is 404 and the board for it 404 — not stale data (Task 6 test `removingAccessHidesDealsImmediately`).
3. **Concurrent board moves** into the same gap: both land, positions stay strictly ordered, re-spacing happens when the gap is under 1e-6 (Task 10 test `manyMidpointInsertsRespaceTheLane`).
4. **Captured chat whose contact was deleted** from Contacts later: the CRM contact still opens with its snapshot and `contactDeleted: true` (Task 8 test `deletedPlatformContactShowsSnapshot`).
5. **Restricted agent opening a contact who also has deals in a pipeline they cannot see**: those deals, their timeline entries and their amounts are absent (Task 11 test `timelineHidesInaccessiblePipelines`, Task 8 test `countersForRestrictedUserCountOnlyAccessibleDeals`).

---

### Task 1: Module skeleton, boundary test, authorities

**Files:**
- Modify: `pom.xml` (`<module>omnichannel-crm</module>` after `omnichannel-security`), `omnichannel-app/pom.xml` (dependency `omnichannel-crm`), `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/access/Authority.java`
- Create: `omnichannel-crm/pom.xml` (parent `omnichannel-crm-backend`; deps `omnichannel-common`, `omnichannel-security`, `spring-boot-starter-web`, `spring-boot-starter-data-mongodb`, `spring-boot-starter-validation`), `omnichannel-crm/src/main/java/me/nageshk/omnichannel/crm/package-info.java`
- Test: `omnichannel-crm/src/test/java/me/nageshk/omnichannel/crm/CrmModuleBoundaryTest.java`; update `omnichannel-security/src/test/.../AuthorityTest` (count/defaults) and `omnichannel-app/src/test/.../access/RoleSeederIT` if they pin the catalogue size

**Interfaces:**
- Produces: `Authority.CRM_VIEW("crm:view")`, `CRM_VIEW_ALL("crm:view-all")`, `CRM_EDIT("crm:edit")`, `CRM_DELETE("crm:delete")`, `CRM_SETTINGS("crm:settings")`, `CRM_EXPORT("crm:export")` (BRAND), `CRM_ENABLE("crm:enable")` (PLATFORM). Defaults and descriptions from §4.1 table; group "CRM".

- [ ] **Step 1: Failing tests.** `CrmModuleBoundaryTest.crmImportsOnlyCommonAndSecurity`: walk `src/main/java` of the module, collect `import me.nageshk.omnichannel.` lines, assert every one starts with `me.nageshk.omnichannel.crm.`, `me.nageshk.omnichannel.common.`, `me.nageshk.omnichannel.security.access.` or `me.nageshk.omnichannel.security.auth.`; also assert no `import static` from forbidden packages. `AuthorityTest.crmAuthoritiesHaveSpecLevelsAndDefaults`: `crm:enable` PLATFORM with default `ACCOUNT_MANAGER`; `crm:view` and `crm:edit` default to CUSTOMER_ADMIN, WORKSPACE_MANAGER, AGENT; `crm:view-all`, `crm:delete`, `crm:settings`, `crm:export` to CUSTOMER_ADMIN, WORKSPACE_MANAGER only.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-crm -am test` → FAIL (module missing).
- [ ] **Step 3: Implement** the poms, `package-info.java` (javadoc: vertical CRM module, see spec §3a), the seven enum constants.
- [ ] **Step 4: Run** `./mvnw -q verify` → PASS (RoleSeeder adds the new codes to built-in roles on start).
- [ ] **Step 5: Commit** `Add the omnichannel-crm module and crm authorities`.

### Task 2: Platform ports and their in-process adapters (§3a)

**Files:**
- Create in `omnichannel-crm/.../crm/platform/`: `ContactDirectory.java`, `ChatDirectory.java`, `NumberDirectory.java`, `PeopleDirectory.java`, `ContactNoteDirectory.java`, `BrandDirectory.java`, `EnablementAudit.java`, records `PlatformContact.java`, `PlatformChat.java`, `PlatformNumber.java`, `PlatformPerson.java`, `PlatformNote.java`, `PlatformBrand.java`
- Create in `omnichannel-app/.../app/crm/`: `ContactDirectoryAdapter.java`, `ChatDirectoryAdapter.java`, `NumberDirectoryAdapter.java`, `PeopleDirectoryAdapter.java`, `ContactNoteDirectoryAdapter.java`, `BrandDirectoryAdapter.java`, `EnablementAuditAdapter.java` (each `@Component`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/CrmTestBase.java`, `PlatformAdaptersIT.java`

**Interfaces (produces; all ids are strings, all methods brand-scoped):**
- `record PlatformContact(String id, String workspaceId, String name, String phone)`; `ContactDirectory`: `Optional<PlatformContact> find(String ws, String contactId)`, `Map<String, PlatformContact> findAll(String ws, Collection<String> ids)`, `PlatformContact ensure(String ws, String customerId, String rawPhone, String name)` — existing by normalised phone, else `ContactService.create(ws, customerId, name, rawPhone, Set.of(), Map.of())`; invalid phone → `ValidationException` field `phone`.
- `record PlatformChat(String id, String workspaceId, String phoneNumberId, String contactId, String assignedUserId, String assignedTeamId, Instant lastInboundAt)`; `ChatDirectory`: `Optional<PlatformChat> find(String ws, String chatId)`, `Optional<PlatformChat> findFor(String phoneNumberId, String contactId)` (`findFirstByPhoneNumberIdAndContactId`).
- `record PlatformNumber(String id, String number, String label)`; `NumberDirectory`: `List<PlatformNumber> numbers(String ws)` (ordered by number), `Set<String> chattable(AuthenticatedUser me, String ws)` — every number when `access.inWorkspace(me, ws)` holds `inbox:view-all`, else numbers whose `teamId` is a team the user is a member of.
- `record PlatformPerson(String id, String name, String username)`; `PeopleDirectory`: `Set<String> teamIdsOf(String ws, String userId)`, `boolean isBrandUser(String ws, String userId)` (has a role in the brand or a company role in its customer, not disabled), `Map<String, PlatformPerson> people(Collection<String> userIds)`, `Optional<PlatformPerson> byUsername(String username)`.
- `record PlatformNote(String id, String text, String createdByName, Instant at)`; `ContactNoteDirectory`: `List<PlatformNote> notes(String ws, String contactId)` newest first.
- `record PlatformBrand(String id, String customerId, String name)`; `BrandDirectory`: `Optional<PlatformBrand> find(String ws)`, `Optional<PlatformBrand> findByNames(String customerName, String brandName)` (demo seeder), `List<String> allBrandIds()` (jobs).
- `EnablementAudit`: `void recordCrmSwitch(AuthenticatedUser by, String workspaceId, boolean before, boolean after)` → `EnablementChange{targetType:"crm", targetId: ws, by: username, level: PLATFORM, at: now}`.
- `CrmTestBase` (abstract, `@SpringBootTest @AutoConfigureMockMvc`): customer "Acme", brands `brand` and `other`, number `number` owned by team `support` (members `manager2`, `agent`), number `number2` in `brand` with no team, users `manager` (WORKSPACE_MANAGER), `agent` and `agent2` (AGENT, `agent2` in no team), `staff` (ACCOUNT_MANAGER, platform), tokens for each; helpers `contact(name, phone)`, `chat(contact, number, assignedUser, assignedTeam)`, `crm()` = `/api/workspaces/{brand}/crm`, `json(...)`; cleans every `crm*` collection, chats and contacts before and after each test. Later tasks add `enableCrm()` (Task 3) and `assignPipelines(user, ids...)` (Task 6).

- [ ] **Step 1: Failing tests** `PlatformAdaptersIT`: `ensureReturnsExistingContactByNormalisedPhone` (`"+91 99000 00001"` finds the stored `919900000001`), `ensureCreatesManualContact` (source MANUAL), `ensureRejectsBadPhone` (ValidationException field `phone`), `chattableIsTeamOwnedNumbersForAgent` (`agent` → {number}; `agent2` → {}), `chattableIsEveryNumberWithViewAll` (`manager` → {number, number2}), `isBrandUserFalseForOtherBrandUser`, `findChatIsBrandScoped` (chat of `other` → empty), `auditRecordsCrmSwitch` (one `enablementChanges` row, targetType `crm`).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=PlatformAdaptersIT -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement** ports in the CRM module and adapters in `app.crm` over the existing repositories/services named in §3a.
- [ ] **Step 4: Run** → PASS; boundary test still PASS. **Step 5: Commit** `Add the CRM platform ports and their in-process adapters`.

### Task 3: CRM settings, defaults and the platform switch (§4.2, §5.1)

**Files:**
- Create in `crm/common/`: `PicklistItem.java`, `CrmErrors.java` (factory methods returning `CodedException`), `CrmPage.java`; in `crm/settings/`: `CrmSettings.java`, `CrmSettingsRepository.java`, `CrmDefaults.java`, `CrmSettingsService.java`, `CrmEnablementController.java`, `dto/EnablementView.java`, `dto/EnablementRequest.java`
- Test: `omnichannel-crm/src/test/.../crm/settings/CrmDefaultsTest.java`; `omnichannel-app/src/test/.../app/crm/CrmEnablementApiIT.java`

**Interfaces:**
- `record PicklistItem(String key, String label, String color, int position, boolean active, boolean isDefault)`.
- `CrmErrors`: `notEnabled()` (402 `CRM_NOT_ENABLED`), `conflict(String code, String message, Map<String,String> extra)` (409), `forbidden(String code, String message)` (403), `badRequest(String code, String message)` (400). `record CrmPage<T>(List<T> items, long total, int page)`.
- `CrmSettings` (`@Document("crmSettings")`, unique index `workspace_unique {workspaceId}`): fields of §5.1 incl. `enabled`, `maxPipelines`, `maxCustomFields`.
- `CrmDefaults`: `static CrmSettings newSettings(String ws, String customerId)` (picklists, currency `INR`, rotting 14, `allowChannelMove` true, `timelineShowsWhatsappMessages` true, `enabled` false), `static List<StageSpec> salesStages()` (the seven stages of §5.1 with positions 1024·n).
- `CrmSettingsService`: `CrmSettings getOrCreate(String ws, String customerId)` (upsert, race-safe on the unique index), `Optional<CrmSettings> find(String ws)`, `CrmSettings setSwitch(AuthenticatedUser by, String customerId, String ws, EnablementRequest req)` (brand must belong to the customer else 404; limits ≥ 1 or null; audits only when `enabled` flips).
- API: `GET /api/customers/{customerId}/workspaces/{workspaceId}/crm` → `EnablementView{enabled, maxPipelines, maxCustomFields}` (defaults when no document); `PUT` same body → view.

- [ ] **Step 1: Failing tests.** `CrmDefaultsTest.salesPipelineHasSevenStagesWithReferenceProbabilities` (names New…Lost, probabilities 10,25,50,70,85,100,0, last two WON/LOST), `picklistsMatchReference` (lifecycle keys `lead,prospect,customer,lost`, `lead` default; dealSources, taskTypes, lostReasons keys of §5.1; industries empty). `CrmEnablementApiIT` (extends `CrmTestBase`): `staffSwitchesCrmOnWithLimits`, `getReturnsOffDefaultsWhenNeverSet`, `brandOfAnotherCustomerIs404`, `brandUserGets403`, `switchChangeIsAudited` (one row per flip; PUT with same `enabled` adds none), `limitsBelowOneRejected` (400 VALIDATION).
- [ ] **Step 2: Run** both → FAIL. **Step 3: Implement.** Add `enableCrm()` / `enableCrm(maxPipelines, maxCustomFields)` to `CrmTestBase` (PUT as `staff`). **Step 4: Run** → PASS. **Step 5: Commit** `Add CRM settings defaults and the per-brand switch`.

### Task 4: Request context, status and settings endpoints (§4.3, §6.9)

**Files:**
- Create in `crm/access/`: `CrmContext.java`, `PipelineAccess.java`, `CrmAccess.java`; `crm/status/CrmStatusController.java`, `crm/status/CrmStatusView.java`; `crm/settings/CrmSettingsController.java`, `crm/settings/dto/SettingsView.java`, `dto/SettingsRequest.java`
- Test: `omnichannel-crm/src/test/.../crm/access/CrmAccessTest.java` (fakes of the ports, `AccessService` mocked); `omnichannel-app/src/test/.../app/crm/CrmStatusApiIT.java`, `CrmSettingsApiIT.java`

**Interfaces:**
- `record PipelineAccess(boolean all, Set<String> pipelineIds)` with `static PipelineAccess ALL`, `boolean allows(String pipelineId)`, `Criteria criteria(String field)` (empty criteria when `all`, else `field in ids`; empty set → a criteria matching nothing).
- `record CrmContext(String workspaceId, String customerId, String userId, Set<String> authorities, CrmSettings settings, List<PlatformNumber> numbers, Set<String> chattable, PipelineAccess pipelines)` with `boolean can(String code)`, `void require(String code)` (403 FORBIDDEN), `boolean viewAll()`.
- `CrmAccess`: `CrmContext resolve(AuthenticatedUser me, String ws)` — 404 when the user has no access to the brand; 402 when the settings are absent or `enabled` false; 403 without `crm:view`. Pipeline set: `ALL` with `crm:view-all`, else the user's `crmPipelineAccess` ids (Task 6 supplies the reader through `PipelineAccessReader`, an interface in `crm.access` with a default bean returning empty until Task 6) intersected with existing pipeline ids. `CrmContext resolveForStatus(...)` does the same without the 402.
- `CrmStatusView{enabled, limits{maxPipelines, maxCustomFields}, phones[{phoneNumberId, number, label, canChat}], pipelineAccess: "ALL" | [ids], authorities: [crm:* held], attachmentsEnabled: true}`; `GET /crm/status` (`crm:view`).
- `SettingsView` = settings fields of §5.1 minus switch/limits, plus `version`; `GET /crm/settings` (`crm:view`), `PUT /crm/settings` (`crm:settings`): picklist keys `^[a-z][a-z0-9_]{0,39}$`, unique per list, an existing key may not disappear (deactivate instead) → 400 VALIDATION; at most one `isDefault` per list; `defaultPipelineId` must be an active pipeline; `dealRottingDays` 1..365 or null. (Task 7 adds the `SETTINGS_CHANGED` activity to this method.)

- [ ] **Step 1: Failing tests.** `CrmAccessTest`: `viewAllGetsAllPipelines`, `restrictedGetsAssignedIntersectExisting` (assignment has a deleted id → dropped), `offBrandThrows402`, `noViewThrows403`, `chattableComesFromNumberDirectory`. `CrmStatusApiIT`: `statusWhenOffSaysDisabled` (200, `enabled:false`), `statusForAgentListsNumbersWithCanChat` (number true, number2 false), `statusForManagerSaysAll`, `everyEndpointButStatusAnswers402WhenOff` (settings, pipelines, contacts search, deals board → 402 `CRM_NOT_ENABLED`; endpoints from later tasks are added to this test as they land), `otherBrandStatusIs404`. `CrmSettingsApiIT`: `firstReadCreatesDefaultsAndSalesPipeline`, `managerUpdatesPicklistsAndCurrency`, `removingPicklistKeyRejected`, `agentCannotUpdate` (403), `twoDefaultsRejected`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** First access to `GET /settings` or `/status` on an enabled brand runs `CrmPipelineService.ensureDefault` (Task 5) — until then create the settings only; Task 5 adds the default pipeline and its test. **Step 4: Run** → PASS. **Step 5: Commit** `Resolve the CRM request context and serve status and settings`.

### Task 5: Pipelines and stages (§5.2)

**Files:**
- Create in `crm/pipeline/`: `CrmPipeline.java`, `CrmStage.java`, `StageType.java`, `CrmPipelineRepository.java`, `CrmPipelineService.java`, `CrmPipelineController.java`, `dto/PipelineView.java`, `dto/PipelineRequest.java`, `dto/StageRequest.java`, `DealPlacement.java` (interface)
- Test: `omnichannel-crm/src/test/.../crm/pipeline/StageRulesTest.java`; `omnichannel-app/src/test/.../app/crm/CrmPipelinesApiIT.java`

**Interfaces:**
- `CrmPipeline` (`@Document("crmPipelines")`, index `ws_default {workspaceId, isDefault}`): `name`, `isDefault`, `active`, `rottingDays`, `List<CrmStage> stages`. `CrmStage{id (UUID), name, color, position (double), probability (0..100), type, wipLimit}`. `enum StageType { OPEN, WON, LOST }`.
- `DealPlacement` (implemented in Task 10; default bean reports zero deals until then): `long countInStage(String ws, String pipelineId, String stageId)`, `long countInPipeline(String ws, String pipelineId)`, `void relocateStage(CrmContext ctx, String pipelineId, String fromStageId, String toStageId)`, `void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId)`.
- `CrmPipelineService`: `CrmPipeline ensureDefault(String ws, String customerId)` (creates "Sales" from `CrmDefaults.salesStages()` and sets `defaultPipelineId` when the brand has no pipeline); `List<CrmPipeline> list(CrmContext)` (accessible only, by `name`); `CrmPipeline get(CrmContext, String id)` (404 when inaccessible); `create(CrmContext, PipelineRequest)` (`crm:settings`; `maxPipelines` → 409 `LIMIT_REACHED {limit}`); `update(CrmContext, String id, PipelineRequest)` (name, active, rottingDays, isDefault; making one default clears the others and updates settings; deactivating the default → 400 VALIDATION `active`); `replaceStages(CrmContext, String id, List<StageRequest>, long version)`; `delete(CrmContext, String id, String moveDealsToPipelineId)`.
- `StageRequest{id?, name, color?, probability, type, wipLimit?, moveToStageId?}` — list order is lane order; positions re-assigned 1024·(i+1). Rules: ≥ 1 OPEN stage; names unique (case-insensitive) and ≤ 60; probability 0..100; `wipLimit` ≥ 0 or null; a removed stage holding deals without `moveToStageId` → 409 `STAGE_HAS_DEALS {stageId, count}`; `moveToStageId` must be a kept stage; retyping a stage holding deals → 409 `STAGE_TYPE_HAS_DEALS`. Delete: holding deals without a target → 409 `PIPELINE_HAS_DEALS {count}`; target must be another accessible active pipeline; the deleted default promotes the oldest remaining pipeline (none left → settings `defaultPipelineId` null); removes the id from every `crmPipelineAccess` (Task 6 adds `PipelineAccessService.removePipeline`, call it from here then).
- API: `GET /crm/pipelines`, `GET /crm/pipelines/{id}`, `POST /crm/pipelines` (`crm:settings`), `PUT /crm/pipelines/{id}`, `PUT /crm/pipelines/{id}/stages {version, stages:[…]}`, `DELETE /crm/pipelines/{id}?moveDealsToPipelineId=` (204). `PipelineView{id, name, isDefault, active, rottingDays, stages[{id, name, color, position, probability, type, wipLimit}], version}`.

- [ ] **Step 1: Failing tests.** `StageRulesTest` (pure validation helper `StageRules.validate(List<StageRequest>, existing stages, counts)`): `needsAnOpenStage`, `duplicateNamesRejected`, `removedStageWithDealsNeedsTarget` (409 with `stageId` and `count`), `retypeWithDealsRejected`, `positionsFollowListOrder`. `CrmPipelinesApiIT`: `defaultSalesPipelineCreatedOnFirstAccess`, `managerCreatesPipelineWithEightLanes`, `limitReachedAtMaxPipelines` (enable with max 1 → second create 409 `LIMIT_REACHED`, `limit=1`), `agentCannotCreate` (403), `makingAnotherDefaultMovesTheFlagAndSettings`, `deletingDefaultPromotesAnother`, `staleVersionOnStagesIs409`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (+ Task 4 `firstReadCreatesDefaultsAndSalesPipeline`). **Step 5: Commit** `Add CRM pipelines and lane editing`.

### Task 6: Pipeline access per user (§5.2, §4.3)

**Files:**
- Create in `crm/access/`: `CrmPipelineAccess.java`, `CrmPipelineAccessRepository.java`, `PipelineAccessService.java` (implements `PipelineAccessReader`), `CrmPipelineAccessController.java`, `dto/UserPipelinesView.java`
- Modify: `CrmPipelineService.delete` (call `removePipeline`)
- Test: `omnichannel-app/src/test/.../app/crm/CrmPipelineAccessApiIT.java`

**Interfaces:**
- `CrmPipelineAccess` (`@Document("crmPipelineAccess")`, unique `ws_user {workspaceId, userId}`, index `ws_pipelines {workspaceId, pipelineIds}`): `userId`, `List<String> pipelineIds`.
- `PipelineAccessService`: `Set<String> pipelineIdsOf(String ws, String userId)`, `UserPipelinesView get(CrmContext, String userId)`, `UserPipelinesView put(CrmContext, String userId, List<String> pipelineIds)` (`crm:settings`; user must be `PeopleDirectory.isBrandUser` else 404; every id a pipeline of the brand else 400 VALIDATION `pipelineIds`; de-duplicated; upsert), `void removePipeline(String ws, String pipelineId)` (`$pull` from all).
- API: `GET /crm/users/{userId}/pipelines`, `PUT /crm/users/{userId}/pipelines {pipelineIds}` → `UserPipelinesView{userId, pipelineIds}`.
- `CrmTestBase.assignPipelines(User u, String... pipelineIds)`.

- [ ] **Step 1: Failing tests:** `managerAssignsSalesToAgent` (agent's `/status` then lists `pipelineAccess:[salesId]`, `/pipelines` lists only Sales), `agentWithNothingSeesNoPipelines` (`pipelineAccess: []`, `/pipelines` empty, `GET /pipelines/{salesId}` 404), `unknownPipelineRejected`, `userOfOtherBrandIs404`, `deletingPipelinePullsItFromAssignments`, `removingAccessHidesDealsImmediately` (add after Task 10: agent sees a deal, manager removes the assignment, agent's `GET /deals/{id}` → 404 and board → 404 — write it now `@Disabled("Task 10")` and enable in Task 10).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; replace the Task 4 placeholder `PipelineAccessReader` bean. **Step 4: Run** → PASS. **Step 5: Commit** `Assign CRM pipelines to users`.

### Task 7: Activity log (§5.3 `crmActivities`)

**Files:**
- Create in `crm/activity/`: `CrmActivity.java`, `ActivityType.java`, `ActorType.java`, `FieldDiff.java`, `CrmActivityRepository.java`, `ActivityLog.java`; `crm/common/CrmTarget.java`, `crm/common/TargetType.java`
- Modify: `CrmSettingsService.update`, `CrmPipelineService` (create/update/stages/delete), `PipelineAccessService.put` → record `SETTINGS_CHANGED`
- Test: `omnichannel-crm/src/test/.../crm/activity/FieldDiffTest.java`; `omnichannel-app/src/test/.../app/crm/ActivityLogIT.java`

**Interfaces:**
- `record CrmTarget(TargetType type, String id)`; `enum TargetType { CONTACT, COMPANY, DEAL, PIPELINE, SETTINGS }`.
- `enum ActivityType` = the full list in §5.3. `enum ActorType { USER, AUTOMATION, SYSTEM }`. `record FieldDiff(String field, Object from, Object to)`.
- `CrmActivity` (`@Document("crmActivities")`, indexes `ws_target {workspaceId, target.type, target.id, happenedAt:-1}`, `ws_related {workspaceId, relatedTargets.id, happenedAt:-1}`): `type`, `target`, `relatedTargets`, `pipelineId` (deal targets, for access filtering), `phoneNumberId`, `actorId`, `actorType`, `happenedAt`, `summary`, `Map<String,Object> payload`.
- `ActivityLog`: `CrmActivity record(CrmContext ctx, ActivityType type, CrmTarget target, List<CrmTarget> related, String pipelineId, String phoneNumberId, String summary, Map<String,Object> payload)` — actor = `ctx.userId()`/USER, `happenedAt` now unless `payload.happenedAt` is given by the log endpoint; then `$max`-updates `lastActivityAt` on the target and each related CONTACT/COMPANY/DEAL record (no version bump). `static List<FieldDiff> diff(Map<String,Object> before, Map<String,Object> after)` — keys present in `after` only, equal values skipped, `BigDecimal` compared with `compareTo`.

- [ ] **Step 1: Failing tests.** `FieldDiffTest`: `onlyChangedKeysInAfter`, `bigDecimalScaleIgnored` (`1.0` vs `1.00` no diff), `nullToValueIsADiff`. `ActivityLogIT`: `recordUpdatesLastActivityOnTargetAndRelated`, `lastActivityNeverMovesBack` (record with an older `happenedAt` leaves it), `settingsChangeIsRecorded` (PUT settings → one `SETTINGS_CHANGED` with target SETTINGS).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS. **Step 5: Commit** `Record CRM activities with field diffs`.

### Task 8: CRM contacts — capture, create, read, edit, communicate, move channel (§4.4, §4.5, §6.1, §6.2)

**Files:**
- Create in `crm/contact/`: `CrmContact.java`, `Channel.java`, `Origin.java`, `OriginKind.java`, `ChannelChange.java`, `CrmContactRepository.java`, `CrmContactService.java`, `CaptureService.java`, `ChannelService.java`, `CrmContactController.java`, `ContactCounters.java`, `dto/CrmContactView.java`, `dto/ContactSearchRequest.java`, `dto/ContactUpdateRequest.java`, `dto/CaptureRequest.java`, `dto/CreateRequest.java`, `dto/CaptureResult.java`, `dto/CommunicationView.java`, `dto/MoveChannelRequest.java`; `crm/common/Ref.java` (`record Ref(String id, String name)`)
- Test: `omnichannel-app/src/test/.../app/crm/CrmContactsApiIT.java`, `CaptureApiIT.java`, `ChannelApiIT.java`

**Interfaces:**
- `CrmContact` (`@Document("crmContacts")`, indexes of §5.3; unique `ws_contact_channel {workspaceId, contactId, channel.phoneNumberId}`): fields of §5.3; `record Channel(String phoneNumberId, String number)`; `record Origin(OriginKind kind, String phoneNumberId, String chatId, Instant firstMessageAt, String capturedById)`; `enum OriginKind { INBOUND_CHAT, CTWA_AD, FACEBOOK_LEAD, WEBSITE_LEAD, MANUAL, IMPORT, API }`; `record ChannelChange(String fromPhoneNumberId, String toPhoneNumberId, String byUserId, Instant at, String reason)`.
- `CaptureService`: `CaptureResult capture(CrmContext ctx, CaptureRequest req)` and `CaptureResult create(CrmContext ctx, CreateRequest req)`; shared `CaptureResult findOrCreate(CrmContext ctx, PlatformContact c, PlatformNumber channel, Origin origin, String pipelineId, String dealTitle)`. `CaptureRequest{chatId, pipelineId?, dealTitle?}`, `CreateRequest{phone, phoneNumberId, firstName?, lastName?, pipelineId, dealTitle?}`, `CaptureResult{contact: CrmContactView, deal: DealView|null, createdContact: boolean, createdDeal: boolean}`. Rules: `crm:edit`; §4.5 (`CHAT_NOT_ASSIGNED`, `NO_PIPELINE_ACCESS`); restricted users must pass `pipelineId` (missing → 400 VALIDATION `pipelineId`), inaccessible → 404, inactive → 400 `PIPELINE_INACTIVE`; `crm:view-all` users default to `settings.defaultPipelineId`; create's `phoneNumberId` must be in `ctx.chattable()` else 404; owner = caller; lifecycle = default picklist key; the deal is created through `DealService.createForCapture` (Task 10 — until then `createdDeal=false`, and Task 10 adds the deal assertions to these tests).
- `CrmContactService`: `CrmPage<CrmContactView> search(CrmContext, ContactSearchRequest)` (`q` on first/last name, phone, email; filters `ownerId`, `lifecycleStatus`, `companyId`, `pipelineId`; sort `lastActivityAt|creationTime|firstName` asc/desc; restricted: `pipelineIds ∈ access`, view-all: all); `CrmContactView get(CrmContext, String id)`; `CrmContactView update(CrmContext, String id, ContactUpdateRequest)` (fields: firstName, lastName, email, jobTitle, companyId, ownerId, lifecycleStatus, source, `version`; owner must be a brand user; company must exist; picklist values must be active keys unless unchanged; `FIELD_CHANGED` with diffs).
- `ContactCounters.recompute(String ws, String crmContactId)` — aggregation over `crmDeals` where `primaryCrmContactId == id or crmContactIds contains id`: `openDealCount`, `openDealAmount` (sum of OPEN `amount`), `pipelineIds` (distinct, any status); `nextTaskDueAt` stays for phase B. `CrmContactView` for a restricted user recomputes `openDealCount/openDealAmount` from accessible deals only (in memory from the deals query).
- `CrmContactView{id, contactId, channel{phoneNumberId, number}, phone, name, firstName, lastName, email, jobTitle, company: Ref|null, owner: Ref|null, lifecycleStatus, source, origin{kind, phoneNumberId, chatId, firstMessageAt, capturedBy: Ref|null}, customFields: {}, openDealCount, openDealAmount, lastActivityAt, nextTaskDueAt, contactDeleted, channelHistory[{from, to, by: Ref, at, reason}], creationTime, version}` — `name` = "first last" if set, else the live platform contact name, else the snapshot, else the phone.
- `ChannelService`: `CommunicationView communication(CrmContext, String id)` → `{phoneNumberId, number, phone, chatId|null, canChat}`; `void assertChannel(CrmContact c, String phoneNumberId)` → 409 `CHANNEL_MISMATCH`; `CrmContactView move(CrmContext, String id, MoveChannelRequest{toPhoneNumberId, reason?, version})` — `crm:settings`, `settings.allowChannelMove` else 403 FORBIDDEN, target a brand number else 404, same number → 400 VALIDATION, existing CRM contact for (contactId, target) → 409 `CHANNEL_TAKEN`; re-points the contact and every deal of it (`channel`), appends history, records `CHANNEL_CHANGED`.
- API: `POST /crm/contacts/search`, `GET /crm/contacts/{id}`, `PUT /crm/contacts/{id}` (`crm:edit`), `POST /crm/contacts/capture` (`crm:edit`), `POST /crm/contacts/create` (`crm:edit`), `GET /crm/contacts/{id}/communication?phoneNumberId=` (mismatch → 409), `POST /crm/contacts/{id}/move-channel` (`crm:settings`).

- [ ] **Step 1: Failing tests.** `CaptureApiIT`: `agentCapturesChatAssignedToThem` (origin INBOUND_CHAT, chatId set, capturedBy agent, owner agent, lifecycle `lead`), `agentCapturesChatAssignedToTheirTeam`, `agentCannotCaptureUnassignedChat` (403 `CHAT_NOT_ASSIGNED`), `agentWithoutPipelineCannotCapture` (403 `NO_PIPELINE_ACCESS`), `managerCapturesAnyChatIntoDefault`, `captureIsIdempotent` (second call `createdContact:false`), `restrictedCaptureNeedsPipelineId`, `createFromBoardOnChattableNumber` (contact created on brand, origin MANUAL), `createOnNumberNotChattableIs404` (agent + number2), `createBadPhoneIs400`, `sameNumberOnSecondChannelMakesSecondCrmContact`. `CrmContactsApiIT`: `searchFindsByNameAndPhone`, `restrictedSearchSeesOnlyContactsInTheirPipelines` (passes after Task 10; mark `@Disabled("Task 10")` until then), `updateRecordsFieldChangedDiffs`, `staleVersionIs409`, `unknownLifecycleRejected`, `deletedPlatformContactShowsSnapshot` (delete the `contacts` row directly → GET shows snapshot name, `contactDeleted:true`), `otherBrandContactIs404`, `countersForRestrictedUserCountOnlyAccessibleDeals` (`@Disabled("Task 10")`). `ChannelApiIT`: `communicationNamesChatOnChannel`, `communicationWithOtherNumberIs409Mismatch`, `canChatFalseForAgentOnNumber2`, `managerMovesChannelAndHistoryRecords`, `moveToNumberWithExistingCrmContactIs409Taken`, `agentCannotMove` (403), `moveRefusedWhenSettingOff` (403).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (disabled ones skipped). **Step 5: Commit** `Add CRM contacts with capture, create and channel moves`.

### Task 9: Companies (§5.3 `crmCompanies`, §6.2)

**Files:**
- Create in `crm/company/`: `CrmCompany.java`, `Address.java`, `CrmCompanyRepository.java`, `CrmCompanyService.java`, `CompanyCounters.java`, `CrmCompanyController.java`, `dto/CompanyView.java`, `dto/CompanyRequest.java`, `dto/CompanySearchRequest.java`, `dto/CompanySummaryView.java`
- Test: `omnichannel-app/src/test/.../app/crm/CrmCompaniesApiIT.java`

**Interfaces:**
- `CrmCompany` (`@Document("crmCompanies")`, indexes of §5.3) fields of §5.3; `record Address(String line1, String city, String state, String postalCode, String country)`.
- `CrmCompanyService`: `search(CrmContext, CompanySearchRequest{q, ownerId, industry, sort, page, size})`, `List<Ref> typeahead(CrmContext, String q)` (case-insensitive prefix on name, 10), `get`, `create(CrmContext, CompanyRequest)` (`crm:edit`; name required), `update(CrmContext, String id, CompanyRequest)` (with `version`, `FIELD_CHANGED`), `delete(CrmContext, String id)` (`crm:delete` and `crm:edit`; open deals → 409 `COMPANY_HAS_OPEN_DEALS`; unlink contacts with `updateMulti`; phase B removes tasks/notes), `CompanySummaryView summary(CrmContext, String id)` → `{company, contacts: CrmContactView[] (accessible), openDeals: DealView[] (accessible), contactCount, openDealAmount}`.
- `CompanyCounters.recompute(String ws, String companyId)`: `contactCount`, `openDealAmount`. Called on contact company change (Task 8 update) and deal writes (Task 10).
- API: `POST /crm/companies/search`, `GET /crm/companies/typeahead?q=` (search-as-you-type; its own path so it never clashes with the POST search), `GET /crm/companies/{id}`, `GET /crm/companies/{id}/summary`, `POST /crm/companies`, `PUT /crm/companies/{id}`, `DELETE /crm/companies/{id}` (204).

- [ ] **Step 1: Failing tests:** `createSearchAndTypeahead`, `industryMustBeActivePicklistKey`, `linkingContactUpdatesContactCount`, `deleteWithOpenDealsIs409` (`@Disabled("Task 10")`), `deleteUnlinksContacts`, `agentCannotDelete` (403), `restrictedSummaryHidesInaccessibleDeals` (`@Disabled("Task 10")`).
- [ ] **Step 2–5:** FAIL → implement (also call `CompanyCounters` from `CrmContactService.update` when `companyId` changes) → PASS → commit `Add CRM companies`.

### Task 10: Deals — create, edit, board, move, close, delete (§5.3 `crmDeals`, §5.4, §6.2)

**Files:**
- Create in `crm/deal/`: `CrmDeal.java`, `DealStatus.java`, `ContactSnapshot.java`, `CrmDealRepository.java`, `DealService.java`, `BoardService.java`, `Positions.java`, `DealPlacementImpl.java` (replaces the Task 5 default), `CrmDealController.java`, `dto/DealView.java`, `dto/DealRequest.java`, `dto/DealSearchRequest.java`, `dto/MoveRequest.java`, `dto/CloseRequest.java`, `dto/BoardView.java`
- Modify: `CaptureService` (deal on capture/create), enable the `@Disabled("Task 10")` tests of Tasks 6, 8, 9
- Test: `omnichannel-crm/src/test/.../crm/deal/PositionsTest.java`; `omnichannel-app/src/test/.../app/crm/CrmDealsApiIT.java`, `BoardApiIT.java`

**Interfaces:**
- `CrmDeal` (`@Document("crmDeals")`, indexes of §5.3): fields of §5.3; `enum DealStatus { OPEN, WON, LOST }`; `record ContactSnapshot(String name, String phone)`.
- `Positions` (pure): `static final double GAP = 1024.0`, `static final double MIN_GAP = 1e-6`; `static OptionalDouble between(Double before, Double after)` — both null → `GAP`; only `before` → `before + GAP`; only `after` → `after / 2`; both → midpoint, or empty when `after - before < MIN_GAP` (caller re-spaces the lane to `GAP·(i+1)` in current order and retries once); `static List<Double> respaced(int n)`.
- `DealService`: `CaptureDeal createForCapture(CrmContext, CrmContact, String pipelineId, String title)` (no-op returning the existing open deal when one exists in that pipeline; title default `"<contact name> deal"`; first OPEN stage; append position); `DealView create(CrmContext, DealRequest)` (`crm:edit`; accessible active pipeline; `primaryCrmContactId` visible; linked `crmContactIds` must share the primary's channel → 409 `CHANNEL_MISMATCH`; stage defaults to first OPEN; `RECORD_CREATED`); `DealView update(CrmContext, String id, DealRequest)` (`version`; stage not editable here; pipeline change needs both accessible → target's first OPEN stage, append; probability follows stage unless `probabilityOverridden`; `FIELD_CHANGED`); `DealView move(CrmContext, String id, MoveRequest{stageId, beforeDealId?, afterDealId?})` (stage of the deal's pipeline; status from stage type; entering WON/LOST sets `closedAt`, leaving clears it and `lostReason`; `stageEnteredAt` now; `STAGE_CHANGED` + `DEAL_WON`/`DEAL_LOST`); `DealView close(CrmContext, String id, CloseRequest{outcome WON|LOST, lostReason?})` (first stage of that type; lostReason must be an active `lostReasons` key when given); `void delete(CrmContext, String id)` (`crm:delete` + `crm:edit`); `CrmPage<DealView> search(CrmContext, DealSearchRequest{pipelineId?, stageId?, status?, ownerId?, companyId?, crmContactId?, q?, sort, page, size})`; `DealView get(CrmContext, String id)`. After every write: `ContactCounters.recompute` for each linked contact and `CompanyCounters.recompute` for old/new company.
- `BoardService.board(CrmContext, String pipelineId, int perStageLimit /*default 25, max 100*/, String ownerId, String q)` → `BoardView{pipelineId, lanes[{stage, total, sumAmount, deals: DealView[]}]}` (deals ordered by `position`; `sumAmount` of OPEN/WON amounts in the lane; inaccessible pipeline → 404). Load more uses `search` with `stageId`, `sort=position`, `page`.
- `DealView{id, title, pipelineId, stageId, position, status, amount, currency, probability, probabilityOverridden, expectedCloseDate (yyyy-MM-dd), closedAt, lostReason, company: Ref|null, primaryContact{id, name, phone}, crmContactIds, owner: Ref|null, source, labelIds, customFields, channel{phoneNumberId, number}, stageEnteredAt, rotting, overdue (nextTaskDueAt < now), lastActivityAt, nextTaskDueAt, creationTime, version}`.
- API: `POST /crm/deals/search`, `GET /crm/deals/board?pipelineId=&perStageLimit=&ownerId=&q=`, `GET /crm/deals/{id}`, `POST /crm/deals` (`crm:edit`), `PUT /crm/deals/{id}`, `POST /crm/deals/{id}/move`, `POST /crm/deals/{id}/close`, `DELETE /crm/deals/{id}` (`crm:delete`).

- [ ] **Step 1: Failing tests.** `PositionsTest`: `appendAddsGap`, `insertAtTopHalvesFirst`, `midpointBetweenNeighbours`, `tooSmallGapAsksForRespace`, `respacedIsEvenlyGapped`. `BoardApiIT`: `boardListsLanesWithTotalsAndSums`, `moveBetweenNeighboursKeepsOrder`, `manyMidpointInsertsRespaceTheLane` (60 inserts at the same spot → order preserved, no duplicate positions), `moveIntoWonClosesAndOutReopens` (closedAt set then cleared), `perStageLimitCapsCardsButNotTotal`, `restrictedBoardOfInaccessiblePipelineIs404`. `CrmDealsApiIT`: `captureCreatesDealInFirstOpenStage` (+ extend Task 8 capture tests: `createdDeal:true`, second capture `createdDeal:false`), `createNeedsVisiblePrimaryContact`, `linkedContactOnOtherChannelIs409Mismatch`, `moveToOtherPipelineNeedsBothAccessible` (agent with Sales only → 404), `closeLostWithReasonRecordsDealLost`, `closeWithUnknownReasonIs400`, `deleteNeedsDeleteAuthority`, `contactCountersFollowDealWrites` (openDealCount/openDealAmount/pipelineIds after create, close, delete), `stageRemovalRelocatesDeals` (Task 5 rule now real: 409 then success with `moveToStageId`), `pipelineDeleteRelocatesDeals`. Enable and pass the Task 6/8/9 `@Disabled` tests.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='Crm*IT,BoardApiIT,CaptureApiIT,ChannelApiIT' -Dsurefire.failIfNoSpecifiedTests=false` → PASS. **Step 5: Commit** `Add CRM deals with the board, moves and won/lost`.

### Task 11: Timeline, manual logging and the inbox summary (§6.3, §10 inbox panel)

**Files:**
- Create in `crm/activity/`: `TimelineService.java`, `CrmActivityController.java`, `dto/ActivityView.java`, `dto/LogRequest.java`; `crm/contact/InboxSummaryService.java`, `crm/contact/dto/InboxSummaryView.java`; add the summary endpoint to `CrmContactController`
- Test: `omnichannel-app/src/test/.../app/crm/TimelineApiIT.java`, `InboxSummaryApiIT.java`

**Interfaces:**
- `TimelineService.timeline(CrmContext, TargetType type, String id, int page, int size)` → `CrmPage<ActivityView>`: the target's own activities + (CONTACT) activities whose `relatedTargets` contain it or whose target is one of its deals + (COMPANY) its deals' activities; restricted users: drop entries with `pipelineId` outside access; for CONTACT targets, the platform contact's notes from `ContactNoteDirectory` as `{type: "LEGACY_NOTE", summary: text, actor: {name}}` merged by time (on page 0 only, all of them). Target must be visible (404 otherwise).
- `ActivityView{id, type, target{type, id}, summary, payload, actor: Ref|null, actorType, happenedAt}`.
- `LogRequest{target{type CONTACT|COMPANY|DEAL, id}, type CALL|MEETING|EMAIL, summary (1..2000), happenedAt? (≤ now + 1 min)}` → `CALL_LOGGED` / `MEETING_LOGGED` / `EMAIL_LOGGED`; `crm:edit`.
- `InboxSummaryService.summary(CrmContext, String chatId)` → `InboxSummaryView{chatId, canCapture, captureBlockedReason: null|"CHAT_NOT_ASSIGNED"|"NO_PIPELINE_ACCESS"|"NO_EDIT", contact: CrmContactView|null, openDeals: DealView[] (accessible), nextTask: null}` for the chat's (contact, number); chat of another brand → 404; a contact that exists but whose deals are all inaccessible to a restricted user → `contact: null`.
- API: `GET /crm/activities?targetType=&targetId=&page=&size=`, `POST /crm/activities/log` (`crm:edit`), `GET /crm/contacts/by-chat/{chatId}/summary`.

- [ ] **Step 1: Failing tests.** `TimelineApiIT`: `contactTimelineIncludesItsDealsAndLegacyNotes`, `timelineHidesInaccessiblePipelines`, `logCallAppearsNewestFirst`, `logInFutureRejected`, `logSummaryTooLongRejected`, `agentWithoutEditCannotLog` (role with `crm:view` only, created via a custom role or by removing `crm:edit` from a test role). `InboxSummaryApiIT`: `uncapturedAssignedChatCanCapture`, `unassignedChatBlockedWithReason`, `capturedChatShowsContactAndOpenDeals`, `otherBrandChatIs404`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the CRM timeline, activity logging and inbox summary`.

### Task 12: Demo data, isolation check, README (§11, §8, §14)

**Files:**
- Create: `omnichannel-crm/.../crm/demo/CrmDemoSeeder.java` (`@Component @Order(10) ApplicationRunner`, `@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")` like `ContactDemoSeeder`)
- Modify: `README.md` (module table row for `omnichannel-crm`; a "CRM" section: switch, authorities table, endpoints summary, demo data)
- Test: `omnichannel-app/src/test/.../app/crm/CrmDemoSeederIT.java`, `CrmIndexIsolationIT.java`

**Interfaces:**
- Seeder (via ports only): finds "Demo Retail"/"Demo Fashion"; skips when `crmSettings` exists for it; switches CRM on (no limits); ensures Sales; creates 3 companies, 8 CRM contacts on the demo number from the existing demo contacts' phones (`91900000000x`), 10 deals across all lanes (one WON, one LOST, one with `rotting=true`), assigns Sales to `demo-agent`, and logs a call on two deals.

- [ ] **Step 1: Failing tests.** `CrmDemoSeederIT.seedsDemoFashionOnce` (counts after one run; a second run adds nothing). `CrmIndexIsolationIT.crmCreatesIndexesOnlyOnItsOwnCollections`: record `getIndexInfo()` of every non-`crm*` collection, exercise capture + deal + move + log, compare equal; every `crm*` index name's key starts with `workspaceId`.
- [ ] **Step 2–3:** FAIL → implement; README.
- [ ] **Step 4: Run** `./mvnw -q verify` → all green; then `./mvnw -q -Pwith-frontend -DskipTests package` builds.
- [ ] **Step 5: Commit** `Seed CRM demo data and document the CRM module`.

---

## Contract for the console

JSON truth for `2026-10-23-crm-a-console.md`. All brand paths are under `/api/workspaces/{ws}/crm`. Dates are ISO-8601 instants; `expectedCloseDate` is `yyyy-MM-dd`; money is a JSON number; `Ref = {id, name}`; pages are `{items, total, page}`.

| Call | Request | Response |
|---|---|---|
| `GET /status` | — | `{enabled, limits:{maxPipelines, maxCustomFields}, phones:[{phoneNumberId, number, label, canChat}], pipelineAccess:"ALL"\|string[], authorities:string[], attachmentsEnabled:true}` |
| `GET\|PUT /api/customers/{c}/workspaces/{ws}/crm` | `{enabled, maxPipelines\|null, maxCustomFields\|null}` | same |
| `GET\|PUT /settings` | `{defaultCurrency, defaultPipelineId, dealRottingDays, allowChannelMove, timelineShowsWhatsappMessages, lifecycleStatuses, dealSources, taskTypes, lostReasons, industries, version}` (lists of `{key,label,color,position,active,isDefault}`) | same |
| `GET /pipelines`, `GET /pipelines/{id}` | — | `Pipeline = {id, name, isDefault, active, rottingDays, stages:[{id,name,color,position,probability,type:"OPEN"\|"WON"\|"LOST",wipLimit}], version}` |
| `POST /pipelines`, `PUT /pipelines/{id}` | `{name, active?, isDefault?, rottingDays?, stages?}` (create may carry stages; default: the Sales lanes) | `Pipeline` |
| `PUT /pipelines/{id}/stages` | `{version, stages:[{id?, name, color?, probability, type, wipLimit?, moveToStageId?}]}` | `Pipeline`; 409 `STAGE_HAS_DEALS {stageId, count}` / `STAGE_TYPE_HAS_DEALS` |
| `DELETE /pipelines/{id}?moveDealsToPipelineId=` | — | 204; 409 `PIPELINE_HAS_DEALS {count}` |
| `GET\|PUT /users/{userId}/pipelines` | `{pipelineIds}` | `{userId, pipelineIds}` |
| `POST /contacts/search` | `{q?, ownerId?, lifecycleStatus?, companyId?, pipelineId?, sort?: "lastActivityAt,desc", page, size}` | page of `CrmContact` (Task 8 view) |
| `GET /contacts/{id}`, `PUT /contacts/{id}` | `{firstName?, lastName?, email?, jobTitle?, companyId?, ownerId?, lifecycleStatus?, source?, version}` | `CrmContact` |
| `POST /contacts/capture` | `{chatId, pipelineId?, dealTitle?}` | `{contact, deal\|null, createdContact, createdDeal}` |
| `POST /contacts/create` | `{phone, phoneNumberId, firstName?, lastName?, pipelineId, dealTitle?}` | same |
| `GET /contacts/{id}/communication` | `?phoneNumberId=` optional | `{phoneNumberId, number, phone, chatId\|null, canChat}` |
| `POST /contacts/{id}/move-channel` | `{toPhoneNumberId, reason?, version}` | `CrmContact`; 409 `CHANNEL_TAKEN` |
| `GET /contacts/by-chat/{chatId}/summary` | — | `{chatId, canCapture, captureBlockedReason, contact\|null, openDeals:Deal[], nextTask:null}` |
| `POST /companies/search`, `GET /companies/typeahead?q=`, `GET /companies/{id}`, `GET /companies/{id}/summary` | `{q?, ownerId?, industry?, sort?, page, size}` | `Company = {id, name, domain, website, industry, size, annualRevenue, phone, email, address{line1,city,state,postalCode,country}, description, owner:Ref\|null, labelIds, customFields, contactCount, openDealAmount, version}`; typeahead `Ref[]`; summary `{company, contacts, openDeals, contactCount, openDealAmount}` |
| `POST /companies`, `PUT /companies/{id}`, `DELETE /companies/{id}` | company fields (+`version` on PUT) | `Company` / 204; 409 `COMPANY_HAS_OPEN_DEALS` |
| `GET /deals/board` | `?pipelineId=&perStageLimit=25&ownerId=&q=` | `{pipelineId, lanes:[{stage, total, sumAmount, deals:Deal[]}]}` |
| `POST /deals/search` | `{pipelineId?, stageId?, status?, ownerId?, companyId?, crmContactId?, q?, sort?, page, size}` | page of `Deal` (Task 10 view) |
| `GET /deals/{id}`, `POST /deals`, `PUT /deals/{id}`, `DELETE /deals/{id}` | `{title, pipelineId, stageId?, amount?, currency?, probability?, expectedCloseDate?, companyId?, primaryCrmContactId, crmContactIds?, ownerId?, source?, labelIds?, version (PUT)}` | `Deal` / 204 |
| `POST /deals/{id}/move` | `{stageId, beforeDealId?, afterDealId?}` | `Deal` |
| `POST /deals/{id}/close` | `{outcome:"WON"\|"LOST", lostReason?}` | `Deal` |
| `GET /activities` | `?targetType=&targetId=&page=&size=` | page of `{id, type, target, summary, payload, actor:Ref\|null, actorType, happenedAt}` (`type` may be `LEGACY_NOTE`) |
| `POST /activities/log` | `{target:{type,id}, type:"CALL"\|"MEETING"\|"EMAIL", summary, happenedAt?}` | activity |

Errors: `{code, message, ...extra}`; validation `{code:"VALIDATION", message, fields}`.
