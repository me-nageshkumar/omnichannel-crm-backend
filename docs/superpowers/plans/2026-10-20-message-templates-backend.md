# Message Templates (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brand-scoped WhatsApp message templates with named placeholders, eight message types, media in GridFS,
duplicate across brands, a safe render step, role-based access and demo data.

**Architecture:** Contact attributes gain an immutable `key`. A new package `me.nageshk.omnichannel.template` holds:
- `PlaceholderParser`, the grammar gate;
- `MessageTemplate`, a document in `message_templates`;
- `TemplateValidator`, for per-type content rules;
- `TemplateService`, for CRUD, attribute binding, warnings and duplicate;
- `TemplateMediaService`, for GridFS bucket `template_media`, magic-byte sniffing and the orphan sweep;
- `TemplateRenderer`, which builds the context and renders with JMustache;
- brand-scoped controllers under `/api/workspaces/{workspaceId}/templates…` and `…/template-media…`;
- `TemplateDemoSeeder`.

**Tech Stack:** Spring Boot 4.1.1, Java 21, Lombok, Jackson 3, Spring Data MongoDB + `MongoTemplate` + `GridFsTemplate`,
JMustache `com.samskivert:jmustache:1.16`, Spring Security method security (`@access.has`), JUnit 5, AssertJ, MockMvc,
embedded flapdoodle Mongo.

**Spec:** `docs/superpowers/specs/2026-10-20-message-templates-design.md`. Its "Calls made without asking" section is binding.

## Global Constraints

- **Branch** `feature/message-templates` in the main checkout (no `.worktrees`). Whole suite: `./mvnw test`.
- **Package** `me.nageshk.omnichannel.template`. **Collections** `message_templates` and GridFS bucket `template_media`
  (`template_media.files`, `template_media.chunks`). Extend `support/TestUsers.deleteAll()` to clear all three.
- **Style:**
  - Lombok `@Getter`/`@Setter` on documents, never hand-written plain accessors;
  - records for views and requests;
  - one-line Javadoc per class.
  - Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Brand scoping:** every query is scoped by `workspaceId`, and another brand's template, media or contact is 404.
- **Controllers** use `@PreAuthorize("@access.has(#workspaceId, '<code>')")`.
- **Errors** use the existing handlers:
  - `VALIDATION` (400, `fields` map);
  - `DUPLICATE` (409, `field`);
  - `NOT_FOUND`;
  - `FORBIDDEN`;
  - `CONFLICT`: the existing `OptimisticLockingFailureException` handler, whose generic message is fine;
  - new `TemplateInUseException`: 409, code `TEMPLATE_IN_USE`, message "This template has been used; archive it instead".
- **Field-error keys:**
  - `name`, `category`, `type`, `mediaId`;
  - `content.<field>` (e.g. `content.body`, `content.options`);
  - `placeholders.<name>.<setting>` (e.g. `placeholders.order_id.label`).
  - Placeholder syntax errors are reported on the content field they occur in.
- **Grammar:**
  - placeholder name and attribute key `[a-z][a-z0-9_]{0,39}`;
  - whitespace inside braces allowed;
  - at most 20 distinct placeholders;
  - `contact` reserved (only `contact.name`, `contact.phone`, `contact.attributes.<key>`).
- **Limits:**
  - template name 1–100, unique per brand ignoring case, archived included;
  - category 1–40 (optional);
  - body 1–4096; caption 0–1024;
  - location name and address 0–256; lat −90..90, long −180..180;
  - contact card fullName 1–256, phone `^[1-9][0-9]{7,14}$`, organization 0–256, email 0–256 valid;
  - poll question 1–255, options 2–12 each 1–100 distinct ignoring case;
  - fallback, example ≤ 200; label ≤ 60; agent value trimmed, cut to 1000;
  - media ≤ 16 MB.
- **Placeholders allowed in:** TEXT body; IMAGE, VIDEO and DOCUMENT caption; LOCATION name and address;
  CONTACT_CARD fullName and organization; POLL question and options. A placeholder in any other field is a field error.
