# WhatsApp Bridge Chrome Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Manifest V3 Chrome extension that runs on the user's own WhatsApp Web tab, forwards incoming messages to the wabridge Spring Boot server over an authenticated WebSocket, and executes `sendMessage` requests coming from the server.

**Architecture:** A MAIN-world page script loads the existing `LoadUtils` from `src/util/Injected/Utils.js`, subscribes to WhatsApp's Backbone collections exactly as `src/Client.js` does, and exposes an RPC method registry over `window.postMessage`. An isolated-world content script relays frames to the service worker over a `chrome.runtime` port. The service worker owns credentials, the JWT login, the WebSocket (with backoff and keepalive pings), and routes frames between the tab and the server.

**Tech Stack:** Chrome MV3, plain ES modules bundled with esbuild into IIFEs, Node 24 built-in test runner (`node --test`), no frameworks.

**Spec:** `docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md`

**Depends on:** the server plan `docs/superpowers/plans/2026-09-15-bridge-server.md` for the manual end-to-end verification in Task 8. Tasks 1-7 need no running server.

## Global Constraints

- Wire protocol is exactly the spec envelope: `type` ∈ `auth|request|response|event|ping|pong`, fields `id, token, method, params, result, error{code,message}, name, payload`. Auth is the first frame after the socket opens; the server answers `{type:"response", id:"auth", result:{ok:true}}`.
- Server close codes: `4401` unauthorized, `4409` replaced by a newer device.
- Event names come from `src/util/Constants.js` `Events` (`ready`, `message`, `message_create`, `message_ack`, `change_state`). Multi-argument WhatsApp callbacks are packed into one payload object (`message_ack` → `{message, ack}`, `change_state` → `{state}`).
- Page methods for the PoC: `sendMessage({chatId, content, options, sendSeen})`, `getState()`, `getInfo()`. Error codes: `METHOD_NOT_FOUND`, `PAGE_ERROR`, `CHAT_NOT_FOUND`, `WHATSAPP_NOT_READY`.
- Reconnect backoff 1 s doubling to a 30 s cap; ping every 20 s; buffer at most 500 events while disconnected.
- The extension never copies `Utils.js`; it imports it from `../../../whatsapp-web.js/src/util/Injected/Utils.js` and esbuild bundles it.
- All extension commands run from `whatsapp-web-extension/`. Node ≥ 24 (`crypto.randomUUID`, `node --test`, `EventTarget` are globals).
- Commit messages follow Conventional Commits, end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Branch: `feat/chrome-extension-bridge`.

---

## File Structure

```
whatsapp-web-extension/
  package.json                 # esbuild devDependency; scripts: build, watch, test
  build.mjs                    # bundles 4 entry points to dist/*.js (iife)
  manifest.json
  popup.html                   # loads dist/popup.js
  README.md
  src/
    shared/protocol.js         # envelope factories, source markers, Events re-export, ErrorCodes
    background/
      connection.js            # WebSocket state machine (auth, backoff, ping)
      router.js                # frame routing + bounded event buffer
      storage.js               # chrome.storage.local wrappers (settings, status)
      index.js                 # service worker wiring (chrome.* APIs live only here)
    content/relay.js           # window.postMessage <-> runtime port relay
    page/
      bridge.js                # postMessage transport + request dispatcher
      methods.js               # RPC method registry (sendMessage, getState, getInfo)
      events.js                # WhatsApp collection listeners → emit()
      inject.js                # page entry: wait for WA, LoadUtils(), wire up, re-inject watch
    popup/popup.js
  tests/
    helpers.js                 # FakeSocket, FakeTimers, flush()
    protocol.test.js
    connection.test.js
    router.test.js
    bridge.test.js
```

Root modification (Task 1): `eslint.config.mjs` gets a block for `whatsapp-web-extension/**` with ES module + webextension globals.

---

### Task 1: Extension scaffold, build, and shared protocol module

**Files:**
- Create: `whatsapp-web-extension/package.json`, `whatsapp-web-extension/build.mjs`, `whatsapp-web-extension/manifest.json`, `whatsapp-web-extension/popup.html`, `whatsapp-web-extension/src/shared/protocol.js`
- Modify: `eslint.config.mjs`
- Test: `whatsapp-web-extension/tests/protocol.test.js`

**Interfaces:**
- Produces (`src/shared/protocol.js`):
  - `PAGE_SOURCE = 'wabridge-page'`, `CONTENT_SOURCE = 'wabridge-content'`, `WA_ORIGIN = 'https://web.whatsapp.com'`, `TAB_PORT_NAME = 'wabridge-tab'`
  - `Events` (re-export of `src/util/Constants.js` `Events`)
  - `ErrorCodes = { METHOD_NOT_FOUND, PAGE_ERROR, CHAT_NOT_FOUND, WHATSAPP_NOT_READY }`
  - `CloseCodes = { UNAUTHORIZED: 4401, REPLACED: 4409, PING_TIMEOUT: 4000 }`
  - `auth(token)`, `request(id, method, params)`, `response(id, result)`, `errorResponse(id, code, message)`, `event(name, payload)`, `ping()`, `pong()`, `isEnvelope(x)`, `newId()`

- [ ] **Step 1: package.json, build script, manifest, popup shell**

`whatsapp-web-extension/package.json`:

```json
{
  "name": "wabridge-extension",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "description": "Chrome extension bridging WhatsApp Web to the wabridge server",
  "scripts": {
    "build": "node build.mjs",
    "watch": "node build.mjs --watch",
    "test": "node --test"
  },
  "devDependencies": {
    "esbuild": "^0.25.0"
  },
  "engines": {
    "node": ">=24"
  }
}
```

`whatsapp-web-extension/build.mjs`:

```js
import { build, context } from 'esbuild';

const watch = process.argv.includes('--watch');

const entries = {
    page: 'src/page/inject.js',
    content: 'src/content/relay.js',
    background: 'src/background/index.js',
    popup: 'src/popup/popup.js',
};

const options = Object.entries(entries).map(([name, entry]) => ({
    entryPoints: [entry],
    outfile: `dist/${name}.js`,
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: ['chrome120'],
    sourcemap: false,
    logLevel: 'info',
}));

if (watch) {
    const contexts = await Promise.all(options.map((o) => context(o)));
    await Promise.all(contexts.map((c) => c.watch()));
    console.log('watching…');
} else {
    await Promise.all(options.map((o) => build(o)));
}
```

`whatsapp-web-extension/manifest.json`:

```json
{
    "manifest_version": 3,
    "name": "WhatsApp Bridge",
    "version": "0.1.0",
    "description": "Relays WhatsApp Web messages to your wabridge server and sends messages on its behalf.",
    "permissions": ["storage", "scripting"],
    "host_permissions": [
        "https://web.whatsapp.com/*",
        "http://localhost:8080/*",
        "http://127.0.0.1:8080/*"
    ],
    "background": {
        "service_worker": "dist/background.js"
    },
    "content_scripts": [
        {
            "matches": ["https://web.whatsapp.com/*"],
            "js": ["dist/content.js"],
            "run_at": "document_idle"
        }
    ],
    "action": {
        "default_popup": "popup.html",
        "default_title": "WhatsApp Bridge"
    }
}
```

`whatsapp-web-extension/popup.html`:

```html
<!doctype html>
<html>
    <head>
        <meta charset="utf-8" />
        <title>WhatsApp Bridge</title>
        <style>
            body { font: 13px system-ui, sans-serif; width: 280px; margin: 0; padding: 12px; }
            label { display: block; margin-top: 8px; }
            input { width: 100%; box-sizing: border-box; padding: 6px; margin-top: 2px; }
            button { margin-top: 10px; padding: 6px 10px; }
            #status { margin-top: 10px; padding: 8px; background: #f2f2f2; border-radius: 4px; white-space: pre-wrap; }
        </style>
    </head>
    <body>
        <label>Server URL <input id="serverUrl" placeholder="http://localhost:8080" /></label>
        <label>Username <input id="username" autocomplete="username" /></label>
        <label>Password <input id="password" type="password" autocomplete="current-password" /></label>
        <button id="connect">Connect</button>
        <button id="disconnect">Disconnect</button>
        <div id="status">…</div>
        <script src="dist/popup.js"></script>
    </body>
</html>
```

- [ ] **Step 2: ESLint block for the extension**

In `eslint.config.mjs`, add this object to the exported array right before the `'whatsapp-web.js/default/ignores'` entry:

```js
    {
        name: 'whatsapp-web.js/extension',
        files: ['extension/**/*.js', 'extension/**/*.mjs'],
        languageOptions: {
            sourceType: 'module',
            globals: {
                ...globals.browser,
                ...globals.webextensions,
                ...globals.node,
            },
        },
    },
```

