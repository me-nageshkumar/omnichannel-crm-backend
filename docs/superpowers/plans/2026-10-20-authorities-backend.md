# Authority Management (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Authorities live in a Mongo `authorities` collection that the Super Admin manages through
an API. The server reads its own `@PreAuthorize` checks so it can report which codes still need
creating and which created ones no code checks.

**Architecture:**
- A cached catalogue (`AuthorityCatalog`, built like `RoleCache`) replaces every
  `Authority.fromCode` lookup.
- The enum stays as the seed list and as constants for the built-ins.
- A startup scanner of `@PreAuthorize` expressions feeds a computed status.
- `AuthorityService` and `AuthorityController` carry the console API, with an audit trail in
  `authority_changes`.

**Tech Stack:** Spring Boot 3.5.6, Java 21, Spring Security 6 method security, Spring Data MongoDB
(embedded flapdoodle in tests), JUnit 5, AssertJ, TestRestTemplate.

**Spec:** `docs/superpowers/specs/2026-10-20-authority-management-design.md`. It builds on
`docs/superpowers/specs/2026-10-20-customers-workspaces-and-roles-design.md`.

## Global Constraints

- Package `me.nageshk.omnichannel`. New classes go in `me.nageshk.omnichannel.access` unless named
  otherwise.
- Collections are `authorities` and `authority_changes`. The `system_state` document
  `known-authorities` is deleted by the seeder.
- Code pattern: `^[a-z0-9]+(-[a-z0-9]+)*:[a-z0-9]+(-[a-z0-9]+)*$`, at most 64 characters.
- Group pattern: `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 40 characters.
- Description: trimmed, 1–200 characters.
- Errors use the existing `{code, message}` shape. New error code: `AUTHORITY_IN_USE` (409), which
  also carries `roles: [names]`.
  - Reused: `VALIDATION` (400, `fields`), `DUPLICATE` (409, `field`), `FORBIDDEN` (403),
    `NOT_FOUND` (404), `CONFLICT` (409, optimistic lock).
- Every `/api/authorities*` endpoint is `@PreAuthorize("hasAuthority('SUPER_ADMIN')")`.
- Authority edits apply on the holder's next request. No restart, no re-login.
- Match the surrounding style:
  - plain getters/setters, no Lombok
  - `record`s for API views
  - one-line Javadoc on each class
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Test command for the whole suite: `./mvnw test`.

## Review Focus

1. **A stale catalogue right after a create.** The Super Admin creates `order:refund`, then
   straight away saves a role that holds it. That must not fail with "unknown", so create must
   evict the catalogue before returning. (Task 5 test
   `roleSaveRightAfterCreateAcceptsTheNewCode`.)
2. **Colons in the URL.** `PUT /api/authorities/customer:view` and the encoded
   `/api/authorities/customer%3Aview` must both reach the controller with the code intact. Spring's
   firewall must not reject them. (Task 5 test `colonInPathReachesController`.)
3. **Migrating a database whose roles were already edited.** A built-in authority that the Super
   Admin removed from a role, and which is listed in `known-authorities`, must not come back. (Task 2
   test `migrationKeepsRemovalsAndDropsKnownAuthorities`.)
4. **Proxied controllers.** Method security puts CGLIB proxies around beans. The scanner must read
   annotations on the target class, or every code would show as Needs creating. (Task 4 test
   `scanFindsRealControllerChecks`.)
5. **The Super Admin's own access after a create or delete.** A new PLATFORM authority shows up in
   `/api/users/me` on the next request. A deleted one disappears from it. (Task 6 test
   `superAdminGainsAndLosesPlatformAuthority`.)

---

### Task 1: Authority documents and the cached catalogue

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityDefinition.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityDefinitionRepository.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityCatalog.java`
- Modify: `src/main/java/me/nageshk/omnichannel/access/Authority.java` (add `group()`)
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityCatalogTest.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityTest.java`

**Interfaces:**
- Produces:
  - `@Document("authorities") class AuthorityDefinition`, with fields:
    - `@Id String code`
    - `String group`
    - `RoleLevel level`
    - `String description`
    - `boolean builtIn`
    - `Instant createdAt`, `String createdBy`, `Instant updatedAt`, `String updatedBy`
    - `@Version Long version`

    Getters and setters as in `Role`.
  - `interface AuthorityDefinitionRepository extends MongoRepository<AuthorityDefinition, String>`
  - `@Component class AuthorityCatalog`:
    - Constructor `AuthorityCatalog(AuthorityDefinitionRepository, RoleCache)`.
    - `Optional<AuthorityDefinition> byCode(String code)`. Null-safe.
    - `Optional<RoleLevel> levelOf(String code)`
    - `Collection<AuthorityDefinition> all()`
    - `void evictAll()`. Also calls `roleCache.evictAll()`.
  - `Authority.group()` returns the code's part before `:`.

- [ ] **Step 1: Write the failing tests**

`AuthorityCatalogTest`, a unit test with a mocked repository and a real `RoleCache` over a mocked
`RoleRepository`, in the style of `RoleCacheTest`:
- `loadsOnceUntilEvicted`: two `byCode("a:b")` calls → `findAll()` called once. After `evictAll()`,
  the next call → `findAll()` called twice.
- `levelOfUnknownIsEmpty`: `levelOf("nope:x")` is empty, and `levelOf(null)` is empty.
- `evictAlsoEvictsRoles`: after `catalog.evictAll()`, `roleCache.byId(..)` triggers a fresh
  `roles.findAll()`.
- `loadOverlappingEvictIsNotCached`: copy the shape of the generation test in `RoleCacheTest`.

`AuthorityTest`: add `groupIsPrefix`, asserting that `Authority.COMPANY_USER_MANAGE.group()` is
`"company-user"` and every `values()` group matches the group pattern.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=AuthorityCatalogTest,AuthorityTest`
Expected: compilation fails, because `AuthorityCatalog` and `group()` don't exist.

