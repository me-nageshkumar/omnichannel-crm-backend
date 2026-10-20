# Authority Management (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Super Admin **Authorities** page (list, search, filter, needs-creating, create,
edit, delete, history) and regroup the role editor by authority group, with search.

**Architecture:**
- A new `src/app/api/authorities.ts` client.
- A new `src/app/Authorities/` page with its modals, built from the existing `admin/` helpers
  (`useLoad`, `useAction`, `ConfirmModal`, `PageShell`).
- `Roles/RoleEditor.tsx` switches from per-level checkbox groups to per-group sections, with a
  search box.

**Tech Stack:** React 18, TypeScript strict, PatternFly 6.5.1 and @patternfly/react-table 6.5.1,
React Router 7, Vitest with Testing Library.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-authority-management-design.md`.
The backend plan is `omnichannel-crm-backend/docs/superpowers/plans/2026-10-20-authorities-backend.md`;
this plan consumes its API exactly.

## Global Constraints

- Repo: `omnichannel-crm-frontend`. Work on branch `feature/authorities` from `main`.
  - Use a plain branch in the main checkout, not a `.worktrees` worktree; ESLint cascades into
    the parent config there.
- **Status copy:** label text is "Not checked" and "Wrong level". Active has no label.
  - Tooltip for Not checked: "No code checks this yet. It has no effect until it does."
  - Tooltip for Wrong level, built from the level: "Checked as a brand authority in code but
    created as Platform." or "Checked as a platform authority in code but created as Brand."
- **Typed-code warning:** "No code checks `<code>` yet. It will have no effect until it does."
- **Validation**, the same patterns as the backend, checked before submit:
  - code `^[a-z0-9]+(-[a-z0-9]+)*:[a-z0-9]+(-[a-z0-9]+)*$`, at most 64 characters
  - group `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 40 characters
  - description 1–200 characters after trimming
- The code goes in URLs as `encodeURIComponent(code)`.
- **New error copy** in `api/messages.ts`: `AUTHORITY_IN_USE` → "This authority is in a role:
  <roles>. Remove it from those roles first." Use the reply's `roles`.
- Nav: **Authorities** goes in the Platform group directly after Roles, at `requires: (a) =>
  a.superAdmin`, path `/authorities`.
- The gate is `npm run ci-checks` (type-check, lint, tests with coverage) plus `npm run build`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Format only the files you touch: `npx prettier --write <files>`, never `src` as a whole.

## Review Focus

1. **The catalogue hasn't loaded.** The role editor must keep Save disabled until authorities load,
   as today, and grouping must not drop selected codes the catalogue doesn't list. (Task 4 test
   `keepsSelectedCodesMissingFromCatalogue`.)
2. **The search hides a selected authority.** Saving must still send it. (Task 4 test
   `savingWhileFilteredKeepsHiddenSelections`.)
3. **A code with a colon.** The URL must be encoded: `PUT /api/authorities/customer%3Aview`. (Task
   3 test `editSendsEncodedCode`.)
4. **Picking a needs-creating code, then changing the code text.** The suggested level must not
   stay applied to a different code silently. Once the text no longer matches the pick, the
   wrong-level check runs against the new text. (Task 3 test `retypedCodeDropsScanContext`.)
5. **A 409 `AUTHORITY_IN_USE` on delete or level change.** It must show the role names, not a
   generic error. (Task 3 test `deleteInUseShowsRoles`.)

---

### Task 1: API client and messages

**Files:**
- Create: `src/app/api/authorities.ts`
- Modify: `src/app/api/roles.ts`. Remove `Authority` and `listAuthorities`, and re-export them from
  `authorities.ts` so existing imports keep working.
- Modify: `src/app/api/messages.ts`, `src/app/api/client.ts` (`ApiError.roles?: string[]`)
- Test: `src/app/api/authorities.test.ts`

