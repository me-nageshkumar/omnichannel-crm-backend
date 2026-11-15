# AI node for chatbots: design

Status: approved by the user 2026-11-15. Plans: `docs/superpowers/plans/2026-11-15-ai-node-{backend,console}.md`.


## 1. Intent

Brands want chatbot steps that think: answer product questions in a conversation, collect details, classify a reply,
write a message. The user wants an **AI node** in the chatbot editor, beside the other nodes, that starts with the
**OpenAI Responses API** (conversation continued with `previous_response_id`) and is **built so Claude, DeepSeek and
others slot in later without redesign**. The provider is the first thing chosen, because what a node can do depends
on it. AI client code lives in its **own Maven module**. Access is **bring your own key** (BYOK) for now. The user
called the node complicated and asked for a future-proof architecture.

## 2. What the user decided

| # | Decision |
|---|---|
| D1 | One AI node with a **mode per node**: *single call* (one call, result into variables, flow continues) or *conversation* (talks with the customer over many messages until it ends). |
| D2 | **Brand AI profiles plus node overrides**: a brand page holds connections (provider + key) and profiles (connection, model, system prompt, MCP servers, vector stores); a node picks a profile and may override profile fields field by field (null = inherit, as in the chatbot settings layers). |
| D3 | A conversation ends by **both** routes: the model calls a **finish tool** with one of the node's outcomes (each outcome is an output handle) and collected fields; **and** optional exit words and a JSON-field condition. Turn limit, inactivity end and agent take-over stay as fallbacks. |
| D4 | Vector stores: **pick existing stores** the brand's key can see (listed from the provider). No uploads in v1. |
| D5 | MCP: **each server says who connects**: the provider (hosted MCP) or our app (we are the MCP client and run the tool calls). |
| D6 | Architecture **A**: our own provider interface in a new `omnichannel-ai` module, a neutral request/reply model, one adapter per provider with declared capabilities, conversation state kept per provider. Not raw OpenAI JSON passthrough (locks to OpenAI), not Spring AI (built on chat completions, hides `previous_response_id` and hosted tools). |
| D7 | BYOK only. |

Everything else below is **(my call)** unless marked otherwise.

## 3. Goals, non-goals, anti-goals

Goals: the AI node in both modes on OpenAI; profiles and connections per brand; encrypted keys; hosted and app-side
MCP; vector store pick; outcomes as branches; a provider SPI proven by a second (fake, test-only) provider.

Non-goals (v1): Claude/DeepSeek adapters themselves (the SPI is shaped for them; each is its own later project);
platform-paid keys, wallets, cost computation; uploading files to vector stores; OAuth for MCP servers (static
bearer or header secrets only); customer images/audio/documents sent to the model; streaming; AI in the inbox (agent
assist); platform defaults for AI (the super admin layer the chatbot settings have).

Anti-goals (a failure even if it works): a key that can be read back by anyone, in any API response, log line, run
view or flow JSON; a slow AI call starving the 4 chatbot threads or tripping the 2-minute run lease; the bot and an
agent talking over each other; a customer message silently lost because it arrived while the AI was thinking; node
settings that only make sense for OpenAI leaking into the neutral model.

## 4. Facts the design rests on

