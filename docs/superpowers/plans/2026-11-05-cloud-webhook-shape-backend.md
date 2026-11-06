# WhatsApp Web events as Cloud API webhooks — backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every conversation event of the extension becomes a Cloud API–shaped webhook event, which is stored and is the only thing the inbox ingest consumes; stored message types take Meta's names.

**Architecture:** A typed record model of Meta's webhook (`omnichannel-common`), a pure converter from the raw WhatsApp Web model to it, a `channelWebhookEvents` store with retention, and a processor that feeds the existing `InboxIngestService` through typed methods. The three raw-model entry points (live events, the console's send result, history pages) all pass through the converter; `MessagePayloads` disappears. A one-off migration renames stored types.

**Tech Stack:** Java 21, Spring Boot 4.1 (Jackson 3: `tools.jackson.databind`, annotations `com.fasterxml.jackson.annotation` and `tools.jackson.databind.annotation.JsonNaming`), Spring Data MongoDB, JUnit 5, AssertJ, Mockito, Testcontainers Mongo (the existing `*IT` base classes).

**Spec:** `docs/superpowers/specs/2026-11-05-cloud-webhook-shape-design.md` (with `docs/superpowers/whatsapp-web-storage-analysis.md` §2 for the raw fields). Console side: `plans/2026-11-05-cloud-webhook-shape-console.md`.

## Global Constraints

- Work in `.worktrees/cloud-webhook-shape` (this branch already holds the spec and this plan); never on `main`. One Maven build at a time in the worktree.
- Meta's objects carry only Meta-defined field names, snake_case on the wire; everything WhatsApp Web knows that Meta lacks goes in the `wa_web` object or a type-named extension object (spec §4.2). A real Meta webhook body must deserialise into the typed model without error.
- Nothing a person sees changes: the console, previews, automation conditions and reports behave as before (spec §2 anti-goals). The existing inbox, campaign, automation, CRM and history integration tests keep passing with no change except the type literals of Task 8 and the inbound-status rule of spec §4.5.
- The acknowledgement contract with the extension is unchanged: `IngestionFailedException` reaches `BridgeWebSocketHandler`; a conversion failure is logged and acknowledged (spec §5.2).
- Mongo collections are camelCase (`channelWebhookEvents`); new things get channel-neutral names (`channelAccountId`, `ChannelKind`).
- Test first (RED, then GREEN); run only the tests that matter while fixing; the full `./mvnw -q verify` once before the merge (about 5 min, near 6 GB).
- Commit trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Prettier only on touched files (console plan).

## Review Focus

Inputs the spec implies but no task's test exercises, most likely to bite first; each line's test is added to the task that owns the code:

1. A `message_create` echo for a message the console already stored (same id, `console` send path) must apply the status once and publish `MessageUpserted(inserted=false)` plus `ChatChanged`, never a second row — Task 6.
2. A `message_ack` that arrives before its message (or for a message never stored) is dropped without an error, as today — Task 6.
3. A raw payload whose `id` has `$1` and no `_serialized`, and one whose `id.remote` is a plain string, both convert to the same message id — Task 2.
4. The webhook store failing (Mongo write error) must not stop the message from being stored and acknowledged; the processor still runs — Task 6.
5. An inbound text from a contact whose chat is new must still publish `ChatCreated` first, then `MessageUpserted`, then `ChatChanged`, in that order, so routing sees the chat before the push listener does — Task 6.

---

### Task 1: The typed webhook model, `ChannelKind` and `ChannelContext`

**Files:**
- Modify: `omnichannel-common/pom.xml` (add `org.springframework.boot:spring-boot-starter-json` if `tools.jackson.databind` is not already on the module's compile path; `./mvnw -q -pl omnichannel-common compile` tells)
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/ChannelKind.java` (`enum ChannelKind { WHATSAPP_WEB, WHATSAPP_CLOUD }`)
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/ChannelContext.java` (`record ChannelContext(ChannelKind kind, String customerId, String workspaceId, String channelAccountId, String displayPhoneNumber)`; `channelAccountId` is the phone number id today)
- Create, package `me.nageshk.omnichannel.common.channel.whatsapp.webhook`: `WebhookEvent`, `Entry`, `Change`, `Value`, `Metadata`, `Contact` (+ nested `Profile`), `Message`, `MessageEcho`, `Status`, `WaWeb`, and the objects `Text`, `Media` (one record for image/video/audio/document/sticker: `id, mimeType, sha256, caption, filename, voice, animated`), `Location`, `ContactCard` (+ `Name`, `Phone`), `Button`, `Interactive` (+ `Header`, `Body`, `Footer`, `Action`, `ActionButton`, `Reply`, `ListReply`, `ButtonReply`), `Order` (+ `ProductItem`), `System`, `Context`, `Referral` (+ `WelcomeMessage`), `MessageError`, `Conversation` (+ `Origin`), `Pricing`, and the extension objects `PollCreation` (+ `Option`, `Vote`), `PollVote`, `CallLog`, `Revoked`, `BizContentPlaceholder`, `NewsletterFollowerInvite`, `Album`
- Test: `omnichannel-common/src/test/java/me/nageshk/omnichannel/common/channel/whatsapp/webhook/WebhookEventJsonTest.java`
- Test fixtures: `omnichannel-common/src/test/resources/webhook/meta/*.json`

**Interfaces:**
- Produces: every record is `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`, `@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonInclude(JsonInclude.Include.NON_NULL)`; field names are Meta's in camelCase Java (`messagingProduct`, `phoneNumberId`, `fromUserId`, `recipientId`, `recipientUserId`, `bizOpaqueCallbackData`, `mimeType`, `errorData`, `ctwaClid`, `sourceUrl`, …). `Change.field` is a `String` (Meta's `messages`, `smb_message_echoes`, `calls`, … and ours). `Value` has `messagingProduct, metadata, contacts, messages, messageEchoes, statuses, errors`. `Message` has `from, fromUserId, id, timestamp (String), type, context, referral, errors, text, image, video, audio, document, sticker, location, contacts, button, interactive, order, system, pollCreation, pollVote, callLog, revoked, bizContentPlaceholder, newsletterFollowerInvite, album, waWeb`. `MessageEcho` has `from, to, id, timestamp, type` and the same type objects plus `waWeb`. `Status` has `id, status, timestamp, recipientId, recipientUserId, conversation, pricing, errors, bizOpaqueCallbackData, waWeb`. `WaWeb` has `chatId, ack (Integer), originalType, fileSize (Long), mediaAvailable (Boolean), businessTag, ctwaContext (Map<String,Object>), ctwaSourceApp, ctwaConversionSource, vcards (List<String>), capturedAt (Long)`. `PollCreation(name, options: List<Option(name, localId)>, allowMultipleAnswers, votes: List<Vote>)`, `PollVote(messageId, selectedOptions: List<Integer>)`, `CallLog(video, missed, duration)`, `Revoked(originalMessageId)`, `NewsletterFollowerInvite(name)`, `BizContentPlaceholder()`, `Album()`.
- Static builders used by later tasks: `WebhookEvent.of(ChannelContext ctx, List<Change> changes)` (fills `object`, one `Entry` with `id = channelAccountId`), `Value.of(ChannelContext ctx)` (fills `messagingProduct = "whatsapp"` and `Metadata(displayPhoneNumber, phoneNumberId = channelAccountId)`).