**Interfaces:**
- Produces:
  - `type AuthorityStatus = 'ACTIVE' | 'NOT_CHECKED' | 'WRONG_LEVEL'`
  - `interface Authority`, with fields:
    - `code`, `group`, `level: RoleLevel`, `description`
    - `builtIn: boolean`
    - `status: AuthorityStatus`
    - `checkedIn: string[]`
    - `roles: string[]`
    - `updatedAt: string | null`, `updatedBy: string | null`
  - `interface NeedsCreating { code; suggestedLevel: RoleLevel | null; suggestedGroup: string;
    checkedIn: string[] }`
  - `interface AuthorityChange { code; action: 'CREATED' | 'UPDATED' | 'DELETED'; groupBefore;
    groupAfter; levelBefore; levelAfter; descriptionBefore; descriptionAfter; changedBy; at }`.
    The before and after fields may be null.
  - `interface AuthorityInput { group: string; level: RoleLevel; description: string }`
  - Calls:
    - `listAuthorities(token)`
    - `listNeedsCreating(token)`
    - `createAuthority(token, {code, ...AuthorityInput})`
    - `updateAuthority(token, code, AuthorityInput)`
    - `deleteAuthority(token, code)`
    - `authorityHistory(token, code)`
  - `CODE_PATTERN`, `GROUP_PATTERN`, and `validateAuthority(input: {code?: string} &
    AuthorityInput): Record<string, string>`, which returns field → message.
  - `groupOf(code: string): string`, the prefix before `:`.

- [ ] **Step 1: Write the failing tests**
  - `editSendsEncodedCode` (here, at the client level): `updateAuthority(t, 'customer:view',
    …)` requests `PUT /api/authorities/customer%3Aview`. Use `mockFetch` and `requestTo`.
  - `validateAuthority`:
    - `Customer:View` → a `code` error
    - `a:b` → no error
    - group `cust omer` → a `group` error
    - description `'  '` → a `description` error
    - a 201-character description → a `description` error
  - `groupOf('company-user:manage')` → `'company-user'`.
  - `MESSAGES` (or the error-to-message function in `messages.ts`) turns an `AUTHORITY_IN_USE`
    error with `roles: ['AGENT', 'TEAM_LEAD']` into text containing "AGENT, TEAM_LEAD".

- [ ] **Step 2: Run to fail**

Run: `npx vitest run src/app/api/authorities.test.ts`
Expected: FAIL, because the module is missing.

- [ ] **Step 3: Implement.** Follow `roles.ts` for call style.

- [ ] **Step 4: Run to pass, then `npm run type-check`**

Expected: PASS, and no type errors. The role editor still compiles, because the new `Authority` is
a superset.

- [ ] **Step 5: Commit**

```bash
git add src/app/api
git commit -m "feat(console): authorities API client"
```

---

### Task 2: Authorities page: list, search, filter, needs-creating panel

**Files:**
- Create: `src/app/Authorities/AuthoritiesPage.tsx`
- Create: `src/app/Authorities/AuthorityStatusLabel.tsx`
- Modify: `src/app/routes.tsx` (route and nav entry)
- Test: `src/app/Authorities/authorities.test.tsx`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `AuthorityStatusLabel({ authority })`: renders nothing for ACTIVE, otherwise a PF `Label` with
    a `Tooltip` carrying the Global Constraints copy. Task 4 reuses it.
  - `AuthoritiesPage` exposes the hooks Task 3 fills in: an `onCreate(prefill?: NeedsCreating)`
    callback, and row actions Edit, History and Delete.

- [ ] **Step 1: Write the failing tests.** The fixtures are a list of 5 authorities:
  - `customer:view` (built-in, ACTIVE, roles `[ACCOUNT_MANAGER]`)
  - `customer:create`
  - `user:manage` (BRAND)
  - `order:refnd` (NOT_CHECKED, not built-in, roles `[]`)
  - `report:run` (WRONG_LEVEL, PLATFORM)

  Needs-creating has `test:probe` with `checkedIn ['ProbeController.probe']`.

  Tests:
  - `navShowsAuthoritiesForSuperAdminOnly`: as SUPER_ADMIN the nav has "Authorities" right after
    "Roles". As ACCOUNT_MANAGER it doesn't, and `/authorities` shows "Page not found".
  - `listsAuthoritiesWithStatus`:
    - The rows show code, group, level label and description.
    - `order:refnd`'s row has "Not checked", and `report:run`'s row has "Wrong level".
    - `customer:view`'s row shows "Built-in" and roles count "1".
  - `searchMatchesCodeGroupAndDescription`:
    - Typing `customer` leaves 2 rows.
    - Typing `refnd` leaves 1.
    - Typing `See the customer` leaves 1.
  - `statusFilter`: choosing "Not checked" leaves only `order:refnd`.
  - `needsCreatingPanel`:
    - The heading reads "Checked in code, not created yet (1)".
    - `test:probe` and `ProbeController.probe` are visible, with a "Create" button.
    - With an empty needs-creating list, the panel is absent.

