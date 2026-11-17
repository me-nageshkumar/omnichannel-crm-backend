# Subscriptions phase 1 (entitlements), backend: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Platform staff define feature groups and plans and assign a plan to a customer; from then on that customer's users, limits and background work are held inside what the plan allows, with grace and read-only after the term ends.

**Architecture:** A subscription is a per-customer document whose state is derived from its dates. `security.entitlement.Entitlements` resolves it (with the feature groups and settings) into an entitled authority set and limits, cached per customer; `AccessService` intersects every customer user's authorities with it, `AssignmentPolicy` compares inside it, creation points ask `Limits`, and senders and workers ask `Entitlements` before they act. No money moves in this phase: prices are stored and summed, nothing is charged.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB (standalone, no transactions), JUnit 5 + AssertJ, embedded Mongo in tests, `MutableClock`.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` (sections 6, 8, 11, 13, 14 phase 1). Read `docs/superpowers/project-rules.md` sections 1 and 2 first.

**Console plan:** `docs/superpowers/plans/2026-11-17-subscriptions-1-entitlements-console.md` consumes the contract in the section "API contract" below.

## Global Constraints

- Work only in `.worktrees/subscriptions` (branch `subscriptions`). Never on `main`. No merge, push or deploy: the user asks for those.
- Test first: every step that adds behaviour starts with a failing test that is run and seen to fail.
- One Maven build at a time. Module tests: `./mvnw -q -pl <module> -am test -Dtest=<Name> -Dsurefire.failIfNoSpecifiedTests=false`. ITs live in `omnichannel-app/src/test` and run through surefire the same way. Full `./mvnw -q verify` once, in Task 13.
- MongoDB is standalone: no `@Transactional`. A change to one subscription is one update on one document.
- Collections camelCase: `featureGroups`, `plans`, `subscriptions`, `billingChanges`, `billingSettings`. History collections are kept, no TTL.
- Money is `BigDecimal` stored with `@Field(targetType = FieldType.DECIMAL128)` (as `CrmDeal.amount`); prices 2 decimals, rates 4, rounding `HALF_UP`.
- Every time-dependent rule takes the injected `Clock`; never `Instant.now()`.
- Error bodies use `CodedException` with upper-case codes: `SUBSCRIPTION_EXPIRED` (402), `SUBSCRIPTION_INACTIVE` (402), `LIMIT_REACHED` (409, extras `key`, `limit`, `used`), `GROUP_IN_USE` (409), `PLAN_IN_USE` (409), `BUILT_IN` (409).
- A customer with **no** subscription must behave exactly as today while the setting `customersWithoutPlan` is `UNRESTRICTED` (the default). Every existing test must pass unchanged, apart from the authority-count pins.
- "template" in names means Meta templates only; nothing here is named Template.
- Commit trailer: `Co-Authored-By: <the session's model> <noreply@anthropic.com>` as the session reminder says.

## Rulings made while planning (deviations from the spec's wording)

- `LimitKey`, `UsageMeter`, `BillingPeriod` and `SubscriptionState` live in `omnichannel-common` (`common.billing`), not in `security.entitlement`: documents in `omnichannel-db` store them and the CRM and AI modules may only import `common` and `security`.
- Read-only is enforced by a Spring MVC `HandlerInterceptor` in `omnichannel-app`, not a servlet filter: it must read the handler method's `@ReadOnlySafe` annotation. Effect is the same.
- Phase 1 adds five authorities (pins 52 → 57). `billing:request` and `refund:manage` come with phase 2.
- Staff may also **remove** a customer's subscription in phase 1 (state `NONE`); needed to undo a wrong assignment before orders exist.
- One history collection, `billingChanges` (with `targetType` GROUP, PLAN, SUBSCRIPTION or SETTINGS), replaces the spec's three (`featureGroupChanges`, `planChanges`, `subscriptionEvents`): same content, one shape.
- The plan document already carries its phase 2 and 3 fields (`addOns`, `limitAddOns`, `rates`, `discountPercent`) so the editor is built once.

## Review Focus

Each line has its test in the task named.

1. A Customer Admin on a plan without CRM gives the built-in `WORKSPACE_MANAGER` role (which holds `crm:*`) and then changes that user's role again: both must be allowed, and the user must not get `crm:*` (Task 4).
2. A plan change applies on the very next request of an already signed-in user, with no restart and no wait for the cache to age (Task 4, through the API).
3. The exact instants: at `termEnd` the state is `GRACE`, one millisecond before it is `ACTIVE`; at `termEnd + graceDays` it is `EXPIRED`; terms are midnights in `Asia/Kolkata`, not UTC (Tasks 3 and 10).
4. A cap lowered below current use keeps everything that exists, refuses the next creation, and refuses re-enabling a disabled user (Task 5).
5. An authority deleted from the catalogue while still listed in a group, and an authority offered to two groups: the first grants nothing and breaks nothing, the second is refused (Task 9).

---

## API contract (phase 1)

All staff endpoints are under `/api/billing`; reads need `subscription:view` **or** `plan:manage`, writes as stated. Dates are ISO local dates in the billing zone; instants are ISO-8601 UTC; money is a JSON number with at most 2 decimals.

```
GroupView      {code, name, description, category: "DEFAULT"|"PAID", authorities: string[], requires: string[],
                builtIn, sortOrder, version, planCodes: string[]}
PlanView       {code, name, description, period: "MONTHLY"|"QUARTERLY"|"HALF_YEARLY"|"YEARLY", rank,
                items: [{groupCode, price}], addOns: [{groupCode, price}],
                limits: {<LimitKey>: number}, limitAddOns: [{key, unitPrice, unit}],
                discountPercent, rates: {<UsageMeter>: number}, offered, status: "DRAFT"|"ACTIVE"|"RETIRED",
                revision, version, actualPrice, discountAmount, discountedPrice, customers: number}
PriceView      {actualPrice, discountAmount, discountedPrice}
SettingsView   {zone, graceDays, customersWithoutPlan: "UNRESTRICTED"|"READ_ONLY", version}
LimitKeyView   {key, scope: "CUSTOMER"|"BRAND"|"CHATBOT", label}
SubscriptionView {state: "NONE"|"ACTIVE"|"GRACE"|"EXPIRED", readOnly, plan: {code, name, period, rank} | null,
                termStart, termEnd, graceEnd,          // null when NONE
                zone,                                  // the billing zone id, for showing the dates
                groups: [{code, name, source: "DEFAULT"|"PLAN"|"ADDON"}],
                addOns: [{id, kind: "GROUP"|"LIMIT", groupCode, limitKey, quantity}],
                limits: [{key, label, scope, limit: number|null, used: number|null}],   // used null for BRAND/CHATBOT scope
                revision}
EventView      {id, kind, by, byName, at, summary}
```

