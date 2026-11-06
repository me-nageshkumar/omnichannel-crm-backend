# Chat history import (WhatsApp Web numbers) — design

**Date:** 2026-10-31
**Repos:** `whatsapp-web-extension`, `omnichannel-crm-backend`, `omnichannel-crm-frontend`
**Builds on:** `2026-10-21-chat-inbox-design.md` (data model, ingestion), `2026-09-15-chrome-extension-bridge-design.md`
(RPC), `2026-10-21-campaigns-design.md` (the job + items + tick pattern)

## 1. Intent

The bridge is forward-only: the extension forwards `message_create` / `message_ack` as they happen and buffers
them in its durable outbox while the backend is down. Nothing can pull messages that already exist on the phone:
conversations from before the number was connected, or a window missed while the extension itself was closed.

An operator asks, for one connected WhatsApp number, that the history of **all chats**, **a chosen set of
contacts**, or **one chat** be pulled from WhatsApp Web into the inbox, optionally limited to a **date range**.
Running the same request twice is harmless. The import survives backend restarts and extension disconnects and
does not disturb the live inbox.

### Goals
- Pull historical messages (both directions) for the chosen scope and range into `waWebMessages` / `waWebChats`.
- Idempotent: no duplicate messages, no duplicate chats, no side effects fired twice; a repeat run reports zero inserts.
- Resumable: a cursor per chat is saved after every page; restarts and disconnects lose at most one page of work.
- Visible: status, counts and progress in the console; cancellable.

### Non-goals
- Groups, broadcasts, channels, status (the inbox ignores them; so does the import).
- Media bytes (metadata only; bytes stay lazy through `downloadMedia`).
- Reconciling a person's `@c.us` and `@lid` chats into one row (inherited limitation).
- A console send stored under a synthetic `console:<id>` (its model never came back) will be stored again under
  its real id by the import, exactly as the live `message_create` already does. Not fixed here.
- Exactly-once transport; at-least-once plus the unique index is the guarantee, as for the outbox.

### Anti-goals
- An import that bumps unread counts, reopens chats, routes six-month-old chats to agents, triggers campaign
  stop-word handling, or floods the live streams.
- Two page fetches in flight to one device at once.
- Any send to a contact.

## 2. Decisions

| Topic | Decision |
|---|---|
| Who drives | Backend-owned job; the extension executes bounded page requests over the existing request/response RPC. Nothing new in the outbox or the event path. |
| Why pull, not push | RPC responses are bounded (30 s timeout, 24 MB frame) and resumable by cursor; the outbox cap (10 000) would make a push model lossy for "all chats". |
| Job state | `waWebHistoryImports` (one per request) + `waWebHistoryImportChats` (one per chat), like `campaigns` / `campaignRecipients`. |
| Idempotency | (1) unique `(phoneNumberId, waMessageId)` on `waWebMessages`; (2) a request identical in `(phoneNumberId, scope, range)` to an active import returns that import (partial unique index on `scopeKey` where `active`); (3) items unique on `(importId, remoteId)`, runner writes conditional on item status. |
| Ingest side effects | A separate ingest path: no `unreadCount++`, no reopen, no `ChatCreated` (no routing), no `MessageUpserted` (no campaign listener, no per-message SSE). Every page that stored something publishes one `ChatChanged` and one `HistoryStored` (the `history` stream event), so a chat fills up as pages land and a pause or a stop shows what arrived. |
| Chats created by the import | `CLOSED`, unassigned, `unreadCount 0`. A later inbound message reopens and routes through the existing path. Existing chats keep their status. **Changed 2026-11-02 (user's choice):** when the chat's fetch ends DONE, a chat the import created opens with the phone's unread count and is routed like a new chat (`RoutedReason.FETCHED`) if it waits on us: not an official business (`business` from the page: an enterprise account, i.e. on the business API, or a bot such as Meta AI; anyone on the WhatsApp Business app is not one, even verified high, by the user's choice; a page that does not say opens nothing), the contact wrote within `omnichannel.history.waiting-window` (7 days), and it is unread on the phone or the contact wrote last. `FetchedChatOpener`. |
| Contact names | An import never renames a contact that already has a name: it walks backwards, so the push names it reads are older than the contact's. It names only a contact without a name. |
| "All chats" | WhatsApp's own chat list on the phone, filtered to `@c.us` / `@lid`. Unknown contacts and chats are created. |
| "Selected contacts" | Phone digits or CRM contact ids → remote ids `<digits>@c.us` plus the contact's `waId` when known. No chat on the device → item `SKIPPED` / `NO_CHAT`. |
| Date range | Optional `from` / `to`. The page walks backwards with `loadEarlierMsgs` until the oldest loaded message is older than `from` or nothing more loads; `to` only filters, and is clamped to the request time (§5). Per-chat cap `maxMessagesPerChat` (5 000) → `DONE`, `capped`. |
| Status of imported rows | `status` from `ack`; `statusTimes` **empty**. Existing rows: status only moves up, `statusTimes` untouched. Since 2026-11-06 the delivered/read/played times fill in from WhatsApp's receipts (`2026-11-05-cloud-webhook-shape-design.md` §5.3). |
| Pacing | One page in flight per device; `pageDelay` 400 ms between pages, `chatDelay` 1 s between chats; at most one active import per number (others wait `QUEUED`). |
| Authority | New `inbox:import-history` (BRAND; default roles Customer Admin, Workspace Manager). |

