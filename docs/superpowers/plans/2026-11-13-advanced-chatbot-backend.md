# Advanced Chatbot (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chatbots as flows of nodes, run per chat, started by a new automation action, with per-node and per-flow server validation, a runs log, versions, and inbox hand-over.

**Architecture:** A `chatbots` document keeps the canvas flow verbatim; `FlowParser` reads the parts the server needs into typed node settings. Each node type is a `NodeExecutor` bean (validate, run, onReply, outputs). `ChatbotRunner` claims a `chatbotRuns` row with a lease and walks the flow on a dedicated pool; an inbox listener feeds customer replies, a ticker handles timers; messages go out through the existing `InboxSendService` Cloud path as a new `ChatbotActor`. Automations gain `START_CHATBOT` and a captured `chat.chatbotRunning` fact.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), Spring Data MongoDB, JMustache, Spring `RestClient` (spring-web), JUnit 5, `TestRestTemplate`, `MutableClock`, `AutomationDevice`/`FakeDevice`.

**Spec:** `docs/superpowers/specs/2026-11-13-advanced-chatbot-design.md`

## Global Constraints

- Work in `.worktrees/advanced-chatbot`; test first; Prettier only on touched files; commit trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Collections camelCase: `chatbots`, `chatbotVersions`, `chatbotRuns`. Channel-neutral names. GridFS, never S3.
- `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the CRM is reached through the existing `AutomationCrm` port.
- Nothing new is named "Template" except the Send template node (saved templates).
- Node names `[A-Za-z0-9_]{1,40}`, unique per flow; chatbot names 1–60, unique per brand.
- Settings defaults: reminder off (1–1440 min), end after 120 min (5–2880), invalid-reply retries 2 (0–5), retry message "Sorry, I didn't get that. Please choose one of the options.", stop on agent reply on.
- Caps: 200 steps per run, 5 jumps in a row, Wait 1 minute to 30 days, HTTP timeout 10 s and 1 MB body, 50 versions kept (never one an active run points to), send retried 3 times when it never reached the extension.
- API under `/api/workspaces/{workspaceId}/chatbots`; authorities `chatbot:view`, `chatbot:manage` (default roles CUSTOMER_ADMIN, WORKSPACE_MANAGER); stop from the inbox needs `inbox:send`.
- Run `./mvnw -q -pl omnichannel-app -am test -Dtest=<ITs> -Dsurefire.failIfNoSpecifiedTests=false` while working; `./mvnw -q verify` once before the merge. `-am` is required.
- **Never send a real message without the user naming the recipient.**

## Review Focus

Inputs the spec implies but no happy path exercises; each has its test in the task named.

1. A customer sends two replies within the same second to a waiting node: exactly one is processed, the other is a plain inbox message (Task 7, `twoRepliesInOneInstantRunOneStep`).
2. The device echoes the chatbot's own message before the send result: the run is not handed over (Task 10, `ownEchoDoesNotHandOver`).
3. A reply arrives while the run is Sleeping on a Wait node: ignored, the run resumes on its timer only (Task 9, `replyDuringWaitIsIgnored`).
4. A published chatbot is moved to draft while a run waits: the run finishes on its version; a Start chatbot action afterwards fails "chatbot is not published" (Task 12, `moveToDraftKeepsRunningRunsAndRefusesNewStarts`).
5. An HTTP request node URL resolves to a private address at run time: the step fails `failure` without a request leaving (Task 11, `privateAddressIsRefused`).

---

### Task 1: Media and location Cloud message types

**Files:**
- Modify: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/whatsapp/message/CloudMessage.java`
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/channel/whatsapp/message/{MediaObject,LocationObject}.java`
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/cloud/CloudMessageValidator.java`
- Modify: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/inbox/InboxSendService.java` (`cloudOutgoing`)
- Test: `omnichannel-common/src/test/java/.../message/CloudMessageJsonTest.java`, fixtures `omnichannel-common/src/test/resources/cloud/{image,document,location}.json`; `omnichannel-service/src/test/java/.../cloud/CloudMessageValidatorTest.java`; `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/inbox/CloudSendApiIT.java`

**Interfaces:**
- Produces: `CloudMessage(messagingProduct, recipientType, to, type, text, interactive, image, video, audio, document, location, context)` with constants `IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION` and `Set<String> TYPES`; `record MediaObject(String id, String link, String caption, String filename)`; `record LocationObject(Double latitude, Double longitude, String name, String address)`. `CloudMessageValidator.validate` accepts the new types: exactly the matching object present; `id` required and a brand `cloudMedia` file with a MIME allowed by `CloudMediaTypes` for that type; `link` → 400 `"<type>.link": "upload the file instead"`; caption ≤ 1024, absent for audio; latitude/longitude required and in range. `cloudOutgoing` returns an `Outgoing` with `media`/`mediaBytes` (bytes from `CloudMediaService.open`) or `location`, type null, `request` the Cloud map, as `fromTemplate()` builds media sends today.

- [ ] **Step 1: Write the failing JSON round-trip tests**

Add to `CloudMessageJsonTest`: `imageRoundTrips`, `documentRoundTrips`, `locationRoundTrips`, each reading its fixture (Meta's documented bodies with `messaging_product: "whatsapp-web"`) and asserting `cloud.toMap(cloud.parse(node))` equals the fixture map. Fixtures:

```json
{"messaging_product":"whatsapp-web","recipient_type":"individual","to":"919999999999","type":"image","image":{"id":"66b1f1a2c3d4e5f60718293a","caption":"Our catalogue"}}
{"messaging_product":"whatsapp-web","recipient_type":"individual","to":"919999999999","type":"document","document":{"id":"66b1f1a2c3d4e5f60718293a","caption":"Price list","filename":"prices.pdf"}}
{"messaging_product":"whatsapp-web","recipient_type":"individual","to":"919999999999","type":"location","location":{"latitude":12.9716,"longitude":77.5946,"name":"Our store","address":"MG Road, Bengaluru"}}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q -pl omnichannel-common test -Dtest=CloudMessageJsonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `unknown field image`.

- [ ] **Step 3: Add the record components and `withTo`**

`CloudMessage` gains `MediaObject image, MediaObject video, MediaObject audio, MediaObject document, LocationObject location` between `interactive` and `context`; `withTo` copies them. Both new records carry the same three Jackson annotations as `Text`.

- [ ] **Step 4: Run the JSON tests to verify they pass**

- [ ] **Step 5: Write the failing validator tests**

In `CloudMessageValidatorTest`: `imageNeedsAnUploadedId` (`image{link}` → field `image.link`), `imageMimeMustFitType` (media lookup returns `application/pdf` for an image id → field `image.id`), `audioRefusesCaption` (field `audio.caption`), `locationNeedsBothCoordinates` (`location{latitude: 12.9}` → field `location.longitude`), `locationRangeChecked` (latitude 91 → field `location.latitude`), `typeMustMatchObject` (`type: image` with `text` present → field `text`).

- [ ] **Step 6: Run them to verify they fail**, then implement the rules in `CloudMessageValidator.validate` (one private method per new type, keyed as above).

- [ ] **Step 7: Run the validator tests to verify they pass**

- [ ] **Step 8: Write the failing send ITs**

In `CloudSendApiIT`: `imageIsSentNatively` (upload a PNG through `/channel-accounts/{id}/media`, POST the image body to `/messages`; the fake device's `sendMessage` params have `options.media.mimetype == "image/png"` and `options.caption == "Our catalogue"`; the stored row has `type` from the echo and `request.type == "image"`); `locationIsSentNatively` (params `content` is a `Location` the bridge accepts, as a LOCATION template send is dispatched today; the row's `location.latitude == 12.9716`).

- [ ] **Step 9: Run them to verify they fail**, then implement `cloudOutgoing` for the five types, reusing the media/location construction of `fromTemplate()`.

- [ ] **Step 10: Run `CloudSendApiIT`, `InboxSendIT`, `CloudMessageValidatorTest`, `CloudMessageJsonTest` to verify they pass**

- [ ] **Step 11: Commit** `feat(cloud): media and location Cloud message types`

---

### Task 2: Chatbot documents, properties, authorities

