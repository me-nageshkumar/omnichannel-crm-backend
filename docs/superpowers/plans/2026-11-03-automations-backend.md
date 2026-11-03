# Automations (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A brand's rules (event, conditions, ordered actions) run on live incoming WhatsApp Web messages and on CRM deal lane changes, through a MongoDB event queue, with draft/publish, a step-by-step test of drafts, a kept run log and event history, and a REST API for the console.

**Architecture:** Two writers put one event document in `automationEvents` (an inbox listener on `MessageUpserted`, and a new `crm.platform.DealObserver` port that `DealService` calls after a move), only for brands that want it. `AutomationWorker`, on its own scheduler, claims the oldest event with a lease, runs every matching published rule in order (one `automationRuns` document per (event, rule), unique), offers the event to a waiting test, then moves it to `automationEventHistory`. Three `ActionHandler`s do the work as the brand (an `AutomationActor` send, a system routing assignment, a CRM capture through the `AutomationCrm` port). Controllers under `/api/workspaces/{ws}/automations` behind `automation:view` / `automation:manage`.

**Tech Stack:** Java 21, Spring Boot 4.1 (Jackson 3 `tools.jackson`), Spring Data MongoDB, Lombok, JUnit 5 + AssertJ + Mockito, embedded Mongo (flapdoodle) for `*IT` run by surefire. Worktree `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/.worktrees/automations` (branch `automations`). Package root `me.nageshk.omnichannel`.

**Spec:** `docs/superpowers/specs/2026-11-03-automations-design.md` (all sections). Console plan: `docs/superpowers/plans/2026-11-03-automations-console.md` consumes the **REST contract** section below verbatim.

## Global Constraints

- Collections camelCase, exactly: `automationRules`, `automationEvents`, `automationEventHistory`, `automationRuns`, `automationTests`. **No TTL index on any of them** (spec 4.3 "Nothing expires").
- `omnichannel-crm` imports only `crm.*`, `common.*`, `security.access.*`, `security.auth.*` (`CrmModuleBoundaryTest`); the host is reached through `crm.platform` ports with adapters in `app.crm`. `omnichannel-service` does not depend on `omnichannel-crm`: the service reaches the CRM only through the `AutomationCrm` port, implemented in `app.crm`.
- Events: live incoming real messages only (direction IN, source CONTACT, `inserted` true, real type); history import never; deal lane change only from `DealService.move` / `close` / `update` (pipeline change), never from `DealPlacementImpl.relocateStage` / `relocatePipeline`, deal creation or CSV import.
- Writing an event never fails or slows the message or the move: every writer call is wrapped in `try { … } catch (RuntimeException e) { log.warn(…) }`.
- A rule acts as the brand: no `AuthenticatedUser` anywhere in the worker or the handlers.
- Copy fixed by the spec: message sender `"Automation: <rule name>"`; routed reason shown as `"by automation: <rule name>"`; skip reason `"Contact is marked do-not-contact"`; `"No chat with this contact"`; `"Already in the CRM"`; `"CRM is off for this brand"`.
- Authorities: `AUTOMATION_VIEW("automation:view", RoleLevel.BRAND, "See automation rules, their runs and the event history", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`, `AUTOMATION_MANAGE("automation:manage", RoleLevel.BRAND, "Create, edit, test, publish, move to draft and delete automation rules", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`.
- Tests never touch a real number: numbers are `testDevices.number(brand, "9198" + (System.nanoTime() % 100000000))`, the device is the fake `AutomationDevice`.
- Run one test class: `./mvnw -q -pl omnichannel-app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; the full `./mvnw -q verify` once, at the end (Task 12).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Plan calls (decisions the spec leaves to the builder)

- Plan call: `chat.new` is computed when the event is written, by a query, not a stored marker: true unless the chat already holds another IN message of a real type (`existsByChatIdAndDirectionAndTypeInAndIdNot`, on the `chat_timestamp` index). No backfill is needed and fetched chats count automatically.
- Plan call: real message types are the whatsapp-web.js types `chat`, `image`, `video`, `audio`, `ptt`, `document`, `sticker`, `location`, `vcard`, `multi_vcard`, `poll_creation`, mapped to the kinds TEXT, PHOTO, VIDEO, AUDIO (audio and ptt), DOCUMENT, STICKER, LOCATION, CONTACT_CARD (vcard, multi_vcard), POLL. Every other type (call_log, revoked, reactions, notices, ciphertext, …) starts nothing.
- Plan call: the message event is written from an `@EventListener` on `InboxEvents.MessageUpserted` (published after routing, with the chat re-read), not from a new inbox event.
- Plan call: a message event's id is `"m-" + <stored message id>`, so the same message can never be queued twice (duplicate insert ignored; an id already in the history is skipped). A deal event gets a fresh ObjectId.
- Plan call: worker tick 2 s, lease 5 min, 3 attempts, 20 events per tick, own scheduler `automationScheduler` (1 thread, prefix `automation-`); send wait 60 s. An event whose lease ran out is claimed again.
- Plan call: a rule's run document is inserted (status RUNNING) **before** its actions run; a re-run of the event skips every rule that has a run, and marks a RUNNING one INTERRUPTED. A crash mid-rule therefore loses that rule's remaining actions rather than risk a second send.
- Plan call: no cache of published rules; one indexed `exists` per incoming message decides whether to write the event. While only a test is waiting (no published rule of that kind), the gate takes the test contact's events alone, so the event history does not fill with the brand's other messages during a test.
- Plan call: only matching rules get a run; the history document keeps the ids of the rules that ran (`ruleIds`) and of the tests that took the event (`testIds`). The history lists processed events only (an event appears once the worker is done with it, seconds later).
- Plan call: an automation send skips the inbox's "chat must be OPEN" check: a closed chat (a lane change on an old chat) gets the template and stays CLOSED; it is never reopened or claimed. (The spec says "like a campaign send", but campaigns reopen; the spec's explicit words win.)
- Plan call: at run time a placeholder is "still empty" only when a required AGENT placeholder has no rule value (contact placeholders always carry a fallback; `TemplateService` refuses one without). The action then fails with `Placeholder "<label>" has no value`.
- Plan call: all three assign choices record a `ROUTED` action with new `RoutedReason.AUTOMATION`, `byName "Automation: <rule>"`, `byUserId` null and a new `reasonDetail` = rule name. An agent is given the chat with the number's owning team when they are a member of it, else with no team. Like routing (and unlike a manual assignment), it does not set the chat's `lastAgent*`: the customer sticks to the agent once the agent replies, not on assignment.
- Plan call: Add to CRM uses `CrmAccess.forSystem` (user id `"system"`): `createdBy` and `origin.capturedById` are `"system"`, origin kind `INBOUND_CHAT`; the CRM contact's and the deal's owner are the chat's assignee when the action runs, else null. "In the CRM" = a `crmContacts` row for (brand, contact, number); false when the CRM is off.
- Plan call: the deal title pattern knows `{{name}}` (contact name, else `+<phone>`, else `WhatsApp contact`) and `{{phone}}` (`+<phone>` or empty); an empty pattern means `{{name}} – WhatsApp`.
- Plan call: publishing a rule with Add to CRM is refused while the brand's CRM is off (its pipeline and lane cannot be checked).
- Plan call: rules are listed in run order (priority largest first, then creation order); `priority` is 0–9999 or null.
- Plan call: conditions are a tree of `ConditionNode` with the segment editor's JSON shape (`{combinator: "and"|"or", not, rules}` / `{field, operator, value}`); the screen never sets `not`, the engine honours it. At most 3 levels and 30 conditions.
- Plan call: the editor's choices come from one `GET /automations/options` under `automation:view`, so the page needs no CRM, user or template authority.
- Plan call: the test page polls `GET /tests/{id}` every 2 s (no stream). A test ends after 30 min with no event **and no step** (any state), when its rule is published or deleted, or on Done; anyone with `automation:manage` can end it. The test reads the rule fresh at each step; actions can be run even when the rule did not match (the page says so).
- Plan call: a typed test number is refused when that phone already has a chat holding a real inbound message ("This number has already messaged you…"). A lane-change test needs an existing contact.
- Plan call: test runs are excluded from the list's "last run / runs / failures in the last 7 days" and shown in Runs and Event history marked as tests.

## Review Focus

1. The same inbound message ingested twice (an extension replay after a lost ack, or a duplicate-key retry inside ingestion) must queue one event and send one template — tests in Task 3 (`replayedMessageQueuesOneEvent`) and Task 8 (`eventClaimedTwiceRunsEachRuleOnce`).
2. A published rule whose template was archived, whose agent left the brand or whose team was deleted must log that action as failed with a readable reason and still run the rule's next actions — test in Task 8 (`brokenActionIsLoggedAndNextActionRuns`).
3. A deal moved on a contact without a chat on the deal's number fails "No chat with this contact"; one whose chat is CLOSED gets the template and the chat stays CLOSED and unassigned — tests in Task 5.
4. A contact whose phone WhatsApp hides (`@lid`, `contact.phone` null) must still be welcomed: text conditions on a null see empty, and the deal title falls back to the name or "WhatsApp contact" — tests in Task 2 (`nullActualComparesAsEmpty`) and Task 7 (`titleFallsBackWithoutNameOrPhone`).
5. A test left open (the browser closed mid-test) must not block testing the rule: it ends after 30 idle minutes, and another manager can end it — tests in Task 10 (`idleTestEndsAfter30Minutes`, `anotherManagerCanEndATest`).

---

## REST contract (the console consumes this verbatim)

Base `/api/workspaces/{workspaceId}/automations`. Errors use the existing bodies: `400 {code:"VALIDATION", message, fields:{<path>: <sentence>}}`, `403 {code:"FORBIDDEN"}`, `404 {code:"NOT_FOUND"}`, `409 {code:"CONFLICT"}` (stale `version`), plus `409 {code:"AUTOMATION_RULE_PUBLISHED", message:"Move the rule to draft to change it."}`, `409 {code:"AUTOMATION_TEST_RUNNING", message, testId, startedById, startedByName}`, `409 {code:"AUTOMATION_TEST_STATE", message}`. Times are ISO-8601 strings. `Ref = {id, name}`. `Page<T> = {items: T[], total, page}`, 50 per page.

| Method & path | Authority | Body → Response |
|---|---|---|
| `GET /options` | view | → `Options` |
| `GET /rules` | view | → `RuleSummary[]` in run order |
| `POST /rules` | manage | `RuleRequest` → `201 Rule` (DRAFT) |
| `GET /rules/{id}` | view | → `Rule` |
| `PUT /rules/{id}` | manage | `RuleRequest` with `version` → `Rule`; published → 409 `AUTOMATION_RULE_PUBLISHED` |
| `POST /rules/{id}/publish` | manage | → `Rule` (PUBLISHED); incomplete → 400 `VALIDATION`; already published → 200 unchanged |
| `POST /rules/{id}/unpublish` | manage | → `Rule` (DRAFT); already draft → 200 unchanged |
| `DELETE /rules/{id}` | manage | → 204; published → 409 `AUTOMATION_RULE_PUBLISHED` |
| `GET /rules/{id}/runs?page=` | view | → `Page<Run>` newest first |
| `GET /events?kind=&ruleId=&contactId=&from=&to=&page=` | view | → `Page<EventRecord>` newest first |
| `GET /test-contacts?q=` | manage | → `TestContact[]` (≤ 20; name or phone, case-insensitive contains) |
| `POST /rules/{id}/tests` | manage | `TestStartRequest` → `201 Test`; published rule → 409 `AUTOMATION_RULE_PUBLISHED`; one active → 409 `AUTOMATION_TEST_RUNNING` |
| `GET /rules/{id}/tests/active` | manage | → `Test` or 204 |
| `GET /tests/{testId}` | manage | → `Test` |
| `POST /tests/{testId}/check` | manage | → `Test` (next condition checked); not CAPTURED or all checked → 409 `AUTOMATION_TEST_STATE` |
| `POST /tests/{testId}/actions/{index}/run` | manage | → `Test`; `index != nextAction` or not CAPTURED → 409 `AUTOMATION_TEST_STATE` |
| `POST /tests/{testId}/actions/{index}/skip` | manage | → `Test`; same 409 |
| `POST /tests/{testId}/again` | manage | → `Test` (WAITING again, event cleared) |
| `POST /tests/{testId}/end` | manage | → `Test` (ENDED, `endReason "DONE"`) |

```ts
type EventKind = 'MESSAGE_RECEIVED' | 'DEAL_LANE_CHANGED';
type ConditionNode =
  | { combinator: 'and' | 'or'; not: boolean; rules: ConditionNode[] }
  | { field: string; operator: string; value: string | number | boolean | string[] | number[] | null };
