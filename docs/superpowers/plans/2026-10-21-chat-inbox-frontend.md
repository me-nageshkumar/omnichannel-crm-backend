# Chat Inbox (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An **Inbox** page in the console: pick a phone number, then a WhatsApp-Web-like two-pane view with a live chat list, a thread, assignment and close controls, and a composer that sends text or a template.

**Architecture:** `src/app/api/inbox.ts` (types, calls, a pure SSE frame parser); `src/app/Inbox/` with a number picker page, the inbox page (left `ChatList`, right `ChatHeader` + `Thread` + `Composer`), a `TemplatePickerModal`, a `RoutingSettingsModal`, and hooks `useChatList`, `useThread`, `useInboxStream`. Live updates arrive over SSE read with `fetch` and applied to local state.

**Tech Stack:** React 18, TypeScript strict, PatternFly 6, React Router, Vitest + Testing Library (`src/test/renderApp.tsx`, `src/test/fetchMock.ts`, `src/test/session.ts`). Repo: `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend`.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-chat-inbox-design.md` (§10 API shapes, §11 console are normative).

## Global Constraints

- Follow `AGENTS.md`: `npm run type-check`, `npm run lint`, `npm test` after every task; `npm run ci-checks` before the final review. Unused imports are errors.
- PatternFly components first; the inbox fills the content height (`PageSection` with `isFilled` and a flex column; no page scroll).
- Error wording through `messageFor(e)` from `@app/api/messages`; add new codes there: `CHAT_CLOSED` → "This chat is closed. Reopen it to reply.", `MEDIA_UNAVAILABLE` → "Media is no longer available on the phone.", `NO_DEVICE` / `WHATSAPP_NOT_READY` → reuse the existing sentences if present, else "WhatsApp is not connected on this number."
- Copy from spec §2: "Inbox", delivery states Pending/Sent/Delivered/Read/Played/Failed, sources "Console" / "WhatsApp Web" / "Contact", "Number hidden" for contacts without a phone.
- Add `'inbox:view','inbox:view-team','inbox:view-all','inbox:send','inbox:assign','inbox:settings'` to `BRAND_CODES` in `src/test/session.ts`.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A `chat` stream event with `visible:false` for the currently open chat must close the thread with a notice ("This chat was reassigned"), not keep showing it — test in Task 6.
2. Enter sends only when the textarea has non-blank text; Shift+Enter inserts a newline; a send in flight disables Send (no double send) — test in Task 5.
3. A stream that drops must reconnect and reload the list, without duplicating rows — test in Task 6 (parser + list reducer level).
4. A media message must not fetch bytes until the user is looking at it (thumbnail requested only for messages rendered in the thread; `available:false` shows a Download button) — test in Task 4.
5. The scope toggle must only offer scopes the user holds and must default to the widest — test in Task 3.

---

### Task 1: API module and SSE parser

**Files:**
- Create: `src/app/api/inbox.ts`, `src/app/api/inbox.test.ts`
- Modify: `src/app/api/messages.ts` (codes above), `src/test/session.ts` (`BRAND_CODES`)

**Interfaces:**
- Produces types mirroring spec §10: `ChatStatus`, `MessageDirection`, `MessageSource`, `MessageStatus`, `ListScope = 'MINE'|'TEAM'|'UNASSIGNED'|'ALL'`, `ChatView`, `MessageView`, `ActionView`, `ThreadItem`, `ChatsPage`, `ThreadPage`, `RoutingMode`, `InboxSettings`, `Assignees`, `InboxEvent = {type:'chat', chat: ChatView, visible: boolean} | {type:'message', chatId, message: MessageView} | {type:'action', chatId, action: ActionView}`.
- Produces calls: `listInboxNumbers(token, ws)`, `listChats(token, ws, pn, {scope?, status?, q?, unread?, before?, limit?})`, `getChat`, `getThread(token, ws, chatId, before?)`, `markRead`, `sendText(token, ws, chatId, text)`, `sendTemplate(token, ws, chatId, templateId, values)`, `assignChat(token, ws, chatId, {userId?, teamId?})`, `unassignChat`, `closeChat`, `reopenChat`, `getAssignees`, `getInboxSettings(token, ws, pn)`, `putInboxSettings`, `chatMediaUrl(ws, chatId, messageId)`, `streamUrl(ws, pn)`.
- Produces pure helpers: `parseSse(buffer: string): { events: Array<{event: string, data: string}>, rest: string }` (splits on blank lines, ignores comment lines starting with `:`, joins multi `data:` lines with `\n`), `toInboxEvent(event: string, data: string): InboxEvent | null`.
- Produces `fetchMedia(token, url): Promise<Blob>` via `apiFetchBlob` if it exists, else `fetch` with the Bearer header.

- [ ] **Step 1: Write failing tests** (`inbox.test.ts`): `parseSse splits complete frames and keeps the partial tail` (input `"event: chat\ndata: {\"a\":1}\n\n: ping\n\nevent: message\ndata: {"` → one event `chat` with data `{"a":1}`, rest `"event: message\ndata: {"`); `parseSse joins multi-line data`; `toInboxEvent maps chat/message/action and returns null for unknown`; `listChats builds the query string` (mockFetch, assert URL contains `scope=TEAM&status=OPEN&unread=true&limit=50`); `sendText posts {text}`; `sendTemplate posts {templateId, values}`.
- [ ] **Step 2: Run** `npm test -- inbox` → fail. **Step 3: Implement.** **Step 4: Run** `npm test -- inbox`, `npm run type-check`, `npm run lint` → pass.
- [ ] **Step 5: Commit** — `feat(inbox): API module and SSE parser`

---

### Task 2: Route, number picker, routing settings modal

**Files:**
- Create: `src/app/Inbox/InboxNumbersPage.tsx`, `src/app/Inbox/RoutingSettingsModal.tsx`, `src/app/Inbox/inboxNumbers.test.tsx`
- Modify: `src/app/routes.tsx` (three routes: `/inbox` label "Inbox", group Brand, `requires: (a) => a.hasInWorkspace('inbox:view')`; `/inbox/:phoneNumberId` and `/inbox/:phoneNumberId/:chatId` without label, same `requires`, element `<InboxPage />` — create a placeholder `src/app/Inbox/InboxPage.tsx` rendering "Inbox" that Task 6 replaces), `src/app/routes.test.tsx` if it enumerates nav labels.

**Interfaces:**
- Consumes: `listInboxNumbers`, `getInboxSettings`, `putInboxSettings`, `listTeams` (`@app/api/teams`), `listWorkspaceUsers` (`@app/api/workspaceUsers`), `PhoneNumberStatusLabel`, `PageShell`, `useLoad`, `useAccess`, `useSession`.
- Produces: `InboxNumbersPage` — PatternFly `DataList` or `Table` of numbers: `+number`, label, `PhoneNumberStatusLabel`; WhatsApp Web rows are links to `/inbox/<id>`; `WHATSAPP_CLOUD_API` rows disabled with text "Cloud API inbox coming later"; a "Routing" button per row when `hasInWorkspace('inbox:settings')`. Empty state "No WhatsApp numbers yet" with a link to `/phone-numbers` when the user holds `phone-number:view`.
- Produces: `RoutingSettingsModal({ phoneNumberId, onClose })` — form: Routing (`Select`: "Leave unassigned" NONE, "Team queue" TEAM, "One agent" USER, "Round robin in team" ROUND_ROBIN), Team (shown for TEAM/ROUND_ROBIN), Agent (shown for USER), switches "Assign the chat to whoever replies first" (`claimOnReply`), "Keep the assignment when a chat is closed" (`keepAssignmentOnClose`); Save → `putInboxSettings`; VALIDATION `fields.teamId/userId` shown under the field.

- [ ] **Step 1: Write failing tests** (`inboxNumbers.test.tsx`, `renderApp('/inbox')` as a WORKSPACE_MANAGER session from `src/test/session.ts` with `inbox:view` + `inbox:settings`): `lists numbers and links WhatsApp Web ones`; `cloud api numbers are disabled with the coming-later text`; `routing button hidden without inbox:settings` (AGENT session); `routing modal saves mode and team` (open, choose "Team queue", pick team, Save → PUT body `{mode:'TEAM', teamId:'t1', …}`); `nav shows Inbox only with inbox:view`.
- [ ] **Step 2: Run → fail. Step 3: Implement. Step 4: Run** `npm test -- inboxNumbers routes`, type-check, lint → pass.
- [ ] **Step 5: Commit** — `feat(inbox): Inbox route, number picker, routing settings`

---

### Task 3: Chat list pane

**Files:**
- Create: `src/app/Inbox/useChatList.ts`, `src/app/Inbox/ChatList.tsx`, `src/app/Inbox/chatDisplay.ts`, `src/app/Inbox/chatList.test.tsx`

**Interfaces:**
- Consumes: `listChats`, `useAccess`, `relative(iso)` from `@app/PhoneNumbers/PhoneNumberStatusLabel`.
- Produces: `chatDisplay.ts`: `contactName(chat)` (name → `+phone` → "Number hidden"), `allowedScopes(access): ListScope[]` (MINE always; TEAM and UNASSIGNED with `inbox:view-team`; ALL with `inbox:view-all`), `defaultScope(scopes)` (ALL > TEAM > MINE), `STATUS_TICK: Record<MessageStatus, {label, glyph}>` (Pending "🕓", Sent "✓", Delivered "✓✓", Read "✓✓" blue, Played same as read, Failed "!").
- Produces: `useChatList(phoneNumberId, filters: {scope, status: 'OPEN'|'CLOSED', q, unread})` → `{ items, loading, error, hasMore, loadMore(), reload(), apply(event: InboxEvent) }`. `apply`: `chat` with `visible:false` → remove; `visible:true` → upsert and re-sort by `lastMessageAt desc`, but only insert when the chat matches the current `status` filter and (for scope ≠ ALL) the row belongs there is left to the server: on an insert of an unknown chat call `reload()` instead of guessing; `message`/`action` events are ignored here. Debounce `q` 300 ms.
- Produces: `ChatList({ phoneNumberId, selectedId, onSelect, filters, onFilters, list })` — toolbar: `SearchInput`, `ToggleGroup` for scope (only `allowedScopes`), `ToggleGroup` Open/Closed, `Switch` "Unread only"; rows: avatar initial, name, preview (tick glyph before outbound text), `relative(lastMessageAt)`, unread `Badge`; "Load more" button when `hasMore`; selected row highlighted; empty state "No chats".

- [ ] **Step 1: Write failing tests**: `offers only allowed scopes and defaults to the widest` (AGENT → only Mine, no toggle group shown; TEAM_LEAD → Mine/Team/Unassigned, Team selected; MANAGER → all four, All selected); `renders rows with preview, tick and unread badge`; `visible:false event removes the row`; `chat event moves updated chat to the top`; `unknown chat event triggers a reload` (callsTo list endpoint increments); `load more appends the next page with before cursor`.
- [ ] **Step 2–5** as usual; commit — `feat(inbox): chat list pane`

---

### Task 4: Thread and message bubbles

**Files:**
- Create: `src/app/Inbox/useThread.ts`, `src/app/Inbox/Thread.tsx`, `src/app/Inbox/MessageBubble.tsx`, `src/app/Inbox/MediaBody.tsx`, `src/app/Inbox/thread.test.tsx`

**Interfaces:**
- Consumes: `getThread`, `chatMediaUrl`, `fetchMedia`, `formatWhatsApp(text)` from `@app/Templates/placeholders` (bold/italic rendering), `STATUS_TICK`.
- Produces: `useThread(chatId | null)` → `{ items, loading, error, hasMore, loadEarlier(), apply(event) }`; `apply`: `message` → upsert by `message.id` (status changes update in place) keeping time order; `action` → append; both only when `event.chatId === chatId`.
- Produces: `Thread({ chat, thread, meId })` — day separators (`Today`, `Yesterday`, else `d MMM yyyy`), `MessageBubble` per message, system line per action ("Assigned to Demo Agent by Demo Lead", "Routed to Demo Support", "Closed by …", "Reopened", "Unassigned by …"), "Load earlier" at the top, auto-scroll to bottom when a new item arrives and the scroller was within 40 px of the bottom.
- Produces: `MessageBubble({ message, side })` — incoming left/outgoing right; body via `formatWhatsApp`; footer with time and, for OUT, the tick glyph plus a source label: `sentBy.name` for CONSOLE, "WhatsApp Web" otherwise; `revoked` → italic "This message was deleted"; `location` → link `https://maps.google.com/?q=<lat>,<lng>` with the description; `poll_creation` → body as question (options are not available on inbound; show "Poll" label); unknown types → "Unsupported message".
- Produces: `MediaBody({ chatId, message })` — `image`/`sticker`: when `media.available` fetch the blob on mount and show `<img>`; else a "Download" button that fetches, then shows; `video`/`audio`/`ptt`: same with `<video controls>` / `<audio controls>`; `document`: a file chip (filename, size) with a Download button that fetches and saves via an object URL. Errors through `messageFor`.

