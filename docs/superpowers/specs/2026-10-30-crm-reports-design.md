# CRM phase C: reports (design)

**Status:** requested 2026-10-30 ("Skip the questions, plan reports first"). The user skipped the design questions,
so every call in §3 is the designer's and is open to change. Builds on the port design
`2026-10-23-crm-port-design.md` (phases A and B, merged into `main`); where this document disagrees with it, this one
wins. Source of the feature set: the requirements document `/Users/nagesh/RedLava/SCM/CRM/crm-addon-requirements.md`
§6.12, and the reference CRM's phase 4 (`/Users/nagesh/RedLava/SCM/CRM/whatsapp/docs/superpowers/specs/2026-10-27-crm-phase4-reports-design.md`,
code `/Users/nagesh/RedLava/SCM/CRM/redlava-crm/src/main/java/com/redlava/crm/report`). Port the rules there where
this document is silent, translated with the port design's §3 table (customer→workspace, username→user id,
phone→number, antd→PatternFly).

## 1. Intent

Phases A and B let a team run deals and follow-ups. Nobody can yet answer a manager's questions without counting by
hand: how much is in the pipeline and where, what was won or lost this month, who is closing, where deals come from,
which WhatsApp number performs, how many contacts become customers, how busy each agent is, where deals get stuck,
and whether the campaigns sent to CRM contacts landed. This phase answers those from data the CRM and the host
already hold, on one Reports page, plus a "group by" grid for the questions the fixed cards do not cover.

## 2. Decisions carried from the port design (binding)

| # | Decision |
|---|---|
| D1 | Reports are read-only. The CRM module computes them from `crm*` collections and, for the campaigns card, the host's campaign data through a new `crm.platform` port. Nothing is written except the one activity-payload field in C12. |
| D2 | Access as in phase A: the pipeline is the unit of access for restricted users (`CrmContext.pipelines()`); companies are brand-wide; numbers are channels, never an access scope. Every query carries `workspaceId` first and fails closed. |
| D3 | No live updates. The page refreshes on demand and when the tab regains focus. |
| D4 | Module boundary: `crm.report` imports only `crm.*`, `common.*`, `security.access`, `security.auth`. Host data comes through `crm.platform` ports implemented in `omnichannel-app` `app.crm`. |
| D5 | Console rules as before: PatternFly 6 tokens only, light and dark, phone width, sentence case, `apiFetch` + `useLoad`/`useAction`, `React.lazy` page chunk. |
| D6 | Jobs, errors and paging conventions as phase A/B: errors are `{code, message, ...extras}`; `CodedException` via `CrmErrors`. |

## 3. Calls made by the designer (flagged; change if wrong)

