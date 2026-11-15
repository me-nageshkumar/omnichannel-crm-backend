# AI node — backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A chatbot node `ai` that runs single calls or customer conversations through a provider-neutral AI module,
OpenAI Responses first, with brand connections (encrypted BYOK keys) and profiles.

**Architecture:** A new Maven module `omnichannel-ai` holds the provider SPI, the OpenAI adapter, the turn engine,
the app-side MCP client, brand connections/profiles and a MongoDB turn queue; the chatbot node in
`omnichannel-service` sees only `ai.api`. A turn is queued, the run waits on a new task kind `AI_RESPONSE`, and the
worker's result answers the run.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB, Jackson 3 (`tools.jackson`), Spring `RestClient` on the
JDK `HttpClient`, JUnit 5 + AssertJ, `com.sun.net.httpserver` stubs, flapdoodle Mongo in `omnichannel-app` ITs.

**Spec:** `docs/superpowers/specs/2026-11-15-ai-node-design.md` (read it first; § numbers below refer to it).

## Global Constraints

- Collections camelCase: `aiConnections`, `aiProfiles`, `aiConversations`, `aiCalls`. Kept, no TTL.
- No "template" naming anywhere in this feature.
- `omnichannel-ai` imports only `me.nageshk.omnichannel.{ai,common,security.access,security.auth}.`;
  `omnichannel-service` imports only `ai.api.` from it.
- A secret (API key, MCP secret) never appears in an API response, a log line, `chatbots.flow`, run variables, steps or
  `aiCalls`. Views carry `keyHint` / `secretHint` and `keySetAt` / `secretSetAt` only.
- Errors use the existing `common.api` exceptions (`ValidationException` field maps, `NotFoundException`,
  `CodedException(status, code, field, message)`); no new exception handler.
- Output ids exactly: `next`, `failure`, `outcome:<id>`, `exitWord`, `exitCondition`, `maxTurns`.
- Work in `.worktrees/ai-node`; test first; one Maven build at a time; full `./mvnw -q verify` once at the end.
- Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Rulings made while planning (spec amendments, already reflected in the spec commit of this plan)

- **R1 — no `pendingReplies` field.** The spec's `$push` onto the run would race the runner's own full save
  (`@Version`). Instead the inbox is the queue: the node keeps `consumedUntil` (the `creationTime` of the last
  customer message fed to the AI) in its node state, and reads unconsumed inbound messages from `waWebMessages`
  (chat, `direction IN`, `source CONTACT`, `creationTime > consumedUntil`, `timestamp ≥ conversation start`, real
  types) when an AI answer comes back and before every WHATSAPP_REPLY wait. Nothing is lost even if a message lands
  while the run is RUNNING.
- **R2 — timers off during AI_RESPONSE.** `ChatbotTimers.onWait` treats AI_RESPONSE like TIMER (no reminder, no
  inactivity end while the bot itself is busy); the runner's timer condition includes AI_RESPONSE.
- **R3 — AI settings live in the AI module.** `AiProperties` (`omnichannel.ai.*`) is the module's own
  `@ConfigurationProperties`; `SecretsProperties` (`omnichannel.secrets.keys`) is in common.

## Review Focus

1. A key replaced while turns are queued: the next claimed turn must use the new key (credentials are opened at
   claim time, never stored on the `aiCalls` row). → test in Task 7.
2. A history import inserting old customer messages into a chat with a live AI conversation must not feed them to the
   AI (the `timestamp ≥ conversation start` filter). → test in Task 9.
3. A profile edited (model, instructions) between turns of a live conversation: later turns use the new values; a
   connection switched to another provider mid-conversation fails the turn with `PROVIDER_CHANGED`, not a crash.
   → test in Task 7.
4. An OpenAI reply with both text and a `finish` call, or only a finish call with an empty `reply`: the customer gets
   the finish `reply` if any, else the text, else nothing, and the outcome is taken. → test in Task 9.
5. A chatbot published with an AI node whose profile is later deleted is refused at delete (409), and a draft-only
   use is also counted. → test in Task 5.

---

### Task 1: Secrets in common (`SecretBox`) and the shared address guard

**Files:**
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/secret/{SealedSecret,SecretBox,SecretsProperties}.java`
- Move: `omnichannel-service/.../service/chatbot/node/HttpAddressGuard.java` → `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/net/HttpAddressGuard.java` (public class, public methods); move its test likewise; update `ChatbotHttpClient` and `HttpRequestExecutorTest` imports.
- Modify: `omnichannel-app/src/main/resources/application.yml` (`omnichannel.secrets.keys: ${OMNICHANNEL_SECRETS_KEYS:}`), `omnichannel-app/src/test/resources/application-test.yml` (one fixed test key `t1:<base64 of 32 bytes>`).
- Test: `omnichannel-common/src/test/java/me/nageshk/omnichannel/common/secret/SecretBoxTest.java`

**Interfaces:**
- Produces: `record SealedSecret(String keyId, String iv, String ciphertext)` (base64); `@Component SecretBox(SecretsProperties)` with `SealedSecret seal(String plain)`, `String open(SealedSecret s)`, `boolean ready()`, `static String hint(String plain)`; `@ConfigurationProperties("omnichannel.secrets") record SecretsProperties(List<String> keys)` (each `id:base64`, first seals); `common.net.HttpAddressGuard` unchanged API.

- [ ] **Step 1: Write the failing tests** — `SecretBoxTest`:
  - `sealThenOpenRoundTrips` (seal "sk-test-123" → open equals it; ciphertext does not contain it).
  - `twoSealsDiffer` (same plain → different `iv` and `ciphertext`).
  - `oldKeyStillOpensAfterRotation` (sealed with box `[a:K1]`, opened by box `[b:K2, a:K1]`; new seals use `b`).
  - `tamperedCiphertextFails` (flip a byte → `IllegalStateException`).
  - `noKeysMeansNotReadyAndSealThrows` → `CodedException` status 503, code `secrets_unavailable`, message "Secrets
    cannot be saved: the server has no encryption key configured".
  - `badKeyConfigFailsAtStartup` (key not 32 bytes → `IllegalStateException` in the constructor).
  - `hint`: `"sk-proj-abcdef1234a91F"` → `"sk-…a91F"`; `"short"` (length < 10) → `"••••"`; null → null.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-common test -Dtest=SecretBoxTest` — Expected: compile failure (classes missing).
