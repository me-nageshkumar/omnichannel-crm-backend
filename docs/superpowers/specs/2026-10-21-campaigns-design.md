# Campaigns Design

Date: 2026-10-21. Repos: `omnichannel-crm-backend`, `omnichannel-crm-frontend` (the extension needs no change; see §5.4).

## 1. Intent

Send a templated first message to a list of leads through the workspace's WhatsApp Web numbers, in a way that keeps those numbers alive for as long as possible. Leads come from marketplaces as CSV files with a phone number and sometimes a name; many numbers are invalid. The user will run a fleet of ten to twenty numbers, on one PC with several Chrome profiles and on several machines, and wants about a thousand first messages a day across the fleet.

The binding priority, in the user's words, is "what is sustainable and avoiding ban for as long time as possible". Every default below is chosen for that, and the user has accepted the warm-up schedule and the pacing that follow from it. Volume comes from adding numbers, never from pushing a number harder.

## 2. Goals, non-goals, anti-goals

**Goals**
- Import a CSV of leads into contacts with a column-mapping dialog, saved mappings, and default values.
- Run a campaign to a saved contact filter or straight from a CSV, with a template whose placeholders map to contact fields, CSV columns, or fixed text with a fallback.
- Pick which numbers send; show each number's state (warming, active, resting) and remaining capacity.
- Track each campaign: running, paused, completed, terminated; totals, sent, delivered, replied, skipped, failed; estimated time left; pause, resume, terminate.
- Enforce the per-number policy (§5) and the fleet rules (§5.6) in the server, not in the UI.

**Non-goals (later enhancements)**
- Scheduling a campaign for a future time, drips and follow-ups, A/B reporting, Cloud API senders, email or push alerts, export of campaign results, SSE progress (polling is enough), multi-instance deployment.

**Anti-goals (a failure even if it works)**
- Anything that lets a campaign exceed the policy from the UI: caps, gaps and hours live in the server and apply to every send.
- Bulk number lookups ahead of sending.
- Sending media or links in campaign messages.
- A campaign that keeps messaging a lead who asked to stop.

## 3. Vocabulary

- **Sender**: a WhatsApp Web phone number of the workspace used by campaigns, with its own state and counters (`campaignSenders`).
- **First message**: any campaign message. Campaign sends are cold by definition and all count toward the caps, whether or not a chat already exists.
- **Recipient**: one row of a campaign's audience, materialised when the campaign starts.
- **Workspace day**: a calendar day in the workspace's campaign timezone.

## 4. Contact import with column mapping

Replaces the header-name-only import in `ContactImportService` for the console; the old by-name behaviour stays as the fallback when no mapping is sent, so the API does not break.

### 4.1 Flow
1. User picks a CSV in the Import dialog. The console posts it to `POST /contacts/import/preview` (multipart `file`, `contact:import`). The server parses with the existing `ContactCsv.parse`, applies the existing limits (5 MB, 20,000 rows), and returns `{ headers: string[], sample: string[][] (first 5 rows), rows: number, suggested: ImportMapping, savedMappings: [{id, name, matches: boolean}] }`. `suggested` is the by-name match used today (phone, name, labels, attributes by name, case-insensitive). `matches` is true when the saved mapping's header signature equals this file's header set (sorted, trimmed, case-insensitive).
2. The dialog shows the mapping step (§4.3). If exactly one saved mapping matches, it is pre-selected; otherwise `suggested` is shown.
3. User confirms. The console posts `POST /contacts/import` with parts `file` and `mapping` (JSON). When `mapping` is absent the server behaves as today.
4. Result summary as today (`created, updated, unchanged, skipped, problems, unknownLabels`); `unknownColumns` now lists headers mapped to nothing, for information only.

### 4.2 Mapping shape
```
ImportMapping {
  phone: string                       // CSV header, required
  name: string | null                 // CSV header or null
  labels: { column: string | null, defaults: string[] /* label ids applied to every row */ }
  attributes: [{ attributeId: string, column: string | null, defaultValue: string | null }]
  createMissingLabels: boolean        // default false; label names in the column that do not exist are created
}
```
**Country code.** Numbers are normalised with `Phones.normalise`; a number of exactly 10 digits then gets the workspace's `defaultCountryCode` (§7.3, default `91`) prefixed before validation. Any other length is validated as typed. The same rule applies to campaign CSV rows at materialisation. My call: marketplace exports in India carry bare 10-digit mobiles, and a bare 10-digit number otherwise passes validation as a wrong foreign number.

