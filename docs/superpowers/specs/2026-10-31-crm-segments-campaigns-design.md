# CRM phase D: segments and campaigns (design)

**Status:** written 2026-10-31 at the user's request ("Plan segments and campaigns first"; "Skip the questions, go ahead
with the spec"). The user asked for one thing in particular: **naming must be channel-neutral** ("currently we are
integrating with WhatsApp Web, later we will do integration with WhatsApp Cloud, Instagram, Facebook lead etc. So, keep
that in naming for field, class, method etc."). Every other call in §3 is the designer's, taken from the reference
(`/Users/nagesh/RedLava/SCM/CRM/redlava-crm` branch `crm-addon-phase-3`, spec
`/Users/nagesh/RedLava/SCM/CRM/whatsapp/docs/superpowers/specs/2026-10-28-crm-phase3-segments-campaigns-design.md`,
decisions D1–D13 and calls C1–C18 there) and translated to this host; flagged, open to change. Amends the port design
(`2026-10-23-crm-port-design.md`, §2.2 said segments were "never built" in the reference — they are now) and the
reports design (`2026-10-30-crm-reports-design.md`, C13 reserved a segment column). Where this disagrees with those,
this one wins.

## 1. Intent

A marketer or sales lead wants to message a group defined by what the CRM knows — "leads in *Proposal* for more than
7 days", "customers in Mumbai with an open deal over ₹50,000", "Gold-tier contacts nobody has messaged in 30 days" —
and send them a campaign from the numbers the brand already uses. Today a campaign's audience is a saved **contact
filter** (host contact fields, labels, attributes) or a CSV; nothing in it knows about pipelines, stages, deals, owners
or custom fields. Phase D adds saved **segments**: live rules over CRM data mixed with the channel contact's own data,
a per-channel-account **preview**, and a third campaign audience kind, **Segment**, resolved by the server when the
campaign starts. Campaign results flow back into the CRM as timeline entries and a segment column on the Reports
campaigns card.

What is different from the reference, and why (details in §3):

- The reference makes the **browser** create one campaign per WhatsApp number through the main app's campaign API,
  because its campaign engine filters one number's contacts. Our campaign engine already takes a list of senders and
  picks, per recipient, the sender whose chat with them was active most recently (channel stickiness), skips contacts
  whose chats live on numbers outside the chosen senders, skips do-not-contact and recently-messaged people, and paces
  for number health. So a segment becomes **one ordinary campaign** with a `SEGMENT` audience, and the reference's send
  records, per-number filters and report-back endpoints are not needed.
- The reference had no opt-out flag; our host contacts carry `doNotContact`, and the campaign engine already honours
  it. The segment resolver does not need to.
- Automation events (Kafka) and lead conversion stay out: the host has no automation engine, no broker and no lead
  intake yet (port design §2.2).

## 2. Decisions carried from earlier phases (binding)

| # | Decision |
|---|---|
| D1 | The CRM is the `omnichannel-crm` module (`me.nageshk.omnichannel.crm.<feature>`); it imports only `common`, `security.access`, `security.auth`; platform data comes through `crm.platform` ports implemented by adapters in `omnichannel-app` (`CrmModuleBoundaryTest`). `omnichannel-service` never imports the CRM either; the two meet only in `omnichannel-app`. |
| D2 | Every query is scoped by `workspaceId` first; every new index leads with `workspaceId` (`CrmIndexIsolationIT`); collections are camelCase `crm*`. |
| D3 | Access: `crm:view` opens the CRM; restricted users see contacts whose `pipelineIds` meet their pipeline access and deals in accessible pipelines; `crm:view-all` sees everything (phase A §4). Fail closed. |
| D4 | Errors are `{code, message, ...extras}` through `CrmErrors`/`CodedException`; 402 `CRM_NOT_ENABLED` when the brand's CRM is off. |
| D5 | No live updates; screens refresh on action and on focus. |
| D6 | Campaign rules (campaigns design 2026-10-21): the audience is materialised at start into `campaignRecipients`; per-number policy, do-not-contact, recontact window and sender choice live in `CampaignMaterialiser`/`RecipientSkips`/`CampaignRunner` and apply to every audience kind; a campaign that keeps messaging someone who asked to stop is an anti-goal. |
| D7 | The console is React 18 + PatternFly 6.5 + TypeScript, vitest; CRM pages live under `src/app/Crm/`, are lazy chunks behind `CrmGate`, and check authorities with `useCrmCan`. |