- [ ] **Step 3: Implement** AES/GCM/NoPadding, 12-byte random IV, 128-bit tag, key id stored with the secret; `open` picks the key by `keyId`.
- [ ] **Step 4: Move the guard** (git mv both files, change package to `common.net`, make it public). Run `./mvnw -q -pl omnichannel-common,omnichannel-service -am test -Dtest='SecretBoxTest,HttpAddressGuardTest,HttpRequestExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(common): sealed secrets with key rotation; address guard moved to common`.

### Task 2: Module `omnichannel-ai`, the API package, the provider SPI and boundaries

**Files:**
- Create: `omnichannel-ai/pom.xml` (deps: omnichannel-common, omnichannel-security, spring-boot-starter-webmvc, -data-mongodb, -validation; test: spring-boot-starter-test); root `pom.xml` module list (after crm, before service) and dependencyManagement entry; `omnichannel-service/pom.xml` adds `omnichannel-ai`.
- Create `ai.api`: `AiGateway`, `TurnRequest`, `TurnResult`, `TurnStatus`, `AiFinish`, `AiUsage`, `AiOverrides`, `AiOutcome`, `AiField`, `AiFieldType`, `AiReplyFormat`, `AiCapabilities`, `StateMode`, `AiProfileRef`, `AiProfileInfo`, `AiTurnListener`, `AiProfileUsage`.
- Create `ai.provider`: `AiProvider`, `AiProviders`, `AiCredential`, `ProviderRequest`, `ProviderReply`, `AiItem` (sealed: `UserText`, `AssistantText`, `ToolCallItem`, `ToolResultItem`), `AiTool` (sealed: `FunctionTool`, `HostedMcpTool`, `FileSearchTool`, `WebSearchTool`), `ToolChoice`, `ToolCall`, `AiProviderException`, `AiModelInfo`, `VectorStoreInfo`.
- Create `ai.config.AiProperties`.
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/{AiModuleBoundaryTest,provider/AiProvidersTest}.java`; modify `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/ServiceBoundaryTest.java`.

**Interfaces (produces — every later task uses these names):**
```java
// ai.api
interface AiGateway {
  String submit(TurnRequest r);                                  // inserts a PENDING aiCalls row, returns its id
  Optional<TurnResult> result(String workspaceId, String callId);
  List<AiProfileRef> profiles(String workspaceId);               // names only, for the node picker
  Optional<AiProfileInfo> profile(String workspaceId, String profileId);
}
record TurnRequest(String workspaceId, String runId, String chatbotId, String nodeName, String profileId,
    AiOverrides overrides, String extraInstructions, List<String> inputs, List<AiOutcome> outcomes,
    List<AiField> collect, boolean forceFinish, AiReplyFormat replyFormat,
    String conversationId /* null = new */, String supersedesCallId /* null = none */) {}
record TurnResult(String callId, TurnStatus status /* DONE|FAILED */, String text, Map<String,Object> json,
    AiFinish finish, AiUsage usage, boolean toolsUsed, String conversationId, String runId, String errorCode, String error) {}
record AiFinish(String outcome, Map<String,Object> fields, String reply) {}
record AiUsage(long inputTokens, long outputTokens, long cachedTokens, long reasoningTokens) {}
record AiOverrides(String model, String instructions, String reasoningEffort, Integer maxOutputTokens, Double temperature) {}
record AiOutcome(String id, String label, String description) {}
record AiField(String key, String description, AiFieldType type /* TEXT|NUMBER|BOOLEAN */) {}
record AiReplyFormat(Type type /* TEXT|JSON */, Map<String,Object> schema, String messageField) {}
record AiCapabilities(StateMode state /* CHAINED|TRANSCRIPT */, boolean hostedMcp, boolean vectorStores,
    boolean functionTools, boolean structuredOutput, boolean reasoningEffort, boolean temperature, boolean webSearch) {}
record AiProfileRef(String id, String name, String provider) {}
record AiProfileInfo(String id, String name, String provider, AiCapabilities capabilities, String model,
    String instructions, String reasoningEffort, Integer maxOutputTokens, Double temperature) {}