| Method and path | Authority | Body → result |
|---|---|---|
| `GET /feature-groups` | read | → `GroupView[]` by `sortOrder` |
| `POST /feature-groups` | `plan:manage` | `{code, name, description, category, authorities, requires, sortOrder}` → 201 `GroupView` |
| `PUT /feature-groups/{code}` | `plan:manage` | same without `code`, plus `version` → `GroupView` |
| `DELETE /feature-groups/{code}` | `plan:manage` | 204; 409 `BUILT_IN`; 409 `GROUP_IN_USE` (extra `plans`) |
| `GET /feature-groups/ungrouped` | read | → `[{code, description, level}]` (COMPANY and BRAND authorities in no group) |
| `GET /feature-groups/{code}/history` | read | → `EventView[]`, newest first |
| `GET /plans?status=` | read | → `PlanView[]` by rank, then period |
| `GET /plans/{code}` | read | → `PlanView` |
| `POST /plans` | `plan:manage` | plan fields → 201 `PlanView` (status `DRAFT`) |
| `PUT /plans/{code}` | `plan:manage` | plan fields + `version` → `PlanView` (bumps `revision` when ACTIVE) |
| `POST /plans/price` | `plan:manage` | `{items, discountPercent}` → `PriceView` |
| `POST /plans/{code}/duplicate` | `plan:manage` | `{code, name, period}` → 201 `PlanView` (DRAFT) |
| `POST /plans/{code}/activate`, `/retire` | `plan:manage` | → `PlanView` |
| `DELETE /plans/{code}` | `plan:manage` | 204 for a DRAFT; else 409 `PLAN_IN_USE` |
| `GET /plans/{code}/history` | read | → `EventView[]` |
| `GET /limit-keys` | read | → `LimitKeyView[]` |
| `GET /settings` | read | → `SettingsView` |
| `PUT /settings` | `billing-settings:manage` | `{zone, graceDays, customersWithoutPlan, version}` → `SettingsView` |
| `GET /customers/{id}/subscription` | `subscription:view` | → `SubscriptionView` (state `NONE` when there is none) |
| `PUT /customers/{id}/subscription` | `subscription:manage` | `{planCode, startDate, endDate, addOns: [{kind, groupCode, limitKey, quantity}], revision}` → `SubscriptionView` |
| `DELETE /customers/{id}/subscription` | `subscription:manage` | 204 |
| `GET /customers/{id}/subscription/events` | `subscription:view` | → `EventView[]` |
| `GET /api/my/billing/subscription` | `billing:view` (COMPANY) | → `SubscriptionView` of the signed-in user's customer |

Changes to existing responses:
- `GET /api/users/me` gains `subscription: {state, planName, termEnd, graceEnd, readOnly, zone} | null` (null for platform users).
- `GET /api/customers` items and `GET /api/customers/{id}` `customer` gain `planName: string|null` and `subscriptionState`; the list takes `noPlan=true`.

---

### Task 1: Shared enums and billing documents

**Files:**
- Create: `omnichannel-common/src/main/java/me/nageshk/omnichannel/common/billing/{LimitKey,LimitScope,UsageMeter,BillingPeriod,SubscriptionState}.java`
- Create: `omnichannel-db/src/main/java/me/nageshk/omnichannel/db/billing/{FeatureGroup,GroupCategory,FeatureGroupRepository,Plan,PlanStatus,PlanItem,LimitAddOn,PlanRepository,Subscription,TermSegment,PlanTerms,SubscriptionAddOn,AddOnKind,SubscriptionRepository,BillingChange,BillingChangeRepository,BillingSettings,WithoutPlan,BillingSettingsRepository}.java`
- Test: `omnichannel-common/src/test/java/me/nageshk/omnichannel/common/billing/BillingPeriodTest.java`, `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/billing/BillingDocumentsIT.java`

**Interfaces (Produces):**
- `enum LimitKey { USERS, BRANDS, PHONE_NUMBERS, AUTOMATION_RULES, CHATBOTS, AI_PROFILES, CHATBOT_NODES, CHATBOT_AI_NODES, CRM_PIPELINES, CRM_CUSTOM_FIELDS }` with `LimitScope scope()` and `String label()`; scopes per spec 6.4 (`CUSTOMER` for the first six, `CHATBOT` for the two node keys, `BRAND` for the two CRM keys). Labels: "Users", "Brands", "WhatsApp numbers", "Automation rules", "Chatbots", "AI profiles", "Nodes per chatbot", "AI nodes per chatbot", "CRM pipelines per brand", "CRM custom fields per record type".
- `enum UsageMeter { WHATSAPP_MESSAGE }`.
- `enum BillingPeriod { MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), YEARLY(12) }` with `int months()` and `static LocalDate endOf(LocalDate start, BillingPeriod p, int anchorDay)`: the date `months` later on `anchorDay`, clamped to the month's last day.
- `enum SubscriptionState { NONE, ACTIVE, GRACE, EXPIRED }`.
- `FeatureGroup` (`featureGroups`, `@Id String code`): `name, description, GroupCategory category, Set<String> authorities, Set<String> requires, boolean builtIn, int sortOrder, createdAt, createdBy, updatedAt, updatedBy, @Version Long version`.
- `Plan` (`plans`, `@Id String code`): `name, description, BillingPeriod period, int rank, List<PlanItem> items, List<PlanItem> addOns, Map<LimitKey,Integer> limits, List<LimitAddOn> limitAddOns, BigDecimal discountPercent, Map<UsageMeter,BigDecimal> rates, boolean offered, PlanStatus status, long revision`, audit fields, `@Version`. `record PlanItem(String groupCode, BigDecimal price)`, `record LimitAddOn(LimitKey key, BigDecimal unitPrice, int unit)`.
- `Subscription` (`subscriptions`, unique index on `customerId`): `id, customerId, TermSegment current, TermSegment next, int anchorDay, BigDecimal creditBalance (0), long termRevision, long revision, Set<String> appliedOrderIds`, `@Version`.
- `TermSegment`: `planCode, long planRevision, PlanTerms terms, Set<String> groupCodes, List<SubscriptionAddOn> addOns, Instant termStart, Instant termEnd, BigDecimal planPaid (0)`. `record PlanTerms(String planName, BillingPeriod period, int rank, BigDecimal discountPercent, List<PlanItem> items, List<PlanItem> addOns, List<LimitAddOn> limitAddOns, Map<LimitKey,Integer> limits, Map<UsageMeter,BigDecimal> rates)`. `SubscriptionAddOn`: `id, AddOnKind kind, groupCode, LimitKey limitKey, int quantity, BigDecimal unitPrice, BigDecimal paid, Instant coveredFrom, String orderId, String invoiceId`.
- `BillingChange` (`billingChanges`): `id`, `targetType` (`GROUP`, `PLAN`, `SUBSCRIPTION`, `SETTINGS`), `targetId` (compound index with `targetType`), `kind`, `summary`, `before` and `after` (`Map<String,Object>`), `by`, `at`.
- `BillingSettings` (`billingSettings`, `@Id` fixed `"billing"`): `String zone ("Asia/Kolkata"), int graceDays (7), WithoutPlan customersWithoutPlan (UNRESTRICTED)`, `@Version`. `enum WithoutPlan { UNRESTRICTED, READ_ONLY }`.
- Repositories: `FeatureGroupRepository`, `PlanRepository` (`findByStatus`, `existsByItemsGroupCode`…as needed), `SubscriptionRepository.findByCustomerId`, `BillingChangeRepository.findByTargetTypeAndTargetIdOrderByAtDescIdDesc`, `BillingSettingsRepository`.

