# One contact for a person seen under two WhatsApp ids — design

Date 2026-11-04. The user chose "option 1: one contact, two chats" and skipped the brainstorming questions; every
decision marked **(my call)** is the builder's and can be reversed. A chat-level merge (one thread per person) is a
possible follow-on and is out of scope here.

## 1. Intent

WhatsApp Web addresses a person either by their phone id (`<digits>@c.us`) or by a hidden id (`<digits>@lid`), and the
same person can arrive under either over time. Our inbox keeps one chat per number and remote id, so one person can be
two chats. That is tolerable. What is not tolerable is that the person also becomes **two contacts**: a `@c.us` chat
finds its contact by phone, a `@lid` chat finds it by the one stored WhatsApp id, and a contact holds one id only. Two
contacts mean two CRM candidates (Add to CRM twice, two leads), two "chat is new" automation events (two welcome
templates), two sets of labels and notes, and a contacts list that lies.

This feature makes the contact the single record of a person across both ids. Chats stay separate, but every chat of
the person points at the same contact, and the contact knows all the ids WhatsApp has used for them.

## 2. Goals, non-goals, anti-goals

Goals
- A contact holds a **set** of WhatsApp ids. A message under a second id joins the existing contact instead of creating
  a new one, as soon as we can tell the two ids are one person.
- We tell the ids apart in three ways, in order: the phone a `@lid` message carries (`senderPn`), a WhatsApp Web lookup
  through the extension (`resolveIds`), and the console-send fold that exists today.
- When a duplicate pair already exists (older data, or a race before the join), the two contacts are **merged**: the
  survivor takes the other's ids, labels, blank fields, notes and chats; CRM records are re-pointed.
- "Chat is new" in Automations means "first real inbound message from this **person** on this number", not from this
  chat.
- Existing duplicates in a brand can be resolved on demand (one action in the contacts page).
- The inbox tells an agent when the person they are looking at has another chat on the same number.

Non-goals
- Merging the two chats into one thread (follow-on).
- Changing which id a send goes to; the console keeps writing to the chat it is in.
- A UI to merge contacts by hand, or to split a wrong merge. Logging suffices for now **(my call)**.
- Back-filling automation history, campaign history or CRM activity under the merged id.

Anti-goals
- Never message anyone: the lookup is read-only.
- Never merge on a guess: two contacts are merged only when WhatsApp itself says the ids are one person (a lid message
  carrying the phone, or the lookup answering both ids), or when the console-send fold already proves it.
- No data loss on merge: nothing is deleted except the empty duplicate contact row, and every reference to it is
  re-pointed first.

## 3. Facts the design rests on

- `Contact` (`omnichannel-db/.../db/contact/Contact.java`) has `waId` (one id, unique per brand) and `phone` (unique per
  brand). `ContactService.findForChat` resolves `@c.us` by phone, `@lid` by `waId`. `upsert` creates a contact when
  neither finds one; `linkLidToPhone` (console-send fold) moves a lid onto the phone's contact and deletes a nameless
  lid-only duplicate.
- A `@lid` message usually carries the sender's phone in `senderPn` (`MessagePayloads.lidPhone`); `upsert` already
  fills the contact's phone from it when no other contact owns the phone, but when another contact **does** own it, it
  silently creates a second contact without a phone. That is the duplicate.
- The page already resolves phone → lid for `getNumberId` (`WAWebApiContact.getCurrentLid`); the reverse,
  `WAWebApiContact.getPhoneNumber(lidWid)`, is in the same module, and whatsapp-web.js's injected
  `enforceLidAndPnRetrieval` shows the full recipe including the existence query fallback.
- `WaWebChat.contactId` is set once at creation. `ContactNote`, `CampaignRecipient`, `CrmContact`, `AutomationEvent`,
  `AutomationEventRecord`, `AutomationRun` also carry `contactId`.
- `CrmContact` is unique per `(workspaceId, contactId, channel.phoneNumberId)`.
- `AutomationEventWriter` computes `isNew` as "no other inbound real message in this chat".
- The demo seeder sets `waId` on contacts (`ContactDemoSeeder:112-116`).

## 4. Behaviour