- Engine (`omnichannel-service/.../service/chatbot`): node types are the `NodeType` enum (wire codes), executors are
  `NodeExecutor` beans (`run`, `onAnswer`, `validate`, `outputs`, `waitsFor`) collected by `NodeExecutors`; every wait
  is a typed task `Waiting(kind, key, since, expiresAt, prompt)` with `TaskKind {WHATSAPP_REPLY, FORM, TIMER}` and a
  `TaskAnswerSource` per kind; `RunClaims.claimForAnswer(kind, key)` claims a WAITING run; an executor can re-wait on
  the same node (Ask's re-ask). Node results land in `run.variables[nodeName]` for `{{Node.x}}`.
- The HTTP node blocks a pool thread (pool 4) inside `run()` under a 2-minute lease; an AI turn with tools can take
  tens of seconds, so the AI node must not copy that.
- `ChatbotReplyListener` hands a contact's message to the run only if the run waits on `WHATSAPP_REPLY` for that
  chat; otherwise the claim fails and the message is ignored by the bot.
- No secret is encrypted anywhere today (HTTP node headers sit in flow JSON in plain text).
- OpenAI Responses: `previous_response_id` needs stored responses (`store: true`, kept 30 days by OpenAI); the
  `instructions` of a previous response are **not** carried over (resend every turn); all earlier tokens are billed
  again each turn; hosted tools `file_search {vector_store_ids}` and `mcp {server_label, server_url, headers |
  authorization, allowed_tools, require_approval}`; app-side tools are `function` tools whose calls come back as
  `function_call` items and are answered with `function_call_output` items in the next request.
- Reference project (`open_ai_v2`, RedLava) splits a reusable config from node fields, stores keys in plain text and
  returns them from its GET. We keep the split, not the key handling.
- The CRM module is the module-boundary precedent: depends on common + security only, plain import-scanner boundary
  test, host reached through ports with adapters in `omnichannel-app`.

## 5. Architecture

### 5.1 Modules

New module **`omnichannel-ai`**, package `me.nageshk.omnichannel.ai`, placed after `crm` and before `service` in the
root pom. Depends on `omnichannel-common`, `omnichannel-security` (for `@access` checks on its controllers),
spring-boot data-mongodb, webmvc, validation. `omnichannel-service` depends on it.

- `ai.api` — the only package other modules may import: `AiGateway`, request/reply records, `AiEvents`, profile and
  connection lookups the node needs. Boundary tests: `AiModuleBoundaryTest` (ai imports only `ai.`, `common.`,
  `security.access.`, `security.auth.`), and `ServiceBoundaryTest` extended so service imports only `ai.api.` from
  `ai`.
- `ai.provider` — the SPI and `ai.provider.openai` (first adapter). A provider is a Spring bean; adding Claude is a new
  package with one bean.
- `ai.connection`, `ai.profile` — brand documents, services, controllers.
- `ai.turn` — the provider-neutral turn engine (tool loop, finish tool, app-side MCP calls).
- `ai.mcp` — the app-side MCP client.
- `ai.conversation`, `ai.call` — conversation state and the call log.
- The chatbot node itself (`AiExecutor`, `AiNodeSettings`) lives in `omnichannel-service` with the other nodes
  (user's instruction).
- Secret sealing goes in **`omnichannel-common`** (`common.secret.SecretBox`) so later features (HTTP node auth) reuse
  it.

### 5.2 Provider SPI (`ai.provider`)

```java
interface AiProvider {
  String id();                                   // "openai"
  String label();                                // "OpenAI"
  AiCapabilities capabilities();
  void verify(AiCredential c);                   // cheap authenticated call; throws AiProviderException
  List<AiModelInfo> models(AiCredential c);      // live list for the profile form
  List<VectorStoreInfo> vectorStores(AiCredential c);   // empty unless capabilities.vectorStores
  ProviderReply respond(AiCredential c, ProviderRequest r);   // ONE provider round trip
}
record AiCapabilities(StateMode state,           // CHAINED (provider keeps history by id) | TRANSCRIPT (we resend it)
    boolean hostedMcp, boolean vectorStores, boolean functionTools, boolean structuredOutput,
    boolean reasoningEffort, boolean temperature, boolean webSearch) {}
```

- `ProviderRequest`: model, instructions, `List<AiItem> input` (neutral items: user text, assistant text, tool call,
  tool result), `ProviderState state` (opaque map the adapter owns; OpenAI: `{responseId}`), tools (neutral
  `FunctionTool{name, description, jsonSchema}`, `HostedMcpTool{label, url, headers, allowedTools}`,
  `FileSearchTool{vectorStoreIds}`, `WebSearchTool`; hosted MCP is always sent with `require_approval: "never"`,
  since no person is in the loop, and is limited by `allowedTools`; the form says so and recommends listing the
  allowed tools; an approval request that comes back anyway fails the turn with `APPROVAL_REQUIRED`), toolChoice (AUTO | REQUIRED | NAMED), output format (TEXT |
  JSON schema), reasoningEffort, maxOutputTokens, temperature, metadata.
- `ProviderReply`: text, `List<ToolCall>` (id, name, argumentsJson), new `ProviderState`, usage (input, output,
  cached, reasoning tokens), finish reason, provider response id, raw error mapping via `AiProviderException(code:
  AUTH | RATE_LIMIT | TIMEOUT | BAD_REQUEST | CONTEXT_TOO_LONG | STATE_EXPIRED | UNAVAILABLE, retryAfter)`.
- The console reads `GET /api/workspaces/{ws}/ai/providers` → `[{id, label, capabilities}]` and shows only the fields a
  provider supports (user's point 3). Save-time validation uses the same capabilities.

### 5.3 OpenAI adapter (`ai.provider.openai`)

Spring `RestClient` on the JDK client (same as `ChatbotHttpClient`), Jackson 3, no SDK (the official SDK brings
Jackson 2 and OkHttp). `POST https://api.openai.com/v1/responses` with `model, instructions, input,
previous_response_id, store: true, tools, tool_choice, text.format, reasoning.effort, max_output_tokens, temperature,
metadata {runId, nodeName}`. `GET /v1/models` (verify, models), `GET /v1/vector_stores` (picker). Maps
`function_call` output items to `ToolCall`s; tool results go back as `function_call_output` with
`previous_response_id` of the response that asked. `STATE_EXPIRED` when OpenAI says the previous response is not found
→ the turn engine retries once from our transcript with no previous id. Base URL configurable per connection only in
tests (`omnichannel.ai.openai.base-url`), so the IT runs against a local stub server.

### 5.4 Turn engine (`ai.turn`)

`AiTurnRunner.runTurn(TurnSpec) -> TurnOutcome`, provider-neutral:

1. Resolve the profile + node overrides into one effective config (field by field, node ?? profile ?? provider
   default).
2. Build tools: the **finish tool** `finish(outcome: enum of the node's outcome ids, fields: {declared collect keys},
   reply?: string)` as a strict function tool when the node declares outcomes; app-side MCP tools (each server's tool
   list, names prefixed `<label>__<tool>`); hosted MCP / file search / web search as hosted tools.
3. Loop: `provider.respond` → if tool calls for app-side MCP tools, run them (§5.6) and send results back → up to
   `maxToolRounds` (default 8). A finish call stops the loop with that outcome; we answer it with a
   `"finished"` tool result only if the chain is later continued (OpenAI requires an output for every call).
4. Retry `RATE_LIMIT`/`UNAVAILABLE` twice with backoff (honouring Retry-After) inside the turn timeout; `AUTH` marks
   the connection `INVALID` with the time and fails the turn.
5. Record the conversation state and an `aiCalls` row (§5.7).

`TurnOutcome`: text, json (parsed when the format is JSON), finish (outcome id, fields) or null, usage, error.

### 5.5 Async execution on a MongoDB queue, so the chatbot pool is never blocked

The user's standing rule for background work applies: a MongoDB collection as the queue, claimed with
`findAndModify` and a lease, behind an interface (`AiTurnQueue`) so SQS or Kafka can replace it; processed items stay
as kept history, no TTL.

- New `TaskKind.AI_RESPONSE`, key = the `aiCalls` id. The node submits the turn through `AiGateway.submit(TurnSpec)`,
  which inserts a PENDING `aiCalls` row and returns its id; the node returns `StepResult.wait(AI_RESPONSE, callId)`
  with `expiresAt = now + turnTimeout × attempts + 30s`. The run is WAITING, no run lease is held, the chatbot thread
  is free.
- `AiTurnWorker` (own pool, default 8 threads; woken on submit and by a 2 s tick) claims PENDING rows
  (`status PENDING → RUNNING`, `leaseUntil = now + turnTimeout + 30s`, `attempts + 1`), runs the turn, writes DONE or
  FAILED. A row whose lease ran out (crash, deploy mid-turn) is claimed again, up to 2 attempts, then FAILED
  (`errorCode INTERRUPTED`). So a restart retries the turn instead of failing a live conversation.
- Delivery: a finished row has `deliveredAt = null` until `service/chatbot/run/AiResponseListener` (the
  `TaskAnswerSource` for AI_RESPONSE) has answered the run; the worker calls it right after finishing, and the tick
  re-delivers any DONE/FAILED row still undelivered after 10 s. The run claim (`claimForAnswer`) makes double
  delivery harmless.
- Timeouts: provider request 60 s, turn 120 s (`omnichannel.ai.*`). The chatbot ticker treats an expired AI_RESPONSE
  wait as a failed turn (node takes `failure`). While a run waits on AI_RESPONSE no reminder or inactivity end runs
  (as for Wait timers), since the bot itself is busy.
- **Messages that arrive while the AI is thinking** (conversation mode): the inbox is the queue (plan ruling R1,
  replacing an earlier `pendingReplies` field that would race the runner's own save). The node keeps
  `consumedUntil`, the `creationTime` of the last customer message it gave the AI, and reads unconsumed inbound
  messages of the chat (`creationTime > consumedUntil`, `timestamp` not before the conversation start, real types)
  when a turn comes back and before every WHATSAPP_REPLY wait. When a turn comes
  back with unconsumed messages:
  - if the turn **called no tool and no finish**, the node does not send the reply; it starts a new turn from the same
    base state (OpenAI: the same previous response id, so the unsent reply never enters the chain) with all unanswered
    customer messages joined. At most 2 supersedes, then the reply is sent and the rest becomes the next turn.
  - if the turn **called any tool** (app-side or hosted MCP, file search excluded) **or finish**, it is never thrown
    away, since a tool may have changed something outside (a booking) and finish carries the outcome. The reply is
    sent (or the outcome taken); pending messages become the next turn's input, or, after a finish, are recorded on
    the step as `pendingAfterFinish` and left to the inbox.
  No customer message is lost (anti-goal).
- **Non-text customer messages** (media is a non-goal): button and list replies go to the model as their titles; a
  location as `[location: <lat>, <lng> <name>]`; an image, video, document, voice note or sticker as a stand-in
  `[customer sent an image: <caption>]` so the model can answer "please type it". Reactions, edits and notices are
  already filtered by the reply listener.

### 5.6 App-side MCP (`ai.mcp`)

A small MCP client over Streamable HTTP (JSON-RPC `initialize`, `tools/list`, `tools/call`; JSON or SSE responses),
written on `RestClient`, because the MCP Java SDK's Jackson and reactor stack would be a second stack (builder may use
the SDK instead if it runs on Jackson 3 without reactor). Every URL passes the existing SSRF guard
(`HttpAddressGuard`, moved to `common` so both modules use it). Tool lists are cached per server for 5 minutes. Each
tool call has a 20 s timeout and a 64 KB result cap; calls and their (truncated) results are recorded on the
`aiCalls` row. Hosted servers (`connectBy: PROVIDER`) are passed to the provider only if `capabilities.hostedMcp`,
else profile save fails with "This provider cannot connect to MCP servers itself; choose Our app".

### 5.7 Data (all collections camelCase, workspace-scoped)

- **`aiConnections`**: `workspaceId, customerId, name, provider, apiKey: SealedSecret, keyHint ("sk-…a91F"),
  status (UNVERIFIED | OK | INVALID), checkedAt, lastError, createdAt, updatedBy, @Version`. Unique
  `{workspaceId, name}`.
- **`aiProfiles`**: `workspaceId, name, connectionId, model, instructions, reasoningEffort, maxOutputTokens,
  temperature, webSearch, vectorStoreIds[], mcpServers[{id, label, url, connectBy PROVIDER|APP, auth NONE|BEARER|HEADER,
  headerName, secret: SealedSecret, secretHint, allowedTools[]}], updatedAt, updatedBy, @Version`. Unique
  `{workspaceId, name}`.
- **`aiConversations`**: `workspaceId, ownerType CHATBOT_RUN, ownerId (runId), nodeName, provider, profileId, state
  (adapter map), transcript[] (neutral items, last 100 kept, always, so expiry or a TRANSCRIPT provider can rebuild),
  turns, createdAt, updatedAt`. Kept, no TTL (standing rule; the inbox keeps the same messages anyway).
- **`aiCalls`** (the turn queue and its history): `workspaceId, conversationId, runId, chatbotId, nodeName, profileId,
  provider, model, spec (the resolved turn input, no secrets), status (PENDING | RUNNING | DONE | FAILED), attempts,
  leaseUntil, deliveredAt, input text, reply text, finish, toolCalls[{name, ms, ok, resultExcerpt}], usage, latencyMs,
  errorCode, error, createdAt, finishedAt`. Indexes `{status, leaseUntil}`, `{workspaceId, createdAt}`, `{runId}`.
  Kept, no TTL.
- No new run field: the node's state (`conversationId, turns, lastCallId, consumedUntil, startedAt`) lives under
  `run.variables[nodeName]`.
- **Secrets** (`common.secret`): `SealedSecret{keyId, iv, ciphertext}` with AES-256-GCM; master keys from
  `omnichannel.secrets.keys` (`OMNICHANNEL_SECRETS_KEYS`, `id:base64` list, first one seals, all can open, so keys can
  rotate). The app refuses to save a secret when no key is configured (clear 503 message), the test profile has a
  fixed test key. Secrets are write-only on the wire: requests carry `apiKey` / `secret` only to set or replace them;
  views carry only the hint and `setAt`. Never logged; provider error bodies are scrubbed of the key before logging.

### 5.8 API (in `omnichannel-ai`, `/api/workspaces/{ws}/ai/...`)

- `GET providers`.
- `GET/POST connections`, `GET/PUT/DELETE connections/{id}` (PUT without `apiKey` keeps the key), `POST
  connections/{id}/verify`, `GET connections/{id}/models`, `GET connections/{id}/vector-stores`.
- `GET/POST profiles`, `GET/PUT/DELETE profiles/{id}`, `POST profiles/{id}/mcp/{serverId}/tools` (list tools for the
  form, app-side servers only).
- Delete of a connection used by a profile, or a profile used by a chatbot draft or published version → 409 with the
  names; the AI module asks through a port `AiProfileUsage` implemented by an adapter in `omnichannel-app`.
- Authorities (BRAND): `ai:view` (see the page, list profiles) and `ai:manage` (create, edit, keys), default roles
  CUSTOMER_ADMIN and WORKSPACE_MANAGER. Pins in `AuthorityTest` and `RoleSeederIT` go 50 → 52. Editing a chatbot
  needs only `chatbot:manage`; the node's profile picker uses a names-only list exposed through the chatbot options
  endpoint, so chatbot editors need not hold `ai:view`.

### 5.9 The AI node (`omnichannel-service`, type code `ai`)

`AiNodeSettings`:

| Field | Meaning |
|---|---|
| `profileId` | required; a profile of this brand |
| `mode` | `SINGLE` or `CONVERSATION` |
| `overrides` | `{model, instructions, reasoningEffort, maxOutputTokens, temperature}`, each null = inherit from the profile |
| `extraInstructions` | templated (`{{contact.name}}` …), appended to the effective instructions every turn |
| `userMessage` | templated. SINGLE: required, the input. CONVERSATION: optional first input; empty → wait for the customer's first message |
| `initialMessage` | CONVERSATION: optional text sent to the customer before the first wait |
| `sendReply` | SINGLE: send the AI text to the customer (default false); CONVERSATION: always true |
| `outcomes` | `[{id, label, description}]`, ids `[a-z0-9_]{1,30}` unique; the description goes into the finish tool |
| `collect` | `[{key, description, type TEXT|NUMBER|BOOLEAN}]`, the finish tool's `fields` |
| `replyFormat` | `TEXT` or `JSON {schema, messageField}`; with JSON the customer gets `messageField`'s value |
| `exitWords` | list; a customer message equal to one (case and spacing ignored) ends without calling the AI |
| `exitCondition` | a `ConditionNode` (the condition node's model) over `{{ThisNode.json.*}}`, checked after each reply |
| `maxTurns` | CONVERSATION, default 20, 1–100 |
| `continueFrom` | optional name of an earlier AI node; this node continues that node's conversation (same provider required) |

Outputs (server and console must match exactly):
- SINGLE: `next` when no outcomes, else `outcome:<id>` per outcome (the finish tool is forced, so this is a
  classifier); always `failure`.
- CONVERSATION: `outcome:<id>` per outcome, `exitWord` if exit words, `exitCondition` if a condition, `maxTurns`,
  `failure`. Customer silence uses the existing reminder and inactivity end; agent take-over the existing hand-over.

`waitsFor`: SINGLE `{AI_RESPONSE}`; CONVERSATION `{WHATSAPP_REPLY, AI_RESPONSE}`.

Result variables `{{Node.reply}}, {{Node.json.*}}, {{Node.outcome}}, {{Node.fields.<key>}}, {{Node.turns}},
{{Node.usage.inputTokens|outputTokens}}, {{Node.conversationId}}`.

Flow of a conversation: run() → send `initialMessage` → either submit the first turn (user message set) and wait on
AI_RESPONSE, or wait on WHATSAPP_REPLY. onAnswer(WHATSAPP_REPLY) → exit word? take `exitWord` : submit a turn, wait on
AI_RESPONSE. onAnswer(AI_RESPONSE) → pending replies? supersede (§5.5) : failure? `failure` : finish? send the
finish `reply` if any, take `outcome:<id>` : send the reply (WhatsApp formatting converter: `**b**`→`*b*`, headings
→ bold, split over 4096) → exit condition? `exitCondition` : turns ≥ max? `maxTurns` : wait on WHATSAPP_REPLY. The run
step is re-used across turns (as Ask's re-ask), with the turn count and last call id in its summary.

Validation (executor): profile exists in the brand; outcome ids and collect keys valid and unique; SINGLE needs a
user message; JSON format needs a schema whose `messageField` is a string property; overrides valid for the profile's
provider capabilities (e.g. temperature on a provider without it → ERROR); `continueFrom` names an earlier AI node;
WARNING when CONVERSATION has no outcomes and no exit words/condition (only max turns or silence ends it).

Test runs (rule test page) call the real provider with the brand's key.

### 5.10 Console (`omnichannel-crm-frontend`)

- **Brand setup → AI** (`/ai`, `ai:view`; edit with `ai:manage`), two tabs:
  - *Connections*: provider select (from `providers`), name, API key as a write-only password field; an existing key
    shows `sk-…a91F · set 12 Nov` with **Replace key**; **Verify** shows OK or the provider's message; a note that
    OpenAI keeps stored responses 30 days.
  - *Profiles*: connection, model (live list + free text), instructions, then only the fields the provider supports:
    reasoning effort, max output tokens, temperature, web search; **Knowledge**: vector store checklist with Refresh;
    **MCP servers**: rows of label, URL, connect by Provider | Our app (option disabled with a reason when the provider
    lacks hosted MCP), auth None | Bearer | Header + write-only secret, allowed tools (for app-side servers a "Load
    tools" button lists them).
- **AI node** in the palette (`nodeCatalog.ts` entry, `FIELDS.ai` form): profile select; mode; overrides with the
  profile's value as placeholder (`gpt-5 — profile`) and Reset, as in `LayeredSettingsForm`; extra instructions, user
  message, initial message with `VariableInput`; outcomes and collect lists; reply format with schema editor; exit
  words; exit condition with the condition node's builder; max turns; continue from. `outputs()` and `produces()`
  mirror §5.9.
- Run panel: the AI step shows turns, last reply, outcome and token usage.

## 6. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB, GridFS never S3, collections camelCase, React 18 + PatternFly 6.5 +
`@xyflow/react` 12. No "template" naming for anything here. Work in `.worktrees/ai-node` in both repos, test first,
Prettier on touched files only, full `./mvnw -q verify` once before merge. Deploy on 8089 needs the new
`OMNICHANNEL_SECRETS_KEYS` env var (the user sets it; never recorded). Real sends only to a recipient the user names.

## 7. Left to the builder

Package layout inside the module beyond §5.1; JSON-schema editor widget; exact backoff numbers; the WhatsApp
formatting converter's edge cases; index names; whether the MCP client uses the SDK (§5.6 condition); console
component split.

## 8. Verification

- Backend: unit tests for `SecretBox`, settings merge, finish-tool schema, OpenAI request mapping (golden JSON) and
  reply parsing, MCP client against a stub server; ITs with a local HTTP stub standing in for OpenAI: connection
  verify/models/vector stores, keys never in any response or log, profile capability checks, SINGLE with and without
  outcomes, CONVERSATION over three turns with `previous_response_id` chained and instructions resent, supersede when a
  message arrives mid-turn (and no supersede after a tool call or finish), non-text messages as stand-ins, expired
  previous id rebuilt from transcript, app-side MCP tool loop, hosted MCP passed through with approval "never",
  timeout → `failure`, a lease that runs out is re-claimed and retried once, undelivered results re-delivered, 409 on deleting a used profile, boundary tests, authority pins 52; a
  `FakeAiProvider` (TRANSCRIPT state, no hosted tools) proves the SPI and capability gating.
- Console: vitest for api modules, AI page (write-only key, replace, capability-driven fields), node form, outputs
  parity with server fixtures; `tsc`, lint, build.
- Manual on 8089 with the user's own OpenAI key (entered by the user in the page): build a chatbot with a conversation
  node (two outcomes, one collect field) and a single-call classifier, test-run on a recipient the user names, check
  the chain in `aiConversations`, the `aiCalls` rows, the run panel, and that no key appears in Mongo in clear or in any
  network response.

## 9. After approval

1. Create `.worktrees/ai-node` (branch `ai-node`) in both repos; write this design as
   `docs/superpowers/specs/2026-11-15-ai-node-design.md` in the backend worktree and commit it.
2. writing-plans: `docs/superpowers/plans/2026-11-15-ai-node-backend.md` (secrets, module + SPI, OpenAI adapter,
   queue + worker, MCP client, connections/profiles API, node executor + reply-listener changes, authorities) and
   `-console.md` (AI page, node form, run panel), each holding the contract the other needs.
3. Ask the user to review the plans and choose the execution method.

## 10. Risks

- Cost: every turn re-bills prior tokens; `maxTurns` and `maxOutputTokens` are the brakes; usage is recorded per call.
- OpenAI keeps stored responses 30 days on the brand's account; the connection form says so.
- Prompt injection through customer text or MCP results can steer tool calls; app-side MCP tools run with the
  server's own auth, so brands should give servers least privilege (stated on the form).
- Lost secrets key = every stored key unreadable; brands re-enter keys. Document key backup in project-rules §2.