## 3. Wire protocol (page methods)

### `listChats({})` → `ChatRow[]`
`ChatRow = { id: string /* <digits>@c.us | <id>@lid */, name: string | null, t: number | null /* seconds */, unreadCount: number }`
From `WAWebCollections.Chat.getModelsArray()`, keeping chats whose `id.server` is `c.us` or `lid` and that are not
groups, newsletters, broadcasts or status. The own-account chat (`wid`/`pnWid`), `0@c.us` and PSA chats are
excluded. Lightweight rows: **not** `WWebJS.getChatModel`. `WHATSAPP_NOT_READY`
when `window.WWebJS` is missing.

### `fetchChatHistory({ chatId, before?, excludeIds?, from?, to?, limit?, maxLoads? })` → `HistoryPage`
All times are WhatsApp seconds (`msg.t`). Defaults `limit 100`, `maxLoads 20`, `excludeIds []`.
- The chat is looked up with `Chat.get` (phone id, then its lid via `getCurrentLid` for `@c.us` ids), never created
  (`WWebJS.getChat` would create it); none → `CHAT_NOT_FOUND`. `PAGE_ERROR` when `chatId` is not a non-empty
  string, or when `limit`/`maxLoads` is not a positive integer.
- keep(m) = `!m.isNotification && m.type !== 'ciphertext' && (before == null || m.t <= before)
  && !excludeIds.includes(m.id._serialized) && (to == null || m.t <= to) && (from == null || m.t >= from)`.
- Loop while `kept.length < limit` and not `exhausted` and not `pastFrom` and `loads < maxLoads`:
  `loadEarlierMsgs({chat})`; empty/null → `exhausted = true` only when `chat.msgs.msgLoadState?.noEarlierMsgs !== false` (otherwise stop
  loading for this call, `exhausted` stays false); re-read `chat.msgs.getModelsArray()`;
  `pastFrom = from != null && min(t of loaded) < from`.
- `kept` sorted ascending by `t`; `messages = kept.slice(-limit)` (the newest `limit` of them) mapped through
  `normalizeMessageModel(WWebJS.getMessageModel(m))`.
- `oldest = messages.length ? { t: messages[0].t, ids: [ids of messages with t === messages[0].t] } : null`. When `before` is set and
  `messages[0].t === before`, `ids` is the union of the incoming `excludeIds` and the page's ids at that second, so a
  second holding more than `limit` messages is still walked to completion.
- `hasMore = kept.length > limit || (!exhausted && !pastFrom)`.
- `HistoryPage = { messages: object[] /* ascending by t */, oldest: {t, ids} | null, hasMore: boolean }`.
- `before` is inclusive: the caller passes `oldest.t` as the next `before` and `oldest.ids` as `excludeIds`, which
  handles several messages in one second without loss. An empty page with `hasMore` true is progress (the page
  loaded `maxLoads` batches that matched nothing yet); the caller repeats the same cursor, bounded by
  `maxEmptyPages`.

Assumption to verify on a real number first: messages added by `loadEarlierMsgs` have `isNewMsg === false`, so the
live `Msg.add` handler (`src/page/events.js`) does not emit `message_create` for them.

Pending verification (as of 2026-11-01; the real-number spike has not run, it is deferred to the end-to-end check):

- `loadEarlierMsgs` results have `isNewMsg === false` so the live `Msg.add` handler stays silent; if not, the fallback is to filter the live emitter by chat id + message id, never to mute all `Msg.add`.
- `chat.msgs.msgLoadState.noEarlierMsgs` exists and means what §3 assumes.
- The lid fallback in the non-creating chat lookup finds chats keyed by lid on current WhatsApp Web builds.
- Whether WhatsApp trims `chat.msgs` between calls (a deep cursor would then yield empty pages until the chat ends `DONE` with `olderUnavailable`).
- Memory growth in the tab during an ALL import.
- The own account's lid (`getMaybeMeLidUser`) is not yet excluded from `listChats`.

