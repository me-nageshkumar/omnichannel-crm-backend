# Cloud API messages over the WhatsApp Web bridge, as tracked pages

Design, 2026-11-12. The user chose "skip the questions"; the builder's own calls are marked **(my call)** throughout,
per project rule. Plans: `../plans/2026-11-12-cloud-messages-backend.md`, `../plans/2026-11-12-cloud-messages-console.md`.

## 1. Intent

Inbound already speaks the WhatsApp Cloud API: WhatsApp Web events are converted into the Cloud webhook shape
(`common.channel.whatsapp.webhook`, collection `channelWebhookEvents`, spec `2026-11-05-cloud-webhook-shape-design.md`).
Outbound does not. Typed text and the project's saved templates go to the bridge as whatsapp-web.js calls, and there is
no way to send the Cloud API's interactive messages (reply buttons, lists, call-to-action URL, location request): the
bridge cannot deliver them natively and the project has no shape for them.

This project makes outbound speak Cloud too, as **a separate feature from the project's templates**:

- Every outbound message is built as a WhatsApp Cloud API `/messages` request object, byte-for-byte the shape Meta
  documents. The one difference: `messaging_product` is `whatsapp-web` when the channel is the WhatsApp Web bridge and
  `whatsapp` when it is the Cloud API.
- Interactive messages are session messages in the Cloud API, composed per send. They work the same way here: a
  Cloud-shaped send API and an inbox composer, no saved store, no change to `messageTemplates` or any `Template*` class
  or screen.
- Over the bridge, an interactive message is delivered as **a link only**. Its link preview image is drawn before the
  send and served on request. The link opens a page rendered by our own HTML renderer, with all CSS and JavaScript
  inline, that shows the message the way WhatsApp would and tracks every customer action back to the message, chat and
  contact.

Meta **templates** (approved header/body/footer/buttons templates, and the carousel, which the Cloud API offers only as
a template) are a later project. Campaigns and automations send saved templates, so interactive messages reach them
with that project.

## 2. Goals, non-goals, anti-goals

Goals:

- Send `text` and `interactive` (`button`, `list`, `cta_url`, `location_request_message`) messages as Cloud API
  objects, validated with Meta's documented limits, through one API that mirrors Meta's `/{phone-number-id}/messages`
  and `/media` endpoints.
- Deliver interactive messages over the bridge as a link whose preview and page are generated from the message.
- Track opens, button taps, list picks, CTA clicks and shared locations per message, visible in the inbox thread and as
  CRM activities; match a customer's WhatsApp reply back to the button or row they tapped.
- Keep the stored outbound request in Cloud shape, as `channelWebhookEvents` keeps inbound events.

Non-goals **(my call)**: Meta templates, carousel, template sync, `flow`, `product`/`product_list`, `address_message`,
reactions, stickers, the actual Cloud API transport, campaigns and automations, automation trigger events for page
actions, `{link}` media headers (rejected with 400: Meta discourages them and the preview card would have to fetch a
remote file during a send), a Cloud-style `error{code,message}` envelope (validation errors use the existing JSON
advice). Image/video/document/audio/location/contacts/poll sends keep their current bridge path unchanged.

Anti-goals: anything that changes `messageTemplates`, `TemplateType`, `TemplateValidator`, `TemplateRenderer` or the
Templates screens; a page that needs a browser engine on the server; a page that makes external requests; a link that
reaches a customer before its preview image exists.

## 3. The message objects

Verified against developers.facebook.com on 2026-11-12. Limits are Meta's.