- [ ] **Step 3: Implement**

Build `AuthorityCatalog` with the same snapshot and generation pattern as `RoleCache`. The snapshot
is `Map<String, AuthorityDefinition>`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest=AuthorityCatalogTest,AuthorityTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/access src/test/java/me/nageshk/omnichannel/access
git commit -m "feat(access): authority documents and cached catalogue"
```

---

### Task 2: Seed authority documents and migrate known-authorities

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/access/RoleSeeder.java`
- Delete: `src/main/java/me/nageshk/omnichannel/access/KnownAuthorities.java`,
  `KnownAuthoritiesRepository.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/RoleSeederIT.java`

**Interfaces:**
- Consumes: `AuthorityDefinitionRepository`, `AuthorityCatalog.evictAll()` (Task 1).
- Produces: after startup, every enum entry has an `authorities` document with `builtIn = true`.
  - Seeded documents have `createdBy = "system"`.
  - `system_state/known-authorities` no longer exists.

- [ ] **Step 1: Write the failing tests** in `RoleSeederIT`. Replace the `KnownAuthorities`-based
  tests; keep the role-seeding ones. The new tests:
  - `freshDatabaseCreatesDocumentsAndGivesDefaults`: clear `roles`, `authorities` and
    `system_state`, then run `seeder.seed()`.
    - 11 documents exist, all `builtIn`.
    - `customer:view` has group `customer`, level PLATFORM and the enum's description.
    - `ACCOUNT_MANAGER` holds `customer:view`.
  - `migrationKeepsRemovalsAndDropsKnownAuthorities`:
    - Seed roles.
    - Remove `user:view` from `TEAM_LEAD`.
    - Clear `authorities`.
    - Insert into `system_state`, using `MongoTemplate`, the document `{_id: "known-authorities",
      codes: [all 11 codes]}`.
    - Run `seed()`.
    - Expect: 11 documents. `TEAM_LEAD` still lacks `user:view`. The `system_state` document with
      id `known-authorities` is gone.
  - `secondStartChangesNothing`: seed. Edit `customer:view`'s description and group to `"x"` and
    `"y"` in the repository. Seed again. The description and group are still `"x"` and `"y"`.
  - `builtInDriftIsCorrected`: seed. Set `customer:view` to level `BRAND` and `builtIn = false`.
    Seed again. It is back to level `PLATFORM` with `builtIn = true`.
  - `removedDefaultStaysRemovedAfterMigration`: seed. Remove `customer:view` from
    `ACCOUNT_MANAGER`. Seed again. It is still removed.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=RoleSeederIT`
Expected: FAIL, because no `authorities` documents exist and `known-authorities` remains.

- [ ] **Step 3: Implement.** In `RoleSeeder.seed()`:
  - Read the legacy codes with `mongo.findById("known-authorities", Document.class,
    "system_state")`. Use the `codes` list from it, or an empty set if it isn't there.
  - For each `Authority`:
    - **No document:** insert one from the enum. If the code isn't in the legacy set, add it to its
      `defaultRoles`.
    - **Document exists:** force `builtIn = true` and the enum's `level`, and save only if either
      changed.
  - Delete the legacy `system_state` document.
  - In `finally`, call `catalog.evictAll()`, which also evicts roles.
  - Update the class Javadoc: the existence of a document is what "seen" now means.

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest=RoleSeederIT`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: all green. No code reads the catalogue yet, so nothing else changes.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/me/nageshk/omnichannel/access src/test/java/me/nageshk/omnichannel/access
git commit -m "feat(access): seed authority documents; retire known-authorities"
```

---

### Task 3: Resolve authority levels from the catalogue

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/access/AccessService.java` (`codes`,
  `globalAuthorities`)
