# Chat History Import (extension) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the server list a number's person chats and page backwards through one chat's history from the WhatsApp Web tab.

**Architecture:** Two new page-side RPC methods, `listChats` and `fetchChatHistory`, registered in `src/page/methods.js` next to `downloadMedia`. `fetchChatHistory` mirrors `Chat.fetchMessages` of whatsapp-web.js (`chat.msgs.getModelsArray()` + `WAWebChatLoadMessages.loadEarlierMsgs`) with an inclusive `before` cursor, `excludeIds`, a `from`/`to` window and a `maxLoads` guard. The page bundle is rebuilt into the backend's resources by `npm run build`.

**Tech Stack:** Plain ES modules, `node --test`, esbuild. Repo: `/Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web-extension`.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/docs/superpowers/specs/2026-10-31-chat-history-import-design.md` §3.

## Global Constraints

- `npm run lint` (eslint + prettier) and `npm test` must pass; `npm run build` must succeed and writes `page.js` / `manifest.json` into `../omnichannel-crm-backend/omnichannel-service/src/main/resources/wabridge/scripts` (committed by the backend plan, not here).
- Page methods throw `codedError(code, message)` so the bridge posts `{error:{code,message}}`; `WHATSAPP_NOT_READY` when `window.WWebJS` is undefined, like `sendMessage`.
- Chat rows are built from the raw `Chat` models, never from `WWebJS.getChatModel` (it fetches group metadata and the last message per chat).
- All times on the wire are WhatsApp seconds (`msg.t`).
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. Several messages in the same second at a page boundary must all be returned once across two pages (inclusive `before` + `excludeIds`) — test in Task 2.
2. A `from` older than the whole chat must stop at the chat's first message without spinning — `exhausted` test in Task 2.
3. A chat whose loaded messages are all newer than `before` must load earlier batches before answering — test in Task 2.
4. `maxLoads` reached with nothing matching yet must return `{messages: [], hasMore: true}`, not hang — test in Task 2.
5. Group, newsletter, broadcast and status chats must be absent from `listChats` — test in Task 1.

---

### Task 1: `listChats` page method

**Files:**
- Modify: `src/page/methods.js` — register `listChats` after `downloadMedia`.
- Test: `tests/methods.test.js` (append; `fakeWindow` in that file shows how `window.require` is faked).

**Interfaces:**
- Produces: method `listChats(params = {})` → `Array<{ id: string, name: string | null, t: number | null, unreadCount: number }>`. Keeps a chat when `chat.id.server` is `'c.us'` or `'lid'` and `!chat.isGroup && !chat.isNewsletter && !chat.isBroadcast` and `chat.id._serialized !== 'status@broadcast'`. `id = chat.id._serialized`, `name = chat.formattedTitle ?? chat.name ?? null`, `t = typeof chat.t === 'number' ? chat.t : null`, `unreadCount = chat.unreadCount ?? 0`. Throws `WHATSAPP_NOT_READY` without `window.WWebJS`.
- Consumes (server side, backend plan Task 6): RPC `listChats` with empty params.

- [ ] **Step 1: Write the failing tests** in `tests/methods.test.js`. Helper `listChats = () => createMethods({ getInfo: () => null }).get('listChats')`. Build `window = { WWebJS: {}, require: (name) => name === 'WAWebCollections' ? { Chat: { getModelsArray: () => chats } } : throwUnexpected(name) }`. Tests:
  - `listChats returns person chats only`: chats = a `c.us` chat (`id: {server:'c.us', _serialized:'919811111111@c.us'}, formattedTitle:'Asha', t: 1700000000, unreadCount: 2`), a `lid` chat (`server:'lid'`, `formattedTitle` undefined, `name: 'Ravi'`, `t` undefined, `unreadCount` undefined), a group (`server:'g.us', isGroup:true`), a newsletter (`server:'newsletter', isNewsletter:true`), a broadcast (`server:'broadcast', isBroadcast:true`), and `status@broadcast`. `assert.deepEqual(await listChats()({}), [{ id:'919811111111@c.us', name:'Asha', t:1700000000, unreadCount:2 }, { id:'<lid>@lid', name:'Ravi', t:null, unreadCount:0 }])`.
  - `listChats → WHATSAPP_NOT_READY without WWebJS`: `globalThis.window = {}` → `assert.rejects(..., e => e.code === 'WHATSAPP_NOT_READY')`.
- [ ] **Step 2: Run** `npm test` → the two tests fail (`listChats` is undefined).
- [ ] **Step 3: Implement** `listChats` in `methods.js` per the Interfaces block.
- [ ] **Step 4: Run** `npm test` and `npm run lint` → pass.
- [ ] **Step 5: Commit** — `feat(page): listChats RPC for history import`

---

### Task 2: `fetchChatHistory` page method

**Files:**
- Modify: `src/page/methods.js` — register `fetchChatHistory` after `listChats`.
- Test: `tests/methods.test.js` (append).

**Interfaces:**
- Produces: method `fetchChatHistory({ chatId, before, excludeIds = [], from, to, limit = 100, maxLoads = 20 })` → `{ messages: object[], oldest: { t: number, ids: string[] } | null, hasMore: boolean }`. `messages` ascend by `t` and are `normalizeMessageModel(window.WWebJS.getMessageModel(m))`. Throws `WHATSAPP_NOT_READY` without `window.WWebJS`; `PAGE_ERROR` ("chatId must be a non-empty string") when `chatId` is not a non-empty string; `CHAT_NOT_FOUND` (`Chat not found: ${chatId}`) when `window.WWebJS.getChat(chatId, { getAsModel: false })` resolves null.
- Algorithm (the spec fixes it; the signature does not):
  ```js
  const exclude = new Set(excludeIds);
  const keep = (m) => !m.isNotification && m.type !== 'ciphertext'
      && (before == null || m.t <= before) && !exclude.has(m.id?._serialized)
      && (to == null || m.t <= to) && (from == null || m.t >= from);
  const load = window.require('WAWebChatLoadMessages');
  let all = chat.msgs.getModelsArray(); let exhausted = false; let pastFrom = false; let loads = 0;
  const oldestLoaded = () => all.reduce((min, m) => (m.t < min ? m.t : min), Infinity);
  while (all.filter(keep).length < limit && !exhausted && !pastFrom && loads < maxLoads) {
      loads++;
      const more = await load.loadEarlierMsgs({ chat });
      if (!more || !more.length) exhausted = true;
      else { all = chat.msgs.getModelsArray(); pastFrom = from != null && oldestLoaded() < from; }
  }
  const kept = all.filter(keep).sort((a, b) => a.t - b.t);
  const page = kept.slice(-limit);
  const oldest = page.length ? { t: page[0].t, ids: page.filter((m) => m.t === page[0].t).map((m) => m.id._serialized) } : null;
  return { messages: page.map((m) => normalizeMessageModel(window.WWebJS.getMessageModel(m))), oldest,
      hasMore: kept.length > limit || (!exhausted && !pastFrom) };
  ```
- Consumes (server side, backend plan Task 6): RPC `fetchChatHistory` with `params` as above.

- [ ] **Step 1: Write the failing tests** in `tests/methods.test.js`. Fake: `msg = (id, t, over = {}) => ({ id: { _serialized: id }, t, type: 'chat', isNotification: false, ...over })`; `fakeChatWindow({ loaded, earlier })` where `loaded` is the initial `chat.msgs` array and `earlier` is an array of batches that successive `loadEarlierMsgs` calls return (each batch is prepended to the chat's array; past the end they return `[]`). `window = { WWebJS: { getChat: async (id) => id === 'x@c.us' ? chat : null, getMessageModel: (m) => ({ id: m.id, t: m.t, body: m.body }) }, require: (n) => n === 'WAWebChatLoadMessages' ? { loadEarlierMsgs: async () => nextBatch() } : throwUnexpected(n) }`. Record `loadCalls`. Tests:
  - `fetchChatHistory returns the newest limit messages ascending with a cursor`: loaded `[m1 t=10, m2 t=20, m3 t=30]`, `earlier: []`, `limit 2` → `messages` ids `['m2','m3']`, `oldest {t:20, ids:['m2']}`, `hasMore true` (three kept, two returned), `loadCalls 0` (the kept count already met the limit).
  - `fetchChatHistory loads earlier batches until the limit is met`: loaded `[m5 t=50]`, `earlier: [[m3 t=30, m4 t=40], [m1 t=10, m2 t=20]]`, `limit 3` → ids `['m3','m4','m5']`, `loadCalls 1`, `hasMore true`.
  - `fetchChatHistory stops when nothing earlier loads`: loaded `[m2 t=20]`, `earlier: []`, `limit 5` → ids `['m2']`, `hasMore false`, `loadCalls 1`.
  - `fetchChatHistory inclusive before with excludeIds`: loaded `[a t=10, b t=20, c t=20, d t=20, e t=30]`, `before 20`, `excludeIds ['d']`, `limit 10`, `earlier: []` → ids `['a','b','c']`, `oldest {t:10, ids:['a']}`, `hasMore false`.
  - `fetchChatHistory same-second boundary split across pages`: same five, `before 20`, `limit 2`, `earlier: []`. Kept ascending is `[a, b, c, d]` (e is newer than `before`), so the page is `['c','d']`, `oldest {t:20, ids:['c','d']}`, `hasMore true`, `loadCalls 0`. Second call `before 20, excludeIds ['c','d'], limit 2` → ids `['a','b']`, `oldest {t:10, ids:['a']}`, `hasMore true` (exactly `limit` kept without loading, so more may exist), `loadCalls 0`. Third call `before 10, excludeIds ['a'], limit 2` → `messages []`, `oldest null`, `hasMore false`, `loadCalls 1`.
  - `fetchChatHistory stops loading once past from`: loaded `[m3 t=30]`, `earlier: [[m2 t=20], [m1 t=10]]`, `from 15`, `limit 10` → ids `['m2','m3']`, `hasMore false`, `loadCalls 2` (the second load reveals t=10 < 15 and sets `pastFrom`).
  - `fetchChatHistory filters to`: loaded `[m1 t=10, m2 t=20, m3 t=30]`, `to 20`, `earlier: []` → ids `['m1','m2']`.
  - `fetchChatHistory drops notifications and ciphertext`: loaded `[m1 t=10, n t=15 {isNotification:true}, c t=16 {type:'ciphertext'}, m2 t=20]`, `earlier: []` → ids `['m1','m2']`.
  - `fetchChatHistory maxLoads reached returns empty page with hasMore`: loaded `[]`, `earlier` = 25 batches each `[msg('z'+i, 1000+i)]` with `to 5` (nothing matches), `maxLoads 3` → `messages []`, `oldest null`, `hasMore true`, `loadCalls 3`.
  - `fetchChatHistory → CHAT_NOT_FOUND`, `→ PAGE_ERROR without chatId` (params `{}`, `{chatId:''}`, `{chatId: 7}`), `→ WHATSAPP_NOT_READY without WWebJS`.
- [ ] **Step 2: Run** `npm test` → the new tests fail.
- [ ] **Step 3: Implement** `fetchChatHistory` in `methods.js` per the Interfaces block (import `normalizeMessageModel` is already there).
- [ ] **Step 4: Run** `npm test` and `npm run lint` → pass.
- [ ] **Step 5: Build** `npm run build` → the log shows the page script version; `git -C ../omnichannel-crm-backend status --short omnichannel-service/src/main/resources/wabridge/scripts` lists `page.js` and `manifest.json` (left for the backend plan to commit).
- [ ] **Step 6: Commit** (extension repo only) — `feat(page): fetchChatHistory RPC with cursor and date window`

---

### Task 3: Verify the `isNewMsg` assumption on a real number (spike, no code kept)

**Files:** none changed; findings go into the spec.

- [ ] **Step 1:** With the backend running and a test number connected, call the super-admin pass-through `POST /api/phone-numbers/{id}/bridge/rpc` with `{"method":"fetchChatHistory","params":{"chatId":"<a chat with history>@c.us","limit":50}}`; expect a 200 with `messages`, `oldest`, `hasMore`.
- [ ] **Step 2:** While it runs, `GET /api/phone-numbers/{id}/bridge/events` and the backend log: no `message_create` events for the loaded old messages. If any appear, add a module-level `suppressed` counter in `src/page/events.js` that `fetchChatHistory` increments around its loads and the `Msg.add` handler checks (`if (suppressed > 0 && !msg.isNewMsg) return` is already implied by the `isNewMsg` guard; the fallback is to skip `Msg.add` entirely while `suppressed > 0`), with a unit test, and commit `fix(page): suppress live emits while loading history`.
- [ ] **Step 3:** Note the result (and typical page timing for 100 messages) under "Assumption to verify" in the spec §3 — one line, committed in the backend repo with the backend plan's first commit.
