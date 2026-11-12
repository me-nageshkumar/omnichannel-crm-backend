# Cloud Messages (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an agent compose and send a Cloud API interactive message from an open chat, see a server-rendered preview and the live Cloud JSON while composing, and see opens, taps and replies on the sent message in the thread.

**Architecture:** `api/cloudMessages.ts` mirrors the Cloud objects in snake_case and wraps the backend's chat-keyed send, media upload and preview endpoints. A pure form model turns composer state into a `CloudMessage`. `InteractiveComposer` is a modal opened from the `Composer` toolbar; `MessagePageCard` renders `MessageView.messagePage`, which the thread already receives and upserts live.

**Tech Stack:** React 18, TypeScript (`tsconfig` target es5: no `[...new Set()]`, `Array.from` instead), PatternFly 6.5 (`Modal`, `Form`, `FileUpload`, `Tabs`), vitest + Testing Library + `mockFetch`.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/.worktrees/cloud-messages/docs/superpowers/specs/2026-11-12-cloud-messages-design.md` (§5.6 for the endpoints, §5.8 for the console)

**Repo:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend/.worktrees/cloud-messages` (run `npm ci --prefer-offline` once). Tests: `npx vitest run <path> --maxWorkers=4`; before the final commit `npx tsc --noEmit && npm run lint && npm run build`. Prettier only on touched files.

## Global Constraints

- Nothing new named "template"; no change under `src/app/Templates/` except reading exports that already exist (`formatWhatsApp`).
- The message object edited and sent is the Cloud shape in snake_case (`messaging_product`, `recipient_type`, `interactive.action.buttons[].reply.title`); `messaging_product` is always `"whatsapp-web"` from the console.
- The preview iframe is `sandbox=""` with `srcdoc`; the HTML comes from the server, never built in the console.
- Server validation keys are JSON paths (`interactive.action.buttons.1.reply.title`); the composer maps them onto its fields and shows anything unmapped as a form-level alert via `messageFor(e)`.
- Backend routes (from the backend plan): `POST /api/workspaces/{ws}/inbox/chats/{chatId}/cloud-messages` (body `CloudMessage` without `to`, response `{messaging_product, contacts:[{input, wa_id}], messages:[{id}]}`), `POST /api/workspaces/{ws}/channel-accounts/{phoneNumberId}/media` (multipart `file`, `messaging_product`, `type` → `{id}`), `POST /api/workspaces/{ws}/channel-accounts/{phoneNumberId}/messages/preview` (JSON body → `text/html`).
- The send response carries only the message id, so after a send the composer reports the id (`onSentId`) and `InboxPage` calls `thread.reload()` (`useThread.ts:182`) to pull the stored `MessageView` with its `messagePage`.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A reply title with a comma or an ampersand must survive the round trip into the JSON tab and the send body unescaped (Task 2 `toCloudMessageKeepsPunctuation`).
2. Deleting the middle button of three must keep the other two in order and renumber server error keys (Task 4 `serverErrorLandsOnTheRightButton`).
3. The modal must not send twice on a double click (Task 4 `sendIsSingleFlight`, same `inFlight` ref pattern as `Composer`).
4. The composer must be disabled with the same notice as the text box when the chat is closed or the number is disconnected (Task 4 `buttonFollowsTheComposerNotice`).
5. A `messagePage` whose `expiresAt` is past must show "Expired" and no live counts confusion (Task 3 `expiredPageShowsExpired`).

---

### Task 1: API types and calls

**Files:**
- Create: `src/app/api/cloudMessages.ts`, `src/app/api/cloudMessages.test.ts`
- Modify: `src/app/api/inbox.ts:108-134` (`MessageView` gains `messagePageId: string | null; messagePage: MessagePageView | null;`), `src/app/Inbox/composer.test.tsx:34-50` and every other fixture that builds a `MessageView` literal (find with `grep -rln "waMessageId:" src`) gets `messagePageId: null, messagePage: null`

**Interfaces (produces):**