- [ ] **Step 1: Write the failing tests**

`BillingPeriodTest`:
```java
assertThat(BillingPeriod.endOf(LocalDate.of(2027, 1, 31), MONTHLY, 31)).isEqualTo(LocalDate.of(2027, 2, 28));
assertThat(BillingPeriod.endOf(LocalDate.of(2027, 2, 28), MONTHLY, 31)).isEqualTo(LocalDate.of(2027, 3, 31));
assertThat(BillingPeriod.endOf(LocalDate.of(2028, 1, 31), MONTHLY, 31)).isEqualTo(LocalDate.of(2028, 2, 29));
assertThat(BillingPeriod.endOf(LocalDate.of(2026, 11, 17), YEARLY, 17)).isEqualTo(LocalDate.of(2027, 11, 17));
assertThat(BillingPeriod.endOf(LocalDate.of(2026, 11, 30), QUARTERLY, 30)).isEqualTo(LocalDate.of(2027, 2, 28));
```
`BillingDocumentsIT` (a `@SpringBootTest` in the style of `CustomersApiIT`): saves a `Plan` with `price 1999.50`, `discountPercent 12.5`, a `limits` entry and a `rates` entry, reads it back and asserts equal values and that Mongo holds `Decimal128` for `items.0.price`; saving two `Subscription`s with the same `customerId` throws `DuplicateKeyException`.

- [ ] **Step 2: Run them and see them fail** (compile errors: the types do not exist).

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='BillingPeriodTest,BillingDocumentsIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Create the enums, documents and repositories** as in Interfaces. Lombok `@Getter @Setter` like `Customer`; `Map` keys that are enums are stored by name.

- [ ] **Step 4: Run the tests; expected PASS.**

- [ ] **Step 5: Commit** `feat(billing): plan, feature group and subscription documents`.

### Task 2: Authorities and seeded feature groups

**Files:**
- Modify: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/access/Authority.java`
- Create: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/entitlement/FeatureGroupSeeder.java`, `SeedGroups.java`
- Modify tests: `omnichannel-security/src/test/.../access/AuthorityTest.java` (pin 57), `omnichannel-app/src/test/.../access/RoleSeederIT.java` (pins 57)
- Test: `omnichannel-security/src/test/.../entitlement/SeedGroupsTest.java`, `omnichannel-app/src/test/.../billing/FeatureGroupSeederIT.java`

**Interfaces (Produces):**
- New `Authority` constants: `PLAN_MANAGE("plan:manage", PLATFORM, "Create and edit feature groups and plans")`, `BILLING_SETTINGS_MANAGE("billing-settings:manage", PLATFORM, "Edit billing settings")`, `SUBSCRIPTION_VIEW("subscription:view", PLATFORM, "See plans and a customer's subscription", ACCOUNT_MANAGER)`, `SUBSCRIPTION_MANAGE("subscription:manage", PLATFORM, "Assign, change and remove a customer's plan", ACCOUNT_MANAGER)`, `BILLING_VIEW("billing:view", COMPANY, "See the company's plan and usage", CUSTOMER_ADMIN)`.
- `SeedGroups.all() : List<SeedGroups.Seed>` where `record Seed(String code, String name, GroupCategory category, Set<String> requires, Predicate<Authority> holds, int sortOrder)`; the nine groups of spec 6.1 with Templates as DEFAULT and Campaigns requiring nothing. `core` holds `workspace:*`, `company-user:manage`, `user:*`, `team:manage`, `phone-number:view`, `phone-number:manage`, `billing:view`; `crm` holds BRAND-level `crm:*` only.
- `SeedGroups.CORE = "core"`, `SeedGroups.coreBuiltIns() : Set<String>`.
- `FeatureGroupSeeder implements ApplicationRunner`, `@Order(2)` (after `RoleSeeder`): for each seed whose code has no document, insert it with `builtIn = true`; never touches an existing document, **except** that it adds to `core` any of `coreBuiltIns()` that is missing (so a new core authority reaches existing installations).

- [ ] **Step 1: Write the failing tests**

`SeedGroupsTest`:
```java
// every built-in COMPANY or BRAND authority is in exactly one seed group
for (Authority a : Authority.values()) {
    if (a.level() == RoleLevel.PLATFORM) continue;
    assertThat(SeedGroups.all().stream().filter(s -> s.holds().test(a)).count()).as(a.code()).isEqualTo(1);
}
// no PLATFORM authority is in any seed group
// categories: core, inbox, contacts, templates DEFAULT; campaigns, automations, chatbots, ai, crm PAID
// ai requires chatbots; nothing else requires anything
```
`FeatureGroupSeederIT`: after startup `featureGroups` holds the nine codes; `core.authorities` contains `billing:view` and `user:manage`; deleting `billing:view` from the stored `core` and running the seeder again restores it; renaming `crm` and running again keeps the new name.
Update `AuthorityTest` and `RoleSeederIT` pins from 52 to 57 first and see them fail.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='SeedGroupsTest,FeatureGroupSeederIT,AuthorityTest,RoleSeederIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Add the authorities, `SeedGroups` and `FeatureGroupSeeder`.**

- [ ] **Step 4: Run the four tests; expected PASS.** Also run `AuthorityCheckScannerIT`; expected PASS.

- [ ] **Step 5: Commit** `feat(billing): billing authorities and seeded feature groups`.

### Task 3: Entitlement resolution

**Files:**
- Create: `omnichannel-security/src/main/java/me/nageshk/omnichannel/security/entitlement/{Entitlement,Entitlements,SubscriptionClock,BillingSettingsStore}.java`
- Test: `omnichannel-security/src/test/.../entitlement/SubscriptionClockTest.java`, `EntitlementsTest.java` (plain unit tests with in-memory fakes or Mockito for the repositories), `omnichannel-app/src/test/.../billing/EntitlementsIT.java`

