# Reply quotes in the inbox — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reply that quotes a message shows the quote inside its bubble, the way WhatsApp does, on both kinds of
number; a forwarded message says "Forwarded".

**Architecture:** The webhook `Context` already reaches ingest. Ingest resolves the quoted message in the same chat
and stores a `Quoted` snapshot plus a `forwarded` flag on the new message; `MessageView` carries them; the console's
`MessageBubble` draws the quote block.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB; React 18, PatternFly 6.5, vitest.

**Spec:** backend `docs/superpowers/specs/2026-11-21-reply-quotes-design.md`.

## Global Constraints

- Backend worktree `omnichannel-crm-backend/.worktrees/reply-quotes` (branch `reply-quotes`); console worktree
  `omnichannel-crm-frontend/.worktrees/reply-quotes` (same branch name), created at Task 3.
- Display only; no sending of quoted replies; no backfill of stored messages.
- `Quoted.text` stored cut to **300** characters; the console shows two lines with an ellipsis.
- Missing quoted text reads exactly "Replying to an earlier message". The forward mark reads exactly "Forwarded".
- Media labels: image "📷 Photo", video "🎥 Video", audio "🎤 Audio", document "📄 Document", sticker "Sticker",
  location "📍 Location"; other types show their text or the missing-text line.
- Test first; full `./mvnw -q verify` and the console's `npx vitest run`, `npx tsc --noEmit`, `npm run lint`,
  `npm run build` once before merge. Prettier on touched files only.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A quote whose id matches a message of **another chat** (stanza ids can repeat across chats): never used — the
   lookup is limited to the reply's own chat (Task 2 test `quoteFromAnotherChatIsNotUsed`).
2. A reply processed **twice** (webhook retry, history import of the same message): the second pass keeps the same
   quote, no error (Task 2 test `reIngestKeepsTheQuote`).
3. A WhatsApp Web stanza id that is a suffix of an unrelated id (`_ABC` vs `_XABC`): matched only on the full
   `_<id>` ending (Task 2 test `stanzaSuffixMustBeWhole`).
4. A quote of one of our own media messages: shows the media label, not an empty line (Task 3 test
   `mediaQuoteShowsItsLabel`).
5. A reply that is itself media with a caption: the quote sits above the media, the caption below (Task 3 test
   `quoteOnMediaReply`).

---

### Task 1: Carry WhatsApp Web's quoted text in the event's context

**Files:**
- Modify: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/whatsapp/webhook/Context.java`
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/channel/whatsapp/WaWebWebhookConverter.java:357-369`
- Test: the converter's existing unit test (`WaWebWebhookConverterTest`, under `omnichannel-service/src/test`)

**Interfaces:**
- Produces: `Context(String from, String id, Boolean forwarded, Boolean frequentlyForwarded, WaWeb waWeb)` with
  nested `record WaWeb(QuotedPreview quoted)` and `record QuotedPreview(String type, String body)`; JSON
  `context.wa_web.quoted.{type, body}`. A 4-argument constructor stays for existing callers.
- `quoted.type` uses the converter's existing WhatsApp Web → Meta type mapping (`chat` → `text`, `image` → `image`, …);
  `body` is `quotedMsg.body`, else `quotedMsg.caption`, else null.

- [ ] **Step 1: Failing tests** — `quotedTextTravelsInContext()`: raw with `quotedStanzaID "3B5D"`,
  `quotedParticipant` a user, `quotedMsg {type: "chat", body: "Good morning Mam"}` → event message
  `context.id == "3B5D"`, `context.wa_web.quoted.type == "text"`, `.body == "Good morning Mam"`;
  `quotedImageCaption()`: `quotedMsg {type: "image", caption: "Fees"}` → `type "image"`, `body "Fees"`;
  `noQuoteNoWaWeb()`: a forwarded message without a quote has no `wa_web` in its context.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service -am test -Dtest='WaWebWebhookConverterTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(inbox): WhatsApp Web quoted text in the event context`.

---

### Task 2: Store the quote and the forward flag at ingest, and show them in the view

**Files:**
- Modify: `omnichannel-db/.../db/inbox/WaWebMessage.java` (fields `Quoted quoted`, `Boolean forwarded`;
  `record Quoted(String messageId, String waMessageId, boolean fromMe, String from, String title, String text,
  String type)`)
- Create: `omnichannel-service/.../service/inbox/QuotedMessages.java` (`@Component`)
- Modify: `omnichannel-service/.../service/inbox/InboxIngestService.java` (`Incoming` gains `Context context`;
  `ingestInbound`/`ingestEcho`/the two import paths pass `message.context()` / `echo.context()`; the message builder
  at ~line 580 sets `quoted` and `forwarded`)