## 4. Data model

### `waWebHistoryImports`
```
id, customerId, workspaceId, phoneNumberId
scope { kind: ALL | CHATS, remoteIds: [..] (CHATS), phones: [..] (as requested) }
range { from: Instant?, to: Instant? }
scopeKey: sha-256 hex of "phoneNumberId|kind|sorted remoteIds joined by ,|from|to"
active: boolean            // true while QUEUED, RUNNING or PAUSED; drives the partial unique index
status: QUEUED | RUNNING | PAUSED | COMPLETED | CANCELLED | FAILED
waiting: null | NO_DEVICE | WHATSAPP_NOT_READY
counts { chatsTotal, chatsDone, chatsSkipped, chatsFailed, messagesSeen, messagesInserted, messagesUpdated }
requestedByUserId, requestedByName, error
creationTime, startedAt, finishedAt, lastUpdateTime, version
```
Indexes: unique partial `(phoneNumberId, scopeKey)` where `{active: true}`; `(phoneNumberId, status)`;
`(workspaceId, creationTime desc)`.

### `waWebHistoryImportChats`
```
id, importId, workspaceId, phoneNumberId, remoteId, requestedPhone?, chatName?
status: PENDING | RUNNING | DONE | SKIPPED | FAILED ; skipReason: NO_CHAT ; error
cursor { beforeT: Long?, excludeIds: [..] }   // saved after every page
counts { seen, inserted, updated } ; capped: boolean ; emptyPages: int ; timeouts: int
attemptedAt, finishedAt
```
Indexes: unique `(importId, remoteId)`; `(importId, status, _id)`, which serves the claim filter and the per-status
listing; `(status, attemptedAt)`, which serves `releaseStuck`. The claim sort `(attemptedAt, _id)` has no dedicated
index: it sorts the PENDING items of one import in memory (at most 500 for CHATS; pending, see `crm-pending.md`, small leftovers).

Deleting a phone number deletes both (`InboxCleanup`).

## 5. Runner

- Own scheduler `historyImportScheduler` (2 threads). Tick every `omnichannel.history.tick` (5 s), behind
  `omnichannel.history.auto-tick`. At startup: items `RUNNING → PENDING` (cursor kept, `attemptedAt` cleared).
- Tick: for each number with an active import (oldest QUEUED/RUNNING first by `creationTime`, then `_id`; one per
  number), skip if busy, else run `process(import)` on the pool. A PAUSED import is skipped and does not hold up its
  number.
- `listChats` leaves out chats last active more than a day before `from`: they cannot hold anything in range, and each
  would cost a `chatDelay`. The day is a margin, as `chat.t` is WhatsApp's own; a chat with no time is kept, and an
  all-time fetch skips nothing.
- `process` first puts the import's items left `RUNNING` back to `PENDING` (cursor and `attemptedAt` kept): only one
  worker serves a number (`busy`), so such an item was left behind by an earlier pass whose write failed (a DB blip)
  or by the last shutdown, and would otherwise stall the number until a restart. This also covers a first tick that
  beats the startup `releaseStuck`.
- Then `InboxDevices.requireReady` → on `NoDeviceException` set `waiting`, return. Else clear `waiting`,
  `QUEUED → RUNNING` (+`startedAt`). `ALL` with no items yet → `listChats` → one PENDING item per row
  (duplicates ignored), `counts.chatsTotal`. Then, before every chat, resolve the device again (`requireReady`; the
  extension may have reconnected with a new session, or gone: set `waiting` and stop), claim the PENDING item
  attempted least recently (`findAndModify` → RUNNING, sort `attemptedAt` asc then `_id`, so never-attempted items go
  first) and run `importChat` until none left, the import stops being RUNNING (pause or stop), the device fails, or the
  `timeBudget` (60 s) is spent. No PENDING/RUNNING items left → `COMPLETED` (also from PAUSED: a pause on the last page), `active=false`,
  `finishedAt`.
