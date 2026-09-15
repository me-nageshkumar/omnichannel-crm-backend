# Design: Durable event outbox with server acknowledgements

Status: approved 2026-09-15. Extends the bridge and remote-page-script specs. Branch: `feat/durable-outbox`.

## Context

When the server is unreachable, WhatsApp keeps delivering messages and acks to the open tab.
Today the worker buffers at most 500 events in memory, which Chrome discards when it suspends the
idle worker (about 30 s after the socket drops), and the content relay drops page events while the
worker is down. The server never acknowledges events, so an event in flight during a drop is lost.

Goal: every message-type event that WhatsApp Web produces while the server is away is persisted by
the extension and delivered, in order, once the server is back. Guarantee: at-least-once.

## Decisions

- At-least-once with cumulative server acknowledgements (`event_ack`).
- Persist in `chrome.storage.local`, one key per event; `unlimitedStorage` permission; cap 10,000
  events, drop oldest beyond the cap and count drops.
- Durable events: `message`, `message_create`, `message_ack` (set `DURABLE_EVENTS` in
  `shared/protocol.js`; future message-type events are added there). Transient events (`ready`,
  `change_state`, `disconnected`) are sent only while connected and never replayed, so a stale state
  can never overwrite the fresh `hello`.
- Duplicates after a reconnect or a server restart are acceptable; every durable event carries
  `seq` so consumers can de-duplicate.

## Protocol changes

- Event envelope: `{ type:'event', name, payload, seq?, ts }`. `ts` = capture time (ms epoch) set by
  the page when emitting. `seq` present only on durable events (per-extension monotonic, persisted).
- New server→extension frame: `{ type:'event_ack', seq }`, cumulative: the extension deletes every
  outbox entry with `seq <= ack.seq`.
- `hello` params gain `outbox: { pending, dropped }`; the server logs them.

## Extension

- `background/outbox.js` (pure; storage injected as `{ get, set, remove, getKeys }`):
  - keys: `ob:meta` → `{ nextSeq, dropped }`; `ob:e:<seq zero-padded to 12>` → the event frame
    (with `seq`).
  - `load()` reads meta and pending keys (sorted) once; `append(frame)` assigns `seq`, persists the
    frame before returning, enforces the cap (drops oldest, increments `dropped`); `pending()` returns
    frames in seq order; `ackUpTo(seq)` removes entries; `stats()` → `{ pending, dropped, nextSeq }`.
  - Appends are serialised through an internal promise chain so seq order equals arrival order.
- `background/router.js`:
  - `fromPage(event)`: durable (name in `DURABLE_EVENTS`) → `outbox.append` then `drain()`;
    transient → send if connected, else drop. Responses unchanged. The 500-entry memory buffer is removed.
  - `drain()`: sends every pending frame in order while `connection.send` succeeds; safe to call
    repeatedly (a flag prevents concurrent drains). Called after each append and on `CONNECTED`.
  - `fromServer(frame)`: `event_ack` → `outbox.ackUpTo(frame.seq)`; requests unchanged.
- `background/index.js`: creates the outbox with the real `chrome.storage.local`, `await outbox.load()`
  during boot before connecting; `helloParams` includes `outbox: outbox.stats()`; status includes
  `outboxPending`.
- `content/relay.js`: while `port === null`, page frames are queued in memory (cap 1,000, oldest
  dropped); when the port reconnects the queue is flushed in order before new frames.
- `page/bridge.js`: `emit(name, payload)` adds `ts: Date.now()` to the event envelope.
- `popup`: shows `Outbox: <pending> pending` when > 0.
- `manifest.json`: add `unlimitedStorage`.

## Server

- `Envelope` record gains `Long seq` and `Long ts`; factory `eventAck(long seq)`; constant `EVENT_ACK`.
- `BridgeWebSocketHandler.handleEvent`: if `seq != null` and `seq <= lastSeq(user)` → re-ack only;
  else publish, update `lastSeq`, ack. `lastSeq` lives in an in-memory `ConcurrentHashMap<String, Long>`
  (`EventSequenceTracker` component) and is lost on restart (documented).
- `WhatsAppEvent` gains `Long seq` and `Long ts`; `RecentEventsStore.StoredEvent` and
  `LoggingEventListener` include them (`seq=` and `capturedAt=`).
- `hello`: log `outbox pending/dropped` when present.

## Testing

- `outbox.test.js`: seq assignment and persistence order, reload from storage, ackUpTo, cap and
  dropped counter, stats.
- `router.test.js`: durable → outbox + drain; transient dropped while disconnected; drain order after
  reconnect with entries persisted before the connection existed; `event_ack` removes entries;
  no concurrent double-send.
- `bridge.test.js`: emitted events carry `ts`.
- Server: `BridgeWebSocketIT` — event with seq gets `event_ack`; duplicate seq is acked but not
  republished; hello logs outbox; `EnvelopeTest` parses `seq`/`ts`/`event_ack`.
- Manual (Task 6): with everything connected, stop the server; send a message to the number and one
  from the phone; wait 90 s (worker suspended); restart the server. Expect the hello log line with
  `pending=N`, then the events in `GET /api/events` in seq order with `ts` from the outage.

## Out of scope

- Server-side persistence of events (still in memory / log).
- Exactly-once delivery.