interface AiTurnListener { void turnFinished(TurnResult r); }   // service implements; the worker calls every bean
interface AiProfileUsage { List<String> chatbotsUsing(String workspaceId, String profileId); } // app adapter
// ai.provider
interface AiProvider { String id(); String label(); AiCapabilities capabilities(); void verify(AiCredential c);
  List<AiModelInfo> models(AiCredential c); List<VectorStoreInfo> vectorStores(AiCredential c);
  ProviderReply respond(AiCredential c, ProviderRequest r); }
record AiCredential(String apiKey) {}
record ProviderRequest(String model, String instructions, List<AiItem> input, Map<String,Object> state,
    List<AiTool> tools, ToolChoice toolChoice, AiReplyFormat format, String reasoningEffort, Integer maxOutputTokens,
    Double temperature, Map<String,String> metadata) {}
record ProviderReply(String text, List<ToolCall> toolCalls, Map<String,Object> state, AiUsage usage,
    String responseId, boolean usedHostedTools) {}
record ToolCall(String id, String name, String argumentsJson) {}
record ToolChoice(Mode mode /* AUTO|REQUIRED|NAMED */, String name) {}
class AiProviderException extends RuntimeException { enum Code { AUTH, RATE_LIMIT, TIMEOUT, BAD_REQUEST,
  CONTEXT_TOO_LONG, STATE_EXPIRED, APPROVAL_REQUIRED, UNAVAILABLE } Code code(); Duration retryAfter(); }
@Component class AiProviders { AiProviders(List<AiProvider>); AiProvider of(String id) /* NotFoundException */; List<AiProvider> all(); }
@ConfigurationProperties("omnichannel.ai") record AiProperties(boolean autoTick /*true*/, Duration tick /*2s*/,
  int poolSize /*8*/, Duration requestTimeout /*60s*/, Duration turnTimeout /*120s*/, int attempts /*2*/,
  int maxToolRounds /*8*/, Duration redeliverAfter /*10s*/, OpenAi openai /* baseUrl https://api.openai.com/v1 */,
  Mcp mcp /* toolTimeout 20s, maxResult 65536, cacheFor 5m */) {}
```

- [ ] **Step 1: Write the failing tests**:
  - `AiModuleBoundaryTest.aiImportsOnlyCommonAndSecurity` (copy of `CrmModuleBoundaryTest` with `ai.` allowed).
  - `ServiceBoundaryTest.serviceImportsOnlyTheAiApi` (any `me.nageshk.omnichannel.ai.` import must start with `ai.api.`).
  - `AiProvidersTest`: `duplicateIdsFailAtStartup`, `unknownIdIsNotFound`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai -am test` — Expected: FAIL (module/classes missing).
- [ ] **Step 3: Implement** the pom wiring and the types above (records only; `AiProviders` logic). Add `omnichannel.ai` defaults to `application.yml`; `application-test.yml` sets `omnichannel.ai.auto-tick: false`.
- [ ] **Step 4: Run** `./mvnw -q -pl omnichannel-ai,omnichannel-service -am test -Dtest='AiModuleBoundaryTest,AiProvidersTest,ServiceBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): omnichannel-ai module, provider SPI and module boundaries`.

### Task 3: OpenAI adapter

**Files:**
- Create: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/provider/openai/{OpenAiProvider,OpenAiRequests,OpenAiReplies}.java`
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/provider/openai/{OpenAiRequestsTest,OpenAiRepliesTest,OpenAiProviderTest}.java`, fixtures `omnichannel-ai/src/test/resources/openai/*.json`.

**Interfaces:**
- Consumes: Task 2 SPI, `AiProperties.openai().baseUrl()`, `requestTimeout`.
- Produces: bean id `"openai"`, label `"OpenAI"`, capabilities `CHAINED, hostedMcp=true, vectorStores=true, functionTools=true, structuredOutput=true, reasoningEffort=true, temperature=true, webSearch=true`; `static Map<String,Object> OpenAiRequests.body(ProviderRequest)`; `static ProviderReply OpenAiReplies.parse(JsonNode)`; state key `"responseId"`.

- [ ] **Step 1: Write the failing tests**:
  - `OpenAiRequestsTest.firstTurn` — golden `first-turn.json`: `model`, `instructions`, `input` `[{role:"user", content:"Hi"}]`, `store: true`, no `previous_response_id`, `metadata`.
  - `chainedTurnSendsPreviousIdAndInstructionsAgain` — state `{responseId:"resp_1"}` → `previous_response_id: "resp_1"` and `instructions` still present.
  - `toolsMapping` — finish `FunctionTool` → `{type:"function", name:"finish", strict:true, parameters}`; `HostedMcpTool` → `{type:"mcp", server_label, server_url, headers, allowed_tools, require_approval:"never"}`; `FileSearchTool` → `{type:"file_search", vector_store_ids}`; `WebSearchTool` → `{type:"web_search"}`; `ToolChoice NAMED finish` → `{type:"function", name:"finish"}`.
  - `toolResultItem` → `{type:"function_call_output", call_id, output}`; `jsonFormat` → `text.format {type:"json_schema", name:"reply", schema, strict:true}`; null overrides omitted.
  - `OpenAiRepliesTest`: `textReply` (joins `output_text` parts), `functionCallReply` (→ `ToolCall(call_id, name, arguments)`), `usageMapped` (`input_tokens`, `output_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens`), `hostedMcpCallSetsUsedHostedTools` (an `mcp_call` item), `approvalRequestThrows` (`mcp_approval_request` → `APPROVAL_REQUIRED`).
  - `OpenAiProviderTest` against a `HttpServer` stub: `sendsBearerKey`, `401IsAuth`, `429IsRateLimitWithRetryAfter` (header `retry-after: 3` → 3 s), `previousResponseNotFoundIsStateExpired` (400 with `error.code` `previous_response_not_found`), `contextLengthIsContextTooLong`, `5xxIsUnavailable`, `slowServerIsTimeout` (request timeout 200 ms in the test), `modelsListed` (`GET /models`), `vectorStoresListed` (`GET /vector_stores`, `name`, `file_counts.completed`), `errorBodyNeverContainsKey` (the stub echoes the Authorization header in its error; the exception message has the key replaced by `«key»`).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai -am test -Dtest='OpenAi*Test'` — Expected: FAIL.
- [ ] **Step 3: Implement** with `RestClient` on `JdkClientHttpRequestFactory` (read timeout = `requestTimeout`), Jackson 3 `JsonMapper`; `verify` = `GET /models?limit=1`.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): OpenAI Responses adapter`.

