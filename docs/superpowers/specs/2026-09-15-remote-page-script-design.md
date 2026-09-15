# Design: Server-delivered page script for the WhatsApp Bridge extension

Status: approved 2026-09-15. Extends `2026-09-15-chrome-extension-bridge-design.md`. Branch: `feat/remote-scripts`.

## Context

The PoC extension ships four bundles: service worker, content relay, popup, and the MAIN-world page
script (`dist/page.js`, ~68 KB, includes `src/util/Injected/Utils.js`). The page script is the
only part that tracks WhatsApp Web internals and is where every future feature lands, so it will
change often. Today every change requires every user to reload the extension.

Goal: keep the extension a minimal, stable shell and deliver the page script from the Java backend
each time a user connects, so updates ship by redeploying the server.

## Constraints that shaped the design

- Chrome MV3 forbids remotely loaded code in extension contexts (service worker, popup, content
  scripts). Only the page script can be remote. Worker, relay and popup stay in the package.
- WhatsApp Web's CSP is `script-src blob: 'self' 'nonce-…' 'wasm-unsafe-eval' …`: no `unsafe-eval`
  and no inline scripts, but `blob:` is allowed. Fetched code is therefore injected through a
  `blob:` URL `<script>` tag. **The tag must be created from the page's MAIN world**, via a
  bootstrap function passed to `chrome.scripting.executeScript({func, args})`: Chrome (verified on
  152) applies the *extension's* CSP to `<script>` elements inserted by a content script, which
  blocks `blob:` there. If WhatsApp ever removes `blob:`, the fallback is `chrome.userScripts`
  (needs the userScripts permission and a per-user toggle); it is not implemented now.
- Decisions: blob-URL injection; the extension bundles no page script at all; the npm build writes
  the script into the server resources and the generated artifact is committed so Maven builds
  need no Node toolchain.

## Architecture

```
npm run build ──► extension/dist/{background,content,popup}.js          (shell, in the package)
              └─► server/src/main/resources/wabridge/scripts/page.js   (committed)
                  server/src/main/resources/wabridge/scripts/manifest.json

connect ─► auth ─► hello ─► reply { ok, scripts: { page: {version, sha256, size, builtAt} } }
   worker: cached sha256 == manifest sha256 ? reuse cache : GET /api/scripts/page.js (Bearer)
           verify sha256 ─► chrome.storage.local.pageScript = { code, version, sha256 }
           on hello reply and on every tab attach ─► chrome.scripting.executeScript(MAIN, bootstrap, [code])
   page:   bootstrap creates blob URL + <script src> ─► page script runs (build-stamped; replaces older instance)
```

## Server

- Resources: `wabridge/scripts/page.js` and `wabridge/scripts/manifest.json`
  (`{"page": {"version": "<12 hex>", "sha256": "<64 hex>", "size": N, "builtAt": "<ISO>"}}`).
- `ScriptCatalog` (component): at startup reads the manifest and every listed script from the
  classpath, recomputes SHA-256, and throws if a hash differs (fail fast). Exposes
  `manifest(): JsonNode` and `Optional<Script> find(name)` with `Script(name, version, sha256, bytes)`.
- `ScriptsController` (JWT-protected like the rest of `/api`):
  - `GET /api/scripts` → the manifest.
  - `GET /api/scripts/{name}` → `application/javascript; charset=utf-8`, header `ETag: "<sha256>"`,
    `Cache-Control: private, max-age=0, must-revalidate`; `If-None-Match` matching → `304`; unknown name → `404`.
- `BridgeWebSocketHandler`: the `hello` response result becomes `{ ok: true, scripts: <manifest> }`.

## Extension

- Package: `manifest.json` keeps `storage`, `scripting` (re-attaching tabs after an extension
  reload, and the MAIN-world bootstrap), `alarms`. No `dist/page.js` in the package.
- `background/scripts.js` (pure, unit-tested):
  `ensurePageScript({ manifestEntry, cached, fetchScript, digest })` →
  `{ code, version, sha256, changed }`. Reuses `cached` when `cached.sha256 === manifestEntry.sha256`;
  otherwise fetches, verifies the digest equals `manifestEntry.sha256` (throws `ScriptIntegrityError`
  on mismatch), and returns the new entry with `changed: true`.
- `background/connection.js`: new option `onHello(result)` invoked with the `hello` response result.
- `background/index.js`:
  - on `onHello`: run `ensurePageScript`, persist to `chrome.storage.local.pageScript`, set
    `status.pageScriptVersion`, then inject into the attached tab if any.
  - on tab attach: inject the cached script if present.
  - `injectPage()`: `chrome.scripting.executeScript({ target: {tabId}, world: 'MAIN', func: bootstrapPageScript, args: [PAGE_SOURCE, WA_ORIGIN, 'page', version, code] })`; the bootstrap creates the blob URL and script tag in the page and reports `{type:'injected'|'inject-failed'}` via `window.postMessage` (relayed like any page frame).
  - Server requests are answered `WHATSAPP_NOT_READY` (via the router) until the tab reports the
    page script is running: the router gains `setTabReady(tab, ready)`; the bootstrap posts
    `{ type: 'injected', name, version }` after the script tag loads (relayed like any page frame),
    and the worker also treats a `ready` event from the page as proof.
  - Failure: `ScriptIntegrityError` or fetch error → keep the cached script, `lastDetail = 'page script: <message>'`, publish status.
- `content/relay.js`: unchanged pure relay. It must not insert script elements (extension CSP applies to those).
- `popup`: shows `Page script: <version>` (or `none`).
- Page script build stamp (`__WABRIDGE_BUILD__`) = manifest `version`, so the existing
  "replace stale instance" logic in `inject.js` keeps working across server-delivered updates.

## Build and sync (`whatsapp-web-extension/build.mjs`)

1. Bundle the shell entries to `whatsapp-web-extension/dist/`.
2. Bundle `src/page/inject.js` to memory with `__WABRIDGE_BUILD__ = "__WABRIDGE_VERSION__"`.
3. `sha256` of that output → `version = first 12 hex`; replace the placeholder with `version`;
   `sha256` of the final text is the manifest hash.
4. Write `page.js` and `manifest.json` into `whatsapp-backend/src/main/resources/wabridge/scripts/`
   (skipped with `--no-sync`; `--watch` keeps syncing on change).
5. `npm run build` output lists both locations.

## Testing

- Server: `ScriptCatalogTest` (manifest/hash validation, mismatch throws), `ScriptsApiIT`
  (200 with ETag, 304 on If-None-Match, 404 unknown, 401 without token), `BridgeWebSocketIT`
  `hello` reply contains `scripts.page.sha256`.
- Extension: `scripts.test.js` (reuse cache on same hash, fetch on change, integrity failure),
  `router.test.js` (not-ready gating until `setTabReady`), `connection.test.js` (`onHello` fires
  with the hello result). Relay injection is verified manually (Task: reload extension, watch tab
  console for `[wabridge] ready … build <version>` matching `/api/scripts`).
- Manual: change a `console.info` in `inject.js`, `npm run build`, restart server, observe the
  new version in the popup and tab console without touching `chrome://extensions`.

## Out of scope

- Delivering worker/relay/popup code (impossible under MV3).
- `chrome.userScripts` fallback.
- Signing scripts beyond hash pinning (the channel is authenticated HTTPS; signing is a hardening item).
