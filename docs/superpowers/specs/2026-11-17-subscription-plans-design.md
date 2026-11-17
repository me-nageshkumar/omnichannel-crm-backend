# Subscription plans for customers: entitlements, pricing, requests, invoices and a usage wallet

Design, 2026-11-17. Built in three phases, each deployable on its own (section 14). "(my call)" marks a decision the
user did not make; the list in section 15 collects the ones worth checking.

## 1. Intent

Before go-live the platform must control **what each customer may use** and **what it pays**. Platform staff (Super
Admin, account managers) define feature groups and plans; a customer on a plan can use only what the plan, the
default groups and its add-ons allow, within count caps; nobody under the Customer Admin can exceed that. Plans are
priced per period with a discount; add-ons are bought mid-term at a prorated price; customers may ask for upgrades,
renewals, add-ons and wallet top-ups; staff record the payment, which activates the request and issues a GST
invoice. On top of the plan, sending a WhatsApp message debits a prepaid wallet.

The user asked for a robust, fault-tolerant design and gave the feature list as hints, not as a specification.

## 2. What the user decided

| # | Decision |
|---|---|
| U1 | Permissions are grouped into logical **feature groups** (CRM, …); some groups are **default** and go to every customer with a plan. A **plan** is a set of groups. |
| U2 | Each group in a plan has an amount; the sum is the plan's price; a **discount %** applies; both the actual and the discounted price are shown. |
| U3 | Periods: monthly, quarterly, half-yearly, yearly. |
| U4 | What a plan lacks can be bought later as an **add-on**, charged for the period that is left. Add-ons survive upgrade and renewal. |
| U5 | Staff can change a customer's plan; permissions follow. No user of a customer can go beyond what the customer has. |
| U6 | **Count caps**: users, automations, chatbots, nodes per chatbot, AI nodes per chatbot, and more later. |
| U7 | A customer may request a plan change **only to a higher plan**; the adjusted amount and the amount payable are calculated. |
| U8 | Usage is charged on top, per WhatsApp message sent as the first example. |
| U9 | Payment: the customer pays outside the app and **staff record it**, which activates the request. A gateway can come later behind the same step. |
| U10 | Usage is collected from a **prepaid wallet**. |
| U11 | **INR with GST**: prices before tax, GST added on the invoice, customer GSTIN shown, running invoice numbers. |
| U12 | After the period ends: **grace** days of full use with warnings, then **read-only** until renewal. |

## 3. Goals, non-goals, anti-goals

**Goals.** Feature groups, plans, one subscription per customer acting as a permission ceiling, count caps, add-ons
(feature groups and extra cap units), orders with quotes, staff-recorded payments, GST invoices, grace and read-only,
credit notes and refunds, invoice and credit-note PDF files, a prepaid wallet debited per sent message, console
pages for staff and for the Customer Admin, full history of every change.

**Non-goals (first version).** Online payment gateway; automatic recurring charges; refund requests raised by the
customer (staff raise them); e-invoicing (IRN) and GST return files; e-mail or WhatsApp reminders; coupons; trials as a separate concept (a staff discount of up to
100% on one order covers them); several currencies; per-brand plans (the plan is per customer); included message
quotas; charging for anything but sent WhatsApp messages (the meter list is built to grow).

**Anti-goals** (a failure even if it works):
- A user who can do something the customer is not entitled to, by any route (role, stale cache, background job).
- A payment recorded twice, or recorded once and applied twice, or applied half (invoice without activation, wallet
  credit without ledger entry).
- A wallet balance that does not equal the sum of its ledger entries, or that goes below zero.
- A plan edit that silently changes what an existing customer pays or gets mid-term.
- Data deleted because a plan ended or was downgraded. Nothing is ever deleted by billing.
- Money computed in the browser. Every amount comes from the server.

## 4. Facts the design rests on

- Roles are global documents shared by all customers (`roles`; `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`, `AGENT`), edited
  only by the Super Admin. A customer has no roles of its own.
- Authorities are resolved on every request by `security.access.AccessService` (`globalAuthorities`, `inWorkspace`);
  the login token carries none. `AssignmentPolicy` compares role sets with a strict-subset rule.
- Built-in authorities are the `Authority` enum (52, pinned in `AuthorityTest` and `RoleSeederIT`); the Super Admin
  can create more in the `authorities` collection.
- MongoDB is standalone: **no transactions**. Atomicity comes from single-document updates and `findAndModify`
  (leases in `CampaignRunner.claim`, `AutomationWorker.claim`, `MongoAiTurnQueue.claim`).
- Every server-originated message goes through `service.inbox.InboxSendService.send` / `sendCloud`, which first call
  `InboxDevices.requireReady`; the caller is a `SendActor` (user, campaign, automation, chatbot). The message row
  (`waWebMessages`) is written only after the device accepted it. Messages typed on the phone never pass the server's
  send path. Two Super-Admin-only bridge endpoints bypass it.
- Background workers (campaign, automation, chatbot, AI turns) read across all tenants and do not check the customer.
- Disabling a customer today is a hard stop (401, devices disconnected). Read-only needs a different state.
- The CRM has a per-brand switch with `maxPipelines` / `maxCustomFields` on `crmSettings`, set by staff with
  `crm:enable`; when off it answers 402. Limit overflow answers 409 `LIMIT_REACHED` with `limit` in the body.
- The console gates routes and buttons from `GET /api/users/me` (`authorities`, `workspaces[].authorities`); it
  refetches only on `refreshUser()`. There is no banner slot and no customer-level page except `/brands`.