### Task 4: Connections, providers endpoint and authorities

**Files:**
- Create `ai.connection`: `AiConnection` (`@Document("aiConnections")`, unique `{workspaceId, name}`), `AiConnectionStatus`, `AiConnectionRepository`, `AiConnectionService`, `AiConnectionController`, `AiConnectionViews`, `AiProvidersController`.
- Modify: `omnichannel-security/.../security/access/Authority.java` (add `AI_VIEW("ai:view", BRAND, "See the brand's AI connections and profiles", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`, `AI_MANAGE("ai:manage", BRAND, "Add and change AI connections, keys and profiles", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`), `AuthorityTest` and `RoleSeederIT` pins 50 → 52.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/{AiTestBase,AiConnectionsApiIT}.java`, `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/support/{ScriptedAiProvider,OpenAiStub}.java`.

**Interfaces:**
- Consumes: `SecretBox`, `AiProviders`.
- Produces: `GET /api/workspaces/{ws}/ai/providers` → `[{id, label, capabilities}]` (`ai:view` or `chatbot:manage`); `GET|POST /api/workspaces/{ws}/ai/connections`, `GET|PUT|DELETE .../connections/{id}`, `POST .../{id}/verify`, `GET .../{id}/models` → `[{id}]`, `GET .../{id}/vector-stores` → `[{id, name, fileCount}]`. View `{id, name, provider, keyHint, keySetAt, status, checkedAt, lastError, version}`; save `{name, provider, apiKey?, version?}`. `AiConnectionService.credential(String ws, String id) -> AiCredential` (opens the key; used by Tasks 5–7). Test helpers: `ScriptedAiProvider` (id `"scripted"`, `TRANSCRIPT`, no hosted tools, `functionTools=true`, `structuredOutput=true`, `temperature=false`; queue of scripted `ProviderReply`s or exceptions, records every `ProviderRequest`, optional latch to hold a reply) registered via `@TestConfiguration`; `OpenAiStub` (an `HttpServer` with a queue of canned responses and the recorded requests; tests point `omnichannel.ai.openai.base-url` at it with `@DynamicPropertySource`).

- [ ] **Step 1: Write the failing tests** — `AiConnectionsApiIT`:
  - `createVerifiesAndShowsOnlyAHint` (POST with `apiKey` → 201 view `keyHint "sk-…a91F"`, `status OK` after the stub's `/models` 200; the raw response body does not contain the key; Mongo document holds `apiKey.ciphertext`, not the key).
  - `putWithoutKeyKeepsIt`, `putWithKeyReplacesIt` (`keySetAt` changes; the stub now sees the new bearer).
  - `badKeyIsInvalid` (stub 401 → `status INVALID`, `lastError` "The provider refused the key").
  - `unknownProviderIs400` (field `provider`), `duplicateNameIs409` (`DuplicateException`), `createWithoutKeyIs400` (field `apiKey`).
  - `noSecretsKeyIs503` (a context with empty `omnichannel.secrets.keys`, code `secrets_unavailable`) — use a nested `@SpringBootTest(properties=...)` class.
  - `modelsAndVectorStoresListed`; `otherBrandIs404`; `agentWithoutAiViewIs403`; `chatbotEditorCanListProviders`.
  - `keyNeverLogged` (capture logs with an appender on `me.nageshk.omnichannel.ai`; no line contains the key after create, verify and a 401).
  - pins: `AuthorityTest` 52, `RoleSeederIT` 52.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest='AiConnectionsApiIT,AuthorityTest,RoleSeederIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement**; access with `@PreAuthorize("@access.has(#workspaceId, 'ai:manage')")` for writes and `'ai:view'` for reads (providers: either `ai:view` or `chatbot:manage`); delete of a connection used by a profile → `CodedException(409, "ai_connection_in_use", null, "Used by profiles: A, B")`.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): brand AI connections with write-only keys; ai:view and ai:manage`.

### Task 5: Profiles, capability checks and the usage port

