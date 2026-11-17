# Subscriptions phase 1 (entitlements), console: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff can build feature groups and plans and assign a plan to a customer; a Customer Admin can see the plan and usage; every user of a customer sees a banner in grace and read-only, and a clear message when the plan refuses something.

**Architecture:** One API module (`src/app/api/billing.ts`) mirrors the backend contract. Staff pages live in `src/app/Billing/platform`, the customer page in `src/app/Billing/my`, shared pieces in `src/app/Billing/common`. The session already carries plan-limited authorities, so existing route and button gating needs no change; the session only gains the subscription summary and refetches itself when the server answers 402.

**Tech Stack:** React 18, TypeScript (`tsconfig` target `es5`: use `Array.from`, not spread of a `Set`), PatternFly 6.5, vitest with `mockFetch` and `renderApp`.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` (section 12, phase 1 parts) in the backend repo.

**Contract:** the section "API contract (phase 1)" of `docs/superpowers/plans/2026-11-17-subscriptions-1-entitlements-backend.md` in the backend repo. Read it before Task 1; the types below are that contract in TypeScript. Build this plan after the backend plan's Task 10, so the endpoints exist for the walk in Task 8.

## Global Constraints

- Work only in `omnichannel-crm-frontend/.worktrees/subscriptions` (branch `subscriptions`; create it from `main` and run `npm ci --prefer-offline`). No merge, push or deploy: the user asks for those.
- Test first: each behaviour starts with a failing vitest test, run and seen to fail. Run tests with `npx vitest run <path> --maxWorkers=4`.
- **No money arithmetic in the browser.** Totals, discounts and prices come from the server (`POST /api/billing/plans/price`, `PlanView`). The console only formats, with `formatMoney(amount, 'INR')` from `src/app/Crm/common/money.ts`.
- Term dates are instants at midnight in the billing zone. Show them with one helper, `billingDate(iso, zone, minusDays = 0)` in `src/app/Billing/common/billingDate.ts` (create it in Task 1 with a test: `billingDate('2026-12-31T18:30:00Z', 'Asia/Kolkata')` → "1 Jan 2027", with `minusDays 1` → "31 Dec 2026"), which formats like `formatDate` ("1 Oct 2026") but in the `zone` the server sent, never the browser's.
- Follow the existing patterns: `PageShell`, `useLoad`, `useAction(FIELDS)` for server field errors, `ConfirmModal`, inline `Alert`s (there is no toast service), PF `Table variant="compact"`.
- Prettier on touched files only. Before finishing: `npx tsc --noEmit && npm run lint && npm run build`.
- Wording: "plan", "feature group", "add-on", "limit". Never "template" for anything here.
- Commit trailer as the session reminder says.

## Review Focus

1. A 402 `SUBSCRIPTION_EXPIRED` arriving while a user works: the session refetches `/users/me` once, the banner appears, and the failed action shows the plan message, not "Something went wrong" (Task 7).
2. A customer whose plan lacks a feature: its pages are absent from the nav and the URL shows the 404 page as for any forbidden route; nothing crashes when `workspace.authorities` is small (Task 7).
3. A plan editor row switched from "Included" to "Add-on" and back keeps its price; a group that `requires` another cannot be included without it and the form says which (Task 4).
4. Limits shown as "3 of 5", "7 of unlimited", and "12 of 10" (over the cap after a downgrade) without a broken progress bar (Task 6).
5. An account manager (reads plans, cannot edit them) sees the Plans page without edit controls; a Super Admin without `subscription:manage` in a test fixture does not see Assign (Tasks 3 and 5).

---

### Task 1: API module, fixtures and session summary

**Files:**
- Create: `src/app/api/billing.ts`, `src/test/billing.ts`
- Modify: `src/app/api/users.ts` (`CurrentUser`), `src/app/api/customers.ts` (`Customer`, `CustomerQuery`), `src/test/session.ts` (personas)
- Test: `src/app/api/billing.test.ts`

**Interfaces (Produces):**
```ts
export type GroupCategory = 'DEFAULT' | 'PAID';
export type BillingPeriod = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY';
export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';
export type SubscriptionState = 'NONE' | 'ACTIVE' | 'GRACE' | 'EXPIRED';
export interface FeatureGroup { code: string; name: string; description: string; category: GroupCategory;
  authorities: string[]; requires: string[]; builtIn: boolean; sortOrder: number; version: number; planCodes: string[] }
