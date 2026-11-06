# WhatsApp Web events as Cloud API webhooks — design

Date 2026-11-05. The user wants the CRM to be ready for the WhatsApp Cloud API: the extension keeps sending what
WhatsApp Web gives it, as it is; the backend turns each such event into the shape of a Cloud API webhook; and
everything after that point works on the Cloud shape only. The user skipped the brainstorming questions (the standing
choice); every decision marked **(my call)** is the builder's and can be reversed.

The analysis behind this document is in §3; the live WhatsApp Web tab was read afterwards and its findings are in
`../whatsapp-web-storage-analysis.md` and §6 here. The raw field names in §4 are confirmed against that build
(2.3000.1053060988) unless §6 says otherwise.

## 1. Intent

Today the backend reads WhatsApp Web's raw message model in three places (the live event path, the console's send
result, the history import) through one helper, `MessagePayloads`, and stores rows whose `type` vocabulary is
whatsapp-web.js's (`chat`, `ptt`, `vcard`, `poll_creation`, `call_log`). A Cloud API number would deliver webhooks in
Meta's shape (`messages`, `statuses`, `smb_message_echoes`) with Meta's types (`text`, `audio`, `contacts`). Two
vocabularies would then meet in the inbox, the automations and the reports.

This feature puts one conversion step between the bridge and the rest of the application. WhatsApp Web's events
become Cloud API webhook events; the inbox ingest, and through it the automations, campaigns, routing, CRM capture and
the console, consume the Cloud shape only. When a Cloud API number is added later, its webhooks enter the same
processor unchanged, and the stored messages of both kinds of number look the same.

Nothing a person sees changes: the inbox, the previews, the automations and the reports behave as before.

## 2. Goals, non-goals, anti-goals

Goals
- A typed Java model of the Cloud API webhook (`object` / `entry` / `changes` / `field` / `value`), with Meta's exact
  field names, that deserialises a real Meta webhook body as well as our converted events.
- A converter from every conversation event of the extension (`message_create`, `message_ack`, `vote_update`) and from
  the two other raw-model entry points (the send result, the history pages) to that model. After this feature exactly
  one class reads the raw WhatsApp Web model.
- A processor that takes a webhook event and does what `WaWebInboxListener` + `InboxIngestService` do today, with the
  same stored rows, the same published `InboxEvents`, the same acknowledgement contract with the extension.
- Every converted webhook event is kept in a collection with a retention period, as the reference application kept
  Meta's (`whatsapp.metaWebhook`), so a field whose shape was guessed can be fixed from stored data (the lesson of
  `ctwaContext`, see `2026-11-04-ctwa-leads-design.md` §3).
- The stored `type` of a message uses Meta's vocabulary where Meta has the type; existing rows are migrated once.

Non-goals (later features)
- Receiving real Cloud API webhooks (the controller, signature check, Meta app, media download through the Graph API,
  sending through the Cloud API). This feature makes the shape and the processor ready; it adds no Cloud channel.
- Events the page does not forward today: reactions, edits, revokes as separate events, group chats, read receipts of
  inbound messages. Their Cloud shapes are named in §4 so the page can grow into them.
- Rendering a Cloud `contacts` message that carries no vCard text (§4.3): the console still renders the vCard body.
- Changes to the extension. It keeps sending the raw model; the "as is" form the user asked for is what arrives today.

Anti-goals
- No visible change for agents: the inbox, previews, automation conditions and reports must look exactly as before.
- Never fail an event because a field the converter did not expect is present or absent: unknown shapes become the
  nearest Cloud type (`unsupported` at worst) and the raw payload is kept.
- Never lose a message to the conversion: the acknowledgement to the extension is sent only after the processor has
  stored the message, exactly as today.

## 3. Facts the design rests on (the analysis)

### 3.1 What the extension sends

- The page subscribes to WhatsApp's `Msg` collection and the socket state (`src/page/events.js`) and emits the
  whatsapp-web.js event names: `message_create` (every new message, both directions), `message` (inbound only, a
  duplicate of `message_create`, ignored by the backend), `message_ack` (`{message, ack}`), `change_state`,
  `vote_update` (`{pollMessageId, voter, fromMe, options[], at}` from `voteOf` in `polls.js`), plus `ready` and
  `disconnected` from `inject.js`.
- The payload of a message event is `WWebJS.getMessageModel(msg)`: WhatsApp's own `message.serialize()` with a few
  additions (`links`, `isEphemeral`, buttons serialised, `id._serialized` normalised). The page sends the whole
  model; the backend keeps what `MessagePayloads` extracts.
- Durable events (`message_create`, `message`, `message_ack`, `vote_update`) travel with an outbox sequence number
  `seq`, a capture time `ts` and the account digits `acct`; the backend acknowledges `seq` only after
  `publisher.publishEvent(new WhatsAppEvent(...))` returned without `IngestionFailedException`
  (`BridgeWebSocketHandler.handleEvent`). An event that failed to store three times is acknowledged anyway and logged.