type Action = {
  type: 'SEND_TEMPLATE' | 'ASSIGN_CHAT' | 'ADD_TO_CRM';
  templateId?: string | null; values?: Record<string, string>;                      // SEND_TEMPLATE
  mode?: 'AGENT' | 'TEAM_QUEUE' | 'TEAM_ROUND_ROBIN'; userId?: string | null;        // ASSIGN_CHAT
  teamId?: string | null; whenAway?: 'KEEP_WITH_AGENT' | 'OWNING_TEAM_QUEUE' | null;
  pipelineId?: string | null; stageId?: string | null; dealTitle?: string | null;    // ADD_TO_CRM
};
type RuleRequest = { name: string; description: string | null; event: EventKind; priority: number | null;
  conditions: ConditionNode | null; actions: Action[]; version?: number };
type Rule = RuleRequest & { id: string; status: 'DRAFT' | 'PUBLISHED'; version: number; createdBy: Ref | null;
  creationTime: string; lastUpdateTime: string; publishedAt: string | null; publishedBy: Ref | null;
  activeTest: { id: string; startedBy: Ref; status: TestStatus } | null };
type RuleSummary = { id; name; event: EventKind; status; priority: number | null; actionsSummary: string;
  lastRunAt: string | null; runs7d: number; failures7d: number; creationTime: string };
type Options = {
  numbers: { id; number; label: string | null; owningTeam: Ref | null }[];
  agents: { id; name; teamIds: string[]; seesAllNumbers: boolean }[];
  teams: Ref[];
  templates: { id; name; type: string; placeholders: { name; kind: 'CONTACT_FIELD' | 'CONTACT_ATTRIBUTE' | 'AGENT';
    label: string | null; required: boolean; fallback: string | null }[] }[];   // not archived, no CONTACT_CARD
  crm: { enabled: boolean; defaultPipelineId: string | null;
    pipelines: { id; name; active: boolean; lanes: { id; name; type: 'OPEN' | 'WON' | 'LOST' }[] }[] };
};
type EventData = {                       // null members are absent for the other event kind
  chat: { id; isNew: boolean; status: 'OPEN' | 'CLOSED' | null; assignedUser: Ref | null; assignedTeam: Ref | null } | null;
  message: { id; text: string | null; type: MessageKind } | null;
  number: { id; number; label: string | null } | null;
  contact: { id; name: string | null; phone: string | null; inCrm: boolean; doNotContact: boolean } | null;
  deal: { id; title; status: 'OPEN' | 'WON' | 'LOST'; amount: number | null; currency: string | null;
    owner: Ref | null; lostReason: string | null } | null;
  pipeline: Ref | null; fromLane: Lane | null; toLane: Lane | null;   // Lane = { id; name: string | null; type }
};
type StepView = { index: number; type: Action['type']; summary: string;
  outcome: 'DONE' | 'SKIPPED' | 'FAILED' | null; reason: string | null; at: string | null };
type Run = { id; eventId; ruleId; ruleName; test: boolean; status: 'RUNNING' | 'TESTING' | 'DONE' | 'FAILED' | 'INTERRUPTED';
  eventKind: EventKind; summary: string; contact: { id; name: string | null; phone: string | null } | null;
  deal: { id; title } | null; startedAt; finishedAt: string | null; steps: StepView[] };
type EventRecord = { id; kind: EventKind; createdAt; finishedAt; outcome: 'DONE' | 'FAILED'; error: string | null;
  summary: string; contact: Run['contact']; deal: Run['deal']; number: EventData['number']; test: boolean; runs: Run[] };
type TestContact = { contactId; name: string | null; phone: string | null; numbers: { id; number; label: string | null }[] };
type TestStartRequest = { contactId: string | null; phone: string | null; treatAsNew: boolean };   // exactly one of contactId/phone
type TestStatus = 'WAITING' | 'CAPTURED' | 'ENDED';
type Test = { id; ruleId; ruleName; event: EventKind; status: TestStatus;
  endReason: 'DONE' | 'IDLE' | 'RULE_PUBLISHED' | 'RULE_DELETED' | null; startedBy: Ref;
  target: { contactId: string | null; name: string | null; phone: string | null }; treatAsNew: boolean;
  numbers: { id; number; label: string | null }[];        // where the test contact should write (message event)
  waitingSince: string | null; idleEndsAt: string;
  captured: { eventId: string; at: string; data: EventData; treatedAsNew: boolean } | null;
  conditionCount: number;
  conditions: { path: string; field: string; operator: string; value: unknown; actual: unknown; passed: boolean }[];
  matched: boolean | null;                                 // set once every condition is checked (true with none)
  actions: StepView[]; nextAction: number | null; runId: string | null };