- `Clock` is injected widely and tests use `MutableClock`; tickers are off in tests and driven by hand. Money exists
  only in the CRM, as `BigDecimal` stored as `Decimal128`.

## 5. Approaches considered

**Where the plan's permissions live.**
1. **A ceiling over roles (chosen).** The customer has an entitled set of authorities; a user's effective set is the
   role's set limited to it, computed in `AccessService`. Nothing is copied into roles. A plan change, expiry or
   add-on applies at the next request for every user, and the "no user beyond the admin" rule holds by construction.
2. Per-customer copies of roles, rewritten on each plan change. Many writes per change, half-applied on a crash,
   and the copies drift from the built-in roles on every release.
3. A per-customer Customer Admin role only, relying on the strict-subset rule for others. Existing users keep what
   they had after a downgrade, so permissions would still have to be stripped user by user.

One consequence differs from the user's hint: after an upgrade, a user whose role already contains the new feature's
authorities gets them at once, not only the Customer Admin. The admin controls this through the roles it gives.

**How an upgrade is charged.**
1. **Restart the term (chosen).** The new plan's period starts on the activation day; the unused part of what was
   paid for the current term becomes a credit against the new charge. Works for any period change.
2. Keep the end date and charge the price difference for the days left. Cannot express monthly → yearly.

**How the wallet stays correct without transactions.**
1. **Outbox inside the wallet document (chosen).** One `findAndModify` changes the balance and appends the ledger
   entry to an array in the same document; a mover copies entries to the ledger collection.
2. Ledger insert, then balance update, with a repair job. A crash between the two cannot be told from a completed
   debit.

## 6. Concepts

### 6.1 Feature group (`featureGroups`)

`code` (unique, `[a-z0-9-]{2,30}`), `name`, `description`, `category` (`DEFAULT` | `PAID`), `authorities[]`,
`requires[]` (codes of groups that must be present with this one), `builtIn`, `sortOrder`, audit fields, `@Version`.

- Holds COMPANY and BRAND level authorities only. An authority is in **at most one** group.
- An authority in **no** group is entitled to nobody under a subscription (fail closed). The staff page lists
  ungrouped authorities as a warning, and a test pins that every built-in COMPANY/BRAND authority is in a seeded group.
- `DEFAULT` groups are part of every plan, cost nothing and cannot be sold as add-ons.
- `requires` is checked when a plan is saved and when an add-on is bought (AI needs Chatbots; CRM segments as
  campaign audiences are already guarded by their own authority).
- Changing a group's authorities reaches every subscribed customer at once (entitlements are resolved from groups
  live); the change is recorded in `featureGroupChanges`.

Seeded built-in groups (my call; staff may edit names, authorities and category, except Core):

| Code | Category | Authorities |
|---|---|---|
| `core` | DEFAULT | `workspace:*`, `company-user:manage`, `user:*`, `team:manage`, `phone-number:view`, `phone-number:manage`, `billing:view`, `billing:request` |
| `inbox` | DEFAULT | `inbox:*` |
| `contacts` | DEFAULT | `contact:*`, `contact-field:manage` |
| `templates` | DEFAULT | `template:*` (the inbox, automations and chatbots all send templates) |
| `campaigns` | PAID | `campaign:*` |
| `automations` | PAID | `automation:*` |
| `chatbots` | PAID, requires `automations` (a chatbot is started by an automation rule) | `chatbot:view`, `chatbot:manage` |
| `ai` | PAID, requires `chatbots` | `ai:*` |
| `crm` | PAID | every BRAND-level `crm:*` |

`core` is fixed as DEFAULT, cannot be deleted, and its built-in authorities cannot be removed, so a Customer Admin can
always manage users and reach the billing page. The user said the reason for default groups comes later; this design
assumes "what every customer needs to operate and to pay".

### 6.2 Plan (`plans`)