- **Media MIME by type:**
  - IMAGE `image/jpeg`, `image/png`;
  - VIDEO `video/mp4`, `video/3gpp`;
  - AUDIO `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`;
  - DOCUMENT `application/pdf`, `application/msword`, `application/vnd.ms-excel`, `application/vnd.ms-powerpoint`,
    the three `openxmlformats` types (docx, xlsx, pptx), `text/plain`, `text/csv`, `application/zip`.
  - `voiceNote` is allowed only for `audio/ogg` whose header contains `OpusHead`; record it at upload as metadata `opus: true`.
- **Who-changed:** follow the contact notes' convention and store `createdByUsername`/`createdByName` and
  `updatedByUsername`/`updatedByName` (not user ids; ruling recorded here).

## API contract (the console plan consumes exactly this)

Base `B = /api/workspaces/{workspaceId}`.

Types:
- `TemplateType`: `TEXT|IMAGE|VIDEO|DOCUMENT|AUDIO|LOCATION|CONTACT_CARD|POLL`.
- `TemplateStatus`: `ACTIVE|ARCHIVED`.
- `PlaceholderKind`: `CONTACT_FIELD|CONTACT_ATTRIBUTE|AGENT`.
- `TemplateContent {body, linkPreview, caption, voiceNote, latitude, longitude, name, address, fullName, phone,
  organization, email, question, options, allowMultiple}`. Unused fields are null.
- `MediaView {mediaId, fileName, mimeType, size}`.
- `PlaceholderView {name, kind, attributeKey, attributeId, attributeName, missing, label, example, required, fallback}`.
  - `name` is the full placeholder text without braces, e.g. `contact.attributes.city` or `order_id`.
  - `attributeName` is the current name of the bound attribute, or null.
  - `missing` is true when the bound attribute no longer exists.
- `TemplateView {id, workspaceId, channel, name, category, type, content, media: MediaView|null,
  placeholders: [PlaceholderView], status, lastUsedAt, createdBy: {username, name}, creationTime,
  updatedBy: {username, name}, lastUpdateTime, version, warnings: [string]}`.
  - Warning texts: `Attribute "city" was deleted — fallback will be used`, and when an attribute with that key exists
    again, `Attribute "city" was deleted — a new "city" exists; save the template to use it`.
- `TemplateRequest {name, category, type, content, mediaId, placeholders: [{name, fallback, label, example, required}],
  version}`. `type` is ignored on PUT, and `version` is required on PUT.
- `RenderRequest {contactId?, values: {name: string}}`.
- `RenderedTemplate {type, content (rendered), media, vcard, missing: [name], warnings: [string]}`.
- `AttributeView` (contacts) gains `key`: `{id, key, name, description, contactCount}`.

Endpoints, with the authority in brackets:
- `GET B/templates?q=&type=&category=&status=` → `[TemplateView]`, sorted by name; `status` defaults to `ACTIVE` [template:view]
- `GET B/templates/categories` → `[string]`, sorted [view]; `GET B/templates/{id}` [view]
- `POST B/templates` → 201 [template:manage]; `PUT B/templates/{id}` [manage]
- `POST B/templates/{id}/archive`, `/unarchive` → TemplateView [manage]; `DELETE B/templates/{id}` → 204 [manage]
- `POST B/templates/{id}/duplicate {targetWorkspaceId, name}` → 201 TemplateView, in the target brand [manage in both brands]
- `POST B/templates/{id}/render` → RenderedTemplate [view; plus `contact:view` when `contactId` is given, else 403]
- `POST B/template-media` (multipart `file`) → 201 MediaView [manage]; `GET B/template-media/{mediaId}` → file bytes
  with `Content-Type` and `Content-Disposition: inline; filename=…` [view]

## Review Focus

1. **Agent values are never re-evaluated.** A value such as `{{contact.phone}}` or `{{#x}}` renders literally.
   (Task 7 `agentValueWithBracesIsLiteral`.)
