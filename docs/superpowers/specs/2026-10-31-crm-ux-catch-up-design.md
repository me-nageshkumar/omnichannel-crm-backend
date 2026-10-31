# CRM phase F: UX catch-up (design)

**Status:** written 2026-10-31 at the user's request ("Ok, let's do UX catch-up bundle"), under the standing instruction
to skip the questions and write the spec. Every call in §3 is the designer's, taken from the reference
(`/Users/nagesh/RedLava/SCM/CRM/redlava-crm` branch `crm-addon-phase-3`, console
`/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm`) and translated to this host; flagged, open to
change. Keeps phase D's naming rule (C1 of `2026-10-31-crm-segments-campaigns-design.md`): nothing new says
`phoneNumberId`. Where this disagrees with the port design (`2026-10-23-crm-port-design.md`), this one wins.

## 1. Intent

Three large gaps are closed (reports, segments, tasks). What remains against the reference is eight small things a
daily user trips over: the board does not say how long a deal has sat in its stage and cannot open a chat; a deal can
only name one person, though the data model holds many; the inbox panel shows open deals but cannot move them or start
one; the contact drawer hides the labels and attributes the inbox shows; a company's contacts cannot be messaged from
the company; importing means guessing column names; nobody can read what changed in the CRM settings; and a restricted
agent can read the name of a pipeline they are not allowed to see.

The eight items, each independently useful:

1. Days in stage and Open chat on deal cards.
2. Several contacts on one deal: pick them in the deal form, list them in a Contacts tab.
3. Inbox panel: change an open deal's stage; start a new deal.
4. Labels and attributes in the contact drawer.
5. Open chat from a company's contacts.
6. CSV import template download.
7. Settings history.
8. Redaction of cross-pipeline moves for restricted users.

## 2. Decisions carried from earlier phases (binding)