| `type` | Cloud API shape | Limits | On the page |
|---|---|---|---|
| `text` | `text{preview_url, body}` | body ≤4096 | not a page; sent natively |
| `interactive` / `button` | `interactive{type:"button", header?{type:text\|image\|video\|document, text\|image{id}\|video{id}\|document{id}}, body{text}, footer?{text}, action{buttons[{type:"reply", reply{id,title}}]}}` | 1–3 buttons, title ≤20, id ≤256, body ≤1024, footer ≤60, header text ≤60 | tap → tracked, then wa.me prefilled with the title |
| `interactive` / `list` | `header?{type:"text", text}, body, footer?, action{button, sections[{title, rows[{id,title,description?}]}]}` | button ≤20, 1–10 sections, ≤10 rows total, section title ≤24, row id ≤200, row title ≤24, description ≤72, body ≤4096 | bottom sheet; pick → tracked, then wa.me prefilled with the row title |
| `interactive` / `cta_url` | `header?, body, footer?, action{name:"cta_url", parameters{display_text, url}}` | display_text ≤20, body ≤1024, url ≤2000 https | tap → tracked redirect |
| `interactive` / `location_request_message` | `body{text}, action{name:"send_location"}` | body only | browser geolocation → tracked lat/lng **(my call)**; denied → wa.me prefilled "Here is my location:" |

Media in a header is `{id}`, where the id comes from the media upload endpoint (§6). Response shapes are Meta's:
`/messages` → `{messaging_product, contacts[{input, wa_id}], messages[{id}]}`; `/media` → `{id}`;
`GET /media/{id}` → `{messaging_product, url, mime_type, sha256, file_size, id}`.

## 4. Naming

Nothing new is called "template". The Cloud API object is a **cloud message** (`CloudMessage`,
`CloudMessageValidator`, `/channel-accounts/{id}/messages`). The public page is a **message page** (`messagePages`,
`MessagePage`, `MessagePageService`, `MessagePageRenderer`, `MessagePageController`, `messagePageId`,
`MESSAGE_PAGE_*`). Media uploaded for these messages is **cloud media** (`cloudMedia`). Collections
and top-level Mongo field names are camelCase (project rule); the Cloud JSON inside a stored map keeps Meta's
snake_case keys.

## 5. Decisions

### 5.1 Cloud message model (`omnichannel-common`, package `common.channel.whatsapp.message`)

Snake_case Jackson 3 records exactly like the webhook package (`@JsonNaming(SnakeCaseStrategy)`,
`@JsonInclude(NON_NULL)`), one record per Meta object: `CloudMessage(messagingProduct, recipientType, to, type, text,
interactive, context)`, `Text(previewUrl, body)`, `Interactive(type, header, body, footer, action)`, `Header(type,
text, image, video, document)`, `MediaRef(id, link)`, `Body(text)`, `Footer(text)`, `Action(buttons, button,
sections, name, parameters)`, `ActionButton(type, reply)`, `Reply(id, title)`, `Section(title, rows)`, `Row(id,
title, description)`, `CtaParameters(displayText, url)`, `CloudSendResponse`, `CloudMediaUploadResponse`,
`CloudMediaInfo`. `MessagingProduct` holds the two constants. `CloudMessages` converts to and from `Map<String,Object>`
through the shared `ObjectMapper`; parsing is **strict** (unknown keys fail) so stored objects stay clean, unlike
inbound. `CloudMessages.Limits` holds the numbers of §3 in one place.

Stored in Mongo as `Map<String, Object>`, exactly like `ChannelWebhookEvent.event`.

### 5.2 Validation (`service/cloud/CloudMessageValidator`)

Pure, unit-tested. Error keys are JSON paths into the object (`interactive.action.buttons.1.reply.title`).
`messaging_product` ∈ {whatsapp, whatsapp-web}; `recipient_type` absent or `individual`; `to` normalised with the
existing `Phones` helper; `type` ∈ {text, interactive} with exactly the matching sub-object; `interactive.type` ∈ the
four kinds; Meta's limits; reply ids and row ids distinct; reply titles and row titles distinct ignoring case (they are
the reply-match key); list header text only; CTA url `https://`; header media `{id}` must be a `cloudMedia` file of
the workspace whose MIME is allowed for the header type (the allowed lists are reused from `TemplateMediaTypes`, the
only thing borrowed from templates).

### 5.3 Message pages (`db/page`, `service/page`)