`code` (unique), `name`, `description`, `period` (`MONTHLY` 1, `QUARTERLY` 3, `HALF_YEARLY` 6, `YEARLY` 12 months),
`rank` (integer; higher = higher plan), `items[{groupCode, price}]` (the PAID groups included),
`addOns[{groupCode, price}]` (PAID groups not included that this plan's customers may buy, price per period),
`limits{LimitKey → integer}` (a key that is absent or null is unlimited), `limitAddOns[{key, unitPrice, unit}]` (price per period for
`unit` extra, e.g. 5 users), `discountPercent` (0–100, two decimals), `rates{UsageMeter → rupees, 4 decimals}`,
`offered` (customers may pick it themselves), `status` (`DRAFT` | `ACTIVE` | `RETIRED`), `revision`, audit fields,
`@Version`.

- **Actual price** = sum of `items[].price`. **Discounted price** = actual × (1 − discount/100), rounded to 2
  decimals HALF_UP. The discount applies to the plan's items only, not to add-ons (my call). Both are returned by the
  API and shown wherever a plan is shown.
- A plan has one period; "Duplicate" copies a plan so the four periods of one tier are quick to make. Tiers share a
  `rank`.
- Only an ACTIVE plan can be assigned or requested. A RETIRED plan stays on the customers that have it and can be
  renewed by staff, not by the customer.
- Editing an ACTIVE plan bumps `revision` and writes `planChanges`. **Existing subscriptions are not touched**: each
  holds a snapshot of the commercial terms it was sold under (section 6.3). The new terms apply at the next renewal.

### 6.3 Subscription (`subscriptions`, one per customer, unique `customerId`)

`current` and `next` (a **term segment** each; `next` is null unless a renewal was paid early), `anchorDay` (day of
month of the first term's start), `creditBalance`, `termRevision` (incremented when a segment's plan or dates
change), `revision` (incremented on every applied change), `appliedOrderIds[]`, `creditedOrderIds[]`, `debitedOrderIds[]`,
`refundIds[]` (the guards that make each step apply once), `@Version`.

A term segment: `planCode`, `planRevision`, `terms` (snapshot: plan name, period, rank, discount, item prices, add-on
prices, limit add-on prices, limits, rates), `groupCodes[]` (the plan's PAID groups), `addOns[{id, kind GROUP |
LIMIT, groupCode | limitKey, quantity, unitPrice, paid, coveredFrom, orderId}]`, `termStart`, `termEnd` (instants at
00:00 in the billing zone; `termEnd` exclusive), `planPaid` (what was paid for the plan for this segment, before
tax).

The segment in force is `next` once now ≥ `next.termStart`, else `current`; the billing ticker then moves `next`
into `current` (a guarded update, safe to repeat), but readers never depend on that having happened. A renewal paid
early therefore changes nothing about the term already paid for. Below, `termEnd` means the end of the last paid
segment for state, and of the segment in force for entitlement.

**State is derived from the dates, never stored**, so no job has to run for a customer to move between states:

| State | When | Effect |
|---|---|---|
| `NONE` | no subscription document | per the platform setting `customersWithoutPlan`: `UNRESTRICTED` (default, so the deploy changes nothing) or `READ_ONLY` |
| `ACTIVE` | now < `termEnd` | entitled set and limits apply |
| `GRACE` | `termEnd` ≤ now < `termEnd` + grace days | as ACTIVE, with a warning banner |
| `EXPIRED` | later | read-only (section 8.3) |

**Entitled set** = authorities of all DEFAULT groups ∪ of the segment's `groupCodes` ∪ of its GROUP add-ons.
**Effective limit** for a key = plan limit + Σ LIMIT add-on quantities (unlimited stays unlimited).

Every applied change writes a `subscriptionEvents` row (kind, before, after, order id, by, at). Kept, no TTL.

### 6.4 Limits (`LimitKey`)

A code enum with a scope, so a new cap is one constant and one check:

| Key | Scope | Counted |
|---|---|---|
| `USERS` | customer | enabled users of the customer |
| `BRANDS` | customer | workspaces |
| `PHONE_NUMBERS` | customer | WhatsApp numbers |
| `AUTOMATION_RULES` | customer | automation rules, draft or published |
| `CHATBOTS` | customer | chatbots |
| `AI_PROFILES` | customer | AI profiles |
| `CHATBOT_NODES` | one chatbot | nodes in the flow |
| `CHATBOT_AI_NODES` | one chatbot | AI nodes in the flow |
| `CRM_PIPELINES` | one brand | pipelines |
| `CRM_CUSTOM_FIELDS` | one brand and record type | active custom fields |

- Checked where the thing is created or re-enabled; overflow answers 409 `LIMIT_REACHED` with `key`, `limit`, `used`
  (the existing CRM convention).
- The two per-chatbot caps are `FlowValidator` problems of severity ERROR ("Your plan allows 30 nodes in a chatbot"):
  a draft over the cap can still be saved, but not published.
- **Lowering a cap never removes anything.** Things over the cap stay and keep working; only new ones are refused.
- **CRM switch (my call).** For a customer with a subscription the plan decides: the CRM is on in every brand when
  `crm:view` is entitled, and the per-brand switch is ignored. For a customer without one (`NONE`, unrestricted) the
  switch works as today. The effective CRM limit is the plan's when the key is present, else the brand's.
- Count-then-create is not atomic: two simultaneous requests can exceed a cap by one. Accepted and documented.

### 6.5 Usage meters and the wallet

`UsageMeter` is a code enum; the first and only constant is `WHATSAPP_MESSAGE`. A plan's `rates` give rupees per unit
(4 decimals; absent or 0 = free).

`wallets` (one per customer): `balance` (Decimal128, 4 decimals, never negative), `lowBalanceAt` (warning threshold,
set by staff), `pending[]` (ledger entries not yet moved), `orderIds[]`, `@Version`. `walletEntries` (the ledger, kept, no TTL):
`id`, `customerId`, `type` (`TOPUP`, `USAGE`, `REVERSAL`, `ADJUSTMENT`, `REFUND`), `amount` (signed), `balanceAfter`, `meter`,
`source` (CONSOLE, CAMPAIGN, AUTOMATION, CHATBOT), `workspaceId`, `ref` (order id, send id, campaign id…), `note`,
`by`, `at`.

## 7. Money rules

- All amounts are `BigDecimal` rupees stored as `Decimal128`: prices, order and invoice amounts at 2 decimals, rates
  and the wallet at 4; rounding HALF_UP, each line rounded on its own. One currency, INR.
- **Billing zone** (platform setting, default `Asia/Kolkata`) defines day boundaries. A term runs from 00:00 of its
  start day to 00:00 of the day `period` months later that falls on `anchorDay`, clamped to the month's last day (a
  term from 31 Jan ends 28 Feb, and the one after it ends 31 Mar). A term restarted by an upgrade or change takes a
  new `anchorDay`.
- **Proration by days.** `termDays` = days from the segment's `termStart` to its `termEnd`; `remainingDays` = days
  from today (inclusive) to `termEnd`. An add-on bought mid-term costs `price × remainingDays / termDays`; when a
  `next` segment exists it is added to both and costs that plus one full `price` at the next segment's add-on price.
  Add-ons can be bought only in `ACTIVE` with at least one day remaining.
- **Credit for unused time** of a paid component = `paid × remainingDays / coveredDays`, where `coveredDays` runs from
  its `coveredFrom` to `termEnd`. Everything paid for a `next` segment is credited in full. A component that was
  given free (or assigned directly in phase 1) has `paid` 0 and yields no credit.
- **`paid`** of a component is its line amount after the plan discount and any extra discount, before tax; the
  component also keeps the `invoiceId` it was billed on, so its unused value can be credited against that invoice.
- **GST.** Prices are before tax. `taxable` = the charges after discounts; GST = `taxable × rate` (platform setting,
  18 to start). When the customer's state equals the supplier's, the invoice shows CGST and SGST (half each, any odd
  paisa on CGST); otherwise IGST. An invoice always carries the full value of what it sells. Money owed back to the
  customer is never netted inside an invoice: it is a **credit note** against the invoice that was paid (section
  9.5), with the tax of that invoice.
- **A quote is fixed.** The amounts on an order are computed once, when it is raised, and never recomputed. An order
  is valid for `quoteValidDays` (platform setting, default 7); after that it is EXPIRED and must be raised again. A
  payment recorded a few days after the quote is therefore charged as quoted (my call).

### 7.1 What each order kind charges

| Kind | Raised by | Charges | Term after activation |
|---|---|---|---|
| `NEW` | staff | discounted plan price | starts on the activation day |
| `RENEWAL` | customer (from 30 days before `termEnd`) or staff | the plan's **current** discounted price + each add-on the customer keeps (all by default; it may untick some) for a full period at the plan's current add-on prices (an add-on the plan no longer offers keeps its old price) | a `next` segment starting at the old `termEnd` if activated before it; the new `current` from the old `termEnd` if in grace; else from the activation day |
| `UPGRADE` | customer or staff | new plan's discounted price + every carried add-on for a full period, minus the credit for unused time of the current plan and add-ons (and of a `next` segment, which is dropped) | restarts on the activation day with the new plan's period |
| `CHANGE` | staff only | as UPGRADE, to any plan (a downgrade), or removing an add-on | as UPGRADE |
| `ADDON` | customer or staff | prorated add-on price (group, or `quantity` limit units) | unchanged |
| `ADJUST` | staff only | nothing; sets `termEnd` (a goodwill extension, or bringing in a customer who already paid outside) or posts an amount to `creditBalance`, with a mandatory reason | as set |
| `CANCEL_ADDON` | staff only | nothing; removes an add-on and credits its unused value (staff may raise the credit up to what was paid for it) | unchanged |
| `CANCEL_PLAN` | staff only | nothing; ends the subscription today and credits the unused value of the plan, its add-ons and any `next` segment (staff may raise the credit up to what was paid) | `termEnd` = today, no grace: read-only at once |
| `WALLET_REFUND` | staff only | nothing; takes an amount out of the wallet and credits it, with the GST paid on it | unchanged |
| `TOPUP` | customer or staff | the top-up amount + GST; the wallet is credited the amount before tax | unchanged |

- **Higher plan** (what a customer may request as UPGRADE): an ACTIVE, `offered` plan whose `rank` is higher, or
  whose `rank` is equal and whose period is longer. Everything else is a staff CHANGE.
- **Add-ons on upgrade or change.** A GROUP add-on whose group the new plan includes, and a LIMIT add-on whose key
  the new plan leaves unlimited, are absorbed (the unused value is in the credit; not charged again). The others are
  carried and charged for the new term at the new plan's add-on price; where the new plan does not offer one, at its
  old unit price scaled by the ratio of the two periods' months. The order is refused if the carried set would break
  a group's `requires`; staff remove that add-on in the same `CHANGE`.
- **`creditBalance`** (account credit) is money the platform owes the customer, **tax included**. It grows only by
  credit notes and staff `ADJUST` postings. It is used against the total of `RENEWAL`, `UPGRADE`, `CHANGE` and
  `ADDON` orders (as much as the total allows; never `TOPUP`), and what is left can be paid out as a refund (section
  9.6). **Amount payable** = order total (with GST) − account credit used.
- On an upgrade or change, the unused value of the old plan and add-ons is credited by credit notes at activation
  and lands in `creditBalance`, which the same order then uses. The quote shows: new charges, GST, total, credit for
  unused time (with its GST), account credit already held, amount payable. If the credit exceeds the total, the
  payable amount is 0 and the remainder stays as account credit.
- Staff may put an extra discount (0–100%) with a mandatory reason on an order **when they raise it**; it applies to
  plan and add-on lines before credit, never to `TOPUP`. This is how a trial, a complimentary plan or a negotiated
  price is given. To discount a customer's request, staff reject it and raise their own. An order's amounts never
  change after it is raised.

## 8. Enforcement

### 8.1 The ceiling (`security.entitlement`)

`Entitlements.of(customerId) → Entitlement(state, authorities | null, limits, readOnly, termEnd, graceEnd, planName)`;
`authorities == null` means unrestricted. Resolved from the subscription, the feature groups and the platform
settings; cached in memory per customer for 30 s and evicted on every change to that customer, to groups or to
settings (single instance, like `RoleCache`; the TTL bounds staleness if a second node is ever added). The state is
recomputed from the dates on every read.

- `AccessService.globalAuthorities` (a customer user's COMPANY codes) and `inWorkspace` keep only entitled codes.
  Platform users are untouched.
- **`AssignmentPolicy` compares inside the ceiling.** Both the actor's set and the role's set are limited to the
  entitled set before the strict-subset test. Without this a Customer Admin on a small plan could not give the
  built-in `WORKSPACE_MANAGER` role (which holds authorities the customer lacks). The role still shows as assignable
  and grants only what is entitled.
- `GET /api/users/me` therefore returns already-limited authorities, and gains `subscription {state, planName,
  termEnd, graceEnd, readOnly, lowWallet}` for the banner.
- `Entitlements.allows(customerId, authorityCode)` is the check for code that has no user (section 8.2).
- `Limits.require(customerId, key, used)` throws the 409 of section 6.4.

### 8.2 Work that runs without a user

A feature the customer is not entitled to must stop running, not only disappear from the screen. Each gate asks
`allows(customerId, <anchor>)` and also refuses in read-only:

| Gate | Where | Anchor | When refused |
|---|---|---|---|
| Sending | `InboxSendService`, beside `requireReady` | from the `SendActor`: campaign → `campaign:manage`, automation → `automation:manage`, chatbot → `chatbot:manage`, user → none | `SubscriptionInactiveException` → 402; callers treat it as they treat an unusable workspace. This stops work already in flight (a waiting chatbot run, a delayed automation action) at its next send |
| Campaign claim | `CampaignRunner` | `campaign:manage` | the campaign stays RUNNING and resumes when entitled again |
| Automation intake | `AutomationGate` | `automation:manage` | events are not matched to rules |
| Chatbot start | `ChatbotRunService.start` | `chatbot:manage` | no run is started; a running run fails at its next send |
| AI turn | `AiGatewayService.submit` | `ai:manage` | the node takes its failure path |
| CRM | the CRM's enabled check | `crm:view` | as "CRM off" today (402) |

Inbound messages are always received and stored, in every state, so a customer that renews has lost nothing.

### 8.3 Read-only

In `EXPIRED` (and `NONE` when the setting says so) a servlet filter refuses every request of a customer user that is
not `GET` or `HEAD` with 402 `SUBSCRIPTION_EXPIRED`, except an allow-list: sign-in and sign-out, own password and
profile, and the customer's own billing endpoints (raise and cancel requests, billing profile). The gates of 8.2
stop sends and background work. Reading, searching and exporting stay possible: handlers that read through `POST`
(search, preview, count, export, mark-as-read) carry a `@ReadOnlySafe` annotation that the filter honours, and a
test pins the list of annotated handlers. One filter, so a new endpoint is read-only unless someone marks it.

## 9. Orders, payments and invoices

### 9.1 Order (`billingOrders`)

`number` (`ORD-000123`), `customerId`, `kind`, `status`, `lines[{type PLAN | ADDON | LIMIT_ADDON | CREDIT | TOPUP,
description, groupCode | limitKey, quantity, amount}]`, `subtotal`, `extraDiscountPercent`, `extraDiscountReason`,
`creditUsed`, `taxable`, `tax{rate, cgst, sgst, igst}`, `total`, `target{planCode, planRevision, terms snapshot,
addOns}`, `quotedRevision` (the subscription's `revision`, or `termRevision` for an `ADDON`, when quoted), `validUntil`, `raisedBy`, `raisedAt`,
`payment{method BANK_TRANSFER | UPI | CHEQUE | CASH | OTHER | NONE, reference, receivedOn, note, recordedBy,
recordedAt}`, `invoiceId`, `rejectedReason`, `steps{applied, wallet, creditNoted, invoiced}`, `@Version`.

`REQUESTED → ACTIVATING → ACTIVATED`, or `REQUESTED → CANCELLED` (by whoever raised it) `| REJECTED` (staff, with a
reason) `| EXPIRED | SUPERSEDED`.

- At most one REQUESTED order that changes the term (`NEW`, `RENEWAL`, `UPGRADE`, `CHANGE`) per customer. Any number
  of `ADDON` and `TOPUP` orders.
- **Stale quotes cannot be applied.** A `RENEWAL`, `UPGRADE` or `CHANGE` order is quoted against the subscription's
  `revision` (its price depends on the add-ons too); an `ADDON` order against `termRevision` only, so two add-on
  orders paid together both apply. Recording a payment on an order whose number no longer matches is refused with
  "This quote is out of date; raise it again", and so is an `ADDON` order once its term has ended. When an order
  activates, the customer's other REQUESTED orders that it made stale become `SUPERSEDED` at once.
- An order with a payable amount of 0 (full credit or 100% discount) still waits for staff, who **Approve** it; it
  is recorded with payment method `NONE`. A customer never activates anything alone.

### 9.2 Activation without transactions

Everything that changes one customer's subscription runs under a **per-customer lease** (`billingLocks`, taken with
`findAndModify`, like the job locks of the CRM). Under the lease, in this order:

0. Finish any order of this customer still in ACTIVATING (steps 2 to 6), so orders apply strictly one after another.
1. **Record.** Check that the order is not expired or stale, then one `findAndModify`: `{_id, status: REQUESTED} →
   status ACTIVATING, payment`. It can succeed only once. Because of the lease and step 0, an order that passed the
   check cannot become stale before it is applied.
2. **Apply.** One update on the subscription document guarded by `appliedOrderIds ≠ orderId`: segments, add-ons,
   `creditBalance`, revisions, `$addToSet appliedOrderIds`. `NEW` on a customer without a subscription is an upsert
   on the unique `customerId`. Then the `subscriptionEvents` row (unique on order id), superseding of stale orders,
   and cache eviction.
3. **Wallet** (`TOPUP` credits it, `WALLET_REFUND` debits it; section 10.1).
4. **Credit notes** (orders that credit unused or returned value; section 9.5): insert each, unique on (order id,
   invoice id), numbered like invoices; then one update adds their total to `creditBalance`, guarded by
   `creditedOrderIds ≠ orderId` on the subscription.
5. **Invoice** (orders with charges), then the account credit it uses is taken from `creditBalance` in one update
   guarded by `debitedOrderIds ≠ orderId`. Insert the invoice; a unique index on `orderId` makes a repeat find the existing one. The number
   (section 9.3) is taken by inserting with the next sequence under a unique index and retrying on a duplicate, so
   numbers have no gaps even after a crash.
6. `status ACTIVATED`.

Each step is safe to repeat and is recorded in `steps`. A ticker (lease-based, like the other workers) re-drives any
order left in ACTIVATING for more than a minute, under the same customer lease. It also marks REQUESTED orders past
`validUntil` as EXPIRED (an expired order is refused at payment even before it is marked) and moves a due `next`
segment into `current`.

### 9.3 Invoice (`invoices`)

Issued at payment, never before, so there are no unpaid invoices to void. Immutable. Holds a full snapshot: supplier
(legal name, address, GSTIN, state), customer billing profile (legal name, address, state, GSTIN), lines, HSN/SAC
code, taxable value, CGST/SGST or IGST, total, total in words, payment method and reference, order number.
Number: `<prefix>/<financial year>/<6-digit sequence>`, e.g. `INV/26-27/000042`; the financial year runs April to
March in the billing zone and the sequence restarts each year. An order with total 0 gets no invoice.

The console shows an invoice as a page and offers **Download PDF** (section 9.7).

### 9.4 Staff orders without payment

`ADJUST`, `CANCEL_ADDON`, `CANCEL_PLAN` and `WALLET_REFUND` are orders too: raised by staff with a mandatory reason,
activated at once through the same lease and steps (payment method `NONE`), listed in the customer's order history.
They need `refund:manage`, except an `ADJUST` that only moves `termEnd`.

### 9.5 Credit notes (`creditNotes`)

A credit note returns value on **one** invoice. Immutable, with a full snapshot like an invoice: supplier, customer,
the invoice's number and date, lines (what is credited and for which days), taxable value, tax at **that invoice's**
rate and CGST/SGST or IGST split, total, reason, order number, issued by. Number: `<prefix>/<financial year>/<6-digit
sequence>` in its own series (default prefix `CN`), taken gaplessly like invoice numbers.

- The credit notes against an invoice can never total more than the invoice, line by line; the invoice shows what
  has been credited against it.
- An order that credits several components writes one credit note per invoice those components were billed on.
- A component with no invoice (free, or assigned in phase 1) yields no credit note and no credit.
- `WALLET_REFUND` credits top-up invoices, newest first, up to what each still has uncredited. The wallet is debited
  the amount before tax; the credit note adds the GST that was paid on it. Only money that came from top-ups can be
  refunded, never a staff `ADJUSTMENT`.
- The total of an order's credit notes is added to `creditBalance`.
- GST law limits how late a credit note may be issued for an invoice; the design does not enforce a date (the user
  should confirm the cut-off with an accountant).

### 9.6 Refunds (`refunds`)

A refund pays account credit out. It never creates credit; credit notes and `ADJUST` do. Staff with `refund:manage`
record it after paying the customer outside the app: amount (at most `creditBalance`), method, reference, date, note.

`number` (`RF-000012`), `customerId`, `amount`, `method`, `reference`, `paidOn`, `note`, `recordedBy`, `status`
(`RECORDING → RECORDED`). Under the customer lease: insert the refund, then one update on the subscription guarded
by `creditBalance ≥ amount` and `refundIds ≠ id` that subtracts the amount and adds the id, then `RECORDED`. A refund
left in `RECORDING` is finished by the ticker; if the balance guard fails it is marked `FAILED` and nothing was
taken. A recorded refund is final; a mistake is corrected with an `ADJUST`.

So returning money is always two visible steps: **credit** (a credit note says why and for which invoice) and
**pay out** (a refund says how it was paid). A customer who prefers to keep the credit simply uses it on the next
order.

### 9.7 PDF files

Invoices and credit notes can be downloaded as PDF by staff and by the customer. The PDF is rendered on the server
from the document's stored snapshot (never from live data), on first download, stored in GridFS, and its file id is
saved on the document with a guarded update; later downloads serve the stored file. Rendering is therefore outside
the activation path: a rendering fault cannot hold up a payment. Pure Java, no browser, with an embedded font that
has the rupee sign. A4, one layout for both document types: supplier and customer blocks, number and date, lines,
HSN/SAC, tax breakdown, total in figures and words, payment or reason, "computer-generated document".

### 9.8 Platform settings and customer profile

`billingSettings` (one document): supplier legal name, address, state, GSTIN, HSN/SAC code, GST rate, invoice prefix,
credit-note prefix, payment instructions (how a customer pays a request; shown beside its open requests), billing zone, grace days (default 7), quote valid days (7), renewal window days (30), default wallet low-balance
threshold, `customersWithoutPlan`. Payment recording is refused until the supplier fields are filled.

`Customer.billing`: legal name, address, state (from a fixed list of Indian states and territories), GSTIN (optional,
checked for the 15-character format and that its first two digits match the state). Edited by staff, or by the
Customer Admin while no order is REQUESTED. Required before the customer's first order.

## 10. Usage charging

### 10.1 Wallet operations

Every operation is **one** `findAndModify` on the wallet document that changes `balance` and pushes the complete
ledger entry onto `pending[]`, guarded, for a debit, by `balance ≥ amount`. So the balance and its entry can never
disagree and the balance cannot go below zero. The only operations that are ever retried are those of an order being
re-driven (a top-up's credit, a wallet refund's debit); they are guarded by `orderIds ≠ orderId` and add the order
id to that set in the same update (the set stays on the wallet for good; a customer has few such orders), so neither
can apply twice even after its entry has left `pending`. Usage debits and reversals use a fresh id and are never retried. A mover (inline after each operation, and from the billing ticker) upserts pending entries into
`walletEntries` by id and then pulls them from the array. The ledger a user sees is `walletEntries` plus `pending`.

Staff can post an `ADJUSTMENT` (positive or negative, with a mandatory note); it never takes the balance below zero.

### 10.2 Charging a message

In `InboxSendService`, for every send whose plan rate for `WHATSAPP_MESSAGE` is above 0:

1. Before dispatch: debit the rate with a fresh send id. Not enough balance → `WalletEmptyException` → 402
   `WALLET_EMPTY`; nothing is sent.
2. Device accepted: nothing more to do. The send id is stored on the message row.
3. Device **refused** (a definite failure): a `REVERSAL` entry returns the amount.
4. Timeout or disconnect (the message may have gone): the charge stays (my call: the customer may have got what it
   paid for; staff can adjust).

What is charged: messages sent by the server for the console, campaigns, automations and chatbots, test runs included
(they really send). Not charged: messages typed on the phone, incoming messages, the Super Admin's bridge endpoints.
The rate is the one in the subscription's snapshot; a customer with no subscription is not charged.

When the wallet is empty: a console send shows the error; a campaign is **paused** with the reason "Wallet balance
too low" (it does not burn through its recipients as failures); an automation action and a chatbot node fail with
that reason and take their failure path. `lowWallet` (balance below `lowBalanceAt`) raises the banner before that.

## 11. API

New authorities (59 after this; the pins in `AuthorityTest` and `RoleSeederIT` move from 52):

| Code | Level | Default roles | For |
|---|---|---|---|
| `plan:manage` | PLATFORM | (Super Admin) | feature groups, plans |
| `billing-settings:manage` | PLATFORM | (Super Admin) | billing settings |
| `subscription:view` | PLATFORM | ACCOUNT_MANAGER | read plans, a customer's subscription, orders, invoices, wallet |
| `subscription:manage` | PLATFORM | ACCOUNT_MANAGER | raise orders for a customer, record payments, reject, adjust the wallet |
| `refund:manage` | PLATFORM | (Super Admin) | cancel a plan or add-on with credit, refund the wallet, post account credit, record refunds |
| `billing:view` | COMPANY | CUSTOMER_ADMIN | see own plan, usage, orders, invoices, credit notes, refunds, wallet |
| `billing:request` | COMPANY | CUSTOMER_ADMIN | raise and cancel own requests, edit the billing profile |

Platform, under `/api/billing`: `feature-groups` (CRUD, history, `ungrouped`), `plans` (CRUD, `duplicate`,
`activate`, `retire`, history), `settings`, `orders` (queue with filters), `customers/{id}/subscription`,
`customers/{id}/quotes` and `/orders`, `orders/{id}/pay | reject | cancel`, `customers/{id}/wallet` (balance,
entries, `adjust`, threshold), `customers/{id}/invoices`, `invoices/{id}`, `invoices/{id}/pdf`,
`customers/{id}/credit-notes`, `credit-notes/{id}`, `credit-notes/{id}/pdf`, `customers/{id}/refunds` (list, record).

Customer, under `/api/my/billing` (the customer is the signed-in user's): `subscription` (plan, state, dates, groups,
add-ons, limits with current use), `options` (plans it may upgrade to, add-ons it may buy), `quotes` (POST: kind and
target → the priced lines, credit, tax, total; nothing stored), `orders` (list, POST, cancel), `invoices`,
`credit-notes`, `refunds` (each with its PDF where it has one), `wallet` (balance, entries, daily totals by source),
`profile`.

A quote and the order made from it run the same pricing code; the order stores the result.

## 12. Console

**Staff.**
- **Plans** (`/platform/plans`, `plan:manage`; read-only with `subscription:view`), two tabs. *Feature groups*: list,
  editor with the authority picker of the role editor, the ungrouped-authorities warning. *Plans*: list with period,
  rank, actual and discounted price, status, customers on it; editor with a row per PAID group (included with price |
  offered as add-on with price | not available), discount %, a live "Actual ₹X · Discount ₹Y · Price ₹Z · with GST
  ₹W" summary returned by the server, limits, limit add-on prices, message rate, offered, Duplicate.
- **Billing settings** (`/platform/billing-settings`).
- **Requests** (`/platform/billing-requests`, `subscription:view`): every REQUESTED order, oldest first, with
  customer, kind, amount, age; open one to **Record payment** (method, reference, date, optional extra discount with
  reason) or **Reject**.
- **Customer detail**: a Subscription card (plan, state, term, add-ons, limits with use, credit balance, wallet
  balance) with Assign plan, Change plan, Renew, Add add-on, Top up, Adjust wallet and, with `refund:manage`, Cancel
  add-on, Cancel plan, Refund wallet, Post credit and **Record refund** (each shows the credit the server computed
  before confirming); tabs for orders, invoices, credit notes, refunds, wallet entries and subscription history. The customer list gains Plan and State columns and a "No plan" filter.

**Customer Admin**: **Plan & billing** (`/billing`, `billing:view`): current plan and state, days left, what is
included, limits with use, add-ons with **Buy** (shows the prorated quote), **Upgrade** (higher plans with the quote:
new price, credit for unused time, GST, amount payable), **Renew**, wallet with **Top up** and usage, requests with
status and how to pay, invoices and credit notes with **Download PDF**, account credit and refunds, billing
  profile.

**Everyone in the customer**: a banner in the app shell: GRACE ("Your plan ended on 12 Dec. Renew by 19 Dec to keep
working"), EXPIRED ("…The account is read-only until it is renewed"), low wallet. `/users/me` is refetched when a 402
arrives, so the screen follows the state. 402 and `LIMIT_REACHED` get one shared message component ("Your plan
allows 5 users" with a link to Plan & billing for those who can see it).

## 13. Constraints

Java 21, Spring Boot 4.1, MongoDB standalone (no transactions), leased tickers (`omnichannel.billing.auto-tick`),
collections camelCase, no TTL on history, React 18 + PatternFly 6.5. Documents in `omnichannel-db` (`db.billing`);
`Entitlements`, `Limits`, `LimitKey` in `omnichannel-security` (`security.entitlement`), which the CRM and AI modules
may import (their boundary tests widen by that one package); plans, pricing, orders, invoices, wallet and tickers in
`omnichannel-service` (`service.billing`); controllers in `omnichannel-app` (`app.billing`). Worktrees
`.worktrees/subscriptions` in both repos. Every time-dependent rule takes the injected `Clock`.

## 14. Phases

1. **Entitlements.** Feature groups, plans without money fields used yet, subscription assigned directly by staff
   (plan, start, end), the ceiling, `AssignmentPolicy`, limits, background gates, read-only filter, banner, `/me`.
   After this the platform can control what each customer uses.
2. **Money.** Prices, discount, billing settings and profile, quotes, orders, payment recording and activation,
   invoices, add-ons, upgrade, renewal, change, credit notes, refunds, PDF files, the customer's Plan & billing page,
   the requests queue. Direct
   assignment from phase 1 becomes the `NEW` order.
3. **Usage.** Wallet, top-up orders, message charging, pause on empty, usage views.

## 15. Calls to check

1. Upgrades reach every user whose role already holds the new authorities, not only the admin (section 5).
2. Default groups are Core, Inbox, Contacts and Templates; the paid ones are Campaigns, Automations, Chatbots, AI, CRM.
3. A customer with no plan is unrestricted until the setting is switched to read-only at go-live.
4. An upgrade restarts the term on the activation day; unused paid time is a credit.
5. The discount applies to the plan, not to add-ons.
6. Add-ons are of two kinds: a feature group, or extra units of a cap.
7. Plan edits never change existing subscriptions before their renewal; group membership changes do, at once.
8. A quote is fixed for 7 days; the invoice is issued only when payment is recorded.
9. Unused value is returned by a credit note against the invoice it was paid on, with that invoice's GST; it
   becomes account credit, which is used on the next order or paid out as a refund. Confirm the latest date a credit
   note may be issued with an accountant.
10. A send that times out stays charged; a refused send is returned.
11. Lowering a cap or losing a feature never deletes or unpublishes anything; it only stops new work.
12. Downgrades, cancelling a plan or add-on, wallet refunds and recording refunds are staff-only (`refund:manage`,
    Super Admin by default); a customer may drop add-ons at renewal. Cancelling a plan makes the customer read-only
    at once.
13. For a customer with a subscription the plan replaces the per-brand CRM switch.
14. A renewal paid early is queued as the next term and changes nothing in the term already paid for.
15. Every order, even one with nothing to pay, is activated by staff.
16. Staff can extend a term's end date or post account credit, with a reason, without an order.
17. Losing a feature stops work already in flight at its next send (a waiting chatbot run, a delayed automation).

## 16. Left to the builder

Package layout inside the named packages; index names; the PDF library (pure Java, licence fit for a commercial
product, checked against its current documentation) and the embedded font; the Indian state list source; amount-in-words helper; exact
banner and message copy beyond the examples; how the plan editor lays out its rows; cache implementation; the
ticker's lease length.

## 17. Verification

- Pricing as pure unit tests with a fixed clock: every order kind, period lengths across month ends and leap years,
  terms anchored on the 29th to 31st, proration on the first and last day, absorbed and carried add-ons, credit above charges, CGST/SGST against IGST,
  rounding.
- Entitlement: a role's authority outside the plan is refused on the API and absent from `/me`; a Customer Admin on a
  small plan can still give `WORKSPACE_MANAGER`; an ungrouped authority is refused; a group edit applies at once.
- Each limit refuses at its cap with `LIMIT_REACHED` and keeps existing items after the cap is lowered.
- State by dates with `MutableClock`: ACTIVE → GRACE → EXPIRED; in EXPIRED every write is 402 except the allow-list,
  reads work (search and export included), inbound messages are stored, each background gate refuses; after a
  downgrade a waiting chatbot run stops at its next send.
- Activation: paying twice applies once; a crash after each step (simulated by calling the steps separately) ends in
  the same final state when re-driven; a stale quote is refused; two add-on orders paid together both apply; a
  top-up re-driven after its entry was moved is credited once; credit notes never exceed their invoice and carry
  its tax split; an upgrade issues credit notes and uses the credit in the same order; a refund above the account
  credit is refused and a re-driven refund is taken once; a wallet refund cannot return adjustment money; the PDF of
  an invoice is rendered once and matches the stored snapshot; an early renewal leaves the current term's
  entitlements and credit untouched; invoice numbers are gapless across a duplicate-key retry and restart in April.
- Wallet: concurrent debits never go below zero; the same entry id applies once; the ledger equals the balance after
  the mover is interrupted; refused send returns the charge; empty wallet pauses a campaign.
- Console: plan editor totals from the server, quote dialogs, requests queue, banner per state, shared limit message;
  `tsc`, lint, build.
- On 8089 with the demo customer: assign a plan without CRM and check the CRM disappears for every user; buy the CRM
  add-on and record the payment; upgrade; let a term end by assigning a past end date and check read-only.
