> **Status: DEFERRED (2026-10-23).** Brainstormed but not planned. Before picking it up, re-base on the chat-assignment design (`2026-10-23-chat-assignment-design.md`): team-owned numbers replace `crmNumberAccess`, and `crm:*` default-role lists must drop TEAM_LEAD.

# CRM for Omnichannel — Design

Source requirement: `/Users/nagesh/RedLava/SCM/CRM/crm-requirements.md` (written for a different host platform).
This design adapts its intent to our app (`omnichannel-crm-backend`, `omnichannel-crm-frontend`).
On approval it is saved as `omnichannel-crm-backend/docs/superpowers/specs/2026-10-23-crm-design.md` and committed,
then each phase gets a backend plan and a console plan via superpowers:writing-plans.

**What changed in this revision:** leads enter pipelines **automatically per phone number** (§6), visibility is by
**assigned phone numbers** instead of own/team/all (§4), the chat-grants-access rule is gone (no longer needed),
Companies became optional per brand, and the acceptance checks were rewritten to match.

---

## 1. Context and intent

Brands talk to people over WhatsApp, but nothing tracks *where each person is* in a business process. The CRM adds
admin-defined pipelines (kanban) fed by WhatsApp conversations, with a timeline, tasks, notes, segments that launch
campaigns, and reports. Three moments it exists for:

1. **A conversation becomes a lead on its own.** A person messages a sales number at 11 pm; next morning the card is
   already on the board. An agent can also capture by hand from the chat.
2. **A manager watches the board** across agents and numbers, with live updates and overdue follow-ups.
3. **A marketer segments by CRM data** and sends a campaign through our existing campaign engine, per number.

