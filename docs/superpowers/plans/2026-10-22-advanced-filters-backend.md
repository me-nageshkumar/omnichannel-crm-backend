# Advanced Contact Filters (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Saved ADVANCED contact filters: the browser sends a rule tree of nested AND/OR/NOT groups, the server checks it against a fixed field and operator table, compiles it to a Mongo query, stores tree and query, previews count and sample, and runs it wherever a `filterId` is accepted (search, count, bulk, export, campaign audiences). Labels and attributes an advanced filter or an unfinished campaign uses cannot be deleted.

**Architecture:** New package `me.nageshk.omnichannel.contact.advanced` holds the rule records, the field table, a pure validator and a pure compiler (unit-tested), the `AdvancedFilters` facade that resolves the workspace's labels, attributes and zone, and `StoredQueryCriteria`, a `Criteria` whose document is the stored query. `ContactQuery.forRequest` ANDs that criteria behind the forced `workspaceId` clause, so every existing consumer gets advanced filters. Query time limits live in one `QueryTimeouts` component; a timeout becomes 504 `QUERY_TIMEOUT`. Two small ports keep `contact` free of `campaign` imports: `WorkspaceZone` (implemented by `CampaignSettingsService`) and `CampaignUses` (implemented in `campaign`), as `phone/LiveDevices` is implemented in `ws`.

