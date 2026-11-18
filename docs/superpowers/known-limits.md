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
  paused for the test contact and run first: a published Start chatbot rule on the same message starts its live run
  before the test captures the event, so the test's Run is skipped (or, with Replace, stops that live run, recorded as
  stopped by the tester "for a rule test").
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

## AI node

Spec `specs/2026-11-15-ai-node-design.md` (§3, §10), plus what the reviews found.

- v1 non-goals: only OpenAI (Claude and DeepSeek adapters are later projects); no platform-paid keys, wallets or cost
  figures; no file upload to vector stores; no OAuth for MCP servers (static bearer or header secret); customer images,
  audio and documents are not sent to the model; no streaming; no AI in the inbox; no platform defaults for AI.
- A turn that is superseded by a newer customer message is dropped, but its tokens are still billed.
- After the AI finishes (an outcome), later customer messages are left to the inbox (`pendingAfterFinish`); an exit
  word consumes the whole batch of waiting messages.
- OpenAI keeps stored responses for 30 days; a conversation older than that is rebuilt from our own record.
- A message that lands in the milliseconds between the last inbox check and the WhatsApp wait being saved waits for the
  next message (same for Ask).
- The inbox read starts one minute before the run: a message typed more than a minute before a jump or a delayed
  automation start is not fed to the AI.
- Replies pass through the WhatsApp formatter, so a model's `*italic*` becomes `_italic_`; say so in the profile
  instructions.
- A residual stage-then-stall race can leave an unsent reply in the AI's context (the next turn then knows what the
  customer never saw).
- Changing an MCP URL from `https://h` to `https://h:443` counts as a new address and asks for the secret again.
- A reply cut at the max output tokens is sent as it is (one cut before any text fails with `MAX_TOKENS`).
- A restart (or a lost lease) mid-turn re-runs the turn, and with it any app-side MCP tool call it had made but not yet
  staged with the result: a booking tool could run twice. Make such tools idempotent (e.g. key them on the run id).
- A JSON reply schema that is not strict-ready is sent to OpenAI with `strict: false`, so the model may leave fields
  out; a missing message field takes `failure` (`NO_MESSAGE_FIELD`).
- The `creationTime` watermark can skip a message inserted out of order by a concurrent writer.
- A connection holds one secret (the API key) plus the provider's plain settings (OpenAI: organization and project
  ids); providers that need a key pair are not supported. Settings are readable through the API, so never a secret.
- The API key pasted into a settings field is refused only when it is exactly that connection's key; any other
  secret typed there is stored and shown in plain text (the field's help text says not to).
- URL settings (no provider declares one yet) are checked at save only, and the private-address check follows the
  chatbot HTTP node's `allowPrivateHttp` switch; a provider that calls such a URL must check it again at call time.
- OpenAI has no per-connection base URL (`omnichannel.ai.openai.base-url` is app-wide, for tests).
- Reasoning effort values are listed per provider, not per model: a value the chosen model refuses fails the turn with
  the provider's message.

## Settings in MongoDB (appConfig): known limits

- The secrets sealing key is stored in the same database as the secrets it seals (`appConfig.secretsKeys`), so a
  database dump or backup can open every brand's AI key and MCP secret. Accepted until production (the user's choice,
  2026-11-16); before going live, move the key out (environment, a key file outside backups, or a secrets service) and
  remove `secretsKeys` from the document.
- Changes to `appConfig` apply after a restart; there is no settings page.
- When moving the key out, put the same key (same id) in its new place before the next start: a start that finds no
  key while sealed secrets exist only warns and saving AI keys answers 503 until the key is back.
