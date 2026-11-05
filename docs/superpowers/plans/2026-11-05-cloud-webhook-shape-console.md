# WhatsApp Web events as Cloud API webhooks — console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The console reads the stored message types by Meta's names (`text`, `audio`, `contacts`, `unsupported`) with no visible change.

**Architecture:** A handful of type switches in the inbox thread, bubble and chat list move to the new names; the old names stay accepted until the backend's migration has run everywhere (both are matched for one release), then the old ones go.

**Tech Stack:** React 18, PatternFly 6.5, TypeScript, vitest.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-11-05-cloud-webhook-shape-design.md` §4.7; backend plan `plans/2026-11-05-cloud-webhook-shape-backend.md` Task 8.

## Global Constraints

- Work in `.worktrees/cloud-webhook-shape` of `omnichannel-crm-frontend`; `npm ci --prefer-offline` first.
- No visible change: every label, body and media rendering stays as it is.
- Prettier only on touched files; `npx tsc --noEmit && npm run lint && npm run build` green before the merge.
- Commit trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A stored row of an old type name (`chat`, `ptt`, `vcard`) from a database the migration has not reached yet must still render — Task C1 keeps both names.
2. An `interactive` row with neither body nor media (a reply whose title was empty) must not render as "Unsupported message" — Task C1.

---

### Task C1: Meta's type names in the thread, the bubble and the chat list

**Files:**
- Modify: `src/app/Inbox/MessageBubble.tsx` (`MEDIA_TYPES` adds nothing; `TEXT_TYPES = ['text', 'chat']` stays; the `vcard || multi_vcard` branch becomes `vcard || multi_vcard || contacts`; `ONLY_ON_PHONE.unsupported = 'A message only the phone can show'` next to `unknown`)
- Modify: `src/app/Inbox/MediaBody.tsx` (`kindOf`: `type === 'audio' || type === 'ptt'` unchanged; nothing else switches on the old names)
- Modify: `src/app/Inbox/Thread.tsx`, `src/app/Inbox/ChatList.tsx`, `src/app/Inbox/chatDisplay.ts` (any `'chat'`, `'ptt'`, `'vcard'`, `'unknown'` message-type comparison also accepts `'text'`, `'audio'`, `'contacts'`, `'unsupported'`; `grep -rn "'chat'\|'ptt'\|'vcard'\|'multi_vcard'\|'unknown'" src/app/Inbox src/app/Automations` lists every place — the `type: 'chat'` of `InboxEvent` is the stream event kind, not a message type, and stays)
- Modify: `src/app/Automations/*` only if a message-type label map exists there (kinds come from the backend; expected: nothing)
- Test: `src/app/Inbox/thread.test.tsx`

**Interfaces:**
- Consumes: `MessageView.type` strings as the backend stores them after its Task 8.

- [ ] **Step 1: Write the failing tests**

```tsx
it('a contact card stored under the Cloud name renders as a card', async () => {
  const log = await showing(message({ id: 'c1', type: 'contacts', body: WIFE }));
  expect(await within(log).findByRole('group', { name: 'Contact card: Wife' })).toBeVisible();
});

it('a message only the phone can show, under the Cloud name', async () => {
  const log = await showing(message({ id: 'u1', type: 'unsupported' }));
  expect(await within(log).findByText('A message only the phone can show')).toBeVisible();
  expect(screen.queryByText('Unsupported message')).toBeNull();
});

it('a text stored as text renders its body', async () => {
  const log = await showing(message({ id: 't1', type: 'text', body: 'hello there' }));
  expect(await within(log).findByText('hello there')).toBeVisible();
});

it('a voice note stored as audio renders the player', async () => {
  const log = await showing(message({ id: 'a1', type: 'audio', media: { mimetype: 'audio/ogg', available: true, ... } }));
  expect(within(log).getByRole('button', { name: /download|play/i })).toBeVisible();   // match the existing ptt test's assertion
});
```

- [ ] **Step 2: Run** — `npx vitest run src/app/Inbox/thread.test.tsx --maxWorkers=4` — Expected: the `contacts` and `unsupported` cases FAIL ("Unsupported message"); `text` and `audio` already pass (they are in the lists today).
- [ ] **Step 3: Implement** the branches.
- [ ] **Step 4: Run** — vitest on the Inbox folder, then `npx tsc --noEmit && npm run lint` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(inbox): read Meta's message type names`

---

### Task C2: Drop the old names (one release later)

Not part of this delivery. Once every environment's database has run the backend migration, remove `'chat'`, `'ptt'`, `'vcard'`, `'multi_vcard'`, `'unknown'` from the console's switches and their tests. Record in `known-limits.md` when C1 ships.
