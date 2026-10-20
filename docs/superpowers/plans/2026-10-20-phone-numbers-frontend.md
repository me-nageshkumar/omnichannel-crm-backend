# Phone Numbers and Enable/Disable (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "Enabled/Disabled" with level-aware Enable everywhere; a Users tab on Customer detail;
brand enable/disable; per-brand access disable on the Users page; a brand **Phone numbers** page with
connect codes; a platform **Phone numbers** page.

**Architecture:** API clients in `src/app/api/` (`enablement.ts`, `phoneNumbers.ts`, edits to
existing ones); one shared `admin/EnabledLabel.tsx` replacing `admin/StatusLabel.tsx`; new
`src/app/PhoneNumbers/` (brand page, platform page, modals) built from `useLoad`, `useAction`,
`ConfirmModal`, `PageShell`.

**Tech Stack:** React 18, TypeScript strict, PatternFly 6.5.1, @patternfly/react-table 6.5.1,
React Router 7, Vitest + Testing Library, `mockFetch` route keys `"METHOD /path"`.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-phone-numbers-and-enable-disable-design.md`.
Consumes exactly the **API contract** section of
`omnichannel-crm-backend/docs/superpowers/plans/2026-10-20-phone-numbers-backend.md`.

## Global Constraints

- Repo `omnichannel-crm-frontend`, branch `feature/phone-numbers` in the main checkout (no
  `.worktrees`: ESLint cascades into the parent config there).
- Wording: buttons/menu items "Disable" / "Enable"; label "Enabled" (green) / "Disabled" (grey).
  No "Deactivate", "Reactivate", "Active", "Inactive" or "Block" left in `src` (tests included).
- Disabled label tooltip: `Disabled by <by> (<level text>) on <date>` with level text
  PLATFORM → "platform staff", COMPANY → "Customer Admin", BRAND → "brand manager".
- `messages.ts` additions: `disabled_at_higher_level` → use the server's message;
  `invalid_connect_code` → "Invalid or expired connect code."; `disabled` → "This number, its brand
  or its company is disabled."; `last_access` copy becomes "This is the person's only brand. Disable
  them instead."
- Status labels for numbers: DISABLED "Disabled (number|brand|company)", NOT_CONNECTED_YET "Not
  connected yet", WAITING_FOR_LOGIN "Waiting for WhatsApp login", CONNECTED "Connected",
  WRONG_NUMBER "Wrong number: +<digits>", DISCONNECTED "Disconnected · last seen <relative>".
- Numbers display as `+` followed by the digits (no further grouping). Number input accepts
  `+`, spaces and dashes and strips them before validating `^[1-9][0-9]{7,14}$`; hint "Country
  code first, e.g. 919812345678".
- Nav: **Phone numbers** (Brand group, after Teams, `requires: a => a.hasInWorkspace('phone-number:view')`,
  path `/phone-numbers`); **All phone numbers** (Platform group, after Customers,
  `requires: a => a.has('phone-number:view-all')`, path `/platform/phone-numbers`).
- Workspace switcher hides brands whose `disabled` is non-null; the Brands page shows them.
- Gate: `npm run ci-checks` plus `npm run build`. Format only touched files with
  `npx prettier --write <files>`. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Enable offered when it will be refused.** The UI cannot know the actor's level reliably, so
   Enable is always offered and a 403 shows the server message inline. (Task 1
   `enableRefusedShowsServerMessage`.)
2. **Connect code shown after the modal closes.** The code must stay visible with its expiry and a
   copy button until the user closes it; re-opening fetches a new code and says the old one stopped
   working. (Task 4 `connectCodeModalShowsCodeAndExpiry`, `newCodeWarnsOldStops`.)
3. **Stale status.** Status on the brand page refreshes every 10 s while the page is open and
   stops on unmount. (Task 4 `pollsStatusAndStopsOnUnmount`.)
4. **Disabled brand still selected.** The stored brand is disabled → switcher falls back to the
   first enabled one. (Task 2 `switcherSkipsDisabledBrand`.)
5. **Number pasted with +, spaces.** `+91 99280-88743` saves as `919928088743`. (Task 4
   `numberInputNormalises`.)

---

### Task 1: Enable/disable wording, API and label everywhere

**Files:** Create `src/app/api/enablement.ts`, `src/app/admin/EnabledLabel.tsx`; delete
`src/app/admin/StatusLabel.tsx`; modify `api/customers.ts`, `api/platformUsers.ts`,
`api/workspaceUsers.ts`, `api/users.ts`, `api/messages.ts`, `Customers/CustomerDetailPage.tsx`,
`Customers/CustomersPage.tsx`, `PlatformUsers/PlatformUsersPage.tsx`, `Users/UsersPage.tsx` and their tests.

**Interfaces:**
- Produces: `type EnableLevel = 'PLATFORM' | 'COMPANY' | 'BRAND'`; `interface Disabled { by: string; level: EnableLevel; at: string }`;
  `levelText(level)`; `EnabledLabel({ disabled }: { disabled: Disabled | null })`; `UserSummary.disabled`,
  `Customer.disabled`; `setCustomerEnabled(token, id, enabled)`, `setPlatformUserEnabled(token, id, enabled)`,
  `setCompanyUserEnabled(token, userId, enabled)` calling `/disable|/enable`.
- [ ] **Step 1: Failing tests:** in each page test, the action reads "Disable"/"Enable" and posts to
  the new path; `EnabledLabel` tooltip text for each level; `enableRefusedShowsServerMessage`
  (403 `DISABLED_AT_HIGHER_LEVEL` → server message shown in the confirm dialog).
- [ ] **Step 2: Run** `npx vitest run src/app/Customers src/app/PlatformUsers src/app/Users src/app/admin` — Expected: FAIL.
- [ ] **Step 3: Implement.** `grep -rn "Deactivat\|Reactivat\|Inactive" src` returns nothing.
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: enable/disable wording and level label`.

