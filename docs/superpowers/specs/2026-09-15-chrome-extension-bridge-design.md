# Design: Chrome Extension + Spring Boot Bridge for WhatsApp Web

Status: approved 2026-09-15. Supersedes nothing; this is a new subsystem alongside the Puppeteer-based client.


## Context

whatsapp-web.js drives WhatsApp Web through Puppeteer: it launches Chromium server-side, injects
`window.WWebJS` helpers (`src/util/Injected/Utils.js`), subscribes to WhatsApp's internal Backbone
collections, and bridges events back to Node via `page.exposeFunction`. This needs a browser per
session and owns QR provisioning.

The new architecture removes Puppeteer:

1. The user opens official WhatsApp Web in their own Chrome and links their phone as normal.
2. A Chrome extension activates on `web.whatsapp.com`; the user logs into a Spring Boot backend
   with username/password from the extension popup.
3. The extension forwards incoming WhatsApp messages to the backend over WebSocket.
4. The backend sends WhatsApp messages through the extension (REST → WS → page → WhatsApp).

Phase 1 is a proof of concept (text in/out, connection status). Later phases port the full
whatsapp-web.js feature set onto the same bridge.

### Key finding from exploration

`window.Store` no longer exists in this repo (removed in `883d7e4`). Every WhatsApp module is
accessed inline via the page's own global `window.require('WAWeb…')`. `src/util/Injected/Utils.js`
(`exports.LoadUtils = () => { window.WWebJS = {} … }`) is 100% pure in-page JS and can be loaded
verbatim in the page's MAIN world. The only Puppeteer-specific parts of `src/Client.js` are:

- the 23 `page.exposeFunction` bridges (`window.onAddMessageEvent`, `onMessageAckEvent`, …)
- `page.evaluate` argument marshalling (which is why `getMessageModel`/`getChatModel` DTOs exist)
- browser lifecycle, auth strategies, web-version pinning, QR/pairing flows (all unnecessary here)
- animated sticker conversion via ffmpeg (`src/util/Util.js`), out of scope

So the port is: run `LoadUtils()` in-page, replace each `window.onXxxEvent(...)` with a
`postMessage` emitter, and expose the existing `page.evaluate` closure bodies as RPC methods.

## Confirmed decisions

| Topic | Decision |
|---|---|
| Backend | New Spring Boot project, created from scratch |
| Tenancy | Many users; each has their own backend account and their own WhatsApp session |
| Server API | REST endpoints + logging for PoC; no DB; in-memory registries |
| Repo layout | New `whatsapp-web-extension/` and `whatsapp-backend/` folders in this repo; extension imports `src/util/Injected/Utils.js` directly |
| Java stack | Java 21, Spring Boot 3.x, Maven wrapper |
| Extension arch | MV3 service worker owns the WebSocket; content script is a relay; page script in MAIN world |
| Transport | Plain WebSocket, JSON envelope, no STOMP; first-frame JWT auth |

## Architecture

```
web.whatsapp.com tab                          Chrome extension                 Spring Boot
┌──────────────────────┐  postMessage  ┌───────────────┐  runtime port  ┌────────────────┐   WS   ┌──────────────┐  REST  ┌────────┐
│ page script (MAIN)   │◄────────────►│ content script │◄─────────────►│ service worker │◄─────►│ BridgeWsHandler│◄─────►│ caller │
│ LoadUtils + events   │              │ (relay only)   │               │ WS + auth      │       │ RpcDispatcher  │       │ (curl) │
│ + method registry    │              └───────────────┘               └──────┬─────────┘       │ DeviceRegistry │       └────────┘
└──────────────────────┘                                                     │ popup           │ REST /api/*    │
                                                                             ▼                 └──────────────┘
                                                                        chrome.storage
```

Incoming: `Msg.on('add')` → page `emit('message', getMessageModel(msg))` → content → worker →
WS `event` frame → server publishes `WhatsAppMessageEvent` → `LoggingEventListener` logs it.

Outgoing: `POST /api/messages` (bearer JWT) → `RpcDispatcher.call(user, "sendMessage", params)` →
WS `request` frame → worker → content → page registry → `WWebJS.getChat` + `WWebJS.sendMessage` +
`getMessageModel` → `response` frame → future completes → REST returns message DTO.

## Wire protocol

One envelope, both directions:

