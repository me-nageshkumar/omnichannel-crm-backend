# Chat inbox (WhatsApp Web numbers) — design

**Date:** 2026-10-21
**Repos:** `omnichannel-crm-backend`, `omnichannel-crm-frontend`, `whatsapp-web-extension`
**Reference studied:** `/Users/nagesh/RedLava/WhatsApp/SCM/whatsapp` (a Cloud API CRM; its inbox row, assignment and visibility model are borrowed, its transport is not)

## 1. Intent

Agents answer WhatsApp conversations from the console instead of from the phone. A user picks
one of the brand's WhatsApp Web numbers, sees the chats on that number as WhatsApp Web shows them
(contacts on the left, the conversation on the right, a composer at the bottom), and replies with
typed text or a message template. Chats are routed to teams and agents; an agent only ever sees
the chats assigned to them.

Today nothing of this exists: WhatsApp Web messages reach the server as transient events that
only auto-create contacts. This feature persists them.

### Goals
- Every message seen on an onboarded WhatsApp Web number is stored, inbound and outbound, with
  its delivery state (pending, sent, delivered, read, played, failed) and who sent it.
- A console inbox per phone number, live-updating, with assignment, open/closed state, unread
  counts and search.
- Sending text and templates from the console, through the extension, tracked as console sends.
- Messages typed on the phone or in the WhatsApp Web tab are tracked too, marked as sent from
  WhatsApp Web with no agent.
- Role-split visibility: agents see their own chats; team leads see their teams' chats and the
  unassigned queue; managers see everything.

### Non-goals (this slice)
- Campaigns, bulk sends, scheduled sends (later; they must reuse the send and render paths here).
- WhatsApp Cloud API numbers. Their messages will live in different collections; the inbox lists
  them greyed out as "Cloud API inbox coming later".
- Group chats, broadcast lists, status, channels. Ignored, as contacts already ignore them.
- Snooze, pin, notes in the thread, canned replies, AI replies, a right-hand contact panel.
- Reactions, replies-with-quote, message editing and deletion, read receipts to the contact on
  open (only a console reply marks the chat seen on WhatsApp).
- Sending contact-card templates from the inbox (whatsapp-web.js only sends cards for contacts
  known to the phone; deferred).
- Multi-instance fan-out (one backend instance; in-process event bus).

### Anti-goals
- An agent seeing, or being pushed, a message of a chat they are not allowed to see.
- Losing a message because the console was closed: persistence happens on the server, from the
  extension's durable outbox, never from the browser.
- A reply that reaches the contact but is not recorded.

## 2. Naming and copy
- Nav label and page title: **Inbox**. "Chat" for a conversation, "message" for one message.
- "Enabled/Disabled" wording stays; the inbox never says "block".
- Delivery states in the console: Pending, Sent, Delivered, Read, Played, Failed. Ticks like
  WhatsApp: one grey (sent), two grey (delivered), two blue (read/played), clock (pending), red
  (failed).
- Message source labels: "Console" (sent from our app, by a named user), "WhatsApp Web" (typed on
  the phone or in the WhatsApp Web tab), "Contact" (inbound).
- Routing modes in settings: "Leave unassigned", "Team queue", "One agent", "Round robin in team".

## 3. Data model (WhatsApp Web only)

All documents carry `customerId` and `workspaceId`. These collections are specific to WhatsApp
Web numbers; Cloud API numbers get their own later, so names carry the `wa_web_` prefix.

### `waWebChats` — one row per (phone number, remote chat)
| field | notes |
|---|---|
| `id` | |
| `customerId`, `workspaceId`, `phoneNumberId` | |
| `remoteId` | WhatsApp chat id, `<digits>@c.us` or `<id>@lid` |
| `contactId` | the `contacts` row the chat belongs to (from `ContactService.upsertFromChat`) |
| `status` | `OPEN` or `CLOSED` |
| `assignedUserId`, `assignedTeamId` | either, both or neither; `assignedAt` set on change |
| `unreadCount` | inbound messages since the chat was last opened in the console |
| `lastMessageAt` | timestamp of the newest message |
| `lastMessage` | embedded preview: `direction`, `type`, `text` (first 120 chars of body or a type label), `status` |
| `lastInboundAt`, `lastOutboundAt` | |
| `creationTime`, `lastUpdateTime`, `version` (@Version) | |