### 4.1 The contact model

- `Contact.waIds: List<String>` replaces `waId`. Order is the order the ids were learnt; the first is the id the
  contact was created under. Unique multikey index `ws_waIds` on `(workspaceId, waIds)` (sparse) replaces `ws_waId`.
- `ContactRepository.findByWorkspaceIdAndWaIdsContaining(ws, id)` replaces `findByWorkspaceIdAndWaId`.
- Views: `ContactView.waIds` (list) replaces `waId`; `numberHidden` stays `phone == null`. The console type follows.
- **Migration** (`ContactIdsMigration`, a `SmartInitializingSingleton` next to `CollectionRenames`): for every contact
  with `waId` and no `waIds`, set `waIds = [waId]` and unset `waId`; drop the index `ws_waId` if present. Idempotent;
  logs the count. Runs before Spring Data's index creation would fail on the old unique index **(my call: a startup
  migration, no manual step, since nothing is deployed beyond the demo)**.
- The demo seeder sets `waIds` instead.

### 4.2 Finding the contact of a message

`ContactService.findForChat(ws, remote)`:
1. `@c.us`: by phone, else by `waIds` containing the id.
2. `@lid`: by `waIds` containing the id.

`ContactService.upsertFromChat(ws, customer, remote, senderName, lidPhone, at)` when nothing is found:
3. `@lid` with a valid `lidPhone`: if a contact owns that phone, **join**: add the lid to its `waIds`, fill the name if
   blank, bump `lastMessageAt`; return it. (Today this path creates a phoneless duplicate.)
4. Otherwise create the contact as today, with `waIds = [remote]`, and mark it for resolution (§4.3) when it lacks
   either a phone (`@lid` without `senderPn`) or a lid (`@c.us`).

`linkLidToPhone` (console-send fold) keeps its meaning but works on `waIds`: the phone's contact gains the lid; a lid
contact that has a name, a phone or any other id of its own is **merged** into the phone's contact (§4.4) instead of
being kept as a separate person **(my call: the two ids are proven one person by WhatsApp's reply, so keeping two
contacts is the bug this feature removes)**.

### 4.3 Resolving the other id through the extension

- New page method `resolveIds({ id })` → `{ phone: '<digits>@c.us' | null, lid: '<digits>@lid' | null }`: for a
  `@c.us` id it asks `getCurrentLid`; for a `@lid` id `getPhoneNumber`; when the counterpart is missing it runs the
  existence query once and asks again, as whatsapp-web.js's `enforceLidAndPnRetrieval` does. `WHATSAPP_NOT_READY`
  when the helpers are not loaded; `{ phone: null, lid: null }` when WhatsApp knows nothing. Read-only.
- `ContactIdResolver` (service): `resolve(ws, phoneNumberId, contactId)` asks the number's device (`RpcDispatcher`,
  like `NumberLookupService`) with one of the contact's ids, then for each returned id not yet on the contact:
  - no contact holds it → add it to `waIds` (and the phone, when it is a `@c.us` id and no contact owns that phone);
  - another contact holds it → merge (§4.4).
- When it runs **(my call)**:
  - after a message is stored whose contact lacks a phone or lacks a lid (`InboxEvents.MessageUpserted` listener on a
    small executor, never on the ingest thread); at most one attempt per contact per hour (in-memory
    `lastAttempt` map), and only when the number's device is ready; failures are logged and retried on the next
    message after the hour;
  - on demand: `POST /api/workspaces/{ws}/contacts/resolve-ids` (`contact:edit`) queues every contact of the brand that
    lacks a phone or a lid and has a chat on a ready device; answers `{ queued }`; the work runs on the resolver
    executor at most one call per 300 ms per device **(my call)** so the device is not flooded.
- A person whose WhatsApp privacy hides the phone stays lid-only: `resolveIds` returns no phone and nothing changes;
  the contact is retried an hour later at most.

### 4.4 Merging two contacts