```json
{ "type": "auth",     "token": "<jwt>" }
{ "type": "request",  "id": "uuid", "method": "sendMessage", "params": { "chatId": "...", "content": "hi", "options": {} } }
{ "type": "response", "id": "uuid", "result": { } }
{ "type": "response", "id": "uuid", "error": { "code": "CHAT_NOT_FOUND", "message": "..." } }
{ "type": "event",    "name": "message", "payload": { } }
{ "type": "ping" }  /  { "type": "pong" }
```

- Either side may issue `request`s. Extension → server methods for PoC: `hello`
  (`{ waState, waVersion, wid, pushname }`). Server → page methods for PoC: `sendMessage`,
  `getState`, `getInfo`.
- Event names reuse `src/util/Constants.js` `Events` values (`message`, `message_create`,
  `message_ack`, `change_state`, `ready`, `disconnected`). PoC emits `ready`, `message`,
  `message_create`, `message_ack`, `change_state`.
- Payloads are exactly the existing DTOs (`WWebJS.getMessageModel`, `getChatModel`, `getContactModel`).
- Auth: first frame must be `auth` within 5 s or the server closes with `4401`. A second device
  for the same user replaces the first (old socket closed with `4409`). Token never appears in the URL.
- Same JWT authorizes REST calls; `POST /api/auth/login` issues it.

## Repository layout

```
whatsapp-web-extension/
  manifest.json
  package.json              # esbuild only
  build.mjs                 # bundles page/content/background/popup into extension/dist/
  src/
    shared/protocol.js      # envelope constructors, type guards, PAGE_SOURCE marker
    page/
      inject.js             # entry: wait for Debug.VERSION + Socket.hasSynced, LoadUtils(), emit ready
      events.js             # ported listener block from src/Client.js:1080-1288 (PoC subset)
      methods.js            # Map<name, async fn(params)> of ported page.evaluate bodies
      bridge.js             # postMessage transport; dispatches request → methods, sends response/event
    content/relay.js        # window.postMessage <-> chrome.runtime.Port relay, tells worker "tab ready"
    background/
      index.js              # wires everything; handles chrome.runtime.onConnect / onMessage
      connection.js         # WebSocket state machine (idle→authenticating→connected), backoff, ping 20s
      router.js             # routes frames between the WA tab port and the socket; bounded event buffer
      storage.js            # chrome.storage.local: serverUrl, username, password, token
    popup/
      popup.html, popup.js  # login form + status
  README.md                 # load-unpacked instructions

whatsapp-backend/
  pom.xml, mvnw, .mvn/
  src/main/java/me/nageshk/whatsapp/
    WabridgeApplication.java
    config/SecurityConfig.java        # stateless, JwtAuthFilter, permit /api/auth/login and /ws
    config/WebSocketConfig.java       # registers BridgeWebSocketHandler at /ws, allowed origins
    auth/AuthController.java          # POST /api/auth/login
    auth/JwtService.java              # issue/verify (jjwt)
    auth/JwtAuthFilter.java
    auth/UsersProperties.java         # users from application.yml (username, bcrypt hash)
    ws/BridgeWebSocketHandler.java    # auth timer, frame parsing, registry, delegates to dispatcher
    ws/DeviceRegistry.java            # ConcurrentHashMap<username, DeviceSession>
    ws/DeviceSession.java             # session + waState + info
    rpc/Envelope.java                 # record types for the protocol (Jackson)
    rpc/RpcDispatcher.java            # pending futures, 30 s timeout, request/response matching
    rpc/RpcException.java             # carries code/message from page error → HTTP mapping
    events/WhatsAppEvent.java         # ApplicationEvent(username, name, payload)
    events/LoggingEventListener.java
    api/MessagesController.java       # POST /api/messages
    api/StatusController.java         # GET /api/status
    api/ApiExceptionHandler.java      # RpcException→400/502, NoDeviceException→409
  src/main/resources/application.yml
  src/test/java/...                   # see Verification
```

Root changes: add `whatsapp-web-extension/dist/`, `whatsapp-web-extension/node_modules/`, `whatsapp-backend/target/` to `.gitignore`;
add `whatsapp-web-extension/dist` and `server` to `eslint.config.mjs` ignores so the existing `npm run check`
is unaffected. `docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md` receives this
design (first task, so the spec is committed alongside the code).

## Extension details