Indexes: unique `(phoneNumberId, remoteId)`; `(phoneNumberId, lastMessageAt desc)`;
`(phoneNumberId, assignedUserId, lastMessageAt desc)`; `(phoneNumberId, assignedTeamId, lastMessageAt desc)`;
`(workspaceId, contactId)`.

### `waWebMessages` — every message, both directions
| field | notes |
|---|---|
| `id` | |
| `customerId`, `workspaceId`, `phoneNumberId`, `chatId` | |
| `waMessageId` | `id._serialized` from whatsapp-web.js, e.g. `true_9199…@c.us_3EB0…` |
| `direction` | `IN` or `OUT` |
| `source` | `CONTACT` (IN), `CONSOLE` (sent by our app), `WHATSAPP_WEB` (typed on the phone / WhatsApp Web tab). Campaigns add a value later. |
| `type` | the whatsapp-web.js type string: `chat`, `image`, `video`, `audio`, `ptt`, `document`, `sticker`, `location`, `vcard`, `poll_creation`, `revoked`, anything else kept verbatim |
| `body` | text, or the caption of a media message, or the vCard text |
| `media` | `{mimetype, filename, size, mediaId, downloadedAt}`; `mediaId` is the `chatMedia` GridFS id once fetched, else null |
| `location` | `{latitude, longitude, description}` for `location` |
| `timestamp` | WhatsApp's message time (seconds → Instant) |
| `status` | `PENDING`, `SENT`, `DELIVERED`, `READ`, `PLAYED`, `FAILED` — the highest reached |
| `statusTimes` | `{sentAt, deliveredAt, readAt, playedAt, failedAt}`; each set once, when first reached |
| `ack` | the raw whatsapp-web.js ack integer last seen (-1 error, 0 pending, 1 server, 2 device, 3 read, 4 played) |
| `sentByUserId`, `sentByName` | for `CONSOLE` |
| `templateId` | when sent from a template (the send also sets `MessageTemplate.lastUsedAt`) |
| `creationTime` | |

Indexes: unique `(phoneNumberId, waMessageId)`; `(chatId, timestamp desc)`.

Ack → status: -1 FAILED, 0 PENDING, 1 SENT, 2 DELIVERED, 3 READ, 4 PLAYED. Status never goes
down (a late `ack=1` after `READ` is ignored); FAILED can be reached from any state.

### `waWebChatActions` — the audit trail, shown in the thread as system lines
`id, workspaceId, chatId, type, byUserId, byName, toUserId, toUserName, toTeamId, toTeamName, at`.
`type`: `ROUTED` (automatic, `byUserId` null, shown as "System"), `ASSIGNED`, `UNASSIGNED`,
`CLOSED`, `REOPENED`. Index `(chatId, at)`.

### `inboxSettings` — routing per phone number
`id, workspaceId, phoneNumberId (unique), routing {mode, teamId, userId}, claimOnReply (default true),
keepAssignmentOnClose (default false), roundRobinCursor (user id last assigned)`.
`mode`: `NONE` (default when no document), `TEAM`, `USER`, `ROUND_ROBIN`.

