# Customers, Workspaces and Roles — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Multi-tenant authorization for the backend: customers → workspaces → teams, roles stored in Mongo as bundles of code-defined authorities, the strict-subset assignment rule, first-login password change, and the management APIs for the first slice.

**Architecture:** A new `access` package owns the authority catalogue, roles, the role cache, effective-authority resolution, the `@access` method-security bean and the pure `AssignmentPolicy`. A new `tenant` package owns customers, workspaces, teams and their APIs. `user` gains tenancy fields and the workspace-users / platform-users APIs. Method security (`@EnableMethodSecurity`) guards every new endpoint; brand endpoints take `{workspaceId}` in the path and answer 404 for workspaces the caller cannot reach.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Security 6 (method security), Spring Data MongoDB, jjwt 0.12.6, JUnit 5 + MockMvc + embedded Mongo (flapdoodle).

**Spec:** `docs/superpowers/specs/2026-10-20-customers-workspaces-and-roles-design.md`. Builds on `2026-10-19-users-and-two-factor-login-design.md` and `2026-10-19-admin-console-design.md`.

## Global Constraints

- Package root `me.nageshk.omnichannel`; no Lombok; plain getters/setters and records, as in existing code.
- Extension contract unchanged: `POST /api/auth/login` / `verify-otp` shapes and `{ error }` login errors; the device user's REST calls and WebSocket `auth` keep working.
- Non-login API errors use `ApiExceptionHandler`'s `{ code, message }`, plus `fields` (VALIDATION) or `field` (DUPLICATE).
- 404 (`NOT_FOUND`) for a customer/workspace/team/user the caller may not see; 403 (`FORBIDDEN`) for one they see but may not change.
- No response contains a password hash, `tokenVersion` or TOTP secret. Temporary passwords appear only in the create/reset response.
- Usernames are lower-cased on every write and on login.
- Passwords set by anyone (temporary or new): at least 10 characters. (Ruling: the spec fixes 10 for new passwords; applying the same floor to temporary ones keeps one rule.)
- `/api/users/me` changes shape (`role` becomes an object). The current console's SUPER_ADMIN gate breaks until the frontend plan (`2026-10-20-roles-frontend.md`) runs; do not deploy one without the other.
- Every task ends with `./mvnw test` → `Failures: 0, Errors: 0`.

## Review Focus

1. A user of customer A requesting any `/api/workspaces/{id}/…` of customer B, or a workspace of their own customer they have no role in, gets 404 — Task 10 matrix.
2. Whole-login actions (reset password, edit details, deactivate) by a Workspace Manager on someone who also holds a role in a workspace the manager cannot outrank them in → 403 — Task 4 unit + Task 8 IT.
3. A role edit (authority removed) takes effect on the very next request of a user holding it, without re-login — Task 3.
4. A pre-change access token (has `role` claim, no change otherwise) still authenticates; a token for a user of a deactivated customer is refused — Task 3.
5. Customer creation failing half-way (duplicate owner username) leaves no customer, workspace or team behind — Task 7.

---

### Task 1: Authority catalogue, roles and the role seeder

**Files:**
- Create: `access/RoleLevel.java`, `access/Authority.java`, `access/Role.java`, `access/RoleRepository.java`, `access/BuiltInRoles.java`, `access/KnownAuthorities.java`, `access/KnownAuthoritiesRepository.java`, `access/RoleSeeder.java`
- Test: `access/AuthorityTest.java`, `access/RoleSeederIT.java`

(Paths under `src/main/java/me/nageshk/omnichannel/` and `src/test/java/me/nageshk/omnichannel/`.)

**Interfaces:**
- Produces:
  - `enum RoleLevel { PLATFORM, COMPANY, BRAND; boolean mayHold(RoleLevel authorityLevel) }` — PLATFORM holds PLATFORM; COMPANY holds COMPANY and BRAND; BRAND holds BRAND.
  - `enum Authority` with `String code()` (e.g. `"customer:create"`), `RoleLevel level()`, `String description()`, `Set<String> defaultRoles()`, `static Optional<Authority> fromCode(String)`. Entries and defaults exactly as the spec's catalogue table (11 entries).
  - `BuiltInRoles`: constants `SUPER_ADMIN`, `ACCOUNT_MANAGER`, `WHATSAPP_DEVICE`, `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`, `TEAM_LEAD`, `AGENT` and `static RoleLevel levelOf(String name)` (SUPER_ADMIN, ACCOUNT_MANAGER, WHATSAPP_DEVICE → PLATFORM; CUSTOMER_ADMIN → COMPANY; rest → BRAND).
  - `@Document("roles") Role`: `id`, `@Indexed(unique) name`, `description`, `level`, `Set<String> authorities`, `builtIn`, `creationTime`, `lastUpdateTime`.
  - `RoleRepository`: `Optional<Role> findByName(String)`, `boolean existsByName(String)`.
  - `@Document("system_state") KnownAuthorities` with fixed id `"known-authorities"` and `Set<String> codes`.
  - `RoleSeeder implements ApplicationRunner`, `@Order(1)`, active in all profiles; public `void seed()`.