export interface PlanItem { groupCode: string; price: number }
export interface LimitAddOn { key: string; unitPrice: number; unit: number }
export interface PlanInput { code: string; name: string; description: string; period: BillingPeriod; rank: number;
  items: PlanItem[]; addOns: PlanItem[]; limits: Record<string, number>; limitAddOns: LimitAddOn[];
  discountPercent: number; rates: Record<string, number>; offered: boolean }
export interface Plan extends PlanInput { status: PlanStatus; revision: number; version: number;
  actualPrice: number; discountAmount: number; discountedPrice: number; customers: number }
export interface Price { actualPrice: number; discountAmount: number; discountedPrice: number }
export interface BillingSettings { zone: string; graceDays: number; customersWithoutPlan: 'UNRESTRICTED' | 'READ_ONLY'; version: number }
export interface LimitKeyInfo { key: string; scope: 'CUSTOMER' | 'BRAND' | 'CHATBOT'; label: string }
export interface SubscriptionAddOnInput { kind: 'GROUP' | 'LIMIT'; groupCode?: string; limitKey?: string; quantity?: number }
export interface Subscription { state: SubscriptionState; readOnly: boolean;
  plan: { code: string; name: string; period: BillingPeriod; rank: number } | null;
  termStart: string | null; termEnd: string | null; graceEnd: string | null;
  groups: { code: string; name: string; source: 'DEFAULT' | 'PLAN' | 'ADDON' }[];
  addOns: (SubscriptionAddOnInput & { id: string })[];
  limits: { key: string; label: string; scope: string; limit: number | null; used: number | null }[];
  zone: string; revision: number }
export interface BillingEvent { id: string; kind: string; by: string; byName: string; at: string; summary: string }
export interface SubscriptionSummary { state: SubscriptionState; planName: string | null; termEnd: string | null;
  graceEnd: string | null; readOnly: boolean; zone: string }