2. **No HTML escaping.** `<b>&"'` renders unchanged. (Task 7 `htmlCharsNotEscaped`.)
3. **Another brand's ids:** media in a save, contact in a render, target brand of another customer in duplicate. Each is
   404 or a field error, never a leak. (Task 5 `otherBrandMediaRejected`, Task 7 `renderWithOtherBrandContactIs404`,
   Task 6 `duplicateToOtherCustomerIs404`.)
4. **Attribute deleted, then recreated with the same key.** Render stays on the fallback, the warning says save, and the
   next save re-binds. (Task 4 `recreatedAttributeRebindsOnSave`.)
5. **Hidden number.** `{{contact.phone}}` uses the fallback, and a present phone renders `+<digits>`.
   (Task 7 `hiddenPhoneUsesFallback`.)

---

### Task 1: Attribute keys

**Files:** `contact/ContactAttribute` (+ `key`, compound unique index `ws_key` on `{workspaceId, key}`, partial on
`key: {$type: "string"}`), new `contact/AttributeKeys`, `ContactFieldService.createAttribute`, `AttributeView`,
new `contact/AttributeKeyBackfill`, `ContactDemoSeeder.attribute(...)`, and `ContactApiTestBase.attribute(...)`.

**Interfaces — Produces:**
- `static String AttributeKeys.generate(String name, Set<String> taken)` follows the spec's rules: lowercase; runs of
  non-`[a-z0-9]` become `_`; trim `_`; prefix `a_` if the first char isn't a letter; cut to 40; then `_2`, `_3`… on
  clash, keeping the total ≤ 40. An empty result becomes `attribute`.
- `ContactAttributeRepository.findByWorkspaceIdAndKey(String, String)`.
- `ContactAttributeRepository.findByWorkspaceIdAndIdIn(String, Collection<String>)`.

Rules:
- `updateAttribute` never changes `key`.
- `AttributeKeyBackfill` is an `ApplicationRunner` with `@Order(7)`, after the demo seeders. It gives every attribute
  without a key one, per brand, in `_id` order.

Tests:
- `contact/AttributeKeysTest`:
  - `"Preferred size"→preferred_size`, `"  City "→city`, `"2nd phone"→a_2nd_phone`, `"Ünïcode!"→n_code`;
  - an empty or symbols-only name gives `attribute`;
  - a clash gives `city_2`, then `city_3`;
  - a 60-char name is cut to 40, and a cut name with a clash still totals ≤ 40.
- `ContactFieldsApiIT`: `createdAttributeHasKey`, `renameKeepsKey`, and `listShowsKey`.
- `contact/AttributeKeyBackfillIT`: attributes saved without a key get keys, and existing keys are untouched.

- [ ] Write the failing tests and watch them fail → implement → `./mvnw test -Dtest='AttributeKeys*,ContactFieldsApiIT'` →
  commit `feat: immutable keys for contact attributes`.

### Task 2: Placeholder grammar

**Files:** new `template/PlaceholderParser`, `template/PlaceholderRef`, `template/PlaceholderKind`,
`template/PlaceholderSyntaxException`.

**Interfaces — Produces:**
- `record PlaceholderRef(String name, PlaceholderKind kind, String attributeKey)`. `name` is the normalised inner text.
- `static List<PlaceholderRef> PlaceholderParser.parse(String text)` returns the distinct refs in order of first
  appearance. On bad syntax it throws `PlaceholderSyntaxException(String message)`.
- `static String PlaceholderParser.normalise(String text)` rewrites `{{  x  }}` to `{{x}}`.

Algorithm:
- Scan for `{{`. Each must be followed by optional spaces, an identifier path, optional spaces and `}}`.
- Reject a first character in `#^/>!{&=` with message `"{{#" is not allowed — only {{name}} placeholders are supported`
  (quoting the actual two-character sequence).