**Tech Stack:** Spring Boot 4.1, Spring Data MongoDB 5.1 (`auto-index-creation`), MongoDB Java driver 5.8 (`org.bson.Document`), Lombok, Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation.*`), JUnit 5 + MockMvc (`ContactApiTestBase`) and `TestRestTemplate` (`CampaignTestBase`), flapdoodle embedded Mongo 7.0.12.

**Spec:** `docs/superpowers/specs/2026-10-22-advanced-contact-filters-design.md` §3–§7, §9, §10, §11 (and the campaign changes in §6). The console (§8) is another plan; it is checked against "Contract for the console" at the end.

## Deviations from the spec

1. `AdvancedFilterCompiler.compile(RuleGroup, Resolved)` has no `workspaceId` parameter (§4 has `compile(workspaceId, RuleGroup, Resolved)`): the stored query never contains the workspace clause (§4: consumers add it), so the parameter would be unused. `Resolved` also carries the workspace's `ZoneId` (§3 dates), besides the attribute and label ids.
2. Text `isNot` and `notContains` compile to `{$nor: [{<field>: {$regex: …, $options: "i"}}]}`, not `$not` (§4 "isNot → `$not` of that"): `$not` is not in §4's closed operator list. `$nor` of one clause matches the same documents, including those without the field.
3. Validation paths: the `fields` key of the 400 is the offending path itself (`rules[2].rules[0].value`). Errors of the root group (bad combinator, no rules) and the 51st rule use the key `rules`; a nested group at `P` reports `P.combinator`, `P.rules` (no rules) or `P` (nested deeper than 5 levels); a rule at `P` reports `P.field`, `P.operator` or `P.value`. The first failure in depth-first order is the one reported.
4. Timeout tests: besides the 1 ms test properties (§10), the timeout ITs start their own embedded mongod with `enableTestCommands=1` and switch on its `maxTimeAlwaysTimeOut` failpoint around the call, because 1 ms over a handful of documents usually finishes before the server checks the deadline.
5. `rules` is stored as the sub-document of the request with Spring Data's `_class` hint on each child (the children are an interface-typed list); the API never shows `_class`.
6. The time limits apply to every filter query, SIMPLE filters included (one code path): 5 s on the preview count and sample, `POST /contacts/count`, the bulk `matched` count and the export row count; 10 s on the search count and page; 30 s on each materialisation page. The export stream, bulk writes and bulk-delete batches have none (a timeout after the CSV headers are sent cannot become a 504).
7. `POST /contacts/count` and `POST /contacts/bulk` accept a `selection` with `filter` and `filterId` together, ANDed as search and export do (console ruling); `ids` stays exclusive.

## Decisions where the spec is silent

- `POST /contact-filters` and `PUT`: neither or both of `filter`/`rules` → 400 `VALIDATION` on `filter` "give exactly one of filter or rules"; changing the kind → 400 on `rules` (SIMPLE filter sent `rules`) or `filter` (ADVANCED filter sent `filter`) "the kind of a saved filter cannot change". A stored filter with `kind` null is SIMPLE and is saved back as `kind: SIMPLE`.
- Preview `sample` absent → 10; clamped to 1–25. The sample is sorted like the default search (`lastMessageAt` desc, `_id` asc). Preview with `rules` absent → 400 on `rules` "is required".
- Text values are trimmed before matching; blank → error. A phone value with no digits → error (an empty pattern would match every phone). A no-value operator (`isSet`, `isNotSet`, `isEmpty`, `isNotEmpty`) accepts `value` null, `""` or `[]` (what react-querybuilder sends) and rejects anything else. `between` dates must not be reversed. `boolean is` takes the strings `"true"` / `"false"` only. Combinators are lowercase `"and"` / `"or"`.
- The 50-rule cap counts rules, not groups. The top group is level 1; a group at level 6 is rejected.
- `FilterView` is one shape for the list, the detail and the save replies: list items also carry `uses` and every item carries `lastUpdateTime` (new `@LastModifiedDate`; null on documents saved before this ships) for the console's "Updated" column.
- `query` in replies is the stored JSON string written raw (`@JsonRawValue`), so the console sees exactly what runs: `{"$regex": …, "$options": "i"}`, dates as `{"$date": "…Z"}`.
- 409 `FILTER_IN_USE` lists filters and campaigns sorted by name ignoring case. Only filters and campaigns of the same workspace are checked (ids are per workspace).
- The compiled date boundaries use the zone at save time; a later timezone change applies when the filter is saved again (the stored query is what runs, §4).
- `contact:bulk`'s description becomes `Bulk label and attribute actions, saved filters and the filter builder`. Existing databases keep the old text (not backfilled: `RoleSeeder` does not overwrite stored descriptions, which admins may edit).
- `CampaignView.audience` gains `filterKind` (extra); `filterQueryJson` and `uses` stay server-side.

## Global Constraints

- Spring Boot 4.1; Spring Data Mongo with `auto-index-creation: true`; Jackson 3 (`tools.jackson.*`).
- Documents use Lombok `@Getter @Setter`; requests, views and rule nodes are records. No hand-written getters or setters.
- New code in `me.nageshk.omnichannel.contact.advanced` (rule records, field table, validator, compiler, facade, `StoredQueryCriteria`, ports); touched existing classes in `contact`, `campaign`, `api`, `access/Authority`. `contact` imports nothing from `campaign`.
- Endpoints under `/api/workspaces/{workspaceId}`, each with `@PreAuthorize("@access.has(#workspaceId, '<code>')")`. No new authorities: reads `contact:view`, saves `contact:bulk`, label/attribute deletes stay `contact-field:manage`. `AuthorityCheckScannerIT` stays green.
- The server never parses client-supplied Mongo JSON: `Document.parse` is only ever called on `ContactFilter.queryJson` / `Campaign.Audience.filterQueryJson`, written by `AdvancedFilters`. `FilterRequest` and the preview request have no `query` field.
- The compiled query contains only `$and`, `$or`, `$nor`, `$eq`, `$ne`, `$in`, `$nin`, `$regex`, `$options` (`"i"`), `$exists`, `$gte`, `$lt`, `$size`. Every run is `workspaceId = ws AND <query>` built in code.
- Caps, verbatim: at most 5 levels of nesting, 50 rules in total, 20 values in an array, 200 characters per value; `rules` of a group non-empty; sample clamped to 1–25; timeouts 5 s (count, sample) / 10 s (search) / 30 s (materialisation). `queryVersion` 1.
- Timeout properties (`@Value`, `Duration`): `omnichannel.contacts.query-timeout.count` (default `5s`), `omnichannel.contacts.query-timeout.search` (`10s`), `omnichannel.contacts.query-timeout.materialise` (`30s`).
- Error codes, verbatim:

| code | status | body | message |
|---|---|---|---|
| `VALIDATION` | 400 | `{code, message, fields: {<path>: <text>}}` | texts in Task 1 |
| `FILTER_IN_USE` | 409 | `{code, message, filters: [{id, name}], campaigns: [{id, name}]}` | `This is used by a saved filter or a campaign. Remove it there first.` |
| `QUERY_TIMEOUT` | 504 | `{code, message}` | `This filter takes too long to run. Make it more specific.` |

- Time only through the `Clock` bean (`MutableClock` in campaign tests).
- Wording "Enabled/Disabled", never "block", in code, comments and messages.
- Per task: the named tests, then `./mvnw -q test` (whole suite) must exit 0.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A payload shaped by react-querybuilder (`id` and `valueSource` keys on rules and groups, `value: ""` on `isSet`) must save, not 400 (Task 3 `builderShapedPayloadIsAccepted`).
- A phone rule whose value has no digits (`"+"`, `"abc"`) must be refused, not match every contact (Task 1 `phoneValueWithoutDigitsIsRejected`).
- A filter saved before this ships (no `kind`) must list, run, update and feed a campaign as SIMPLE (Task 3 `legacyFilterWithoutKindReadsAsSimple`, Task 6 `campaignOverALegacyFilterSnapshotsSimple`).
- "name is not Asha" and "city does not contain Pune" must include contacts with no name or no city (Task 5 `negativeTextRulesMatchContactsWithoutTheField`).
- A filter whose label was removed behind the guard's back runs as compiled (matches nothing on that rule, no error) and refuses to be saved again until fixed (Task 5 `filterWithAMissingLabelRunsAsCompiled`).

---

### Task 1: Rule tree, field table, validator

**Files:**
- Create in `src/main/java/me/nageshk/omnichannel/contact/advanced/`: `RuleNode.java`, `Rule.java`, `RuleGroup.java`, `FilterFields.java`, `Resolved.java`, `Uses.java`, `AdvancedFilterValidator.java`
- Test: `src/test/java/me/nageshk/omnichannel/contact/advanced/AdvancedFilterValidatorTest.java`

**Interfaces:**
- Produces:
  - `@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION) @JsonSubTypes({@Type(Rule.class), @Type(RuleGroup.class)}) public sealed interface RuleNode permits Rule, RuleGroup {}`
  - `public record Rule(String field, String operator, Object value) implements RuleNode {}` — `value` is `String`, `List<?>` or null as Jackson/Mongo read it.
  - `public record RuleGroup(String combinator, boolean not, List<RuleNode> rules) implements RuleNode {}`
  - `public final class FilterFields { public enum Type { TEXT, ENUM, BOOLEAN, DATE, LABELS } public static final String ATTRIBUTE_PREFIX = "attributes."; public static Optional<Type> typeOf(String field); public static Set<String> operators(Type t); public static final Set<String> NO_VALUE /* isSet, isNotSet, isEmpty, isNotEmpty */; }` — the table of spec §3 verbatim (`attributes.<non-empty>` is TEXT).
  - `public record Resolved(Set<String> attributeIds, Set<String> labelIds, ZoneId zone) {}`
  - `public record Uses(List<String> attributeIds, List<String> labelIds) {}` — sorted, distinct.
  - `public final class AdvancedFilterValidator { public static final int MAX_DEPTH = 5, MAX_RULES = 50, MAX_VALUES = 20, MAX_VALUE_LENGTH = 200; public static Uses validate(RuleGroup rules, Resolved resolved); }` — throws `ValidationException(path, text)`.

Paths: the top group's children are `rules[i]`; a child of the group at `P` is `P.rules[i]`; a rule's parts are `<path>.field|operator|value`. One depth-first pass, first failure wins. Texts:

| failure | key | text |
|---|---|---|
| combinator not `and`/`or` | `rules` for the root, else `<path>.combinator` | `combinator must be "and" or "or"` |
| `rules` null or empty | `rules` for the root, else `<path>.rules` | `must have at least one rule` |
| group at level 6 | its path | `is nested deeper than 5 levels` |
| the 51st rule | `rules` | `has more than 50 rules` |
| null child | its path | `must be a rule or a group` |
| field not in the table | `.field` | `is not a filter field` |
| `attributes.<id>`, id not in `resolved` | `.field` | `is not an attribute of this brand` |
| operator not of the field's type | `.operator` | `is not an operator of this field` |
| single value missing, not a string, blank after trim, > 200 | `.value` | `must be a text of 1 to 200 characters` |
| list missing, not a list, 0 or > 20 entries, an entry as above | `.value` | `must be a list of 1 to 20 values of 1 to 200 characters` |
| phone value with no digits | `.value` | `must contain digits` |
| enum value | `.value` | `must be one of CHAT, IMPORT, MANUAL` |
| boolean value | `.value` | `must be "true" or "false"` |
| `before`/`after` not `LocalDate.parse`-able | `.value` | `must be a date YYYY-MM-DD` |
| `between` not two such dates, or first after second | `.value` | `must be two dates YYYY-MM-DD, the first not after the second` |
| label id not in `resolved` | `.value` | `is not a label of this brand` |
| no-value operator with a value other than null, `""`, `[]` | `.value` | `takes no value` |

Single-value operators: text all but isSet/isNotSet; enum `is`/`isNot`; boolean `is`; date `before`/`after`; labels `has`/`hasNot`. List operators: enum `in`/`notIn`, labels `hasAny`/`hasAll`/`hasNone`, date `between` (exactly two).

- [ ] **Step 1: Write the failing tests** (`RuleGroup and(RuleNode...)`, `Rule rule(field, op, value)` helpers; `Resolved R = new Resolved(Set.of("a1"), Set.of("l1","l2"), ZoneId.of("Asia/Kolkata"))`; `assertFails(group, key, text)` asserts `ValidationException.fields()` equals `Map.of(key, text)`)

```java
@Test void acceptsEveryOperatorOfEveryFieldType()   // one valid rule per (field type, operator) of spec §3, all in one group of ≤ 50 → no exception
@Test void returnsTheLabelsAndAttributesItUses()    // has l2, hasAny [l1,l2], attributes.a1 is "x" → new Uses(List.of("a1"), List.of("l1","l2"))
@Test void depthCapIsFiveLevels()                   // 5 nested levels pass; 6 → assertFails(..., "rules[0].rules[0].rules[0].rules[0].rules[0]", "is nested deeper than 5 levels")
@Test void ruleCapIsFifty()                         // 50 rules pass (spread over nested groups); 51 → ("rules", "has more than 50 rules")
@Test void valueCaps()                              // "x".repeat(200) passes, 201 → ("rules[0].value", "must be a text of 1 to 200 characters"); 20 label ids pass, 21 → list text
@Test void emptyGroupsAndBadCombinators()           // top rules [] → ("rules", "must have at least one rule"); nested at rules[1] empty → ("rules[1].rules", …); top "xor" → ("rules", "combinator must be \"and\" or \"or\""); nested "AND" at rules[1] → ("rules[1].combinator", …)
@Test void unknownFieldsOperatorsAndIds()           // "email" → .field "is not a filter field"; "attributes.zz" → "is not an attribute of this brand"; source contains → .operator; has "l9" → .value "is not a label of this brand"; hasAny ["l1","l9"] → same
@Test void valueShapes()                            // source is "WEB"; doNotContact is "yes"; creationTime before "21-10-2026"; between ["2026-10-21","2026-10-01"]; between ["2026-10-01"]; name isSet "x" → each its text; isSet "" / null / [] pass
@Test void phoneValueWithoutDigitsIsRejected()      // phone contains "+" and "abc" → ("rules[0].value", "must contain digits"); "+91 98" passes
@Test void rulesRoundTripThroughJackson()           // JsonMapper reads {"combinator":"and","not":false,"id":"g1","rules":[{"id":"r1","field":"name","operator":"is","value":"A","valueSource":"value"},{"combinator":"or","rules":[{"field":"labels","operator":"isEmpty","value":""}]}]}
                                                    // → RuleGroup with a Rule and a RuleGroup child (not false by default); writing it back has no type or id keys
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=AdvancedFilterValidatorTest`
Expected: compilation failure (`RuleGroup`, `AdvancedFilterValidator` missing).

- [ ] **Step 3: Implement** the records, `FilterFields` and `AdvancedFilterValidator.validate` as under Interfaces. The walk checks a group's depth before descending, so a deeply nested body never recurses past level 6.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=AdvancedFilterValidatorTest` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact/advanced src/test/java/me/nageshk/omnichannel/contact/advanced
git commit -m "feat(contact): advanced filter rule tree, field table and validator"
```

---

### Task 2: Compiler

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/contact/advanced/AdvancedFilterCompiler.java`
- Modify: `src/main/java/me/nageshk/omnichannel/contact/ContactQuery.java` (`escape` becomes `public static`)
- Test: `src/test/java/me/nageshk/omnichannel/contact/advanced/AdvancedFilterCompilerTest.java`