- [ ] **Step 1: Write the failing tests**

Fixtures are Meta's wire shape. The stored `whatsapp.metaWebhook` documents are Jackson-mapped (camelCase), so write each fixture by hand from one of them, turning `messagingProduct/phoneNumberId/displayPhoneNumber/waId/recipientId/mimeType/messageId/errorData/pricingModel/expirationTimestamp/productItems/productRetailerId/itemPrice/catalogId/formattedName/firstName/lastName` into snake_case: `text_inbound.json`, `image_forwarded.json`, `document.json`, `audio_voice.json`, `video.json`, `location.json`, `contacts.json`, `reaction.json`, `button.json`, `interactive_nfm_reply.json`, `order.json`, `unsupported.json`, `system_changed_number.json`, `text_with_referral.json`, `text_with_user_id.json`, `status_sent_marketing.json`, `status_read_user_id.json`, `status_failed.json`, `smb_message_echo_text.json`.

```java
@ParameterizedTest
@MethodSource("fixtures")   // every file under webhook/meta
void everyRealMetaBodyDeserialises(Path fixture) throws IOException {
    WebhookEvent e = mapper.readValue(Files.readString(fixture), WebhookEvent.class);
    assertThat(e.object()).isEqualTo("whatsapp_business_account");
    assertThat(e.entry()).hasSize(1);
    assertThat(e.entry().get(0).changes().get(0).value().messagingProduct()).isEqualTo("whatsapp");
}

@Test
void anInboundTextIsTyped() {            // text_inbound.json
    Message m = first(read("text_inbound.json")).messages().get(0);
    assertThat(m.from()).isEqualTo("919928088743");
    assertThat(m.id()).startsWith("wamid.");
    assertThat(m.type()).isEqualTo("text");
    assertThat(m.text().body()).isEqualTo("Hi");
    assertThat(first(read("text_inbound.json")).contacts().get(0).profile().name()).isEqualTo("Nagesh Kumar");
}

@Test
void aStatusIsTyped() {                   // status_sent_marketing.json
    Status s = first(read("status_sent_marketing.json")).statuses().get(0);
    assertThat(s.status()).isEqualTo("sent");
    assertThat(s.recipientId()).isEqualTo("919928088743");
    assertThat(s.conversation().origin().type()).isEqualTo("marketing");
    assertThat(s.pricing().billable()).isTrue();
}

@Test
void aReferralAndAUserIdAreTyped() {
    Message ad = first(read("text_with_referral.json")).messages().get(0);
    assertThat(ad.referral().sourceId()).isEqualTo("120234880794530738");
    assertThat(ad.referral().ctwaClid()).isNotBlank();
    Message lid = first(read("text_with_user_id.json")).messages().get(0);
    assertThat(lid.fromUserId()).isEqualTo("IN.1976699579621058");
}

@Test
void ourOwnEventRoundTripsInSnakeCaseWithoutNulls() {
    ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
    WebhookEvent e = WebhookEvent.of(ctx, List.of(new Change("messages", Value.of(ctx).withMessages(List.of(
            Message.text("false_91…@c.us_ABC", "919912345678", null, "1700000000", "hello",
                    new WaWeb("919912345678@c.us", 0, "chat", null, null, null, null, null, null, null, null)))))));
    JsonNode json = mapper.valueToTree(e);
    JsonNode value = json.at("/entry/0/changes/0/value");
    assertThat(value.path("messaging_product").asString()).isEqualTo("whatsapp");
    assertThat(value.at("/metadata/phone_number_id").asString()).isEqualTo("pn-1");
    assertThat(value.at("/messages/0/wa_web/chat_id").asString()).isEqualTo("919912345678@c.us");
    assertThat(value.at("/messages/0").has("from_user_id")).isFalse();     // nulls left out
    assertThat(mapper.treeToValue(json, WebhookEvent.class)).isEqualTo(e);
}
```

(`Message.text(id, from, fromUserId, timestamp, body, waWeb)` and `Value.withMessages(...)` are convenience factories the test fixes; add the ones later tasks need as they need them.)

- [ ] **Step 2: Run them** — `./mvnw -q -pl omnichannel-common test -Dtest=WebhookEventJsonTest` — Expected: FAIL, the classes do not exist.
- [ ] **Step 3: Write the records**, the two enums/records, the factories, and the fixtures. Jackson 3 deserialises records through their canonical constructor; no `@JsonCreator` is needed when every component name matches after the snake-case strategy.
- [ ] **Step 4: Run them** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(common): typed Cloud API webhook model, ChannelKind and ChannelContext`

---

### Task 2: Converter, part 1 — ids, inbound text, media, location, cards, contacts, context and referral

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/channel/whatsapp/WaWebWebhookConverter.java`
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/channel/whatsapp/WaIds.java` (the key and Wid readers moved from `LoggingEventListener.wid/messageId` and `MessagePayloads.waMessageId`, now reading `_serialized`, then `$1`, then rebuilding; `LoggingEventListener` delegates to it)
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/channel/whatsapp/WaWebWebhookConverterTest.java`
- Fixtures: `omnichannel-service/src/test/resources/wawebhook/<case>.raw.json` and `<case>.expected.json`