- Reject an unclosed `{{` with `unclosed "{{"`, and any other content with `invalid placeholder "{{…}}"`.
- `contact.name` and `contact.phone` → CONTACT_FIELD; `contact.attributes.<key>` → CONTACT_ATTRIBUTE; `<name>` → AGENT.
- `contact`, `contact.x` and `contact.attributes` → `"{{contact…}}" is not a contact field`.
- More than 20 distinct → `at most 20 placeholders`. The count is checked by `TemplateValidator` across all fields,
  so `parse` does not limit.

Tests `template/PlaceholderParserTest`:
- `parsesAllKinds`, `normalisesWhitespace`, `distinctInOrder`;
- `rejectsSection`, `rejectsInverted`, `rejectsPartial`, `rejectsComment`, `rejectsTripleBrace`, `rejectsAmpersand`,
  `rejectsDelimiterChange`;
- `rejectsUnclosed`, `rejectsUppercaseName`, `rejectsDashName`, `rejectsReservedContact`, `rejectsLongName` (41 chars);
- `plainTextHasNoRefs`, `singleBraceIsText` (`{x}` is fine).

- [ ] Failing tests → implement → `./mvnw test -Dtest=PlaceholderParserTest` → commit `feat: template placeholder grammar`.

### Task 3: Authorities, template document, TEXT CRUD

**Files:**
- `access/Authority`: add `TEMPLATE_VIEW("template:view", BRAND, "See the brand's message templates and preview them",
  CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD, AGENT)` and `TEMPLATE_MANAGE("template:manage", BRAND,
  "Create, edit, duplicate, archive and delete message templates, upload template media", CUSTOMER_ADMIN,
  WORKSPACE_MANAGER, TEAM_LEAD)`.
- New in `template/`:
  - `MessageTemplate` (document, `@Version`, unique index `ws_nameKey`, index `ws_status_nameKey`);
  - `TemplateContent`, `TemplateMediaRef`, `TemplatePlaceholder` (embedded classes, Lombok);
  - `TemplateType`, `TemplateStatus`;
  - `MessageTemplateRepository`;
  - `TemplateValidator`, `TemplateService`, `TemplateController`;
  - `TemplateView`, `TemplateRequest`, `TemplateInUseException` (+ handler in `api/ApiExceptionHandler`).
- `TestUsers.deleteAll()` and the `pom.xml` JMustache dependency (added here so later tasks only consume it).

**Interfaces — Produces:**
- `TemplateService`:
  - `list(String ws, String q, TemplateType type, String category, TemplateStatus status)`
  - `categories(String ws)`
  - `get(String ws, String id)`
  - `create(AuthenticatedUser me, String ws, TemplateRequest r)`
  - `update(AuthenticatedUser me, String ws, String id, TemplateRequest r)`
  - `archive`, `unarchive`, `delete(String ws, String id)`
  - `MessageTemplate require(String ws, String id)`
- `TemplateValidator.validate(TemplateType, TemplateContent, Map<String,String> errors)` checks per-type fields and
  placeholder positions. It returns `List<PlaceholderRef>` across all allowed fields, in field order. Task 4 extends it.

Rules:
- On create, `channel = WHATSAPP_WEB` and `status = ACTIVE`. Unused content fields are nulled.
- `nameKey = name.trim().toLowerCase()`. A clash gives `DUPLICATE` with field `name`, message `"<name>" already exists in this brand`.
- In this task only TEXT is accepted. Other types give a `type` field error "not supported yet", which Task 4 and Task 5 remove.
- Placeholder settings for TEXT agent placeholders are validated here as specified (label required, ≤60; example ≤200;
  required flag; fallback only when not required). Contact placeholder fallbacks are required and non-blank.
- Attribute placeholders are bound in Task 4. Until then, a `contact.attributes.*` ref gives field error
  `placeholders.<name>` "not supported yet".
- `delete` throws `TemplateInUseException` when `lastUsedAt != null`.
- `lastUsedAt` is never set by this feature.