## 3. Calls made by the designer (flagged; change if wrong)

### Naming (the user's one requirement, applied)

| # | Call | Why |
|---|---|---|
| C1 | **Channel-neutral vocabulary for everything new.** A **channel account** is one connected account a brand messages from — today a WhatsApp Web number, later a WhatsApp Cloud API number, an Instagram account or a Facebook page. New code says `channelAccountId` (never `phoneNumberId`), `ChannelKind` (`WHATSAPP_WEB`, `WHATSAPP_CLOUD`, `INSTAGRAM`, `FACEBOOK` — the first two map from the host's `ConnectionType`; the others are reserved), **channel contact** for the host's `Contact` as seen through a channel (its labels, attributes, last message, source, do-not-contact), **address** for the thing a message is sent to (a phone today; an Instagram handle or page-scoped id later — `address` appears only where a value is shown, never as a field the CRM stores). Existing phase A–C names (`Channel.phoneNumberId`, `NumberDirectory`, `PlatformNumber`, the report filter's `phoneNumberIds`) are **not** renamed in this phase: a rename touches every collection and screen and belongs to its own migration. At the boundary, new code maps `channelAccountId ⇔ Channel.phoneNumberId` in one place (`ChannelAccounts.of(CrmContact)`), so a later rename changes that place only. | The user's instruction, applied without a risky rename of three shipped phases. |
| C2 | **Activity types go neutral**: `WHATSAPP_CAMPAIGN_SENT` → `CAMPAIGN_SENT`, `WHATSAPP_MESSAGE_SENT` → `MESSAGE_SENT`, `WHATSAPP_MESSAGE_RECEIVED` → `MESSAGE_RECEIVED` in `ActivityType` (checked: nothing writes any of the three today, in either repo, so no stored value changes). Entries of these types carry `payload.channelKind`. The settings flag `timelineShowsWhatsappMessages` keeps its stored name (it is written) but the console labels it "Show channel messages on timelines". | Same instruction; the enum is the only place the old names were about to be written. |

### Segments

| # | Call | Why |
|---|---|---|
| C3 | **A segment** (`crmSegments`): `name` (1–80, unique per brand ignoring case → 409 `SEGMENT_NAME_TAKEN`), `description` (≤ 500), `rules` — a rule tree with **the same JSON shape as the host's advanced contact filters** (`{combinator: "and"\|"or", not, rules: [rule \| group]}`, `rule = {field, operator, value}`), so the console reuses its tree helpers and the server its validation style — plus cached counts (C9) and `lastSentAt`/`lastCampaignId`/`lastCampaignName` (C14). The CRM defines its own `SegmentRuleGroup`/`SegmentRule` records (it cannot import the host's `RuleGroup`, D1). Limits: ≤ 30 rules, ≤ 3 group levels, ≤ 50 values in a list, text values ≤ 200 characters, days 1–3650. Violations → 400 `INVALID_SEGMENT` with `fieldErrors: {"<path>": "<message>"}` where `path` is `rules[1].rules[0].value` (the console already maps that shape to "Group 2, rule 1"). | One shape for filters and segments; the console's `filterModel` tree helpers are generic already. |
| C4 | **Field catalogue** (`GET /segments/fields`, built per caller; allowlist class `SegmentFields`). Three groups. **CRM contact** (`contact.`): `lifecycleStatus` (PICK, lifecycle picklist), `ownerId` (USER), `source` (TEXT), `origin.kind` (ENUM `OriginKind`), `companyId` (COMPANY, typeahead), `channelAccountId` (PICK, the brand's channel accounts; today the numbers), `email`, `jobTitle` (TEXT), `creationTime`, `lastActivityAt`, `nextTaskDueAt` (DATE), `openDealCount` (NUMBER), `openDealAmount` (MONEY), `custom:<key>` for every **active** contact field by `FieldType`: TEXT/LONG_TEXT/URL/EMAIL/PHONE → TEXT, NUMBER → NUMBER, CURRENCY → MONEY, BOOLEAN → BOOLEAN, DATE/DATETIME → DATE, SELECT → PICK (its options), MULTI_SELECT → MULTI, USER → USER. **Has a deal where** (`deal.`): `pipelineId` (PICK, accessible pipelines only), `stageId` (PICK, stages of the chosen pipeline; needs a `deal.pipelineId` rule in the same group → else `INVALID_SEGMENT`), `status` (ENUM OPEN/WON/LOST), `amount` (MONEY), `currency` (TEXT), `ownerId` (USER), `source` (PICK, deal sources), `lostReason` (PICK), `daysInStage` (NUMBER, from `stageEnteredAt`), `expectedCloseDate`, `closedAt`, `creationTime` (DATE), `rotting` (BOOLEAN), `custom:<key>` (deal fields, same mapping). **Channel contact** (`channel.`): `label` (LABEL, the brand's labels by id), `attribute:<attributeId>` (TEXT, the brand's attributes), `lastMessageAt` (DATE), `source` (ENUM CHAT/IMPORT/MANUAL), `doNotContact` (BOOLEAN). Operators by type: TEXT `is isNot contains notContains startsWith isSet isNotSet`; NUMBER/MONEY `eq neq lt lte gt gte between`; DATE `before after between withinLastDays moreThanDaysAgo isSet isNotSet` (absolute dates `YYYY-MM-DD` in the brand's zone; relative ones a day count); PICK/USER/ENUM/COMPANY `is isNot in notIn isSet isNotSet`; BOOLEAN `is`; LABEL `has hasNot hasAny hasAll hasNone isEmpty isNotEmpty`; MULTI `contains isSet isNotSet`. A rule naming a label, attribute, custom field, pipeline or stage that no longer exists (or that the caller cannot see) is kept, returned with `missing: true` or `inaccessible: true` on GET, and **matches nothing**. | The reference's C2 field list mapped to our entities; labels and attributes by id because the host keys them by id brand-wide (no per-number merge needed here). |
| C5 | **Evaluation** (`SegmentResolver`). The **universe** is the caller's visible CRM contacts (brand + pipeline access on `pipelineIds`; unrestricted callers: all). Each rule becomes one query returning CRM contact ids: `contact.*` rules on `crmContacts` (universe ∧ rule); all `deal.*` rules **in one group** become one query on `crmDeals` (brand + access on `pipelineId` ∧ every deal rule — *one* deal satisfies them all) projecting `primaryCrmContactId` and `crmContactIds`; `channel.*` rules go through a new port `ChannelContactQuery.matching(workspaceId, rule, limit)` which returns host contact ids (the adapter compiles the rule with the host's own `AdvancedFilters`/`ContactQuery` allowlist — one rule tree of one rule, relative dates already turned absolute by the CRM), then `crmContacts {workspaceId, contactId ∈ ids}` ∧ universe. Groups combine with set algebra in Java: AND intersects starting from the smallest set; OR unions; NOT and the negative operators (`isNot`, `notIn`, `hasNot`, `hasNone`, `neq`) are a complement **within the universe**, which is loaded only then. Every query runs with `maxTime` 10 s (the guard shared with reports) → 503 `SEGMENT_TIMEOUT`; any materialised set over 200,000 ids → 400 `SEGMENT_TOO_BROAD`. The result is `SegmentMembers{members: [{crmContactId, contactId, channelAccountId}], total, people, truncated}` ordered by `crmContactId`, capped at **50,000** records (`truncated: true`); `people` = distinct `contactId`s (C20). | The reference's C3/C4/C16 with the channel side delegated to the host's existing, allowlisted filter compiler instead of a second query language. |
| C6 | **Preview** — `POST /segments/preview {rules}` and `POST /segments/{id}/preview` (the saved rules) — resolves live for the caller and returns `{total, people, truncated, channelAccounts: [{channelAccountId, kind, label, count}]}` grouped by the member's `channelAccountId` (C20). The console debounces 600 ms and drops stale answers. | D8/D9 of the reference: what you see is what you (can) send. |
| C7 | **Who.** New brand authority `CRM_SEGMENTS("crm:segments", BRAND, "Build and preview CRM segments and use them as campaign audiences", CUSTOMER_ADMIN, WORKSPACE_MANAGER)` — `RoleSeeder` grants a new authority to its default roles on the next start, so existing managers get it without a migration. Every `/segments/**` call needs `crm:view` + `crm:segments`; the console hides the Segments nav entry without it. Using a segment as a campaign audience additionally needs the host's `campaign:manage` (enforced by the campaign API as today). Agents get neither by default; a role editor can grant them. | Reference D7/C17, in our authority model. |
| C8 | **Saved segments are brand-wide objects; results are per caller** (reference D8/C13): the same segment previews and sends differently for a restricted agent, whose universe is their pipelines, whose deal rules see only their deals, and whose catalogue offers only their pipelines and stages. | Nobody targets people using data they are not allowed to see. |
| C9 | **Cached counts** for the list page: `cachedCounts {total, people, truncated, channelAccounts: [{channelAccountId, count}], at}` computed over the **brand-wide** universe (the system context) on save and by `SegmentCountRefreshJob` hourly (`CrmJobsTicker`, cron `0 5 * * * *`, behind `omnichannel.crm.jobs.enabled`), one segment at a time; a failing segment is logged and skipped; the write is a targeted `$set` that leaves `version` alone. | The reference's C5; our jobs run in one instance, so no distributed lock yet (the job is idempotent when a second instance runs it). |
| C10 | **Deleting** a segment is refused with 409 `SEGMENT_IN_USE {campaigns: [{id, name}]}` while a DRAFT, RUNNING or PAUSED campaign uses it (through `CampaignDirectory.usingSegment`), like deleting a label a campaign uses; otherwise it is deleted and finished campaigns keep their snapshot of its name. Names are unique per brand. | Predictable; history is never rewritten. |

