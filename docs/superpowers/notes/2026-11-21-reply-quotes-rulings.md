# Reply quotes: rulings and deferred minors

- Task 1: Ruling: Context.WaWeb/QuotedPreview are nested records of Context (not top-level types) — they only ever travel inside a context — none if wrong
- Task 2: Ruling: InboxIngestService keeps its 7-argument constructor (no quotes) for the tests that build it by hand, and gains an @Autowired one with QuotedMessages — WaWebWebhookListenerIT builds it directly — those hand-built services store no quotes
- Task 2: Ruling: a quoted message found by us gives its own type and its body, else the template snapshot's body; title only from a TEXT template header
- Task 2: Ruling: tests assert on stored rows (and the thread API in the Cloud test) rather than only the view
- Task 3: Ruling: bubble styles are inline (MessageBubble has no stylesheet), so the quote is styled inline with PatternFly tokens and the flash is a 1.5 s outline set by openQuoted (plus data-flash for tests) — plan named a stylesheet that does not exist
- Task 3: Ruling: the media-reply test wraps the bubble in SessionProvider (media bodies read the session), as the other inbox tests do
- Task 3: Ruling: a media quote with text shows the icon and the text (📷 Fees); without text the icon and the media name
- Final: Ruling (declined): originals in the same person's other chat (lid vs c.us) not found — spec says same chat — a few unresolved quotes after identity merges
- Final: Ruling (declined): context.wa_web on events forwarded to external webhook consumers — extra field, consumers ignore unknown fields — none expected
- Final: Ruling: Quoted.fromMe became Boolean (null = cannot tell) in the stored row, view and console — needed so an unknown sender shows no name — old rows have none

## Fixed after the final review

- Final: fixed I1 unknown quotes tell ours/theirs from the quoted sender against the number's own phone (Cloud: account display number); unknown → no sender line — unknownWaWebQuoteUsesItsText (+fromMe), unknownQuoteOfTheirsIsTheirs, unknownCloudQuoteKeepsOnlyTheId (+fromMe), unknownSenderShowsNoName RED→GREEN, suite 4006/4006 + console 290/290
- Final: fixed I2 quoted media prefers the caption, text passes MessageBodies.readable and is cut to 300 in the event; found originals' body also readable — quotedImageThumbnailIsNotItsText, longQuotedTextIsCutInTheEvent RED→GREEN, suite 4006/4006
- Final: fixed I3 an inserted message links the earlier replies in its chat that quote it (exact id or stanza suffix) — quoteResolvesWhenTheOriginalArrivesLater RED→GREEN, suite 4006/4006

## Deferred minors

- Final: minor (deferred): lookup uses the chatId index prefix, two queries for WhatsApp Web; could use {phoneNumberId, waMessageId}
- Final: minor (deferred): QuoteBlock is a tab-stop button even when nothing to open; Space not prevented; aria-label lacks the quoted text
- Final: minor (deferred): stored originals with no text and unlisted types (contacts, call, ptt, poll, media-header template) read "Replying to an earlier message"
- Final: minor (deferred): dead "+from"/"Contact" fallback in QuoteBlock sender line (contactName always set)
- Final: minor (deferred): sender colour token should be text--color--brand; quote background close to right-bubble shade — check by eye
- Final: minor (deferred): QuoteBlock import placed after consts in MessageBubble; duplicate ids if a thread is mounted twice
- Final: minor (deferred): 7-argument InboxIngestService constructor could be picked by a production caller