```
Functions, each `(token, …) => Promise<…>` through `apiFetch` like `src/app/api/customers.ts`: `listGroups`, `createGroup`, `updateGroup(code, body)`, `deleteGroup`, `listUngrouped`, `groupHistory`, `listPlans(status?)`, `getPlan`, `createPlan`, `updatePlan(code, body & {version})`, `pricePlan({items, discountPercent})`, `duplicatePlan(code, {code, name, period})`, `activatePlan`, `retirePlan`, `deletePlan`, `planHistory`, `listLimitKeys`, `getBillingSettings`, `putBillingSettings`, `getCustomerSubscription(customerId)`, `putCustomerSubscription(customerId, {planCode, startDate, endDate, addOns, revision})`, `deleteCustomerSubscription`, `customerSubscriptionEvents`, `getMySubscription`.
`CurrentUser.subscription: SubscriptionSummary | null`. `Customer.planName: string | null`, `Customer.subscriptionState: SubscriptionState`; `CustomerQuery.noPlan?: boolean`.
`PERIOD_LABEL: Record<BillingPeriod, string>` = Monthly, Quarterly, Half-yearly, Yearly.
Fixtures in `src/test/billing.ts`: `group(over?)`, `plan(over?)`, `subscription(over?)`, `SEEDED_GROUPS` (the nine), `billingRoutes(over?)` returning the `mockFetch` routes for every GET above. Personas in `src/test/session.ts`: `SUPER_ADMIN` gains `plan:manage`, `billing-settings:manage`, `subscription:view`, `subscription:manage`; `ACCOUNT_MANAGER` gains `subscription:view`, `subscription:manage`; `CUSTOMER_ADMIN` gains `billing:view`; every customer persona gains `subscription: {state: 'NONE', planName: null, termEnd: null, graceEnd: null, readOnly: false, zone: 'Asia/Kolkata'}`, platform personas `subscription: null`.

- [ ] **Step 1: Write the failing test** `billing.test.ts`: `listPlans(token, 'ACTIVE')` requests `GET /api/billing/plans?status=ACTIVE`; `updatePlan` sends `PUT /api/billing/plans/gold-monthly` with `version` in the body; `putCustomerSubscription` sends `PUT /api/billing/customers/c1/subscription`; `pricePlan` posts `{items, discountPercent}` to `/api/billing/plans/price`; `listCustomers(token, {noPlan: true})` adds `noPlan=true`.
- [ ] **Step 2: Run** `npx vitest run src/app/api/billing.test.ts --maxWorkers=4`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement** the module, the type additions and the fixtures.
- [ ] **Step 4: Run it again; expected PASS.** Run `npx vitest run src/app/Customers src/app/session --maxWorkers=4`; expected PASS (personas still valid).
- [ ] **Step 5: Commit** `feat(billing): billing API module and fixtures`.

### Task 2: Authority picker shared with the role editor

**Files:**
- Create: `src/app/admin/AuthorityPicker.tsx`
- Modify: `src/app/Roles/RoleEditor.tsx` (use it; behaviour unchanged)
- Test: `src/app/admin/authorityPicker.test.tsx`; the existing role editor tests must pass untouched

**Interfaces (Produces):**
`AuthorityPicker {authorities: Authority[]; selected: string[]; onChange(next: string[]): void; disabledCodes?: Record<string, string>; idPrefix: string}`: the search box, the `FormFieldGroup` per `Authority.group` with "N of M selected", and a `Checkbox` per authority, exactly as `RoleEditor.tsx:189-224` renders today. `disabledCodes` maps a code to the reason shown beside a disabled checkbox (used for "already in group CRM").

- [ ] **Step 1: Write the failing test:** renders two groups; ticking a box calls `onChange` with the code added; search "export" hides non-matching rows; a code in `disabledCodes` is disabled and shows its reason.
- [ ] **Step 2: Run** `npx vitest run src/app/admin/authorityPicker.test.tsx --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Extract** the component and use it in `RoleEditor`.
- [ ] **Step 4: Run** `npx vitest run src/app/admin src/app/Roles --maxWorkers=4`. Expected: PASS.
- [ ] **Step 5: Commit** `refactor(roles): shared authority picker`.

### Task 3: Plans page, feature groups tab

**Files:**
- Create: `src/app/Billing/platform/PlansPage.tsx`, `FeatureGroupsTab.tsx`, `FeatureGroupModal.tsx`, `BillingHistoryModal.tsx`, `src/app/Billing/routes.tsx`
- Modify: `src/app/routes.tsx` (spread the billing routes; nav entry "Plans" in section 3)
- Test: `src/app/Billing/platform/featureGroups.test.tsx`

**Interfaces (Produces):**
- Route `/platform/plans`, `requires: a => a.has('plan:manage') || a.has('subscription:view')`; tabs "Plans" (Task 4) and "Feature groups" by `?tab=`.
- Feature groups tab: a warning `Alert` "N permissions are in no group, so no customer on a plan can use them" with the codes, when `listUngrouped` is not empty. Table: Name, Code, Category label (Default | Paid), Permissions (count), Requires (names), Plans (count). Row actions with `plan:manage`: Edit, History, Delete (hidden for built-in `core`; `ConfirmModal`; 409 `GROUP_IN_USE` shows "Used by plans: …" from `error.extra.plans`).
- `FeatureGroupModal`: Code (new only), Name, Description, Category radio (disabled for `core`), Requires (multi-select of other groups), `AuthorityPicker` over COMPANY and BRAND authorities with `disabledCodes` for authorities in another group ("In group <name>") and for `core`'s built-ins on `core` ("Always in Core"). Server field errors via `useAction(['code','name','category','authorities','requires'])`.
- `BillingHistoryModal {title, load(token) => Promise<BillingEvent[]>}`: table When, Who, What.

- [ ] **Step 1: Write the failing tests:** as `SUPER_ADMIN` the tab lists the nine groups; the ungrouped warning shows `crm:export` when the route returns it; creating posts the body and the new row appears; an authority of another group is disabled with "In group CRM"; a 400 with `fields.authorities` shows under the picker; delete of a used group shows the plans; as `ACCOUNT_MANAGER` no Add or row actions except History.
- [ ] **Step 2: Run** `npx vitest run src/app/Billing/platform/featureGroups.test.tsx --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): feature groups page`.