(`whatsapp-web-extension/dist` was added to `ignores` in the server plan's Task 1; if it is missing, add `'extension/dist'` to the `ignores` array.)

- [ ] **Step 3: Write the failing protocol test**

`whatsapp-web-extension/tests/protocol.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    auth, request, response, errorResponse, event, ping, pong,
    isEnvelope, newId, Events, ErrorCodes, CloseCodes, PAGE_SOURCE, CONTENT_SOURCE,
} from '../src/shared/protocol.js';

test('factories build spec-shaped envelopes', () => {
    assert.deepEqual(auth('t'), { type: 'auth', token: 't' });
    assert.deepEqual(request('1', 'sendMessage', { a: 1 }), { type: 'request', id: '1', method: 'sendMessage', params: { a: 1 } });
    assert.deepEqual(response('1', { ok: true }), { type: 'response', id: '1', result: { ok: true } });
    assert.deepEqual(errorResponse('1', 'PAGE_ERROR', 'boom'), { type: 'response', id: '1', error: { code: 'PAGE_ERROR', message: 'boom' } });
    assert.deepEqual(event('message', { body: 'hi' }), { type: 'event', name: 'message', payload: { body: 'hi' } });
    assert.deepEqual(ping(), { type: 'ping' });
    assert.deepEqual(pong(), { type: 'pong' });
});

test('isEnvelope accepts objects with a string type only', () => {
    assert.equal(isEnvelope({ type: 'ping' }), true);
    assert.equal(isEnvelope({ type: 7 }), false);
    assert.equal(isEnvelope(null), false);
    assert.equal(isEnvelope('ping'), false);
});

test('newId returns unique uuid strings', () => {
    const a = newId();
    const b = newId();
    assert.match(a, /^[0-9a-f-]{36}$/);
    assert.notEqual(a, b);
});

test('re-exports whatsapp-web.js event names and constants', () => {
    assert.equal(Events.MESSAGE_RECEIVED, 'message');
    assert.equal(Events.MESSAGE_CREATE, 'message_create');
    assert.equal(Events.MESSAGE_ACK, 'message_ack');
    assert.equal(Events.STATE_CHANGED, 'change_state');
    assert.equal(Events.READY, 'ready');
    assert.equal(ErrorCodes.METHOD_NOT_FOUND, 'METHOD_NOT_FOUND');
    assert.equal(CloseCodes.UNAUTHORIZED, 4401);
    assert.equal(CloseCodes.REPLACED, 4409);
    assert.equal(PAGE_SOURCE, 'wabridge-page');
    assert.equal(CONTENT_SOURCE, 'wabridge-content');
});
```

- [ ] **Step 4: Run to verify failure**

Run: `cd whatsapp-web-extension && npm install && npm test`
Expected: `protocol.test.js` fails with "Cannot find module '../src/shared/protocol.js'".

- [ ] **Step 5: Implement protocol.js**

`whatsapp-web-extension/src/shared/protocol.js`:

```js
// Shared wire-protocol helpers. Pure module: no chrome.* or window access.
import { Events } from '../../../whatsapp-web.js/src/util/Constants.js';

export { Events };

export const PAGE_SOURCE = 'wabridge-page';
export const CONTENT_SOURCE = 'wabridge-content';
export const WA_ORIGIN = 'https://web.whatsapp.com';
export const TAB_PORT_NAME = 'wabridge-tab';

export const ErrorCodes = Object.freeze({
    METHOD_NOT_FOUND: 'METHOD_NOT_FOUND',
    PAGE_ERROR: 'PAGE_ERROR',
    CHAT_NOT_FOUND: 'CHAT_NOT_FOUND',
    WHATSAPP_NOT_READY: 'WHATSAPP_NOT_READY',
});

export const CloseCodes = Object.freeze({
    PING_TIMEOUT: 4000,
    UNAUTHORIZED: 4401,
    REPLACED: 4409,
});

export const auth = (token) => ({ type: 'auth', token });
export const request = (id, method, params) => ({ type: 'request', id, method, params });
export const response = (id, result) => ({ type: 'response', id, result });
export const errorResponse = (id, code, message) => ({ type: 'response', id, error: { code, message } });
export const event = (name, payload) => ({ type: 'event', name, payload });
export const ping = () => ({ type: 'ping' });
export const pong = () => ({ type: 'pong' });

export const isEnvelope = (x) => Boolean(x) && typeof x === 'object' && typeof x.type === 'string';

export const newId = () => crypto.randomUUID();
```

- [ ] **Step 6: Run tests and a build**

Run: `cd whatsapp-web-extension && npm test`
Expected: 4 tests pass.

Run: `cd whatsapp-web-extension && npm run build`
Expected: esbuild fails because the four entry files do not exist yet. That is expected at this point; the build is exercised for real in Task 7. To keep the commit clean, do not commit `dist/` (already git-ignored).

Run: `cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web.js && npx eslint extension`
Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add eslint.config.mjs extension/package.json extension/build.mjs extension/manifest.json extension/popup.html extension/src/shared extension/tests/protocol.test.js
git commit -m "feat(extension): scaffold MV3 extension with build and shared protocol

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: WebSocket connection state machine

**Files:**
- Create: `whatsapp-web-extension/src/background/connection.js`, `whatsapp-web-extension/tests/helpers.js`
- Test: `whatsapp-web-extension/tests/connection.test.js`

**Interfaces:**
- Consumes: `protocol.js` (`auth`, `request`, `pong`, `ping`, `isEnvelope`, `newId`, `CloseCodes`).
- Produces:
  - `States = { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, WAITING, AUTH_FAILED, REPLACED, STOPPED }` (values are lowercase strings: `'idle'`, `'connecting'`, `'authenticating'`, `'connected'`, `'waiting'`, `'auth_failed'`, `'replaced'`, `'stopped'`).
  - `class AuthError extends Error`
  - `class Connection` with ctor options `{ url, authenticate, createSocket, setTimer, clearTimer, pingIntervalMs, baseBackoffMs, maxBackoffMs, onFrame, onStateChange, helloParams }`, properties `state`, `attempts`; methods `start(): Promise<void>`, `stop()`, `send(frame): boolean`.
  - `onStateChange(state, detail)` fires on every transition; `onFrame(frame)` fires for every server frame except `pong`, `ping` (auto-answered) and the auth response.
  - Test helpers: `FakeTimers { set, clear, advance(ms), pending() }`, `FakeSocket { sent, closed, readyState, open(), receive(frame), close(code, reason) }`, `flush()`.

- [ ] **Step 1: Write the test helpers**

`whatsapp-web-extension/tests/helpers.js`:

```js
// Deterministic stand-ins for WebSocket and timers.

export const flush = () => new Promise((resolve) => setImmediate(resolve));

export class FakeTimers {
    constructor() {
        this.now = 0;
        this.queue = [];
        this.nextId = 1;
        this.set = this.set.bind(this);
        this.clear = this.clear.bind(this);
    }

    set(fn, ms) {
        const id = this.nextId++;
        this.queue.push({ id, at: this.now + ms, fn });
        return id;
    }

    clear(id) {
        this.queue = this.queue.filter((t) => t.id !== id);
    }

    /** Runs every timer due within ms, in order, flushing microtasks between them. */
    async advance(ms) {
        const target = this.now + ms;
        for (;;) {
            this.queue.sort((a, b) => a.at - b.at);
            const next = this.queue[0];
            if (!next || next.at > target) break;
            this.queue.shift();
            this.now = next.at;
            next.fn();
            await flush();
        }
        this.now = target;
    }

    /** Delays (ms from now) of pending timers, sorted. */
    pending() {
        return this.queue.map((t) => t.at - this.now).sort((a, b) => a - b);
    }
}

export class FakeSocket {
    constructor(url) {
        this.url = url;
        this.sent = [];
        this.closed = null;
        this.readyState = 0; // CONNECTING
        this.onopen = null;
        this.onmessage = null;
        this.onclose = null;
        this.onerror = null;
    }

    send(data) {
        this.sent.push(JSON.parse(data));
    }

    close(code = 1000, reason = '') {
        if (this.readyState === 3) return;
        this.closed = { code, reason };
        this.readyState = 3; // CLOSED
        // Browsers fire onclose asynchronously after close().
        setImmediate(() => this.onclose?.({ code, reason }));
    }

    open() {
        this.readyState = 1; // OPEN
        this.onopen?.();
    }

    receive(frame) {
        this.onmessage?.({ data: JSON.stringify(frame) });
    }

    /** Simulates the server dropping the connection. */
    serverClose(code, reason = '') {
        this.readyState = 3;
        this.closed = { code, reason };
        this.onclose?.({ code, reason });
    }
}
```

- [ ] **Step 2: Write the failing tests**

`whatsapp-web-extension/tests/connection.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Connection, States, AuthError } from '../src/background/connection.js';
import { FakeSocket, FakeTimers, flush } from './helpers.js';

function harness(overrides = {}) {
    const timers = new FakeTimers();
    const sockets = [];
    const states = [];
    const frames = [];
    let tokens = 0;
    const conn = new Connection({
        url: 'ws://localhost:8080/ws',
        authenticate: async () => `token-${++tokens}`,
        createSocket: (url) => {
            const s = new FakeSocket(url);
            sockets.push(s);
            return s;
        },
        setTimer: timers.set,
        clearTimer: timers.clear,
        onFrame: (f) => frames.push(f),
        onStateChange: (state, detail) => states.push({ state, detail }),
        helloParams: () => ({ waState: 'CONNECTED' }),
        ...overrides,
    });
    const last = () => sockets[sockets.length - 1];
    const authenticateOk = async () => {
        last().open();
        last().receive({ type: 'response', id: 'auth', result: { ok: true } });
        await flush();
    };
    return { conn, timers, sockets, states, frames, last, authenticateOk, stateNames: () => states.map((s) => s.state) };
}

test('start authenticates, opens socket and sends auth frame first', async () => {
    const h = harness();
    await h.conn.start();

    assert.equal(h.sockets.length, 1);
    assert.equal(h.last().url, 'ws://localhost:8080/ws');
    assert.equal(h.conn.state, States.CONNECTING);

    h.last().open();
    assert.equal(h.conn.state, States.AUTHENTICATING);
    assert.deepEqual(h.last().sent[0], { type: 'auth', token: 'token-1' });
});

test('auth ok → connected, hello request sent, attempts reset', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();

    assert.equal(h.conn.state, States.CONNECTED);
    const hello = h.last().sent[1];
    assert.equal(hello.type, 'request');
    assert.equal(hello.method, 'hello');
    assert.deepEqual(hello.params, { waState: 'CONNECTED' });
    assert.equal(h.conn.attempts, 0);
    assert.deepEqual(h.stateNames(), [States.CONNECTING, States.AUTHENTICATING, States.CONNECTED]);
});

test('auth error response → auth_failed and socket closed, no reconnect', async () => {
    const h = harness();
    await h.conn.start();
    h.last().open();
    h.last().receive({ type: 'response', id: 'auth', error: { code: 'UNAUTHORIZED', message: 'bad token' } });
    await flush();

    assert.equal(h.conn.state, States.AUTH_FAILED);
    assert.ok(h.last().closed);
    assert.deepEqual(h.timers.pending(), []);
});

test('AuthError from authenticate → auth_failed without opening a socket', async () => {
    const h = harness({ authenticate: async () => { throw new AuthError('invalid username or password'); } });
    await h.conn.start();

    assert.equal(h.conn.state, States.AUTH_FAILED);
    assert.equal(h.sockets.length, 0);
    assert.equal(h.states.at(-1).detail, 'invalid username or password');
});

test('network error from authenticate → waiting with backoff, then retries', async () => {
    let calls = 0;
    const h = harness({
        authenticate: async () => {
            calls++;
            if (calls === 1) throw new Error('fetch failed');
            return 'token-ok';
        },
    });
    await h.conn.start();
    assert.equal(h.conn.state, States.WAITING);
    assert.deepEqual(h.timers.pending(), [1000]);

    await h.timers.advance(1000);
    assert.equal(h.sockets.length, 1);
    assert.equal(h.conn.state, States.CONNECTING);
});

test('unexpected close → exponential backoff capped at 30s', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();

    const expected = [1000, 2000, 4000, 8000, 16000, 30000, 30000];
    for (const delay of expected) {
        h.last().serverClose(1006, 'abnormal');
        await flush();
        assert.equal(h.conn.state, States.WAITING);
        assert.deepEqual(h.timers.pending(), [delay]);
        await h.timers.advance(delay);
        assert.equal(h.conn.state, States.CONNECTING);
        h.last().open(); // authenticating, but never acknowledged → next close increases attempts
    }
    assert.equal(h.sockets.length, expected.length + 1);
});

test('successful connection resets backoff', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();
    h.last().serverClose(1006);
    await flush();
    await h.timers.advance(1000);
    await h.authenticateOk();
    assert.equal(h.conn.attempts, 0);

    h.last().serverClose(1006);
    await flush();
    assert.deepEqual(h.timers.pending(), [1000]);
});

test('close 4409 (replaced) → replaced, no reconnect', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();
    h.last().serverClose(4409, 'replaced by newer connection');
    await flush();

    assert.equal(h.conn.state, States.REPLACED);
    assert.deepEqual(h.timers.pending(), []);
});

test('two consecutive 4401 closes → auth_failed; a single one retries', async () => {
    const h = harness();
    await h.conn.start();
    h.last().open();
    h.last().serverClose(4401, 'invalid token');
    await flush();
    assert.equal(h.conn.state, States.WAITING);

    await h.timers.advance(1000);
    h.last().open();
    h.last().serverClose(4401, 'invalid token');
    await flush();
    assert.equal(h.conn.state, States.AUTH_FAILED);
});

test('sends ping every 20s and closes the socket when no reply arrives', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();
    const sentBefore = h.last().sent.length;

    await h.timers.advance(20000);
    assert.deepEqual(h.last().sent[sentBefore], { type: 'ping' });

    h.last().receive({ type: 'pong' });
    await h.timers.advance(20000);
    assert.deepEqual(h.last().sent[sentBefore + 1], { type: 'ping' });
    assert.equal(h.last().closed, null);

    await h.timers.advance(20000); // no pong this time
    assert.equal(h.last().closed.code, 4000);
    await flush();
    assert.equal(h.conn.state, States.WAITING);
});

test('server frames reach onFrame; server ping is answered; pong is swallowed', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();
    const before = h.last().sent.length;

    h.last().receive({ type: 'request', id: 'r1', method: 'sendMessage', params: {} });
    h.last().receive({ type: 'ping' });
    h.last().receive({ type: 'pong' });
    h.last().receive('not json at all');

    assert.deepEqual(h.frames, [{ type: 'request', id: 'r1', method: 'sendMessage', params: {} }]);
    assert.deepEqual(h.last().sent[before], { type: 'pong' });
});

test('send returns false unless connected', async () => {
    const h = harness();
    assert.equal(h.conn.send({ type: 'event', name: 'x' }), false);
    await h.conn.start();
    h.last().open();
    assert.equal(h.conn.send({ type: 'event', name: 'x' }), false);
    h.last().receive({ type: 'response', id: 'auth', result: { ok: true } });
    await flush();
    assert.equal(h.conn.send({ type: 'event', name: 'x' }), true);
    assert.deepEqual(h.last().sent.at(-1), { type: 'event', name: 'x' });
});

test('stop closes the socket, cancels timers and prevents reconnect', async () => {
    const h = harness();
    await h.conn.start();
    await h.authenticateOk();
    h.conn.stop();
    await flush();

    assert.equal(h.conn.state, States.STOPPED);
    assert.equal(h.last().closed.code, 1000);
    assert.deepEqual(h.timers.pending(), []);
    assert.equal(h.sockets.length, 1);
});

test('start is idempotent while running and restarts after stop', async () => {
    const h = harness();
    await h.conn.start();
    await h.conn.start();
    assert.equal(h.sockets.length, 1);
    h.conn.stop();
    await flush();
    await h.conn.start();
    assert.equal(h.sockets.length, 2);
});
```

- [ ] **Step 3: Run to verify failure**

Run: `cd whatsapp-web-extension && npm test`
Expected: `connection.test.js` fails with module not found.

- [ ] **Step 4: Implement connection.js**

`whatsapp-web-extension/src/background/connection.js`:

```js
// WebSocket connection state machine. Pure module: all I/O is injected so it is testable in Node.
import { auth, request, ping, pong, isEnvelope, newId, CloseCodes } from '../shared/protocol.js';

export const States = Object.freeze({
    IDLE: 'idle',
    CONNECTING: 'connecting',
    AUTHENTICATING: 'authenticating',
    CONNECTED: 'connected',
    WAITING: 'waiting',
    AUTH_FAILED: 'auth_failed',
    REPLACED: 'replaced',
    STOPPED: 'stopped',
});

/** Thrown by `authenticate` when credentials are rejected (no point retrying). */
export class AuthError extends Error {}

const OPEN = 1;
const RESTARTABLE = new Set([States.IDLE, States.STOPPED, States.AUTH_FAILED, States.REPLACED]);

export class Connection {
    constructor({
        url,
        authenticate,
        createSocket = (u) => new WebSocket(u),
        // Wrapped on purpose: calling a native timer as `this.setTimer(...)` with a non-global
        // `this` throws "Illegal invocation" in browsers (Node ignores it, so tests would not notice).
        setTimer = (fn, ms) => setTimeout(fn, ms),
        clearTimer = (id) => clearTimeout(id),
        pingIntervalMs = 20000,
        baseBackoffMs = 1000,
        maxBackoffMs = 30000,
        onFrame = () => {},
        onStateChange = () => {},
        helloParams = () => ({}),
    }) {
        this.url = url;
        this.authenticate = authenticate;
        this.createSocket = createSocket;
        this.setTimer = setTimer;
        this.clearTimer = clearTimer;
        this.pingIntervalMs = pingIntervalMs;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.onFrame = onFrame;
        this.onStateChange = onStateChange;
        this.helloParams = helloParams;

        this.state = States.IDLE;
        this.attempts = 0;
        this.authRejects = 0;
        this.socket = null;
        this.reconnectTimer = null;
        this.pingTimer = null;
        this.awaitingPong = false;
    }

    /** Begins connecting. Resolves once the first connection attempt has been initiated. */
    start() {
        if (!RESTARTABLE.has(this.state)) return Promise.resolve();
        this.attempts = 0;
        this.authRejects = 0;
        return this._open();
    }

    stop() {
        this._clearTimers();
        this._setState(States.STOPPED);
        const socket = this.socket;
        this.socket = null;
        if (socket && socket.readyState !== 3) socket.close(1000, 'stopped');
    }

    /** @returns {boolean} true if the frame was handed to an open, authenticated socket. */
    send(frame) {
        if (this.state !== States.CONNECTED || !this.socket || this.socket.readyState !== OPEN) return false;
        this.socket.send(JSON.stringify(frame));
        return true;
    }

    async _open() {
        this._setState(States.CONNECTING);
        let token;
        try {
            token = await this.authenticate();
        } catch (err) {
            if (this.state !== States.CONNECTING) return;
            if (err instanceof AuthError) {
                this._setState(States.AUTH_FAILED, err.message);
                return;
            }
            this._scheduleReconnect(err.message || 'login failed');
            return;
        }
        if (this.state !== States.CONNECTING) return; // stopped while logging in

        const socket = this.createSocket(this.url);
        this.socket = socket;
        socket.onopen = () => {
            if (this.socket !== socket) return;
            this._setState(States.AUTHENTICATING);
            socket.send(JSON.stringify(auth(token)));
        };
        socket.onmessage = (ev) => {
            if (this.socket === socket) this._onMessage(ev.data);
        };
        socket.onerror = () => {
            /* onclose always follows an error */
        };
        socket.onclose = (ev) => {
            if (this.socket === socket) this._onClose(ev.code, ev.reason);
        };
    }

    _onMessage(data) {
        let frame;
        try {
            frame = JSON.parse(data);
        } catch {
            return;
        }
        if (!isEnvelope(frame)) return;
        this.awaitingPong = false;

        if (this.state === States.AUTHENTICATING) {
            if (frame.type !== 'response' || frame.id !== 'auth') return;
            if (frame.error) {
                this._setState(States.AUTH_FAILED, frame.error.message || frame.error.code);
                this.socket.close(1000, 'auth failed');
                return;
            }
            this.attempts = 0;
            this.authRejects = 0;
            this._setState(States.CONNECTED);
            this.awaitingPong = false;
            this._schedulePing();
            this.send(request(newId(), 'hello', this.helloParams()));
            return;
        }

        if (frame.type === 'pong') return;
        if (frame.type === 'ping') {
            this.send(pong());
            return;
        }
        this.onFrame(frame);
    }

    _onClose(code, reason) {
        this._clearPing();
        this.socket = null;
        if (this.state === States.STOPPED || this.state === States.AUTH_FAILED) return;

        if (code === CloseCodes.REPLACED) {
            this._setState(States.REPLACED, reason || 'replaced by another device');
            return;
        }
        if (code === CloseCodes.UNAUTHORIZED) {
            this.authRejects++;
            if (this.authRejects >= 2) {
                this._setState(States.AUTH_FAILED, reason || 'token rejected');
                return;
            }
        }
        this._scheduleReconnect(reason || `socket closed (${code})`);
    }

    _scheduleReconnect(detail) {
        const delay = Math.min(this.baseBackoffMs * 2 ** this.attempts, this.maxBackoffMs);
        this.attempts++;
        this._setState(States.WAITING, `${detail}; retry in ${Math.round(delay / 1000)}s`);
        this.reconnectTimer = this.setTimer(() => {
            this.reconnectTimer = null;
            this._open();
        }, delay);
    }

    _schedulePing() {
        this.pingTimer = this.setTimer(() => {
            this.pingTimer = null;
            if (this.state !== States.CONNECTED) return;
            if (this.awaitingPong) {
                // No frame since our last ping: the socket is dead. Closing triggers reconnect.
                this.socket?.close(CloseCodes.PING_TIMEOUT, 'ping timeout');
                return;
            }
            this.awaitingPong = true;
            this.send(ping());
            this._schedulePing();
        }, this.pingIntervalMs);
    }

    _clearPing() {
        if (this.pingTimer !== null) {
            this.clearTimer(this.pingTimer);
            this.pingTimer = null;
        }
        this.awaitingPong = false;
    }

    _clearTimers() {
        this._clearPing();
        if (this.reconnectTimer !== null) {
            this.clearTimer(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    _setState(state, detail) {
        this.state = state;
        this.onStateChange(state, detail);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd whatsapp-web-extension && npm test`
Expected: all protocol and connection tests pass (18 total). If the backoff test fails on the socket count, check that `serverClose` in the helper fires `onclose` synchronously and that `_open` is invoked from the reconnect timer.

- [ ] **Step 6: Commit**

```bash
git add extension/src/background/connection.js extension/tests/helpers.js extension/tests/connection.test.js
git commit -m "feat(extension): websocket connection state machine with auth, backoff and ping

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Frame router with bounded event buffer

**Files:**
- Create: `whatsapp-web-extension/src/background/router.js`
- Test: `whatsapp-web-extension/tests/router.test.js`

**Interfaces:**
- Consumes: `protocol.js` (`errorResponse`, `ErrorCodes`).
- Produces `class Router({ maxBuffer = 500 })`:
  - `setConnection(conn | null)` where `conn` has `send(frame): boolean`
  - `attachTab(tab)` / `detachTab(tab)` where `tab` has `postMessage(frame)`; `hasTab(): boolean`
  - `fromPage(frame)`: `event` → connection or buffer; `response` → connection (dropped if down); anything else ignored
  - `fromServer(frame)`: `request` → tab, or `WHATSAPP_NOT_READY` error response back to the server when no tab
  - `flush(): number` sends buffered events in order; `bufferedCount`

- [ ] **Step 1: Write the failing tests**

`whatsapp-web-extension/tests/router.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Router } from '../src/background/router.js';

