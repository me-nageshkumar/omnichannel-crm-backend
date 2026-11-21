# Reply quotes in the inbox (short design)

2026-11-21. Small project, backend and console.

## Intent

A customer who swipes to reply to one of our messages sees it quoted on their phone; the inbox shows only the
reply ("Ok"). The user wants the inbox to **match WhatsApp's look**: inside the reply's bubble, a quote block with a
coloured bar on the left, the quoted sender in colour, the quoted template's header in bold, the quoted text cut to
two lines, then the reply (user's screenshot, 2026-11-21). Display only.

## What exists

- Both channels already deliver Meta's shape on the event: `messages[].context` = `{from, id}` for a quoted reply,
  `{forwarded: true}` / `frequently_forwarded` for forwards. Cloud: `id` is the quoted wamid (equals our stored
  `waMessageId`). WhatsApp Web: `WaWebWebhookConverter.context()` puts `quotedStanzaID` in `id` (our
  `waMessageId` for that message ends with `_<id>`), and the raw event also carries `quotedMsg {type, body}`.
- Nothing reads `context`: `InboxIngestService` builds `WaWebMessage` from `Incoming` without it; `MessageView` and
  `MessageBubble.tsx` have no quote.
- Message edits are not applied today (an edited message keeps its first text).

## Decisions

| # | Decision |
|---|---|
| D1 | Display only; agents sending quoted replies is a later project **(my call, stated to the user and not objected to)**. |
| D2 | Match WhatsApp's look (user's screenshot). |
| D3 | Approach **A**: a copy of the quote is stored on the reply when it arrives, with the quoted message's id (user's choice). |

## Approaches

- **A (chosen).** Resolve the quote at ingest and store a snapshot on the reply. Shows even when the original is
  gone or older than our history; no cost when a chat opens; an edit feature can later refresh quotes with one
  update by `quoted.messageId`.
- B. Store only the id, resolve on every chat read. Always current, but a lookup per chat open and WhatsApp Web's
  quoted text is lost for messages we never stored.
- C. Console matches among loaded messages only. No backend work; quotes of older messages missing.

## Design

**Backend** (my calls unless marked):
- `WaWebMessage.quoted: Quoted{messageId, waMessageId, fromMe, from, title, text, type}` and
  `forwarded: Boolean` (true also for frequently forwarded). Null when the message quotes nothing.
- `Incoming` gains the event's `context` (`id`, `from`, `forwarded`) and, for WhatsApp Web, the raw quoted text and
  type (`quotedMsg.body` / caption, `quotedMsg.type`), put on the converted event as
  `context.wa_web.quoted {type, body}` by `WaWebWebhookConverter`.
- At ingest (inbound and our own messages sent from the phone): look the quoted message up in the same chat —
  `waMessageId` equal to `id` (Cloud), or ending with `_<id>` (WhatsApp Web). Found: `messageId`, `fromMe` from its
  direction, `title` = its template snapshot's header text (Cloud templates) or null, `text` = its body or caption,
  `type` = its type. Not found: WhatsApp Web's quoted text and type; Cloud keeps only the id (`text` null).
- `text` is cut to 300 characters when stored (the console cuts to two lines).
- `MessageView.quoted` and `MessageView.forwarded` carry them to the console.

**Console:**
- `MessageBubble` draws the quote above the message text: a 4 px coloured bar, the sender line ("You" for our own,
  else the contact's name, else the number) in the accent colour, the bold header when there is one, the text in two
  lines with an ellipsis, media types as "📷 Photo", "📄 Document" etc.; a missing text reads "Replying to an earlier
  message". Colours from PatternFly tokens, light and dark.
- Clicking the quote scrolls to the original and flashes it when that message is loaded in the chat; otherwise it
  does nothing.
- "Forwarded" in small italic above a forwarded message.

**Non-goals:** sending quoted replies; edits; quotes inside the campaign and chatbot views; backfilling stored
messages (only messages that arrive after the deploy get quotes; the raw events are kept, so a backfill is possible
later).

**Verification:** backend ITs — a Cloud reply quoting our stored template shows its header and body; a WhatsApp Web
reply quoting a stored message by stanza suffix; one quoting an unknown message keeps WhatsApp Web's text; a Cloud
reply quoting an unknown id has `text` null; forwarded flag; our own phone-sent reply. Console vitest — quote block
with header, two-line text, "You", media label, missing text, click scrolls, Forwarded. Then a real-device check on
the test number (swipe-reply to `hello_world`) with the user.