type MessageKind = 'TEXT' | 'PHOTO' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'STICKER' | 'LOCATION' | 'CONTACT_CARD' | 'POLL';
```

**Condition fields** (`field` → type → operators). Operator codes: `is`, `is_not`, `is_one_of`, `contains`, `not_contains`, `starts_with`, `is_empty`, `is_not_empty`, `greater_than`, `less_than`, `between`.

| Event | `field` | Type | Operators | `value` |
|---|---|---|---|---|
| MESSAGE_RECEIVED | `chat.new` | BOOLEAN | is | `true` (new) / `false` (existing) |
| | `message.text` | TEXT | is, is_not, contains, not_contains, starts_with, is_empty, is_not_empty | string |
| | `message.type` | CHOICE | is_one_of | `MessageKind[]` |
| | `number` | CHOICE | is, is_not, is_one_of | number id(s) |
| | `contact.inCrm` | BOOLEAN | is | `true` / `false` |
| | `contact.name` | TEXT | as `message.text` | string |
| DEAL_LANE_CHANGED | `pipeline` | CHOICE | is, is_not, is_one_of | pipeline id(s) |
| | `toLane` / `fromLane` | CHOICE | is, is_not, is_one_of | lane id(s) |
| | `toLane.type` | CHOICE | is, is_not, is_one_of | `OPEN`/`WON`/`LOST` |
| | `deal.amount` | NUMBER | is, is_not, greater_than, less_than, between, is_empty, is_not_empty | number; `between` `[low, high]` inclusive |
| | `deal.owner` | CHOICE | is, is_not, is_one_of, is_empty, is_not_empty | user id(s) |

Validation keys: `name`, `description`, `priority`, `event`, `conditions` (tree-wide), `conditions.rules[0].rules[1].field|operator|value` (paths as the segment editor's `rulePath`, prefixed `conditions.`), `actions`, `actions[i].type|templateId|values.<placeholder>|mode|userId|teamId|whenAway|pipelineId|stageId|dealTitle`.

---

### Task 1: Data model, authorities, settings

**Files:**
- Create in `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/automation/`: `AutomationEventKind.java`, `RuleStatus.java`, `ConditionNode.java`, `AutomationActionType.java`, `AssignMode.java`, `WhenAway.java`, `AutomationAction.java`, `AutomationRule.java`, `EventData.java`, `EventStatus.java`, `AutomationEvent.java`, `EventOutcome.java`, `AutomationEventRecord.java`, `RunStatus.java`, `StepOutcome.java`, `RunStep.java`, `AutomationRun.java`, `TestStatus.java`, `TestEndReason.java`, `ConditionResult.java`, `AutomationTest.java`, and one repository per document.
- Modify: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/access/Authority.java` — the two constants after `CAMPAIGN_SETTINGS`.
- Modify: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/config/OmnichannelProperties.java` — `Automation automation` component.
- Modify: `omnichannel-app/src/main/resources/application.yml` (`omnichannel.automation` block), `omnichannel-app/src/test/resources/application-test.yml` (`omnichannel.automation.auto-tick: false`).
- Modify: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/access/RoleSeederIT.java:66-73` — WORKSPACE_MANAGER list gains both codes.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationModelIT.java` (`@SpringBootTest`, `MongoTemplate`).

**Interfaces:**
- Produces enums: `AutomationEventKind { MESSAGE_RECEIVED, DEAL_LANE_CHANGED }`, `RuleStatus { DRAFT, PUBLISHED }`, `AutomationActionType { SEND_TEMPLATE, ASSIGN_CHAT, ADD_TO_CRM }`, `AssignMode { AGENT, TEAM_QUEUE, TEAM_ROUND_ROBIN }`, `WhenAway { KEEP_WITH_AGENT, OWNING_TEAM_QUEUE }`, `EventStatus { PENDING, RUNNING }`, `EventOutcome { DONE, FAILED }`, `RunStatus { RUNNING, TESTING, DONE, FAILED, INTERRUPTED }`, `StepOutcome { DONE, SKIPPED, FAILED }`, `TestStatus { WAITING, CAPTURED, ENDED }`, `TestEndReason { DONE, IDLE, RULE_PUBLISHED, RULE_DELETED }`.
- `@JsonInclude(NON_NULL) record ConditionNode(String combinator, Boolean not, List<ConditionNode> rules, String field, String operator, Object value) { boolean isGroup() { return rules != null; } }`.
- `record AutomationAction(AutomationActionType type, String templateId, Map<String,String> values, AssignMode mode, String userId, String teamId, WhenAway whenAway, String pipelineId, String stageId, String dealTitle)`.
- `record EventData(ChatData chat, MessageData message, NumberData number, ContactData contact, DealData deal, Ref pipeline, LaneData fromLane, LaneData toLane)` with nested `Ref(String id, String name)`, `ChatData(String id, boolean isNew, ChatStatus status, Ref assignedUser, Ref assignedTeam)`, `MessageData(String id, String text, String type)` (type = kind), `NumberData(String id, String number, String label)`, `ContactData(String id, String name, String phone, boolean inCrm, boolean doNotContact)`, `DealData(String id, String title, String status, @Field(targetType = DECIMAL128) BigDecimal amount, String currency, Ref owner, String lostReason)`, `LaneData(String id, String name, String type)`.
- `@Document("automationRules") AutomationRule`: `id, customerId, workspaceId, name, description; AutomationEventKind event; ConditionNode conditions; List<AutomationAction> actions; RuleStatus status; Integer priority; Instant publishedAt; String publishedByUserId, publishedByName, createdByUserId, createdByName; @CreatedDate creationTime; @LastModifiedDate lastUpdateTime; @Version Long version`. Index `ws_event_status {workspaceId:1, event:1, status:1}`.
- `@Document("automationEvents") AutomationEvent`: `id, customerId, workspaceId; AutomationEventKind kind; EventData data; String contactId, contactPhone; EventStatus status; int attempts; Instant leaseUntil; String lastError; Instant createdAt`. Index `status_created {status:1, createdAt:1}`.
- `@Document("automationEventHistory") AutomationEventRecord`: `id` (= the event's id), `customerId, workspaceId, kind, data, contactId, dealId; EventOutcome outcome; String error; int attempts; List<String> ruleIds, testIds; Instant createdAt, finishedAt`. Indexes `ws_created {workspaceId:1, createdAt:-1}`, `ws_kind_created {workspaceId:1, kind:1, createdAt:-1}`, `ws_contact_created {workspaceId:1, contactId:1, createdAt:-1}`, `rule_created {ruleIds:1, createdAt:-1}`.
- `record RunStep(int index, AutomationActionType type, StepOutcome outcome, String reason, Instant at)`.
- `@Document("automationRuns") AutomationRun`: `id, customerId, workspaceId, eventId; AutomationEventKind eventKind; String ruleId, ruleName; boolean test; String testId; RunStatus status; String contactId, contactName, contactPhone, dealId, dealTitle, summary; List<RunStep> steps; Instant startedAt, finishedAt`. Indexes `event_rule {eventId:1, ruleId:1}` **unique**, `rule_started {ruleId:1, startedAt:-1}`, `ws_started {workspaceId:1, startedAt:-1}`.
- `record ConditionResult(String path, String field, String operator, Object value, Object actual, boolean passed)`.
- `@Document("automationTests") AutomationTest`: `id, customerId, workspaceId, ruleId; AutomationEventKind event; boolean active; TestStatus status; TestEndReason endReason; String startedByUserId, startedByName, targetContactId, targetPhone, targetName; boolean treatAsNew; String eventId; Instant capturedAt; EventData data; List<ConditionResult> conditionResults; Boolean matched; String runId; Integer nextAction; Instant waitingSince, lastActivityAt; @CreatedDate creationTime; @Version Long version`. Indexes `rule_active {ruleId:1}` **unique, partial `{active: true}`**, `ws_event_status {workspaceId:1, event:1, status:1}`, `active_activity {active:1, lastActivityAt:1}`.
- Repositories (`MongoRepository<…, String>`): `AutomationRuleRepository` — `Optional<AutomationRule> findByIdAndWorkspaceId(String id, String workspaceId)`, `List<AutomationRule> findByWorkspaceId(String workspaceId)`, `List<AutomationRule> findByWorkspaceIdAndEventAndStatus(String ws, AutomationEventKind event, RuleStatus status)`, `boolean existsByWorkspaceIdAndEventAndStatus(String ws, AutomationEventKind event, RuleStatus status)`; `AutomationEventRepository`; `AutomationEventRecordRepository`; `AutomationRunRepository` — `Optional<AutomationRun> findByEventIdAndRuleId(String eventId, String ruleId)`, `List<AutomationRun> findByEventIdIn(Collection<String> eventIds)`; `AutomationTestRepository` — `Optional<AutomationTest> findByIdAndWorkspaceId(String id, String ws)`, `Optional<AutomationTest> findByRuleIdAndActiveTrue(String ruleId)`, `List<AutomationTest> findByWorkspaceIdAndEventAndStatus(String ws, AutomationEventKind event, TestStatus status)`, `boolean existsByWorkspaceIdAndEventAndStatus(...)`, `List<AutomationTest> findByActiveTrueAndLastActivityAtBefore(Instant t)`.
- `OmnichannelProperties.Automation(@DefaultValue("true") boolean autoTick, @DefaultValue("2s") Duration tick, @DefaultValue("5m") Duration lease, @DefaultValue("3") int maxAttempts, @DefaultValue("20") int batch, @DefaultValue("30m") Duration testIdle, @DefaultValue("60s") Duration sendTimeout)` as `props.automation()`.

- [ ] **Step 1: Write the failing test** `AutomationModelIT`: `collectionsAreCamelCase` (`mongo.getCollectionName(X.class)` equals the five names); `noCollectionHasATtlIndex` (for each of the five, every `mongo.indexOps(c).getIndexInfo()` has `getExpireAfter()` empty); `runIsUniquePerEventAndRule` (two `AutomationRun` with `eventId "e1"`, `ruleId "r1"` → second `insert` throws `DuplicateKeyException`); `oneActiveTestPerRule` (two active tests of `r1` → `DuplicateKeyException`; one `active=false` plus one active → both saved); `conditionTreeRoundTrips` (save a rule with `{and, not false, rules:[{field "message.type", operator "is_one_of", value ["TEXT","PHOTO"]}, {or, rules:[{field "contact.inCrm", operator "is", value false}]}]}` → read back equal); `propertiesHaveDefaults` (`props.automation().lease()` = 5 min, `maxAttempts()` 3, `testIdle()` 30 min, `autoTick()` false in tests); `authoritiesSeededToManagers` (`roles.findByName(WORKSPACE_MANAGER)` and `CUSTOMER_ADMIN` hold `automation:view` and `automation:manage`; `AGENT` holds neither). Update `RoleSeederIT.builtInRolesGetTheirDefaultAuthorities` with the two codes.
- [ ] **Step 2: Run** `-Dtest=AutomationModelIT,RoleSeederIT` → compilation fails.
- [ ] **Step 3: Implement** the documents, enums, repositories, properties, yml (`omnichannel.automation.auto-tick: true`, `tick: 2s`, `lease: 5m`, `max-attempts: 3`, `batch: 20`, `test-idle: 30m`, `send-timeout: 60s`) and the two authorities.
- [ ] **Step 4: Run** `-Dtest=AutomationModelIT,RoleSeederIT,AuthorityTest` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): collections, authorities and settings`

---

### Task 2: Conditions — catalogue, evaluation, shape validation

**Files:**
- Create in `omnichannel-service/src/main/java/me/nageshk/omnichannel/service/automation/`: `MessageKinds.java`, `Operator.java`, `ConditionCatalog.java`, `AutomationFields.java`, `ConditionEvaluator.java`, `RuleRequest.java`, `RuleShapeValidator.java`.
- Test: `omnichannel-service/src/test/java/me/nageshk/omnichannel/service/automation/ConditionEvaluatorTest.java`, `RuleShapeValidatorTest.java` (plain JUnit).

**Interfaces:**
- `MessageKinds`: `static final Set<String> REAL_TYPES` (the 11 wa types of the plan call); `static Optional<String> kindOf(String waType)`; `static final List<String> KINDS = List.of("TEXT","PHOTO","VIDEO","AUDIO","DOCUMENT","STICKER","LOCATION","CONTACT_CARD","POLL")`.
- `enum Operator { IS("is"), IS_NOT("is_not"), IS_ONE_OF("is_one_of"), CONTAINS("contains"), NOT_CONTAINS("not_contains"), STARTS_WITH("starts_with"), IS_EMPTY("is_empty"), IS_NOT_EMPTY("is_not_empty"), GREATER_THAN("greater_than"), LESS_THAN("less_than"), BETWEEN("between"); String code(); static Optional<Operator> of(String code) }`.
- `ConditionCatalog`: `enum FieldType { BOOLEAN, TEXT, CHOICE, NUMBER }`, `record FieldSpec(String key, FieldType type, Set<Operator> operators)`, `static Optional<FieldSpec> field(AutomationEventKind event, String key)` — exactly the REST contract's field table.
- `AutomationFields`: `static Map<String,Object> values(EventData d, boolean inCrmNow)` — message keys from `chat.isNew`, `message.text`, `message.type`, `number.id`, `inCrmNow`, `contact.name`; lane keys from `pipeline.id`, `toLane.id`, `fromLane.id`, `toLane.type`, `deal.amount`, `deal.owner.id`.
- `ConditionEvaluator`: `record Leaf(String path, ConditionNode node)`; `static List<Leaf> leaves(ConditionNode root)` (depth-first; paths `rules[0]`, `rules[1].rules[0]`, …); `static ConditionResult check(Leaf leaf, Map<String,Object> values)`; `static boolean combine(ConditionNode root, Map<String,Boolean> passedByPath)`; `static boolean matches(ConditionNode root, Map<String,Object> values)` (null root or empty group → true; `not` negates a group). Text comparisons trim and ignore case (`Locale.ROOT`); null actual is empty (`is` false, `is_not` true, `contains`/`starts_with` false, `not_contains` true); numbers compared as `BigDecimal`; `between` inclusive.
- `record RuleRequest(String name, String description, AutomationEventKind event, Integer priority, ConditionNode conditions, List<AutomationAction> actions, Long version)`.
- `RuleShapeValidator`: `static Map<String,String> check(RuleRequest r)` — `name` "is required" (blank) / "must be at most 100 characters"; `description` ≤ 500; `event` "is required"; `priority` "must be a whole number from 0 to 9999"; `conditions` "at most 3 levels of groups" / "at most 30 conditions"; per leaf `.field` "is not a field of this event", `.operator` "is not offered for this field", `.value` "is required" / "must be a number" / "must be two numbers, low then high" / "choose at least one" (empty `is_one_of`) / must be `true` or `false`; group `.combinator` "must be and or or"; `actions[i].type` "is required"; `ADD_TO_CRM` on `DEAL_LANE_CHANGED` → `actions[i].type` "Add to CRM works on incoming messages only"; `ASSIGN_CHAT` without `mode` → `actions[i].mode` "is required"; `dealTitle` ≤ 200. Completeness (names that exist) is Task 9's.