**Files:**
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/chatbot/{Chatbot,ChatbotStatus,ChatbotSettings,ChatbotVersion,ChatbotRun,RunStatus,RunStep,StartedBy,IfRunning,ChatbotRepository,ChatbotVersionRepository,ChatbotRunRepository}.java`
- Modify: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/inbox/WaWebChat.java` (+ `ActiveChatbot chatbot`), `omnichannel-db/.../db/inbox/MessageSource.java` (+ `CHATBOT`)
- Modify: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/config/OmnichannelProperties.java` (+ `@DefaultValue Chatbot chatbot`), `omnichannel-app/src/main/resources/application.yml`, `omnichannel-app/src/test/resources/application-test.yml` (`chatbot.auto-tick: false`), the four tests constructing `OmnichannelProperties` (append `, null`)
- Modify: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/access/Authority.java` (+ `CHATBOT_VIEW`, `CHATBOT_MANAGE` after `AUTOMATION_MANAGE`)
- Modify: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/support/TestUsers.java` (`deleteAll` clears the three collections)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotModelIT.java`

**Interfaces:**
- Produces:
  - `@Document("chatbots") Chatbot { id, customerId, workspaceId, name, description, ChatbotStatus status, Map<String,Object> flow, ChatbotSettings settings, int versionNumber, @Version Long version, String createdBy, Instant creationTime, lastUpdateTime, publishedAt, String publishedBy }`; unique index `{workspaceId, name}`.
  - `record ChatbotSettings(Integer reminderAfterMinutes, String reminderMessage, int endAfterMinutes, String endMessage, int invalidReplyRetries, String retryMessage, String giveUpMessage, boolean stopOnAgentReply)` with `static ChatbotSettings defaults()` (null, null, 120, null, 2, the default retry text, null, true).
  - `@Document("chatbotVersions") ChatbotVersion { id, workspaceId, chatbotId, int versionNumber, Map<String,Object> flow, Instant savedAt, String savedBy }`; unique `{chatbotId, versionNumber}`.
  - `@Document("chatbotRuns") ChatbotRun { id, customerId, workspaceId, chatbotId, chatbotName, int versionNumber, chatId, contactId, phoneNumberId, RunStatus status, String currentNodeId, currentNodeName, Map<String,Object> variables, List<RunStep> steps, boolean test, StartedBy startedBy, String lastInboundMessageId, int invalidReplies, int sendAttempts, int jumps, Instant leaseUntil, remindAt, endAt, resumeAt, boolean reminderSent, String endReason, String stoppedBy, Instant createdAt, finishedAt }`; indexes `{chatId, status}`, `{workspaceId, chatbotId, createdAt:-1}`, `{status, resumeAt}`, `{status, endAt}`, `{status, remindAt}`, `{status, leaseUntil}`, unique partial `{startedBy.eventId, startedBy.ruleId}` where `test:false` and both exist.
  - `enum RunStatus { RUNNING, WAITING, SLEEPING, COMPLETED, ENDED, STOPPED, HANDED_OVER, JUMPED, FAILED; boolean active() }` (the first three).
  - `record RunStep(int index, String nodeId, String nodeName, String type, Instant startedAt, Instant finishedAt, StepOutcome outcome, String output, String summary, String error, Map<String,Object> result)` with `enum StepOutcome { DONE, WAITING, FAILED }` nested.
  - `record StartedBy(String ruleId, String ruleName, String eventId, String userId)`; `enum IfRunning { SKIP, REPLACE }` (used by the run service and, in Task 12, by `AutomationAction`).
  - `WaWebChat.chatbot: record ActiveChatbot(String runId, String chatbotId, String chatbotName, String nodeName)`.
  - `OmnichannelProperties.Chatbot(@DefaultValue("true") boolean autoTick, @DefaultValue("2s") Duration tick, @DefaultValue("2m") Duration lease, @DefaultValue("4") int poolSize, @DefaultValue("200") int maxSteps, @DefaultValue("5") int maxJumps, @DefaultValue("10s") Duration httpTimeout, @DefaultValue("1048576") int httpMaxBody, @DefaultValue("60s") Duration sendTimeout, @DefaultValue("3") int sendAttempts, @DefaultValue("30s") Duration sendRetryDelay, @DefaultValue("50") int versionsKept, @DefaultValue("false") boolean allowPrivateHttp)`.
  - Repositories: `ChatbotRepository extends MongoRepository<Chatbot,String>` with `findAllByWorkspaceIdOrderByNameAsc`, `existsByWorkspaceIdAndNameIgnoreCase`; `ChatbotVersionRepository` with `findAllByChatbotIdOrderByVersionNumberDesc`, `findByChatbotIdAndVersionNumber`; `ChatbotRunRepository` with `findFirstByChatIdAndStatusIn(String, Collection<RunStatus>)`, `findAllByWorkspaceIdAndChatbotIdOrderByCreatedAtDesc(…, Pageable)`.

- [ ] **Step 1: Write the failing model IT**

`ChatbotModelIT` (`@SpringBootTest`, cleans with `TestUsers.deleteAll`): `uniqueNamePerBrand` (two chatbots "Lead qualifier" in one workspace → `DuplicateKeyException`; the same name in another workspace saves), `oneLiveRunPerEventAndRule` (two runs with `startedBy(eventId "e1", ruleId "r1")`, `test=false` → second insert fails; a third with `test=true` saves), `authoritiesExist` (`Authority.valueOf("CHATBOT_MANAGE").code()` is `chatbot:manage` with `WORKSPACE_MANAGER` among default roles).

- [ ] **Step 2: Run it to verify it fails** (`-Dtest=ChatbotModelIT`)

- [ ] **Step 3: Create the documents, enums, repositories, properties and authorities as specified; wire `application.yml`**

```yaml
  chatbot:
    auto-tick: true
    tick: 2s
    lease: 2m
    pool-size: 4
    max-steps: 200
    max-jumps: 5
    http-timeout: 10s
    http-max-body: 1048576
    send-timeout: 60s
    send-attempts: 3
    send-retry-delay: 30s
    versions-kept: 50
    allow-private-http: false
```

- [ ] **Step 4: Run `ChatbotModelIT`, the four `OmnichannelProperties` tests and `AuthorityIT`-style role tests (`-Dtest=ChatbotModelIT,RoleSeederIT,JwtServiceTest,FetchedChatOpenerTest`) to verify they pass**

- [ ] **Step 5: Commit** `feat(chatbot): documents, properties and authorities`

---

### Task 3: Flow model, parser and node settings

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/flow/{Flow,FlowNode,FlowEdge,NodeType,FlowParser,Outputs}.java` and `.../flow/settings/{SendMessageSettings,SendTemplateSettings,AskQuestionSettings,AnswerType,ConditionSettings,UpdateContactSettings,DoNotContactSettings,AssignChatSettings,AddToCrmSettings,HttpRequestSettings,WaitSettings,WaitUnit,JumpSettings}.java`
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/flow/FlowParserTest.java`

**Interfaces:**
- Produces:
  - `record Flow(List<FlowNode> nodes, List<FlowEdge> edges)` with `Optional<FlowNode> node(String id)`, `List<FlowEdge> from(String nodeId)`, `Optional<String> target(String nodeId, String output)`, `Set<String> entryCandidates()` (nodes with no incoming edge).
  - `record FlowNode(String id, String name, NodeType type, JsonNode settings)`; `record FlowEdge(String id, String source, String output, String target)` (`output` = React Flow `sourceHandle`, `"next"` when absent).
  - `enum NodeType { SEND_MESSAGE("send_message"), SEND_TEMPLATE("send_template"), ASK_QUESTION("ask_question"), CONDITION("condition"), UPDATE_CONTACT("update_contact"), DO_NOT_CONTACT("do_not_contact"), ASSIGN_CHAT("assign_chat"), ADD_TO_CRM("add_to_crm"), HTTP_REQUEST("http_request"), WAIT("wait"), JUMP("jump"); @JsonValue String code(); static Optional<NodeType> of(String) }`.
  - `FlowParser.parse(Map<String,Object> flow) -> Flow` reads `nodes[].id`, `nodes[].type`, `nodes[].data.name`, `nodes[].data.settings`, `edges[].id/source/sourceHandle/target`; everything else is ignored and kept by the caller. Throws `ValidationException("flow", …)` for: no `nodes` array, a node without `id`, an unknown `type`, duplicate node ids, an edge whose `source` or `target` names no node. `<T> T settings(FlowNode, Class<T>)` converts with the shared `ObjectMapper` (`FAIL_ON_UNKNOWN_PROPERTIES` off, so the console may keep UI-only keys) and throws `ValidationException("nodes."+id+".settings", …)` on a type mismatch.
  - `Outputs` constants: `NEXT="next"`, `OTHER="other"`, `NO_ANSWER="noAnswer"`, `OTHERWISE="otherwise"`, `SUCCESS="success"`, `FAILURE="failure"`, `static String reply(String id) -> "reply:"+id`, `static String caseOf(String id) -> "case:"+id`.
  - Settings records (camelCase JSON): `SendMessageSettings(Map<String,Object> message)`; `SendTemplateSettings(String templateId, Map<String,String> values)`; `AskQuestionSettings(String text, AnswerType answerType, String saveAs, String attributeId, Integer retries, String retryMessage, String giveUpMessage)`; `enum AnswerType { TEXT, NUMBER, EMAIL, PHONE, DATE, LOCATION, FILE, IMAGE, VIDEO, AUDIO, DOCUMENT }`; `ConditionSettings(List<Case> cases)` with `record Case(String id, String label, ConditionNode conditions)` (the automations' `ConditionNode`); `UpdateContactSettings(Map<String,String> setAttributes, List<String> clearAttributes, List<String> addLabels, List<String> removeLabels, String nameIfBlank)`; `DoNotContactSettings(boolean mark)`; `AssignChatSettings(AssignMode mode, String userId, String teamId, WhenAway whenAway)` (automations' enums); `AddToCrmSettings(String pipelineId, String stageId, String dealTitle)`; `HttpRequestSettings(String method, String url, Map<String,String> headers, String body, String contentType)`; `WaitSettings(int amount, WaitUnit unit)` with `enum WaitUnit { MINUTES, HOURS, DAYS }` and `Duration duration()`; `JumpSettings(String chatbotId)`.