**Interfaces:**
- Consumes: Task 1 records, `AdvancedFilterValidator.validate`, `FilterFields`; `ContactQuery.escape(String)` (backslash before each of `\ ^ $ . | ? * + ( ) [ ] { } - /`, no `\Q…\E`).
- Produces: `public final class AdvancedFilterCompiler { public static org.bson.Document compile(RuleGroup rules, Resolved resolved); }` — validates first (same `ValidationException`), then builds per spec §4 with Deviation 2:
  - group → `{$and: [children]}` / `{$or: [children]}` (also for one child); `not` → `{$nor: [<that>]}`.
  - regex clauses are `new Document("$regex", pattern).append("$options", "i")` (insertion order kept). Phone values are `value.replaceAll("\\D", "")`; other text values trimmed.
  - text `isNot` → `{$nor: [{f: {$regex: "^v$", $options: "i"}}]}`, `notContains` → `{$nor: [{f: {$regex: "v", …}}]}`; enum `is` → `{source: {$eq: v}}`, `isNot` → `$ne`, `in` → `$in`, `notIn` → `$nin`; dates are `java.util.Date` of `LocalDate.atStartOfDay(resolved.zone())`.
  - Field paths: `name`, `phone`, `source`, `doNotContact`, `creationTime`, `lastMessageAt`, `labels`, `attributes.<id>`; `numberHidden` compiles on `phone`.

- [ ] **Step 1: Write the failing tests.** `assertCompiles(RuleGroup g, String json)` compares `JsonMapper.readTree(compile(g, R).toJson())` with `readTree(json)`; date cases compare `Document`s with `Date.from(Instant.parse(…))`.

