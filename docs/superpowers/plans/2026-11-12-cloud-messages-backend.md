# Cloud Messages (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send WhatsApp Cloud API `text` and `interactive` messages through the WhatsApp Web bridge, delivering interactive ones as a link to a tracked, server-rendered page whose preview image exists before the send.

**Architecture:** A snake_case `CloudMessage` record family in `omnichannel-common` is the one message shape; a validator applies Meta's limits. `InboxSendService` gains a Cloud entry point: text goes out as today, interactive creates a `messagePages` row and its og.png, then sends only the link. A public `@Controller` serves the page, the preview, the media and the action beacons; listeners match replies, push live updates and write CRM activities.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson`), Spring Data MongoDB + GridFS, JMustache 1.16 (already a dependency), Java2D/ImageIO (JDK), JUnit 5 + MockMvc/TestRestTemplate + `FakeDevice` (existing).

**Spec:** `docs/superpowers/specs/2026-11-12-cloud-messages-design.md`

## Global Constraints

- Work in `.worktrees/cloud-messages`; never on `main`. One Maven build at a time in this worktree.
- Test first; while fixing run only the tests that matter; full `./mvnw -q verify` once before the merge.
- Nothing new is named "template"; no change to `messageTemplates`, `TemplateType`, `TemplateValidator`, `TemplateRenderer` or the template controllers. The only borrowed template code is `MediaSniffer` (public) and the MIME lists copied from `TemplateMediaTypes` (package-private, so copy, do not widen).
- Mongo collections and top-level fields camelCase (`messagePages`, `cloudMedia`, `messagePagePreviews`); the Cloud JSON inside a stored `Map<String,Object>` keeps Meta's snake_case keys.
- `messaging_product` is `whatsapp-web` on the bridge; `whatsapp` is rejected with 400 until a Cloud number exists.
- The page makes no external request; all CSS and JS inline; no contact name, no number label, no workspace internals on the page or the card.
- og.png is drawn before dispatch; a drawing failure fails the send (500, "preview image could not be generated").
- Public endpoints never return the JSON advice; they return their own HTML (page, 404, 410) or 204.
- New config under `omnichannel.pages`: `base-url` (default `http://localhost:8080`, env `OMNICHANNEL_PAGES_BASE_URL`), `ttl-days` (90), `reply-window` (48h).
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A body with `<script>` or `"` in a button title must render escaped on the page and in OG attributes (Task 4 test `escapesHtmlEverywhere`).
2. Two opens from the same phone must not double `openedAt`; 51 beacons must leave 50 actions (Task 6 tests `openSetsOpenedAtOnce`, `capsActionsAtFifty`).
3. A beacon with a reply id that is not in the message must be ignored silently with 204 (Task 6 `ignoresUnknownReplyId`).
4. An inbound text equal to a button title but sent before the page existed must not be matched (Task 8 `ignoresRepliesOlderThanThePage`).
5. A send whose device refuses must leave no `WaWebMessage` but may leave the page row; the page must still answer 404/410 rules and never 500 (Task 7 `refusedSendLeavesNoMessage`, Task 6 `pageWithoutMessageStillRenders`).

---

### Task 1: Cloud message model