function fakeConnection(connected = true) {
    const sent = [];
    return { sent, connected, send(frame) { if (!this.connected) return false; sent.push(frame); return true; } };
}

function fakeTab() {
    const received = [];
    return { received, postMessage(frame) { received.push(frame); } };
}

const evt = (n) => ({ type: 'event', name: 'message', payload: { n } });

test('page events go straight to a connected server', () => {
    const router = new Router();
    const conn = fakeConnection();
    router.setConnection(conn);
    router.fromPage(evt(1));
    assert.deepEqual(conn.sent, [evt(1)]);
    assert.equal(router.bufferedCount, 0);
});

test('page events are buffered while disconnected and flushed in order', () => {
    const router = new Router();
    const conn = fakeConnection(false);
    router.setConnection(conn);
    router.fromPage(evt(1));
    router.fromPage(evt(2));
    assert.equal(router.bufferedCount, 2);
    assert.deepEqual(conn.sent, []);

    conn.connected = true;
    assert.equal(router.flush(), 2);
    assert.deepEqual(conn.sent, [evt(1), evt(2)]);
    assert.equal(router.bufferedCount, 0);
});

test('events are buffered when no connection is set at all', () => {
    const router = new Router();
    router.fromPage(evt(1));
    assert.equal(router.bufferedCount, 1);
});