```ts
// src/app/api/cloudMessages.ts
export interface MediaRef { id?: string; link?: string }
export interface Header { type: 'text' | 'image' | 'video' | 'document'; text?: string; image?: MediaRef; video?: MediaRef; document?: MediaRef }
export interface Reply { id: string; title: string }
export interface ActionButton { type: 'reply'; reply: Reply }
export interface Row { id: string; title: string; description?: string }
export interface Section { title: string; rows: Row[] }
export interface CtaParameters { display_text: string; url: string }
export interface Action { buttons?: ActionButton[]; button?: string; sections?: Section[]; name?: 'cta_url' | 'send_location'; parameters?: CtaParameters }
export type InteractiveType = 'button' | 'list' | 'cta_url' | 'location_request_message';
export interface Interactive { type: InteractiveType; header?: Header; body: { text: string }; footer?: { text: string }; action: Action }
export interface CloudMessage { messaging_product: 'whatsapp-web' | 'whatsapp'; recipient_type?: 'individual'; to?: string; type: 'text' | 'interactive'; text?: { preview_url?: boolean; body: string }; interactive?: Interactive }
export interface CloudSendResponse { messaging_product: string; contacts: { input: string; wa_id: string }[]; messages: { id: string }[] }
export const sendCloudMessage = (token: string, ws: string, chatId: string, message: CloudMessage) => Promise<CloudSendResponse>;   // POST .../inbox/chats/{chatId}/cloud-messages via apiFetch
export async function uploadCloudMedia(token: string, ws: string, phoneNumberId: string, type: 'image' | 'video' | 'document', file: File): Promise<{ id: string }>; // FormData file + messaging_product + type via apiFetchFile
export async function previewCloudMessage(token: string, ws: string, phoneNumberId: string, message: CloudMessage): Promise<string>; // apiFetchFile(json: true) then res.text()
```
In `src/app/api/inbox.ts`, next to `MessageView` (a view field, not a Cloud object):

```ts
export interface MessagePageCount { id: string; title: string; count: number; replied: boolean }
export interface MessagePageView { url: string; expiresAt: string; openedAt: string | null; openCount: number; buttons: MessagePageCount[]; rows: MessagePageCount[]; cta: MessagePageCount | null; location: { latitude: number; longitude: number; at: string } | null }
```

- [ ] **Step 1: Write the failing tests** (`mockFetch`, `requestTo`, `rejectionOf` from `src/test/fetchMock.ts`):

```ts
it('sends a cloud message to the chat route with the bearer', ...)   // requestTo(fetch, 'POST /api/workspaces/w1/inbox/chats/c1/cloud-messages').body deep-equals the message; Authorization header present
it('uploads media as multipart with product and type', ...)          // fetch.mock.calls[0][1].body instanceof FormData; get('messaging_product') === 'whatsapp-web'; get('type') === 'image'
it('returns the preview html as text', ...)                           // route answers { status: 200, text: '<!doctype html>', headers: { 'Content-Type': 'text/html' } } → resolves to that string; the request body is the message JSON
it('rejects with ApiError carrying fields', ...)                      // 400 {code:'VALIDATION', fields:{'interactive.body.text':'is required'}} → ApiError.fields
```

- [ ] **Step 2: Run to verify failure.** `npx vitest run src/app/api/cloudMessages.test.ts --maxWorkers=4`
- [ ] **Step 3: Implement** the module and the `MessageView` additions; fix fixtures until `npx tsc --noEmit` is clean.
- [ ] **Step 4: Run to verify pass**, plus `npx vitest run src/app/Inbox src/app/api --maxWorkers=4` for the fixture edits.
- [ ] **Step 5: Commit** `feat(cloud): Cloud message types and API calls`

---

### Task 2: Composer form model

**Files:**
- Create: `src/app/Inbox/interactiveModel.ts`, `src/app/Inbox/interactiveModel.test.ts`

**Interfaces (produces):**

```ts
export type Kind = InteractiveType;
export interface HeaderDraft { type: 'none' | 'text' | 'image' | 'video' | 'document'; text: string; mediaId: string | null; fileName: string | null }
export interface ButtonDraft { id: string; title: string; idEdited: boolean }
export interface RowDraft { id: string; title: string; description: string; idEdited: boolean }
export interface SectionDraft { title: string; rows: RowDraft[] }
export interface Draft { kind: Kind; header: HeaderDraft; body: string; footer: string; buttons: ButtonDraft[]; listButton: string; sections: SectionDraft[]; cta: { text: string; url: string } }
export const KINDS: { value: Kind; label: string }[];   // 'Reply buttons', 'List', 'Call-to-action link', 'Location request'
export const LIMITS = { body: 1024, listBody: 4096, footer: 60, headerText: 60, buttons: 3, buttonTitle: 20, listButton: 20, sections: 10, rows: 10, sectionTitle: 24, rowTitle: 24, rowDescription: 72, ctaText: 20 } as const;
export function initialDraft(): Draft;                 // kind 'button', one empty button, one section with one empty row
export function slug(title: string): string;           // lower-case, spaces and punctuation → '-', trimmed, ≤ 50; '' when nothing is left
export function withTitle(b: ButtonDraft, title: string): ButtonDraft; // updates title and, unless idEdited, id = slug(title)
export function toCloudMessage(d: Draft): CloudMessage; // messaging_product 'whatsapp-web', recipient_type 'individual', type 'interactive'; header omitted for 'none'; footer omitted when blank; per kind the action shape of the spec; list rows without description omit the key
export function problems(d: Draft): Record<string, string>; // client-side required/length checks keyed by the same JSON paths the server uses ('interactive.body.text': 'is required', 'interactive.action.buttons.0.reply.title': 'is required', 'interactive.header.image.id': 'Upload a file', ...)
export function keyFor(field: string): string;         // identity for now; the single place both sides agree on paths
```

