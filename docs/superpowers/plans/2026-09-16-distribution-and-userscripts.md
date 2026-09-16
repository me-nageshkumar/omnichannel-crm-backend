# Distribution Channels and `chrome.userScripts` Injection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the extension Web Store compliant while keeping server-delivered page scripts, give it a stable ID, produce the store zip reproducibly, and (optionally) add a self-hosted CRX channel served by the backend.

**Architecture:** Page-script injection moves to `chrome.userScripts` (register a persistent MAIN-world script; `update` on new versions; `execute` for open tabs), with the existing `chrome.scripting` blob bootstrap kept only in non-store builds via a build-time channel flag. The build generates per-channel manifests and artifacts from the committed dev manifest.

**Tech Stack:** Chrome MV3 (`userScripts` 120+/`execute` 135+), esbuild `define` for channels, Node 24 tests, `archiver`-free zipping via `zip` CLI or a small JS zip lib, `crx3` for CRX signing, Spring Boot for the optional channel.

**Spec:** `docs/superpowers/specs/2026-09-16-distribution-and-userscripts-design.md`

## Global Constraints

- Repos: `whatsapp-web-extension` (Tasks 1–5), `whatsapp-backend` (Task 6, optional). Work on a branch per repo (`feat/userscripts-distribution`), merge to `main` when the manual checks pass. No commit hooks in these repos; keep Conventional Commits and the `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` trailer.
- Channel flag: esbuild `define: { __WABRIDGE_CHANNEL__: JSON.stringify(channel) }`; source reads it via a `/* global __WABRIDGE_CHANNEL__ */` header as `inject.js` does for `__WABRIDGE_BUILD__`.
- The store bundle must contain neither `createObjectURL` nor `chrome.scripting.executeScript` for the page (the relay re-injection on `onInstalled` uses `executeScript` with a packaged file, which is allowed and may stay).
- Readiness: the worker already marks the tab ready on the page's `ready` event; keep that as the signal under `userScripts`.
- Run `npm run lint && npm test && npm run build` before each commit in the extension repo; `./mvnw test` in the backend.

---

## File Structure (extension)

```
whatsapp-web-extension/
  manifest.json                       # modify: userScripts permission, key, icons
  icons/icon16.png icon48.png icon128.png   # new (generated)
  build.mjs                           # modify: --channel, per-channel manifest, release/<channel>/
  pack.mjs                            # new: zip (store) / crx + update.xml (managed)
  src/build/channels.js               # new: channel → manifest overrides (pure, tested)
  src/background/injector.js          # new: userScripts/bootstrap selection and operations (pure, tested)
  src/background/bootstrap.js         # new: bootstrapPageScript moved here (dev/managed only)
  src/background/index.js             # modify: use injector; onInstalled re-register; status.injection
  src/popup/popup.js                  # modify: injection line + enable-toggle hint
  tests/channels.test.js, injector.test.js, pack.test.js
  docs/store-listing.md               # new: listing checklist and justification texts
```

---

### Task 1: Stable ID, icons, and channel-aware build

**Files:** `manifest.json`, `icons/*`, `src/build/channels.js`, `tests/channels.test.js`, `build.mjs`, `README.md`

**Interfaces:**
- `channelManifest(baseManifest, channel, { version, updateUrl })` → manifest object: `dev` unchanged; `store` removes `update_url` if present and keeps everything else; `managed` adds `update_url`. All channels include `"userScripts"` in permissions (added in the base manifest here) and `icons`.
- Build outputs: `dev` → `dist/` (unchanged behaviour); `store`/`managed` → `release/<channel>/{manifest.json,popup.html,managed-schema.json,icons/,dist/*.js}`.

- [ ] **Step 1: Generate the signing key and the manifest `key`** (one-time, outside the repo):
  ```bash
  mkdir -p ~/.config/whatsapp-web-extension
  openssl genrsa -out ~/.config/whatsapp-web-extension/extension.pem 2048
  openssl rsa -in ~/.config/whatsapp-web-extension/extension.pem -pubout -outform DER | base64 | tr -d '\n'
  ```
  Paste the base64 output as `"key": "..."` in `manifest.json`. Compute the resulting ID (first 32 chars of the SHA-256 of the DER public key, mapped 0-9a-f → a-p) with a tiny node one-liner and record it in the README. Back the `.pem` up; losing it means a new ID.