test('buffer drops the oldest beyond maxBuffer', () => {
    const router = new Router({ maxBuffer: 3 });
    for (let i = 1; i <= 5; i++) router.fromPage(evt(i));
    const conn = fakeConnection();
    router.setConnection(conn);
    router.flush();
    assert.deepEqual(conn.sent.map((f) => f.payload.n), [3, 4, 5]);
});

test('flush stops at the first failed send and keeps the rest', () => {
    const router = new Router();
    const conn = fakeConnection(false);
    router.setConnection(conn);
    router.fromPage(evt(1));
    router.fromPage(evt(2));
    let allowed = 1;
    conn.send = (frame) => { if (allowed-- <= 0) return false; conn.sent.push(frame); return true; };
    assert.equal(router.flush(), 1);
    assert.equal(router.bufferedCount, 1);
});

test('page responses are forwarded but never buffered', () => {
    const router = new Router();
    const conn = fakeConnection(false);
    router.setConnection(conn);
    router.fromPage({ type: 'response', id: 'r1', result: null });
    assert.equal(router.bufferedCount, 0);
    conn.connected = true;
    router.fromPage({ type: 'response', id: 'r2', result: null });
    assert.deepEqual(conn.sent, [{ type: 'response', id: 'r2', result: null }]);
});

test('server requests go to the attached tab', () => {
    const router = new Router();
    const tab = fakeTab();
    router.attachTab(tab);
    const req = { type: 'request', id: 'r1', method: 'sendMessage', params: {} };
    router.fromServer(req);
    assert.deepEqual(tab.received, [req]);
    assert.equal(router.hasTab(), true);
});

