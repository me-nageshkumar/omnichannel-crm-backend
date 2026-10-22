# Contacts — design

Date: 2026-10-20. Repos: `omnichannel-crm-backend`, `omnichannel-crm-frontend`.
Builds on `2026-10-20-phone-numbers-and-enable-disable-design.md` (numbers belong to brands; the
server publishes a `WhatsAppEvent` with `phoneNumberId`, `workspaceId`, `customerId` once the number is
confirmed). The console calls workspaces "brands".

## Why

Each brand needs one list of the people it talks to, so agents know who they are chatting with and
managers can segment, import and export them. Later projects (inbox, campaigns) use the same contacts.
Reference: RedLava's WhatsApp platform (`/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp`, contact
package) — same ideas, adapted to brands and to WhatsApp Web (not Cloud API).

## Goals

1. Contacts are created automatically from WhatsApp chats on a brand's numbers, plus by CSV import
   and by hand.
2. Labels (coloured) and attributes (free-text values), each from a per-brand list.
3. Notes on a contact, with author and time.
4. Filter contacts, save filters, export the result as CSV.
5. Bulk actions on a selection or a filter.
6. Role-based access (table below); everything brand-scoped.
7. Demo data for the demo brand.

## Non-goals

- Storing messages, an inbox, campaigns, assigning contacts to agents.
- Merging two existing contacts into one.
- Contacts shared across brands (the same person in two brands is two contacts).
- Group chats, broadcast lists, status updates, channels/newsletters.

## Anti-goals

- One brand ever seeing another brand's contacts (including via export, filters, bulk, import).
- An agent able to export, import, bulk-edit or delete.
- A huge export or bulk action taking the server down or timing out silently.

## Contact

Collection `contacts`, one per (brand, WhatsApp person):

- `workspaceId`, `customerId` (tenant scoping; every query filters on `workspaceId`).
- `waId`: the WhatsApp chat id of the person, e.g. `919812345678@c.us` or `123456789012345@lid`.
  Unique per brand with `phone` (see identity). Null for a contact created by import/hand until a chat
  arrives.
