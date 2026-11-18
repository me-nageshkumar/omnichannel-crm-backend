# Knowledge bases and moving a number: backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** brands create OpenAI-backed knowledge bases (files, web pages, notes) from the console and use them in AI
profiles; a super admin moves a number to another brand of the same customer, its old chats kept in an archive.

**Architecture:** knowledge bases live in `omnichannel-ai` (`ai.knowledge`), with an optional provider interface
`KnowledgeStoreProvider` that `OpenAiProvider` implements; sources are processed on a MongoDB queue with leases (the
source row is the queue item), our copies in GridFS bucket `aiKnowledgeFiles`. The move lives in
`omnichannel-service` (`service.phone.move`), records in `phoneNumberMoves`, CRM records re-pointed through a port with
its adapter in `omnichannel-app`.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), MongoDB + GridFS, jsoup 1.17.2 (new, in
`omnichannel-ai`), JUnit 5 + AssertJ, embedded Mongo ITs.

**Spec:** `docs/superpowers/specs/2026-11-18-knowledge-bases-and-number-move-design.md` (sections §6 and §7; §8 is
the demo, built by hand after deploy, not in this plan).

## Global Constraints

- Collections camelCase: `aiKnowledgeBases`, `aiKnowledgeSources`, GridFS bucket `aiKnowledgeFiles`,
  `phoneNumberMoves`. Files in GridFS, never S3. Kept, no TTL.
- `omnichannel-service` imports only `ai.api.` from the AI module (`ServiceBoundaryTest`); `omnichannel-ai` imports
  only `ai.`, `common.`, `security.access.`, `security.auth.` (`AiModuleBoundaryTest`); `omnichannel-crm` is reached
  only through `crm.platform` ports with adapters in `app.crm`.
- Keys never in a response or a log line (existing redaction in `OpenAiProvider.redact`).
- Uploads: up to 16 MB (`spring.servlet.multipart.max-file-size`, unchanged). File types PDF, DOCX, PPTX, TXT, MD,
  HTML. Web pages: https only, through `HttpAddressGuard`, 10 s, 2 MB, at most 3 redirects each re-checked. Notes up
  to 50,000 characters. At most 200 sources per knowledge base, 20 knowledge bases per brand.
- Source states on the wire: `QUEUED | UPLOADING | INDEXING | READY | FAILED | REMOVING`; READY only after OpenAI
  reports `completed`. Indexing gives up after 30 min; transient errors retried up to 3 attempts.
- Authorities: `ai:view` to read, `ai:manage` to change (existing); the move: `phone-number:manage-all`.
- Archive label: `"<label> (until <d MMM>)"`, e.g. `Main line (until 18 Nov)`, date in Asia/Kolkata.
- Test first. Run only the tests that matter while working; the full `./mvnw -q verify` once before the merge.
  Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A source whose OpenAI file finished `failed` (or `cancelled`) must end FAILED with OpenAI's `last_error.message`,
   never sit in INDEXING forever: pinned in Task 3 (`indexingFailedEndsFailedWithReason`).
2. A web page that redirects to `http://127.0.0.1/…` or a private address must be refused at the hop, not after the
   fetch: pinned in Task 3 (`redirectToPrivateAddressIsRefused`).
3. Deleting a knowledge base whose OpenAI store is already gone (deleted on platform.openai.com) must still succeed:
   pinned in Task 1 (`deleteMissingStoreCountsAsDone`) and Task 2 (`deleteKnowledgeBaseWhoseStoreIsGone`).
4. After a move, an ack for a message sent before the move must still update that message under the archive id:
   pinned in Task 6 (`ackForMessageSentBeforeMoveUpdatesArchivedMessage`).
5. A move that stops after re-pointing (crash) must finish on Retry without duplicating the archive or losing chats:
   pinned in Task 5 (`retryAfterStopAtEachStepFinishesTheMove`).

---

## Part 1: knowledge bases

### Task 1: OpenAI knowledge calls behind an optional provider interface

**Files:**
- Create: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/provider/KnowledgeStoreProvider.java`
- Create: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/provider/KnowledgeFileState.java`
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/provider/openai/OpenAiProvider.java` (implements it; a
  multipart send beside `send`)
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/provider/openai/OpenAiReplies.java` (strip citation
  markers from output text)
- Modify: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/connection/AiConnectionViews.java` (`ProviderView`
  gains `boolean knowledgeBases`)
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/provider/openai/OpenAiKnowledgeTest.java`,
  `OpenAiRepliesTest.java` (one new test)

