# New Chat (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "New chat" button on the inbox page opens a modal where the user types a phone number and sends a text or a template as the first message, then lands in that chat.

**Architecture:** One new API call (`startChat`), the template picker generalised to work without a chat (`contactId` + `send` callback), a `NewChatModal` component owning the phone/text form, and the inbox page wiring the button, the modal and the navigation after the send.

**Tech Stack:** React 18 + TypeScript, PatternFly 6, Vitest + Testing Library (`mockFetch`, `renderApp`, `storeSession` helpers under `src/test/`).

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-new-chat-design.md` §3 (console). Repo: `omnichannel-crm-frontend`.

## Global Constraints

- Copy: button **New chat**; modal title **New chat**; field labels **Phone number** and **Message**; buttons **Template…**, **Send**, **Cancel**; phone helper `Country code first, digits only, e.g. 919812345678`; phone error `Enter 8 to 15 digits with the country code`; disconnected notice `WhatsApp is not connected on this number` (as the composer).
- Phone check: strip `[+\s\-()]`, then `^[1-9][0-9]{7,14}$`.
- Text limit 4096 with the composer's counter behaviour (`MAX_LENGTH`, `COUNTER_FROM`): extract or copy the composer's constants rather than inventing new ones.
- `CHAT_TAKEN` message: `This number already has a chat assigned to someone else.`
- Gate the button on `inbox:send` (`useAccess().hasInWorkspace('inbox:send')`).
- `npm run lint` and `npm test` must pass; `npm run type-check` if the repo has it (check `package.json` scripts).
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- Pressing Send twice fast must send once (Task 2 test `sendsOnce`).
- A `VALIDATION` error naming `phone` from the server must show under the phone field, not as a generic alert (Task 2 test `serverPhoneErrorLandsUnderTheField`).
- With the number not CONNECTED both Send and Template… are disabled and the notice shows (Task 2 test `disabledWhenNotConnected`).
- The composer's template send must still post to `/chats/{id}/messages` after the picker refactor (existing `composer.test.tsx` template cases stay green, Task 1).
- After a start the page must navigate to the returned `chatId` even if the stream has not pushed the chat yet (Task 3 test `startsAChatAndOpensIt`).

---

### Task 1: `startChat` API, `CHAT_TAKEN` copy, chat-free template picker

**Files:**
- Modify: `src/app/api/inbox.ts` (add `startChat`)
- Modify: `src/app/api/messages.ts` (add `chat_taken`)
- Modify: `src/app/Inbox/TemplatePickerModal.tsx` (props)
- Modify: `src/app/Inbox/Composer.tsx` (pass the new props)
- Test: `src/app/api/messages.test.ts` (if present; else `src/app/Inbox/composer.test.tsx` only)

**Interfaces:**
- Produces:
  - `export type NewChatBody = { phone: string; text: string } | { phone: string; templateId: string; values: Record<string, string> };`
  - `export const startChat = (token: string, ws: string, pn: string, body: NewChatBody) => Promise<MessageView>` → `POST ${base(ws)}/phone-numbers/${encodeURIComponent(pn)}/chats`.
  - `TemplatePickerModal` props: `{ contactId: string | null; send(templateId: string, values: Record<string, string>): Promise<MessageView>; onClose(): void; onSent(message: MessageView): void }`. The render preview passes `contactId` (null → omit it from the render body, or send `null`: check what `renderTemplate`'s request type allows and keep it type-correct).

- [ ] **Step 1: Write the failing test** — in `composer.test.tsx` the picker tests already post to `SEND` with `{templateId, values}`; they must keep passing. Add to a messages test (create `src/app/api/messages.test.ts` if none exists, following any sibling `*.test.ts` under `src/app/api/`):

```ts
it('names the chat that belongs to someone else', () => {
  expect(messageFor(new ApiError(409, { code: 'CHAT_TAKEN', message: 'x' }))).toBe(
    'This number already has a chat assigned to someone else.',
  );
});
```

(Check `ApiError`'s constructor signature in `src/app/api/client.ts` and build it the way other tests do.)

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/api/messages.test.ts`
Expected: FAIL — generic message returned.

