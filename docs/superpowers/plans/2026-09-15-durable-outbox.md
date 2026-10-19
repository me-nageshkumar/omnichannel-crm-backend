# Durable Event Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist message-type WhatsApp events in the extension while the server is unreachable and replay them in order, with at-least-once delivery confirmed by server acknowledgements.

**Architecture:** An outbox in `chrome.storage.local` (one key per event, persisted seq) fed by the router; the server acks each durable event cumulatively and the outbox deletes acked entries; the relay buffers in memory during worker restarts; transient state events bypass the outbox.

**Tech Stack:** Node 24 tests, Chrome MV3 storage, Spring Boot server (existing).

**Spec:** `docs/superpowers/specs/2026-09-15-durable-outbox-design.md`

## Global Constraints

- Branch `feat/durable-outbox`. Commit with `--no-verify` after validating with `printf '%s\n' "$MSG" | npx --no-install commitlint`; subjects ≤ 72 chars; end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Durable events: `DURABLE_EVENTS = new Set([Events.MESSAGE_RECEIVED, Events.MESSAGE_CREATE, Events.MESSAGE_ACK])` in `whatsapp-web-extension/src/shared/protocol.js`.
- Outbox keys: `ob:meta`, `ob:e:<seq padded 12>`; cap 10,000.
- Frames: durable event `{type:'event', name, payload, ts, seq}`; ack `{type:'event_ack', seq}` cumulative; hello params `outbox:{pending, dropped}`.
- Run prettier + eslint from repo root before committing JS; server tests with `./mvnw -q test -Dtest=...` and confirm via surefire reports.

---

### Task 1: Protocol additions (both sides)

**Files:**
- Modify: `whatsapp-web-extension/src/shared/protocol.js`, `whatsapp-web-extension/src/page/bridge.js`, `whatsapp-web-extension/tests/protocol.test.js`, `whatsapp-web-extension/tests/bridge.test.js`
- Modify: `whatsapp-backend/src/main/java/me/nageshk/omnichannel/rpc/Envelope.java`, `whatsapp-backend/src/test/java/me/nageshk/omnichannel/rpc/EnvelopeTest.java`

**Interfaces:**
- JS: `DURABLE_EVENTS: Set<string>`, `isDurableEvent(name): boolean`, `eventAck(seq)`, `event(name, payload, ts?)` (ts optional third arg, omitted when undefined), `bridge.emit` passes `Date.now()`.
- Java: `Envelope(type, id, token, method, params, result, error, name, payload, Long seq, Long ts)`; `Envelope.EVENT_ACK = "event_ack"`; `Envelope.eventAck(long seq)`; `Envelope.event(name, payload)` unchanged (seq/ts null).

- [ ] Step 1: tests — protocol: `isDurableEvent('message') === true`, `isDurableEvent('ready') === false`, `eventAck(7)` deep-equals `{type:'event_ack', seq:7}`, `event('x', {}, 123)` has `ts: 123`, `event('x', {})` has no `ts` key. bridge: after `bridge.emit('message', {body:'hi'})` the posted frame has a numeric `ts` close to `Date.now()`. Envelope: parses `{"type":"event","name":"message","seq":5,"ts":1700000000000}` (seq 5L, ts), parses `{"type":"event_ack","seq":3}`, and `Envelope.eventAck(3)` serialises to exactly `{"type":"event_ack","seq":3}`.
- [ ] Step 2: run, expect failures.
- [ ] Step 3: implement (protocol.js, bridge.js emit, Envelope record + all factories pass `null, null` for the two new fields; existing factory call sites unaffected).
- [ ] Step 4: run tests both sides; lint.
- [ ] Step 5: commit `feat: add seq/ts on events and event_ack frame to the protocol`.

---

### Task 2: Outbox module

**Files:**
- Create: `whatsapp-web-extension/src/background/outbox.js`, `whatsapp-web-extension/tests/outbox.test.js`

**Interfaces:**
- `class Outbox({ storage, cap = 10000 })` where `storage = { get(keys) → Promise<object>, set(obj) → Promise, remove(keys) → Promise, getKeys() → Promise<string[]> }`.
- `await load()`; `await append(frame) → seq` (frame is stored with `seq` added); `pending() → frame[]` (in-memory ordered copy, cheap); `await ackUpTo(seq) → removedCount`; `stats() → { pending, dropped, nextSeq }`.
- Internal: `this.entries: Map<seq, frame>` kept in seq order; `this.meta = { nextSeq, dropped }`; `this.chain` promise for serialised writes.

