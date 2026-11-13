# Known limits and parked work

Behaviour a future reader might take for a bug, and features parked by the user. Each limit needs a decision or more
investigation before it becomes a task; decisions already made live in the specs, history in git. The rules and
commands are in `project-rules.md` next to this file. Nothing is queued: the CRM port, the inbox work, Automations
(spec `specs/2026-11-03-automations-design.md`), click-to-WhatsApp ad leads (spec
`specs/2026-11-04-ctwa-leads-design.md`) and one contact across two ids (spec `specs/2026-11-04-one-contact-design.md`)
are merged and deployed.

## Checks still owed by the user

- Automations end-to-end walk (console plan `plans/2026-11-03-automations-console.md`, Task 9): build a "Welcome (test)"
  rule, test it on a contact the user names, publish, move back to draft.
- Click-to-WhatsApp ad check (console plan `plans/2026-11-04-ctwa-leads-console.md`, Task 4): the user taps one of
  their own ads from a phone they name; confirm the inbox line, the CRM origin and a draft rule "Ad is yes".
- One-contact check (console plan `plans/2026-11-04-one-contact-console.md`, Task 3): count the demo brand's contacts
  lacking a phone or a hidden id, press "Resolve hidden numbers" as demo-manager and compare; then write from a phone the
  user names with the number hidden and confirm one contact, the "WhatsApp ids" row and the header's other-chat line.
- Cloud webhook shape check (backend plan `plans/2026-11-05-cloud-webhook-shape-backend.md`, "Verification before the
  merge"): after the deploy, a text, a photo and a voice note from a phone the user names appear in the inbox as
  before; `db.channelWebhookEvents.find().sort({_id:-1}).limit(3)` shows the events; and
  `db.waWebMessages.distinct("type")` has no `chat`, `ptt`, `vcard`, `multi_vcard` or `unknown`.
All need the demo-manager password typed into the session. Never message anyone the user has not named.

## Parked features

- **Facebook Lead Ads conversion.** Parked by the user on 2026-11-04: there is no Facebook (Meta) app yet, so no lead
  intake, webhook or page connection can be built. Reference when the time comes: `redlava-crm/.../leadconversion/`,
  `LeadChannelDefault`, and the historical intake design
  `whatsapp/docs/superpowers/specs/2026-08-05-facebook-leads-management-design.md` (read its header). Our side already
  has `OriginKind.FACEBOOK_LEAD` / `WEBSITE_LEAD` and `CaptureService.findOrCreate(…, Origin, …)`. Click-to-WhatsApp ad
  leads shipped on 2026-11-04 (spec `specs/2026-11-04-ctwa-leads-design.md`) and need no Meta app. Website-form leads
  would need the same decisions (a leads collection, intake, a screen, the number choice) and are not asked for.

## Known limits of the inbox, not tasks

The user chose (2026-11-03) to treat these as known limits to come back to later, like the features above. Each needs
a decision or more investigation.

- The same person can be two chats (`@c.us` and `@lid`) but is one contact; merging the threads is a possible
  follow-on. A link to a chat the agent cannot open shows the inbox's error.
- A console send whose model never came back is stored as `console:<id>` and again under its real id.
- The console shows poll votes but cannot vote.
- The buttons of a business interactive message travel in the stored webhook event (`interactive.action.buttons`); the
  row keeps the text only.
- WhatsApp's own notices are not stored: showing their words needs a table of subtypes (each skipped one logs its
  subtype, the place to start).
- A dated fetch leaves out chats last active over a day before its range; whether `chat.t` always covers a chat's
  newest message is unconfirmed. Pending real-number checks: spec `2026-10-31-chat-history-import-design.md`.

## Cloud messages and message pages: known limits

Spec `specs/2026-11-12-cloud-messages-design.md`. Interactive Cloud messages go over the bridge as a link to a page.

- A send the device refuses after the page was created leaves an orphan `messagePages` row (no `messageId`); it is
  never linked to anyone and expires with the rest.
- A typed reply is matched to a button or row by title within 48 h, best effort: a customer who edits the prefilled
  wa.me text is not matched.