Tests `template/TemplateApiIT`, with a base `TemplateApiTestBase` modelled on `ContactApiTestBase`:
- `teamLeadCreatesTextTemplate`, `nameDuplicateIgnoringCaseIs409`, `archivedNameStillBlocks`;
- `bodyLimits`: an empty body is 400 and a 4097-char body is 400;
- `badPlaceholderIs400OnBody`, `twentyOnePlaceholdersIs400`;
- `agentPlaceholderNeedsLabel`, `requiredAgentPlaceholderRejectsFallback`, `contactPlaceholderNeedsFallback`;
- `settingsForAbsentPlaceholderAreDropped`, `missingSettingsIs400`;
- `listFiltersByQTypeCategoryStatus`, `categoriesDistinctSorted`;
- `archiveHidesFromDefaultList`, `unarchive`;
- `staleVersionIs409`;
- `deleteUnusedIs204`, `deleteUsedIs409`;
- `agentCanViewNotManage` (403 on POST), `otherBrandIs404`, `accountManagerHasNoAccess` (403).

- [ ] Failing tests → implement → `./mvnw test -Dtest='TemplateApiIT'` → commit `feat: message templates with text type`.

### Task 4: Location, contact card, poll; attribute binding and warnings

**Files:** `TemplateValidator`, `TemplateService`, `TemplateView` (warnings, `missing`, `attributeName`).

**Interfaces — Consumes:**
- `ContactAttributeRepository.findByWorkspaceIdAndKey` and `findByWorkspaceIdAndIdIn` (Task 1);
- `PlaceholderParser` (Task 2).

Binding on save, per spec. For each CONTACT_ATTRIBUTE ref:
- the key matches a current attribute in the brand → `attributeId` = its id;
- else the previous saved version has a placeholder with the same name → keep its `attributeId`;
- else → field error `placeholders.<name>`: `Unknown contact attribute "<key>"`.

Warnings are computed on every read. `missing` = the bound id is not among the brand's attributes. A missing placeholder
whose key now matches a current attribute gets the "a new … exists; save" text.

Content rules for LOCATION, CONTACT_CARD and POLL follow the Global Constraints. POLL options are distinct ignoring case
before rendering.

Tests `template/TemplateContentIT`:
- `locationLimits`, `contactCardValidatesPhoneAndEmail`, `contactCardPlaceholderOnlyInNameAndOrganization`;
- `pollNeedsTwoToTwelveDistinctOptions`, `placeholderInLatitudeIs400`.

Tests `template/TemplateBindingIT`:
- `attributePlaceholderBindsToId`, `unknownKeyIs400`;
- `renameAttributeKeepsBinding` (no warning);
- `deletedAttributeWarnsAndSaveKeepsBinding`;
- `recreatedAttributeRebindsOnSave` (the warning before the save says save; after the save there's no warning and the
  id is new).

- [ ] Failing tests → implement → `./mvnw test -Dtest='Template*IT'` → commit `feat: location, contact card and poll templates with attribute binding`.

### Task 5: Media

**Files:**
- `application.yml` (multipart 16MB/17MB);
- new `template/TemplateMediaConfig`: a `GridFsTemplate` bean `templateMediaGridFs` on bucket `template_media`;
- new `template/MediaSniffer`, `template/TemplateMediaService`, `template/TemplateMediaController`, `template/MediaView`;
- `TemplateValidator` and `TemplateService` for IMAGE, VIDEO, DOCUMENT and AUDIO;
- new `template/TemplateMediaSweeper` (`@Scheduled(cron = "0 30 3 * * *")` plus `@EnableScheduling` on
  `TemplateMediaConfig`).

**Interfaces — Produces:**
- `static Optional<String> MediaSniffer.detect(byte[] head, String fileName)`. It reads the first 64 bytes:
  - JPEG `FF D8 FF`; PNG `89 50 4E 47 0D 0A 1A 0A`; PDF `%PDF-`;
  - `....ftyp` + brand: `M4A ` → `audio/mp4`; `3gp*` → `video/3gpp`; else `video/mp4`;
  - `OggS` → `audio/ogg`, with opus = `OpusHead` within the first 64 bytes;
  - `ID3` or `FF Ex/Fx` frame sync with layer bits ≠ 00 → `audio/mpeg`; ADTS `FF F1`/`FF F9` → `audio/aac`;
  - `PK 03 04` + extension docx/xlsx/pptx/zip → the matching type;
  - OLE `D0 CF 11 E0` + extension doc/xls/ppt → the matching type;
  - extension txt/csv with no NUL in the head → `text/plain` / `text/csv`.
  - Anything else → empty, which gives field error `file`: "is not a supported file type".
