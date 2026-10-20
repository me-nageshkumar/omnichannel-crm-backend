# Phone Numbers and Enable/Disable (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brands onboard WhatsApp numbers; the extension connects for one number with a connect
code and device key; numbers, brands, customers and people are enabled/disabled with the
"no enabling above your level" rule; the shared `wabridge` device login is removed.

**Architecture:**
- One small enablement core (`me.nageshk.omnichannel.enablement`): the `Disabled` record stored on
  each thing, the actor-level resolution, the enable check, and an `enablement_changes` audit.
- Users, customers, workspaces and per-brand access gain a `disabled` record; the existing
  deactivate/reactivate endpoints become disable/enable.
- A new `me.nageshk.omnichannel.phone` package: `phone_numbers` documents, connect codes, device
  keys, brand and platform APIs, and a `DeviceAccessChanged` application event that the WebSocket
  layer listens to so a live socket closes the moment its number may no longer connect.
- The WebSocket layer authenticates device keys, keys sessions by phone number id, confirms the
  WhatsApp Web number before forwarding events.

**Tech Stack:** Spring Boot 3.5.6, Java 21, Spring Security 6 method security, Spring Data MongoDB
(embedded flapdoodle in tests), JUnit 5, AssertJ, TestRestTemplate, the existing `FakeDevice`
WebSocket test client.

**Spec:** `docs/superpowers/specs/2026-10-20-phone-numbers-and-enable-disable-design.md`.

## Global Constraints

- Packages: `me.nageshk.omnichannel.enablement` (core), `me.nageshk.omnichannel.phone` (numbers,
  codes, keys, device API, demo seeder). Existing classes change in place.
- Collections: `phone_numbers`, `connect_codes`, `enablement_changes`.
- `Disabled` JSON shape everywhere: `{"by": "<username>", "level": "PLATFORM"|"COMPANY"|"BRAND",
  "at": "<ISO instant>"}`, or `null` when enabled. Existing `active` booleans stay in JSON and in
  Mongo (`active == (disabled == null)`); `disabled` is added beside them.
- Level order PLATFORM > COMPANY > BRAND. Enable refused when actor level < stored level:
  403 `{code: "DISABLED_AT_HIGHER_LEVEL", message, level}`; message text:
  `Disabled by platform staff. Only platform staff can enable it.` /
  `Disabled by a Customer Admin. Only a Customer Admin or platform staff can enable it.`
- Number format: digits only, `^[1-9][0-9]{7,14}$`, unique platform-wide (`DUPLICATE`, field
  `number`). Label: trimmed, ≤ 60 chars, blank → null.
- Connect code: 8 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, displayed `ABCD-EFGH`, accepted
  case-insensitive with or without the dash; valid 15 minutes; single use; stored as SHA-256 hex.
- Device key: 32 random bytes, base64url without padding, prefixed `dk_`; stored as SHA-256 hex on
  the phone number (`deviceKeyHash`), with `deviceKeyId` (random 8-hex id shown to the extension
  for outbox tagging).
- Connect failures: 10 per client IP per 15 minutes, then 429 `TOO_MANY_ATTEMPTS` with
  `retryAfterSeconds` (existing shape).
- WebSocket close codes: 4401 unauthorized (exists), 4409 replaced (exists), **4403 disabled**,
  **4410 revoked**, **4412 wrong number**. Reasons are human sentences the extension shows.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Style: plain getters/setters, no Lombok; `record`s for API views; one-line Javadoc per class.
- Whole-suite command: `./mvnw test`.

## API contract (the frontend and extension plans consume exactly this)

Enable/disable (all `POST`, empty body, answer the updated view):
- `/api/customers/{id}/disable|enable` (`customer:manage`) → `CustomerView` + `disabled`.
- `/api/customers/{id}/workspaces/{workspaceId}/disable|enable` (`customer:manage`) → `WorkspaceView`.
- `/api/workspaces/{workspaceId}/disable|enable` (`workspace:enable`, own customer) → `WorkspaceView`.
- `/api/customers/{id}/users` `GET` (`customer:view`) → `UserSummary[]` (every user of the customer).
- `/api/customers/{id}/users/{userId}/disable|enable` (`customer:manage`) → `UserSummary`.
  Replaces `/api/customers/{id}/admins/{userId}/deactivate|reactivate`.
