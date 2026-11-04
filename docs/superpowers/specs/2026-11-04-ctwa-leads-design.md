# Click-to-WhatsApp ad leads — design

Date 2026-11-04. Follows the Automations design (`2026-11-03-automations-design.md`). The user skipped the
brainstorming questions and chose the approach below; every decision marked **(my call)** is the builder's and can
be reversed.

## 1. Intent

A person who taps a click-to-WhatsApp ad (Facebook or Instagram) lands in a chat with one of the brand's numbers.
Their first message carries the ad's context. Today that context is thrown away, so the brand cannot tell an ad lead
from any other new chat, and the CRM records the contact as "Inbound chat".

This feature keeps the ad context, shows it in the inbox, records the CRM origin as a click-to-WhatsApp ad with the ad
identity, and lets an Automations rule react to "a message came from an ad" so the whole lead flow (welcome template,
assignment, Add to CRM) runs without a person. It is the cheapest lead source to support: no Meta integration, no
lead collection, no new screen.

## 2. Goals, non-goals, anti-goals

Goals
- Every inbound message that carries ad context is stored with it, and the chat remembers the first ad it came from.
- The inbox shows "From ad" on the message and an "Ad" mark on the chat.
- Add to CRM (the inbox button and the automation action) records origin `CTWA_AD` with the ad's id, link and headline
  when the chat came from an ad; the contact drawer says so with a link to the ad.
- Automations gain three conditions on the message event: came from an ad (yes/no), ad headline (text), ad id (text).
- The "contacts by source" report already breaks contacts down by origin kind, so ad leads appear there without
  further work.

Non-goals (left for later features)
- Facebook Lead Ads, website forms, any `leads` collection or lead screen, lead "conversion" as a separate action.
- A per-ad report, an inbox filter "from ads", attribution of later messages (only the first message carries context).
- Changes to the extension: the page already sends the whole message model.
- A new deal-source picklist value **(my call)**: origin drives the report; the picklist stays as the brand set it.

Anti-goals
- Never message anyone: this feature only reads.
- No new permission: ad context is part of the message, visible to whoever sees the chat.

## 3. Facts the design rests on

- The page sends `message.serialize()` (served page script, `getMessageModel`), so any ad field WhatsApp Web keeps
  on the message model reaches the backend as part of the raw payload. The backend keeps only the fields
  `MessagePayloads` extracts, so the ad context is dropped today.
- WhatsApp Web's message model carries the ad context as `ctwaContext`. The wa-js model (same underlying WhatsApp
  Web) declares it as an object with `conversionSource`, `conversionData`, `sourceUrl`, `sourceId`, `sourceApp`,
  `title`, `description`, `thumbnail`, `thumbnailUrl`, `mediaType`, `mediaUrl`, `originalImageUrl`,
  `greetingMessageBody`, `automatedGreetingMessageShown`, `adContextPreviewDismissed`. The Cloud API's equivalent
  (`referral`) also carries a click id `ctwa_clid`; whether WhatsApp Web exposes one (`ctwaClid`) is unconfirmed.
- The exact field set is confirmed only by a real ad click. The parser is therefore defensive (§4.1), and the raw
  object is kept (§4.2) so a different shape can be fixed from stored data instead of a second capture.

## 4. Behaviour

### 4.1 Detecting an ad click

A message **came from an ad** when it is inbound (not `fromMe`), its payload has an object `ctwaContext`, and that
object has a non-blank `sourceUrl`, `sourceId` or `conversionSource`. Outbound messages and messages without the
object are unchanged. `MessagePayloads.adClick(JsonNode)` returns an `AdClick` or null.

`AdClick` (a record on `WaWebMessage`, also on `WaWebChat`):

| field | from | notes |
|---|---|---|
| `sourceId` | `sourceId` | the ad's id as Meta sends it; may be null |
| `sourceUrl` | `sourceUrl` | the ad's link; may be null |
| `sourceApp` | `sourceApp` | e.g. `facebook`, `instagram`; may be null |
| `headline` | `title`, else `headline` | **(my call)** title is the ad headline the person saw |
| `description` | `description` | ad body text; may be null |
| `conversionSource` | `conversionSource` | WhatsApp's own label for the entry point |
| `clickId` | `ctwaClid`, else `ctwa_clid` | null when WhatsApp Web does not send one |
| `at` | the message timestamp | |
| `raw` | the `ctwaContext` object minus `thumbnail`, `thumbnailUrl`, `originalImageUrl`, `mediaUrl`, `conversionData` | §4.2 |

Text fields are trimmed and cut at 500 characters **(my call)**.

### 4.2 Storage

- `WaWebMessage.adClick`: set on insert when §4.1 matches. Existing messages are not revisited.
- `WaWebChat.adClick`: the chat's ad is the **earliest** ad click by message time **(my call)**: a chat is one lead,
  and the first ad is the one that brought the person in. It is set when null; an inbound ad message older than the
  stored one replaces it (live ingest, and history import, which walks newest to oldest); a later ad never replaces
  it. Later ad clicks stay on their messages.