- [ ] **Step 1: Write failing tests**: `renders incoming and outgoing with ticks and sender label`; `action items render as system lines`; `media not available shows Download and fetches on click` (no fetch to the media URL before the click); `available image fetches on mount`; `message event updates status in place`; `load earlier prepends`; `day separators`.
- [ ] **Step 2–5**; commit — `feat(inbox): thread and message bubbles`

---

### Task 5: Composer and template picker

**Files:**
- Create: `src/app/Inbox/Composer.tsx`, `src/app/Inbox/TemplatePickerModal.tsx`, `src/app/Inbox/composer.test.tsx`

**Interfaces:**
- Consumes: `sendText`, `sendTemplate`, `listTemplates(token, ws, {status:'ACTIVE', q})`, `getTemplate`, `renderTemplate`, `TemplateBubble`, `loadedSettings`, `settingFor` and `previewValues` from `@app/Templates/editorModel`, `parsePlaceholders` / `PlaceholderRef` from `@app/Templates/placeholders` (derive agent refs from `template.placeholders.filter(p => p.kind === 'AGENT')` instead when simpler), `messageFor`.
- Produces: `Composer({ chat, numberConnected, onSent(message: MessageView) })` — `TextArea` (Enter sends, Shift+Enter newline, max 4096 with a counter after 3900), "Template" button, "Send" button; disabled with an inline `Alert` (variant info, plain) "Reopen to reply" when `chat.status === 'CLOSED'` and "WhatsApp is not connected on this number" when `!numberConnected`; send in flight disables Send and the Template button; error `Alert` (danger) with `messageFor`; clears the textarea on success and calls `onSent`.
- Produces: `TemplatePickerModal({ chat, onClose, onSent })` — left: `SearchInput` + list of ACTIVE templates (name, type label from `TYPE_META`), CONTACT_CARD rows disabled with "Not sendable from the inbox yet"; right: for the chosen template, agent placeholder fields (label, helper = example, required mark; initial value = example), a live `TemplateBubble` preview from `renderTemplate(token, ws, id, {contactId: chat.contact.id, values})` debounced 300 ms, `missing` names shown as field errors; Send → `sendTemplate`; VALIDATION `fields.<name>` mapped to the field.