**Files:**
- Create `ai.profile`: `AiProfile` (`@Document("aiProfiles")`, unique `{workspaceId, name}`), `McpServer` (embedded: `id, label, url, connectBy, auth, headerName, secret: SealedSecret, secretHint, secretSetAt, allowedTools`), `ConnectBy {PROVIDER, APP}`, `McpAuth {NONE, BEARER, HEADER}`, `AiProfileRepository`, `AiProfileService`, `AiProfileController`, `AiProfileViews`.
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/ai/AiProfileUsageAdapter.java` (implements `AiProfileUsage` by scanning `chatbots.flow` and `chatbotVersions.flow` of the workspace for nodes `type == "ai"` with `settings.profileId`; returns chatbot names).
- Modify: `omnichannel-service/.../service/chatbot/ChatbotOptionsService.java` — `ChatbotOptions` gains `List<AiProfileRef> aiProfiles` from `AiGateway.profiles(ws)` (needs `chatbot:view` only).
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/AiProfilesApiIT.java`.

**Interfaces:**
- Produces: `GET|POST /api/workspaces/{ws}/ai/profiles`, `GET|PUT|DELETE .../profiles/{id}`, `POST .../profiles/{id}/mcp/{serverId}/tools` → `[{name, description}]` (APP servers only; Task 6 client). View: profile fields + `mcpServers[{id, label, url, connectBy, auth, headerName, secretHint, secretSetAt, allowedTools}]` + `version`; save carries `secret` per server only to set/replace it (absent = keep; server `id` matches the stored one). `AiProfileService.resolved(ws, profileId) -> ResolvedProfile(AiProfile, AiConnection, AiProvider)` for Task 7. `AiGateway.profiles/profile` implemented here.

- [ ] **Step 1: Write the failing tests** — `AiProfilesApiIT`:
  - `createAndRead` (OpenAI connection; model `gpt-5`, instructions, vector store ids, one PROVIDER MCP server with a BEARER secret → view has `secretHint`, no secret anywhere in the body).
  - `mcpLabelRules` (`[a-z0-9_]{1,20}`, unique per profile → field `mcpServers.1.label`); `mcpUrlMustBeHttps` (field `mcpServers.0.url`); APP server URL to `127.0.0.1` passes only because the test profile allows private addresses — assert the guard is called by a unit test in Task 6 instead.
  - `capabilityChecks` with the `scripted` provider: PROVIDER MCP server → 400 field `mcpServers.0.connectBy` "This provider cannot connect to MCP servers itself; choose Our app"; `vectorStoreIds` → 400 field `vectorStoreIds`; `temperature` → 400 field `temperature`; `webSearch` → 400.
  - ranges: `maxOutputTokens` 1–128000, `temperature` 0–2, `reasoningEffort` ∈ {minimal, low, medium, high}, `instructions` ≤ 20000, `allowedTools` ≤ 50 names.
  - `secretKeptWhenAbsentAndReplacedWhenSent`.
  - `deleteUsedByDraftOrPublishedIs409` (code `ai_profile_in_use`, message names the chatbot) — Review Focus 5.
  - `chatbotOptionsListProfilesForChatbotEditors` (a chatbot:view user without ai:view gets `aiProfiles` `[{id, name, provider}]`).
  - `otherBrandIs404`.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-app -am test -Dtest=AiProfilesApiIT -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): brand AI profiles with provider capability checks`.

### Task 6: App-side MCP client

**Files:**
- Create `ai.mcp`: `McpClient`, `McpTool`, `McpException`.
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/mcp/McpClientTest.java` (`HttpServer` stub speaking Streamable HTTP).

**Interfaces:**
- Consumes: `common.net.HttpAddressGuard`, `AiProperties.mcp()`.
- Produces: `List<McpTool> tools(McpEndpoint e)` (cached `cacheFor` per url+secret hash), `String call(McpEndpoint e, String tool, String argumentsJson)` (text content joined; capped at `maxResult` with "…[cut]"), `record McpEndpoint(String url, Map<String,String> headers)`, `record McpTool(String name, String description, Map<String,Object> inputSchema)`; the profile tools endpoint (Task 5) calls `tools`.

- [ ] **Step 1: Write the failing tests**: `initializeThenListTools` (sends `initialize`, keeps `Mcp-Session-Id`, sends `notifications/initialized`, then `tools/list`); `sseResponseParsed` (a `text/event-stream` reply with one `data:` JSON-RPC message); `callReturnsTextContent`; `toolErrorIsMcpException` (`isError: true` → message is the text); `resultCapped`; `slowToolTimesOut` (timeout 200 ms); `privateAddressRefused` (guard with private off → `McpException` "address not allowed"); `headersSent` (Bearer / custom header).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai -am test -Dtest=McpClientTest` — Expected: FAIL.
- [ ] **Step 3: Implement** JSON-RPC 2.0 over `RestClient` POST with `Accept: application/json, text/event-stream`, protocol version `2025-06-18`.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): app-side MCP client over Streamable HTTP`.

### Task 7: Turn engine, conversations, queue and worker

