# CRM phase C (backend): reports — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the reports API to the CRM module: a shared filter, ten `POST /crm/reports/*` endpoints computed by Mongo aggregations over what the caller may see, one write-side change (`fromStageEnteredAt` on stage moves), a `CampaignDirectory` port and adapter, three indexes, and backdated demo data.

**Architecture:** A new package `crm.report` in `omnichannel-crm`: `ReportFilter`/`ResolvedFilter` (parsing and validation), `ReportScope` (the first `$match` of every aggregation: brand + access + narrowing), `ReportBuckets` and `ReportMoney` (pure helpers), one class per report, `CrmReportService` (facade adding settings, pipelines, labels) and `CrmReportController`. Aggregations run through `MongoTemplate.aggregate` with `AggregationOptions.maxTime(10 s)`; a `MongoExecutionTimeoutException` maps to 503 `REPORT_TIMEOUT`. Host campaign data comes through `crm.platform.CampaignDirectory`, implemented in `omnichannel-app` `app.crm`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB aggregations (`$dateTrunc` with timezone, MongoDB 7.0), JUnit 5 + AssertJ + MockMvc, embedded Mongo 7.0.12 in `*IT`.

**Spec:** `docs/superpowers/specs/2026-10-30-crm-reports-design.md` (calls C1–C15, §4, §5, §7). **Depends on:** phases A and B merged into `main` (they are). Reference behaviour: `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/report` — port its aggregation shapes where this plan is silent, translated per the port design's §3 table (`customerId`→`workspaceId`, `ownerUsername`→`ownerId`, `phoneNumberId` stays, `actorUsername`→`actorId`, `crmDeal`→`crmDeals` etc.).