### Task 2: Customer Users tab and brand enable/disable

**Files:** Modify `api/customers.ts` (`listCustomerUsers`, `setCustomerUserEnabled`,
`setCustomerWorkspaceEnabled`; `CustomerDetail.workspaces: Workspace[]`), `api/workspaces.ts`
(`Workspace.disabled`, `setWorkspaceEnabled`), `api/users.ts` (`WorkspaceAccess.disabled`),
`Customers/CustomerDetailPage.tsx`, `Brands/BrandsPage.tsx`, `AppLayout/WorkspaceSwitcher.tsx`,
`session/SessionContext.tsx`; tests `customers.test.tsx`, `brands.test.tsx`, `workspaceSwitcher.test.tsx`.

**Interfaces:**
- Customer detail gets PF `Tabs`: "Brands" (existing list + `EnabledLabel` + Disable/Enable for
  `customer:manage`), "Owners" (existing), "Users" (new: name, username, role(s), `EnabledLabel`,
  Disable/Enable for `customer:manage`).
- Brands page: `EnabledLabel` column; Disable/Enable in the actions menu when `has('workspace:enable')`.
- [ ] **Step 1: Failing tests:** `usersTabListsAllCustomerUsers`, `accountManagerDisablesAgent`,
  `brandDisableFromCustomerDetail`, `brandsPageDisablesBrand`, `switcherSkipsDisabledBrand`.
