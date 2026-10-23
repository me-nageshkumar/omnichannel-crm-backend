# CRM, ported from the RedLava CRM service — Design

**Date:** 2026-10-23
**Status:** Draft for review. The user asked for every capability of the reference CRM service, re-contexted to this app,
and skipped the question round; every decision not taken from the reference is marked **(call)** and listed in §13.
**Supersedes:** nothing. `2026-10-23-crm-design.md` (deferred) is a separate, older design the user asked to ignore.
**Repos:** `omnichannel-crm-backend` (new Maven module **`omnichannel-crm`**, §3a), `omnichannel-crm-frontend` (new
`Crm/` pages), no extension change.

## 1. Source and intent

The reference is a working CRM add-on built as a separate Spring Boot service for the RedLava WhatsApp platform:

| What | Where |
|---|---|
| CRM service (Java 21, Spring Boot 4.1, Mongo), branch `crm-addon-phase-2` | `/Users/nagesh/RedLava/SCM/CRM/redlava-crm` |
| Its main app (auth, phones, plans, contacts, chats) | `/Users/nagesh/RedLava/SCM/CRM/whatsapp` |
| CRM screens (React + antd + Redux), `src/Components/Crm/`, branch `crm-addon-phase-2` | `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend` |
| Designs: original, pipeline-access amendment, phase 2 | `whatsapp/docs/superpowers/specs/2026-10-22-crm-addon-design.md`, `2026-10-23-crm-pipeline-access-design.md`, `2026-10-23-crm-phase2-design.md` |
| Sample data | local Mongo `whatsapp`, collections `crm*` |

Where the reference documents disagree, the later one and then the code win. What it does for people:

1. **An agent chatting with a customer** captures them into a pipeline from the inbox, logs a call, adds a follow-up task
   and a note, without leaving the chat.
2. **A manager** watches a kanban of deals per pipeline (stage, value, owner, overdue and gone-quiet deals) and moves
   cards between lanes.
3. **An admin** shapes the CRM to the business: pipelines and lanes, extra fields per record type, picklists, who
   works which pipeline.

This design keeps every capability the reference service has built and changes only what our app does differently:
one jar instead of two services, brands instead of customers, teams that own numbers instead of per-agent phone lists,
authorities instead of a permission enum, MongoDB GridFS instead of S3, in-app reminders instead of mobile push, and
PatternFly + TypeScript instead of antd + JavaScript.

## 2. Scope

### 2.1 In: every capability the reference service has built (its phases 0, 1, 1b and 2)

- **Entitlement:** the CRM is switched on per brand, with limits on pipelines and custom fields.
- **Pipeline access:** admins see every pipeline; other users see only pipelines assigned to them.
- **Settings:** currency, default pipeline, rotting days, channel moves allowed, five picklists (lifecycle statuses,
  deal sources, task types, lost reasons, industries).
- **Pipelines and stages:** any number of pipelines and lanes; stage colour, probability, type OPEN/WON/LOST, WIP
  limit; relocation of deals when a stage or pipeline is removed.
- **Custom fields** on contacts, companies and deals: 13 types, validation, required, default, list/card visibility,
  reorder, soft delete.
- **CRM contacts:** capture from a chat, create from the board, edit, open chat, move channel, export, import.
- **Companies:** create, edit, search-as-you-type, summary, delete, export, import.
- **Deals:** create, edit, board per pipeline with per-lane paging and totals, drag between lanes, move between
  pipelines, close won/lost, delete, table view, export, rotting flag, overdue flag.
- **Timeline:** append-only activity log with field diffs; manual call/meeting/email logging; existing contact notes
  shown on the contact's timeline.
- **Tasks:** on contacts, companies and deals; My tasks buckets (overdue / today / upcoming) with counts; complete,
  reopen, delete; reminders.
- **Notes:** markdown, pin, attachments.
- **Jobs:** task reminders every minute, deal rotting daily.
- **Inbox panel:** the CRM side of the open chat: capture, lifecycle, deals, next task, quick log.
- **Users page:** assign CRM pipelines to a user.

### 2.2 Out

| Left out | Why |
|---|---|
| Lead conversion (`/leads/{id}/convert`, Facebook/website leads) | Our app has no lead intake. `origin.kind` keeps the values so it can return later. |
| Segments → campaigns, reports, dashboard charts | Designed in the reference (its phases 3–4) but never built; not a capability of the service. A later design. |
| Live updates | The reference removed them (pipeline-access call C9). Screens refresh on demand and when the tab regains focus. |
| Kafka automation events | Reference phase 3, not built. |
| Moving data from the reference's `whatsapp` database | Different tenants, users and phone ids; nothing maps. The demo seeder recreates the same shapes (§11). |

## 3. Translation table