### Task 4: Plans tab and plan editor

**Files:**
- Create: `src/app/Billing/platform/PlansTab.tsx`, `PlanEditorPage.tsx`, `PlanGroupRows.tsx`, `PlanLimitRows.tsx`, `PriceSummary.tsx`, `DuplicatePlanModal.tsx`, `planDraft.ts`
- Modify: `src/app/Billing/routes.tsx` (`/platform/plans/new`, `/platform/plans/:code`)
- Test: `src/app/Billing/platform/plans.test.tsx`, `planDraft.test.ts`

**Interfaces (Produces):**
- Plans tab: status filter (All, Draft, Active, Retired); table Name (link), Period, Rank, Actual price, Discount %, Price (discounted), Status label, Customers. "New plan" with `plan:manage`. Row actions: Duplicate, Activate / Retire (per status), History, Delete (Draft only).
- Editor (page, not modal; `registerNavGuard` for unsaved changes as other editors do): Code (new only), Name, Description, Period select, Rank, Offered to customers switch; **Feature groups**: one row per PAID group with a three-way toggle "Included | Add-on | Not available" and a price input (enabled for the first two; the typed price is kept in the draft when the toggle moves to Not available and back); DEFAULT groups listed above as "Always included, free"; **Discount %**; **`PriceSummary`**: "Actual ₹X · Discount ₹Y · Price ₹Z per <period>" from `pricePlan`, called 300 ms after the last change to items or discount, showing the previous figures dimmed while a call is in flight and an inline error if it fails; **Limits**: one row per `LimitKeyInfo` with a number input (blank = unlimited) and, beside it, "Extra units: price ₹ per [unit]" inputs for the limit add-on; **Message rate**: ₹ per WhatsApp message (4 decimals, blank = free). Save → `createPlan` / `updatePlan`; a version conflict offers Reload as `LayerEditor` does. An ACTIVE plan shows the note "Changes apply to customers at their next renewal."
- `planDraft.ts`: `toDraft(plan | null, groups, limitKeys)`, `toInput(draft): PlanInput`, `requiresProblems(draft, groups): Record<groupCode, string>` ("AI needs Chatbots to be included" / "…included or offered as an add-on").
- Read-only rendering (all inputs disabled, no Save) without `plan:manage` and for a RETIRED plan.

- [ ] **Step 1: Write the failing tests.**
`planDraft.test.ts`: `toInput` puts Included rows in `items`, Add-on rows in `addOns`, omits Not available; blank limit omitted from `limits`; a limit add-on only when both price and unit are filled; `requiresProblems` flags `ai` included without `chatbots`, and is empty when `chatbots` is included; toggling a row to Not available and back keeps its price (Review Focus 3).
`plans.test.tsx`: the list shows `formatMoney` of the server's `actualPrice` and `discountedPrice`; the editor's summary shows the figures returned by `POST /plans/price` and **not** a locally computed sum (the mocked response is deliberately different from the sum of the inputs); saving a new plan posts `toInput`'s shape and navigates to the plan; a 400 `fields.rank` shows under Rank; Activate calls the endpoint and the label turns Active; as `ACCOUNT_MANAGER` the editor is read-only (Review Focus 5); Duplicate posts `{code, name, period}`.
- [ ] **Step 2: Run** `npx vitest run src/app/Billing/platform/plans.test.tsx src/app/Billing/platform/planDraft.test.ts --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): plans list and editor`.

### Task 5: Billing settings, and the subscription on the customer pages

**Files:**
- Create: `src/app/Billing/platform/BillingSettingsPage.tsx`, `SubscriptionCard.tsx`, `AssignPlanModal.tsx`, `src/app/Billing/common/{StateLabel,LimitsTable,IncludedGroups}.tsx`
- Modify: `src/app/Billing/routes.tsx` (`/platform/billing-settings`, `requires: a => a.has('billing-settings:manage')`; nav "Billing settings"), `src/app/Customers/CustomerDetailPage.tsx` (card above Brands when `has('subscription:view')`), `src/app/Customers/CustomersPage.tsx` (Plan and State columns; "No plan" added to the toggle group)
- Test: `src/app/Billing/platform/billingSettings.test.tsx`, `subscriptionCard.test.tsx`; extend `src/app/Customers/customers.test.tsx`