**Files:**
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/whatsapp/message/CloudMessage.java`, `Text.java`, `Interactive.java`, `MessagingProduct.java`, `CloudSendResponse.java`, `CloudMediaUploadResponse.java`, `CloudMediaInfo.java`, `CloudMessages.java`
- Create: `omnichannel-common/src/test/java/me/nageshk/omnichannel/common/channel/whatsapp/message/CloudMessageJsonTest.java`
- Create fixtures: `omnichannel-common/src/test/resources/cloud/text.json`, `button.json`, `list.json`, `cta_url.json`, `location_request.json` (Meta's documented examples from the spec §3, each with `"messaging_product": "whatsapp-web"`, `"recipient_type": "individual"`, `"to": "919900000001"`)

**Interfaces:**
- Produces, all records annotated like `webhook/Interactive.java` (`@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`, `@JsonInclude(NON_NULL)`) but **without** `@JsonIgnoreProperties(ignoreUnknown = true)`:
  - `public record CloudMessage(String messagingProduct, String recipientType, String to, String type, Text text, Interactive interactive, Context context)` with nested `public record Context(String messageId)`.
  - `public record Text(Boolean previewUrl, String body)`.
  - `public record Interactive(String type, Header header, Body body, Footer footer, Action action)` with nested `Header(String type, String text, MediaRef image, MediaRef video, MediaRef document)`, `MediaRef(String id, String link)`, `Body(String text)`, `Footer(String text)`, `Action(List<ActionButton> buttons, String button, List<Section> sections, String name, CtaParameters parameters)`, `ActionButton(String type, Reply reply)`, `Reply(String id, String title)`, `Section(String title, List<Row> rows)`, `Row(String id, String title, String description)`, `CtaParameters(String displayText, String url)`.
  - `public final class MessagingProduct { public static final String WHATSAPP = "whatsapp"; public static final String WHATSAPP_WEB = "whatsapp-web"; }`
  - `public record CloudSendResponse(String messagingProduct, List<Contact> contacts, List<Message> messages)` with `Contact(String input, String waId)`, `Message(String id)`.
  - `public record CloudMediaUploadResponse(String id)`; `public record CloudMediaInfo(String messagingProduct, String url, String mimeType, String sha256, long fileSize, String id)`.
  - `public final class CloudMessages` with `public CloudMessages(ObjectMapper mapper)` (a `@Component`), `public Map<String,Object> toMap(CloudMessage m)`, `public CloudMessage fromMap(Map<String,Object> m)` and `public CloudMessage parse(JsonNode n)`; both parse paths use `mapper.readerFor(CloudMessage.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` so an unknown key throws `ValidationException("message", "unknown field " + name)` (catch `UnrecognizedPropertyException`, use its `getPropertyName()`). Nested `public static final class Limits` with `int BODY_INTERACTIVE = 1024, BODY_LIST = 4096, BODY_TEXT = 4096, FOOTER = 60, HEADER_TEXT = 60, BUTTONS = 3, BUTTON_TITLE = 20, BUTTON_ID = 256, LIST_BUTTON = 20, SECTIONS = 10, ROWS = 10, SECTION_TITLE = 24, ROW_ID = 200, ROW_TITLE = 24, ROW_DESCRIPTION = 72, CTA_TEXT = 20, URL = 2000`.
  - Package-visible `static final Set<String> INTERACTIVE_TYPES = Set.of("button","list","cta_url","location_request_message")` on `Interactive`.

- [ ] **Step 1: Write the failing test**

```java
class CloudMessageJsonTest {
    private final ObjectMapper mapper = new ObjectMapper(); // tools.jackson, defaults
    private final CloudMessages cloud = new CloudMessages(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"text", "button", "list", "cta_url", "location_request"})
    void roundTripsMetaExamplesByteEqual(String name) throws Exception {
        JsonNode original = mapper.readTree(getClass().getResourceAsStream("/cloud/" + name + ".json"));
        CloudMessage parsed = cloud.parse(original);
        assertThat(mapper.valueToTree(cloud.toMap(parsed))).isEqualTo(original);
    }

    @Test
    void unknownKeyIsRejected() {
        Map<String, Object> m = Map.of("messaging_product", "whatsapp-web", "type", "text",
                "text", Map.of("body", "hi"), "surprise", 1);
        assertThatThrownBy(() -> cloud.fromMap(m)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsEntry("message", "unknown field surprise"));
    }

    @Test
    void nullsAreOmitted() {
        CloudMessage m = new CloudMessage("whatsapp-web", null, "1", "text", new Text(null, "hi"), null, null);
        assertThat(cloud.toMap(m)).containsOnlyKeys("messaging_product", "to", "type", "text");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl omnichannel-common test -Dtest=CloudMessageJsonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (classes missing).

- [ ] **Step 3: Create the records and `CloudMessages` in `common/channel/whatsapp/message/`**

`toMap` is `mapper.convertValue(m, new TypeReference<Map<String,Object>>(){})`; `fromMap` is `parse(mapper.valueToTree(m))`. Keep JSON key order as Meta prints it by declaring record components in the spec's order.

- [ ] **Step 4: Run test to verify it passes**

Run: same command. Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add omnichannel-common/src
git commit -m "feat(cloud): Cloud API message records with strict parsing"
```

---

### Task 2: Cloud message validator

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/cloud/CloudMessageValidator.java`, `CloudMediaTypes.java`
- Create: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/cloud/CloudMessageValidatorTest.java`

**Interfaces:**
- Consumes: Task 1 records and `CloudMessages.Limits`; `Phones.normalise/isValid` (`service/contact/Phones.java`); `ValidationException(Map<String,String>)`.
- Produces:
  - `public final class CloudMediaTypes` with `public static final Set<String> IMAGE = Set.of("image/jpeg","image/png")`, `VIDEO = Set.of("video/mp4","video/3gpp")`, `DOCUMENT` = the eleven document types from `TemplateMediaTypes`; `public static Set<String> forHeader(String headerType)` (`image`→IMAGE, `video`→VIDEO, `document`→DOCUMENT, else empty); `public static long maxBytes(String headerType)` (image 5 MB, video 16 MB, document 16 MB).
  - `@Component public class CloudMessageValidator` with `public interface MediaLookup { Optional<String> mimeOf(String workspaceId, String mediaId); }` and `public CloudMessage validate(String workspaceId, CloudMessage m, MediaLookup media, boolean toIsPhone)`: returns `m` with `to` normalised, or throws `ValidationException` whose keys are JSON paths (`to`, `messaging_product`, `type`, `text.body`, `interactive.type`, `interactive.header.type`, `interactive.header.text`, `interactive.header.image.id`, `interactive.body.text`, `interactive.footer.text`, `interactive.action.buttons`, `interactive.action.buttons.1.reply.title`, `interactive.action.button`, `interactive.action.sections`, `interactive.action.sections.0.rows.2.title`, `interactive.action.parameters.display_text`, `interactive.action.parameters.url`). All problems collected into one exception.
  - `public static List<Interactive.Reply> replies(Interactive i)`: the reply buttons (type `button`) or every row as `Reply(id, title)` (type `list`), else empty. Used by Tasks 6 and 8 for id checks and title matching.

Rules (each one a test):
- `messaging_product` ∈ {`whatsapp`, `whatsapp-web`} → else `messaging_product: "must be whatsapp or whatsapp-web"`.
- `recipient_type` null or `individual`.
- `to`: required non-blank; when `toIsPhone`, `Phones.normalise` then `Phones.isValid` → else `to: Phones.MESSAGE`.
- `type` ∈ {`text`, `interactive`}; `text` present iff type text, `interactive` present iff type interactive (`"is required"` / `"must be absent"`).
- `text.body` 1..4096.
- `interactive.type` ∈ `Interactive.INTERACTIVE_TYPES`.
- header: `type` ∈ {text,image,video,document}; for `text` a non-blank `text` ≤60 and no media; for a media type exactly that media field present with a non-blank `id` and `link` null (`interactive.header.image.link: "is not supported; upload the file and use its id"`); `media.mimeOf(ws,id)` must be present (`"unknown media"`) and in `CloudMediaTypes.forHeader(type)` (`"must be a JPEG or PNG"` etc.). `list` and `location_request_message` allow no header except `text` for list.
- `body.text` required 1..1024 (list: 4096); footer ≤60 when present; `location_request_message` allows no header, footer or action other than `name: send_location`.
- `button`: 1..3 buttons, every `type == "reply"`, `reply.id` 1..256, `reply.title` 1..20, ids distinct, titles distinct ignoring case (`"must be unique"`).
- `list`: `action.button` 1..20; 1..10 sections, each `title` 1..24 and 1..N rows; ≤10 rows in total; `row.id` 1..200, `row.title` 1..24, `description` ≤72; ids distinct and titles distinct ignoring case across all sections.
- `cta_url`: `action.name == "cta_url"`, `parameters.display_text` 1..20, `parameters.url` matches `^https://\S{1,1990}$` and ≤2000.

- [ ] **Step 1: Write the failing tests** — one `@Test` per rule above (about 25), each building the message with Task 1 records and asserting the exact key and message, e.g.

```java
@Test
void rejectsDuplicateButtonTitlesIgnoringCase() {
    CloudMessage m = button(reply("a", "Yes"), reply("b", "yes"));
    assertThat(fieldsOf(m)).containsEntry("interactive.action.buttons.1.reply.title", "must be unique");
}
@Test
void normalisesTo() {
    assertThat(validator.validate("w1", text("+91 99000-00001", "hi"), (w, id) -> Optional.empty(), true).to()).isEqualTo("919900000001");
}
@Test
void repliesOfListAreItsRows() {
    assertThat(CloudMessageValidator.replies(list(section("S", row("r1", "One", null), row("r2", "Two", null))).interactive()))
            .extracting(Interactive.Reply::title).containsExactly("One", "Two");
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl omnichannel-service -am test -Dtest=CloudMessageValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement `CloudMediaTypes` and `CloudMessageValidator`** (pure Java, `LinkedHashMap<String,String> errors`, throw once at the end when non-empty).

- [ ] **Step 4: Run to verify pass.** Expected: all green.

- [ ] **Step 5: Commit** `feat(cloud): validate Cloud messages against Meta's limits`

---

### Task 3: Cloud media upload

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/cloud/CloudMediaConfig.java` (bucket `cloudMedia`, bean `cloudMediaGridFs`, copy of `TemplateMediaConfig`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/cloud/CloudMediaService.java`
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/cloud/CloudMediaController.java`
- Modify: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/support/TestUsers.java:130-131` (add `"cloudMedia.files", "cloudMedia.chunks", "messagePages", "messagePagePreviews.files", "messagePagePreviews.chunks"` to the cleaned collections)
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/cloud/CloudMediaIT.java` (extends `InboxApiTestBase`)

**Interfaces:**
- Consumes: `MediaSniffer.detect(byte[] head, String fileName)` / `MediaSniffer.HEAD` (public, `service/template`); `CloudMediaTypes` (Task 2); `InboxDevices.requireReady(ws, phoneNumberId)` is **not** needed for upload (a number only has to exist in the workspace: `PhoneNumberRepository.findByIdAndWorkspaceId`).
- Produces `@Service CloudMediaService`:
  - `public record Stored(String id, String mimeType, long size, String sha256)`
  - `public Stored upload(AuthenticatedUser me, String workspaceId, String headerType, MultipartFile file)`: `headerType` is the multipart `type` field (`image|video|document`); size ≤ `CloudMediaTypes.maxBytes(headerType)` else `ValidationException("file", "is larger than N MB")`; MIME via `MediaSniffer.detect` else `("file","is not a supported file type")`, must be in `forHeader(headerType)` else `("file","must be ...")`; GridFS metadata `{workspaceId, uploadedBy, mimeType, size, sha256, uploadedAt}`.
  - `public Optional<String> mimeOf(String workspaceId, String mediaId)` (satisfies `CloudMessageValidator.MediaLookup`).
  - `public record MediaFile(String mimeType, long size, GridFsResource resource)`; `public MediaFile open(String workspaceId, String mediaId)` → `NotFoundException("media not found")`.
  - `public CloudMediaInfo info(String workspaceId, String mediaId, String url)`.
  - `public int sweepOrphans(Instant cutoff)`: deletes files with `metadata.uploadedAt < cutoff` whose id appears in no `messagePages` document at any of `message.interactive.header.image.id`, `...video.id`, `...document.id` (one `Query` with `Criteria().orOperator(...)`, collection `messagePages`, via `MongoTemplate.exists`).
- Produces `CloudMediaController` (`@RestController @RequestMapping("/api/workspaces/{workspaceId}/channel-accounts/{phoneNumberId}")`):
  - `POST /media` (`@PreAuthorize("@access.has(#workspaceId, 'inbox:send')")`, `@RequestPart("file") MultipartFile file, @RequestParam("messaging_product") String product, @RequestParam("type") String type`) → 200 `CloudMediaUploadResponse(id)`; `product` must be `whatsapp-web` (`ValidationException("messaging_product", "must be whatsapp-web")`); unknown number in the workspace → 404.
  - `GET /media/{mediaId}` (`inbox:view`) → `CloudMediaInfo` with `url` = `{pages.baseUrl}/api/workspaces/{ws}/channel-accounts/{n}/media/{id}/file`.
  - `GET /media/{mediaId}/file` (`inbox:view`) → the bytes, inline, `X-Content-Type-Options: nosniff` (reuse the header-building style of `InboxController.download`, without a filename).

- [ ] **Step 1: Write the failing IT**

```java
class CloudMediaIT extends InboxApiTestBase {
    @Autowired @Qualifier("cloudMediaGridFs") GridFsTemplate files;
    @Autowired CloudMediaService media;

    private ResultActions upload(String token, String type, String name, String mime, byte[] bytes) throws Exception {
        return mvc.perform(multipart(inboxBase() + "/channel-accounts/" + number.getId() + "/media")
                .file(new MockMultipartFile("file", name, mime, bytes))
                .param("messaging_product", "whatsapp-web").param("type", type)
                .header("Authorization", "Bearer " + token));
    }
    // PNG_HEAD = {(byte)0x89,'P','N','G',0x0D,0x0A,0x1A,0x0A} + 100 zero bytes

    @Test void uploadsAnImageAndAnswersWithItsId() // 200, body.id is a 24-hex ObjectId, GridFS metadata.workspaceId == brand.getId(), metadata.mimeType == "image/png"
    @Test void rejectsWhatsappProduct()            // messaging_product=whatsapp → 400, fields.messaging_product
    @Test void rejectsWrongTypeForHeader()         // a PNG with type=video → 400, fields.file contains "MP4"
    @Test void rejectsOversizedImage()             // 5 MB + 1 byte PNG → 400 fields.file "is larger than 5 MB"
    @Test void infoAndFileAreBrandScoped()         // GET info 200 with mime_type and file_size; GET /file 200 image/png; other brand's token → 404 on both
    @Test void sweepKeepsReferencedFiles()         // insert a messagePages Document referencing the id under message.interactive.header.image.id; sweepOrphans(now+1h) deletes an unreferenced upload, keeps the referenced one
}
```
`inboxBase()` = `"/api/workspaces/" + brand.getId()`.

- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl omnichannel-app -am test -Dtest=CloudMediaIT -Dsurefire.failIfNoSpecifiedTests=false` → compilation failure.

- [ ] **Step 3: Implement** `CloudMediaConfig`, `CloudMediaService` (sha256 with `MessageDigest` while streaming the second read, as `TemplateMediaService.store` reads twice), `CloudMediaController`; add the collections to `TestUsers.deleteAll`.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(cloud): Cloud-style media upload into the cloudMedia bucket`

---

### Task 4: Page renderer

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/WhatsAppMarkup.java`, `MessagePageRenderer.java`, `PageModel.java`
- Create resources: `omnichannel-service/src/main/resources/pages/page.mustache`, `notfound.mustache`, `expired.mustache`, `page.css`, `page.js`
- Create: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/page/WhatsAppMarkupTest.java`, `MessagePageRendererTest.java`

**Interfaces:**
- Consumes: Task 1 records; `CloudMessageValidator.replies` (Task 2).
- Produces:
  - `public final class WhatsAppMarkup { public static String toHtml(String text); public static String plain(String text); }` — `toHtml` HTML-escapes (`& < > " '`) then applies, in this order and without nesting, ```` ```mono``` ```` → `<code>`, `*bold*` → `<strong>`, `_italic_` → `<em>`, `~strike~` → `<s>` using the console's regexes (`placeholders.ts:79-87`, ported as four alternatives of one pattern), then `\n` → `<br>`; `plain` strips the four marks for og:description and the card.
  - `public record PageModel(CloudMessage message, String token, String baseUrl, String senderDigits, boolean preview)`; `public record Rendered(String html, String ogTitle, String ogDescription)`.
  - `@Component public class MessagePageRenderer` with `public Rendered render(PageModel m)`, `public String notFound()`, `public String expired()`. Uses `Mustache.compiler().escapeHTML(true)`; CSS and JS read once from the classpath in the constructor and inlined. Template variables: `ogTitle`, `ogDescription`, `ogImage` (`{baseUrl}/t/{token}/og.png`), `ogUrl`, `headerText`, `headerMediaUrl` (`{baseUrl}/t/{token}/m`), `headerKind` (`text|image|video|document|none`), `bodyHtml` (triple-mustache, output of `WhatsAppMarkup.toHtml`), `footer`, `kind` (`button|list|cta_url|location_request_message`), `buttons[{id,title,href}]` with `href = https://wa.me/{senderDigits}?text={urlencoded title}` or `#` when preview, `listButton`, `sections[{title, rows[{id,title,description,href}]}]`, `cta{text, href}` with `href = {baseUrl}/t/{token}/a` or `#`, `locationHref` (wa.me with `Here is my location:`), `beacon` (`{baseUrl}/t/{token}/e`, empty string in preview), `senderDigits`.
  - `page.js` (inline, no dependencies, ES2017): on load `POST beacon {k:"open"}` with `keepalive` unless `beacon` is empty; `[data-k="reply"]` click → beacon `{k:"reply", id}` then let navigation proceed; `[data-k="cta"]` → no beacon (the redirect records); `[data-k="location"]` → `navigator.geolocation.getCurrentPosition` → beacon `{k:"location", lat, lng}` and swap the button text for "Location shared", on error/denial `location.href = locationHref`; list button opens the sheet (`hidden` attribute toggle). In preview mode every handler is a no-op.
  - OG rules: `ogTitle` = header text, else `plain(body)` cut at 60 chars; `ogDescription` = `plain(body)` cut at 200.
  - Markup: `<!doctype html>`, `<meta name="viewport" content="width=device-width, initial-scale=1">`, `<meta name="robots" content="noindex">`, `og:title`, `og:description`, `og:image`, `og:url`, `og:type` `website`; body max width 420px centered, WhatsApp green header strip (`#075e54`) showing `+{senderDigits}`, canvas `#efeae2`, incoming-style bubble `#ffffff`, buttons as full-width white rows with `#00a5f4` text, list sheet as a fixed bottom panel.

- [ ] **Step 1: Write the failing tests**

```java
class WhatsAppMarkupTest {
    @Test void boldItalicStrikeMono()  { assertThat(WhatsAppMarkup.toHtml("*a* _b_ ~c~ ```d```")).isEqualTo("<strong>a</strong> <em>b</em> <s>c</s> <code>d</code>"); }
    @Test void escapesBeforeFormatting() { assertThat(WhatsAppMarkup.toHtml("<script>*x*</script>")).isEqualTo("&lt;script&gt;<strong>x</strong>&lt;/script&gt;"); }
    @Test void noNesting()              { assertThat(WhatsAppMarkup.toHtml("*_a_*")).isEqualTo("<strong>_a_</strong>"); }
    @Test void newlines()               { assertThat(WhatsAppMarkup.toHtml("a\nb")).isEqualTo("a<br>b"); }
    @Test void plainStripsMarks()       { assertThat(WhatsAppMarkup.plain("*a* _b_")).isEqualTo("a b"); }
}

class MessagePageRendererTest {
    MessagePageRenderer r = new MessagePageRenderer();
    PageModel model(CloudMessage m) { return new PageModel(m, "tok", "https://pages.test", "919900000001", false); }

    @Test void buttonPageHasWaMeLinksAndBeacon()   // contains href="https://wa.me/919900000001?text=Yes%2C+book" for a title "Yes, book", data-k="reply", "https://pages.test/t/tok/e"
    @Test void listPageHasSheetWithRows()          // every row title present, data-id for each row
    @Test void ctaPageLinksToRedirect()            // href="https://pages.test/t/tok/a"
    @Test void locationPageHasFallback()           // contains "Here+is+my+location" and data-k="location"
    @Test void escapesHtmlEverywhere()             // body "<b>x</b>", title "\"q\"", header text "<i>" → no raw "<b>", attribute shows &quot;q&quot;
    @Test void ogTagsAreAbsolute()                 // og:image == https://pages.test/t/tok/og.png, og:url == https://pages.test/t/tok, title cut at 60, description cut at 200
    @Test void noExternalReferences()              // html contains no "src=\"http" / "href=\"http" other than wa.me and pages.test, and contains "<style>" and "<script>"
    @Test void previewHasNoBeaconAndInertLinks()   // preview=true → no "/t/tok/e", every data-k href is "#"
    @Test void expiredAndNotFoundShowNoMessage()   // expired() and notFound() contain no "wa.me" and a fixed sentence each
}
```

- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl omnichannel-service -am test -Dtest='WhatsAppMarkupTest,MessagePageRendererTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** `WhatsAppMarkup`, `PageModel`, `MessagePageRenderer`, the three mustache files, `page.css`, `page.js`.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(pages): render a Cloud interactive message as an inline-only HTML page`

---

### Task 5: Preview card image

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/PreviewCardRenderer.java`
- Create resources: `omnichannel-service/src/main/resources/pages/fonts/NotoSans-Regular.ttf` and `pages/fonts/OFL.txt` (from github.com/google/fonts, `ofl/notosans`; the licence file travels with the font)
- Create: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/page/PreviewCardRendererTest.java`

**Interfaces:**
- Produces `@Component public class PreviewCardRenderer` with `public byte[] draw(CloudMessage message, Optional<byte[]> headerImage)`: a 1200×630 PNG. Layout: when `headerImage` is present and decodes, it fills the left 480 px (cover-scaled, centre-cropped), else a `#075e54` panel with a white speech-bubble glyph; right side white with title (header text or first line of `WhatsAppMarkup.plain(body)`, 44 px bold, 2 lines max, ellipsised) and body (28 px, wrapped with `LineBreakMeasurer`, max 6 lines) and the reply titles / list button / CTA text / "Send location" as rounded pills along the bottom (26 px, `#00a5f4` border). Font from the bundled TTF via `Font.createFont`, falling back to `Font.SANS_SERIF` if loading fails (log once). Any exception → `IllegalStateException("preview image could not be generated", e)`.

- [ ] **Step 1: Write the failing test**

```java
class PreviewCardRendererTest {
    PreviewCardRenderer r = new PreviewCardRenderer();
    @ParameterizedTest @ValueSource(strings = {"button","list","cta_url","location_request"})
    void drawsA1200x630PngForEachKind(String fixture) throws Exception {
        byte[] png = r.draw(fixtureMessage(fixture), Optional.empty());   // read /cloud/<fixture>.json via CloudMessages
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(1200); assertThat(img.getHeight()).isEqualTo(630);
    }
    @Test void usesTheHeaderImageWhenGiven()  // a 10x10 red PNG → pixel at (100, 315) is red-ish (r > 200, g < 80)
    @Test void greenPanelWithoutHeaderImage() // pixel at (100, 315) ≈ #075e54
    @Test void corruptHeaderImageFallsBackToPanel()
    @Test void veryLongBodyStillFits()        // 1024 'a' characters → draws without exception
}
```
Copy the `/cloud/*.json` fixtures from Task 1 into `omnichannel-service/src/test/resources/cloud/` (test resources are not shared between modules).

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement** with `BufferedImage.TYPE_INT_RGB`, `Graphics2D` antialiasing on, `ImageIO.write(img, "png", out)`.

- [ ] **Step 4: Run to verify pass.** Also run once with `-Djava.awt.headless=true` explicitly to prove the server mode works.

- [ ] **Step 5: Commit** `feat(pages): draw the link-preview card with Java2D`

---

### Task 6: Message pages store, service and public controller

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/page/MessagePage.java`, `MessagePageRepository.java`, `MessagePagePreviewConfig.java` (bucket `messagePagePreviews`, bean `messagePagePreviewsGridFs`)
- Modify: `omnichannel-common/.../common/config/OmnichannelProperties.java:9-11` (add `@DefaultValue Pages pages`) and define `public record Pages(@DefaultValue("http://localhost:8080") String baseUrl, @DefaultValue("90") int ttlDays, @DefaultValue("48h") Duration replyWindow)`
- Modify: `omnichannel-app/src/main/resources/application.yml` (add `pages: { base-url: ${OMNICHANNEL_PAGES_BASE_URL:http://localhost:8080}, ttl-days: 90, reply-window: 48h }`), `omnichannel-app/src/test/resources/application-test.yml` (`pages: { base-url: http://pages.test }`)
- Modify: `omnichannel-db/.../db/inbox/WaWebMessage.java:56` (add `private String messagePageId;` and `private Map<String, Object> request;`)
- Modify: `omnichannel-service/.../service/inbox/MessageView.java` (add `String messagePageId, MessagePageView messagePage` after `adClick`), `InboxViews.java:52` (`message()` fills them)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/MessagePageService.java`, `MessagePageView.java`, `MessagePageEvents.java`
- Modify: `omnichannel-service/.../service/inbox/InboxEvents.java` (add `public record MessagePageChanged(WaWebChat chat, WaWebMessage message) {}`), `InboxPushListener.java` (add `on(MessagePageChanged)` → `broker.pushMessage(ws, chat, views.message(event.message()))`, same guard and try/catch as `on(MessageUpserted)`)
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/page/MessagePageController.java`
- Modify: `omnichannel-app/.../app/web/SpaForwardController.java:15` → `FIRST = "{first:(?!api$|ws$|t$)[^.]*}"` and its javadoc
- Modify: `omnichannel-security/.../security/config/SecurityConfig.java:75-78` (add constant `PAGE_EVENT_PATH = "/t/*/e"` and `.requestMatchers(HttpMethod.POST, PAGE_EVENT_PATH).permitAll()` before the `/api/**` line)
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/page/MessagePageIT.java` (extends `InboxApiTestBase`); Modify: `app/web/SpaForwardIT.java` (add `pagePathIsNotForwarded`)

**Interfaces:**
- Consumes: Tasks 1, 2 (`replies`), 3 (`CloudMediaService.open`), 4, 5; `InboxDevices.digitsOf`; `Clock` bean; `InboxStreamBroker.pushMessage`.
- Produces:
  - `@Document("messagePages") @Getter @Setter public class MessagePage` with `@Id String id; String workspaceId; String customerId; @Indexed(unique = true) String token; String chatId; String contactId; String phoneNumberId; String senderDigits; @Indexed(sparse = true) String messageId; Map<String,Object> message; Instant createdAt; Instant expiresAt; Instant openedAt; int openCount; Instant lastActionAt; List<Action> actions = new ArrayList<>(); boolean previewReady; String previewFileId;` plus `@CompoundIndex(name = "chat_created", def = "{'chatId': 1, 'createdAt': -1}")`; nested `public enum ActionKind { OPEN, BUTTON_REPLY, LIST_REPLY, CTA_URL, LOCATION }` and `public record Action(ActionKind kind, String id, String title, Double latitude, Double longitude, Instant at, String uaHash, String repliedMessageId)`.
  - `MessagePageRepository extends MongoRepository<MessagePage,String>` with `Optional<MessagePage> findByToken(String token)`, `List<MessagePage> findByChatIdAndCreatedAtAfterOrderByCreatedAtDesc(String chatId, Instant after)`.
  - `public record MessagePageView(String url, Instant expiresAt, Instant openedAt, int openCount, List<Count> buttons, List<Count> rows, Count cta, Location location)` with `Count(String id, String title, int count, boolean replied)` and `Location(double latitude, double longitude, Instant at)`.
  - `public final class MessagePageEvents { public record Activity(MessagePage page, MessagePage.Action action, boolean firstOfItsKind) {} }` — `firstOfItsKind` is true for the first OPEN and for the first action with that `(kind, id)`; Task 9 keys on it.
  - `@Service public class MessagePageService` (constructor-injected `MessagePageRepository pages, @Qualifier("messagePagePreviewsGridFs") GridFsTemplate previews, CloudMediaService media, MessagePageRenderer renderer, PreviewCardRenderer cards, CloudMessages cloud, InboxDevices devices, WaWebMessageRepository messages, WaWebChatRepository chats, OmnichannelProperties props, ApplicationEventPublisher publisher, Clock clock`):
    - `public MessagePage create(WaWebChat chat, CloudMessage message)`: token = Base64 url-safe no-padding of 16 `SecureRandom` bytes; `senderDigits = devices.digitsOf(chat.getPhoneNumberId())`; `expiresAt = now + ttlDays`; `message = cloud.toMap(message)`; saves with `previewReady=false`.
    - `public MessagePage drawPreview(MessagePage page)`: header image bytes via `media.open` when the header is an image (else empty), `cards.draw(...)`, stores PNG in `previews` with metadata `{pageId, createdAt}`, sets `previewReady=true, previewFileId`, saves. On any exception deletes the page row and rethrows `IllegalStateException`.
    - `public String link(MessagePage page)` → `baseUrl + "/t/" + token`.
    - `public void attachMessage(String pageId, String messageId)`.
    - `public Optional<MessagePage> byToken(String token)`; `public boolean expired(MessagePage p)`.
    - `public String html(MessagePage p)` → `renderer.render(new PageModel(cloud.fromMap(p.getMessage()), p.getToken(), baseUrl, p.getSenderDigits(), false)).html()`; `public String previewHtml(CloudMessage m, String senderDigits)` (preview=true, token `preview`).
    - `public Optional<GridFsResource> preview(MessagePage p)`; `public Optional<CloudMediaService.MediaFile> headerMedia(MessagePage p)`.
    - `public void open(String token, String userAgent)`; `public void record(String token, String kind, String id, Double lat, Double lng, String userAgent)`; `public Optional<String> ctaUrl(String token)` (records CTA_URL, returns the url; empty when not a cta page). All three: unknown or expired token → silent no-op; 50-action cap; `uaHash` = first 12 hex of SHA-256(UA or ""); `id` must be in `replies(...)` for `reply`, kind is BUTTON_REPLY for a `button` page and LIST_REPLY for a `list` page; `location` only on a `location_request_message` page with `-90..90` / `-180..180`. After a stored change: save, then `publisher.publishEvent(new MessagePageEvents.Activity(page, action, first))` and, when `messageId != null`, `publisher.publishEvent(new InboxEvents.MessagePageChanged(chat, message))` (chat and message re-read by id).
    - `public MessagePageView view(MessagePage p)`: counts per reply id over `actions`, `replied` = any action with that id has `repliedMessageId`; `cta.count`; `location` = the latest LOCATION action.
    - `public static String newToken()` (package-visible for tests).
  - `InboxViews.message(WaWebMessage m)`: when `m.getMessagePageId() != null`, `pages.findById(...)` and set `messagePage = service.view(page)` (inject `MessagePageService`; a missing page → null view).
  - `@Controller public class MessagePageController` (constructor-injected `MessagePageService pages`):
    - `@GetMapping(value = "/t/{token}", produces = "text/html;charset=UTF-8") ResponseEntity<String> page(@PathVariable String token)`: 404 `notFound()`, 410 `expired()`, 200 `html()`; headers `Cache-Control: no-store`, `X-Robots-Tag: noindex`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` on all three.
    - `@GetMapping("/t/{token}/og.png") ResponseEntity<Resource>`: 404 when no preview, else `image/png`, `Cache-Control: public, max-age=86400`; served even after expiry (the preview is already in the message).
    - `@GetMapping("/t/{token}/m") ResponseEntity<Resource>`: header media inline, `Accept-Ranges: bytes` (Spring's `ResourceRegion` handling via returning the `Resource` lets the framework answer Range requests), 404 when none or expired.
    - `@PostMapping(value = "/t/{token}/e", consumes = "application/json") ResponseEntity<Void> event(@PathVariable String token, @RequestBody Beacon b, @RequestHeader(value = "User-Agent", required = false) String ua)` with `record Beacon(String k, String id, Double lat, Double lng)` → always 204; `k` ∈ {open, reply, location} dispatches to `open`/`record`; anything else ignored.
    - `@GetMapping("/t/{token}/a") ResponseEntity<Void> cta(...)`: 302 `Location: url`, 404 unknown or not a cta page, 410 expired.
    - A `@ExceptionHandler(Exception.class)` inside this controller returning 404 HTML, so no JSON advice ever leaks for `/t/**`.

- [ ] **Step 1: Write the failing ITs**

```java
class MessagePageIT extends InboxApiTestBase {
    @Autowired MessagePageService pages; @Autowired MessagePageRepository repo; @Autowired CloudMessages cloud;
    MessagePage seeded(String fixture) { /* chat(...) from the base, cloud.parse(fixture json), pages.create(chat, msg), pages.drawPreview(page); set messageId to a stored OUT message with messagePageId */ }

    @Test void servesTheHtmlPageWithoutAuth()      // GET /t/{token} 200 text/html, contains wa.me link, headers no-store/noindex/no-referrer
    @Test void unknownTokenIs404Html()             // GET /t/nope 404, content-type text/html, body contains no "wa.me"
    @Test void expiredIs410WithoutMessageText()    // set expiresAt past → 410, body lacks the button title
    @Test void ogPngExistsRightAfterCreate()       // GET /t/{token}/og.png 200 image/png, max-age=86400
    @Test void headerMediaStreams()                // button page with image header → GET /t/{token}/m 200 image/png
    @Test void openSetsOpenedAtOnce()              // POST /e {k:open} twice → 204 both, openedAt set once, openCount 2
    @Test void replyRecordsAndIsVisibleInTheView() // POST /e {k:reply,id:"change-button"} → actions has BUTTON_REPLY "Change"; GET inbox thread shows message.messagePage.buttons[0].count == 1
    @Test void ignoresUnknownReplyId()             // {k:reply,id:"zzz"} → 204, no action
    @Test void capsActionsAtFifty()                // 51 reply beacons → 50 actions
    @Test void locationOnlyOnLocationPages()       // {k:location,lat,lng} on a button page → ignored; on a location page → LOCATION action, view.location set; lat 95 → ignored
    @Test void ctaRedirectsAndRecords()            // GET /t/{token}/a → 302 Location == url, one CTA_URL action; on a button page → 404; expired → 410
    @Test void beaconOnExpiredIsSilent()           // 204, nothing stored
    @Test void pageWithoutMessageStillRenders()    // page with messageId null → GET 200, beacon 204 and no exception
    @Test void previewFailureDeletesThePage()      // header image id pointing at a corrupt file → drawPreview throws IllegalStateException and repo has no row
    @Test void liveEventPushesTheMessage()         // subscribe to the inbox stream as the existing stream IT does (`grep -rl "/inbox/stream" omnichannel-app/src/test`), POST /e open → a `message` event arrives with messagePage.openCount == 1
}
// SpaForwardIT
@Test void pagePathIsNotForwarded() { mvc.perform(get("/t/abc")).andExpect(forwardedUrl(null)); }
```

- [ ] **Step 2: Run to verify failure.** `-Dtest='MessagePageIT,SpaForwardIT'`

- [ ] **Step 3: Implement** everything in the Files list. Order: db classes and properties → service → views/events/push → controller → SPA and security changes.

- [ ] **Step 4: Run to verify pass.** Then run `InboxStreamIT` and `InboxThreadIT` (or whatever reads `MessageView`, find with `grep -rl "templateId" omnichannel-app/src/test`) to prove the added `MessageView` fields broke no existing assertion.

- [ ] **Step 5: Commit** `feat(pages): message pages with preview, beacons, redirect and live view`

---

### Task 7: Sending a Cloud message

**Files:**
- Modify: `omnichannel-service/.../service/inbox/InboxSendService.java` (`Outgoing` +2 fields; new `sendCloud`; `send()` split into `dispatch`; `record()` stores the two new fields; `outgoing()` text branch builds a `CloudMessage`)
- Modify: `omnichannel-service/.../service/inbox/NewChatService.java` (extract chat resolution; add `startCloud`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/cloud/CloudSendService.java`
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/cloud/CloudMessageController.java`
- Modify: `omnichannel-app/.../app/inbox/InboxController.java` (add `POST /chats/{chatId}/cloud-messages`)
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/cloud/CloudSendApiIT.java`; Modify: `app/inbox/InboxSendIT.java` (two tests)

**Interfaces:**
- Consumes: Tasks 1, 2, 3, 6; `InboxQueryService.requireVisible(me, ws, chatId)`; `NewChatService.existingChat` logic (`:153`), `sendToExisting`, `lookups.lookup`.
- Produces:
  - `InboxSendService`: `private record Outgoing(..., List<String> pollOptions, String messagePageId, Map<String,Object> request)` keeping the two existing constructors delegating with `null, null`; `public CompletableFuture<MessageView> sendCloud(AuthenticatedUser me, String ws, WaWebChat chat, CloudMessage m, String typedPhone, boolean toIsPhone)` and `public CompletableFuture<MessageView> sendCloud(SendActor actor, String ws, WaWebChat chat, CloudMessage m, String typedPhone, boolean toIsPhone)`: closed-chat and `requireReady` checks as `send`, then `validator.validate(ws, m, media::mimeOf, toIsPhone)` (`startCloud` passes true, `sendToChat` passes false), then `cloudOutgoing(chat, m)`, then the shared `private CompletableFuture<MessageView> dispatch(SendActor, String ws, WaWebChat, DeviceSession, Outgoing, String typedPhone)` that `send()` now also uses. `cloudOutgoing`: `type text` → `Outgoing(body, options{linkPreview:true}, "text", body, ..., request)`; `type interactive` → `MessagePage page = pages.create(chat, m); page = pages.drawPreview(page); String link = pages.link(page); Outgoing(link, options{linkPreview:true}, "text", link, null, null, null, null, null, page.getId(), request)`. `outgoing()`'s typed-text branch now also sets `request = cloud.toMap(new CloudMessage(WHATSAPP_WEB, "individual", digitsOf(chat), "text", new Text(true, text), null, null))` (digits = remote id before `@` when `@c.us`, else null). `record()` sets `message.setMessagePageId(out.messagePageId())` and `message.setRequest(out.request())` for both inserted and existing rows (never overwriting a non-null with null). `afterSend()` calls `pages.attachMessage(out.messagePageId(), message.getId())` in try/catch when set.
  - `NewChatService`: `public CompletableFuture<MessageView> startCloud(AuthenticatedUser me, String ws, String phoneNumberId, CloudMessage m)`: same phone/number/scope/device checks as `start` (`:56-64`) with `phone = m.to()`, then `existingChat(...)`; existing → reopen/claim as `sendToExisting` does but calling `sender.sendCloud(me, ws, chat, m, null, true)`; else lookup → `NotOnWhatsAppException` or a `fresh` chat (`:80-89`) and `sender.sendCloud(me, ws, fresh, m, phone, true)`. Extract the shared pieces into private helpers so `start` and `startCloud` share them.
  - `@Service CloudSendService` with `public CompletableFuture<CloudSendResponse> send(AuthenticatedUser me, String ws, String phoneNumberId, CloudMessage m)`: `messaging_product` must equal `whatsapp-web` else `ValidationException("messaging_product", "channel not available")`; number must be `WHATSAPP_WEB` (it always is today); delegates to `newChats.startCloud` and maps `MessageView` → `CloudSendResponse(WHATSAPP_WEB, [Contact(m.to(), m.to())], [Message(view.id())])`. And `public CompletableFuture<CloudSendResponse> sendToChat(AuthenticatedUser me, String ws, WaWebChat chat, CloudMessage m)`: fills `to` from the chat (`remoteId` digits before `@` for a `@c.us` chat, else the contact's phone when known, else the bare `@lid` remote id) and calls `sender.sendCloud(me, ws, chat, withTo, null, false)`. For that path `to` is not a phone, so `CloudMessageValidator.validate` gains a fourth parameter `boolean toIsPhone`: the `to`-keyed API passes `true` (normalise + `Phones.isValid`), the chat-keyed path passes `false` (non-blank only).
  - `CloudMessageController` (`@RestController @RequestMapping("/api/workspaces/{workspaceId}/channel-accounts/{phoneNumberId}")`): `@PostMapping("/messages") @PreAuthorize("@access.has(#workspaceId, 'inbox:send')") CompletableFuture<CloudSendResponse> send(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable ..., @RequestBody JsonNode body)` → `cloud.parse(body)` then `cloudSend.send(...)`; `@PostMapping(value = "/messages/preview", produces = "text/html;charset=UTF-8") String preview(...)` → `validator.validate(ws, m, media::mimeOf, false)` then `pages.previewHtml(m, devices.digitsOf(phoneNumberId))`.
  - `InboxController`: `@PostMapping("/chats/{chatId}/cloud-messages") @PreAuthorize(inbox:send) CompletableFuture<CloudSendResponse> sendCloud(me, ws, chatId, @RequestBody JsonNode body)` → `cloudSend.sendToChat(me, ws, query.requireVisible(me, ws, chatId), cloud.parse(body))`.

- [ ] **Step 1: Write the failing ITs**

```java
class CloudSendApiIT extends InboxApiTestBase {   // uses the FakeDevice pattern of InboxSendIT.viaDevice, with the url parametrised
    @Test void textGoesOutAsText()                     // POST .../channel-accounts/{n}/messages text.json (to = an existing chat's phone) → device sees content "hi", options.linkPreview true; 200 body messaging_product "whatsapp-web", contacts[0].wa_id digits, messages[0].id == stored message id; stored request.type == "text"
    @Test void buttonGoesOutAsALinkWithPreview()       // button.json → device content matches "^http://pages\\.test/t/[A-Za-z0-9_-]{22}$", options.linkPreview true; stored message body == link, messagePageId set, request.interactive.type == "button"; page.messageId == message id; GET /t/{token}/og.png 200
    @Test void chatKeyedRouteFillsTo()                 // POST /inbox/chats/{id}/cloud-messages with no "to" → 200; for a @lid chat without phone also 200
    @Test void whatsappProductIsRejected()             // 400 fields.messaging_product "channel not available"
    @Test void linkHeaderIsRejected()                  // header image {link} → 400 fields "interactive.header.image.link"
    @Test void unknownMediaIdIsRejected()              // 400 fields "interactive.header.image.id" "unknown media"
    @Test void newNumberStartsAChat()                  // to = unseen phone, device answers the lookup then the send → chat created, 200
    @Test void previewReturnsInertHtml()               // POST /messages/preview → text/html, contains the body, no "/t/" beacon, hrefs "#"
    @Test void refusedSendLeavesNoMessage()            // device answers an error → 5xx, no WaWebMessage, page row may exist with messageId null
}
// InboxSendIT additions
@Test void typedTextStoresTheCloudRequest()  // existing text send → stored request == {messaging_product: whatsapp-web, recipient_type: individual, to: digits, type: text, text: {preview_url: true, body: "hello"}}
@Test void templateSendsKeepNoRequest()      // request null, messagePageId null
```

- [ ] **Step 2: Run to verify failure.** `-Dtest='CloudSendApiIT,InboxSendIT'`

- [ ] **Step 3: Implement** in this order: `InboxSendService` refactor (run `InboxSendIT` to prove nothing moved), `NewChatService.startCloud`, `CloudSendService`, the two controllers.

- [ ] **Step 4: Run to verify pass**, then `-Dtest='InboxSendIT,NewChatIT,CloudSendApiIT,MessagePageIT'` (find the new-chat IT's real name with `ls omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/`).

- [ ] **Step 5: Commit** `feat(cloud): Cloud-shaped send API; interactive messages go out as a tracked page link`

---

### Task 8: Reply matching

**Files:**
- Modify: `omnichannel-service/.../service/campaign/CampaignMessageListener.java:127` (`static String bare` → `public static String bare`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/MessagePageReplyListener.java`
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/page/MessagePageReplyIT.java` (extends `InboxApiTestBase`, with a `MutableClock` fixture as `CampaignTestBase.Fixtures` does)

**Interfaces:**
- Consumes: `InboxEvents.MessageUpserted(chat, message, inserted)`; `MessagePageRepository.findByChatIdAndCreatedAtAfterOrderByCreatedAtDesc`; `CloudMessageValidator.replies`; `MessagePageService` (add `public Optional<MessagePage> matchReply(WaWebChat chat, WaWebMessage inbound)` there so the listener stays thin).
- Produces: `@Component MessagePageReplyListener` with `@EventListener public void on(InboxEvents.MessageUpserted e)`: only `direction IN && inserted`; `text`/`chat` types → `bare(body)` compared with `bare(title)` of each reply of each page in the window (newest page first); `location` type → the newest location-request page in the window. On a match: an existing action with that `(kind,id)` and null `repliedMessageId` gets the message id, else a new action with `repliedMessageId` (and lat/lng from `inbound.getLocation()` for LOCATION) is appended; save, publish `Activity` and `MessagePageChanged`. Pages created after the inbound's timestamp are skipped. Everything in try/catch with a warn log.

- [ ] **Step 1: Write the failing IT**

```java
class MessagePageReplyIT extends InboxApiTestBase {
    @Test void matchesATypedButtonTitle()          // seed button page (Change/Cancel); publish MessageUpserted(IN, "change", inserted) → page has BUTTON_REPLY "Change" with repliedMessageId; view.buttons[0].replied true
    @Test void completesATappedActionInstead()     // page already has BUTTON_REPLY "Change" from a beacon; inbound "Change" → same action gets repliedMessageId, no second action
    @Test void matchesAListRowTitle()              // list page, inbound "Two-Day Delivery" → LIST_REPLY
    @Test void ignoresOtherText()                  // "hello" → no action
    @Test void ignoresRepliesOutsideTheWindow()    // clock.advance(49h) → no match
    @Test void ignoresRepliesOlderThanThePage()    // inbound timestamp before page.createdAt → no match
    @Test void inboundLocationClosesALocationRequest() // location page; inbound type location with coordinates → LOCATION action with lat/lng
    @Test void newestPageWins()                    // two button pages both with "Yes" → the newer gets it
}
```

- [ ] **Step 2: Run to verify failure.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass**, plus `CampaignMessageListenerTest` if one exists (`bare` visibility change).

- [ ] **Step 5: Commit** `feat(pages): match a customer's WhatsApp reply to the button or row they chose`

---

### Task 9: CRM activities

**Files:**
- Modify: `omnichannel-crm/.../crm/activity/ActivityType.java:7` (add `MESSAGE_PAGE_OPENED, MESSAGE_PAGE_ACTION` with javadoc)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/MessagePageObserver.java` (port), `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/crm/MessagePageObserverAdapter.java`, `omnichannel-crm/src/main/java/me/nageshk/omnichannel/crm/segment/MessagePageActivityRecorder.java`
- Modify: `MessagePageService` (call the observer from the `Activity` publication points, inside try/catch)
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/MessagePageActivityIT.java`

**Interfaces:**
- Consumes: `MessagePageEvents.Activity` (Task 6); `CampaignObserverAdapter`/`CampaignActivityRecorder` as the pattern; `ActivityLog.append(ws, customerId, type, target, pipelineId, phoneNumberId, actorId, actorType, happenedAt, summary, payload)`.
- Produces:
  - `public interface MessagePageObserver { record PageActivity(String workspaceId, String contactId, String channelAccountId, String messageId, String kind, String id, String title, Double latitude, Double longitude, Instant at) {} void opened(PageActivity e); void acted(PageActivity e); }`
  - `MessagePageService` calls `observer.opened` on the first OPEN and `observer.acted` on every `Activity` with `firstOfItsKind` and kind ≠ OPEN (so one per distinct button/row/CTA/location per page).
  - `MessagePageActivityRecorder.recordOpened(ws, contactId, channelAccountId, ChannelKind kind, String messageId, Instant at)` → summary `"Opened the message page"`; `recordAction(ws, contactId, channelAccountId, kind, messageId, String action, String title, Double lat, Double lng, Instant at)` → summaries `Tapped "<title>"` (BUTTON_REPLY/LIST_REPLY), `Opened the link "<title>"` (CTA_URL), `Shared location` (LOCATION); payload `{messageId, action, id, title, latitude, longitude, channelAccountId, channelKind}`; same CRM-enabled and per-CRM-contact loop as `recordSent`.

- [ ] **Step 1: Write the failing IT** in the `SegmentCampaignIT` style (CRM enabled via `PUT /crm {enabled:true}`, a CRM contact captured for the host contact):

```java
@Test void firstOpenAndEachDistinctActionWriteOneActivity() // open twice, reply "Change" twice, reply "Cancel" once → crmActivities: 1 MESSAGE_PAGE_OPENED + 2 MESSAGE_PAGE_ACTION; summaries exact; payload.channelKind WHATSAPP_WEB; actorType SYSTEM
@Test void nothingWhenCrmIsOff()
@Test void typedReplyMatchWritesTheAction()              // via MessageUpserted → MESSAGE_PAGE_ACTION Tapped "Change"
```

- [ ] **Step 2: Run to verify failure.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(crm): message page opens and actions on the contact timeline`

---

### Task 10: Sweeps, docs, full verification

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/page/MessagePageSweeper.java` (`@Scheduled(cron = "0 45 3 * * *")`: `media.sweepOrphans(now - 24h)` and `pages.sweepExpiredPreviews(now)` which deletes `messagePagePreviews` files whose page has `expiresAt < now` and clears `previewReady`)
- Modify: `MessagePageService` (add `public int sweepExpiredPreviews(Instant now)`)
- Modify: `docs/superpowers/known-limits.md` (entries: orphan page row on a refused dispatch; best-effort title matching within 48 h; `link` headers rejected; document uploads capped at 16 MB; campaigns, automations and Meta templates incl. carousel deferred to the template project; public HTTPS origin needed for the real-device check)
- Modify: `docs/superpowers/project-rules.md` §2 (one line: `OMNICHANNEL_PAGES_BASE_URL` must be the public HTTPS origin for link previews; for a local check use `cloudflared tunnel --url http://localhost:8089`)
- Modify: `README.md` API section (the three new endpoints and the public `/t/*` routes)
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/page/MessagePageSweeperIT.java`

- [ ] **Step 1: Write the failing IT** (`sweepExpiredPreviews` removes the file of an expired page and keeps a live one; `og.png` then 404 for the expired page).
- [ ] **Step 2: Run to verify failure.** - [ ] **Step 3: Implement sweeper and docs.** - [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Full verification**: `./mvnw -q verify` (about 5 min, 4 forks, ~6 GB). Expected: BUILD SUCCESS.
- [ ] **Step 6: Commit** `chore(pages): sweeps, limits and docs for cloud messages`

---

## Manual check (after the console plan)

Deploy on 8089 per project-rules §2. With `curl` and a bearer token: upload a PNG to `/media`, send `button.json` with its id to a chat of your own number (name the recipient; Mon–Sat 10:00–18:00 IST). Expect a message that is only a link, with a preview card. For the real preview, run the app behind a tunnel and set `OMNICHANNEL_PAGES_BASE_URL` to it. Open the link on the phone, tap a reply, send it in WhatsApp, and check the inbox card shows "Replied".