- `importChat`: loop `fetchChatHistory(remoteId, cursor, range, pageSize)`, with `to` clamped to
  `min(range.to, creationTime)` in epoch seconds (the creation time alone when `range.to` is null). The first page
  has no `before`, so without the clamp it holds the newest messages; one stored by the import before its live
  `message_create` is processed (typical while a reconnecting extension replays its outbox) would be an existing row
  to the live path, which would then skip `unreadCount++`, reopen and routing. Anything newer than the request is the
  live path's; ingest each message (ascending);
  write of cursor + counts unless the item has ended (not "while RUNNING": a stop hands the item back while its page
  is on the way, and that page is stored and counted), refresh the import's counts, announce the page; stop on
  `hasMore=false`, `seen >= maxMessagesPerChat` (`capped`), `emptyPages >= maxEmptyPages` (3; a page that loaded messages but kept none is progress and resets the count) or `maxIdlePages` (25) pages in a row storing nothing, loaded or not, as nothing WhatsApp reports may hold an import (`DONE`, `olderUnavailable`:
  WhatsApp Web stopped giving older messages without saying there are none, which is no failure of what was stored;
  on the real phone its "no earlier messages" flag stayed false at the true start of a chat, so this is how an
  all-time fetch normally ends),
  or a pause or stop. A page that leaves the cursor where it was stores nothing and counts towards `maxIdlePages`, and towards
  `maxEmptyPages` unless it loaded something, so a repeating page cannot loop or inflate `seen`. `CHAT_NOT_FOUND` → `SKIPPED NO_CHAT`;
  `DEVICE_DISCONNECTED` / `SEND_FAILED` (the session closed between pages) / `WHATSAPP_NOT_READY` → item back to
  PENDING (cursor and `attemptedAt` kept), `waiting` set, return; a timeout increments the item's `timeouts` and does
  the same, except that at `maxTimeoutsPerChat` (3) the item is `FAILED TIMEOUT` instead, so one chat that never
  answers cannot stall the import. `timeouts` counts consecutive timeouts: every page that arrives resets it to 0 (in
  the same conditional write as the cursor), so a chat that times out now and then but keeps making progress is not
  failed. A device that stops answering with its socket open ends each chat `FAILED TIMEOUT` after
  `maxTimeoutsPerChat` rounds and the import `COMPLETED`; a probe-before-charging refinement is pending; other `RpcException` → item `FAILED` with the code, next chat. `pageDelay` between pages, `chatDelay`
  between chats; pause and stop are checked after the wait, so no page is asked for after a click.

## 6. Ingest (`InboxIngestService.importMessage`)

Returns `INSERTED | UPDATED | UNCHANGED | SKIPPED` with the chat row. Differences from the live path: contact upsert
as today, except that the push name is passed only when the contact has no name yet (`ContactService.findForChat`
finds it the way the upsert does), so an import never renames a named contact; created chat is `CLOSED`, `unreadCount 0`; existing message → status moves up only, no `statusTimes`;
new message → `statusTimes` null, status from `ack`; fold `lastMessage*` / `lastInboundAt` / `lastOutboundAt` only
(`foldPreview` already ignores older messages); no `unreadCount++`, no reopen, no `REOPENED` action, no campaign
first-reply detection, no events. Wrapped in `retrying`; `DataAccessException` → `IngestionFailedException`.

A message's time is the raw model's `t`, in seconds. The page sends the raw model, not the whatsapp-web.js `Message`
class, so test fixtures use that shape: `HistoryDevice` and `ImportMessageIT` once carried the class's `timestamp`,
which the page never sends, and that hid every imported message taking the import's own time.

## 7. API (`/api/workspaces/{ws}/inbox`, authority `inbox:import-history`)

| | |
|---|---|
| `POST /phone-numbers/{phoneNumberId}/history-imports` | `{scope:{kind:'ALL'} \| {kind:'CHATS', phones?:[], contactIds?:[]}, from?, to?}` → view; 201 created, 200 identical active returned |
| `POST /chats/{chatId}/history-imports` | `{from?, to?}` → view (scope CHATS [chat.remoteId]) |
| `GET /history-imports?phoneNumberId=&status=&page=` | `{items, total, page}` |
| `GET /history-imports/{id}` | view with fresh counts |
| `GET /history-imports/{id}/chats?status=&page=` | `{items, total, page}` |
| `POST /history-imports/{id}/pause` | view, PAUSED; 409 `HISTORY_IMPORT_STATE` unless QUEUED/RUNNING |
| `POST /history-imports/{id}/resume` | view, back to RUNNING if it had started, else QUEUED; 409 unless PAUSED |
| `POST /history-imports/{id}/cancel` | the Stop button; view; 409 `HISTORY_IMPORT_STATE` unless QUEUED/RUNNING/PAUSED |
| `GET /chats/{chatId}/history-imports/active` | the newest active import that names the chat, or 204; an ALL import does not count |

