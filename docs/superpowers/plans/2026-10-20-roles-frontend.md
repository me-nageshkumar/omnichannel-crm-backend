# Customers, Workspaces and Roles — Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open the console to every role, render navigation from authorities per workspace, add the first-login password change, and build the management pages: platform users, roles, customers, workspaces, workspace users, teams.

**Architecture:** `/api/users/me` now carries authorities and workspaces. `SessionProvider` drops the super-admin gate and gains the current workspace (persisted in `localStorage`). A `useAccess()` hook answers "may I" for company/platform codes and for the current workspace; routes declare a `requires` predicate used both for the nav and for rendering NotFound. Management pages are PatternFly tables, modals and forms over small typed API modules.

**Tech Stack:** React 18, TypeScript (strict), PatternFly 6.5.1 (`@patternfly/react-core`, `-icons`, `-table`), React Router 7, Vitest + Testing Library + user-event.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-customers-workspaces-and-roles-design.md` (Console section, API summary, `/api/users/me`, first-login flow). Backend contract as implemented by `2026-10-20-roles-backend.md` — run that plan first.

**Repo:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend`. Paths below are relative to it.

## Global Constraints

- PatternFly components and guidelines only; app name "Omnichannel CRM"; document titles `"<Page> | Omnichannel CRM"`; imports via `@app/*`.
- The only new runtime dependency allowed is `@patternfly/react-table` at the same 6.x line as `react-core`, if not already installed (tables are a PatternFly guideline for lists).
- `npm run ci-checks` green at the end of every task.
- `localStorage` keys: `omnichannel.session` (unchanged) and `omnichannel.workspace` (current workspace id). Every access in try/catch.
- The password-change token, like the two-factor token, lives in React state only.
- Hiding UI is convenience; every action still handles 403/404 from the server with a message, never a crash.
- Backend contract (exact; errors are `{ code, message }` plus `fields`/`field`, login errors `{ error }`):
  - Login and verify-otp may also answer `200 { passwordChangeRequired: true, passwordChangeToken }`.
  - `POST /api/auth/initial-password` (Bearer change token) `{ newPassword }` → `{ token, expiresAt }` | 400 `WEAK_PASSWORD` | 401 `INVALID_TOKEN`.
  - `GET /api/users/me` → `{ id, name, username, email, mobileNumber, twoFactorEnabled, superAdmin, customer: {id,name}|null, role: {id,name}|null, authorities: string[], workspaces: [{ id, name, role: {id,name}|null, authorities: string[] }], mustChangePassword }`.
  - Management endpoints exactly as in the spec's API summary table, with bodies from the backend plan (Tasks 6–9).
- Messages (exact copy, added to `MESSAGES`):
  - `WEAK_PASSWORD`: "Use at least 10 characters, different from your temporary password."
  - change token `INVALID_TOKEN`: "Your sign-in took too long. Please log in again."
  - `FORBIDDEN`: "You don't have permission to do that."
  - `NOT_FOUND`: "That item no longer exists or you can't see it."
  - `DUPLICATE`: "<Field label> is already in use." (field labels: username → "Username", name → "Name")
  - `ROLE_IN_USE`: "This role is assigned to users. Move them to another role first."
  - `ALREADY_ALL_BRANDS`: "This person already has access to every brand."
  - `ALREADY_IN_WORKSPACE`: "This person already has a role in this brand."
  - `LAST_ACCESS`: "This is the person's only brand. Deactivate them instead."
  - `NOT_IN_WORKSPACE`: "Everyone in a team must have access to this brand."
  - The old "This console is only available to the super admin." message is removed.
- UI wording: a workspace is called a **brand** on screen ("Brand", "Switch brand"); code and API keep `workspace`. (Ruling: the user describes workspaces as brands; cost if wrong: copy change.)

## Review Focus