- `/api/users/{userId}/disable|enable` (Customer Admin, `company-user:manage`) → `UserSummary`.
- `/api/platform-users/{id}/disable|enable` → `UserSummary`.
- `/api/workspaces/{workspaceId}/users/{userId}/disable|enable` (`@access.has(#workspaceId,'user:enable')`)
  → workspace user view with `brandDisabled`.
- Views gain: `UserSummary.disabled`, `CustomerView.disabled`, `WorkspaceView {id, name, disabled}`,
  `CurrentUser.workspaces[].disabled`, `Detail.workspaces[]` → `WorkspaceView`, workspace user
  view `brandDisabled`.

Phone numbers:
- `PhoneNumberView {id, number, label, customerId, customerName, workspaceId, workspaceName,
  status, disabled, disabledBecause, lastConnectedAt, lastSeenAt, wrongNumber, wrongNumberAt,
  hasDevice, creationTime}`; `status` ∈ `DISABLED | NOT_CONNECTED_YET | WAITING_FOR_LOGIN |
  CONNECTED | WRONG_NUMBER | DISCONNECTED`; `disabledBecause` ∈ `NUMBER | BRAND | CUSTOMER | null`.
- Brand (`/api/workspaces/{workspaceId}/phone-numbers`): `GET` (`phone-number:view`), `POST
  {number,label}` 201 (`phone-number:manage`), `PUT /{id} {label}`, `DELETE /{id}` 204,
  `POST /{id}/disable|enable`, `POST /{id}/connect-code` → `{code:"ABCD-EFGH", expiresAt}`,
  `POST /{id}/revoke-device` → view. All manage endpoints `phone-number:manage`.
- Platform (`/api/phone-numbers`): `GET ?q=&status=&page=` (`phone-number:view-all`) →
  `{items, total, page}` (page size 50), `POST /{id}/disable|enable`, `DELETE /{id}`
  (`phone-number:manage-all`).
- Device: `POST /api/devices/connect {code}` (no auth) → `{deviceKey, deviceKeyId, phoneNumberId,
  number, brandName}`; 400 `INVALID_CONNECT_CODE` "Invalid or expired connect code."; 403
  `DISABLED` when the number/brand/customer is disabled.
- `GET /api/scripts`, `/api/scripts/{name}` accept `Authorization: Device <key>` (and Super Admin bearer).
- WebSocket `auth` frame: `{type:"auth", token:"<deviceKey>"}` → response `{ok, phoneNumberId,
  number, brandName}`. `hello.params.info` may carry `pnWid` (phone-number id
  `"<digits>@c.us"`) beside `wid`. Event frames may carry `acct` (digits of the account the event
  was captured under).
- PoC test endpoints, Super Admin only: `GET /api/phone-numbers/{id}/bridge/status`,
  `GET .../bridge/events?after=`, `POST .../bridge/messages`, `POST .../bridge/rpc`.

## Review Focus

1. **A Customer Admin's disable undone by a Workspace Manager.** Customer Admin disables a number
   (BRAND authority held through the COMPANY role) → stored level COMPANY → WM enable is 403.
   (Task 1 `levelComesFromRoleNotAuthority`, Task 4 `managerCannotEnableNumberAdminDisabled`.)
2. **Live socket survives a disable.** Disabling the brand or customer of a connected number must
   close its socket with 4403 within the request. (Task 6 `disablingBrandClosesSocket`,
   `disablingCustomerClosesSocket`.)
3. **Wrong account traffic leaking into a brand.** Events before confirmation are not acked or
   published; a `@lid`-only wid never confirms; an event whose `acct` differs is acked and dropped.
   (Task 6 `unconfirmedEventsAreNotAcked`, `lidOnlyNeverConfirms`, `foreignAcctIsDropped`.)
4. **Bypassing a brand-access disable by remove + re-add.** (Task 3 `brandDisableSurvivesReAdd`.)
5. **Connect-code guessing.** 11th failure from one IP inside 15 minutes is 429 even with a valid
   code; codes are single use. (Task 5 `connectIsRateLimitedPerIp`, `codeIsSingleUse`.)

---

### Task 1: Enablement core

**Files:**
- Create: `enablement/Disabled.java`, `enablement/EnableLevel.java`, `enablement/EnablementChange.java`,
  `enablement/EnablementChangeRepository.java`, `enablement/Enablement.java`,
  `api/DisabledAtHigherLevelException.java`; Modify: `api/ApiExceptionHandler.java`.
