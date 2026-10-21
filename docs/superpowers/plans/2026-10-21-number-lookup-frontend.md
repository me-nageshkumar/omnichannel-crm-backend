# Number Lookup (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The New chat modal tells the user whether the typed number is on WhatsApp before they send, and refuses to send to one that is not.

**Architecture:** One new API call (`lookupNumber`), two new error sentences, and lookup state inside `NewChatModal` keyed by the cleaned phone so a changed number clears it and a late answer for an old number is ignored.

**Tech Stack:** React 18 + TypeScript, PatternFly 6, Vitest + Testing Library (`mockFetch`, `requestTo`, `callsTo` under `src/test/fetchMock.ts`, `urlsOf` under `src/test/admin.ts`).

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-number-lookup-design.md` §4. Repo: `omnichannel-crm-frontend`.

## Global Constraints

- Copy, verbatim: `Checking WhatsApp…`, `On WhatsApp`, `This number is not on WhatsApp`, `Check again`, `Could not check this number`.
- `messageFor` sentences: `not_on_whatsapp: 'This number is not on WhatsApp.'`, `lookup_failed: 'Could not check this number on WhatsApp. Try again.'`.
- Lookup runs on blur of a valid number and before Send / Template… when the current number has no answer yet; never on every keystroke.
- `npm run lint` and `npm test` must pass.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- Typing a new number after a "not on WhatsApp" answer must clear the block (Task 2 test `changingTheNumberClearsTheAnswer`).
- A lookup answer arriving after the number changed must be ignored (Task 2 test `lateAnswerForAnOldNumberIsIgnored`).
- Send pressed on an unchecked number (focus never left the field, e.g. Enter) must look up first and still send once when registered (Task 2 test `sendLooksUpAnUncheckedNumberFirst`).
- A failed lookup must not block sending: the server checks again (Task 2 test `failedLookupKeepsSendEnabled`).
- `NOT_ON_WHATSAPP` from the start call must land under the phone field with Check again, not as a generic alert (Task 2 test `serverNotOnWhatsAppLandsUnderTheField`).

---

### Task 1: `lookupNumber` API call and the two sentences

**Files:**
- Modify: `src/app/api/inbox.ts`
- Modify: `src/app/api/messages.ts`
- Test: `src/app/api/inbox.test.ts`, `src/app/api/messages.test.ts`

**Interfaces:**
- Produces:
  - `export interface NumberLookup { phone: string; registered: boolean; remoteId: string | null; checkedAt: string; cached: boolean }`
  - `export const lookupNumber = (token: string, ws: string, pn: string, phone: string, force = false) => Promise<NumberLookup>` → `GET ${base(ws)}/phone-numbers/${encodeURIComponent(pn)}/number-lookup?phone=${encodeURIComponent(phone)}` plus `&force=true` when forced (use the file's existing GET pattern via `apiFetch`).

- [ ] **Step 1: Write the failing tests.** In `inbox.test.ts` (follow the file's existing call tests):

```ts
it('looks a number up', async () => {
  const fetch = mockFetch({ 'GET /api/workspaces/w1/inbox/phone-numbers/p1/number-lookup': { status: 200, body: { phone: '919811111111', registered: true, remoteId: 'x@lid', checkedAt: 't', cached: false } } });
  await expect(lookupNumber('tok', 'w1', 'p1', '919811111111')).resolves.toMatchObject({ registered: true });
  expect(urlsOf(fetch, 'GET /api/workspaces/w1/inbox/phone-numbers/p1/number-lookup')[0]).toContain('?phone=919811111111');
  expect(urlsOf(fetch, 'GET /api/workspaces/w1/inbox/phone-numbers/p1/number-lookup')[0]).not.toContain('force');
});
it('forces a fresh lookup', async () => { /* force = true → url contains 'force=true' */ });
```

In `messages.test.ts`: `not_on_whatsapp` → `This number is not on WhatsApp.`; `lookup_failed` → `Could not check this number on WhatsApp. Try again.` (build `ApiError` the way the file's `chat_taken` test does).

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/api/inbox.test.ts src/app/api/messages.test.ts`
Expected: FAIL (`lookupNumber` not exported; generic sentence returned).

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/api/inbox.test.ts src/app/api/messages.test.ts && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/api/inbox.ts src/app/api/inbox.test.ts src/app/api/messages.ts src/app/api/messages.test.ts
git commit -m "feat(inbox): lookupNumber call and lookup error sentences"
```

---

### Task 2: Lookup states in `NewChatModal`

**Files:**
- Modify: `src/app/Inbox/NewChatModal.tsx`
- Test: `src/app/Inbox/newChatModal.test.tsx`

**Interfaces:**
- Consumes: Task 1's `lookupNumber`, `NumberLookup`.

Behaviour:
- State `answer: { phone: string; state: 'checking' | 'registered' | 'unregistered' | 'failed' } | null`. `current = answer && answer.phone === cleaned ? answer : null` (an answer for another number is as good as none).
- `check(force: boolean): Promise<'registered' | 'unregistered' | 'failed'>`: sets `{phone: cleaned, state: 'checking'}`, calls `lookupNumber(token, ws, phoneNumberId, cleaned, force)`; on resolve sets `registered`/`unregistered`; on any rejection sets `failed`. Before writing the result it compares the phone it checked with the latest cleaned value (a ref updated on every change) and drops the result if they differ. Returns the state it would have set.
- `onBlur`: `setTouched(true)`; if `phoneValid && !current` → `void check(false)`.
- `ensureChecked()`: `current?.state === 'registered' || current?.state === 'failed'` → proceed; `current?.state === 'checking'` → await that in-flight promise (keep it in a ref); otherwise `await check(false)`. Returns false only for `unregistered`. `send()` and the Template… click call it first and stop on false. Keep the `inFlight` guard around the whole of `send` so a second click during the lookup is ignored too.
- Helper text precedence under the phone field: `shownPhoneError` (error) → `current.state` (`checking`: variant `indeterminate` "Checking WhatsApp…"; `registered`: `success` "On WhatsApp"; `unregistered`: `error` "This number is not on WhatsApp" followed by `<Button variant="link" isInline onClick={() => void check(true)}>Check again</Button>`; `failed`: `warning` "Could not check this number") → the hint.
- `canSend` additionally requires `current?.state !== 'unregistered'`; Template… is disabled under the same condition. Neither is disabled while `checking` (the click awaits).
- Start-call error with code `NOT_ON_WHATSAPP` (any status) → `setAnswer({ phone: cleaned, state: 'unregistered' })` (so the field shows the sentence and Check again) and no alert. The picker's `send` callback rejects the same way; the picker shows `messageFor` (sentence from Task 1) as it does for any error: acceptable, no extra handling.

- [ ] **Step 1: Write the failing tests** in `newChatModal.test.tsx` (`LOOKUP = 'GET /api/workspaces/w1/inbox/phone-numbers/p1/number-lookup'`; `lookup(registered: boolean) => ({ status: 200, body: { phone: '919811111111', registered, remoteId: registered ? 'x@lid' : null, checkedAt: 't', cached: false } })`; `await user.tab()` moves focus out of the phone box to trigger blur):

```ts
it('checks the number on blur and shows On WhatsApp', async () => { /* LOOKUP: lookup(true); type phone; tab; expect "Checking WhatsApp…" then "On WhatsApp"; callsTo(LOOKUP) === 1 */ });
it('shows not on WhatsApp and disables Send and Template…', async () => { /* lookup(false) → sentence visible, Send disabled (with text typed), Template… disabled, "Check again" button present; callsTo(START) === 0 after clicking Send */ });
it('Check again forces a fresh lookup and re-enables Send', async () => { /* LOOKUP route function: first call → registered false, second → true; click Check again; expect urlsOf(fetch, LOOKUP)[1] toContain 'force=true'; "On WhatsApp"; Send enabled */ });
it('failedLookupKeepsSendEnabled', async () => { /* LOOKUP: {status: 502, body: {code: 'LOOKUP_FAILED', message: 'x'}}; "Could not check this number"; Send enabled; click Send → START called once */ });
it('changingTheNumberClearsTheAnswer', async () => { /* lookup(false); blur; sentence shown; type one more digit → sentence gone, hint back, Send enabled again */ });
it('lateAnswerForAnOldNumberIsIgnored', async () => { /* LOOKUP route returns a deferred Response (resolve manually); blur with 919811111111; change number to 919811111112; resolve the first lookup with registered false; expect no "not on WhatsApp" text and Send enabled */ });
it('sendLooksUpAnUncheckedNumberFirst', async () => { /* type text, then phone, press Enter without tabbing; LOOKUP lookup(true) then START → onStarted called once; callsTo(LOOKUP) === 1; callsTo(START) === 1; lookup request happened before the start request (fetch.mock.calls order) */ });
it('does not send when the pre-send lookup says not on WhatsApp', async () => { /* Enter in phone box; lookup(false); callsTo(START) === 0; sentence shown */ });
it('serverNotOnWhatsAppLandsUnderTheField', async () => { /* LOOKUP lookup(true) (stale cache said yes); START: {status: 409, body: {code: 'NOT_ON_WHATSAPP', message: 'x'}}; after Send: sentence under the field, Check again present, no role="alert" */ });
it('Template… looks up first', async () => { /* lookup(false) → clicking Template… does not open the picker (no "Choose a template"/picker title; reuse whatever title the existing template test asserts) */ });
```

Also update the existing tests that now trigger a lookup: `sends a text to a cleaned phone number`, `sends on Enter…`, `sendsOnce`, `serverPhoneErrorLandsUnderTheField`, `serverTextErrorLandsUnderTheMessage`, `shows the taken-chat message`, `sends a template as the first message`, `Escape in the template picker…`, `ignores close requests…` need `[LOOKUP]: lookup(true)` in their routes (an unmatched request fails the test, which is how you find them all).

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Inbox/newChatModal.test.tsx`
Expected: new tests FAIL; existing ones still pass (no lookup yet).

- [ ] **Step 3: Implement** in `NewChatModal.tsx` as described.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Inbox/newChatModal.test.tsx src/app/Inbox/inboxPage.test.tsx && npm run lint && npm test`
Expected: PASS, whole suite green (`inboxPage.test.tsx`'s `startsAChatAndOpensIt` needs the LOOKUP route too).

- [ ] **Step 5: Commit**

```bash
git add src/app/Inbox/NewChatModal.tsx src/app/Inbox/newChatModal.test.tsx src/app/Inbox/inboxPage.test.tsx
git commit -m "feat(inbox): new chat checks the number on WhatsApp before sending"
```