1. A stored `omnichannel.workspace` id that is no longer in `/users/me` workspaces (access removed) must fall back to the first workspace, not request a 404 page — Task 1.
2. A user with no workspaces and no platform/company authorities (an Agent whose last brand was removed elsewhere, or the device user) sees dashboard and profile only, no broken nav — Task 1/3.
3. Switching brand while on a brand page reloads that page's data for the new brand, not stale rows — Task 8.
4. A 403/404 from any management action shows the message inline and leaves the page usable — Task 3 helper, used by Tasks 4–9.
5. The temporary password is shown exactly once after create/reset and is not kept in state after the modal closes — Task 3.

---

### Task 1: New `/users/me`, session without the gate, current workspace, `useAccess`

**Files:**
- Modify: `src/app/api/users.ts` (types; remove `UserRole`, `roleLabel` → `roleLabel(name: string)` turning `WORKSPACE_MANAGER` into "Workspace manager"), `src/app/session/SessionContext.tsx`, `src/app/api/messages.ts`, `src/test/session.ts` (fixtures `SUPER_ADMIN`, `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`, `AGENT_NO_BRANDS`), `src/app/AppLayout/AppLayout.tsx` (role label from `user.role?.name`, "Super admin" when `superAdmin`)
- Create: `src/app/session/workspaceStore.ts`, `src/app/session/useAccess.ts`, `src/app/AppLayout/WorkspaceSwitcher.tsx`
- Test: `src/app/session/session.test.tsx`, `src/app/session/useAccess.test.tsx`, `src/app/AppLayout/workspaceSwitcher.test.tsx`