- `TemplateMediaService`:
  - `upload(AuthenticatedUser, String ws, MultipartFile)` → `MediaView`;
  - `MediaFile open(String ws, String mediaId)`, which throws 404 for another brand;
  - `String copy(String ws, String mediaId, String targetWs)`;
  - `void delete(String mediaId)`;
  - `int sweepOrphans(Instant cutoff)`.

Rules:
- GridFS metadata: `workspaceId`, `fileName`, `mimeType`, `size`, `opus`, `uploadedBy`, `uploadedAt`.
- On save:
  - `mediaId` is required for media types;
  - it must exist in the same brand (else field `mediaId` "not found");
  - its MIME type must be allowed for the type (field `mediaId` "a PDF can't be used for an image template");
  - no other template may reference it (field `mediaId` "is used by another template");
  - `voiceNote` is valid only when opus.
- Replacing the media on update deletes the old file after the save. Deleting a template deletes its file.
- `sweepOrphans` deletes files with `uploadedAt < cutoff` that no `message_templates.media.mediaId` refers to. The
  scheduled call passes now − 24 h.

Tests:
- `template/MediaSnifferTest`, one per format plus `rejectsExeAndHtml` and `txtWithNulIsRejected`.
- `template/TemplateMediaIT`:
  - `uploadAndDownload`, `uploadOver16MbIs400`, `pngNamedPdfIsDetectedAsPng`;
  - `imageTemplateWithPdfIs400`, `otherBrandMediaRejected`, `sharedMediaRejected`;
  - `voiceNoteOnlyForOpus`, `replacingMediaDeletesOld`, `deletingTemplateDeletesMedia`;
  - `sweepDeletesOnlyOldOrphans`, `agentCanDownloadNotUpload`.

- [ ] Failing tests → implement → `./mvnw test -Dtest='MediaSnifferTest,Template*IT'` → commit `feat: template media in GridFS`.

### Task 6: Duplicate

**Files:** `TemplateService.duplicate(AuthenticatedUser, String ws, String id, String targetWs, String name)`, plus the controller.

**Interfaces — Consumes:** `TemplateMediaService.copy` (Task 5) and the binding rules (Task 4).

Rules:
- The target must be a workspace of the same customer as the source, else 404.
- The caller needs `template:manage` in the target, checked with `access.has(targetWs, "template:manage")`, else 403.
- The name is validated and unique in the target.
- Media is copied.
- Attribute placeholders are re-bound by key in the target brand. A key the target lacks is kept with `attributeId = null`
  and reads as `missing`; this is the one place a save may hold an unknown key.
- The copy is ACTIVE with fresh createdBy/updatedBy values.

Tests `template/TemplateDuplicateIT`:
- `duplicateInSameBrand`, `duplicateToSisterBrandRebindsByKey`, `missingKeyInTargetWarns`;
- `mediaIsCopiedNotShared` (deleting the original leaves the copy downloadable);
- `noManageInTargetIs403`, `duplicateToOtherCustomerIs404`, `duplicateNameInTargetIs409`.

- [ ] Failing tests → implement → `./mvnw test -Dtest='Template*IT'` → commit `feat: duplicate templates across brands`.

### Task 7: Render

**Files:** new `template/TemplateRenderer`, `template/RenderRequest`, `template/RenderedTemplate`, plus a controller endpoint.

**Interfaces — Produces:** `RenderedTemplate TemplateRenderer.render(AuthenticatedUser me, String ws, String templateId,
RenderRequest r)`. The send and campaign features will call exactly this.