- `{link}` media headers are rejected; upload the file to `/channel-accounts/{id}/media` and use its id.
- Document uploads are capped at 16 MB (the app's multipart cap), not Meta's 100 MB.
- Campaigns and automations send saved templates, so interactive messages reach them with the template project;
  Meta templates and the carousel (template-only in the Cloud API) are that project too.
- The real-device link preview needs a public HTTPS origin in `OMNICHANNEL_PAGES_BASE_URL`; nothing is deployed, so
  that check is owed by the user, behind a tunnel.

## Advanced chatbot: known limits

Spec `specs/2026-11-13-advanced-chatbot-design.md`. A run belongs to a contact; every wait is a task `(kind, key)`.

- A run started without a chat (a deal event whose contact never wrote) fails at its first messaging node unless the
  contact has a chat somewhere in the brand; the run then adopts that chat.
- A form link goes out over WhatsApp only: the form is the first non-WhatsApp task kind, email is a later project.
- A phone-typed agent reply does not hand the chat over: the device echoes the chatbot's own messages as WhatsApp Web
  messages before the send result comes back, so only console replies count. Agents on the phone stop the bot from the
  inbox.
- Replies are matched to a button or row by title (the project's `bare()`), best effort; over a Cloud channel the
  reply id would be used.
- Page taps on an interactive message page do not drive the flow; the WhatsApp reply does.
- A run interrupted mid-node (the app stopped) is failed by the ticker once its lease runs out, never resumed: the
  node may have sent.
- One ticker thread serves every brand; the run pool is four threads.
- HTTP request nodes follow no redirects (a 3xx is a failure), refuse private, loopback, link-local and carrier-NAT
  addresses, and read at most 1 MB.
- Versions are pruned to the newest 50 (never one an active run points to); runs and forms have no retention.
- The Start chatbot step says "still running" after 60 s and the rule goes on; the chatbot finishes on its own pool.
- A chatbot moved to draft leaves live runs finishing on their version; new starts fail "is not published".
- The rule test is the way to test a chatbot on its event (spec `2026-11-13-chatbot-in-rule-test-design.md`): Run on
  the Start chatbot step opens the chatbot's live view. The rule test keeps idling out 30 min after its last step, so a
  long chatbot test may find the rule test ended on return (the chatbot run is unaffected). Published rules are not
  paused for the test contact: a published Start chatbot rule on the same message finds the test run active.
- The editor's live view polls the run every 2 seconds; the inbox chip rides on the chat stream.
- A form's token lives max(24 h, the chatbot's inactivity end) and closes with its run.
- Console: the run panel draws a run over the chatbot's current flow, so steps of a node removed since show
  "(removed)"; variable suggestions after `{{` show the first eight matches; the Send message node's per-kind drafts
  live only while the drawer is open (switching kind and back keeps them, closing the drawer does not).
- Console: run settings are saved with the flow (the Save button), not on their own.

## Automations: known limits

- An event is lost if the app stops between saving a message or a move and writing its event.
- A hidden-id chat whose phone is not yet resolved can be new once more.
- A typed test number whose first message arrives with a hidden number is never captured (use an existing contact
  with "Treat this chat as new").
- A crash mid-rule marks an unfinished send Interrupted and runs the rest of the rule.
- A send that timed out is not retried (it may have gone); only a send that never reached the extension is.
- No retention setting: nothing expires.
- No lease renewal while a long event is processed.
- A published rule can be moved to draft while a retry waits, which closes its run as Interrupted.
- Message text and the contact's name and phone stay for good in `automationEventHistory` and `automationRuns`:
  removing a number or a contact leaves them.
- One worker thread serves every brand, so a hung extension delays all brands by up to the send timeout per send.

## Contacts across two ids: known limits

- Resolution needs the number's device online.
- A person whose privacy hides the phone stays a hidden-number contact only.
- A stalled merge is retried only by "Resolve hidden numbers".
- An automation test's target contact and a CRM record left after a conflict can point at a deleted contact
  ("Contact deleted" is shown).
- The hourly cap and the pending set reset on restart.

## Click-to-WhatsApp ads: known limits

- The `ctwaContext` field names of a real ad are unconfirmed until the end-to-end check, which needs the user to tap
  one of their own ads from a named phone. The live tab (2026-11-05, `whatsapp-web-storage-analysis.md` §2.3) showed
  the object on a business's link preview with `sourceUrl`, `title`, `description`, `thumbnail`, `thumbnailUrl`,
  `mediaType` (a number) and `mediaUrl` only, so an ad now needs `sourceId`, `conversionSource` or a click id; a
  context with none is logged by its field names. If the real ad carries none of those, fix
  `WaWebWebhookConverter.referral` from that log line and the stored `adClick.raw`.

## WhatsApp Web events as Cloud webhooks

Spec `specs/2026-11-05-cloud-webhook-shape-design.md` (§7), plus what the reviews found.

- Inbound rows carry no ack or status; the stored webhook event has it in `wa_web.ack`.
- A real Cloud `contacts` message would render nothing: it has no vCard text, and our rows get their text from the
  vCard.
- The page does not forward reactions or edits. They map to Meta's `reaction` and to the echo's `edit` / `revoke`
  when they do arrive.
- `poll_vote` and its deterministic id (`vote:<poll>:<voter>`) are ours; Meta has no such message.
- The webhook store doubles the writes per message (the event, then the row). `omnichannel.webhooks.retention` bounds
  it (30d).
- `WaWebMessage.Media.mediaId` is the GridFS cache id; a Cloud media id has no field yet.
- A real Meta body (no `wa_web`) stores no `Media`, because `media_available` is absent.
- A `rich_response` with text starts TEXT automations; a textless one is `unsupported`.
- Raw types Meta has no name for store `unsupported` ("Message to view on the phone").
- `product` keeps its caption through `wa_web.body`.
- The migration (`MessageTypeMigration`) renames only the table in spec §4.7; older `rich_response` and `product`
  rows keep their names.
- The console accepts the old and the new type names for one release; console plan Task C2 drops the old ones.
- `_id` of a webhook event is the event's time, not the insertion time; `creationTime` is. The time is in seconds, so
  events of one second sort by the order they were generated in, and two changes of one voter's poll vote within
  one second are stored once (`vote:<id>:<timestamp>`), though both are processed.
- A poll vote's time is kept to the second (the webhook `timestamp`), so a live vote and an imported vote of the same
  voter within one second may be ordered wrongly; main kept milliseconds.
- A fetched message's times come from WhatsApp's receipts; when WhatsApp has none for a state the row's ack implies,
  the earliest real receipt of a later state is used (else the fetch time) and the event says `estimated`. The row's
  times come from real receipts only: an estimated event moves the status but never stamps a time, so the console shows
  ticks without a time for it, and `statusTimes.estimatedAt` (the fetch time) records that the fetch tried, so the next
  fetch does not ask again.