**Interfaces:**
- Produces:
  - `CurrentUser`, `WorkspaceAccess`, `RoleRef`, `CustomerRef` types matching the contract.
  - `Session` adds `workspace: WorkspaceAccess | null` and `selectWorkspace(id: string): void`.
  - `readWorkspaceId(): string | null`, `writeWorkspaceId(id: string): void`.
  - `useAccess(): { superAdmin: boolean; has(code: string): boolean; hasInWorkspace(code: string): boolean }` — `has` checks `user.authorities`; `hasInWorkspace` checks `session.workspace.authorities`. (Ruling: the spec says the UI treats every authority as held for the Super Admin, but the backend gives the Super Admin all PLATFORM codes in `authorities` and no brand access; trusting the server's list avoids showing brand pages that would 404. `superAdmin` is used only for the Roles page.)

- [ ] **Step 1: Tests.** `session.test.tsx`: `customerAdminCanSignIn` (no "only super admin" notice; dashboard shown), `workspaceDefaultsToFirst`, `storedWorkspaceIsRestored`, `staleStoredWorkspaceFallsBackToFirst`, `noWorkspacesGivesNull`. `useAccess.test.tsx`: `hasReadsUserAuthorities`, `hasInWorkspaceReadsCurrentWorkspace`, `superAdminHasPlatformCodesOnly` (from fixture). `workspaceSwitcher.test.tsx`: `hiddenWithoutWorkspaces`, `listsBrandsAndSwitches` (selection persisted to `omnichannel.workspace`), `showsSingleBrandAsLabelNotMenu`.
- [ ] **Step 2: Run** `npm test` — FAIL.
- [ ] **Step 3: Implement.** Switcher sits in the masthead toolbar before the user menu; label "Brand: <name>".
- [ ] **Step 4: Run** `npm run ci-checks` — green (update existing login/profile tests to the new fixture shape).
- [ ] **Step 5: Commit** `feat(session): any role can sign in; current brand; useAccess`

### Task 2: First-login password change

**Files:**
- Modify: `src/app/api/auth.ts` (`LoginResult` adds `{ kind: 'passwordChangeRequired'; passwordChangeToken: string }`; `verifyOtp` returns `LoginResult`-like union), `src/app/Login/LoginPage.tsx`, `src/app/Login/VerifyCodePage.tsx`, `src/app/session/SessionContext.tsx` (`pendingPasswordChangeToken`, `startPasswordChange(token)`), `src/app/AppShell.tsx` (route `/login/new-password`)
- Create: `src/app/Login/NewPasswordPage.tsx`, `src/app/api/auth.ts#setInitialPassword(token, newPassword): Promise<IssuedToken>`
- Test: `src/app/Login/newPassword.test.tsx`, `src/app/api/auth.test.ts`

**Interfaces:**
- Produces: `NewPasswordPage` at `/login/new-password`: inside `LoginFrame`, title "Choose a new password", two password fields ("New password", "Confirm new password") with show-password toggles, helper text "At least 10 characters.", submit "Save and continue". Without a pending token it redirects to `/login`.

- [ ] **Step 1: Tests.** `loginWithMustChangeGoesToNewPassword`, `verifyOtpWithMustChangeGoesToNewPassword`, `mismatchShowsFieldErrorWithoutCalling` ("Passwords don't match."), `shortPasswordBlockedClientSide` (< 10, no call), `weakPasswordFromServerShowsMessage`, `expiredChangeTokenReturnsToLoginWithMessage`, `successSignsInAndGoesToFrom`, `directVisitWithoutTokenRedirectsToLogin`, `tokenNeverWrittenToStorage`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4:** `npm run ci-checks` — green.
- [ ] **Step 5: Commit** `feat(login): first-login password change`

### Task 3: Authority-driven routes and nav; shared admin building blocks

**Files:**
- Modify: `src/app/routes.tsx` (route `requires?: (a: Access) => boolean`; nav groups), `src/app/AppLayout/AppLayout.tsx` (nav filtered), `src/app/api/client.ts` (`ApiError` exposes `field?: string`, `fields?: Record<string,string>`), `src/app/api/messages.ts`
- Create: `src/app/admin/TemporaryPasswordModal.tsx`, `src/app/admin/PersonFields.tsx` (name, username, email, mobile, temporary password with show toggle; `mode: 'create' | 'edit'` hides username/password on edit), `src/app/admin/RoleSelect.tsx` (PatternFly `FormSelect` over `RoleRef[]`, labels via `roleLabel`), `src/app/admin/ConfirmModal.tsx`, `src/app/admin/useAction.ts`, `src/app/admin/PageShell.tsx` (title + toolbar + body)
- Test: `src/app/routes.test.tsx`, `src/app/admin/admin.test.tsx`

**Interfaces:**
- Produces:
  - Routes and requirements: `/` Dashboard (all), `/profile` (all, not in nav), `/platform-users` (`has('platform-user:manage')`), `/roles` (`superAdmin`), `/customers` and `/customers/:id` (`has('customer:view')`), `/brands` (`has('workspace:create')`), `/users` (`hasInWorkspace('user:view')`), `/teams` (`hasInWorkspace('user:view')`). Nav groups: "Platform" (Platform users, Roles, Customers), "Company" (Brands), "Brand" (Users, Teams); empty groups hidden. Unmet requirement → `NotFound`.
  - `useAction()` → `{ run<T>(fn: () => Promise<T>): Promise<T | undefined>; error: string | null; fieldErrors: Record<string,string>; busy: boolean; reset(): void }` mapping `ApiError` codes to the messages above (`VALIDATION` → `fieldErrors` from `fields`; `DUPLICATE` → `fieldErrors[field]`).
  - `TemporaryPasswordModal({ username, password, onClose })` — "Temporary password for <username>", `ClipboardCopy` read-only, text "Share it with them securely. They must change it when they first log in. It won't be shown again.", button "Done". Callers clear the password from their state in `onClose`.
  - `@patternfly/react-table` installed if missing (`npm ls @patternfly/react-table` first).

- [ ] **Step 1: Tests.** `routes.test.tsx`: `agentSeesDashboardOnly`, `customerAdminSeesBrandsUsersTeams`, `accountManagerSeesCustomersOnly`, `superAdminSeesPlatformUsersRolesCustomers`, `directUrlWithoutAuthorityIsNotFound`. `admin.test.tsx`: `useActionMapsDuplicateToField`, `useActionMapsForbidden`, `temporaryPasswordModalShowsAndCopies`, `personFieldsHidesUsernameAndPasswordOnEdit`, `temporaryPasswordUnder10IsInvalid`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** (pages not yet built render a placeholder `PageShell` with the title, replaced by later tasks). **Step 4:** `npm run ci-checks` — green.
- [ ] **Step 5: Commit** `feat(console): authority-driven navigation and shared admin components`

### Task 4: Platform users page

**Files:** Create `src/app/api/platformUsers.ts`, `src/app/PlatformUsers/PlatformUsersPage.tsx`, `PlatformUserModal.tsx`; Test `src/app/PlatformUsers/platformUsers.test.tsx`.

**Interfaces:** `listPlatformUsers`, `createPlatformUser`, `updatePlatformUser`, `deactivatePlatformUser`, `reactivatePlatformUser`, `resetPlatformUserPassword`, `platformAssignableRoles` — paths per spec. Table columns: Name, Username, Role, Status ("Active"/"Inactive" label), actions kebab (Edit, Reset password, Deactivate/Reactivate). Toolbar button "Add user".

- [ ] **Step 1: Tests.** `listsUsers`, `createShowsTemporaryPasswordOnce`, `duplicateUsernameShowsFieldError`, `roleSelectOffersAssignableRolesOnly`, `resetPasswordConfirmsThenShowsPassword`, `deactivateConfirmsAndUpdatesRow`, `forbiddenShowsMessage`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4:** `npm run ci-checks`. **Step 5: Commit** `feat(console): platform users`

### Task 5: Roles pages

**Files:** Create `src/app/api/roles.ts`, `src/app/Roles/RolesPage.tsx`, `RoleEditor.tsx` (modal), `RoleChangesDrawer.tsx`; Test `src/app/Roles/roles.test.tsx`.

**Interfaces:** `listAuthorities`, `listRoles`, `createRole`, `updateRole`, `deleteRole`, `previewRole(id, authorities)`, `roleChanges(id)`.
- Roles table grouped by level ("Platform", "Company", "Brand"), columns Name, Description, Authorities (count), Users, "Built-in" label; actions Edit, History, Delete (custom and unused only).
- Editor: Name (disabled for built-in), Level (create only), Description, authority checklist grouped by level showing only levels the role may hold (PLATFORM→Platform; COMPANY→Company+Brand; BRAND→Brand), each checkbox labelled with the description and code in small text. SUPER_ADMIN row shows "All authorities" and no Edit.
- Save on edit: call `previewRole`; if `changes` is non-empty show a warning modal listing each as "<Assigner> will no longer be able to assign <Assigned>." / "<Assigner> will now be able to assign <Assigned>." with "Save anyway" / "Cancel"; else save directly.
- History drawer: newest first, "<when> · <who> · added a, b · removed c".

- [ ] **Step 1: Tests.** `listsRolesGroupedByLevel`, `createCustomBrandRoleOnlyOffersBrandAuthorities`, `companyRoleOffersCompanyAndBrand`, `builtInNameIsReadOnly`, `superAdminNotEditable`, `editWithChangesShowsWarningAndSavesOnConfirm`, `editWithoutChangesSavesDirectly`, `cancelWarningDoesNotSave`, `deleteHiddenForBuiltInAndInUse`, `deleteRoleInUseShowsMessage` (409 race), `historyListsChanges`.
- [ ] **Step 2–4** as above. **Step 5: Commit** `feat(console): roles editor with change preview and history`

### Task 6: Customers pages

**Files:** Create `src/app/api/customers.ts`, `src/app/Customers/CustomersPage.tsx`, `CreateCustomerModal.tsx`, `CustomerDetailPage.tsx`, `CustomerAdminModal.tsx`; Test `src/app/Customers/customers.test.tsx`.

**Interfaces:**
- List: search box (debounced 300 ms) → `q`, toggle group "Active / Inactive / All", `Pagination` (50 per page), columns Name, Status, Created; row links to `/customers/:id`. Button "Add customer" (`has('customer:create')`).
- Create modal sections "Company" (Company name, First brand name — placeholder "Same as company name") and "Owner" (`PersonFields` create mode). On success: temporary password modal, then navigate to the detail page.
- Detail: header with name + status label, actions Edit name, Deactivate/Reactivate (`customer:manage`, confirm text "All of <name>'s users will be logged out and unable to log in."); cards "Brands" (list) and "Owners" (table Name, Username, Status; actions Reset password, Deactivate/Reactivate; button "Add owner").

- [ ] **Step 1: Tests.** `searchesAndFilters`, `pages`, `createShowsPasswordThenOpensDetail`, `createDuplicateOwnerUsernameShowsFieldError`, `detailShowsBrandsAndOwners`, `deactivateCustomerConfirms`, `addOwner`, `resetOwnerPassword`, `withoutCustomerManageNoActions`, `unknownCustomerShowsNotFoundMessage`.
- [ ] **Step 2–4.** **Step 5: Commit** `feat(console): customers`

### Task 7: Brands (workspaces) page

**Files:** Create `src/app/api/workspaces.ts`, `src/app/Brands/BrandsPage.tsx`; Test `src/app/Brands/brands.test.tsx`.

**Interfaces:** `listWorkspaces`, `createWorkspace`, `renameWorkspace`. Table Name, actions Rename; button "Add brand". After create/rename call `refreshUser()` so the switcher updates.

- [ ] **Step 1: Tests.** `listsBrands`, `createAddsBrandAndRefreshesSwitcher`, `duplicateNameShowsFieldError`, `renameUpdatesSwitcher`.
- [ ] **Step 2–4.** **Step 5: Commit** `feat(console): brands`

### Task 8: Brand users page

**Files:** Create `src/app/api/workspaceUsers.ts`, `src/app/Users/UsersPage.tsx`, `AddUserModal.tsx` (tabs "New person" / "Existing person"), `EditUserModal.tsx`; Test `src/app/Users/users.test.tsx`.

**Interfaces:**
- Data keyed by current workspace id; switching brand refetches.
- Table: Name, Username, Role (Customer Admins show label "All brands"), Status; row actions only when `manageable` and `hasInWorkspace('user:manage')`: Edit, Change role, Reset password, Remove from brand; Deactivate/Reactivate when `has('company-user:manage')`.
- "Existing person" tab lists company users from the other brands the caller can see (union of `GET …/users` over `session.user.workspaces`, minus people already here and "All brands" people), then role select.
- Role select uses `GET /api/workspaces/{id}/assignable-roles`.

- [ ] **Step 1: Tests.** `listsUsersWithAllBrandsLabel`, `teamLeadSeesListWithoutActions`, `createAgentShowsTemporaryPassword`, `addExistingPersonFromOtherBrand`, `changeRole`, `removeLastAccessShowsMessage`, `resetPasswordForbiddenShowsMessage`, `switchingBrandRefetches`, `deactivateVisibleOnlyWithCompanyUserManage`.
- [ ] **Step 2–4.** **Step 5: Commit** `feat(console): brand users`

### Task 9: Teams page, docs

**Files:** Create `src/app/api/teams.ts`, `src/app/Teams/TeamsPage.tsx`, `TeamModal.tsx` (name + `DualListSelector` of people with access to the brand); Test `src/app/Teams/teams.test.tsx`. Modify `README.md` (roles, brands, first-login flow, page/authority table).

- [ ] **Step 1: Tests.** `listsTeamsWithMemberCount`, `createTeam`, `editMembersOffersOnlyPeopleInBrand`, `notInWorkspaceShowsMessage`, `withoutTeamManageReadOnly`, `switchingBrandRefetches`.
- [ ] **Step 2–4.** **Step 5: Commit** `feat(console): teams; docs`