Algorithm:
1. Load the template (brand-scoped). If `contactId` is given:
   - require `access.has(ws, "contact:view")` (else 403);
   - load the contact in the brand (else 404).
2. Context map:
   - `contact.name` = the contact name, else fallback;
   - `contact.phone` = `"+" + phone`, else fallback;
   - `contact.attributes.<key>` = `contact.attributes[attributeId]`, else fallback. A missing binding always uses the
     fallback and adds a warning.
   - Blank values count as absent. With no contact, every contact placeholder uses its fallback.
3. Agent values:
   - trim each value and cut it to 1000 chars;
   - empty + optional → fallback or `""`;
   - empty + required → add to `missing` and use `[<label>]`.
4. Compile each allowed field with
   `Mustache.compiler().escapeHTML(false).strictSections(true).defaultValue(null)`, which throws on a missing variable.
   The context is nested maps, so `contact.attributes.city` resolves as a compound key. Values are inserted, never
   compiled, so agent text is literal.
5. Re-check lengths: body 4096, caption 1024, poll question 255, options 100 and distinct, card fields 256. Overflow is
   400 with field `content.<field>`, message "is longer than N characters after filling placeholders".
6. CONTACT_CARD: build a vCard 3.0 (`BEGIN:VCARD`, `VERSION:3.0`, `FN`, `ORG` if set,
   `TEL;type=CELL;waid=<digits>:+<digits>`, `EMAIL` if set, `END:VCARD`), with `\`, `,`, `;` and newlines escaped.

Tests `template/TemplateRenderIT`:
- `previewWithoutContactUsesFallbacks`, `renderWithContactFillsNameAndAttributes`, `hiddenPhoneUsesFallback`;
- `deletedAttributeUsesFallbackWithWarning`, `requiredAgentValueMissingIsListed`, `optionalAgentValueUsesFallback`;
- `agentValueWithBracesIsLiteral`, `htmlCharsNotEscaped`, `whatsappFormattingKept`;
- `overflowAfterRenderIs400`, `pollOptionsDuplicateAfterRenderIs400`, `contactCardHasVcard`;
- `renderWithOtherBrandContactIs404`, `renderWithContactNeedsContactView` (a role with `template:view` only gets 403),
  `agentCanRender`.

- [ ] Failing tests → implement → `./mvnw test -Dtest='Template*IT'` → commit `feat: render templates`.

### Task 8: Demo data and docs

**Files:** new `template/TemplateDemoSeeder` (`@Order(8)`, conditional on `omnichannel.seed.demo=true`), a bundled
`src/main/resources/demo/new-collection.png` (≤ 20 KB), and `README.md` (a Templates section: placeholders, types, the
render endpoint).

Rules:
- For each of Demo Fashion and Demo Electronics, skip the brand if it already has any template. Otherwise create the
  five spec templates through `TemplateService` and `TemplateMediaService`, as `demo-manager`, so validation runs:
  - "Welcome": `Hi {{contact.name}}, welcome to <brand>! Reply here any time.`, fallback "there";
  - "Order shipped": `Hi {{contact.name}}, your order *{{order_id}}* has shipped and arrives by {{delivery_date}}.`,
    with agent fields required, labels "Order ID" / "Delivery date", examples "FS-10234" / "25 Oct";
  - "New collection": IMAGE, caption `Our new collection is in store in {{contact.attributes.city}}!`, fallback
    "your city". The key `city` exists only in Demo Fashion; Demo Electronics uses `{{contact.name}}` instead;
  - "Store location": LOCATION, 19.0760, 72.8777, "<brand> flagship", "Linking Road, Bandra West, Mumbai";
  - "Feedback poll": POLL "How was your last visit, {{contact.name}}?", options Great / Okay / Not good, single choice.
- Category values: Greeting, Orders, Promotion, Store, Feedback.

Tests `template/TemplateDemoSeederIT`: `seedsFiveTemplatesPerDemoBrand` (and the image downloads), `secondRunIsNoOp`.

- [ ] Failing tests → implement → `./mvnw test` (whole suite green) → commit `feat: demo message templates`.