- [ ] **Step 1: Write failing tests**: `enter sends trimmed text and clears` (POST body `{text:'hello'}`); `shift+enter inserts newline, nothing sent`; `blank text does not send`; `send in flight disables the button` (delay the mock, assert disabled, resolve, assert enabled); `closed chat shows Reopen to reply and no textarea input`; `not connected shows the notice`; `template picker lists active templates, disables contact cards`; `template send posts templateId and values`; `missing required value shows a field error and sends nothing`.
- [ ] **Step 2–5**; commit — `feat(inbox): composer and template picker`

---

### Task 6: Live stream and the inbox page

**Files:**
- Create: `src/app/Inbox/useInboxStream.ts`, `src/app/Inbox/ChatHeader.tsx`, `src/app/Inbox/AssignMenu.tsx`, `src/app/Inbox/inboxPage.test.tsx`
- Modify: `src/app/Inbox/InboxPage.tsx` (replace the Task 2 placeholder)

**Interfaces:**
- Consumes: Tasks 1–5; `getChat`, `markRead`, `assignChat`, `unassignChat`, `closeChat`, `reopenChat`, `getAssignees`, `listInboxNumbers` (for the number's status), `useParams`, `useNavigate`.
- Produces: `useInboxStream(phoneNumberId, onEvent: (e: InboxEvent) => void, onReconnect: () => void)` — opens `fetch(streamUrl, {headers: {Authorization: 'Bearer …', Accept: 'text/event-stream'}})`, reads `res.body.getReader()`, decodes with `TextDecoder`, feeds `parseSse`, dispatches `toInboxEvent`; on end or error waits `min(30000, 1000 * 2^attempt)` and reconnects, calling `onReconnect` after a successful reconnect (not on the first connect); aborts with `AbortController` on unmount or number change. Returns `{ connected: boolean }`.
- Produces: `InboxPage` — layout per spec §11: left `ChatList` (width 360 px, own scroll), right pane: no chat → `EmptyState` "Pick a chat"; chat → `ChatHeader` + `Thread` + `Composer`. URL is the source of truth for the open chat (`/inbox/:phoneNumberId/:chatId`); selecting a row navigates; opening a chat loads it (`getChat`), calls `markRead`, zeroes the badge locally. Stream events go to `list.apply`, `thread.apply`, and the open chat (`chat` event with the same id updates header state; `visible:false` for it clears the open chat and shows an `Alert` "This chat was reassigned and is no longer in your inbox"). A top bar shows the number (`+number`, label, status label) and a "Change number" link to `/inbox`; `connected` false shows a small warning label "Reconnecting…".
- Produces: `ChatHeader({ chat, onChange })` — name, `+phone`/"Number hidden", link "Open contact" to `/contacts/:id`, assignment pill ("Unassigned" / "Team: Support" / "Agent: Demo Agent"), `AssignMenu` when `inbox:assign` (Dropdown: "Assign to agent…" submenu from `getAssignees().users`, "Assign to team…" from `.teams`, "Unassign"), Close / Reopen button when `inbox:send`. Each action calls the API and `onChange(chatView)`.

- [ ] **Step 1: Write failing tests** (`inboxPage.test.tsx`; mock the stream route with a `ReadableStream` the test can push frames into — write a small helper `streamMock()` in `src/test/inbox.ts` returning `{ response, push(frame: string), close() }`): `opening a chat loads thread and marks read`; `message event appends to the open thread and updates the list preview`; `chat event with visible:false closes the open chat with the reassigned notice`; `assign menu assigns and updates the header`; `close button closes and the composer shows Reopen to reply`; `stream reconnect reloads the list once` (close the stream, advance fake timers, assert the list endpoint was called again and rows are not duplicated); `change number link goes back to the picker`.
- [ ] **Step 2–4** as usual, then `npm run ci-checks` → green.
- [ ] **Step 5: Commit** — `feat(inbox): inbox page with live updates, assignment and close`

---

## Self-review notes
- Spec §11 coverage: picker + routing modal → T2; left pane → T3; thread → T4; composer + template picker → T5; header, assignment, stream, layout → T6; API → T1.
- Names reused across tasks: `ListScope`, `InboxEvent`, `ChatView`, `MessageView`, `ThreadItem`, `useChatList().apply`, `useThread().apply`, `STATUS_TICK`, `contactName`, `allowedScopes`, `defaultScope`, `parseSse`, `toInboxEvent`, `streamUrl`, `chatMediaUrl`.
- Review Focus pinned to T6, T5, T6/T1, T4, T3.