**Files:**
- Create `ai.conversation`: `AiConversation` (`@Document("aiConversations")`: `workspaceId, ownerType, ownerId, nodeName, provider, profileId, state, transcript (List<Map>), openCallIds, turns, createdAt, updatedAt`), repository.
- Create `ai.call`: `AiCall` (`@Document("aiCalls")`, fields per spec §5.7 plus `request` (the `TurnRequest` as a map), `stateBefore`, `transcriptSizeBefore`), `AiCallStatus {PENDING, RUNNING, DONE, FAILED}`, `AiTurnQueue` (interface), `MongoAiTurnQueue`, `AiTurnWorker`, `AiGatewayService implements AiGateway`.
- Create `ai.turn`: `AiTurnRunner`, `EffectiveConfig`, `FinishTool`.
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/turn/{AiTurnRunnerTest,FinishToolTest,EffectiveConfigTest}.java`; `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/{AiTurnQueueIT,OpenAiChainIT}.java`.

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces: `AiTurnQueue { String enqueue(AiCall c); Optional<AiCall> claim(Instant now); void finish(AiCall c); List<AiCall> undelivered(Instant finishedBefore); void delivered(String id); }`; `AiTurnWorker.tick()` (public, tests call it; claims until empty, runs on the AI pool, returns a `CompletableFuture<Void>`); `AiTurnRunner.run(AiCall call) -> TurnResult`; the finish tool name `"finish"`, parameters `{outcome: {type:"string", enum:[ids]}, fields: {type:"object", properties: per collect key (string|number|boolean, each also nullable), required: every key, additionalProperties:false}, reply: {type:["string","null"]}}`, required `["outcome","fields","reply"]`, `additionalProperties:false` (OpenAI strict mode needs every property required); app-side MCP tool names `<label>__<tool>`.

Behaviour the tests pin (spec §5.4–§5.5):
- Effective config: node override ?? profile; extraInstructions appended after a blank line.
- Credentials are opened from the connection at **claim time** (Review Focus 1); the `aiCalls` row never holds them.
- CHAINED provider: send only the new inputs plus `ToolResultItem`s for `openCallIds`; state from the conversation. TRANSCRIPT provider: send the whole transcript + new inputs. Transcript always appended (cap 100 items, oldest dropped).
- `STATE_EXPIRED` → one retry with the transcript and empty state.
- Tool loop: app-side calls run through `McpClient`, results fed back, ≤ `maxToolRounds`, then FAILED `TOOL_ROUNDS`; a `finish` call ends the loop; its call id is added to `openCallIds`.
- `RATE_LIMIT`/`UNAVAILABLE` retried twice (backoff 1 s, 4 s, or Retry-After) inside `turnTimeout`; `AUTH` sets the connection `INVALID` and fails with `AUTH`; provider of the profile's connection differs from the conversation's → FAILED `PROVIDER_CHANGED` (Review Focus 3).
- JSON format: `json` parsed from text; parse failure → FAILED `BAD_JSON`.
- `supersedesCallId`: allowed only when that call is the conversation's latest DONE call; state and transcript are restored to its `stateBefore` / `transcriptSizeBefore` before running.
- Worker: claim `PENDING`, or `RUNNING` with `leaseUntil < now` and `attempts < attempts` → `RUNNING`, lease `now + turnTimeout + 30s`, `attempts+1`; exhausted → FAILED `INTERRUPTED`. After `finish`, every `AiTurnListener` bean is called and the row marked delivered; `tick()` re-delivers DONE/FAILED rows with `deliveredAt == null` and `finishedAt < now - redeliverAfter`.
- `submit` wakes the worker (an executor submit) unless `autoTick` is false (tests tick by hand).

- [ ] **Step 1: Write the failing tests**:
  - `EffectiveConfigTest`: `nodeOverridesWin`, `nullInherits`, `extraInstructionsAppended`.
  - `FinishToolTest`: `schemaListsOutcomesAndFields` (golden map), `noOutcomesNoTool`.
  - `AiTurnRunnerTest` (scripted provider + fake MCP client, no Spring): `singleTextTurn`, `transcriptProviderGetsWholeHistory`, `chainedProviderGetsOnlyNewInputs`, `finishEndsLoopAndRecordsOpenCall`, `nextTurnAnswersOpenFinishCall`, `appSideToolLoop`, `toolRoundsExhausted`, `stateExpiredRebuildsFromTranscript`, `rateLimitRetriedThenSucceeds`, `authMarksConnectionInvalid`, `providerChangedFails`, `badJsonFails`, `supersedeRestoresBaseState`, `supersedeOfOlderCallRefused`, `toolsUsedFlag` (true for app-side MCP and hosted MCP, false for file search only).
  - `AiTurnQueueIT`: `submitThenTickDelivers` (a test `AiTurnListener` bean receives the result once), `expiredLeaseIsRetriedOnce`, `exhaustedAttemptsFailInterrupted`, `undeliveredIsRedelivered`, `replacedKeyUsedByQueuedTurn` (Review Focus 1), `profileEditBetweenTurnsUsesNewModel` (Review Focus 3), `rowHoldsNoSecrets` (raw Mongo document of `aiCalls` and `aiConversations` contains neither the key nor an MCP secret).
  - `OpenAiChainIT` (real adapter → `OpenAiStub`): three turns; turn 2 and 3 carry `previous_response_id` of the previous reply and the instructions again; usage summed on rows.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai -am test -Dtest='EffectiveConfigTest,FinishToolTest,AiTurnRunnerTest'` and `./mvnw -q -pl omnichannel-app -am test -Dtest='AiTurnQueueIT,OpenAiChainIT' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement**. Indexes on `aiCalls`: `{status:1, leaseUntil:1}`, `{workspaceId:1, createdAt:-1}`, `{runId:1}`; `aiConversations`: `{ownerType:1, ownerId:1, nodeName:1}`.
- [ ] **Step 4: Run** both commands — Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): provider-neutral turn engine on a MongoDB turn queue`.

### Task 8: Engine plumbing and the AI node in single-call mode