- The startup read gives Mongo 3 seconds; a Mongo slower than that means no key and the default pages URL until the
  next restart (the app's own Mongo use then decides whether it starts at all).

## Subscription plans: known limits

Phases 1 (entitlements, 2026-11-17) and 2 (orders, payments, invoices, credit notes, refunds, PDF files, 2026-11-18)
and 3 (the usage wallet and per-message charges, 2026-11-18) are built.

- Payments are recorded by staff; there is no payment gateway. A refund is recorded after the money was paid
  outside the system.
- A quote is fixed for its validity (`quoteValidDays`): an order paid on its last valid day still credits and
  charges the days as counted on the day it was raised.
- A GSTIN is checked for its shape and its state, not its check digit. There is no e-invoicing (IRN, QR code) and no
  GST return export.
- A credit note may be issued against an invoice of any age; the law limits this by date. Confirm the limit with an
  accountant before going live. The list of GST state codes was written from memory and should be checked too.
- Issued invoices and credit notes never change: correcting the supplier details, the billing zone or a customer's
  profile affects later documents only. A PDF is drawn on its first download with the billing zone of that moment.
- The PDF font (DejaVu Sans) has Latin, Greek and Cyrillic and the rupee sign; a legal name or address in another
  script prints as question marks.
- A term given before orders existed (phase 1's direct assignment) was not paid for, so upgrading or cancelling it
  gives no credit.
- An activation that fails half way answers 500 and the order stays `ACTIVATING`; the next billing change for the
  customer or the ticker (within about 90 seconds) finishes it. Until then no other billing change for that customer
  goes through if the same step keeps failing.
- Two requests for the same add-on can both be raised; the second is refused as stale when it is paid.
- A new plan starts today or earlier, never later: the customer has the plan from the moment it is paid for.
- Document numbers read `INV/26-27/000001` (16 characters, the most GST allows), so a prefix has at most 3
  characters. An order is not paid if the GST rate, the supplier's state or the supplier details changed since it
  was priced (409 `ORDER_STALE` or `BILLING_NOT_SET_UP`): raise it again.
- An order whose activation keeps failing has no staff action to set it aside; it needs a look at the data.
- The wallet: a send whose outcome is unknown (the phone did not answer, the connection dropped after the message
  left, or the page reported an error of its own) stays charged, because the message may have gone; staff can give it back with an adjustment.
- The message rate is the one in the term's snapshot: a plan's new rate applies from the customer's next term.
- Wallet adjustments carry no tax and are not refundable; only what top-ups put in and usage has not spent is.
  Messages are taken to spend top-up money first, so goodwill added by staff is what is left last.
- A wallet refund interrupted before its money left the wallet is cancelled, not finished, if messages have since
  used that money; its line in the subscription's history stays.
- A top-up's GST is invoiced when it is paid, not as the balance is used. Confirm this treatment of an advance with
  an accountant before going live.
- The wallet survives a cancelled or expired plan; the balance waits. A top-up needs only a subscription, and is
  accepted on a plan that charges no messages if asked for through the API (the console does not offer it).
- The raw bridge calls for a super admin (`/api/phone-numbers/{id}/bridge/messages`, `/rpc`) do not go through
  the plan or the wallet: what staff send with them is not charged.
- Usage totals are by day in the billing zone, for at most 90 days back.
- A campaign paused for an empty wallet is not resumed by a top-up; a person resumes it.
- The low-wallet flag is read on `GET /api/users/me`; there is no e-mail or message when the wallet runs low.
- A customer sees its staff-raised orders (adjustments, cancellations) without staff's reasons, notes or names.
- With a paid renewal whose terms no longer offer an add-on, that add-on cannot be bought for the current term
  either. An add-on of extra units carried onto a plan with another unit size keeps its quantity.
- Lists of a customer's orders, invoices, credit notes and refunds are not paged.
- Staff may post account credit and cancel with any credit up to what was paid; nothing asks a second person to
  approve it. Every such act is an order with a reason in the history.
- Counting and creating are two steps: two requests at the same moment can end one over a limit.
- The entitlement cache is per server (30 seconds at most on a second server); every change on this server is seen at
  once.
- A limit lowered below what a customer has keeps everything; only new ones are refused. Disabling a user's login
  frees its place; switching off its access to one brand does not.
- The per-brand CRM switch is ignored for a customer with a plan, and such a customer's brand gets its CRM settings
  document the first time anything asks whether its CRM is on.
- Losing a feature stops its work at the next message: a waiting chatbot run fails, a running campaign waits (it
  stays RUNNING and goes on when the plan covers campaigns again), queued automation actions fail.
- A customer's test runs of chatbots and automation rules are gated like real ones.
- The two Super Admin bridge endpoints (`/api/phone-numbers/{id}/bridge/messages` and `/bridge/rpc`) are outside
  every gate.
- In read-only mode a CRM reminder cannot be marked seen, an AI connection cannot be verified and a profile's MCP
  tools cannot be listed (those handlers write or call out).
- Changing the billing zone does not move the terms already stored; they keep the midnights of the zone they were
  assigned in.
- Built-in feature groups cannot be deleted (they would be seeded again); they can be renamed, emptied or, except
  Core, moved between default and paid while no plan lists them.