```java
@Test void textOperators() {
    assertCompiles(and(rule("name", "is", "Asha")), """{"$and":[{"name":{"$regex":"^Asha$","$options":"i"}}]}""");
    // isNot → {"$nor":[{"name":{"$regex":"^Asha$","$options":"i"}}]}; contains "sha" → "sha"; notContains → $nor of that;
    // startsWith → "^Ash"; endsWith → "sha$"; isSet → {"name":{"$exists":true,"$nin":[null,""]}};
    // isNotSet → {"$or":[{"name":{"$exists":false}},{"name":null},{"name":""}]}; " Asha " is trimmed
}
@Test void attributeAndPhoneRules()          // attributes.a1 is "Pune" → {"attributes.a1":{"$regex":"^Pune$","$options":"i"}}; phone contains "+91 98-12" → {"phone":{"$regex":"919812","$options":"i"}}
@Test void regexMetacharactersAreEscaped()   // contains ".*+?()[]{}|^$\\" → regex "\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\^\\$\\\\" (Java literal)
@Test void enumAndBooleanOperators()         // source is CHAT → {"source":{"$eq":"CHAT"}}; isNot → $ne; in [CHAT,IMPORT] → $in; notIn → $nin;
                                             // doNotContact is "true" → {"doNotContact":{"$ne":null}}, "false" → {"doNotContact":null};
                                             // numberHidden is "true" → {"phone":null}, "false" → {"phone":{"$ne":null}}
@Test void dateBoundariesInTheZone()         // Asia/Kolkata: before 2026-10-21 → {creationTime: {$lt: 2026-10-20T18:30:00Z}}; after 2026-10-21 → {$gte: 2026-10-21T18:30:00Z};
                                             // between [2026-10-01, 2026-10-21] → {$gte: 2026-09-30T18:30:00Z, $lt: 2026-10-21T18:30:00Z};
                                             // with zone UTC: before 2026-10-21 → {$lt: 2026-10-21T00:00:00Z}; lastMessageAt isSet → {$ne: null}, isNotSet → {lastMessageAt: null}
@Test void labelOperators()                  // has l1 → {"labels":"l1"}; hasNot → {"labels":{"$ne":"l1"}}; hasAny → {"labels":{"$in":["l1","l2"]}};
                                             // hasAll → {"$and":[{"labels":"l1"},{"labels":"l2"}]}; hasNone → {"labels":{"$nin":[…]}};
                                             // isEmpty → {"$or":[{"labels":{"$exists":false}},{"labels":{"$size":0}}]}; isNotEmpty → {"labels.0":{"$exists":true}}
@Test void groupsAndNot()                    // or-group of two rules → {"$or":[…]}; and-group with not=true → {"$nor":[{"$and":[…]}]}; nested group inside or
@Test void onlyAllowedOperatorsAppear()      // one tree using every operator of every type: every "$" key found recursively is in
                                             // Set.of("$and","$or","$nor","$eq","$ne","$in","$nin","$regex","$options","$exists","$gte","$lt","$size")
@Test void invalidTreeIsRejected()           // compile(and(rule("email","is","x")), R) → ValidationException with key "rules[0].field"
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=AdvancedFilterCompilerTest`
Expected: compilation failure (`AdvancedFilterCompiler` missing).

- [ ] **Step 3: Implement `AdvancedFilterCompiler.compile`** as under Interfaces; make `ContactQuery.escape` public.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=AdvancedFilterCompilerTest,ContactSearchIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact/advanced
git commit -m "feat(contact): compile advanced filter rules to a closed set of Mongo operators"
```

---

### Task 3: Saving advanced filters, `GET /contact-filters/{id}`

**Files:**
- Create: `contact/FilterKind.java`; in `contact/advanced/`: `WorkspaceZone.java`, `AdvancedFilters.java`
- Modify: `contact/ContactFilter.java`, `ContactFilterService.java`, `ContactFilterController.java`, `FilterView.java`; `campaign/CampaignSettingsService.java` (implements `WorkspaceZone`); `access/Authority.java` (`CONTACT_BULK` description)
- Test: `src/test/java/me/nageshk/omnichannel/contact/AdvancedFilterApiIT.java` (extends `ContactApiTestBase`); `ContactSearchIT` stays green

**Interfaces:**
- Consumes: Task 1 `RuleGroup`, `Uses`, `Resolved`, `validate`; Task 2 `compile`.
- Produces:
  - `public enum FilterKind { SIMPLE, ADVANCED; public static FilterKind orSimple(FilterKind k) }`
  - `ContactFilter` gains `FilterKind kind`, `RuleGroup rules`, `String queryJson`, `Integer queryVersion`, `Uses uses`, `@LastModifiedDate Instant lastUpdateTime`.
  - `public interface WorkspaceZone { ZoneId zoneOf(String workspaceId); }`; `CampaignSettingsService implements WorkspaceZone` with `public ZoneId zoneOf(String workspaceId)` = `ZoneId.of(get(workspaceId).getTimezone())`.
  - `@Service public class AdvancedFilters { public static final int QUERY_VERSION = 1; public record Compiled(String queryJson, Uses uses) {} public Resolved resolve(String workspaceId); public Compiled compile(String workspaceId, RuleGroup rules); public static Document parse(String queryJson); }` — `resolve` reads the workspace's label ids, attribute ids (`findByWorkspaceIdOrderByNameKey`) and `zoneOf`; `compile` = `validate` + `AdvancedFilterCompiler.compile(...).toJson()`.
  - `public record FilterView(String id, String name, FilterKind kind, ContactFilterSpec filter, RuleGroup rules, @JsonRawValue String query, Uses uses, String createdBy, Instant creationTime, Instant lastUpdateTime)`; `of(ContactFilter)` maps `kind` through `orSimple` and `query` from `queryJson`.
  - `ContactFilterController.FilterRequest(String name, ContactFilterSpec filter, RuleGroup rules)`.
  - `ContactFilterService`: `FilterView get(String ws, String id)`, `FilterView create(String ws, String username, String name, ContactFilterSpec filter, RuleGroup rules)`, `FilterView update(String ws, String id, String name, ContactFilterSpec filter, RuleGroup rules)`; `list`/`delete` unchanged.
  - `GET /contact-filters/{id}` (`contact:view`) → `FilterView`; 404 `filter not found` outside the workspace.

Save: name errors and the exactly-one check go into one `VALIDATION` map as today (texts in "Decisions"); then the kind-change check (update only); SIMPLE keeps `query.build(ws, filter)` and clears `rules`, `queryJson`, `queryVersion`, `uses`; ADVANCED runs `AdvancedFilters.compile` on every create and update and stores `rules`, `queryJson`, `queryVersion = 1`, `uses`, `filter = null`.

- [ ] **Step 1: Write the failing `AdvancedFilterApiIT`** (`vip = label(brandA, "VIP")`, `city = attribute(brandA, "City")`; `filtersUrl()` = `base(brandA) + "/contact-filters"`; rules as `Map`s)

```java
@Test void createStoresRulesQueryAndUses() {
    // POST {name: "Pune VIPs", rules: {combinator: "and", not: false, rules: [{field: "labels", operator: "has", value: vip},
    //       {field: "attributes." + city, operator: "is", value: "Pune"}]}} as leadToken → 201
    // $.kind "ADVANCED"; $.filter null; $.rules.rules[0].value == vip; $.query['$and'][0].labels == vip;
    // $.query['$and'][1]['attributes.<city>']['$regex'] == "^Pune$"; $.uses.labelIds == [vip]; $.uses.attributeIds == [city]
    // stored document: queryVersion 1, queryJson not null, kind ADVANCED
}
@Test void getReturnsTheFullFilter()               // GET /{id} as agentToken → rules, query, uses; a SIMPLE one → kind "SIMPLE", rules/query/uses null; brandB's id → 404
@Test void listShowsBothKinds()                    // one SIMPLE, one ADVANCED → $[*].kind; the ADVANCED item has rules and query
@Test void exactlyOneOfFilterOrRules()             // both → 400 $.fields.filter == "give exactly one of filter or rules"; neither → same
@Test void kindCannotChange()                      // PUT {name, rules} on a SIMPLE → 400 $.fields.rules == "the kind of a saved filter cannot change"; PUT {name, filter} on an ADVANCED → $.fields.filter
@Test void updateRecompiles()                      // PUT new rules (name startsWith "As") → $.query['$and'][0].name['$regex'] == "^As", uses.labelIds empty;
                                                   // a body with "query": {"$where": "1"} is ignored: the stored queryJson does not contain "$where"