- Modify: `src/main/java/me/nageshk/omnichannel/access/AssignmentPolicy.java`
- Modify: `src/main/java/me/nageshk/omnichannel/access/RoleService.java` (`validateAuthorities`,
  `gives`)
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityLevels.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AssignmentPolicyTest.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AccessServiceIT.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/RolesApiIT.java`

**Interfaces:**
- Consumes: `AuthorityCatalog.levelOf`, `all()` (Task 1). Documents exist after the seeder runs
  (Task 2).
- Produces:
  - `@FunctionalInterface interface AuthorityLevels { Optional<RoleLevel> levelOf(String code); }`
  - `AuthorityLevels.BUILT_IN`, a constant that looks codes up in the enum. Tests only.
  - `AuthorityCatalog implements AuthorityLevels`.
  - `AssignmentPolicy(AuthorityLevels levels)` constructor. Spring injects the catalogue.
  - `effective(Role)` becomes an instance method.
  - `RoleService` uses the injected policy's `effective`.

- [ ] **Step 1: Write the failing tests**
  - `AssignmentPolicyTest`: construct with `new AssignmentPolicy(AuthorityLevels.BUILT_IN)`. Add
    `effectiveUsesTheGivenLevels`:
    - Use a lambda `AuthorityLevels` mapping `x:y` → BRAND.
    - A BRAND role holding `x:y` and `nope:z` has `effective` `{x:y}`.
  - `AccessServiceIT`: add `superAdminHoldsConsoleCreatedPlatformAuthority`:
    - Save an `AuthorityDefinition` `report:export` at PLATFORM, then `catalog.evictAll()`.
    - The Super Admin's global authorities contain `report:export`.
  - `AccessServiceIT`: add `brandRoleWithConsoleCreatedCodePassesHas`:
    - Save `order:refund` at BRAND, evict, and add it to `WORKSPACE_MANAGER`.
    - `access.has(workspaceId, "order:refund")` is `true` for a Workspace Manager in that
      workspace.
  - `RolesApiIT`: add `roleSaveWithUncreatedCodeIsRejected`. A POST to `/api/roles` with
    `authorities: ["ghost:code"]` returns 400 `VALIDATION` with `fields.authorities` containing
    `ghost:code`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=AssignmentPolicyTest,AccessServiceIT,RolesApiIT`
Expected:
- compilation fails on the new constructor and `AuthorityLevels`
- once those exist, the two `AccessServiceIT` tests fail

- [ ] **Step 3: Implement**
  - Replace every `Authority.fromCode(...)` level lookup with `levels.levelOf(...)` or
    `catalog.levelOf(...)`.
  - The Super Admin's set is `catalog.all()` filtered to PLATFORM, plus `SUPER_ADMIN`.
  - The constants in `AssignmentPolicy` that read `Authority.X.code()` stay.

- [ ] **Step 4: Run them to verify they pass, then run the full suite**

Run: `./mvnw test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/me/nageshk/omnichannel/access src/test/java/me/nageshk/omnichannel/access
git commit -m "refactor(access): resolve authority levels from the stored catalogue"
```

---