- Message keys and WhatsApp ids may arrive with `_serialized` or with `$1` (the 2026-07 WhatsApp Web build); the page
  normalises keys (`normalizeMessageModel`) and the backend rebuilds ids from parts when neither is present
  (`LoggingEventListener.wid`, `MessagePayloads.waMessageId`).

### 3.2 Who consumes the events today

| Consumer | Events | Role |
|---|---|---|
| `WaWebInboxListener` → `InboxIngestService` | `message_create`, `message_ack`, `vote_update` | the only path that writes messages; reads the raw model through `MessagePayloads` |
| `InboxSendService.afterSend` | the `sendMessage` RPC result (a raw model) | stores the console's own message from the returned model |
| `HistoryImportRunner` → `InboxIngestService.importMessage` | `fetchChatHistory` pages (raw models) | the history import |
| `SenderEventsListener` | `change_state`, `ready` | campaign sender state; device events, not conversation events |
| `DeviceSession` (via the handler) | `ready`, `change_state`, `disconnected` | device state |
| `LoggingEventListener`, `RecentEventsStore` | all | logs and the debugging endpoint |

Everything after `InboxIngestService` (`InboxEvents` listeners: routing, push, automations, campaigns, CRM capture,
contact resolution, media) works on stored rows and is untouched by this feature.

### 3.3 What the Cloud API sends (19 300 real webhooks in `whatsapp.metaWebhook`, 2024-07 to 2026-11)

- Shape: `{object: "whatsapp_business_account", entry: [{id: <WABA id>, changes: [{field, value}]}]}`. The
  reference application stored them after Jackson mapping onto Java classes, so that collection holds mixed
  camelCase (`phoneNumberId`, `waId`, `recipientId`, `mimeType`) and snake_case; this design uses **Meta's wire names**
  (snake_case), not the reference model's.
- `field` breakdown: `messages` carries both inbound messages (`value.messages[]` with `value.contacts[]`) and the
  statuses of our messages (`value.statuses[]`: `delivered` 5 615, `sent` 4 594, `read` 4 301, `failed` 261,
  `captured`/`pending` for payments, `played` 1). Business-sent messages never appear in `messages`; a message sent
  from the WhatsApp Business app or a linked device arrives as `field: "smb_message_echoes"` with
  `value.message_echoes[]` (87 of them). Other fields seen: `calls` (178), `account_settings_update`, `flows`,
  `user_preferences`, `group_*`, `template_*`.
- Inbound message types seen: `text`, `interactive` (`nfm_reply`, `call_permission_reply`), `image`, `button`,
  `order`, `document`, `location`, `reaction`, `unsupported` (with `errors[]`), `video`, `audio` (`voice: true`),
  `contacts`, `system` (`user_changed_number`). Common fields: `from`, `id` (`wamid.…`), `timestamp` (seconds, as a
  string), `type`, the type-named object, `context` (`from`, `id` of the quoted message, or `forwarded: true`),
  `referral` (a click-to-WhatsApp ad: `source_url`, `source_id`, `source_type`, `headline`, `body`, `media_type`,
  `ctwa_clid`, `welcome_message`), and since 2026 `from_user_id` with `contacts[].user_id` (Meta's user ids,
  `IN.1976699579621058`), the Cloud analogue of WhatsApp Web's `@lid`.
- Status objects: `id`, `status`, `timestamp`, `recipient_id`, `recipient_user_id`, `conversation {id, origin.type,
  expiration_timestamp}`, `pricing {billable, pricing_model, category, type}`, `errors[{code, title, message,
  error_data.details, href}]`.
- Meta's references: `developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages.md`,
  `…/messages/status.md`, `…/messages/<type>.md`, `…/smb_message_echoes.md` (index: `…/whatsapp/llms.txt`).

### 3.4 Where the two worlds differ

| WhatsApp Web (what arrives) | Cloud API (what we convert to) |
|---|---|
| one event per message, both directions, with `ack` on it | inbound in `messages[]`; our own only as `message_echoes[]`; delivery state as `statuses[]` |
| ids `true_919…@c.us_3EB0…` | opaque `wamid.…`; ours stay as they are |
| a chat is `remote` (`@c.us` phone or `@lid`); the phone behind a lid may come as `senderPn` | no chat: `from` (digits) and `from_user_id`; `contacts[]` names the person |
| types `chat`, `ptt`, `vcard`/`multi_vcard`, `poll_creation`, `call_log`, `revoked`, `biz_content_placeholder`, `unknown`, `newsletter_follower_invite`, business `interactive`/`list`/`hsm` | `text`, `audio` (`voice`), `contacts` (parsed cards), `unsupported`; no polls, calls, revokes, channel invites, business-authored interactive messages |
| ad context `ctwaContext` on the message | `referral` |
| media: `directPath`, `mediaKey`, `mimetype`, `filehash` (base64 SHA-256), `size`, `filename`, `caption` | `{id, mime_type, sha256, caption, filename}`; the bytes come from the Graph API, not from the page |
| poll votes as their own event | nothing; the nearest precedent is `reaction`, a message about another message |
| ack of inbound messages too | statuses exist only for business-sent messages |
| device events (`ready`, `change_state`, `disconnected`) | none that matter here |

