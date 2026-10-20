# Phone Numbers: extension connect (Chrome extension) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The extension connects for one onboarded number with a connect code and stored device
key instead of a username/password, shows the server's refusal reasons, and never replays events
captured under another number or key.

**Architecture:** `settings.js` becomes `{serverUrl, deviceKey, deviceKeyId, number, brandName}`
with a pure `redeem` step; `connection.js` learns the new close codes as terminal states;
`outbox.js` gains an owner tag; the page script sends `pnWid`; `popup` shows code entry or the
connected number. All I/O stays injected so Node tests cover it.

**Tech Stack:** Chrome MV3 service worker, plain ES modules, esbuild (`build.mjs`), `node --test`,
ESLint + Prettier.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-phone-numbers-and-enable-disable-design.md`.
Consumes the **API contract** of `omnichannel-crm-backend/docs/superpowers/plans/2026-10-20-phone-numbers-backend.md`
(device connect, WebSocket auth/close codes, `pnWid`, `acct`, `Authorization: Device`).

## Global Constraints

- Repo `whatsapp-web-extension`, branch `feature/phone-numbers`.
- Storage keys: `chrome.storage.local` `settings` = `{serverUrl, deviceKey, deviceKeyId, number,
  brandName}`; managed schema = `{serverUrl, connectCode}` (username/password removed).
- Close codes (`shared/protocol.js` `CloseCodes`): `UNAUTHORIZED 4401`, `DISABLED 4403`,
  `REPLACED 4409`, `REVOKED 4410`, `WRONG_NUMBER 4412`, `PING_TIMEOUT 4000`.
- States added to `connection.js`: `DISABLED`, `REVOKED`, `WRONG_NUMBER` — terminal like
  `AUTH_FAILED` (no automatic retry); `DISABLED` retries every 5 minutes (the number may be
  enabled again); `WRONG_NUMBER` retries when the page reports a new `ready` with a different
  account.
- REVOKED or 4401 twice → forget `deviceKey` (keep `serverUrl`) and show "Enter a new connect code".
- Popup copy: code field label "Connect code", button "Connect"; connected view "Connected for
  +<number> (<brandName>)"; "Disconnect" button text "Disconnect this browser", confirm "This
  browser stops sending WhatsApp for +<number>. You will need a new connect code."
- The page script build writes into `../omnichannel-crm-backend/src/main/resources/wabridge/scripts`
  (existing `build.mjs`); that output is committed in the **backend** repo as part of Task 4.
- Gate: `npm test && npm run lint`. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Events from the previous number replayed to the new one.** Redeeming a code for a different
   number, Disconnect, or a WhatsApp account change clears the outbox before anything else is
   appended. (Task 3 `ownerChangeClearsOutbox`, `accountChangeClearsOutbox`.)
2. **Retry storm on a disabled number.** 4403 must not reconnect faster than every 5 minutes.
   (Task 2 `disabledRetriesEvery5Minutes`.)
3. **Managed code redeemed twice.** A policy `connectCode` is redeemed only when no key is stored;
   after a REVOKED the same (used) code is not retried in a loop. (Task 1 `managedCodeRedeemedOnce`.)
4. **Key leaked in status.** `statusSnapshot` and the popup never include `deviceKey`.
   (Task 4 `statusOmitsDeviceKey`.)
5. **Server URL with a trailing slash or spaces** behaves like the clean one. (Task 1, existing
   `normalize` tests kept.)

---

### Task 1: Settings and redeem

**Files:** Modify `src/background/settings.js`, `src/background/storage.js`, `managed-schema.json`;
create `src/background/redeem.js`; tests `tests/settings.test.js`, `tests/redeem.test.js`.

**Interfaces:**
- Produces: `isComplete(s)` true when `serverUrl` and `deviceKey` are non-empty; `normalize(s)`;
  `pickSettings(local, managed)` → `{settings, source, pendingCode}` where `pendingCode` is the
  managed `connectCode` when local has no `deviceKey`; `redeem({serverUrl, code, fetch})` →
  `{deviceKey, deviceKeyId, phoneNumberId, number, brandName}` posting `/api/devices/connect`;
  throws `RedeemError` with `.code` (`INVALID_CONNECT_CODE`, `DISABLED`, `TOO_MANY_ATTEMPTS`, `NETWORK`)
  and the server message.
- [ ] **Step 1: Failing tests:** `completeNeedsKey`, `managedCodeOnlyWithoutKey`, `managedCodeRedeemedOnce`,
  `redeemPostsCodeAndReturnsKey`, `redeemMapsErrors` (400/403/429/network).
- [ ] **Step 2: Run** `node --test tests/settings.test.js tests/redeem.test.js` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat: device-key settings and connect-code redeem`.