test('server requests without a tab get WHATSAPP_NOT_READY', () => {
    const router = new Router();
    const conn = fakeConnection();
    router.setConnection(conn);
    router.fromServer({ type: 'request', id: 'r1', method: 'sendMessage', params: {} });
    assert.equal(conn.sent.length, 1);
    assert.equal(conn.sent[0].type, 'response');
    assert.equal(conn.sent[0].id, 'r1');
    assert.equal(conn.sent[0].error.code, 'WHATSAPP_NOT_READY');
});

test('detachTab only detaches the same tab; a newer tab replaces the older', () => {
    const router = new Router();
    const a = fakeTab();
    const b = fakeTab();
    router.attachTab(a);
    router.attachTab(b);
    router.detachTab(a); // stale
    assert.equal(router.hasTab(), true);
    router.fromServer({ type: 'request', id: 'r1', method: 'getState' });
    assert.equal(b.received.length, 1);
    assert.equal(a.received.length, 0);
    router.detachTab(b);
    assert.equal(router.hasTab(), false);
});

test('non-request server frames are ignored', () => {
    const router = new Router();
    const tab = fakeTab();
    router.attachTab(tab);
    router.fromServer({ type: 'pong' });
    router.fromServer({ type: 'event', name: 'x' });
    assert.deepEqual(tab.received, []);
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-web-extension && npm test`
Expected: `router.test.js` fails with module not found.

- [ ] **Step 3: Implement router.js**

`whatsapp-web-extension/src/background/router.js`:

```js
// Routes frames between the WhatsApp tab (page script) and the server connection.
// Pure module: the service worker wires chrome.runtime ports and the Connection into it.
import { errorResponse, ErrorCodes } from '../shared/protocol.js';

export class Router {
    constructor({ maxBuffer = 500 } = {}) {
        this.maxBuffer = maxBuffer;
        this.connection = null;
        this.tab = null;
        this.buffer = [];
    }

    get bufferedCount() {
        return this.buffer.length;
    }

    setConnection(connection) {
        this.connection = connection;
    }

    attachTab(tab) {
        this.tab = tab;
    }

    detachTab(tab) {
        if (this.tab === tab) this.tab = null;
    }

    hasTab() {
        return this.tab !== null;
    }

    /** Frames produced by the page script. */
    fromPage(frame) {
        if (!frame || typeof frame.type !== 'string') return;
        if (frame.type === 'event') {
            if (!this._send(frame)) this._buffer(frame);
            return;
        }
        if (frame.type === 'response') {
            this._send(frame); // a response to a request the server may have already timed out; never buffer
        }
    }

    /** Frames received from the server. */
    fromServer(frame) {
        if (!frame || frame.type !== 'request') return;
        if (this.tab) {
            this.tab.postMessage(frame);
            return;
        }
        this._send(errorResponse(frame.id, ErrorCodes.WHATSAPP_NOT_READY, 'no WhatsApp Web tab is connected'));
    }

    /** Sends buffered events in order until one fails. @returns number sent. */
    flush() {
        let sent = 0;
        while (this.buffer.length > 0) {
            if (!this._send(this.buffer[0])) break;
            this.buffer.shift();
            sent++;
        }
        return sent;
    }

    _send(frame) {
        return Boolean(this.connection && this.connection.send(frame));
    }

    _buffer(frame) {
        this.buffer.push(frame);
        if (this.buffer.length > this.maxBuffer) this.buffer.shift();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-web-extension && npm test`
Expected: all tests pass (28 total).

- [ ] **Step 5: Commit**

```bash
git add extension/src/background/router.js extension/tests/router.test.js
git commit -m "feat(extension): frame router with bounded event buffer

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Page bridge (postMessage transport and RPC dispatch)

**Files:**
- Create: `whatsapp-web-extension/src/page/bridge.js`
- Test: `whatsapp-web-extension/tests/bridge.test.js`

**Interfaces:**
- Consumes: `protocol.js` (`PAGE_SOURCE`, `CONTENT_SOURCE`, `WA_ORIGIN`, `event`, `response`, `errorResponse`, `isEnvelope`, `ErrorCodes`).
- Produces `createBridge({ win, methods, origin = WA_ORIGIN })` → `{ emit(name, payload), post(frame), dispose() }`:
  - listens for `message` events on `win` whose `data` is `{ source: CONTENT_SOURCE, frame }` with `frame.type === 'request'`
  - looks up `methods.get(frame.method)`, awaits it with `frame.params ?? {}`, posts `{ source: PAGE_SOURCE, frame: response(...) }` to `win` with `origin`
  - errors: missing method → `METHOD_NOT_FOUND`; thrown error → `err.code ?? PAGE_ERROR` with `err.message`
  - `emit(name, payload)` posts an `event` envelope the same way

- [ ] **Step 1: Write the failing tests**

`whatsapp-web-extension/tests/bridge.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../src/page/bridge.js';
import { PAGE_SOURCE, CONTENT_SOURCE } from '../src/shared/protocol.js';
import { flush } from './helpers.js';

function fakeWindow() {
    const win = new EventTarget();
    win.posted = [];
    win.postMessage = (data, origin) => win.posted.push({ data, origin });
    win.deliver = (data, source = win) => {
        const ev = new Event('message');
        ev.data = data;
        ev.source = source;
        win.dispatchEvent(ev);
    };
    return win;
}

const fromContent = (frame) => ({ source: CONTENT_SOURCE, frame });

test('dispatches a request to the registered method and posts the response', async () => {
    const win = fakeWindow();
    const methods = new Map([['add', async ({ a, b }) => a + b]]);
    createBridge({ win, methods, origin: 'https://web.whatsapp.com' });

    win.deliver(fromContent({ type: 'request', id: 'r1', method: 'add', params: { a: 2, b: 3 } }));
    await flush();

    assert.equal(win.posted.length, 1);
    assert.equal(win.posted[0].origin, 'https://web.whatsapp.com');
    assert.deepEqual(win.posted[0].data, { source: PAGE_SOURCE, frame: { type: 'response', id: 'r1', result: 5 } });
});

test('unknown method → METHOD_NOT_FOUND', async () => {
    const win = fakeWindow();
    createBridge({ win, methods: new Map() });
    win.deliver(fromContent({ type: 'request', id: 'r1', method: 'nope' }));
    await flush();
    assert.equal(win.posted[0].data.frame.error.code, 'METHOD_NOT_FOUND');
    assert.equal(win.posted[0].data.frame.id, 'r1');
});

test('thrown errors carry their code, default PAGE_ERROR', async () => {
    const win = fakeWindow();
    const methods = new Map([
        ['coded', async () => { const e = new Error('no chat'); e.code = 'CHAT_NOT_FOUND'; throw e; }],
        ['plain', async () => { throw new Error('kaboom'); }],
    ]);
    createBridge({ win, methods });
    win.deliver(fromContent({ type: 'request', id: '1', method: 'coded' }));
    win.deliver(fromContent({ type: 'request', id: '2', method: 'plain' }));
    await flush();
    const byId = Object.fromEntries(win.posted.map((p) => [p.data.frame.id, p.data.frame.error]));
    assert.deepEqual(byId['1'], { code: 'CHAT_NOT_FOUND', message: 'no chat' });
    assert.deepEqual(byId['2'], { code: 'PAGE_ERROR', message: 'kaboom' });
});

test('undefined results are posted as null; missing params become {}', async () => {
    const win = fakeWindow();
    let seen;
    const methods = new Map([['noop', async (params) => { seen = params; }]]);
    createBridge({ win, methods });
    win.deliver(fromContent({ type: 'request', id: '1', method: 'noop' }));
    await flush();
    assert.deepEqual(seen, {});
    assert.equal(win.posted[0].data.frame.result, null);
});

test('ignores messages from other sources, other windows, and non-request frames', async () => {
    const win = fakeWindow();
    const methods = new Map([['x', async () => 1]]);
    createBridge({ win, methods });
    win.deliver({ source: 'someone-else', frame: { type: 'request', id: '1', method: 'x' } });
    win.deliver(fromContent({ type: 'event', name: 'x' }));
    win.deliver(fromContent({ type: 'request', id: '2', method: 'x' }), {});
    win.deliver('junk');
    await flush();
    assert.equal(win.posted.length, 0);
});

test('emit posts an event envelope; dispose stops listening', async () => {
    const win = fakeWindow();
    const bridge = createBridge({ win, methods: new Map([['x', async () => 1]]) });
    bridge.emit('message', { body: 'hi' });
    assert.deepEqual(win.posted[0].data, { source: PAGE_SOURCE, frame: { type: 'event', name: 'message', payload: { body: 'hi' } } });

    bridge.dispose();
    win.deliver(fromContent({ type: 'request', id: '1', method: 'x' }));
    await flush();
    assert.equal(win.posted.length, 1);
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-web-extension && npm test`
Expected: `bridge.test.js` fails with module not found.

- [ ] **Step 3: Implement bridge.js**

`whatsapp-web-extension/src/page/bridge.js`:

```js
// Page-side transport: talks to the content script over window.postMessage and dispatches RPC requests.
import {
    PAGE_SOURCE, CONTENT_SOURCE, WA_ORIGIN, event, response, errorResponse, isEnvelope, ErrorCodes,
} from '../shared/protocol.js';

/**
 * @param {object} opts
 * @param {Window} opts.win            window to listen on / post to
 * @param {Map<string, Function>} opts.methods  method name → async (params) => result
 * @param {string} [opts.origin]       target origin for postMessage
 */
export function createBridge({ win, methods, origin = WA_ORIGIN }) {
    const post = (frame) => win.postMessage({ source: PAGE_SOURCE, frame }, origin);
    const emit = (name, payload) => post(event(name, payload));

    async function handle(frame) {
        const fn = methods.get(frame.method);
        if (!fn) {
            post(errorResponse(frame.id, ErrorCodes.METHOD_NOT_FOUND, `unknown method: ${frame.method}`));
            return;
        }
        try {
            const result = await fn(frame.params ?? {});
            post(response(frame.id, result === undefined ? null : result));
        } catch (err) {
            post(errorResponse(frame.id, err?.code || ErrorCodes.PAGE_ERROR, err?.message || String(err)));
        }
    }

    const listener = (ev) => {
        if (ev.source !== win) return;
        const data = ev.data;
        if (!data || typeof data !== 'object' || data.source !== CONTENT_SOURCE) return;
        if (!isEnvelope(data.frame) || data.frame.type !== 'request') return;
        handle(data.frame);
    };

    win.addEventListener('message', listener);

    return {
        emit,
        post,
        dispose: () => win.removeEventListener('message', listener),
    };
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-web-extension && npm test`
Expected: all tests pass (34 total).

- [ ] **Step 5: Commit**

```bash
git add extension/src/page/bridge.js extension/tests/bridge.test.js
git commit -m "feat(extension): page bridge for postMessage RPC dispatch

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Page methods, WhatsApp event listeners, and page entry point

These modules touch live WhatsApp internals (`window.require`, `window.WWebJS`) and are verified manually in Task 8; the pure parts (bridge, protocol) are already tested.

**Files:**
- Create: `whatsapp-web-extension/src/page/methods.js`, `whatsapp-web-extension/src/page/events.js`, `whatsapp-web-extension/src/page/inject.js`

**Interfaces:**
- Consumes: `LoadUtils` from `src/util/Injected/Utils.js`; `createBridge`; `Events`, `ErrorCodes`.
- Produces:
  - `createMethods({ getInfo })` → `Map` with `sendMessage`, `getState`, `getInfo`
  - `attachEvents(emit)` → `detach()` function; emits `message_create`, `message`, `message_ack {message, ack}`, `change_state {state}`
  - `window.__wabridge = { ready, info, announce() }` set by `inject.js`; re-running the bundle calls `announce()` (re-emits `ready`) instead of initializing twice.
  - `ready` payload: `{ ...Conn.serialize(), wid, waVersion, state }`

- [ ] **Step 1: Implement methods.js**

`whatsapp-web-extension/src/page/methods.js`:

```js
// RPC methods executed inside the WhatsApp Web page (MAIN world).
// Bodies mirror the page.evaluate closures in src/Client.js so behaviour matches whatsapp-web.js.
import { ErrorCodes } from '../shared/protocol.js';

function codedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

/**
 * @param {{ getInfo: () => object | null }} deps
 * @returns {Map<string, (params: object) => Promise<any>>}
 */
export function createMethods({ getInfo }) {
    const methods = new Map();

    methods.set('getState', async () => window.require('WAWebSocketModel').Socket.state);

    methods.set('getInfo', async () => getInfo());

    // Mirrors Client.sendMessage's page.evaluate body (src/Client.js:1593-1611).
    // `options` is passed through as whatsapp-web.js "internalOptions"
    // (linkPreview, quotedMessageId, mentionedJidList, ...). Text only for the PoC.
    methods.set('sendMessage', async ({ chatId, content, options = {}, sendSeen = false }) => {
        if (typeof window.WWebJS === 'undefined') {
            throw codedError(ErrorCodes.WHATSAPP_NOT_READY, 'WWebJS helpers are not loaded yet');
        }
        if (!chatId || typeof content !== 'string') {
            throw codedError(ErrorCodes.PAGE_ERROR, 'chatId and string content are required');
        }
        const chat = await window.WWebJS.getChat(chatId, { getAsModel: false });
        if (!chat) throw codedError(ErrorCodes.CHAT_NOT_FOUND, `Chat not found: ${chatId}`);

        if (sendSeen) await window.WWebJS.sendSeen(chatId);

        const msg = await window.WWebJS.sendMessage(chat, content, options);
        return msg ? window.WWebJS.getMessageModel(msg) : null;
    });

    return methods;
}
```

- [ ] **Step 2: Implement events.js**

`whatsapp-web-extension/src/page/events.js`:

```js
// Subscribes to WhatsApp's Backbone collections and forwards a PoC subset of the
// whatsapp-web.js events. Ported from the listener block in src/Client.js:1080-1288,
// with each `window.onXxxEvent(...)` replaced by `emit(name, payload)`.
import { Events } from '../shared/protocol.js';

/**
 * @param {(name: string, payload: any) => void} emit
 * @returns {() => void} detach function that removes every listener added here
 */
export function attachEvents(emit) {
    const { Msg } = window.require('WAWebCollections');
    const AppState = window.require('WAWebSocketModel').Socket;
    const model = (msg) => window.WWebJS.getMessageModel(msg);

    const registered = [];
    const on = (target, name, handler) => {
        target.on(name, handler);
        registered.push([target, name, handler]);
    };

    on(Msg, 'add', (msg) => {
        if (!msg.isNewMsg) return;
        // Ciphertext recovery (placeholder resend) is part of the event-parity phase, not the PoC.
        if (msg.type === 'ciphertext') return;
        const m = model(msg);
        emit(Events.MESSAGE_CREATE, m);
        if (!m.id.fromMe) emit(Events.MESSAGE_RECEIVED, m);
    });

    on(Msg, 'change:ack', (msg, ack) => {
        emit(Events.MESSAGE_ACK, { message: model(msg), ack });
    });

    on(AppState, 'change:state', (_appState, state) => {
        emit(Events.STATE_CHANGED, { state });
    });

    // Same cleanup pattern as src/Client.js:426-434 so re-injection never doubles listeners.
    window._wwjsListeners = registered;

    return () => {
        for (const [target, name, handler] of registered) target.off(name, handler);
        registered.length = 0;
        window._wwjsListeners = [];
    };
}
```

- [ ] **Step 3: Implement inject.js**

`whatsapp-web-extension/src/page/inject.js`:

```js
// Page entry (MAIN world). Mirrors src/Client.js inject() without Puppeteer:
// wait for the WhatsApp bundle, wait until the session has synced, load WWebJS helpers,
// then wire events and RPC methods over the postMessage bridge.
import { LoadUtils } from '../../../whatsapp-web.js/src/util/Injected/Utils.js';
import { Events } from '../shared/protocol.js';
import { createBridge } from './bridge.js';
import { createMethods } from './methods.js';
import { attachEvents } from './events.js';

const POLL_MS = 500;
const REINJECT_CHECK_MS = 2000;
const TAG = '[wabridge]';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate) {
    for (;;) {
        try {
            if (predicate()) return;
        } catch {
            // WhatsApp modules may not be registered yet; keep polling.
        }
        await sleep(POLL_MS);
    }
}

const socket = () => window.require('WAWebSocketModel').Socket;

// Same info Client.js builds at src/Client.js:339-355.
function buildInfo() {
    const me = window.require('WAWebUserPrefsMeUser');
    return {
        ...window.require('WAWebConnModel').Conn.serialize(),
        wid: me.getMaybeMePnUser() || me.getMaybeMeLidUser(),
        waVersion: window.Debug.VERSION,
    };
}

async function main() {
    if (window.__wabridge) {
        // The service worker re-injects on every (re)connect; just re-announce readiness.
        window.__wabridge.announce();
        return;
    }

    const ctl = {
        ready: false,
        info: null,
        emit: null,
        announce() {
            if (this.ready && this.emit) this.emit(Events.READY, { ...this.info, state: socket().state });
        },
    };
    window.__wabridge = ctl;

    const bridge = createBridge({ win: window, methods: createMethods({ getInfo: () => ctl.info }) });
    ctl.emit = bridge.emit;

    await waitFor(() => window.Debug?.VERSION != undefined);
    await waitFor(() => socket().hasSynced === true);

    let detach = setup();

    // WhatsApp's SPA can reset page globals; if our helpers vanish, re-load and re-subscribe
    // (equivalent of Client.js's framenavigated re-inject).
    setInterval(() => {
        if (typeof window.WWebJS === 'undefined') {
            console.info(TAG, 'WWebJS missing, re-injecting');
            detach();
            detach = setup();
        }
    }, REINJECT_CHECK_MS);

    function setup() {
        if (typeof window.WWebJS === 'undefined') LoadUtils();
        ctl.info = buildInfo();
        const off = attachEvents(bridge.emit);
        ctl.ready = true;
        ctl.announce();
        console.info(TAG, 'ready', ctl.info.wid?._serialized, window.Debug.VERSION);
        return off;
    }
}

main().catch((err) => console.error(TAG, 'initialisation failed', err));
```

- [ ] **Step 4: Lint**

Run: `cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web.js && npx eslint extension`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add extension/src/page
git commit -m "feat(extension): page methods, WhatsApp event listeners and MAIN-world entry

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Content relay, storage, service worker, popup

**Files:**
- Create: `whatsapp-web-extension/src/content/relay.js`, `whatsapp-web-extension/src/background/storage.js`, `whatsapp-web-extension/src/background/index.js`, `whatsapp-web-extension/src/popup/popup.js`

**Interfaces:**
- Consumes: `Connection`, `States`, `AuthError`, `Router`, protocol constants.
- Produces:
  - Runtime messages handled by the worker: `{type:'connect', settings:{serverUrl, username, password}}` → `{ok:true}`; `{type:'disconnect'}` → `{ok:true}`; `{type:'status'}` → `{connection, detail, waState, username, hasTab}`.
  - `chrome.storage.local` keys: `settings` `{serverUrl, username, password}`, `status` `{connection, detail, waState, username, updatedAt}`.
  - Port name `wabridge-tab` from the content script; frames flow raw (envelope objects) over the port.

- [ ] **Step 1: Implement relay.js**

`whatsapp-web-extension/src/content/relay.js`:

```js
// Content script (ISOLATED world). Pure relay between the page script and the service worker.
import { PAGE_SOURCE, CONTENT_SOURCE, WA_ORIGIN, TAB_PORT_NAME, isEnvelope } from '../shared/protocol.js';

const RECONNECT_MS = 1000;
let port = null;

function connectPort() {
    port = chrome.runtime.connect({ name: TAB_PORT_NAME });
    port.onMessage.addListener((frame) => {
        window.postMessage({ source: CONTENT_SOURCE, frame }, WA_ORIGIN);
    });
    port.onDisconnect.addListener(() => {
        // The MV3 worker was suspended or the extension reloaded; reconnect and let the worker re-inject.
        port = null;
        setTimeout(connectPort, RECONNECT_MS);
    });
}

window.addEventListener('message', (ev) => {
    if (ev.source !== window || ev.origin !== WA_ORIGIN) return;
    const data = ev.data;
    if (!data || typeof data !== 'object' || data.source !== PAGE_SOURCE || !isEnvelope(data.frame)) return;
    if (!port) return; // worker is restarting; page events are lost until it is back (bounded PoC gap)
    try {
        port.postMessage(data.frame);
    } catch {
        // port is closing; onDisconnect will reconnect
    }
});

connectPort();
```

- [ ] **Step 2: Implement storage.js**

`whatsapp-web-extension/src/background/storage.js`:

```js
// Thin wrappers around chrome.storage.local so the rest of the worker stays testable.
const area = () => chrome.storage.local;

export async function loadSettings() {
    const { settings } = await area().get('settings');
    return settings ?? null;
}

export async function saveSettings(settings) {
    await area().set({ settings });
}

export async function getStatus() {
    const { status } = await area().get('status');
    return status ?? { connection: 'stopped', waState: 'UNKNOWN' };
}

export async function setStatus(status) {
    await area().set({ status: { ...status, updatedAt: Date.now() } });
}
```

- [ ] **Step 3: Implement the service worker**

`whatsapp-web-extension/src/background/index.js`:

```js
// MV3 service worker: owns credentials, login, the WebSocket, and routing to the WhatsApp tab.
import { Connection, States, AuthError } from './connection.js';
import { Router } from './router.js';
import { loadSettings, saveSettings, setStatus } from './storage.js';
import { Events, TAB_PORT_NAME } from '../shared/protocol.js';

const TAG = '[wabridge]';
const router = new Router();
let connection = null;
let settings = null;
let wa = { state: 'UNKNOWN', info: null };
let lastDetail = '';

async function login({ serverUrl, username, password }) {
    const res = await fetch(new URL('/api/auth/login', serverUrl), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    });
    if (res.status === 401) throw new AuthError('invalid username or password');
    if (!res.ok) throw new Error(`login failed: HTTP ${res.status}`);
    const body = await res.json();
    return body.token;
}

function wsUrl(serverUrl) {
    const url = new URL('/ws', serverUrl);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    return url.toString();
}

function publishStatus() {
    return setStatus({
        connection: connection?.state ?? States.STOPPED,
        detail: lastDetail,
        waState: wa.state,
        username: settings?.username ?? null,
    });
}

async function connect(newSettings) {
    await disconnect();
    settings = newSettings;
    await saveSettings(settings);

    connection = new Connection({
        url: wsUrl(settings.serverUrl),
        authenticate: () => login(settings),
        helloParams: () => ({ waState: wa.state, info: wa.info }),
        onFrame: (frame) => router.fromServer(frame),
        onStateChange: (state, detail) => {
            lastDetail = detail ?? '';
            console.info(TAG, 'connection', state, detail ?? '');
            if (state === States.CONNECTED) {
                const flushed = router.flush();
                if (flushed) console.info(TAG, `flushed ${flushed} buffered events`);
            }
            publishStatus();
        },
    });
    router.setConnection(connection);
    await connection.start();
}

async function disconnect() {
    if (connection) {
        connection.stop();
        connection = null;
        router.setConnection(null);
    }
    lastDetail = '';
    await publishStatus();
}

function trackWaState(frame) {
    if (frame?.type !== 'event') return;
    if (frame.name === Events.READY) {
        wa = { state: 'CONNECTED', info: frame.payload ?? null };
    } else if (frame.name === Events.STATE_CHANGED) {
        wa = { ...wa, state: frame.payload?.state ?? 'UNKNOWN' };
    } else {
        return;
    }
    publishStatus();
}

// ---- popup ↔ worker -------------------------------------------------------

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    (async () => {
        switch (msg?.type) {
            case 'connect':
                await connect(msg.settings);
                return { ok: true };
            case 'disconnect':
                await disconnect();
                return { ok: true };
            case 'status':
                return {
                    connection: connection?.state ?? States.STOPPED,
                    detail: lastDetail,
                    waState: wa.state,
                    username: settings?.username ?? null,
                    hasTab: router.hasTab(),
                };
            default:
                return { error: `unknown message type: ${msg?.type}` };
        }
    })().then(sendResponse, (err) => sendResponse({ error: err.message }));
    return true; // keep the channel open for the async response
});