- [ ] **Step 3: Implement** `startChat`, `chat_taken`, the picker props (`chat` removed; `chat.contact.id` → `contactId`; `sendTemplate(...)` → `send(chosen.id, values)`), and `Composer` passing `contactId={chat.contact.id}` and `send={(id, values) => sendTemplate(token as string, ws, chat.id, id, values)}`.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/api/messages.test.ts src/app/Inbox/composer.test.tsx && npm run lint`
Expected: PASS, lint clean.

- [ ] **Step 5: Commit**

```bash
git add src/app/api/inbox.ts src/app/api/messages.ts src/app/api/messages.test.ts src/app/Inbox/TemplatePickerModal.tsx src/app/Inbox/Composer.tsx
git commit -m "feat(inbox): startChat call and a template picker that needs no chat"
```

---

### Task 2: `NewChatModal`

**Files:**
- Create: `src/app/Inbox/NewChatModal.tsx`
- Test: `src/app/Inbox/newChatModal.test.tsx`

**Interfaces:**
- Consumes: Task 1's `startChat`, `NewChatBody`, `TemplatePickerModal`.
- Produces: `export const NewChatModal: React.FunctionComponent<{ phoneNumberId: string; numberConnected: boolean; onClose(): void; onStarted(message: MessageView): void }>`.

Behaviour: PatternFly `Modal` (variant `small` or `medium`, `aria-labelledby="new-chat-title"`), `Form` with `FormGroup` "Phone number" (`TextInput`, `isRequired`, helper text, error after blur or submit), `FormGroup` "Message" (`TextArea`, counter as the composer), footer buttons **Send** (primary, disabled unless phone valid, text trimmed non-empty, connected, not sending), **Template…** (secondary, disabled unless phone valid and connected), **Cancel** (link). Enter in the phone box submits when Send is enabled. The notice when not connected is an inline plain `Alert` above the form. The picker is rendered on top when `picking` with `contactId={null}` and `send={(templateId, values) => startChat(t, ws, phoneNumberId, { phone: cleaned, templateId, values })}`; its `onSent` calls `onStarted`. Errors: `ApiError` `VALIDATION` with `fields.phone` → under the phone field; `fields.text` → under the text; anything else → inline danger `Alert` with `messageFor(e)`. Guard double sends with a ref like the composer.

- [ ] **Step 1: Write the failing tests** (render with `SessionProvider` + `ThemeProvider` and `storeSession(meAs(AGENT_IN_BRAND))` like `composer.test.tsx`; `START = 'POST /api/workspaces/w1/inbox/phone-numbers/p1/chats'`):

```ts
it('sends a text to a cleaned phone number', async () => { /* type "+91 98111-11111" and "hello", click Send;
  expect(requestTo(START).body).toEqual({ phone: '919811111111', text: 'hello' }); onStarted called with the MessageView */ });
it('rejects a bad phone number before calling the server', async () => { /* "12", blur → "Enter 8 to 15 digits with the country code"; Send disabled; callsTo(START) is 0 */ });
it('sendsOnce', async () => { /* click Send twice; callsTo(START) === 1 */ });
it('serverPhoneErrorLandsUnderTheField', async () => { /* mock 400 {code:'VALIDATION', fields:{phone:'must be 8 to 15 digits…'}}; the text appears in the phone helper, no alert role */ });
it('shows the taken-chat message', async () => { /* 409 CHAT_TAKEN → alert with the sentence */ });
it('disabledWhenNotConnected', () => { /* numberConnected=false → notice visible, Send and Template… disabled */ });
it('sends a template as the first message', async () => { /* valid phone, click Template…, pick t1 (mock TEMPLATES + RENDER as composer.test does), Send in the picker;
  requestTo(START).body toEqual({ phone: '919811111111', templateId: 't1', values: {...} }); onStarted called */ });
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Inbox/newChatModal.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `NewChatModal.tsx`.**

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Inbox/newChatModal.test.tsx && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Inbox/NewChatModal.tsx src/app/Inbox/newChatModal.test.tsx
git commit -m "feat(inbox): new chat modal"
```

---

### Task 3: "New chat" on the inbox page

**Files:**
- Modify: `src/app/Inbox/InboxPage.tsx`
- Test: `src/app/Inbox/inboxPage.test.tsx`

**Interfaces:**
- Consumes: Task 2's `NewChatModal`.

- [ ] **Step 1: Write the failing tests** in `inboxPage.test.tsx` (reuse its fixtures; `START = \`POST ${B}/inbox/phone-numbers/p1/chats\``):

```ts
it('startsAChatAndOpensIt', async () => { /* as agent (has inbox:send): click "New chat", fill phone + text, Send;
  START answers message({ id: 'm9', chatId: 'c9', direction: 'OUT' }); expect location pathname toBe('/inbox/p1/c9');
  GET …/chats/c9 was requested; LIST was requested again (reload) */ });
it('hides New chat without inbox:send', async () => { /* session whose brand authorities exclude inbox:send (build from AGENT_IN_BRAND with a filtered authorities list) → no button */ });
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Inbox/inboxPage.test.tsx`
Expected: FAIL — no "New chat" button.

- [ ] **Step 3: Implement**: in the header row, after "Change number", `{access.hasInWorkspace('inbox:send') && <Button variant="secondary" size="sm" onClick={() => setStarting(true)}>New chat</Button>}`; render `<NewChatModal phoneNumberId={phoneNumberId} numberConnected={numberConnected} onClose={() => setStarting(false)} onStarted={(message) => { setStarting(false); setNotice(null); list.reload(); navigate(\`/inbox/${encodeURIComponent(phoneNumberId)}/${encodeURIComponent(message.chatId)}\`); }} />` when `starting`.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Inbox/inboxPage.test.tsx && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Inbox/InboxPage.tsx src/app/Inbox/inboxPage.test.tsx
git commit -m "feat(inbox): New chat button opens the modal and lands in the chat"
```
