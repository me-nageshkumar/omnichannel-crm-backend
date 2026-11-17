# AI provider fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each AI provider declares the non-secret settings its connections take and the reasoning effort values it
accepts; the server and the console read those declarations instead of hard-coded OpenAI assumptions.

**Architecture:** `AiProvider` gains `connectionFields()`; `AiConnection` stores a plain `settings` map that reaches the
provider inside `AiCredential`. `AiCapabilities.reasoningEffort` (boolean) becomes `reasoningEfforts` (list). OpenAI
declares `organization` and `project` and sends them as headers. The console renders declared fields and effort
options generically.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB; React 18, PatternFly 6.5, vitest.

**Spec:** `docs/superpowers/specs/2026-11-17-ai-provider-fields-design.md` (parent: `specs/2026-11-15-ai-node-design.md`).

## Global Constraints

- Backend worktree `omnichannel-crm-backend/.worktrees/ai-provider-fields` (exists, branch `ai-provider-fields`).
  Console: create `omnichannel-crm-frontend/.worktrees/ai-provider-fields`, same branch name, before Task 4.
- Read `docs/superpowers/project-rules.md` §1 and §2 first. Test first. One Maven build at a time. Absolute paths.
- Prettier only on touched console files. No lint clean-up of files not touched.
- Settings are never secrets: never seal them, never mask them, never accept the API key through them.
- Existing `aiConnections` and `aiProfiles` documents must keep working without a migration.
- Merge, push, deploy only when the user asks. Full `./mvnw -q verify` once, before merge (see "Before merge").
- Module boundary: `omnichannel-service` and the console contract use only `ai.api` types, so `AiConnectionField`
  lives in `ai.api`.

## Review Focus

1. A connection saved before this change (no `settings` field in Mongo) → verifies and runs a turn; `View.settings`
   is `{}`. (Task 2 IT)
2. A setting value containing a line break or other control character → 400 on `settings.<key>`; it must never reach
   an HTTP header. (Task 2 unit test)
3. Provider changed on edit while old settings are still in the request → settings the new provider does not declare
   are dropped silently, not an error. (Task 2 unit test)
4. A provider with an empty effort list → the console hides the select. `Boolean([])` is `true` in JavaScript, so the
   existing `shows(flag)` helper cannot be used for it. (Task 4 tests)
5. A profile or node holding an effort the provider no longer lists → loads and runs unchanged; only a save rejects
   it. (Task 3 tests)

---

### Task 1: SPI — connection fields, credential settings, OpenAI headers

**Files:**
- Create: `omnichannel-ai/src/main/java/me/nageshk/omnichannel/ai/api/AiConnectionField.java`
- Modify: `omnichannel-ai/.../ai/provider/AiProvider.java`, `.../ai/provider/AiCredential.java`,
  `.../ai/provider/openai/OpenAiProvider.java`
- Test: `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/provider/openai/OpenAiProviderTest.java`

**Interfaces:**
- Produces:
  - `record AiConnectionField(String key, String label, String help, Kind kind, boolean required)` with
    `enum Kind { TEXT, URL }`.
  - `AiProvider.connectionFields()`: `default List<AiConnectionField> connectionFields() { return List.of(); }`.
  - `record AiCredential(String apiKey, Map<String, String> settings)`; compact constructor makes `settings`
    `Map.copyOf` (null → empty); keep the one-argument constructor `AiCredential(String apiKey)`; `toString` still
    prints only `apiKey=****`, never the settings.
  - `OpenAiProvider.connectionFields()` returns, in this order:
    `organization` ("Organization ID", help "Optional. Sent as OpenAI-Organization. Not a secret; the key goes in API key.", TEXT, not required),
    `project` ("Project ID", help "Optional. Sent as OpenAI-Project. Not a secret; the key goes in API key.", TEXT, not required).