Collection `messagePages`: `workspaceId, customerId, token, chatId, contactId, phoneNumberId, senderDigits,
messageId, message (Map: the CloudMessage as sent), createdAt, expiresAt, openedAt, openCount, lastActionAt,
actions[] {kind OPEN|BUTTON_REPLY|LIST_REPLY|CTA_URL|LOCATION, id, title, latitude, longitude, at, uaHash,
repliedMessageId}, previewReady, previewFileId`. Token: 22-char base64url of 16 `SecureRandom` bytes, unique index.
Indexes `{chatId, createdAt}`, `{messageId}` sparse. **No TTL index (my call)**: `expiresAt` only gates serving (410);
rows feed the inbox card for as long as the message exists; preview PNGs are swept by a scheduled job.

`WaWebMessage` gains `messagePageId` and `request` (the CloudMessage sent); `MessageView` gains `messagePageId` and
`messagePage` (the tracking summary, see §5.6).
GridFS buckets `messagePagePreviews` and `cloudMedia`. Properties `omnichannel.pages.base-url` (default
`http://localhost:8080`, env `OMNICHANNEL_PAGES_BASE_URL`), `ttl-days` (90), `reply-window` (48h). CRM
`ActivityType.MESSAGE_PAGE_OPENED`, `MESSAGE_PAGE_ACTION`.

### 5.4 Renderer and preview card (`service/page`)

Input is the `CloudMessage`, so the renderer serves a future Cloud channel's console preview too. `WhatsAppMarkup`
HTML-escapes then applies the console's four `formatWhatsApp()` regexes in the same order. `MessagePageRenderer` uses
JMustache (`escapeHTML(true)`) over classpath templates `pages/page.mustache`, `expired.mustache`,
`notfound.mustache`, inlining `pages/page.css` and `pages/page.js` into `<style>`/`<script>`. No external requests.
Mobile-first, max width 420px, WhatsApp look: header strip, bubble with header media or text, formatted body, footer,
then per kind: reply buttons; list button opening a bottom sheet; CTA button; "Send location" button.

Page behaviour **(my call)**: reply button or list row → beacon with `keepalive`, then navigate to
`https://wa.me/{senderDigits}?text={title}` so the customer sends the reply from their own WhatsApp (href rendered
server-side); CTA → `GET /t/{token}/a` records then 302; Send location → `navigator.geolocation`, success beacons
lat/lng and shows "Location shared", denial falls back to wa.me prefilled "Here is my location:". Two variants: full
page (beacon JS) and composer preview (no beacon, inert buttons). OG tags: `og:title` = header text or body excerpt
(≤60), `og:description` = body excerpt (≤200), `og:image` = `{baseUrl}/t/{token}/og.png`, `og:url`, `og:type=website`,
`<meta name="robots" content="noindex">`.