## 4. The shape

### 4.1 Envelope **(my call)**

One bridge event becomes one webhook event, Meta's wire shape and names:

```json
{
  "object": "whatsapp_business_account",
  "entry": [{
    "id": "<phoneNumberId>",
    "changes": [{
      "field": "messages",
      "value": {
        "messaging_product": "whatsapp",
        "metadata": { "display_phone_number": "919928088743", "phone_number_id": "<phoneNumberId>" },
        "contacts": [ ... ], "messages": [ ... ], "statuses": [ ... ], "message_echoes": [ ... ]
      }
    }]
  }]
}
```

- `object` says where the event came from: `whatsapp_web_account` for an event we converted from WhatsApp Web,
  `whatsapp_business_account` for a real Cloud API one. `entry[].id` is Meta's business-account id; ours is the
  workspace id (the brand, the analogue of the business account); the number is `metadata.phone_number_id`. `display_phone_number` is the number's digits.
- One bridge event may yield two changes in one entry: an echo (`field: "smb_message_echoes"`) and the status it
  already carries (`field: "messages"` with `statuses`), see §4.4. Meta sends one change per webhook; the arrays allow
  more. The processor handles an **entry** as a unit: a status whose `id` matches an echo in the same entry is
  applied while the echo is stored, so the row is inserted with its status and one set of `InboxEvents` is published,
  exactly as `storeMessage` does today. A status with no echo in its entry is processed on its own.
- The typed model: records with Jackson's snake-case naming, `@JsonIgnoreProperties(ignoreUnknown = true)`, nulls left
  out on the wire. A real Meta body (any of the 19 300) must deserialise into it without error; the test fixtures use
  a dozen real ones (numbers replaced).

### 4.2 The extension object `wa_web` **(my call)**

Meta's objects carry only Meta-defined fields. What WhatsApp Web knows and Meta's shape has no place for goes into
one object `wa_web` on the message, echo or status, so a reader can tell at a glance what a real Cloud webhook would
not have:

