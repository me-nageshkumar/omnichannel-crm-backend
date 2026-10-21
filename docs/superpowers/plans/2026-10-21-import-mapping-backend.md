# Contact Import Mapping (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A CSV import where the user maps columns to the phone, name, labels and attributes, with defaults, optional label creation and saved mappings, while an import without a mapping behaves exactly as today.

**Architecture:** `ImportMapping` is the one mapping shape. The by-name match the old import did becomes `suggest(...)`, the preview endpoint returns it, and `ContactImportService.run` gains an overload that takes a mapping. Saved mappings live in `contact_import_mappings` behind their own small service and controller. The default country code is the workspace's `defaultCountryCode` from the campaigns plan's `campaign_settings`.

**Tech Stack:** Spring Boot 4.1, Spring Data Mongo, Lombok, Jackson 3 (`tools.jackson.*`), JUnit 5 + MockMvc (`ContactApiTestBase`), flapdoodle embedded Mongo.

**Spec:** `docs/superpowers/specs/2026-10-21-campaigns-design.md` §4 (with the country-code paragraph of §4.2, the `defaultCountryCode` line of §7.3 and the import codes of §9).

## Global Constraints

- Depends on the campaigns backend plan (`2026-10-21-campaigns-backend.md`) being merged first. It provides `Phones.withDefaultCountryCode(String digits, String countryCode)` (its Task 1), `me.nageshk.omnichannel.api.CodedException(HttpStatus status, String code, String field, String message)` and `CampaignSettingsService.get(String workspaceId)` returning `CampaignSettings` whose `getDefaultCountryCode()` is `"91"` by default (its Task 2). This plan defines none of them.
- Lombok accessors on classes; no hand-written getters/setters. Records stay records.
- Endpoints, all `contact:import`, under `/api/workspaces/{workspaceId}/contacts`: `POST /import/preview` (multipart `file`), `POST /import` (multipart `file` + optional `mapping`), `GET /import-mappings`, `POST /import-mappings`, `DELETE /import-mappings/{id}`.
- `ImportMapping`, verbatim: `{phone: string, name: string|null, labels: {column: string|null, defaults: string[]}, attributes: [{attributeId, column: string|null, defaultValue: string|null}], createMissingLabels: boolean (default false), saveAs: string|null}`.
- Preview body, verbatim: `{headers, sample (first 5 rows), rows, suggested, savedMappings: [{id, name, matches}]}`.
- Codes: `MAPPING_COLUMN_REUSED` (400) `A CSV column can be used only once.`; `PHONE_COLUMN_REQUIRED` (400) `Choose the column that holds the phone number.`; duplicate saved-mapping name is `409 DUPLICATE` on field `name`.
- Limits unchanged: 5 MB, 20,000 rows, 200 problems. Label names are 1 to 40 characters; attribute values at most `ContactService.MAX_ATTRIBUTE_VALUE`.
- Country code: after `Phones.normalise`, `Phones.withDefaultCountryCode(digits, settings.getDefaultCountryCode())` prefixes exactly-10-digit numbers with the workspace's code (default `91`); any other length is validated as typed.
- Saved mapping document: `{workspaceId, name, nameKey (unique per workspace), headerSignature: string[], mapping, createdBy, creationTime, lastUsedAt}`. Signature = headers trimmed, lower-cased, blanks dropped, distinct, sorted.
- Wording: "Enabled"/"Disabled" wherever a state is shown (nothing in this feature needs it).
- Tests: `./mvnw -q test -Dtest=<Class>` per task, `./mvnw -q test` for the suite (~2 min).
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A column name typed with different case or stray spaces than the CSV header (`" Phone "` vs `phone`) must still resolve (Task 3 test `mappingMatchesHeadersIgnoringCaseAndSpaces`).
- A `mapping` part that is not JSON, or names label/attribute ids of another brand, must be a 400 or be ignored, never a 500 and never leak the other brand's data (Task 3 tests `mappingThatIsNotJsonIs400`, `idsOfAnotherBrandAreIgnored`).
- A column header present twice in the file maps to its first occurrence, as the old import did (Task 3 test `duplicateHeaderMapsToTheFirstColumn`).
- A label name longer than 40 characters with `createMissingLabels` on must be reported in `unknownLabels`, not abort the import (Task 3 test `overlongLabelIsReportedNotCreated`).
- An import that fails (bad file, missing phone column, duplicate `saveAs`) must save nothing and import nothing (Task 4 tests `failedImportSavesNothing`, `duplicateSaveAsFailsBeforeImporting`).

---