### Task 4: Read the `@PreAuthorize` checks

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityCheckScanner.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityCheckScannerTest.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityCheckScannerIT.java`
- Create (test only): `src/test/java/me/nageshk/omnichannel/access/ProbeController.java`

**Interfaces:**
- Produces:
  - `enum CheckKind { GLOBAL, BRAND }`. GLOBAL is `hasAuthority`/`hasAnyAuthority`; BRAND is
    `@access.has`.
  - `record FoundCheck(String code, Set<CheckKind> kinds, List<String> locations)`
  - `@Component class AuthorityCheckScanner`:
    - Constructor `AuthorityCheckScanner(ApplicationContext)`.
    - `Map<String, FoundCheck> found()`. Computed on first call and memoised. Codes are sorted
      (`TreeMap`).
    - `static Map<String, Set<CheckKind>> parse(String expression)`. Pure, for unit tests.

- [ ] **Step 1: Write the failing tests**

`AuthorityCheckScannerTest` (unit, `parse` only):
- `hasAuthority('customer:view')` → `{customer:view=[GLOBAL]}`.
- `hasAnyAuthority('a:b', 'c-d:e')` → both, GLOBAL.
- `@access.has(#workspaceId, 'user:manage')` → `{user:manage=[BRAND]}`.
- `hasAuthority('customer:manage') or hasAuthority('customer:create')` → both.
- `hasAuthority('SUPER_ADMIN')` → empty map.
- `@access.has(#w, 'x:y') or hasAuthority('x:y')` → `{x:y=[GLOBAL, BRAND]}`.

`ProbeController`, under the test sources so it is picked up only in tests:
- `@RestController @RequestMapping("/api/test-probe")`.
- One `GET` method `probe()` with `@PreAuthorize("hasAuthority('test:probe')")` that returns
  `"ok"`.
- A class-level `@PreAuthorize("isAuthenticated()")`.

`AuthorityCheckScannerIT` (`@SpringBootTest`):
- `scanFindsRealControllerChecks`:
  - `found()` contains `customer:view` with kind GLOBAL and a location starting with
    `CustomerController.`.
  - It contains `user:manage` with kind BRAND.
  - It contains `test:probe` with location `ProbeController.probe`.
- `scanIgnoresMarkerAndUnformattedStrings`: `found()` has no `SUPER_ADMIN` key, and every key
  matches the code pattern.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=AuthorityCheckScannerTest,AuthorityCheckScannerIT`
Expected: compilation fails.

- [ ] **Step 3: Implement**
  - `parse`: one regex per form over the expression string. Collect the quoted strings in
    `hasAuthority(...)` and `hasAnyAuthority(...)`, and the last quoted argument of
    `@access.has(...)`. Keep only strings matching the code pattern.
  - `found()`:
    - Iterate `context.getBeanDefinitionNames()`.
    - Resolve each with `AopUtils.getTargetClass(context.getBean(name))`, or better
      `context.getType(name)` followed by `ClassUtils.getUserClass`, to avoid creating lazy beans.
    - Keep classes whose package starts with `me.nageshk.omnichannel`.
    - Read `AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class)` on the class and
      on each declared method.
    - Locations are `SimpleName.method`, or `SimpleName` for a class-level annotation.

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest=AuthorityCheckScannerTest,AuthorityCheckScannerIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/access src/test/java/me/nageshk/omnichannel/access
git commit -m "feat(access): read authority codes from @PreAuthorize checks"
```

---

### Task 5: Authority API: list, needs-creating, create, edit, delete, history

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityStatus.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityChange.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityChangeRepository.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityService.java`
- Create: `src/main/java/me/nageshk/omnichannel/access/AuthorityController.java`
- Create: `src/main/java/me/nageshk/omnichannel/api/AuthorityInUseException.java`
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java`
- Modify: `src/main/java/me/nageshk/omnichannel/access/RoleController.java`. Remove `GET
  /authorities` and `AuthorityView`; the new controller owns that path.
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthoritiesApiIT.java`
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityServiceTest.java`

**Interfaces:**
- Consumes: the catalogue (Task 1), the seeded documents (Task 2), `RoleCache` and `RoleRepository`
  for "roles holding it", and `AuthorityCheckScanner.found()` (Task 4).