**Interfaces:**
- Produces: `public final class WaWebWebhookConverter` (a Spring `@Component` with no state):
  - `Optional<WebhookEvent> convert(ChannelContext ctx, String eventName, JsonNode payload, Instant capturedAt)` — `message_create` → one event (inbound here; echoes in Task 3); `message` → empty (duplicate of `message_create`); any other name → empty in this task.
  - `Optional<Message> message(ChannelContext ctx, JsonNode raw, Instant fallbackTime)` — an inbound message (`fromMe` false), empty for a notice, a non-person chat or a missing id.
  - `Contact contactOf(Message m, JsonNode raw)` — `wa_id = from`, `user_id = from_user_id`, `profile.name` from `notifyName`, `_data.notifyName`, `pushname`.
  - `static String chatIdOf(JsonNode raw)` (= today's `MessagePayloads.remote`), `static boolean isPersonChat(String chatId)` (`@c.us` or `@lid`).
- Consumes: Task 1's records and factories.

Golden test mechanics: for each case the test reads `raw.json`, calls `convert(CTX, "message_create", raw, CAPTURED)` and compares `mapper.valueToTree(result)` with `mapper.readTree(expected.json)` (`assertThat(actual).isEqualTo(expected)` on `JsonNode`); `CTX = new ChannelContext(WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743")`, `CAPTURED = Instant.parse("2026-11-05T10:00:00Z")`. A case whose expected file is absent asserts `isEmpty()`.

- [ ] **Step 1: Write the failing tests** — one `@ParameterizedTest` over the case names plus the targeted asserts below. Cases (raw shapes per `whatsapp-web-storage-analysis.md` §2.2; ids like `false_919912345678@c.us_3EB0ABC`):
  `text_cus` (`@c.us`, `notifyName`, `t`), `text_lid_with_sender_pn` (`@lid` remote, `senderPn` → `from` digits and `from_user_id`), `text_lid_without_phone` (no `from`, `from_user_id` set), `text_key_dollar1` (id has `$1`, no `_serialized`; `remote` an object), `text_remote_string` (`id.remote` a plain string), `text_template` (`hsmTag UTILITY`, `templateId`, `hydratedButtons` → `text` + `wa_web.business_tag`), `text_link_preview` (`subtype url`, `title`, `description` → `text`), `image_caption` (`directPath`, `filehash`, `mimetype`, `size`, `caption`, base64 `body` → `image{id = message id, mime_type, sha256 = filehash, caption}`, `wa_web.file_size`, `wa_web.media_available true`, no `text`), `image_view_once` (no `directPath` → `media_available false`), `video_gif`, `ptt` (→ `audio{voice: true}`, `wa_web.original_type "ptt"`), `document` (`filename`), `sticker`, `location` (`lat/lng/loc` → `location{latitude, longitude, name, address}`), `vcard` (→ `contacts[{name{formatted_name, first_name}, phones[{phone, wa_id}]}]` parsed from `FN`, `N`, `TEL;waid=`; `wa_web.vcards`), `multi_vcard` (`vcardList`), `reply_quoted` (`quotedStanzaID`, `quotedParticipant` → `context{id, from}`), `forwarded` (`isForwarded`, `forwardingScore 6` → `context{forwarded: true, frequently_forwarded: true}`), `ad_referral` (`ctwaContext{sourceId, sourceUrl, sourceApp, conversionSource, title, description, ctwaClid, mediaType}` → `referral{source_id, source_url, source_type "ad", headline, body, media_type, ctwa_clid}`, `wa_web.ctwa_context` without `thumbnail`/`conversionData`, `wa_web.ctwa_source_app`, `wa_web.ctwa_conversion_source`), `link_preview_not_ad` (`ctwaContext{sourceUrl, title, description, mediaType: 1}` → no `referral`, no `ctwa_*`), `notice_dropped` (`notification_template` → empty), `group_dropped` (`@g.us` → empty), `no_id_dropped`, `message_event_ignored` (event name `message` → empty).

```java
@Test
void theTwoKeyFormsGiveTheSameId() {
    Message a = convert("text_key_dollar1").messages().get(0);
    Message b = convert("text_cus").messages().get(0);
    assertThat(a.id()).isEqualTo(b.id());     // both fixtures describe the same message
}

@Test
void timeFallsBackToTheCaptureTimeThenNow() {
    ObjectNode raw = (ObjectNode) read("text_cus"); raw.remove("t");
    assertThat(convertRaw(raw).messages().get(0).timestamp()).isEqualTo(String.valueOf(CAPTURED.getEpochSecond()));
}

@Test
void aContactNamesThePersonOnce() {
    Value v = convert("text_lid_with_sender_pn");
    assertThat(v.contacts()).singleElement().satisfies(c -> {
        assertThat(c.waId()).isEqualTo("919912345678");
        assertThat(c.userId()).isEqualTo("148073477075110");
        assertThat(c.profile().name()).isEqualTo("Asha");
    });
}
```

- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-service -am test -Dtest=WaWebWebhookConverterTest -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL, class missing.
- [ ] **Step 3: Implement** `WaIds` and the converter's inbound path. Body rules come from `MessagePayloads.body` and `MessageBodies.readable` (caption on media, thumbnail dropped); location from `location.*`, else `lat/lng/loc`, else `_data.*`; `name` is the first line of the description, `address` the rest. vCard parsing: reuse `ContactCards`' `FN`/`N` patterns, add `TEL(;[^:]*)?:` with `waid=` for `wa_id`. Ad rule as `MessagePayloads.adClick` after the 2026-11-05 fix (needs `sourceId`, `conversionSource` or a click id); `media_type` from the numeric code (`1` → `image`, `2` → `video`, else absent). Timestamp: `t`, else `timestamp`, else the capture time, else now, as `MessagePayloads.timestamp`.
- [ ] **Step 4: Run** — Expected: PASS, every golden case equal.
- [ ] **Step 5: Commit** — `feat(service): WaWebWebhookConverter for inbound messages`

---

### Task 3: Converter, part 2 — echoes, statuses and acks

**Files:**
- Modify: `WaWebWebhookConverter.java`
- Test: `WaWebWebhookConverterTest.java` (new cases), fixtures under `wawebhook/`

**Interfaces:**
- Produces: `Optional<MessageEcho> echo(ChannelContext ctx, JsonNode raw, Instant fallbackTime, String toDigitsIfKnown)`; `Optional<Status> status(ChannelContext ctx, JsonNode ackPayload, Instant capturedAt)`; `static Status statusOf(String messageId, int ack, Instant at, String chatId, String recipientDigits)` (ack → `sent/delivered/read/played/failed`, 0 → null). `convert` handles `message_create` with `fromMe` → `smb_message_echoes` change (+ a `messages` change with one status when ack ≥ 1) and `message_ack` → a `messages` change with one status when the message is ours, empty when it is inbound.

- [ ] **Step 1: Write the failing tests** — cases `echo_text_ack0` (one change, `message_echoes[0].from = 919928088743`, `to = 919912345678`, `wa_web.ack 0`, no `statuses`), `echo_image_ack2` (two changes: the echo, then `{field: messages, value.statuses[{id, status: delivered, timestamp, recipient_id, wa_web{chat_id, ack: 2}}]}`), `echo_lid_unknown_phone` (`to` absent, `wa_web.chat_id` the lid), `ack_read_ours` (`message_ack` payload `{message: {id: {fromMe: true, …}}, ack: 3}` → `statuses[{status: read, timestamp = capture seconds}]`), `ack_failed_ours` (`ack -1` → `failed` with `errors[{code: 0, title: "Not sent", message: …}]`), `ack_inbound_dropped` (`fromMe false` → empty), `ack_pending_dropped` (`ack 0` → empty).

```java
@Test
void anEchoWithAnAckCarriesItsStatusInTheSameEntry() {
    WebhookEvent e = convertEvent("echo_image_ack2");
    List<Change> changes = e.entry().get(0).changes();
    assertThat(changes).extracting(Change::field).containsExactly("smb_message_echoes", "messages");
    assertThat(changes.get(1).value().statuses().get(0).id()).isEqualTo(changes.get(0).value().messageEchoes().get(0).id());
}
```

- [ ] **Step 2: Run** — Expected: FAIL (`echo`/`status` missing; `fromMe` payloads currently come back as inbound).
- [ ] **Step 3: Implement.** `from` of an echo is `ctx.displayPhoneNumber()`; `to` is the chat's digits for `@c.us`, else `toDigitsIfKnown`, else absent; `timestamp` of a status from an ack is the capture time in seconds.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(service): converter echoes and statuses`

---

### Task 4: Converter, part 3 — the extension types and the business shapes

**Files:**
- Modify: `WaWebWebhookConverter.java`
- Test: `WaWebWebhookConverterTest.java` (new cases), fixtures

**Interfaces:**
- Produces: `convert` handles `vote_update` (→ a `poll_vote` message in `messages` or `message_echoes` by `fromMe`, id `"vote:" + pollMessageId + ":" + voter chat key`, `timestamp = at / 1000`, `poll_vote{message_id, selected_options}`); the type table of spec §4.3 for `poll_creation` (`allow_multiple_answers = pollSelectableOptionsCount != 1`; a fetched `pollVotes` list → `votes[]` through `PollVotes.parse`), `call_log` (`missed` for `Missed`/`Rejected`), `revoked` (`protocolMessageKey` via `WaIds`), `biz_content_placeholder`, `newsletter_follower_invite`, `album`, `unknown` → `unsupported` with `errors[{code 131051, title "Message type unknown", message same, error_data{details: "<original type>"}}]`, business `interactive`/`list`/`buttons`/`hsm`/`template`/`native_flow` → `interactive{type, header, body{text}, footer{text}, action{buttons}}` (+ media header and `wa_web.media_available` when `directPath` present), `buttons_response`/`template_button_reply` → `button{payload, text}`, `list_response` → `interactive{type: list_reply, list_reply{id, title}}`, `rich_response` → `text` when a text can be found in `unifiedResponse`, else `unsupported`, `order` → `order{catalog_id, text}` from `orderId`/`orderTitle` when present else `unsupported`; any other type → `unsupported`. `wa_web.original_type` is set whenever the type changed.

- [ ] **Step 1: Write the failing tests** — cases `poll_creation_single`, `poll_creation_multi_with_votes`, `vote_inbound`, `vote_ours`, `call_missed`, `call_rejected`, `call_completed_video`, `revoked_sender` (`protocolMessageKey` with `$1` → `revoked{original_message_id}`), `biz_placeholder_auth` (`wa_web.business_tag AUTHENTICATION`), `newsletter_invite`, `album`, `unknown_phone_only`, `interactive_quick_reply_with_video` (from the live shape: `nativeFlowName quick_reply`, `interactiveHeader{hasMediaAttachment, mediaType VIDEO}`, `interactivePayload.buttons[{name quick_reply, buttonParamsJson "{\"display_text\":\"English\",\"id\":\"0004_hindi_0\"}"}]`, `caption`, `footer`, `hsmTag`, `directPath`, `mimetype video/mp4` → `interactive{type: button, header{type: video, video{id, mime_type, sha256}}, body{text = caption}, footer{text}, action{buttons[{type: reply, reply{id: "0004_hindi_0", title: "English"}}]}}`, `wa_web{business_tag, media_available: true, original_type: interactive}`), `interactive_cta_url` (`cta_url` → `type: cta_url`, the button's `url`), `buttons_response`, `list_response`, `rich_response_text`, `order_minimal`, `unknown_type_falls_to_unsupported` (type `scheduled_event_creation`).

```java
@Test
void aVoteIsAMessageAboutThePollWithADeterministicId() {
    Value v = convertEvent("vote_inbound", "vote_update").entry().get(0).changes().get(0).value();
    Message vote = v.messages().get(0);
    assertThat(vote.type()).isEqualTo("poll_vote");
    assertThat(vote.id()).isEqualTo("vote:true_919912345678@c.us_POLL1:919912345678@c.us");
    assertThat(vote.pollVote().selectedOptions()).containsExactly(0, 2);
    assertThat(vote.waWeb().chatId()).isEqualTo("919912345678@c.us");
}
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS, and `MessagePayloadsTest`'s cases for calls, polls, notices, cards and ads each have a converter counterpart (they move here in Task 7).
- [ ] **Step 5: Commit** — `feat(service): converter extension types and business interactive shapes`

---

### Task 5: The `channelWebhookEvents` store and its properties

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/channel/ChannelWebhookEvent.java` (`@Document("channelWebhookEvents")`: `id, channelKind (ChannelKind), channelAccountId, workspaceId, customerId, field, event (org.bson.Document or Map<String,Object>), raw (Map<String,Object>), rawDropped (boolean), seq (Long), capturedAt (Instant), creationTime (Instant, @CreatedDate)`; `@CompoundIndex(name = "account_time", def = "{'channelAccountId': 1, 'creationTime': -1}")`)
- Create: `omnichannel-db/.../db/channel/ChannelWebhookEventRepository.java` (`deleteByChannelAccountId`, `findByChannelAccountIdOrderByCreationTimeDesc(String, Pageable)`)
- Modify: `omnichannel-common/.../config/OmnichannelProperties.java` (add `Webhooks webhooks` with `record Webhooks(@DefaultValue("30d") Duration retention, @DefaultValue("true") boolean keepRaw, @DefaultValue("65536") int rawMaxBytes)`; update every test that constructs `OmnichannelProperties` by hand — `grep -rl "new OmnichannelProperties(" --include=*.java`)
- Modify: `omnichannel-app/src/main/resources/application.yml` (`omnichannel.webhooks.retention: 30d`, `keep-raw: true`, `raw-max-bytes: 65536`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/channel/ChannelWebhookEventStore.java`
- Modify: `omnichannel-service/.../service/inbox/InboxCleanup.java` (`webhookEvents.deleteByChannelAccountId(phoneNumberId)`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/channel/ChannelWebhookEventStoreIT.java`

**Interfaces:**
- Produces: `ChannelWebhookEventStore.save(ChannelContext ctx, WebhookEvent event, JsonNode raw, Long seq, Instant capturedAt)` returning the saved document; never throws (logs WARN on `DataAccessException`); `raw` is kept when `keepRaw` and its JSON is at most `rawMaxBytes`, else `raw = null, rawDropped = true`; keys with a dot or leading `$` are removed at any depth (move `MessagePayloads.dropMongoKeys` here). The TTL index `creationTime_ttl` on `creationTime` with `expireAfter = retention` is created in `@PostConstruct`/`SmartInitializingSingleton` and replaced when an existing one has a different expiry.

- [ ] **Step 1: Write the failing tests** — `savesTheEventAndTheRaw`, `dropsARawOverTheCap` (`@TestPropertySource(properties = "omnichannel.webhooks.raw-max-bytes=200")`, a 1 KB raw → `raw null`, `rawDropped true`, `event` kept), `removesKeysMongoRefuses` (`{"a.b": 1, "$c": 2, "ok": 3}` → only `ok`), `createsTheTtlIndexFromTheProperty` (`mongo.indexOps(ChannelWebhookEvent.class).getIndexInfo()` has an index on `creationTime` whose `getExpireAfter()` is `Duration.ofDays(30)`), `aWriteFailureIsLoggedNotThrown` (a store built on a `MongoTemplate` mock throwing `DataAccessResourceFailureException` returns null and throws nothing), `cleanupDeletesTheNumbersEvents`.
- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-app -am test -Dtest=ChannelWebhookEventStoreIT -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS; also `./mvnw -q -pl omnichannel-service -am test` compiles everything that builds `OmnichannelProperties`.
- [ ] **Step 5: Commit** — `feat: channelWebhookEvents store with retention`

---

### Task 6: The processor, the typed ingest and the listener swap

**Files:**
- Create: `omnichannel-service/.../service/channel/whatsapp/WhatsAppWebhookProcessor.java`
- Create: `omnichannel-service/.../service/channel/whatsapp/WaWebWebhookListener.java`
- Delete: `omnichannel-service/.../service/inbox/WaWebInboxListener.java`
- Create: `omnichannel-service/.../service/inbox/MessageFields.java` (static readers over the typed records, replacing `MessagePayloads` for the typed shape: `chatId(Message|MessageEcho|Status)` from `wa_web.chat_id` else `from`/`recipient_id` + `@c.us`, `bodyOf`, `mediaOf` (null unless `wa_web.media_available` is true; the type object or the interactive header), `locationOf`, `pollOptionsOf`, `pollVotesOf`, `callOf`, `businessTagOf`, `adClickOf(Message, Instant)` (from `referral` + `wa_web.ctwa_*`), `senderNameOf(Contact)`, `lidPhoneOf(Message)` (= `from` when `from_user_id` is set), `timestampOf(String seconds, Instant fallback)`, `ackOf(Status)` (`sent 1, delivered 2, read 3, played 4, failed -1`))
- Modify: `omnichannel-service/.../service/inbox/InboxIngestService.java`
- Modify: `omnichannel-service/.../service/events/LoggingEventListener.java` (use `WaIds`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/channel/WhatsAppWebhookProcessorIT.java`; `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/channel/whatsapp/WaWebWebhookListenerTest.java`
- Modify tests: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/WaWebInboxListenerIT.java` (rename to `WaWebWebhookListenerIT`; inbound rows are now `PENDING` with `ack 0`, spec §4.5)

**Interfaces:**
- Produces: `WhatsAppWebhookProcessor.process(ChannelContext ctx, WebhookEvent event)`: per entry, collects the entry's statuses by id, then per change in order: `contacts` are passed with each `messages[]` item to `ingest.ingestInbound(ctx, contact, message)` (a `poll_vote` goes to `ingest.ingestVote(ctx, chatId, PollVote, voter, fromMe, at)`), `message_echoes[]` to `ingest.ingestEcho(ctx, echo, statusInSameEntryOrNull)`, remaining `statuses[]` to `ingest.ingestStatus(ctx, status)`. `InboxIngestService` keeps `Ingested`, `ImportContext`, `ImportResult`, `retrying`, `preview`, `applyAck`, `foldOutbound`; its `ingestMessage/ingestAck/ingestPollVote(WhatsAppEvent)` are replaced by the typed methods with the same stored rows and the same published events (`ChatCreated` → `ActionRecorded`/`ChatReopened` → `CampaignChatFirstReply` → `MessageUpserted` → `ChatChanged`). `WaWebWebhookListener` (`@EventListener` on `WhatsAppEvent`, synchronous): builds `ChannelContext` (digits from `PhoneNumberRepository.findById(phoneNumberId).number` without `+`, cached in a `ConcurrentHashMap`), calls `converter.convert`, then `store.save`, then `processor.process`; rethrows `IngestionFailedException`; logs other `RuntimeException`s at WARN with the event name and id and does not throw; device events (`ready`, `change_state`, `disconnected`) fall through untouched.
- Consumes: Tasks 1–5.

- [ ] **Step 1: Write the failing tests**

`WaWebWebhookListenerTest` (Mockito on converter, store, processor, repository): `aConvertedEventIsStoredThenProcessed` (in that order, `InOrder`), `anIngestionFailureReachesTheHandler` (processor throws `IngestionFailedException` → the listener throws it), `aStoreFailureDoesNotStopTheMessage` (store throws → processor still called; Review Focus 4), `anUnconvertibleEventIsLoggedAndAcknowledged` (converter throws `IllegalArgumentException` → nothing thrown, store called with the raw payload and no event), `aDeviceEventIsNotConverted` (`ready` → converter never called).

`WhatsAppWebhookProcessorIT` (real Mongo, `omnichannel.contacts.resolve-on-message=false`):
```java
@Test
void aRealMetaInboundTextIsStoredAsAMessageFromAPhoneChat() {   // text_inbound.json from Task 1, no wa_web
    processor.process(CTX, read("text_inbound.json"));
    WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId("pn-1", "919928088743@c.us").orElseThrow();
    WaWebMessage m = messages.findByPhoneNumberIdAndWaMessageId("pn-1", "wamid.HBgMOTE5OTI4MDg4NzQzFQIAEhgUM0EwMUMyQjk2NDBDMTE5N0Y3RkQA").orElseThrow();
    assertThat(m.getType()).isEqualTo("text");
    assertThat(m.getBody()).isEqualTo("Hi");
    assertThat(m.getDirection()).isEqualTo(MessageDirection.IN);
    assertThat(chat.getUnreadCount()).isEqualTo(1);
}

@Test
void anEchoAndItsStatusInOneEntryAreOneWrite() {   // Review Focus 1 and spec §4.1
    processor.process(CTX, echoWithStatus("true_919912345678@c.us_E1", 2));
    assertThat(messages.findByPhoneNumberIdAndWaMessageId("pn-1", "true_919912345678@c.us_E1")).hasValueSatisfying(m -> {
        assertThat(m.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(m.getStatusTimes().getDeliveredAt()).isNotNull();
    });
    assertThat(recorder.events).filteredOn(e -> e instanceof InboxEvents.MessageUpserted).hasSize(1);
}

@Test
void anEchoForAMessageTheConsoleStoredAppliesTheStatusOnce() { … inserted=false, one row … }

@Test
void aStatusForAnUnknownMessageIsDropped() { … no row, no event … }

@Test
void aNewInboundChatPublishesCreatedThenUpsertedThenChanged() { … Review Focus 5, event order … }
```
Then edit `WaWebInboxListenerIT` → `WaWebWebhookListenerIT`: keep every test; where an inbound row's status was asserted from its ack, assert `PENDING`/`ack 0`; a `message_ack` on an inbound message now changes nothing.

- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-app -am test -Dtest='WaWebWebhookListenerTest,WhatsAppWebhookProcessorIT,WaWebWebhookListenerIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL (classes missing).
- [ ] **Step 3: Implement** the processor, `MessageFields`, the typed ingest methods (port `storeMessage`, `storeAck`, `storeVote` line by line onto the typed inputs; the chat is found by `MessageFields.chatId`; contact upsert gets `senderNameOf(contact)` and `lidPhoneOf(message)` on inbound only), the listener, and delete `WaWebInboxListener`.
- [ ] **Step 4: Run** those plus the inbox neighbours — `-Dtest='WaWebWebhookListenerTest,WhatsAppWebhookProcessorIT,WaWebWebhookListenerIT,CampaignChatIT,AdClickIngestIT,ContactIdResolverIT,InboxStreamIT,ChatRoutingIT,AutomationMessageIT*'` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: WhatsAppWebhookProcessor and typed ingest replace the raw-model listener`

---

### Task 7: The send result and the history pages go through the converter; `MessagePayloads` goes

**Files:**
- Modify: `omnichannel-service/.../service/inbox/InboxSendService.java` (`afterSend`: `converter.echo(ctx, result, now, typedPhone)`; read id, timestamp, type, chat id from the typed echo; `console:<id>` fallback unchanged)
- Modify: `omnichannel-service/.../service/inbox/history/HistoryImportRunner.java` (each `page.messages()` node → `converter.message(...)` or `converter.echo(...)` by `fromMe`, then `ingest.importMessage(ctx, Message)` / `ingest.importEcho(ctx, MessageEcho)`)
- Modify: `InboxIngestService.java` (`importMessage(ImportContext, Message)`, `importEcho(ImportContext, MessageEcho)`; `storeImported` ported onto the typed inputs, `fillIn` reads `poll_creation.votes`, `call_log`, `wa_web.business_tag`, `newsletter_follower_invite.name`)
- Delete: `omnichannel-service/.../service/inbox/MessagePayloads.java` and `MessagePayloadsTest.java` (its cases live in `WaWebWebhookConverterTest` since Tasks 2–4; move any that do not yet)
- Modify: `ChatPreviewBackfill.java` (`NOTICE_TYPES` moves to `WaWebWebhookConverter.NOTICE_TYPES`)
- Tests to keep green: `InboxSendIT`, `HistoryImportRunnerIT`, `HistoryImportRunnerPagingIT`, `HistoryImportNamesIT`, `AdClickIngestIT` (its `historyAd` case), `ChatPreviewBackfillIT`

**Interfaces:**
- Consumes: `WaWebWebhookConverter.message/echo` (Tasks 2–3), the typed ingest (Task 6).
- Produces: nothing new; `grep -rn "MessagePayloads" --include=*.java .` returns nothing.

- [ ] **Step 1: Write the failing test** — in `HistoryImportRunnerIT`, `aFetchedPageIsStoredThroughTheConverter`: a page with a `ptt` model is stored as `audio`… (type names change only in Task 8; here assert on `media != null`, `direction`, `status` without times) — and in `InboxSendIT`, `aSendResultWithADollarOneKeyIsStoredUnderItsRealId` (the fake device answers a model whose `id` has `$1` only).
- [ ] **Step 2: Run** — Expected: the `$1` send case FAILS (today `MessagePayloads.waMessageId` reads `_serialized` only, so the row is stored as `console:<id>`).
- [ ] **Step 3: Implement**, delete `MessagePayloads`.
- [ ] **Step 4: Run** the listed tests — Expected: PASS.
- [ ] **Step 5: Commit** — `refactor: one reader of the raw WhatsApp Web model`

---

### Task 8: Meta's type names in the store, and the one-off migration

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/config/MessageTypeMigration.java` (`updateMany` on `waWebMessages.type` and `waWebChats.lastMessage.type`: `chat→text`, `ptt→audio`, `vcard→contacts`, `multi_vcard→contacts`, `unknown→unsupported`, `list/buttons/hsm/template/native_flow/list_response→interactive`, `buttons_response/template_button_reply→button`; logs the counts; idempotent)
- Modify: `CollectionRenames.java` (call `messageTypes.migrate()` after `contactIds.migrate()`)
- Modify: `WaWebWebhookConverter.java` (the stored `type` is the webhook `type`: no reverse mapping anywhere)
- Modify: `InboxIngestService.preview` (`"text"` replaces `"chat"`; `"audio"` only; `"contacts"` → `ContactCards.preview`; `"unsupported"` → "Message to view on the phone"), `MessageBodies.TEXT_TYPES = Set.of("text")`, `MessageKinds.KIND_BY_TYPE` (`text→TEXT, image→PHOTO, video→VIDEO, audio→AUDIO, document→DOCUMENT, sticker→STICKER, location→LOCATION, contacts→CONTACT_CARD, poll_creation→POLL`), `ChatPreviewBackfill.labelled()`, `InboxSendService` (`Outgoing.type`: `"text"` for text, `"audio"` for voice and audio), `ContactCards` callers
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/MessageTypeMigrationIT.java`
- Modify tests: the 26 test files using `"chat"`, `"ptt"`, `"vcard"`, `"multi_vcard"`, `"unknown"` as stored or expected types (`grep -rln '"chat"\|"ptt"\|"vcard"\|"multi_vcard"\|"unknown"' --include='*Test.java' --include='*IT.java' omnichannel-service/src/test omnichannel-app/src/test`); raw fixtures keep WhatsApp Web's names, stored-row assertions take Meta's

**Interfaces:**
- Consumes: the converter's `type` (Tasks 2–4) is now what `WaWebMessage.type` holds.
- Produces: the stored vocabulary of spec §4.7; the console plan's Task C1 depends on it.

- [ ] **Step 1: Write the failing tests** — `MessageTypeMigrationIT`: `renamesTheOldTypesOnMessagesAndChatPreviews` (seed rows of each old type and a chat whose `lastMessage.type` is `ptt`; run `migration.migrate()`; assert the new names and that `image`/`interactive`/`poll_creation` rows are untouched), `runningTwiceChangesNothing` (second run modifies 0). In `InboxIngestServicePreviewTest`: `preview("text", "hello", null)` is `hello`, `preview("contacts", vcard, null)` is `👤 Wife`, `preview("unsupported", null, null)` is `Message to view on the phone`. In `WaWebWebhookListenerIT`: a raw `chat` message is stored with `type text`; a raw `ptt` with `type audio`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** the migration and the switches; update the test literals.
- [ ] **Step 4: Run** — `./mvnw -q -pl omnichannel-app -am test` (the whole app module: many tests touch types) — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: Meta's message type names in the store, with a one-off migration`

---

### Task 9: Docs and limits

**Files:**
- Modify: `docs/superpowers/known-limits.md` (new section "WhatsApp Web events as Cloud webhooks": inbound rows carry no ack; a Cloud `contacts` message would render nothing; reactions/edits not forwarded; `poll_vote` and its id are ours; the webhook store doubles writes; `Media.mediaId` is the GridFS id; the "interactive business messages lose their buttons" line changes to "the buttons travel in the stored webhook event; the row keeps the text")
- Modify: `docs/superpowers/crm-rulings.md` (a section for this plan's rulings, as the executor records them)
- Modify: `README.md` (configuration: `omnichannel.webhooks.*`; a line on `channelWebhookEvents`)
- Modify: `docs/superpowers/project-rules.md` §3 (where the webhook model and converter live)

- [ ] **Step 1: Write the entries.**
- [ ] **Step 2: Verify** — `grep -n "channelWebhookEvents" README.md docs/superpowers/known-limits.md` lists both.
- [ ] **Step 3: Commit** — `docs: known limits and configuration for the Cloud webhook shape`

---

### Task 10: A self-describing webhook event; the store keeps the event only

Requested by the user on 2026-11-06 after seeing the first documents. Spec amendments are part of this task.

**Files:**
- Modify: `omnichannel-common/.../common/channel/whatsapp/webhook/WebhookEvent.java` (`WebhookEvent.of(ctx, changes)`: `object` = `"whatsapp_web_account"` when `ctx.kind() == WHATSAPP_WEB`, `"whatsapp_business_account"` when `WHATSAPP_CLOUD`; `Entry.id` = `ctx.workspaceId()`)
- Modify: `omnichannel-db/.../db/channel/ChannelWebhookEvent.java` — remove `channelKind`, `channelAccountId`, `workspaceId`, `customerId`, `field`; keep `id, event, raw, rawDropped, seq, capturedAt, creationTime`; replace the compound index with `{"event.entry.changes.value.metadata.phone_number_id": 1, "creationTime": -1}` (name `number_time`)
- Modify: `ChannelWebhookEventRepository` — `deleteByChannelAccountId` becomes a delete by the nested path (a `@Query`/`@DeleteQuery` on `event.entry.changes.value.metadata.phone_number_id`, or a `MongoTemplate` remove in the store); the find-by-account method likewise, or dropped if unused
- Modify: `omnichannel-service/.../service/channel/ChannelWebhookEventStore.java` — stop setting the removed fields; when `event` is null (an unconvertible raw) the document has no number path, so give it `event = {object, entry: [{id: workspaceId, changes: [{field: null, value: {messaging_product, metadata}}]}]}` built from the context via `Value.of(ctx)` so the raw is still found by number — **(my call)**; cleanup in `InboxCleanup.deleteForNumber` keeps working through the nested path
- Modify: `WaWebWebhookConverter` only if it builds `WebhookEvent`/`Entry` other than through `WebhookEvent.of` (make it use the factory)
- Modify: every golden `*.expected.json` under `omnichannel-service/src/test/resources/wawebhook/` (`object` and `entry[0].id` change; a sed over the files is fine), `WebhookEventJsonTest.ourOwnEventRoundTripsInSnakeCaseWithoutNulls`, `ChannelWebhookEventStoreIT` (asserts on the removed fields → asserts on `event.object`, `event.entry[0].id`, the nested number path; the cleanup test stays), `WhatsAppWebhookProcessorIT`/`WaWebWebhookListenerIT` if they read the removed fields
- Modify docs: spec §4.1 (envelope: `object` is `whatsapp_web_account` for a converted event, `whatsapp_business_account` for a real one; `entry[].id` is the workspace id, the brand being the analogue of Meta's business account), §5.4 (the document's fields and the nested index), README (`channelWebhookEvents` paragraph), `known-limits.md` if it names the fields

**Interfaces:**
- Consumes: `ChannelContext(kind, customerId, workspaceId, channelAccountId, displayPhoneNumber)`; `Value.of(ctx)` (sets `metadata.phone_number_id = channelAccountId`).
- Produces: `WebhookEvent.object ∈ {whatsapp_web_account, whatsapp_business_account}`, `Entry.id = workspaceId`; `ChannelWebhookEvent{id, event, raw, rawDropped, seq, capturedAt, creationTime}`.

- [ ] **Step 1: Write the failing tests** — in `WebhookEventJsonTest`: `aConvertedEventSaysWhereItCameFrom` (`WebhookEvent.of(webCtx, …).object()` is `whatsapp_web_account`, `entry().get(0).id()` is the workspace id; a `WHATSAPP_CLOUD` context gives `whatsapp_business_account`); in `ChannelWebhookEventStoreIT`: `theDocumentHoldsTheEventAndNothingElseAboutTheChannel` (saved document has no `channelKind/channelAccountId/workspaceId/customerId/field` keys — read it back as a raw `Document` — and `event.entry[0].id` equals the workspace id), `anUnconvertibleRawIsStillFoundByNumber` (save with `event = null` → the document's `event.entry[0].changes[0].value.metadata.phone_number_id` is the number and `raw` is kept), `cleanupDeletesTheNumbersEvents` kept, `theNumberIndexExists` (an index on the nested path + creationTime).
- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-common test -Dtest=WebhookEventJsonTest` and `./mvnw -q -pl omnichannel-app -am test -Dtest=ChannelWebhookEventStoreIT -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement**, update the goldens and the docs.
- [ ] **Step 4: Run** — the two above, then `./mvnw -q -pl omnichannel-service -am test` and `./mvnw -q -pl omnichannel-app -am test -Dtest='WhatsAppWebhookProcessorIT,WaWebWebhookListenerIT,InboxSendIT,HistoryImportRunnerIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: a self-describing webhook event; the store keeps the event only`

---

### Task 11: One change per webhook event

Requested by the user on 2026-11-06: Meta never sends more than one object in `changes[]`, so a bridge event that yields an echo and its status becomes **two** webhook events, in sequence (echo first, then status), each with one `entry` holding one `change`. Stored as two documents, in that order. The processor still handles the events of one bridge event as a unit, so an echo and its status remain one row write and one set of `InboxEvents` (plan Review Focus 1, spec §4.1).

**Files:**
- Modify: `omnichannel-service/.../service/channel/whatsapp/WaWebWebhookConverter.java` — `convert(...)` returns `List<WebhookEvent>` (empty, one or two); every event has exactly one entry with exactly one change; the echo event comes first.
- Modify: `omnichannel-service/.../service/channel/whatsapp/WaWebWebhookListener.java` — stores each event in order (`store.save` per event, same `seq` and `capturedAt`), then `processor.process(ctx, events)` once with the whole list.
- Modify: `omnichannel-service/.../service/channel/whatsapp/WhatsAppWebhookProcessor.java` — `process(ChannelContext, List<WebhookEvent>)` treats the list as one unit: statuses anywhere in the list are indexed by id first; an echo takes its status; the rest go to `ingestStatus`. Keep `process(ChannelContext, WebhookEvent)` as a one-element convenience (a real Cloud webhook arrives one at a time).
- Modify: `omnichannel-db/.../db/channel/ChannelWebhookEvent.java` — add `ordinal` (int, 0-based position of the event within its bridge event) so two documents of one `seq` keep their order even when sorted by anything but `_id`; `ChannelWebhookEventStore.save(ctx, event, raw, seq, capturedAt, ordinal)`. The `raw` is stored on the first document only (`ordinal 0`); later documents of the same bridge event carry `raw = null`, `rawDropped = false` **(my call: the raw is one payload; storing it twice doubles the biggest part of the document for nothing)**.
- Modify: `WaWebWebhookConverterTest` — golden expected files for the echo-with-status cases (`echo_image_ack2`, `echo_text_ack_failed`, any other case with two changes) become a JSON array of two events; the test compares `convert(...)` as a list against the expected file (an object counts as a one-element list, so the single-event goldens stay as they are); the targeted test `anEchoWithAnAckCarriesItsStatusInTheSameEntry` becomes `anEchoWithAnAckIsFollowedByItsStatusEvent` (two events, `changes` size 1 each, the status event's id equals the echo's, order echo → status).
- Modify: `WaWebWebhookListenerTest` (store called once per event in order; processor called once with the list), `WhatsAppWebhookProcessorIT` (`anEchoAndItsStatusInOneEntryAreOneWrite` → `anEchoAndItsStatusFromOneBridgeEventAreOneWrite`: feed the two-event list; still one `MessageUpserted`), `ChannelWebhookEventStoreIT` (ordinal stored; raw only on ordinal 0).
- Modify docs: spec §4.1 (the arrays hold one element each; a bridge event may yield two webhook events, echo then status, stored as two documents in order; the processor treats one bridge event's events as a unit), §4.4 ("the same entry gets a second change" → "a second webhook event follows, with the status"), §5.1 (the listener/processor signature), §5.4 (`ordinal`; raw on the first document only); README paragraph.

**Interfaces:**
- Produces: `List<WebhookEvent> WaWebWebhookConverter.convert(ChannelContext, String, JsonNode, Instant)`; `void WhatsAppWebhookProcessor.process(ChannelContext, List<WebhookEvent>)`; `ChannelWebhookEvent.ordinal`; `ChannelWebhookEventStore.save(ctx, event, raw, seq, capturedAt, ordinal)`.
- Consumes: everything else as it is; `InboxIngestService` is untouched.

- [ ] **Step 1: Write the failing tests** (the renamed/changed cases above, plus `ChannelWebhookEventStoreIT.theSecondEventOfABridgeEventCarriesNoRaw`).
- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-service -am test -Dtest='WaWebWebhookConverterTest,WaWebWebhookListenerTest' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — the above, then `./mvnw -q -pl omnichannel-app -am test -Dtest='WhatsAppWebhookProcessorIT,WaWebWebhookListenerIT,ChannelWebhookEventStoreIT,InboxSendIT,HistoryImportRunnerIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: one change per webhook event; an echo and its status are two events in sequence`

### Task 12: History events in the store, an idempotency key, and `_id` in conversation order

Requested by the user on 2026-11-06. Three changes to `channelWebhookEvents`, all in one task because they share the store and its tests.

**A. Idempotency key.** Every stored event with a converted `event` gets `eventKey`, derived from the event's content:
- an inbound message or an echo from a live bridge event: `message:<message id>`
- a message or echo written by the history import: `history:<message id>`
- a status: `status:<message id>:<status>` (one per state)
- a poll vote (a message of type `poll_vote`): `vote:<vote message id>:<timestamp>` (the vote message id is already `vote:<poll>:<voter>`)
- a raw-only shell (conversion failed, `event` is the minimal shell): no key
A unique **sparse** index `eventKey_unique` on `eventKey`. The store inserts (never upserts); a `DuplicateKeyException` means the event exists: log at DEBUG, return the existing document (or null), never throw. Keys are computed by a small helper `WebhookEventKeys.of(WebhookEvent event, boolean imported)` in `me.nageshk.omnichannel.service.channel`, from the single change's value (`messages[0]`, `message_echoes[0]`, `statuses[0]`).

**B. `_id` from the event's own time.** The store sets the document's id to `new ObjectId(Date.from(eventTime)).toHexString()` where `eventTime` is the message's or status's `timestamp` (seconds, as a string, from the same single change), else `capturedAt`, else now. So sorting by `_id` gives conversation order (a fetched two-day-old message lands in its place), while `creationTime` keeps the insertion time and the TTL. Replace the compound index `number_time` with `number_order`: `{'event.entry.changes.value.metadata.phone_number_id': 1, '_id': -1}`.

**C. History events.** `FetchedMessageImporter` stores one event per fetched message (the converted `Message`/`MessageEcho` wrapped as a one-change `WebhookEvent` through `WebhookEvent.of`/`Value.of`), with a new `WaWeb.imported` (Boolean, wire `imported`) set to `true`, **the raw payload kept** (the user's choice), `seq = null`, `capturedAt` = the fetch time, `ordinal 0`, before calling the typed import. A repeat fetch of the same message inserts nothing (A). Live events have `imported` absent (null).

**Files:**
- Modify: `omnichannel-common/.../webhook/WaWeb.java` (+ `imported`), its tests/goldens if a factory signature changes (keep `wa_web.imported` absent on live goldens: null is omitted)
- Modify: `omnichannel-db/.../db/channel/ChannelWebhookEvent.java` (+ `eventKey`; index `number_order`; `@Indexed(unique = true, sparse = true, name = "eventKey_unique") eventKey`)
- Create: `omnichannel-service/.../service/channel/WebhookEventKeys.java`
- Modify: `omnichannel-service/.../service/channel/ChannelWebhookEventStore.java` (`save(ctx, event, raw, seq, capturedAt, ordinal, imported)` or an overload; key, id, insert + duplicate handling)
- Modify: `omnichannel-service/.../service/inbox/history/FetchedMessageImporter.java` (store before import; needs the `ChannelContext` it already builds and the store)
- Tests: `WebhookEventKeysTest` (the four key forms + no key for a shell), `ChannelWebhookEventStoreIT` (`aRepeatedEventIsStoredOnce`: two saves of the same event → one document; `theIdCarriesTheEventsTime`: `new ObjectId(id).getTimestamp()` equals the message timestamp seconds; `aShellHasNoKey`; `theNumberOrderIndexExists`), `HistoryImportRunnerIT` (`aFetchStoresOneEventPerMessageWithTheRaw`: after an import, one document per fetched message, `wa_web.imported true`, `raw` present; `aSecondFetchStoresNoNewEvent`), `WaWebWebhookListenerIT` or the processor IT (a live replay of the same bridge event stores one document — optional if the store IT covers it)
- Docs: spec §5.3 (history events, raw kept), §5.4 (`eventKey`, the `_id` rule with its two caveats: seconds resolution and same-second ties by generation order; a status sorts at its own time), README paragraph; `known-limits.md`: "`_id` of a webhook event is the event's time, not the insertion time; `creationTime` is"
- Plan: append this text as "### Task 12 …" after Task 11

**Interfaces:**
- Consumes: `WebhookEvent.of`, `Value.of`, `ChannelContext`, `InboxDevices.digitsOf`, the store.
- Produces: `WaWeb.imported`, `ChannelWebhookEvent.eventKey`, `WebhookEventKeys.of`, the store's new signature.

- [ ] **Step 1: Write the failing tests** (above).
- [ ] **Step 2: Run** — `./mvnw -q -pl omnichannel-service -am test -Dtest='WebhookEventKeysTest' -Dsurefire.failIfNoSpecifiedTests=false`; `./mvnw -q -pl omnichannel-app -am test -Dtest='ChannelWebhookEventStoreIT,HistoryImportRunnerIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — the above, then `./mvnw -q -pl omnichannel-service -am test` and `./mvnw -q -pl omnichannel-app -am test -Dtest='WhatsAppWebhookProcessorIT,WaWebWebhookListenerIT,InboxSendIT,HistoryImportRunnerPagingIT,HistoryImportNamesIT,HistoryImportRunnerEventsIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: history events in the webhook store, an idempotency key, and _id in conversation order`

## Verification before the merge

- `./mvnw -q verify` once in the worktree (all modules; expect every surefire report at 0 failures, 0 errors).
- Console plan complete (`plans/2026-11-05-cloud-webhook-shape-console.md`), since the stored type names change together with Task 8: deploy both or neither.
- Manual, on 8089 after the deploy with the user watching: a text, a photo and a voice note from a phone the user names appear in the inbox as before; `db.channelWebhookEvents.find().sort({_id:-1}).limit(3)` shows the events; `db.waWebMessages.distinct("type")` shows no `chat`, `ptt`, `vcard`, `multi_vcard`, `unknown`.