### `chatMedia` — GridFS bucket
Media bytes of messages, inbound (fetched on demand) and outbound (copied from `templateMedia`
at send time so the thread shows what was sent). Metadata: `workspaceId`, `phoneNumberId`,
`messageId`. 16 MB cap per file (whatsapp-web.js's own limit). Deleting a phone number deletes
its chats, messages, actions, settings and media.

## 4. Ingestion (server, from extension events)

A listener on `WhatsAppEvent` replaces `ChatContactListener` (its contact upsert moves into
the new listener so the contact id is at hand; its tests move with it). Never throws.

- `message_create` (fires for every new message, both directions) is the ingestion event;
  `message` (inbound only) is ignored because it duplicates it. Ciphertext placeholders never
  arrive (the extension skips them).
- Skip unless `id.remote` ends in `@c.us` or `@lid`.
- Steps: upsert the contact (existing rule) → find or create the chat by `(phoneNumberId,
  remoteId)` → upsert the message by `(phoneNumberId, waMessageId)`: on insert, `direction` =
  `fromMe ? OUT : IN`, `source` = `fromMe ? WHATSAPP_WEB : CONTACT`, status from the payload's
  `ack`; on an existing row (the console send already stored it) only the status moves →
  update the chat: `lastMessage*`, `unreadCount += 1` for IN, and if the chat was CLOSED set it
  OPEN and record `REOPENED` (by System) → route a brand-new chat, or a reopened one with no
  assignment (§5) → push to streams (§7).
- `message_ack` `{message, ack}`: look the message up by `message.id._serialized`; apply the
  ack → status rule; refresh `chat.lastMessage.status` if it is the last message; push.
- A `DuplicateKey` or optimistic-lock failure retries once, like contacts.

Media on inbound messages: the message stores `mimetype/filename/size` from the payload
(`mimetype`, `filename`, `size`, falling back to `_data.*`) and `hasMedia`; bytes are fetched
lazily (§6).

## 5. Routing, assignment, open/closed

Borrowed from the reference's `inboxSetting` + `ChatAssignmentService`, simplified.

- **Routing** runs when a chat is created by an inbound message, and when an inbound message
  reopens a closed chat that has no assignment. Per the number's `inboxSettings`:
  - `NONE`: stays unassigned.
  - `TEAM`: `assignedTeamId = teamId`, no user (the team queue).
  - `USER`: `assignedUserId = userId`.
  - `ROUND_ROBIN`: next member of `teamId` after `roundRobinCursor`, in `memberIds` order,
    wrapping, skipping users who are disabled or have lost access to the workspace; sets both
    user and team and advances the cursor. No eligible member → team queue.
  - A chat created by an outbound message (someone wrote first from the phone or console) is
    not routed; the console sender claims it (below), a phone sender leaves it unassigned.
  - Records `ROUTED` (by System) when anything was set.
- **Claim on reply**: a successful console send on a chat with no `assignedUserId` assigns it
  to the sender when `claimOnReply` is on (records `ASSIGNED` by the sender to themselves).
  A chat already assigned to someone else is **not** reassigned by a reply (my call; the
  reference reassigns by default. A manager helping out should not silently take the chat).
- **Assign** (`inbox:assign`): to a user, to a team, or both. Assigning to a team whose members
  do not include the current user clears the user (it goes to the queue). Any user with access
  to the workspace may be a target. `UNASSIGNED` clears both.
- **Close / reopen** (`inbox:send` on a visible chat): close sets `CLOSED` and clears the
  assignment unless `keepAssignmentOnClose`. Reopen is manual (keeps whatever assignment) or
  automatic on an inbound message (then routed if unassigned).
- **Unread**: inbound increments; `POST …/read` zeroes it. Opening a chat in the console does
  not send read receipts to the contact; a console reply does (`sendSeen: true`), so the
  contact sees blue ticks only once answered.

## 6. Sending

`POST /api/workspaces/{ws}/inbox/chats/{chatId}/messages` with either
`{"text": "…"}` (1–4096 chars, trimmed, not blank) or
`{"templateId": "…", "values": {agentName: value}}`.

- Requires `inbox:send`, the chat visible to the caller, `status == OPEN` (409
  `CHAT_CLOSED` otherwise), the number usable (enabled at all three levels) and its device
  confirmed and `CONNECTED` (existing `NoDeviceException` → 409 `NO_DEVICE` / `WHATSAPP_NOT_READY`).
- Template path: calls `TemplateRenderer.render(me, ws, templateId, new RenderRequest(chat.contactId, values))`.
  `missing` non-empty → 400 VALIDATION with `fields.<name> = "required"`. Then by type:
  - TEXT → content = body, `options.linkPreview` from the template.
  - IMAGE / VIDEO / DOCUMENT / AUDIO → content = caption or "", `options.media = {mimetype, data (base64), filename}`
    read from `templateMedia`; AUDIO with `voiceNote` → `options.sendAudioAsVoice = true`;
    DOCUMENT → `options.sendMediaAsDocument = true`. The bytes are also copied into
    `chatMedia` and linked from the stored message.
  - LOCATION → content "", `options.location = {latitude, longitude, description: name + ", " + address}`.
  - POLL → content "", `options.poll = {pollName: question, pollOptions: options, options: {allowMultipleAnswers}}`.
  - CONTACT_CARD → 400 VALIDATION `templateId: "contact cards cannot be sent from the inbox yet"`.
  These `options` keys are what `window.WWebJS.sendMessage` in whatsapp-web.js
  (`src/util/Injected/Utils.js`) reads; the extension's `sendMessage` page method passes
  `options` through untouched.
- RPC `sendMessage {chatId: remoteId, content, options, sendSeen: true}` through the existing
  `RpcDispatcher`. The result is the sent message model (or null when WhatsApp's key lookup
  missed). The message row is upserted by `waMessageId` with `source = CONSOLE`, `sentBy*`,
  `templateId`, status from its `ack` (PENDING when null result: then a synthetic
  `waMessageId = "console:" + messageId` placeholder is stored as FAILED after the RPC timeout;
  on success without a model the row is stored PENDING with that synthetic id and is **not**
  merged with the later `message_create` event — accepted gap, logged).
  The ingestion listener's `message_create` for the same `waMessageId` then only moves status.
- On success: chat `lastMessage*`, `lastOutboundAt`, claim-on-reply (§5), template `lastUsedAt`,
  push. Returns the `MessageView`.
- RPC failure (timeout, page error, device gone mid-call) → the existing `RpcException`
  mapping; nothing stored.

## 7. Inbound media and live updates

**Media**: `GET …/chats/{chatId}/messages/{messageId}/media` streams the bytes. First call
with no `mediaId`: RPC `downloadMedia {messageId: waMessageId}` to the extension (new page
method, §9), store in `chatMedia`, set `media.mediaId/downloadedAt`, then stream. Device not
connected → 409 `NO_DEVICE`; WhatsApp cannot resolve the media (expired, evicted) → 410
`MEDIA_UNAVAILABLE` with message "Media is no longer available on the phone". Headers as the
template media download (content type, inline disposition for image/video/audio, attachment
for the rest, `X-Content-Type-Options: nosniff`).

**Live updates**: Server-Sent Events, `GET …/inbox/phone-numbers/{pn}/stream` (`inbox:view`),
authenticated with the usual Bearer token (the console reads the stream with `fetch`, not
`EventSource`, so the header is available). `SseEmitter`, no timeout; a `: ping` comment every
25 s. Event types, each a JSON data line:
- `chat` — `{chat: ChatView, visible: boolean}`; `visible` false tells the client to drop the
  row (it was reassigned away from the viewer). Sent on any chat change.
- `message` — `{chatId, message: MessageView}` on insert or status change, only to viewers
  who can see the chat.
- `action` — `{chatId, action: ActionView}`.
Visibility is evaluated per subscriber at push time (§8); a subscriber's scope is computed when
the stream opens and refreshed every push from the current user and team membership (a cheap
lookup; correctness over caching). The console reloads the chat list on reconnect.

## 8. Authorities and visibility

New BRAND authorities (added to `Authority.java`; the seeder grants them):
| code | default roles | meaning |
|---|---|---|
| `inbox:view` | Customer Admin, Workspace Manager, Team Lead, Agent | Open the inbox, see own chats and their messages |
| `inbox:view-team` | Customer Admin, Workspace Manager, Team Lead | Also see chats routed to own teams and the unassigned queue |
| `inbox:view-all` | Customer Admin, Workspace Manager | See every chat on the number |
| `inbox:send` | all four | Reply from the console, close and reopen visible chats |
| `inbox:assign` | Customer Admin, Workspace Manager, Team Lead | Assign, reassign, unassign visible chats |
| `inbox:settings` | Customer Admin, Workspace Manager | Edit a number's routing settings |

Visibility of a chat for a user in a workspace (one `Criteria`, used by list, get, thread,
media, stream push):
- holds `inbox:view-all` → every chat of the number;
- else holds `inbox:view-team` → `assignedUserId == me` OR `assignedTeamId ∈ teams I am a member of`
  OR (`assignedUserId == null` AND `assignedTeamId == null`);
- else (`inbox:view`) → `assignedUserId == me`.
A chat outside the scope is 404, like everything else in this codebase.

The number picker (`GET …/inbox/phone-numbers`) needs only `inbox:view`, because agents do not
hold `phone-number:view`; it returns id, number, label, connection type and status.

List scopes the console offers: **Mine**, **Team** (needs `inbox:view-team`; team-routed + unassigned),
**Unassigned** (needs `inbox:view-team`), **All** (needs `inbox:view-all`). Default scope is the
widest the user holds. The server rejects a scope the user may not use with 403.

## 9. Extension change

One new page method, `downloadMedia({messageId})`, mirroring `Message.downloadMedia` in
whatsapp-web.js: `window.WWebJS.resolveMediaBlob(messageId)` then
`arrayBufferToBase64Async`; returns `{data, mimetype, filename, filesize}` or throws
`MEDIA_UNAVAILABLE` (new `ErrorCodes` entry) when resolve returns null. `sendMessage` already
accepts an empty string `content` with `options.media`, so sends need no change. The extension
bundle is served by the backend (`GET /api/scripts/page`), so the rebuilt bundle lands in
`src/main/resources/wabridge/scripts` as today.

## 10. API summary (base `/api/workspaces/{ws}/inbox`)

| method & path | authority | notes |
|---|---|---|
| GET `/phone-numbers` | inbox:view | numbers of the brand with status |
| GET `/phone-numbers/{pn}/chats?scope=&status=&q=&unread=&before=&limit=` | inbox:view | `scope` mine/team/unassigned/all; `status` OPEN (default) / CLOSED / ALL; `q` matches contact name or phone (prefix, case-insensitive); `before` = lastMessageAt cursor; limit ≤ 100, default 50 → `{items, hasMore}` |
| POST `/phone-numbers/{pn}/chats` `{phone, text \| templateId+values}` | inbox:send | start or land in the chat of a phone number and send its first message → MessageView; see the new-chat design |
| GET `/phone-numbers/{pn}/stream` | inbox:view | SSE |
| GET/PUT `/phone-numbers/{pn}/settings` | inbox:settings | routing |
| GET `/assignees` | inbox:assign | `{users: [{id, name, username}], teams: [{id, name}]}` — users with access to the workspace, enabled |
| GET `/chats/{id}` | inbox:view | ChatView |
| GET `/chats/{id}/thread?before=&limit=` | inbox:view | messages and actions merged, oldest first within the page, `before` = timestamp cursor → `{items: [{kind: 'message'|'action', …}], hasMore}` |
| POST `/chats/{id}/read` | inbox:view | unread → 0 |
| POST `/chats/{id}/messages` | inbox:send | §6 |
| GET `/chats/{id}/messages/{mid}/media` | inbox:view | §7 |
| POST `/chats/{id}/assign` `{userId?, teamId?}` | inbox:assign | at least one |
| POST `/chats/{id}/unassign` | inbox:assign | |
| POST `/chats/{id}/close`, `/reopen` | inbox:send | |

`ChatView`: `{id, phoneNumberId, remoteId, contact: {id, name, phone, numberHidden}, status,
assignedUser: {id, name} | null, assignedTeam: {id, name} | null, unreadCount, lastMessageAt,
lastMessage: {direction, type, text, status} | null, creationTime}`.
`MessageView`: `{id, chatId, waMessageId, direction, source, type, body, media: {mimetype, filename,
size, available} | null, location | null, timestamp, status, statusTimes, sentBy: {id, name} | null,
templateId}`. `available` is true once bytes are cached, so the console can show a "Download"
affordance versus an immediate thumbnail.
`ActionView`: `{id, type, by: {id, name} | null, toUser: {id, name} | null, toTeam: {id, name} | null, at}`.

## 11. Console

- Nav: **Inbox** in the Brand group, `requires inbox:view`. Route `/inbox` → number picker;
  `/inbox/:phoneNumberId` → the inbox; `/inbox/:phoneNumberId/:chatId` → a chat open.
- **Number picker**: the brand's numbers as a list with status label; WhatsApp Web numbers open the
  inbox; Cloud API rows are disabled with "Cloud API inbox coming later". Holders of
  `inbox:settings` get a "Routing" action per number opening the settings modal (mode, team,
  user, claim on reply, keep assignment on close).
- **Inbox layout** (fills the content area height; no page scroll):
  - Left pane (fixed ~360 px): number name/status at top with a "Change number" link; search box;
    scope toggle (Mine / Team / Unassigned / All, only the ones the user may use); Open / Closed
    toggle; unread-only switch; the chat list (avatar initial, contact name or `+phone` or
    "Number hidden", preview line with a tick for outbound, relative time, unread badge). Infinite
    "Load more" at the bottom.
  - Right pane: empty state "Pick a chat" when none. Otherwise a header (name, phone, link to
    `/contacts/:id`, assignment pill with a menu for `inbox:assign` holders: assign to user, to
    team, unassign; Close / Reopen button), the thread (day separators, incoming bubbles left,
    outgoing right with ticks and the sender's name for console sends and "WhatsApp Web" for phone
    sends; media as thumbnail / player / file chip, fetched from the media endpoint on demand;
    location as a map link; poll as a list; system lines for actions; "Load earlier" at the top;
    auto-scroll to bottom on new messages when already at the bottom), and the composer.
  - Composer: textarea (Enter sends, Shift+Enter newline), a "Template" button opening the picker,
    Send. Disabled with an inline notice when the chat is CLOSED ("Reopen to reply") or the number
    is not CONNECTED ("WhatsApp is not connected on this number").
  - Template picker modal: searchable template list (ACTIVE only, `template:view`), the agent
    placeholder form (label, example, required) exactly as the templates page's "Preview with a
    contact", a live preview bubble, Send. Contact-card templates are listed disabled.
- Live updates via a `useInboxStream(phoneNumberId)` hook: `fetch` with `Authorization`, parses
  SSE frames, reconnects with 1 s → 30 s backoff, and triggers a list reload on reconnect.
  Applies `chat` events to the list (insert/move to top/update/remove when `visible` false),
  `message` and `action` events to the open thread.
- Opening a chat calls `/read` and zeroes the badge locally.

## 12. Demo seed

`InboxDemoSeeder` (@Order(9), `omnichannel.seed.demo=true`, skipped when the demo number already
has chats): creates team "Demo Support" (demo-lead, demo-agent) if missing, then on the demo
number six chats with the demo contacts: two assigned to demo-agent, one in the Demo Support
queue, one assigned to demo-lead, one unassigned, one closed; each with 4–8 messages mixing
contact text, console replies by demo-agent (with sent/delivered/read states), one WhatsApp Web
reply with no agent, one inbound image whose bytes are the bundled demo PNG (so the thumbnail
renders without a phone), and the matching actions. `waWebChats.remoteId` uses the contact's
phone `@c.us`.

## 13. Calls made without asking

- Team Lead visibility: own chats + chats routed to teams they are a member of + the unassigned
  queue. (Teams have no "lead" field; membership is the only link. Cost if wrong: one Criteria.)
- Default routing is "leave unassigned", not round robin over a default team as in the reference
  (we have no default team).
- A reply never reassigns a chat already assigned to someone else.
- Opening a chat sends no read receipt; replying does.
- Live updates over SSE rather than a second WebSocket channel.
- Inbound media fetched on demand and cached, not downloaded eagerly.
- Snooze, pin, right-hand contact panel, contact-card sends: deferred.
- Message `type` kept as whatsapp-web.js's string rather than an enum, so unknown types still
  store and render as "Unsupported message".
- Deleting a phone number removes its inbox data.