// ---- WhatsApp tab ↔ worker ------------------------------------------------

chrome.runtime.onConnect.addListener((port) => {
    if (port.name !== TAB_PORT_NAME) return;
    const tabId = port.sender?.tab?.id;
    if (tabId === undefined) return;

    const tab = { postMessage: (frame) => port.postMessage(frame) };
    router.attachTab(tab);
    port.onMessage.addListener((frame) => {
        trackWaState(frame);
        router.fromPage(frame);
    });
    port.onDisconnect.addListener(() => {
        router.detachTab(tab);
        wa = { state: 'UNKNOWN', info: null };
        publishStatus();
    });

    // Inject (or re-announce) the MAIN-world page script. inject.js is idempotent.
    chrome.scripting
        .executeScript({ target: { tabId }, world: 'MAIN', files: ['dist/page.js'] })
        .catch((err) => console.warn(TAG, 'page injection failed', err));
});

// ---- boot -----------------------------------------------------------------

loadSettings()
    .then((saved) => (saved ? connect(saved) : publishStatus()))
    .catch((err) => console.error(TAG, 'boot failed', err));
```

- [ ] **Step 4: Implement the popup**

`whatsapp-web-extension/src/popup/popup.js`:

```js
import { loadSettings, getStatus } from '../background/storage.js';

