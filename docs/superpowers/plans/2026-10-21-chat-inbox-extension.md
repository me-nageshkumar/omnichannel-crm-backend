# Chat Inbox (extension) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the server fetch the bytes of an inbound media message from the WhatsApp Web tab.

**Architecture:** One new page-side RPC method `downloadMedia` next to `sendMessage` in `src/page/methods.js`, mirroring `Message.downloadMedia` of whatsapp-web.js (`resolveMediaBlob` + `arrayBufferToBase64Async`). A new error code. The page bundle is rebuilt into the backend's resources by `npm run build`.

**Tech Stack:** Plain ES modules, `node --test`, esbuild. Repo: `/Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web-extension`.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-chat-inbox-design.md` §9.

## Global Constraints

- `npm run lint` (eslint + prettier) and `npm test` must pass; `npm run build` must succeed and writes `page.js`/`manifest.json` into `../omnichannel-crm-backend/src/main/resources/wabridge/scripts` (committed by the backend plan's Task 8, not here).
- Page methods must throw `codedError(code, message)` so the bridge posts `{error:{code,message}}`.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A message id WhatsApp cannot resolve (expired, evicted) must produce `MEDIA_UNAVAILABLE`, not a generic `PAGE_ERROR` — test in Task 1.
2. `WWebJS` helpers not yet loaded must produce `WHATSAPP_NOT_READY`, like `sendMessage` — test in Task 1.

---

### Task 1: `downloadMedia` page method

**Files:**
- Modify: `src/shared/protocol.js` — add `MEDIA_UNAVAILABLE: 'MEDIA_UNAVAILABLE'` to `ErrorCodes`.
- Modify: `src/page/methods.js` — register `downloadMedia`.
- Test: `tests/methods.test.js` (new; `tests/bridge.test.js` shows the style and how a fake `window` is built).

**Interfaces:**
- Produces: method `downloadMedia({ messageId })` → `{ data, mimetype, filename, filesize }` (`data` base64 string). Throws `WHATSAPP_NOT_READY` when `window.WWebJS` is undefined; `PAGE_ERROR` when `messageId` is not a non-empty string; `MEDIA_UNAVAILABLE` ("Media is no longer available on the phone") when `window.WWebJS.resolveMediaBlob(messageId)` returns null. Otherwise `data = await window.WWebJS.arrayBufferToBase64Async(await resolved.blob.arrayBuffer())`.
- Consumes (server side, backend plan Task 8): RPC `downloadMedia` with `params.messageId` = `id._serialized`.

- [ ] **Step 1: Write the failing tests** in `tests/methods.test.js`. Build `window` as a plain object assigned to `globalThis.window` in a `before`/`after` (restore afterwards). Tests:
  - `downloadMedia returns base64 payload from resolveMediaBlob`: `window.WWebJS = { resolveMediaBlob: async (id) => ({ blob: { arrayBuffer: async () => new Uint8Array([1,2,3]).buffer }, mimetype: 'image/png', filename: 'a.png', filesize: 3 }), arrayBufferToBase64Async: async (buf) => Buffer.from(buf).toString('base64') }`; `createMethods({ getInfo: () => null }).get('downloadMedia')({ messageId: 'true_1@c.us_X' })` deep-equals `{ data: 'AQID', mimetype: 'image/png', filename: 'a.png', filesize: 3 }`.
  - `downloadMedia → MEDIA_UNAVAILABLE when resolve returns null`: `assert.rejects(..., (e) => e.code === 'MEDIA_UNAVAILABLE')`.
  - `downloadMedia → WHATSAPP_NOT_READY without WWebJS`.
  - `downloadMedia → PAGE_ERROR without messageId`.
- [ ] **Step 2: Run** `npm test` → the new tests fail (`downloadMedia` undefined).
- [ ] **Step 3: Implement** in `methods.js`, next to `sendMessage`, using the same `codedError` helper.
- [ ] **Step 4: Run** `npm test` and `npm run lint` → pass.
- [ ] **Step 5: Build** `npm run build` → log shows the page script version and that it was written to the backend resources; `git -C ../omnichannel-crm-backend status --short src/main/resources/wabridge/scripts` shows `page.js` and `manifest.json` modified (leave them for the backend plan).
- [ ] **Step 6: Commit** (extension repo only) — `feat(page): downloadMedia RPC for inbox media`