`ContactMergeService.merge(ws, survivorId, otherId)` — the survivor is the contact with a phone, else the older
`creationTime` **(my call)**:
1. `waIds`: union, survivor's first. `phone`: survivor's, else the other's. `name`: survivor's unless blank or not
   `nameEdited` while the other's is `nameEdited`. `labels`: union. `attributes`: survivor's, blanks filled from the
   other. `lastMessageAt`: max. `doNotContact`: kept if either has it. `source`: survivor's.
2. Re-point `contactId` on `waWebChats`, `contactNotes`, `campaignRecipients` (bulk updates).
3. Publish `ContactsMerged(ws, survivorId, otherId)` (a service-level event, like `InboxEvents`). The application layer
   handles it for the CRM (`app.crm.ContactMergeAdapter`): `crmContacts.contactId` re-pointed; when both contacts have a
   CRM record on the same number (unique `(ws, contactId, channel.phoneNumberId)`), the other's record stays as it is
   and a warning is logged **(my call: a CRM-level merge is its own feature; the console already shows "Contact
   deleted" for such a record)**. Automation history rows keep the old `contactId` (history is not rewritten).
4. Delete the other contact. Publish `InboxEvents.ChatChanged` for every re-pointed chat so open consoles refresh.
Everything runs in one `retrying(...)` block; the merge is idempotent (a second run finds nothing to move).

### 4.5 "Chat is new" in Automations

`AutomationEventWriter`: `isNew` = no inbound real message, other than this one, in **any chat on this number whose
`contactId` is this message's contact**. `WaWebChatRepository.findAllByPhoneNumberIdAndContactId` +
`WaWebMessageRepository.existsByChatIdInAndDirectionAndTypeInAndIdNot`. A chat whose contact is still unresolved (lid
without `senderPn`, lookup not yet done) can still be counted new once more; recorded as a known limit. The test page's
"Treat this chat as new" is unchanged.

### 4.6 Inbox

- `ChatView.otherChats: [{id, remoteId}]`: the other chats on the same number with the same contact (usually zero or one).
- The chat header shows, when non-empty, a compact line "This person also has another chat on this number" with a link
  to it (several: "other chats", one link each by remote id) **(my call)**.
- The contacts page gains a kebab action "Resolve hidden numbers" (`contact:edit`) that calls `resolve-ids` and shows
  "<n> contacts queued"; the contact drawer/detail shows every WhatsApp id (phone id first, hidden ids after) where it
  shows one today.

## 5. Constraints

- Collections camelCase; the migration is the only schema change; `waIds` is multikey-unique and sparse.
- Module rule: `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the merge reaches the CRM
  through a Spring event handled in `app.crm`. `omnichannel-service` never imports `omnichannel-crm`.
- The resolver never runs on the ingest thread and never blocks a message save.
- The extension change is one page method; the page script is rebuilt into the backend's resources (picked up by
  hash, no extension reload).
- Console copy fixed here: "This person also has another chat on this number", "Resolve hidden numbers",
  "<n> contacts queued", "WhatsApp ids".
- Never send a message; the end-to-end check uses the user's own phones.

## 6. Testing

- Backend: migration IT (old `waId` → `waIds`, index swapped, idempotent); ingest ITs for §4.2 (lid with `senderPn`
  joins the phone's contact; lid without phone creates and marks; `@c.us` after lid joins by lookup); resolver ITs with
  the fake device answering `resolveIds` (adds the id; merges when another contact holds it; no phone returned leaves
  the contact; one attempt per hour; endpoint queues the right contacts); merge ITs (every re-pointed collection, the
  CRM re-point and the conflict case, chat-changed events, idempotence); automation IT (second chat of the same person
  on the number is not new; a different person is); view IT (`otherChats`, `waIds`).
- Extension: `resolveIds` unit tests with the fake window (both directions, fallback query, nothing known, not ready).
- Console: header line present/absent; contacts action calls the endpoint and shows the count; ids shown.
- End to end (needs the user): on the user's own two phones (or one phone with a hidden-number setting), write from a
  hidden-number phone to the demo number, confirm one contact, then the header line; run "Resolve hidden numbers" on
  the demo brand and confirm the duplicate pairs in it collapse (count before and after).

## 7. Left to the builder

- Executor sizing for the resolver (one thread is enough).
- Exact PatternFly components for the header line and the kebab action.
- Test fixture values.