- [ ] **Step 2: Icons.** Generate three PNGs (16/48/128) with a script using the `canvas`-free approach: a small SVG rendered via `sharp`, or hand-made PNGs committed. Add `"icons": {"16": "icons/icon16.png", "48": "icons/icon48.png", "128": "icons/icon128.png"}` and the same as `action.default_icon`.
- [ ] **Step 3: Write `channels.test.js`** (dev keeps manifest identical except `userScripts` present; store has no `update_url`; managed has `update_url` equal to the given URL; version overridden from package.json for store/managed) and run it to fail.
- [ ] **Step 4: Implement `src/build/channels.js`** and extend `build.mjs`: parse `--channel=<dev|store|managed>` (default dev), pass `__WABRIDGE_CHANNEL__` via `define`, for non-dev channels write bundles to `release/<channel>/dist/`, copy `popup.html`, `managed-schema.json`, `icons/`, and write the generated manifest. Add `release/` to `.gitignore`.
- [ ] **Step 5: Verify** `npm run build` (dev unchanged), `npm run build -- --channel=store` produces `release/store/manifest.json` with `userScripts` and no `update_url`. Commit `feat: stable extension key, icons and channel-aware build`.

---

### Task 2: Injector module

**Files:** `src/background/injector.js`, `src/background/bootstrap.js`, `tests/injector.test.js`

**Interfaces:**
```js
// api facade: { userScripts?: { getScripts, register, update, execute? }, scripting?: { executeScript }, channel: 'dev'|'store'|'managed' }
export const SCRIPT_ID = 'wabridge-page';
export async function detect(api) → 'userScripts' | 'bootstrap' | 'none'
export async function ensureRegistered(api, { code, version }) → { action: 'registered'|'updated'|'unchanged' }
export async function executeNow(api, tabId, { code, version }) → 'userScripts' | 'bootstrap' | 'none'
```
- `detect`: try `await api.userScripts.getScripts()` → `'userScripts'`; on throw/absence → `'bootstrap'` if `api.channel !== 'store' && api.scripting` else `'none'`.
- `ensureRegistered`: `getScripts({ ids: [SCRIPT_ID] })`; compare a version marker stored alongside (`chrome.storage.local.registeredPageVersion`, passed in via the facade as `getMarker/setMarker`) → `register([{ id: SCRIPT_ID, matches: ['https://web.whatsapp.com/*'], js: [{ code }], world: 'MAIN', runAt: 'document_idle' }])` or `update([...])`.
- `executeNow`: `userScripts.execute({ target: { tabId }, js: [{ code }], world: 'MAIN' })` when present; else bootstrap via `scripting.executeScript({ target, world: 'MAIN', func: bootstrapPageScript, args })` when allowed; else `'none'`.
- `bootstrap.js` exports `bootstrapPageScript` (moved verbatim from `index.js`). `injector.js` imports it only inside `if (__WABRIDGE_CHANNEL__ !== 'store')` branches so esbuild drops it from store bundles.

- [ ] **Step 1: Tests** with a fake facade: detect → userScripts when `getScripts` resolves; → bootstrap when it throws in `dev`; → none when it throws in `store`; ensureRegistered registers on first call, updates when version differs, no-ops when equal; executeNow prefers `userScripts.execute`, falls back per channel, never touches `scripting` in `store`.
- [ ] **Step 2: Run, expect failures. Step 3: Implement. Step 4: Run, lint.**
- [ ] **Step 5: Commit** `feat(extension): userScripts injector with channel-gated bootstrap fallback`.

---

### Task 3: Worker and popup wiring

**Files:** `src/background/index.js`, `src/popup/popup.js`, `README.md`

- [ ] **Step 1:** In `index.js` replace `bootstrapPageScript`/`injectPage` with the injector: build the facade from `chrome` and `__WABRIDGE_CHANNEL__`; `injectionMode = await detect(api)` at boot and whenever `handleHello` runs (the toggle can change at any time); on a new page-script version → `ensureRegistered` then, if a tab is attached and not ready, `executeNow`; on tab attach → `executeNow` only if `!router.isTabReady()` after a short grace period (registered scripts run on load and emit `ready`); `runtime.onInstalled` → `ensureRegistered` from the cached page script (registrations do not survive extension updates). Status gains `injection: injectionMode`.
- [ ] **Step 2:** Popup line: `Injection: user scripts` / `Injection: bootstrap (dev channel)` / `Injection: unavailable — enable "Allow User Scripts" for this extension` with a clickable link opened via `chrome.tabs.create({ url: 'chrome://extensions/?id=' + chrome.runtime.id })`.
- [ ] **Step 3:** README: "Enable Allow User Scripts" instructions with screenshot placeholders; channel table (dev/store/managed) and what each contains.
- [ ] **Step 4:** Build all three channels; `grep -c createObjectURL release/store/dist/background.js` must print `0`; `grep -c createObjectURL dist/background.js` must be `>= 1`.
- [ ] **Step 5:** Manual checks (dev channel, this Mac): toggle off → popup hint, page script still injected via bootstrap; toggle on → reload extension, reload WhatsApp tab → `[wabridge] ready … build …` appears without the worker's `page injection` log, status `Injection: user scripts`; push a page-script change (rebuild + backend restart) → open tab updates. Commit `feat(extension): inject via userScripts, bootstrap only outside store builds`.