- [ ] Step 1: tests with an in-memory fake storage (`Map`-backed object implementing the four methods; record the order of `set` calls):
  - append assigns 1,2,3 and persists each frame under `ob:e:000000000001` etc. before resolving; `pending()` returns frames with `seq` in order.
  - a second `Outbox` over the same fake storage, after `load()`, has the same pending entries and continues seq at 4.
  - `ackUpTo(2)` removes entries 1 and 2 from memory and storage, keeps 3; `ackUpTo(0)` is a no-op; `ackUpTo(99)` clears all.
  - cap: `new Outbox({ storage, cap: 3 })`, append 5 → pending seqs `[3,4,5]`, `stats().dropped === 2`, storage no longer has keys 1 and 2, and `dropped` survives `load()` in a fresh instance.
  - concurrent appends (`Promise.all` of 5) still yield seqs 1..5 in call order.
- [ ] Step 2: run, expect module not found.
- [ ] Step 3: implement:

```js
const META = 'ob:meta';
const PREFIX = 'ob:e:';
const key = (seq) => PREFIX + String(seq).padStart(12, '0');

export class Outbox {
    constructor({ storage, cap = 10000 }) { this.storage = storage; this.cap = cap; this.entries = new Map(); this.meta = { nextSeq: 1, dropped: 0 }; this.chain = Promise.resolve(); this.loaded = false; }
    async load() {
        const { [META]: meta } = await this.storage.get(META);
        if (meta) this.meta = { nextSeq: meta.nextSeq ?? 1, dropped: meta.dropped ?? 0 };
        const keys = (await this.storage.getKeys()).filter((k) => k.startsWith(PREFIX)).sort();
        if (keys.length) { const items = await this.storage.get(keys); for (const k of keys) { const f = items[k]; if (f && typeof f.seq === 'number') this.entries.set(f.seq, f); } }
        this.loaded = true; return this;
    }
    append(frame) {
        const run = async () => {
            const seq = this.meta.nextSeq++;
            const stored = { ...frame, seq };
            this.entries.set(seq, stored);
            const removeKeys = [];
            while (this.entries.size > this.cap) { const oldest = this.entries.keys().next().value; this.entries.delete(oldest); removeKeys.push(key(oldest)); this.meta.dropped++; }
            await this.storage.set({ [key(seq)]: stored, [META]: { ...this.meta } });
            if (removeKeys.length) await this.storage.remove(removeKeys);
            return seq;
        };
        const p = this.chain.then(run);
        this.chain = p.catch(() => {});
        return p;
    }
    pending() { return [...this.entries.values()]; }
    async ackUpTo(seq) {
        const removeKeys = [];
        for (const s of [...this.entries.keys()]) { if (s <= seq) { this.entries.delete(s); removeKeys.push(key(s)); } }
        if (removeKeys.length) await this.storage.remove(removeKeys);
        return removeKeys.length;
    }
    stats() { return { pending: this.entries.size, dropped: this.meta.dropped, nextSeq: this.meta.nextSeq }; }
}
```
  (Map preserves insertion order, and seqs are inserted ascending, so iteration order is seq order.)
- [ ] Step 4: run tests; lint.
- [ ] Step 5: commit `feat(extension): persistent event outbox in chrome.storage`.

---

### Task 3: Router uses the outbox; acks; drain

**Files:**
- Modify: `whatsapp-web-extension/src/background/router.js`, `whatsapp-web-extension/tests/router.test.js`

**Interfaces:**
- `new Router({ outbox })` (required; `maxBuffer` removed). `fromPage(frame)`: durable event → `outbox.append` then `drain()`; transient event → `_send` (dropped if false); response → `_send`. `fromServer(frame)`: `event_ack` → `outbox.ackUpTo(frame.seq)`; request handling unchanged. `drain(): Promise<number>` sends pending in order until a send fails; guarded against concurrent runs; `flush()` is an alias kept for `index.js` (`return this.drain()`). `bufferedCount` → `outbox.stats().pending`.

- [ ] Step 1: rewrite the buffering tests in `router.test.js` using a real `Outbox` over a fake storage (import from `../src/background/outbox.js`; helper `makeRouter()` that creates the outbox, awaits `load()`, and returns `{router, outbox, conn}`):
  - durable event while disconnected → persisted (`outbox.stats().pending === 1`), nothing sent; after `conn.connected = true; await router.drain()` → sent with `seq`, still pending until ack.
  - `fromServer({type:'event_ack', seq})` → pending 0.
  - transient `ready` while disconnected → not persisted, not sent; while connected → sent without seq.
  - order: append 3 while down, reconnect, drain → sent seqs `[1,2,3]`.
  - drain stops at the first failed send and resumes later.
  - concurrent `drain()` calls do not duplicate sends.
  - existing readiness/`toTab` tests unchanged.