- [ ] **Step 1: Write the failing tests.** `ConditionEvaluatorTest`: `noConditionsMatchEverything` (`matches(null, …)` and empty `and` group → true); `textIgnoresCaseAndTrims` (`message.text contains "PRICE"` on `" what is the price? "` → true; `starts_with "what"` → true; `is "hello"` on `"Hello "` → true); `nullActualComparesAsEmpty` (`contact.name` null: `is_empty` true, `contains "a"` false, `not_contains "a"` true, `is "x"` false, `is_not "x"` true); `messageTypeIsOneOf` (`["TEXT","PHOTO"]` vs `"PHOTO"` true, vs `"STICKER"` false); `anyGroupNestedInAll` (`and[chat.new is true, or[message.text contains "hi", message.type is_one_of ["PHOTO"]]]` with new=true,text "bye",type PHOTO → true; new=false → false); `notNegatesAGroup`; `amountBetweenIsInclusive` (`[1000, 5000]` on 1000 and 5000 → true, 5000.01 → false); `amountEmpty` (null amount `is_empty` true, `greater_than 0` false); `leavesAreDepthFirstWithPaths` (paths `rules[0]`, `rules[1].rules[0]`, `rules[1].rules[1]`); `checkReportsActual` (`check` on `chat.new is true` with new=false → `actual false`, `passed false`); `combineUsesStepResults`; `valuesReadEventData` (message `EventData` with `isNew true`, type "TEXT", number id "n1", `inCrmNow true` → map entries `chat.new=true`, `message.type="TEXT"`, `number="n1"`, `contact.inCrm=true`); `kindsOfRealTypes` (`ptt`→AUDIO, `multi_vcard`→CONTACT_CARD, `poll_creation`→POLL; `call_log`, `revoked`, `reaction`, `ciphertext`, `e2e_notification` → empty). `RuleShapeValidatorTest`: one test per message above, asserting the exact key and sentence, e.g. `unknownFieldForEvent` (`deal.amount` on MESSAGE_RECEIVED → `conditions.rules[0].field` = "is not a field of this event"), `addToCrmOnLaneChangeRefused`, `fourLevelsRefused`, `thirtyOneConditionsRefused`, `priorityOutOfRange` (10000).
- [ ] **Step 2: Run** `./mvnw -q -pl omnichannel-service -am test -Dtest='ConditionEvaluatorTest,RuleShapeValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false` → compilation fails.
- [ ] **Step 3: Implement** the classes per the Interfaces block.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automation): condition catalogue, evaluator and rule shape checks`

---

### Task 3: The message event

**Files:**
- Create in `service/automation/`: `AutomationGate.java`, `AutomationEventWriter.java`, `AutomationMessageListener.java`, `AutomationCrm.java` (port; Task 7 adds the lead methods).
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/crm/AutomationCrmAdapter.java` (only `inCrm` for now).
- Modify: `omnichannel-db/.../db/inbox/WaWebMessageRepository.java` — `boolean existsByChatIdAndDirectionAndTypeInAndIdNot(String chatId, MessageDirection direction, Collection<String> types, String id)`.
- Modify: `omnichannel-crm/.../crm/contact/CaptureService.java` — `public boolean inCrm(String workspaceId, String contactId, String phoneNumberId)` (a `crmContacts` row exists).
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationTestBase.java`, `AutomationDevice.java`, `MessageEventIT.java`.

**Interfaces:**
- `AutomationCrm` (service port, implemented in `app.crm`): `boolean inCrm(String workspaceId, String contactId, String phoneNumberId)` — false when no row or the CRM is off.
- `AutomationGate` (`@Component`): `boolean wants(String workspaceId, AutomationEventKind kind, String contactId, String phone)` = a PUBLISHED rule of that event exists, **or** a WAITING test of that event targets this contact (`targetContactId == contactId`, or `targetPhone == phone` when phone is not null). A draft under test therefore queues only the test contact's events, not every message of the brand.
- `AutomationEventWriter` (`@Component`): `void messageReceived(WaWebChat chat, WaWebMessage message)` — returns unless `gate.wants`; id `"m-" + message.getId()`; skips when `history.existsById(id)`; builds `EventData` (chat with `isNew = !existsBy…(chat.id, IN, REAL_TYPES, message.id)`, status, assigned user/team as `Ref` with names; message `{id, text = body, type = kind}`; number `{id, number, label}`; contact `{id, name, phone, inCrm = crm.inCrm(...), doNotContact = contact.getDoNotContact() != null}`); inserts `AutomationEvent` PENDING with `contactId`, `contactPhone`, `createdAt = clock.instant()`; a `DuplicateKeyException` is ignored. Task 4 adds `dealLaneChanged`.
- `AutomationMessageListener` (`@Component`): `@EventListener void on(InboxEvents.MessageUpserted e)` → writer only when `e.inserted()`, `direction == IN`, `source == CONTACT`, `MessageKinds.REAL_TYPES.contains(type)`; any `RuntimeException` logged at warn, never rethrown.
- Test base `AutomationTestBase` (model on `CampaignTestBase`: `@AutoConfigureTestRestTemplate`, `RANDOM_PORT`, `properties = "omnichannel.rpc.timeout=2s"`, `@Primary MutableClock` at `2026-11-03T05:30:00Z`): fields `acme, brand, other, n1` (owned by team `support` = {agent, agent2}), `n2` (no team), `manager` (WORKSPACE_MANAGER), `admin` (CUSTOMER_ADMIN), `agent`, `agent2`, `outsider` (AGENT, no team), `staff` (ACCOUNT_MANAGER); helpers `ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body)` on `/api/workspaces/{brand}/automations`; `WaWebChat incoming(PhoneNumber n, String remote, String type, String body, String notifyName)` (publishes `new WhatsAppEvent(this, n.getId(), brand.getId(), acme.getId(), "message_create", payload, null, null)` with `id._serialized = "false_" + remote + "_" + counter`, returns the stored chat); `AutomationRule rule(String name, AutomationEventKind event, RuleStatus status, Integer priority, ConditionNode conditions, AutomationAction... actions)` (saved directly); `String template(String name, String body)` (via `POST /templates`, contact placeholders with fallback `"there"`, as `CampaignTestBase.template`); `void enableCrm()` (`PUT /api/customers/{acme}/workspaces/{brand}/crm {enabled:true}` as staff); `static ConditionNode leaf(String field, String op, Object value)`, `static ConditionNode all(ConditionNode... rules)`, `any(...)`. `clean()` empties the five automation collections, `Contact`, `WaWebChat`, `WaWebMessage`, `WaWebChatAction`, `MessageTemplate`, `InboxSetting` and every `crm*` collection.
- `AutomationDevice implements AutoCloseable` (model on `app/campaign/AutoDevice.java`): connects and sends `ready`; answers `sendMessage` with a fromMe model whose `body` is the request's `content`; `List<Envelope> requests(String method)`; `volatile Function<Envelope, Envelope> sendAnswer`.

- [ ] **Step 1: Write the failing tests** `MessageEventIT extends AutomationTestBase` (a PUBLISHED MESSAGE_RECEIVED rule exists unless the test says otherwise):
  - `firstRealMessageQueuesNewChatEvent`: `incoming(n1, "919811100001@c.us", "chat", "Hello", "Asha")` → one `automationEvents` doc, `status PENDING`, `kind MESSAGE_RECEIVED`, `data.chat.isNew true`, `data.message.text "Hello"`, `data.message.type "TEXT"`, `data.number.id == n1.id`, `data.contact.name "Asha"`, `data.contact.phone "919811100001"`, `inCrm false`, `doNotContact false`; `data.chat.assignedTeam.id == support.id` (routing ran first: `support` set to TEAM mode with `InboxSettingService.putOverride`, as `ChatRoutingIT.configure` does).
  - `secondRealMessageIsNotNew`: two messages → second event `isNew false`.
  - `callOrNoticeFirstKeepsTheChatNew`: `incoming(..., "call_log", null, …)` then `"chat"` → exactly one event (the chat one), `isNew true`.
  - `fetchedChatIsNotNew`: `ingest.importMessage(new ImportContext(acme, brand, n1), payload)` of an inbound `chat` message, then a live message → event `isNew false`; the import itself queued nothing.
  - `nonRealTypesQueueNothing`: `call_log`, `revoked`, `e2e_notification` → zero events.
  - `outgoingQueuesNothing`: a `fromMe` `message_create` → zero events.
  - `noEventWithoutPublishedRuleOrWaitingTest`: only a DRAFT rule → zero events; add a WAITING `AutomationTest` of the brand (event MESSAGE_RECEIVED) targeting Asha's contact → Asha's next message queues one, and a message from Ravi queues none (`testOnlyGateTakesTheTestContactOnly`); a WAITING test with `targetPhone "919811100009"` → a first message from `919811100009@c.us` queues one.
  - `otherEventKindDoesNotOpenTheGate`: only a published DEAL_LANE_CHANGED rule → zero events.
  - `replayedMessageQueuesOneEvent`: publish the same payload twice → one event (`id "m-" + message id`).
  - `ingestionSurvivesEventWriteFailure`: `@MockitoSpyBean AutomationGate`, `doThrow(new DataAccessResourceFailureException("down")).when(gate).wants(any(), any())` → the message and chat are stored, the chat is routed, no exception reaches the publisher.
  - `inCrmReadsTheCrm`: CRM enabled and the contact captured on `n1` (`CaptureService` via `POST /crm/contacts/capture` as manager) → next event `contact.inCrm true`.
- [ ] **Step 2: Run** `-Dtest=MessageEventIT` → compilation fails.
- [ ] **Step 3: Implement** per the Interfaces block; `AutomationCrmAdapter.inCrm` delegates to `CaptureService.inCrm`.
- [ ] **Step 4: Run** `-Dtest=MessageEventIT,WaWebInboxListenerIT,ImportMessageIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): queue an event for live incoming real messages`

---

### Task 4: The deal lane change port

**Files:**
- Create: `omnichannel-crm/src/main/java/me/nageshk/omnichannel/crm/platform/DealObserver.java`, `DealLaneChange.java`.
- Modify: `omnichannel-crm/.../crm/deal/DealService.java` — constructor gains `DealObserver observer`; `move` (:576), `close` (:609) and `update` (:328; **both** its branches: a stage change inside the same pipeline at :348 and a pipeline change at :405-451) call `private void laneChanged(CrmContext ctx, CrmDeal before, CrmDeal saved, CrmPipeline from, CrmPipeline to)` once, after `placement.recompute`; it returns unless the stage or the pipeline changed and swallows any `RuntimeException` (logged). `Placement` and `DealPlacementImpl` are not touched.
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/crm/DealObserverAdapter.java`.
- Modify: `service/automation/AutomationEventWriter.java` — `dealLaneChanged`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/crm/DealObserverIT.java` (`extends CrmTestBase`), `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/DealEventIT.java` (`extends AutomationTestBase`).

**Interfaces:**
- `public interface DealObserver { void laneChanged(DealLaneChange change); }` — called after the move is saved; implementations must not throw.
- `public record DealLaneChange(String workspaceId, String customerId, String dealId, String title, String status, BigDecimal amount, String currency, String ownerId, String lostReason, String pipelineId, String pipelineName, Lane from, Lane to, String contactId, String phoneNumberId, Instant at) { public record Lane(String id, String name, String type) {} }` — `contactId` is the primary CRM contact's brand contact id, `phoneNumberId` the deal's `channel.phoneNumberId`; `from.name` null for a removed stage; `pipeline*` is the pipeline the deal is now in.
- `AutomationEventWriter.dealLaneChanged(DealMove m)` (gate with the deal's `contactId`, no phone) with `public record DealMove(String workspaceId, String customerId, String dealId, String title, String status, BigDecimal amount, String currency, String ownerId, String lostReason, String pipelineId, String pipelineName, EventData.LaneData from, EventData.LaneData to, String contactId, String phoneNumberId)`: gate on `DEAL_LANE_CHANGED`; data `deal {…, owner Ref with the user's name}`, `pipeline`, `fromLane`, `toLane`, `contact {id, name, phone, inCrm true, doNotContact}`, `number`, `chat` = `ChatData(id, false, status, …)` of `chats.findFirstByPhoneNumberIdAndContactId(phoneNumberId, contactId)` or null; `contactId` on the event.
- `DealObserverAdapter implements DealObserver` maps to `DealMove` and calls the writer inside try/catch.

- [ ] **Step 1: Write the failing tests.** `DealObserverIT` (`@MockitoSpyBean DealObserver observer`, `ArgumentCaptor<DealLaneChange>`): `boardMoveReports` (`POST /crm/deals/{id}/move {stageId: <second lane>}` → one call, `from.id` first lane, `to.id` second, `to.type "OPEN"`, `contactId` = the captured contact); `closeWonReports` (`POST …/close {outcome:"WON"}` → `to.type "WON"`, `status "WON"`); `pipelineChangeReports` (`PUT /crm/deals/{id}` with another `pipelineId` → `pipelineId` the new one); `formStageChangeReports` (`PUT /crm/deals/{id}` with another `stageId` of the same pipeline → one call, `from`/`to` the two lanes); `sameLaneIsSilent` (`POST …/move` with the deal's current `stageId` and a new position → zero calls; closing LOST twice → no second call, whatever the second close answers); `stageDeletionRelocatesSilently` (replace stages without the deal's lane → zero calls); `pipelineDeletionRelocatesSilently` (`DELETE /crm/pipelines/{id}?moveDealsToPipelineId=…` → zero calls); `creationIsSilent` (`POST /crm/deals`, capture → zero calls); `dealMoveSurvivesObserverFailure` (`doThrow(new RuntimeException("x")).when(observer).laneChanged(any())` → move answers 200 and the deal is in the new lane). `DealEventIT`: `laneChangeQueuesEvent` (published DEAL_LANE_CHANGED rule; call the adapter bean with a `DealLaneChange` for a contact with a chat on `n1` → event `data.toLane.name "Won"`, `data.deal.owner.name` = the owner's name, `data.chat.id` = that chat); `dealWithoutChatHasNullChat`; `noRuleNoEvent` (each calls the `DealObserver` bean, autowired by its interface).
- [ ] **Step 2: Run** `-Dtest=DealObserverIT,DealEventIT` → compilation fails.
- [ ] **Step 3: Implement** the port, the `DealService` calls, the adapter and `dealLaneChanged`.
- [ ] **Step 4: Run** `-Dtest=DealObserverIT,DealEventIT,CrmDealsApiIT,BoardApiIT,CrmPipelinesApiIT`; `./mvnw -q -pl omnichannel-crm test -Dtest=CrmModuleBoundaryTest` → PASS.
- [ ] **Step 5: Commit** — `feat(crm): report deal lane changes through crm.platform.DealObserver`

---

### Task 5: Send template action

**Files:**
- Create: `omnichannel-service/.../service/inbox/AutomationActor.java`; modify `SendActor.java` (`permits UserActor, CampaignActor, AutomationActor`).
- Modify: `omnichannel-db/.../db/inbox/MessageSource.java` — add `AUTOMATION`.
- Modify: `service/inbox/InboxSendService.java` — `:122` the OPEN check applies to every actor but `AutomationActor`; `:153` switch gains `case AutomationActor a -> renderer.renderForAutomation(workspaceId, templateId, chat.getContactId(), req.values())`.
- Modify: `service/template/TemplateRenderer.java` — `renderForAutomation`.
- Create in `service/automation/`: `ActionContext.java`, `ActionResult.java`, `ActionHandler.java`, `SendTemplateHandler.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/SendTemplateActionIT.java`.

**Interfaces:**
- `public record AutomationActor(String ruleId, String ruleName) implements SendActor`: `userId()` null, `displayName()` `"Automation: " + ruleName`, `source()` `AUTOMATION`, `campaignId()` null.
- `TemplateRenderer.renderForAutomation(String workspaceId, String templateId, String contactId, Map<String,String> values): RenderedTemplate` — `context(...)` with the contact loaded by `contacts.require` (no authority check; null contact id → fallbacks), AGENT values from `values`; `missing` lists required AGENT placeholders without a value.
- `record ActionContext(String workspaceId, String customerId, AutomationEventKind kind, String eventId, EventData data, String ruleId, String ruleName, boolean test)`.
- `record ActionResult(StepOutcome outcome, String reason)` with `static ActionResult done(String)`, `skipped(String)`, `failed(String)`.
- `interface ActionHandler { AutomationActionType type(); ActionResult run(ActionContext ctx, AutomationAction action); }`.
- `SendTemplateHandler` (`@Component`): chat = `chats.findById(data.chat.id)` else `failed("No chat with this contact")`; do-not-contact when the chat's contact has `doNotContact != null` **or** `RecipientSkips.isDoNotContact(ws, contact.phone)` → `skipped("Contact is marked do-not-contact")`; template missing → `failed("The template no longer exists")`; archived → `failed("The template is archived")`; then `sender.send(new AutomationActor(ruleId, ruleName), ws, chat, new SendRequest(null, templateId, values), null).get(props.automation().sendTimeout())` → `done("Sent “<template name>”")`. Failures: `NoDeviceException` `NO_DEVICE` → `"The number's extension is offline or the number is disabled"`, `WHATSAPP_NOT_READY` → `"WhatsApp Web is not ready on the number"`; `ValidationException` naming a placeholder → `"Placeholder “<label>” has no value"`; `RpcException` → `"WhatsApp did not send it (<code>)"`; `TimeoutException` → `"The extension did not answer in time"`. No sending-hours check.

- [ ] **Step 1: Write the failing tests** `SendTemplateActionIT` (handler called directly with an `ActionContext`; an `AutomationDevice` on `n1`; template "Welcome" = `"Hi {{contact.name}}, welcome to Acme!"`):
  - `sendsFilledTemplateAsAutomation`: contact "Asha" → `done`; device got one `sendMessage` with `content "Hi Asha, welcome to Acme!"`; stored message `source AUTOMATION`, `sentByName "Automation: Welcome rule"`, `sentByUserId null`, `templateId` set.
  - `nameFallsBackWhenUnknown`: contact without name → `"Hi there, welcome to Acme!"`.
  - `agentPlaceholderTakesRuleValue`: body `"Order {{order_id}} is ready"` (AGENT, required) with `values {order_id: "A-17"}` → content `"Order A-17 is ready"`; without the value → `failed("Placeholder “order_id” has no value")` and no request.
  - `doNotContactIsSkipped`: contact with `DoNotContact(now, MANUAL, null)` → `skipped("Contact is marked do-not-contact")`, zero `sendMessage` requests.
  - `doNotContactByPhoneIsSkipped`: the chat's `@lid` contact is clear but another contact with its phone is marked → skipped.
  - `sendsOutsideCampaignHours`: clock at Sunday 2026-11-08 23:00 IST → `done` (24x7).
  - `doesNotClaimOrReopen`: unassigned OPEN chat → still unassigned after; CLOSED chat → message stored, chat still `CLOSED`, no `REOPENED` action.
  - `noChatFails`: `data.chat` null → `failed("No chat with this contact")`.
  - `archivedTemplateFails`; `deviceOfflineFails` (no device connected → `"The number's extension is offline or the number is disabled"`).
  - `consoleSendStillRefusesClosedChat`: `InboxSendService.send(userActor, …)` on a CLOSED chat still throws `ChatClosedException`.
- [ ] **Step 2: Run** `-Dtest=SendTemplateActionIT` → compilation fails.
- [ ] **Step 3: Implement** per the Interfaces block.
- [ ] **Step 4: Run** `-Dtest=SendTemplateActionIT,InboxSendIT,CampaignRunnerIT,NewChatIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): send template action as "Automation: <rule>"`

---

### Task 6: Assign chat action

**Files:**
- Modify: `omnichannel-db/.../db/inbox/RoutedReason.java` (add `AUTOMATION`), `WaWebChatAction.java` (add `String reasonDetail`).
- Modify: `service/inbox/ActionView.java` (component `String reasonDetail` after `reason`), `InboxViews.java:63-64`.
- Modify: `service/inbox/ChatRoutingService.java` — `public Team owningTeam(WaWebChat)`, `public static boolean canTakeChats(User, String)`, `assignByAutomation`, `roundRobinByAutomation`; the private `routed(...)` takes `byName` and `reasonDetail`.
- Modify: `service/inbox/ChatAssignmentService.java:262` — `canSeeNumber` public.
- Create: `service/automation/AssignChatHandler.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AssignChatActionIT.java`.

**Interfaces:**
- `ChatRoutingService.assignByAutomation(WaWebChat chat, User user, Team team, String ruleName): WaWebChat` — sets user/team/`assignedAt`, saves, records `ROUTED` with `reason AUTOMATION`, `reasonDetail ruleName`, `byName "Automation: " + ruleName`, publishes `ActionRecorded` **and** `ChatChanged`.
- `ChatRoutingService.roundRobinByAutomation(WaWebChat chat, Team team, String ruleName): WaWebChat` — the next eligible member after the team's fresh `roundRobinCursor` (the same `nextMember` the inbox uses, whatever the team's routing mode), cursor moved after the save; nobody → the team's queue (user null).
- `AssignChatHandler` (`@Component`, `ASSIGN_CHAT`): chat from `data.chat.id` else `failed("No chat with this contact")`.
  - `AGENT`: user among `userAdmin.withAccessTo(customerId, ws)` else `failed("The agent is no longer in the brand")`; `!assignment.canSeeNumber(user, chat)` → `failed("<name> cannot see this number")`; away (`!canTakeChats`) with `OWNING_TEAM_QUEUE` and an owning team → `assignByAutomation(chat, null, owningTeam)` → `done("<name> is away; waiting in <team>'s queue")`; else `assignByAutomation(chat, user, owningTeam if user is a member else null)` → `done("Assigned to <name>")`.
  - `TEAM_QUEUE`: team of the brand else `failed("The team no longer exists")` → `assignByAutomation(chat, null, team)` → `done("Waiting in <team>'s queue")`.
  - `TEAM_ROUND_ROBIN`: → `done("Assigned to <name> (round robin in <team>)")` or `done("Nobody in <team> is available; waiting in its queue")`.

- [ ] **Step 1: Write the failing tests** `AssignChatActionIT` (a chat on `n1` already routed to `agent2` by routing):
  - `agentReplacesRouting`: AGENT `agent` → chat `assignedUserId agent`, `assignedTeamId support`; newest action `type ROUTED`, `reason AUTOMATION`, `reasonDetail "Welcome rule"`, `byName "Automation: Welcome rule"`; `GET /inbox/chats/{id}/thread` shows the action with `reasonDetail`.
  - `awayAgentGoesToOwningTeamQueue`: `agent` AWAY, `OWNING_TEAM_QUEUE` → user null, team `support`, reason "Agent… is away; waiting in Support's queue".
  - `awayAgentKept`: `KEEP_WITH_AGENT` → assigned to `agent`.
  - `awayAgentKeptWhenNumberHasNoTeam`: chat on `n2`, `OWNING_TEAM_QUEUE` → assigned to `agent`, team null.
  - `agentWhoCannotSeeNumberFails`: `outsider` (not in `support`, no `inbox:view-all`) → `failed("<outsider name> cannot see this number")`, assignment unchanged.
  - `teamQueue`: TEAM_QUEUE `support` on a chat assigned to `agent2` → user null, team `support`.
  - `roundRobinSharesTheTeamCursor`: cursor at `agent` → assigned `agent2`, `support.roundRobinCursor == agent2`; next inbox routing (team ROUND_ROBIN) picks `agent`.
  - `roundRobinNobodyAvailable`: both AWAY → team queue, reason "Nobody in Support is available; waiting in its queue".
  - `removedAgentFails`, `deletedTeamFails`, `noChatFails`.
- [ ] **Step 2: Run** `-Dtest=AssignChatActionIT` → compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `-Dtest=AssignChatActionIT,ChatRoutingIT,AvailabilityRoutingIT,OwnershipRoutingIT,ChatAssignmentIT,InboxApiIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): assign chat action recorded as routed by automation`

---

### Task 7: Add to CRM action

**Files:**
- Modify: `service/automation/AutomationCrm.java` — lead methods; `app/crm/AutomationCrmAdapter.java`.
- Modify: `omnichannel-crm/.../crm/contact/CaptureService.java` — `captureForAutomation`; `placed(...)` takes the owner.
- Modify: `omnichannel-crm/.../crm/deal/DealService.java` — `createForAutomation`.
- Create: `service/automation/AutomationActionFailed.java`, `AddToCrmHandler.java`, `DealTitles.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AddToCrmActionIT.java`.

**Interfaces:**
- `AutomationCrm` adds: `record LeadRequest(String workspaceId, String customerId, String contactId, String phoneNumberId, String chatId, Instant firstMessageAt, String pipelineId, String stageId, String dealTitle, String ownerUserId)`, `record LeadResult(String crmContactId, String dealId, String pipelineName, String laneName)`, `LeadResult addLead(LeadRequest r)` (throws `AutomationActionFailed`), `record LaneOption(String id, String name, String type)`, `record PipelineOption(String id, String name, boolean active, List<LaneOption> lanes)`, `record CrmOptions(boolean enabled, String defaultPipelineId, List<PipelineOption> pipelines)`, `CrmOptions options(String workspaceId)` (disabled → `false, null, []`).
- `public class AutomationActionFailed extends RuntimeException` (message = the step reason).
- `CaptureService.captureForAutomation(CrmContext ctx, PlatformContact c, PlatformNumber channel, Origin origin, String pipelineId, String stageId, String dealTitle, String ownerId): CaptureResult` — CRM contact owner and deal owner = `ownerId` (null allowed); `DealService.createForAutomation(CrmContext ctx, CrmContact contact, String pipelineId, String stageId, String title, String ownerId): CaptureDeal` puts the deal in `stageId` (a stage of the pipeline, else `NotFoundException("Lane not found")`) or, null, the first OPEN stage; status from the lane's type.
- Adapter `addLead`: `crmAccess.forSystem(ws, customer)` (`CRM_NOT_ENABLED` → `AutomationActionFailed("CRM is off for this brand")`); pipeline null → `settings.defaultPipelineId` (null → `"The brand has no default pipeline"`); missing pipeline → `"The pipeline no longer exists"`, inactive → `"The pipeline is not active"`, missing lane → `"The lane no longer exists"`; `Origin(INBOUND_CHAT, numberId, chatId, firstMessageAt, "system")`.
- `DealTitles.fill(String pattern, String name, String phone): String` per the plan call.
- `AddToCrmHandler` (`ADD_TO_CRM`): kind not MESSAGE_RECEIVED → `failed("Add to CRM works on incoming messages only")`; `crm.inCrm(ws, contactId, numberId)` → `skipped("Already in the CRM")`; owner = fresh chat's `assignedUserId`; `done("Added to <pipeline> › <lane>")`.

- [ ] **Step 1: Write the failing tests** `AddToCrmActionIT` (CRM enabled; a message chat on `n1` with contact "Asha" `919811100001`):
  - `addsLeadToDefaultPipelineFirstLane`: empty settings → `done("Added to Sales › <first lane>")`; one `crmContacts` row (contact, `channel.phoneNumberId n1`, `origin.kind INBOUND_CHAT`, `origin.chatId`), one deal titled `"Asha – WhatsApp"` in the first OPEN lane of the default pipeline.
  - `chosenPipelineAndLane`: pipeline "Leads" lane "Qualified", pattern `"{{name}} ({{phone}})"` → deal `"Asha (+919811100001)"` in "Qualified".
  - `ownerFollowsTheAssignment`: chat assigned to `agent` → CRM contact and deal `ownerId agent`; unassigned → both `ownerId` null.
  - `alreadyInCrmDoesNothing`: run twice → second `skipped("Already in the CRM")`, still one contact and one deal.
  - `crmOffFails`: CRM disabled → `failed("CRM is off for this brand")`.
  - `missingLaneFails`; `inactivePipelineFails`.
  - `titleFallsBackWithoutNameOrPhone`: `@lid` contact with no name, no phone → deal `"WhatsApp contact – WhatsApp"`; `DealTitles.fill("{{name}} – WhatsApp", null, "919811100001")` → `"+919811100001 – WhatsApp"`.
  - `createdDealFiresNoLaneEvent`: a published DEAL_LANE_CHANGED rule exists → after the action, zero `automationEvents`.
- [ ] **Step 2: Run** `-Dtest=AddToCrmActionIT` → compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `-Dtest=AddToCrmActionIT,CaptureApiIT,CrmDealsApiIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): add to CRM action through the AutomationCrm port`

---

### Task 8: The worker — claim, rules in order, runs, history

**Files:**
- Create in `service/automation/`: `AutomationSchedulerConfig.java`, `AutomationTicker.java`, `AutomationWorker.java`, `RuleRunner.java`, `ActionRunner.java`, `RuleOrder.java`, `EventSummaries.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationWorkerIT.java`.

**Interfaces:**
- Bean `ThreadPoolTaskScheduler automationScheduler` (pool 1, prefix `automation-`, daemon, wait on shutdown, 60 s), a copy of `HistoryImportSchedulerConfig`.
- `AutomationTicker` (`@ConditionalOnProperty("omnichannel.automation.auto-tick", havingValue "true", matchIfMissing true)`): `@Scheduled(fixedDelayString = "${omnichannel.automation.tick:2s}", scheduler = "automationScheduler") void tick()` → `worker.tick()`, exceptions logged.
- `RuleOrder.COMPARATOR`: priority non-null first, larger first; then `creationTime` asc; then `id` asc.
- `EventSummaries.of(AutomationEventKind kind, EventData d): String` — `"Message from <name | +phone | a contact>: <text cut to 80 | kind label>"`; `"<deal title> moved from <from | a removed lane> to <to>"`.
- `ActionRunner` (`@Component`, all `ActionHandler` beans by type): `ActionResult run(ActionContext ctx, AutomationAction a)` — any `RuntimeException` → `failed("Unexpected error: " + simpleName)`.
- `RuleRunner` (`@Component`): `Optional<AutomationRun> run(AutomationEvent e, AutomationRule rule)` — existing run: RUNNING → set INTERRUPTED, `finishedAt`; return empty. Else values = `AutomationFields.values(e.data, freshInCrm)` (message: `crm.inCrm` re-read; lane: true); no match → empty. Else insert the run (`RUNNING`, contact/deal snapshot, `summary`; `DuplicateKeyException` → empty), run each action in order through `ActionRunner`, `$push` each `RunStep`, then `DONE` (no FAILED step) or `FAILED`, `finishedAt`.
- `AutomationWorker` (`@Service`): `int tick()` (guarded by an `AtomicBoolean`; Task 10 adds `endIdleTests` first; then up to `batch` × `claim` + `process`; returns events processed); `Optional<AutomationEvent> claim()` — `findAndModify` where `status PENDING` or (`status RUNNING` and `leaseUntil < now`), sort `createdAt` asc, set `RUNNING`, `leaseUntil now + lease`, `inc attempts`, `returnNew`; `void process(AutomationEvent e)` — published rules of (ws, kind) sorted by `RuleOrder`, `ruleRunner.run` each; then `finish(e, DONE, null)`. A `RuntimeException` outside a rule: `attempts < maxAttempts` → back to PENDING with `lastError`; else `finish(e, FAILED, message)`. `finish` inserts the `AutomationEventRecord` (same id, `ruleIds` = runs of the event, `finishedAt`; `DuplicateKeyException` ignored) then deletes the event.

- [ ] **Step 1: Write the failing tests** `AutomationWorkerIT` (an `AutomationDevice` on `n1`; `worker.tick()` called by hand):
  - `welcomeRuleEndToEnd` (spec §1): CRM on; published rule "Welcome" with `chat.new is true`, actions [SEND_TEMPLATE "Welcome", ASSIGN_CHAT TEAM_ROUND_ROBIN `support`, ADD_TO_CRM]; `incoming(n1, …, "chat", "Hello", "Asha")`; `tick()` → content `"Hi Asha, welcome to Acme!"`, chat assigned to a `support` member, deal owner = that member; one run `DONE` with steps `[DONE, DONE, DONE]`; `automationEvents` empty; one `automationEventHistory` doc `outcome DONE`, `ruleIds [welcome.id]`.
  - `existingChatDoesNotMatch`: second message → run count stays 1.
  - `movedToDraftStopsAtOnce`: event queued while the rule is published, `unpublish` before `tick()` → no run, nothing sent.
  - `draftRuleNeverRunsOnLiveEvent`: DRAFT rule plus a published rule that does nothing relevant → the draft has no run, nothing sent by it.
  - `rulesRunInPriorityThenCreationOrder`: rules A(null), B(5), C(10), D(null), E(5) created in that order, each sending a template whose body is its letter → `sendMessage` contents in order `C, B, E, A, D`.
  - `everyMatchingRuleSends`: two matching rules each with a template → two `sendMessage` (spec 4.4, answer 2b).
  - `laterRuleSeesEarlierRulesWork`: rule 1 ADD_TO_CRM, rule 2 `contact.inCrm is true` → rule 2 runs; with the order swapped rule 2 does not.
  - `brokenActionIsLoggedAndNextActionRuns`: actions [SEND_TEMPLATE archived, ASSIGN_CHAT AGENT removed user, ASSIGN_CHAT TEAM_QUEUE `support`] → steps `[FAILED "The template is archived", FAILED "The agent is no longer in the brand", DONE]`, run `FAILED`, chat in `support`'s queue.
  - `eventClaimedTwiceRunsEachRuleOnce`: process the event, re-insert the same event PENDING, `tick()` → one `sendMessage`, one run.
  - `expiredLeaseIsClaimedAgain`: event RUNNING with `leaseUntil` in the past and a RUNNING run for its rule → after `tick()` the run is `INTERRUPTED`, nothing sent, event in history.
  - `failingEventRetriesThenFails`: `@MockitoSpyBean AutomationRuleRepository` throwing on `findByWorkspaceIdAndEventAndStatus` → after tick 1 and 2 the event is PENDING with `attempts` 1, 2; after tick 3 it is in history `outcome FAILED`, `attempts 3`.
  - `automationSendStartsNoEvent`: after `welcomeRuleEndToEnd`'s send, the device echoes the sent message as a `fromMe` `message_create` → no new event (no loops).
  - `laneChangeSendsThankYou` (spec §1, second moment): DEAL_LANE_CHANGED rule `toLane.type is "WON"` with SEND_TEMPLATE "Thanks"; close the deal WON through `DealService` → after `tick()` the deal's chat got the template.
- [ ] **Step 2: Run** `-Dtest=AutomationWorkerIT` → compilation fails.
- [ ] **Step 3: Implement** per the Interfaces block.
- [ ] **Step 4: Run** `-Dtest='Automation*IT,MessageEventIT,DealEventIT,*ActionIT'` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): queue worker runs published rules in order`

---

### Task 9: Rules API — CRUD, draft/publish, options

**Files:**
- Create in `service/automation/`: `AutomationErrors.java`, `RulePublishValidator.java`, `AutomationRuleService.java`, `AutomationOptionsService.java`, `AutomationViews.java`, `RuleView.java`, `RuleSummaryView.java`, `OptionsView.java`, `AutomationRef.java`.
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/automation/AutomationController.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationRulesApiIT.java`.