**Interfaces (Produces):**
- `record Entitlement(SubscriptionState state, Set<String> authorities /* null = unrestricted */, Map<LimitKey,Integer> limits /* absent = unlimited */, boolean readOnly, String planName, Instant termStart, Instant termEnd, Instant graceEnd)` with `boolean unrestricted()`, `boolean allows(String code)` (true when unrestricted or contained), `Optional<Integer> limit(LimitKey)`. `Entitlement.UNRESTRICTED` constant (state NONE, authorities null, no limits, not read-only).
- `SubscriptionClock.segmentAt(Subscription s, Instant now) : TermSegment` (`next` once `now ≥ next.termStart`, else `current`); `SubscriptionClock.stateAt(Subscription s, int graceDays, ZoneId zone, Instant now) : SubscriptionState` using the last paid segment's `termEnd`; `graceEnd` = `termEnd` plus `graceDays` calendar days in `zone`.
- `BillingSettingsStore.get() : BillingSettings` (the stored document or defaults; cached, `evict()`).
- `@Service Entitlements`: `of(String customerId)`, `ofWorkspace(String workspaceId)` (resolves the workspace's customer; unknown workspace → `UNRESTRICTED` is **wrong**: return an entitlement that allows nothing and is read-only), `allows(String customerId, String code)`, `allowsInWorkspace(String workspaceId, String code)`, `evict(String customerId)`, `evictAll()`.
  - No subscription: `UNRESTRICTED` when the setting is `UNRESTRICTED`; otherwise state `NONE`, authorities null, `readOnly = true`.
  - With one: `authorities` = authorities of every DEFAULT group ∪ of `segment.groupCodes` ∪ of GROUP add-ons' groups; `limits` = `segment.terms.limits` plus Σ LIMIT add-on `quantity` per key (a key absent from the plan stays absent whatever the add-ons); `readOnly` = state is `EXPIRED`.
  - Group codes that no longer exist are ignored. Authority codes are passed through as stored: `AccessService` already drops codes missing from the catalogue.
  - Cache: per customer, the resolved inputs (subscription, group sets) for 30 seconds by the injected `Clock`; **state, `readOnly` and the segment in force are recomputed on every call** from the cached dates. `evict` and `evictAll` clear it at once.

- [ ] **Step 1: Write the failing tests**

`SubscriptionClockTest` (zone `Asia/Kolkata`, grace 7, term 2026-12-01 → 2027-01-01 IST, i.e. `termEnd = 2026-12-31T18:30:00Z`):
```java
assertThat(stateAt(sub, 7, IST, Instant.parse("2026-12-31T18:29:59.999Z"))).isEqualTo(ACTIVE);
assertThat(stateAt(sub, 7, IST, Instant.parse("2026-12-31T18:30:00Z"))).isEqualTo(GRACE);
assertThat(stateAt(sub, 7, IST, Instant.parse("2027-01-07T18:29:59.999Z"))).isEqualTo(GRACE);
assertThat(stateAt(sub, 7, IST, Instant.parse("2027-01-07T18:30:00Z"))).isEqualTo(EXPIRED);
// with a next segment 2027-01-01 → 2027-02-01: ACTIVE on 2027-01-15, segmentAt returns next from 2026-12-31T18:30:00Z
// grace 0: EXPIRED exactly at termEnd
```
`EntitlementsTest`:
- no subscription + `UNRESTRICTED` → `Entitlement.UNRESTRICTED`; + `READ_ONLY` → `readOnly`, `unrestricted()`.
- plan with `groupCodes {crm}` → authorities = core ∪ inbox ∪ contacts ∪ templates ∪ crm authorities; `allows("crm:view")` true, `allows("campaign:manage")` false.
- GROUP add-on `campaigns` → `allows("campaign:manage")` true.
- limits `{USERS: 5}` + LIMIT add-on `USERS × 5` → `limit(USERS)` = 10; add-on for `CHATBOTS` with no plan limit → `limit(CHATBOTS)` empty.
- a `groupCodes` entry with no document → ignored, no exception.
- `EXPIRED` → `readOnly`, authorities unchanged.
- cache: a second `of` within 30 s does not query again; after `evict(customerId)` it does; after the clock advances 31 s it does; state flips from ACTIVE to GRACE across `termEnd` **without** a query.
- `ofWorkspace("missing")` → `allows(anything)` false and `readOnly`.

`EntitlementsIT`: with real Mongo, the seeded groups and a saved subscription, `of(customerId)` matches the unit expectations.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='SubscriptionClockTest,EntitlementsTest,EntitlementsIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** the four classes.

- [ ] **Step 4: Run; expected PASS.**

- [ ] **Step 5: Commit** `feat(billing): entitlement resolution with derived state`.

### Task 4: The ceiling in `AccessService`, `AssignmentPolicy` and `/users/me`

**Files:**
- Modify: `omnichannel-security/.../security/access/AccessService.java`, `AssignmentPolicy.java`, `omnichannel-security/.../security/user/UserService.java` (and the `CurrentUser` record it returns)
- Test: `omnichannel-security/src/test/.../access/AssignmentPolicyTest.java` (extend the existing test class if present, else create), `omnichannel-app/src/test/.../billing/CeilingApiIT.java`

**Interfaces:**
- Consumes: `Entitlements.of`, `Entitlement.authorities()`.
- Produces:
  - `AccessService`: for a customer user, `globalAuthorities` and `inWorkspace` and `companyCodes` return their previous result `retainAll(entitlement.authorities())` when the entitlement is not unrestricted. Platform users untouched. `actorOf` passes the customer's entitled set into the actor.
  - `AssignmentPolicy.Actor` gains a fifth component `Set<String> ceiling` (null = none); a four-argument constructor stays for existing callers and tests and sets it to null. `effective(Role)` is unchanged; every comparison (`canGiveBrandRole`, `canChangeInWorkspace`, `canManageLogin`, `managesIn`) first limits the role's or target's set to `a.ceiling()` when it is not null.
  - `CurrentUser` gains `SubscriptionSummary subscription` (`record SubscriptionSummary(SubscriptionState state, String planName, Instant termEnd, Instant graceEnd, boolean readOnly, String zone)`), null for platform users.

- [ ] **Step 1: Write the failing tests**

`AssignmentPolicyTest` additions:
```java
// actor: customer admin whose effective set in ws is {user:manage, user:view, inbox:view, workspace:settings}
// ceiling: the same set. role WORKSPACE_MANAGER holds {user:manage, user:view, inbox:view, crm:view, crm:edit}
// → canGiveBrandRole is true (crm:* is outside the ceiling, the rest is a strict subset)
// → with ceiling null and the same sets, canGiveBrandRole is false (today's behaviour, unchanged)
// target user holding that role in ws → canChangeInWorkspace true with the ceiling
// a role equal to the actor's set inside the ceiling → still false (strict)
```
`CeilingApiIT` (create a customer through the API as an account manager, as `CustomersApiIT` does; write the subscription with the repository, then `entitlements.evict`):
1. Plan without `crm`: the Customer Admin's `GET /api/users/me` has no `crm:*` in `workspaces[0].authorities`, has `user:manage`, and `subscription.state` is `ACTIVE`.
2. `GET /api/workspaces/{ws}/crm/status` (or any `crm:view` endpoint) → 403; `GET /api/workspaces/{ws}/users` → 200.
3. The Customer Admin creates a user with the built-in `WORKSPACE_MANAGER` role → 201; that user's `/me` has no `crm:*`; the admin then changes the user's role to `AGENT` → 200. (Review Focus 1.)
4. With the same signed-in token, replace the subscription by one that includes `crm`, evict, call the CRM endpoint again → not 403. (Review Focus 2.)
5. A customer with no subscription: `/me` authorities equal what they are on `main` (assert `crm:view` present for the admin) and `subscription.state` is `NONE`, `readOnly` false.
6. A platform user's `/me` has `subscription` null.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='AssignmentPolicyTest,CeilingApiIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.** `AccessService` gets `Entitlements` injected.

- [ ] **Step 4: Run; expected PASS.** Then run the existing access suites: `-Dtest='*Access*,*Role*,*User*IT,CustomersApiIT'`; expected PASS with no test edited.

- [ ] **Step 5: Commit** `feat(billing): plan ceiling over roles and role assignment`.

### Task 5: Limits for users, brands and numbers

**Files:**
- Create: `omnichannel-security/.../security/entitlement/{Limits,LimitCounter}.java`; counters `UserLimitCounter` (security, `security.user`), `BrandLimitCounter` and `PhoneNumberLimitCounter` (service, beside `WorkspaceService` and `PhoneNumberService`)
- Modify: `UserAdminService.create` and the method that enables a user; `WorkspaceService.create`; `PhoneNumberService.create`
- Test: `omnichannel-security/src/test/.../entitlement/LimitsTest.java`, `omnichannel-app/src/test/.../billing/CustomerLimitsIT.java`

**Interfaces (Produces):**
- `interface LimitCounter { LimitKey key(); long count(String customerId); }` (beans; only for `CUSTOMER`-scope keys).
- `@Service Limits`: `void require(String customerId, LimitKey key)` (CUSTOMER scope: uses the registered counter; throws when `count ≥ limit`), `void require(String customerId, LimitKey key, long used)` (any scope, caller counts), `Optional<Integer> effective(String customerId, LimitKey key)`, `OptionalLong used(String customerId, LimitKey key)` (empty for keys without a counter). The exception is `new CodedException(HttpStatus.CONFLICT, "LIMIT_REACHED", null, "Your plan allows " + limit + " " + label, Map.of("key", key.name(), "limit", ..., "used", ...))`. A null `customerId` (platform user creation) never throws.
- Counted: `USERS` = users of the customer with `active` true; `BRANDS` = workspaces of the customer; `PHONE_NUMBERS` = numbers of the customer.

- [ ] **Step 1: Write the failing tests**

`LimitsTest`: no limit → no throw whatever `used`; limit 5, used 4 → ok; used 5 → `CodedException` 409 `LIMIT_REACHED` with extras `{key: USERS, limit: 5, used: 5}`; `customerId` null → ok.
`CustomerLimitsIT` (subscription with `limits {USERS: 2, BRANDS: 1, PHONE_NUMBERS: 1}`; the customer starts with 1 user, 1 brand):
- second user → 201; third → 409 `LIMIT_REACHED`, body `key` `USERS`, `limit` `2`, `used` `2`.
- disable one user, create another → 201; re-enable the disabled one → 409. (Review Focus 4.)
- lower the limit to 1 with two users present: both still sign in and work; creating → 409. (Review Focus 4.)
- second brand → 409 with `key` `BRANDS`; second number → 409 with `key` `PHONE_NUMBERS`.
- the same calls for a customer without a subscription all succeed.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='LimitsTest,CustomerLimitsIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** `Limits`, the three counters and the three call sites (`limits.require(customerId, KEY)` before the insert; in `UserAdminService.create` only when `customerId != null`).

- [ ] **Step 4: Run; expected PASS.**

- [ ] **Step 5: Commit** `feat(billing): plan limits on users, brands and numbers`.

### Task 6: Limits for automations, chatbots, flows and AI profiles

**Files:**
- Create: `AutomationRuleLimitCounter`, `ChatbotLimitCounter` (service), `AiProfileLimitCounter` (ai module)
- Modify: `service/automation/AutomationRuleService.create`, `service/chatbot/ChatbotService.create`, `service/chatbot/FlowValidator.java` (and its `ValidationContext`), `omnichannel-ai/.../ai/profile/AiProfileService.create`, `omnichannel-ai/src/test/.../AiModuleBoundaryTest.java` (allow `security.entitlement.` and `common.billing.`)
- Test: `omnichannel-service/src/test/.../chatbot/FlowValidatorLimitsTest.java`, `omnichannel-app/src/test/.../billing/FeatureLimitsIT.java`

**Interfaces:**
- Consumes: `Limits.require`, `Limits.effective`.
- Produces: `ValidationContext` carries `Integer maxNodes` and `Integer maxAiNodes` (null = unlimited), filled by `ChatbotService` from `limits.effective(customerId, CHATBOT_NODES | CHATBOT_AI_NODES)`. `FlowValidator.validate` adds a flow-level problem of severity ERROR `"Your plan allows N nodes in a chatbot"` when the node count exceeds `maxNodes`, and `"Your plan allows N AI nodes in a chatbot"` when the count of nodes of type `ai` exceeds `maxAiNodes`. Counters count across all of the customer's workspaces (by `customerId` where the document has it, else by the customer's workspace ids).