@Test void validationErrorsCarryThePath()          // rules [{name is "A"}, {or: [{labels has "nope"}]}] → 400 $.code "VALIDATION", $.fields['rules[1].rules[0].value'] == "is not a label of this brand"
@Test void builderShapedPayloadIsAccepted()        // groups and rules with "id", rules with "valueSource": "value", a {field: "lastMessageAt", operator: "isSet", value: ""} → 201
@Test void legacyFilterWithoutKindReadsAsSimple()  // filters.save(ContactFilter without kind, filter = empty spec) → list item kind "SIMPLE"; PUT {name, filter} → 200, stored kind SIMPLE
@Test void datesUseTheWorkspaceTimezone()          // creationTime after "2026-10-20" → $.query['$and'][0].creationTime['$gte']['$date'] == "2026-10-20T18:30:00Z";
                                                   // mongo.updateFirst(campaignSettings of brandA, timezone "UTC"), save again → "2026-10-21T00:00:00Z"
@Test void savingNeedsContactBulk()                // agentToken POST {name, rules} → 403; PUT → 403
@Test void deleteAnAdvancedFilter()                // DELETE /{id} as wmToken → 204; GET /{id} → 404; POST /contacts/search {filterId} → 404
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=AdvancedFilterApiIT`
Expected: FAIL (`rules` ignored: 400 on `filter`; `GET /{id}` 405/404).

- [ ] **Step 3: Implement** as under Interfaces; change `Authority.CONTACT_BULK`'s description to `Bulk label and attribute actions, saved filters and the filter builder`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=AdvancedFilterApiIT,ContactSearchIT,AuthorityCheckScannerIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel src/test/java/me/nageshk/omnichannel/contact
git commit -m "feat(contact): save advanced filters compiled on the server, GET one filter"
```

---

### Task 4: Preview and query timeouts

**Files:**
- Create: `api/QueryTimeoutException.java`; `contact/QueryTimeouts.java`; `contact/advanced/StoredQueryCriteria.java`
- Modify: `api/ApiExceptionHandler.java`, `contact/ContactQuery.java`, `ContactFilterService.java`, `ContactFilterController.java`
- Create (test): `src/test/java/me/nageshk/omnichannel/support/MongoTestCommands.java`, `contact/QueryTimeoutsTest.java`, `contact/AdvancedFilterTimeoutIT.java`
- Test: `AdvancedFilterApiIT` (preview cases)

**Interfaces:**
- Consumes: Task 3 `AdvancedFilters.compile`/`parse`.
- Produces:
  - `public class QueryTimeoutException extends RuntimeException { public static final String CODE = "QUERY_TIMEOUT"; public static final String MESSAGE = "This filter takes too long to run. Make it more specific."; }`; `ApiExceptionHandler` maps it and `com.mongodb.MongoExecutionTimeoutException` to 504 `{code, message}`.
  - `@Component public class QueryTimeouts { public Duration count(); public Duration search(); public Duration materialise(); public static <T> T guarded(Supplier<T> call); }` — properties in Global Constraints; `guarded` rethrows any `RuntimeException` with a `MongoExecutionTimeoutException` in its cause chain (Spring wraps it in a `DataAccessException`) as `QueryTimeoutException`, others unchanged.
  - `public final class StoredQueryCriteria extends Criteria { public StoredQueryCriteria(String queryJson); @Override public Document getCriteriaObject(); }` — returns `AdvancedFilters.parse(queryJson)` (parsed once). Used only inside `andOperator` (`Criteria.andOperator` reads each part through `getCriteriaObject()`).
  - `ContactQuery.advanced(String workspaceId, String queryJson) -> Criteria` = `workspaceId = ws AND StoredQueryCriteria`.
  - `ContactFilterService.preview(String ws, RuleGroup rules, Integer sample) -> Preview`; `public record Preview(long count, List<ContactView> sample, @JsonRawValue String query)`; `SAMPLE_DEFAULT = 10`, `SAMPLE_MAX = 25`. Count and sample run with `maxTime(timeouts.count())`, each through `guarded`.
  - `POST /contact-filters/preview` (`contact:view`) body `PreviewRequest(RuleGroup rules, Integer sample)` → `Preview`.
  - Test support: `@TestConfiguration public class MongoTestCommands { @Bean MongodArguments mongodArguments() /* MongodArguments.defaults().withParams(Map.of("enableTestCommands", "1")) — replaces flapdoodle's @ConditionalOnMissingBean default */; public static void alwaysTimeOut(MongoClient client, boolean on) /* admin: {configureFailPoint: "maxTimeAlwaysTimeOut", mode: on ? "alwaysOn" : "off"} */ }`

- [ ] **Step 1: Write the failing tests**