**Interfaces (Produces):**
- Settings page: Time zone (text, help "Days begin and end in this zone"), Grace days, "Customers without a plan" radio (Unrestricted | Read-only, with the warning "Read-only stops every customer that has no plan from changing anything"), Save with field errors, success `Alert`.
- `StateLabel {state}`: None grey "No plan", Active green, Grace orange "Grace", Expired red "Read-only".
- `LimitsTable {limits}`: Label, Used, Limit, a PF `Progress` only when both are numbers (value capped at 100, danger variant when used ≥ limit); text "3 of 5", "7 of unlimited", "12 of 10"; `used` null shows "–" (Review Focus 4).
- `IncludedGroups {groups}`: label groups per source: "Included in every plan", "In this plan", "Add-ons".
- `SubscriptionCard {customerId}`: plan name, period, `StateLabel`, "1 Dec 2026 – 31 Dec 2026" (the last day is `termEnd` minus one day), grace end when in grace, `IncludedGroups`, `LimitsTable`, History. With `subscription:manage`: "Assign plan" (none) or "Change plan", and "Remove plan" (`ConfirmModal`, danger).
- `AssignPlanModal {customerId, current: Subscription, onSaved}`: Plan select (ACTIVE plans; shows period and price), Start date (default today), End date (prefilled from the plan's period when the plan or start changes; editable), Add-ons: a checkbox per group the chosen plan lists under `addOns`, and a quantity per limit add-on. Sends `revision`. Field errors for `planCode`, `startDate`, `endDate`, `addOns`; a 409 offers Reload.
- Customers list: Plan column (`planName` or "–"), State column (`StateLabel`).

- [ ] **Step 1: Write the failing tests:** settings load, save, bad zone error under the field, account manager cannot open the route (404 page); the card for state NONE shows "No plan" and Assign; assigning sends `{planCode, startDate, endDate, addOns, revision}` and the card shows the returned plan; dates render "1 Dec 2026 – 31 Dec 2026" for `termStart 2026-11-30T18:30:00Z`, `termEnd 2026-12-31T18:30:00Z` (through `billingDate` with the response's `zone`); Remove calls DELETE; a persona without `subscription:manage` sees no actions (Review Focus 5); the list shows Plan and State and the "No plan" toggle sends `noPlan=true`.
- [ ] **Step 2: Run** `npx vitest run src/app/Billing/platform src/app/Customers --maxWorkers=4`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): billing settings and plan assignment`.

### Task 6: The customer's Plan & billing page

**Files:**
- Create: `src/app/Billing/my/MyBillingPage.tsx`
- Modify: `src/app/Billing/routes.tsx` (`/billing`, `requires: a => a.has('billing:view')`; nav "Plan & billing" in section 3 beside Brands)
- Test: `src/app/Billing/my/myBilling.test.tsx`

**Interfaces:**
- Consumes: `getMySubscription`, `StateLabel`, `LimitsTable`, `IncludedGroups`.
- Produces: `PageShell title="Plan & billing"`; a summary card (plan name and period, state, term dates, "N days left" in ACTIVE, "Renew by <graceEnd>" in GRACE, "Read-only since <graceEnd>" in EXPIRED); "What is included"; "Limits and usage". For state NONE: an `EmptyState` "No plan yet" / "Your account manager assigns the plan." No actions in this phase.

- [ ] **Step 1: Write the failing tests:** as `CUSTOMER_ADMIN` the page shows plan, dates, groups by source and limits "3 of 5"; "12 of 10" renders without error (Review Focus 4); GRACE shows "Renew by 7 Jan 2027"; NONE shows the empty state; `WORKSPACE_MANAGER` gets the 404 page and no nav entry.
- [ ] **Step 2: Run** `npx vitest run src/app/Billing/my --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): plan and usage page for the customer`.

### Task 7: Banner, 402 handling and the shared plan message

**Files:**
- Create: `src/app/Billing/common/SubscriptionBanner.tsx`, `planMessage.ts`
- Modify: `src/app/AppLayout/AppLayout.tsx` (PF `Page` `banner` prop), `src/app/api/client.ts` (a `setPaymentRequiredHandler`, called on any 402 like the 401 handler), `src/app/session/SessionContext.tsx` (register it: `refreshUser()`, at most once per 5 seconds), `src/app/api/messages.ts` (`messageFor`: `subscription_expired`, `subscription_inactive`, `limit_reached`), `src/app/Crm/errors.ts` (its `LIMIT_REACHED` text now comes from `planMessage`)
- Test: `src/app/Billing/common/banner.test.tsx`, `planMessage.test.ts`

**Interfaces (Produces):**
- `SubscriptionBanner`: nothing for platform users, NONE-not-read-only and ACTIVE. GRACE: warning "Your plan ended on <termEnd − 1 day>. Renew by <graceEnd − 1 day> to keep working." EXPIRED or `readOnly`: danger "Your plan has ended. The account is read-only until it is renewed." For users with `billing:view` both end with a link "Plan & billing" to `/billing`; others see "Ask your administrator."
- `planMessage(error: ApiError): string | null`:
  - `SUBSCRIPTION_EXPIRED` → "Your plan has ended, so nothing can be changed until it is renewed."
  - `SUBSCRIPTION_INACTIVE` → "Your plan does not allow this."
  - `LIMIT_REACHED` with `extra.limit` → "Your plan allows <limit> <label>." using a label per `extra.key` (`USERS` → "users", `BRANDS` → "brands", `PHONE_NUMBERS` → "WhatsApp numbers", `AUTOMATION_RULES` → "automation rules", `CHATBOTS` → "chatbots", `AI_PROFILES` → "AI profiles", `CRM_PIPELINES` → "pipelines", `CRM_CUSTOM_FIELDS` → "fields per record type"); without extras → "Your plan's limit has been reached."
  - anything else → null.
  `messageFor` asks `planMessage` first.

- [ ] **Step 1: Write the failing tests.**
`planMessage.test.ts`: each code above, with and without extras; an unrelated error → null.
`banner.test.tsx`: `renderApp('/contacts')` with `meAs` a customer persona in GRACE shows the grace text and, for `CUSTOMER_ADMIN`, the link; EXPIRED shows the read-only text; ACTIVE and platform personas show none. Review Focus 1: the persona starts ACTIVE, a mocked `POST` answers 402 `SUBSCRIPTION_EXPIRED`, `/api/users/me` now returns EXPIRED → the banner appears, the form shows "Your plan has ended, so nothing can be changed until it is renewed.", and `/api/users/me` was requested exactly twice even when two 402s arrive together. Review Focus 2: a persona whose `workspaces[0].authorities` is `['inbox:view']` renders the app, has no Campaigns nav entry, and `/campaigns` shows the 404 page.
- [ ] **Step 2: Run** `npx vitest run src/app/Billing/common --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.** Then `npx vitest run src/app/Crm/Settings src/app/session src/app/AppLayout --maxWorkers=4`; expected PASS.
- [ ] **Step 5: Commit** `feat(billing): plan banner and plan messages`.

### Task 8: Whole-branch checks

- [ ] **Step 1:** `npx vitest run --maxWorkers=4 > /tmp/claude-501/subscriptions-console.log 2>&1; tail -30 /tmp/claude-501/subscriptions-console.log`. Expected: all pass. A CRM test (`contactDrawer`, `companies`, `addContact`) that fails under load and passes alone is reported by name as flaky.
- [ ] **Step 2:** `npx tsc --noEmit && npm run lint && npm run build`. Expected: no errors.
- [ ] **Step 3: Stop.** Do not merge, push or deploy. Report tasks done, rulings and deferred minors. The walk on 8089 (assign a plan without CRM to the demo customer, check the CRM disappears for every user, re-add it, set a past end date and check read-only) is done with the user after they ask for the deploy.