Rules: a column may be used by at most one target. An attribute with `column` set uses the cell, and `defaultValue` when the cell is blank. An attribute with no column and a non-blank `defaultValue` gets that value on every imported row. `labels.defaults` are added to every row in addition to the column's names (split on `;` or `,`). Blank values never overwrite existing contact data (same as today). Existing contacts are only added to (same as today).

### 4.3 Dialog layout
A two-column table under a "Saved mapping" select (`None` plus the saved names, matching ones first and marked "matches this file"):

| Contact field | CSV column | Default value |
|---|---|---|
| Phone number (required) | select of headers | – |
| Name | select or "Not imported" | – |
| Labels | select or "Not imported" | label multi-select (applied to every row) |
| one row per attribute, by name | select or "Not imported" | text box, used when the column is missing or the cell is blank |

Below: checkbox "Create labels that do not exist yet", checkbox "Save this mapping as" with a name box, the five sample rows for the chosen phone and name columns, and the row count. Primary button "Import N rows".

### 4.4 Saved mappings
Collection `contactImportMappings`: `{ workspaceId, name, nameKey (unique per workspace), headerSignature: string[], mapping: ImportMapping, createdBy, creationTime, lastUsedAt }`. Endpoints under `/api/workspaces/{ws}/contacts/import-mappings`: `GET` (list), `POST` (create; name duplicate → 409 DUPLICATE on `name`), `DELETE /{id}`. All `contact:import`. Saving happens from the import request itself when `saveAs` is present: `mapping` JSON gains an optional `saveAs: string`; the server saves after a successful import. Choosing a saved mapping whose attribute no longer exists drops that row silently and shows the rest.

### 4.5 Leads with phone only
The dialog only requires the phone column; everything else may be "Not imported". A contact with only a phone is valid today.

## 5. Sender policy (ban avoidance)

All numbers are in the server; the UI only displays them. Numeric values below are defaults in `campaignSettings` (§7.3); an admin may change them, and the settings page shows a warning next to any value looser than the default.

### 5.1 Sender states
`campaignSenders`, one document per `phoneNumberId` (created on first use, also listed for every enabled WhatsApp Web number of the workspace):

```
{ phoneNumberId (unique), workspaceId, state: WARMING|ACTIVE|RESTING|SUSPENDED|RETIRED,
  warmupStartedAt: Instant|null,          // first campaign send, ever or after a restart
  sendingDays: int, lastSendingDay: LocalDate|null,   // age in sending days (§5.2)
  restUntil: Instant|null,                // RESTING: when it returns to WARMING
  runsOn: string|null,                    // free text, e.g. "Office PC, profile 3"
  nextSendAt: Instant|null,               // pacing cursor (§5.3)
  pausedUntil: Instant|null, pauseReason: string|null,   // automatic day pauses (§5.5)
  halfPaceUntil: Instant|null,            // fleet rule (§5.6)
  lowReplyRate: boolean,                  // §5.5, recalculated daily
  dayStartOffsetMinutes: int, dayStartFor: LocalDate,    // §5.3
  logoutAt: Instant|null, lastSendAt: Instant|null, version }
```

- WARMING → ACTIVE automatically when the number's age in sending days (§5.2) reaches 31. ACTIVE never goes back to WARMING by itself.
- Any state → SUSPENDED when WhatsApp logs the number out (§5.4). SUSPENDED → RESTING when the number is connected again, with `restUntil` = reconnection + 7 days. RESTING → WARMING at `restUntil`, with `warmupStartedAt` reset to the first send after that.
- RETIRED and RESTING can be set by an admin (`campaign:settings`); an admin can also move RETIRED/RESTING/SUSPENDED back to WARMING ("Reactivate"), which resets `warmupStartedAt`. Admins cannot set ACTIVE directly.
- Campaigns may select WARMING and ACTIVE senders; the picker shows RESTING with "rests until" and no checkbox; SUSPENDED and RETIRED are hidden from the picker and shown on the Senders page.