| `wa_web` field | On | What |
|---|---|---|
| `chat_id` | message, echo, status | the WhatsApp Web chat key (`919…@c.us` or `1480…@lid`); the processor's chat identity, exactly today's `remoteId` |
| `ack` | message, echo | WhatsApp Web's ack integer, kept for logging and the stored `ack` column |
| `original_type` | message, echo | the whatsapp-web.js type before mapping (`ptt`, `vcard`, `list`, …) |
| `file_size` | media messages | bytes; Meta has no size |
| `media_available` | media messages | true when the model carries `directPath` (today's `hasMedia` rule): the page can fetch the bytes. False for expired and view-once media, whose type object still describes them |
| `business_tag` | message | WhatsApp's `hsmTag` (`AUTHENTICATION`, `UTILITY`, …) |
| `ctwa_context` | message | the raw ad context minus images and conversion data, as `AdClick.raw` is kept today |
| `ctwa_source_app`, `ctwa_conversion_source` | message | `ctwaContext.sourceApp` and `conversionSource`, which `AdClick` stores and the console and CRM show; Meta's `referral` has no place for them |
| `vcards` | `contacts` messages | the vCard texts, for the console's card rendering |
| `captured_at` | message, echo, status | the extension's capture time (ms), when it had one |
| `body` | `unsupported`, `album`, an `order` without a title | what the person wrote (the caption, else a body that is no thumbnail) when Meta's shape has no place for it; the store reads it as the row's body |

Types Meta does not have keep WhatsApp Web's name as `type` and get a type-named object, following Meta's own
convention (the object is named after the type): `poll_creation`, `poll_vote`, `call_log`, `revoked`,
`biz_content_placeholder`, `newsletter_follower_invite`. These are the **extension types**; everything else is Meta's.

### 4.3 An inbound message: `field: "messages"`, `value.messages[]` + `value.contacts[]`

| Cloud field | From the raw model |
|---|---|
| `id` | `id._serialized`, else `$1`, else `fromMe_remote_id` rebuilt (today's `waMessageId`) |
| `from` | the phone digits: `remote` user when `@c.us`; `senderPn` user when the chat is `@lid` and WhatsApp exposed it; absent otherwise |
| `from_user_id` | the lid digits when the chat is `@lid`; absent for `@c.us` |
| `timestamp` | `t` in seconds as a string, else the capture time, else now |
| `type` | §4.7 mapping |
| `context` | `{id: quotedStanzaID or quotedMsg key, from: quotedParticipant digits}` when quoting; `forwarded: true` from `isForwarded` (`frequently_forwarded` when `forwardingScore ≥ 5`) |
| `referral` | from `ctwaContext`: `source_url ← sourceUrl`, `source_id ← sourceId`, `source_type: "ad"`, `headline ← title or headline`, `body ← description`, `media_type ← mediaType`, `ctwa_clid ← ctwaClid or ctwa_clid`, `welcome_message.text ← greetingMessageBody`; only on an inbound message whose context names a source url, id or conversion source (today's `adClick` rule) |
| `errors` | on `unsupported`: `[{code: 131051, title: "Message type unknown", message: same, error_data.details: "<original type>"}]` |
| `contacts[0]` | `wa_id ← from`, `user_id ← from_user_id`, `profile.name ← notifyName / _data.notifyName / pushname` |

Type objects, with Meta's field names:

| `type` | object | from the raw model |
|---|---|---|
| `text` | `text.body` | `body`, unless it is a media thumbnail (`MessageBodies.readable`) |
| `image`, `video`, `sticker` | `{id, mime_type, sha256, caption, animated}` | `id` = the message id **(my call: the page downloads media by message id, so the message id is the media handle)**, `mimetype`, `filehash`, `caption`; `isGif` → `video` keeps `caption`; sticker `animated` from `isAnimated` if present. The object is always present on a media type; whether the bytes can be fetched is `wa_web.media_available`, and the processor records `WaWebMessage.Media` only when it is true, as today. `Media.mediaId` stays the GridFS cache id and never takes the Cloud `id` |
| `audio` | `{id, mime_type, sha256, voice}` | `voice: true` when the type was `ptt` |
| `document` | `{id, mime_type, sha256, filename, caption}` | `filename` from the model or `_data` |
| `location` | `{latitude, longitude, name, address}` | `location.latitude/longitude/description`, else `lat/lng/loc`, else `_data.*`; `description`'s first line → `name`, the rest → `address` |
| `contacts` | `[{name: {formatted_name, first_name}, phones: [{phone, wa_id, type}]}]` | parsed from each vCard's `FN`, `N`, `TEL;waid=` lines (`vcard` body, `multi_vcard` `vcardList[].vcard`); the texts also go to `wa_web.vcards` |
| `button` | `{payload, text}` | `buttons_response` / `template_button_reply`: `selectedButtonId` → `payload`, `body` → `text` |
| `interactive` (a customer's reply) | `{type: "list_reply", list_reply: {id, title, description}}` | `list_response`: `listResponse.singleSelectReply.selectedRowId` → `id`, `body` → `title` |
| `interactive` (business-authored) | `{type: "button" \| "cta_url" \| "list", header, body: {text}, footer: {text}, action: {buttons: [{type: "reply", reply: {id, title}}]}}` — Meta's own shape for an interactive message a business sends | whatsapp-web.js `interactive` (and `list`, `buttons`, `hsm`, `template`, `native_flow`): `type` from `nativeFlowName`, `caption` → `body.text`, `footer`, `title`, buttons from `interactivePayload.buttons[].buttonParamsJson` (`id`, `display_text`, `url`); see §6. A business message with a picture (63 of the tab's 87, 16 of the demo's 28 rows) gets `header: {type: "image" \| "video" \| "document", <type>: {id, mime_type, sha256, filename}}` and `wa_web.media_available`, and the processor records `Media` from the header as it does for a media type |
| `order` | `{catalog_id, text, product_items}` | `orderId`, `orderTitle`, `itemCount` as far as the model has them; else `unsupported` |
| `unsupported` | `errors[]` as above | `unknown`, `ciphertext` (never forwarded anyway), and any type this table does not name |
| `poll_creation` (ext.) | `{name, options: [{name, local_id}], allow_multiple_answers}` | `pollName`, `pollOptions`, `pollSelectableOptionsCount != 1`; a fetched poll's `pollVotes` → `votes[]` in the same shape as §4.6 |
| `call_log` (ext.) | `{video, missed, duration}` | `isVideoCall`, `callOutcome` contains "miss" or "reject" (`Missed` and `Rejected` are missed; `Completed` and `AcceptedElsewhere` are not; main's rule since 2026-11-05), `callDuration`/`duration` |
| `revoked` (ext.) | `{original_message_id}` | `protocolMessageKey` when present |
| `biz_content_placeholder` (ext.) | `{}` | the tag is in `wa_web.business_tag` |
| `newsletter_follower_invite` (ext.) | `{name}` | `newsletterFollowerInviteInfo.newsletterName` |

Dropped before conversion, as today: WhatsApp's own notices (`notification_template`, `e2e_notification`,
`notification`, `group_notification`, `broadcast_notification`), group and broadcast chats (anything not `@c.us` or
`@lid`), and a message with no usable id. The converter returns no webhook event for them; the extension's event is
acknowledged.

Business-authored `interactive`, `list`, `buttons`, `hsm`, `template` messages (a bank's menu, a business's
template) stay `type: "interactive"` with Meta's shape for an interactive message as a business sends it **(my
call, changed after the builder check)**: they were going to become `text`, but a `text` from a contact starts
automations (`MessageKinds.REAL_TYPES`, `AutomationMessageListener`) and today a bank's menu or an OTP template starts
none. Keeping them `interactive` keeps that, keeps the stored `interactive` rows as they are (no migration, nothing
to run twice), and a customer's list reply is `interactive` too, told apart by `interactive.type`. Neither starts an
automation, as neither does today.

### 4.4 Our own message: `field: "smb_message_echoes"`, `value.message_echoes[]`

A `message_create` with `fromMe: true` (sent from the phone, WhatsApp Web, the console, a campaign or an automation;
the page sees them all) becomes an echo:

| Cloud field | From the raw model |
|---|---|
| `from` | the number's digits |
| `to` | the person's phone digits when known (`@c.us` remote, or the phone the stored chat already knows); absent for a lid chat whose phone is unknown — `wa_web.chat_id` carries the chat |
| `id`, `timestamp`, `type`, type object, `context` | as §4.3 |

Meta's echoes support `text`, `image`, `video`, `document`, `revoke`, `edit`; ours carry every type of §4.3
**(my call)**, since a WhatsApp Web number sends polls, locations and cards too. When the echo's `ack` has a status (1 to 4, or −1 for failed; 0 has none), the
same entry gets a second change `{field: "messages", value: {statuses: [<§4.5>]}}`, so the stored webhook event says
what Meta would have said in two webhooks.

### 4.5 Delivery state: `value.statuses[]`

A `message_ack` whose message is ours (`fromMe: true`) becomes

```json
{ "id": "<message id>", "status": "delivered", "timestamp": "<seconds>", "recipient_id": "<digits or absent>",
  "recipient_user_id": "<lid digits when a lid chat>", "wa_web": { "chat_id": "…", "ack": 2 } }
```

| ack | `status` |
|---|---|
| 1 | `sent` |
| 2 | `delivered` |
| 3 | `read` |
| 4 | `played` |
| −1 | `failed`, with `errors: [{code: 0, title: "Not sent", message: "WhatsApp Web reported the message as failed"}]` |
| 0 | no status (Meta has no `pending`; the echo itself says the message exists) |

A `message_ack` on an inbound message (`fromMe: false`) produces nothing **(my call)**: Meta has no such status, and
nothing reads the status of an inbound row (the console draws ticks for `OUT` only, campaigns and automations read
outbound rows). Inbound rows are stored `PENDING` with `ack` 0 from now on. `timestamp` is the ack's capture time,
since the ack event carries no time of its own; the processor keeps stamping `statusTimes` with its own clock, as
today.

### 4.6 Poll votes **(my call)**

A `vote_update` becomes a message about another message, as Meta models a `reaction`: in `messages[]` when the voter
is the person, in `message_echoes[]` when it is us:

```json
{ "id": "vote:<poll message id>:<voter chat key>", "from": "<voter digits>", "from_user_id": "<lid digits if any>",
  "timestamp": "<at / 1000>", "type": "poll_vote", "poll_vote": { "message_id": "<poll message id>", "selected_options": [0, 2] },
  "wa_web": { "chat_id": "<the poll's chat>" } }
```

The id is deterministic (one row per voter per poll is what the store keeps, merged by voter), and the processor never
inserts a `poll_vote` as a message: it folds the vote into the poll row, as `storeVote` does today.

### 4.7 The type vocabulary and the one-off migration **(my call)**

The stored `WaWebMessage.type` (and `WaWebChat.lastMessage.type`) switches to the webhook's `type`. Meta's names where
Meta has the type; WhatsApp Web's name for the extension types; nothing else changes on the row.

| stored today | stored after | note |
|---|---|---|
| `chat` | `text` | |
| `ptt` | `audio` | the voice flag is not stored; the preview says "Voice message" for both already |
| `vcard`, `multi_vcard` | `contacts` | the body keeps the vCard text |
| `unknown` | `unsupported` | preview "Message to view on the phone" moves to `unsupported` |
| `list`, `buttons`, `hsm`, `template`, `native_flow`, `list_response` | `interactive` | §4.3; none stored in the demo database today |
| `buttons_response`, `template_button_reply` | `button` | none stored today |
| `image`, `video`, `audio`, `document`, `sticker`, `location`, `interactive`, `poll_creation`, `call_log`, `revoked`, `biz_content_placeholder`, `newsletter_follower_invite` | unchanged | |

Raw types Meta has no name for (`product`, `order` without a title, a `rich_response` without text, and anything not
in §4.3) are stored as `unsupported` and preview as "Message to view on the phone" (they previewed as "Unsupported
message", and a textless bot reply as "🤖 Bot reply", before). Rows stored under the old names before the migration
keep them; the migration renames only the table above.

`MessageTypeMigration` runs at every startup from `CollectionRenames.afterSingletonsInstantiated()`, like
`ContactIdsMigration`: `updateMany` on `waWebMessages.type` and `waWebChats.lastMessage.type`, idempotent because
every old name in the table differs from every new name (which is why `interactive` is not renamed). The demo database
has 316 messages; the migration is instant. The code that switches on
types moves with it: `preview`, `MessageBodies.TEXT_TYPES`, `ChatPreviewBackfill`, `MessageKinds` (the automation kind
of each type; the kinds `TEXT`/`AUDIO`/`CONTACT_CARD`… that rules store are unchanged, so no rule migrates),
`InboxSendService` (an outgoing voice note is `audio`, a text is `text`), and in the console `MessageBubble`,
`MediaBody`, `Thread`, `ChatList`. Alternative not taken: keep today's vocabulary and map Cloud types back to it in
the processor; it would make a future Cloud number's rows look like WhatsApp Web's, the wrong way round.

## 5. The pipeline

### 5.1 Components **(my call on names and places)**

```
extension ──ws event──▶ BridgeWebSocketHandler ──WhatsAppEvent (raw, unchanged)──▶ WaWebWebhookListener
                                                                                     │ converter.convert(event)      → 0..1 WebhookEvent
                                                                                     │ store.save(ctx, event, raw)   → channelWebhookEvents
                                                                                     ▼
                                                                          WhatsAppWebhookProcessor.process(ctx, webhookEvent)
                                                                                     │ per change: contacts → messages → message_echoes → statuses
                                                                                     ▼
                                                                          InboxIngestService (typed: ingestInbound / ingestEcho / ingestStatus / ingestVote)
                                                                                     ▼
                                                                          stored rows + InboxEvents  (routing, push, automations, campaigns, CRM … unchanged)
```

| Piece | Module and package | Role |
|---|---|---|
| `WebhookEvent`, `Entry`, `Change`, `Value`, `Metadata`, `Contact`, `Message`, `MessageEcho`, `Status`, the type objects, `WaWeb` (the `wa_web` object) | `omnichannel-common`, `common.channel.whatsapp.webhook` | the typed Cloud shape; pure data, snake-case Jackson naming; shared by service and app |
| `WaWebWebhookConverter` | `omnichannel-service`, `service.channel.whatsapp` | raw `JsonNode` → `Optional<WebhookEvent>`; the only reader of WhatsApp Web's raw model; also `message(JsonNode)` and `echo(JsonNode)` for the two other entry points (§5.3). Pure, unit-tested against fixtures |
| `ChannelWebhookEventStore` + `ChannelWebhookEvent` | `omnichannel-service` + `omnichannel-db` (`db.channel`) | §5.4 |
| `WhatsAppWebhookProcessor` | `omnichannel-service`, `service.channel.whatsapp` | walks a webhook event and calls the ingest; the entry point a Cloud controller would call later |
| `WaWebWebhookListener` | `omnichannel-service`, `service.channel.whatsapp` | `@EventListener` on `WhatsAppEvent`; replaces `WaWebInboxListener`; converts, stores, processes; keeps the failure contract (§5.2) |
| `InboxIngestService` | unchanged place | its `JsonNode` methods and `MessagePayloads` go; typed methods take `Message`/`MessageEcho`/`Status` plus a `ChannelContext(customerId, workspaceId, channelAccountId)`; the stored rows and the published events are exactly today's |
| `ChannelContext` | `omnichannel-common` | who the webhook is for; `channelAccountId` is the phone number id today (channel-neutral name, per the project rules) |

### 5.2 Failure semantics (unchanged contract)

- The listener runs synchronously on the publishing thread, like today. `IngestionFailedException` from the processor
  reaches the handler, which does not acknowledge the event and closes the socket so the outbox replays it.
- A conversion failure (a payload the converter cannot make sense of) is logged at WARN with the event name, id and
  the field set, and the event is acknowledged: a replay would fail the same way. The raw payload is still stored
  (§5.4) so the shape can be studied.
- Storing the webhook event (§5.4) is best effort: a failure there is logged and does not block the message. **(my
  call)** The message is what matters; the stored event is for debugging and replay.

### 5.3 The other two raw entry points

- `InboxSendService.afterSend` converts the `sendMessage` result with `converter.echo(result)` and records the row
  from the typed echo (id, timestamp, type, `wa_web.chat_id` for the reported chat). The `console:<id>` fallback when
  no model came back stays.
- `HistoryImportRunner` converts each fetched model with `converter.message(m)` or `converter.echo(m)` by `fromMe`
  and calls the typed `importMessage(ImportContext, Message | MessageEcho)`; the import's own rules (closed chats, no
  unread, no status times) stay.

After this, `MessagePayloads` is deleted and `LoggingEventListener.wid/messageId` are the only raw readers left
outside the converter (they log, they do not store) — or they call the converter's id helpers. **(my call)**

### 5.4 Persisted webhook events: collection `channelWebhookEvents`

```
id
event: <the webhook event as JSON; says its origin (object), workspace (entry.id) and number>
raw: <the bridge payload as JSON, capped>, rawDropped
seq, capturedAt, creationTime
```

- The document holds no copy of the channel, workspace or customer; everything comes from `event`. When a raw
  cannot be converted, `event` is a shell (`object`, `entry[0].id`, one change whose value has only
  `messaging_product` and `metadata`) so the raw is still found by number **(my call)**.

- Indexes: `(event.entry.changes.value.metadata.phone_number_id, creationTime desc)` (name `number_time`; cleanup of a number deletes by that path), TTL on `creationTime` with `omnichannel.webhooks.retention`
  (default `30d`) created programmatically at startup so the property rules **(my call)**.
- `raw` is kept when `omnichannel.webhooks.keep-raw` is true (default) and the payload is at most
  `omnichannel.webhooks.raw-max-bytes` (default 65 536) as JSON; larger payloads store `raw: null` and
  `rawDropped: true`. Keys with a dot or a leading `$` are removed at any depth, as `AdClick.raw` does today.
- `InboxCleanup.deleteForNumber` removes the number's events.
- Only conversation events are stored (the ones that produced a webhook, plus the ones that failed to convert).
  Device events are not.

### 5.5 Device events stay as they are

`ready`, `change_state`, `disconnected` describe the bridge and the device, not a conversation; Meta has no webhook
for them. They keep flowing as `WhatsAppEvent` to `DeviceSession`, `SenderEventsListener`, `LoggingEventListener`
and `RecentEventsStore`. `RecentEventsStore` and `GET …/events` keep showing the raw events (they are the debugging
view of the bridge).

## 6. The raw field names, confirmed on the live tab (2026-11-05, build 2.3000.1053060988)

Read from 1 023 in-memory message models of the Main line's tab (`whatsapp-web-storage-analysis.md` §2 has the full
table). What the converter reads, and what changed against the first draft:

- **Keys and ids.** `id` = `{fromMe, remote, id, $1, participant, self}`: `_serialized` is absent from message keys
  and `$1` carries its value; Wids (`remote`, `author`, `callCreator`, `quotedParticipant`) still have
  `{server, user, _serialized}`. The converter uses the page's `messageKey`/`widKey` logic (both forms).
- **Media.** `directPath`, `mediaKey`, `mediaKeyTimestamp`, `filehash` (44-char base64 of the SHA-256, so it is the
  Cloud `sha256` as is), `encFilehash`, `mimetype`, `size`, `width`, `height`, `caption`, `filename`, `pageCount`,
  `duration`, `waveform`; `body` is the base64 thumbnail. A view-once image has `mimetype` but no `directPath`:
  `wa_web.media_available` is false for it. `isGif` and `isViewOnce` exist as flags.
- **Text.** `body`; a link preview is `chat` with `subtype: url` plus `title`, `description`, `matchedText`,
  `canonicalUrl`, thumbnails: still `text` (Meta has no preview object on inbound text). A business template is
  `chat` with `hsmTag`, `hsmCategory`, `templateId`, `isFromTemplate`, `hydratedButtons[]`, `footer`, `title`: still
  `text` (today's behaviour, including automations), its tag in `wa_web.business_tag`.
- **Replies and forwards.** `quotedStanzaID`, `quotedParticipant` (wid), `quotedMsg` (the whole quoted model);
  `isForwarded`, `forwardingScore`.
- **Polls.** `pollName`, `pollOptions[{name, localId}]`, `pollSelectableOptionsCount` (1 = single choice; the draft
  said `allowMultipleAnswers`, which does not exist here: `allow_multiple_answers = pollSelectableOptionsCount != 1`),
  `pollType`, `pollContentType`.
- **Calls.** `callOutcome` ∈ {`Completed`, `Missed`, `Rejected`, `AcceptedElsewhere`}, `callDuration`, `isVideoCall`,
  `callCreator`, `callParticipants[{participant, outcome}]`; no `ack` (so no status).
- **Revokes.** `protocolMessageKey` (a key with `$1`), `revokeSender`, `revokeTimestamp`, `subtype: sender`.
- **Cards.** `vcard`: `body` = the vCard text, `vcardFormattedName`; `multi_vcard`: `vcardList[].vcard`.
- **Business interactive messages** (87, all inbound): `interactiveType` (`native_flow`, `carousel`),
  `nativeFlowName` (`quick_reply`, `cta_url`, `cta_copy`, `mixed`), `interactiveHeader {hasMediaAttachment,
  mediaType}`, `interactivePayload.buttons[{name, buttonParamsJson}]` where the JSON holds `display_text` and `id`
  (and a `url` for `cta_url`), `title`, `footer`, `caption` (the text), `hsmTag`, `templateId`; 63 of 87 carry media.
  The converter therefore fills Meta's business interactive shape fully: `interactive.type` from `nativeFlowName`
  (`quick_reply` → `button`, `cta_url` → `cta_url`, else `list`), `header` from the media, `body.text` from
  `caption`, `footer.text`, and `action.buttons[{type: "reply", reply: {id, title}}]` from the payload. That lifts
  the "buttons are lost" limit of `known-limits.md`; the processor still stores the text only.
- **Other.** `biz_content_placeholder` has `bizContentPlaceholderType` and `hsmTag`; `newsletter_follower_invite`
  has `newsletterFollowerInviteInfo {newsletterId, newsletterName, inviteMessage}`; `unknown` has `subtype`,
  `futureproofParams`, `hsmTag`; `album` groups pictures; `rich_response` (a bot answer) → `text` from
  `unifiedResponse` text when present, else `unsupported`. `orderId`/`orderTitle`/`itemCount`,
  `selectedButtonId`, `listResponse` were not present on any model: those rows of §4.3 stay as whatsapp-web.js
  documents them, unverified.
- **Not on stored models.** `senderPn` (0 of 839 lid messages) and `notifyName` (1) arrive only with a live stanza;
  the converter reads them when present and the contact fallback (`resolveIds`) stays the way a lid gets its phone.
- **`ctwaContext`.** Present on one message: a business's link preview of a group invite, with `sourceUrl`,
  `title`, `description`, `thumbnail`, `thumbnailUrl`, `mediaType` (a number), `mediaUrl`, and none of `sourceId`,
  `conversionSource`, `ctwaClid`, `sourceApp`. Today's ad rule (`sourceUrl` alone suffices) would have called it an
  ad. The converter applies the rule main has since 2026-11-05: an ad needs `sourceId`, `conversionSource` or a click
  id; a `sourceUrl`-only context is a link preview and is logged by its field names. `media_type` is written as
  Meta's string (`image`/`video`) from the numeric code, best effort.
- **Storage.** Message content in IndexedDB is encrypted (`msgRowOpaqueData`), so a fetch of history through the page's
  decrypted models is the only way to read old messages; nothing here changes the history import.

## 7. Limits and follow-ons

- Inbound rows no longer carry WhatsApp's ack (§4.5). If something ever needs it, the stored webhook event has it in
  `wa_web.ack`.
- A message of a non-media type that carries `directPath` gets no media record any more (today it does), except a
  business-authored `interactive` message, whose picture travels in `interactive.header` (§4.3). In the demo database
  only `interactive` rows are like that.
- `WaWebMessage.Media.mediaId` is the GridFS cache id. A real Cloud webhook's `image.id` (a Graph API media id) has no
  field on the row yet; the Cloud channel feature adds one.
- A real Cloud `referral` has no `sourceApp`; `AdClick.sourceApp` will be null for a Cloud number and "came from an
  ad" is then "a `referral` is present".
- A real Cloud `contacts` message has no vCard text, so the console's card renderer would show nothing for it; a
  Cloud channel needs the console to render from `contacts[]` (kept for that feature).
- Reactions, edits and separate revoke events are not forwarded by the page; when they are, they map to Meta's
  `reaction` and the echo types `edit` / `revoke`.
- `poll_vote` and the deterministic vote id are ours; Meta has no polls.
- The webhook store doubles the writes per message (one event document, one message row). At the demo's volume this is
  nothing; `retention` bounds the collection.
- The Cloud channel itself (webhook controller, verification, Graph API media and sending) is the next feature; this
  one makes `WhatsAppWebhookProcessor` the place it will call.
- A `rich_response` with text is stored as `text` and can start a TEXT automation, as any text can; before it started
  none (bot chats are dropped as non-person chats, so this touches a bot reply inside a person's chat only).
- The `preview` label and the `labelled()` entry for `rich_response` apply to rows stored before the migration only.

## 8. Left to the builder

- The exact record and field set of the typed model (every Meta field in §3.3 plus the type objects of §4.3; unknown
  fields ignored, nulls omitted), and the Jackson configuration for snake case.
- The fixture set: one raw payload per row of §4.3 and §4.4 (taken from the live tab or built as the ITs build them
  today) with its expected webhook event, and a dozen real Meta bodies (numbers replaced) that must deserialise.
- The order of the processor's work inside one change (contacts, then messages, then echoes, then statuses) and how
  `ChannelContext` is built from `WhatsAppEvent`.
- The migration's exact filters, and the console's type switches (`MessageBubble`, `MediaBody`, `Thread`, `ChatList`,
  the thread tests).
- Test strategy: the existing inbox ITs (`WaWebInboxListenerIT`, `InboxSendIT`, `CampaignChatIT`, the history and
  automation ITs) keep feeding raw payloads through the whole pipeline and must keep passing with only their type
  literals updated; the converter gets its own unit tests; the processor gets tests with typed events, including a real
  Meta `messages` body stored as a message with `chat_id` derived from `from` (no `wa_web`).