- [ ] **Step 1: Write the failing parser tests**

`FlowParserTest`: `readsNodesEdgesAndHandles` (a two-node map with an edge `sourceHandle: "reply:yes"` → `flow.target("n1","reply:yes") == Optional.of("n2")`, an edge without `sourceHandle` → output `"next"`), `ignoresCanvasOnlyKeys` (`position`, `measured`, `selected` present → parses), `refusesUnknownType` (`type: "open_ai"` → `ValidationException` field `flow`, message contains `open_ai`), `refusesDuplicateIds`, `refusesDanglingEdge`, `entryCandidatesAreNodesWithoutIncomingEdges`, `settingsConvertByType` (`WaitSettings(2, HOURS).duration() == 2h`; a string `amount` → `ValidationException` field `nodes.n1.settings`).

- [ ] **Step 2: Run to verify they fail** (`./mvnw -q -pl omnichannel-service -am test -Dtest=FlowParserTest -Dsurefire.failIfNoSpecifiedTests=false`)

- [ ] **Step 3: Implement the records, enum, parser and `Outputs`**

- [ ] **Step 4: Run `FlowParserTest` to verify it passes**

- [ ] **Step 5: Commit** `feat(chatbot): flow model and parser`

---

### Task 4: Variables and text rendering

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/{RunVariables,ChatbotText}.java`
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/run/{RunVariablesTest,ChatbotTextTest}.java`

**Interfaces:**
- Produces:
  - `RunVariables` (static helpers over `Map<String,Object>`): `Map<String,Object> initial(Contact c, List<ContactAttribute> attributes, List<ContactLabel> labels, WaWebChat chat, PhoneNumber number, EventData event, StartedBy trigger, Instant now)` builds `contact{name, phone, attributes{<key>: value}, labels[names], doNotContact}`, `chat{id, status, assignedUser, assignedTeam}`, `number{label, phone}`, `event{…}` (the `EventData` converted by the mapper, `chat.isNew` exposed as `event.chat.new`), `trigger{ruleId, ruleName, eventId, at}`, `now` (ISO). `Map<String,Object> flatten(Map<String,Object> vars)` → dotted paths (`"AskBudget.answer" -> 5000`, lists kept as lists). `void refreshContact(Map<String,Object> vars, Contact c, …)`.
  - `ChatbotText.render(String template, Map<String,Object> vars) -> String` (plain, unknown path → empty, no HTML escaping) and `renderJson(String template, Map<String,Object> vars)` (values JSON-escaped first); both JMustache with `defaultValue("")`, `escapeHTML(false)`, `strictSections(false)`. `Set<String> paths(String template)` → the `{{a.b}}` paths it names (for validation). `String renameNode(String template, String oldName, String newName)` rewrites `{{old.` → `{{new.` (the console does the same on rename; the server helper serves the restore path and tests).

- [ ] **Step 1: Write the failing tests**

`RunVariablesTest`: `buildsContactChatNumberEventTrigger` (asserts `vars.contact.attributes.city == "Pune"`, `vars.event.message.text`, `vars.trigger.ruleName`), `flattenUsesDottedPaths` (`flatten(Map.of("AskBudget", Map.of("answer", 5000)))` has `"AskBudget.answer"`), `emptyEventWhenStartedByTest` (`event` is an empty map, `trigger.ruleName == "Test"`).
`ChatbotTextTest`: `rendersNestedPaths` (`"Hi {{contact.name}}, {{AskBudget.answer}}"`), `unknownPathIsEmpty`, `plainTextIsNotHtmlEscaped` (`"<b>"` stays), `jsonEscapesValues` (an answer `He said "hi"` inside `{"a":"{{Q.answer}}"}` parses as JSON), `pathsListsReferences`, `renameNodeRewritesPrefixOnly` (`{{Old.answer}}` → `{{New.answer}}`, `{{Older.x}}` untouched).

- [ ] **Step 2: Run to verify they fail**, then implement.

- [ ] **Step 3: Run `RunVariablesTest,ChatbotTextTest` to verify they pass**

- [ ] **Step 4: Commit** `feat(chatbot): run variables and text rendering`

---

### Task 5: Node executor contract, registry and flow validation

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/{NodeExecutor,NodeExecutors,ValidationContext,Problem,Severity,StepResult,RunContext}.java`, `.../chatbot/FlowValidator.java`, and a first executor per type with `outputs` + `validate` only (run bodies come in Tasks 7–11): `.../chatbot/node/{SendMessageExecutor,SendTemplateExecutor,AskQuestionExecutor,ConditionExecutor,UpdateContactExecutor,DoNotContactExecutor,AssignChatExecutor,AddToCrmExecutor,HttpRequestExecutor,WaitExecutor,JumpExecutor}.java`
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/FlowValidatorTest.java`, `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/NodeValidationIT.java`