**Interfaces:**
- `AutomationErrors`: `static CodedException published()` (409 `AUTOMATION_RULE_PUBLISHED`, "Move the rule to draft to change it."), `static CodedException testRunning(AutomationTest t)` (409 `AUTOMATION_TEST_RUNNING`, "This rule is already being tested by <name>.", extras `testId`, `startedById`, `startedByName`), `static CodedException testState(String message)` (409 `AUTOMATION_TEST_STATE`).
- `RulePublishValidator` (`@Component`): `Map<String,String> problems(AutomationRule r)` — shape errors plus: `actions` "Add at least one action"; `actions[i].templateId` "Choose a template" / "The template no longer exists" / "The template is archived" / "Contact card templates cannot be sent"; `actions[i].values.<name>` "<label> needs a value" (required AGENT placeholder without a value); `actions[i].userId` "Choose an agent" / "The agent is no longer in the brand"; `actions[i].teamId` "Choose a team" / "The team no longer exists"; `actions[i].pipelineId` "CRM is off for this brand" / "The pipeline no longer exists" / "The pipeline is not active" / "The brand has no default pipeline"; `actions[i].stageId` "The lane is not in the pipeline".
- `AutomationRuleService`: `List<RuleSummaryView> list(String ws)`; `RuleView get(String ws, String id)`; `RuleView create(AuthenticatedUser me, String ws, RuleRequest r)` (shape errors → `ValidationException(fields)`; status DRAFT); `RuleView update(AuthenticatedUser me, String ws, String id, RuleRequest r)` (published → `published()`; `version` null → `ValidationException("version","is required")`; stale → `OptimisticLockingFailureException`); `RuleView publish(AuthenticatedUser me, String ws, String id)` (problems → `ValidationException`; sets `publishedAt/By`; ends the rule's active test with `RULE_PUBLISHED`); `RuleView unpublish(String ws, String id)`; `void delete(String ws, String id)` (published → `published()`; ends the active test with `RULE_DELETED`; runs and history are kept).
- `RuleSummaryView(String id, String name, AutomationEventKind event, RuleStatus status, Integer priority, String actionsSummary, Instant lastRunAt, long runs7d, long failures7d, Instant creationTime)`: counts over non-test runs with `startedAt >= now - 7d` (`failures7d` = status FAILED); `actionsSummary` = `" · "`-joined `Send “<template>”`, `Assign to <agent>`, `Queue in <team>`, `Round robin in <team>`, `Add to CRM (<pipeline> › <lane>)` or `Add to CRM (default pipeline)`.
- `RuleView` and `OptionsView` exactly the REST contract's `Rule` and `Options`.
- `AutomationController` (`@RequestMapping("/api/workspaces/{workspaceId}/automations")`): `GET /options`, `GET /rules`, `POST /rules` (201), `GET /rules/{id}`, `PUT /rules/{id}`, `POST /rules/{id}/publish`, `POST /rules/{id}/unpublish`, `DELETE /rules/{id}` (204) with `@PreAuthorize("@access.has(#workspaceId, 'automation:view')")` / `'automation:manage'` per the contract table.

- [ ] **Step 1: Write the failing tests** `AutomationRulesApiIT`: `createStartsAsDraft` (201, `status DRAFT`, `version 0`, `createdBy.name`); `invalidShapeIs400` (`fields["conditions.rules[0].field"] == "is not a field of this event"`); `publishedRuleCannotBeEdited` (409 `AUTOMATION_RULE_PUBLISHED`); `moveToDraftThenEdit` (unpublish → PUT 200 → publish 200); `publishChecksWholeness` (no actions → `fields.actions "Add at least one action"`; archived template → `fields["actions[0].templateId"] "The template is archived"`; required `order_id` without value → `fields["actions[0].values.order_id"] "order_id needs a value"`; deleted team; missing lane; Add to CRM with CRM off → `"CRM is off for this brand"`); `publishTwiceIsUnchanged`; `deleteDraftOnly` (published → 409; draft → 204, then 404); `staleVersionIs409` (`code CONFLICT`); `listInRunOrderWithStats` (rules B(5), A(null), C(10) → order C, B, A; a FAILED run 2 days ago and a DONE run 8 days ago and a test run → `runs7d 1`, `failures7d 1`, `lastRunAt` the 2-day one; `actionsSummary "Send “Welcome” · Round robin in Support · Add to CRM (default pipeline)"`); `optionsListTheBrand` (numbers with `owningTeam`, agents with `teamIds` and `seesAllNumbers` true for `manager`, templates without archived or CONTACT_CARD, `crm.enabled false` → `pipelines []`; after `enableCrm()` → the default pipeline with its lanes); `agentIsForbidden` (403 on GET and POST with an AGENT token); `customRoleWithViewOnly` (a role holding only `automation:view`: GET 200, POST 403); `otherBrandRuleIs404`.
- [ ] **Step 2: Run** `-Dtest=AutomationRulesApiIT` → 404s / compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automation): rules API with draft, publish and options`

---

### Task 10: Testing a draft

**Files:**
- Create in `service/automation/`: `AutomationTestService.java`, `TestView.java`, `TestStartRequest.java`, `TestContactView.java`.
- Modify: `service/automation/AutomationWorker.java` — `tick()` calls `tests.endIdle(now)` first; `process` calls `tests.capture(e)` after the rules and before `finish`, and `finish` records `testIds`.
- Create: `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/automation/AutomationTestController.java`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationTestsApiIT.java`.