- Test: `src/test/java/me/nageshk/omnichannel/enablement/EnablementTest.java`.

**Interfaces:**
- Produces:
  - `record Disabled(String by, EnableLevel level, Instant at)`.
  - `enum EnableLevel { BRAND, COMPANY, PLATFORM; boolean atLeast(EnableLevel other) }`.
  - `@Service Enablement` with:
    - `EnableLevel levelOf(AuthenticatedUser actor, String authority, String workspaceId)` —
      platform user (customerId null) → PLATFORM; customer user whose COMPANY role holds
      `authority` → COMPANY; else (holds it through a BRAND role in `workspaceId`) → BRAND.
      `workspaceId` may be null for non-brand authorities.
    - `Disabled disable(Disabled current, AuthenticatedUser actor, EnableLevel level)` — returns the
      record to store: `current` when it is non-null and its level is strictly higher than
      `level`; otherwise a new `Disabled(actor.getUsername(), level, now)`.
    - `void checkEnable(Disabled current, EnableLevel level)` — throws
      `DisabledAtHigherLevelException(current.level())` when `current != null && !level.atLeast(current.level())`.
    - `void record(String targetType, String targetId, boolean enabledBefore, boolean enabledAfter,
      AuthenticatedUser actor, EnableLevel level)` → inserts an `EnablementChange`.
  - `ApiExceptionHandler`: `DisabledAtHigherLevelException` → 403 `{code:"DISABLED_AT_HIGHER_LEVEL",
    message, level}` with the Global Constraints texts (PLATFORM / COMPANY).