### Task 1: `ImportMapping` and saved mappings (`GET/POST/DELETE /contacts/import-mappings`)

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/contact/ImportMapping.java`
- Create: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMapping.java`
- Create: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMappingRepository.java`
- Create: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMappingService.java`
- Create: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMappingController.java`
- Modify: `src/test/java/me/nageshk/omnichannel/support/TestUsers.java` (add `"contact_import_mappings"` to the collection list in `deleteAll`)
- Modify: `src/test/java/me/nageshk/omnichannel/contact/ContactApiTestBase.java` (`@Autowired ContactImportMappingRepository savedMappings;`)
- Test: `src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingsIT.java`

**Interfaces:**
- Consumes: `ContactApiTestBase`; `DuplicateException(field, message)`, `ValidationException`, `NotFoundException` from `me.nageshk.omnichannel.api`.
- Produces:
  - `public record ImportMapping(String phone, String name, Labels labels, List<AttributeMapping> attributes, boolean createMissingLabels, String saveAs)` with nested `public record Labels(String column, List<String> defaults)` and `public record AttributeMapping(String attributeId, String column, String defaultValue)`. A compact constructor turns a null `labels` into `Labels(null, List.of())`, a null `defaults` or `attributes` into an empty list. `ImportMapping withoutSaveAs()` returns a copy with `saveAs = null`.
  - `@Document("contact_import_mappings") @CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true) class ContactImportMapping` (Lombok `@Getter @Setter`): `@Id String id; String workspaceId; String name; String nameKey; List<String> headerSignature; ImportMapping mapping; String createdBy; @CreatedDate Instant creationTime; Instant lastUsedAt;` and `setName` also sets `nameKey` (lower-case), as `ContactFilter` does.
  - `interface ContactImportMappingRepository extends MongoRepository<ContactImportMapping, String>`: `List<ContactImportMapping> findByWorkspaceId(String workspaceId); Optional<ContactImportMapping> findByIdAndWorkspaceId(String id, String workspaceId); Optional<ContactImportMapping> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);`
  - `public record ImportMappingView(String id, String name, List<String> headerSignature, ImportMapping mapping, String createdBy, Instant creationTime, Instant lastUsedAt)`.
  - `@Service ContactImportMappingService` with `public static final int MAX_NAME = 60;` and
    - `static List<String> signatureOf(List<String> headers)` (see Global Constraints);
    - `List<ImportMappingView> list(String workspaceId)` ordered by `nameKey`;
    - `ImportMappingView create(String workspaceId, String username, String name, List<String> headers, ImportMapping mapping)`: name 1 to 60 characters else `ValidationException("name", "must be 1 to 60 characters")`; `mapping` and `headers` required (`ValidationException("mapping" | "headers", "is required")`); a clash on `nameKey` throws `DuplicateException("name", "\"<name>\" already exists in this brand")`, also when the unique index trips (`DuplicateKeyException`); stored mapping is `mapping.withoutSaveAs()`; `lastUsedAt` is null;
    - `void delete(String workspaceId, String id)` (`NotFoundException("import mapping not found")` outside the brand).
  - `GET /api/workspaces/{ws}/contacts/import-mappings` → `List<ImportMappingView>`; `POST` body `record SaveRequest(String name, List<String> headers, ImportMapping mapping)` → 201 `ImportMappingView`; `DELETE /{id}` → 204.

- [ ] **Step 1: Write the failing tests** `ContactImportMappingsIT extends ContactApiTestBase` (`url() = base(brandA) + "/contacts/import-mappings"`; `body(name)` = `Map.of("name", name, "headers", List.of("Phone", "Full Name"), "mapping", Map.of("phone", "Phone", "name", "Full Name"))`):


```java
private String url() { return base(brandA) + "/contacts/import-mappings"; }

private Map<String, Object> body(String name) {
    return Map.of("name", name, "headers", List.of("Phone", "Full Name"),
            "mapping", Map.of("phone", "Phone", "name", "Full Name"));
}