---

### Task 4: Store packaging and listing checklist

**Files:** `pack.mjs`, `tests/pack.test.js`, `docs/store-listing.md`, `package.json` (scripts `pack`, `pack:store`)

- [ ] **Step 1:** `pack.mjs --channel=store`: run the channel build, then zip `release/store/**` to `release/whatsapp-web-extension-<version>.zip` (use the `zip` CLI via `child_process` or the `fflate` package). Test: the zip lists exactly `manifest.json`, `popup.html`, `managed-schema.json`, `icons/*`, `dist/background.js`, `dist/content.js`, `dist/popup.js`; the manifest inside has `key`, `userScripts`, no `update_url`; the background bundle contains no `createObjectURL`.
- [ ] **Step 2:** `docs/store-listing.md`: single-purpose statement; permission justifications (see spec); remote-code justification: "Page-side logic is delivered by the user's own configured backend and executed exclusively through the User Scripts API, per the MV3 remote code policy exemption"; privacy policy points (credentials stored locally, messages relayed only to the server the user configures, no third parties); asset checklist (128 icon, 1280×800 screenshots, promo tile optional); versioning rule (`npm version patch` bumps `package.json`, build stamps the manifest).
- [ ] **Step 3:** Commit `feat(extension): store packaging and listing checklist`.
- [ ] **Step 4 (manual, later):** register the developer account, upload the zip as unlisted, complete the listing from `docs/store-listing.md`, submit for review. After approval install from the store link on a Windows machine, enable "Allow User Scripts", connect, verify `Injection: user scripts` and a message round-trip.

---

### Task 5: Merge and record

- [ ] Merge `feat/userscripts-distribution` into `main` after Task 3 manual checks pass; push; add a verification-log line to the README with Chrome version and outcome.

---

### Task 6 (optional): Managed channel served by the backend

**Files (extension):** `pack.mjs` (`--channel=managed`: sign CRX with the `.pem` via the `crx3` package, write `update.xml`, `--sync` copies into the backend resources), `src/build/updateXml.js` + test.
**Files (backend):** `src/main/resources/wabridge/extension/{whatsapp-bridge.crx,update.xml,manifest.json}`, `me.nageshk.whatsapp.extension.ExtensionCatalog`, `me.nageshk.whatsapp.api.ExtensionController`, `SecurityConfig` (permit `/extension/**`), tests `ExtensionCatalogTest`, `ExtensionApiIT`, README section.

- [ ] **Step 1:** `updateXml(appId, version, codebaseUrl, sha256)` pure function + test producing the Omaha manifest:
  ```xml
  <?xml version='1.0' encoding='UTF-8'?>
  <gupdate xmlns='http://www.google.com/update2/response' protocol='2.0'>
    <app appid='<id>'><updatecheck codebase='<url>' version='<ver>' hash_sha256='<hex>'/></app>
  </gupdate>
  ```
- [ ] **Step 2:** `pack.mjs --channel=managed --update-url=https://<host>/extension/update.xml --sync`: build, sign, write xml + `manifest.json` (`{crx:{version,sha256,size}}`), copy into the backend.
- [ ] **Step 3:** Backend catalog + controller + security permit + tests (200 with correct content types; 404 unknown; hash mismatch fails startup like `ScriptCatalog`).
- [ ] **Step 4 (manual):** on a managed Windows VM (or CBCM-enrolled test machine) set `ExtensionInstallForcelist` to `<id>;https://<host>/extension/update.xml`, confirm silent install and credentials from managed storage, and that bumping the version and redeploying updates the extension within Chrome's update cycle.
- [ ] **Step 5:** Commit both repos; push.

---

## Self-review notes

- Spec coverage: userScripts primary path + channel-gated fallback (T2, T3), stable key and icons (T1), channels and generated manifests (T1), store zip and listing texts (T4), popup guidance (T3), optional managed channel end to end (T6), manual verification for toggle on/off and version updates (T3, T4, T6).
- Risks to re-check at execution time: Chrome may change the toggle name or `userScripts` gating; confirm on the target Chrome version. The Web Store's stance on `userScripts` outside "user script manager" extensions should be re-read at submission time; the justification text in `docs/store-listing.md` is the mitigation.