- Consumes: `AccessService.inWorkspace`, `RoleCache`, `AuthorityCatalog` (level resolution needs
  the user's COMPANY role codes: add `public Set<String> companyCodes(AuthenticatedUser)` to
  `AccessService`, the COMPANY role's codes at COMPANY and BRAND levels).

- [ ] **Step 1: Write failing tests** in `EnablementTest` (Spring IT with `TestUsers`):
  `platformStaffActAtPlatform`, `levelComesFromRoleNotAuthority` (Customer Admin + `phone-number:manage`
  → COMPANY; Workspace Manager in that brand → BRAND), `lowerDisableDoesNotOverwriteHigher`
  (COMPANY record kept when a BRAND actor disables), `higherDisableReplacesLower`,
  `enableBelowStoredLevelIsRefused` (asserts exception level COMPANY),
  `enableAtOrAboveLevelIsAllowed`, `recordWritesAChange`. Note: `phone-number:manage` doesn't exist
  until Task 4 — use `user:manage` here.
- [ ] **Step 2: Run** `./mvnw test -Dtest=EnablementTest` — Expected: compilation failure (classes missing).
- [ ] **Step 3: Implement** the classes above; handler test in `ApiExceptionHandlerTest`
  `disabledAtHigherLevelIs403WithLevel`.
- [ ] **Step 4: Run** `./mvnw test -Dtest='EnablementTest,ApiExceptionHandlerTest'` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: enablement core with level rule and audit`.

### Task 2: Customers and logins: disable/enable with levels

**Files:** Modify `tenant/Customer.java`, `tenant/CustomerService.java`, `tenant/CustomerController.java`,
`user/User.java`, `user/UserAdminService.java`, `user/UserSummary.java`, `user/CompanyUserController.java`,
`user/PlatformUserController.java`, `access/AssignmentPolicy.java`, `access/Authority.java` (descriptions);
tests `tenant/CustomersApiIT.java`, `user/PlatformUsersApiIT.java`, `user/WorkspaceUsersApiIT.java`
(company-user cases), `access/AssignmentPolicyTest.java`.

**Interfaces:**
- Consumes: Task 1 `Enablement`.
- Produces:
  - `Customer.disabled`, `User.disabled` (`Disabled`, null when enabled; `active` kept in sync).
  - `CustomerService.setEnabled(String id, boolean enabled, AuthenticatedUser actor)` (level PLATFORM).
  - `UserAdminService.setEnabled(Actor actor, AuthenticatedUser me, User target, boolean enabled, EnableLevel level)`
    replacing `setActive`; disabling bumps `tokenVersion` (existing behaviour).
  - `AssignmentPolicy.canDisableLogin(Actor, Target)` replacing `canDeactivateLogin`: the old rule,
    plus platform staff holding `customer:manage` may disable **any** customer user.
  - `CustomerService.usersOf(Customer)` → all users with that `customerId`, by name.
  - Endpoints per the API contract (customers, customer users, company users, platform users).
    Old `/deactivate` `/reactivate` paths are deleted.
  - Level used: customer and platform-user endpoints PLATFORM; `/api/users/{id}` COMPANY.
- [ ] **Step 1: Failing tests:** `CustomersApiIT.disableAndEnableCustomerRecordsPlatformLevel`,
  `CustomersApiIT.oldDeactivatePathIsGone` (404), `CustomersApiIT.listsAllCustomerUsers`,
  `CustomersApiIT.accountManagerDisablesAnAgent` (agent's token then 401),
  `WorkspaceUsersApiIT.customerAdminCannotEnableLoginPlatformDisabled` (403
  `DISABLED_AT_HIGHER_LEVEL`, level PLATFORM), `WorkspaceUsersApiIT.customerAdminDisablesAtCompanyLevel`,
  `PlatformUsersApiIT.disableEnablePlatformUser`, `AssignmentPolicyTest.platformStaffMayDisableAnyCustomerUser`.
  Rename existing deactivate tests to the new paths in the same step.
- [ ] **Step 2: Run** `./mvnw test -Dtest='CustomersApiIT,PlatformUsersApiIT,WorkspaceUsersApiIT,AssignmentPolicyTest'`
  — Expected: new tests FAIL (404 on the new paths / missing `disabled`).
- [ ] **Step 3: Implement.** Customer disable keeps the existing token behaviour (users refused while
  disabled, see `AccessService.load`). Each change calls `Enablement.record`.
- [ ] **Step 4: Run** the same command, then `./mvnw test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: disable/enable customers and logins with levels`.

### Task 3: Brands and brand access

**Files:** Modify `tenant/Workspace.java`, `tenant/WorkspaceService.java`, `tenant/WorkspaceController.java`,
`tenant/CustomerController.java`, `access/AccessService.java`, `user/User.java`,
`user/WorkspaceUserController.java`, `user/UserAdminService.java`, `user/UserService.java`,
`access/Authority.java` (add `WORKSPACE_ENABLE("workspace:enable", COMPANY, "Disable or enable the
company's brands", CUSTOMER_ADMIN)`, `USER_ENABLE("user:enable", BRAND, "Disable or enable a
person's access to the brand", CUSTOMER_ADMIN, WORKSPACE_MANAGER)`); tests
`tenant/WorkspacesApiIT.java`, `user/WorkspaceUsersApiIT.java`, `access/AccessServiceIT.java`,
`user/CurrentUserIT.java`.

**Interfaces:**
- Produces:
  - `Workspace.disabled`. `WorkspaceView(id, name, disabled)`.
  - `record BrandDisable(String workspaceId, Disabled disabled)`; `User.brandDisables: List<BrandDisable>`
    (default empty; never cleared by removing the person's role in that workspace or by role changes).
  - `AccessService` effects:
    - `inWorkspace`: workspace disabled → empty set for a COMPANY-role holder (so every
      `@access.has` is 403), not found for anyone else; a `BrandDisable` for that workspace → not
      found (404).
    - `workspacesOf`: COMPANY-role holders still get disabled workspaces; others get neither
      disabled workspaces nor ones with a `BrandDisable`.
    - `isWorkspaceUsable(String workspaceId)` → workspace exists, not disabled, customer active
      (consumed by Task 4/6).
  - `WorkspaceService.setEnabled(String customerId, String workspaceId, boolean enabled, AuthenticatedUser actor, EnableLevel level)`.
  - Endpoints per contract: `/api/workspaces/{id}/disable|enable` (`hasAuthority('workspace:enable')`,
    own customer only else 404, level COMPANY), `/api/customers/{id}/workspaces/{wid}/disable|enable`
    (level PLATFORM), `/api/workspaces/{wid}/users/{userId}/disable|enable` (`user:enable`; target
    must pass `AssignmentPolicy.canChangeInWorkspace`; level from `Enablement.levelOf(me,
    "user:enable", wid)`), workspace user view gains `brandDisabled`.
  - `CurrentUser.workspaces[]` gains `disabled`.
  - Task 3 publishes no events; Task 6 adds the disconnect publishes.
- [ ] **Step 1: Failing tests:** `WorkspacesApiIT.customerAdminDisablesBrand` (managers' `/api/users/me`
  no longer lists it; Customer Admin's still lists it with `disabled`), `WorkspacesApiIT.disabledBrandRefusesActions`
  (Customer Admin rename → 403), `WorkspacesApiIT.platformDisableCannotBeEnabledByCustomerAdmin`,
  `WorkspacesApiIT.otherCustomersBrandIs404`, `WorkspaceUsersApiIT.managerDisablesAgentInBrand`
  (agent's brand call → 404; agent's other brand still works), `WorkspaceUsersApiIT.brandDisableSurvivesReAdd`,
  `WorkspaceUsersApiIT.managerCannotEnableAgentAdminDisabled`, `CurrentUserIT.disabledFlagOnWorkspaces`.
- [ ] **Step 2: Run** `./mvnw test -Dtest='WorkspacesApiIT,WorkspaceUsersApiIT,CurrentUserIT,AccessServiceIT'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then `./mvnw test` — Expected: PASS (RoleSeeder grants the two new authorities to their default roles on a fresh DB).
- [ ] **Step 5: Commit** `feat: disable/enable brands and a person's brand access`.

### Task 4: Phone numbers (data, brand and platform APIs)

**Files:** Create `phone/PhoneNumber.java`, `phone/PhoneNumberRepository.java`, `phone/PhoneNumberService.java`,
`phone/PhoneNumberStatus.java`, `phone/PhoneNumberView.java`, `phone/BrandPhoneNumberController.java`,
`phone/PlatformPhoneNumberController.java`, `phone/DeviceAccessChanged.java`, `phone/LiveDevices.java`;
modify `access/Authority.java` (four authorities per spec table: `PHONE_NUMBER_VIEW("phone-number:view", BRAND, …,
CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD)`, `PHONE_NUMBER_MANAGE("phone-number:manage", BRAND, …,
CUSTOMER_ADMIN, WORKSPACE_MANAGER)`, `PHONE_NUMBER_VIEW_ALL("phone-number:view-all", PLATFORM, …, ACCOUNT_MANAGER)`,
`PHONE_NUMBER_MANAGE_ALL("phone-number:manage-all", PLATFORM, …, ACCOUNT_MANAGER)`).
Test `phone/PhoneNumbersApiIT.java`, `phone/PhoneNumberStatusTest.java`.

**Interfaces:**
- Produces:
  - `PhoneNumber` fields per spec (+ `deviceKeyHash`, `deviceKeyId`, `deviceKeyIssuedAt`,
    `lastWrongNumber`, `lastWrongNumberAt`, `lastConnectedAt`, `lastSeenAt`, `disabled`, `@Version`);
    unique index on `number`.
  - `interface LiveDevices { Optional<LiveState> stateOf(String phoneNumberId); enum LiveState { WAITING_FOR_LOGIN, CONNECTED } }`
    — implemented by Task 6; Task 4 ships a default `@ConditionalOnMissingBean` returning empty.
  - `PhoneNumberStatus.of(PhoneNumber, Workspace, Customer, Optional<LiveState>)` → `(status, disabledBecause)`;
    order: DISABLED (customer, then brand, then number) → live state → WRONG_NUMBER if
    `lastWrongNumberAt` after `lastConnectedAt` (or no connect) → NOT_CONNECTED_YET if
    `lastConnectedAt == null` → DISCONNECTED.
  - `record DeviceAccessChanged(String phoneNumberId, String customerId, String workspaceId, Reason reason)`
    with `enum Reason { DISABLED, REVOKED, REPLACED, DELETED }`; null `phoneNumberId` means "every
    number of the workspace" (or of the customer when `workspaceId` is null too). Published by
    `PhoneNumberService` on number disable / revoke / delete; Task 6 adds brand/customer publishes.
  - `PhoneNumberService`: `create(String customerId, String workspaceId, String number, String label, AuthenticatedUser)`,
    `relabel`, `delete`, `setEnabled(..., EnableLevel)`, `listForWorkspace`, `search(String q, String status, int page)`,
    `requireInWorkspace(String workspaceId, String id)` (other brand's → 404), `revokeDevice`.
  - Controllers per the API contract. Brand level from `Enablement.levelOf(me, "phone-number:manage", wid)`;
    platform level PLATFORM.
- [ ] **Step 1: Failing tests:** `PhoneNumbersApiIT.managerAddsNumber`, `invalidNumberIs400` (`+91…`, 7 digits,
  leading 0), `duplicateNumberAcrossBrandsIs409`, `teamLeadCanViewNotManage`, `agentCannotView` (403),
  `otherBrandsNumberIs404`, `managerCannotEnableNumberAdminDisabled`, `accountManagerSeesAllAndSearches`
  (q matches number, customer name, brand name), `accountManagerDisableCannotBeLiftedByManager`,
  `platformStaffCannotAdd` (no endpoint → 404/405), `deleteRemovesNumber`; `PhoneNumberStatusTest`
  one test per status branch, including `customerDisabledWinsOverBrand`.
- [ ] **Step 2: Run** `./mvnw test -Dtest='PhoneNumbersApiIT,PhoneNumberStatusTest'` — Expected: compile FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then `./mvnw test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: phone numbers per brand with platform view`.

### Task 5: Connect codes, device keys, device auth for HTTP

**Files:** Create `phone/ConnectCode.java`, `phone/ConnectCodeRepository.java`, `phone/DeviceKeys.java`,
`phone/DeviceConnectController.java`, `phone/ConnectAttemptLimiter.java`, `phone/DevicePrincipal.java`,
`phone/DeviceKeyAuthFilter.java`, `api/InvalidConnectCodeException.java`; modify `config/SecurityConfig.java`,
`phone/BrandPhoneNumberController.java`, `phone/PhoneNumberService.java`, `api/ScriptsController.java`,
`api/ApiExceptionHandler.java`. Test `phone/DeviceConnectIT.java`, `api/ScriptsApiIT.java`.

**Interfaces:**
- Produces:
  - `PhoneNumberService.issueConnectCode(workspaceId, id)` → `record ConnectCodeView(String code, Instant expiresAt)`;
    deletes earlier unused codes for that number. Refused 403 `DISABLED` when not usable.
  - `DeviceKeys.redeem(String code, String clientIp)` → `record Connected(String deviceKey, String deviceKeyId,
    String phoneNumberId, String number, String brandName)`; replaces any existing key and publishes
    `DeviceAccessChanged(REPLACED)` for the old one when a key existed.
  - `DeviceKeys.authenticate(String deviceKey)` → `Optional<DevicePrincipal>` — present only if a number
    has that hash **and** number, brand and customer are enabled. `record DevicePrincipal(String phoneNumberId,
    String customerId, String workspaceId, String number, String deviceKeyId, String brandName)`.
  - `DeviceKeyAuthFilter`: `Authorization: Device <key>` → authentication with authority `DEVICE`
    (marker, like `SUPER_ADMIN`, not a catalogue code). `ScriptsController` →
    `@PreAuthorize("hasAuthority('DEVICE') or hasAuthority('SUPER_ADMIN')")`.
  - `/api/devices/connect` permitted without auth in `SecurityConfig`; client IP = `request.getRemoteAddr()`.
  - `ConnectAttemptLimiter` constants `MAX_FAILURES = 10`, `WINDOW = 15 min`, `Clock`-driven.
- [ ] **Step 1: Failing tests:** `DeviceConnectIT.codeRedeemsForKey` (response fields per contract),
  `codeIsSingleUse`, `codeExpiresAfter15Minutes` (move the test `Clock`), `newCodeInvalidatesOldCode`,
  `codeAcceptsLowercaseWithoutDash`, `wrongCodeIs400`, `connectIsRateLimitedPerIp`,
  `disabledNumberCannotGetCode`, `redeemReplacesOldKey` (old key `authenticate` empty),
  `revokeDeviceClearsKey`, `keyRefusedWhileBrandDisabledAndWorksAfterEnable`;
  `ScriptsApiIT.deviceKeyCanFetchScripts`, `ScriptsApiIT.customerUserCannotFetchScripts`.
- [ ] **Step 2: Run** `./mvnw test -Dtest='DeviceConnectIT,ScriptsApiIT'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then `./mvnw test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: connect codes and device keys`.

### Task 6: WebSocket on device keys, number confirmation, live disconnects

**Files:** Modify `ws/BridgeWebSocketHandler.java`, `ws/DeviceSession.java`, `ws/DeviceRegistry.java`,
`ws/EventSequenceTracker.java`, `events/WhatsAppEvent.java`, `events/RecentEventsStore.java`,
`events/LoggingEventListener.java`, `rpc/RpcDispatcher.java` (key rename), `api/StatusController.java`,
`api/EventsController.java`, `api/MessagesController.java`, `api/RpcController.java`,
`tenant/WorkspaceService.java`, `tenant/CustomerService.java` (publish), `test/.../support/FakeDevice.java`;
create `ws/DeviceDisconnector.java`, `ws/RegistryLiveDevices.java`, `ws/NumberCheck.java`.
Tests `ws/BridgeWebSocketIT.java` (rewritten for device keys), `api/MessagesApiIT.java`, `ws/NumberCheckTest.java`.

**Interfaces:**
- Consumes: `DeviceKeys.authenticate`, `DeviceAccessChanged`, `LiveDevices`.
- Produces:
  - `DeviceSession(DevicePrincipal principal, WebSocketSession, ObjectMapper)`; `phoneNumberId()`,
    `confirmed()`/`setConfirmed`. Registry, sequence tracker, recent events, RPC dispatcher keyed by
    `phoneNumberId` (rename parameters, no behaviour change).
  - `NumberCheck.check(String number, JsonNode info)` → `enum Result { MATCH, MISMATCH, UNKNOWN }` plus
    the reported digits: uses `info.pnWid` if present, else `info.wid` when it ends `@c.us`; `@lid` /
    missing → UNKNOWN. Accepts both string and `{_serialized}` forms (reuse `LoggingEventListener.wid`).
  - On `hello` and on `ready` events: MATCH → `confirmed = true`, `lastConnectedAt`/`lastSeenAt`
    updated; MISMATCH → store `lastWrongNumber(At)`, close 4412 reason
    `WhatsApp Web is logged in to +<reported>, this device is for +<number>.`; UNKNOWN → stay.
  - `handleEvent`: not confirmed → neither published nor acked (drop silently); `acct` present and ≠
    number → ack + drop; otherwise current behaviour. `WhatsAppEvent(source, phoneNumberId, workspaceId,
    customerId, name, payload, seq, ts)`.
  - `DeviceDisconnector` `@EventListener(DeviceAccessChanged)`: closes matching sessions — DISABLED →
    4403 `This number, its brand or its company is disabled.`; REVOKED / DELETED → 4410 `This device
    was removed. Enter a new connect code.`; REPLACED → 4409.
  - `WorkspaceService.setEnabled(false)` and `CustomerService.setEnabled(false)` publish
    `DeviceAccessChanged(DISABLED)` for the workspace / customer.
  - `RegistryLiveDevices implements LiveDevices` (WAITING_FOR_LOGIN when not confirmed).
  - PoC controllers move to `/api/phone-numbers/{id}/bridge/...` with
    `@PreAuthorize("hasAuthority('SUPER_ADMIN')")`; `MessagesController.requireReadyDevice(phoneNumberId)`.
  - `FakeDevice.connectAndAuth(port, deviceKey)`; tests obtain a key via `DeviceKeys.redeem`.
- [ ] **Step 1: Failing tests** in `BridgeWebSocketIT`: `deviceKeyAuthenticates` (auth reply fields),
  `invalidKeyCloses4401`, `disabledNumberKeyCloses4401`, `matchingHelloConfirms`,
  `wrongNumberCloses4412AndIsRecorded`, `lidOnlyNeverConfirms`, `unconfirmedEventsAreNotAcked`,
  `foreignAcctIsDropped`, `disablingNumberClosesSocket` (4403), `disablingBrandClosesSocket`,
  `disablingCustomerClosesSocket`, `revokeCloses4410`, `newKeyReplaces4409`; keep and port
  `pingGetsPong`, `durableEventsAreAckedAndDuplicatesAreNotRepublished`, `disconnectUnregistersDevice`,
  `closesWith4401WhenNoAuthFrameArrivesInTime`. `NumberCheckTest`: pnWid wins, c.us wid, lid wid,
  object form, missing info. `MessagesApiIT`: Super Admin sends via `/api/phone-numbers/{id}/bridge/messages`;
  Account Manager 403.
- [ ] **Step 2: Run** `./mvnw test -Dtest='BridgeWebSocketIT,NumberCheckTest,MessagesApiIT'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then `./mvnw test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: WebSocket on device keys with number confirmation and live disconnects`.

### Task 7: Remove the device login; migrate

**Files:** Modify `access/Authority.java` (delete `WHATSAPP_BRIDGE`), `access/BuiltInRoles.java`,
`access/RoleSeeder.java`, `access/RoleService.java`, `user/UserSeeder.java`, `user/UserRoleMigration.java`,
`config/OmnichannelProperties.java`, `src/main/resources/application.yml`; create
`access/DeviceAccountCleanup.java` (`@Order(2)`, before `UserRoleMigration` which moves to `@Order(3)`,
`UserSeeder` to `@Order(4)`); tests `access/DeviceAccountCleanupIT.java`, `user/UserRoleMigrationIT.java`,
`user/UserSeederTest.java`, `auth/InitialPasswordIT.java` (drop device-user references).

**Interfaces:**
- `DeviceAccountCleanup.run`: deletes users whose `roleId` is the `WHATSAPP_DEVICE` role id or whose
  legacy `role` is `"USER"`; deletes the role document named `WHATSAPP_DEVICE`; deletes the
  `authorities` document `whatsapp:bridge` and removes that code from every role's `authorities`;
  evicts `AuthorityCatalog` (which evicts `RoleCache`). Idempotent; logs counts.
- `UserRoleMigration.LEGACY_ROLES` keeps only `SUPER_ADMIN`.
- `omnichannel.device-user.*` removed from properties and yml.
- [ ] **Step 1: Failing tests:** `DeviceAccountCleanupIT.removesDeviceUserRoleAndAuthority` (seed the three by
  hand through `MongoTemplate`), `isIdempotent`, `keepsOtherRolesAuthoritiesIntact`;
  `UserRoleMigrationIT` legacy `USER` case now expects the cleanup to have removed it.
- [ ] **Step 2: Run** `./mvnw test -Dtest='DeviceAccountCleanupIT,UserRoleMigrationIT,UserSeederTest'` — Expected: FAIL.
- [ ] **Step 3: Implement**, then `grep -rn "WHATSAPP_DEVICE\|whatsapp:bridge\|deviceUser\|device-user" src` returns nothing.
- [ ] **Step 4: Run** `./mvnw test` — Expected: PASS; `AuthorityCheckScannerIT` shows no `whatsapp:bridge`.
- [ ] **Step 5: Commit** `refactor: remove the shared device login`.

### Task 8: Demo seed data and docs

**Files:** Create `phone/DemoSeeder.java` (`@Order(5)`, `@Profile("!test")`,
`@ConditionalOnProperty("omnichannel.seed.demo")`), add `seed.demo: ${OMNICHANNEL_SEED_DEMO:false}`
to `application.yml` and `OmnichannelProperties`; modify `README.md`; test `phone/DemoSeederIT.java`
(activates the bean with `@TestPropertySource(properties="omnichannel.seed.demo=true")` and calls `run`).

**Interfaces:**
- Uses `CustomerService.create` (owner `demo-admin`, brand "Demo Fashion"), `WorkspaceService.create`
  ("Demo Electronics"), `UserAdminService` to add `demo-manager` (WORKSPACE_MANAGER), `demo-lead`
  (TEAM_LEAD), `demo-agent` (AGENT) in Demo Fashion, and `PhoneNumberService.create` for
  `919928088743` label "Main line" in Demo Fashion. Random temporary passwords logged once at WARN;
  all four must change password on first login (existing `mustChangePassword`).
- Skips everything when a customer named "Demo Retail" exists; skips the number with a WARN when it
  already exists anywhere.
- [ ] **Step 1: Failing tests:** `DemoSeederIT.seedsCustomerBrandsPeopleAndNumber`, `isIdempotent`,
  `existingNumberIsLeftAlone`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=DemoSeederIT` — Expected: FAIL.
- [ ] **Step 3: Implement**; README: "Phone numbers" section (connect flow, close codes, statuses),
  "Enable / disable" section (level rule), demo seed switch, and removal of the device-user config.
- [ ] **Step 4: Run** `./mvnw test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: demo seed data; docs for phone numbers and enable/disable`.