- [ ] Step 2: run, expect failures.
- [ ] Step 3: implement.
- [ ] Step 4: run tests; lint.
- [ ] Step 5: commit `feat(extension): route durable events through the outbox with acks`.

---

### Task 4: Worker, relay, popup, manifest wiring

**Files:**
- Modify: `whatsapp-web-extension/src/background/index.js`, `whatsapp-web-extension/src/content/relay.js`, `whatsapp-web-extension/src/popup/popup.js`, `whatsapp-web-extension/manifest.json`, `whatsapp-web-extension/README.md`

- [ ] Step 1: `index.js`: `const outbox = new Outbox({ storage: chrome.storage.local }); const router = new Router({ outbox });` boot: `Promise.all([loadSettings(), loadPageScript(), outbox.load()])`. `helloParams` adds `outbox: outbox.stats()`. On `CONNECTED` call `router.drain()` (replace `flush`). Status adds `outboxPending: outbox.stats().pending`, `outboxDropped`. `onFrame` already routes `event_ack` through `router.fromServer`.
- [ ] Step 2: `relay.js`: add `const queue = []; const QUEUE_CAP = 1000;` In the window message listener, if `!port` → `queue.push(frame); if (queue.length > QUEUE_CAP) queue.shift(); return;`. In `connectPort()` after listeners are attached: `while (queue.length) port.postMessage(queue.shift());` wrapped in try/catch.
- [ ] Step 3: popup: `if (s.outboxPending) lines.push(\`Outbox: ${s.outboxPending} pending${s.outboxDropped ? \`, ${s.outboxDropped} dropped\` : ''}\`);`
- [ ] Step 4: manifest: add `"unlimitedStorage"` to permissions. README: describe the outbox and the ack semantics; note duplicates possible.
- [ ] Step 5: lint, tests, build (`npm run build` also refreshes the server page script because `bridge.js` changed → commit `whatsapp-backend/src/main/resources/wabridge/scripts/`).
- [ ] Step 6: commit `feat(extension): durable outbox wiring, relay queue, unlimitedStorage`.

---

### Task 5: Server acks and de-duplication

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/omnichannel/ws/EventSequenceTracker.java`
- Modify: `BridgeWebSocketHandler.java` (handleEvent, hello logging), `WhatsAppEvent.java` (seq, ts), `RecentEventsStore.java` (+ `StoredEvent.seqFromDevice`, `capturedAt`), `LoggingEventListener.java`, `BridgeWebSocketIT.java`, `MessagesApiIT.java` (events endpoint shape), `whatsapp-backend/README.md`

**Interfaces:**
- `EventSequenceTracker`: `boolean isDuplicate(String user, long seq)` (true if `seq <= last`), `void record(String user, long seq)`, `OptionalLong last(String user)`.
- `WhatsAppEvent(source, username, name, payload, Long seq, Long ts)`; old 4-arg constructor delegates with nulls.
- `StoredEvent(seq, at, name, payload, Long deviceSeq, Long capturedAt)`.

- [ ] Step 1: tests in `BridgeWebSocketIT`: durable event with `seq` 1 → device receives `event_ack` with seq 1 and the captured `WhatsAppEvent` has `seq()==1`; sending seq 1 again → another `event_ack` but no second `WhatsAppEvent`; event without seq → published, no ack. `MessagesApiIT.eventsEndpoint...`: stored event JSON contains `deviceSeq` and `capturedAt` when provided.
- [ ] Step 2: run, expect failures.
- [ ] Step 3: implement; `LoggingEventListener` prints `seq=` and `capturedAt=` (ISO) for durable events; hello logs `[user] hello waState=… outbox pending=… dropped=…`.
- [ ] Step 4: full server suite; README section "Event delivery guarantees".
- [ ] Step 5: commit `feat(server): acknowledge durable events and de-duplicate by seq`.

---

### Task 6: Manual outage test

- [ ] Restart the server, reload the extension (manifest changed). Popup shows no `Outbox` line.
- [ ] Stop the server. Send a message to the linked number from another phone, and one from the phone to someone. Wait 90 s. Popup (opening it wakes the worker): `Outbox: N pending` (N ≥ 3 including acks).
- [ ] Start the server. Within 30 s: server log `hello … outbox pending=N`, then the replayed events with `capturedAt` timestamps from the outage and increasing `seq`; `GET /api/events` shows them in order; popup `Outbox` line disappears (acked).
- [ ] Record in `whatsapp-web-extension/README.md` verification log; commit.
