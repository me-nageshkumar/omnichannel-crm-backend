# Campaigns (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brand users can create, start and watch campaigns, manage the sending numbers and the pacing settings, and see campaign traces in contacts and the inbox.

**Architecture:** One API module (`api/campaigns.ts`) with every type and call, a `Campaigns/` folder holding five pages (list, new-campaign wizard, detail, senders, settings), and pure helpers for the decisions tests should pin (placeholder mapping, settings warnings, estimate text). The detail page refreshes through one hook, `useCampaignProgress`, so polling can later be swapped for a stream. The template list in `TemplatePickerModal` is extracted into `TemplateSearchList` and reused by the wizard.

**Tech Stack:** React 18 + TypeScript (strict), PatternFly 6.5.1 (`Wizard`, `WizardStep`, `WizardFooterWrapper`, `useWizardContext`, `Progress`, `FileUpload` from `@patternfly/react-core`; `@patternfly/react-table`), React Router 7, Vitest + Testing Library (`mockFetch`, `requestTo`, `callsTo` in `src/test/fetchMock.ts`; `urlsOf`, `rowWith`, `rowAction`, `fill` in `src/test/admin.ts`).

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-campaigns-design.md` §6.5, §7 (except the import dialog in §7.4), §8, §9, §12. Repo: `omnichannel-crm-frontend` (all paths below are relative to it). The contact import mapping dialog (§4, §7.4 first sentence) is a separate plan; so is the backend.

## Contract assumptions

Where the spec leaves a name or shape open, this plan uses the following. Reconcile with the backend plan before either is executed.

1. Every campaign path is under `/api/workspaces/{ws}/campaigns`: `GET ?status=&page=`, `POST`, `GET|DELETE /{id}`, `POST /{id}/start|pause|resume|terminate`, `GET /{id}/recipients?status=&page=`, `GET /templates/{templateId}/sample-csv`, `POST /csv/preview`, `GET /senders`, `PUT /senders/{phoneNumberId}`, `GET|PUT /settings`. Pages are 0-based; the query always carries `page`.
2. List reply: `{ items: CampaignView[], total, page, systemPaused: boolean }`; `systemPaused` is true when any campaign of the workspace (not only this page) is PAUSED with `pausedBy = SYSTEM`.
3. `CampaignView`: `{ id, name, status, pausedBy, pauseReason, audience: { kind, filterId, filterName, csvFileName, csvHeaders, csvRows, phoneColumn, nameColumn }, templateIds, templateNames, placeholders, senderIds, counts, creationTime, startedAt, finishedAt, version }`. `GET /{id}` adds `estimate: { remaining, perDay, days: number|null, finishBy: 'YYYY-MM-DD'|null }` and `senders: SenderView[]` (the campaign's senders, same shape as `GET /senders`).
4. `counts` are cumulative: `sent` = SENT+DELIVERED+READ, `delivered` = DELIVERED+READ, `read` = READ, `pending` = PENDING+SENDING, `replied` = recipients with `repliedAt`.
5. `POST /campaigns` and the four lifecycle POSTs return `CampaignView`; the console ignores the lifecycle bodies and re-fetches.
6. A CSV campaign is created with multipart parts `campaign` (a Blob with Content-Type `application/json`) and `file`; a FILTER campaign is a plain JSON POST.
7. Placeholder source: `{ kind: 'CONTACT_FIELD', field: 'name'|'phone' } | { kind: 'CONTACT_ATTRIBUTE', attributeId } | { kind: 'CSV_COLUMN', column } | { kind: 'FIXED', text }`; `fallback` is always a string (`''` for none).
8. CSV preview `suggested` is an object whose `phone` key holds the suggested phone header or null; the console matches placeholders to headers itself, because templates are chosen after the upload.
9. `phoneColumn` and `nameColumn` do not count toward `MAPPING_COLUMN_REUSED`; only two placeholders on one column do.
10. `RecipientView`: `{ id, phone, contactId, contactName, status, skipReason, senderId, senderNumber, sentAt, repliedAt, error }`; page `{ items, total, page }`.
11. `SenderView`: `{ phoneNumberId, number, label, phoneStatus (PhoneNumberStatus), state, ageDay, capToday (already halved when lowReplyRate), usedToday, hourlyUsed, restUntil, pausedUntil, pauseReason ('UNDELIVERED_RUN'|null), lowReplyRate, halfPaceUntil, runsOn }`. `PUT /senders/{id}` body `{ runsOn?: string|null, action?: 'REST'|'RETIRE'|'REACTIVATE'|'RESUME' }` returns `SenderView`.
12. Settings document: `{ timezone, defaultCountryCode, workingDays: ('MONDAY'…'SUNDAY')[], startTime: 'HH:mm', endTime: 'HH:mm', gapMinSeconds, gapMaxSeconds, skipGapMinSeconds, skipGapMaxSeconds, hourlyCap, warmupCaps: {fromDay, cap}[], matureCap, healthyCap, restDays, recontactDays, stopWords: string[], version }`; `PUT` takes and returns the same; VALIDATION `fields` keys use these names.
13. Error bodies keep `{ code, message, field? }`. `SENDER_NOT_AVAILABLE` also carries `number` (digits) of the first unavailable sender. `TEMPLATE_HAS_LINK` is its own code with `field: 'templateIds'` (§9), not a VALIDATION (§5.7 says VALIDATION).
14. `Contact` gains `doNotContact: { at, reason: 'STOP_REPLY'|'MANUAL', campaignId: string|null } | null`. `PUT /contacts/{id}/do-not-contact` marks (MANUAL), `DELETE /contacts/{id}/do-not-contact` clears; both `contact:edit`, both return the Contact. `ContactFilterSpec` gains `doNotContact?: boolean`.
15. `MessageSource` gains `CAMPAIGN`; a campaign message's `sentBy` is `{ id: campaignId, name: 'Campaign: <name>' }` (the campaign actor's displayName, §6.3 step 4).
16. Sample CSV is served as `text/csv`; the console names the download `<template name> sample.csv`.

## Global Constraints

- PatternFly 6.5.1 is installed; `Wizard`, `WizardStep`, `WizardFooterWrapper`, `useWizardContext` and `Progress` exist in `@patternfly/react-core` (checked). No new dependencies.
- Authorities (§8): `campaign:view` opens Campaigns, a campaign, Senders; `campaign:manage` shows New campaign and Start/Pause/Resume/Terminate/Delete; `campaign:settings` opens Campaign settings and the sender actions and runs-on edit. `contact:edit` marks and clears Do not contact.
- Routes (§7.1): `/campaigns` (nav label `Campaigns`, group Brand, after Templates), `/campaigns/new`, `/campaigns/senders`, `/campaigns/settings`, `/campaigns/:id`; Senders and settings are reached from the Campaigns toolbar, not the nav.
- Copy, verbatim from the spec: `Campaigns were paused after two numbers were logged out this week. Review the senders before resuming.`; `Rows need a phone column; other columns can fill template placeholders. 10-digit numbers get the default country code.`; `Fewer than three variants makes the fleet's messages look alike`; `Contains a link`; `Download sample CSV`; `Selected senders can send about N messages a day`; `not connected`; `rests until`; `No sender can send`; `About 3 working days (by Fri 24 Oct)` (the form); `Refreshing in 5 s` … `Refreshing in 1 s`; `Refreshing…`; `Refreshed <time>`; `Refresh`; `Looser than the recommended value`; `Do not contact`; `Clear`; `Campaign: <name>`; `Save draft`; `Start campaign`; step names `Audience`, `Message`, `Senders`, `Review`; radios `Saved contact filter`, `CSV file`; selects `Phone column`, `Name column`; link `Manage filters`.
- `messageFor` sentences (§9), verbatim: `campaign_not_editable: 'Only a draft campaign can be edited.'`; `campaign_state: 'This campaign cannot be <started|paused|resumed|terminated> now.'`; `template_has_link: 'Campaign messages cannot contain links.'`; `template_not_text: 'Only text templates can be used in campaigns.'`; `sender_not_available: '<number> cannot send campaigns right now.'`; `placeholder_unmapped: 'Every placeholder needs a value.'`; `mapping_column_reused: 'A CSV column can be used only once.'`. `PHONE_COLUMN_REQUIRED` belongs to the import plan.
- Recommended values (§5, §7.3), used for the warnings and the test fixture: timezone `Asia/Kolkata`, country code `91`, Mon–Sat, `10:00`–`18:00`, gaps 45/180 s, skip gaps 15/45 s, hourly cap 12, warm-up `[{1,5},{4,10},{8,20},{15,35},{22,50}]`, mature cap 75, healthy cap 100, rest days 7, recontact days 7, stop words `stop, unsubscribe, remove me, do not message, don't message, no`.
- Wording: "Enabled/Disabled", never "block", in any user-facing text.
- Tests drive the UI by role and accessible name and assert on `requestTo(...).body` and `urlsOf(...)`. Timer tests use `vi.useFakeTimers({ shouldAdvanceTime: true })` (the repo's pattern; `findBy*` needs it), `renderApp(path, undefined, { advanceTimers: vi.advanceTimersByTime })`, `await act(() => vi.advanceTimersByTimeAsync(ms))`, and `vi.useRealTimers()` in `afterEach`.
- `npm run type-check`, `npm run lint` and `npm test` pass at the end of every task.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A user without `campaign:settings` or `campaign:manage` opening `/campaigns/settings` or `/campaigns/new` must see the 404 page, not the detail page fetching a campaign called "settings" (the filtered route falls through to `/campaigns/:id`). Tasks 4 and 7, tests `settingsRouteIsNotFoundWithoutSettings`, `newRouteIsNotFoundWithoutManage`.
- The runner completes or the system pauses a campaign while the page polls: the countdown must stop and "Refreshed <time>" with Refresh must appear, with no further fetches. Task 5, `stopsCountingWhenTheRunnerCompletesIt`.
- Leaving the detail page must stop polling, and an answer arriving after a manual Refresh must not overwrite the newer one. Task 5, `stopsPollingOnUnmount`, `aStaleAnswerIsDropped`.
- `Asia/Kolkata` is missing from `Intl.supportedValuesOf('timeZone')` in Node 24 (checked; ICU lists `Asia/Calcutta`, and browsers use ICU too); the saved value must still show and save. Task 4, `keepsATimezoneMissingFromTheBrowserList`.
- Uploading a second CSV with other headers, or switching the audience between filter and CSV, must drop mappings to columns or contact fields that no longer apply, not send them. Tasks 6 and 7, `mergeDropsStaleSources`, `reUploadDropsMappingsToMissingColumns`.

---

### Task 1: `api/campaigns.ts`, error sentences, test builders

**Files:**
- Create: `src/app/api/campaigns.ts`, `src/test/campaigns.ts`
- Modify: `src/app/api/client.ts` (`ApiError` gains `number`), `src/app/api/messages.ts`
- Test: `src/app/api/campaigns.test.ts`, `src/app/api/messages.test.ts`, `src/app/api/client.test.ts`

**Interfaces:**
- Produces (in `api/campaigns.ts`, types per Contract assumptions 2–12; `base = (ws) => '/api/workspaces/' + encodeURIComponent(ws) + '/campaigns'`):
  - Types `CampaignStatus`, `AudienceKind = 'FILTER'|'CSV'`, `SourceKind`, `PlaceholderSource`, `CampaignPlaceholder { name; source; fallback }`, `CampaignCounts`, `CampaignAudience`, `CampaignView`, `CampaignEstimate`, `CampaignDetail extends CampaignView { estimate; senders: SenderView[] }`, `CampaignPage`, `RecipientStatus`, `SkipReason`, `RecipientView`, `RecipientPage`, `CampaignRequest { name; audience: { kind: 'FILTER'; filterId: string } | { kind: 'CSV'; phoneColumn: string; nameColumn: string | null }; templateIds; placeholders; senderIds }`, `CsvPreview { headers; sample: string[][]; rows; suggested: { phone: string | null } }`, `SenderState`, `SenderView`, `SenderAction`, `DayOfWeek`, `CampaignSettings`, `CampaignAction = 'start'|'pause'|'resume'|'terminate'`.
  - `CAMPAIGN_PAGE_SIZE = 50`
  - `listCampaigns(token, ws, q: { status?: CampaignStatus; page: number }): Promise<CampaignPage>`
  - `getCampaign(token, ws, id): Promise<CampaignDetail>`
  - `createCampaign(token, ws, req: CampaignRequest, file?: File): Promise<CampaignView>` (JSON via `apiFetch` without a file; with a file, `FormData` parts `campaign` = `new Blob([JSON.stringify(req)], { type: 'application/json' })` and `file`, via `apiFetchFile`)
  - `deleteCampaign(token, ws, id): Promise<void>`
  - `campaignAction(token, ws, id, action: CampaignAction): Promise<CampaignView>` → `POST /{id}/{action}`
  - `listRecipients(token, ws, id, q: { status?: RecipientStatus; page: number }): Promise<RecipientPage>`
  - `sampleCsv(token, ws, templateId): Promise<Blob>` (via `apiFetchBlob`)
  - `previewCsv(token, ws, file: File): Promise<CsvPreview>`
  - `listSenders(token, ws): Promise<SenderView[]>`; `updateSender(token, ws, phoneNumberId, body: { runsOn?: string | null; action?: SenderAction }): Promise<SenderView>`
  - `getCampaignSettings(token, ws)`, `updateCampaignSettings(token, ws, s: CampaignSettings)`: `Promise<CampaignSettings>`
  - In `messages.ts`: the five fixed sentences in `BY_CODE`; `sender_not_available` handled in `messageFor` (`${shown(error.number)} cannot send campaigns right now.`; without a number `This number cannot send campaigns right now.`); `export function campaignActionMessage(error: unknown, action: CampaignAction): string` (CAMPAIGN_STATE → `This campaign cannot be ${started|paused|resumed|terminated} now.`, else `messageFor(error)`).
  - `ApiError` gains an 8th constructor parameter `number?: string`, read in `failure()` from `data.number` when a string.
  - In `src/test/campaigns.ts`: builders `campaign(over)`, `campaignDetail(over)`, `sender(over)`, `recipient(over)`, `campaignSettings(over)` (defaults = the recommended values in Global Constraints, `version: 0`), and `partJson(form: FormData, name: string): Promise<unknown>` (reads the Blob part with `FileReader`; jsdom's Blob has no `.text()`).

- [ ] **Step 1: Write the failing tests.** `campaigns.test.ts` (`B = '/api/workspaces/w1/campaigns'`):

```ts
it('listsWithPageAndStatus', ...)        // urlsOf GET B: `${B}?page=0`; with status → `${B}?status=RUNNING&page=2`
it('createsAFilterCampaignAsJson', ...)  // requestTo(POST B).body toEqual req; Content-Type application/json
it('createsACsvCampaignAsMultipart', ...) // init.body instanceof FormData; await partJson(form,'campaign') toEqual req; (form.get('file') as File).name === 'leads.csv'
it('postsLifecycleActions', ...)         // campaignAction(...,'terminate') → callsTo(POST `${B}/c1/terminate`) === 1
it('listsRecipientsByStatus', ...)       // `${B}/c1/recipients?status=SKIPPED&page=1`; without status `?page=0`
it('previewsACsv', ...)                  // POST `${B}/csv/preview` with a FormData `file`; resolves the body
it('downloadsASampleCsv', ...)           // GET `${B}/templates/t1/sample-csv` route {status:200, text:'phone,first_name\n', headers:{'Content-Type':'text/csv'}} → Blob size > 0
it('updatesASender', ...)                // PUT `${B}/senders/p1` body {action:'REST'}
it('readsAndSavesSettings', ...)         // GET/PUT `${B}/settings`; PUT body toEqual campaignSettings({hourlyCap: 10})
```

`messages.test.ts`: one assertion per §9 sentence (build `new ApiError(409, 'CAMPAIGN_NOT_EDITABLE')` etc.); `campaignActionMessage(new ApiError(409,'CAMPAIGN_STATE'), 'pause')` → `This campaign cannot be paused now.` and the same for `start`, `resume`, `terminate`; `new ApiError(400,'SENDER_NOT_AVAILABLE',undefined,'senderIds',undefined,undefined,'x','919800000001')` → `+919800000001 cannot send campaigns right now.`; `campaignActionMessage(new ApiError(404,'NOT_FOUND'),'start')` → the not_found sentence. `client.test.ts`: a 400 body `{code:'SENDER_NOT_AVAILABLE', field:'senderIds', number:'919800000001'}` → `error.number === '919800000001'`.

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/api`
Expected: FAIL (module `@app/api/campaigns` missing; generic sentences).

- [ ] **Step 3: Implement** the module, the `ApiError` field, the sentences and the builders as listed.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/api && npm run type-check && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/api/campaigns.ts src/app/api/campaigns.test.ts src/app/api/client.ts src/app/api/client.test.ts src/app/api/messages.ts src/app/api/messages.test.ts src/test/campaigns.ts
git commit -m "feat(campaigns): campaigns API module and error sentences"
```

---

### Task 2: Authorities, route, nav and the Campaigns list

**Files:**
- Create: `src/app/Campaigns/CampaignsPage.tsx`, `src/app/Campaigns/CampaignStatusLabel.tsx`, `src/app/Campaigns/useCampaignActions.tsx`
- Modify: `src/app/routes.tsx`, `src/test/session.ts`, `src/app/routes.test.tsx`
- Test: `src/app/Campaigns/campaigns.test.tsx`

**Interfaces:**
- Consumes: Task 1 `listCampaigns`, `campaignAction`, `deleteCampaign`, `campaignActionMessage`, builders.
- Produces:
  - `CampaignStatusLabel: FC<{ status: CampaignStatus }>`: `Draft` grey, `Running` blue, `Paused` orange, `Completed` green, `Terminated` red.
  - `SYSTEM_PAUSE_TEXT` (the §7.3 banner sentence), exported from `CampaignsPage.tsx`.
  - `useCampaignActions(onDone: () => void)` → `{ menuFor(c: CampaignView): IAction[]; buttonsFor(c: CampaignView): ReactNode; modal: ReactNode; error: string | null }`. Which actions: DRAFT → Start, Delete; RUNNING → Pause, Terminate; PAUSED → Resume, Terminate; COMPLETED/TERMINATED → none; nothing without `campaign:manage`. Terminate and Delete open a `ConfirmModal` (`Terminate campaign?` / body `Pending messages of <name> will not be sent. This cannot be undone.` / `Terminate`, danger; `Delete draft?` / `Delete <name>? This cannot be undone.` / `Delete`, danger). Errors go through `campaignActionMessage` (Delete through `messageFor`). `onDone` runs after each success. Task 5 reuses `buttonsFor` on the detail page.
  - `src/test/session.ts`: `BRAND_CODES` gains `campaign:view`, `campaign:manage`, `campaign:settings`; `TEAM_LEAD` gains `campaign:view`, `campaign:manage`; `AGENT_IN_BRAND` gains `campaign:view`.

Page: `PageShell` title `Campaigns`; actions `New campaign` (manage, link to `/campaigns/new`), `Senders` (link to `/campaigns/senders`), `Campaign settings` (settings, link to `/campaigns/settings`); warning `Alert` with `SYSTEM_PAUSE_TEXT` when `page.systemPaused`; action error as an inline danger alert; compact table Name (link to `/campaigns/:id`), Status, Progress (`Progress size="sm" measureLocation="outside" value={sent/total*100} label={`${sent} / ${total}`} aria-label={`Progress of ${name}`}`), Replied, Skipped, Started (`relative(startedAt)` or `—`), kebab; `Pagination` at 50; empty text `No campaigns yet.`.

- [ ] **Step 1: Write the failing tests** (`campaigns.test.tsx`, `B = '/api/workspaces/w1/campaigns'`):

```ts
it('listsCampaignsWithProgress', ...)    // row 'Diwali leads': 'Running', '3 / 10', replied '1', skipped '2'; link href '/campaigns/c1'; urlsOf(GET B)[0] === `${B}?page=0`
it('pagesThroughCampaigns', ...)         // total 120; click 'Go to next page' → urlsOf(GET B)[1] === `${B}?page=1`
it('showsTheSystemPauseBanner', ...)     // systemPaused true → SYSTEM_PAUSE_TEXT visible; false → absent
it('rowActionsFollowTheStatus', ...)     // DRAFT kebab: Start, Delete; RUNNING: Pause, Terminate; PAUSED: Resume, Terminate; COMPLETED row has no 'Kebab toggle'
it('startPostsAndReloads', ...)          // rowAction 'Start' → callsTo(POST `${B}/c1/start`) 1; callsTo(GET B) 2
it('terminateConfirmsFirst', ...)        // rowAction 'Terminate' → dialog 'Terminate campaign?'; Cancel → no POST; again + 'Terminate' → POST once
it('deleteConfirmsFirst', ...)           // dialog 'Delete draft?' → DELETE `${B}/c1` once
it('actionConflictNamesTheAction', ...)  // POST resume 409 {code:'CAMPAIGN_STATE'} → 'This campaign cannot be resumed now.'
it('toolbarFollowsAuthorities', ...)     // WORKSPACE_MANAGER: New campaign, Senders, Campaign settings; TEAM_LEAD: no Campaign settings; AGENT_IN_BRAND: Senders only, no kebabs
```

`routes.test.tsx`: add `'Campaigns'` to `ALL` and after `'Templates'` in the customer admin and workspace manager lists; new test `teamLeadAndAgentSeeCampaigns` (both nav lists contain `Campaigns`).

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns src/app/routes.test.tsx`
Expected: FAIL (no route; nav lacks Campaigns).

- [ ] **Step 3: Implement** the fixtures, the `/campaigns` route (`requires: a => a.hasInWorkspace('campaign:view')`, label `Campaigns`, group Brand, after Templates), `CampaignStatusLabel`, `useCampaignActions` and the page.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns src/app/routes.test.tsx && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns src/app/routes.tsx src/app/routes.test.tsx src/test/session.ts
git commit -m "feat(campaigns): campaigns list with lifecycle actions"
```

---

### Task 3: Senders page

**Files:**
- Create: `src/app/Campaigns/SendersPage.tsx`, `src/app/Campaigns/senderDisplay.tsx`
- Modify: `src/app/routes.tsx`, `src/app/PhoneNumbers/PhoneNumberStatusLabel.tsx` (add `PhoneStatusLabel`)
- Test: `src/app/Campaigns/senders.test.tsx`

**Interfaces:**
- Consumes: Task 1 `listSenders`, `updateSender`, `sender()`.
- Produces:
  - `PhoneStatusLabel: FC<{ status: PhoneNumberStatus }>` (the file's existing colours; text `Connected`, `Waiting for WhatsApp login`, `Not connected yet`, `Disconnected`, `Wrong number`, `Disabled`).
  - In `senderDisplay.tsx`: `SenderStateLabel: FC<{ sender: SenderView }>` (`Warming` blue, `Active` green, `Resting` orange, `Suspended` red, `Retired` grey; RESTING with `restUntil` appends ` rests until <d MMM>` using `toLocaleDateString(undefined, { day: 'numeric', month: 'short' })`); `pauseText(s: SenderView, now = Date.now()): string | null`, joining with ` · `: `pausedUntil > now` → `Paused: last messages not delivered` (UNDELIVERED_RUN) or `Paused`; `lowReplyRate` → `Low reply rate, cap halved`; `halfPaceUntil > now` → `Half pace after a logout`; null when none.
  - Route `/campaigns/senders` (`campaign:view`, no label).

Page: `PageShell` title `Senders`, link `Back to campaigns`; compact table Number (`shown`), Label, Phone status, State, Age (`Day N`), Cap today, Used today, Pause (`pauseText` or `—`), Runs on, kebab. Kebab (only with `campaign:settings`): `Rest` (WARMING, ACTIVE), `Retire` (all but RETIRED; `ConfirmModal` `Retire this number?` / `It will send no campaign messages until reactivated.` / `Retire`), `Reactivate` (RETIRED, RESTING, SUSPENDED), `Resume pause` (`pausedUntil` in the future); no action ever sets ACTIVE. Runs on: text plus, with settings, a pencil button `Edit runs on` that swaps in a `TextInput` (aria-label `Runs on`) with `Save` and `Cancel`; Enter saves; blank saves `null`. The row is replaced from the PUT reply (`upsert` by `phoneNumberId`); errors show inline.

- [ ] **Step 1: Write the failing tests** (`senders.test.tsx`, `S = 'GET /api/workspaces/w1/campaigns/senders'`, `PUT = 'PUT /api/workspaces/w1/campaigns/senders/p1'`):

```ts
it('listsSendersWithStateAndCapacity', ...) // row '+919800000001': 'Main', 'Connected', 'Warming', 'Day 3', '5', '2', 'Office PC, profile 3'
it('restingShowsRestsUntil', ...)          // RESTING restUntil '2026-10-28T10:00:00Z' → text /rests until/
it('showsPauseReasons', ...)               // pausedUntil future + UNDELIVERED_RUN, lowReplyRate → 'Paused: last messages not delivered · Low reply rate, cap halved'
it('actionsFollowTheState', ...)           // WARMING: Rest, Retire; RETIRED: Reactivate only; paused WARMING also 'Resume pause'; no menuitem named /^(set )?active$/i in any row (Reactivate is fine)
it('restSendsTheActionAndUpdatesTheRow', ...) // requestTo(PUT).body toEqual {action:'REST'}; row now 'Resting'
it('retireConfirmsFirst', ...)             // dialog 'Retire this number?'; confirm → body {action:'RETIRE'}
it('editsRunsOnInline', ...)               // 'Edit runs on' → type 'Laptop, profile 1' + Enter → body {runsOn:'Laptop, profile 1'}; clearing → {runsOn:null}
it('viewerSeesNoActions', ...)             // TEAM_LEAD: no 'Kebab toggle', no 'Edit runs on'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/senders.test.tsx`
Expected: FAIL (404 page).

- [ ] **Step 3: Implement** the label, display helpers, route and page.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns src/app/PhoneNumbers && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns src/app/routes.tsx src/app/PhoneNumbers/PhoneNumberStatusLabel.tsx
git commit -m "feat(campaigns): senders page with state actions and runs-on edit"
```

---

### Task 4: Campaign settings page

**Files:**
- Create: `src/app/Campaigns/CampaignSettingsPage.tsx`, `src/app/Campaigns/settingsModel.ts`
- Modify: `src/app/routes.tsx`
- Test: `src/app/Campaigns/campaignSettings.test.tsx`, `src/app/Campaigns/settingsModel.test.ts`

**Interfaces:**
- Consumes: Task 1 `getCampaignSettings`, `updateCampaignSettings`, `campaignSettings()`.
- Produces:
  - `RECOMMENDED: Omit<CampaignSettings, 'version'>` (the Global Constraints values).
  - `looserFields(s: CampaignSettings): Set<string>` with keys `gapMinSeconds`, `gapMaxSeconds`, `skipGapMinSeconds`, `skipGapMaxSeconds` (looser when lower), `hourlyCap`, `matureCap`, `healthyCap` (when higher), `restDays`, `recontactDays` (when lower), `warmupCaps.<i>` (row `i` looser when `cap > recommendedCapAt(fromDay)`, where `recommendedCapAt(d)` is the cap of the last recommended row with `fromDay <= d`; the recommended table only rises, so checking the row's first day is enough), `stopWords` (when any recommended word, trimmed and lower-cased, is missing). Working window fields never warn (§7.3 warns on pacing fields only).
  - `timezoneOptions(current: string): string[]`: `current` first when `Intl.supportedValuesOf('timeZone')` lacks it, then that list.
  - Route `/campaigns/settings` with `requires: campaign:view`; the page renders `<NotFound />` without `campaign:settings` (a filtered-out route would fall through to `/campaigns/:id`).

Page: `PageShell` title `Campaign settings`, link `Back to campaigns`; a `Form` with `Timezone` (`FormSelect`), `Default country code` (digits, `/^[1-9][0-9]{0,3}$/`), `Working days` (seven checkboxes `Mon`…`Sun`), `Start time`, `End time` (`type="time"`), then `Minimum gap (seconds)`, `Maximum gap (seconds)`, `Minimum gap after a skip (seconds)`, `Maximum gap after a skip (seconds)`, `Hourly cap`, a warm-up table (one row per `warmupCaps` entry, inputs labelled `Warm-up row N from day` and `Warm-up row N cap`), `Mature cap`, `Healthy cap`, `Rest days`, `Recontact days`, `Stop words` (`TextArea`, one per line). Each field in `looserFields` shows `HelperTextItem variant="warning"` `Looser than the recommended value`. Client checks before PUT (field errors, PUT not sent): gap min ≤ max for both pairs, start < end, at least one working day, all numbers whole and ≥ 1. `Save` PUTs the whole document (with `version`) through `useAction(<every field name>)` and replaces the form with the reply; success shows an inline success alert `Settings saved.`.

- [ ] **Step 1: Write the failing tests.** `settingsModel.test.ts`:

```ts
it('recommendedValuesAreNotLooser', ...)      // looserFields(campaignSettings()).size === 0
it('flagsEachLooserPacingValue', ...)          // gapMinSeconds 30, hourlyCap 13, matureCap 80, restDays 5 → exactly those four keys
it('flagsAWarmUpRowAboveTheRecommendedCap', ...) // row {fromDay:3, cap:10} → 'warmupCaps.0'; {fromDay:4, cap:10} → none
it('flagsARemovedStopWord', ...)               // stopWords without 'no' → 'stopWords'; extra words and other case → none
it('keepsACurrentTimezoneTheBrowserLacks', ...) // timezoneOptions('Asia/Kolkata')[0] === 'Asia/Kolkata'; no duplicate for 'Europe/London'
```

`campaignSettings.test.tsx` (`SET = '/api/workspaces/w1/campaigns/settings'`):

```ts
it('loadsTheSettings', ...)                    // Hourly cap value '12'; Start time '10:00'; Mon…Sat checked, Sun not
it('keepsATimezoneMissingFromTheBrowserList', ...) // Timezone select value 'Asia/Kolkata'; option 'Asia/Kolkata' present
it('warnsWhenLooserThanRecommended', ...)      // fill Minimum gap 30 → one 'Looser than the recommended value'; Hourly cap 13 → two; End time 20:00 → still two
it('stricterValuesShowNoWarning', ...)         // Minimum gap 60, Hourly cap 10 → zero warnings
it('savesTheWholeDocument', ...)               // Hourly cap 10, Save → requestTo(PUT SET).body toEqual campaignSettings({hourlyCap:10}); 'Settings saved.'
it('checksTheGapsBeforeSaving', ...)           // Minimum gap 200 (> max 180) → field error, callsTo(PUT) 0
it('serverFieldErrorsLandUnderTheirField', ...) // PUT 400 VALIDATION fields {hourlyCap:'must be at most 60'} → text under Hourly cap
it('settingsRouteIsNotFoundWithoutSettings', ...) // TEAM_LEAD at '/campaigns/settings' → '404 Page not found'; callsTo(GET SET) 0
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/settingsModel.test.ts src/app/Campaigns/campaignSettings.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement** `settingsModel.ts`, the route and the page.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns src/app/routes.tsx
git commit -m "feat(campaigns): campaign settings with looser-than-recommended warnings"
```

---

### Task 5: Campaign detail with the polling countdown

**Files:**
- Create: `src/app/Campaigns/CampaignDetailPage.tsx`, `src/app/Campaigns/useCampaignProgress.ts`, `src/app/Campaigns/RefreshCountdown.tsx`, `src/app/Campaigns/campaignDisplay.ts`
- Modify: `src/app/routes.tsx`, `src/test/renderApp.tsx`
- Test: `src/app/Campaigns/campaignDetail.test.tsx`, `src/app/Campaigns/campaignDisplay.test.ts`

**Interfaces:**
- Consumes: Task 1 `getCampaign`, `listRecipients`; Task 2 `useCampaignActions`, `CampaignStatusLabel`, `SYSTEM_PAUSE_TEXT`; Task 3 `SenderStateLabel`, `PhoneStatusLabel`, `pauseText`.
- Produces:
  - `renderApp(path = '/', extra?: React.ReactNode, userOptions?: Parameters<typeof userEvent.setup>[0])`.
  - `REFRESH_SECONDS = 5`; `useCampaignProgress(id: string): CampaignProgress`, exported interface `{ campaign: CampaignDetail | undefined; error: string | null; fetching: boolean; secondsLeft: number | null; refreshedAt: Date | null; refresh(): void }`.
  - `RefreshCountdown: FC<{ progress: CampaignProgress }>`.
  - `estimateText(e: CampaignEstimate): string | null`: `remaining === 0` → null; `days === null` → `No sender can send`; else `About ${days} working day${days === 1 ? '' : 's'} (by ${weekday day month})`, the date formatted `new Date(`${finishBy}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })`.
  - `RECIPIENT_STATUS_TEXT`, `SKIP_REASON_TEXT` (`Not on WhatsApp`, `Do not contact`, `Recently messaged`, `No phone number`, `Duplicate`, `Chat on another number`, `Cancelled`).
  - Route `/campaigns/:id` (`campaign:view`). The page reads `useLocation().state?.startError` (Task 8 hands it over) and shows it as a danger alert.

Hook rules (the timing the tests pin):
- `load()`: bump a request counter, `fetching = true`; on success set `campaign`, clear `error`, set `refreshedAt = new Date()`; on failure set `error = messageFor(e)` and keep `campaign`; then `fetching = false`. An answer whose counter is not the latest, or that arrives after unmount, is dropped.
- When a load settles and `campaign.status === 'RUNNING'`: `secondsLeft = 5` and a 1000 ms interval decrements it; on the tick that would reach 0, clear the interval, set `secondsLeft = 5` (the bar refills) and `load()`. Any other status: `secondsLeft = null`, no timer.
- `refresh()` clears the interval and calls `load()`. A changed `id` resets and loads. Unmount clears the interval.

`RefreshCountdown`: while `secondsLeft !== null` a `Progress size="sm" measureLocation="none"` with `title` = `Refreshing…` when `fetching`, else `Refreshing in ${secondsLeft} s`, and `value` = 100 when `fetching`, else `secondsLeft / 5 * 100` (the title is the progressbar's accessible name). Otherwise `Refreshed ${refreshedAt.toLocaleTimeString()}` and a `Refresh` button (not disabled while fetching; the hook drops stale answers). `error` shows as an inline danger alert next to it.

Page: title = campaign name, actions = `buttonsFor(campaign)` with `onDone = refresh` (Delete navigates to `/campaigns`); link `Back to campaigns`; `SYSTEM_PAUSE_TEXT` alert when `pausedBy === 'SYSTEM'`; a `DescriptionList` of the eight counts (`Total`, `Pending`, `Sent`, `Delivered`, `Read`, `Replied`, `Skipped`, `Failed`) with `RefreshCountdown` beside it; `estimateText` for DRAFT/RUNNING/PAUSED; table `Senders today` (Number, State, Cap today, Used today, Phone status, Pause); table `Recipients` with a `Status` `FormSelect` (`All` + the seven statuses), `Pagination` at 50, columns Phone, Name, Status, Skip reason, Sender, Sent, Replied, Error. Recipients use `useLoad` keyed by `[ws, id, status, page]` and call `reload()` when `refreshedAt` changes, so rows are not cleared every 5 s.

- [ ] **Step 1: Write the failing tests.** `campaignDisplay.test.ts`: `estimateText({remaining: 120, perDay: 45, days: 3, finishBy: '2026-10-24'})` → `About 3 working days (by Sat 24 Oct)`; `days: 1` → `About 1 working day (by …)`; `perDay: 0, days: null` → `No sender can send`; `remaining: 0` → null.

`campaignDetail.test.tsx` (`GET = 'GET /api/workspaces/w1/campaigns/c1'`, `REC = 'GET /api/workspaces/w1/campaigns/c1/recipients'`; to hold an answer, gate the n-th GET by wrapping `fetch.getMockImplementation()` as `sendsOnce` in `newChatModal.test.tsx` does; timer tests per Global Constraints):

```ts
it('showsCountsEstimateAndSendersToday', ...)   // dd after 'Sent' is '3', after 'Failed' '1'; 'About 3 working days (by Sat 24 Oct)'; Senders today row '+919800000001' with 'Warming', '5', '2'
it('countdownDrainsAndRefetchesEveryFiveSeconds', ...) // progressbar 'Refreshing in 5 s' aria-valuenow 100 → +1000 ms 'Refreshing in 4 s' 80 → +3000 'Refreshing in 1 s' 20 → (2nd GET gated) +1000 'Refreshing…' 100, callsTo(GET) 2 → release → 'Refreshing in 5 s'; callsTo(REC) 2
it('fetchErrorKeepsNumbersAndKeepsCounting', ...) // 2nd GET 500 → danger alert with MESSAGES.generic; Sent still '3'; 'Refreshing in 5 s' present; +5000 → callsTo(GET) 3
it('stopsCountingWhenTheRunnerCompletesIt', ...) // 2nd GET COMPLETED → no progressbar; text /^Refreshed /; +10_000 → callsTo(GET) 2; click 'Refresh' → 3
it('pausedCampaignDoesNotPoll', ...)             // PAUSED → 'Refreshed …' + 'Refresh' at once; +10_000 → callsTo(GET) 1
it('aStaleAnswerIsDropped', ...)                 // PAUSED; Refresh (2nd GET gated, answers sent 3); Refresh again (3rd GET answers sent 7); release the 2nd → Sent stays '7'
it('stopsPollingOnUnmount', ...)                 // unmount; +20_000 → callsTo(GET) 1
it('filtersAndPagesRecipients', ...)             // Status 'Skipped' → urlsOf(REC) last `…/recipients?status=SKIPPED&page=0`; total 120 → next page `page=1`; row shows 'Not on WhatsApp' and sender '+919800000001'
it('actionsFollowTheStatus', ...)                // RUNNING: buttons Pause, Terminate; click Pause → POST …/c1/pause once, then callsTo(GET) 2; DRAFT: Start, Delete; COMPLETED: none; AGENT_IN_BRAND: none
it('stateConflictNamesTheAction', ...)           // POST pause 409 CAMPAIGN_STATE → 'This campaign cannot be paused now.'
it('systemPauseShowsTheBanner', ...)             // pausedBy SYSTEM → SYSTEM_PAUSE_TEXT
it('deleteOpensTheList', ...)                    // DRAFT, Delete confirmed → DELETE once; heading 'Campaigns'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/campaignDisplay.test.ts src/app/Campaigns/campaignDetail.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement** `renderApp`'s option, the display helpers, the hook, the countdown, the route and the page.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns src/app/routes.tsx src/test/renderApp.tsx
git commit -m "feat(campaigns): campaign detail with 5-second refresh countdown"
```

---

### Task 6: `TemplateSearchList` and the placeholder mapping model

**Files:**
- Create: `src/app/Templates/TemplateSearchList.tsx`, `src/app/Campaigns/mapping.ts`
- Modify: `src/app/Inbox/TemplatePickerModal.tsx`
- Test: `src/app/Campaigns/mapping.test.ts`; existing `src/app/Inbox/composer.test.tsx`, `src/app/Inbox/newChatModal.test.tsx` stay unchanged and green

**Interfaces:**
- Produces:
  - `TemplateSearchList: FC<{ filters: Omit<TemplateFilters, 'q'>; selectedIds: string[]; unavailable(t: TemplateView): string | null; onPick(t: TemplateView): void; onError(message: string | null): void }>`. It holds what the picker's left column does today: `SearchInput` `Search templates`, 300 ms debounce (0 for an empty search), `listTemplates(t, ws, { ...filters, q })`, spinner `Loading templates`, `No templates found`, `Menu` `Templates`. An item is disabled when `unavailable(t)` is non-null; its description is that reason, else `TYPE_META[type].label`; a disabled item's text is `${name} (${TYPE_META[type].label})`; `isSelected` when its id is in `selectedIds`. The effect depends on `filters.status` and `filters.type`, not on the object.
  - `TemplatePickerModal` uses it with `filters={{ status: 'ACTIVE' }}`, `unavailable = t => t.type === 'CONTACT_CARD' ? 'Not sendable from the inbox yet' : null`, `onPick = t => choose(t.id)`; its alert shows `error ?? listError`. Values, preview and send stay as they are.
  - `mapping.ts`:
    - `interface Mapping { source: PlaceholderSource | null; fallback: string }`
    - `interface MappingContext { kind: AudienceKind; headers: string[]; phoneColumn: string | null; nameColumn: string | null; attributes: ContactAttribute[] }`
    - `distinctPlaceholders(templates: TemplateView[]): PlaceholderView[]` (by name, first occurrence wins)
    - `defaultMapping(p: PlaceholderView, ctx): Mapping`. FILTER: `contact.name`/`contact.phone` → `CONTACT_FIELD` `name`/`phone`, attribute → `CONTACT_ATTRIBUTE` with `p.attributeId`, AGENT → null. CSV (headers compared trimmed and case-insensitively): `contact.phone` → `phoneColumn`, `contact.name` → `nameColumn` else a header `name`, attribute → a header equal to `attributeKey` or `attributeName`, AGENT → a header equal to `p.name`; no match → null. `fallback = p.fallback ?? ''`.
    - `mergeMappings(prev: Record<string, Mapping>, placeholders: PlaceholderView[], ctx): Record<string, Mapping>`: keep `prev[name]` while its source is valid in `ctx` (FIXED always; `CSV_COLUMN` only for CSV with the column in `headers`; `CONTACT_*` only for FILTER, and the attribute still in `attributes`), else `defaultMapping`; names no longer present are dropped.
    - `sourceOptions(ctx): { value: string; label: string }[]`: FILTER → `Contact name`, `Contact phone`, each attribute by name, `Fixed text`; CSV → each header, `Fixed text`. `encodeSource(s: PlaceholderSource | null): string` (`''`, `field:name`, `attr:<id>`, `col:<header>`, `fixed`) and `decodeSource(v: string, current: Mapping): PlaceholderSource | null` (`fixed` keeps any text already typed).
    - `mappingProblems(placeholders, mappings): { unmapped: string[]; reused: string[] }`: unmapped = source null or FIXED with blank text; reused = each placeholder whose `CSV_COLUMN` an earlier placeholder already uses (Contract assumption 9).
    - `previewValues(placeholders, mappings, sample: { contact?: Contact; row?: Record<string, string> }): Record<string, string>`: FIXED → text; `CONTACT_FIELD` → `contact.name` or `'+' + contact.phone`; `CONTACT_ATTRIBUTE` → `contact.attributes[attributeId]`; `CSV_COLUMN` → `row[column]`; blank or missing → `fallback`.
    - `toPlaceholders(placeholders, mappings): CampaignPlaceholder[]` (in `placeholders` order; FIXED carries `fallback: ''`).

- [ ] **Step 1: Write the failing tests** (`mapping.test.ts`, with `placeholderView`, `template`, `attribute`, `contact` builders):

```ts
it('distinctPlaceholdersKeepFirstOccurrence', ...) // two templates sharing 'first_name' → one entry, order of first appearance
it('filterDefaultsUseTheContact', ...)          // contact.name → {kind:'CONTACT_FIELD', field:'name'}; city attr → {kind:'CONTACT_ATTRIBUTE', attributeId:'a1'}; agent 'offer' → null; fallback 'there' copied
it('csvDefaultsMatchHeaders', ...)              // headers ['Phone','First_Name','City','name']: first_name → col 'First_Name'; contact.phone → phoneColumn 'Phone'; contact.name → 'name'; city attr (key 'city') → 'City'; 'offer' → null
it('mergeDropsStaleSources', ...)               // CSV_COLUMN 'City' dropped when headers lack it; CONTACT_FIELD dropped on switch to CSV; FIXED kept across kinds; removed placeholder gone
it('problemsListUnmappedAndReusedColumns', ...)  // null source and FIXED '' → unmapped; two placeholders on 'City' → reused === [second]; phoneColumn reuse by contact.phone not reported
it('previewValuesFillFromContactRowOrFallback', ...) // contact {name:'Asha', attributes:{a1:'Pune'}} → name 'Asha', city 'Pune'; row {City:''} → fallback
it('encodeAndDecodeRoundTrip', ...)             // each source kind survives encode→decode; decode('fixed') keeps text
it('toPlaceholdersBuildsTheRequest', ...)       // toEqual [{name:'contact.name', source:{kind:'CONTACT_FIELD', field:'name'}, fallback:'there'}, {name:'offer', source:{kind:'FIXED', text:'10% off'}, fallback:''}]
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/mapping.test.ts`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement** `mapping.ts`; extract `TemplateSearchList` and switch the picker to it.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns/mapping.test.ts src/app/Inbox && npm run lint`
Expected: PASS; the Inbox tests pass without edits.

- [ ] **Step 5: Commit**

```bash
git add src/app/Templates/TemplateSearchList.tsx src/app/Inbox/TemplatePickerModal.tsx src/app/Campaigns/mapping.ts src/app/Campaigns/mapping.test.ts
git commit -m "refactor(templates): extract TemplateSearchList; campaign placeholder mapping model"
```

---

### Task 7: New campaign wizard, Audience and Message steps

**Files:**
- Create: `src/app/Campaigns/NewCampaign/NewCampaignPage.tsx`, `draft.ts`, `AudienceStep.tsx`, `MessageStep.tsx`, `PlaceholderMapping.tsx`, `SampleCsvLinks.tsx` (all under `src/app/Campaigns/NewCampaign/`)
- Modify: `src/app/routes.tsx`
- Test: `src/app/Campaigns/newCampaign.test.tsx`

**Interfaces:**
- Consumes: Task 1 `previewCsv`, `sampleCsv`; contacts `listFilters`, `countContacts`, `searchContacts`, `listAttributes`, `saveFile`, `countText`; Task 6 `TemplateSearchList` and `mapping.ts`; `TemplateBubble`, `fillPreview`.
- Produces:
  - `draft.ts`: `interface Draft { name: string; kind: AudienceKind; filterId: string; file: File | null; preview: CsvPreview | null; phoneColumn: string | null; nameColumn: string | null; templates: TemplateView[]; mappings: Record<string, Mapping>; senderIds: string[] }`, `EMPTY_DRAFT`, `mappingContext(d: Draft, attributes: ContactAttribute[]): MappingContext`, `hasLink(body: string | null): boolean` (`/https?:\/\/|www\./i`), `audienceReady(d)`, `messageReady(d)`.
  - Steps take `{ draft: Draft; update(patch: Partial<Draft>): void }`; `NewCampaignPage` re-runs `mergeMappings` whenever the kind, headers, columns or templates change.
  - Route `/campaigns/new` (`campaign:view`; the page renders `<NotFound />` without `campaign:manage`, as in Task 4).

Wizard: `PageShell` title `New campaign`; `<Wizard isVisitRequired onClose={() => navigate('/campaigns')}>` with steps `Audience`, `Message`, `Senders`, `Review` (the last two are empty here; Task 8 fills them). Next is disabled (`footer={{ isNextDisabled }}`) while the step is not ready.

- Audience: `Name` (required); radios `Saved contact filter` / `CSV file`. Filter: `FormSelect` `Saved contact filter` from `listFilters` (placeholder `Choose a filter`), `countText(count)` from `countContacts(t, ws, { filterId })`, link `Manage filters` to `/contacts`. CSV: `FileUpload` (as in `ImportContactsModal`), on a file `previewCsv` → `${rows} rows` and `Columns: a, b, c`; `Phone column` preset to `suggested.phone`, `Name column` (`None` + headers) preset to a header `name`; the §7.2 note verbatim under the upload; a preview error shows under the upload with `messageFor`; clearing the file clears the preview and both columns. `SampleCsvLinks` under the note once templates are chosen and no file is. Ready: name non-blank and (FILTER: filter chosen; CSV: no file yet, or a phone column chosen). A CSV may go on without a file, so the user can fetch the sample in Message first (§7.2).
- Message: `TemplateSearchList` with `filters={{ status: 'ACTIVE', type: 'TEXT' }}`, `unavailable = t => hasLink(t.content.body) ? 'Contains a link' : (five chosen and t not among them ? 'Five templates chosen' : null)`, `onPick` toggles. Chosen templates as `LabelGroup` labels, each closable (`Remove <name>`). Warning `Fewer than three variants makes the fleet's messages look alike` for 1 or 2. `PlaceholderMapping`: one row per `distinctPlaceholders` with the name, a `FormSelect` `Source for <name>` (`Choose a source` + `sourceOptions`), and a `TextInput` labelled `Fixed text for <name>` when FIXED, else `Fallback for <name>`; a reused column shows `A CSV column can be used only once.` under its select; any unmapped row shows `Every placeholder needs a value.` once under the table. For a CSV without a file: `SampleCsvLinks` and the note `Choose the CSV file in Audience to map its columns.`. Preview: one `TemplateBubble` per chosen template, `content.body = fillPreview(body, previewValues(...))`, sample from `searchContacts(t, ws, { filterId, page: 0 }).items[0]` (FILTER) or the first CSV row. Ready: 1–5 templates, no problems, and for CSV a preview.
- `SampleCsvLinks`: one link button per chosen template, `Download sample CSV` (with several: `Download sample CSV (<name>)`), `sampleCsv` then `saveFile(blob, `${name} sample.csv`)`.

- [ ] **Step 1: Write the failing tests** (`newCampaign.test.tsx`, `C = '/api/workspaces/w1/campaigns'`; templates `WELCOME` body `Hello {{contact.name}}, your city is {{contact.attributes.city}}`, `OFFER` with AGENT `offer`, `PROMO` body with `https://x.in`):

```ts
it('newRouteIsNotFoundWithoutManage', ...)        // AGENT_IN_BRAND at '/campaigns/new' → '404 Page not found'; no GET `${C}/new`
it('audienceNeedsANameAndAFilter', ...)          // Next disabled; Name 'Diwali' + choose filter 'Pune leads' → requestTo(POST …/contacts/count).body toEqual {selection:{filterId:'f1'}}; '1,204 contacts'; Next enabled
it('csvUploadShowsColumnsAndPresetsThem', ...)   // upload → POST `${C}/csv/preview` once; '120 rows'; Phone column value 'phone'; Name column 'name'; the §7.2 note visible
it('csvPreviewErrorShowsUnderTheUpload', ...)     // preview 400 {code:'VALIDATION', message:'file has more than 20000 rows'} → that text under the upload; Next stays enabled without a file
it('listsActiveTextTemplatesAndDisablesLinks', ...) // urlsOf(GET …/templates)[0] contains 'status=ACTIVE' and 'type=TEXT'; menuitem 'Promo (Text)' disabled with 'Contains a link'
it('warnsUnderThreeVariants', ...)               // pick 1 → warning; 3 → gone; remove via 'Remove Welcome' → back
it('filterPlaceholdersDefaultToTheContact', ...) // Source for contact.name value 'field:name'; Source for offer empty; 'Every placeholder needs a value.'; Next disabled; choose 'Fixed text', type '10% off' → Next enabled
it('csvPlaceholdersOfferHeadersAndFixedText', ...) // CSV: options = headers + 'Fixed text', no 'Contact name'
it('aReusedColumnDisablesNext', ...)             // offer and city both on 'City' → 'A CSV column can be used only once.'; Next disabled
it('previewUsesTheFirstContactOfTheFilter', ...) // requestTo(POST …/contacts/search).body toEqual {filterId:'f1', page:0}; bubble 'Hello Asha, your city is Pune'
it('previewUsesTheFirstCsvRow', ...)             // sample row Asha/Pune → same text
it('sampleCsvDownloadsBeforeAFileIsChosen', ...) // CSV, no file, Next, pick Welcome → 'Download sample CSV' → GET `${C}/templates/t1/sample-csv` once; anchor download 'Welcome sample.csv' (stub URL.createObjectURL as contactImportExport.test.tsx does); 'Choose the CSV file in Audience to map its columns.'; Next disabled; Back → link also in Audience
it('reUploadDropsMappingsToMissingColumns', ...)  // map offer to 'Offer'; Back; upload a file without 'Offer' → Next; Source for offer is empty again
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/newCampaign.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement** `draft.ts`, the two steps, `PlaceholderMapping`, `SampleCsvLinks`, the page and the route.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns src/app/routes.tsx
git commit -m "feat(campaigns): new campaign wizard audience and message steps"
```

---

### Task 8: Wizard Senders and Review steps; save and start

**Files:**
- Create: `src/app/Campaigns/NewCampaign/SendersStep.tsx`, `src/app/Campaigns/NewCampaign/ReviewStep.tsx`
- Modify: `src/app/Campaigns/NewCampaign/NewCampaignPage.tsx`, `draft.ts`
- Test: `src/app/Campaigns/newCampaign.test.tsx`

**Interfaces:**
- Consumes: Task 1 `listSenders`, `createCampaign`, `campaignAction`, `campaignActionMessage`; Task 3 `SenderStateLabel`, `PhoneStatusLabel`; Task 6 `toPlaceholders`; Task 7 `Draft`.
- Produces: `toRequest(d: Draft, placeholders: PlaceholderView[]): CampaignRequest` in `draft.ts`; `audienceSize(d, filterCount: number | null): number | null` (FILTER → the count; CSV → `preview.rows`).

- Senders: `listSenders` without SUSPENDED and RETIRED; columns select, Number, State, Age, Cap today, Used today, Phone status. WARMING/ACTIVE rows have a checkbox (`Select <number>`); those whose phone is not CONNECTED also show `not connected`. RESTING rows have no checkbox and show `rests until <date>`. Nothing is preselected. Footer line `Selected senders can send about ${sum of capToday} messages a day`. Ready: at least one selected.
- Review: a `DescriptionList` with Name; Audience (`Saved filter <name> · <countText>` or `CSV <file name> · <rows> rows`); Templates (names); Placeholders (`<name>: <option label or "Fixed text “…”">`); Senders (numbers); Estimate (`About ${ceil(size / perDay)} working days` from the selected caps, or `No sender can send` when the sum is 0). Its footer (`WizardFooterWrapper`): `Back` (`useWizardContext().goToPrevStep`), `Save draft`, `Start campaign` (primary), `Cancel`; both submit buttons disabled while busy.
- Submit: `create = useAction(['name', 'templateIds', 'placeholders', 'senderIds'])`; `created = await create.run(() => createCampaign(t, ws, toRequest(...), kind === 'CSV' ? file : undefined))`; on failure the Review shows `create.error` or each field error as a danger alert. Save draft → `navigate(`/campaigns/${id}`)`. Start → `campaignAction(..., 'start')`, then the same navigation; if the start fails, navigate there anyway with `state: { startError: campaignActionMessage(e, 'start') }` (the draft exists; Task 5 shows it).

- [ ] **Step 1: Write the failing tests** (same file; a helper walks Audience and Message for a FILTER campaign of `WELCOME` and, with `csv = true`, for a CSV one):

```ts
it('sendersStepOffersWarmingAndActive', ...)  // p1 WARMING CONNECTED cap 5, p2 ACTIVE DISCONNECTED cap 10, p3 RESTING, p4 SUSPENDED: checkboxes 'Select +919800000001' and '…02'; p2 row 'not connected'; p3 row /rests until/ and no checkbox; p4 absent; Next disabled until one is checked; both checked → 'Selected senders can send about 15 messages a day'
it('reviewSummarisesTheCampaign', ...)        // p1 and p2 selected: 'Saved filter Pune leads · 1,204 contacts'; 'Welcome'; 'About 81 working days' (ceil(1204 / 15))
it('saveDraftPostsTheCampaignAndOpensIt', ...) // requestTo(POST C).body toEqual {name:'Diwali', audience:{kind:'FILTER', filterId:'f1'}, templateIds:['t1'], placeholders:[contact.name → field name, fallback 'there'; contact.attributes.city → attributeId 'a1'], senderIds:['p1']}; callsTo(POST `${C}/c9/start`) 0; heading 'Diwali' on the detail page
it('startCampaignCreatesThenStarts', ...)     // POST C once, POST `${C}/c9/start` once, after the create
it('csvCampaignPostsMultipart', ...)          // partJson(form,'campaign') audience toEqual {kind:'CSV', phoneColumn:'phone', nameColumn:'name'}; placeholders on CSV_COLUMN; form.get('file').name 'leads.csv'
it('startFailureOpensTheDraftWithTheReason', ...) // start 409 CAMPAIGN_STATE → detail page shows 'This campaign cannot be started now.'
it('createErrorsShowOnReview', ...)           // 409 DUPLICATE field name → 'Name is already in use.'; 400 SENDER_NOT_AVAILABLE number '919800000002' → '+919800000002 cannot send campaigns right now.'; 400 TEMPLATE_HAS_LINK → 'Campaign messages cannot contain links.'
it('submitsOnce', ...)                        // gate POST C; click Start campaign twice → callsTo(POST C) 1
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Campaigns/newCampaign.test.tsx`
Expected: the new tests FAIL; Task 7's still pass.

- [ ] **Step 3: Implement** the two steps, `toRequest`, `audienceSize` and the submit flow.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Campaigns && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Campaigns
git commit -m "feat(campaigns): wizard senders and review steps, save draft and start"
```

---

### Task 9: Do not contact in Contacts; campaign sender in the inbox

**Files:**
- Modify: `src/app/api/contacts.ts`, `src/test/contacts.ts`, `src/app/Contacts/ContactsPage.tsx`, `src/app/Contacts/ContactDetailPage.tsx`, `src/app/Contacts/FilterPanel.tsx`, `src/app/api/inbox.ts`, `src/app/Inbox/MessageBubble.tsx`
- Test: `src/app/Contacts/contacts.test.tsx`, `src/app/Contacts/contactFilters.test.tsx`, `src/app/Inbox/thread.test.tsx`

**Interfaces:**
- Produces (Contract assumptions 14, 15):
  - `Contact.doNotContact: { at: string; reason: 'STOP_REPLY' | 'MANUAL'; campaignId: string | null } | null`; `contact()` builder default `doNotContact: null`.
  - `ContactFilterSpec.doNotContact?: boolean`.
  - `markDoNotContact(token, ws, id): Promise<Contact>` (PUT) and `clearDoNotContact(token, ws, id): Promise<Contact>` (DELETE), path `${base(ws)}/contacts/${id}/do-not-contact`.
  - `MessageSource` gains `'CAMPAIGN'`.

- List: a compact red `Label` `Do not contact` after the name link when set.
- Detail: a `Do not contact` group in the `DescriptionList`. When set: the red label, `Replied with a stop word` or `Marked by hand`, and with `contact:edit` a `Clear` button. When not set: `No`, and with `contact:edit` a `Mark do not contact` button. Both replace the contact from the reply; errors inline via `useAction`.
- Filter panel: `FormSelect` `Do not contact` with `Any` / `Only` / `Exclude` → `doNotContact` undefined / `true` / `false` (`cleanSpec` keeps `false`).
- `MessageBubble`: for `CAMPAIGN` the source line is `message.sentBy?.name ?? 'Campaign'`.

- [ ] **Step 1: Write the failing tests**

```ts
// contacts.test.tsx
it('listShowsDoNotContact', ...)          // contact {doNotContact:{at, reason:'STOP_REPLY', campaignId:'c1'}} → row has 'Do not contact'; others do not
it('detailClearsDoNotContact', ...)       // 'Replied with a stop word'; Clear → callsTo(DELETE …/contacts/k1/do-not-contact) 1; then 'No' and 'Mark do not contact'
it('detailMarksDoNotContact', ...)        // Mark do not contact → PUT once; label shown, 'Marked by hand'
it('doNotContactNeedsEdit', ...)          // AGENT_IN_BRAND: label shown, no Clear / Mark buttons
// contactFilters.test.tsx
it('doNotContactFilterIsTriState', ...)   // 'Only' → last search body filter.doNotContact === true; 'Exclude' → false; 'Any' → key absent
// thread.test.tsx
it('showsTheCampaignAsTheSender', ...)    // message {direction:'OUT', source:'CAMPAIGN', sentBy: named('Campaign: Diwali leads')} → bubble contains 'Campaign: Diwali leads', not 'WhatsApp Web'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts src/app/Inbox/thread.test.tsx`
Expected: the new tests FAIL.

- [ ] **Step 3: Implement** as listed.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run type-check && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/api/contacts.ts src/test/contacts.ts src/app/Contacts src/app/api/inbox.ts src/app/Inbox/MessageBubble.tsx src/app/Inbox/thread.test.tsx
git commit -m "feat(campaigns): do-not-contact in contacts and campaign sender in the inbox"
```