- [ ] **Step 1: Write the failing tests**

`FlowValidatorLimitsTest`: a flow of 3 nodes with `maxNodes 3` → no such problem; with `maxNodes 2` → one ERROR whose message is `"Your plan allows 2 nodes in a chatbot"`; two AI nodes with `maxAiNodes 1` → `"Your plan allows 1 AI nodes in a chatbot"`; nulls → none.
`FeatureLimitsIT` (limits `{AUTOMATION_RULES: 1, CHATBOTS: 1, AI_PROFILES: 1, CHATBOT_NODES: 2}`, plan including automations, chatbots, ai):
- second automation rule → 409 `LIMIT_REACHED` `key` `AUTOMATION_RULES`; second chatbot → `CHATBOTS`; second AI profile → `AI_PROFILES`.
- saving a 3-node flow as a draft → 200 and the response's problems contain the plan message; publishing it → refused as any flow with an ERROR is today.
- rules and chatbots in **two brands** of the customer count together.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='FlowValidatorLimitsTest,FeatureLimitsIT,AiModuleBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run; expected PASS.** Also `-Dtest='*Chatbot*,*Automation*IT,AiProfilesApiIT'`; expected PASS.

- [ ] **Step 5: Commit** `feat(billing): plan limits on rules, chatbots, flows and AI profiles`.