### Campaigns

| # | Call | Why |
|---|---|---|
| C11 | **A third audience kind, `SEGMENT`**, in the host: `AudienceKind.SEGMENT`; `Campaign.Audience` gains `segmentId`, `segmentName`; `CampaignRequest.AudienceRequest` gains `segmentId`; `CampaignView.AudienceView` returns both. The console's campaign wizard offers "CRM segment" as a third radio when the workspace grants `crm:view` and `crm:segments`, lists the segments with their cached total ("≈ 1,240 people, as of 09:05"), and accepts `?segmentId=` to preselect one; the Review step shows "Segment: <name>" and the live preview total; the campaign list and detail say "Segment: <name>". | One ordinary campaign per segment; the engine already does per-recipient sender choice (§1). |
| C12 | **The host reaches the CRM through a port in `omnichannel-service`**: `me.nageshk.omnichannel.service.campaign.SegmentAudiences { Optional<SegmentRef> find(workspaceId, segmentId); AudienceMembers resolve(workspaceId, segmentId, AuthenticatedUser actor); }` with `SegmentRef{id, name}` and `AudienceMembers{contactIds (host contact ids, distinct, in member order), total, people, truncated}`. Implemented in `omnichannel-app` by `app.crm.SegmentAudiencesAdapter`, which calls the CRM module's public `crm.segment.SegmentAudienceApi` (resolves with `CrmAccess.resolve(actor, ws)` → the actor's universe, C8). `CampaignValidator` resolves `audience.segmentId` through `find` (unknown → 400 `audience.segmentId` "unknown segment"; the acting user without `crm:view`+`crm:segments` → 403). `CampaignLifecycleService.start` gains the acting user (the controller already has `me`): `CampaignMaterialiser` resolves the members for **the user who starts the campaign**, then loads the host contacts by id in pages of 500 (`phone != null`) and judges them exactly as a filter audience (`RecipientSkips`: do-not-contact, recently messaged, chat on another number, preferred sender). A segment that resolves to nobody starts and finishes with zero recipients, as an empty filter does today; a truncated one refuses to start (C23); a member without a chat on a chosen sender is preferred to its own channel account when that is a chosen sender (C21). | The module boundary (D1) forbids `service → crm`; a port in `service` with an adapter in `app` is the pattern phase C used in the other direction. Resolving for the starter keeps C8 honest. |
| C13 | **Campaign results on the timeline** (reference D12/C7/C15, done at the real moment instead of at creation): a new port in `omnichannel-service`, `CampaignObserver.sent(CampaignSent{workspaceId, campaignId, campaignName, segmentId?, segmentName?, contactId, channelAccountId, channelKind, sentAt})`, called by `CampaignRunner` when it marks a recipient `SENT` (best effort: an exception is logged and never fails the send). The app adapter forwards to the CRM's `CampaignActivityRecorder`, which appends one `CAMPAIGN_SENT` activity (actor `system`, `ActorType.SYSTEM`) to **every** CRM contact of that brand with that `contactId` — summary `Campaign "<name>" sent`, payload `{campaignId, campaignName, segmentId, segmentName, channelAccountId, channelKind}`, `phoneNumberId` = the channel account (phase A's stamping rule) — through a new `ActivityLog.append(...)` that **does not** raise `lastActivityAt` (C15 of the reference: a "no activity in 30 days" segment must keep working after it is used) and never names a deal (so it never clears rotting). This applies to **every** campaign whose recipient is a CRM contact, not only segment ones; the segment fields are null otherwise. The console timeline renders it ("Campaign “Diwali offer” sent from +91 98…"). | The CRM learns of real sends, per channel, for every campaign — the data the reference could not get. |
| C14 | **`lastSentAt`** on the segment is set (with `lastCampaignId`/`lastCampaignName`) when a campaign with that segment **starts** (`SegmentAudiences.resolve` records it; a start that fails afterwards leaves the stamp, which is acceptable). | The list page's "Last sent" column. |
| C15 | **Reports campaigns card** gains a `segment` column: `PlatformCampaign` gains `segmentId`, `segmentName` (read from `audience`), `CampaignsView.Row` gains `segmentId`, `segmentName`; the console card shows the segment name (or "—"). Nothing else in the card changes. | The column reports C13 reserved. |