**Preview image without a browser (my call)**: `PreviewCardRenderer` draws a 1200×630 PNG with Java2D: header image
on the left when the header is an image, else a WhatsApp-green panel; header text or the body's first line as
title (never the number's internal label: the public card and page show only the message and, in the page's header
strip, the sender's digits); wrapped body text with markup stripped; button titles as pills. One TTF bundled under
`pages/fonts/` and loaded
with `Font.createFont`. Drawn **once, before dispatch**, stored in `messagePagePreviews`, served on every
`GET /t/{token}/og.png` for the life of the page. A drawing failure fails the send with 500 "preview image could not be
generated" rather than sending a bare link **(my call)**: Java2D failures are deterministic bugs, not transient.

### 5.5 Public endpoints (`app/page/MessagePageController`, `@Controller`, not under `/api`)

- `GET /t/{token}` HTML; `Cache-Control: no-store`, `X-Robots-Tag: noindex`, `Referrer-Policy: no-referrer`. Unknown →
  404 HTML, expired → 410 HTML with no message text. Own HTML error pages, never the JSON advice.
- `GET /t/{token}/og.png` from `messagePagePreviews`, `Cache-Control: public, max-age=86400`; 404 when none.
- `GET /t/{token}/m` header media from `cloudMedia`, inline, no filename leak, range support for video.
- `POST /t/{token}/e` body `{k, id?, lat?, lng?}` → 204 always (unknown or expired: 204, nothing stored). `open` sets
  `openedAt` once and bumps `openCount`; at most 50 actions per page; `uaHash` = first 12 hex of SHA-256(User-Agent);
  lat/lng only for location-request pages and range-checked; `id` must be a reply or row id present in the message.
- `GET /t/{token}/a` → record `CTA_URL`, 302 to the url; expired → 410 **(my call)**; not a CTA page → 404.
- `SpaForwardController` excludes `t` as a first segment; `SecurityConfig` permits `POST /t/*/e`.

### 5.6 Authenticated endpoints (`app/cloud/CloudMessageController`)

- `POST /api/workspaces/{ws}/channel-accounts/{phoneNumberId}/messages` (`inbox:send`, body = `CloudMessage`).
  `{phoneNumberId}` is our `PhoneNumber.id`. `messaging_product` must be `whatsapp-web` for a bridge number;
  `whatsapp` → 400 "channel not available" until a Cloud number exists. `to` resolves to the chat on that number, or
  starts one through the inbox's new-chat flow; a number that cannot be resolved → 400 on `to`. Response:
  `CloudSendResponse` with our `WaWebMessage.id`.
- `POST /api/workspaces/{ws}/inbox/chats/{chatId}/cloud-messages` (`inbox:send`, body = `CloudMessage` with `to`
  omitted) **(my call)**: the chat-keyed twin the composer uses, so hidden-number (`@lid`) chats, which have no phone to
  put in `to`, can receive interactive messages. The controller fills `to` from the chat before validation. Same
  response shape.
- `POST .../channel-accounts/{phoneNumberId}/media` (`inbox:send`, multipart `file` + `messaging_product` + `type`)
  → `{id}`; stored in `cloudMedia` with workspace, uploader, MIME, size, sha256, time; MIME sniffed from the bytes as
  template media is. Limits: image 5 MB, video 16 MB, document 16 MB **(my call: the app's multipart cap is 16 MB;
  Meta allows 100 MB documents, raise both together if ever needed)**. `GET .../media/{id}` → `CloudMediaInfo`. Files
  unreferenced by any page after 24 h are swept.
- `POST .../channel-accounts/{phoneNumberId}/messages/preview` (`inbox:send`) → `text/html` composer preview.
- No separate read endpoint **(my call)**: `MessageView` carries `messagePage` (url, expiresAt, openedAt, openCount,
  buttons[{id, title, count, replied}], rows[{id, title, count, replied}], cta{count}, location{latitude, longitude,
  at} or null) built by `InboxViews` from the page row, so the thread listing and the inbox stream's `message` event
  both deliver it.

### 5.7 Sending (`InboxSendService`)

New entry `sendCloud(SendActor, ws, chat, CloudMessage)`. **Every send builds a CloudMessage (my call)**: typed text
becomes `{messaging_product:"whatsapp-web", recipient_type:"individual", to, type:"text", text{preview_url:true,
body}}` and is stored on `WaWebMessage.request`; template sends are untouched. `type: "text"` takes today's text path.
`type: "interactive"`, all before `dispatcher.call`: (1) create the `messagePages` row with the message,
`previewReady=false`; (2) draw og.png into `messagePagePreviews`, set `previewReady`; failure deletes the row and fails
the send; (3) outgoing content is `{baseUrl}/t/{token}` with `linkPreview: true`, stored body is the link, `record()`
stores `messagePageId` and `request`, `afterSend()` attaches the message id to the page. A refused dispatch leaves an
orphan page that is never served and expires (known limit).

Reply matching **(my call)**: `MessagePageReplyListener` on `InboxEvents.MessageUpserted` (inbound, inserted) looks at
the pages of that chat created within `reply-window`, newest first. An inbound text whose bare body equals the bare
title of a button or row in a page's message is a reply to that page: if a BUTTON_REPLY/LIST_REPLY action for that id
already exists without `repliedMessageId`, it gets the message id; otherwise the action is created with it (the
customer may have read the button titles off the preview card and typed one without opening the page). An inbound
`location` after a location-request page likewise completes the existing LOCATION action or creates one with the
message's coordinates. `CampaignMessageListener.bare()` becomes public static and is reused.

Live updates **(my call)**: every open, action and reply match publishes `InboxEvents.MessagePageChanged(chat,
message)`; `InboxPushListener` pushes the rebuilt `MessageView` as a `message` stream event, which the thread already
upserts, so an open thread's card updates with no console change beyond rendering `messagePage`.

CRM **(my call)**: port `service/page/MessagePageObserver`, adapter `app/crm/MessagePageObserverAdapter`, recorder
`crm/segment/MessagePageActivityRecorder` in the `CampaignActivityRecorder` style; called in try/catch so a beacon never
fails. One `MESSAGE_PAGE_OPENED` per page (first open only) and one `MESSAGE_PAGE_ACTION` per distinct button, row,
CTA or location per page (repeat taps bump the page's counts but add no activity), so a contact's timeline is not
flooded.

### 5.8 Console

`api/cloudMessages.ts` mirrors the Cloud objects in snake_case and wraps the endpoints; `MessageView.messagePage`
carries the tracking summary. `Inbox/InteractiveComposer.tsx` opens from an "Interactive
message" button beside the template picker: kind select, header (text or upload), body, footer, per-kind fields (reply
buttons 1–3 with ids auto-slugged from titles and editable; list button and sections/rows; CTA display text and url),
a debounced server preview in a sandboxed `srcdoc` iframe inside a phone frame, and a read-only JSON tab of the live
object **(my call)**. Server error keys land on the fields. `Inbox/MessagePageCard.tsx` renders under a message with
`messagePageId`: body excerpt, "Opened · n" or "Not opened", a badge per button or row with its count, a "Replied" tick
on matched replies, shared location as a map link, the link visible beneath.

## 6. Constraints

- Work in `.worktrees/cloud-messages`; test first; Prettier only on touched files; one Maven build per worktree; full
  `./mvnw -q verify` once before merge; real sends only to a recipient the user names, Mon–Sat 10:00–18:00 IST.
- Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), Mongo; React 18, TypeScript (`tsconfig` target es5), PatternFly
  6.5, vitest. GridFS, never S3. Channel-neutral names for new things. No new external runtime dependency for the
  preview image.