**Interfaces:**
- `record TestStartRequest(String contactId, String phone, boolean treatAsNew)`; `TestView` = the contract's `Test`; `TestContactView` = `TestContact`.
- `AutomationTestService`:
  - `TestView start(AuthenticatedUser me, String ws, String ruleId, TestStartRequest r)` — rule PUBLISHED → `published()`; `endIdle` first; an active test → `testRunning`; exactly one of `contactId`/`phone` else `ValidationException("contactId", "Choose a contact or type a number")`; contact not in the brand → 404; `phone` through `Phones.normalise`/`isValid` else `ValidationException("phone", Phones.MESSAGE)`; a phone whose contact has a chat holding a real IN message → `ValidationException("phone", "This number has already messaged you. Choose it as an existing contact and switch on “Treat this chat as new”.")`; DEAL_LANE_CHANGED with `phone` → `ValidationException("phone", "Choose an existing contact to test a lane change")`. Inserts `active true`, WAITING, `waitingSince = lastActivityAt = now`.
  - `void capture(AutomationEvent e)` — every WAITING test of (ws, kind) whose `targetContactId == e.contactId` or `targetPhone == e.contactPhone`, each on its own (an `OptimisticLockingFailureException` or other `RuntimeException` on one test, say it was ended that instant, is logged and the next test is tried; the event is never failed or retried for it): `CAPTURED`, `eventId`, `capturedAt`, `data` (with `chat.isNew = true` when `treatAsNew`), `conditionResults []`, `matched` = true when the rule has no conditions else null, `nextAction 0`, a run (`test true`, `testId`, `TESTING`, steps empty), `runId`, `lastActivityAt now`.
  - `TestView check(String ws, String testId)` — CAPTURED and leaves remain else `testState("Every condition is checked")`/`testState("Wait for the event first")`; evaluates the next leaf of the **current** rule on `AutomationFields.values(data, freshInCrm)`, appends the `ConditionResult`; after the last leaf `matched = combine(...)`.
  - `TestView runAction(String ws, String testId, int index)` / `skipAction(...)` — CAPTURED and `index == nextAction` else `testState("Run or skip action <nextAction + 1> first")`; run through `ActionRunner` with `ActionContext(…, test true)`, or skip with `reason "Skipped in the test"`; `$push` the step on the run; after the last action the run is `DONE`/`FAILED` and `nextAction` null.
  - `TestView again(...)` — WAITING, `eventId`/`data`/results cleared, `waitingSince now`; the previous run is closed (`DONE`/`FAILED`, or `DONE` with the steps so far).
  - `TestView end(String ws, String testId)` — `ENDED`, `DONE`, `active false`; any user with `automation:manage`.
  - `int endIdle(Instant now)` — `active` tests with `lastActivityAt < now - testIdle` → `ENDED`, `IDLE`, `active false`.
  - `Optional<TestView> active(String ws, String ruleId)`; `TestView get(String ws, String testId)`; `List<TestContactView> contacts(String ws, String q)` (≤ 20 brand contacts whose name or phone contains `q`, case-insensitive; `numbers` = the numbers they have chats on).