**Generic (user's decision):** it must suit *any business*, not only sales, and be built from *reusable pieces*.
- Customer admins create their own pipelines; **each brand has its own** pipelines, stages, fields and settings.
- **Each pipeline has its own field set.** "Deal" is just a default label; a pipeline names its cards
  ("Admission", "Booking", "Order", "Ticket").
- Kanban board, timeline, custom-field engine (definitions → validation → form/table/card rendering) and the record
  drawer are shared components/services usable outside the CRM.

**Channel rule (user's decision, already how our app works):** a contact who arrived on a phone number is only ever
contacted through that number. (Campaigns already do this: `preferredSenderId`, skip `CHAT_ON_OTHER_NUMBER`.)

**Visibility rule (user's decision):** unlike the inbox, where a user works one number's chats at a time, a CRM user
sees **all leads on every phone number assigned to them**, and narrows with a phone-number filter (all or some).

## 2. Goals, non-goals, anti-goals

**Goals** — whole requirement, delivered in phases (§10): pipelines/stages/fields per brand, automatic + manual
intake, CRM contacts, optional companies, cards, board with live updates, timeline, inbox panel, tasks with reminders,
notes with attachments, picklists, CSV import/export, rotting, segments → per-channel campaigns, reports and dashboard.

**Non-goals (v1)**
- Lead ingestion/conversion from Facebook Lead Ads or web forms — no lead intake exists. Origin kinds are recorded so
  it can slot in later.
- Billing/plans. Gating is a per-brand toggle (§4.3) that billing can drive later.
- An automation engine. CRM publishes in-process events (§8.4) for one to consume later.
- Browser/mobile push. Reminders are in-app (§7.2).
- User-defined record *types*. Records are Contact, Company, Card; configurability is via pipelines, per-pipeline
  fields and picklists.
- Merging CRM contacts, moving chat history between numbers, email/calendar integration.

**Anti-goals (failure even if it works)**
- Any outbound message from the CRM on a number other than the record's channel; any phone-number picker for
  communication.
- Brands without CRM seeing any CRM element, CRM request, or loading CRM code (inbox and contacts must not regress).
- A support number's every complaint becoming a "lead" because intake can't be switched per number.
- Sales vocabulary hard-coded where a business would need to rename it.
- CRM writing to existing collections or creating indexes on them.

## 3. Mapping the requirement onto our app

| Requirement says | We do | Why |
|---|---|---|
| D6 CRM scoped to customer | Scoped to **brand (workspace)** | Our contacts, numbers, teams, campaigns are per brand; user chose per-brand pipelines |
| D8 separate deployable service | Package `me.nageshk.omnichannel.crm` in the same jar | One jar; still own-storage-only (D5) |
| §4.5 token introspection | Existing JWT auth (`JwtAuthFilter`) | Same process |
| D9 snake_case permissions | `crm:*` codes in `noun:verb` style | Our `Authority` convention |
| D2 add-on per phone number | Per-brand CRM toggle + limits | No billing; CRM data is per brand |
| D3 own / team / all record visibility | **Assigned phone numbers** decide visibility; owner is a field, not a fence (user's decision) | The user wants everyone on a number to see that number's leads |
| Agents assigned to subsets of numbers | **New:** CRM keeps a per-user set of numbers per brand (`crmNumberAccess`); admin roles see all | The platform has no such assignment today; keeping it in CRM storage honours D5 |
| Capture is manual | Manual capture **plus** per-number automatic intake into a chosen pipeline (user's decision) | Night-time enquiries must land on the board without an agent |
| Same person on two numbers = two CRM contacts | Kept: one CRM contact per platform contact **per number** (user's decision) | Each number's team works its own people |
| Every contact has a channel from creation | Manual/imported CRM contacts may have **no number yet**; the first message fixes it (user's decision) | Our contacts can exist without any chat |
| Problem-detail errors | Existing `{code, message}` via `CodedException` / `ApiExceptionHandler` | House convention |
| Lead conversion | Out of scope | No lead intake exists |
| Push reminders | In-app via CRM SSE stream | No push mechanism |
| `crm_*` events to automation engine | Spring `ApplicationEvent`s (`CrmEvent`) | No automation engine yet |

Collections: camelCase with `crm` prefix (`crmPipelines`, `crmDeals`, …), per the Mongo naming memory.

## 4. Access

### 4.1 Authorities (added to `access/Authority.java`, BRAND level unless noted)

| Code | Grants | Default roles |
|---|---|---|
| `crm:view` | Open the CRM; see every record on the user's assigned numbers | all brand roles incl. AGENT |
| `crm:view-all` | See records on **all** of the brand's numbers (no assignment needed) | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:edit` | Create/edit contacts, companies, cards, tasks, notes; capture; move; close; claim — on visible numbers | CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD, AGENT |
| `crm:delete` | Delete records on visible numbers | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:settings` | Pipelines, stages, fields, picklists, intake rules, number assignment, general settings, channel moves | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:segment` | Segments and campaign launches from segments (launch also needs `campaign:manage`) | CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD |
| `crm:import` / `crm:export` | CSV import / export | CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD |
| `crm:enable` (PLATFORM) | Turn CRM on/off for a brand, set limits | ACCOUNT_MANAGER |

`crm:view-team` from the requirement is dropped: number assignment replaces the own/team/all ladder.

### 4.2 Scoping — every read and write, fail closed

1. **Brand:** URL `/api/workspaces/{ws}/crm/...`; `@PreAuthorize("@access.has(#workspaceId, 'crm:…')")`; every
   query carries `workspaceId` (no unscoped repository finders). A record of another brand is "not found".
2. **Visible numbers (the only visibility fence):**
   - *allowed* = all of the brand's numbers for `crm:view-all`, else the user's entry in **`crmNumberAccess`**
     `{workspaceId, userId, phoneNumberIds}` (edited on the CRM Settings → "Who works which number" page, and from the
     brand's Users page when CRM is on). No entry = no numbers = every list is empty and every record is "not found".
   - *selected* = requested filter ∩ allowed; empty filter = all allowed; an intersection that leaves nothing → 404.
   - Records carry `phoneNumberId` (channel). Records with **no number yet** are visible to everyone with `crm:view`
     (shown under a "No number yet" chip). Records whose number was **deleted** from the brand appear only under an
     admin-only "Removed number" filter (`crm:settings`), where they can be moved.
   - Companies are brand-wide; they carry the set of channels of their contacts/cards and show when that set is empty
     or intersects *selected*.
   - The filter narrows lists, boards and reports only; saves and moves are checked against *allowed*, never the
     filter. Mutations on a record whose number is outside *allowed* → 404.
3. **Owner is a field:** `ownerId` on contacts and cards drives "My leads", the unassigned badge, reports and
   reminders — never visibility.

A single `CrmAccess` service builds a `CrmContext(workspaceId, customerId, userId, allowedNumbers, selectedNumbers,
limits)` once per request; every repository query takes it. A query that cannot establish the context fails.

### 4.3 Enablement (per-brand toggle)

- `crmBrandSettings` (one per brand) holds `enabled`, `disabled` (`enablement/Disabled`), `maxPipelines`,
  `maxCustomFields` (null = unlimited). Toggled at `PUT /api/workspaces/{ws}/crm/enablement` with `crm:enable`, using
  the existing `Enablement` rules and `EnablementChange` audit (`targetType = "crm"`).
- Every CRM endpoint except enablement and status answers **402 `CRM_NOT_ENABLED`** when off; missing `crm:view` → 403.
  Automatic intake does nothing for brands with CRM off. The console renders both as quiet states.
- `GET /api/workspaces/{ws}/crm/status` → `{enabled, limits, authorities, allowedNumbers}`; the console calls it
  once per brand switch and only loads CRM code / shows CRM nav when enabled and `crm:view` is held.
- Settings and a default pipeline are seeded lazily the first time an admin opens CRM settings or intake fires.

## 5. Data model (new collections only; every index leads with `workspaceId`; `@Version` on every mutable record)

### 5.1 Settings, numbers, pipelines
- **`crmBrandSettings`**: enablement fields; `currency`; `rottingDays`; `timelineShowsMessages`;
  `allowChannelMoves`; `companiesEnabled` (*my call:* off hides the Companies nav, fields and drawers for brands that
  sell to individuals); picklists `lifecycleStatuses`, `sources`, `taskTypes`, `lostReasons`, `industries` — each
  item `{key, label, position, active, isDefault}`.
- **`crmNumberSettings`** (one per phone number of the brand): **`intake`** `{mode MANUAL|AUTO, pipelineId,
  reentry NEW_CARD|REOPEN_LAST|NONE}` (§6.1), `defaultOwnerMode CHAT_ASSIGNEE|UNASSIGNED`. Missing document = MANUAL.
- **`crmNumberAccess`**: `{workspaceId, userId, phoneNumberIds[]}`, unique per user per brand.
- **`crmPipelines`**: `name`, `cardLabel {singular, plural}`, `isDefault` (exactly one), `position`, `showValue`,
  `showCloseDate`, `stages[]` embedded `{id, name, color, position, probability, type OPEN|WON|LOST, wipLimit}`.
  Bounded by `maxPipelines`. Starter templates (Sales, Orders, Admissions, Support, Bookings, Blank) are code-side data.
- Stage removal / pipeline delete / stage type change while holding cards → **409 `STAGE_HAS_DEALS`** with
  `{stageId, count}` list; the client sends a relocation target; relocation runs first; deleting the default pipeline
  promotes another; a pipeline used by an intake rule cannot be deleted until the rule is changed (409
  `PIPELINE_IN_USE`). Changes broadcast on the brand's CRM stream.

### 5.2 Custom fields — the reusable field engine (`crm.field`)
- **`crmFields`**: `entity` CONTACT|COMPANY|DEAL, `pipelineId` (required for DEAL; null otherwise), `key`
  (immutable, unique per entity+pipeline), `label`, `type` (TEXT, LONG_TEXT, NUMBER, CURRENCY, BOOLEAN, DATE,
  DATETIME, SELECT, MULTI_SELECT, URL, EMAIL, PHONE, USER), `options`, `required`, `default`, `position`,
  `showInList`, `showInCard`, `active`. Count bounded by `maxCustomFields`.
- Values live in each record's `fields: Map<String, Object>`; `FieldValidator` validates per type on every save;
  unknown keys rejected; inactive fields kept, not editable. Required fields are **not** enforced on cards created by
  automatic intake (there is no human to fill them); the card shows a "needs details" badge until saved by a person.
- Relocating a card to another pipeline keeps values whose key exists in the target and records dropped ones in the
  activity payload.
- Frontend `FieldForm`, `FieldValue`, `fieldColumns()` render any entity from definitions.

### 5.3 Records
- **`crmContacts`** — the CRM profile of a platform contact **on one number**: unique `{workspaceId, contactId,
  channel}`. `channel` (= `phoneNumberId`, server-derived; **null = no number yet**; at most one no-number record per
  platform contact, and only while it has none on any number). `origin {kind INBOUND_CHAT|CTWA_AD|MANUAL|IMPORT|API,
  campaignId?, firstMessageAt, capturedBy (user id or SYSTEM)}` set once (*my call:* `CTWA_AD` kept but never set in
  v1 — no ad-referral data is stored). Name/phone snapshot, first/last name, email, jobTitle, `companyId`, `ownerId`,
  `teamId`, `lifecycle`, `source`, `fields`, counters (`openDealCount`, `openDealAmount`, `lastActivityAt`,
  `nextTaskDueAt`), `channelHistory[]`, `channelRemoved` (derived at read time).
- **`crmCompanies`** — name, domain, industry, size, annualRevenue, website, phone, email, address{…}, description,
  owner, team, labels, `fields`; derived `contactCount`, `openDealAmount`, `phoneNumberIds`. Delete refused while
  open cards exist (409 `COMPANY_HAS_OPEN_DEALS`).
- **`crmDeals`** (UI label from the pipeline) — `channel` (= primary CRM contact's channel incl. null; re-derived when
  that changes; linked contacts must share it), `title` (auto-intake default: contact name + pipeline card label),
  `pipelineId`, `stageId`, `position` (sparse double), `status` (from stage type), `amount`, `currency`,
  `probability`, `expectedCloseDate`, `closedAt`, `lostReason`, `companyId`, `primaryContactId` + snapshot,
  `contactIds`, `ownerId` (**nullable = unassigned**), `teamId`, `source`, `labels`, `fields`, `stageEnteredAt`,
  `rotting`, `lastActivityAt`, `nextTaskDueAt`, `archived`, `createdBy {USER id | INTAKE}`, `returning` (bool),
  `intakeKey` (unique sparse `{workspaceId, intakeKey}`, §6.1).
- **`crmActivities`** — append-only: `type` (RECORD_CREATED, FIELD_CHANGED{diffs}, STAGE_CHANGED, DEAL_WON,
  DEAL_LOST, DEAL_CLAIMED, NOTE_ADDED, TASK_CREATED, TASK_COMPLETED, CALL_LOGGED, MEETING_LOGGED, EMAIL_LOGGED,
  WHATSAPP_CAMPAIGN_SENT, CHANNEL_CHANGED, SETTINGS_CHANGED, SYSTEM), `target {type,id}`, `related[]`, `channel`,
  `actor {type USER|AUTOMATION|SYSTEM, id}`, `at`, `summary`, `payload`. Reusable `ActivityLog` service.
- **`crmTasks`**, **`crmNotes`** (+ GridFS `crmNoteFiles`) in phase 2; **`crmSegments`** in phase 3.

Counters on contacts/companies are recomputed on every card write under optimistic locking (retry on version
conflict), so a stale save cannot write old counters back.

## 6. How a conversation enters a pipeline

### 6.1 Automatic intake (per number; user's decision, mechanics are my call)
- An admin sets each number's intake rule in CRM Settings → Numbers: **Manual** (default) or **Automatic into
  pipeline P**. A sales number points at "Enquiries"; a support number stays manual.
- **Trigger:** the CRM listens (read-only) to existing inbox events `InboxEvents.ChatCreated` (first direction
  inbound), `ChatReopened` (reopened by an inbound message) and `CampaignChatFirstReply`. Outbound-started chats and
  ordinary follow-up messages never trigger intake.
- **Effect** (idempotent, runs after the inbox transaction commits):
  1. find or create the CRM contact for (platform contact, number) — adopting a no-number record if one exists —
     origin INBOUND_CHAT, `capturedBy = SYSTEM`, lifecycle = default picklist item;
  2. if the CRM contact has an **open** card in P → nothing new; the message simply appears on its timeline;
  3. else create a card in P's first stage, `createdBy = INTAKE`, `returning = true` when a closed card exists in P
     (the repeat buyer the user described), owner per the number's `defaultOwnerMode`: the chat's assignee when
     routing assigned one, else **unassigned**.
  Idempotency uses `intakeKey = chatId + ":" + triggering event time`, so a replayed event cannot create twice.
- **Reentry** (`reentry` on the number's rule): `NEW_CARD` (default; the repeat purchase gets its own card and the
  old one stays won), `REOPEN_LAST` (move the latest closed card back to the first open stage, recorded as
  `STAGE_CHANGED`), `NONE` (only people with no card ever get one).
- **Known contacts count too (user's decision):** an imported or campaign-only contact who messages for the first
  time gets a card exactly like a stranger.
- **Unassigned cards** show a badge on the board; any user who can see the number can **Claim** (sets owner to self,
  `DEAL_CLAIMED`). *My call:* when an agent replies to the chat and the card is unassigned, the card is claimed for
  that agent too (listening to the inbox assignment event), so the board tracks who is actually talking to the person.

### 6.2 Manual capture (from the inbox panel or the Contacts page)
- Given a chat → same find-or-create as above with `capturedBy = user`, owner = the capturer, and a card in the
  **chosen** pipeline (defaults to the number's intake pipeline, else the brand default) unless an open card exists
  there. Works on manual and automatic numbers; it is also how a person gets into a *second* pipeline.

### 6.3 Channel rule (D7, adapted)
- Channel is server-derived from the chat's number; cards, tasks, notes and activities carry their CRM contact's
  channel. A mismatching number in any communicate call → 409 `CHANNEL_MISMATCH`.
- **Same person, another number:** a chat on B for a person with a CRM contact on A creates a separate CRM contact on
  B (user's decision). The drawer lists "also on A" as a link for users who can see A.
- **No number yet (user's decision):** manual and imported CRM contacts start with `channel = null`, fixed by
  whichever comes first — an inbound message (via the same `ChatCreated` listener) or a "Start chat" from the record,
  which opens the existing New chat flow where the user picks the number **once** (the only number choice in the
  CRM). Until then the record is editable and cardable but excluded from communication and campaigns.
- **Open chat** navigates to `/inbox/:phoneNumberId/:chatId` on the record's channel; if no chat exists there it
  opens New chat pinned to that number.
- **Move channel** (`crm:settings`, `allowChannelMoves` on): 409 `CHANNEL_TAKEN` if the person already has a CRM
  contact on the target. *My call:* the target must already have a chat with the person, or the dialog sends a first
  message through `NewChatService` (needs `inbox:send` and a ready device), so later campaigns on the new number don't
  skip the contact as `CHAT_ON_OTHER_NUMBER`. Re-points the CRM contact and all its cards, appends `channelHistory`,
  records `CHANNEL_CHANGED`; chat history stays where it is and remains linked from the timeline.
- **Deleted number (my call):** records stay, visible only under the admin "Removed number" filter for moving. No hook
  into `PhoneNumberService.delete`.
- Segments resolve and campaigns launch **per channel**; no-number records are counted separately and never launched.

## 7. Functional behaviour

### 7.1 Phases 0–1 (foundation, pipeline core)
- **Contacts:** list (search, sort, filters owner/lifecycle/company/number, paging), drawer, save (blank clears,
  absent unchanged), communicate lookup, move channel. The list joins live `contacts` for the current name/number; a
  deleted platform contact shows its snapshot and "contact deleted".
- **Companies** (when enabled): list, drawer (contacts, cards, timeline), save, search-as-you-type with inline create,
  delete guard.
- **Cards:** create (needs a visible primary contact), edit (not stage/pipeline), claim, delete, table + board. Board
  per pipeline: each stage with count, sum (single currency) and first N cards, load-more per lane; filters
  number/owner/source/unassigned/returning. **Move** to stage + "before X / after Y" with sparse positions and
  re-spacing when gaps get small, safe under concurrent moves. Into WON/LOST closes, out reopens; every move records
  `STAGE_CHANGED`. **Close** won/lost (with lost reason) → first stage of that type.
- **Timeline:** own activities + related records' activities (a contact's cards) + legacy `contactNotes` + (if
  `timelineShowsMessages`) WhatsApp message summaries from `waWebMessages` on the record's channel — all read-only.
  Manual log of call/meeting/email (summary ≤ 2,000 chars, time not in the future). View needs `crm:view` on the
  number; log needs `crm:edit`.
- **Inbox panel:** a third column in `InboxPage` for the open chat's contact *when the chat's number is in the user's
  allowed CRM numbers*: exists? / lifecycle / open cards (with claim) / next task, with Capture, lifecycle change, open
  card, (admin) move channel. Lazy-loaded; not rendered and no requests when CRM is off.
- **Live updates:** `GET /api/workspaces/{ws}/crm/stream` (SSE, Bearer, fetch reader like `useInboxStream`); events
  `deal`, `task`, `pipeline`, `settings`, `access` (number assignment changed → client reloads status) carrying
  `phoneNumberId`; the server pushes only events on the viewer's allowed numbers (re-auth per push like
  `InboxStreamBroker`). Fed by `@TransactionalEventListener` on `CrmEvent`s.

### 7.2 Phase 2 (work management)
- **Tasks:** title, description, type (picklist), priority, dueAt, remindAt, status OPEN/DONE/CANCELLED, assignee,
  team, target record. "My tasks" buckets overdue / today / upcoming; complete / reopen. **Reminders:** a scheduled
  job claims due reminders (`findAndModify` on `reminderSentAt`, idempotent) and pushes a `reminder` event on the CRM
  stream to the assignee; console shows a toast and a badge. Overdue tasks flag cards and boards.
- **Notes:** markdown (rendered sanitised), target, pin, attachments in `crmNoteFiles` (limits mirror `ChatMediaConfig`).
- **Picklists** editor; **custom fields** editor per entity and per pipeline; **intake rules** and **number
  assignment** editors (phase 0 actually, listed here for completeness of Settings).
- **CSV:** streamed export of contacts/companies/cards (`crm:export`, scoped to allowed numbers); import of companies
  and contacts (match an existing platform contact by phone or create one through the existing contact service; CRM
  contacts start with no number, origin IMPORT; rows for people who already have exactly one CRM contact update it,
  others are reported as skipped). Reuse `ContactImportController`'s CSV parsing where it fits.
- **Rotting job:** daily, flags cards in OPEN stages longer than `rottingDays`.

### 7.3 Phase 3 (segments → campaigns)
- **Segment:** name, description, rule tree reusing `contact.advanced` (`RuleGroup`/`Rule`, sealed `RuleNode`) with
  an extended field catalogue: platform contact attributes/labels + CRM contact fields, lifecycle, owner, company
  fields, card pipeline/stage/status/amount/returning, custom fields. `CrmSegmentCompiler` produces an aggregation
  over `crmContacts` joined to `contacts`/`crmDeals`/`crmCompanies` with a closed operator set (same safety as
  `AdvancedFilterCompiler`). Per-channel counts cached, refreshed hourly. Console reuses `RuleTreeEditor`.
- **Preview** → per-channel counts over the user's allowed numbers. **Launch** → one campaign draft per channel through
  `CampaignService`, sender = that number's `CampaignSender`, audience = new `AudienceKind.CRM_SEGMENT` with a snapshot
  of contact ids (≤ 50,000 per channel); `doNotContact` respected by the existing materialiser. Sends recorded as
  `WHATSAPP_CAMPAIGN_SENT` activities via a listener on existing campaign events. Replies to the campaign on an
  automatic number flow through §6.1 like any other inbound message.

### 7.4 Phase 4 (reports, dashboard)
Pipeline summary (count, value, probability-weighted), cards over time, by owner, by source, by number, by intake
(auto vs manual, new vs returning), lifecycle funnel, activity summary, stage duration, and a generic grid report
(entity, group-by, COUNT/SUM/AVG, field incl. custom fields, filters, date bucket). Every aggregation's first `$match`
is the `CrmContext` scope. Dashboard page with stat tiles and charts.

## 8. Cross-cutting

### 8.1 Input safety
Whitelisted sort/filter/search fields per entity; client-supplied Mongo operators rejected; bounded string lengths;
paging mandatory (page size cap 200); phone numbers normalised before anything is created.

### 8.2 Errors (existing `{code, message}` shape, `CodedException` subclasses in `crm`)
402 `CRM_NOT_ENABLED`, 403 forbidden, 404 not found (also for foreign brands and numbers outside *allowed*),
409 `STAGE_HAS_DEALS` (+ details), 409 `PIPELINE_IN_USE`, 409 `CHANNEL_MISMATCH`, 409 `CHANNEL_TAKEN`,
409 `VERSION_CONFLICT` (UI offers reload), 409 `COMPANY_HAS_OPEN_DEALS`, 409 `LIMIT_REACHED`, 422 `FIELD_INVALID`
(+ per-field). Unexpected → 500 with error id, as now.

### 8.3 Isolation (D5)
CRM writes only `crm*` collections and `crmNoteFiles`; indexes declared only on CRM entities. It listens (read-only) to
existing inbox and campaign Spring events and reads `contacts`, `waWebChats`, `waWebMessages`, `contactNotes`,
`phoneNumbers`, `teams`, `users` through small read-only projections (`crm.readmodel`), each with a parity test that
fails if an upstream field it reads is renamed. Chats and campaigns are created only through existing services
(`NewChatService`, `CampaignService`).

**Existing files touched (each justified in the plans):**
- `access/Authority.java` — new `crm:*` codes.
- `campaign/AudienceKind.java` + `CampaignMaterialiser` — `CRM_SEGMENT` audience (phase 3).
- Frontend: `routes.tsx` (`NAV_SECTIONS` CRM group, lazy routes), `Inbox/InboxPage.tsx` (lazy CRM panel slot),
  Contacts contact page (link to CRM profile when enabled), Users page (CRM numbers column/editor when enabled),
  platform/brand admin page for the CRM toggle.

### 8.4 Events
`CrmEvent` Spring events (`crm_deal_created` with `source INTAKE|USER`, `crm_deal_stage_changed`, `crm_deal_won`,
`crm_deal_lost`, `crm_deal_claimed`, `crm_lifecycle_changed`, `crm_task_completed`, …) feed the CRM stream and are the
hook for a future automation engine.

## 9. Console (frontend)

- **Nav:** CRM group in `NAV_SECTIONS` (Dashboard, Pipelines, Contacts, Companies*, Tasks, Segments, Reports,
  Settings; *only when `companiesEnabled`), shown only when `crm/status` says enabled and `crm:view` is held. CRM pages
  are `React.lazy` chunks never loaded for brands without CRM; direct navigation renders an "add-on inactive" state.
- **Phone-number filter** on every CRM page over the user's allowed numbers (all / some), persisted per user + brand
  in localStorage. **Channel chip** on cards, rows, drawers when the user has more than one allowed number.
- **Reusable components** (no CRM imports): `KanbanBoard` (columns, cards, move callback with before/after
  neighbours, load-more per lane, header totals, horizontal scroll, keyboard-accessible drag, optimistic move with
  rollback, pending move survives a concurrent refresh, grouped-list fallback on narrow screens), `Timeline`,
  `FieldForm`/`FieldValue`, `RecordDrawer` (PatternFly `Drawer`).
- **Pages:** Pipelines (selector + board/table toggle, unassigned/returning/needs-details badges, Claim), card drawer
  (details, contact, company, timeline, open chat, close), contact and company lists + drawers, Tasks, Segments
  (builder reusing `RuleTreeEditor`), Reports, Dashboard, Settings (pipelines + stage editor with drag reorder and
  relocation dialog, fields per entity/pipeline, picklists, **Numbers: intake rule and default owner per number**,
  **Access: who works which number**, general).
- Data fetching via `api/*.ts` + `useLoad`/`useAction`. PatternFly 6 theming (light/dark/contrast) and phone layout on
  every page. Structured errors render as actionable inline messages; 402/403 render as states.

## 10. Phases → plans (each = backend plan + console plan, backend first; contract in the backend plan)

| Phase | Delivers |
|---|---|
| 0 Foundation | `crm` package skeleton, `crm:*` authorities, `CrmAccess`/`CrmContext` (allowed numbers), per-brand enablement + limits + status, `crmNumberAccess` + `crmNumberSettings`, lazy settings + default pipeline seeding, pipeline/stage CRUD with relocation, field engine, CRM SSE stream, read models + parity tests; console: status gate, lazy CRM nav, phone filter, settings pages (pipelines/stages/fields/numbers/access), platform toggle, Users page column |
| 1 Pipeline core | Automatic intake listener + manual capture, CRM contacts (list, drawer, communicate, move channel), companies (optional), cards (board, move, close, claim, table), activities + timeline, inbox panel, live board updates; console `KanbanBoard`, `Timeline`, `RecordDrawer`, `FieldForm` |
| 2 Work management | Tasks + in-app reminders, notes + attachments, picklist editor, CSV import/export, rotting job |
| 3 Segments & campaigns | Segment model + compiler + per-channel preview/cached counts, `CRM_SEGMENT` audience, per-channel launch, campaign-sent activities, `CrmEvent` catalogue |
| 4 Reports | Report aggregations, grid report, dashboard |

## 11a. Calls made by Claude (not agreed by the user — check these)
1. CRM lives in the existing jar as package `crm`, not a separate service.
2. Number assignment lives in CRM storage (`crmNumberAccess`), edited in CRM Settings and on the Users page; admin
   roles (`crm:view-all`) see all numbers without assignment. `crm:view-team` is dropped.
3. Intake mechanics (§6.1): triggers (`ChatCreated` inbound, `ChatReopened` inbound, `CampaignChatFirstReply`),
   "no open card in that pipeline" dedup, `reentry` default `NEW_CARD` with a `returning` flag, unassigned cards by
   default, claim on reply, required fields not enforced on auto-created cards.
4. Companies optional per brand (`companiesEnabled`).
5. Internal name "deal" (`crmDeals`); UI label from each pipeline; nav entry "Pipelines".
6. Value and expected close date built-in on every card, hideable per pipeline.
7. Starter templates (Sales, Orders, Admissions, Support, Bookings, Blank).
8. Move channel needs an existing chat on the target or a first message sent from the dialog.
9. Deleted numbers: records kept, visible under an admin "Removed number" filter.
10. `CTWA_AD` origin never set in v1.
11. Task reminders in-app (SSE + toast), no push.
12. CRM events are in-process Spring events only.
13. Segments get a new campaign audience kind `CRM_SEGMENT`.

## 11. Left to the builder
Exact endpoint paths beyond those named; DTO shapes; drag-and-drop library (`@patternfly/react-drag-drop` or
`@dnd-kit`, keyboard-accessible); board lane size N (suggest 25); re-spacing threshold; chart library for reports
(follow dataviz guidance); cache refresh cadence; internal package layout under `crm`; auto-card title format.

## 12. Verification (end-to-end acceptance)

Run: `npm run build` (frontend) → `./mvnw -q -Pwith-frontend -DskipTests package` →
`OMNICHANNEL_SEED_DEMO=true java -jar target/omnichannel-crm-backend-0.1.0-SNAPSHOT.jar --server.port=8089`.
Unit + `*IT` tests (embedded Mongo) per phase; vitest for the console.

1. CRM off for a brand: no CRM nav, CRM routes show the inactive state, inbox and contacts make zero `/crm` requests
   and don't load the CRM chunk; CRM API answers 402; an inbound message creates nothing.
2. Account manager enables CRM on brand X only: brand Y still answers 402; a user without `crm:view` gets 403.
3. Revoked token / bumped `tokenVersion` is refused on CRM endpoints and the CRM stream.
4. **Visibility:** agent assigned numbers A and B (not C) sees leads on A and B, can filter to B only, gets 404 for a
   card on C and for a move on C; an admin sees A, B, C; removing B from the agent's assignment drops B's cards from
   their board live.
5. **Automatic intake:** number A set to Automatic → "Enquiries". A stranger messages A: the card appears unassigned in
   lane 1 on another browser within seconds, timeline shows the first message, the CRM contact has origin
   INBOUND_CHAT / SYSTEM. A second message creates nothing. The agent replies → the card is claimed by them.
6. **Repeat buyer:** close that card as won; the person messages again → a new card marked "returning" in lane 1 (with
   `reentry = REOPEN_LAST` the old card moves back instead). An imported contact who messages for the first time also
   gets a card. On a Manual number nothing is created and the panel offers Capture.
7. Admin creates an "Admissions" pipeline from a template with 6+ stages and its own fields; capture a contact from a
   chat on A into it; move the card to lane 4; timeline shows the stage change; close as won; summary reflects it.
8. With the inbox on B, open an A card and press Open chat → inbox switches to A's chat. No number picker anywhere;
   an API call with a different number returns `CHANNEL_MISMATCH`.
9. Admin moves a contact's channel to B: history records it, chip changes, cards follow; a user without
   `crm:settings` gets 403; a target where the person already has a CRM contact → `CHANNEL_TAKEN`.
10. Same person messages on B: a second CRM contact on B; A's untouched. A manually added contact has no number; the
    first inbound message on A (or Start chat picking A) fixes A and its cards follow.
11. Removing a stage holding cards → 409 until a target is chosen; cards relocate and stay visible; changing a
    stage's type while holding cards is refused; deleting a pipeline used by an intake rule → `PIPELINE_IN_USE`.
12. A segment with contacts on A and B previews two counts and launches two campaigns, each on its own sender only.
13. After running, only `crm*` collections/indexes (and `crmNoteFiles`) are new; existing collections' indexes are
    unchanged (IT compares index lists before/after).
14. Light, dark and phone-width layouts work on every CRM page.