const $ = (id) => document.getElementById(id);

function render(status) {
    const s = status ?? {};
    const lines = [
        `Server: ${s.connection ?? 'stopped'}${s.detail ? ` (${s.detail})` : ''}`,
        `WhatsApp: ${s.waState ?? 'UNKNOWN'}`,
    ];
    if (s.username) lines.push(`User: ${s.username}`);
    $('status').textContent = lines.join('\n');
}

async function init() {
    const settings = await loadSettings();
    $('serverUrl').value = settings?.serverUrl ?? 'http://localhost:8080';
    $('username').value = settings?.username ?? '';
    $('password').value = settings?.password ?? '';

    render(await chrome.runtime.sendMessage({ type: 'status' }).catch(() => getStatus()));

    chrome.storage.onChanged.addListener((changes) => {
        if (changes.status) render(changes.status.newValue);
    });

    $('connect').onclick = async () => {
        const next = {
            serverUrl: $('serverUrl').value.trim().replace(/\/+$/, ''),
            username: $('username').value.trim(),
            password: $('password').value,
        };
        if (!next.serverUrl || !next.username || !next.password) {
            $('status').textContent = 'Server URL, username and password are required.';
            return;
        }
        $('status').textContent = 'Connecting…';
        const reply = await chrome.runtime.sendMessage({ type: 'connect', settings: next });
        if (reply?.error) $('status').textContent = `Error: ${reply.error}`;
    };

    $('disconnect').onclick = () => chrome.runtime.sendMessage({ type: 'disconnect' });
}