**Branch:** `crm-c` worktree at `.worktrees/crm-c` cut from `main`. Commands: module tests `./mvnw -q -pl omnichannel-crm -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; app ITs `./mvnw -q -pl omnichannel-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`; all CRM tests `'-Dtest=**/crm/**/*'`; full `./mvnw -q verify`. Never run two Maven builds in one worktree at once. Counts: surefire reports (`find . -path '*surefire-reports*' -name '*.txt' -mmin -N -exec sed -n 4p {} \;`).

## Global Constraints

- Phase A and B Global Constraints apply: module boundary (`crm` imports only `me.nageshk.omnichannel.crm.`, `.common.`, `.security.access.`, `.security.auth.` — `CrmModuleBoundaryTest`), every query scoped by `workspaceId` first, errors `{code, message, ...extras}` through `CrmErrors`/`CodedException`, no new collection.
- Every new index leads with `workspaceId` (`CrmIndexIsolationIT` checks every `crm*` index); the three new ones are exactly: `crmDeals` `ws_created {'workspaceId': 1, 'creationTime': 1}`, `crmContacts` `ws_created {'workspaceId': 1, 'creationTime': 1}`, `crmActivities` `ws_type_happened {'workspaceId': 1, 'type': 1, 'happenedAt': 1}`.
- Filter rules (C3): `from`/`to` ISO instants, default `[now − 90 d, now)`, `from < to`, `to − from ≤ 731 days`; `tz` an IANA zone id (`ZoneId.of`, must be a region id or "UTC"; `ZoneOffset`-only ids like `+05:30` and `GMT+5` are refused), default `UTC`; `pipelineId` must be accessible; `phoneNumberIds` ⊆ the brand's numbers; `ownerIds` free strings. Any problem → 400 `INVALID_REPORT_FILTER` with `field` = the parameter.
- Access (C2): deals by `pipelineId ∈ access`; contacts by `pipelineIds ∈ access`; companies brand-wide; activities by `pipelineId ∈ access` **and for restricted callers `pipelineId` must be present**. `ctx.pipelines().all()` short-circuits every access clause.
- Money (C4): amounts summed per `currency` (`$ifNull: ["$currency", <default>]`), returned as `double` with 2 decimals from `Decimal128`; `otherCurrencyDeals` on every money response; weighted = `ReportMoney.weighted(amount, dealProbability, stageProbability)`.
- Buckets (C6): auto `DAY` ≤ 31 days, `WEEK` ≤ 182 days, else `MONTH`; `$dateTrunc {unit, timezone, startOfWeek: "monday"}`; zero-filled by `ReportBuckets.starts(from, to, zone, bucket)`.
- Aggregations: `AggregationOptions.builder().maxTime(Duration.ofSeconds(10)).build()`; `org.springframework.dao.QueryTimeoutException` / `com.mongodb.MongoExecutionTimeoutException` → 503 `REPORT_TIMEOUT` (one translation in `CrmReportController`).
- Grid (C14): ≤ 100 groups, overflow folded into `{key: "__other", label: "Other"}`, `truncated: true`.
- Commits: one per task, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. **A restricted user whose only pipeline has no deals but who owns deals elsewhere**: every report shows zeros, never the other pipeline's figures; activities without a `pipelineId` (settings changes, company notes) never count for them (Task 2 test `restrictedCallerSeesOnlyTheirPipeline`, Task 5 test `activitiesWithoutPipelineExcludedForRestricted`).
2. **A deal whose amount is in another currency** must not be added into the default-currency sums but must be counted and reported in `otherCurrencyDeals` (Task 3 test `otherCurrencyCountedNotSummed`).
3. **A range that crosses a DST change in the caller's zone** (e.g. `Europe/London`, late October): daily buckets are still one per calendar day with no duplicate or missing day (Task 1 test `bucketsAcrossDstChange`).
4. **A stage move recorded before this phase** (no `fromStageEnteredAt`): stage duration ignores it and `since` reflects only counted moves (Task 5 test `legacyMovesIgnored`).
5. **A grid request naming an inactive or multi-valued custom field, or `stage` without a pipeline**: 400 `INVALID_REPORT_FILTER`, never a silent empty result (Task 6 test `gridRefusesBadFields`).

---

### Task 1: Filter, scope and pure helpers (spec C2–C4, C6, C15)

**Files:**
- Create: `omnichannel-crm/src/main/java/me/nageshk/omnichannel/crm/report/ReportFilter.java` (request record), `ResolvedFilter.java`, `ReportFilterResolver.java`, `ReportScope.java`, `ReportBucket.java` (enum DAY/WEEK/MONTH), `ReportBuckets.java`, `ReportMoney.java`, `ReportErrors.java`, `ReportRunner.java`
- Test: `omnichannel-crm/src/test/java/me/nageshk/omnichannel/crm/report/ReportFilterResolverTest.java`, `ReportBucketsTest.java`, `ReportMoneyTest.java`

**Interfaces:**
- `record ReportFilter(String from, String to, String tz, String pipelineId, List<String> phoneNumberIds, List<String> ownerIds)` — the JSON body (all optional).
- `record ResolvedFilter(Instant from, Instant to, ZoneId zone, String pipelineId, Set<String> phoneNumberIds, Set<String> ownerIds)` with `withPipeline(String)`.
- `ReportFilterResolver.resolve(CrmContext ctx, ReportFilter f, Clock clock): ResolvedFilter` — the Global Constraints' rules; `pipelineId` checked with `ctx.pipelines().allows(id)` and existence via `PipelineIds.existing(ws)`; numbers via `ctx.numbers()`.
- `ReportErrors.invalid(String field, String message): CodedException` → 400 `INVALID_REPORT_FILTER`, extra `{field}`; `ReportErrors.timeout(): CodedException` → 503 `REPORT_TIMEOUT`.
- `ReportScope` (component): `Criteria deals(CrmContext, ResolvedFilter)` = `workspaceId` + access on `pipelineId` + optional `pipelineId`, `channel.phoneNumberId in`, `ownerId in`; `Criteria contacts(...)` = same with access on `pipelineIds` and `channel.phoneNumberId`; `Criteria companies(...)` = `workspaceId` + `ownerId in`; `Criteria activities(...)` = `workspaceId` + (restricted: `pipelineId in access` — which excludes missing ones) + optional `pipelineId`, `phoneNumberId in`, `actorId in` + `happenedAt in [from, to)`; `static Criteria inRange(String field, ResolvedFilter)`; `CrmPipeline pipelineOrDefault(CrmContext, String pipelineId)` (named → must be accessible, else default when accessible, else first accessible by creation, else `invalid("pipelineId", "No accessible pipeline")`).
- `ReportBuckets.auto(Instant from, Instant to): ReportBucket`; `starts(Instant from, Instant to, ZoneId, ReportBucket): List<Instant>`; `truncExpression(String field, ReportBucket, ZoneId): Document`.
- `ReportMoney.currencyOf(String dealCurrency, String defaultCurrency)` (blank → default → `"INR"`); `toBigDecimal(Object)`; `weighted(BigDecimal, Integer, Integer)`; `scaled(BigDecimal): double` (2 decimals, HALF_UP).
- `ReportRunner.run(MongoTemplate, String collection, List<AggregationOperation>): List<Document>` — `newAggregation(ops).withOptions(maxTime 10 s)`, `aggregate(…, collection, Document.class).getMappedResults()`; translates `MongoExecutionTimeoutException`/`QueryTimeoutException` to `ReportErrors.timeout()`.

- [ ] **Step 1: Failing tests.** `ReportFilterResolverTest`: `defaultsToLastNinetyDaysUtc`, `refusesFromAfterTo`, `refusesRangeOver731Days`, `refusesNonRegionZone` (`+05:30`, `GMT+5`, `Mars/Base` → field `tz`), `acceptsRegionZoneAndUtc`, `refusesInaccessiblePipeline`, `refusesUnknownNumber`. `ReportBucketsTest`: `autoBucketByRangeLength` (31 d → DAY, 32 d → WEEK, 182 d → WEEK, 183 d → MONTH), `weekStartsOnMonday`, `monthBucketsInZone` (Asia/Kolkata), `bucketsAcrossDstChange` (`Europe/London`, 2026-10-20 → 2026-11-02, DAY → 13 starts, each at local midnight), `truncExpressionShape`. `ReportMoneyTest`: `currencyFallsBackToDefaultThenInr`, `weightedUsesDealThenStageProbability`, `weightedOfMissingAmountIsZero`, `scaledRoundsHalfUp`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-crm -am test -Dtest='Report*Test' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (classes missing).
- [ ] **Step 3: Implement** the files above. `ReportScope` is pure Criteria building (no Mongo) except `pipelineOrDefault`, which uses `MongoTemplate` + `PipelineIds`.
- [ ] **Step 4: Run** the same → PASS.
- [ ] **Step 5: Commit** `Add the CRM report filter, scope and helpers`.

### Task 2: Pipeline summary and deals over time (spec C5, C6)

**Files:**
- Create: `crm/report/PipelineSummaryReport.java`, `DealsOverTimeReport.java`, `dto/PipelineSummaryView.java`, `dto/DealsOverTimeView.java`, `CrmReportService.java`, `CrmReportController.java`
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/ReportsApiIT.java` (shared by Tasks 2–6; use `CrmTestBase`)