- [ ] **Step 1: Write the failing tests** in `OpenAiProviderTest` (it already runs a local stub server and records
  requests; follow its existing style):

  - `sendsOrganizationAndProjectHeadersWhenSet`: credential
    `new AiCredential(KEY, Map.of("organization", "org_1", "project", "proj_1"))`; call `verify`, `models`,
    `vectorStores` and `respond`; every recorded request has header `OpenAI-Organization: org_1` and
    `OpenAI-Project: proj_1`.
  - `sendsNoAccountHeadersWhenUnset`: `new AiCredential(KEY)`; `verify`; the request has neither header.
  - `declaresOrganizationAndProject`: `connectionFields()` keys equal `["organization", "project"]`, both `TEXT`,
    both not required.
  - `credentialToStringHidesSettings`: `new AiCredential("k", Map.of("organization", "org_1")).toString()` contains
    neither `k"`-style key text nor `org_1`.

- [ ] **Step 2: Run to see them fail**

  Run: `./mvnw -q -pl omnichannel-ai test -Dtest=OpenAiProviderTest`
  Expected: compile failure (two-argument `AiCredential`, `connectionFields`).

- [ ] **Step 3: Implement** the record, the default method, the credential change and, in `OpenAiProvider.send`, the
  two headers (set only when the setting is non-blank).

- [ ] **Step 4: Run the module's tests**

  Run: `./mvnw -q -pl omnichannel-ai test`
  Expected: BUILD SUCCESS (no output with `-q`).

- [ ] **Step 5: Commit** — `feat(ai): providers declare connection fields; OpenAI sends organization and project`

---

### Task 2: Connections store, validate and return settings

**Files:**
- Create: `omnichannel-ai/.../ai/connection/ConnectionSettings.java`
- Modify: `.../ai/connection/AiConnection.java`, `AiConnectionViews.java`, `AiConnectionService.java`
- Test: create `omnichannel-ai/src/test/java/me/nageshk/omnichannel/ai/connection/ConnectionSettingsTest.java`;
  modify `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/ai/AiConnectionsApiIT.java`

**Interfaces:**
- Consumes: Task 1's `AiConnectionField`, `AiProvider.connectionFields()`, `AiCredential(apiKey, settings)`.
- Produces:
  - `AiConnection.settings: Map<String, String>`; the getter returns an empty map when the stored field is absent.
  - `Save(String name, String provider, String apiKey, Map<String, String> settings, Long version)`.
  - `View(..., Map<String, String> settings, Long version)` — `settings` just before `version`, never null.
  - `ProviderView(String id, String label, AiCapabilities capabilities, List<AiConnectionField> connectionFields)`.
  - `static Map<String, String> ConnectionSettings.clean(List<AiConnectionField> declared, Map<String, String> in,
    HttpAddressGuard guard, Map<String, String> errors)` — returns the settings to store (insertion order of
    `declared`) and adds problems to `errors` under `settings.<key>`.

  Rules of `clean` (exact messages):
  - `in` null → treated as empty.
  - values trimmed; blank = absent (not stored).
  - a key not declared → error `"is not a setting of this provider"`. `clean` has no exception to this; the service
    removes undeclared keys before calling it when the provider changed in this save (Review Focus 3; test
    `providerChangeDropsTheOldProvidersSettings` in `AiConnectionsApiIT`: connection on `openai` with
    `organization`, PUT with provider `scripted`, a key and the same settings → 200, `settings` is `{}`).
  - required and absent → `"is required"`.
  - longer than 500 characters → `"must be at most 500 characters"`.
  - any character below U+0020 or U+007F → `"must not contain control characters"`.
  - `URL` kind: not an absolute `https` URL → `"must be an https URL"`; refused by `HttpAddressGuard` (use it the way
    `McpClient` does) → `"must be a public address"`.

  Service behaviour:
  - `create`: clean settings, store them, verify with `new AiCredential(key, settings)`.
  - `replace`: when the provider changed, drop request keys the new provider does not declare, then clean. If the
    cleaned settings differ from the stored ones and no new key came, verify again with the stored key
    (`secrets.open`). Settings equal and no new key → no provider call (as today).
  - `credential(AiConnection)` passes the stored settings; so do `models` and `vectorStores`.