**Manifest (MV3)**: `permissions: ["storage", "scripting"]`,
`host_permissions: ["https://web.whatsapp.com/*", "<server origin from config>"]` (for the PoC use
`http://localhost:8080/*` and `ws://localhost:8080/*`), `background.service_worker`,
`content_scripts` for `https://web.whatsapp.com/*` at `document_idle`, `action.default_popup`.
The worker injects the page bundle with `chrome.scripting.executeScript({ target: {tabId}, world: 'MAIN', files: ['dist/page.js'] })`
when the content script connects.

**page/inject.js** mirrors `src/Client.js:114-160` and `323-365` without Puppeteer:
poll `window.Debug?.VERSION`; poll `window.require('WAWebSocketModel').Socket` for `hasSynced`;
call the bundled `LoadUtils()` (imported from `../../../whatsapp-web.js/src/util/Injected/Utils.js`, esbuild handles
the CommonJS export); build info with `WAWebConnModel.Conn.serialize()` +
`WAWebUserPrefsMeUser.getMaybeMePnUser() || getMaybeMeLidUser()` (as in `Client.js:339-355`); emit
`ready`. Watch for `window.WWebJS` becoming undefined (SPA re-injection) and re-run, reusing the
`window._wwjsListeners` detach pattern from `Client.js:426-434`.

**page/events.js** ports `Client.js:1080-1288`. PoC subset: `Msg.on('add')` (skip `!isNewMsg`,
skip `ciphertext` for now) → `message_create`, plus `message` when `!msg.id.fromMe`;
`Msg.on('change:ack')` → `message_ack`; `AppState.on('change:state')` → `change_state`.
Each `window.onXxxEvent(...)` call becomes `emit(name, payload)`.

**page/methods.js** PoC registry:
- `sendMessage({chatId, content, options, sendSeen})` = body of `Client.js:1593-1611`, using
  `WWebJS.getChat(chatId, {getAsModel:false})`, optional `WWebJS.sendSeen`, `WWebJS.sendMessage`,
  `WWebJS.getMessageModel`. Options are passed through as the `internalOptions` shape documented at
  `Client.js:1515-1533`; PoC only exercises text, `quotedMessageId`, `mentionedJidList`.
- `getState()` = `Socket.state`; `getInfo()` = the ready info object.
Unknown method → `{code:"METHOD_NOT_FOUND"}`; thrown errors → `{code:"PAGE_ERROR", message}`.

**background/connection.js**: connect → send `auth` → wait for server `hello`-ack/`response` → state
`connected`. Backoff 1 s doubling to 30 s cap; `ping` every 20 s (keeps the MV3 worker alive on
Chrome ≥116 and detects dead sockets). Never clears stored credentials on failure; stores `lastError`
for the popup. If a `401` comes back on login, state becomes `auth_failed` and no retry until the
popup re-submits.

**background/router.js**: page `event` → socket; socket `request` → WA tab port; page `response` →
socket. If the socket is down, buffer up to 500 events and flush on reconnect (drop oldest beyond
that). If no WA tab is connected when a server request arrives, reply with
`{code:"WHATSAPP_NOT_READY"}`.

**popup**: fields serverUrl, username, password; buttons Connect / Disconnect; status text driven
by `chrome.runtime.sendMessage({type:'status'})` and updated via `chrome.storage.onChanged`.

## Server details

- **Security**: stateless; `JwtAuthFilter` reads `Authorization: Bearer`; `/api/auth/login` and
  `/ws` are permitted at the HTTP layer (WS authenticates via the first frame). Users from
  `application.yml` (`wabridge.users[]: {username, passwordHash}`), `BCryptPasswordEncoder`.
  JWT secret and TTL (default 12 h) from `application.yml`. Allowed WS origins: `chrome-extension://*`
  for PoC (comment noting to pin the extension ID later).
- **BridgeWebSocketHandler**: on open, schedule 5 s auth timeout. On `auth`, verify JWT, register in
  `DeviceRegistry` (closing any previous session with `4409`), reply `{type:"response", id:"auth", result:{ok:true}}`.
  Route `request` → handle `hello` (store state on `DeviceSession`); `response` → `RpcDispatcher.complete`;
  `event` → publish `WhatsAppEvent`; `ping` → `pong`. On close → unregister and fail pending futures
  with `DEVICE_DISCONNECTED`.