init();
```

- [ ] **Step 5: Build and lint**

Run: `cd whatsapp-web-extension && npm run build`
Expected: four bundles in `whatsapp-web-extension/dist/` (`page.js`, `content.js`, `background.js`, `popup.js`), no errors. `dist/page.js` must contain the string `window.WWebJS = {}` (proves `Utils.js` was bundled): `grep -c "window.WWebJS = {}" dist/page.js` → `1`.

Run: `cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web.js && npx eslint extension && npm test --prefix extension`
Expected: lint clean, all extension tests pass.

- [ ] **Step 6: Commit**

```bash
git add extension/src
git commit -m "feat(extension): content relay, service worker wiring, storage and popup

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Extension README

**Files:**
- Create: `whatsapp-web-extension/README.md`

- [ ] **Step 1: Write the README**

`whatsapp-web-extension/README.md`:

````markdown
# WhatsApp Bridge extension

Chrome (MV3) extension that runs on your own WhatsApp Web tab and bridges it to the
`whatsapp-backend/` Spring Boot backend. Design: `docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md`.

## Build

```bash
cd whatsapp-web-extension
npm install
npm run build        # or: npm run watch
npm test
```

## Install (unpacked)

1. Open `chrome://extensions`, enable **Developer mode**, click **Load unpacked**, select the `whatsapp-web-extension/` folder.
2. Start the server (`cd whatsapp-backend && ./mvnw spring-boot:run`).
3. Open https://web.whatsapp.com and link your phone if needed.
4. Click the extension icon, enter server URL (`http://localhost:8080`), username and password, click **Connect**.
   The status box shows `Server: connected` and `WhatsApp: CONNECTED` once the page reports ready.

If your server is not on `localhost:8080`, add its origin to `host_permissions` in `manifest.json`
and reload the extension.

## How it works

- `dist/page.js` runs in the page's MAIN world, loads the whatsapp-web.js helpers
  (`src/util/Injected/Utils.js`) and subscribes to WhatsApp's internal collections.
- `dist/content.js` relays `window.postMessage` frames to the service worker over a runtime port.
- `dist/background.js` logs in, keeps the WebSocket to `/ws` alive (ping every 20 s, exponential
  reconnect up to 30 s), buffers up to 500 events while offline, and routes server requests to the tab.

Credentials are stored in `chrome.storage.local` so the worker can log in again after a restart
(PoC trade-off; refresh tokens are on the roadmap).

## Debugging

- Service worker logs: `chrome://extensions` → WhatsApp Bridge → **service worker** link.
- Page script logs: DevTools on the WhatsApp tab, filter `[wabridge]`.
- Server logs show `ready`, `message`, `message_ack` lines per user.
````

- [ ] **Step 2: Commit**

```bash
git add extension/README.md
git commit -m "docs(extension): add build, install and debugging guide

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Manual end-to-end verification (requires the server plan to be complete)

**Files:** none created. Fixes discovered here go into the module that owns them, each with its own commit.

- [ ] **Step 1: Start the server**

Run: `cd whatsapp-backend && ./mvnw -q spring-boot:run`
Expected: Tomcat on 8080, no errors.

- [ ] **Step 2: Build and load the extension**

Run: `cd whatsapp-web-extension && npm run build`, then load `whatsapp-web-extension/` unpacked in Chrome (see README). Open the service worker console from `chrome://extensions`.

- [ ] **Step 3: Connect**

Open https://web.whatsapp.com (link the phone if needed). Open the popup, enter `http://localhost:8080`, `demo`, `demo123`, click Connect.
Expected: worker console logs `connection connecting`, `authenticating`, `connected`; popup shows `Server: connected`. Server log shows `[demo] device connected`.

- [ ] **Step 4: Page readiness**

Expected in the WhatsApp tab console: `[wabridge] ready <your wid> <version>`. Server log: `[demo] ready wid=... pushname=... waVersion=...`. Popup: `WhatsApp: CONNECTED`.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"demo123"}' | jq -r .token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status
```
Expected: `{"deviceConnected":true,"waState":"CONNECTED","info":{...}}`.

- [ ] **Step 5: Incoming message**

Send a text to the linked number from another phone.
Expected server log: `[demo] message id=... chat=<sender>@c.us from=... type=chat body=<text>` followed by a `message_create` line with the same id.

- [ ] **Step 6: Outgoing message**

```bash
curl -s -X POST localhost:8080/api/messages -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"<sender number>@c.us","content":"hello from server"}'
```
Expected: HTTP 200 with the message DTO (`id._serialized` starting `true_`); message visible in WhatsApp; server logs `message_create` and then `message_ack ... ack=1`, later `ack=2`/`3`.

- [ ] **Step 7: Error paths**

```bash
curl -s -i -X POST localhost:8080/api/messages -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"chatId":"000@c.us","content":"x"}'
```
Expected: 400 with `code` `CHAT_NOT_FOUND` or `PAGE_ERROR` (WhatsApp may reject the id at different layers; either is acceptable, but the call must not hang).

- [ ] **Step 8: Reconnect**

Stop the server. Expected: popup shows `Server: waiting (…; retry in Ns)` with growing N. Restart the server. Expected: popup returns to `connected` within 30 s, server logs `device connected`, and `GET /api/status` shows `waState: CONNECTED` again (the worker's `hello` carries the last known state).

- [ ] **Step 9: Replacement**

Open a second Chrome profile with the same extension and credentials and click Connect. Expected: the first profile's popup shows `Server: replaced`, and it does not reconnect on its own.

- [ ] **Step 10: Record results**

Append a short "Verified on <date>, WhatsApp Web <version>, Chrome <version>" note to `whatsapp-web-extension/README.md` and commit:

```bash
git add extension/README.md
git commit -m "docs(extension): record manual end-to-end verification

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- Spec coverage: MAIN-world injection via `chrome.scripting.executeScript` (T6), `LoadUtils` reuse without copying (T5), event subset `ready/message/message_create/message_ack/change_state` (T5), method registry with `sendMessage/getState/getInfo` and error codes (T4, T5), service worker owning credentials + JWT login + WebSocket + 20 s ping + backoff to 30 s + `auth_failed` on 401 (T2, T6), 500-event buffer and `WHATSAPP_NOT_READY` reply (T3), popup fields and status (T1, T6), re-injection guard using `window._wwjsListeners` (T5), 4401/4409 handling (T2), manual acceptance checklist (T8).
- Type consistency: `Connection.send` returns boolean and is what `Router._send` relies on; `States` values are the strings the popup renders; `TAB_PORT_NAME`, `PAGE_SOURCE`, `CONTENT_SOURCE` are used identically in relay, bridge, and worker; `helloParams` shape `{waState, info}` matches the server's `hello` handler (`params.waState`, `params.info`).
- Known PoC gaps, intentionally deferred per spec: page events emitted while the worker port is down are lost (relay Task 6 comment); animated stickers, media, group notifications, ciphertext recovery belong to the parity phase.