### Console

| # | Call | Why |
|---|---|---|
| C16 | **Nav entry "Segments"** in the CRM group between Companies and Reports, shown only with `crm:segments`; routes `/crm/segments` (list), `/crm/segments/new`, `/crm/segments/:id` (editor), lazy chunks behind `CrmGate` like every CRM page; a user with `crm:view` but not `crm:segments` who types the URL sees an empty state "You need the Segments permission". | Same placement as the reference. |
| C17 | **List page**: table Name (link; description under it), Audience (cached total with "as of <time>"; a popover lists the per-channel-account counts and "first 50,000 only" when truncated; "Not counted yet" before the first count), Last sent (campaign name linking to `/campaigns/<id>`, or "Never"), actions **Edit**, **Send campaign** (with `campaign:manage`; goes to `/campaigns/new?segmentId=<id>`), **Delete** (confirm; on 409 lists the campaigns still using it). Toolbar: search by name, "New segment". | The reference's list, plus the host's campaign link. |
| C18 | **Editor**: name, description, the rule builder and a "Who matches" preview card. The builder is a **new `SegmentRuleEditor` in `src/app/Crm/segments/`** driven by the server catalogue (C4): field select grouped "CRM contact / Has a deal where / Channel contact", operator select by type, and a value editor per type — text, number, money, date picker, day count, picklist select, pipeline then stage select, user select (`useBrandUsers`), the existing `CompanyTypeahead`, the existing `LabelPicker`, Yes/No. It reuses the generic tree helpers of `src/app/Contacts/Filters/filterModel.ts` (`nodeAt`, `replaceAt`, `addTo`, `removeAt`, `Path`, `rulePath`, `nodeName`, `splitErrors`) but **not** `RuleEditor`/`RuleTreeEditor`, whose field model is the host contact's. An AND group holding two or more deal rules shows the hint "all deal conditions in this group must match the same deal" (C22). Rules flagged `missing`/`inaccessible` are shown greyed with the reason and can be removed. Save → list; a `SEGMENT_NAME_TAKEN` error lands on the name field; `INVALID_SEGMENT.fieldErrors` land on their rules. | The two builders share the tree but not the fields; forcing one component to serve both would widen the contacts builder for no user benefit. A later unification is possible, not required. |
| C19 | **Preview card**: total, "first 50,000 only" when truncated, one row per channel account (label, kind icon, count), "Finish the rules to see who matches" while a rule is incomplete, the server's `SEGMENT_TOO_BROAD`/`SEGMENT_TIMEOUT` messages inline, a Refresh button. | Reference C11. |