**Interfaces:**
- Produces:
  - `interface NodeExecutor { NodeType type(); List<String> outputs(FlowNode node, ValidationContext ctx); boolean waits(FlowNode node, Flow flow); List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx); StepResult run(RunContext ctx, FlowNode node); default StepResult onReply(RunContext ctx, FlowNode node, WaWebMessage reply) { throw new IllegalStateException(); } }`.
  - `record ValidationContext(String workspaceId, Chatbot chatbot, boolean test)` plus lookups injected into executors (repositories, `TemplateService`, `AutomationCrm`, `CloudMessageValidator`, `CloudMediaService`).
  - `record Problem(String nodeId, String nodeName, String field, String message, Severity severity)` with `static Problem error(FlowNode, field, msg)`, `warning(...)`, `flow(field, msg)` (nodeId null); `enum Severity { ERROR, WARNING }`.
  - `record StepResult(Kind kind, String output, Map<String,Object> result, String summary, String error, Instant resumeAt, String jumpToChatbotId)` with `enum Kind { NEXT, WAIT, SLEEP, END, JUMP, FAIL }` and factories `next(output, result, summary)`, `wait(summary)`, `sleep(resumeAt, summary)`, `end(summary)` (used when an output has no edge is the runner's business, not the node's), `jump(chatbotId, summary)`, `fail(error)`.
  - `RunContext` (class): `ChatbotRun run`, `Chatbot chatbot`, `Flow flow`, `WaWebChat chat`, `Contact contact`, `ChatbotSettings settings`, `Map<String,Object> vars()`, `String render(String)`, `String renderJson(String)`, `Instant now()`, `ChatbotSender sender()` (Task 7), `boolean test()`.
  - `NodeExecutors(List<NodeExecutor>)` → `NodeExecutor of(NodeType)` (EnumMap; duplicate → `IllegalStateException`).
  - `FlowValidator.validate(Flow flow, ValidationContext ctx) -> List<Problem>`: flow checks from spec §6 (name pattern and uniqueness, edges from real outputs per `executor.outputs`, at most one edge per output, exactly one entry node, reachability WARNING, unknown variable paths WARNING via `ChatbotText.paths` over every string in each node's settings, Jump to itself ERROR), then each node's `executor.validate`. `static boolean publishable(List<Problem>)` (no ERROR).
  - Per-node validation rules (each executor's `validate`), with field keys relative to the node: SEND_MESSAGE `message` via `CloudMessageValidator.validate(ws, msg.withTo("0"), media::mimeOf, false)` mapping its JSON-path keys through as `message.<path>`, plus WARNING `message` "no button is connected" when buttons/rows exist and no `reply:*` edge leaves the node; SEND_TEMPLATE `templateId` (exists, not archived), `values.<name>` (required agent placeholders without value or fallback); ASK_QUESTION `text` required ≤1024, `saveAs` matches `[A-Za-z_][A-Za-z0-9_]*` when set, `retries` 0–5, `attributeId` exists; CONDITION `cases` ≥1, `cases[i].conditions` complete (field, operator in `Operator`, value unless `is_empty`/`is_not_empty`), `cases[i].label` ≤40; UPDATE_CONTACT at least one change, `setAttributes.<id>`/`clearAttributes[i]`/`addLabels[i]`/`removeLabels[i]` exist, values ≤500; DO_NOT_CONTACT none; ASSIGN_CHAT reuse `RulePublishValidator`'s agent/team rules (extract them into a package-visible `AssignTargets.check(ws, mode, userId, teamId) -> Map<String,String>` in `service.automation` and call it from both); ADD_TO_CRM reuse the CRM check the same way (`CrmTargets.check`); HTTP_REQUEST `method` in the five, `url` `http(s)://` ≤2000, `headers.<name>` no `\r\n`, `body` parses as JSON when `contentType` is `application/json` and the body has no `{{` (a templated body is checked at run time), `contentType` ≤100; WAIT `amount` with `duration()` in 1 min–30 days; JUMP `chatbotId` exists in the brand and ≠ self.

- [ ] **Step 1: Write the failing flow validator unit tests**

`FlowValidatorTest` with stub executors (a `TestExecutors` fixture that returns fixed outputs): `needsExactlyOneEntry` (a 2-cycle → flow ERROR `entry` "no entry node"; two roots → ERROR), `unreachableNodeIsWarning`, `edgeFromUnknownOutputIsError` (`reply:gone` → field `edges.<id>`), `twoEdgesFromOneOutputIsError`, `badNameIsError` (`"Ask budget"`), `duplicateNameIsError`, `unknownVariableIsWarning` (`{{Nope.answer}}` → WARNING field `settings.text`), `jumpToSelfIsError`, `publishableOnlyWithoutErrors`.

- [ ] **Step 2: Run to verify they fail**, implement `FlowValidator`, `Problem`, `NodeExecutors`, the contract records, and all eleven executors with `type()`, `outputs()`, `waits()`, `validate()`; `run()` throws `UnsupportedOperationException("Task 7+")` for now.

- [ ] **Step 3: Run `FlowValidatorTest` to verify it passes**

- [ ] **Step 4: Write the failing per-node validation IT**

`NodeValidationIT` (extends the base from Task 6, or a minimal `@SpringBootTest` with `TestUsers`): one test per executor rule listed above, each building a flow with that one node and asserting the problem's `field` and `severity`. Names: `sendMessageMapsCloudPaths` (`message.interactive.action.buttons.0.reply.title` too long), `sendMessageWarnsWhenNoButtonConnected`, `sendTemplateRefusesArchived`, `askQuestionSaveAsPattern`, `conditionNeedsCompleteRules`, `updateContactNeedsAChange`, `assignChatUnknownAgent`, `addToCrmWhenCrmOff`, `httpRequestBadUrl`, `httpRequestHeaderNewline`, `waitOutOfRange`, `jumpUnknownChatbot`.

- [ ] **Step 5: Run to verify it fails, implement the rules, run again to verify it passes**

- [ ] **Step 6: Commit** `feat(chatbot): node executors' validation and the flow validator`

---

### Task 6: Chatbot CRUD, save-with-problems, publish, versions API

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/{ChatbotService,ChatbotViews,ChatbotOptionsService}.java`, `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/chatbot/ChatbotController.java`
- Create: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/{ChatbotTestBase,ChatbotApiIT}.java`
- Modify: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/{AutomationTestBase,AutomationDevice}.java` → make both `public` (and their used members) so `app.chatbot` tests can extend/use them.

**Interfaces:**
- Produces (`ChatbotService`): `ChatbotView create(AuthenticatedUser me, String ws, String name)` (an empty flow `{nodes:[], edges:[]}`, `ChatbotSettings.defaults()`, DRAFT, versionNumber 0); `ChatbotView get(ws, id)`; `List<ChatbotSummary> list(ws)`; `SaveResult save(me, ws, id, SaveRequest r)` where `record SaveRequest(String name, String description, Map<String,Object> flow, ChatbotSettings settings, Long version)` and `record SaveResult(ChatbotView chatbot, List<Problem> problems)`: DRAFT only (409 `CodedException "CHATBOT_PUBLISHED"` otherwise), parses the flow (400 on unreadable), stores it verbatim, validates, appends a `ChatbotVersion` when `nodes`/`edges` changed (compared after dropping canvas-only keys `position`, `measured`, `selected`, `dragging`, `width`, `height`), bumps `versionNumber`, prunes versions beyond `versionsKept` that no active run uses; stale `version` → 409 `STALE`; `publish(me, ws, id)` → 400 `ValidationException` with fields `nodes.<id>.<field>` / `flow.<field>` from the ERROR problems, else PUBLISHED; `unpublish(me, ws, id)`; `delete(ws, id)` (DRAFT only); `List<VersionView> versions(ws, id)`; `Map<String,Object> versionFlow(ws, id, int n)`; `SaveResult restore(me, ws, id, int n)` (a save of that flow).
- `ChatbotViews`: `record ChatbotView(id, name, description, status, flow, settings, versionNumber, version, createdBy Ref, creationTime, lastUpdateTime, publishedAt, publishedBy Ref)`, `record ChatbotSummary(id, name, status, int runs7d, int activeNow, Instant lastRunAt, Instant lastUpdateTime)`, `record VersionView(int versionNumber, Instant savedAt, Ref savedBy)`, `record ProblemView(nodeId, nodeName, field, message, severity)`.
- `ChatbotOptionsService.options(ws) -> ChatbotOptions(numbers, agents, teams, templates, crm, attributes[{id,name,key}], labels[{id,name}], chatbots[{id,name,status}])` reusing `AutomationOptionsService.options` for the first five.
- Controller routes (`@RestController`, package-private, `@PreAuthorize("@access.has(#workspaceId, 'chatbot:view'|'chatbot:manage')")`): `GET /options`, `GET /` list, `POST /` `{name}` → 201, `GET /{id}`, `PUT /{id}` → `SaveResult`, `POST /{id}/publish`, `POST /{id}/unpublish`, `DELETE /{id}` → 204, `GET /{id}/versions`, `GET /{id}/versions/{n}`, `POST /{id}/versions/{n}/restore`.
- `ChatbotTestBase extends AutomationTestBase` adding `Chatbot`, `ChatbotVersion`, `ChatbotRun` to `collections()`, a `flow(FlowNode…)` builder helper (`Map<String,Object> flow(List<Map<String,Object>> nodes, List<Map<String,Object>> edges)`), `node(id, type, name, settings)`, `edge(source, output, target)`, and `createChatbot(name, flow)` through the API as `manager`.

- [ ] **Step 1: Write the failing API IT**

`ChatbotApiIT`: `createListGetDelete`; `saveKeepsFlowVerbatimAndReturnsProblems` (a node with `position` and an unconnected button → the GET returns the same `position`; `problems[0].severity == "WARNING"`); `saveRefusesUnreadableFlow` (unknown type → 400 `VALIDATION` field `flow`); `saveAppendsVersionOnlyWhenGraphChanged` (two saves moving a node only → one version; adding a node → two); `staleSaveIs409`; `publishRefusedWithErrors` (two entry nodes → 400 fields contain `flow.entry`); `publishedCannotBeSaved` (409 `CHATBOT_PUBLISHED`); `unpublishThenSave`; `restoreMakesNewVersion`; `viewerCannotSave` (`agent` → 403); `otherBrandIs404`; `duplicateNameIs409` (`DUPLICATE` field `name`).

- [ ] **Step 2: Run to verify it fails**, implement service, views, options and controller.

- [ ] **Step 3: Run `ChatbotApiIT` to verify it passes**

- [ ] **Step 4: Commit** `feat(chatbot): chatbots API with validation problems and versions`

---

### Task 7: The runner: start, loop, claim, replies, Send message (text) and Ask a question (text)

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/{ChatbotRunner,ChatbotRunService,ChatbotSender,ChatbotReplyListener,RunClaims}.java`, `.../service/inbox/ChatbotActor.java`
- Modify: `.../service/inbox/InboxEvents.java` (+ `record ChatbotRunChanged(ChatbotRun run)`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/ChatbotExecutorConfig.java` (a `ThreadPoolTaskExecutor` bean `chatbotExecutor`, `poolSize` threads, queue 1000)
- Modify: `SendMessageExecutor.run`/`onReply` (text only here), `AskQuestionExecutor.run`/`onReply` (TEXT only here), `InboxViews.sentBy` (CHATBOT like AUTOMATION), `InboxSendService.sendCloud` (a `ChatbotActor` may send to a CLOSED chat, as `AutomationActor`)
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotRunIT.java`

**Interfaces:**
- Produces:
  - `record ChatbotActor(String chatbotId, String chatbotName, String runId) implements SendActor` → `userId()` null, `displayName()` `"Chatbot: "+name`, `source()` `MessageSource.CHATBOT`, `campaignId()` null.
  - `ChatbotRunService` (uses `db.chatbot.IfRunning`): `record StartResult(ChatbotRun run, boolean started, String reason)`; `StartResult start(String ws, String chatbotId, WaWebChat chat, StartedBy by, EventData event, IfRunning ifRunning, boolean test)`: loads the chatbot (PUBLISHED, or DRAFT when `test`), builds `RunVariables.initial`, inserts the run `RUNNING` with `leaseUntil = now + lease`, then **claims the chat pointer** (`findAndModify` on `waWebChats` `{_id, chatbot: null}` → set `ActiveChatbot`; when it fails: SKIP → delete the run, `started=false, reason="Chatbot <other> is running"`; REPLACE → `stop(otherRun, "replaced by "+name)` then retry), then `runner.proceed(run)` on `chatbotExecutor`; returns at once with the run. `CompletableFuture<ChatbotRun> startAndAwaitFirstWait(…)` = `start` then wait until the run is no longer RUNNING, bounded by the caller. `ChatbotRun stop(ChatbotRun run, String reason, String byUserId)` (final status STOPPED, clears the chat pointer, publishes `ChatChanged`). `Optional<ChatbotRun> active(String chatId)`.
  - `ChatbotRunner.proceed(ChatbotRun run)`: loop per spec §5.2: current node's executor `run` (or `onReply` when `run.lastInboundMessageId` is new) → `StepResult`; append `RunStep`; `NEXT` → follow `flow.target(node, output)` (none → COMPLETED `"<node>: no edge from <output>"`); `WAIT` → WAITING, timers set; `SLEEP` → SLEEPING `resumeAt`; `END` → COMPLETED; `JUMP` → JUMPED then `start(target, same chat, by, vars)` with `jumps+1` (cap → FAILED); `FAIL` → FAILED; steps cap → FAILED "too many steps". Every write renews `leaseUntil`; final states clear `chat.chatbot` and publish `InboxEvents.ChatChanged(chat)` and a new `InboxEvents.ChatbotRunChanged(run)`.
  - `RunClaims.claimForReply(runId, messageId) -> Optional<ChatbotRun>`: `findAndModify {_id, status: WAITING}` → `RUNNING, leaseUntil, lastInboundMessageId`; `claimForResume(runId)` (`SLEEPING`); `claimStale(now)` (RUNNING with `leaseUntil < now` → FAILED "interrupted", pointer cleared) used by Task 9's ticker.
  - `ChatbotReplyListener @EventListener on(MessageUpserted e)`: IN, inserted, `source == CONTACT`, type in `MessageKinds.REAL_TYPES ∪ {"button","interactive"}`, `chat.chatbot != null` → `claimForReply` → `runner.proceed` on the pool; everything in try/catch, logged.
  - `ChatbotSender.send(RunContext ctx, CloudMessage m) -> MessageView`: `InboxSendService.sendCloud(new ChatbotActor(…), ws, chat, m.withTo(digits), null, false).get(sendTimeout)`; maps `NoDeviceException`/`RpcException SEND_FAILED|WHATSAPP_NOT_READY` to a `RetryLater` exception the runner handles by re-scheduling `proceed` after `sendRetryDelay` up to `sendAttempts` (counting `run.sendAttempts`), `TimeoutException`/`DEVICE_DISCONNECTED` to `Interrupted` (run FAILED "the message may have gone", never re-sent), anything else to `StepResult.fail`. Do-not-contact contact → run STOPPED "do not contact" before any send.
  - `SendMessageExecutor.run`: render every string in `settings.message` with `ctx.render` (JSON-escaped: walk the map), parse with `CloudMessages.fromMap`, send; `waits()` true when the message has buttons/rows and at least one `reply:*` edge exists → `StepResult.wait`, else `next("next")`. `onReply` (Task 8 completes it; here: text reply vs button titles by `CampaignMessageListener.bare`, result `{reply:{id,title,text}}`, output `Outputs.reply(id)`; no match → invalid-reply handling from settings: `run.invalidReplies < settings.invalidReplyRetries` → send `retryMessage` then the message again, stay WAITING; else send `giveUpMessage` if set, output `OTHER`).
  - `AskQuestionExecutor.run`: send `text` as a Cloud text message, `wait`. `onReply` TEXT: `result {answer: body}`, writes `vars[saveAs ?: node.name].answer`, sets the contact attribute when `attributeId` set (`ContactService.update`), output `NEXT`.

- [ ] **Step 1: Write the failing run ITs**

`ChatbotRunIT extends ChatbotTestBase`, using `AutomationDevice` as the fake extension and `publisher.publishEvent(new MessageUpserted(chat, inbound, true))` to simulate replies (the inbound row inserted first):
- `startSendsFirstMessageAndWaits`: flow `Welcome(send_message text "Hi {{contact.name}}") → AskName(ask_question TEXT) → Done(send_message "Thanks {{AskName.answer}}")`; start as test on a seeded chat; device gets `sendMessage` with `content == "Hi Veenu"` then the question; run WAITING at `AskName`; chat row has `chatbot.runId`.
- `replyAdvancesAndCompletes`: an inbound text "Rahul" → device gets "Thanks Rahul"; run COMPLETED; `steps.size()==3`, `steps[1].result.answer == "Rahul"`; chat pointer cleared.
- `twoRepliesInOneInstantRunOneStep` (Review Focus 1): publish two `MessageUpserted` for one WAITING run from two threads; exactly one step is appended, the run ends once.
- `skipWhenRunningReplaceWhenAsked`: a second `start` with SKIP returns `started=false`; with REPLACE the first run is STOPPED "replaced by …".
- `noEdgeEndsCompleted`; `stepCapFails` (the class's `@SpringBootTest(properties = "omnichannel.chatbot.max-steps=7")`; a 2-node loop `A → B → A` of text sends → FAILED "too many steps" with 7 steps; every other test in the class stays under 7 steps); `doNotContactStops`.
- `chatbotMessagesCarrySourceAndName` (stored row `source == CHATBOT`, `sentByName == "Chatbot: Lead qualifier"`; `GET /inbox/chats/{id}/messages` shows `sentBy.name`).
- `sendRetriedWhenDeviceOffline` (number offline → run stays RUNNING; `clock.advance(sendRetryDelay)` + a manual `runner.retryDue()` call (Task 9 wires the ticker) → sent when the device is back).

- [ ] **Step 2: Run to verify they fail** (`-Dtest=ChatbotRunIT`)

- [ ] **Step 3: Implement the actor, sender, claims, runner, run service, listener, executor config, and the two executors' `run`/`onReply` for text**

- [ ] **Step 4: Run `ChatbotRunIT`, `InboxSendIT`, `CloudSendApiIT` to verify they pass**

- [ ] **Step 5: Commit** `feat(chatbot): runner, replies and the first two nodes`

---

### Task 8: Send message replies (buttons, lists, media, location) and every answer type

**Files:**
- Modify: `SendMessageExecutor` (interactive routing, invalid-reply retries, media/location types), `AskQuestionExecutor` (all `AnswerType`s, file answers, per-node retries, `noAnswer`)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/{ReplyMatcher,Answers}.java`
- Test: `omnichannel-service/src/test/java/.../chatbot/node/{ReplyMatcherTest,AnswersTest}.java`, `omnichannel-app/src/test/java/.../chatbot/ChatbotRepliesIT.java`

**Interfaces:**
- Produces: `ReplyMatcher.match(Interactive i, WaWebMessage reply, Map<String,Object> webhookEvent) -> Optional<Interactive.Reply>`: `button_reply.id`/`list_reply.id` from the stored webhook event when present (`ChannelWebhookEventStore.find("message:"+waMessageId)`), else `bare(title) == bare(body)` over `CloudMessageValidator.replies(i)`. `Answers.parse(AnswerType t, WaWebMessage m) -> Optional<Object>`: TEXT any body; NUMBER `BigDecimal` from a body with digits, one dot, optional sign, commas stripped; EMAIL the `PlaceholderParser`-style regex `^[^@\s]+@[^@\s]+\.[^@\s]+$`; PHONE 9–15 digits after stripping `+ - ( ) space`; DATE `dd/MM/yyyy` or `yyyy-MM-dd` → ISO date string; LOCATION a `location` message → `{latitude, longitude}`; FILE any media type; IMAGE/VIDEO/AUDIO/DOCUMENT the matching type (`audio` includes voice notes) → `{messageId, mimeType, filename, size}`.
- `AskQuestionExecutor.onReply`: parse; valid → `NEXT` with `{answer}`; invalid → `run.invalidReplies < retries` → send `retryMessage` (default settings' text), stay WAITING, `invalidReplies++`; else send `giveUpMessage` when set, output `NO_ANSWER` (result `{answer:null, gaveUp:true}`); `invalidReplies` resets to 0 when a node completes.

- [ ] **Step 1: Write the failing unit tests**

`ReplyMatcherTest`: `matchesButtonReplyIdFromEvent`, `matchesTitleIgnoringCaseAndPunctuation` ("Yes, book a demo!" vs "yes book a demo"), `noMatchIsEmpty`. `AnswersTest`: one test per type including `numberStripsCommas` ("5,000" → 5000), `dateAcceptsTwoFormats`, `phoneNeedsNineToFifteenDigits`, `imageRejectsDocument`, `audioAcceptsVoiceNote`.

- [ ] **Step 2: Run to verify they fail**, implement, run to verify they pass.

- [ ] **Step 3: Write the failing replies IT**

`ChatbotRepliesIT`: `buttonTitleRoutesToItsOutput` (send_message with buttons `yes`/`no`, edges `reply:yes → Yes`, `reply:no → No`; reply "Yes" → the `Yes` node runs; `steps[0].output == "reply:yes"`, `vars.Ask.reply.id == "yes"`), `unmatchedReplyRetriesThenOther` (settings retries 1: first wrong reply → retry message + the message again, WAITING; second → `other`), `listRowByTitle`, `locationAnswer`, `imageAnswerStoresMediaRef`, `invalidNumberRetriesPerNode` (node retries 0 → straight to `noAnswer`), `answerSavedToAttribute`, `ctaMessageDoesNotWait`, `mediaMessageSendsNatively` (an image send_message node: device params carry `options.media`).

- [ ] **Step 4: Run to verify it fails, implement, run `ChatbotRepliesIT,ChatbotRunIT` to verify they pass**

- [ ] **Step 5: Commit** `feat(chatbot): interactive replies and every answer type`

---

### Task 9: Condition, Update contact, Do not contact, Wait, Jump; timers and the ticker

**Files:**
- Modify: `ConditionExecutor`, `UpdateContactExecutor`, `DoNotContactExecutor`, `WaitExecutor`, `JumpExecutor` (`run` bodies)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/{ChatbotTicker,ChatbotTimers}.java`, `.../service/chatbot/ChatbotSchedulerConfig.java` (one-thread `ThreadPoolTaskScheduler chatbotScheduler`, `@ConditionalOnProperty omnichannel.chatbot.auto-tick`)
- Test: `omnichannel-app/src/test/java/.../chatbot/{ChatbotNodesIT,ChatbotTimersIT}.java`

**Interfaces:**
- Produces: `ConditionExecutor.run`: `flat = RunVariables.flatten(vars)`; first case with `ConditionEvaluator.matches(case.conditions, flat)` → output `Outputs.caseOf(id)`, `{matched: label}`; none → `OTHERWISE`. `UpdateContactExecutor.run`: `ContactService.update(ws, id, null, rendered setAttributes + cleared as "")`, `addLabel`/`removeLabel`, `nameIfBlank`; then `RunVariables.refreshContact`. `DoNotContactExecutor.run`: `markDoNotContact(ws, id, DoNotContact.Reason.CHATBOT, null)` (add `CHATBOT` to the enum) or `clearDoNotContact`. `WaitExecutor.run` → `StepResult.sleep(now + duration)`. `JumpExecutor.run` → `StepResult.jump(chatbotId)`.
- `ChatbotTimers.onWait(run, settings, now)` sets `remindAt` (when reminder on and not yet sent) and `endAt`; `onReply` pushes both forward and clears `reminderSent` only when the reminder is configured to repeat (it is not: once per run, spec §5.6); `onSleep` clears both.
- `ChatbotTicker.tick()` `@Scheduled(fixedDelayString="${omnichannel.chatbot.tick:2s}", scheduler="chatbotScheduler")`, each step in its own try/catch: (1) reminders due (`WAITING, remindAt <= now, reminderSent false`) → send `reminderMessage`, set `reminderSent`; (2) ends due (`WAITING, endAt <= now`) → ENDED "no reply for N minutes", send `endMessage` if set; (3) resumes due (`claimForResume` on `SLEEPING, resumeAt <= now`) → `proceed`; (4) send retries due (`RUNNING` with `retryAt <= now`; add `Instant retryAt` to `ChatbotRun`); (5) `claimStale(now)`. `ChatbotTicker` is also callable from tests (`tick()` public).

- [ ] **Step 1: Write the failing node IT**

`ChatbotNodesIT`: `conditionPicksFirstMatchingCase` (cases `budget >= 5000` → `High`, else `Low`; with `AskBudget.answer = 7000` the `High` node runs; `steps[1].result.matched == "High"`), `conditionOtherwise`, `updateContactSetsAttributesAndLabels` (contact attribute `city` == rendered value, label present, `vars.contact.attributes.city` refreshed), `doNotContactMarksWithChatbotReason`, `jumpStartsTargetWithVariables` (target chatbot's first message renders `{{AskName.answer}}` from the first run; first run JUMPED, second run exists on the chat), `jumpCapFails` (two chatbots jumping to each other, `maxJumps` 5 → FAILED "too many jumps").

- [ ] **Step 2: Run to verify it fails, implement the five executors, run to verify it passes**

- [ ] **Step 3: Write the failing timers IT**

`ChatbotTimersIT` (uses `MutableClock` and `ticker.tick()`): `reminderSentOnce` (reminder 5 min: advance 5 min, tick → device gets the reminder; advance 5 more, tick → nothing more), `inactivityEndsRun` (end 120 min → ENDED, end message sent, pointer cleared), `replyPushesTimersForward`, `waitResumesOnTick` (Wait 2 hours: advance 2h, tick → the next node sends), `replyDuringWaitIsIgnored` (Review Focus 3: an inbound during SLEEPING appends no step; the run resumes on its timer), `staleRunningRunFails` (a RUNNING run with `leaseUntil` in the past → FAILED "interrupted", chat pointer cleared).

- [ ] **Step 4: Run to verify it fails, implement timers, ticker and scheduler config (`auto-tick: false` in `application-test.yml`), run `ChatbotTimersIT,ChatbotNodesIT` to verify they pass**

- [ ] **Step 5: Commit** `feat(chatbot): condition, contact, wait and jump nodes; timers`

---

### Task 10: Send template, Assign chat, Add to CRM nodes; agent hand-over; stop from the inbox

**Files:**
- Modify: `SendTemplateExecutor`, `AssignChatExecutor`, `AddToCrmExecutor` (`run` bodies, sharing code with the automation handlers: extract `SendTemplateHandler.failure(...)` mapping into a package-visible `SendFailures` in `service.automation` if the chatbot sender does not already cover it; `AssignChatExecutor` calls `ChatRoutingService.assignByAutomation/roundRobinByAutomation(chat, …, "chatbot: "+name)`; `AddToCrmExecutor` calls `AutomationCrm.addLead` as `AddToCrmHandler` does)
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/run/ChatbotHandoverListener.java`
- Modify: `omnichannel-service/.../service/inbox/{ChatView,InboxViews}.java` (+ `ActiveChatbotView chatbot(runId, chatbotId, name, nodeName)`), `omnichannel-app/.../app/inbox/InboxController.java` (+ `POST /chats/{chatId}/chatbot/stop`, `inbox:send`, visible-chat check as `/assign` does); the runner already publishes `ChatChanged` on every run status change, so the inbox stream carries the chip for free
- Test: `omnichannel-app/src/test/java/.../chatbot/{ChatbotActionsNodesIT,ChatbotHandoverIT}.java`

**Interfaces:**
- Produces: `SendTemplateExecutor.run` → `InboxSendService.send(new ChatbotActor(…), ws, chat, new SendRequest(null, templateId, values), null)` through `ChatbotSender.sendTemplate(ctx, templateId, values)` with the same retry/interrupted mapping as `send`; renders `values` with `ctx.render` first. `AssignChatExecutor.run` → `{assignedTo: {userId|teamId, name}}`; agent cannot see the number → `StepResult.fail`. `AddToCrmExecutor.run` → `{dealId, created}`; already in CRM → `created:false`, output `NEXT`.
- `ChatbotHandoverListener @EventListener on(MessageUpserted e)`: `inserted`, `direction OUT`, `source == CONSOLE`, `chat.chatbot != null`, chatbot `settings.stopOnAgentReply` → `runService.handOver(run, e.message().getSentByUserId())` (HANDED_OVER, pointer cleared). Nothing for `WHATSAPP_WEB`, `CHATBOT`, `CAMPAIGN`, `AUTOMATION`.
- Inbox stop: `POST /api/workspaces/{ws}/inbox/chats/{chatId}/chatbot/stop` → `runService.stop(active, "stopped by "+me.displayName(), me.getId())`, 204; no active run → 404 `NO_CHATBOT`. `ChatView.chatbot` filled from `chat.chatbot`.

- [ ] **Step 1: Write the failing node IT**

`ChatbotActionsNodesIT`: `sendTemplateRendersValuesFromVariables` (template with agent placeholder `order_id`, node values `{order_id: "{{AskOrder.answer}}"}` → device content contains the answer; stored `templateId`), `assignChatToTeamQueue` (chat `assignedTeamId == support`, action reason `by chatbot: <name>`), `assignRoundRobin`, `addToCrmCreatesDealOnce` (two runs → one deal; second `created:false`), `addToCrmWhenCrmOffFails`.

- [ ] **Step 2: Run to verify it fails, implement, run to verify it passes**

- [ ] **Step 3: Write the failing hand-over IT**

`ChatbotHandoverIT`: `consoleReplyHandsOver` (`POST /inbox/chats/{id}/messages` as `agent` → run HANDED_OVER, `GET /inbox/chats/{id}` has `chatbot == null`), `ownEchoDoesNotHandOver` (Review Focus 2: publish `MessageUpserted(chat, OUT row with source WHATSAPP_WEB, inserted=true)` while WAITING → still WAITING), `settingOffKeepsRunning` (`stopOnAgentReply=false`), `stopFromInbox` (`POST /chatbot/stop` as `agent` → STOPPED `stoppedBy == agent.id`; `ChatView.chatbot` null; a second call → 404 `NO_CHATBOT`), `chatViewShowsRunningChatbot` (`chatbot.name`, `chatbot.nodeName == "AskName"`), `agentWithoutSendCannotStop` (403).

- [ ] **Step 4: Run to verify it fails, implement listener, view, endpoint, run `ChatbotHandoverIT,ChatbotActionsNodesIT,InboxApiIT` to verify they pass**

- [ ] **Step 5: Commit** `feat(chatbot): template, assign and CRM nodes; agent hand-over and inbox stop`

---

### Task 11: HTTP request node

**Files:**
- Create: `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/chatbot/node/{HttpAddressGuard,ChatbotHttpClient}.java` (the guard reads `props.chatbot().allowPrivateHttp()` from Task 2)
- Modify: `HttpRequestExecutor.run`
- Test: `omnichannel-service/src/test/java/.../chatbot/node/{HttpAddressGuardTest,HttpRequestExecutorTest}.java`

**Interfaces:**
- Produces: `HttpAddressGuard.check(URI uri)` throws `IllegalArgumentException("private address")` when any resolved address of the host is loopback, link-local, site-local (10/8, 172.16/12, 192.168/16), `169.254/16`, `0.0.0.0`, `::1`, `fc00::/7`, or the host is `localhost`/`*.localhost`/`*.internal`; `allowPrivateHttp` true skips the check so tests can hit a local server. `ChatbotHttpClient.call(HttpRequestSettings s, RunContext ctx) -> Response(int status, Map<String,String> headers, Object body)`: `RestClient` built with `JdkClientHttpRequestFactory` (connect and read timeouts `httpTimeout`), URL/headers/body rendered (`renderJson` when `contentType` is JSON, `render` otherwise), body read capped at `httpMaxBody` (longer → `failure` "response too large"), JSON parsed to `Map`/`List` when the response `Content-Type` is JSON, else text; redirects not followed (a 3xx is a `failure`).
- `HttpRequestExecutor.run`: guard → client → `NEXT` with output `SUCCESS` when 2xx else `FAILURE`; result `{response:{status, headers, body}}`; a timeout or guard refusal → `FAILURE` with `response.error`.

- [ ] **Step 1: Write the failing tests**

`HttpAddressGuardTest`: `refusesLoopback`, `refusesPrivateRanges` (`10.1.2.3`, `172.20.0.1`, `192.168.1.1`, `169.254.1.1`), `refusesLocalhostNames`, `allowsPublicLiteral` (`93.184.216.34`). `HttpRequestExecutorTest` (plain JUnit with `com.sun.net.httpserver.HttpServer` on `127.0.0.1` and the guard allowed): `postsRenderedJsonBody` (body `{"name":"{{AskName.answer}}"}` with answer `O'Brien "Bob"` → server receives valid JSON; header `X-Token: {{trigger.ruleId}}` rendered), `parsesJsonResponse` (`response.body.ok == true`, output `success`), `nonTwoHundredIsFailure` (500), `timeoutIsFailure` (server sleeps past a 300 ms timeout), `oversizeBodyIsFailure`, `privateAddressIsRefused` (Review Focus 5: guard on, URL `http://127.0.0.1:<port>/x` → `failure` and the server saw no request).

- [ ] **Step 2: Run to verify they fail, implement, run to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbot): HTTP request node with an address guard`

---

### Task 12: Automations: Start chatbot action and the "Chatbot is running" fact

**Files:**
- Modify: `omnichannel-db/.../db/automation/{AutomationActionType,AutomationAction}.java` (+ `START_CHATBOT`; + `String chatbotId, IfRunning ifRunning` components; update every `new AutomationAction(` call, tests included), `EventData.ChatData` (+ `boolean chatbotRunning`; update constructors)
- Modify: `omnichannel-service/.../service/automation/{AutomationEventWriter,AutomationFields,ConditionCatalog,RulePublishValidator,RuleShapeValidator,AutomationViews,AutomationTestService,AutomationOptionsService,OptionsView,RuleRunner}.java`
- Create: `omnichannel-service/.../service/automation/StartChatbotHandler.java`
- Test: `omnichannel-app/src/test/java/.../automation/StartChatbotActionIT.java`, additions to `MessageEventIT` and `AutomationRulesApiIT`

**Interfaces:**
- Produces: `AutomationAction(type, templateId, values, mode, userId, teamId, whenAway, pipelineId, stageId, dealTitle, chatbotId, IfRunning ifRunning)` (`db.chatbot.IfRunning` from Task 2). `EventData.ChatData(id, isNew, status, assignedUser, assignedTeam, chatbotRunning)`; `AutomationEventWriter.messageReceived` sets it from `chat.getChatbot() != null`; `AutomationFields.values` puts `chat.chatbotRunning`; `ConditionCatalog.MESSAGE` adds `spec("chat.chatbotRunning", BOOLEAN, Set.of(IS))`.
- `StartChatbotHandler implements ActionHandler` (`type() START_CHATBOT`): chat from the event (none → `failed("No chat with this contact")`); do-not-contact → `skipped`; existing run for `(eventId, ruleId)` (non-test) → report its state, no new start; chatbot not PUBLISHED → `failed("Chatbot “<name>” is not published")`; else `runService.startAndAwaitFirstWait(ws, chatbotId, chat, new StartedBy(ruleId, ruleName, eventId, null), ctx.data(), ifRunning ?: SKIP, ctx.test())` bounded by `props.automation().sendTimeout()`: `started=false` → `skipped(reason)`; WAITING → `done("Started “<name>”: waiting at <node>")`; COMPLETED → `done("Started “<name>”: completed")`; still RUNNING at the bound → `done("Started “<name>”: still running")`; FAILED → `failed(run.endReason)`.
- `RulePublishValidator`: `START_CHATBOT` → `actions[i].chatbotId` "pick a chatbot" / "the chatbot no longer exists" / "the chatbot is a draft". `RuleShapeValidator`: `chatbotId` present. `AutomationViews.typeLabel` "Start chatbot", `part` `"Start “<name>”"` (lookup through a new `Lookups.chatbot(id)`). `AutomationTestService` warning: `"Start chatbot really starts “<name>” on the test contact's chat"`. `OptionsView` + `AutomationOptionsService`: `List<ChatbotOption(id, name, status)> chatbots`. `RuleRunner`: treat `START_CHATBOT` like `SEND_TEMPLATE` for the interrupted-on-resume rule (lines ~138 and ~153: the set `{SEND_TEMPLATE, START_CHATBOT}`).

- [ ] **Step 1: Write the failing ITs**

`StartChatbotActionIT extends ChatbotTestBase` (rules built through the automations API as `MessageEventIT` does): `publishedRuleStartsChatbotOnMessage` (a published chatbot "Lead qualifier", a published rule `MESSAGE_RECEIVED` with no conditions and `START_CHATBOT`; an inbound message → run exists on the chat, `WAITING`; the rule's run step summary starts with `Started “Lead qualifier”: waiting at`), `secondEventWhileRunningIsSkipped` (the customer's reply raises another event; the rule's step is SKIPPED "Chatbot “Lead qualifier” is running"; the reply advanced the run instead), `chatbotRunningConditionIsCaptured` (a rule with `chat.chatbotRunning is false` does not match the reply event; `GET /automations/events` shows `data.chat.chatbotRunning == true` for it), `reclaimedEventFindsExistingRun` (insert a run with `startedBy(eventId, ruleId)` first; running the rule reports it, `chatbotRuns` count stays 1), `moveToDraftKeepsRunningRunsAndRefusesNewStarts` (Review Focus 4), `publishRefusesDraftChatbot` (400 field `actions[0].chatbotId`), `doNotContactSkipped`, `replaceStopsTheOldRun`.
`AutomationRulesApiIT` addition: `optionsListChatbots`. `MessageEventIT` addition: `eventCarriesChatbotRunning`.

- [ ] **Step 2: Run to verify they fail, implement, run `StartChatbotActionIT,MessageEventIT,AutomationRulesApiIT,AutomationWorkerIT,AutomationTestsApiIT,AutomationModelIT` to verify they pass**

- [ ] **Step 3: Commit** `feat(automation): Start chatbot action and the chatbot-running fact`

---

### Task 13: Test start, runs API, editor media and preview, media sweeper

**Files:**
- Modify: `ChatbotController` (+ `POST /{id}/test`, `GET /{id}/runs`, `GET /{id}/runs/{runId}`, `POST /{id}/runs/{runId}/stop`, `POST /{id}/media`, `POST /{id}/preview`), `ChatbotService`/`ChatbotRunService` (+ `test`, `runs`, `run`), `ChatbotViews` (+ `RunView`, `RunStepView`, `RunPage`)
- Modify: `omnichannel-service/.../service/cloud/CloudMediaService.sweepOrphans` (also keep ids referenced by any `chatbots.flow.nodes.data.settings.message.*.id` and any `chatbotVersions.flow…`): collect referenced ids with one aggregation per collection (`$unwind nodes`, project the five media id paths) into a `Set<String>` once per sweep
- Test: `omnichannel-app/src/test/java/.../chatbot/{ChatbotTestRunIT,ChatbotRunsApiIT,ChatbotMediaIT}.java`

**Interfaces:**
- Produces: `POST /{id}/test {contactId, phoneNumberId, stopRunning?: boolean}` (`chatbot:manage`): DRAFT with no ERROR problems (else 400 with the problems as fields); the contact's chat on that number (none → 400 `fields.contactId` "no chat with this contact on the number"); another chatbot's active run → 409 `CodedException("CHATBOT_RUNNING", extra {chatbotName})` unless `stopRunning`, which stops it first; a previous test of this chatbot → stopped; `StartedBy(null, "Test", null, me.id)`, `test=true`, `event` empty; returns the `RunView` (201). `GET /{id}/runs?status=&from=&to=&page=&size=` → `RunPage(items, total)`; `GET /{id}/runs/{runId}` → `RunView(id, chatbotId, chatbotName, versionNumber, chat{id, remoteId}, contact{id, name, phone}, number{id, label}, status, currentNodeId, currentNodeName, test, startedBy, steps[RunStepView(index, nodeId, nodeName, type, startedAt, finishedAt, outcome, output, summary, error, result)], variables, endReason, stoppedBy Ref, createdAt, finishedAt)`; `POST /{id}/runs/{runId}/stop` → 204 (`chatbot:manage`). `POST /{id}/media` (multipart `file` + `type` image|video|document|audio) → `CloudMediaUploadResponse{id}` via `CloudMediaService.upload`; `POST /{id}/preview` (a Cloud message body) → `text/html` via `MessagePageService.previewHtml(ws, m, "0000000000")` for interactive, and a small text/media/location bubble rendered by `MessagePageRenderer` for the other types (extend `PageModel` with the five types; the renderer shows a caption/location card; inert).
- `sweepOrphans` keeps chatbot-referenced media (both collections).

- [ ] **Step 1: Write the failing ITs**

`ChatbotTestRunIT`: `testStartsOnPickedContact` (device gets the first message; run `test==true`, `variables.trigger.ruleName == "Test"`), `testRefusedWithErrors` (400), `testRefusedWhenAnotherChatbotRuns` (409 `CHATBOT_RUNNING`), `stopRunningThenTest`, `secondTestStopsFirst`, `draftNeverStartsOnLiveEvent` (a published rule naming a draft chatbot → step FAILED "is not published"; no run).
`ChatbotRunsApiIT`: `listsRunsNewestFirstWithStatusFilter`, `runDetailHasStepsAndVariables`, `managerStopsRun`, `agentCannotListOrStop` (`agent` → 403 on both), `otherBrand404`.
`ChatbotMediaIT`: `uploadThroughChatbotEndpoint`, `previewRendersButtons` (HTML contains the button titles), `previewRendersImageCaption`, `sweeperKeepsMediaUsedByAFlow` (upload, reference it in a saved flow, advance the clock 2 days, `sweepOrphans` → file still there; an unreferenced upload is gone), `sweeperKeepsMediaUsedByAnOldVersion`.

- [ ] **Step 2: Run to verify they fail, implement, run `ChatbotTestRunIT,ChatbotRunsApiIT,ChatbotMediaIT,CloudMediaIT,MessagePageIT` to verify they pass**

- [ ] **Step 3: Commit** `feat(chatbot): test start, runs API, editor media and preview`

---

### Task 14: Docs, known limits, full verification

**Files:**
- Modify: `docs/superpowers/known-limits.md` (+ "Advanced chatbot: known limits"), `README.md` (a Chatbots API section listing the routes of Tasks 6 and 13 and the inbox stop), `docs/superpowers/project-rules.md` §3 (where the chatbot code lives)

- [ ] **Step 1: Write the known-limits section**

Lines: a phone-typed agent reply does not hand over (the echo race); replies matched by title are best effort; page taps do not drive the flow; a run interrupted mid-node is failed, never resumed; one ticker thread for all brands; HTTP requests follow no redirects and refuse private addresses; versions are pruned to 50; no retention for runs; the Start chatbot step says "still running" after 60 s and the rule goes on; a chatbot moved to draft leaves live runs finishing on their version.

- [ ] **Step 2: Run the full build**

Run: `./mvnw -q verify` (about 5 min, one Maven at a time in this worktree).
Expected: BUILD SUCCESS, zero failures.

- [ ] **Step 3: Commit** `docs(chatbot): known limits and API notes`
