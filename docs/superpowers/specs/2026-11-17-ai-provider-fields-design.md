# AI providers declare their connection settings and reasoning effort values: short design

Design, 2026-11-17. Follows `specs/2026-11-15-ai-node-design.md` (§5.2 provider SPI, §5.7 data). Small change.

## 1. Intent

The AI node was built so that Claude, DeepSeek and others slot in later. A check of the code on `main` (7b37de6)
found two places where that is not yet true, both cheaper to change now, before more connections and profiles exist:

1. **A connection is only an API key.** `AiCredential(apiKey)` is all a provider gets; the base URL is one app-wide
   property. Providers that need an endpoint, a region, a deployment name or an account header per connection cannot
   be described.
2. **Reasoning effort values are hard-coded** as `minimal, low, medium, high` in four places
   (`AiProfileService`, `AiExecutor`, console `ProfileModal.tsx` and `AiFields.tsx`); the capability is a yes/no.
   Other providers take other values.

The user asked for the short design for these two (2026-11-17). Everything below is **(my call)**.

## 2. Goals, non-goals, anti-goals

Goals: a provider declares the non-secret settings its connections take and the effort values it accepts; server
validation and both console forms read those declarations; OpenAI uses the mechanism for something real.

Non-goals: a second provider adapter; more than one secret per connection (AWS-style key pairs); a per-connection
base URL for OpenAI (the Responses API is not served by OpenAI-compatible hosts in a dependable way; the app-wide
`omnichannel.ai.openai.base-url` stays, for tests); effort values per model; provider-specific blocks in replies
(gap 3 of the check, to be done with the Claude adapter).

Anti-goals: a secret stored in the readable settings map; a URL setting that reaches an internal address; existing
connections or profiles that stop working after the deploy.

## 3. Approaches

- **A. Providers declare (chosen).** `AiProvider` returns a list of connection fields and a list of effort values;
  one generic form and one generic validator serve every provider. Adding a provider touches only its own package.
- B. Typed settings per provider (a class per provider, a form per provider in the console). Clearer types, but each
  new provider changes the connection document, the API and the console; that is the redesign the user asked to avoid.
- C. Free-form key/value pairs typed by the user. No code per provider, but nothing can be validated and the user has
  to know header names by heart.

## 4. Design

### 4.1 Connection settings

```java
// ai.api (the console reads it through the providers list)
record AiConnectionField(String key, String label, String help, Kind kind, boolean required) {
    enum Kind { TEXT, URL }
}
// ai.provider
interface AiProvider { ... List<AiConnectionField> connectionFields(); }   // empty when none
record AiCredential(String apiKey, Map<String, String> settings) {}        // settings never null; toString still masks the key
```

- `AiConnection.settings: Map<String, String>` (plain, readable; absent on old documents = empty, no migration).
- `Save` and `View` carry `settings`. `ProviderView` gains `connectionFields`.
- Validation on save (`AiConnectionService`): a key the provider does not declare → error on `settings.<key>`; a
  required field blank → error; values trimmed, blank = absent, at most 500 characters; `URL` must be `https` and pass
  `HttpAddressGuard`. A provider change drops settings the new provider does not declare (the key is already required
  again in that case).
- A save that changes settings without a new key verifies again with the stored key, so the status shown is about the
  settings in force.
- **OpenAI declares two optional TEXT fields:** `organization` (sent as `OpenAI-Organization`) and `project`
  (`OpenAI-Project`), on every call including verify, models and vector stores. They matter for keys that belong to
  several organizations or projects, and they give the mechanism a real user.
- Settings are not secrets and are returned by the API. A field's `help` says so where it could be mistaken
  ("Not a secret; the key goes in API key").

### 4.2 Reasoning effort values

- `AiCapabilities`: `boolean reasoningEffort` becomes `List<String> reasoningEfforts` (empty = the provider takes
  none). The wire field changes name with it; backend and console ship in one jar, so nothing else reads it.
- OpenAI declares the values it accepts today. The builder checks OpenAI's current reference for the Responses API
  `reasoning.effort` values and lists exactly those; the present four are the floor.
- The list is per provider, not per model. A value the chosen model refuses comes back from the provider as
  `BAD_REQUEST` and the node takes `failure` with the provider's message, as now.
- The four hard-coded sets go: `AiProfileService` and `AiExecutor` validate against the profile's provider
  (`"must be one of <list>"`, or the existing "does not take a reasoning effort" when the list is empty);
  `ProfileModal.tsx` and `AiFields.tsx` fill their selects from `capabilities.reasoningEfforts`.
- A stored value that is no longer in the list is left alone until the profile or node is saved again, then it is an
  error on that field.

### 4.3 Console

- `ConnectionModal`: under the API key, one input per declared field (label, help text, required mark), shown for
  the selected provider; changing the provider swaps the fields. Values are shown on edit (they are not secrets).
- `ProfileModal`, `AiFields`: effort options from the provider's list; the field is hidden when the list is empty
  (as it is now when the capability is false).

## 5. Constraints

As the AI node spec §6: Java 21, Spring Boot 4.1, Jackson 3, React 18 + PatternFly 6.5; work in
`.worktrees/ai-provider-fields` in both repos; test first; Prettier on touched files only; full `./mvnw -q verify`
once before merge; merge, push and deploy only when the user asks.

## 6. Left to the builder

Exact help texts; where the field list sits in the provider JSON; whether `AiConnectionField` needs a placeholder.

## 7. Verification

- Backend: unit tests for the settings validator (unknown key, required, length, URL guard, provider change);
  OpenAI adapter test that both headers are sent when set and absent when not; IT that a connection saved before this
  change (no `settings`) still verifies and runs a turn; IT that a settings-only save re-verifies; profile and node
  validation against a scripted provider with its own effort list and with an empty one; providers endpoint returns
  `connectionFields` and `reasoningEfforts`.
- Console: vitest for `ConnectionModal` (fields follow the provider, values round-trip) and for both effort selects
  (options from capabilities, hidden when empty); `tsc`, build.
- On 8089: open the existing "Dev Test" connection and "Test assistant" profile, save each unchanged, run the
  "AI shirt test" chatbot's rule test once (a real send, so only to a recipient the user names).

## 8. Risks

- A wrong `organization` or `project` makes a working key fail verification; the provider's message is shown and
  clearing the field restores it.
- The capabilities wire rename breaks a console tab left open across the deploy until it is reloaded.