- [ ] **Step 1: Write `AuthorityTest`**: `codesAreUnique`, `everyCodeMatchesThingColonAction` (`^[a-z-]+:[a-z-]+$`), `defaultRolesAreBuiltInAndMayHoldTheAuthority` (each default role's `BuiltInRoles.levelOf(r).mayHold(a.level())`), `superAdminIsNeverADefault`, `customerAdminDefaultsAreAStrictSupersetOfWorkspaceManager`.
- [ ] **Step 2: Write `RoleSeederIT`** (`@SpringBootTest`, clears `roles` and `system_state`, calls `seed()`):
  - `seedsSevenBuiltInRoles` — names as `BuiltInRoles`, all `builtIn`, levels per `levelOf`, `SUPER_ADMIN.authorities` empty.
  - `builtInRolesGetTheirDefaultAuthorities` — e.g. `WORKSPACE_MANAGER` = `{workspace:settings, user:view, user:manage, team:manage}`, `AGENT` = `{}`.
  - `seedIsIdempotent` — two runs, still 7 roles, same ids.
  - `removedAuthorityIsNotReAdded` — remove `user:view` from TEAM_LEAD, `seed()`, still absent.
  - `newAuthorityIsAddedToDefaults` — remove `team:manage` from `KnownAuthorities.codes` and from WORKSPACE_MANAGER, `seed()`, WORKSPACE_MANAGER has it again.
  - `customRolesAreUntouched` — a custom BRAND role with `{user:view}` survives `seed()` unchanged.
- [ ] **Step 3: Run** `./mvnw test -Dtest='AuthorityTest,RoleSeederIT'` — Expected: compilation FAILURE.
- [ ] **Step 4: Implement.** `seed()`: create missing built-in roles (empty authorities); then for every catalogue code not in `KnownAuthorities.codes`, add it to each existing built-in default role; then save `codes` = all catalogue codes. Never removes anything.
- [ ] **Step 5: Run** the two tests — PASS; `./mvnw test` — green.
- [ ] **Step 6: Commit** `feat(access): authority catalogue, roles collection and seeder`

### Task 2: Tenancy documents and the user model change

**Files:**
- Create: `tenant/Customer.java`, `tenant/CustomerRepository.java`, `tenant/Workspace.java`, `tenant/WorkspaceRepository.java`, `tenant/Team.java`, `tenant/TeamRepository.java`, `user/WorkspaceRole.java`, `user/UserRoleMigration.java`
- Delete: `user/UserRole.java`
- Modify: `user/User.java`, `user/UserRepository.java`, `user/UserSeeder.java`, `auth/AuthenticatedUser.java` (role → roleId for now), `auth/JwtService.java` (drop `role` claim), `auth/MongoUserDetailsService.java` (lower-case lookup), `user/UserService.java` (`CurrentUser.role` temporarily the role name string from the role document), `support/TestUsers.java`, and every test using `UserRole`.
- Test: `user/UserRoleMigrationIT.java`, `tenant/TenantRepositoriesIT.java`, updates to `UserSeederTest`, `JwtServiceTest`, `LoginIT`.

**Interfaces:**
- Consumes: `RoleRepository.findByName`, `BuiltInRoles` (Task 1).
- Produces:
  - `Customer`: `id`, `name`, `active`, `createdBy`, timestamps. `CustomerRepository`: `Page<Customer> findByNameContainingIgnoreCaseAndActive(String, boolean, Pageable)`, `Page<Customer> findByNameContainingIgnoreCase(String, Pageable)`.
  - `Workspace`: `id`, `customerId`, `name`, timestamps; compound unique index `(customerId, name)`. Repo: `List<Workspace> findByCustomerIdOrderByName(String)`, `Optional<Workspace> findByIdAndCustomerId(String, String)`.
  - `Team`: `id`, `customerId`, `workspaceId`, `name`, `List<String> memberIds`, timestamps; unique `(workspaceId, name)`. Repo: `List<Team> findByWorkspaceIdOrderByName(String)`, `Optional<Team> findByIdAndWorkspaceId(String, String)`.
  - `record WorkspaceRole(String workspaceId, String roleId)`.
  - `User` new fields: `customerId`, `roleId`, `List<WorkspaceRole> workspaceRoles` (never null), `mustChangePassword`. `role` removed.
  - `UserRepository`: `existsByRoleId(String)`, `long countByRoleIdOrWorkspaceRolesRoleId(String, String)`, `List<User> findByCustomerId(String)`, `List<User> findByCustomerIdAndRoleId(String, String)`, `List<User> findByWorkspaceRolesWorkspaceId(String)`, `List<User> findByCustomerIdIsNullOrderByName()`.
  - `TestUsers.create(String username, String rawPassword, String roleName, String totpSecret)` (platform user with that role), `createInCustomer(String customerId, String username, String companyRoleName /*nullable*/, List<WorkspaceRole>)`, `customer(String name)`, `workspace(String customerId, String name)`, `roleId(String name)`, `tokenFor(String username)` now creates a `WHATSAPP_DEVICE` user. `deleteAll()` also clears customers, workspaces, teams, `roles` and `system_state`, then calls `RoleSeeder.seed()` and `RoleCache.evictAll()` (once Task 3 adds it), so every test starts from default roles; tests look roles up by name, never by remembered id.
  - `UserRoleMigration implements ApplicationRunner`, `@Order(2)`, all profiles; public `void migrate()`.

- [ ] **Step 1: Write `UserRoleMigrationIT`**: insert raw documents through `MongoTemplate` with `role: "SUPER_ADMIN"` and `role: "USER"`, `migrate()`; assert `roleId` = SUPER_ADMIN / WHATSAPP_DEVICE role ids, field `role` gone, `workspaceRoles` = `[]`. `usernamesAreLowerCased` (`NageshK` → `nageshk`). `unknownRoleFailsWithClearMessage` (`role: "APP_ADMIN"` → `IllegalStateException` mentioning `APP_ADMIN`). `migrateIsIdempotent`.
- [ ] **Step 2: Write `TenantRepositoriesIT`**: `workspaceNameUniquePerCustomer` (same name, two customers OK; same customer → `DuplicateKeyException`), `teamNameUniquePerWorkspace`, `findByIdAndCustomerIdHidesOtherCustomersWorkspace`.
- [ ] **Step 3: Update existing tests** to the new `TestUsers` API (`"SUPER_ADMIN"`, `"WHATSAPP_DEVICE"` instead of the enum). `UserSeederTest`: super admin gets the SUPER_ADMIN `roleId`, device user WHATSAPP_DEVICE; `existsByRoleId` drives idempotence. `JwtServiceTest`: `accessTokenHasNoRoleClaim`. `LoginIT`: `usernameIsCaseInsensitive` (`NAGESHK` logs in).
- [ ] **Step 4: Run** `./mvnw test` — Expected: compilation FAILURE.
- [ ] **Step 5: Implement.** Migration uses `MongoTemplate` on raw documents (the enum is gone). `UserSeeder` gets `@Order(3)` so roles exist. `CurrentUser.role` stays a `String` (role name) in this task; Task 5 replaces the record.
- [ ] **Step 6: Run** `./mvnw test` — green (existing behaviour unchanged apart from the role claim and case-insensitive login).
- [ ] **Step 7: Commit** `feat(tenant): customers, workspaces, teams; users get roleId and workspaceRoles`

### Task 3: Effective authorities, method security and the `@access` bean

**Files:**
- Create: `access/RoleCache.java`, `access/AccessService.java` (bean name `access`), `api/NotFoundException.java`, `api/ForbiddenException.java`
- Modify: `auth/AuthenticatedUser.java`, `auth/AccessTokenAuthenticator.java`, `auth/MongoUserDetailsService.java`, `auth/login/PasswordAuthenticationProvider.java` (inactive customer → `account_inactive`), `config/SecurityConfig.java` (`@EnableMethodSecurity`), `api/ApiExceptionHandler.java` (404, 403, `AccessDeniedException` → 403 `FORBIDDEN`), `api/StatusController.java`, `MessagesController`, `RpcController`, `EventsController`, `ScriptsController` (class-level `@PreAuthorize("hasAuthority('whatsapp:bridge')")`), `ws/BridgeWebSocketHandler.java`
- Test: `access/AccessServiceIT.java`, `auth/BearerAuthIT.java`, `ws/BridgeWebSocketIT.java`, `api/ScriptsApiIT.java`, `api/MessagesApiIT.java`

**Interfaces:**
- Consumes: Task 1 and 2 types.
- Produces:
  - `RoleCache`: `Optional<Role> byId(String)`, `Optional<Role> byName(String)`, `void evictAll()`. Loads all roles once, reloads after `evictAll()`.
  - `AuthenticatedUser(User user, Set<String> grantedAuthorities)`; accessors `userId()`, `customerId()`, `roleId()`, `workspaceRoles()`, `superAdmin()`, `mustChangePassword()`, `hasAuthority(String)`. `getAuthorities()` = granted set as `SimpleGrantedAuthority`.
  - Granted set: Super Admin → every PLATFORM code + `"SUPER_ADMIN"`; platform user → its role's PLATFORM codes; customer user → its COMPANY role's COMPANY codes. BRAND codes are never granted globally.
  - `AccessService`:
    - `AuthenticatedUser load(User)` — builds the principal (used by authenticator and details service).
    - `Set<String> inWorkspace(AuthenticatedUser, String workspaceId)` — effective BRAND+COMPANY set per spec; throws `NotFoundException` if the workspace does not exist, is another customer's, or the user has neither a COMPANY role in its customer nor a role in it. Super Admin and platform staff: `NotFoundException`.
    - `boolean has(String workspaceId, String authority)` — for SpEL; reads the principal from `SecurityContextHolder`; `inWorkspace(...).contains(authority)` (so no access → 404 via exception, access without authority → false → 403).
    - `List<Workspace> workspacesOf(AuthenticatedUser)` — all of the customer's workspaces for a COMPANY-role holder, else those in `workspaceRoles`.
  - `AccessTokenAuthenticator` also rejects users whose customer is missing or inactive.
  - `ApiExceptionHandler`: `NotFoundException` → 404 `NOT_FOUND`; `ForbiddenException`, `AccessDeniedException` → 403 `FORBIDDEN`.

- [ ] **Step 1: Write `AccessServiceIT`**:
  - `customerAdminHasBrandAuthoritiesInEveryWorkspaceOfItsCustomer`.
  - `workspaceRoleGivesThatRolesAuthoritiesOnlyThere` — WM in W1 has `user:manage` in W1; W2 (same customer, no role) → `NotFoundException`.
  - `otherCustomersWorkspaceIsNotFound`.
  - `platformStaffHaveNoBrandAccess` — Super Admin and Account Manager → `NotFoundException`.
  - `roleEditAppliesAfterEvict` — remove `user:manage` from WORKSPACE_MANAGER via repo + `evictAll()`; `inWorkspace` lacks it.
- [ ] **Step 2: Extend `BearerAuthIT`**: `customerAdminTokenIsForbiddenOnBridgeEndpoints` (`GET /api/status` → 403), `deviceTokenStillWorks` (200), `superAdminMayUseBridge` (200), `tokenRefusedAfterCustomerDeactivated` (401), `legacyTokenWithRoleClaimStillAccepted` (hand-built JWT with `role` claim → 200 on `/api/users/me`), `authorityRemovedFromRoleAppliesOnNextRequest` (Account Manager role loses `customer:view` in the repo + `evictAll()`; `AccessTokenAuthenticator.authenticate(sameToken).get().getAuthorities()` lacks it).
- [ ] **Step 3: Extend `BridgeWebSocketIT`**: `authWithCustomerUserTokenIsRefused` (same refusal frame as a stale token).
- [ ] **Step 4: Run** — FAIL (missing types / 200 where 403 expected).
- [ ] **Step 5: Implement.** Role edits from any code path must call `RoleCache.evictAll()`; tests evict explicitly.
- [ ] **Step 6: Run** `./mvnw test` — green.
- [ ] **Step 7: Commit** `feat(access): effective authorities, method security, bridge requires whatsapp:bridge`

### Task 4: The assignment policy (strict-subset rule)

**Files:**
- Create: `access/AssignmentPolicy.java`
- Test: `access/AssignmentPolicyTest.java` (plain unit test, no Spring)

**Interfaces:**
- Consumes: `Role`, `RoleLevel`, `AuthenticatedUser`, `User`, `WorkspaceRole`.
- Produces: a stateless component taking resolved inputs so it can be unit-tested:
  - `record Actor(boolean superAdmin, String customerId, Set<String> platformOrCompany, Map<String, Set<String>> perWorkspace)` — `perWorkspace` holds effective sets for every workspace the actor can access.
  - `record Target(String customerId, Role companyRole /*nullable*/, Map<String, Role> workspaceRoles)`.
  - `boolean canGiveBrandRole(Actor a, String workspaceId, Role role)` — rule 1 (`user:manage` in W) + rule 2 (strict subset), role.level == BRAND.
  - `boolean canGivePlatformRole(Actor a, Role role)` — `platform-user:manage` + strict subset of actor's set (Super Admin: any PLATFORM role except SUPER_ADMIN).
  - `boolean canGiveCompanyRole(Actor a, Role role)` — Super Admin, or `customer:manage`/`customer:create` held (platform staff); role.level == COMPANY.
  - `boolean canChangeInWorkspace(Actor a, Target t, String workspaceId)` — rule 3, one-workspace form.
  - `boolean canManageLogin(Actor a, Target t)` — rule 3, whole-login form: same customer; for every workspace in `t.workspaceRoles`, actor has `user:manage` there and the role is a strict subset; `t.companyRole == null` unless actor holds `customer:manage`. Super Admin: true.
  - `boolean canDeactivateLogin(Actor a, Target t)` — `canManageLogin` and (`company-user:manage` in `a.platformOrCompany` or, for a target with a company role, `customer:manage`).
  - `List<Role> assignableBrandRoles(Actor a, String workspaceId, Collection<Role> all)` and `assignablePlatformRoles(...)`, `assignableCompanyRoles(...)`.

- [ ] **Step 1: Write the tests**, building actors from the seeded default sets (copy the defaults from `Authority` in a `@BeforeEach`):
  - `customerAdminGivesWorkspaceManagerTeamLeadAgent`
  - `workspaceManagerGivesTeamLeadAndAgentButNotWorkspaceManager`
  - `teamLeadGivesNothing`
  - `teamLeadWithUserManageGivesAgentButNotTeamLead` (TEAM_LEAD + `user:manage` custom set)
  - `emptyRoleIsAssignableByAnyManager` (AGENT = `{}`)
  - `workspaceManagerCannotResetAnotherWorkspaceManager`
  - `workspaceManagerOfAOnlyCannotManageLoginOfAgentAlsoInB` (target Agent in A and B, actor WM in A only → false)
  - `workspaceManagerOfBothManagesThatAgent` → true
  - `workspaceManagerCannotManageCustomerAdmin`
  - `accountManagerManagesCustomerAdminLogin`
  - `otherCustomerIsNeverManageable`
  - `customPlatformRoleWithPlatformUserManageCannotGiveItsEqual`
  - `superAdminCanGiveAnyPlatformRoleButSuperAdmin`
  - `companyRoleOnlyFromPlatformStaff`
  - `customerAdminCannotDeactivateAnotherCustomerAdmin`, `accountManagerCanDeactivateCustomerAdmin`
- [ ] **Step 2: Run** `./mvnw test -Dtest=AssignmentPolicyTest` — FAIL (compilation).
- [ ] **Step 3: Implement.** "Strict subset": `actor.containsAll(role) && !role.containsAll(actor)`.
- [ ] **Step 4: Run** — PASS; `./mvnw test` — green.
- [ ] **Step 5: Commit** `feat(access): strict-subset assignment policy`

### Task 5: `/api/users/me` with authorities, and first-login password change

**Files:**
- Create: `auth/InitialPasswordController.java`, `user/PasswordPolicy.java`, `api/WeakPasswordException.java`, `api/InvalidTokenException.java`
- Modify: `user/UserService.java` (`CurrentUser`), `auth/JwtService.java` (`PASSWORD_CHANGE_PENDING`), `auth/login/LoginSuccessHandler.java`, `config/SecurityConfig.java` (permit `POST /api/auth/initial-password`), `api/ApiExceptionHandler.java`
- Test: `user/CurrentUserIT.java`, `auth/InitialPasswordIT.java`, `auth/JwtServiceTest.java`, `user/PasswordPolicyTest.java`

**Interfaces:**
- Consumes: `AccessService.workspacesOf`, `inWorkspace`, `RoleCache`.
- Produces:
  - `CurrentUser(String id, String name, String username, String email, String mobileNumber, boolean twoFactorEnabled, boolean superAdmin, CustomerRef customer, RoleRef role, Set<String> authorities, List<WorkspaceAccess> workspaces, boolean mustChangePassword)` with `record CustomerRef(String id, String name)`, `record RoleRef(String id, String name)`, `record WorkspaceAccess(String id, String name, RoleRef role, Set<String> authorities)`. `authorities` = granted set minus `"SUPER_ADMIN"`; `WorkspaceAccess.role` null when access comes from the COMPANY role.
  - `JwtService.issuePasswordChangeToken(String username)` (10 min, `authState = PASSWORD_CHANGE_PENDING`), `Optional<String> verifyPasswordChangeToken(String)`; `verifyPendingToken` and `verifyAccessToken` reject it.
  - `PasswordPolicy.check(String newPassword, String currentHash)` throws `WeakPasswordException` (< 10 chars, or matches current hash).
  - `POST /api/auth/initial-password` `{ newPassword }` with `Authorization: Bearer <passwordChangeToken>` → `200 { token, expiresAt }`; clears `mustChangePassword`, bumps `tokenVersion`, writes the new hash. Bad token → 401 `INVALID_TOKEN`; weak → 400 `WEAK_PASSWORD`.
  - `LoginSuccessHandler`: when the final step (password without 2FA, or OTP) succeeds and `mustChangePassword` → `{ passwordChangeRequired: true, passwordChangeToken }`.

- [ ] **Step 1: Rewrite `CurrentUserIT`** for the new shape: `superAdminShape` (`superAdmin` true, `customer` null, `workspaces` []), `customerAdminSeesEveryWorkspaceWithNullRole`, `workspaceManagerSeesOnlyItsWorkspaceWithRoleAndAuthorities`, `authoritiesReflectRoleEditWithoutNewToken`, keep `neverExposesSecrets`, `requiresToken`.
- [ ] **Step 2: Write `InitialPasswordIT`**: `loginReturnsChangeTokenWhenMustChange`, `otpStepReturnsChangeTokenWhenMustChangeAndTwoFactorOn`, `changeTokenIsNotAnAccessToken` (401 on `/api/users/me`), `initialPasswordIssuesAccessTokenAndClearsFlag`, `oldPasswordNoLongerWorks`, `shortPasswordIs400WeakPassword`, `sameAsTemporaryIs400`, `expiredOrAccessTokenIs401InvalidToken`, `deviceUserLoginUnchanged` (token + expiresAt only).
- [ ] **Step 3: `PasswordPolicyTest`**: 9 chars rejected, 10 accepted, same-as-current rejected.
- [ ] **Step 4: Run** — FAIL.
- [ ] **Step 5: Implement.**
- [ ] **Step 6: Run** `./mvnw test` — green.
- [ ] **Step 7: Commit** `feat(auth): /users/me authorities per workspace; first-login password change`

### Task 6: Roles API (Super Admin)

**Files:**
- Create: `access/RoleService.java`, `access/RoleController.java`, `access/RoleChange.java`, `access/RoleChangeRepository.java`, `api/DuplicateException.java`
- Modify: `api/ApiExceptionHandler.java` (DUPLICATE with `field`; VALIDATION adds `fields` map)
- Test: `access/RolesApiIT.java`

**Interfaces:**
- Consumes: `RoleCache.evictAll`, `AssignmentPolicy` (for preview), `Authority`, `UserRepository.countByRoleIdOrWorkspaceRolesRoleId`.
- Produces (all `@PreAuthorize("hasAuthority('SUPER_ADMIN')")`):
  - `GET /api/authorities` → `[{ code, level, description }]`.
  - `GET /api/roles` → `[{ id, name, description, level, authorities, builtIn, userCount }]` ordered by level then name.
  - `POST /api/roles` `{ name, description, level, authorities }` → 201 role. Name `^[A-Z][A-Z0-9_]{1,39}$`.
  - `PUT /api/roles/{id}` `{ name?, description, authorities }` → 200 role. Built-in: name/level fixed (name change → 400 VALIDATION). SUPER_ADMIN → 403.
  - `DELETE /api/roles/{id}` → 204; built-in → 403; held by anyone → 409 `ROLE_IN_USE`.
  - `POST /api/roles/{id}/preview` `{ authorities }` → `{ changes: [{ assigner, assigned, nowAssignable }] }` — over all pairs of roles of the same "giving" relation (BRAND assigner with `user:manage` → BRAND roles; PLATFORM → PLATFORM), using `AssignmentPolicy` strict-subset on role sets alone, before vs after.
  - `GET /api/roles/{id}/changes` → newest first.
  - `RoleChange`: `roleId`, `roleName`, `changedBy` (username), `at`, `action` (`CREATED|UPDATED|DELETED`), `added`, `removed`, `descriptionBefore`, `descriptionAfter`.

- [ ] **Step 1: Write `RolesApiIT`**: `listsAuthoritiesWithLevels`, `listsRolesWithUserCount`, `createsCustomBrandRole`, `rejectsAuthorityAboveLevel` (BRAND role with `customer:create` → 400), `rejectsUnknownAuthority` (400), `duplicateNameIs409`, `editsBuiltInAuthorities`, `cannotRenameBuiltIn`, `superAdminRoleIsNotEditable` (403), `cannotDeleteBuiltIn` (403), `cannotDeleteRoleInUse` (409 `ROLE_IN_USE`), `deletesUnusedCustomRole`, `everyChangeIsAudited` (create+update+delete → 3 entries with added/removed), `previewReportsTeamLeadLosingAgent` (give TEAM_LEAD `user:manage`, preview AGENT + `team:manage` → change `{ assigner: TEAM_LEAD, assigned: AGENT, nowAssignable: false }`), `previewDoesNotSave`, `nonSuperAdminIs403` (Account Manager).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Every write calls `RoleCache.evictAll()` after saving.
- [ ] **Step 4: Run** `./mvnw test` — green.
- [ ] **Step 5: Commit** `feat(access): roles API with level checks, audit and edit preview`

### Task 7: Platform users and customers APIs

**Files:**
- Create: `user/PlatformUserController.java`, `user/UserAdminService.java` (shared create/edit/reset/deactivate operations with policy checks), `tenant/CustomerService.java`, `tenant/CustomerController.java`, `user/TemporaryPasswordResponse.java`
- Test: `user/PlatformUsersApiIT.java`, `tenant/CustomersApiIT.java`

**Interfaces:**
- Consumes: `AssignmentPolicy`, `AccessService`, `PasswordPolicy`, `RoleCache`.
- Produces:
  - User view record `UserSummary(id, name, username, email, mobileNumber, active, role: RoleRef?, workspaceRoles: [{ workspaceId, workspaceName, role: RoleRef }], mustChangePassword)` — reused by Tasks 8.
  - Create bodies take `temporaryPassword` (≥ 10); responses that set a password return `{ user: UserSummary, temporaryPassword }`; reset returns `{ temporaryPassword }` (the value the caller sent) — Ruling: the caller types the temporary password, as the user chose in the original brainstorm ("temporary password" is a create-form field); the server does not generate one.
  - `/api/platform-users`: `GET` (platform users, excluding SUPER_ADMIN), `POST { name, username, email, mobileNumber, roleId, temporaryPassword }`, `PUT /{id} { name, email, mobileNumber, roleId }`, `POST /{id}/deactivate|reactivate` (deactivate bumps `tokenVersion`), `POST /{id}/reset-password { temporaryPassword }` (sets `mustChangePassword`, bumps `tokenVersion`), `GET /assignable-roles`. All `hasAuthority('platform-user:manage')` + `canGivePlatformRole`/`canManageLogin`.
  - `/api/customers`: `GET ?q=&active=&page=` (page size 50, `{ items, total, page }`), `GET /{id}` → `{ customer, workspaces, admins: [UserSummary] }`, `POST { name, workspaceName?, admin: { name, username, email, mobileNumber, temporaryPassword } }` → 201, `PUT /{id} { name }`, `POST /{id}/deactivate|reactivate`, `POST /{id}/admins { …admin, roleId? (default CUSTOMER_ADMIN) }`, `POST /{id}/admins/{userId}/reset-password|deactivate|reactivate`, `GET /assignable-roles` (COMPANY roles).
  - `CustomerService.create`: insert customer → workspace (name default = customer name) → team `General` → admin user; on any exception delete what was inserted, then rethrow.

- [ ] **Step 1: Write `PlatformUsersApiIT`**: `superAdminCreatesAccountManager` (201, temporary password echoed, `mustChangePassword` true), `accountManagerCannotListPlatformUsers` (403), `cannotGiveSuperAdminRole` (403), `usernameStoredLowerCaseAndUnique` (`AM1` then `am1` → 409 field `username`), `resetPasswordForcesChangeAndRevokesTokens` (old token 401), `deactivateRevokesTokens`, `shortTemporaryPasswordIs400`.
- [ ] **Step 2: Write `CustomersApiIT`**: `accountManagerCreatesCustomerWithWorkspaceTeamAndAdmin` (one workspace named as the customer unless given, team `General`, admin holds CUSTOMER_ADMIN, `customerId` set), `createIsAllOrNothing` (owner username already taken → 409, and counts of customers/workspaces/teams unchanged), `listIsPagedAndSearchable`, `detailShowsWorkspacesAndAdmins`, `deactivateLocksOutCustomerUsers` (admin's token → 401; login → 401 `account_inactive`), `reactivateRestoresLogin`, `addSecondCustomerAdmin`, `accountManagerResetsAdminPassword`, `accountManagerDeactivatesAdmin`, `customerAdminCannotUseCustomersApi` (403), `unknownCustomerIs404`.
- [ ] **Step 3: Run** — FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** `./mvnw test` — green.
- [ ] **Step 6: Commit** `feat(tenant): platform users and customers APIs`

### Task 8: Workspaces and workspace users APIs

**Files:**
- Create: `tenant/WorkspaceController.java`, `tenant/WorkspaceService.java`, `user/WorkspaceUserController.java`, `user/CompanyUserController.java`
- Modify: `user/UserAdminService.java`
- Test: `tenant/WorkspacesApiIT.java`, `user/WorkspaceUsersApiIT.java`

**Interfaces:**
- Consumes: `AccessService`, `AssignmentPolicy`, `UserAdminService`, `UserSummary`.
- Produces:
  - `GET /api/workspaces` → caller's `workspacesOf` (`[{ id, name }]`); `POST /api/workspaces { name }` (`hasAuthority('workspace:create')`, customer from principal; also creates team `General` — Ruling: mirrors the first workspace so every workspace starts with one team; cost if wrong: one extra team to rename); `PUT /api/workspaces/{workspaceId} { name }` (`@access.has(#workspaceId,'workspace:settings')`).
  - `/api/workspaces/{workspaceId}/users`:
    - `GET` (`user:view`) → `[UserSummary + allBrands: boolean + manageable: boolean]` — users with a role here plus the customer's COMPANY-role holders (`allBrands` true); `manageable` = `canManageLogin` for the caller.
    - `POST` (`user:manage`) either `{ name, username, email, mobileNumber, temporaryPassword, roleId }` (new user in caller's customer) or `{ userId, roleId }` (existing company user; refused with 400 `ALREADY_ALL_BRANDS` if they hold a COMPANY role, 409 `ALREADY_IN_WORKSPACE` if they have a role here).
    - `PUT /{userId}` `{ name, email, mobileNumber, roleId }` — details need `canManageLogin`; role change needs `canChangeInWorkspace` + `canGiveBrandRole`.
    - `DELETE /{userId}` — `canChangeInWorkspace`; refuses (400 `LAST_ACCESS`) if it would leave the user with no role anywhere and no COMPANY role; also removes them from this workspace's teams.
    - `POST /{userId}/reset-password { temporaryPassword }` — `canManageLogin`.
    - `GET /api/workspaces/{workspaceId}/assignable-roles` (`user:manage`).
  - `/api/users/{userId}/deactivate|reactivate` (`hasAuthority('company-user:manage')` + `canDeactivateLogin`; target must be in caller's customer, else 404).

- [ ] **Step 1: Write `WorkspacesApiIT`**: `customerAdminCreatesWorkspaceWithGeneralTeam`, `duplicateWorkspaceNameIs409`, `workspaceManagerCannotCreateWorkspace` (403), `workspaceManagerRenamesOwnWorkspace`, `listShowsOnlyAccessibleWorkspaces`.
- [ ] **Step 2: Write `WorkspaceUsersApiIT`**: `listIncludesCustomerAdminAsAllBrands`, `workspaceManagerCreatesAgentWithMustChangePassword`, `workspaceManagerCannotCreateWorkspaceManager` (403), `customerAdminCreatesWorkspaceManager`, `addExistingCompanyUserToSecondWorkspace`, `cannotGiveBrandRoleToCustomerAdmin` (400 `ALREADY_ALL_BRANDS`), `teamLeadCanViewButNotCreate` (200 / 403), `workspaceManagerCannotResetPasswordOfAgentAlsoInOtherWorkspace` (403), `removeFromWorkspaceAlsoLeavesTeams`, `removingLastAccessIs400`, `assignableRolesForWorkspaceManagerAreTeamLeadAndAgent`, `customerAdminDeactivatesAgent` (token 401 after), `customerAdminCannotDeactivateAnotherCustomerAdmin` (403), `deactivateUserOfOtherCustomerIs404`.
- [ ] **Step 3: Run** — FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** `./mvnw test` — green.
- [ ] **Step 6: Commit** `feat(tenant): workspaces and workspace users APIs`

### Task 9: Teams API

**Files:**
- Create: `tenant/TeamController.java`, `tenant/TeamService.java`
- Test: `tenant/TeamsApiIT.java`

**Interfaces:**
- Produces: `/api/workspaces/{workspaceId}/teams`: `GET` (`user:view`) → `[{ id, name, members: [{ id, name, username }] }]`; `POST { name }` and `PUT /{teamId} { name, memberIds }` (`team:manage`). Every member must have access to the workspace (role there or COMPANY role) → else 400 `NOT_IN_WORKSPACE`. Team of another workspace → 404.

- [ ] **Step 1: Write `TeamsApiIT`**: `listsTeamsWithMembers`, `createsTeam`, `duplicateTeamNameIs409`, `setsMembersIncludingCustomerAdmin`, `memberWithoutAccessIs400`, `teamOfOtherWorkspaceIs404`, `teamLeadCannotManageTeams` (403).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw test` — green.
- [ ] **Step 5: Commit** `feat(tenant): teams API`

### Task 10: Isolation matrix and docs

**Files:**
- Create: `tenant/IsolationIT.java`
- Modify: `README.md` (roles and authorities section, new endpoints table, first-login flow, `whatsapp:bridge` requirement, role seeding/migration note)

- [ ] **Step 1: Write `IsolationIT`**, parameterised over every brand endpoint (GET/POST/PUT/DELETE under `/api/workspaces/{id}/users`, `/teams`, `/assignable-roles`, and `PUT /api/workspaces/{id}`):
  - `otherCustomersWorkspaceIs404ForCustomerAdmin`
  - `ownCustomersWorkspaceWithoutRoleIs404ForWorkspaceManager`
  - `superAdminAndAccountManagerGet404OnBrandEndpoints`
  - `otherCustomersUserIdIs404` on `/api/users/{id}/deactivate` and `/api/workspaces/{own}/users/{foreignUserId}`
- [ ] **Step 2: Run** `./mvnw test -Dtest=IsolationIT` — Expected: PASS (it pins behaviour built in Tasks 3–9; a failure is a bug in those tasks — fix there with systematic-debugging).
- [ ] **Step 3: Update README.** **Step 4:** `./mvnw test` — green.
- [ ] **Step 5: Commit** `test(tenant): cross-tenant isolation matrix; docs`