| # | Call | Why |
|---|---|---|
| C1 | **A new CRM nav entry, Reports (`/crm/reports`)**, after Companies and before Settings. Two tabs: **Overview** (the fixed cards) and **Grid** (the group-by builder). Pipelines keeps its name. | Same shape as the reference; "Dashboard" already means the host's home page. |
| C2 | **Who sees what.** Anyone with `crm:view` can open Reports; no new authority. Every figure is computed over what the caller may see: deals in accessible pipelines; contacts whose `pipelineIds` meet the access (`pipelineIds` is already maintained by phase A); companies brand-wide; activities stamped with an accessible `pipelineId`. For restricted callers, activities with no `pipelineId` are **excluded** (unlike the timeline), because a report would otherwise count other teams' work. | The same rule as every list; a restricted agent's report is their slice. |
| C3 | **Shared filter on every report:** `from`/`to` (ISO instants; default the last 90 days ending now; at most 731 days; `from < to`), `tz` (IANA zone, the browser's; decides day/week/month boundaries), optional `pipelineId`, optional `phoneNumberIds[]` (narrows by the record's `phoneNumberId` / `channel.phoneNumberId`), optional `ownerIds[]` (user ids; narrows by `ownerId`, and by `actorId` on activities). Stage-based reports (pipeline summary, stage duration) need one pipeline: the named one when accessible, else the brand's default when accessible, else the first accessible one, else 400. Any invalid value → 400 `INVALID_REPORT_FILTER {field}`. | One filter bar drives the whole page. |
| C4 | **Money.** Sums are grouped by `currency`; a deal without a currency counts in the settings' `defaultCurrency` (else `INR`); a deal without an amount counts as 0 in sums and normally in counts. Each money-bearing response names its `currency` (the default) and `otherCurrencyDeals` (how many matched deals were in other currencies); the UI shows "+ n deals in other currencies". Weighted value = amount × probability / 100 with the deal's probability, else its stage's, else 0; 2 decimals. Amounts are returned as numbers (JSON) with 2 decimals, computed from `Decimal128`. | Adding rupees to dollars is wrong; most brands use one currency. |
| C5 | **Pipeline summary** (one pipeline): per stage in lane order — open count, open amount, weighted amount; for the period (by `closedAt`) — won count/amount, lost count/amount, win rate = won ÷ (won + lost) (null when both 0, 3 decimals), average won amount (null when no wins). The KPI row comes from this report: open value (sum of open amounts), won in period, win rate, average won deal. | The manager's first question. |
| C6 | **Deals over time:** per bucket — created (`creationTime`), won and lost (`closedAt` with the status), won amount. The bucket is chosen from the range unless `bucket` is given: ≤ 31 days → `DAY`, ≤ 182 → `WEEK` (Monday start), else `MONTH`. Buckets are computed in `tz` with `$dateTrunc`; empty buckets are zero-filled from the bucket containing `from` to the last one before `to`. | Trend at a glance without a setting to fiddle with. |
| C7 | **By owner:** per `ownerId` (no owner → key `""`, label "Unassigned") — open count/amount now, won count/amount and lost count in the period, win rate. Labels through `PeopleDirectory.people`; an unknown id is labelled "Unknown person" (the console's `personName` rule). | Who is closing. |
| C8 | **By source:** deals by `source` (label from the `dealSources` picklist; blank → "Unspecified"; an unknown key → the key) with created and won counts and won amount in the period; and contacts by `origin.kind` created in the period (every `OriginKind` listed, zero-filled, in enum order). | Where business comes from. |
| C9 | **By number:** deals per `channel.phoneNumberId` — created and won in the period, won amount, open amount now; labelled with the number's label, else its number (`NumberDirectory.numbers`); an id no longer in the brand is labelled "Removed number". | Which number performs. |
| C10 | **Lifecycle funnel:** a snapshot of contacts per `lifecycleStatus` **now**, in picklist order (inactive statuses after active ones, then "Not set"); it ignores the date range (the CRM keeps no lifecycle history) and the card says so. Owner, number and pipeline filters still apply. | Honest about what the data can show. |
| C11 | **Activity summary:** per actor (`actorType` USER only, `actorId` ≠ `"system"`) in the period — tasks completed, notes added, stage moves, deals won, deals lost, calls, meetings, emails (activity types `TASK_COMPLETED`, `NOTE_ADDED`, `STAGE_CHANGED`, `DEAL_WON`, `DEAL_LOST`, `CALL_LOGGED`, `MEETING_LOGGED`, `EMAIL_LOGGED`). Rows sorted by tasks completed + stage moves + deals won descending, then name. | How busy each agent is, from the timeline the CRM already writes. |
| C12 | **Stage duration** needs data the CRM does not keep yet: from this phase on every `STAGE_CHANGED` activity records `payload.fromStageEnteredAt` (the deal's `stageEnteredAt` before the move) — board moves, deal edits that change the pipeline, stage removals and pipeline deletions that relocate deals. The report (one pipeline; moves in the period by `happenedAt`) gives per stage: moves out, average and median days spent (1 decimal; null when none), and for OPEN deals now: count and average days in their current stage (`stageEnteredAt`). A move out of this pipeline counts for its `fromStageId` when `payload.fromPipelineId` is this pipeline, or when it is absent and the activity's `pipelineId` is this pipeline. Entries without `fromStageEnteredAt` are left out; the response carries `since` = the earliest counted move, and the card says "based on moves since <date>" (or "no moves recorded yet"). | A small write-side change instead of a fragile replay of the whole timeline. |
| C13 | **Campaigns card** (the requirements' FR-REP-10, done now because the host already has campaigns): campaigns of the brand **started** in the period, with per campaign: name, status, number(s) used, recipients total, sent, delivered, read, replied, failed, and how many of its recipients are CRM contacts the caller may see. Data comes through a new port `CampaignDirectory.campaigns(workspaceId, from, to)` returning `PlatformCampaign{id, name, status, startedAt, senderPhoneNumberIds, counts{total, sent, delivered, read, replied, failed}, recipientContactIds}`; the CRM side intersects `recipientContactIds` with the caller's visible CRM contacts (`crmContacts.contactId` in access) to compute "CRM contacts reached". Restricted callers see only campaigns with at least one visible CRM contact. At most 200 campaigns per call (newest first; `truncated` flag). No segment exists yet, so there is no segment column; the field is reserved for the segments phase. | "Did it land?" where people already look, without waiting for segments. |
| C14 | **Grid report:** `entityType` DEAL / CONTACT / COMPANY; `groupBy` from an allowlist — deal: `pipeline`, `stage` (needs `pipelineId`), `status`, `owner`, `source`, `number`, `currency`, `lostReason`; contact: `lifecycle`, `owner`, `source`, `origin`, `number`; company: `industry`, `size`, `owner` — or `custom:<key>`, an **active** single-valued custom field of that entity (SELECT, BOOLEAN, USER, TEXT). `metric` COUNT (default), SUM or AVG over `field` = `amount` (deals) or `custom:<key>` of type NUMBER or CURRENCY. Optional `dateBucket` DAY/WEEK/MONTH over `dateField` = `creationTime` (default) or `closedAt` (deals only); the date range applies to the same field; without `dateBucket` the range still applies. At most 100 groups (by value, then count, descending); the rest fold into one row `key: "__other"`, label "Other", with `truncated: true`. Money metrics are per currency as in C4. Labels: pipeline/stage names, picklist labels, people names, number labels, `OriginKind`/status/currency as is, custom SELECT option labels, BOOLEAN "Yes"/"No", null → "Not set". Unknown `groupBy`/`field`/`dateField` → 400 `INVALID_REPORT_FILTER`. | Covers "how many deals per city", "average deal by source" without free-form queries. |
| C15 | **Performance.** Each report is one or two aggregations whose first `$match` is `workspaceId` + access + the date range; `maxTime` 10 s → 503 `REPORT_TIMEOUT`; no caching. New indexes: `crmDeals {workspaceId, creationTime}`, `crmActivities {workspaceId, type, happenedAt}`, `crmContacts {workspaceId, creationTime}`. Every index leads with `workspaceId` (the isolation IT keeps checking). MongoDB 7.0 (already required) supports `$dateTrunc` with a timezone. | Fast enough for brand-sized data; no new moving parts. |
| C16 | **Charts are drawn by a small in-house SVG component set** (`src/app/Crm/reports/charts/`: `BarChart` (horizontal, ranked), `LineChart` (multi-series with a crosshair tooltip), `StatTile`, `SegmentedBar`), **no chart library**: `@patternfly/react-charts` needs PatternFly 6.7 and ~20 Victory (or echarts) packages for four chart forms the console can draw in ~400 lines; a dependency that size is not worth it for this page. Chart forms follow the dataviz rules: KPI row of stat tiles; pipeline by stage = horizontal bars, one hue; deals over time = multi-line (created, won, lost) with a legend and a crosshair tooltip; by owner / by source / by number = ranked horizontal bars, one hue, plus a table; lifecycle funnel = horizontal bars in picklist order; activity summary = table only; stage duration = horizontal bars (average days) with the median in the tooltip and a table; grid = bars (one hue) or, when bucketed, lines per group capped at 8 series with the rest folded into "Other"; campaigns = table with a thin segmented bar per row (sent/delivered/read/failed). Every card has a **Chart / Table** toggle; the table is the accessible form. Mark rules: thin marks, 2px lines, ≥ 8px markers, a 2px surface gap between adjacent fills, selective direct labels (never every point), recessive hairline grid, text in PatternFly text tokens. Categorical colours are the dataviz reference palette (light `#2a78d6,#eb6834,#1baf7a,#eda100,#e87ba4,#008300,#6250d6,#e34948`; dark `#3987e5,#d95926,#199e70,#c98500,#d55181,#008300,#9085e9,#e66767`), already validated with the dataviz validator on PatternFly's surfaces (`#ffffff` light, `#151515` dark); series take slots in fixed order, never cycled; the console's lane palette fails that validator and is **not** used for charts. Single-hue charts use PatternFly blue (`--pf-t--color--blue-50` light, `--pf-t--color--blue-40` dark). The palette lives in one CSS block (`src/app/Crm/reports/charts/charts.css`) as `--crm-series-1..8` with the light values on `:root` and the dark values under `.pf-v6-theme-dark` (the class the console's theme store applies; the OS setting is already folded into that class). Charts carry the hover layer by default (crosshair + tooltip on lines, per-mark tooltip on bars); hit targets are larger than the marks. | Charts the team can read, including with colour-vision deficiency and in dark mode, without a 20-package dependency. |
| C17 | **Filter bar:** date presets (Last 7 days, Last 30 days, Last 90 days (default), This month, Last month, This quarter, Custom) that are **day-aligned in the browser's zone** (a preset covers whole days: Last 7 days = the 7 days ending today, `to` = start of tomorrow); pipeline select (accessible pipelines, default pipeline preselected); numbers multi-select (the brand's numbers); owner select (people seen on loaded data plus the brand user list, `crm:view-all` only). Changing a filter reloads every card; each card loads on its own with its own empty and error states. The bar is sticky at the top of the page on wide screens and plain at phone width. | One filter bar, honest about day boundaries. |
| C18 | **No new write paths** beyond C12, no export of reports, no saved grid reports, no scheduling. | Scope. |

## 4. Data model

No new collection. One write-side change: `crmActivities` entries of type `STAGE_CHANGED` gain
`payload.fromStageEnteredAt` (Instant) when the deal had a `stageEnteredAt` before the move (C12); `Placement.recordMove`
already receives the pre-move deal in every path (board move, close, deal edit, stage and pipeline relocation), so it
is one `payload.put` there. Three new indexes
(C15) declared with `@CompoundIndex` on the documents, names `ws_created` (deals, contacts) and `ws_type_happened`
(activities).

One new port in `crm.platform`: `CampaignDirectory` (C13), implemented by `app.crm.CampaignDirectoryAdapter` over the
host's `campaigns` and `campaignRecipients` collections through `MongoTemplate` (the recipient repository has no
finders): campaigns by `workspaceId` with `startedAt` in range, newest first, at most 200; per campaign the `counts`
record as stored, and the distinct recipient `contactId`s (non-null) read with a projection. The port returns the
numbers used through `senderIds` → `campaignSenders.phoneNumberId`.

## 5. API (`/api/workspaces/{ws}/crm/reports`, all `POST`, JSON; `crm:view`)

Every body extends `ReportFilter {from, to, tz, pipelineId?, phoneNumberIds?, ownerIds?}`. Money fields are numbers
with 2 decimals; `currency` is the brand's default; `otherCurrencyDeals` counts matched deals in other currencies.

| Endpoint | Returns |
|---|---|
| `/pipeline-summary` | `{pipelineId, pipelineName, currency, stages:[{stageId, name, type, openCount, openAmount, weightedAmount}], openCount, openAmount, won:{count, amount}, lost:{count, amount}, winRate, averageWonAmount, otherCurrencyDeals}` |
| `/deals-over-time` (+ `bucket?` DAY/WEEK/MONTH) | `{bucket, currency, points:[{start, created, won, lost, wonAmount}], otherCurrencyDeals}` |
| `/by-owner` | `{currency, rows:[{ownerId, label, openCount, openAmount, wonCount, wonAmount, lostCount, winRate}], otherCurrencyDeals}` |
| `/by-source` | `{currency, deals:[{source, label, created, won, wonAmount}], contacts:[{originKind, created}], otherCurrencyDeals}` |
| `/by-number` | `{currency, rows:[{phoneNumberId, label, created, won, wonAmount, openAmount}], otherCurrencyDeals}` |
| `/lifecycle-funnel` | `{rows:[{key, label, active, count}], total}` |
| `/activity-summary` | `{rows:[{actorId, label, tasksCompleted, notesAdded, stageMoves, dealsWon, dealsLost, calls, meetings, emails}]}` |
| `/stage-duration` | `{pipelineId, pipelineName, since, stages:[{stageId, name, movesOut, averageDays, medianDays, openNow, averageDaysOpenNow}]}` |
| `/campaigns` | `{rows:[{campaignId, name, status, startedAt, numbers:[{phoneNumberId, label}], recipients, sent, delivered, read, replied, failed, crmContactsReached}], truncated}` |
| `/grid` (+ `entityType, groupBy, metric?, field?, dateBucket?, dateField?`) | `{entityType, groupBy, metric, field, dateBucket, currency, rows:[{key, label, bucket?, value, count}], truncated, otherCurrencyDeals}` |

Errors: 400 `INVALID_REPORT_FILTER {field}` (also for an inaccessible pipeline named in the filter); 503
`REPORT_TIMEOUT`; 402/403 as every CRM endpoint.

## 6. Console (`src/app/Crm/reports/`)

- **Route** `/crm/reports` (lazy chunk), nav link "Reports" after Companies. Page title "Reports".
- **Filter bar** (C17) with a Refresh button; the chosen preset, pipeline, numbers and owner live in the URL query
  (`range`, `from`, `to`, `pipelineId`, `numbers`, `owner`) so a reload and the back button keep them.
- **Overview tab:** KPI row (Open pipeline, Won in period, Win rate, Average won deal; stat tiles with the period
  under each), then cards in a responsive grid (`Gallery`, one column at phone width): Pipeline by stage, Deals over
  time, By owner, By source (deals; a small table of contact origins underneath), By number, Lifecycle funnel ("now"),
  Activity per person (table), Stage duration ("based on moves since …"), Campaigns. Each card: title, period line,
  Chart/Table toggle (table only where the spec says so), its own spinner, empty state ("Nothing in this period") and
  error state with Retry, and the "+ n deals in other currencies" note when `otherCurrencyDeals > 0`.
- **Grid tab:** record type, group by (built-ins first, then "Custom fields" with the active single-valued ones),
  metric (+ field for SUM/AVG), date bucket (none/day/week/month), date field (deals only); a bar chart (or lines when
  bucketed) and a table; an "Other" row when truncated; the money note as above.
- **Accessibility:** every chart has an `aria-label` naming the card and the period; the table view is the accessible
  form; the series legend is always present for ≥ 2 series; status is never shown by colour alone.
- **Tests:** vitest with mocked fetch as phase A/B. The chart components are plain SVG, so tests can assert on
  rendered `<rect>`/`<path>` counts, `aria-label`s and tooltips; page tests assert on the table view and on the
  request bodies.

## 7. Demo data

`CrmDemoSeeder` (Demo Fashion) backdates its records so the Overview has something to show on a fresh seed: deal
creation times spread over the last 60 days, the won and lost deals closed 3 and 10 days ago, stage moves dated when
the deal was created plus a few days (with `fromStageEnteredAt`), and the two logged calls in the last week. Dates are
written directly by the seeder after the services have created the records (as phase B did for the rotting deal).

## 8. Out of scope (explicit)

Segment metrics (the segments phase adds a segment column to the campaigns card); exporting reports to CSV or PDF;
scheduled or emailed reports; saving grid reports; targets, quotas and forecasting; lifecycle history; cross-brand
reports; caching or precomputed rollups; live refresh.

## 9. Acceptance (end to end, as `demo-manager` on the seeded brand)

1. Reports shows in the CRM nav; the Overview loads with the last 90 days and the Sales pipeline preselected.
2. The KPI row and Pipeline by stage match the board's lane totals; Won in period counts the seeded won deal.
3. Deals over time shows weekly buckets with zero-filled weeks; switching to Last 7 days switches to days.
4. By owner lists Demo Manager and Demo Agent with the right open counts; the owner filter narrows every card.
5. Stage duration says "based on moves since <seed date>" and shows days for the stages the seeder moved through.
6. The campaigns card lists the demo campaign(s) with delivery counts, or an empty state when none ran.
7. Grid: deals by stage (with the pipeline), deals by source with Sum of amount per month, contacts by lifecycle,
   companies by industry; a BOOLEAN custom field groups as Yes/No.
8. As `demo-agent` (Sales only): every card shows only Sales data; the owner select is absent.
9. Light, dark, and 375 px width: cards stack, charts fit, the table view is readable.