### Task 7: The CRM follows the plan

**Files:**
- Create: `omnichannel-crm/.../crm/settings/CrmSwitch.java`
- Modify: `crm/access/CrmAccess.java` (lines using `s.isEnabled()`), `crm/status/CrmStatusController.java`, `crm/job/DealRottingJob.java` (and any other reader of `isEnabled()` except `CrmSettingsService.setSwitch` and `CrmEnablementController`), `crm/pipeline/CrmPipelineService.java:138-142`, `crm/field/FieldDefinitionService.java:227-237`, `omnichannel-crm/src/test/.../CrmModuleBoundaryTest.java` (allow `security.entitlement.` and `common.billing.`)
- Test: `omnichannel-crm/src/test/.../settings/CrmSwitchTest.java`, `omnichannel-app/src/test/.../billing/CrmPlanIT.java`

**Interfaces (Produces):**
- `@Component CrmSwitch`: `boolean enabled(CrmSettings s)` → when `Entitlements.of(s.getCustomerId())` is unrestricted: `s.isEnabled()`; otherwise `entitlement.allows("crm:view")`. `Integer maxPipelines(CrmSettings s)` and `Integer maxCustomFields(CrmSettings s)` → the plan's `CRM_PIPELINES` / `CRM_CUSTOM_FIELDS` when present, else the stored brand value.
- `CrmStatusView.enabled` and `limits` report the effective values.

- [ ] **Step 1: Write the failing tests**