`QueryTimeoutsTest`:
```java
@Test void timeoutInTheCauseChainBecomesQueryTimeout()   // guarded(() -> { throw new UncategorizedMongoDbException("x", new MongoExecutionTimeoutException(50, "time limit")); }) → QueryTimeoutException
@Test void otherFailuresPassUnchanged()                  // an IllegalStateException comes out as itself
```
`AdvancedFilterApiIT`:
```java
@Test void previewCountsSamplesAndShowsTheQuery()  // 3 brandA contacts, 2 with vip, a brandB contact with brandB's own label; POST /contact-filters/preview {rules: labels has vip, sample: 1} as agentToken
                                                   // → 200 $.count 2, $.sample.length() 1, $.sample[0].name and .phone present, $.query['$and'][0].labels == vip; filters.count() == 0
@Test void sampleIsClampedTo1To25()                // 30 matching contacts: sample 100 → 25 rows; 0 → 1; absent → 10; count 30 each time
@Test void previewValidation()                     // {} → 400 $.fields.rules == "is required"; a bad label → $.fields['rules[0].value']
@Test void previewNeedsAccessToTheWorkspace()      // wmBToken on brandA → 404
```
`AdvancedFilterTimeoutIT` — `@TestPropertySource(properties = {"omnichannel.contacts.query-timeout.count=1ms", "omnichannel.contacts.query-timeout.search=1ms", "omnichannel.contacts.query-timeout.materialise=1ms"})`, `@Import(MongoTestCommands.class)`, extends `ContactApiTestBase`; `@AfterEach` switches the failpoint off:
```java
@Test void previewTimeoutIs504() {
    // contact + rules name contains "a"; MongoTestCommands.alwaysTimeOut(client, true); POST preview as agentToken
    // → 504 $.code "QUERY_TIMEOUT", $.message "This filter takes too long to run. Make it more specific."
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=QueryTimeoutsTest,AdvancedFilterApiIT,AdvancedFilterTimeoutIT`
Expected: compilation failure (`QueryTimeouts`, `MongoTestCommands` missing).

- [ ] **Step 3: Implement** as under Interfaces. Sample sort `lastMessageAt` desc, `_id` asc; `limit` = clamp(sample ?? 10, 1, 25).

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=QueryTimeoutsTest,AdvancedFilterApiIT,AdvancedFilterTimeoutIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel src/test/java/me/nageshk/omnichannel
git commit -m "feat(contact): preview an advanced filter, time limits on filter queries with 504 QUERY_TIMEOUT"
```

---

### Task 5: Running advanced filters through `filterId`

**Files:**
- Modify: `contact/ContactQuery.java` (`forRequest`), `ContactSearchService.java`, `ContactBulkService.java` (`criteria`, `count`, `run`), `ContactExportController.java`
- Modify (test): `contact/ContactBulkIT.java` `exactlyOneSelection` — `{filter: {}, filterId: "x"}` now answers 404 (unknown filter), not 400
- Test: `src/test/java/me/nageshk/omnichannel/contact/AdvancedFilterExecutionIT.java` (extends `ContactApiTestBase`), `AdvancedFilterTimeoutIT`

**Interfaces:**
- Consumes: Task 3 `ContactFilter.kind/queryJson`, `FilterKind.orSimple`; Task 4 `StoredQueryCriteria`, `QueryTimeouts`.
- Produces: `forRequest(ws, inline, filterId)` — saved filter ADVANCED → part `new StoredQueryCriteria(saved.getQueryJson())`, else `parts(ws, saved.getFilter())` as today; the inline spec's parts follow; always `scoped(ws, parts)`. Time limits: `ContactSearchService.search` count and page `maxTime(timeouts.search())`; `ContactBulkService.count` and `run`'s `matched` count `maxTime(timeouts.count())`; export row count `maxTime(timeouts.count())`; each call through `QueryTimeouts.guarded`.
- `ContactBulkService.criteria` (Deviation 7): `ids` with `filter` or `filterId` → 400 on `selection` `give ids, or filter and/or filterId`; none → the same; `ids` as today; otherwise `query.forRequest(ws, s.filter(), s.filterId())`. `Selection`'s comment becomes "`ids` alone, or `filter` and/or `filterId`".

- [ ] **Step 1: Write the failing tests.** Data in `@BeforeEach`: `vip` label, `city` attribute; Asha (vip, city "Pune", phone 919812345678), Bela (vip, city "Mumbai"), Chitra (no labels, city "Pune"), Dev (no name, no city); brandB: Asha (city "Pune" under brandB's own attribute). `advanced(Map rules)` saves via POST and returns the id.

```java
@Test void searchThroughAnAdvancedFilter()                 // or[ and[labels has vip, attributes.city is "pune"], name startsWith "chi" ] → names Asha, Chitra; total 2
@Test void advancedFilterIsAndedWithAnInlineSpec()         // same filterId + filter {q: "chitra"} → Chitra
@Test void negativeTextRulesMatchContactsWithoutTheField()  // name isNot "asha" → Bela, Chitra, Dev; attributes.city notContains "pun" → Bela, Dev
@Test void notGroupExcludesItsMatches()                    // and(not=true)[labels has vip] → Chitra, Dev
@Test void regexCharactersAreLiteral()                     // contacts "a.*+?()[]{}|^$\\b" and "axb": contains "a.*+?()[]{}|^$\\b" → only the first; contains "a.b" → neither
@Test void phoneRulesMatchDigits()                         // phone contains "+91 98123" → Asha; phone is "919812345678" → Asha
@Test void datesUseDayBoundariesInTheZone()                // Asha creationTime 2026-10-20T19:00:00Z (21 Oct 00:30 IST): after 2026-10-20 → includes Asha; before 2026-10-21 → excludes; between [2026-10-21, 2026-10-21] → includes
@Test void countThroughAnAdvancedFilter()                  // POST /contacts/count {selection: {filterId}} → $.count 2
@Test void bulkThroughAnAdvancedFilter()                   // POST /contacts/bulk {selection: {filterId}, action: ADD_LABELS, labelIds: [lead]} as leadToken → matched 2, changed 2; brandB's Asha unchanged
@Test void exportThroughAnAdvancedFilter()                 // download({filterId}) → header + Asha and Chitra rows only
@Test void countAndBulkAndTheFilterWithAnInlineSpec()        // POST /contacts/count {selection: {filterId, filter: {q: "chitra"}}} → 1;
                                                           // POST /contacts/bulk {selection: {filterId, filter: {q: "chitra"}}, action: ADD_LABELS, labelIds: [lead]} → matched 1, changed 1; only Chitra has lead
@Test void idsStayExclusive()                              // {ids: [asha], filterId} → 400 $.fields.selection == "give ids, or filter and/or filterId"
@Test void filterWithAMissingLabelRunsAsCompiled()         // filter labels has vip; labels.deleteById(vip) (bypassing the guard) → search 200 with no items;
                                                           // GET /{id} 200; PUT the same rules → 400 $.fields['rules[0].value'] == "is not a label of this brand"