**Files:**
- Modify: `omnichannel-db/.../db/chatbot/TaskKind.java` (+ `AI_RESPONSE`, javadoc key = the aiCalls id), `Waiting.java` (+ `static Waiting ai(String callId, Instant since, Instant expiresAt)`), `omnichannel-service/.../chatbot/run/ChatbotTimers.java` and `ChatbotRunner.java` (R2), `ChatbotTicker.java` (new part `aiExpired`: WAITING runs with `waiting.kind AI_RESPONSE` and `waiting.expiresAt <= now` → `runs.answerNow(TaskAnswer.ai(key, TurnResult failed TIMEOUT))`), `node/TaskAnswer.java` (+ `static TaskAnswer ai(String callId, TurnResult r)`, payload key `"result"`), `flow/NodeType.java` (+ `AI("ai")`).
- Create: `service/chatbot/flow/settings/AiNodeSettings.java`, `service/chatbot/node/AiExecutor.java`, `service/chatbot/run/AiResponseListener.java` (`TaskAnswerSource` for AI_RESPONSE and `AiTurnListener`; calls `runs.answer(TaskAnswer.ai(...))`).
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/node/AiExecutorValidationTest.java`, `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotAiSingleIT.java`.

**Interfaces:**
- Consumes: `AiGateway`, `TurnRequest`, `TurnResult`, `AiProfileInfo.capabilities()`.
- Produces: `AiNodeSettings(String profileId, Mode mode /* SINGLE|CONVERSATION */, AiOverrides overrides, String extraInstructions, String userMessage, String initialMessage, Boolean sendReply, List<AiOutcome> outcomes, List<AiField> collect, AiReplyFormat replyFormat, List<String> exitWords, ConditionNode exitCondition, Integer maxTurns, String continueFrom)`; node state under `vars[nodeName]`: `{conversationId, turns, lastCallId, supersedes, consumedUntil, startedAt, lastReply, usage{inputTokens, outputTokens}}` (summed usage; the console's run panel reads it); result variables per spec §5.9 (`reply, json, outcome, fields, turns, usage{inputTokens, outputTokens}, conversationId`, plus `errorCode` on `failure`); `outputs()` per Global Constraints; `waitsFor` SINGLE `{AI_RESPONSE}`, CONVERSATION `{WHATSAPP_REPLY, AI_RESPONSE}`.

- [ ] **Step 1: Write the failing tests**:
  - `AiExecutorValidationTest` (a stub `AiGateway`): `profileRequired`, `unknownProfile` ("the AI profile no longer exists"), `outcomeIdsValidAndUnique` (`[a-z0-9_]{1,30}`, field `outcomes.1.id`), `collectKeysValidAndUnique`, `singleNeedsUserMessage`, `jsonNeedsMessageFieldStringProperty` (only CONVERSATION or `sendReply`), `overrideAgainstCapabilities` (temperature on a provider without it → ERROR field `overrides.temperature`), `maxTurnsRange` (1–100), `continueFromMustBeEarlierAiNode`, `conversationWithoutAnyExitWarns`, `outputsPerMode` (SINGLE no outcomes → `[next, failure]`; SINGLE with outcomes a,b → `[outcome:a, outcome:b, failure]`; CONVERSATION with words and a condition → `[outcome:a, exitWord, exitCondition, maxTurns, failure]`).
  - `ChatbotAiSingleIT` (scripted provider): `singleCallStoresReplyAndContinues` (text node after it renders `{{Classify.reply}}`; the run step shows WAITING with AI_RESPONSE until `worker.tick()`, chatbot pool not used meanwhile), `singleWithOutcomesBranches` (finish `outcome b` → edge `outcome:b`; `{{Classify.fields.city}}`), `sendReplyTrueSendsText`, `providerFailureTakesFailure` (`{{Classify.errorCode}}` is the code, e.g. `AUTH`; the step summary names it), `expiredWaitTakesFailure` (clock past `expiresAt`, chatbot ticker → `failure`), `noReminderWhileAiThinks` (reminders on 1 min; clock +5 min while waiting on AI_RESPONSE; ticker; no reminder sent) — R2.
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service -am test -Dtest=AiExecutorValidationTest` and `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotAiSingleIT -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement**; `AiExecutor` extends `BaseExecutor`.
- [ ] **Step 4: Run** both — Expected: PASS; also `-Dtest='ChatbotTimersIT,ChatbotRunIT,NodeValidationIT'` still PASS.
- [ ] **Step 5: Commit** `feat(chatbot): AI node, single-call mode, AI_RESPONSE task`.

### Task 9: Conversation mode

**Files:**
- Modify: `service/chatbot/node/AiExecutor.java`.
- Create: `service/chatbot/node/AiInputs.java` (unconsumed messages → model text, R1), `service/chatbot/node/WhatsAppFormat.java` (markdown → WhatsApp, split at 4096).
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/chatbot/node/{WhatsAppFormatTest,AiInputsTest}.java`, `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/chatbot/ChatbotAiConversationIT.java`.

**Interfaces:**
- Consumes: Task 8.
- Produces: `AiInputs.unconsumed(String chatId, Instant consumedUntil, Instant startedAt) -> List<WaWebMessage>`; `static String AiInputs.text(WaWebMessage m)` (button/list → title; location → `[location: <lat>, <lng> <name>]`; image/video/document/audio/ptt/sticker → `[customer sent a(n) <kind>: <caption>]`); `static List<String> WhatsAppFormat.convert(String markdown)`.