- `raw` keeps the object as sent (images and `conversionData` dropped) so a wrong guess about field names can be
  corrected from the database. It is not shown anywhere and is capped at 4 KB; a larger object is stored without `raw`.

### 4.3 Inbox

- `MessageView` gains `adClick` (sourceId, sourceUrl, sourceApp, headline, description); `raw` and `clickId` are not
  sent to the console.
- The message bubble shows, above the body, a compact line "From ad: <headline>" (headline falls back to the
  description, then to "an ad"), with the headline linking `sourceUrl` in a new tab when present.
- `ChatView` gains `adClick` (same fields). The chat header shows a compact `Label` "Ad" (title: the headline) next to
  the number label when the chat has one.

### 4.4 CRM origin

- `crm.platform.PlatformChat` gains `PlatformAdClick ad` (sourceId, sourceUrl, sourceApp, headline, clickId); the
  adapter maps the chat's `adClick`.
- `crm.contact.Origin` gains `AdOrigin ad` (same fields). `CaptureService.capture` sets kind `CTWA_AD` and `ad` when the
  chat has one, else `INBOUND_CHAT` as today. `AutomationCrmAdapter.addLead` does the same: it loads the chat by
  `r.chatId()` through `ChatDirectory` and uses its ad **(my call: the port's `LeadRequest` is unchanged)**.
- `CrmContactView.OriginView` gains `ad` (sourceId, sourceUrl, headline). The contact drawer's Origin line for
  `CTWA_AD` reads "Came from a click-to-WhatsApp ad “<headline>” on <date>" with the headline linking the ad, and
  "Came from a click-to-WhatsApp ad on <date>" without one; the other kinds keep today's wording.
- The reports already break contacts down by `OriginKind` and the console already labels `CTWA_AD`; nothing changes.
- Existing CRM contacts are not re-classified **(my call)**.

### 4.5 Automations

Three new fields on the message event, in the condition catalogue, the evaluator values and the console:

| key | type | operators | console label |
|---|---|---|---|
| `message.ad` | BOOLEAN | is | "Ad" — "Message came from an ad" / "Message did not come from an ad" |
| `message.ad.headline` | TEXT | the text operators | "Ad headline" |
| `message.ad.sourceId` | TEXT | the text operators | "Ad id" |

- `EventData.MessageData` gains `AdData ad (sourceId, sourceUrl, headline)`, filled from the message's `adClick`.
  `AutomationFields.values` puts `message.ad` (true/false, always present on the message event), and the headline and
  id when present.
- The event summary becomes "Message from <who> (from ad “<headline>”): <text>" when the message came from an ad.
- The test page's event rows gain "From ad" (headline, linking `sourceUrl`) when present; the condition result for
  `message.ad` reads "yes"/"no" like the other booleans.
- No change to actions: a rule "When a message comes in, if Chat is new and Ad is yes, then Send template, Assign chat,
  Add to CRM" is the lead flow, and Add to CRM records the ad origin by §4.4.

## 5. Constraints

- Module rule: `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the ad reaches the CRM
  through `crm.platform` (`PlatformAdClick`) with the mapping in `app.crm`. `omnichannel-service` never depends on
  `omnichannel-crm`.
- Collections stay camelCase; no new collection. No schema migration: the new fields are nullable.
- Console copy fixed here: "From ad", "Ad", "Ad headline", "Ad id", "Message came from an ad",
  "Message did not come from an ad", "Came from a click-to-WhatsApp ad".
- `tsconfig` targets es5 in the console.

## 6. Testing

- Backend: unit tests for `MessagePayloads.adClick` (full wa-js shape; missing object; blank fields; outbound ignored;
  oversize raw dropped; `ctwa_clid` alias). Integration tests through the real ingest for: message and chat get the ad;
  a second ad message leaves the chat's first ad; history import sets it; `ChatView`/`MessageView` carry it; inbox
  capture and the automation Add to CRM record `CTWA_AD` with the ad; a rule on `message.ad` matches only ad messages;
  the catalogue refuses unknown operators; the event summary wording.
- Console: vitest for the bubble line and header label, the origin line, the three condition fields in the editor
  (labels, operators, saved shape), and the test page's "From ad" row.
- End to end (needs the user): the user taps one of their own ads, or an ad preview, from a phone they name and sends a
  message; the inbox shows "From ad", Add to CRM shows the ad origin, and a draft rule with "Ad is yes" matches in the
  test page. If the real `ctwaContext` differs from §4.1, the stored `raw` shows how, and the parser is adjusted in a
  follow-up commit.

## 7. Left to the builder

- Exact PatternFly components for the bubble line and header label (follow the existing compact labels).
- Where the `AdClick` record lives in `omnichannel-db` (next to `WaWebMessage` is fine).
- Test fixture values.