- [ ] **Step 1: Write the failing tests**

```ts
it('builds a button message in Cloud shape', () => expect(toCloudMessage(buttonDraft)).toEqual({ messaging_product: 'whatsapp-web', recipient_type: 'individual', type: 'interactive', interactive: { type: 'button', body: { text: 'Hi' }, action: { buttons: [{ type: 'reply', reply: { id: 'yes', title: 'Yes' } }] } } }));
it('toCloudMessageKeepsPunctuation', ...)              // title 'Yes, & go' stays as is; id slug 'yes-go'
it('omits header, footer and row descriptions that are blank', ...)
it('builds list, cta_url and location_request_message shapes', ...)   // exact objects from the spec table
it('slug and withTitle follow the title until the id is edited', ...)
it('problems reports required and over-length fields by json path', ...) // body '', a 21-char title, a cta url without https → three keys
```

- [ ] **Step 2: Run to verify failure.** `npx vitest run src/app/Inbox/interactiveModel.test.ts --maxWorkers=4`
- [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** `feat(cloud): interactive composer form model`

---

### Task 3: Message page card in the thread

**Files:**
- Create: `src/app/Inbox/MessagePageCard.tsx`, `src/app/Inbox/messagePageCard.test.tsx`
- Modify: `src/app/Inbox/MessageBubble.tsx:31-68` (`Content()`: when `message.messagePage` is set, render `<MessagePageCard page={message.messagePage} />` above the existing text rendering of the link)

**Interfaces (produces):** `export const MessagePageCard: React.FunctionComponent<{ page: MessagePageView }>` rendering: a label "Interactive message" with a small link "Open page" (`page.url`, `target="_blank" rel="noreferrer"`); "Opened · {openCount}" or "Not opened" (or "Expired" when `expiresAt < now`, in which case counts still show); one `Label` per button and row as `{title} · {count}` with a check icon and `aria-label="{title}: replied"` when `replied`; for cta `Link opened · {count}`; for location a link "Shared location" to `https://maps.google.com/?q={lat},{lng}`. PatternFly `Label`, `LabelGroup`; styles inline like `MessageBubble`.

- [ ] **Step 1: Write the failing tests**

```tsx
it('shows opened count and per-button counts with replied ticks', ...)  // page with buttons [{Change, 2, true}, {Cancel, 0, false}] → text 'Opened · 3', 'Change · 2' has aria-label 'Change: replied'
it('shows not opened and the page link', ...)
it('expiredPageShowsExpired', ...)
it('shows the shared location as a map link', ...)
it('MessageBubble renders the card for a message with messagePage', ...) // render <MessageBubble message={{...SENT, body: 'http://x/t/abc', messagePage}} side="right" contact="Asha" /> → card present and link text present
```

- [ ] **Step 2: Run to verify failure.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass** (`npx vitest run src/app/Inbox --maxWorkers=4`).
- [ ] **Step 5: Commit** `feat(inbox): show opens, taps and replies of an interactive message`

---

### Task 4: Interactive composer modal

**Files:**
- Create: `src/app/Inbox/InteractiveComposer.tsx`, `src/app/Inbox/interactiveComposer.test.tsx`
- Modify: `src/app/Inbox/Composer.tsx:13-17,91-109` (prop `onSentId(messageId: string): void` added; an "Interactive" `Button variant="secondary"` next to "Template", disabled under the same `notice !== null || sending` rule; `composing` state renders `<InteractiveComposer chat={chat} onClose onSent={(id) => { setComposing(false); onSentId(id); }} />`)
- Modify: `src/app/Inbox/InboxPage.tsx:361-365` (pass `onSentId={() => thread.reload()}`; check `useThread` exposes `reload`, it does at `useThread.ts:182`)
- Modify: `src/app/Inbox/composer.test.tsx` (one test: the Interactive button opens the modal and follows the notice)

**Interfaces:**
- Consumes: Task 1 calls, Task 2 model, `useSession()`, `ApiError`, `messageFor`, `MediaField`-style `FileUpload` usage (`src/app/Templates/MediaField.tsx:93-109` as the pattern, not imported), `TemplatePickerModal` as the modal/footer pattern.
- Produces: `export const InteractiveComposer: React.FunctionComponent<{ chat: ChatView; onClose(): void; onSent(messageId: string): void }>`.
  - Layout: `Modal variant="large"`, title "Send an interactive message"; left column the form, right column `Tabs` "Preview" / "JSON". Form: kind `FormSelect` from `KINDS`; header `FormSelect` (`None`, `Text`, `Image`, `Video`, `Document`; for `list` and `location_request_message` only `None`/`Text` or `None`), header text `TextInput` (maxLength 60) or a `FileUpload` dropzone (accept per type as `MediaField.ACCEPT` does; maxSize 5 MB image, 16 MB others) that calls `uploadCloudMedia(token, ws, chat.phoneNumberId, type, file)` and stores `{mediaId, fileName}`; body `TextArea` (maxLength by kind) with a counter from 900; footer `TextInput`; per kind: buttons list (title `TextInput` + id `TextInput` + remove; "Add button" up to 3), list button `TextInput` + sections (title, rows with title/description/id, add/remove), CTA display text + url.
  - Preview: 400 ms debounced `previewCloudMessage(token, ws, chat.phoneNumberId, toCloudMessage(draft))` when `problems(draft)` is empty; `<iframe title="Preview" sandbox="" srcDoc={html} style={{ width: 380, height: 640, border: 0 }} />` inside a rounded phone frame; a `Spinner` while loading; a server validation error here shows under the field it names.
  - JSON tab: `<pre>{JSON.stringify(toCloudMessage(draft), null, 2)}</pre>` read-only.
  - Send: `sendCloudMessage(token, ws, chat.id, toCloudMessage(draft))` single-flight (`inFlight` ref); on `ApiError` `VALIDATION` map `e.fields` by `keyFor` onto the fields, unknown keys → `Alert`; on success `onSent(res.messages[0].id)`.
  - Client-side gating: Send disabled while `problems(draft)` is non-empty, while uploading, or while sending.

- [ ] **Step 1: Write the failing tests** (`Host`/`SessionProvider`/`ThemeProvider` pattern from `composer.test.tsx:52-72`; routes `PREVIEW = 'POST /api/workspaces/w1/channel-accounts/p1/messages/preview'`, `SEND = 'POST /api/workspaces/w1/inbox/chats/c1/cloud-messages'`, `MEDIA = 'POST /api/workspaces/w1/channel-accounts/p1/media'`; fake timers for the debounce):

```tsx
it('builds and sends a reply-buttons message', ...)         // type body 'Hi', title 'Yes' → JSON tab shows "title": "Yes"; click Send → requestTo(SEND).body equals toCloudMessage of that draft; onSent called with 'm9'
it('previews after the debounce with the server html', ...) // iframe srcdoc === '<!doctype html><p>ok</p>' after advancing 400 ms
it('serverErrorLandsOnTheRightButton', ...)                  // three buttons, remove the second, server answers 400 fields {'interactive.action.buttons.1.reply.title': 'must be unique'} → the helper text appears under the (now second) button's title
it('sendIsSingleFlight', ...)                                // click Send twice fast → callsTo(SEND) === 1
it('uploads a header image and puts its id in the message', ...) // drop a file (fireEvent.drop as newCampaign.test.tsx:266 explains) → MEDIA called; JSON shows "image": {"id": "img1"}
it('switching kind to list changes the form and the JSON', ...)
it('disables Send until required fields are filled', ...)
// composer.test.tsx
it('buttonFollowsTheComposerNotice', ...)                     // chat CLOSED → "Interactive" disabled; OPEN → click opens the modal title
```

- [ ] **Step 2: Run to verify failure.** `npx vitest run src/app/Inbox/interactiveComposer.test.tsx src/app/Inbox/composer.test.tsx --maxWorkers=4`
- [ ] **Step 3: Implement** `InteractiveComposer`, the `Composer` button and `InboxPage` wiring.
- [ ] **Step 4: Run to verify pass**, then `npx vitest run src/app/Inbox src/app/api --maxWorkers=4`, then `npx tsc --noEmit && npm run lint && npm run build`.
- [ ] **Step 5: Commit** `feat(inbox): compose and send Cloud interactive messages from a chat`

---

## Manual check

Deploy per project-rules §2 (backend built with `-Pwith-frontend`). Open a chat, click "Interactive", build one message of each kind, watch the Preview and JSON tabs, send to your own number (named recipient, Mon–Sat 10:00–18:00 IST). Open the page link in a 375 px window, tap a button, and confirm the thread card updates to "Opened · 1" and "Yes · 1" without a reload.