### Task 2: Connection auth and close codes

**Files:** Modify `src/shared/protocol.js`, `src/background/connection.js`; tests
`tests/connection.test.js`, `tests/protocol.test.js`.

**Interfaces:** `authenticate()` now returns the device key (no HTTP login); new close-code handling
per Global Constraints; `Connection.onAccountChanged()` lets a WRONG_NUMBER connection retry.
- [ ] **Step 1: Failing tests:** `closeDisabledEntersDisabled`, `disabledRetriesEvery5Minutes` (fake
  timers), `closeRevokedEntersRevoked`, `closeWrongNumberEntersWrongNumberAndWaits`,
  `accountChangeRetriesWrongNumber`, `reasonIsKeptAsDetail`, existing replaced/unauthorized tests kept.
- [ ] **Step 2: Run** `node --test tests/connection.test.js tests/protocol.test.js` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat: device-key auth and refusal states`.

### Task 3: Outbox owner tag

**Files:** Modify `src/background/outbox.js`, `src/background/router.js`; tests `tests/outbox.test.js`, `tests/router.test.js`.

**Interfaces:** `Outbox.setOwner({deviceKeyId, acct})` — persists the owner; if either field
differs from the stored owner (and the stored owner is non-null), clears pending entries first.
`append(frame)` stamps `frame.acct = owner.acct` when known. `Outbox.clear()`.
- [ ] **Step 1: Failing tests:** `ownerChangeClearsOutbox`, `sameOwnerKeepsOutbox`,
  `accountChangeClearsOutbox`, `appendStampsAcct`, `clearEmptiesAndPersists`.
- [ ] **Step 2: Run** `node --test tests/outbox.test.js tests/router.test.js` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat: tag the outbox with its key and account`.

### Task 4: Worker wiring, popup, page script `pnWid`

**Files:** Modify `src/background/index.js`, `src/popup/popup.js`, `popup.html`, `src/page/inject.js`,
`README.md`; tests `tests/bridge.test.js` (or a new `tests/status.test.js` for `statusSnapshot` if
`index.js` needs a small pure helper extracted: `src/background/status.js` `snapshot(state)`).
Backend repo: rebuilt `src/main/resources/wabridge/scripts/*`.

**Interfaces:**
- Worker messages: `connect {serverUrl, code}` → redeem, save settings, `outbox.setOwner`, start;
  `disconnect` → stop, forget key, `outbox.clear()`; `status` → snapshot `{connection, detail,
  waState, number, brandName, credentialsSource, hasTab, pageScriptVersion, pageReady,
  outboxPending, outboxDropped}`.
- `fetchScript` uses `Authorization: Device <key>`.
- On page `ready`: compute `acct` from `pnWid` (digits before `@`), call `outbox.setOwner` and, if
  the connection is WRONG_NUMBER and acct changed, `connection.onAccountChanged()`.
- `buildInfo()` adds `pnWid: me.getMaybeMePnUser()?._serialized ?? null`.
- Popup: not connected → server URL + Connect code + Connect, errors from `RedeemError`; connected →
  number/brand, connection state text (Connected / Waiting for WhatsApp login / Disabled / Wrong
  number: <reason> / Device removed), "Disconnect this browser".
- [ ] **Step 1: Failing tests:** `statusOmitsDeviceKey`, `snapshotShowsNumberAndBrand`, `acctFromPnWid`.
- [ ] **Step 2: Run** `npm test` — Expected: FAIL.
- [ ] **Step 3: Implement**; `npm run build`, then in the backend repo
  `git add src/main/resources/wabridge/scripts && git commit -m "chore: page script sends pnWid"` (with the attribution line).
- [ ] **Step 4: Run** `npm test && npm run lint` — Expected: PASS. Manual check (with backend running
  and demo seed on): get a code for +919928088743, enter it, popup shows "Connected for +919928088743 (Demo Fashion)".
- [ ] **Step 5: Commit** `feat: connect with a code; popup shows number and refusals`.