- The page must not leak anything beyond the message itself: no contact name, no workspace internals; token is
  unguessable; expired pages show no message text.

## 7. Left to the builder

Exact CSS of the page and the preview card; the bundled font; the beacon payload encoding; the composer's layout within
PatternFly; how the per-id cache of the inbox card is kept; sweep cadence for previews and unreferenced media (follow
`TemplateMediaSweeper`).

## 8. Risks

- **Ban risk**: each interactive send is a message with a same-domain link; campaigns blocked links for this reason.
  Inbox sends are one-to-one replies, the lower-risk case.
- **Preview fetch**: WhatsApp fetches the OG tags once at send time and embeds the preview; `base-url` must be public
  HTTPS and `og.png` must answer within seconds. Crawlers run no JS, so opens come only from the beacon. **Nothing is
  deployed yet**, so the real-device check needs a tunnel (e.g. `cloudflared tunnel --url http://localhost:8089`) with
  `OMNICHANNEL_PAGES_BASE_URL` set to its HTTPS origin; the user decides when and how (open question for them).
- **wa.me prefill**: the customer still taps send and may edit the text; matching is best-effort within 48 h.
- **Geolocation** needs HTTPS and consent; the wa.me fallback covers denial.
- **Meta shape drift**: limits are as documented on 2026-11-12; `CloudMessages.Limits` is the single place to update.
