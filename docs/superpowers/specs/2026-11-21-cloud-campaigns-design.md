# Campaigns on Cloud API numbers

Design, 2026-11-21. Follows the Cloud numbers project (spec 2026-11-20-cloud-numbers-design.md, shipped 9003a5f),
which left campaigns on Cloud numbers out.

## 1. Intent

The user's idea for the whole product: **everything is Cloud-compatible, and when the chosen number is a WhatsApp Web
number, things work the WhatsApp Web way.** Campaigns are the last big feature that only knows WhatsApp Web. Bimal
Institute now has a Cloud number on its own Meta app and needs to send campaigns from it (admission enquiries,
follow-ups after days). There is one campaign feature, not two: the kind of number a campaign sends from decides how
it sends.

## 2. What the user decided

| # | Decision |
|---|---|
| D1 | **One kind of number per campaign.** A campaign sends from WhatsApp Web numbers or from Cloud numbers, never both. |
| D2 | A Cloud campaign sends **as fast as Meta allows**, inside the campaign's sending hours, and stops at Meta's daily limit. No spreading over the day. |
| D3 | Architecture **A**: one campaign engine with a switch on the number kind at the points where pacing and sending differ (the same shape as the inbox's `SendChannel` switch). Not a second engine; not today's engine with gaps set to zero. |

Everything else is **(my call)** unless marked otherwise; the standing rule is to skip the questions and flag calls.

## 3. Goals, non-goals, anti-goals

Goals: a campaign on one or more Cloud numbers sends one imported, approved Meta template to a segment, filter or
CSV audience, with variables mapped as placeholders are today; Meta's daily limit, quality rating and template status
stop or pause it with a visible reason; delivered/read/replied/failed counts work as on WhatsApp Web; every send is
charged and refused sends refunded; WhatsApp Web campaigns behave exactly as today.

Non-goals (v1): mixed campaigns (D1); spreading Cloud sends over the day; parallel sends per number; writing or
submitting templates; rotating several Meta templates in one campaign; a header file per recipient; Cloud
interactive (non-template) messages in campaigns; retrying failed recipients; reading Meta's portfolio-level limit
from an API; managing Cloud numbers on the campaign senders page (rest, retire).

Anti-goals (a failure even if it works):
- A WhatsApp Web campaign behaving differently in any way (pacing, warm-up, skips, counts). All existing campaign
  tests pass unchanged.
- A recipient messaged twice. A send whose outcome is unknown (timeout after Meta may have taken it) is never retried.
- The brand paying for a message Meta refused.
- A Cloud campaign stalling silently. Every stop shows why and, for the daily limit, roughly when it goes on.

## 4. Facts the design rests on

- `Campaign` (`omnichannel-db/.../db/campaign/Campaign.java`): `audience`, `templateIds`/`templateNames` (the app's
  own saved templates, rotated per recipient), `placeholders` (`PlaceholderMapping{name, source, fallback}`),
  `senderIds` (phone number ids), `status`, `pausedBy`, `pauseReason`, `counts`. `CampaignRecipient` carries
  `templateId`, `senderId`, `preferredSenderId`, `status` (PENDING, SENDING, SENT, DELIVERED, READ, FAILED, SKIPPED),
  `skipReason`, `error`, `dispatchedAt`.
- `CampaignRunner` (586 lines): the 10 s `CampaignTicker` calls `tick()`; per workspace, inside the sending window
  (`SenderPolicy.inWindow`: settings' working days, start and end time, timezone), each eligible sender runs
  `process()` on the campaign executor: `claim()` the oldest campaign's first PENDING recipient (SENDING), `handle()`
  it (recently messaged → free skip; number lookup → not on WhatsApp skip; `send()` renders the saved template, fails
  `HAS_LINK`, calls `NewChatService.startForCampaign`), then paces the sender (random gap). One real send per sender
  per tick. Shared parts: `failStuck`, `release`, `pause`, `completeFinished`, `skipUnusablePreferred`,
  `phonesInFlight`, `counter.refresh`.
- WhatsApp Web only: `eligible()` (sender WARMING/ACTIVE, device ready, day-start offset, daily cap from warm-up,
  hourly cap, `nextSendAt`), `SenderPolicy` caps and gaps, `CampaignSender` rows (warm-up, rest, logout, health),
  `SenderHealth`, number lookups, `HAS_LINK`, template rotation.
- Cloud numbers are excluded today in three places: `CampaignSenderService` lines 93 and 225, `CampaignValidator`
  line 246; `known-limits.md` says "No campaigns on Cloud numbers yet".
- Cloud sending exists: `InboxSendService.sendCloudTemplate(SendActor, ws, chat, CloudTemplateRequest, typedPhone)`
  checks the template is sendable and of the chat number's WABA, resolves blanks (`CloudTemplateFills`), requires a
  header file for a media header and uploads it to Meta **on every send**, charges through `charged(actor, …)`
  (refund on Meta refusal; a later `failed` status refunds a charged row; an unknown outcome keeps the charge).
  `NewChatService.startCloud` resolves the chat for a phone on a Cloud number without a number lookup.
  `CampaignActor(campaignId, name)` exists.
- `CloudAccount` stores `qualityRating` and `messagingLimitTier`, refreshed from Meta and from the
  `phone_number_quality_update` / `account_update` webhooks. `CloudTemplate` stores `status`, `supported`,
  `variables` (keys like `body:1` or named), `fills`, `headerFormat`; template status webhooks update it (review fix
  I5).
- Meta: outside the 24-hour window only templates can be sent. The messaging limit is the number of unique people a
  business may start conversations with in a rolling 24 hours (tiers 250, 2K, 10K, 100K, unlimited; since late 2025
  counted across the business portfolio). Meta may hold back marketing templates to a person who got many
  (error 131049) and reports undeliverable numbers as a `failed` status (131026).
- Console wizard (`src/app/Campaigns/NewCampaign/`): Audience → Message → Senders → Review; `MessageStep` picks saved
  templates and maps placeholders (`PlaceholderMapping.tsx`); `SendersStep` lists WhatsApp Web senders.

## 5. Design

### 5.1 Data (no migration)

- `Campaign.channel: ConnectionType` (`WHATSAPP_WEB` | `WHATSAPP_CLOUD_API`), the same type `MessageTemplate.channel`
  uses. A stored campaign without it reads as `WHATSAPP_WEB`.
- `Campaign.cloudTemplate: CampaignCloudTemplate{cloudTemplateId, name, language, headerMediaId}`, null on WhatsApp
  Web campaigns. `name`/`language` are copied at save for display, like `templateNames`. `headerMediaId` is an
  uploaded file of the app (`/channel-accounts/{id}/media`), required when the template has a media header.
- Variables use the existing `placeholders` list: one `PlaceholderMapping` per template variable, `name` = the
  variable key (`body:1`, `header:1`, a named key). A variable with no mapping uses the template's own fill, then
  fails validation if it has none.
- `templateIds`/`templateNames` stay empty on a Cloud campaign; recipients' `templateId` stays null.
- No `campaignSenders` rows for Cloud numbers: warm-up, rest and health do not apply. The runner's `Claim` and
  `startSenders` stop requiring a `CampaignSender` row (today `startSenders` calls `senders.ensure`); a Cloud claim
  carries the number id only.

### 5.2 The switch in the engine

A package-private sealed interface `CampaignChannel` in `service.campaign`, chosen per campaign from `channel`:

```java
sealed interface CampaignChannel permits WebCampaignChannel, CloudCampaignChannel {
  boolean eligible(String ws, String senderId, CampaignSettings s, Instant now); // may this number send now?
  void handle(Claim claim);              // send (or skip, or fail) one claimed recipient
  boolean keepGoing(Claim last, Instant tickStarted); // claim another in this tick?
}
```

- `WebCampaignChannel` is today's code moved, not changed: `eligible()`, lookups, render, `HAS_LINK`,
  `startForCampaign`, pacing, free skips, `SenderHealth`. One real send per tick, as now.
- The runner keeps everything shared: the tick, the window check, claiming, `phonesInFlight`, release, pause, stuck
  rows, completion, counts, preferred-sender skips, the wallet pause.
- The tick groups senders per channel. A number can be in Web campaigns or Cloud campaigns only (its kind decides),
  so a sender's campaigns are always one kind.

### 5.3 A Cloud number sending

- **Eligible** when: the number is not disabled; its Cloud account can send (`CloudState.canSend()`: READY or
  WAITING_FOR_WEBHOOK, the check `devices.requireChannel` makes; `devices.isReady` is WhatsApp Web only and always
  false for a Cloud number); the plan covers campaigns (`workGate`); the campaign window
  is open (the same settings: working days, start, end, timezone); the number is not held for its limit (below); its
  quality is not `RED` (else §5.5).
- **Speed (D2):** inside one tick a Cloud number claims and sends one recipient after another, sequentially, until
  nothing is left, the limit is reached, the window closes, its campaign stops running, or **8 s** of the tick are
  used (so pause and terminate are seen within one tick). The next tick (10 s) continues. At ~3 sends a second that
  is ~250 in under 2 min and 10,000 in about an hour **(my call: sequential in v1; parallel sends per number are a
  later step if Bimal needs more)**.
- **Daily limit:** `messagingLimitTier` (fetched per WABA, stored per `CloudAccount`; the highest tier among the
  WABA's numbers is used) maps to a number (`TIER_50` 50, `TIER_250` 250, `TIER_1K` 1,000, `TIER_2K`
  2,000, `TIER_10K` 10,000, `TIER_100K` 100,000, `TIER_UNLIMITED` none; unknown → 250). Used = distinct phones that
  received a template from any number of the same WABA in the last 24 h, counted from stored outbound messages (so
  inbox follow-ups and rule sends count too). At the limit the number waits; as the 24 h roll, it goes on by itself
  **(my call: WABA as the stand-in for Meta's portfolio count, which we cannot read)**. If Meta still refuses for
  the limit (a portfolio we cannot see, Wati on the same portfolio), the recipient is released, not failed, and the
  **whole WABA** is held for 1 h in memory (a restart forgets the hold; the next refusal sets it again). A throughput
  or pair rate refusal (`CloudSendException.Kind.RATE_LIMITED`) releases the recipient and holds only that number
  for its `retryAfter` (default 60 s), not an hour **(my call)**.
- **No** number lookup, warm-up, gaps, rest days, logout, hourly cap, `HAS_LINK` check or template rotation.
- **Send:** `NewChatService.startCloudTemplateForCampaign(CampaignActor, ws, senderId, phone, CloudTemplateRequest)`
  treats the chat **exactly as `startForCampaign` does for WhatsApp Web** (no user scope or claim; a closed chat is
  reopened and keeps its assignment; an unassigned or new chat is marked with `campaignId`, so the first reply goes
  through bot-first routing and reply and stop-word handling as today), minus the number lookup, then calls
  `InboxSendService.sendCloudTemplate` with the campaign actor **(my call: the same chat behaviour on both kinds)**.
  Values come from `PlaceholderValues` as today.
- **A blank value for one recipient** (empty CSV cell or attribute, no fallback) fails that recipient
  `MISSING_VALUE` before anything is charged; it never pauses the campaign. The runner's catch-all `sendRefused`
  (pause `SEND_REFUSED`) is kept only for unexpected errors **(my call)**.
- **Header media:** uploaded to Meta once per campaign and number, and the Meta media id reused for every send
  (Meta keeps uploads 30 days); an expired-media refusal uploads again once. `sendCloudTemplate` gains a way to take
  an already-uploaded Meta media id **(my call)**.

### 5.4 Outcomes of a Cloud send

| What happened | Recipient | Campaign / number |
|---|---|---|
| Meta accepted | SENT, `messageId` linked; statuses arrive as for any Cloud message | — |
| Later `failed` status (131026 undeliverable, 131049 held back, others) | FAILED, `error` = Meta's code and title | charge refunded (existing) |
| Refused for this recipient (4xx on parameters) | FAILED with Meta's title | refunded |
| Refused for the daily limit or a rate limit | released (PENDING) | number held 1 h (§5.3) |
| Template not approved / paused / disabled | released | campaign paused `TEMPLATE_UNAVAILABLE` |
| AUTH (token revoked) | released | campaign paused `CLOUD_AUTH`; connection marked invalid (existing) |
| Wallet empty | released | paused `WALLET_EMPTY` (existing) |
| Unknown (timeout after the call left) | FAILED `OUTCOME_UNKNOWN`, never retried | charge kept (existing) |
| A value blank at send time | FAILED `MISSING_VALUE` | nothing charged |

A later `failed` status moving a SENT/DELIVERED recipient to FAILED (with `WaWebMessage.failure`'s code and title) is
**new work**: `CampaignMessageListener.acked` handles only DELIVERED, READ and PLAYED today. It applies to Cloud
campaigns only, so WhatsApp Web campaigns are unchanged.

### 5.5 Pauses from Meta's side

- A template status event that makes the template unsendable pauses every RUNNING campaign using it
  (`TEMPLATE_UNAVAILABLE`). A quality update to `RED` (the stored `qualityRating`) pauses the RUNNING campaigns on
  that number (`CLOUD_QUALITY_LOW`) **(my call: a red number is a ban risk for the brand's only official line)**.
  Flagged/restricted webhook events are only logged today and stay so in v1 (no new stored field); Meta's own
  refusals then pause via §5.4.
- A person resumes. For a **Cloud** campaign, resume first checks the template is sendable and the numbers can send
  (409 with the reason otherwise); WhatsApp Web resume is unchanged (today `CampaignLifecycleService.resume` only
  changes the status).
- The pause reasons are string constants beside `SEND_REFUSED` and `WALLET_EMPTY` in `CampaignRunner`; the console
  maps them in `CampaignsPage.tsx` and `CampaignDetailPage.tsx`.

### 5.6 Replies and counts

- Delivered and read come from Cloud statuses through the existing `MessageUpserted` path that
  `CampaignMessageListener` already listens to; a customer message (including a tap on a template quick-reply
  button) on that chat counts as replied, as on WhatsApp Web. The builder confirms the Cloud ingest raises the same
  events and fills the gap if not.
- Skips shared with WhatsApp Web: do not contact, recently messaged (`recontactDays`), chat on another number, stop
  words. `NOT_ON_WHATSAPP` stays WhatsApp Web only.

### 5.7 Validation (`CampaignValidator`)

- `channel` required on a new campaign; every `senderId` must be a usable number of that kind.
- Cloud: one or more Cloud numbers, all of one WABA; `cloudTemplate.cloudTemplateId` an approved, supported template
  of that WABA; every variable mapped or filled (an `AGENT` fill has no value in a campaign and counts as unfilled); `headerMediaId` present for a media header; `templateIds` empty.
- WhatsApp Web: as today, and `cloudTemplate` must be null.
- Estimate (`CampaignEstimates`) for Cloud: `perDay` = the WABA's limit minus nothing reserved; `days` from the
  remaining recipients; the review shows it with "if nothing else uses the daily limit".

### 5.8 API

- `CampaignRequest` and `CampaignView` gain `channel` and `cloudTemplate {cloudTemplateId, headerMediaId}` (the view
  also `name`, `language`, `status`).
- The senders list `GET …/campaign-senders` gains `?channel=` (default `WHATSAPP_WEB`, so the Senders page with its
  rest and retire actions is unchanged); `channel=WHATSAPP_CLOUD_API` lists Cloud numbers with
  `{qualityRating, messagingLimitTier, dailyLimit, usedLast24h}` and no actions; the wizard asks for the chosen kind
  **(my call)**.
- New pause reasons `TEMPLATE_UNAVAILABLE`, `CLOUD_QUALITY_LOW`, `CLOUD_AUTH`; recipient errors carry Meta's code and
  title.

### 5.9 Console

- Wizard order becomes **Audience → Numbers → Message → Review** for both kinds **(my call: the number kind decides
  what the message step offers)**. The Numbers step starts with a choice "WhatsApp Web numbers / Cloud API numbers"
  and lists only that kind; Cloud rows show quality, daily limit and used in the last 24 h.
- Message step, Cloud: one approved Meta template (picker with preview, unsupported and unapproved ones shown
  disabled with the reason), its variables mapped with the existing placeholder mapping, a header file upload when
  the template has a media header. WhatsApp Web: unchanged.
- Review, Cloud: estimate from the daily limit, a note that Meta charges per message and may hold back marketing
  messages to people who got many.
- Campaign list and detail: the number kind, the template, Meta's failure reasons per recipient, the new pause
  reasons, and "waiting for Meta's daily limit" while a number is held.
- Campaign settings page: a line that warm-up, gaps and caps apply to WhatsApp Web numbers; working days and hours to
  both.
- `known-limits.md`: the "No campaigns on Cloud numbers" line is replaced with this feature's limits.

## 6. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB, React 18 + PatternFly 6.5. "Template" means Meta templates (and the
existing saved-template code); no new `Template*` names for anything else. Channel-neutral names for new shared
things. Work in `.worktrees/cloud-campaigns` in both repos, test first, Prettier on touched files only, full
`./mvnw -q verify` once before merge. Real sends only to a recipient the user names, inside sending hours (Mon–Sat
10:00–18:00 IST for first messages).

## 7. Approaches considered

- **A. One engine with a channel switch (chosen, D3).** Shared claim/skip/count/pause code, a sealed
  `CampaignChannel` with a Web and a Cloud implementation. Matches the inbox send switch; the cost is moving today's
  Web code behind an interface, covered by the existing campaign tests.
- B. A separate Cloud engine beside today's. Safer for Web campaigns now, but two engines drift and every later fix
  to claiming or pausing is made twice; against the user's "one campaign, the number decides".
- C. Today's engine with gaps and warm-up set to zero for Cloud. Lookups, warm-up and health would still run and are
  wrong for Cloud. Dropped.

## 8. Left to the builder

Class split inside `service.campaign` beyond §5.2; how the rolling 24 h count is queried and indexed; the exact Meta
error codes for the limit, rate limit and expired media (from Meta's error-code page); the 8 s budget's mechanics;
console component split and copy.

## 9. Verification

- Backend ITs with a local Graph stub: a Cloud campaign sends every recipient within one or two ticks; stops at the
  limit and goes on when the oldest counted send leaves the 24 h; a limit refusal releases and holds the number;
  template paused event pauses the campaign; quality `RED` pauses; AUTH pauses; `failed` 131026/131049 statuses mark
  FAILED with Meta's title and refund; an unknown outcome is FAILED and not retried; the header is uploaded once per
  campaign and number; validation (mixed kinds, two WABAs, unmapped variable, missing header, unapproved template);
  outside the window nothing sends; every existing campaign test unchanged and green.
- Console: vitest for the wizard (kind choice, Cloud message step, review note), detail texts; `tsc`, lint, build.
- Manual on 8089 with Meta's free test number: a Cloud campaign to a recipient the user names, inside sending hours;
  check counts, the chat in the inbox, the charge, and a paused template's pause.

## 10. Risks

- Meta's limit is per portfolio; sends from other numbers or tools on the same portfolio are invisible to our count.
  Meta's refusal is the backstop (§5.3).
- Sequential sending (~3 a second) is slower than Meta's 80 a second; fine for Bimal's volumes, parallelism later.
- Cost: every recipient is a paid marketing or utility message; the review says so.
- Moving the Web code behind `CampaignChannel` could change its behaviour; the anti-goal is guarded by the unchanged
  existing tests.