- Produces:
  - **`enum AuthorityStatus { ACTIVE, NOT_CHECKED, WRONG_LEVEL }`**, and
    `static AuthorityStatus statusOf(AuthorityDefinition d, FoundCheck found)`. `found` may be
    null. Rules, in order:
    1. `builtIn` → ACTIVE.
    2. `found == null` → NOT_CHECKED.
    3. BRAND and kinds lacks BRAND → WRONG_LEVEL.
    4. PLATFORM and kinds lacks GLOBAL → WRONG_LEVEL.
    5. COMPANY → ACTIVE whatever the kinds.
    6. Otherwise → ACTIVE.
  - **`@Document("authority_changes") class AuthorityChange`**, with fields:
    - `id`
    - `@Indexed code`
    - `action` (`CREATED`, `UPDATED` or `DELETED`)
    - `groupBefore`, `groupAfter`, `levelBefore`, `levelAfter`, `descriptionBefore`,
      `descriptionAfter`
    - `changedBy`, `at`
  - **`AuthorityInUseException(String message, List<String> roles)`**. The handler replies 409
    with `{code: "AUTHORITY_IN_USE", message, roles}`.
  - **API views:**
    - `AuthorityView(code, group, level, description, builtIn, status, checkedIn, roles,
      updatedAt, updatedBy)`
    - `NeedsCreatingView(code, suggestedLevel, suggestedGroup, checkedIn)`. `suggestedLevel` is
      BRAND when kinds is only BRAND, otherwise null.
    - `ChangeView(code, action, groupBefore, groupAfter, levelBefore, levelAfter,
      descriptionBefore, descriptionAfter, changedBy, at)`
  - **Endpoints:**
    - `GET /api/authorities`: sorted by group, then code.
    - `GET /api/authorities/needs-creating`
    - `POST /api/authorities` with `{code, group, level, description}` → 201.
    - `PUT /api/authorities/{code}` with `{group, level, description}`
    - `DELETE /api/authorities/{code}` → 204.
    - `GET /api/authorities/{code}/history`: newest first.

- [ ] **Step 1: Write the failing tests**

`AuthorityServiceTest`: unit tests for `statusOf`, one per rule above, including a COMPANY code
checked only with BRAND → ACTIVE.

`AuthoritiesApiIT`. Set up as in `RolesApiIT`: a Super Admin `root`, plus an Account Manager
`am` for the 403 test.
- `listShowsBuiltInsGroupedAndActive`:
  - GET returns 11 or more entries. The `customer:*` entries come together, sorted.
  - `customer:view` has status ACTIVE, `builtIn` true and `roles` containing `ACCOUNT_MANAGER`.