### 5.2 Daily cap
Counted per sender per workspace day over `campaignRecipients` with `status in (SENT, DELIVERED, READ, PLAYED, FAILED)` and `attemptedAt` in the day (a failed attempt still cost a send; `PLAYED` is treated as read everywhere). Only campaign sends count; agents' own sends from the inbox do not, on the assumption that they are replies in two-way chats and few New chat first messages. Cap by **age in sending days**: the number of distinct workspace days since `warmupStartedAt` on which the sender attempted at least one campaign send, plus one for today. Calendar days do not advance the age, so a number that rests, is paused, or sits over a weekend does not skip ahead (my call; stricter than calendar age and what "cool off" asks for). Stored on the sender as `sendingDays: int`, incremented by the first attempt of each workspace day.

| Age | Cap |
|---|---|
| 1–3 | 5 |
| 4–7 | 10 |
| 8–14 | 20 |
| 15–21 | 35 |
| 22–30 | 50 |
| 31+ | 75; 100 when `lowReplyRate` is false |

Stored as a list of `{fromDay, cap}` in settings plus `matureCap` (75) and `healthyCap` (100). `lowReplyRate` halves the sender's cap (§5.5). A sender with no `warmupStartedAt` is age 1.

### 5.3 Pacing
- After each action on a sender (a send, a failed send, or a lookup that found the number unregistered), `nextSendAt = now + random(gapMinSeconds, gapMaxSeconds)`; defaults 45 and 180. For an unregistered-number skip the gap is `random(skipGapMinSeconds, skipGapMaxSeconds)`, defaults 15 and 45, because no message was sent.
- Hourly cap: at most `hourlyCap` (12) actions in any rolling 60 minutes, counted over recipients with `attemptedAt` in the window (sends and skips both count).
- Working window: `workingDays` (default Mon–Sat), `startTime` 10:00, `endTime` 18:00, in `timezone`. Each sender starts its day at `startTime + dayStartOffsetMinutes`, where the offset is drawn uniformly from 0–60 once per workspace day and stored on the sender.
- Lookups: `NumberLookupService.lookup(ws, senderId, phone, false)` immediately before the send, inside the same tick. Never ahead of time, never in bulk. A cached answer touches no device: a cached "not registered" skip sets no gap and counts toward no cap; only device actions (a send, a failed send, a device lookup that found the number unregistered) set `nextSendAt` and count toward the hourly cap.
- `halfPaceUntil` in the future doubles both gap bounds.

### 5.4 Device events the runner listens to
- Not ready (disconnected, waiting for login, disabled): the sender is simply skipped by every tick; no state change. The Senders page shows the live phone status next to the campaign state.
- Logged out: `WhatsAppEvent` named `change_state` whose payload `state` is `UNPAIRED` or `UNPAIRED_IDLE` (the raw WhatsApp Web state; the extension already forwards it through `AppState change:state`; see `BridgeWebSocketHandler.handleEvent`). On it: `state = SUSPENDED`, `logoutAt = now`, fleet rule §5.6 applies. The builder confirms by reading `whatsapp-web.js/src/Client.js` what state a remote logout produces; if `disconnected` with a reason is the reliable signal instead, use that. Either way the detection lives in one listener class.
- Reconnection: `WhatsAppEvent` `ready` on a SUSPENDED sender → RESTING with `restUntil = now + restDays (7)`. UNPAIRED already makes `requireReady` fail, so no send can slip through between the event and the state change.

### 5.5 Automatic pauses per sender (reason stored, shown on Senders and campaign pages)
- `UNDELIVERED_RUN`: the sender's last 5 sends, each at least 10 minutes old, are all still below DELIVERED → `pausedUntil` = end of the workspace day.
- `LOW_REPLY_RATE`: recalculated by a daily job at the start of each workspace day: of the sender's last 30 sends that are at least 24 hours old, fewer than 3 got a reply → `lowReplyRate = true` (cap halved); it clears when the same calculation passes again. Needs at least 30 such sends to evaluate.
- An admin cannot override an active pause except by resuming the sender from the Senders page, which clears `pausedUntil`.