- [ ] **Step 2: Run to fail**

Run: `npx vitest run src/app/Authorities`
Expected: FAIL.

- [ ] **Step 3: Implement.**
  - Build it on `PageShell` and `useLoad`: two loads, the authorities and needs-creating.
  - Build the table in the shape of `RolesPage`, with PF `Toolbar`, `SearchInput` and a status
    `Select`.

- [ ] **Step 4: Run to pass**

Run: `npx vitest run src/app/Authorities src/app/routes.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Authorities src/app/routes.tsx
git commit -m "feat(console): Authorities page with search, status filter and needs-creating"
```

---

### Task 3: Create, edit, delete and history

**Files:**
- Create: `src/app/Authorities/AuthorityModal.tsx` (create and edit)
- Create: `src/app/Authorities/AuthorityHistoryModal.tsx`
- Create: `src/app/Authorities/Typeahead.tsx`: a small PF `Select` typeahead that allows a typed
  value, following PF6's "typeahead with create" example.
- Modify: `src/app/Authorities/AuthoritiesPage.tsx`
- Test: `src/app/Authorities/authorityModal.test.tsx`

**Interfaces:**
- Consumes:
  - Tasks 1 and 2.
  - `useAction(shownFields)`. Server field errors map to the fields `code`, `group`, `level` and
    `description`; anything else goes to the banner.
  - `ConfirmModal`.
- Produces:
  - `AuthorityModal({ mode: 'create' | 'edit', authority?, prefill?: NeedsCreating, needsCreating:
    NeedsCreating[], groups: string[], onSaved(a: Authority), onCancel })`
  - `Typeahead({ id, label, value, options: {value, description?}[], onChange(value),
    onPick(value) })`. `onPick` fires only when a listed option is chosen; typing fires
    `onChange`.

- [ ] **Step 1: Write the failing tests**
  - `createFromNeedsCreatingPrefills`:
    - Click "Create" on `test:probe`.
    - The dialog opens with Code `test:probe` and Group `test`, and Level empty (the suggestion
      was null). No typed-code warning.
  - `pickingBrandSuggestionSetsLevel`:
    - With needs-creating `x:y` suggestedLevel BRAND, open "Add authority" from the toolbar.
    - Type `x:`, pick option `x:y` → Level shows Brand.
  - `typedUnknownCodeWarns`:
    - Type `order:refnd`, choose Level Platform and type Description `Refund`. The warning "No code checks `order:refnd` yet…" is visible.
    - Save still works: POST body `{code: 'order:refnd', group: 'order', level: 'PLATFORM',
      description: 'Refund'}`. The group defaults from the code's prefix while the group field is
      untouched.
  - `wrongLevelWarningInline`: pick `test:probe` (checked GLOBAL, so suggestedLevel null) and choose
    Level Brand → the inline wrong-level warning is visible.
  - `retypedCodeDropsScanContext`:
    - Pick `x:y` (BRAND suggestion), then change the code to `x:z`.
    - The level stays as chosen, but the typed-code warning now shows for `x:z`, and no
      wrong-level warning refers to `x:y`.
  - `clientValidationBlocksSubmit`: code `Bad Code` and Save → a field error on Code, and no POST.
  - `duplicateShowsOnCodeField`: POST returns 409 `DUPLICATE`, `field: 'code'` → the error appears
    under Code.
  - `editBuiltInDisablesLevel`:
    - Edit `customer:view`. Code is read-only.
    - Level is disabled with helper text "Built-in authorities keep their level."
    - Delete in the row actions is disabled.
  - `editInUseDisablesLevel`: for a non-built-in with `roles ['AGENT']`, Level is disabled with
    "In a role (AGENT). Remove it from roles to change its level."
  - `editSendsEncodedCode`: saving an edit of `customer:view` → `PUT
    /api/authorities/customer%3Aview` with `{group, level, description}`.
  - `deleteConfirmsAndRemovesRow`:
    - Delete `order:refnd` and confirm → `DELETE /api/authorities/order%3Arefnd`.
    - The row is gone.
  - `deleteInUseShowsRoles`: DELETE returns 409 `AUTHORITY_IN_USE` with `roles ['AGENT']` → the
    banner text contains "AGENT".
  - `historyLists`: History on `customer:view` shows entries newest first, with the action, who,
    and a before→after for changed fields.