**Interfaces:**
- `CrmReportService` holds `MongoTemplate`, `ReportScope`, `ReportFilterResolver`, `CrmSettingsService` (default currency via `find(ws)`), `CrmPipelineService`, `PeopleDirectory`, `NumberDirectory`, `FieldDefinitionService`, `Clock`; one public method per report taking `(CrmContext, ReportFilter)` (grid takes `GridRequest`).
- `CrmReportController` at `/api/workspaces/{workspaceId}/crm/reports`, `@PreAuthorize("@access.has(#workspaceId, 'crm:view')")`, `@PostMapping` per spec §5 path (`pipeline-summary`, `deals-over-time`, `by-owner`, `by-source`, `by-number`, `lifecycle-funnel`, `activity-summary`, `stage-duration`, `campaigns`, `grid`), bodies `@RequestBody(required = false)` (null → empty filter), `crmAccess.resolve(me, workspaceId)` as every CRM controller.
- Views exactly as spec §5. `PipelineSummaryView(pipelineId, pipelineName, currency, List<Stage> stages, long openCount, double openAmount, Outcome won, Outcome lost, Double winRate, Double averageWonAmount, long otherCurrencyDeals)`; `Stage(stageId, name, StageType type, long openCount, double openAmount, double weightedAmount)`; `Outcome(long count, double amount)`. `DealsOverTimeView(ReportBucket bucket, String currency, List<Point> points, long otherCurrencyDeals)`; `Point(Instant start, long created, long won, long lost, double wonAmount)`; request field `bucket` (optional, on a `DealsOverTimeRequest extends`-style record that carries the filter fields plus `bucket`).
- Pipeline summary: one aggregation over `crmDeals` matching `scope.deals` with the pipeline, grouping by `{stageId, status, currency}` with count, sum amount, and sum of `amount × coalesce(probability, <stage probability via $switch>)/100` — simpler: group by `{stageId, status, currency, probability}` and weight in Java with the stage's probability when `probability` is null. Won/lost in the period by `closedAt in [from, to)`.