### 5.6 Fleet rules (per workspace)
- One logout: every other WARMING/ACTIVE sender in the workspace gets `halfPaceUntil` = end of the workspace day.
- Two logouts within 7 days (`logoutAt` on any senders of the workspace): every RUNNING campaign in the workspace becomes PAUSED with `pausedBy = SYSTEM`, `pauseReason = TWO_LOGOUTS`; an admin resumes them one by one.
- A phone is messaged at most once per campaign (materialisation dedupes) and not again by any campaign within `recontactDays` (7): a recipient whose phone has a SENT/DELIVERED/READ recipient in the workspace in that window is skipped with `RECENTLY_MESSAGED`.

### 5.7 Content rules
- Campaign templates must be `TEXT`, not archived, and their body must not contain `http://`, `https://` or `www.` (checked on create and again on start; 400 `VALIDATION` on `templateIds`).
- A campaign takes 1 to 5 templates (variants). Recipients are assigned a template round-robin at materialisation. The wizard warns below 3 variants ("Fewer than three variants makes the fleet's messages look alike") but does not block. My call: blocking would make a quick campaign impossible; the name placeholder already varies the text.
- Stop words: an inbound message on a chat whose **last outbound message is a campaign message** (once an agent has written, "no" is an answer to the agent, not to the campaign) whose trimmed, lower-cased text equals one of `stopWords` (default `stop, unsubscribe, remove me, do not message, don't message, no`) marks the contact `doNotContact = { at, reason: STOP_REPLY, campaignId }`, skips that phone's PENDING recipients in every campaign (`DO_NOT_CONTACT`), and the chat stays open for an agent. The contact page shows a "Do not contact" label with a "Clear" action (`contact:edit`), and manual marking from the contact page is allowed (`reason: MANUAL`). Materialisation skips do-not-contact contacts. `ContactFilterSpec` gains `doNotContact: Boolean` so a saved filter can exclude or target them.

## 6. Campaigns

### 6.1 Model
```
campaigns {
  id, workspaceId, customerId, name, nameKey (unique per workspace),
  audience: { kind: FILTER|CSV,
              filterId, filterName, filterSpec (snapshot of the saved filter at creation),
              csvFileName, csvHeaders: string[], csvRows: number },
  templateIds: string[] (1..5), templateNames: string[],
  placeholders: [{ name, source: { kind: CONTACT_FIELD|CONTACT_ATTRIBUTE|CSV_COLUMN|FIXED, field|attributeId|column|text }, fallback: string }],
  senderIds: string[],
  status: DRAFT|RUNNING|PAUSED|COMPLETED|TERMINATED,
  pausedBy: USER|SYSTEM|null, pauseReason: string|null,
  counts: { total, pending, sent, delivered, read, replied, skipped, failed },   // maintained by the runner, recomputed on read of one campaign
  createdBy, creationTime, startedAt, finishedAt, lastUpdateTime, version }

campaignRecipients {
  id, campaignId, workspaceId, phone (digits), contactId|null, templateId,
  values: Map<String,String> (CSV cells for CSV campaigns; empty for FILTER),
  status: PENDING|SENDING|SENT|DELIVERED|READ|FAILED|SKIPPED,     // READ covers PLAYED acks
  skipReason: NOT_ON_WHATSAPP|DO_NOT_CONTACT|RECENTLY_MESSAGED|NO_PHONE|DUPLICATE|CHAT_ON_OTHER_NUMBER|CANCELLED|null,
  preferredSenderId|null,   // the sender that already has a chat with this phone (§6.3 step 1)
  senderId|null, chatId|null, messageId|null, attemptedAt, sentAt, deliveredAt, readAt, repliedAt, error|null, attempts }
```
Indexes: recipients unique `(campaignId, phone)`; `(campaignId, status)`; `(workspaceId, phone, sentAt)` for `RECENTLY_MESSAGED`; `(senderId, sentAt)` and `(senderId, attemptedAt)` for caps; `(messageId)` for acks. Campaigns `(workspaceId, status)`.