Validation (400 `VALIDATION`): `from` after `to`; CHATS with neither phones nor contactIds; more than 500 resolved
chats; an invalid phone (`Phones.MESSAGE`); a number that is not WhatsApp Web (`phoneNumberId`: "only WhatsApp Web
numbers can import history") or is disabled (`phoneNumberId`: "the number is disabled"), on both create endpoints.
404 when the number or chat is not in the workspace.

View: `{id, phoneNumber{id,number,label}, scope{kind, phones, chatCount}, range{from,to}, status, waiting, counts,
requestedBy{id,name}, creationTime, startedAt, finishedAt, progressPercent}`; `progressPercent` =
`(chatsDone+chatsSkipped+chatsFailed)*100/chatsTotal`, null while `chatsTotal` is 0.

## 8. Console

- Phone numbers page: kebab "Import chat history…" (with `inbox:import-history`) → modal: All chats / Specific
  contacts (phone numbers, one per line), optional From / To dates, Start → navigates to `/inbox/history-imports`.
- Chat header: "Fetch history" button → the same modal in chat mode (dates only). While that import runs the header
  shows "Importing history… N messages so far" with Pause and Stop, or "History import paused: N messages so far" with
  Play and Stop; it finds an import already going when a chat opens. An open thread merges each page as it lands (the
  `history` stream event); on completion the thread reloads.
- `/inbox/history-imports` (nav "History imports" under Inbox): list with status, progress bar, counts, waiting
  reason ("Waiting for the extension to connect"), Pause / Play and Stop; polling every 5 s while anything is
  QUEUED/RUNNING/PAUSED, and once more after a stop because the page on its way still lands;
  expandable row with the chat items.
- Copy: "Import chat history", "All chats", "Specific contacts", "From", "To", "Start import", "Pause", "Play", "Stop",
  statuses "Queued", "Running", "Paused", "Completed", "Stopped", "Failed".

## 9. Configuration

`omnichannel.history`: `auto-tick true`, `tick 5s`, `page-size 100`, `max-loads 20`, `page-delay 400ms`,
`chat-delay 1s`, `max-messages-per-chat 5000`, `max-empty-pages 3`, `max-idle-pages 25`, `time-budget 60s`, `max-timeouts-per-chat 3`. Tests: `auto-tick false`,
delays `0`.

Changed 2026-11-03 (the user asked for the small leftovers to be done):
- `chat-delay` follows only a chat whose pages asked WhatsApp for earlier messages (the page's new `asked`, true
  whenever `loadEarlierMsgs` was called, whatever came back; a page script without it counts as asking), or that timed
  out. A chat read from what the browser already held is followed by none. Whether `loadEarlierMsgs` reaches the phone
  is not known, so asking always counts: a dated fetch gains most (a chat already holding a message older than the
  range asks nothing), an all-time fetch little, since most chats hold under a page and ask once.
- A page that times out is followed by `getState`. No answer, or WhatsApp Web not `CONNECTED`, means the page is
  silent, not the chat: the chat goes back to PENDING uncharged and the import waits (`WHATSAPP_NOT_READY`, or
  `NO_DEVICE` when the extension went). Only a timeout while the page answers and WhatsApp is connected counts towards
  `max-timeouts-per-chat`. A page frozen with its socket open is now waited for indefinitely, each pass costing up to
  two RPC timeouts.
- An ALL import records `listedAt` when its listing finished; until then each pass lists again, keeping the chats
  already stored. An import running when this shipped lists once more, harmlessly.
- A chat an import creates carries the item's id (`waWebChats.createdByHistoryChatId`, sparse index), so the item's
  `createdChatId` is recovered when a crash lost it.
- A chat that ends DONE after its import was stopped (CANCELLED) or failed is not opened or routed.
- Round robin moves the team's cursor after the chat is saved, so a routing retry after a version collision no longer
  skips a member.

## 10. Pause, play and stop (added 2026-11-01; my calls, the request skipped the questions)

- A pause never drops a page. A page already asked for is stored, counted and announced; the runner then hands the chat
  back with its cursor. So pages, not single messages, are the unit: with `pageSize` 100 a pause lands up to one page
  after the click. The rule that holds is that what the console counts is what the inbox has.
- Stop is the existing cancel under a new name. It asks for no confirmation, unlike the Cancel it replaces: what was
  fetched stays and the same fetch can be started again, re-reading at no cost.
- A paused import stays `active`, so an identical request returns it instead of starting another. It does not hold its
  number, so another import there may run meanwhile.
- The chat header shows only imports that name the chat. Pausing an ALL import from one chat's header would stop every
  other chat, so that is done on the imports page.
- An open thread takes the newest page each time a page is stored, and drops what was shown behind it, so messages
  imported between two stored stretches cannot end up out of reach of "Load earlier". A reader who has loaded earlier
  pages keeps them, so such a gap can stay until the thread reloads.