| # | Decision |
|---|---|
| D1 | Module boundary: `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; platform data through `crm.platform` ports with adapters in `app.crm` (`CrmModuleBoundaryTest`). |
| D2 | Every query scoped by `workspaceId` first; collections camelCase; no new collection or index in this phase. |
| D3 | Access: `crm:view` opens the CRM; restricted users see contacts whose `pipelineIds` meet their access and deals in accessible pipelines; `crm:settings` guards settings changes; fail closed. |
| D4 | Errors `{code, message, ...extras}` through `CrmErrors`/`CodedException`; validation is `{code: "VALIDATION", fields: {key: message}}`. |
| D5 | No live updates; screens refresh on action and on focus. |
| D6 | Naming is channel-neutral in new code. Existing `Channel.phoneNumberId` and `useOpenChat(id, phoneNumberId)` stay (phase A names). |
| D7 | Console: React 18 + PatternFly 6.5 + TypeScript, vitest; `useLoad`/`useAction`, `useCrmCan`; phone width (375 px) on every screen. |
| D8 | Deals already store `primaryCrmContactId` and `crmContactIds`; `POST`/`PUT /deals` already accept `crmContactIds`, keep the primary first, cap the list at 50 and refuse a contact on another number with 409 `CHANNEL_MISMATCH` (phase A `DealService.linked`). `PUT` with `crmContactIds` absent leaves the list unchanged. |

## 3. Calls made by the designer (flagged; change if wrong)

### Deal cards (item 1)

| # | Call | Why |
|---|---|---|
| C1 | **Days in stage** shows on OPEN deals only, as subtle small text: "New in stage" under one full day, else "<n>d in stage", from `stageEnteredAt` and the browser's clock. Closed deals show nothing. No `stageEnteredAt` shows nothing. | Reference label; on a Won lane the number means nothing, so the reference's "every deal" is narrowed. |
| C2 | **Open chat on the card** is a plain icon button named "Open chat with <contact>", shown when the deal has a primary contact. Clicking it never opens the drawer and never starts a drag. It uses the one `useOpenChat` of the page; its refusal shows in the page's notice area. Table view unchanged. | Reference `DealCard`. One hook per page, not per card. |

### Several contacts on one deal (item 2)

| # | Call | Why |
|---|---|---|
| C3 | **`GET /crm/deals/{id}/contacts`** returns `DealContactView[]` `{id, name, phone, jobTitle, primary}`, primary first then list order, in one query. Access follows the deal (404 when it is not visible). A linked contact that no longer exists is left out. | The reference fetched each contact separately, a request per row. A deal's contacts always carry its pipeline in `pipelineIds`, so nobody who sees the deal is refused a contact on it. |
| C4 | **`POST /contacts/search` gains `channelAccountId`** (filters on `channel.phoneNumberId`, through `ChannelAccounts.FIELD`). The deal form passes it so the picker only offers contacts a save would accept. | D8's rule, surfaced before the 409 rather than after. New filter name is neutral (D6). |
| C5 | **Deal form:** "Other contacts" below "Primary contact", on create and edit: a search picker plus removable chips, at most 49. On create it is disabled until a primary is chosen, and changing the primary clears it. On edit the chips load from C3. **If that load fails, the form says so, disables the field and omits `crmContactIds` from the `PUT`**, so a failed read can never wipe the list. | Reference field. The omission is the safety rule: `crmContactIds: [primary]` from an empty form would unlink everyone. |
| C6 | **Drawer:** a Contacts tab after Details: name (link), "Primary" label, phone, job title, and an Open chat button per row using the row's own contact, no number passed. Editing stays in the form. | Reference `DealContacts`. |
| C7 | **Timeline:** a change to a deal's contact list records under the field `contacts` with names ("Asha, Ravi"), never raw ids. The rewrite applies only to what the activity records: `DealService.update` writes Mongo from the same diff list, so the stored field stays `crmContactIds`. Names come from `displayName`; an unknown id shows "a removed contact". | The form makes this entry reachable for the first time, and raw ids in a timeline read as a bug. |

### Inbox panel (item 3)

| # | Call | Why |
|---|---|---|
| C8 | **Stage select per open deal**, for `crm:edit`, using `POST /deals/{id}/move` with no neighbours. It offers **OPEN stages only**, plus the deal's current stage if that is somehow not OPEN. After a move the summary refetches. A failure uses `moveMessage` and refetches on `VERSION_CONFLICT`. | The reference offers every stage, but a stage move into LOST closes a deal with no reason, and one mis-tap in a narrow panel should not close a deal. Closing stays on the deal page. |
| C9 | **New deal** reuses `DealFormModal`, not the capture call. New props: `contact` (fixes the primary, picker disabled) and `pipelines` (a Pipeline select when there is more than one usable). The button shows for `crm:edit` when the caller has a usable pipeline. | Capture returns the existing open deal when the contact has one in that pipeline, so the reference's button cannot start a second deal there, which is the main reason to press it. `POST /deals` also enforces required custom fields, which only the full form can fill. |

### Contact drawer (item 4)

| # | Call | Why |
|---|---|---|
| C10 | **`GET /crm/contacts/{id}/channel-details`** returns `{labels: [{id, name, color}], attributes: [{name, value}]}`, sorted by name, read-only. Access follows the contact. New port method `ChannelContactQuery.details(workspaceId, contactId): ChannelContactDetails`, adapter in `app.crm`. A label or attribute whose definition is gone is dropped; a deleted host contact returns empty lists, not 404. | Reference shows them read-only ("edited in Contacts or the Inbox"). Host contacts store label ids and attribute ids, so only the host can name them. |
| C11 | **Display:** "Labels" and "Attributes" rows in the Details tab, shown only when non-empty, label colours as in the host. A failed load hides the rows silently; the drawer is never blocked by the channel side. | Same soft-failure rule as the inbox panel. |

### Company drawer (item 5)

| # | Call | Why |
|---|---|---|
| C12 | **Open chat per contact** in the Contacts tab, using `useOpenChat` with the contact's own number; a refusal shows above the list. The tab keeps its existing cap (`MAX_SIZE`) and gains nothing else. | Reference `CompanyContacts`. |

### CSV template (item 6)

| # | Call | Why |
|---|---|---|
| C13 | **Server-generated:** `GET /crm/contacts/import/template` and `GET /crm/companies/import/template`, `crm:edit`, `text/csv` with BOM, file `crm-contacts-template.csv` / `crm-companies-template.csv`. The columns live next to the import in `CsvImportService`, plus one `Label (key)` column per active custom field, the shape the import already reads. | The reference builds it in the browser, which duplicates the column list. A round-trip test (generate, fill a row, import) pins the two together. |
| C14 | **Header row only, no example row.** | The reference adds an example row. Left in by accident, it creates a fake contact, a fake host contact and a deal. |
| C15 | "Download template" button in `ImportModal`, shown before a file is chosen. | Reference placement. |

### Settings history (item 7)

| # | Call | Why |
|---|---|---|
| C16 | **`GET /crm/settings/history?page=&size=`** returns `CrmPage<ActivityView>` of `SETTINGS_CHANGED` entries, newest first, default size 50, max 200. Needs `crm:view` and `crm:settings`. The entries include pipeline-access changes, which is why plain `crm:view` is not enough. | Reference rule ("settings changes, agent pipeline assignments included, are not disclosed"). The reference has no screen; this adds one. |
| C17 | **Summaries say what changed.** `CrmSettingsService.update` compares request and current and writes "Updated CRM settings: default currency, lost reasons" (labels in §5), with `payload.changed` holding the keys; nothing changed writes nothing. Old entries keep "Updated CRM settings". The history read replaces a user id named in `payload.userId` with the person's name, so "Changed CRM pipelines of <id>" reads properly, old entries included. | A log repeating "Updated CRM settings" answers nothing. Small, and the only way item 7 is worth having. |
| C18 | **Screen:** a "History" tab last in CRM settings, shown only with `crm:settings`, using the existing `Timeline` component with Load more. | Existing component; no new visual language. |

### Redaction (item 8)

| # | Call | Why |
|---|---|---|
| C19 | **Applied at read in `TimelineService`, on every timeline a restricted caller reads** (deal, contact, company), to every `STAGE_CHANGED` entry, side by side. A side is **hidden** when its pipeline id (`fromPipelineId` / `toPipelineId`) is outside the caller's access, or its stage id (`fromStageId` / `toStageId`) belongs to a pipeline outside their access and to none inside it. A hidden from-side loses `fromPipelineId`, `fromStageId`, `fromStageName`, `fromStageEnteredAt`; a hidden to-side loses `toPipelineId`, `toStageId`, `toStageName`, `toPipelineName`. Summaries: from-side hidden "Moved into <stage> (<pipeline>) from another pipeline"; to-side hidden "Moved from <stage> to another pipeline"; both hidden "Moved within another pipeline". Unrestricted callers see the stored entry. | The reference redacts the source side of cross-pipeline moves on deal timelines only. That is not enough here: contact and company timelines merge the same entries, and `Placement.retag` moves a deal's whole history to its new pipeline, so moves made inside a hidden pipeline, or into one and back out, stay readable. Stage ids catch those entries, which carry no pipeline id. Testing against hidden stages rather than visible ones means a stage later removed from a visible pipeline keeps its history. A stage removed from a hidden pipeline is unknown and stays readable: an accepted limit, since §4 rules out a migration. `fromStageEnteredAt` is ours (phase C). |
| C20 | **Console:** a `STAGE_CHANGED` entry missing both `fromStageId` and `fromStageName`, or both `toStageId` and `toStageName`, shows its `summary`, instead of "Stage changed from a removed stage to …". An entry that keeps its id and only lost a name still reads as today. | Otherwise redaction reads as a data bug. |

### Settled after the builder check (a fresh reviewer read the draft; these are the designer's answers, flagged)

| # | Call |
|---|---|
| C21 | History a deal made inside a hidden pipeline is redacted too, by stage id, because `retag` makes it visible after a move (C19). It costs one read of the brand's pipelines per timeline page for restricted callers only. |
| C22 | Redaction covers a hidden destination as well as a hidden source (V to H to V, H1 to H2 to V), with the three summaries of C19. Fail closed, as D3 says. |

## 4. Data model

No new collection, no index, no migration. Activities written from now on may carry `payload.changed` (C17) and a
`contacts` diff (C7). Older entries still read correctly.

## 5. API (`/api/workspaces/{ws}/crm`; JSON unless stated)

| Call | Rights | Response |
|---|---|---|
| `GET /deals/{id}/contacts` | `crm:view` | `DealContactView[]` |
| `POST /contacts/search` | `crm:view` | body gains `channelAccountId?: string` |
| `GET /contacts/{id}/channel-details` | `crm:view` | `{labels: [{id, name, color}], attributes: [{name, value}]}` |
| `GET /contacts/import/template` | `crm:view`, `crm:edit` | CSV |
| `GET /companies/import/template` | `crm:view`, `crm:edit` | CSV |
| `GET /settings/history?page=&size=` | `crm:view`, `crm:settings` | `CrmPage<ActivityView>` |

`GET /activities` keeps its shape; restricted callers get redacted entries (C19).

**Template columns.** Contacts: `WhatsApp number, First name, Last name, Email, Job title, Company, Lifecycle, Source`.
Companies: `Name, Domain, Website, Industry, Size, Annual revenue, Phone, Email, City, Country, Description`. Then
`Label (key)` per active custom field of that entity.

**Settings change labels (C17).** `defaultCurrency` default currency, `dealRottingDays` rotting days, `allowChannelMove`
channel moves, `timelineShowsWhatsappMessages` channel messages on timelines, `lifecycleStatuses` lifecycle statuses,
`dealSources` deal sources, `taskTypes` task types, `lostReasons` lost reasons, `industries` industries.

## 6. Console (`src/app/Crm/`)

- `api/crm.ts`: `DealContact`, `ChannelDetails`, `ContactSearch.channelAccountId`, `dealContacts`, `channelDetails`,
  `importTemplate(token, ws, 'contacts' | 'companies')`, `settingsHistory`.
- `deals/DealCard.tsx`, `deals/daysInStage.ts` (C1), `deals/PipelinesPage.tsx` (C2).
- `deals/ContactPicker.tsx` (`channelAccountId` filter; `onChange(id, contact)`), new `deals/OtherContactsField.tsx`,
  `deals/DealFormModal.tsx` (C5, C9), new `deals/DealContacts.tsx`, `deals/DealDrawer.tsx` (C6).
- `inbox/CrmChatPanel.tsx` (C8, C9).
- `Contacts/CrmContactDrawer.tsx` (C11), `Companies/CompanyDrawer.tsx` (C12), `csv/ImportModal.tsx` and its two callers
  (C15), new `Settings/SettingsHistory.tsx`, `Settings/CrmSettingsPage.tsx` (C18), `activity/CrmTimeline.tsx` (C20).

## 7. Out of scope (explicit)

- Editing labels or attributes from the CRM; they stay read-only (C10).
- Changing a deal's primary contact from the form (unchanged from phase A).
- Removing a linked contact from the Contacts tab; the form does that (C6).
- Bulk actions on the board; days in stage in the table view; filtering by days in stage.
- Diffs in settings history beyond which settings changed (C17 names them, it does not show old and new values).
- Retroactively improving old settings entries beyond the user-name substitution.
- The deferred minors of phases D and E; each stays in its ledger.

## 8. Acceptance (end to end, as `demo-manager` and `demo-agent` on the seeded brand)

1. Board: open deals show "New in stage" or "<n>d in stage"; closed lanes show nothing. The chat icon opens the chat,
   does not open the drawer, and dragging the card by its body still works.
2. Edit a deal: add two contacts on the same number; contacts on another number are not offered. The Contacts tab lists
   three, the primary marked, each with Open chat. The timeline reads "Changed contacts" with names.
3. On a new deal, "Other contacts" is disabled until a primary is chosen.
4. Inbox, a captured chat: change an open deal's stage; the panel refreshes; Won and Lost are not offered. New deal
   opens the full form with the contact fixed, and creates a second deal in a pipeline that already has one open.
5. Contact drawer of a labelled contact: Labels and Attributes rows appear; on a contact with neither they do not.
6. Company drawer, Contacts tab: Open chat works; for a number the user does not work, the refusal shows.
7. Import modal: Download template gives a header-only CSV with custom field columns; filling a row and importing it
   works.
8. Settings: change the default currency and a lost reason; History reads "Updated CRM settings: default currency, lost
   reasons". Assign a pipeline to the agent; History names the agent. An agent sees no History tab, and the endpoint
   answers 403.
9. In a pipeline the agent cannot see, move a deal between two stages, then move it into a pipeline they can see. As
   the agent, the deal's and the contact's timelines read "Moved within another pipeline" for the first move and "Moved
   into <stage> (<pipeline>) from another pipeline" for the second, and name nothing hidden. As the manager, both
   entries name every stage.