`CrmSwitchTest`: unrestricted + stored false → false; unrestricted + stored true → true; plan with crm + stored false → true; plan without crm + stored true → false; plan limit 3 + brand limit 10 → 3; no plan limit + brand limit 10 → 10; neither → null.
`CrmPlanIT`: a customer whose plan includes `crm`, with the brand switch never touched: `GET …/crm/status` → `enabled` true and a default pipeline exists afterwards; with `CRM_PIPELINES: 1` a second pipeline → 409 `LIMIT_REACHED`. A customer without subscription and the switch off → `enabled` false (today's behaviour).

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='CrmSwitchTest,CrmPlanIT,CrmModuleBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run; expected PASS.** Then `./mvnw -q -pl omnichannel-crm -am test`; expected PASS.

- [ ] **Step 5: Commit** `feat(billing): CRM switch and limits follow the plan`.

### Task 8: Sending and background work stop outside the plan

**Files:**
- Create: `omnichannel-common/.../common/api/SubscriptionInactiveException.java` (`extends CodedException`, 402, code `SUBSCRIPTION_INACTIVE`), `omnichannel-service/.../service/billing/WorkGate.java`
- Modify: `service/inbox/InboxSendService.java` (`send`, `sendCloud`, right after `devices.requireReady`), `service/campaign/CampaignRunner.java` (`eligible`, and the `catch` beside `NoDeviceException` at `:383-385`), `service/automation/AutomationGate.java` (`wants`), `service/automation/SendTemplateHandler.java`, `service/chatbot/run/ChatbotRunService.java` (`start`), `service/chatbot/run/ChatbotSender.java`, `omnichannel-ai/.../ai/call/AiGatewayService.java` (`submit`)
- Test: `omnichannel-service/src/test/.../billing/WorkGateTest.java`, `omnichannel-app/src/test/.../billing/WorkGatesIT.java`

**Interfaces (Produces):**
- `@Component WorkGate`: `void requireSend(String workspaceId, SendActor actor)` throws `SubscriptionInactiveException` when the workspace's entitlement is read-only, or when the actor's anchor is not allowed (`CampaignActor` → `campaign:manage`, `AutomationActor` → `automation:manage`, `ChatbotActor` → `chatbot:manage`, `UserActor` → none). `boolean allows(String workspaceId, String anchor)` → not read-only and `allowsInWorkspace`.
- Behaviour at each caller when refused:
  - console send: the 402 reaches the client.
  - `CampaignRunner.eligible` is false; a claim that meets the exception is released exactly as for `NoDeviceException`. The campaign stays RUNNING.
  - `AutomationGate.wants` → false. `SendTemplateHandler`: a **failed** action result (not `retryLater`) with reason `"The plan does not allow this"`.
  - `ChatbotRunService.start` → the result variant that means "not started" (add a reason value `NOT_ENTITLED` to the existing result type); `ChatbotSender` maps the exception to the node's failure, not to `RetryLater`, so a waiting run ends at its next send.
  - `AiGatewayService.submit` → throws `SubscriptionInactiveException` when `!allows(workspaceId, "ai:manage")`; the AI node takes its `failure` output (assert this in the IT; adapt `AiExecutor` only if it does not already map a failed submit to `failure`).

- [ ] **Step 1: Write the failing tests**

`WorkGateTest`: read-only → throws for every actor; plan without campaigns → throws for `CampaignActor`, not for `UserActor`; unrestricted → never throws; `allows` mirrors it.
`WorkGatesIT` (uses the test devices as `SendTemplateActionIT` and the campaign tests do):
- Customer on a plan **with** automations and chatbots sends from the console → stored message. Subscription moved to `EXPIRED` by advancing `MutableClock` past grace → console send 402 `SUBSCRIPTION_INACTIVE`; an incoming message is still stored.
- Plan without `campaigns`, a RUNNING campaign, `runner.tick()` → no message sent, campaign still RUNNING, recipient still pending; switch to a plan with campaigns, tick → sent.
- Plan without `automations`: an event that matches a published rule → no run.
- Plan with chatbots: start a run that waits; replace the plan by one without chatbots; answer → no message sent and the run is FAILED. (Spec 8.2, in-flight work.)
- Plan without `ai`: a chatbot with an AI node → the node's `failure` output is taken and no `aiCalls` row is RUNNING.
- A customer without subscription: all of the above work as today.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='WorkGateTest,WorkGatesIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run; expected PASS.** Then `-Dtest='*Campaign*IT,*Automation*IT,*Chatbot*IT,*Ai*IT,*Inbox*IT'`; expected PASS.

- [ ] **Step 5: Commit** `feat(billing): sends and background work respect the plan`.

### Task 9: Feature groups and plans API

**Files:**
- Create: `omnichannel-service/.../service/billing/{FeatureGroupService,PlanService,PlanPricing,BillingAudit,BillingViews}.java`, `omnichannel-app/.../app/billing/{FeatureGroupController,PlanController}.java`
- Test: `omnichannel-service/src/test/.../billing/PlanPricingTest.java`, `omnichannel-app/src/test/.../billing/FeatureGroupsApiIT.java`, `PlansApiIT.java`

**Interfaces:**
- Consumes: documents and repositories (Task 1), `Entitlements.evictAll()`, `AuthorityCatalog` (levels of authority codes), `SeedGroups.CORE`, `SeedGroups.coreBuiltIns()`.
- Produces: the group and plan rows of the API contract. `PlanPricing.price(List<PlanItem> items, BigDecimal discountPercent) : Price` with `record Price(BigDecimal actual, BigDecimal discount, BigDecimal discounted)`; `actual` = Σ prices, `discounted` = `actual × (100 − d) / 100` at 2 decimals HALF_UP, `discount` = `actual − discounted`. `BillingAudit.record(targetType, targetId, kind, summary, before, after, by)`.
- Validation (all → `ValidationException` with the field name, as `AuthorityService` does):
  - group `code` `[a-z0-9-]{2,30}` unique; `name` 1–60; every authority exists in the catalogue at COMPANY or BRAND level; **an authority already in another group is refused** (`authorities`, "crm:view is already in group CRM"); `requires` name existing other groups and form no cycle; `core`: category stays DEFAULT, `coreBuiltIns()` cannot be removed, cannot be deleted (409 `BUILT_IN`); a DEFAULT group cannot be made PAID or the reverse while a plan lists it (409 `GROUP_IN_USE`); delete refused while any plan or subscription segment names it.
  - plan `code` `[a-z0-9-]{2,40}` unique; `name` 1–80; `rank` 0–999; `items` and `addOns` name PAID groups, each group at most once across both; prices ≥ 0 with at most 2 decimals; `discountPercent` 0–100 with at most 2 decimals; every `requires` of an item is an item; every `requires` of an add-on is an item or an add-on; `limits` values ≥ 0; `limitAddOns` keys unique, `unit` ≥ 1, `unitPrice` ≥ 0; `rates` ≥ 0 with at most 4 decimals.
  - `activate`: DRAFT or RETIRED → ACTIVE. `retire`: ACTIVE → RETIRED. Editing a RETIRED plan is refused. `PUT` on an ACTIVE plan bumps `revision` and never touches a subscription.
- Every write records a `BillingChange` and calls `entitlements.evictAll()`.

- [ ] **Step 1: Write the failing tests**

`PlanPricingTest`:
```java
assertThat(price(items(1000, 499.50), pct("10"))).isEqualTo(new Price(bd("1499.50"), bd("149.95"), bd("1349.55")));
assertThat(price(items(999.99), pct("33.33")).discounted()).isEqualByComparingTo("666.69"); // 999.99 × 0.6667 = 666.693…
assertThat(price(items(), pct("50"))).isEqualTo(new Price(bd("0.00"), bd("0.00"), bd("0.00")));
assertThat(price(items(100), pct("100")).discounted()).isEqualByComparingTo("0.00");
```
`FeatureGroupsApiIT`: list returns the nine seeded groups in order; create, update (version conflict → 409), history rows; an authority in two groups → 400 on `authorities` (Review Focus 5); a PLATFORM authority → 400; removing `user:manage` from `core` → 400; deleting `core` → 409 `BUILT_IN`; deleting a group listed in a plan → 409 `GROUP_IN_USE` with `plans`; `GET /ungrouped` lists an authority after it is removed from its group; **a group edit reaches a subscribed customer at once** (remove `crm:export` from `crm`, the admin's `/me` loses it on the next call); an authority deleted from the catalogue but still in a group breaks nothing (Review Focus 5); `ACCOUNT_MANAGER` can read and cannot write (403).
`PlansApiIT`: create returns DRAFT with `actualPrice`, `discountAmount`, `discountedPrice`; each validation above → 400 with its field; `POST /plans/price` equals what the saved plan reports; duplicate copies everything but code, name, period and is DRAFT; activate/retire transitions and the refused ones; editing an ACTIVE plan bumps `revision` and leaves an existing subscription's `terms` unchanged; delete of a DRAFT → 204, of an ACTIVE → 409; `customers` counts subscriptions whose segment in force names the plan.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='PlanPricingTest,FeatureGroupsApiIT,PlansApiIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run; expected PASS.** Also `ServiceBoundaryTest` and `AuthorityCheckScannerIT`; expected PASS.

- [ ] **Step 5: Commit** `feat(billing): feature group and plan API`.

### Task 10: Settings, assigning a plan, and the subscription views

**Files:**
- Create: `omnichannel-service/.../service/billing/{BillingSettingsService,SubscriptionService,SubscriptionViews}.java`, `omnichannel-app/.../app/billing/{BillingSettingsController,SubscriptionController,MyBillingController,LimitKeyController}.java`
- Modify: `omnichannel-app/.../app/tenant/CustomerController.java` (`CustomerView`, list filter), `omnichannel-service/.../service/tenant/CustomerService.java` (list query)
- Test: `omnichannel-app/src/test/.../billing/SubscriptionApiIT.java`, `BillingSettingsApiIT.java`; extend `CustomersApiIT`

**Interfaces:**
- Consumes: `SubscriptionClock`, `Entitlements`, `Limits.used`, `BillingPeriod.endOf`, `BillingAudit`.
- Produces: the settings, limit-keys, subscription and my-billing rows of the contract, and the customer-list additions.
  - `SubscriptionService.assign(customerId, Assign body, by)`: plan must be ACTIVE (or RETIRED and already the customer's plan); `startDate < endDate`; add-on groups must be PAID, not in the plan, with `requires` satisfied by plan groups and the other add-ons; LIMIT add-ons `quantity` ≥ 1 on a key the plan limits; `revision` must equal the stored one when a subscription exists (409 otherwise). Writes `current` (snapshot `PlanTerms` from the plan, `termStart`/`termEnd` at 00:00 of the dates in the billing zone, `planPaid` 0, add-ons with `paid` 0), clears `next`, `anchorDay` = start day, increments `termRevision` and `revision`, in **one** save; records a `BillingChange`; `entitlements.evict(customerId)`.
  - `remove(customerId, by)`: deletes the document, records the change, evicts.
  - `view(customerId)`: `SubscriptionView`; `groups` lists DEFAULT groups, then plan groups, then add-on groups; `limits` lists every `LimitKey` with its effective limit (null = unlimited) and `used` from the counters.
  - Settings validation: `zone` a valid `ZoneId`; `graceDays` 0–90; saving evicts `BillingSettingsStore` and `entitlements.evictAll()`.

- [ ] **Step 1: Write the failing tests**

`SubscriptionApiIT`:
- `GET` for a customer with none → `state NONE`, `plan` null, `limits` all null limit.
- `PUT` with an ACTIVE plan, `startDate 2026-12-01`, `endDate 2027-01-01` → `termStart` `2026-11-30T18:30:00Z`, `termEnd` `2026-12-31T18:30:00Z`, `graceEnd` `2027-01-07T18:30:00Z`, `state ACTIVE` at clock 2026-12-10; `groups` sources DEFAULT/PLAN/ADDON; `limits` USERS `{limit: 5, used: 1}`.
- DRAFT plan → 400 `planCode`; `endDate ≤ startDate` → 400 `endDate`; add-on group already in the plan → 400; AI add-on on a plan without chatbots → 400; stale `revision` → 409.
- A second `PUT` (change plan) replaces the segment, `revision` 2, and the events list has two rows, newest first, with `byName`.
- `DELETE` → 204, then `GET` is `NONE`, and the Customer Admin's `/me` is unrestricted again.
- `ACCOUNT_MANAGER` may `PUT`; a customer user gets 403 on `/api/billing/**`; the Customer Admin's `GET /api/my/billing/subscription` returns its own; a `WORKSPACE_MANAGER` gets 403 there.
- `GET /api/billing/limit-keys` returns ten rows with scope and label.

`BillingSettingsApiIT`: defaults `Asia/Kolkata`, 7, `UNRESTRICTED`; `PUT` needs `billing-settings:manage` (account manager 403); bad zone → 400 `zone`; after `customersWithoutPlan = READ_ONLY`, a customer without subscription has `/me.subscription.readOnly` true.
`CustomersApiIT` additions: list items carry `planName` and `subscriptionState`; `noPlan=true` returns only customers without a subscription.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='SubscriptionApiIT,BillingSettingsApiIT,CustomersApiIT' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run; expected PASS.**

- [ ] **Step 5: Commit** `feat(billing): billing settings and plan assignment API`.

### Task 11: Read-only mode

**Files:**
- Create: `omnichannel-common/.../common/api/ReadOnlySafe.java` (`@Target(METHOD) @Retention(RUNTIME)`), `omnichannel-app/.../app/billing/{ReadOnlyInterceptor,BillingWebConfig}.java`
- Modify: annotate handlers (list below)
- Test: `omnichannel-app/src/test/.../billing/ReadOnlyIT.java`, `ReadOnlySafeHandlersTest.java`

**Interfaces (Produces):**
- `ReadOnlyInterceptor implements HandlerInterceptor`: when the principal is an `AuthenticatedUser` with a `customerId`, the method is not `GET`, `HEAD` or `OPTIONS`, the handler is a `HandlerMethod` without `@ReadOnlySafe`, and `entitlements.of(customerId).readOnly()` → throw `new CodedException(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_EXPIRED", null, "The subscription has ended; the account is read-only until it is renewed")`. Registered for `/api/**` by `BillingWebConfig implements WebMvcConfigurer`.
- `@ReadOnlySafe` goes on every handler that answers a read through a non-GET method, and on the user's own account actions. Find them by listing every `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping` and judging each; the list must include at least: contact `search`, filter `preview` and bulk `count`; CRM contact, company and deal `search` and the three `export`s; segment `preview`; chatbot message `preview` and Cloud message `preview`; inbox mark-as-read (`POST …/chats/{id}/read`); change own password; own profile and two-factor settings; own availability; sign-out if it is an MVC handler.

- [ ] **Step 1: Write the failing tests**

`ReadOnlySafeHandlersTest` (a `@SpringBootTest` that reads `RequestMappingHandlerMapping`): the set of `Class#method` names carrying `@ReadOnlySafe` equals a literal expected set written in the test (so adding or removing one is a visible decision); and no handler mapped to `GET` carries it.
`ReadOnlyIT` (subscription EXPIRED via `MutableClock`):
- `GET` contacts, inbox chats, CRM deals → 200.
- `POST` a contact, `PUT` a workspace name, `DELETE` a team, publish a chatbot → 402 `SUBSCRIPTION_EXPIRED`.
- `POST` contact search → 200; CRM export → 200; mark a chat read → 200; change own password → 200.
- The same user in `GRACE` (clock one day after `termEnd`) can `POST` a contact.
- A platform user's writes are never refused.
- Sign-in still works for an EXPIRED customer's user.

- [ ] **Step 2: Run and see them fail.**

Run: `./mvnw -q -pl omnichannel-app -am test -Dtest='ReadOnlyIT,ReadOnlySafeHandlersTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** the annotation, the interceptor, the config, and annotate the handlers.

- [ ] **Step 4: Run; expected PASS.**

- [ ] **Step 5: Commit** `feat(billing): read-only mode after the term ends`.

### Task 12: Documentation

**Files:**
- Modify: `README.md` (a "Subscription plans" section: concepts, the contract table, error codes, the `customersWithoutPlan` setting), `docs/superpowers/known-limits.md` (new section "Subscription plans: known limits"), `docs/superpowers/project-rules.md` section 3 (where the code is)

- [ ] **Step 1: Write the three edits.** Known limits to record: count-then-create can exceed a cap by one under simultaneous requests; the entitlement cache is per node (30 s bound on a second node); a cap lowered below use keeps what exists; the per-brand CRM switch is ignored for a customer with a plan; phase 1 assigns plans without payment (`planPaid` 0, no credit later); the two Super Admin bridge endpoints are outside every gate; a customer's test runs of chatbots and automations are gated like real ones.

- [ ] **Step 2: Commit** `docs: subscription plans phase 1`.

### Task 13: Whole-branch verification

- [ ] **Step 1: Run the full suite once.**

Run: `./mvnw -q verify > /tmp/claude-501/subscriptions-verify.log 2>&1; tail -40 /tmp/claude-501/subscriptions-verify.log`
Expected: BUILD SUCCESS. A failure that passes when its test is run alone is reported by name as flaky, not hidden.

- [ ] **Step 2: Confirm nothing leaked.** `git status` clean; `grep -rn "Instant.now()" omnichannel-*/src/main/java/me/nageshk/omnichannel/*/billing omnichannel-security/src/main/java/me/nageshk/omnichannel/security/entitlement` prints nothing.

- [ ] **Step 3: Stop.** Do not merge, push or deploy. Report: tasks done, rulings, deferred minors, and that the console plan is next.