- Modify: `MessageView.java` + `InboxViews.java:75` (fields `QuotedView quoted`, `Boolean forwarded`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/ReplyQuotesIT.java` (WhatsApp Web through
  the bridge ingest, as other inbox ITs do) and `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/meta/CloudReplyQuotesIT.java`
  (Cloud webhooks, extends `MetaTestBase`)

**Interfaces:**
- Consumes: Task 1 `Context.waWeb().quoted()`.
- Produces: `QuotedMessages.resolve(String chatId, Context context): WaWebMessage.Quoted` (null when `context` has no
  id). Lookup in `waWebMessages` with `chatId` = the reply's chat and `waMessageId` equal to `id`, or (when none)
  ending with `"_" + id` (anchored regex on the quoted id, escaped). Found: `messageId`, `waMessageId`,
  `fromMe = direction OUT`, `from = context.from`, `title` = the found message's `template.header` text when its
  format is TEXT (the Cloud template snapshot), `text` = its body (else media caption), `type` = its type. Not
  found: `waMessageId = id`, `fromMe = false` unless `from` is the number itself, `text`/`type` from
  `context.waWeb.quoted` (null for Cloud). `text` cut to 300 characters.
- `forwarded = TRUE` when `context.forwarded` or `context.frequentlyForwarded`, else null.
- `MessageView.QuotedView(String messageId, boolean fromMe, String from, String title, String text, String type)`;
  JSON `quoted`, `forwarded` (absent when null).

- [ ] **Step 1: Failing tests**
  - `CloudReplyQuotesIT.replyQuotesOurTemplate()`: send `hello_world`-shaped template (header "Hello World", body
    text) from the inbox, then an inbound webhook text "Ok" with `context {from: "15551833836", id: <its wamid>}` →
    the stored "Ok" has `quoted.messageId` = the template message's id, `fromMe true`, `title "Hello World"`,
    `text` starting "Welcome and congratulations"; GET of the chat's messages returns the same `quoted`.
  - `CloudReplyQuotesIT.unknownCloudQuoteKeepsOnlyTheId()`: context id not stored → `quoted.waMessageId` set,
    `messageId` null, `text` null.
  - `ReplyQuotesIT.waWebReplyQuotesStoredMessage()`: a stored message `true_919…@c.us_3B5D`, then an inbound reply
    with `context {id: "3B5D", from: <number>}` → `quoted.messageId` = it, `fromMe true`, `text` its body.
  - `ReplyQuotesIT.unknownWaWebQuoteUsesItsText()`: no stored message, `context.wa_web.quoted {type: "text",
    body: "Good morning Mam"}` → `quoted.text "Good morning Mam"`, `type "text"`, `messageId` null.
  - `ReplyQuotesIT.forwardedIsMarked()`: `context {forwarded: true}` → `forwarded true`, `quoted` null.
  - `ReplyQuotesIT.quoteFromAnotherChatIsNotUsed()` (Review Focus 1): the only stored `_3B5D` is in another chat →
    `messageId` null.
  - `ReplyQuotesIT.reIngestKeepsTheQuote()` (Review Focus 2): the same reply ingested twice → one row, same quote.
  - `ReplyQuotesIT.stanzaSuffixMustBeWhole()` (Review Focus 3): stored `…_X3B5D` only → no match.
  - `ReplyQuotesIT.longQuoteIsCut()`: quoted body of 500 characters → stored 300.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='ReplyQuotesIT,CloudReplyQuotesIT' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the two plus `InboxIngest*IT,CloudSendIT,CloudTemplatesIT,MetaWebhookIT` → PASS.
- [ ] **Step 5: Commit** — `feat(inbox): store reply quotes and forwards at ingest`.

---

### Task 3: Quote block and Forwarded mark in the console

**Files:**
- Modify: `omnichannel-crm-frontend/src/app/api/inbox.ts` (`MessageView.quoted?: QuotedView | null`,
  `forwarded?: boolean | null`)
- Create: `src/app/Inbox/QuoteBlock.tsx`
- Modify: `src/app/Inbox/MessageBubble.tsx` (root element `id={`message-${message.id}`}`; `Forwarded` line; quote
  above the body or media)
- Modify: the inbox stylesheet that styles bubbles (quote: 4 px left bar, accent sender line, tinted background,
  rounded; flash class for 1.5 s)
- Test: `src/app/Inbox/quoteBlock.test.tsx`

**Interfaces:**
- Consumes: Task 2 JSON `quoted {messageId, fromMe, from, title, text, type}`, `forwarded`.
- Produces: `QuoteBlock({ quoted, contactName, onOpen })`; sender line = "You" when `fromMe`, else `contactName`,
  else `+${from}`; `quoteLabel(quoted): string` (media labels in Global Constraints, else text, else
  "Replying to an earlier message"). `onOpen` scrolls `#message-<messageId>` into view and adds the flash class when
  it exists; nothing otherwise.

- [ ] **Step 1: Failing tests**
  - `quoteShowsSenderHeaderAndText()`: `fromMe`, title "Hello World", long text → "You", bold "Hello World", text
    element has the two-line clamp class.
  - `contactQuoteShowsTheirName()`: not `fromMe`, contact "Nagesh Kumar" → "Nagesh Kumar".
  - `mediaQuoteShowsItsLabel()` (Review Focus 4): `type "image"`, no text → "📷 Photo".
  - `missingTextSaysEarlierMessage()`: no text, type null → "Replying to an earlier message".
  - `clickScrollsToTheOriginal()`: an element `#message-m1` in the document → click calls its `scrollIntoView` and
    adds the flash class; with no such element nothing throws.
  - `forwardedMarked()`: `forwarded true` → "Forwarded" above the body.
  - `quoteOnMediaReply()` (Review Focus 5): an image reply with caption and a quote → quote before the image, caption
    after.
  - `noQuoteUnchanged()`: a message without `quoted`/`forwarded` renders as before (snapshot of existing test data).
- [ ] **Step 2: Run** `npx vitest run src/app/Inbox/quoteBlock.test.tsx` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx vitest run src/app/Inbox`, then `npx tsc --noEmit`, `npm run lint`, `npm run build` → green.
- [ ] **Step 5: Commit** — `feat(inbox): quote block and Forwarded mark`.