| Reference | Here | Notes |
|---|---|---|
| Separate service on `:8081`, `/api/v1/crm/**`, nginx route | Maven module `omnichannel-crm`, packages `me.nageshk.omnichannel.crm.<feature>`, bundled in the one jar; URLs `/api/workspaces/{workspaceId}/crm/**` | §3a; built so it can become its own service later |
| JWT introspection against the main app, 30 s cache | Existing `JwtAuthFilter`, `@AuthenticationPrincipal AuthenticatedUser` | In process |
| `customerId` scope | **`workspaceId` (brand)** scope, `customerId` also stored | Contacts, numbers, teams, campaigns are per brand here |
| Plan add-on `crm.active` on any phone; `maxPipelines`, `maxCustomFields` | Per-brand switch + limits in `crmSettings`, set by platform staff | No billing here **(call)** |
| Permission enum (`view_crm`, `manage_crm_records`, …) | `crm:*` authorities, BRAND level (§4.1) | House convention `thing:action` |
| "Customer roles see everything" | Authority `crm:view-all` (default CUSTOMER_ADMIN, WORKSPACE_MANAGER) | Authorities decide, not role names |
| `BasicUser.phoneNumberIds` (agent's phones) | Numbers owned by the user's teams (`PhoneNumber.teamId`, `Team.memberIds`); every brand number with `inbox:view-all` | Same rule the inbox uses (`InboxScopeService`) |
| `lastMessage.assignedUsername/assignedTeamId` | `WaWebChat.assignedUserId/assignedTeamId` | Capture rule C6 |
| Usernames as user references | User ids (`ownerId`, `assigneeId`) | Our references are ids |
| Main app `contacts/ensure` | `ContactDirectory.ensure` port, implemented in `omnichannel-app` over `ContactService` | §3a |
| `contact` per phone (`phoneNumberId` + `receiverNumber`) | `contacts` per brand (`phone`, `waId`) | A CRM contact pairs one brand contact with one channel number (§5.3) |
| Main app `contactNote` read model | `ContactNoteDirectory` port over `contactNotes` (read only) | Shown on the CRM contact's timeline |
| S3 bucket from `appConfig` | GridFS bucket **`crmNoteFiles`** | User's instruction; same limits |
| FCM push via main app `CRM_TASK_REMINDER` | In-app: reminder badge + toast from a polled endpoint **(call)** | No push mechanism here |
| Redis ShedLock jobs | `@Scheduled` on the existing `taskScheduler` | One instance; `omnichannel.crm.jobs.enabled` switch kept |
| `crmIndexInitializer`, auto-index off | `@CompoundIndex` on CRM documents only | House convention; CRM declares no index on existing collections |
| Collections `crmContact`, `crmDeal`, … (singular) | `crmContacts`, `crmDeals`, … (plural camelCase) | House convention: `phoneNumbers`, `waWebChats` |
| RFC 7807 problem details | `{code, message}` through `CodedException` / `ApiExceptionHandler` | House convention |
| `TableRequestDto` POST `/list` | `POST …/search {q, filters, sort, page, size}` → `{items, total, page}` | House convention (`ContactSearchService.Page`) |
| Agent add/edit page field "CRM pipelines" | Users page: a "CRM pipelines" field in the user's brand access editor | Same idea |
| Sidebar group with `checkFeature` | `NAV_SECTIONS` group "CRM", shown when `crm/status` says enabled and `crm:view` is held | |
| antd, react-dnd, react-markdown, papaparse | PatternFly 6; drag library and markdown renderer are new dependencies (§10) | |

## 3a. The `omnichannel-crm` module

The CRM is one **vertical** Maven module holding all its own layers, unlike the existing layer modules. It is built
so that a later split into its own service changes only the adapters in `omnichannel-app`, not the CRM.

```
omnichannel-common ← omnichannel-db ← omnichannel-security ← omnichannel-service ← omnichannel-app
                                              ↑                                         │ depends on both,
                                       omnichannel-crm ─────────────────────────────────┘ implements crm.platform
```

- **Coordinates:** `<module>omnichannel-crm</module>` in the root `pom.xml` (between `omnichannel-security` and
  `omnichannel-service`), artifact `omnichannel-crm`, root package `me.nageshk.omnichannel.crm`.
- **Depends on** `omnichannel-common` (`CodedException`, `OmnichannelProperties`, the clock) and
  `omnichannel-security` (`AuthenticatedUser`, `AccessService`/`@access.has`, `Authority`, JWT filter).
  `omnichannel-security` brings `omnichannel-db` transitively, but **CRM code imports nothing from
  `me.nageshk.omnichannel.db..` or `..service..` or `..app..`**. A test in the module (`CrmModuleBoundaryTest`, plain
  source scan, no new dependency) fails the build on such an import; `security.access`, `security.auth` and
  `common..` are the only outside packages allowed.
- **`omnichannel-app` depends on `omnichannel-crm`** and on `omnichannel-service`, so it is the one place that sees
  both; nothing else depends on the CRM. `OmnichannelApplication` in the root package already scans
  `me.nageshk.omnichannel.crm`.
- **Packages:** `crm.platform` (ports, below), `crm.access` (`CrmAccess`, `CrmContext`, pipeline access),
  `crm.settings`, `crm.pipeline`, `crm.field`, `crm.contact`, `crm.company`, `crm.deal`, `crm.activity`, `crm.task`,
  `crm.note`, `crm.csv`, `crm.job`, `crm.status`, `crm.demo`, `crm.common` (errors, paging, `CrmTarget`, picklist
  item). Each feature package holds its document, repository, service, controller and DTOs, as the reference does.
- **Ports (`crm.platform`)** — small interfaces the CRM owns, returning CRM-side records, never platform documents:

  | Port | Answers | In-process adapter (in `omnichannel-app`) over |
  |---|---|---|
  | `ContactDirectory` | contact by id (name, phone, deleted?), `ensure(workspaceId, customerId, rawPhone, name)` → contact id (400 `VALIDATION` field `phone`) | `ContactRepository`, `ContactService` |
  | `ChatDirectory` | chat by id (workspace, number, contact, assigned user/team), latest chat for (number, contact) | `WaWebChatRepository` |
  | `NumberDirectory` | the brand's numbers with label, and which ones a user can chat on (team-owned, or all with `inbox:view-all`) | `PhoneNumberRepository`, `TeamRepository`, `AccessService` |
  | `PeopleDirectory` | the user's team ids in a brand, whether a user id belongs to the brand, display names for ids | `TeamRepository`, `UserRepository` |
  | `ContactNoteDirectory` | existing contact notes of a contact (for the timeline) | `ContactNoteRepository` |
  | `BrandDirectory` | brand exists, its customer id, list of brand ids with CRM data (jobs) | `WorkspaceRepository` |
  | `EnablementAudit` | record a CRM switch change | `EnablementChangeRepository` |

  The CRM's own module tests use fakes of these ports; `omnichannel-app` holds the adapters, their tests and the
  Spring-context ITs, as for every other feature.
- **Errors** are `CodedException`s with their status, so the existing `ApiExceptionHandler` maps them unchanged.
- **Jobs** use `@Scheduled` (the existing `taskScheduler` bean) behind `omnichannel.crm.jobs.enabled`.
- **Splitting later** (not now): a new `omnichannel-crm-app` boots the module on its own, with adapters that call the
  main app over HTTP (the reference's `mainapp` client and internal endpoints) and either the shared JWT secret or a
  token-check endpoint; the CRM already owns its collections, so its data moves with it. `Authority` codes stay in the
  shared catalogue.

## 4. Access

### 4.1 Authorities (added to `security.access.Authority`, all BRAND level)

| Code | Reference | Grants | Default roles |
|---|---|---|---|
| `crm:view` | `view_crm` | Open the CRM; see records in accessible pipelines | CUSTOMER_ADMIN, WORKSPACE_MANAGER, AGENT |
| `crm:view-all` | customer role / `crm_view_all_records` | Every pipeline without assignment | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:edit` | `manage_crm_records` | Create and edit contacts, companies, deals, tasks, notes; capture; move; close; log; import | CUSTOMER_ADMIN, WORKSPACE_MANAGER, AGENT |
| `crm:delete` | `delete_crm_records` | Delete companies and deals (together with `crm:edit`) | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:settings` | `manage_crm_settings` | Settings, pipelines, stages, fields, picklists, pipeline assignment, move channel | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:export` | `export_crm` | CSV export | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `crm:enable` (PLATFORM) | plan add-on purchase | Switch the CRM on/off for a brand, set limits | ACCOUNT_MANAGER (Super Admin holds every platform authority) |

`crm_view_team_records` and `manage_crm_segments` are not ported (the first had no effect any more, the second has no
feature). In the reference, customer roles skip some permission checks implicitly; here only authorities count.

### 4.2 Entitlement (call)

- `crmSettings` (one per brand) carries `enabled` (default false), `maxPipelines`, `maxCustomFields` (null =
  unlimited; custom-field limit is per entity type, as in the reference).
- `GET|PUT /api/customers/{customerId}/workspaces/{workspaceId}/crm {enabled, maxPipelines, maxCustomFields}` needs
  `crm:enable` (`hasAuthority`, platform level, like the existing customer-brand enable/disable endpoints); each change
  is recorded in the existing `enablementChanges` with `targetType = "crm"`.
- Every brand CRM endpoint except `status` answers **402 `CRM_NOT_ENABLED`** when off. Missing `crm:view` →
  403 (404 for a brand the user cannot see, as everywhere).
- The platform customer detail page (`/customers/:id`, where staff already enable and disable brands) gets a CRM switch
  and the two limits per brand.

### 4.3 Request context and pipeline access (reference `CrmRequestContextResolver`, pipeline-access §4)

`CrmAccess.resolve(me, workspaceId)` builds once per request:

```
CrmContext(workspaceId, customerId, userId, authorities,
           enabled, limits,
           phones: [{phoneNumberId, number, label, canChat}],   // every number of the brand
           pipelineAccess: ALL | Set<pipelineId>)
pipelineAccess = ALL                                            if crm:view-all
               = crmPipelineAccess[workspaceId, userId].pipelineIds ∩ existing pipelines   otherwise (may be empty)
canChat        = true for every number with inbox:view-all, else the number is owned by one of the user's teams
```

Applied in order on every call: brand → enabled → `crm:view` → pipeline access → `crm:edit` / `crm:delete` /
`crm:settings` for writes. Every repository query takes the context and carries `workspaceId`; a query without it throws.

| Collection | A restricted user sees |
|---|---|
| `crmDeals` | `pipelineId ∈ access` |
| `crmContacts` | `pipelineIds` intersects `access` (contacts with no deals: `crm:view-all` only) |
| `crmActivities`, `crmTasks`, `crmNotes` | target record accessible (company targets: always) |
| `crmCompanies` | all (brand-wide) |
| `crmPipelines` | `id ∈ access` for list/get; writes need `crm:settings` |
| `crmSettings` | read with `crm:view`; write with `crm:settings` |

A restricted user with no assignment sees empty lists, "No pipeline assigned to you. Ask your admin." on the board, and
404 on any record id. A contact's detail, timeline and counters shown to a restricted user include only deals in
accessible pipelines (reference C15). Moving a deal between pipelines needs both accessible, else 404 on the target.

### 4.4 Channels (reference `CrmChannelPolicy`)

- A CRM contact and its deals carry `channel {phoneNumberId, number}`: the number the person landed on. Derived at
  creation, immutable through edits.
- Communication (`GET /contacts/{id}/communication`) answers `{phoneNumberId, number, phone, chatId?, canChat}`; the
  console opens `/inbox/chats/{chatId}` when a chat exists, else the New chat modal pinned to that number. A call
  naming a different number → 409 `CHANNEL_MISMATCH`. When `canChat` is false the console explains that the inbox
  will refuse (the reference's accepted side effect).
- **Move channel** (`crm:settings`, and `allowChannelMove` on): re-points the CRM contact, its deals, tasks and notes;
  appends `channelHistory {from, to, byUserId, at, reason}`; records `CHANNEL_CHANGED`. Refused with 409
  `CHANNEL_TAKEN` when the person already has a CRM contact on the target number. Chats stay where they are.

### 4.5 Who may capture from a chat (reference C6/C14)

With `crm:view-all` a user may always capture. Otherwise the chat must be assigned to the user or to one of their teams
(`WaWebChat.assignedUserId == me` or `assignedTeamId ∈ my teams`); else 403 `CHAT_NOT_ASSIGNED`. A user with no
accessible pipeline cannot capture (403 `NO_PIPELINE_ACCESS`).

## 5. Data model

New collections only, camelCase plural with the `crm` prefix (`CollectionRenamesIT` enforces camelCase). Every document
has `workspaceId`, `customerId`, `creationTime`/`lastUpdateTime`, `createdBy`/`lastModifiedBy` (user ids) and
`@Version`. Every index leads with `workspaceId`. Money is `BigDecimal` stored as Decimal128 through
`@Field(targetType = FieldType.DECIMAL128)` on each money field (no global converter change). Ids are strings.
`createdBy`/`lastModifiedBy` are set by the CRM services (the app has no `AuditorAware`).

### 5.1 `crmSettings` (one per brand, unique `{workspaceId}`)
`enabled`, `maxPipelines`, `maxCustomFields`, `defaultCurrency` (ISO 4217, default from the reference `INR`),
`defaultPipelineId`, `dealRottingDays` (default 14), `allowChannelMove` (default true), `timelineShowsWhatsappMessages`
(stored and editable; the reference never reads it — kept for parity, see §13), picklists `lifecycleStatuses`,
`dealSources`, `taskTypes`, `lostReasons`, `industries`: `[{key, label, color, position, active, isDefault}]`.
Created lazily with the reference defaults (lead/prospect/customer/lost; whatsapp/facebook_lead/website/referral/other;
call/follow_up/meeting/email/other; price/competitor/no_response/not_interested/other; industries empty) and a default
"Sales" pipeline with the reference's seven stages (New 10, Contacted 25, Qualified 50, Proposal 70, Negotiation 85,
Won 100 WON, Lost 0 LOST). A picklist item is never removed, only deactivated; a non-empty picklist restricts values
to its active keys; an unchanged stored value always passes.

### 5.2 `crmPipelines`, `crmPipelineAccess`
- **`crmPipelines`**: `name`, `isDefault`, `stages[{id, name, color, position, probability, type OPEN|WON|LOST,
  wipLimit}]`, `rottingDays` (null = settings default), `active` (inactive pipelines stay visible but are not offered
  for new deals). Count bounded by `maxPipelines` (409 `LIMIT_REACHED`). Rules from the reference: at least one OPEN
  stage; removing a stage that holds deals needs `moveToStageId` (409 `STAGE_HAS_DEALS {stageId, count}` otherwise);
  retyping a stage that holds deals → 409 `STAGE_TYPE_HAS_DEALS`; deleting a pipeline that holds deals needs
  `moveDealsToPipelineId`; deleting the default promotes another; deleting removes it from every
  `crmPipelineAccess`. Index `{workspaceId, isDefault}`.
- **`crmPipelineAccess`**: `{workspaceId, userId, pipelineIds[]}`, unique `{workspaceId, userId}`, index
  `{workspaceId, pipelineIds}`. Written only through `PUT /crm/users/{userId}/pipelines` (`crm:settings`), which
  checks every id is a pipeline of the brand and the user has access to the brand; records `SETTINGS_CHANGED`.

### 5.3 Records
- **`crmContacts`** — one per (brand contact, channel number): `contactId` (→ `contacts`), `channel {phoneNumberId,
  number}`, `phone` (snapshot), `contactSnapshot {name}`, `origin {kind INBOUND_CHAT|CTWA_AD|FACEBOOK_LEAD|
  WEBSITE_LEAD|MANUAL|IMPORT|API, phoneNumberId, chatId?, firstMessageAt?, capturedById}` (set once), `firstName`,
  `lastName`, `email`, `jobTitle`, `companyId`, `ownerId`, `teamId`, `lifecycleStatus`, `source`, `customFields`,
  `pipelineIds` (all deals, any status), counters `openDealCount`, `openDealAmount`, `lastActivityAt`,
  `nextTaskDueAt`, `channelHistory[]`. Unique `{workspaceId, contactId, channel.phoneNumberId}`; indexes
  `{workspaceId, pipelineIds, _id:-1}`, `{workspaceId, companyId}`, `{workspaceId, ownerId}`,
  `{workspaceId, lifecycleStatus}`, wildcard `customFields.$**`. A deleted brand contact keeps the snapshot and shows
  "contact deleted" (not intercepted).
- **`crmCompanies`** — `name`, `domain`, `website`, `industry`, `size`, `annualRevenue`, `phone`, `email`,
  `address {line1, city, state, postalCode, country}`, `description`, `ownerId`, `teamId`, `labelIds`,
  `customFields`, `contactCount`, `openDealAmount`. Brand-wide. Indexes `{workspaceId, _id:-1}`, `{workspaceId,
  name}`, `{workspaceId, ownerId}`. Delete (`crm:delete` + `crm:edit`) is refused with 409 `COMPANY_HAS_OPEN_DEALS`
  while it has open deals; otherwise it unlinks every contact of the brand from it (no per-contact activity) and
  removes its tasks and notes.
- **`crmDeals`** — `channel` (= primary contact's), `title`, `pipelineId`, `stageId`, `position` (double), `status
  OPEN|WON|LOST` (from the stage type), `amount`, `currency`, `probability`, `probabilityOverridden`,
  `expectedCloseDate`, `closedAt`, `lostReason`, `companyId`, `primaryCrmContactId`, `contactSnapshot {name, phone}`,
  `crmContactIds[]`, `ownerId`, `teamId`, `source`, `labelIds`, `customFields`, `stageEnteredAt`, `rotting`,
  `lastActivityAt`, `nextTaskDueAt`, `archived`. Indexes `{workspaceId, pipelineId, stageId, position}`,
  `{workspaceId, ownerId, status}`, `{workspaceId, primaryCrmContactId}`, `{workspaceId, crmContactIds}`,
  `{workspaceId, companyId}`, `{workspaceId, status, closedAt}`, `{workspaceId, status, lastActivityAt}` (rotting),
  wildcard `customFields.$**`.
- **`crmActivities`** — append-only: `type` (RECORD_CREATED, FIELD_CHANGED, STAGE_CHANGED, DEAL_WON, DEAL_LOST,
  NOTE_ADDED, TASK_CREATED, TASK_UPDATED, TASK_COMPLETED, CALL_LOGGED, MEETING_LOGGED, EMAIL_LOGGED,
  WHATSAPP_CAMPAIGN_SENT, WHATSAPP_MESSAGE_SENT, WHATSAPP_MESSAGE_RECEIVED, LEAD_CONVERTED, CHANNEL_CHANGED,
  SETTINGS_CHANGED, SYSTEM — the whole reference enum, some unused), `target {type CONTACT|COMPANY|DEAL|PIPELINE|
  SETTINGS, id}`, `relatedTargets[]`, `phoneNumberId`, `actorId`, `actorType USER|AUTOMATION|SYSTEM`, `happenedAt`,
  `summary`, `payload` (field diffs `[{field, from, to}]`). Indexes `{workspaceId, target.type, target.id,
  happenedAt:-1}`, `{workspaceId, relatedTargets.id, happenedAt:-1}`.
- **`crmTasks`** — `title` (≤ 200), `description` (≤ 5000), `type` (picklist), `priority LOW|NORMAL|HIGH`, `dueAt`,
  `reminderAt` (≤ dueAt), `status OPEN|DONE|CANCELLED`, `completedAt`, `completedBy`, `assigneeId`, `target`,
  `pipelineId` (deal targets), `phoneNumberId`, `reminderSentAt`, `reminderSeenAt` **(call, in-app)**. Indexes
  `{workspaceId, assigneeId, status, dueAt}`, `{workspaceId, target.type, target.id, status, dueAt}`,
  `{workspaceId, status, reminderAt}`.
- **`crmNotes`** — `body` (markdown ≤ 20000), `target`, `pipelineId`, `phoneNumberId`, `attachments[{id, fileName,
  contentType, size, fileId (GridFS), uploadedBy, uploadedAt}]`, `pinned`. Index `{workspaceId, target.type,
  target.id, pinned:-1, _id:-1}`. GridFS bucket **`crmNoteFiles`** (`crmNoteFiles.files` / `.chunks`).
- **`crmFieldDefinitions`** — `entityType CONTACT|COMPANY|DEAL`, `key` (immutable, `^[a-z][a-zA-Z0-9_]{1,39}$`),
  `label` (≤ 60), `type`, `options[{key, label, color, position, active}]`, `required`, `defaultValue`, `position`,
  `showInList`, `showInCard`, `active`. Unique `{workspaceId, entityType, key}`; index `{workspaceId, entityType,
  active, position}`.

### 5.4 Derived values (reference rules)
- Counters on contacts (`openDealCount`, `openDealAmount`, `lastActivityAt`, `nextTaskDueAt`, `pipelineIds`) and
  companies (`contactCount`, `openDealAmount`) are **recomputed** by aggregation on every deal/task write, never
  incremented, and written with the version guard.
- Deal `nextTaskDueAt` = earliest `dueAt` of its OPEN tasks.
- Board position math: gap 1024.0; insert at midpoint of neighbours; append = last + gap; re-space the lane when a gap
  falls under 1e-6; a single conditional update on `{_id, workspaceId}`.

## 6. Behaviour

### 6.1 Capture and create (reference contact service, pipeline-access C6–C8, C18)
- **Capture from a chat** `POST /crm/contacts/capture {chatId, pipelineId?, dealTitle?}`: checks §4.5; finds or
  creates the CRM contact for (chat's contact, chat's number) with origin `INBOUND_CHAT`, `capturedById = me`, owner =
  me, lifecycle = default; then creates a deal in the pipeline's first OPEN stage unless the contact already has an
  open deal there. `pipelineId` is required for restricted users (must be accessible) and defaults to the brand
  default for `crm:view-all`. Idempotent.
- **New contact from the board** `POST /crm/contacts/create {phone, phoneNumberId, firstName?, lastName?, pipelineId,
  dealTitle?}`: normalises the phone like contacts do (400 `VALIDATION`, field `phone`), the channel number must be one with
  `canChat` (404 otherwise), finds or creates the brand contact through `ContactService` (source `MANUAL`), then the
  same find-or-create + deal as capture with origin `MANUAL`. Identity is (brand contact, channel number).
- No CRM record is ever created by an incoming message on its own (as in the reference).

### 6.2 Contacts, companies, deals
- Lists: search, sort, filter (owner, lifecycle, company, pipeline, stage, status), paging (cap 200), whitelisted
  fields, `showInList` custom fields as columns. Contacts list joins `contacts` for the live name/phone.
- Contact drawer: details, custom fields, deals (accessible only), tasks, notes, timeline, Open chat, Move channel…,
  lifecycle change. Company drawer: details, contacts, deals, tasks, notes, timeline; summary endpoint.
- CRM contacts are not deleted (the reference has no contact delete); deals delete with `crm:delete` + `crm:edit`
  and take their tasks and notes with them.
- Saves: blank clears, absent leaves unchanged; every change records `FIELD_CHANGED` with diffs; picklist and custom
  field validation (§6.5).
- Deal create (`POST /crm/deals`) needs an accessible pipeline and a primary CRM contact; linked contacts must share
  the primary's channel. Edit cannot change stage (use move) but may change pipeline (both accessible; lands in the
  target's first OPEN stage; `pipelineIds` recomputed).
- **Board** `GET /crm/deals/board?pipelineId=&perStageLimit=&ownerId=&q=` → `[{stage, total, sumAmount, deals[]}]`;
  load more per lane. **Move** `POST /crm/deals/{id}/move {stageId, beforeDealId?, afterDealId?}`: into WON/LOST
  closes (sets `closedAt`), out of them reopens; records `STAGE_CHANGED` (and `DEAL_WON`/`DEAL_LOST`); probability
  follows the stage unless overridden. **Close** `POST /crm/deals/{id}/close {outcome WON|LOST, lostReason?}` → first
  stage of that type. WIP limits are shown on the lane header and warn when exceeded; they are not enforced.
- Overdue mark on cards from `nextTaskDueAt < now`; rotting badge from `rotting`.

### 6.3 Timeline
`GET /crm/activities?targetType=&targetId=` merges the target's activities, related-target activities (a contact
shows its accessible deals' entries) and, for contacts, the brand contact's existing `contactNotes` as read-only
entries. Restricted users never see entries of inaccessible pipelines. `POST /crm/activities/log {target, type
CALL|MEETING|EMAIL, summary ≤ 2000, happenedAt ≤ now}` needs `crm:edit`.

### 6.4 Tasks and reminders
- Task fields and buckets as the reference: `GET /crm/tasks/mine?bucket=overdue|today|upcoming&tz=&assigneeId=`
  (another assignee only with `crm:view-all`, else 403), `GET /crm/tasks/mine/counts`, target task lists (OPEN first
  then `dueAt`), complete, reopen, delete, save. Access follows the target (§4.3). Timeline entries `TASK_CREATED`,
  `TASK_COMPLETED`, `TASK_UPDATED {action UPDATED|REOPENED|CANCELLED|DELETED}`.
- **Reminder job** (every 60 s, `omnichannel.crm.jobs.enabled`): claims up to 200 OPEN tasks with `reminderAt <= now`,
  `reminderSentAt == null`, an assignee, each by `findAndModify` setting `reminderSentAt` first (at most once).
- **Delivery (call):** instead of a mobile push, `GET /crm/reminders` returns the caller's claimed, unseen reminders;
  the console polls it every 60 s while signed in to a CRM-enabled brand, shows a toast ("Follow-up due: <title> · due
  <HH:mm>") with a link to the record, and a count on the Tasks nav item; `POST /crm/reminders/{taskId}/seen` sets
  `reminderSeenAt`.

### 6.5 Custom fields and picklists
- Definitions `GET/POST/PUT/DELETE /crm/fields`, `PUT /crm/fields/reorder {entityType, ids[]}`; key, entity type and
  type immutable (400 `FIELD_IMMUTABLE`); limit 409 `LIMIT_REACHED {limit}`; delete is soft and keeps values.
- Validation per type (reference C9): TEXT ≤ 1000, LONG_TEXT ≤ 10000, NUMBER finite, CURRENCY finite ≥ 0, BOOLEAN,
  DATE `yyyy-MM-dd` string, DATETIME ISO-8601 → Date, SELECT one active option, MULTI_SELECT distinct active options,
  URL absolute http(s), EMAIL plausible, PHONE `+?[0-9]{6,15}` after stripping spaces/dashes, USER a user id of the
  brand **(call: the reference stored an unchecked username)**. Blank clears. Unknown or inactive keys are refused
  unless unchanged; required enforced on create and when present on update. All of these answer the app's existing
  400 `VALIDATION` with `fields: {"customFields.<key>": message}` **(call: the reference's `UNKNOWN_CUSTOM_FIELD` /
  `INVALID_CUSTOM_FIELD` become field errors in the house shape)**.
- Custom fields are shown, not filterable or sortable (reference C10).

### 6.6 Notes
`POST /crm/notes`, `PUT /crm/notes/{id}`, `GET /crm/notes?targetType=&targetId=` (pinned first, newest first, paged),
`DELETE` (also deletes its GridFS files), `POST /{id}/pin {pinned}`, `POST /{id}/attachments` (multipart),
`DELETE /{id}/attachments/{attachmentId}`, `GET /{id}/attachments/{attachmentId}` (streams with
`Content-Disposition: attachment`; target access applies). Limits: 5 files per note, 10 MB each, images, PDF, Office
documents, CSV, plain text. `NOTE_ADDED {noteId, attachmentCount}` on create; pin and delete record nothing. Since
attachments always work here, `status.attachmentsEnabled` is always true (kept so the console code reads like the
reference).

### 6.7 CSV
- **Export** `POST /crm/{contacts|companies|deals}/export` with the list's filters and sort: streamed `text/csv`, UTF-8
  BOM, RFC 4180, standard columns then every active custom field (`label (key)`), ≤ 50,000 rows in pages of 1,000,
  formula-leading cells prefixed with an apostrophe like the existing contact export. Needs `crm:export`. Restricted
  users export only accessible records.
- **Import** synchronous and bounded: companies ≤ 2,000 rows (upsert by case-insensitive name, then domain); contacts
  ≤ 1,000 rows into one chosen pipeline and one channel number (each row runs §6.1 create with origin `IMPORT`; an
  existing CRM contact is filled where blank and gets a deal only if it has none open in that pipeline). Result
  `{total, created, updated, skipped, errors:[{row, message}]}`; a bad row never aborts the file. 400
  `IMPORT_TOO_LARGE {limit}`, 400 `IMPORT_BAD_HEADER {missing[]}`. Needs `crm:edit`.

### 6.8 Rotting job
Daily at 02:15 server time: per brand and pipeline, OPEN deals whose `lastActivityAt` (else `stageEnteredAt`, else
`creationTime`) is older than the effective rotting days get `rotting=true`, other OPEN deals `false`; closed deals are
never flagged; no rotting days = never. `updateMulti`, no version bump, no activity.

### 6.9 Status
`GET /crm/status` → `{enabled, limits, phones:[{phoneNumberId, number, label, canChat}], pipelineAccess: "ALL" |
[ids], authorities: [crm:*], attachmentsEnabled}`. Answers even when the CRM is off (that is how the console knows).

## 7. Errors (existing `{code, message}` shape)

402 `CRM_NOT_ENABLED`; 403 forbidden, `CHAT_NOT_ASSIGNED`, `NO_PIPELINE_ACCESS`; 404 not found (also other brands and
inaccessible pipelines); 409 `STAGE_HAS_DEALS {stageId, count}`, `STAGE_TYPE_HAS_DEALS`, `CHANNEL_MISMATCH`,
`CHANNEL_TAKEN`, `COMPANY_HAS_OPEN_DEALS`, `LIMIT_REACHED {limit}`, `VERSION_CONFLICT`; 400 `VALIDATION {fields}` (bad phone, custom fields, picklist values, lengths), `PIPELINE_INACTIVE`,
`FIELD_IMMUTABLE`, `IMPORT_TOO_LARGE`,
`IMPORT_BAD_HEADER`, `ATTACHMENT_REJECTED`. The reference used 400 for several conflicts; this app uses 409 for
conflicts **(call)**.

## 8. Isolation and existing code touched

The CRM writes only `crm*` collections and the `crmNoteFiles` bucket, and declares indexes only on its own
documents. Everything else it reads or does goes through the `crm.platform` ports (§3a), whose adapters read
`contacts`, `contactNotes`, `waWebChats`, `phoneNumbers`, `teams`, `users`, `workspaces` and create brand contacts only
through `ContactService`. An IT compares the index lists of existing collections before and after a CRM run.

| File | Change |
|---|---|
| Root `pom.xml` | `<module>omnichannel-crm</module>` |
| `omnichannel-app/pom.xml` | dependency on `omnichannel-crm` |
| `omnichannel-app` new package `app.crm` | the `crm.platform` adapters and their tests (new files only) |
| `security.access.Authority` | seven `crm:*` codes |
| README | module table row and a CRM section |
| Frontend `routes.tsx` | CRM group in `NAV_SECTIONS`, lazy routes |
| Frontend `Inbox/InboxPage.tsx` | a lazy CRM side panel slot for the open chat |
| Frontend `Users/UsersPage.tsx` | a "CRM pipelines…" row action opening a CRM modal, when CRM is on and the caller has `crm:settings` |
| Frontend `Customers/CustomerDetailPage.tsx` | CRM switch and limits per brand for `crm:enable` holders |
| Frontend `package.json` | drag-and-drop and markdown libraries |

Nothing in inbox, contacts or campaigns services changes.

## 9. API summary (`/api/workspaces/{workspaceId}/crm`)

| Area | Endpoints |
|---|---|
| Status | `GET /status` |
| Enablement (platform) | `GET|PUT /api/customers/{customerId}/workspaces/{workspaceId}/crm` |
| Settings | `GET /settings`, `PUT /settings` |
| Pipelines | `GET /pipelines`, `GET /pipelines/{id}`, `POST /pipelines`, `PUT /pipelines/{id}`, `PUT /pipelines/{id}/stages`, `DELETE /pipelines/{id}?moveDealsToPipelineId=` |
| Pipeline access | `GET /users/{userId}/pipelines`, `PUT /users/{userId}/pipelines` |
| Fields | `GET /fields?entityType=&includeInactive=`, `POST /fields`, `PUT /fields/{id}`, `DELETE /fields/{id}`, `PUT /fields/reorder` |
| Contacts | `POST /contacts/search`, `GET /contacts/{id}`, `GET /contacts/by-chat/{chatId}/summary` (inbox panel), `PUT /contacts/{id}`, `POST /contacts/capture`, `POST /contacts/create`, `GET /contacts/{id}/communication`, `POST /contacts/{id}/move-channel`, `POST /contacts/export`, `POST /contacts/import` |
| Companies | `POST /companies/search`, `GET /companies/typeahead?q=`, `GET /companies/{id}`, `GET /companies/{id}/summary`, `POST /companies`, `PUT /companies/{id}`, `DELETE /companies/{id}`, `POST /companies/export`, `POST /companies/import` |
| Deals | `POST /deals/search`, `GET /deals/board`, `GET /deals/{id}`, `POST /deals`, `PUT /deals/{id}`, `POST /deals/{id}/move`, `POST /deals/{id}/close`, `DELETE /deals/{id}`, `POST /deals/export` |
| Activities | `GET /activities`, `POST /activities/log` |
| Tasks | `GET /tasks?targetType=&targetId=`, `POST /tasks`, `PUT /tasks/{id}`, `POST /tasks/{id}/complete`, `POST /tasks/{id}/reopen`, `DELETE /tasks/{id}`, `GET /tasks/mine`, `GET /tasks/mine/counts`, `GET /reminders`, `POST /reminders/{taskId}/seen` |
| Notes | `GET /notes`, `POST /notes`, `PUT /notes/{id}`, `DELETE /notes/{id}`, `POST /notes/{id}/pin`, `POST /notes/{id}/attachments`, `DELETE /notes/{id}/attachments/{attachmentId}`, `GET /notes/{id}/attachments/{attachmentId}` |

The reference's `POST /save` (create-or-update) is split into `POST` and `PUT` per house style; behaviour is the same.

## 10. Console

- **Nav:** group "CRM" in `NAV_SECTIONS` — Pipelines (`/crm`), Tasks (`/crm/tasks`), Contacts (`/crm/contacts`),
  Companies (`/crm/companies`), Settings (`/crm/settings`, `crm:settings` or read-only view). Shown only when
  `crm/status` says enabled and `crm:view` is held; pages are `React.lazy` chunks; direct navigation when off shows an
  "CRM is not switched on for this brand" state. (The reference hid Companies from the menu late in phase 2;
  **call:** keep it, it costs nothing and companies are otherwise reachable only from a drawer.)
- **Pipelines page** (the reference "Dashboard"): pipeline selector (accessible ones), board/table toggle, filters
  (owner, search), Refresh and refetch on tab focus, "New contact" (§6.1), "New deal" (`crm:view-all` users, as in
  the reference), Export CSV; deal drawer at `/crm/deals/:dealId` (details, custom fields, contacts, company, tasks,
  notes, timeline, Open chat, Close, Delete). Empty state for users with no pipeline.
- **Reusable components** (no CRM imports): `KanbanBoard` (lanes, cards, `onMove(cardId, toLane, beforeId, afterId)`,
  lane totals, load more, horizontal scroll, keyboard-accessible drag, optimistic move with rollback, list fallback at
  phone width), `Timeline`, `FieldInput`/`FieldValue`/`fieldColumns` driven by definitions, `RecordDrawer`.
- **Contacts and Companies pages:** tables with `showInList` columns, drawers, form modals with "More fields",
  company typeahead with inline create, import modal (pipeline + channel number, preview of first rows, result
  table), export.
- **Tasks page:** bucket tabs with counts, assignee selector for `crm:view-all`, inline complete, row opens the record.
- **Settings:** General (currency, default pipeline, rotting days, channel moves, show WhatsApp messages), Pipelines
  (list, form, stage editor with drag reorder, colour, probability, type, WIP limit, removal dialog with relocation),
  Fields (tab per entity, reorder, active switch, form modal with options editor, `n / N` limit), Picklists (add,
  rename, colour, reorder, deactivate, default).
- **Inbox panel:** a collapsible right-hand panel in the chat view when the CRM is on: not captured → Capture (pipeline
  picker when more than one; disabled with a hint when §4.5 refuses); captured → name, lifecycle (editable), open
  deals with stage, next task, "Add follow-up", "Log call", open in CRM. Refetches when the chat changes. No requests
  when the CRM is off.
- **Users page:** a "CRM pipelines…" row action (CRM on, caller has `crm:settings`) opens a modal with a pipeline
  multi-select saved by `PUT /crm/users/{userId}/pipelines` (call: a separate action instead of a field in the shared
  person modal, so the admin components stay CRM-free).
- **Customer detail page (platform):** per brand, CRM switch, max pipelines, max custom fields (`crm:enable`).
- Data fetching through `api/crm.ts` with `apiFetch`; PatternFly 6 light/dark/contrast; phone layout on every page.
- **New dependencies (builder's choice, flag in the plan):** a keyboard-accessible drag-and-drop library
  (`@dnd-kit/core` + `@dnd-kit/sortable`, or `@patternfly/react-drag-drop`), and a sanitising markdown renderer
  (`react-markdown`, no raw HTML).

## 11. Demo data

With `OMNICHANNEL_SEED_DEMO=true`, `crm.demo.CrmDemoSeeder` (ordered after the existing demo seeders, using the ports
to find "Demo Fashion", its number and its contacts) switches the CRM on for "Demo Fashion" and recreates the sample's
shapes: the default Sales pipeline, a handful of companies, CRM contacts on the demo number with deals spread over the
lanes (one WON, one LOST, one rotting), tasks (one overdue, one today), a note, a DEAL custom field, and `demo-agent`
assigned to Sales. Skipped when the brand already has CRM settings.

## 12. Delivery (each phase = backend plan + console plan; backend first; the backend plan holds the JSON contract)

| Phase | Reference phases | Delivers |
|---|---|---|
| A. Foundation and pipeline core | 0, 1, 1b | the `omnichannel-crm` module, ports and adapters, boundary test, authorities, enablement, `CrmAccess`, settings + picklists storage, pipelines/stages/relocation, pipeline access, contacts (capture, create, list, drawer data, communication, move channel), companies, deals (board, move, close, table), activities/timeline, inbox summary, demo seeder; console nav/status gate, customer-page CRM switch, Users "CRM pipelines…" action, Pipelines page with board and drawers, Contacts, Companies, Settings (General, Pipelines), inbox panel |
| B. Work management and customisation | 2 | tasks + reminder job + in-app reminders, notes + GridFS attachments, custom fields engine + editor, picklist editor, CSV export/import, rotting job; console Tasks page, task/notes tabs, field forms and columns, Fields and Picklists settings, import/export |

## 13. Calls made without the user (check these)

1. URL prefix `/api/workspaces/{ws}/crm`. (The module, `omnichannel-crm`, and its dependency rule are the user's
   decision; the port list and package layout in §3a are mine.)
2. Scope is the **brand**, like contacts and numbers; nothing is shared across brands of a customer.
3. Entitlement is a per-brand switch with limits, set by platform staff (`crm:enable`), standing in for the plan add-on.
4. Scope = what the reference built (phases 0–2). Lead conversion, segments/campaigns, reports and live updates are out.
5. "Agent's phones" = numbers owned by the user's teams (all numbers with `inbox:view-all`).
6. A CRM contact pairs one brand contact with one channel number, so the same person on two numbers is two CRM
   contacts — the reference's rule, kept even though our contacts are brand-wide.
7. Reminders are in-app (polled endpoint, toast, nav count), not push.
8. Collection names plural (`crmDeals`) per house style; reference sample data is not migrated.
9. Conflicts answer 409 instead of the reference's 400; `POST /save` split into `POST` and `PUT`.
10. USER custom fields store a user id and are checked against the brand's users.
11. Companies stay in the CRM menu.
12. `timelineShowsWhatsappMessages` is stored and editable but, as in the reference, does nothing yet.
13. Demo seeder recreates the sample data's shapes for "Demo Fashion".

## 14. Acceptance (end to end, after phase A; phase B adds its own)

Build: `npm run build` (frontend) → `./mvnw -q -Pwith-frontend -DskipTests package` →
`OMNICHANNEL_SEED_DEMO=true java -jar omnichannel-app/target/omnichannel-app-0.1.0-SNAPSHOT.jar --server.port=8089`.

1. CRM off for a brand: no CRM nav, `/crm` shows the off state, inbox makes no `/crm` request except `status`, CRM API
   answers 402.
2. Platform user switches it on for Demo Fashion only: Demo Electronics still 402; a user without `crm:view` gets 403.
3. Agent with no pipeline: empty state, empty contacts list, inbox Capture disabled with the hint.
4. Admin assigns Sales to the agent on the Users page: the agent sees Sales only, and every contact with a deal in it.
5. Agent captures from a chat assigned to them: the deal appears in lane 1 after Refresh for an admin; an agent with
   access only to another pipeline does not see it. A chat not assigned to the agent or their team → Capture disabled.
6. "New contact" on the board creates the brand contact on the chosen number, the CRM contact (origin MANUAL) and a
   deal; repeating it does not duplicate.
7. Admin creates a pipeline with 8 lanes; board scrolls horizontally; dragging a card to lane 4 records STAGE_CHANGED;
   closing as won moves it to the WON stage; removing a lane that holds deals asks for a target.
8. Open chat from a deal goes to its chat on its own number; an API call naming another number → `CHANNEL_MISMATCH`.
9. Admin moves a contact's channel: history and chip change, deals follow; without `crm:settings` → 403.
10. Moving a deal between pipelines changes which agents see its contact.
11. Only `crm*` collections, their indexes and `crmNoteFiles` are new; existing collections' indexes are unchanged.
12. Light, dark and phone-width layouts work on every CRM page.