@Test
void createListAndDelete() throws Exception {
    JsonNode created = json(send(post(url()), leadToken, body("Marketplace")).andExpect(status().isCreated()));
    assertThat(created.get("headerSignature")).extracting(JsonNode::asString).containsExactly("full name", "phone");
    assertThat(created.get("mapping").get("labels").get("defaults")).isEmpty();
    assertThat(created.get("lastUsedAt").isNull()).isTrue();
    assertThat(created.get("createdBy").asString()).isEqualTo("lead");
    send(get(url()), leadToken, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Marketplace"));
    send(delete(url() + "/" + created.get("id").asString()), leadToken, null).andExpect(status().isNoContent());
    assertThat(savedMappings.count()).isZero();
}

@Test
void saveAsInTheBodyIsNotStored() throws Exception {
    Map<String, Object> req = Map.of("name", "X", "headers", List.of("Phone"),
            "mapping", Map.of("phone", "Phone", "saveAs", "ignored"));
    send(post(url()), leadToken, req).andExpect(status().isCreated());
    assertThat(savedMappings.findByWorkspaceIdAndNameKey(brandA.getId(), "x").orElseThrow().getMapping().saveAs()).isNull();
}

@Test
void duplicateNameIsA409OnNameIgnoringCaseAndPerBrand() throws Exception {
    send(post(url()), leadToken, body("Marketplace")).andExpect(status().isCreated());
    send(post(url()), leadToken, body("  marketplace ")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE")).andExpect(jsonPath("$.field").value("name"));
    send(post(base(brandB) + "/contacts/import-mappings"), wmBToken, body("Marketplace")).andExpect(status().isCreated());
    assertThat(savedMappings.count()).isEqualTo(2);
}

@Test
void badNameOrMissingPartsAre400() throws Exception {
    send(post(url()), leadToken, body("")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.name").exists());
    send(post(url()), leadToken, body("x".repeat(61))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.name").exists());
    send(post(url()), leadToken, Map.of("name", "A", "headers", List.of("Phone")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.mapping").exists());
    send(post(url()), leadToken, Map.of("name", "A", "mapping", Map.of("phone", "Phone")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.headers").exists());
    assertThat(savedMappings.count()).isZero();
}

@Test
void signatureIsSortedTrimmedCaseInsensitiveAndDistinct() {
    assertThat(ContactImportMappingService.signatureOf(List.of(" Phone", "NAME ", "", "phone", "City")))
            .containsExactly("city", "name", "phone");
}

@Test
void authoritiesAndBrandIsolation() throws Exception {
    String id = json(send(post(url()), leadToken, body("Marketplace")).andExpect(status().isCreated())).get("id").asString();
    send(get(url()), agentToken, null).andExpect(status().isForbidden());
    send(post(url()), agentToken, body("x")).andExpect(status().isForbidden());
    send(delete(url() + "/" + id), agentToken, null).andExpect(status().isForbidden());
    send(get(url()), wmBToken, null).andExpect(status().isNotFound());
    // brand B's manager deleting brand A's mapping through brand B's own URL: 404 and the document stays
    send(delete(base(brandB) + "/contacts/import-mappings/" + id), wmBToken, null).andExpect(status().isNotFound());
    assertThat(savedMappings.count()).isEqualTo(1);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ContactImportMappingsIT`
Expected: compilation failure (`ContactImportMappingRepository`, `ContactImportMappingService` missing).

- [ ] **Step 3: Implement** the types in Interfaces. Follow `ContactFilterService` / `ContactFilterController` line for line for the duplicate handling, `@PreAuthorize("@access.has(#workspaceId, 'contact:import')")` on all three endpoints, and `me.getUsername()` for `createdBy`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ContactImportMappingsIT,AuthorityCheckScannerIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/support/TestUsers.java src/test/java/me/nageshk/omnichannel/contact/ContactApiTestBase.java src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingsIT.java
git commit -m "feat(contact): saved import mappings"
```

---

### Task 2: `POST /contacts/import/preview`

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportController.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMappingService.java` (add `matching(...)`)
- Test: `src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java`

**Interfaces:**
- Consumes: Task 1's `ImportMapping`, `ContactImportMappingService.signatureOf`, `ContactImportMappingRepository`.
- Produces:
  - In `ContactImportService`: `public record SavedMappingRef(String id, String name, boolean matches)`, `public record Preview(List<String> headers, List<List<String>> sample, int rows, ImportMapping suggested, List<SavedMappingRef> savedMappings)`, `public Preview preview(String workspaceId, byte[] file)`, and `ImportMapping suggest(List<String> headers, List<ContactAttribute> attributes)`.
  - The size, empty and row-count checks of `run` move into one private `parse(byte[])` used by both; the messages stay verbatim (`fields.file`).
  - `preview`: `headers` are the trimmed header cells (blank ones kept, so positions line up); `sample` is the first 5 data rows, each padded with `""` or cut to `headers.size()`; `rows` is the data row count; the phone column is not required here.
  - `suggest` is today's by-name match: first header equal to `phone` (case-insensitive) is `phone`, first `name` is `name`, first `labels` is `labels.column`, every other header equal to an attribute name (case-insensitive, each attribute once) becomes `AttributeMapping(attributeId, header, null)`; `labels.defaults` empty; `createMissingLabels` false. When there is no `phone` header, `phone` is `null`.
  - `savedMappings`: the brand's saved mappings, `matches` = stored `headerSignature` equals `signatureOf(headers)`; order: matching first, then `lastUsedAt` descending (nulls last), then name.
  - `POST /api/workspaces/{ws}/contacts/import/preview` → `Preview`, multipart `file`, `contact:import`.

- [ ] **Step 1: Write the failing tests** in `ContactImportMappingIT extends ContactApiTestBase` (`@BeforeEach` creates labels `VIP`, `Lead` and attributes `City`, `Email` in brand A as `ContactImportExportIT` does; `preview(token, csv)` posts multipart `file` to `base(brandA) + "/contacts/import/preview"`):


```java
private ResultActions preview(String token, String csv) throws Exception {
    return mvc.perform(multipart(base(brandA) + "/contacts/import/preview")
            .file(new MockMultipartFile("file", "c.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + token));
}

private ContactImportMapping saved(String name, String headers, Instant lastUsedAt) {
    ContactImportMapping s = new ContactImportMapping();
    s.setWorkspaceId(brandA.getId());
    s.setName(name);
    s.setHeaderSignature(ContactImportMappingService.signatureOf(List.of(headers.split(","))));
    s.setMapping(new ImportMapping("Phone", null, null, null, false, null));
    s.setLastUsedAt(lastUsedAt);
    return savedMappings.save(s);
}

@Test
void previewReturnsHeadersSampleRowsAndSuggestion() throws Exception {
    String csv = "Phone,Name,Labels,city,Shoe\n" + "9812345678,A,VIP,Pune,9\n".repeat(7);
    preview(leadToken, csv).andExpect(status().isOk())
            .andExpect(jsonPath("$.headers.length()").value(5))
            .andExpect(jsonPath("$.rows").value(7))
            .andExpect(jsonPath("$.sample.length()").value(5))
            .andExpect(jsonPath("$.sample[0][0]").value("9812345678"))
            .andExpect(jsonPath("$.suggested.phone").value("Phone"))
            .andExpect(jsonPath("$.suggested.name").value("Name"))
            .andExpect(jsonPath("$.suggested.labels.column").value("Labels"))
            .andExpect(jsonPath("$.suggested.attributes.length()").value(1))
            .andExpect(jsonPath("$.suggested.attributes[0].attributeId").value(city.getId()))
            .andExpect(jsonPath("$.suggested.attributes[0].column").value("city"))
            .andExpect(jsonPath("$.suggested.createMissingLabels").value(false))
            .andExpect(jsonPath("$.savedMappings.length()").value(0));
    assertThat(contacts.count()).isZero();
}

@Test
void previewWithoutAPhoneHeaderStillWorks() throws Exception {
    preview(leadToken, "mobile,name\n9812345678,A\n").andExpect(status().isOk())
            .andExpect(jsonPath("$.suggested.phone").isEmpty())
            .andExpect(jsonPath("$.suggested.name").value("name"));
}

@Test
void shortRowsAreCutOrPaddedToTheHeader() throws Exception {
    preview(leadToken, "a,b,c\n1\n1,2,3,4\n").andExpect(status().isOk())
            .andExpect(jsonPath("$.rows").value(2))
            .andExpect(jsonPath("$.sample[0]").value(List.of("1", "", "")))
            .andExpect(jsonPath("$.sample[1]").value(List.of("1", "2", "3")));
}

@Test
void savedMappingsMatchBySortedTrimmedCaseInsensitiveSignatureAndSortMatchesFirst() throws Exception {
    saved("Exact", "Phone,Name", null);
    saved("Other", "mobile", null);
    saved("Recent", "phone,name", Instant.parse("2026-10-01T00:00:00Z"));
    preview(leadToken, "name , PHONE\n9812345678,A\n").andExpect(status().isOk())
            .andExpect(jsonPath("$.savedMappings.length()").value(3))
            .andExpect(jsonPath("$.savedMappings[0].name").value("Recent"))
            .andExpect(jsonPath("$.savedMappings[0].matches").value(true))
            .andExpect(jsonPath("$.savedMappings[1].name").value("Exact"))
            .andExpect(jsonPath("$.savedMappings[1].matches").value(true))
            .andExpect(jsonPath("$.savedMappings[2].name").value("Other"))
            .andExpect(jsonPath("$.savedMappings[2].matches").value(false));
}

@Test
void previewRejectsBadFilesAndOtherRoles() throws Exception {
    preview(leadToken, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
    byte[] big = new byte[5 * 1024 * 1024 + 1];
    java.util.Arrays.fill(big, (byte) 'a');
    mvc.perform(multipart(base(brandA) + "/contacts/import/preview")
            .file(new MockMultipartFile("file", "c.csv", "text/csv", big))
            .header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
    StringBuilder rows = new StringBuilder("phone\n");
    for (int i = 0; i < 20_001; i++) rows.append(String.format("9198%08d%n", i));
    preview(leadToken, rows.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
    mvc.perform(multipart(base(brandA) + "/contacts/import/preview").header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isBadRequest());
    preview(agentToken, "phone\n9812345678\n").andExpect(status().isForbidden());
    mvc.perform(multipart(base(brandA) + "/contacts/import/preview")
            .file(new MockMultipartFile("file", "c.csv", "text/csv", "phone\n1\n".getBytes()))
            .header("Authorization", "Bearer " + wmBToken)).andExpect(status().isNotFound());
    assertThat(contacts.count()).isZero();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT`
Expected: FAIL, 404/405 on the new path (and compilation errors for `Preview` if referenced).

- [ ] **Step 3: Implement** `parse`, `suggest`, `preview` and the controller method (`@PostMapping("/preview")`, same `@RequestPart("file")` as `upload`). The mapping service gets `List<SavedMappingRef> matching(String workspaceId, List<String> headers)` doing the ordering above.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT,ContactImportExportIT`
Expected: PASS; the existing import tests unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java
git commit -m "feat(contact): import preview with suggested and saved mappings"
```

---

### Task 3: Importing with a `mapping`

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportController.java`
- Test: `src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java`

**Interfaces:**
- Consumes: Task 2's `parse`, `suggest`; from the campaigns plan, `Phones.withDefaultCountryCode(String, String)`, `CampaignSettingsService.get(String workspaceId): CampaignSettings` (`getDefaultCountryCode()`) and `CodedException(HttpStatus, String code, String field, String message)`.
- Produces:
  - The two mapping errors are `new CodedException(HttpStatus.BAD_REQUEST, "MAPPING_COLUMN_REUSED", null, "A CSV column can be used only once.")` and `new CodedException(HttpStatus.BAD_REQUEST, "PHONE_COLUMN_REQUIRED", null, "Choose the column that holds the phone number.")`; no new exception type and no `ApiExceptionHandler` change.
  - `ContactImportService.run(String workspaceId, String customerId, byte[] file)` keeps its signature and behaviour (no country code, no label creation) and now delegates to a private core; new `public Result run(String workspaceId, String customerId, byte[] file, ImportMapping mapping)` where `mapping == null` means the legacy path. `Result` is unchanged; `unknownColumns` on the mapped path lists non-blank headers that no target uses.
  - `ContactImportController.upload` gains `@RequestPart(value = "mapping", required = false) String mapping`, parsed with the injected `tools.jackson.databind.ObjectMapper`; blank or absent means `null`; unparsable JSON throws `ValidationException("mapping", "is not valid JSON")`.

Rules of the mapped path (the implementer writes the code; these are the decisions):
- Validate before reading rows, in this order: `phone` blank or not among the headers → `PHONE_COLUMN_REQUIRED`; a header name used by two targets (phone, name, labels column, attribute columns; compared trimmed, case-insensitive, nulls and blanks ignored) → `MAPPING_COLUMN_REUSED`; a `defaultValue` longer than `MAX_ATTRIBUTE_VALUE` → `ValidationException("mapping", ...)`. A `name`, labels or attribute column that is not among the headers is treated as no column.
- A column name resolves to the first header equal to it, so a duplicate header's second column is unused (listed in `unknownColumns`, as today).
- Attribute rows whose `attributeId` is not an attribute of this brand are ignored; so are `labels.defaults` ids that are not labels of this brand; so is a repeated `attributeId` after its first row.
- Per row: phone = `Phones.withDefaultCountryCode(Phones.normalise(cell), code)` with `code = campaignSettings.get(workspaceId).getDefaultCountryCode()` read once per run, then the existing validity and name-length checks. An attribute value is the trimmed cell, else the trimmed `defaultValue`, else nothing. A default fills an attribute only where the contact has no value yet (new contacts get it; existing contacts keep theirs); a cell value overwrites as today. Labels are the column's names (split on `;` or `,`) plus `labels.defaults`.
- Label names missing from the brand: with `createMissingLabels` false they go to `unknownLabels` (today); with it true they are created (colour `blue`, matched by lower-case name, once per run, only for rows that pass validation); a name over 40 characters, or one whose creation loses a duplicate race and cannot be found again, goes to `unknownLabels` instead.
- The mapped path never touches `saveAs` (Task 4).

- [ ] **Step 1: Write the failing tests** in `ContactImportMappingIT` (`importWith(token, csv, Object mapping)` posts multipart `file` plus a `mapping` part, `new MockMultipartFile("mapping", "", "application/json", mapper.writeValueAsBytes(mapping))`; `m(...)` builds a mapping map; `@Autowired CampaignSettingsRepository settingsRepository` cleared with `deleteAll()` in `@BeforeEach` so every test starts on the default `"91"`):


```java
private Map<String, Object> m(String phone) {
    Map<String, Object> x = new java.util.LinkedHashMap<>();
    x.put("phone", phone);
    return x;
}

/** Sets the workspace's default country code through the settings endpoint (campaigns plan, `campaign:settings`). */
private void setCountryCode(String code) throws Exception {
    String url = base(brandA) + "/campaigns/settings";
    tools.jackson.databind.node.ObjectNode settings =
            (tools.jackson.databind.node.ObjectNode) json(send(get(url), wmToken, null).andExpect(status().isOk())).get("settings");
    settings.put("defaultCountryCode", code);
    send(put(url), wmToken, settings).andExpect(status().isOk());
}

private ResultActions importWith(String token, String csv, Object mapping) throws Exception {
    return mvc.perform(multipart(importUrl())
            .file(new MockMultipartFile("file", "c.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("mapping", "", "application/json", mapper.writeValueAsBytes(mapping)))
            .header("Authorization", "Bearer " + token));
}

@Test
void mappedColumnsAndDefaults() throws Exception {
    String csv = "Mobile,Full name,Tags,Town\n9812345678,Asha,VIP;Gold,\n9812345679,Bela,,Delhi\n";
    importWith(leadToken, csv, Map.of("phone", "Mobile", "name", "Full name",
            "labels", Map.of("column", "Tags", "defaults", List.of(lead.getId())),
            "attributes", List.of(Map.of("attributeId", city.getId(), "column", "Town", "defaultValue", "Pune"),
                    Map.of("attributeId", email.getId(), "defaultValue", "none@x.com"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(2))
            .andExpect(jsonPath("$.unknownLabels[0]").value("Gold"));
    Contact a = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
    assertThat(a.getName()).isEqualTo("Asha");
    assertThat(a.getLabels()).containsExactlyInAnyOrder(vip.getId(), lead.getId());
    assertThat(a.getAttributes().get(city.getId())).isEqualTo("Pune");          // blank cell -> default
    assertThat(a.getAttributes().get(email.getId())).isEqualTo("none@x.com");   // no column -> default
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345679").orElseThrow()
            .getAttributes().get(city.getId())).isEqualTo("Delhi");
}

@Test
void phoneOnlyMappingImportsLeads() throws Exception {
    importWith(leadToken, "p,name\n9812345678,Asha\n", m("p")).andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.unknownColumns[0]").value("name"));
    Contact c = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
    assertThat(c.getName()).isNull();
    assertThat(c.getLabels()).isEmpty();
    assertThat(c.getAttributes()).isEmpty();
    assertThat(c.getSource()).isEqualTo(ContactSource.IMPORT);
}

@Test
void tenDigitNumbersGetTheDefaultCountryCode() throws Exception {
    setCountryCode("44");
    importWith(leadToken, "phone\n7911123456\n+91 98123-45678\n123456789\n", m("phone"))
            .andExpect(jsonPath("$.created").value(3));
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "447911123456")).isPresent();
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678")).isPresent();
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "123456789")).isPresent();
}

@Test
void withoutAMappingTenDigitsAreLeftAsTyped() throws Exception {
    upload(leadToken, "phone\n9812345678\n").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "9812345678")).isPresent();
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678")).isEmpty();
}

@Test
void columnUsedTwiceIs400() throws Exception {
    importWith(leadToken, "phone,name\n9812345678,A\n", Map.of("phone", "phone", "name", "PHONE "))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAPPING_COLUMN_REUSED"))
            .andExpect(jsonPath("$.message").value("A CSV column can be used only once."));
    importWith(leadToken, "phone,tags\n9812345678,VIP\n", Map.of("phone", "phone",
            "labels", Map.of("column", "tags"),
            "attributes", List.of(Map.of("attributeId", city.getId(), "column", "Tags"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAPPING_COLUMN_REUSED"));
    assertThat(contacts.count()).isZero();
}

@Test
void phoneColumnRequired() throws Exception {
    for (Object phone : new Object[] {null, "", "Nope"}) {
        importWith(leadToken, "phone\n9812345678\n", m((String) phone)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHONE_COLUMN_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Choose the column that holds the phone number."));
    }
    assertThat(contacts.count()).isZero();
}

@Test
void blankCellsNeverOverwriteAndDefaultsOnlyFill() throws Exception {
    Contact old = contact(brandA, "Old", "919812345678");
    old.getLabels().add(lead.getId());
    old.getAttributes().put(city.getId(), "Delhi");
    contacts.save(old);
    Map<String, Object> mapping = Map.of("phone", "phone", "name", "name",
            "attributes", List.of(Map.of("attributeId", city.getId(), "column", "city", "defaultValue", "Pune")));
    importWith(leadToken, "phone,name,city\n919812345678,,\n919812345679,New,Jaipur\n919812345680,Other,\n", mapping)
            .andExpect(status().isOk()).andExpect(jsonPath("$.unchanged").value(1))
            .andExpect(jsonPath("$.created").value(2));
    Contact kept = contacts.findById(old.getId()).orElseThrow();
    assertThat(kept.getName()).isEqualTo("Old");
    assertThat(kept.getAttributes().get(city.getId())).isEqualTo("Delhi");   // default does not overwrite
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345679").orElseThrow()
            .getAttributes().get(city.getId())).isEqualTo("Jaipur");          // cell beats default
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345680").orElseThrow()
            .getAttributes().get(city.getId())).isEqualTo("Pune");            // blank cell, new contact -> default
}

@Test
void createMissingLabels() throws Exception {
    Map<String, Object> mapping = new java.util.LinkedHashMap<>(Map.of("phone", "phone",
            "labels", Map.of("column", "tags"), "createMissingLabels", true));
    importWith(leadToken, "phone,tags\n9812345678,\"VIP;Gold, gold\"\n", mapping).andExpect(status().isOk())
            .andExpect(jsonPath("$.unknownLabels.length()").value(0));
    ContactLabel gold = labels.findByWorkspaceIdAndNameKey(brandA.getId(), "gold").orElseThrow();
    assertThat(gold.getName()).isEqualTo("Gold");
    assertThat(gold.getColor()).isEqualTo("blue");
    assertThat(labels.findByWorkspaceIdOrderByNameKey(brandA.getId())).hasSize(3);
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow().getLabels())
            .containsExactlyInAnyOrder(vip.getId(), gold.getId());
    // key absent: nothing is created and the name is reported
    importWith(leadToken, "phone,tags\n9812345679,Silver\n", Map.of("phone", "phone", "labels", Map.of("column", "tags")))
            .andExpect(jsonPath("$.unknownLabels[0]").value("Silver"));
    assertThat(labels.findByWorkspaceIdOrderByNameKey(brandA.getId())).hasSize(3);
}

@Test
void overlongLabelIsReportedNotCreated() throws Exception {
    String longName = "L".repeat(41);
    importWith(leadToken, "phone,tags\n9812345678," + longName + "\n", Map.of("phone", "phone",
            "labels", Map.of("column", "tags"), "createMissingLabels", true))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.unknownLabels[0]").value(longName));
    assertThat(labels.findByWorkspaceIdOrderByNameKey(brandA.getId())).hasSize(2);
}

@Test
void mappingMatchesHeadersIgnoringCaseAndSpaces() throws Exception {
    importWith(leadToken, " Phone \n9812345678\n", m("phone")).andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));
    importWith(leadToken, "mobile\n9812345679\n", m("  MOBILE ")).andExpect(jsonPath("$.created").value(1));
}

@Test
void duplicateHeaderMapsToTheFirstColumn() throws Exception {
    importWith(leadToken, "phone,phone\n9812345678,111\n", m("phone")).andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.unknownColumns[0]").value("phone"));
    assertThat(contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678")).isPresent();
}

@Test
void idsOfAnotherBrandAreIgnored() throws Exception {
    ContactLabel theirLabel = label(brandB, "Secret");
    ContactAttribute theirAttribute = attribute(brandB, "Hidden");
    importWith(leadToken, "phone,h\n9812345678,x\n", Map.of("phone", "phone",
            "labels", Map.of("defaults", List.of(theirLabel.getId(), "deleted-id")),
            "attributes", List.of(Map.of("attributeId", theirAttribute.getId(), "column", "h"),
                    Map.of("attributeId", "deleted-id", "defaultValue", "x"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
    Contact c = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
    assertThat(c.getLabels()).isEmpty();
    assertThat(c.getAttributes()).isEmpty();
}

@Test
void mappingThatIsNotJsonIs400() throws Exception {
    mvc.perform(multipart(importUrl())
            .file(new MockMultipartFile("file", "c.csv", "text/csv", "phone\n9812345678\n".getBytes()))
            .file(new MockMultipartFile("mapping", "", "application/json", "{nope".getBytes()))
            .header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
            .andExpect(jsonPath("$.fields.mapping").exists());
    assertThat(contacts.count()).isZero();
}

@Test
void unmappedColumnsAreListed() throws Exception {
    importWith(leadToken, "phone,Shoe,\n9812345678,9,x\n", m("phone")).andExpect(status().isOk())
            .andExpect(jsonPath("$.unknownColumns.length()").value(1))
            .andExpect(jsonPath("$.unknownColumns[0]").value("Shoe"));
}
```

`@BeforeEach` of the class: create `vip`, `lead`, `city`, `email` as `ContactImportExportIT.data()` does, and clear `settingsRepository`. Reuse `importUrl()` and the String-based `upload(token, csv)` from `ContactImportExportIT` by copying them into this class.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT`
Expected: FAIL (the `mapping` part is ignored, so mapped columns are not applied).

- [ ] **Step 3: Implement** the `run` overload, the exception and its handler, and the controller part as specified under Interfaces and the rules. Reuse the existing `apply(...)` with one added parameter for the fill-only defaults rather than copying it.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT,ContactImportExportIT,AuthorityCheckScannerIT`
Expected: PASS, including every pre-existing import test.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java
git commit -m "feat(contact): import with a column mapping, defaults, label creation and country code"
```

---

### Task 4: `saveAs` and `lastUsedAt`

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportController.java` (pass `me.getUsername()`)
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactImportMappingService.java`
- Test: `src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java`

**Interfaces:**
- Consumes: Task 1's service and repository; Task 3's `run`.
- Produces:
  - `ContactImportService.run(String workspaceId, String customerId, String username, byte[] file, ImportMapping mapping)` replaces the Task 3 overload (update its callers); the 3-argument legacy `run` stays.
  - `ContactImportMappingService`: `void checkSaveAs(String workspaceId, String saveAs)` (blank or null is fine; trimmed length over 60 → `ValidationException("saveAs", "must be 1 to 60 characters")`; name taken → `DuplicateException("name", ...)`, same message as `create`) and `void afterImport(String workspaceId, String username, String saveAs, List<String> headers, ImportMapping mapping)`.
  - Flow: header and mapping validation and `checkSaveAs` run before the first row is imported (so a clash imports nothing); after the rows, `afterImport` saves a new mapping when `saveAs` is non-blank (headers = this file's headers, `lastUsedAt` = now from the injected `Clock`, a `DuplicateKeyException` from a race is swallowed), and sets `lastUsedAt = now` on every saved mapping of the brand whose `headerSignature` equals this file's signature and whose stored `mapping` equals `mapping.withoutSaveAs()`. The import result is unchanged.

- [ ] **Step 1: Write the failing tests** in `ContactImportMappingIT`:


```java
private String csv() { return "Mobile,Full name\n9812345678,Asha\n"; }

private Map<String, Object> withSaveAs(Map<String, Object> mapping, String name) {
    Map<String, Object> copy = new java.util.LinkedHashMap<>(mapping);
    copy.put("saveAs", name);
    return copy;
}

@Test
void saveAsStoresTheMappingAfterAnImport() throws Exception {
    importWith(leadToken, csv(), withSaveAs(Map.of("phone", "Mobile", "name", "Full name"), "Marketplace"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
    ContactImportMapping saved = savedMappings.findByWorkspaceIdAndNameKey(brandA.getId(), "marketplace").orElseThrow();
    assertThat(saved.getHeaderSignature()).containsExactly("full name", "mobile");
    assertThat(saved.getMapping().phone()).isEqualTo("Mobile");
    assertThat(saved.getMapping().saveAs()).isNull();
    assertThat(saved.getCreatedBy()).isEqualTo("lead");
    assertThat(saved.getLastUsedAt()).isNotNull();
}

@Test
void duplicateSaveAsFailsBeforeImporting() throws Exception {
    importWith(leadToken, csv(), withSaveAs(m("Mobile"), "Marketplace")).andExpect(status().isOk());
    contacts.deleteAll();
    importWith(leadToken, "Mobile\n9812345679\n", withSaveAs(m("Mobile"), "marketplace"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"))
            .andExpect(jsonPath("$.field").value("name"));
    assertThat(contacts.count()).isZero();
    assertThat(savedMappings.count()).isEqualTo(1);
}

@Test
void failedImportSavesNothing() throws Exception {
    importWith(leadToken, csv(), withSaveAs(m("Nope"), "A")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PHONE_COLUMN_REQUIRED"));
    importWith(leadToken, "", withSaveAs(m("Mobile"), "B")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.file").exists());
    assertThat(savedMappings.count()).isZero();
    assertThat(contacts.count()).isZero();
}

@Test
void blankSaveAsSavesNothing() throws Exception {
    importWith(leadToken, csv(), withSaveAs(m("Mobile"), "")).andExpect(status().isOk());
    importWith(leadToken, csv(), withSaveAs(m("Mobile"), "  ")).andExpect(status().isOk());
    assertThat(savedMappings.count()).isZero();
}

@Test
void overlongSaveAsIs400() throws Exception {
    importWith(leadToken, csv(), withSaveAs(m("Mobile"), "x".repeat(61))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.saveAs").exists());
    assertThat(contacts.count()).isZero();
    assertThat(savedMappings.count()).isZero();
}

@Test
void importingWithASavedMappingTouchesLastUsedAt() throws Exception {
    String mappings = base(brandA) + "/contacts/import-mappings";
    Map<String, Object> same = Map.of("phone", "Mobile", "name", "Full name");
    send(post(mappings), leadToken, Map.of("name", "Marketplace", "headers", List.of("Mobile", "Full name"),
            "mapping", same)).andExpect(status().isCreated()).andExpect(jsonPath("$.lastUsedAt").isEmpty());
    send(post(mappings), leadToken, Map.of("name", "Other", "headers", List.of("Mobile", "Full name"),
            "mapping", Map.of("phone", "Mobile"))).andExpect(status().isCreated());
    importWith(leadToken, csv(), same).andExpect(status().isOk());
    JsonNode list = json(send(get(mappings), leadToken, null).andExpect(status().isOk()));
    assertThat(list.get(0).get("name").asString()).isEqualTo("Marketplace");
    assertThat(list.get(0).get("lastUsedAt").isNull()).isFalse();   // same headers, same mapping
    assertThat(list.get(1).get("lastUsedAt").isNull()).isTrue();    // same headers, different mapping
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT`
Expected: new tests FAIL (nothing is saved).

- [ ] **Step 3: Implement** as specified under Interfaces.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ContactImportMappingIT,ContactImportMappingsIT`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `./mvnw -q test`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact/ContactImportMappingIT.java
git commit -m "feat(contact): save a mapping from the import request and track lastUsedAt"
```