- **RpcDispatcher**: `CompletableFuture<JsonNode> call(String username, String method, Object params)`;
  `ConcurrentHashMap<String, CompletableFuture>`; `orTimeout(30s)`; error responses complete
  exceptionally with `RpcException(code, message)`.
- **REST**: `POST /api/messages {chatId, content, options?}` → 200 with message DTO; 409 when no
  device or WhatsApp state not `CONNECTED`; 400 for `PAGE_ERROR`/`CHAT_NOT_FOUND`; 504 on timeout.
  `GET /api/status` → `{deviceConnected, waState, wid, pushname, waVersion}`.
- **Logging**: `LoggingEventListener` logs `[user] message from=<id> chat=<id> body=<text>` at INFO.

## Implementation order (PoC)

1. Repo scaffolding: `.gitignore`/eslint ignores, spec file under `docs/superpowers/specs/`.
2. Server: Maven project, security + JWT + login, unit tests for `JwtService`.
3. Server: WebSocket handler, `DeviceRegistry`, `RpcDispatcher` with unit tests (matching, timeout,
   disconnect failure), integration test with a Java WS client.
4. Server: REST controllers + exception mapping, integration test end-to-end (fake device).
5. Extension: build setup, manifest, `shared/protocol.js`, `background/connection.js` with a small
   unit test harness (node + `ws` mock) for the state machine and backoff.
6. Extension: `router.js`, `storage.js`, popup.
7. Extension: `content/relay.js`, `page/inject.js`, `page/bridge.js`, `page/methods.js`, `page/events.js`.
8. Manual end-to-end run (see Verification), fix module-name drift if WhatsApp changed anything.

Development follows TDD for the server and for the pure-JS extension modules (protocol,
connection state machine, router). Page-world code is verified manually, since it depends on the
live WhatsApp bundle.

## Verification

Automated:
- `cd whatsapp-backend && ./mvnw test` — JWT unit tests; dispatcher unit tests; `@SpringBootTest(webEnvironment=RANDOM_PORT)`
  that logs in, connects a `StandardWebSocketClient`, sends `auth`, sends a fake `event` and asserts
  `WhatsAppEvent` published; calls `POST /api/messages` and asserts the client receives a `request`
  and that replying with a `response` completes the HTTP call with the same body; asserts `4401` on
  missing auth and `409` when no device.
- `cd whatsapp-web-extension && npm test` — node tests for `protocol.js`, `connection.js` (state transitions,
  backoff schedule, ping cadence with fake timers), `router.js` (buffering and flush).
- `npm run check` at repo root still passes (new folders ignored).

Manual PoC acceptance:
1. `cd whatsapp-backend && ./mvnw spring-boot:run` with a test user in `application.yml`.
2. `cd whatsapp-web-extension && npm run build`; load `whatsapp-web-extension/` unpacked in Chrome.
3. Open `https://web.whatsapp.com`, link phone if needed. Open popup, enter server URL and
   credentials, Connect. Popup shows "connected as <user>, WhatsApp CONNECTED".
4. Server log shows the `ready` event with wid and version.
5. Send a text to the linked number from another phone → server log line with chat id, sender, body.
6. `curl -H "Authorization: Bearer $TOKEN" -d '{"chatId":"<number>@c.us","content":"hello from server"}' -H 'Content-Type: application/json' localhost:8080/api/messages`
   → message appears in WhatsApp; response includes the message id; a `message_ack` event is logged.
7. Kill the server, restart it: extension reconnects within backoff and the popup returns to connected.

## Roadmap after PoC (separate plans)

1. **Event parity**: port the remaining listeners from `Client.js:1080-1288` (revokes, edits,
   media uploaded, reactions and poll votes via `WWebJS.injectToFunction`, group notifications,
   calls, chat archive/unread, ciphertext recovery with placeholder resend).
2. **Method parity**: register all public `Client.js` methods and the `Message`/`Chat`/`Contact`/
   `GroupChat` structure methods as RPC methods; generate Java DTOs and a typed `WhatsAppClient`
   service; media in/out as base64 first, chunked or HTTP upload for large files.
3. **Server hardening**: persistence, multiple devices per user, webhooks, extension-ID-pinned
   origins, refresh tokens.

Known gaps vs whatsapp-web.js: no web-version pinning (mitigate with defensive `require` wrappers
and `waVersion` in `ready`); animated sticker conversion needs ffmpeg (deferred); QR/pairing flows
are intentionally absent because the user links the phone themselves.