`WaWebMessage` gains `campaignId` and `MessageSource.CAMPAIGN`; the inbox shows the sender name as "Campaign: <name>". `WaWebChat` gains `campaignId` (the campaign that created it, null otherwise).

### 6.2 Lifecycle
- **Create** (`POST /campaigns`, `campaign:manage`): body `{ name, audience: {kind, filterId} | {kind: CSV, phoneColumn, nameColumn|null}, templateIds, placeholders, senderIds }`; for CSV the request is multipart with parts `campaign` (JSON) and `file`. Validation: name, 1–5 templates per §5.7, every placeholder of every chosen template mapped (FILTER campaigns: CONTACT_* placeholders default to their field or attribute and AGENT placeholders need a source; CSV campaigns: every placeholder, contact ones included, needs a CSV column or fixed text, since there is no contact to read from; `{{contact.phone}}` may also map to the phone column), senders exist in the workspace, are WhatsApp Web, enabled, and WARMING/ACTIVE, at least one. Saved as DRAFT. A DRAFT can be edited (`PUT`) and deleted (`DELETE`).
- **Start** (`POST /campaigns/{id}/start`): DRAFT → RUNNING. Materialises recipients synchronously in pages of 500 ordered by `_id` (cursor, never skip/limit): FILTER → `ContactQuery.build(ws, filterSpec)` restricted to contacts with a phone; CSV → the stored rows. Each row becomes a recipient or a SKIPPED one (`NO_PHONE`, invalid phone, `DUPLICATE` within the campaign, `DO_NOT_CONTACT`, `RECENTLY_MESSAGED`). The CSV rows are stored in GridFS at create time (bucket `campaignCsv`) and deleted after materialisation. Counts are set. Materialisation of 20,000 rows must finish within the request; the builder keeps it to a handful of bulk writes per page.
- **Pause** (`POST /{id}/pause`): RUNNING → PAUSED, `pausedBy = USER`. In-flight sends finish. **Resume** (`POST /{id}/resume`): PAUSED → RUNNING, clears `pausedBy`/`pauseReason`; allowed for SYSTEM pauses too (that is the admin's acknowledgement).
- **Terminate** (`POST /{id}/terminate`, confirm in UI): RUNNING/PAUSED → TERMINATED; all PENDING recipients → SKIPPED `CANCELLED`. Final.
- **Complete**: the runner sets COMPLETED when no PENDING or SENDING recipients remain. Final.
- Illegal transitions → 409 `CONFLICT`.

### 6.3 Runner
- A dedicated `ThreadPoolTaskScheduler` bean named `campaignScheduler` (pool 2, prefix `campaign-`), separate from the existing `taskScheduler` bean in `TemplateMediaConfig` (pool 1, which must keep its name so `@EnableScheduling` keeps using it); the tick is registered on `campaignScheduler` explicitly (`@Scheduled(scheduler = "campaignScheduler")` or programmatic scheduling), so inbox pings and the media sweeper are never blocked. One tick every `tickSeconds` (10). The tick is also a public method so ITs call it directly with a `MutableClock`.
- Tick, per workspace with a RUNNING campaign: if outside the working window, do nothing. Otherwise for each sender referenced by a RUNNING campaign, eligible when state WARMING/ACTIVE, device ready (`InboxDevices.requireReady` semantics, surfaced through a package-public method), not paused, day started, under daily and hourly caps, `nextSendAt <= now`:
  1. Claim the next PENDING recipient across that sender's RUNNING campaigns, oldest campaign first (`findAndModify` PENDING → SENDING with `senderId`, `attemptedAt`), restricted to recipients whose `preferredSenderId` is null or equals this sender. At materialisation a recipient whose contact already has a chat on one of the campaign's senders gets that sender as `preferredSenderId`, so a lead always hears from the same number; a recipient whose only existing chat is on a number outside the campaign is SKIPPED `CHAT_ON_OTHER_NUMBER` (visible in counts, so the user can add that sender and run again). Otherwise a recipient is claimed by whichever eligible sender reaches it first.
  2. Lookup. Unregistered → SKIPPED `NOT_ON_WHATSAPP`, short gap. Lookup failure → recipient back to PENDING, `attempts + 1`, normal gap; after 3 attempts → FAILED with the error.
  3. Render the template through `TemplateRenderer` with the recipient's values: FILTER campaigns pass `contactId` and the fixed/AGENT values; CSV campaigns pass every placeholder as an agent value (the renderer's 1000-character agent limit applies and truncates; a CSV cell longer than that is a data problem, not an error). CSV campaigns create contacts only through the send itself (the existing chat creation upserts a contact); when `nameColumn` is set and the created or found contact has no name, the campaign sets it. The renderer needs a path without `AuthenticatedUser`: introduce `TemplateRenderer.renderAs(SystemActor, ...)` or split the authority check out of `render`; builder's choice.
  4. Send through the new-chat mechanics without a user: refactor `NewChatService`/`InboxSendService` so the "actor" is an interface with `userId|null`, `displayName`, `source`. Campaign actor: `userId = null`, `displayName = "Campaign: <name>"`, `source = CAMPAIGN`. Existing chat (by contact or `phone@c.us` or looked-up lid) → send into it, keep its assignment, reopen if closed. No chat → create it unassigned with `campaignId`. `ChatRoutingListener` already routes only chats whose first message is inbound, so campaign-born chats are not routed at creation; what is new is routing on the first inbound message: `InboxIngestService.foldIntoChat` publishes a routing event when `chat.campaignId != null && assignedUserId == null` and the chat had no inbound message before.
  5. On success: recipient SENT with `chatId`, `messageId`, `sentAt`; counts updated; `lastSendAt`, `nextSendAt` on the sender. On `RpcException`/timeout: FAILED with the error (one attempt; a failed send still costs a cap slot).
  6. Acks: a listener on `InboxEvents.MessageUpserted` with `message.campaignId != null` moves the recipient to DELIVERED/READ by `messageId` and updates counts. Inbound on a chat with `campaignId` or whose last outbound was a campaign message → `repliedAt` on the latest recipient for that chat, `counts.replied`, stop-word check (§5.7).
  7. After the loop, campaigns with no PENDING/SENDING → COMPLETED. §5.5 pause checks run per sender after each send.
  8. Stuck claims: a recipient in SENDING for longer than the RPC timeout plus 2 minutes (a restart or a device that never answered) becomes FAILED with error `INTERRUPTED`, never retried, because a retry risks a second message to a cold lead. Its `attemptedAt` still counts toward the caps. Checked at startup and on every tick.