- `AutomationTestController`: the contract's test routes, all `'automation:manage'`.

- [ ] **Step 1: Write the failing tests** `AutomationTestsApiIT` (draft MESSAGE_RECEIVED rule "Welcome draft": `chat.new is true`, actions [SEND_TEMPLATE "Welcome", ADD_TO_CRM]; `AutomationDevice` on `n1`):
  - `typedNumberWaitsForItsFirstMessage`: start `{phone "+91 98111 00009"}` → 201 `status WAITING`, `target.phone "919811100009"`, `numbers` = the brand's numbers; another contact writes → still WAITING; `incoming(n1, "919811100009@c.us", …)`; `tick()` → GET shows `CAPTURED`, `captured.data.chat.isNew true`, `conditionCount 1`.
  - `stepThroughConditionsThenActions`: `check` → `conditions[0] {field "chat.new", actual true, passed true}`, `matched true`; `check` again → 409 `AUTOMATION_TEST_STATE`; `actions/1/run` → 409 (action 0 first); `actions/0/run` → `actions[0].outcome DONE`, device got `"Hi …, welcome to Acme!"`; `actions/1/skip` → `outcome SKIPPED`, `reason "Skipped in the test"`, `nextAction null`; the run has `test true`, status `DONE`.
  - `treatAsNewOverridesExistingChat`: existing contact with a chat holding messages, `treatAsNew true` → captured `isNew true`, condition passes; `false` → `actual false`, `matched false`.
  - `draftDoesNotRunLiveDuringTest`: captured event → no live run of the draft, nothing sent until `run`.
  - `publishedRulesStillRunForTestContact`: a published rule matching the same event → it ran (one run, `test false`) and the test captured the event too; history doc `testIds [testId]`, `ruleIds` both rules.
  - `oneTestPerRule`: second start → 409 `AUTOMATION_TEST_RUNNING` with `testId`, `startedById`, `startedByName`.
  - `anotherManagerCanEndATest`: `admin` POSTs `/tests/{id}/end` → `ENDED`, `endReason DONE`; a new start then succeeds.
  - `idleTestEndsAfter30Minutes`: `clock.plus(Duration.ofMinutes(31))`, `worker.tick()` → GET `status ENDED`, `endReason IDLE`.
  - `testAgainWaitsForTheNextEvent`: `again` → `WAITING`, `captured null`; next message → captured with a new `eventId`.
  - `publishingEndsTheTest` (`endReason RULE_PUBLISHED`); `publishedRuleCannotBeTested` (409 `AUTOMATION_RULE_PUBLISHED`).
  - `typedNumberThatMessagedIsRefused` (400, `fields.phone` the sentence above); `laneChangeNeedsAContact` (400).
  - `testContactsSearch`: `q "ash"` finds "Asha" with `numbers [{id n1}]`; a hidden-phone `@lid` contact "Ashwin" is found by name with `phone null`.
  - `agentIsForbidden`: AGENT token → 403 on start and GET.
