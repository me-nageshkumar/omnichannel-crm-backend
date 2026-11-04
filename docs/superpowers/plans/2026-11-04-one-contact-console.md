# One contact across two WhatsApp ids — console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show an agent when the person in a chat has another chat on the number, list every WhatsApp id of a contact, and give the contacts page a "Resolve hidden numbers" action.

**Architecture:** Two API types change (`waIds` replaces `waId`; `ChatView.otherChats`), one new API call (`resolveContactIds`), one line in the chat header, one kebab action on the contacts page, and the ids shown where the single id was.

**Tech Stack:** React 18, PatternFly 6.5, react-router, vitest; `tsconfig` target es5.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-11-04-one-contact-design.md` (binding, §4.1 and §4.6). Backend contract: `…/plans/2026-11-04-one-contact-backend.md` Tasks 1, 5 and 7 (Interfaces blocks).

## Global Constraints

- Worktree `omnichannel-crm-frontend/.worktrees/one-contact` (branch `one-contact`, off main 7ee43a1; `node_modules` linked). Checks per task: `npx vitest run <paths> --maxWorkers=4`; before the last commit `npx tsc --noEmit && npm run lint && npm run build`. Prettier only on touched files.
- Copy fixed by the spec: "This person also has another chat on this number" (one other) / "This person also has other chats on this number" (several); link text = the other chat's remote id without its suffix, prefixed `+` for a phone id, "hidden number" for a lid; "Resolve hidden numbers"; "<n> contacts queued"; "WhatsApp ids".
- Wire shapes: `ContactView.waIds: string[]` (no `waId`); `ChatView.otherChats: { id: string; remoteId: string }[]`; `POST /api/workspaces/{ws}/contacts/resolve-ids` → `202 { queued: number }`, permission `contact:edit`.
- es5: no `[...new Set()]`.
- Commits end with the trailer the session reminder names (`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` as of 2026-11-04).

## Plan calls (decisions the spec leaves to the builder)

- Plan call: the header line sits under the existing label row in `ChatHeader.tsx` as a small muted `<div>`; each other chat is a router `Link` to `/inbox/<chatId>` (the inbox's existing chat route; check `routes.tsx` for its exact shape and reuse the helper the chat list uses).
- Plan call: "Resolve hidden numbers" lives in the contacts page's existing kebab/toolbar next to Import (`ContactsPage.tsx` ~line 223), shown with `contact:edit`; on success a PatternFly `Alert` (info, inline, dismissible) reads "<n> contacts queued"; on failure the existing error alert pattern of the page.
- Plan call: "WhatsApp ids" is a `Detail` row in the contact detail page (`Contacts/ContactDetailPage.tsx`) listing `waIds` joined by ", " with the phone id first as the backend orders them; hidden when empty.

## Review Focus

1. A chat with `otherChats` undefined or empty (every chat from an older server or a person with one chat) must render the header exactly as before — Task 1 `noOtherChatsNoLine`.
2. The other-chat link must lead to that chat, not the current one — Task 1 `otherChatLinksToIt`.
3. A contact view without `waIds` (undefined from an older cached response) must not crash the detail page — Task 2 `missingWaIdsRendersNothing`.
4. "Resolve hidden numbers" must be hidden without `contact:edit` and must show the server's count — Task 2 `resolveHiddenNumbersNeedsEdit`, `resolveHiddenNumbersShowsTheCount`.

---

### Task 1: Chat header names the person's other chats

**Files:**
- Modify: `src/app/api/inbox.ts` (`ChatView.otherChats: { id: string; remoteId: string }[]`), `src/app/Inbox/ChatHeader.tsx`, test fixtures `tsc` names (`otherChats: []`).
- Test: `src/app/Inbox/chatHeader.test.tsx` (append).

**Interfaces:**
- Produces: `export function otherChatLabel(remoteId: string): string` in `src/app/Inbox/chatDisplay.ts` (`+<digits>` for `@c.us`, "hidden number" for `@lid`).

- [ ] **Step 1: Write the failing tests**: `oneOtherChatShowsTheLine` (chat with `otherChats [{id 'c2', remoteId '919876543210@c.us'}]` → text "This person also has another chat on this number" and a link "+919876543210" to the chat route for `c2`); `severalOtherChatsUsePlural`; `noOtherChatsNoLine` (`[]` and `undefined`); `otherChatLinksToIt`.
- [ ] **Step 2: Run** `npx vitest run src/app/Inbox/chatHeader.test.tsx --maxWorkers=4` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `npx tsc --noEmit`.
- [ ] **Step 5: Commit** — `feat(inbox): the chat header names the person's other chats`

### Task 2: WhatsApp ids and "Resolve hidden numbers"

**Files:**
- Modify: `src/app/api/contacts.ts` (`waIds: string[]` replaces `waId`; `export const resolveContactIds = (token, ws) => apiFetch<{ queued: number }>(`${base}/resolve-ids`, { method: 'POST', token })`), `src/app/Contacts/ContactsPage.tsx` (~:223 kebab/toolbar), `src/app/Contacts/ContactDetailPage.tsx` (Detail row), fixtures `tsc` names.
- Test: `src/app/Contacts/contacts.test.tsx` (append), `src/app/api/contacts.test.ts` if it exists (append the fetcher test), a detail-page test file if one exists else `contacts.test.tsx`.

- [ ] **Step 1: Write the failing tests**: `resolveHiddenNumbersShowsTheCount` (manager → action visible → click → `POST …/contacts/resolve-ids` → alert "3 contacts queued"); `resolveHiddenNumbersNeedsEdit` (viewer → no action); `detailListsWhatsAppIds` (`waIds ['919876543210@c.us', '1203@lid']` → "WhatsApp ids" row with both); `missingWaIdsRendersNothing`.
- [ ] **Step 2: Run** `npx vitest run src/app/Contacts src/app/api --maxWorkers=4` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; then `npx tsc --noEmit && npm run lint && npm run build` → clean.
- [ ] **Step 5: Commit** — `feat(contacts): WhatsApp ids and resolve hidden numbers`

### Task 3: End-to-end check against the running stack

Needs both branches deployed on 8089 (project rules §2) and **the user's own phones**; never message anyone the user has not named.

- [ ] **Step 1:** Count contacts in the demo brand that lack a phone or have no hidden id (Mongo, read only). Run "Resolve hidden numbers" as demo-manager; note the queued count; after a minute count again and list merged pairs from the log (`ContactMergeService`).
- [ ] **Step 2:** From a phone the user names, set to hide the number if possible, write to the demo number; confirm one contact, the chat header line if a second chat exists, and the "WhatsApp ids" row.
- [ ] **Step 3:** With a draft rule "Chat is new" tested on that contact, confirm the second chat of the same person is not new.
- [ ] **Step 4:** Note anything surprising in `docs/superpowers/known-limits.md` (backend repo) and commit there.