**Interfaces:**
- Produces:
  ```java
  public interface KnowledgeStoreProvider {                    // ai.provider; implemented only by providers that can
      String createStore(AiCredential c, String name);        // returns the provider's store id
      void deleteStore(AiCredential c, String storeId);       // 404 → returns normally
      String uploadFile(AiCredential c, String fileName, String contentType, byte[] bytes);   // returns file id
      void attach(AiCredential c, String storeId, String fileId);
      KnowledgeFileState fileState(AiCredential c, String storeId, String fileId);
      void detach(AiCredential c, String storeId, String fileId);   // 404 → returns normally
      void deleteFile(AiCredential c, String fileId);               // 404 → returns normally
  }
  public record KnowledgeFileState(Kind kind, String error) { public enum Kind { INDEXING, READY, FAILED } }
  ```
  `ProviderView.knowledgeBases` = `provider instanceof KnowledgeStoreProvider`.
- Ruling for the executor: the spec's knowledge-base status `CREATING` is not used, since the store is created
  synchronously before the row is saved (statuses READY | FAILED).
- Ruling for the executor: the spec's `AiCapabilities.knowledgeBases` flag is carried as `instanceof` plus the view
  field instead, so the six existing `new AiCapabilities(...)` call sites stay unchanged.

- [ ] **Step 1: Write the failing tests** in `OpenAiKnowledgeTest` (same local `HttpServer` harness as
  `OpenAiProviderTest`):
  - `createStorePostsNameAndReturnsId`: reply `{"id":"vs_1"}` → `"vs_1"`; path `/v1/vector_stores`, body
    `{"name":"Bimal courses"}`.
  - `uploadFileSendsMultipartWithPurposeAssistants`: reply `{"id":"file_1"}`; request `Content-Type` starts
    `multipart/form-data; boundary=`; body contains `name="purpose"`, `assistants`, `filename="CFTP.pdf"`,
    `Content-Type: application/pdf` and the bytes; path `/v1/files`.
  - `attachPostsFileId`: path `/v1/vector_stores/vs_1/files`, body `{"file_id":"file_1"}`.
  - `fileStateMapsStatuses`: `in_progress` → INDEXING; `completed` → READY; `failed` with
    `"last_error":{"code":"unsupported_file","message":"File type not supported"}` → FAILED, error
    `"File type not supported"`; `cancelled` → FAILED, error `"Cancelled"`.
  - `deleteMissingStoreCountsAsDone`: 404 on `DELETE /v1/vector_stores/vs_gone` returns normally; same for
    `detach` and `deleteFile`.
  - `knowledgeErrorsRedactTheKey`: a 400 whose message contains the key → `AiProviderException` message has `«key»`.
  - In `OpenAiRepliesTest`: `citationMarkersAreStripped`: output text `"Fees are ₹19,999【4:0†fees.md】."` →
    `"Fees are ₹19,999."`.
- [ ] **Step 2: Run them, expect failures** —
  `./mvnw -q -pl omnichannel-ai -am test -Dtest='OpenAiKnowledgeTest,OpenAiRepliesTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure (no `KnowledgeStoreProvider`).
- [ ] **Step 3: Implement.** `OpenAiProvider implements AiProvider, KnowledgeStoreProvider`; a private
  `sendMultipart(c, path, fileName, contentType, bytes)` builds the body by hand (`purpose` field, then `file` part)
  with a random boundary on the same `RestClient`, sharing the error mapping of `send`. A 404 on the three deletes is
  swallowed. `OpenAiReplies` removes `【[^】]*】` from output text. `ProviderView.of` fills `knowledgeBases`.
- [ ] **Step 4: Run the tests again** — same command. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(ai): OpenAI vector-store and file calls behind KnowledgeStoreProvider; citation markers stripped`

### Task 2: Knowledge bases and sources: data, service and API

**Files:**
- Create in `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/knowledge/`: `KnowledgeBase.java`,
  `KnowledgeBaseRepository.java`, `KnowledgeSource.java`, `KnowledgeSourceRepository.java`, `SourceKind.java`
  (`FILE, WEB_PAGE, NOTE`), `SourceState.java` (`QUEUED, UPLOADING, INDEXING, READY, FAILED, REMOVING`),
  `KnowledgeBaseStatus.java` (`READY, FAILED`), `KnowledgeFilesConfig.java` (bucket `aiKnowledgeFiles`, bean
  `aiKnowledgeFilesGridFs`), `KnowledgeService.java`, `KnowledgeViews.java`, `KnowledgeController.java`