- The runner never sends more than one message per sender per tick, which with a 10-second tick and a 45-second minimum gap is already the case.
- Everything reads the `Clock` bean.

### 6.4 Estimated time left
`GET /campaigns/{id}` returns `estimate: { remaining, perDay, days, finishBy }` where `perDay` is the sum over the campaign's eligible senders (WARMING/ACTIVE, not RETIRED/SUSPENDED/RESTING) of their current daily cap (halved when `lowReplyRate`), `days = ceil(remaining / perDay)` counted in working days, and `finishBy` the date that many working days ahead. `perDay = 0` → `days = null` and the UI says "No sender can send". Shown as "About 3 working days (by Fri 24 Oct)". Good enough; the runner's real rate is below the caps.

### 6.5 Endpoints (`/api/workspaces/{ws}/campaigns`, authorities in §8)
- `GET` list (status filter, paged 50, newest first), `POST`, `GET /{id}`, `PUT /{id}` (DRAFT only), `DELETE /{id}` (DRAFT only), `POST /{id}/start|pause|resume|terminate`.
- `GET /{id}/recipients?status=&page=` (paged 50) with phone, contact name when known, status, skipReason, sender, sentAt, repliedAt, error.
- `GET /templates/{id}/sample-csv` → `text/csv` with header `phone` then one column per placeholder name of that template, one example row from the placeholder examples (`campaign:view`).
- `POST /csv/preview` (multipart `file`) → same shape as §4.1 for the campaign CSV: `{ headers, sample, rows, suggested }` where `suggested` maps `phone` and each placeholder name to the equal-named header.
- Senders: `GET /senders` (every enabled WhatsApp Web number of the workspace with its campaign state, age day, today's cap, used today, hourly used, live phone status, pause info, runsOn), `PUT /senders/{phoneNumberId}` (`runsOn`, and `action: REST|RETIRE|REACTIVATE|RESUME`; `campaign:settings`).
- Settings: `GET/PUT /settings` (`campaign:settings`), §7.3.

## 7. Console

### 7.1 Navigation and authorities
New Brand pages: **Campaigns** (`/campaigns`, `campaign:view`), with `/campaigns/new` and `/campaigns/:id`; **Senders** (`/campaigns/senders`, `campaign:view`, actions need `campaign:settings`); **Campaign settings** (`/campaigns/settings`, `campaign:settings`). Senders and settings are reached from the Campaigns page toolbar, not the nav.

### 7.2 New campaign wizard (PatternFly `Wizard`, new to this codebase)
1. **Audience**: name; radio "Saved contact filter" (select, shows "N contacts" via `countContacts`, link "Manage filters" to Contacts) or "CSV file" (FileUpload; after upload, headers and row count, a "Phone column" select pre-set to a header named `phone` and an optional "Name column" select). A note under CSV: "Rows need a phone column; other columns can fill template placeholders. 10-digit numbers get the default country code."
2. **Message**: pick 1–5 active TEXT templates (search list from `listTemplates`, chips for the chosen ones, warning under 3, templates with links shown disabled with "Contains a link"). Below, one row per distinct placeholder across the chosen templates: name, source select (for FILTER: contact field/attribute list plus "Fixed text"; for CSV: the CSV headers plus "Fixed text"), text box (fixed text or fallback). "Download sample CSV" link appears here for CSV campaigns before a file is chosen, in step 1 as well. Preview bubble rendered with the first matching contact of the filter, or the first CSV row.
3. **Senders**: table of senders (state label, age day, cap today, used today, phone status), checkboxes on WARMING/ACTIVE with a connected device; RESTING rows shown without checkbox and "rests until"; disconnected WARMING/ACTIVE rows selectable but marked "not connected". Footer: "Selected senders can send about N messages a day".
4. **Review**: audience summary, templates, placeholder mapping, senders, estimated days. Buttons "Save draft" and "Start campaign".

### 7.3 Pages
- **Campaigns list**: name, status label, progress "sent / total" with a bar, replied, skipped, started, actions (Start for DRAFT, Pause/Resume, Terminate with ConfirmModal, Delete for DRAFT). Banner at the top when any campaign is PAUSED by SYSTEM: "Campaigns were paused after two numbers were logged out this week. Review the senders before resuming."
- **Campaign detail**: counts tiles (total, pending, sent, delivered, read, replied, skipped, failed), estimate, per-sender today table, recipients table with status filter and paging, actions. Polls every 5 s while RUNNING. Next to the counts a small countdown shows the refresh: a PatternFly `Progress` bar (size `sm`, no percentage text) that drains from full to empty over the 5 s with the label "Refreshing in 5 s", "4 s" … "1 s", then resets to full when the fetch starts; while a fetch is in flight the label reads "Refreshing…". The countdown stops on PAUSED, COMPLETED and TERMINATED (the page then shows "Refreshed <time>" and a Refresh button). A fetch error keeps the last numbers, shows an inline danger alert, and the countdown continues.
- **Senders**: table of all workspace WhatsApp Web numbers: number, label, phone status, campaign state, age day, cap today, used today, pause reason, "runs on" (inline edit), actions Rest / Retire / Reactivate / Resume pause.
- **Campaign settings**: timezone (IANA select), default country code (digits, default `91`), working days, start and end time, then pacing values (gaps, hourly cap, warm-up table, mature and healthy caps, rest days, recontact days, stop words). Each pacing field shows a warning "Looser than the recommended value" when looser than the default. `campaignSettings` is one document per workspace, created with defaults on first read; `timezone` defaults to `Asia/Kolkata` (my call: the user's numbers are Indian).

### 7.4 Contacts changes
Import dialog per §4.3. Contact detail and list show a "Do not contact" label; the filter panel gets a "Do not contact" tri-state (any / only / exclude).

### 7.5 Inbox changes
Campaign messages show "Campaign: <name>" as the sender. No other change.

## 8. Authorities
- `campaign:view` (BRAND): see campaigns, senders, recipients. Defaults: Customer Admin, Workspace Manager, Team Lead, Agent.
- `campaign:manage` (BRAND): create, edit drafts, start, pause, resume, terminate, delete drafts. Defaults: Customer Admin, Workspace Manager, Team Lead.
- `campaign:settings` (BRAND): campaign settings, sender states, runsOn. Defaults: Customer Admin, Workspace Manager.
- Import mappings use `contact:import`. Do-not-contact clearing uses `contact:edit`.

## 9. Error codes (console sentences in `messages.ts`)
- `CAMPAIGN_NOT_EDITABLE` 409: "Only a draft campaign can be edited."
- `CAMPAIGN_STATE` 409: "This campaign cannot be <started|paused|resumed|terminated> now."
- `TEMPLATE_HAS_LINK` 400 on `templateIds`: "Campaign messages cannot contain links."
- `TEMPLATE_NOT_TEXT` 400: "Only text templates can be used in campaigns."
- `SENDER_NOT_AVAILABLE` 400 on `senderIds`: "<number> cannot send campaigns right now."
- `PLACEHOLDER_UNMAPPED` 400 on `placeholders`: "Every placeholder needs a value."
- `MAPPING_COLUMN_REUSED` 400: "A CSV column can be used only once."
- Import: `PHONE_COLUMN_REQUIRED` 400: "Choose the column that holds the phone number."

## 10. Testing expectations
- Backend ITs with `FakeDevice` and `MutableClock`: warm-up cap by age, gap and hourly cap, working window and timezone, day start offset, lookup-before-send and `NOT_ON_WHATSAPP` skip, `RECENTLY_MESSAGED`, do-not-contact from a stop word, undelivered run pause, low reply rate halving, logout → SUSPENDED → RESTING → WARMING, one logout halves pace, two logouts pause campaigns, pause/resume/terminate transitions, completion, estimate, materialisation of a 20,000-row CSV and a filter in `_id` order with dedupe, import mapping with defaults and saved mappings, authorities (the `AuthorityCheckScannerIT` catalogue).
- Console Vitest: import mapping dialog (preview, saved mapping auto-select, defaults, save-as), wizard steps and validation, campaigns list actions, detail polling, senders page actions, settings warnings.

## 11. Left to the builder
- Exact class split in a new `campaign` package; whether `TemplateRenderer`/`InboxSendService` get an actor interface or a parallel entry point (the behaviour in §6.3 is fixed either way).
- How the logout state is detected (§5.4), after reading whatsapp-web.js.
- GridFS vs a `campaign_csv_rows` collection for the uploaded CSV before materialisation.
- Which PatternFly components implement the wizard and the mapping table.

## 12. Decisions I made that the user may want to check
- All campaign sends count toward the caps, even into existing chats.
- Minimum variants is a warning, not a block (§5.7).
- Links and media are blocked outright in campaign templates (§5.7).
- A phone is not re-messaged by any campaign for 7 days (§5.6).
- Campaign chats are born unassigned and routed on first reply, so a thousand sends do not flood agents' inboxes (§6.3).
- Campaign messages into an existing assigned chat keep that assignment.
- Stop-word list and the default `no` in it (§5.7); `no` is in because a one-word "no" to a cold message is a refusal.
- Timezone default `Asia/Kolkata`; working hours 10:00–18:00 Mon–Sat.
- Progress by polling every 5 s with a visible 5-4-3-2-1 countdown bar, not SSE.
- No sender may be set ACTIVE by hand; every number earns it through 30 sending days of warm-up.
- Age counts sending days, not calendar days (§5.2); only campaign sends count toward caps, agents' inbox sends do not.
- 10-digit numbers get the default country code `91` on import and in campaign CSVs (§4.2).
- A lead with an existing chat is messaged from that number or skipped, never from a second number (§6.3).
- A claim interrupted by a restart or a silent device is failed, not retried (§6.3 step 8).
- Stop words apply only while the campaign message is the last thing the business said (§5.7).
- CSV campaigns do not import contacts; the send creates them, and the name column fills the name (§6.2).