### Settled after the builder check (a fresh reviewer read the draft; these are the designer's answers, flagged)

| # | Call | Why |
|---|---|---|
| C20 | **A member is a CRM contact record, and the numbers say so.** `crmContacts` is unique per (brand, host contact, channel account), so one person captured on two numbers is two records. `total` counts records; `people` counts distinct host contacts; the per-channel-account counts sum to `total`; the 50,000 cap and the `crmContactId` order apply to records. The console shows "1,240 CRM contacts" and adds "(1,180 people)" only when the two differ. The campaign receives the distinct host contact ids (`people`), so nobody is messaged twice. | Honest counts; the campaign engine dedupes by phone anyway. |
| C21 | **The preview informs; the send follows the member's channel account when nothing better is known.** The campaign engine keeps choosing the sender by the most recent chat among the chosen senders and skipping contacts whose chats live elsewhere (D6). For a member with **no** chat on any chosen sender (imported or manually created CRM contacts), the materialiser sets `preferredSenderId` to the member's `channelAccountId` when that account is among the senders — channel stickiness for people who have not chatted yet. The wizard's Review step shows the preview's per-account rows and marks the accounts that are **not** chosen senders ("n contacts landed on <label>, which is not a chosen sender — those with chats there will be skipped"); it warns, it does not block. | Keeps the one campaign engine and its rules; surfaces the one thing a marketer would otherwise learn from the skip list. |
| C22 | **Deal rules: "the same deal" applies inside AND groups only.** In an AND group, every `deal.*` rule must be met by one deal (one query). In an OR group, each `deal.*` rule is its own "has a deal where …" (one query each). `deal.stageId` needs a `deal.pipelineId` rule in the same **AND** group (400 `INVALID_SEGMENT` otherwise, message "needs a pipeline rule in the same group"). The editor shows the "must match the same deal" hint only on AND groups holding two or more deal rules. | The only reading under which both combinators mean what they say. |
| C23 | **A truncated segment cannot be sent.** When the starter's resolution is `truncated` (over 50,000 records), `POST /campaigns/{id}/start` answers 400 `{code: "VALIDATION", field: "audience.segmentId", message: "The segment has more than 50,000 contacts for you; narrow its rules before sending."}` and the campaign stays a draft. The preview and the wizard say the same thing beside the "first 50,000 only" mark. | A send is irreversible; messaging an arbitrary half of a 120,000-person segment is worse than refusing. |

