# CRM phase C (console): reports — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Reports page to the console: a filter bar, an Overview tab of KPI tiles and nine cards, a Grid tab with a group-by builder, and a small in-house SVG chart set that follows the dataviz rules in light and dark.

**Architecture:** `src/app/Crm/reports/` holds the page, the filter state (in the URL), one component per card, and `charts/` (plain SVG `BarChart`, `LineChart`, `StatTile`, `SegmentedBar` with no CRM imports). Every card loads on its own with `useLoad` keyed on the resolved filter, shows its own spinner, empty and error states, and a Chart/Table toggle. Report calls go through `src/app/api/crm.ts`. No chart library (spec C16).

**Tech Stack:** as phases A/B (React 18, TypeScript, PatternFly 6.5, vitest + Testing Library). **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-10-30-crm-reports-design.md` (§6, C1, C3, C4, C16, C17). **Contract:** "Contract for the console" in `2026-10-30-crm-c-backend.md` — the contract wins over this plan. **Depends on:** `2026-10-30-crm-c-backend.md` merged (or its branch available at `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/.worktrees/crm-c`). Dataviz rules: the `dataviz` skill (`references/marks-and-anatomy.md`, `interaction.md`, `anti-patterns.md`); read them before Task 1.

**Branch:** `crm-c` worktree at `omnichannel-crm-frontend/.worktrees/crm-c` cut from `main`. Commands: `npx vitest run <path>`, `npx tsc --noEmit`, `npm run lint`, `npm run build`. Plan and ledger live in the backend `crm-c` worktree (`.superpowers/sdd/2026-10-30-crm-c-console/`), as in phase B.

## Global Constraints

- Phase A/B Global Constraints apply: CRM code under `src/app/Crm/`, PatternFly tokens only (no raw hex outside `charts/charts.css`), sentence case, phone width (375 px) on every screen, `apiFetch` + `useLoad`/`useAction`, `React.lazy` page chunk, `useRefetchOnFocus`.
- `charts/` imports nothing from `@app/Crm/*` outside `charts/` and nothing from `@app/api` (extend `common/commonImports.test.ts`'s idea with a `charts/chartsImports.test.ts`).
- Chart rules (spec C16, dataviz): horizontal ranked bars for magnitudes, one hue; lines only for time; a legend whenever ≥ 2 series; never a number on every point (direct labels only on the largest/last values); 2 px lines, ≥ 8 px markers, a 2 px gap between adjacent fills; recessive hairline grid; text in PatternFly text tokens; hover tooltips on every chart; every chart `role="img"` with an `aria-label` naming the card and period; the table view is the accessible form and always available.
- Palette: `charts.css` defines `--crm-series-1..8` (light on `:root`: `#2a78d6 #eb6834 #1baf7a #eda100 #e87ba4 #008300 #6250d6 #e34948`; dark under `.pf-v6-theme-dark`: `#3987e5 #d95926 #199e70 #c98500 #d55181 #008300 #9085e9 #e66767`), `--crm-mono: var(--pf-t--color--blue-50)` (dark: `--pf-t--color--blue-40`), `--crm-grid: var(--pf-t--global--border--color--default)`. Series take slots in order 1..n, never cycled; a 9th series folds into "Other".
- Money: `formatMoney(amount, null, currency)` from `Crm/common/money.ts`; `otherCurrencyDeals > 0` shows "+ n deals in other currencies" under the card.
- Dates sent as ISO instants; `tz` = `Intl.DateTimeFormat().resolvedOptions().timeZone`; presets are day-aligned in that zone (spec C17): "Last N days" = `[start of today − (N−1) days, start of tomorrow)`; "This month" = `[1st 00:00, start of tomorrow)`; "Last month" = the whole previous month; "This quarter" = `[quarter start, start of tomorrow)`; Custom = `[start of from-day, start of the day after to-day)`. The range must be ≤ 731 days (client check with the same message as the server: "Pick a range of at most two years.").
- URL query: `range` (preset key or `custom`), `from`/`to` (yyyy-MM-dd, custom only), `pipelineId`, `numbers` (comma-separated), `owner`; the page reads them on load and writes them with `navigate(..., {replace: true})` on change.
- Commits: one per task, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. **Theme switch while the page is open**: charts recolour with the theme (CSS variables, no cached hex in React state) (Task 1 test `seriesColoursComeFromCssVariables`).
2. **A card's request failing while the others succeed**: only that card shows an error with Retry; the others render (Task 3 test `oneFailingCardDoesNotBlankTheOthers`).
3. **Stale responses after a fast filter change**: the card shows the result of the latest filter only (`useLoad` keying) (Task 3 test `fastFilterChangeShowsLatestOnly`).
4. **A restricted user with no accessible pipeline**: the page shows the "No pipeline assigned to you" empty state and makes no report calls (Task 2 test `noPipelineMakesNoReportCalls`).
5. **Keyboard-only use of the Chart/Table toggle and the grid builder**: every control is reachable and labelled (Task 4 test `gridBuilderIsKeyboardOperable`, Task 3 `toggleHasAccessibleName`).

---

### Task 1: Chart components

**Files:**
- Create: `src/app/Crm/reports/charts/charts.css`, `BarChart.tsx`, `LineChart.tsx`, `StatTile.tsx`, `SegmentedBar.tsx`, `chartText.ts` (number/date tick formatting), `charts.test.tsx`, `chartsImports.test.ts`

**Interfaces:**
- `BarChart({ title: string; period?: string; rows: Array<{ key: string; label: string; value: number; hint?: string }>; format(v: number): string; series?: 'mono' | number; maxBars?: number })` — horizontal bars, largest first unless `keepOrder`, 1 px rounded data-end, a label column (ellipsis at 18 ch, full text in the tooltip), a value label on the top 3 bars only, a tooltip (`<title>` plus a hover `div`) per bar, `role="img"`, `aria-label={`${title}${period ? `, ${period}` : ''}`}`; empty rows → `null` (the card decides what to show).
- `LineChart({ title; period?; series: Array<{ key; label; points: Array<{ x: Date; y: number }> }>; formatY(v): string; formatX(d: Date): string })` — up to 8 series (slot n → `--crm-series-n`), 2 px lines, markers ≥ 8 px only on hover, a crosshair that follows the pointer and a tooltip listing every series' value at that x, a legend row under the chart (always for ≥ 2 series; for one series the title names it), hairline grid on the y axis only.
- `StatTile({ label; value: string; sub?: string })` — the KPI tile: label in `--pf-t--global--text--color--subtle`, value at `--pf-t--global--font--size--3xl`.
- `SegmentedBar({ parts: Array<{ key; label; value: number }>; total: number; ariaLabel: string })` — a thin segmented bar (height 8 px, 2 px gaps), slots in order, no text on it; the row beside it carries the figures.
- `chartText.compact(n)` ("1.2k", "3.4M"), `chartText.day(d, tz)`, `week(d)`, `month(d)`.

- [ ] **Step 1: Failing tests.** `charts.test.tsx`: `barChartRanksAndLabelsTopThree` (6 rows → 6 `rect`s in value order, exactly 3 value texts), `barChartKeepsOrderWhenAsked`, `barChartTooltipShowsFullLabelAndHint`, `lineChartDrawsOneSeriesPathEach` (3 series → 3 `path`s with `stroke: var(--crm-series-1..3)`), `lineChartLegendForTwoOrMoreSeries` (1 series → no legend; 2 → legend with both labels), `lineChartCrosshairTooltipListsEverySeries` (pointer move → tooltip text contains each series label and value), `seriesColoursComeFromCssVariables` (no `#` hex in any `fill`/`stroke` attribute of rendered SVG), `segmentedBarGapsAndOrder`, `statTileRendersLabelValueSub`, `chartsHaveAccessibleNames` (`role="img"` + `aria-label` with the period). `chartsImports.test.ts`: no import from `@app/Crm/` (except `./`) or `@app/api/` in `charts/*.tsx`.
- [ ] **Step 2: Run** `npx vitest run src/app/Crm/reports/charts` → FAIL (modules missing).
- [ ] **Step 3: Implement.** SVG with a `viewBox` and `width="100%"`, heights from row count (bars: 28 px per row + axes; lines: 220 px). Fonts/colours via CSS variables only. Hover handled with React state and `onPointerMove`; hit targets are transparent `rect`s wider than the marks.
- [ ] **Step 4: Run** → PASS; `npx tsc --noEmit`; `npm run lint`.
- [ ] **Step 5: Commit** `Add the CRM report chart components`.

### Task 2: Report API, filter bar and the page shell

**Files:**
- Create: `src/app/Crm/reports/ReportsPage.tsx`, `ReportFilterBar.tsx`, `reportFilter.ts` (presets, URL ↔ state, body builder), `reportFilter.test.ts`, `reportsPage.test.tsx`, `ReportCard.tsx` (title, period, toggle, states, money note)
- Modify: `src/app/api/crm.ts` (types `ReportFilter`, `PipelineSummary`, `DealsOverTime`, `ByOwner`, `BySource`, `ByNumber`, `LifecycleFunnel`, `ActivitySummary`, `StageDuration`, `CampaignsReport`, `GridReport`, `GridRequest`; calls `reportPipelineSummary(token, ws, body)` … `reportGrid(token, ws, body)` — each `send<T>(token, 'POST', `${base(ws)}/reports/<path>`, body)`), `src/app/Crm/routes.tsx` (`/crm/reports`), `src/app/routes.tsx` (CRM link "Reports" after Companies), `src/test/crm.ts` (fixtures `sampleReportFilterBody`, `samplePipelineSummary`, `sampleDealsOverTime`, …, one per response)

**Interfaces:**
- `reportFilter.ts`: `type Preset = 'last7' | 'last30' | 'last90' | 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'custom'`; `PRESETS: Array<{key: Preset; label}>` (labels "Last 7 days", "Last 30 days", "Last 90 days", "This month", "Last month", "This quarter", "Custom range"); `rangeOf(preset, now: Date, tz: string, custom?: {from: string; to: string}): {from: Date; to: Date} | null`; `periodLabel(range, tz): string` ("1 Aug – 29 Oct 2026"); `type ReportState = {preset; from?; to?; pipelineId: string; numbers: string[]; ownerId: string}`; `fromSearch(params: URLSearchParams, defaults): ReportState`; `toSearch(state): URLSearchParams`; `bodyOf(state, tz): ReportFilter` (ISO `from`/`to`, `tz`, `pipelineId`, `phoneNumberIds`, `ownerIds`); `MAX_RANGE_DAYS = 731`.
- `ReportFilterBar({ state; onChange; pipelines: Pipeline[]; phones: CrmPhone[]; owners: Ref[]; canPickOwner: boolean; onRefresh })` — a `Toolbar`: preset `FormSelect` ("Range"), two `DatePicker`s when custom ("From", "To"), pipeline `FormSelect` ("Pipeline"), numbers multi `Select` with checkboxes ("Numbers", toggle text "All numbers" / "n numbers"), owner `FormSelect` ("Owner": "Everyone", "Me", people) only when `canPickOwner`, Refresh button; a validation line when the custom range is bad.
- `ReportCard({ title; period; loading; error; onRetry; empty: boolean; emptyText?; otherCurrencyDeals?; chart: ReactNode; table: ReactNode; tableOnly?: boolean; note?: string })` — `Card` with the toggle (`ToggleGroup` "Chart"/"Table", `aria-label="View of <title>"`), remembers the choice per card id in `localStorage` (`crm.reports.view.<id>`, try/catch), spinner/`Alert`/empty state.
- `ReportsPage`: `useCrmStatus()` for phones/access/authorities; `listPipelines` (accessible, active first); owners from `useBrandUsers()` plus "Me"; `canPickOwner = can('crm:view-all')`; `tz` once per mount; state from the URL; `Tabs` Overview/Grid (`mountOnEnter`); no pipeline accessible → `EmptyState` "No pipeline assigned to you. Ask your admin." and no report calls; `useRefetchOnFocus` bumps a `tick` that every card includes in its `useLoad` key.

- [ ] **Step 1: Failing tests.** `reportFilter.test.ts`: `last7IsDayAlignedInZone` (now = 2026-10-30T10:00Z, tz Asia/Kolkata → from 2026-10-24T00:00+05:30, to 2026-10-31T00:00+05:30), `thisMonthAndLastMonth`, `thisQuarter`, `customRangeInclusiveOfToDay`, `customOverMaxIsNull`, `urlRoundTrip`, `bodyOfSendsIsoAndZone`. `reportsPage.test.tsx`: `navShowsReportsWhenCrmOn` (link "Reports" → `/crm/reports`, `hidden: true` as the nav test idiom), `defaultsToLast90AndDefaultPipeline` (every card's request body has `tz` = the stubbed zone, `pipelineId` = `p1`, `from`/`to` 90 days apart on day boundaries), `filtersLiveInTheUrl` (choose Last 7 days and Support → URL has `range=last7&pipelineId=p2`; reload with that URL restores them), `ownerPickerOnlyWithViewAll`, `noPipelineMakesNoReportCalls` (`pipelineAccess: []` → empty state, zero `/reports/` requests), `refreshRefetchesEveryCard`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (cards in this task are placeholders rendering `ReportCard` with the raw JSON of each call in the table slot — Task 3 replaces them). **Step 4: Run** → PASS; `tsc`; `lint`.
- [ ] **Step 5: Commit** `Add the CRM Reports page, filter bar and report API`.

### Task 3: Overview cards

**Files:**
- Create: `src/app/Crm/reports/cards/KpiRow.tsx`, `PipelineByStageCard.tsx`, `DealsOverTimeCard.tsx`, `ByOwnerCard.tsx`, `BySourceCard.tsx`, `ByNumberCard.tsx`, `LifecycleFunnelCard.tsx`, `ActivityCard.tsx`, `StageDurationCard.tsx`, `CampaignsCard.tsx`, `overview.test.tsx`
- Modify: `ReportsPage.tsx` (Overview tab = `KpiRow` + a `Gallery` of the nine cards, `minWidths={{ default: '100%', md: '420px' }}`)

**Interfaces:** each card is `({ body: ReportFilter; tick: number })`, loads with `useLoad((t) => report…(t, ws, body), [ws, JSON.stringify(body), tick])`, and renders through `ReportCard`:
- `KpiRow`: four `StatTile`s from `/pipeline-summary` — "Open pipeline" (`openAmount`, sub `${openCount} deals`), "Won in period" (`won.amount`, sub `${won.count} deals`), "Win rate" (`winRate` as `NN%` or "—"), "Average won deal" (`averageWonAmount` or "—"); its own spinner/error line.
- `PipelineByStageCard`: `BarChart` of `stages` in lane order (`keepOrder`), value `openAmount`, hint `${openCount} open · weighted ${weighted}`; table Stage / Open / Open amount / Weighted.
- `DealsOverTimeCard`: `LineChart` series Created, Won, Lost (slots 1–3) over `points` (`x = start`); x formatter by `bucket`; table Period / Created / Won / Lost / Won amount; subtitle "by day|week|month".
- `ByOwnerCard`: `BarChart` of `wonAmount` ranked (hint `${wonCount} won · ${lostCount} lost · win rate`); table Owner / Open / Open amount / Won / Won amount / Lost / Win rate.
- `BySourceCard`: `BarChart` of deals `won` ranked (hint created/won amount); under it a compact `Table` "Contacts by origin" (label from a map: INBOUND_CHAT "Inbound chat", CTWA_AD "Click-to-WhatsApp ad", FACEBOOK_LEAD "Facebook lead", WEBSITE_LEAD "Website lead", MANUAL "Added by hand", IMPORT "Imported", API "API"); table view shows both.
- `ByNumberCard`: `BarChart` of `wonAmount` ranked (hint created/won/open amount); table.
- `LifecycleFunnelCard`: `BarChart` with `keepOrder` (picklist order), note "Snapshot of contacts now; the date range does not apply."; table Status / Contacts.
- `ActivityCard`: `tableOnly`; columns Person / Tasks done / Notes / Moves / Won / Lost / Calls / Meetings / Emails.
- `StageDurationCard`: `BarChart` `keepOrder` of `averageDays` (hint `median ${medianDays} d · ${movesOut} moves · ${openNow} open now, avg ${averageDaysOpenNow} d`), note "Based on moves since <date>" or "No moves recorded yet."; table.
- `CampaignsCard`: `tableOnly`; columns Campaign / Started / Numbers / Recipients / Delivery (a `SegmentedBar` sent/delivered/read/failed with the four figures beside it) / CRM contacts reached; `truncated` → note "Showing the latest 200".

- [ ] **Step 1: Failing tests** in `overview.test.tsx` (mock every `/reports/*` route with the fixtures): `kpiRowShowsMoneyAndRate`, `pipelineByStageKeepsLaneOrder` (bars in stage order, not value order), `dealsOverTimeHasThreeSeriesAndLegend`, `byOwnerTableMatchesRows`, `bySourceShowsContactOrigins`, `lifecycleFunnelSaysSnapshot`, `stageDurationSaysSince`, `campaignsRowShowsDeliveryFigures`, `otherCurrencyNoteShown` (`otherCurrencyDeals: 2` → "+ 2 deals in other currencies"), `oneFailingCardDoesNotBlankTheOthers` (`/by-owner` 500 → that card shows Retry; the pipeline card still renders; Retry refetches), `fastFilterChangeShowsLatestOnly` (first response delayed; change the preset; the card shows the second response's figure and never the first), `toggleHasAccessibleName` (every toggle `aria-label` starts "View of"), `tableViewIsRememberedPerCard` (toggle a card to Table, remount → still Table; other cards unaffected).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `tsc`; `lint`.
- [ ] **Step 5: Commit** `Add the CRM report overview cards`.

### Task 4: Grid tab

**Files:**
- Create: `src/app/Crm/reports/GridTab.tsx`, `gridFields.ts` (the allowlist with labels per entity, custom-field mapping), `grid.test.tsx`
- Modify: `ReportsPage.tsx` (Grid tab)

**Interfaces:**
- `gridFields.ts`: `GROUP_BY: Record<'DEAL'|'CONTACT'|'COMPANY', Array<{key; label}>>` (deal: pipeline "Pipeline", stage "Stage", status "Status", owner "Owner", source "Source", number "WhatsApp number", currency "Currency", lostReason "Lost reason"; contact: lifecycle "Lifecycle", owner, source, origin "Origin", number; company: industry "Industry", size "Size", owner); `customGroupOptions(defs)` = active SELECT/BOOLEAN/USER/TEXT as `{key: 'custom:'+key, label}`; `customValueOptions(defs)` = active NUMBER/CURRENCY; `METRICS` COUNT "Count", SUM "Sum of", AVG "Average of"; `BUCKETS` none/DAY/WEEK/MONTH.
- `GridTab({ body: ReportFilter; tick })`: `FormSelect`s "Record type", "Group by" (built-ins then an "Custom fields" `FormSelectOptionGroup`), "Metric", "Field" (when SUM/AVG: "Amount" for deals + numeric custom fields), "Date bucket", "Date field" (deals only; creationTime "Created", closedAt "Closed"); state in the URL under `g.entity`, `g.by`, `g.metric`, `g.field`, `g.bucket`, `g.date`; a "Stage needs a pipeline" helper when `stage` is chosen without one; `useFieldDefinitions(entity)` for custom options; result in a `ReportCard` "Grid": `BarChart` (ranked, `keepOrder` when `groupBy` is `stage` or `lifecycle`) or, when bucketed, `LineChart` with one series per group (top 8 by total, rest folded into "Other" client-side); the table has Group / (Period) / Value / Count and an "Other" row when `truncated`.

- [ ] **Step 1: Failing tests** in `grid.test.tsx`: `defaultsToDealCountByPipeline`, `stageNeedsPipelineHint`, `sumShowsFieldPicker` (SUM → "Field" with "Amount" and the CURRENCY custom field; COUNT hides it), `switchingEntityResetsGroupBy` (DEAL → CONTACT keeps a valid `groupBy`), `bucketedResultDrawsLines`, `otherRowWhenTruncated`, `gridBuilderIsKeyboardOperable` (tab through the six selects and the toggle; each has a label), `gridStateInUrl`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `npx vitest run`, `npx tsc --noEmit`, `npm run lint`, `npm run build` → green.
- [ ] **Step 5: Commit** `Add the CRM grid report`.
- [ ] **Step 6: Acceptance walk** (needs a running backend with demo data, as `demo-manager` and `demo-agent`): spec §9 items 1–9, in light, dark and at 375 px. Record what was checked in the ledger; what could not be checked is reported, not assumed.