- [ ] **Step 2: Run** `-Dtest=AutomationTestsApiIT` → compilation fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `-Dtest=AutomationTestsApiIT,AutomationWorkerIT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): step-by-step test of a draft rule`

---

### Task 11: Runs and event history API

**Files:**
- Create in `service/automation/`: `AutomationQueryService.java`, `RunView.java`, `EventRecordView.java`, `AutomationPage.java`.
- Modify: `app/automation/AutomationController.java` — `GET /rules/{id}/runs`, `GET /events`.
- Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/automation/AutomationHistoryApiIT.java`.

**Interfaces:**
- `record AutomationPage<T>(List<T> items, long total, int page)` (50 per page).
- `RunView`, `EventRecordView` exactly the contract's `Run` and `EventRecord`; a step's `summary` comes from the rule's current action (`actionsSummary` part) and, when the rule is gone, from its type (`"Send template"`, `"Assign chat"`, `"Add to CRM"`).
- `AutomationQueryService`: `AutomationPage<RunView> runs(String ws, String ruleId, int page)` (sorted `startedAt desc, _id desc`; unknown rule with no runs → 404); `AutomationPage<EventRecordView> events(String ws, AutomationEventKind kind, String ruleId, String contactId, Instant from, Instant to, int page)` (`createdAt desc`; `ruleId` filters on `ruleIds`; runs joined with `findByEventIdIn`; `test` = `testIds` non-empty).

- [ ] **Step 1: Write the failing tests** `AutomationHistoryApiIT` (seed through the worker: two messages from Asha, one from Ravi, a deal close; a failing rule; a test run): `runsNewestFirstWithSteps` (`items[0].steps[0] {type SEND_TEMPLATE, outcome DONE, reason "Sent “Welcome”"}`, `contact.name "Asha"`, `summary "Message from Asha: Hello"`); `runsKeepFailureReasons`; `testRunsAreMarked` (`test true`); `eventsNewestFirstWithRuns`; `filterByKind` (`DEAL_LANE_CHANGED` → one, `summary "<title> moved from Proposal sent to Won"`); `filterByRule`; `filterByContact`; `filterByDate` (`from`/`to` around the second message → one); `paging` (51 records → page 0 has 50, `total 51`); `failedEventShowsError`; `historyIsKept` (records with `createdAt` two years ago are still listed); `viewOnlyRoleCanRead` (`automation:view` only → 200).
- [ ] **Step 2: Run** `-Dtest=AutomationHistoryApiIT` → fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(automation): runs and event history API`

---

### Task 12: Docs and the full build

**Files:**
- Modify: `README.md` — an "Automations" section after "Message templates": the two events, the queue collections (and that nothing expires), the endpoints table (link to this plan's contract), the authorities.
- Modify: `docs/superpowers/crm-pending.md` — replace the "Deal automation events" item with: done (link the spec); known limits: an event lost if the app stops between saving a message or move and writing its event; the same person as `@c.us` and `@lid` can be new twice; a crash mid-rule leaves that rule's remaining actions undone (INTERRUPTED); no retries of failed actions; no retention setting.
- Modify: `docs/superpowers/crm-rulings.md` — one line per plan call above.

- [ ] **Step 1:** Write the three edits.
- [ ] **Step 2: Run** `./mvnw -q verify` (≈ 5 min, 4 forks) → BUILD SUCCESS.
- [ ] **Step 3: Commit** — `docs(automation): README, pending limits and rulings`