- [ ] **Step 1: Write the failing unit tests** (`ConnectionSettingsTest`, plain JUnit, guard
  `new HttpAddressGuard(false)`), declared fields for the tests:
  `org` TEXT optional, `region` TEXT required, `endpoint` URL optional.

  | test | input | expected |
  |---|---|---|
  | `keepsDeclaredValuesTrimmed` | `{region:" eu ", org:"o1"}` | returns `{org:"o1", region:"eu"}`, no errors |
  | `blankIsAbsent` | `{region:"eu", org:"  "}` | returns `{region:"eu"}` |
  | `nullInputMissesRequired` | `null` | error `settings.region` = `is required` |
  | `undeclaredKeyIsAnError` | `{region:"eu", apiKey:"sk"}` | error `settings.apiKey` = `is not a setting of this provider` |
  | `tooLong` | `region` of 501 chars | `must be at most 500 characters` |
  | `controlCharacterRefused` | `{region:"eu\r\nX-Evil: 1"}` | `must not contain control characters` |
  | `urlMustBeHttps` | `{region:"eu", endpoint:"http://example.com"}` | `must be an https URL` |
  | `urlMustBePublic` | `{region:"eu", endpoint:"https://127.0.0.1/x"}` | `must be a public address` |
  | `noFieldsNoInput` | declared `[]`, `{}` | returns `{}`, no errors |

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai test -Dtest=ConnectionSettingsTest` — Expected: compile failure.

- [ ] **Step 3: Implement `ConnectionSettings.clean`.** Run the test again — Expected: BUILD SUCCESS.

- [ ] **Step 4: Write the failing ITs** in `AiConnectionsApiIT` (OpenAI against the existing stub; reuse its helpers):

  - `settingsAreStoredReturnedAndSent`: POST with `settings {organization:"org_1"}` → 201, body `settings` equals
    `{organization:"org_1"}`; the stub's verify request carried `OpenAI-Organization: org_1`; GET returns the same.
  - `settingsOnlyChangeVerifiesAgain`: create; count stub requests; PUT without `apiKey`, `settings
    {project:"proj_9"}` → one more stub request, carrying `OpenAI-Project: proj_9`; `keyHint` unchanged.
  - `unchangedSaveMakesNoProviderCall`: create with settings; PUT the same name and settings, no key → stub request
    count unchanged.
  - `unknownSettingIs400`: POST with `settings {baseUrl:"https://x.example"}` → 400, field `settings.baseUrl`.
  - `connectionWithoutSettingsFieldStillWorks`: insert an `aiConnections` document through `MongoTemplate` with no
    `settings` key (copy a created one and `$unset` it); GET → `settings` is `{}`; POST `/verify` → status `OK`.
  - `providersListCarriesConnectionFields`: GET providers → the `openai` entry has `connectionFields` with keys
    `organization`, `project`.
  - extend `keyNeverLogged` only if it asserts on the whole view shape; otherwise leave it.

- [ ] **Step 5: Run** `./mvnw -q -pl omnichannel-app -am verify -Dit.test=AiConnectionsApiIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
  (or the single-IT command project-rules §2 gives) — Expected: the new tests fail (unknown `settings` property or
  missing field).

- [ ] **Step 6: Implement** the document field, views and service behaviour above.

- [ ] **Step 7: Run the same command** — Expected: BUILD SUCCESS. Also run
  `./mvnw -q -pl omnichannel-ai test` — Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit** — `feat(ai): connections carry provider-declared settings`

---

### Task 3: Reasoning effort values come from the provider

**Files:**
- Modify: `omnichannel-ai/.../ai/api/AiCapabilities.java`, `.../ai/provider/openai/OpenAiProvider.java`,
  `.../ai/profile/AiProfileService.java` (lines 45, 159–160, 278–279),
  `omnichannel-service/.../service/chatbot/node/AiExecutor.java` (lines 55, 157–162),
  every `new AiCapabilities(` call site (tests: `AiProvidersTest`, `ScriptedProvider`, `AiExecutorValidationTest`,
  `omnichannel-app/.../support/ScriptedAiProvider`)
- Modify docs: `docs/superpowers/known-limits.md` (AI node section), `README.md` AI section if it names the effort
  values or the connection fields
- Test: `omnichannel-service/.../chatbot/node/AiExecutorValidationTest.java`,
  `omnichannel-app/.../ai/AiProfilesApiIT.java`, `omnichannel-ai/.../provider/openai/OpenAiProviderTest.java`