```
`AdvancedFilterTimeoutIT`:
```java
@Test void searchTimeoutIs504()       // failpoint on; POST /contacts/search {filterId} → 504 QUERY_TIMEOUT
@Test void countTimeoutIs504()        // POST /contacts/count {selection: {filterId}} → 504
@Test void exportTimeoutIs504()       // POST /contacts/export {filterId} as wmToken → 504 (raised before streaming starts)
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=AdvancedFilterExecutionIT,AdvancedFilterTimeoutIT`
Expected: FAIL (an ADVANCED `filterId` matches every contact of the brand: its `filter` is null; `{filterId, filter}` selections are 400).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=AdvancedFilterExecutionIT,AdvancedFilterTimeoutIT,ContactSearchIT,ContactBulkIT,ContactImportExportIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/contact src/test/java/me/nageshk/omnichannel/contact
git commit -m "feat(contact): search, count, bulk and export through advanced filters"
```

---

### Task 6: Campaign audiences over advanced filters

**Files:**
- Modify in `campaign/`: `Campaign.java` (`Audience`), `CampaignValidator.java`, `CampaignService.java` (`withFile`), `CampaignMaterialiser.java`, `CampaignView.java` (`AudienceView`), `CampaignViews.java`
- Modify (test): `CampaignTestBase.java` (`collections()` adds `ContactLabel.class`; helpers `ContactLabel label(String name)`, `String advancedFilter(String name, Map<String, Object> rules)`)
- Test: `campaign/CampaignAdvancedAudienceIT.java`, `campaign/CampaignAdvancedTimeoutIT.java`

**Interfaces:**
- Consumes: Task 3 `ContactFilter.kind/queryJson/uses`, `FilterKind`, `Uses`; Task 4 `ContactQuery.advanced`, `QueryTimeouts`.
- Produces:
  - `Campaign.Audience` gains, after `csvFileId`: `FilterKind filterKind, String filterQueryJson, Uses uses`. A FILTER audience snapshots `FilterKind.orSimple(f.getKind())`, `f.getQueryJson()`, `f.getUses()` (null for SIMPLE); CSV audiences null. `withFile` copies them.
  - `CampaignView.AudienceView` gains last component `FilterKind filterKind`.
  - `CampaignMaterialiser.filter`: base = `filterKind == ADVANCED ? contactQuery.advanced(ws, filterQueryJson) : contactQuery.build(ws, filterSpec)`; each page `.maxTime(timeouts.materialise())` through `QueryTimeouts.guarded`. A `QueryTimeoutException` takes the existing failure path (recipients removed, rethrown); the campaign stays DRAFT and `POST /start` answers 504.

- [ ] **Step 1: Write the failing tests**

`CampaignAdvancedAudienceIT`:
```java
@Test void createSnapshotsTheAdvancedFilter()  // L = label("VIP"); f = advancedFilter("VIPs", labels has L); POST /campaigns over f
                                               // → stored audience.filterKind ADVANCED, filterQueryJson equals the filter's queryJson, uses.labelIds [L];
                                               // GET /campaigns/{id} → $.audience.filterKind "ADVANCED"; a campaign over filter("All", Map.of()) → filterKind SIMPLE, filterQueryJson null, uses null
@Test void startMaterialisesTheAdvancedAudience()  // A (L, phone), B (phone, no label), C (L, no phone) → start 200; recipients: only A, PENDING
@Test void startRunsTheSnapshotNotTheEditedFilter() // create over f; PUT f to name is "B"; start → recipients A only
@Test void campaignOverALegacyFilterSnapshotsSimple() // ContactFilter saved by mongo without kind (empty spec) → campaign audience.filterKind SIMPLE; start materialises every phone contact
```
`CampaignAdvancedTimeoutIT` — `@TestPropertySource(properties = "omnichannel.contacts.query-timeout.materialise=1ms")`, `@Import(MongoTestCommands.class)`, extends `CampaignTestBase`; failpoint off in `@AfterEach`:
```java
@Test void startTimeoutIs504AndLeavesTheDraft()  // A (L, phone); campaign over advancedFilter; failpoint on; POST /start → 504 $.code "QUERY_TIMEOUT", exact message;
                                                 // failpoint off; campaign(id).getStatus() DRAFT; no recipients of id; a second start (failpoint off) → 200 RUNNING
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=CampaignAdvancedAudienceIT,CampaignAdvancedTimeoutIT`
Expected: compilation failure (`Audience.filterKind` missing).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest='Campaign*'` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/campaign src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(campaign): audiences over advanced filters, snapshotted, with a materialisation time limit"
```

---

### Task 7: Delete guard — 409 `FILTER_IN_USE`

**Files:**
- Create: `api/FilterInUseException.java`; `contact/advanced/CampaignUses.java`; `campaign/CampaignFieldUses.java`
- Modify: `api/ApiExceptionHandler.java`, `contact/ContactFieldService.java`, `contact/ContactFilterRepository.java`
- Test: `src/test/java/me/nageshk/omnichannel/campaign/FilterInUseIT.java` (extends `CampaignTestBase`)

**Interfaces:**
- Consumes: Task 3 `ContactFilter.kind/uses`; Task 6 `Campaign.Audience.uses`.
- Produces:
  - `public class FilterInUseException extends RuntimeException { public static final String CODE = "FILTER_IN_USE"; public static final String MESSAGE = "This is used by a saved filter or a campaign. Remove it there first."; public record Ref(String id, String name) {} public FilterInUseException(List<Ref> filters, List<Ref> campaigns); public List<Ref> filters(); public List<Ref> campaigns(); }`; handler → 409 `{code, message, filters, campaigns}` (a `Map<String, Object>` body like `AuthorityInUseException`).
  - `public interface CampaignUses { List<FilterInUseException.Ref> using(String workspaceId, String usesField, String id); }` — `usesField` is `"labelIds"` or `"attributeIds"`.
  - `@Component class CampaignFieldUses implements CampaignUses` — campaigns of the workspace with `status in [DRAFT, RUNNING, PAUSED]` and `audience.uses.<usesField> = id`, sorted by `nameKey`.
  - `ContactFilterRepository`: `List<ContactFilter> findByWorkspaceIdAndKindAndUsesLabelIdsOrderByNameKey(String ws, FilterKind kind, String labelId)` and `…UsesAttributeIds…`.
  - `ContactFieldService.deleteLabel` / `deleteAttribute`: after `require…`, ADVANCED filters and unfinished campaigns using the id → `FilterInUseException`; otherwise delete and cascade as today. SIMPLE filters are not checked.