## 4. Data model

| Collection | Fields | Indexes |
|---|---|---|
| `crmSegments` (new) | `workspaceId`, `customerId`, `name`, `nameKey` (lower-cased, trimmed), `description`, `rules` (`SegmentRuleGroup`), `cachedCounts {total, people, truncated, channelAccounts[{channelAccountId, count}], at}`, `lastSentAt`, `lastCampaignId`, `lastCampaignName`, `creationTime`, `lastUpdateTime`, `createdBy`, `lastModifiedBy`, `@Version version` | unique `ws_nameKey {'workspaceId': 1, 'nameKey': 1}`; `ws_name {'workspaceId': 1, 'name': 1}` for the list order |
| `campaigns` (host, changed) | `audience.kind` may be `SEGMENT`; `audience.segmentId`, `audience.segmentName` | none new (the in-use check reads `{workspaceId, status, audience.segmentId}` through the existing `ws_status` index) |
| `crmActivities` (changed) | new type value `CAMPAIGN_SENT` (C13); the three renamed enum values (C2) | none new (`ws_type_happened` exists) |

New ports in `crm.platform`: `ChannelContactQuery { List<ChannelLabel> labels(ws); List<ChannelAttribute> attributes(ws); List<ChannelAccount> accounts(ws); Set<String> matching(ws, ChannelRule rule, int limit); }` with `ChannelRule{field, operator, value}` (fields `label`, `attribute:<id>`, `lastMessageAt`, `source`, `doNotContact`; dates absolute) and `ChannelAccount{id, kind, label, address}`; `CampaignDirectory` gains `List<CampaignRef> usingSegment(ws, segmentId)` (DRAFT/RUNNING/PAUSED). New ports in `service.campaign` (C12, C13): `SegmentAudiences`, `CampaignObserver`. Adapters in `app.crm`: `ChannelContactQueryAdapter` (over `ContactLabelRepository`, `ContactAttributeRepository`, `PhoneNumberRepository`, `AdvancedFilters`, `ContactQuery`, `MongoTemplate`), `SegmentAudiencesAdapter`, `CampaignObserverAdapter`, and `CampaignDirectoryAdapter` extended.