- `phone`: digits, country code first, or null when hidden.
- `name`: the WhatsApp display name (from the message's sender name) unless a person edited it;
  `nameEdited` stops chats from overwriting a typed name.
- `labels`: set of label ids. `attributes`: map attribute id → string value (≤ 500 chars).
- `source`: `CHAT` | `IMPORT` | `MANUAL` (how it was first created).
- `lastMessageAt`: time of the last chat message seen (in or out). `creationTime`, `lastUpdateTime`,
  `@Version`.

**Identity.** Within a brand: two contacts never share a `phone`, nor a `waId`. A chat from
`…@c.us` finds the contact by `phone` (so an imported contact is linked to its chat, and its `waId`
filled in). A chat from `…@lid` finds by `waId`; if the event also carries the person's phone-number
id (WhatsApp Web exposes it on some versions; the builder checks the real payload), the phone is
filled in — unless another contact of the brand already has that phone, in which case the hidden
contact stays as it is (no merge). A contact with `phone == null` shows "Number hidden".

**From chats.** A listener on `WhatsAppEvent` (`message`, `message_create`): the person is the chat's
`id.remote`. Ignore `@g.us`, `@broadcast`, `@newsletter`, `status@broadcast`. Upsert the contact
(create with `source = CHAT`), set `name` from the sender name of an incoming message unless
`nameEdited`, set `lastMessageAt`. Outgoing messages to a new chat also create the contact. Must be
idempotent (events can be replayed) and must not slow the WebSocket thread noticeably (a Mongo
upsert per message is fine).

## Labels and attributes

- `contactLabels`: `id`, `workspaceId`, `name` (1–40 chars, unique per brand, case-insensitive),
  `color` (one of PatternFly label colours: blue, teal, green, orange, purple, red, orangered, yellow,
  grey), `description` (≤ 200).
- `contactAttributes`: `id`, `workspaceId`, `name` (1–40, unique per brand, case-insensitive),
  `description` (≤ 200). Values are free text.
- Deleting a label or attribute removes it from every contact of the brand (cascade).
- Renaming keeps ids, so contacts follow.

## Notes

`contactNotes`: `id`, `workspaceId`, `contactId`, `text` (1–2000), `createdBy` (username + name),
`creationTime`, `lastUpdateTime`. The author may edit or delete their own note; a Workspace Manager /
Customer Admin may delete any. Deleting a contact deletes its notes.

## Filters

A structured filter (not raw Mongo), ANDed:

- `q`: text — matches name (contains, case-insensitive) or phone (contains digits).
- `labelsAny` / `labelsAll` / `labelsNone`: lists of label ids.
- `attributes`: list of `{attributeId, op, value}` with `op` ∈ `EQUALS`, `CONTAINS`, `EXISTS`,
  `NOT_EXISTS`.
- `source`: any of CHAT / IMPORT / MANUAL. `numberHidden`: true/false.
- `createdFrom`/`createdTo`, `lastMessageFrom`/`lastMessageTo` (dates).

Saved filters (`contactFilters`): `id`, `workspaceId`, `name` (unique per brand), the filter, created
by. Shared by everyone in the brand who can view contacts; created/edited/deleted by those with
`contact:bulk`.

List: page-number paging (50 per page, like Customers), sort by `lastMessageAt` desc (default),
`name`, `creationTime`. The list response includes `total`.

## Export

`GET` CSV of a filter (inline or saved) — streamed, synchronous. Columns: `phone`, `name`,
`number_hidden`, `source`, `labels` (names, `;`-separated), one column per attribute (by name),
`created`, `last_message`. Limit 100 000 rows; above that 400 asking to narrow the filter.

## CSV import

Upload (≤ 5 MB, ≤ 20 000 rows, UTF-8, header row required). Columns, by header name
(case-insensitive): `phone` (required; normalised by stripping `+`, spaces, dashes; must be 8–15
digits), `name`, `labels` (names separated by `;` or `,`), and any column whose header is an existing
attribute name. Unknown columns and unknown label names are reported, not created.
Existing contact (same phone in the brand): labels are added, given attributes overwritten, and a
non-empty `name` column replaces the name (and sets `nameEdited`). Nothing is removed.
Runs synchronously; the reply is a summary: created, updated, skipped rows with row number + reason
(first 200 reasons).

## Bulk actions

Selection: either explicit contact ids (≤ 1 000) or a filter (inline or saved). Actions: add labels,
remove labels, set an attribute value, clear an attribute, delete contacts (delete needs
`contact:delete`). Each runs as one `updateMany` / `deleteMany` scoped by `workspaceId` — no job queue.
The console shows the matched count and asks to confirm before running; the reply says how many
changed.

## Access

New BRAND authorities (group `contact`), granted by default as follows:

| Authority | What | Agent | Team Lead | Workspace Manager | Customer Admin |
|---|---|---|---|---|---|
| `contact:view` | list, view, notes, saved filters | ✓ | ✓ | ✓ | ✓ |
| `contact:annotate` | add/remove labels on a contact, add/edit own notes | ✓ | ✓ | ✓ | ✓ |
| `contact:edit` | edit name/attributes, add a contact by hand | | ✓ | ✓ | ✓ |
| `contact:import` | CSV import | | ✓ | ✓ | ✓ |
| `contact:export` | CSV export | | ✓ | ✓ | ✓ |
| `contact:bulk` | bulk label/attribute actions, saved-filter edits | | ✓ | ✓ | ✓ |
| `contact:delete` | delete contacts (one or bulk), delete others' notes | | | ✓ | ✓ |
| `contact-field:manage` | create/rename/delete labels and attributes | | | ✓ | ✓ |

## Console

- **Contacts** (Brand group, `contact:view`): search box, filter panel (labels any/all/none,
  attributes, source, number hidden, dates), saved filter picker (+ save current), table (name or
  "Number hidden", phone, labels as coloured chips, last message, source), row checkboxes with a bulk
  toolbar ("N selected" / "all N matching"), Import and Export buttons, "Add contact".
- **Contact detail** (drawer or page): name, phone, labels (add/remove), attributes (edit), notes
  (list newest first, add), created/last message, delete.
- **Contact fields** (Brand group, `contact-field:manage`): two tables, Labels (name, colour,
  description, contacts using it) and Attributes; add/rename/delete with confirm showing how many
  contacts lose it.
- Same PatternFly look as the rest of the console.

## Seed data

Extend the demo seeder (`omnichannel.seed.demo=true`): in Demo Fashion, labels VIP (purple), Lead
(blue), Customer (green), Follow-up (orange); attributes City, Email, Preferred size; ~40 contacts with
Indian names and numbers in a reserved test range (`91900000xxxx`), a mix of sources, labels,
attributes, a few "Number hidden", and a few notes. Idempotent like the rest of the seeder.

## Builder questions settled (my calls, while the user was away)

1. The chat listener never throws: it catches and logs, so a database error never blocks the event
   ack and replay. `lastMessageAt` only moves forward (max of stored and event time).
2. The person is the chat's `id.remote`. Only incoming messages set the name (sender name field of
   the payload). If the payload has no phone for a `@lid` chat, the contact simply stays "Number
   hidden" — not a blocker.
3. Contact demo data is a separate seeder (`ContactDemoSeeder`, after `DemoSeeder`) that finds
   "Demo Fashion" by name and skips when the brand already has labels, so it also fills databases
   seeded before this project.
4. Eight new BRAND `Authority` entries with the defaults in the Access table (Customer Admin listed
   like the existing BRAND authorities); existing databases get them through `RoleSeeder`'s
   new-authority rule.
5. Uniqueness uses partial indexes (`phone`/`waId` only when they are strings); null fields are not
   written. A duplicate-key error on a concurrent chat upsert is retried once as an update.
6. Phones match by exact digits (country code first). Imports without a country code won't link to
   chats; the import screen says to include it.
7. A CSV row that changes nothing counts as unchanged. A bulk action on a filter matches at run
   time. Label/attribute delete cascades with one `updateMany`.
8. Export is `POST /export` with the filter (or saved filter id) in the body, streaming CSV.
9. Paging copies Customers: 0-based `page`, 50 per page, `{items, total, page}`.

## Left to the builder

- Exact sender-name and lid phone fields in the message payload (check a real event).
- Indexes (at least `workspaceId+phone` unique sparse, `workspaceId+waId` unique sparse,
  `workspaceId+lastMessageAt`, `workspaceId+labels`).
- Table layout details, drawer vs page for detail.

## My calls (not discussed — check these)

1. Team Leads get import/export/bulk; only Workspace Managers/Customer Admins delete contacts and
   define labels/attributes (agreed in chat as a guess; confirmed "Looks right").
2. Structured filters instead of the reference's raw Mongo filters.
3. Bulk actions run synchronously as single update/delete queries; no background jobs.
4. Import never creates labels/attributes; unknown ones are reported.
5. Deleting a label/attribute removes it from contacts (the reference left dangling ids).
6. Outgoing messages also create contacts; groups/broadcasts are ignored.
7. No merging of a hidden-number contact into an existing one.