- [ ] **Step 1: Write the failing `FilterInUseIT`** (`api(DELETE, "/contact-labels/" + id, manager, null)`; `assertInUse(res, filters, campaigns)` checks 409, `code`, exact `message`, and the `[{id, name}]` arrays)

```java
@Test void labelUsedByAnAdvancedFilterCannotBeDeleted()   // f "VIPs" uses L → 409 filters [{f, "VIPs"}], campaigns []; label still listed
@Test void labelUsedByAnUnfinishedCampaignCannotBeDeleted() // f uses L; campaign "Diwali" over f (DRAFT); PUT f to name is "x" → 409 filters [], campaigns [{id, "Diwali"}];
                                                           // status RUNNING → 409; PAUSED → 409; COMPLETED → 204 (set via mongo)
@Test void terminatedCampaignDoesNotGuard()                // same with TERMINATED → 204
@Test void attributeUsedByAnAdvancedFilterCannotBeDeleted() // a from POST /contact-attributes as manager; f uses attributes.<a> → DELETE /contact-attributes/{a} → 409 filters [{f, name}]
@Test void simpleFilterDoesNotGuard()                      // filter("Simple", Map.of("labelsAny", List.of(L))) → DELETE label → 204; the filter still lists
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=FilterInUseIT`
Expected: FAIL (deletes answer 204).

- [ ] **Step 3: Implement** as under Interfaces.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=FilterInUseIT,ContactFieldsApiIT` → PASS. Then `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel src/test/java/me/nageshk/omnichannel/campaign
git commit -m "feat(contact): refuse to delete labels and attributes used by advanced filters or unfinished campaigns"
```

---

## Contract for the console

Paths under `/api/workspaces/{ws}`. Instants are ISO-8601 strings. Fields marked *(extra)* go beyond the spec; the console may ignore them.

```
Rule      = { field: string, operator: string, value: string | string[] | null }
RuleGroup = { combinator: "and" | "or", not: boolean, rules: (Rule | RuleGroup)[] }
            fields: "name" | "phone" | "source" | "numberHidden" | "doNotContact" | "creationTime" | "lastMessageAt" | "labels" | "attributes.<attributeId>"
            operators and values per spec §3; dates "YYYY-MM-DD"; booleans "true" | "false"; no-value operators take null, "" or []
            extra keys (react-querybuilder "id", "valueSource") are accepted and not stored: rules come back without ids
Uses      = { attributeIds: string[], labelIds: string[] }
FilterView = { id: string, name: string, kind: "SIMPLE" | "ADVANCED" (never null in replies; stored null is sent as SIMPLE),
               filter: ContactFilterSpec | null,        (SIMPLE)
               rules: RuleGroup | null,                 (ADVANCED)
               query: object | null,                    (ADVANCED; the stored compiled query, e.g.
                                                         {"$and": [{"labels": "<id>"}, {"name": {"$regex": "^Asha$", "$options": "i"}},
                                                                   {"creationTime": {"$gte": {"$date": "2026-10-20T18:30:00Z"}}}]})
               uses: Uses | null,                       (ADVANCED; also in list items (extra))
               createdBy: string | null, creationTime: string, lastUpdateTime: string | null (extra) }
Preview   = { count: number, sample: ContactView[], query: object }

GET    /contact-filters                     → FilterView[]  sorted by name ignoring case           (contact:view)
GET    /contact-filters/{id}                → FilterView    404 NOT_FOUND outside the workspace      (contact:view)
POST   /contact-filters  {name, filter} | {name, rules}   → 201 FilterView                         (contact:bulk)
PUT    /contact-filters/{id}  same bodies; the kind cannot change → FilterView                     (contact:bulk)
DELETE /contact-filters/{id}                → 204                                                  (contact:bulk)
POST   /contact-filters/preview  {rules: RuleGroup, sample?: number}  → Preview                     (contact:view)
       sample absent → 10, clamped to 1–25; nothing is saved
POST   /contacts/search  {filter?, filterId?, sort?, page?}, POST /contacts/export {filter?, filterId?}
       accept an ADVANCED filterId unchanged; an inline `filter` is ANDed with it
POST   /contacts/count {selection}, POST /contacts/bulk {selection, action, …}
       selection = {ids} | {filter?, filterId?} (at least one; both ANDed as search does); ids with either → 400 on selection
DELETE /contact-labels/{id}, DELETE /contact-attributes/{id}
       → 204, or 409 { code: "FILTER_IN_USE", message: "This is used by a saved filter or a campaign. Remove it there first.",
                       filters: [{id, name}], campaigns: [{id, name}] }      (contact-field:manage)
CampaignView.audience.filterKind: "SIMPLE" | "ADVANCED" | null (extra; null for CSV)
```

**Errors.**
- `400 { code: "VALIDATION", message, fields: { <key>: <text> } }`. Keys: `name`; `filter` (`give exactly one of filter or rules`, or the kind change); `rules` (`is required`, the kind change, the root group's combinator or emptiness, the 51st rule); a nested group at `P`: `P.combinator`, `P.rules` (empty), `P` (deeper than 5 levels); a rule at `P`: `P.field`, `P.operator`, `P.value`, or `P` (null child). `.not` is never reported (a missing `not` reads false). The texts are in Task 1's table. One error per reply, the first in depth-first order.
- `409 DUPLICATE` on `name` as today.
- `504 { code: "QUERY_TIMEOUT", message: "This filter takes too long to run. Make it more specific." }` from preview, search, count, bulk, export and `POST /campaigns/{id}/start` (the campaign stays DRAFT).
- `messages.ts`: `filter_in_use: 'This is used by a saved filter or a campaign. Remove it there first.'`, `query_timeout: 'This filter takes too long to run. Make it more specific.'`.

## Console must change

None. Every assumption 1–10 of the console plan's "Contract assumptions" is adopted: the validation keys follow its trailing-segment form (Deviation 3), `{filterId, filter}` selections are accepted by count and bulk (Deviation 7), `value: null` is accepted on no-value operators, the depth and rule caps count as it assumes. The backend additionally sends `kind` never null, a 10-row sample when `sample` is absent, `uses` in list items, and `QUERY_TIMEOUT` from bulk too; the console may ignore all of these.