## 5. API (`/api/workspaces/{ws}/crm/segments`; `crm:view` + `crm:segments`; JSON)

| Endpoint | Purpose |
|---|---|
| `GET /` | list `[{id, name, description, cachedCounts, lastSentAt, lastCampaignId, lastCampaignName, lastUpdateTime, version}]`, by name |
| `GET /{id}` | one segment, rules annotated per caller: each rule may carry `missing: true` or `inaccessible: true` |
| `POST /` | create `{name, description, rules}` → 201 the segment (counts refreshed inline, best effort) |
| `PUT /{id}` | update `{name, description, rules, version}` → the segment; 409 `VERSION_CONFLICT` on a stale version (the CRM's usual rule) |
| `DELETE /{id}` | 204; 409 `SEGMENT_IN_USE {campaigns}` (C10) |
| `GET /fields` | the catalogue (C4): `{groups: [{key, label, fields: [{key, label, type, operators, options?, dependsOn?}]}]}`, built for the caller |
| `POST /preview {rules}` | `{total, people, truncated, channelAccounts: [{channelAccountId, kind, label, count}]}` (C6, C20) |
| `POST /{id}/preview` | the same for the saved rules |

Errors: 400 `INVALID_SEGMENT {fieldErrors}`, 400 `SEGMENT_TOO_BROAD`, 409 `SEGMENT_NAME_TAKEN`, 409 `SEGMENT_IN_USE
{campaigns}`, 503 `SEGMENT_TIMEOUT`, 404 for an unknown id; 402/403 as every CRM endpoint.

Host campaign API changes: `audience: {kind: "SEGMENT", segmentId}` accepted by create/update; the audience view carries
`segmentId`, `segmentName`; `POST /{id}/start` resolves for the caller. Validation errors: `audience.segmentId`
"required" / "unknown segment"; 403 when the caller lacks `crm:view` or `crm:segments`.

## 6. Console (`src/app/Crm/segments/`, plus small changes elsewhere)

- `SegmentsPage.tsx` (C17), `SegmentEditorPage.tsx` (C18), `SegmentRuleEditor.tsx` + `segmentModel.ts` (field catalogue
  types, operator labels, value kinds, `cleanRules`, `isComplete`), `SegmentPreview.tsx` (C19), `useSegmentFields.ts`.
- `src/app/api/crm.ts`: `Segment`, `SegmentRules`, `SegmentFieldCatalog`, `SegmentPreview` types and the eight calls.
- `src/app/Campaigns/NewCampaign/`: `draft.ts` (`kind: 'SEGMENT'`, `segmentId`, `segmentName`), `AudienceStep.tsx`
  (third radio, segment select with cached totals, `?segmentId=` preselect), `ReviewStep.tsx` (segment line and the
  live preview total), `CampaignsPage.tsx`/`CampaignDetailPage.tsx` (`audienceText`).
- `src/app/Crm/reports/cards/CampaignsCard.tsx`: Segment column. `src/app/Crm/activity/CrmTimeline.tsx`: `CAMPAIGN_SENT`.
- `src/app/routes.tsx`: nav entry; `src/app/Crm/routes.tsx`: three routes.
- Tests: vitest with mocked fetch as phases A–C; fixtures in `src/test/crm.ts` (`sampleSegment`, `sampleSegmentFields`,
  `sampleSegmentPreview`).

## 7. Demo data

`CrmDemoSeeder` (Demo Fashion) adds two segments with their counts computed at seed time: **"Gold-tier deals in Sales"**
(has a deal where pipeline = Sales and custom `tier` = Gold) and **"Quiet contacts"** (channel `lastMessageAt`
`moreThanDaysAgo 30` or `isNotSet`, and lifecycle is not Customer). Both are owned by the system actor like the rest
of the seed. Unlike the rest of the seeder (which skips a brand that already has CRM settings), the segment step runs
whenever the demo brand has the CRM on and **no segments yet**, so an existing local demo database gains them on the
next start without a re-seed.

Dates typed as `YYYY-MM-DD` are read in the brand's zone, which the CRM learns through a new `BrandDirectory.zoneOf(ws)`
(the host's campaign-settings time zone, `WorkspaceZone`); relative day counts are computed from the clock.

## 8. Out of scope (explicit)

Frozen/static member lists; automation events and the Kafka producer (no engine in the host); lead conversion and
lead-channel defaults (no lead intake); segments over companies; exporting segment members; per-segment quiet hours or
caps beyond the campaign policy; A/B tests, drips and follow-ups; a campaign analytics page beyond the Reports card;
renaming phase A–C fields to the neutral vocabulary (C1); multi-instance job locking.

## 9. Acceptance (end to end, as `demo-manager` on the seeded brand)

1. Segments shows in the CRM nav; the list shows the two seeded segments with counts "as of" the seed time.
2. New segment: "Festive buyers" = has a deal where pipeline = Sales, status = WON; preview shows the won deals'
   contacts grouped by number; save; the list shows it; the name reused → the name field says it is taken.
3. Edit it to add "channel contact label has VIP" (AND) — the preview shrinks; make the top group NOT — the preview is the
   complement within the brand's CRM contacts.
4. Send campaign → the campaign wizard opens with the segment preselected and its total; pick a template and a sender;
   Review says "Segment: Festive buyers"; start. The campaign materialises exactly the previewed people minus
   do-not-contact / recently messaged / chat-on-another-number skips, which the recipients tab names.
5. When a recipient is marked sent (dev: the device simulator, or a manually sent recipient), the contact's CRM timeline
   shows `Campaign "…" sent from <number>` and the contact's `lastActivityAt` is unchanged.
6. The segment's "Last sent" names the campaign; Reports → Campaigns card shows the segment column.
7. Deleting the segment while the campaign runs is refused and names the campaign; after it finishes, deletion works.
8. As `demo-agent` (Sales only, no `crm:segments`): no Segments nav entry; `/crm/segments` shows the permission empty
   state; the campaign wizard has no segment radio. Grant the authority to the Agent role: the preview of "Festive
   buyers" counts only Sales contacts.
9. Light, dark, 375 px: the editor stacks (builder above preview), the list's audience popover is reachable by keyboard.