- [ ] **Step 2: Run to fail**

Run: `npx vitest run src/app/Authorities`
Expected: the new tests FAIL.

- [ ] **Step 3: Implement.**
  - The group typeahead offers the distinct groups of the loaded authorities.
  - After a create, the page reloads needs-creating, so the created code leaves the panel.

- [ ] **Step 4: Run to pass**

Run: `npx vitest run src/app/Authorities`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Authorities
git commit -m "feat(console): create, edit, delete and history for authorities"
```

---

### Task 4: Role editor grouped by authority group, with search

**Files:**
- Modify: `src/app/Roles/RoleEditor.tsx`
- Test: `src/app/Roles/roles.test.tsx`. Update the fixtures to the new `Authority` shape and add
  tests.

**Interfaces:**
- Consumes: `Authority.group` and `status` (Task 1), `AuthorityStatusLabel` (Task 2).

- [ ] **Step 1: Write the failing tests**
  - Fixtures: add `group` (the prefix), `status: 'ACTIVE'`, `builtIn`, `checkedIn: []` and
    `roles: []` to `AUTHORITIES`. Add `order:refnd` (BRAND, NOT_CHECKED, group `order`).
  - `groupsWithCounts`:
    - Edit WORKSPACE_MANAGER (BRAND, which holds `user:view`, `user:manage` and `team:manage`).
    - The section headings are "order 0 of 1", "team 1 of 1" and "user 2 of 2".
    - Groups are shown alphabetically, and only authorities at the levels the role may hold.
  - `searchFiltersAcrossGroupCodeDescription`:
    - Typing `team` leaves only the team section.
    - Typing `See users` leaves `user:view` only.
    - Typing `user` leaves the whole user group.
  - `statusLabelInEditor`: `order:refnd` shows "Not checked".
  - `savingWhileFilteredKeepsHiddenSelections`:
    - Filter to `team`, then untick `team:manage` and Save.
    - The PUT body's `authorities` is `['user:manage', 'user:view']`: the hidden selections are
      kept.
  - `keepsSelectedCodesMissingFromCatalogue`:
    - The role holds `gone:code`, which isn't in the catalogue.
    - Save sends it back unchanged, and the editor shows no checkbox for it.
  - The existing tests (preview warning, Save disabled until the catalogue loads, level rules) keep
    passing.

- [ ] **Step 2: Run to fail**

Run: `npx vitest run src/app/Roles`
Expected: the new tests FAIL.

- [ ] **Step 3: Implement.**
  - Use one `SearchInput` above the list.
  - Each group is a `FormFieldGroup` whose header text is `${group}` and whose description is
    `${n} of ${m} selected`. The test matches heading text with a regex, so the exact markup is
    free.
  - A group whose name matches the search shows all its authorities.
  - `selected` stays the source of truth; filtering only hides rows.

- [ ] **Step 4: Run to pass**

Run: `npx vitest run src/app/Roles`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Roles
git commit -m "feat(console): role editor groups and searches authorities"
```

---

### Task 5: README and the full gate

**Files:**
- Modify: `README.md`. Add an "Authorities page" paragraph to the roles section: what each status
  means, and the create-from-code flow.

- [ ] **Step 1: Write the README paragraph.**

- [ ] **Step 2: Run the gate**

Run: `npm run ci-checks && npm run build`
Expected: type-check, lint and every test pass, and the build succeeds.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: Authorities page"
```