- [ ] **Step 2: Run** `npx vitest run src/app/Customers src/app/Brands src/app/AppLayout` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: customer users tab and brand enable/disable`.

### Task 3: Brand access disable on the Users page

**Files:** Modify `api/workspaceUsers.ts` (`brandDisabled`, `setBrandAccessEnabled(token, workspaceId, userId, enabled)`),
`Users/UsersPage.tsx`, `users.test.tsx`.

**Interfaces:** Users page shows a second label "Disabled in this brand" when `brandDisabled`;
actions "Disable in this brand" / "Enable in this brand" when `hasInWorkspace('user:enable')`; the
whole-login Disable/Enable stays for `company-user:manage` holders. Confirm copy: "<name> loses
access to <brand> until enabled. Their other brands are not affected."
- [ ] **Step 1: Failing tests:** `managerDisablesAgentInBrand`, `brandDisabledLabelShown`,
  `actionHiddenWithoutUserEnable`.
- [ ] **Step 2: Run** `npx vitest run src/app/Users` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: disable a person's access to a brand`.

### Task 4: Brand Phone numbers page

**Files:** Create `api/phoneNumbers.ts`, `PhoneNumbers/PhoneNumbersPage.tsx`,
`PhoneNumbers/PhoneNumberModal.tsx`, `PhoneNumbers/ConnectCodeModal.tsx`,
`PhoneNumbers/PhoneNumberStatusLabel.tsx`, `PhoneNumbers/phoneNumbers.test.tsx`; modify `routes.tsx`, `routes.test.tsx`.

**Interfaces:**
- Produces (`phoneNumbers.ts`): `PhoneNumber` (contract `PhoneNumberView`), `PhoneNumberStatus`,
  `normaliseNumber(input) → string`, `NUMBER_PATTERN`, `listBrandNumbers`, `addNumber`, `relabelNumber`,
  `deleteNumber`, `setNumberEnabled(token, workspaceId, id, enabled)`, `getConnectCode`,
  `revokeDevice`, `listAllNumbers(token, {q, status, page})`, `setAnyNumberEnabled`, `deleteAnyNumber`.
- Page: table (number, label, status label, last seen, actions: Edit label, Get connect code,
  Revoke device [only `hasDevice`], Disable/Enable, Delete); "Add number" primary button; actions
  only with `hasInWorkspace('phone-number:manage')`.
- Connect code modal: big monospace code, "Expires in mm:ss" countdown, Copy button, and the steps
  "1. Open WhatsApp Web in Chrome and log in to +<number>. 2. Click the extension icon, enter the
  server address and this code." When `hasDevice` is true, before fetching: confirm "A new code
  replaces the connected device when it is used."
- Delete confirm: "Delete +<number>? Its device is disconnected and its connect code stops working."
- [ ] **Step 1: Failing tests:** `listsNumbersWithStatus` (one per status text), `addNumberValidates`,
  `numberInputNormalises`, `duplicateShowsFieldError`, `connectCodeModalShowsCodeAndExpiry`,
  `newCodeWarnsOldStops`, `revokeOnlyWhenDevice`, `teamLeadSeesNoActions`, `pollsStatusAndStopsOnUnmount`
  (fake timers), `routeVisibleWithPhoneNumberView`.
- [ ] **Step 2: Run** `npx vitest run src/app/PhoneNumbers src/app/routes.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: brand phone numbers page with connect codes`.

### Task 5: Platform phone numbers page and README

**Files:** Create `PhoneNumbers/AllPhoneNumbersPage.tsx`, `PhoneNumbers/allPhoneNumbers.test.tsx`;
modify `routes.tsx`, `routes.test.tsx`, `README.md`.

**Interfaces:** Table (number, customer, brand, label, status, last seen, actions Disable/Enable,
Delete when `has('phone-number:manage-all')`); SearchInput "Search by number, company or brand"
(debounced 300 ms, server `q`); status Select ("Status: All" + six statuses); pagination 50 per page.
- [ ] **Step 1: Failing tests:** `listsAcrossCustomers`, `searchSendsQ`, `statusFilterSendsStatus`,
  `pagination`, `disableAsPlatform`, `viewOnlyHasNoActions`, route test.
- [ ] **Step 2: Run** `npx vitest run src/app/PhoneNumbers src/app/routes.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement**; README rows for both pages and the enable/disable rule.
- [ ] **Step 4: Run** `npm run ci-checks && npm run build` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: platform phone numbers page`.