- [ ] **Step 1: Write the failing tests**:
  - `WhatsAppFormatTest`: `**b**`→`*b*`, `__i__`/`*i*`→`_i_`, `# H`→`*H*`, `[t](u)`→`t (u)`, code fences kept as ```` ``` ````, 5000 chars → two parts split at a paragraph or sentence end.
  - `AiInputsTest.text` per type above.
  - `ChatbotAiConversationIT` (scripted provider, `AutomationDevice`):
    - `waitsForFirstMessageThenTalks` (no user message: WAITING WHATSAPP_REPLY; customer "hi" → AI_RESPONSE → reply sent → WHATSAPP_REPLY again; turns 1).
    - `initialMessageAndUserMessageStartAtOnce`.
    - `finishOutcomeRoutes` (turn 2 finish `booked` with fields → edge `outcome:booked`, `{{Sales.fields.size}}`; finish reply sent) and `finishWithTextAndEmptyReply` (text sent when reply empty; nothing sent when both empty) — Review Focus 4.
    - `exitWordEndsWithoutCallingAi` ("STOP " matches "stop"; no provider request recorded).
    - `exitConditionOnJson` (JSON format, `{{Sales.json.done}} is true` → `exitCondition`; customer gets `messageField`).
    - `maxTurnsReached`.
    - `messageDuringThinkingSupersedes` (provider latch held; second customer message lands; release → first reply not sent, a second request carries both texts joined by "\n"; `supersedesCallId` set) and `noSupersedeAfterToolUse` (scripted reply with `usedHostedTools` → reply sent; the pending message becomes the next turn) and `messageDuringRunningIsNotLost` (message inserted while the run is RUNNING after the answer → picked up before the WHATSAPP_REPLY wait).
    - `historyImportedOldMessageIgnored` (a message with `timestamp` before the conversation start but a new `creationTime` is not sent to the AI) — Review Focus 2.
    - `nonTextAsStandIn` (an image with caption → input "[customer sent an image: red shirt]").
    - `agentReplyHandsOver` (console message → HANDED_OVER; a later AI result for that run is ignored: `claimForAnswer` fails, no message sent).
    - `continueFromReusesConversation` (second node with `continueFrom` → same `conversationId`; open finish call answered).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service -am test -Dtest='WhatsAppFormatTest,AiInputsTest'` and `./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotAiConversationIT -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement** the flow in spec §5.9 with R1.
- [ ] **Step 4: Run** both — Expected: PASS; also `-Dtest='ChatbotRepliesIT,ChatbotHandoverIT,ChatbotTestRunIT'` still PASS.
- [ ] **Step 5: Commit** `feat(chatbot): AI conversation mode with outcomes, exits and superseding`.

### Task 10: Docs and the whole build

**Files:**
- Modify: `README.md` (AI API section), `docs/superpowers/project-rules.md` §2 (`OMNICHANNEL_SECRETS_KEYS`: format `id:base64`, how to generate `openssl rand -base64 32`, back it up; losing it means re-entering every key), `docs/superpowers/known-limits.md` (v1 non-goals from spec §3; a superseded turn's tokens are still billed; after a finish, later messages are left to the inbox).

- [ ] **Step 1:** Write the docs.
- [ ] **Step 2: Run** `./mvnw -q verify` — Expected: BUILD SUCCESS (all modules, boundary tests, pins 52).
- [ ] **Step 3: Commit** `docs: AI node API, secrets key and known limits`.

## Console contract (for `2026-11-15-ai-node-console.md`)

- `GET /api/workspaces/{ws}/ai/providers` → `[{id, label, capabilities:{state, hostedMcp, vectorStores, functionTools, structuredOutput, reasoningEffort, temperature, webSearch}}]`.
- Connections: view `{id, name, provider, keyHint, keySetAt, status: "UNVERIFIED"|"OK"|"INVALID", checkedAt, lastError, version}`; save `{name, provider, apiKey?, version?}`; `POST .../{id}/verify` → view; `GET .../{id}/models` → `[{id}]`; `GET .../{id}/vector-stores` → `[{id, name, fileCount}]`; 409 `ai_connection_in_use`; 503 `secrets_unavailable`.
- Profiles: view `{id, name, connectionId, model, instructions, reasoningEffort, maxOutputTokens, temperature, webSearch, vectorStoreIds, mcpServers:[{id, label, url, connectBy:"PROVIDER"|"APP", auth:"NONE"|"BEARER"|"HEADER", headerName, secretHint, secretSetAt, allowedTools}], updatedAt, version}`; save same minus hints, plus `mcpServers[].secret?`; `POST .../profiles/{id}/mcp/{serverId}/tools` → `[{name, description}]`; 409 `ai_profile_in_use`. Validation errors keyed by field path (`mcpServers.0.connectBy`).
- Chatbot options gain `aiProfiles: [{id, name, provider}]`.
- Node type `ai`, settings as `AiNodeSettings` (JSON names as the record), outputs per Global Constraints, `TaskKind` gains `AI_RESPONSE`, result variables `reply, json.*, outcome, fields.<key>, turns, usage.inputTokens, usage.outputTokens, conversationId`.
- Authorities `ai:view`, `ai:manage` (BRAND).