- [ ] **Step 1: Failing tests** in `ReportsApiIT` (seed through the API: `captured(...)`, `createDeal`, `/deals/{id}/move`, `/deals/{id}/close`, `PUT /deals/{id}` with `amount`/`currency`; backdate `closedAt`/`creationTime` directly with `mongo.updateFirst` on `"crmDeals"` where the test needs history): `pipelineSummaryCountsOpenAndClosed` (stages in lane order, open amounts, weighted with stage probability, won/lost in period, winRate 0.5, averageWonAmount), `pipelineSummaryDefaultsToDefaultPipeline`, `restrictedCallerSeesOnlyTheirPipeline` (agent assigned to "Second": summary of Sales → 400 `INVALID_REPORT_FILTER`; deals-over-time without pipeline counts only Second's deals), `dealsOverTimeZeroFillsWeeks` (60-day range → WEEK, every week present, created/won on the right starts in `Asia/Kolkata`), `dealsOverTimeBucketOverride` (`bucket: "DAY"`), `badFilterIs400` (`tz: "+05:30"` → `field: "tz"`; `from > to` → `field: "from"`), `agentWithoutViewIs403`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=ReportsApiIT -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (404s).
- [ ] **Step 3: Implement.** Register nothing new in security: `crm:view` is a brand authority already seeded.
- [ ] **Step 4: Run** → PASS; also `CrmModuleBoundaryTest`.
- [ ] **Step 5: Commit** `Add the CRM pipeline summary and deals-over-time reports`.

### Task 3: Breakdowns — by owner, by source, by number, lifecycle funnel (spec C7–C10)

**Files:**
- Create: `crm/report/BreakdownReports.java`, `LifecycleFunnelReport.java`, `dto/ByOwnerView.java`, `dto/BySourceView.java`, `dto/ByNumberView.java`, `dto/LifecycleFunnelView.java`
- Modify: `CrmReportService`, `CrmReportController` (four endpoints)

**Interfaces:**
- Views as spec §5. Labels: owners through `PeopleDirectory.people(ids)` (missing → "Unknown person"; `""` → "Unassigned"); sources through `settings.getDealSources()` (blank → "Unspecified"; unknown key → the key); numbers through `NumberDirectory.numbers(ws)` (label else number; missing → "Removed number"); lifecycle through `settings.getLifecycleStatuses()` ordered active-by-position, then inactive-by-position, then `{key: "", label: "Not set", active: false}` last; `OriginKind.values()` order for contacts by origin, zero-filled.
- `BreakdownReports.byField(ctx, filter, String field, String defaultCurrency)` — one aggregation over `crmDeals`: `$match scope.deals`, `$group {_id: {k: $field, status, currency, inPeriod: closedAt in range}}` with count and sum; Java folds into rows. Reused by owner, source and number; `created` uses a second group on `creationTime in range`.
- `otherCurrencyDeals` = count of matched deals whose reporting currency ≠ default (per report).

- [ ] **Step 1: Failing tests** in `ReportsApiIT`: `byOwnerListsUnassignedAndNames`, `bySourceLabelsPicklistAndOrigins` (a deal with `source: "website"`, one blank; contacts by origin show `INBOUND_CHAT` from captures and `MANUAL` from `/contacts/create`), `byNumberLabelsNumbers` (two numbers; deals on each), `lifecycleFunnelOrdersPicklistAndNotSet` (an inactive status still listed after active ones), `otherCurrencyCountedNotSummed` (a USD deal in a brand whose default is INR: by-owner `wonAmount` excludes it, `otherCurrencyDeals: 1`, counts include it), `ownerFilterNarrowsEveryBreakdown`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Add the CRM breakdown and lifecycle reports`.

### Task 4: `fromStageEnteredAt` on stage moves (write side, spec C12)

**Files:**
- Modify: `crm/deal/Placement.java` (`recordMove`: `payload.put("fromStageEnteredAt", before.getStageEnteredAt())` when non-null)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/TimelineApiIT.java` (extend)

- [ ] **Step 1: Failing test** `stageChangeRecordsWhenTheStageWasEntered`: capture → move → the `STAGE_CHANGED` activity's `payload.fromStageEnteredAt` equals the deal's `stageEnteredAt` before the move (read the deal first); a pipeline change via `PUT /deals/{id}` with `pipelineId` records it too; a stage removal with `moveToStageId` (`PUT /pipelines/{id}/stages`) records it on every relocated deal.
- [ ] **Step 2: Run** `-Dtest=TimelineApiIT` → FAIL. **Step 3: Implement** (one line plus Javadoc). **Step 4: Run** → PASS, plus `CrmDealsApiIT,CrmPipelinesApiIT`.
- [ ] **Step 5: Commit** `Record when a deal entered the stage it leaves`.

### Task 5: Activity summary and stage duration (spec C11, C12, C15)

**Files:**
- Create: `crm/report/ActivitySummaryReport.java`, `StageDurationReport.java`, `dto/ActivitySummaryView.java`, `dto/StageDurationView.java`
- Modify: `CrmReportService`, `CrmReportController` (two endpoints); `crm/activity/CrmActivity.java` (index `ws_type_happened`), `crm/deal/CrmDeal.java` (index `ws_created`), `crm/contact/CrmContact.java` (index `ws_created`)

**Interfaces:**
- Activity summary: one aggregation over `crmActivities`: `$match scope.activities ∧ actorType = USER ∧ type in [the eight]`, `$group {_id: {actorId, type}, n}`; rows per actor with the eight counters; labels via `PeopleDirectory`; sort per C11.
- Stage duration: as spec C12; `since` = min `happenedAt` of counted moves; median/average 1 decimal, null when none; open deals: `$match scope.deals(pipeline) ∧ status OPEN`, project `stageId, stageEnteredAt`, average days = `(now − stageEnteredAt)` in Java (`Clock`).

- [ ] **Step 1: Failing tests** in `ReportsApiIT`: `activitySummaryCountsPerPerson` (manager completes a task, adds a note, logs a call, moves and wins a deal; agent logs a meeting), `activitiesWithoutPipelineExcludedForRestricted` (a company note by the manager, an agent restricted to Sales: the agent's report never counts it; the manager's does), `stageDurationFromMoves` (backdate `stageEnteredAt` before each move with `mongo.updateFirst`, move twice; averages/medians per stage; `openNow` and `averageDaysOpenNow` for the final stage), `legacyMovesIgnored` (`$unset payload.fromStageEnteredAt` on one activity → it is not counted and `since` moves later), `newIndexesLeadWithWorkspace` (assert the three index names exist on their collections — `CrmIndexIsolationIT` already enforces the leading key).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS, plus `CrmIndexIsolationIT`.
- [ ] **Step 5: Commit** `Add the CRM activity summary and stage duration reports`.

### Task 6: Grid report (spec C14)

**Files:**
- Create: `crm/report/GridReport.java`, `GridFields.java`, `dto/GridRequest.java`, `dto/GridView.java`
- Modify: `CrmReportService`, `CrmReportController` (`/grid`)

**Interfaces:**
- `record GridRequest(…ReportFilter fields…, String entityType, String groupBy, String metric, String field, String dateBucket, String dateField)`.
- `GridFields.resolve(ctx, GridRequest, List<CrmFieldDefinition> active): Resolved{TargetType entity, String collection, String groupPath, LabelSource labels, String metric, String valuePath, String dateField, ReportBucket bucket}`; built-in allowlist as spec C14 (`number` → `channel.phoneNumberId`); `custom:<key>` → `customFields.<key>` for active SELECT/BOOLEAN/USER/TEXT (group) or NUMBER/CURRENCY (value); anything else → `invalid("groupBy"|"field"|"dateField"|"entityType"|"metric"|"dateBucket", …)`.
- `GridView(entityType, groupBy, metric, field, dateBucket, currency, List<Row> rows, boolean truncated, long otherCurrencyDeals)`; `Row(String key, String label, Instant bucket, double value, long count)`.
- Aggregation: `$match scope.<entity> ∧ dateField in range`, `$group {_id: {g: $groupPath[, b: $dateTrunc][, c: $ifNull currency]}, count, sum}`; Java: for SUM/AVG on `amount` keep only the default currency, count the rest into `otherCurrencyDeals`; AVG = sum/count; rank groups by value desc then count desc; fold past 100 into `__other` (summing); label as C14.

- [ ] **Step 1: Failing tests** in `ReportsApiIT`: `gridCountsDealsByStage` (needs `pipelineId`; labels are stage names), `gridSumsAmountBySourcePerMonth` (rows carry `bucket`), `gridGroupsContactsByLifecycleAndCompaniesByIndustry`, `gridCustomFieldGroupAndMetric` (a SELECT field groups by option label; a BOOLEAN groups as Yes/No; a CURRENCY field as AVG), `gridFoldsBeyond100Groups` (seed 101 companies with distinct `size` values → 100 rows + Other, `truncated: true`), `gridRefusesBadFields` (`stage` without pipeline; `custom:multi` on a MULTI_SELECT; inactive field; `dateField: "closedAt"` on CONTACT; `metric: "MAX"` → each 400 with the right `field`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Add the CRM grid report`.

### Task 7: Campaigns card and the `CampaignDirectory` port (spec C13, §4)

**Files:**
- Create: `crm/platform/CampaignDirectory.java`, `crm/platform/PlatformCampaign.java`, `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/crm/CampaignDirectoryAdapter.java`, `crm/report/CampaignsReport.java`, `dto/CampaignsView.java`
- Modify: `CrmReportService`, `CrmReportController` (`/campaigns`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/PlatformAdaptersIT.java` (extend), `ReportsApiIT` (extend)

**Interfaces:**
- `interface CampaignDirectory { List<PlatformCampaign> campaigns(String workspaceId, Instant from, Instant to, int limit); }` — campaigns of the brand with `startedAt in [from, to)`, newest first, at most `limit` (+1 so the caller can tell truncation).
- `record PlatformCampaign(String id, String name, String status, Instant startedAt, List<String> phoneNumberIds, Counts counts, Set<String> recipientContactIds)`; `record Counts(int total, int sent, int delivered, int read, int replied, int failed)`.
- Adapter: `MongoTemplate` over `Campaign.class` (`workspaceId`, `startedAt`), `campaignSenders` by `senderIds` → `phoneNumberId`, recipients by `campaignId` projecting `contactId` (`distinct`-style via `findDistinct`). Nothing in `omnichannel-db` changes.
- `CampaignsReport.build(ctx, filter)`: campaigns through the port (limit 200); visible CRM contact ids = `crmContacts` matching `scope.contacts(ctx, filter without owner)` projected to `contactId`; `crmContactsReached` = |recipients ∩ visible|; restricted callers (`!ctx.pipelines().all()`) drop rows with 0 reached; `phoneNumberIds` filter keeps campaigns using any of them; rows carry number labels via `NumberDirectory`. `CampaignsView(List<Row> rows, boolean truncated)`.

- [ ] **Step 1: Failing tests.** `PlatformAdaptersIT.campaignDirectoryListsStartedCampaignsWithRecipients` (insert two `Campaign` docs with `startedAt` and `senderIds`, a `CampaignSender`, and recipients with `contactId`; one outside the range; assert order, counts, ids, limit+1 behaviour). `ReportsApiIT.campaignsCardCountsCrmContactsReached` (manager sees the campaign with `crmContactsReached` = captured contacts among recipients; an agent restricted to a pipeline with none of them sees no row), `campaignsCardEmptyWhenNoneStarted`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `-Dtest='PlatformAdaptersIT,ReportsApiIT,CrmModuleBoundaryTest'` → PASS.
- [ ] **Step 5: Commit** `Add the CRM campaigns report through a campaign port`.

### Task 8: Demo data, README, full build (spec §7, C18)

**Files:**
- Modify: `crm/demo/CrmDemoSeeder.java` (§7 backdating), `README.md` (CRM section: Reports endpoints, the shared filter, `fromStageEnteredAt`, new indexes), `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/CrmDemoSeederIT.java`, `CrmIndexIsolationIT.java` (exercise two report calls)

- [ ] **Step 1: Failing tests.** `CrmDemoSeederIT`: deals' `creationTime` spread over ≥ 30 distinct days in the last 60; the WON deal `closedAt` ≈ 3 days ago and the LOST ≈ 10; every `STAGE_CHANGED` activity of the brand carries `fromStageEnteredAt`; the two `CALL_LOGGED` entries within the last 7 days. `CrmIndexIsolationIT`: add `POST /reports/pipeline-summary` and `/reports/grid` to the exercised calls (the snapshot assertion then proves reports add no index outside `crm*`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the seeder backdating (one `updateFirst` per deal/activity by collection name, after the services ran; keep the rotting deal's 21-day staleness). Write the README section.
- [ ] **Step 4: Run** `./mvnw -q verify` → green; `./mvnw -q -Pwith-frontend -DskipTests -Dfrontend.dist=<frontend dist> package` builds.
- [ ] **Step 5: Commit** `Backdate the CRM demo data for reports and document them`.

---

## Contract for the console (additions to phase A/B's contract)

All under `/api/workspaces/{ws}/crm/reports`, `POST`, JSON, `crm:view`. Every body may carry `ReportFilter = {from?, to?, tz?, pipelineId?, phoneNumberIds?: string[], ownerIds?: string[]}` (ISO instants; `tz` IANA region or `UTC`). Money fields are numbers with 2 decimals; `currency` is the brand's default.

| Call | Extra request fields | Response |
|---|---|---|
| `/pipeline-summary` | — | `{pipelineId, pipelineName, currency, stages:[{stageId, name, type, openCount, openAmount, weightedAmount}], openCount, openAmount, won:{count, amount}, lost:{count, amount}, winRate: number\|null, averageWonAmount: number\|null, otherCurrencyDeals}` |
| `/deals-over-time` | `bucket?: "DAY"\|"WEEK"\|"MONTH"` | `{bucket, currency, points:[{start, created, won, lost, wonAmount}], otherCurrencyDeals}` |
| `/by-owner` | — | `{currency, rows:[{ownerId, label, openCount, openAmount, wonCount, wonAmount, lostCount, winRate}], otherCurrencyDeals}` |
| `/by-source` | — | `{currency, deals:[{source, label, created, won, wonAmount}], contacts:[{originKind, created}], otherCurrencyDeals}` |
| `/by-number` | — | `{currency, rows:[{phoneNumberId, label, created, won, wonAmount, openAmount}], otherCurrencyDeals}` |
| `/lifecycle-funnel` | — | `{rows:[{key, label, active, count}], total}` (date range ignored) |
| `/activity-summary` | — | `{rows:[{actorId, label, tasksCompleted, notesAdded, stageMoves, dealsWon, dealsLost, calls, meetings, emails}]}` |
| `/stage-duration` | — | `{pipelineId, pipelineName, since: string\|null, stages:[{stageId, name, movesOut, averageDays, medianDays, openNow, averageDaysOpenNow}]}` |
| `/campaigns` | — | `{rows:[{campaignId, name, status, startedAt, numbers:[{phoneNumberId, label}], recipients, sent, delivered, read, replied, failed, crmContactsReached}], truncated}` |
| `/grid` | `entityType: "DEAL"\|"CONTACT"\|"COMPANY"`, `groupBy`, `metric?: "COUNT"\|"SUM"\|"AVG"`, `field?: "amount"\|"custom:<key>"`, `dateBucket?`, `dateField?: "creationTime"\|"closedAt"` | `{entityType, groupBy, metric, field, dateBucket, currency, rows:[{key, label, bucket?, value, count}], truncated, otherCurrencyDeals}` |

Errors: 400 `{code: "INVALID_REPORT_FILTER", message, field}`; 503 `{code: "REPORT_TIMEOUT", message}`; 402/403 as every CRM call.
