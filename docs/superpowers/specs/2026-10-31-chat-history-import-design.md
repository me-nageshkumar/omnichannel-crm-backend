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
| Ingest side effects | A separate ingest path: no `unreadCount++`, no reopen, no `ChatCreated` (no routing), no `MessageUpserted` (no campaign listener, no per-message SSE). One `ChatChanged` per chat when its import finishes. |
| Chats created by the import | `CLOSED`, unassigned, `unreadCount 0`. A later inbound message reopens and routes through the existing path. Existing chats keep their status. |
| "All chats" | WhatsApp's own chat list on the phone, filtered to `@c.us` / `@lid`. Unknown contacts and chats are created. |
| "Selected contacts" | Phone digits or CRM contact ids → remote ids `<digits>@c.us` plus the contact's `waId` when known. No chat on the device → item `SKIPPED` / `NO_CHAT`. |
| Date range | Optional `from` / `to`. The page walks backwards with `loadEarlierMsgs` until the oldest loaded message is older than `from` or nothing more loads; `to` only filters. Per-chat cap `maxMessagesPerChat` (5 000) → `DONE`, `capped`. |
| Status of imported rows | `status` from `ack`; `statusTimes` **empty**. Existing rows: status only moves up, `statusTimes` untouched. |
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

## 4. Data model

### `waWebHistoryImports`
```
id, customerId, workspaceId, phoneNumberId
scope { kind: ALL | CHATS, remoteIds: [..] (CHATS), phones: [..] (as requested) }
range { from: Instant?, to: Instant? }
scopeKey: sha-256 hex of "phoneNumberId|kind|sorted remoteIds joined by ,|from|to"
active: boolean            // true while QUEUED or RUNNING; drives the partial unique index
status: QUEUED | RUNNING | COMPLETED | CANCELLED | FAILED
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
counts { seen, inserted, updated } ; capped: boolean ; emptyPages: int
attemptedAt, finishedAt
```
Indexes: unique `(importId, remoteId)`; `(importId, status, _id)`; `(status, attemptedAt)`.

Deleting a phone number deletes both (`InboxCleanup`).

## 5. Runner

- Own scheduler `historyImportScheduler` (2 threads). Tick every `omnichannel.history.tick` (5 s), behind
  `omnichannel.history.auto-tick`. At startup: items `RUNNING → PENDING` (cursor kept).
- Tick: for each number with an active import (oldest QUEUED/RUNNING first, one per number), skip if busy, else run
  `process(import)` on the pool.
- `process`: `InboxDevices.requireReady` → on `NoDeviceException` set `waiting`, return. Else clear `waiting`,
  `QUEUED → RUNNING` (+`startedAt`). `ALL` with no items yet → `listChats` → one PENDING item per row
  (duplicates ignored), `counts.chatsTotal`. Then claim PENDING items (`findAndModify` → RUNNING) and run
  `importChat` until none left, the import stops being RUNNING (cancel), the device fails, or the `timeBudget`
  (60 s) is spent. No PENDING/RUNNING items left → `COMPLETED`, `active=false`, `finishedAt`.
- `importChat`: loop `fetchChatHistory(remoteId, cursor, range, pageSize)`; ingest each message (ascending);
  conditional write (item still RUNNING) of cursor + counts; stop on `hasMore=false`, `seen >= maxMessagesPerChat`
  (`capped`), `emptyPages >= maxEmptyPages` (`FAILED NO_PROGRESS`), or cancel. `CHAT_NOT_FOUND` → `SKIPPED NO_CHAT`;
  `DEVICE_DISCONNECTED` / `WHATSAPP_NOT_READY` / timeout → item back to PENDING (cursor kept), `waiting` set,
  return; other `RpcException` → item `FAILED` with the code, next chat. On DONE/SKIPPED/FAILED publish one
  `ChatChanged` if a chat row exists. `pageDelay` between pages, `chatDelay` between chats.

## 6. Ingest (`InboxIngestService.importMessage`)

Returns `INSERTED | UPDATED | UNCHANGED | SKIPPED` with the chat row. Differences from the live path: contact upsert
as today; created chat is `CLOSED`, `unreadCount 0`; existing message → status moves up only, no `statusTimes`;
new message → `statusTimes` null, status from `ack`; fold `lastMessage*` / `lastInboundAt` / `lastOutboundAt` only
(`foldPreview` already ignores older messages); no `unreadCount++`, no reopen, no `REOPENED` action, no campaign
first-reply detection, no events. Wrapped in `retrying`; `DataAccessException` → `IngestionFailedException`.

## 7. API (`/api/workspaces/{ws}/inbox`, authority `inbox:import-history`)

| | |
|---|---|
| `POST /phone-numbers/{phoneNumberId}/history-imports` | `{scope:{kind:'ALL'} \| {kind:'CHATS', phones?:[], contactIds?:[]}, from?, to?}` → view; 201 created, 200 identical active returned |
| `POST /chats/{chatId}/history-imports` | `{from?, to?}` → view (scope CHATS [chat.remoteId]) |
| `GET /history-imports?phoneNumberId=&status=&page=` | `{items, total, page}` |
| `GET /history-imports/{id}` | view with fresh counts |
| `GET /history-imports/{id}/chats?status=&page=` | `{items, total, page}` |
| `POST /history-imports/{id}/cancel` | view; 409 `HISTORY_IMPORT_STATE` unless QUEUED/RUNNING |

Validation (400 `VALIDATION`): `from` after `to`; CHATS with neither phones nor contactIds; more than 500 resolved
chats; an invalid phone (`Phones.MESSAGE`). 404 when the number or chat is not in the workspace.

View: `{id, phoneNumber{id,number,label}, scope{kind, phones, chatCount}, range{from,to}, status, waiting, counts,
requestedBy{id,name}, creationTime, startedAt, finishedAt, progressPercent}`; `progressPercent` =
`(chatsDone+chatsSkipped+chatsFailed)*100/chatsTotal`, null while `chatsTotal` is 0.

## 8. Console

- Phone numbers page: kebab "Import chat history…" (with `inbox:import-history`) → modal: All chats / Specific
  contacts (phone numbers, one per line), optional From / To dates, Start → navigates to `/inbox/history-imports`.
- Chat header: "Fetch history" button → the same modal in chat mode (dates only). While that import runs the header
  shows "Importing history… N messages so far"; on completion the thread reloads.
- `/inbox/history-imports` (nav "History imports" under Inbox): list with status, progress bar, counts, waiting
  reason ("Waiting for the extension to connect"), Cancel; polling every 5 s while anything is QUEUED/RUNNING;
  expandable row with the chat items.
- Copy: "Import chat history", "All chats", "Specific contacts", "From", "To", "Start import", "Cancel import",
  statuses "Queued", "Running", "Completed", "Cancelled", "Failed".

## 9. Configuration

`omnichannel.history`: `auto-tick true`, `tick 5s`, `page-size 100`, `max-loads 20`, `page-delay 400ms`,
`chat-delay 1s`, `max-messages-per-chat 5000`, `max-empty-pages 50`, `time-budget 60s`. Tests: `auto-tick false`,
delays `0`.