**Interfaces:**
- Produces: `record AiCapabilities(StateMode state, boolean hostedMcp, boolean vectorStores, boolean functionTools,
  boolean structuredOutput, List<String> reasoningEfforts, boolean temperature, boolean webSearch)`; compact
  constructor: null → `List.of()`, else `List.copyOf`. JSON field name `reasoningEfforts`.
- OpenAI's list: open OpenAI's current API reference for `POST /v1/responses`, parameter `reasoning.effort`, and list
  exactly the values it documents, weakest first. It must include `minimal`, `low`, `medium`, `high`; if the reference
  cannot be reached, use those four and ledger it.
- Messages:
  - list empty and a value given → profile: `"This provider does not take a reasoning effort"` (unchanged);
    node: `"the profile's provider does not take a reasoning effort"` (unchanged).
  - value not in the list → `"must be one of " + String.join(", ", list)` (both places).
  - `AiExecutor` with `caps == null` (profile missing; already reported elsewhere) → no effort error.
- Existing boolean call sites map as: `true` → `List.of("minimal", "low", "medium", "high")`, `false` → `List.of()`.

- [ ] **Step 1: Write the failing tests**

  - `OpenAiProviderTest.declaresEffortValues`: `capabilities().reasoningEfforts()` contains `minimal`, `low`,
    `medium`, `high` in that relative order.
  - `AiExecutorValidationTest`:
    - `effortOutsideTheProvidersListIsAnError`: capabilities with `List.of("fast", "deep")`, override `low` → error on
      `overrides.reasoningEffort` = `must be one of fast, deep`.
    - `effortFromTheProvidersListIsAccepted`: same capabilities, override `deep` → no error on that field.
    - keep the existing "does not take a reasoning effort" case, now built with an empty list.
  - `AiProfilesApiIT`:
    - `effortNotListedByTheProviderIs400`: OpenAI connection, `reasoningEffort: "turbo"` → 400, field
      `reasoningEffort`, message starts `must be one of minimal`.
    - `storedUnlistedEffortStillLoads`: save a profile with `low`, set its `reasoningEffort` to `"turbo"` directly in
      Mongo, GET → 200 with `turbo`; PUT unchanged → 400 on `reasoningEffort`.
    - `providersListCarriesEffortValues`: GET providers → `openai.capabilities.reasoningEfforts` is a non-empty array
      and there is no `reasoningEffort` property.

- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-ai,omnichannel-service test` — Expected: compile failure
  (`reasoningEfforts`).

- [ ] **Step 3: Implement**: the record, OpenAI's list, both validators (delete both `EFFORTS` constants), all call
  sites.

- [ ] **Step 4: Run** `./mvnw -q -pl omnichannel-ai,omnichannel-service test`, then the two ITs
  (`AiProfilesApiIT`, `ChatbotAiSingleIT`) with the single-IT command — Expected: BUILD SUCCESS each.

- [ ] **Step 5: Docs.** `known-limits.md`, AI node section, add:
  - "A connection holds one secret (the API key) plus the provider's plain settings; providers that need a key pair
    are not supported."
  - "Reasoning effort values are listed per provider, not per model: a value the chosen model refuses fails the turn
    with the provider's message."
  - "OpenAI has no per-connection base URL."

- [ ] **Step 6: Commit** — `feat(ai): reasoning effort values are declared by the provider`

---

### Task 4: Console

**Files (in `omnichannel-crm-frontend/.worktrees/ai-provider-fields`):**
- Modify: `src/app/api/ai.ts`, `src/app/AI/ConnectionModal.tsx`, `src/app/AI/ProfileModal.tsx`,
  `src/app/Chatbots/nodes/AiFields.tsx`
- Test: `src/app/api/ai.test.ts`, `src/app/AI/aiConnections.test.tsx`, `src/app/AI/aiProfiles.test.tsx`,
  `src/app/Chatbots/nodes/aiFields.test.tsx`, and fixtures in `src/app/AI/profileProblems.test.ts`

**Interfaces:**
- Consumes (server contract from Tasks 2 and 3):
  - provider: `{id, label, capabilities: {..., reasoningEfforts: string[], ...}, connectionFields: [{key, label,
    help, kind: 'TEXT' | 'URL', required}]}`
  - connection view: `settings: Record<string, string>`; save body: `settings` (always sent, `{}` when none).
  - validation errors arrive as fields `settings.<key>`.
- Produces in `ai.ts`: `AiConnectionField`, `AiProvider.connectionFields`, `AiCapabilities.reasoningEfforts: string[]`
  (the boolean is removed), `AiConnection.settings`, `AiConnectionSave.settings`.
  `withoutEmpty` must keep `settings` even when it is `{}` (so a cleared field is saved).

- [ ] **Step 1: Write the failing tests**

  - `aiConnections.test.tsx`
    - `shows the selected provider's fields`: providers `openai` (fields Organization ID, Project ID) and `other`
      (none); the two inputs are present with their help text; selecting `other` removes them.
    - `sends settings on save`: type `org_1` into Organization ID, save → request body `settings` equals
      `{organization: 'org_1'}`.
    - `shows stored settings on edit and can clear one`: connection with `settings {project: 'p1'}` → input holds
      `p1`; clear it, save → body `settings` equals `{}`.
    - `shows a settings field error under its input`: server answers VALIDATION with
      `{'settings.organization': 'must not contain control characters'}` → the text shows under Organization ID.
    - `marks a required field`: a provider field with `required: true` renders as required and Save is disabled
      while it is empty.
  - `aiProfiles.test.tsx`
    - `effort options come from the provider`: `reasoningEfforts: ['fast', 'deep']` → the select offers exactly the
      provider default option plus `fast`, `deep`.
    - `no effort select when the provider lists none`: `reasoningEfforts: []` → no "Reasoning effort" field, and the
      save body has `reasoningEffort: null`.
  - `aiFields.test.tsx`: the same two cases for "Reasoning effort override"; plus `keeps a stored override visible
    when the provider lists none` (the existing `present('reasoningEffort')` behaviour: the field shows with the
    stored value so it can be reset).
  - `ai.test.ts`: `createAiConnection` and `saveAiConnection` send `settings: {}`.

- [ ] **Step 2: Run** `npx vitest run src/app/AI src/app/api/ai.test.ts src/app/Chatbots/nodes/aiFields.test.tsx` —
  Expected: the new tests fail; `tsc` errors on `reasoningEfforts`.

- [ ] **Step 3: Implement.**
  - `ConnectionModal`: state `settings: Record<string, string>` initialised from `connection?.settings ?? {}`;
    render `providers.find(p => p.id === provider)?.connectionFields ?? []` under the API key as `TextInput`s
    (`id="ai-connection-setting-<key>"`, label, `isRequired`, help as helper text, error from
    `fields['settings.' + key]`); on save send only the selected provider's keys, trimmed, blanks left out.
  - `ProfileModal`, `AiFields`: delete both `EFFORTS` constants; `const efforts = caps?.reasoningEfforts ?? []`;
    show the select when `efforts.length > 0` (in `AiFields`: or when an override is present); never route
    `reasoningEfforts` through `shows()` — narrow that helper's key type so it cannot be passed.

- [ ] **Step 4: Run** the vitest command of Step 2, then `npx tsc --noEmit`, then `npm run -s build` —
  Expected: all tests pass, no type errors, build succeeds.

- [ ] **Step 5: Prettier on the touched files only, then commit** —
  `feat(ai): connection fields and effort options come from the provider`

---

## Before merge

- [ ] Backend: `./mvnw -q verify` once, from the backend worktree. Expected: BUILD SUCCESS (`CrmExportIT` is a known
  flake: rerun it alone once before treating it as a failure).
- [ ] Console: `npx vitest run`, `npx tsc --noEmit`, `npm run -s build`.
- [ ] Fresh reviewer on both branches, one fix pass (project-rules §1).
- [ ] After a deploy the user asks for: on 8089, open "Dev Test" and "Test assistant", save each unchanged, confirm
  both still show OK. A chatbot run is a real send: only to a recipient the user names.