- Modify: `omnichannel-ai/pom.xml` (jsoup 1.17.2, used in Task 3; added here so the module compiles once)
- Modify: `AiConnectionService.delete` (409 `ai_connection_in_use` also when a knowledge base uses it: message
  `"Used by knowledge bases: <names>"`), `AiProfileRepository` (`findByWorkspaceIdAndVectorStoreIdsContaining`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/AiKnowledgeApiIT.java` (extends `AiTestBase`; add
  the two classes to `collections()` and remove bucket `aiKnowledgeFiles` in a `@BeforeEach`)

**Interfaces:**
- Consumes: Task 1's `KnowledgeStoreProvider`; `AiConnectionService.credential(ws, id)`,
  `AiConnectionService.markInvalid(ws, id, message)`, `AiProviders.of(id)`.
- Produces (wire, used by the console plan):
  ```
  GET    /api/workspaces/{ws}/ai/knowledge-bases                     ai:view  → [KnowledgeBaseView]
  POST   /api/workspaces/{ws}/ai/knowledge-bases  {name, description, connectionId}   ai:manage → 201 KnowledgeBaseView
  GET    /{id}  ai:view;  PUT /{id} {name, description, version} ai:manage;  DELETE /{id} ai:manage → 204
  GET    /{id}/sources                               ai:view  → [SourceView]
  POST   /{id}/sources/file   multipart "file"       ai:manage → 201 SourceView
  POST   /{id}/sources/web-page {url}                ai:manage → 201 SourceView
  POST   /{id}/sources/note {title, text}            ai:manage → 201 SourceView
  PUT    /{id}/sources/{sid} {title?, text?, action?: "FETCH_AGAIN"|"RETRY"}   ai:manage → SourceView
  DELETE /{id}/sources/{sid}                         ai:manage → 202 SourceView (state REMOVING)
  GET    /{id}/sources/{sid}/content                 ai:view  → our copy (file bytes, or text/markdown)
  KnowledgeBaseView(id, name, description, connectionId, connectionName, provider, providerStoreId, status, error,
      counts {queued, adding, ready, failed}, sourceCount, usedBy [profile names], createdAt, updatedAt, version)
      // "adding" = UPLOADING + INDEXING; REMOVING rows are not counted
  SourceView(id, kind, title, url, fileName, contentType, size, state, error, addedAt, addedBy, fetchedAt, version)
  ```
  Error codes: `ai_knowledge_in_use` (409, message `"Used by profiles: <names>"`), `ai_knowledge_no_store_support`
  (400, the connection's provider has no `KnowledgeStoreProvider`), `ai_knowledge_limit` (409, `"At most 20
  knowledge bases"` / `"At most 200 sources"`), `ai_knowledge_file_type` (400, `"PDF, DOCX, PPTX, TXT, MD or HTML
  only"`), validation errors through `ValidationException` (fields `name`, `url`, `title`, `text`), duplicate name
  through `DuplicateException`, provider failure on create as `ai_provider_error` / `ai_provider_refused_key` (502)
  like `AiConnectionService.call`.
- Produces (Java, for Task 3): `KnowledgeSourceRepository`, `KnowledgeService.wake()` hook — a
  `Runnable`-style callback the worker registers so new or changed sources are picked up at once.

- [ ] **Step 1: Write the failing ITs** in `AiKnowledgeApiIT` (OpenAI is `OPENAI`, the stub; a connection is created
  through the API first, as in `AiConnectionsApiIT`):
  - `createKnowledgeBaseCreatesTheStoreAtOpenAi`: enqueue `{"id":"vs_1"}`; POST → 201, `providerStoreId` `"vs_1"`,
    `status` `"READY"`, counts all 0; OpenAI got `POST /v1/vector_stores` with the name.
  - `createRefusedWhenOpenAiRefuses`: enqueue 401 → 502 `ai_provider_refused_key`; nothing stored.
  - `addNoteQueuesASource`: POST note → 201 `state` `"QUEUED"`, `kind` `"NOTE"`, size = UTF-8 bytes of the Markdown;
    GridFS holds `"# Counsellor pitch\n\n<text>"`.
  - `addFileChecksType`: a `.pdf` → 201 QUEUED with `fileName`, `contentType` `application/pdf`; a `.exe` → 400
    `ai_knowledge_file_type`.
  - `addWebPageNeedsHttps`: `http://bimalinstitute.com` → 400 field `url`; `https://bimalinstitute.com/` → 201
    QUEUED, `title` = the url until fetched.
  - `deleteKnowledgeBaseUsedByProfile409`: a profile with `vectorStoreIds ["vs_1"]` → 409 `ai_knowledge_in_use`,
    message names the profile.
  - `deleteKnowledgeBaseWhoseStoreIsGone`: enqueue 404 for the store delete → 204; rows and GridFS files gone.
  - `deleteConnectionHoldingKnowledgeBase409`.
  - `listShowsCountsAndUsedBy`: two READY, one FAILED, one INDEXING (set in Mongo) → counts {0,1,2,1}, `usedBy`.
  - `viewerCannotChange`: a user with `ai:view` only → 403 on POST; 200 on GET.
  - `limitsEnforced`: the 21st knowledge base and the 201st source → 409 `ai_knowledge_limit`.
  - `noKeyInResponses`: no response body of this IT contains the connection's key.
- [ ] **Step 2: Run, expect failures** —
  `./mvnw -q -pl omnichannel-app -am test -Dtest=AiKnowledgeApiIT -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: compilation failure / 404s.
- [ ] **Step 3: Implement** the documents (indexes `{workspaceId, name}` unique on `aiKnowledgeBases`;
  `{state, leaseUntil}` and `{knowledgeBaseId}` on `aiKnowledgeSources`; `@Version` on both), the service and
  controller (same `@PreAuthorize("@access.has(#workspaceId, 'ai:…')")` style as `AiConnectionController`).
  Deleting a knowledge base: profile check → delete the store at the provider (404 is done) → delete its sources'
  OpenAI files best effort (failures logged without the key) → delete GridFS copies and rows. Removing a source sets
  REMOVING and wakes the worker. Notes and web pages store Markdown in GridFS with content type `text/markdown`.
- [ ] **Step 4: Run again** — same command. Expected: PASS. Also `AiConnectionsApiIT` and `AiModuleBoundaryTest`.
- [ ] **Step 5: Commit** — `feat(ai): knowledge bases with file, web-page and note sources (API, GridFS copies)`

### Task 3: The knowledge worker and the web-page reader

**Files:**
- Create in `ai/knowledge/`: `KnowledgeWorker.java`, `KnowledgeQueue.java` (claims on `aiKnowledgeSources`),
  `WebPageReader.java` (fetch through `HttpAddressGuard` + jsoup to Markdown), `KnowledgeSchedule` (inner
  `@Component` gated by `omnichannel.ai.auto-tick`, ticking every `omnichannel.ai.knowledge.tick`, default 5s)
- Modify: `AiProperties` (nested `Knowledge(@DefaultValue("5s") Duration tick, @DefaultValue("2") int poolSize,
  @DefaultValue("30m") Duration giveUpAfter, @DefaultValue("3") int attempts, @DefaultValue("2m") Duration lease)`)
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/knowledge/WebPageReaderTest.java` (+ fixture
  `src/test/resources/knowledge/course-page.html`, a hand-written page with nav, script, headings, a fee table,
  footer), `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/AiKnowledgeWorkerIT.java`

**Interfaces:**
- Consumes: Task 1 provider calls, Task 2 repositories and `wake()`.
- Produces: `KnowledgeWorker.tick(): CompletableFuture<Void>` (tests tick by hand, as `AiTurnWorker.tick`);
  `WebPageReader.read(URI url): Page(String title, String markdown, URI finalUrl)`.

The claim (one `findAndModify`, `leaseUntil = now + lease`, `attempts + 1`, newest work first by `addedAt`) takes a
row that is QUEUED or UPLOADING with an expired or absent lease, INDEXING with `nextCheckAt <= now` and an expired or
absent lease, or REMOVING with an expired or absent lease. Per state:

| Claimed in | Work | Next |
|---|---|---|
| QUEUED / UPLOADING | WEB_PAGE: fetch → Markdown → GridFS (replacing the old copy), `title` = page title, `fetchedAt`; then upload our copy; attach | INDEXING, `nextCheckAt = now + 2s`, `indexingSince = now`, attempts reset |
| INDEXING | `fileState` | READY; FAILED with the provider's error; or INDEXING with `nextCheckAt` doubling 2 s → 30 s; FAILED `"OpenAI did not finish indexing in 30 minutes"` after `giveUpAfter` from `indexingSince` |
| REMOVING | detach, deleteFile, delete GridFS copy, delete row | gone |
| FETCH_AGAIN / RETRY / note edit (API) | detach + deleteFile of the old provider file (best effort), clear `providerFileId` | QUEUED |

Transient errors (`RATE_LIMIT`, `UNAVAILABLE`, `TIMEOUT`) leave the row for the next claim until `attempts` reaches 3,
then FAILED with the message; `AUTH` → FAILED `"The provider refused the key"` and
`AiConnectionService.markInvalid`; `BAD_REQUEST` → FAILED with the message. A web fetch failure → FAILED with
`"The page answered <status>"`, `"The page could not be reached"`, `"The page is larger than 2 MB"`, `"Only
https addresses"` or the guard's refusal `"This address is not allowed"`.

- [ ] **Step 1: Write the failing tests.**
  `WebPageReaderTest` (a local `HttpServer`, guard built with `new HttpAddressGuard(true)` for the happy path):
  - `pageBecomesMarkdownWithoutNavScriptsOrFooter`: the fixture → markdown starts with
    `"Source: <url>\nFetched: "`, has `"# Crypto & Forex Trading Program"`, `"- Zoom Sessions"`, a Markdown table row
    `"| Online | ₹19,999 |"`, and contains none of the nav text, script text or footer text.
  - `redirectToPrivateAddressIsRefused`: guard `new HttpAddressGuard(false)`, a public-looking first hop is not
    possible locally, so test `read` with a `URI` of `https://127.0.0.1:<port>/` → `"This address is not allowed"`;
    and a redirect chain test with the guard allowing private but a 4th redirect → `"Too many redirects"`.
  - `largerThan2MbRefused`, `nonHtmlTextIsKeptAsIs` (`text/plain` body → the text under the header).
  `AiKnowledgeWorkerIT` (`omnichannel.ai.auto-tick=false`, tick by hand; OpenAI stub queue):
  - `noteGoesQueuedToReady`: enqueue file `{"id":"file_1"}`, attach `{}`, state `in_progress`, then `completed`;
    tick → INDEXING; advance the clock 2 s, tick → INDEXING; 4 s, tick → READY. OpenAI saw `POST /v1/files`,
    `POST /v1/vector_stores/vs_1/files`, two `GET .../files/file_1`.
  - `indexingFailedEndsFailedWithReason`: status `failed` with `last_error.message` `"File type not supported"` →
    FAILED with that error.
  - `givesUpAfter30Minutes`.
  - `transientErrorRetriesThenFails`: three 503s → FAILED after the third claim; a 503 then success → READY path.
  - `authFailureMarksConnectionInvalid`.
  - `removingDeletesEverywhere`: REMOVING → OpenAI saw the detach and file delete; row and GridFS copy gone.
  - `expiredLeaseIsClaimedAgain`: a row UPLOADING with `leaseUntil` in the past is processed.
  - `fetchAgainReplacesTheProviderFile`: PUT action FETCH_AGAIN on a READY web page → old file detached and deleted,
    new upload, READY.
- [ ] **Step 2: Run, expect failures** —
  `./mvnw -q -pl omnichannel-app -am test -Dtest='WebPageReaderTest,AiKnowledgeWorkerIT' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** The worker runs claimed rows on its own pool (`aiKnowledgeExecutor`, size from
  properties) so a slow upload never holds the AI turn pool; `wake()` triggers a tick. `WebPageReader` uses the JDK
  `HttpClient` with redirects off and follows up to 3 `Location`s by hand, calling `guard.check` before every hop;
  jsoup drops `script, style, noscript, nav, footer, header nav, form, iframe, svg`, keeps `h1–h4` as `#` headings,
  `li` as `- `, `table` as Markdown rows, `p` as paragraphs, `a` as text; collapses blank lines.
- [ ] **Step 4: Run again** — same command. Expected: PASS. Then
  `./mvnw -q -pl omnichannel-app -am test -Dtest='Ai*IT,*Knowledge*' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 5: Commit** — `feat(ai): knowledge worker on a leased queue; web pages read through the address guard`


## Part 2: moving a number

Facts from the code survey (2026-11-18): chats are unique on `{phoneNumberId, remoteId}` and messages on
`{phoneNumberId, waMessageId}`; inbox scope lists the brand's numbers with `findByWorkspaceIdOrderByNumber` /
`findByWorkspaceIdAndTeamIdIn` and does not filter disabled numbers; the device principal keeps the workspaceId read at
connect; the extension reconnects with backoff on any close code it does not know, waits 5 minutes after 4403 and
stops for good after 4410 or two 4401s.

### Task 4: Archive numbers and a number that is moving

**Files:**
- Modify: `omnichannel-db/.../db/phone/PhoneNumber.java` (fields `String archiveOf` sparse-indexed, `Instant
  archivedAt`, `Instant movingSince`, `Instant movedInAt`; `number` no longer `@Indexed(unique = true)`)
- Create: `omnichannel-db/.../db/phone/PhoneNumberIndexMigration.java` (`ApplicationRunner`, `@Order(5)`, as
  `InboxSettingsMigration.ensureIndex`: drop the old unique `number` index if present, create `number_unique`
  unique with `partialFilterExpression {number: {$type: "string"}}`)
- Modify: `PhoneNumberLimitCounter` (count only `archiveOf` absent), `DeviceKeys` (`usable` false while
  `movingSince != null` or `archiveOf != null`; new `boolean isMovingKey(String deviceKey)`),
  `BridgeWebSocketHandler` (a moving key closes with `CLOSE_MOVING = 4503`, reason `"number is moving"`, checked
  before `isDisabledKey`), `DeviceAccessChanged.Reason` (+ `MOVED`), `DeviceDisconnector` (MOVED → 4503),
  `InboxDevices.requireReady` (archive → `NoDeviceException("NUMBER_ARCHIVED", "This number moved to another brand
  on <d MMM>.")`), `PhoneNumberStatus.Status` (+ `ARCHIVED`, computed first for archives), `PhoneNumberView` (+
  `String archiveOf`, `Instant archivedAt`), `PhoneNumberService` (enable, connect code, revoke device and set team
  on an archive → 409 `number_archived`; relabel and delete stay)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/phone/ArchivedNumberIT.java`,
  `omnichannel-db` or app IT for the migration: `PhoneNumberIndexMigrationIT`

**Interfaces:**
- Produces: the fields above; `DeviceAccessChanged.Reason.MOVED`; `BridgeWebSocketHandler.CLOSE_MOVING = 4503`;
  wire status `"ARCHIVED"` and code `NUMBER_ARCHIVED` (console plan); `PhoneNumberRepository.findByArchiveOf(String)`.
- Ruling for the executor: the spec says "device authentication refuses" a moving number; a plain refusal is 4401,
  after two of which the extension stops for good, so a moving key closes with 4503 instead (generic reconnect with
  backoff; the extension is unchanged).

- [ ] **Step 1: Write the failing tests.** `ArchivedNumberIT`:
  - `archiveDoesNotCountTowardLimit`: customer limit 1 and one archive of that customer → creating a real number
    succeeds; a second real number → refused with the limit.
  - `twoArchivesWithoutNumberAreAllowed` (insert two `PhoneNumber` with `number` null → no duplicate key).
  - `archiveViewSaysArchived`: brand list returns `status` `"ARCHIVED"`, `archiveOf`.
  - `sendOnArchiveRefused`: inbox send on an archive chat → 409 code `NUMBER_ARCHIVED`.
  - `archiveRefusesEnableConnectCodeAndTeam` → 409 `number_archived` each.
  - `movingKeyClosesWith4503` (`TestDevices`: a connected device; set `movingSince`; reconnect → close 4503).
  `PhoneNumberIndexMigrationIT`: an old unique index on `number` is replaced by `number_unique` (partial).
- [ ] **Step 2: Run, expect failures** —
  `./mvnw -q -pl omnichannel-app -am test -Dtest='ArchivedNumberIT,PhoneNumberIndexMigrationIT' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** as listed in Files.
- [ ] **Step 4: Run again** — same command, then `PhoneNumbersApiIT` and the device ITs
  (`-Dtest='PhoneNumber*IT,*Device*IT,BridgeWebSocket*IT'`). Expected: PASS.
- [ ] **Step 5: Commit** — `feat(phone): archive numbers and a moving state the device sees as "try again"`

### Task 5: The move service

**Files:**
- Create in `omnichannel-service/.../service/phone/move/`: `PhoneNumberMove.java` (`@Document("phoneNumberMoves")`:
  id, customerId, phoneNumberId, fromWorkspaceId, toWorkspaceId, sourceArchiveId, mergedArchiveId, step
  (`STARTED, OFFLINE, RUNS_ENDED, ARCHIVED, MERGED, DONE`), counts {chats, messages, runsEnded}, startedAt,
  finishedAt, byUserId, error, @Version), `PhoneNumberMoveRepository.java`, `PhoneNumberMoveService.java`,
  `NumberRecords.java` (the re-point list), `CrmNumberRecords.java` (port), `MovePreview.java`
- Create: `omnichannel-app/.../app/crm/CrmNumberRecordsAdapter.java` (re-points `crmContacts.channel.phoneNumberId`,
  `crmContacts.origin.phoneNumberId`, `crmActivities`, `crmNotes`, `crmTasks` `phoneNumberId`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/phone/PhoneNumberMoveIT.java`,
  `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/phone/NumberRecordsCoverageTest.java`

**Interfaces:**
- Consumes: Task 4 fields and `Reason.MOVED`; `ChatbotRunService.stop(run, reason, byUserId)`;
  `HistoryImportService.cancel(ws, id)`; `PhoneNumberService.require(id)`.
- Produces:
  ```java
  public PhoneNumberMove move(String phoneNumberId, String toWorkspaceId, AuthenticatedUser me);
  public PhoneNumberMove retry(String phoneNumberId, AuthenticatedUser me);   // resumes the last unfinished move
  public Optional<PhoneNumberMove> last(String phoneNumberId);
  public MovePreview preview(String phoneNumberId, String toWorkspaceId);
  record MovePreview(String fromWorkspaceName, String toWorkspaceName, long chats, long activeRuns, String teamName,
      String archiveLabel, boolean movingBack, long returningChats, List<String> rulesNamingNumber) {}
  public interface CrmNumberRecords { void repoint(String fromPhoneNumberId, String toPhoneNumberId); }
  ```
  `NumberRecords.repoint(from, to)` updates, with `updateMulti`: `waWebChats`, `waWebMessages`, `waWebChatActions`
  (by the chats' ids: no number field, nothing to do; listed as LEFT), `waWebHistoryImports`,
  `waWebHistoryImportChats`, `chatbotRuns`, `messagePages`, `campaignSenders`, `campaigns.senderIds` (array
  element), `campaignRecipients.senderId` and `.preferredSenderId`, `channelWebhookEvents` (its number field),
  GridFS `chatMedia.files` `metadata.phoneNumberId`, then `CrmNumberRecords.repoint`.
  Left as is, with the reason in `NumberRecordsCoverageTest`: `phoneNumbers` (the entity), `connectCodes` (belong to
  the device), `waNumberLookups` (per brand), `inboxSettings` (legacy field, read only by its migration).

Steps of `move` (each safe to run again; the record's `step` says where to resume; one unfinished move per number,
a second `move` → 409 `number_move_unfinished`):

1. Refuse: target brand of another customer → 400 `number_move_other_customer`; same brand → 400
   `number_move_same_brand`; an archive → 409 `number_archived`. Save the record (STARTED). Set `movingSince`,
   publish `DeviceAccessChanged(..., MOVED)` → OFFLINE.
2. Stop the number's RUNNING/WAITING runs with reason `"number moved"`; then set the number's chats still in BOT
   status to OPEN with assignment cleared and `chatbot` unset; cancel its QUEUED/RUNNING/PAUSED history imports →
   RUNS_ENDED.
3. Create the source archive once (reuse `sourceArchiveId` if set): label `"<label> (until <d MMM>)"` in
   Asia/Kolkata, `archiveOf` = number id, `archivedAt`, customerId, the source workspaceId, the number's `teamId`,
   `number` null, `disabled` set (level PLATFORM, reason "Archived"), no device fields; then
   `NumberRecords.repoint(realId, archiveId)` → ARCHIVED.
4. If the target brand holds an archive of this number (`findByArchiveOf` filtered by workspaceId):
   `NumberRecords.repoint(archiveId, realId)` and delete that archive; store `mergedArchiveId` → MERGED.
5. Number: `workspaceId` = target, `teamId` null, `movedInAt` = now, `movingSince` null → DONE, `finishedAt`.

An exception in any step stores `error` and leaves `movingSince` set (the phone keeps retrying with 4503 until Retry
finishes the move) **(the spec's Retry)**.

- [ ] **Step 1: Write the failing tests.** `PhoneNumberMoveIT` (a customer with brands A and B, a connected Main
  line in A with team Sales, chats from contacts Veenu and Ravi with messages, one WAITING chatbot run, a CRM contact
  on the number, a campaign sender):
  - `moveKeepsOldChatsInAnArchiveOfTheSourceBrand`: after `move` → A's chats and messages carry the archive id; the
    archive is labelled `"Main line (until 18 Nov)"` (clock fixed 2026-11-18), team Sales, status ARCHIVED; the
    number is in B with no team and `movedInAt`; the run is STOPPED with `"number moved"`; the CRM contact's channel
    points at the archive; the campaign sender too; counts {chats 2, messages n, runsEnded 1}.
  - `newMessageAfterMoveOpensAChatInTheTargetBrand`: an incoming message from Veenu after the move → a new chat in B
    (new contact in B), while A still lists Veenu's old chat under the archive.
  - `movingBackMergesTheArchive`: move A→B, a message in B, move B→A → A's old chats are back on the real id, A has no
    archive of the number, B has `"Main line (until <date>)"` holding B's chat.
  - `refusedAcrossCustomersAndSameBrandAndArchives`.
  - `secondMoveWhileUnfinished409`.
  - `retryAfterStopAtEachStepFinishesTheMove`: for each step S in OFFLINE..MERGED, make `NumberRecords` (a spy) throw
    once right after S; `retry` → DONE, exactly one archive in A, every chat present once.
  - `deviceIsClosedWithMovedAndReconnectsIntoTheTargetBrand` (`TestDevices`): the session closes 4503; a reconnect
    after DONE has a principal with B's workspaceId.
  - `previewCounts`: chats 2, activeRuns 1, teamName "Sales", archiveLabel, movingBack false, a rule in A whose
    condition names the number is listed by name.
  `NumberRecordsCoverageTest`: scans `@Document` classes under `me.nageshk.omnichannel` for a field named
  `phoneNumberId` (also inside nested types such as `Channel`) and fails unless each collection is in
  `NumberRecords.REPOINTED` or `NumberRecords.LEFT` (a map collection → reason).
- [ ] **Step 2: Run, expect failures** —
  `./mvnw -q -pl omnichannel-app -am test -Dtest='PhoneNumberMoveIT,NumberRecordsCoverageTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** the service, the records list and the CRM adapter (a package-private `@Component` in
  `app.crm`, as `CampaignObserverAdapter`).
- [ ] **Step 4: Run again** — same command; then the inbox, chatbot, campaign and CRM ITs that touch numbers
  (`-Dtest='Inbox*IT,Chatbot*IT,Campaign*IT,Crm*IT'`). Expected: PASS.
- [ ] **Step 5: Commit** — `feat(phone): move a number to another brand, its old records kept on an archive`

### Task 6: After the move: acks, history imports and the platform API

**Files:**
- Modify: `InboxIngestService.storeStatus` and `storeVote` (a miss on the number's id looks in its archives:
  `PhoneNumberRepository.findByArchiveOf(id)` ids → `WaWebMessageRepository.findFirstByPhoneNumberIdInAndWaMessageId`)
- Modify: `HistoryImportRunner` / `ImportContext` (a `notBefore` instant = the number's `movedInAt`; `from` is clamped
  to it; `FetchedMessageImporter.importMessage` drops older messages; a chat with no message left is SKIPPED)
- Modify: `omnichannel-app/.../app/phone/PlatformPhoneNumberController.java`:
  ```
  GET  /api/phone-numbers/{id}/move/preview?workspaceId=   phone-number:manage-all → MovePreview
  POST /api/phone-numbers/{id}/move {workspaceId}          phone-number:manage-all → PhoneNumberMoveView
  GET  /api/phone-numbers/{id}/move                        phone-number:view-all   → PhoneNumberMoveView | 404
  POST /api/phone-numbers/{id}/move/retry                  phone-number:manage-all → PhoneNumberMoveView
  PhoneNumberMoveView(id, phoneNumberId, fromWorkspaceId, fromWorkspaceName, toWorkspaceId, toWorkspaceName, step,
      done, error, counts, startedAt, finishedAt)
  ```
  The search view (`PhoneNumberView`) gains `Boolean moveUnfinished` (true when the last move has no `finishedAt`).
- Test: `PhoneNumberMoveApiIT.java`, additions to `PhoneNumberMoveIT`, a history-import IT
  (`HistoryImportAfterMoveIT`, on `HistoryImportTestBase`)

**Interfaces:**
- Consumes: Task 5 service and views.
- Produces: the endpoints above (console plan).

- [ ] **Step 1: Write the failing tests.**
  - `ackForMessageSentBeforeMoveUpdatesArchivedMessage`: a message sent from A before the move; after the move an ack
    (`read`) arrives on the number → that archived message's status is READ.
  - `historyImportAfterMoveTakesOnlyNewMessages`: phone history holds Ravi's chat (messages before the move only) and
    Veenu's (one before, one after) → B gets Veenu's later message only; Ravi's chat SKIPPED.
  - `PhoneNumberMoveApiIT`: `superAdminMovesAndSeesTheRecord`; `managerOfTheBrandCannotMove` (403);
    `accountManagerWithViewAllSeesButCannotMove`; `previewNeedsWorkspaceOfSameCustomer` (400);
    `searchShowsMoveUnfinished`.
- [ ] **Step 2: Run, expect failures** —
  `./mvnw -q -pl omnichannel-app -am test -Dtest='PhoneNumberMove*IT,HistoryImportAfterMoveIT' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run again**, then the full `./mvnw -q verify` (once, before the merge; write the exit code to a file
  and read it). Expected: PASS, exit 0.
- [ ] **Step 5: Commit** — `feat(phone): acks and imports after a move; the platform move API`