- `needsCreatingListsProbe`: `test:probe` (from Task 4's `ProbeController`) appears with
  `suggestedLevel` null, `suggestedGroup` `test` and `checkedIn` `["ProbeController.probe"]`.
- `createThenListShowsActive`:
  - POST `test:probe` / `test` / PLATFORM / "Probe" → 201.
  - It is no longer in needs-creating, and the list shows it ACTIVE.
- `createUncheckedCodeIsNotChecked`: POST `order:refnd` → 201, status NOT_CHECKED.
- `createBrandCodeCheckedGloballyIsWrongLevel`: POST `test:probe` at BRAND → status WRONG_LEVEL.
- `createDuplicateIs409`: POST `customer:view` → 409 `DUPLICATE`, `field` `code`.
- `createValidation`: bad code `Customer:View`, bad group `Cust omer`, and a blank description →
  400 `VALIDATION` with `fields` for `code`, `group` and `description`.
- `editDescriptionAndGroupOfBuiltIn`: PUT `customer:view` with a new group and description, same
  level → 200, and it reads back.
- `builtInLevelChangeIsForbidden`: PUT `customer:view` with level COMPANY → 403 `FORBIDDEN`.
- `levelChangeInUseIs409`:
  - Create `order:refund` at BRAND and add it to the `AGENT` role via the roles API.
  - PUT with level COMPANY → 409 `AUTHORITY_IN_USE`, `roles` `["AGENT"]`.
- `deleteInUseIs409AndBuiltInIs403`:
  - DELETE `order:refund` while `AGENT` holds it → 409, `roles` `["AGENT"]`.
  - DELETE `customer:view` → 403.
- `deleteUnusedRemovesIt`: create `order:x` and DELETE it → 204. GET list lacks it, and the history
  shows CREATED then DELETED.
- `historyRecordsUpdate`: after a PUT, the history's first entry is UPDATED, with `groupBefore`
  and `groupAfter` and `changedBy` `root`.
- `unknownCodeIs404`: PUT or DELETE `nope:x` → 404.
- `notSuperAdminIs403`: `am` GET `/api/authorities` → 403.
- `colonInPathReachesController`: PUT to `/api/authorities/customer:view` and to
  `/api/authorities/customer%3Aview` both → 200 for a description change.
  - Build the encoded URI with `URI.create(...)` so `TestRestTemplate` does not re-encode it.
  - If the firewall rejects either form, note it in the report. The spec's fallback is a query
    parameter.
- `roleSaveRightAfterCreateAcceptsTheNewCode`: POST `order:new` at BRAND, then immediately PUT the
  `AGENT` role adding `order:new` → 200.
- `staleVersionIs409Conflict`:
  - Load `customer:view` through the repository, then change it through the API.
  - Save the stale copy through `AuthorityService.update`. Expect a `CONFLICT` reply, using the
    same mechanism the user `@Version` handling uses.
  - If that path can't be reached through the API, assert the service throws
    `OptimisticLockingFailureException` and that the existing handler maps it to 409.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=AuthorityServiceTest,AuthoritiesApiIT`
Expected: compilation fails; then the API tests fail.

- [ ] **Step 3: Implement**
  - **`AuthorityService`:**
    - Validates input with the Global Constraints patterns and throws `ValidationException` with
      a map of every bad field.
    - Trims the description.
    - "Roles holding it" means the `RoleCache.all()` names whose `authorities` contain the code,
      sorted.
    - Each write saves, writes an `AuthorityChange`, and calls `catalog.evictAll()` before
      returning.
  - **`AuthorityController`:** `@PreAuthorize("hasAuthority('SUPER_ADMIN')")` at class level. For
    the console's typeahead, a missing code on PUT, DELETE or history → `NotFoundException`.

- [ ] **Step 4: Run them to verify they pass, then run the full suite**

Run: `./mvnw test`
Expected: all green. Fix any `RolesApiIT` test that read the old `/api/authorities` shape. The new
shape is a superset, so most should pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/me/nageshk/omnichannel src/test/java/me/nageshk/omnichannel
git commit -m "feat(access): Super Admin authority API with status, rules and history"
```

---

### Task 6: End-to-end probe, Super Admin access, README

**Files:**
- Test: `src/test/java/me/nageshk/omnichannel/access/AuthorityLifecycleIT.java`
- Modify: `README.md` (the roles and authorities section)
- Modify: `docs/superpowers/specs/2026-10-20-customers-workspaces-and-roles-design.md`. Add one
  line under "## Authorities" pointing to the authority-management spec as superseding "never
  created from the console".

**Interfaces:**
- Consumes: everything above, plus `ProbeController` (Task 4).

- [ ] **Step 1: Write the tests**
  - `probeIsClosedUntilCreatedAndGranted`:
    - Create a platform user `pu` with a console-created PLATFORM role `PROBER` that has no
      authorities.
    - `GET /api/test-probe` as `pu` → 403.
    - Create `test:probe` (PLATFORM) as root → still 403.
    - Add `test:probe` to `PROBER` → 200 `"ok"` on `pu`'s next request, with no new token.
  - `superAdminGainsAndLosesPlatformAuthority`:
    - Create `report:export` at PLATFORM → root's `/api/users/me` `authorities` contain it.
    - DELETE it → the next `/me` lacks it.

- [ ] **Step 2: Run them**

Run: `./mvnw test -Dtest=AuthorityLifecycleIT`
Expected: PASS. If either fails, the bug is in Tasks 3–5. Use superpowers:systematic-debugging; do
not change the test.

- [ ] **Step 3: README.** Add a short subsection, "Authorities", covering:
  - the workflow: guard the method with `@PreAuthorize`, deploy, then create the code on the
    Authorities page and add it to roles
  - the four statuses
  - built-ins
  - the limit that codes built at runtime, and checks outside `@PreAuthorize`, are not found

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add -A README.md docs src/test
git commit -m "test(access): authority lifecycle end to end; docs"
```
