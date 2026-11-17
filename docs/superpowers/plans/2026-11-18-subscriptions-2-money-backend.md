# Subscriptions phase 2 (money), backend: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A customer's plan, add-ons, renewals and upgrades become priced orders that staff activate by recording the payment; each paid order issues a GST invoice, unused value comes back as credit notes and account credit, and account credit can be paid out as a refund.

**Architecture:** Pure pricing code turns "what is asked" into a fixed quote; an order stores that quote. Activation runs under a per-customer lease as repeatable steps (apply to the subscription, credit notes, invoice, done), each guarded by an id set on the document it changes, so a crash at any point finishes cleanly and nothing applies twice. Invoices and credit notes are immutable snapshots numbered without gaps; their PDF is rendered on first download and kept in GridFS.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB (standalone, no transactions), JUnit 5 + AssertJ, embedded Mongo, `MutableClock`. A pure-Java PDF library chosen in Task 14.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` sections 7, 9, 11, 14 (phase 2). Read `docs/superpowers/project-rules.md` sections 1 and 2 first. Phase 1 code: `common.billing`, `db.billing`, `security.entitlement`, `service.billing`, `app.billing`.

**Console plan:** `docs/superpowers/plans/2026-11-18-subscriptions-2-money-console.md` consumes the "API contract" below.

## Global Constraints

- Work only in `.worktrees/subscriptions-money` (branch `subscriptions-money`). No merge, push or deploy: the user asks for those.
- Test first: every behaviour starts with a failing test that is run and seen to fail.
- One Maven build at a time. `./mvnw -q -pl omnichannel-app -am test -Dtest=<Names> -Dsurefire.failIfNoSpecifiedTests=false`. Full `./mvnw -q verify` once, in Task 16, with nothing else running.
- No transactions. One change to one document is one update; a multi-document change is a sequence of guarded, repeatable steps.
- **All amounts are worked out on the server** and stored on the order. An order's amounts never change after it is raised. Money is `BigDecimal` with `@Field(targetType = DECIMAL128)`, 2 decimals, `HALF_UP`, each line rounded on its own.
- Every time-dependent rule takes the injected `Clock`; "today" is the date in the billing zone (`BillingSettingsStore.zone()`).
- Collections camelCase, kept, no TTL: `billingOrders`, `invoices`, `creditNotes`, `refunds`, `billingLocks`, `billingCounters`. GridFS bucket `billingDocuments`.
- Error codes (via `CodedException`): `ORDER_STALE` (409), `ORDER_EXPIRED` (409), `ORDER_PENDING` (409), `ORDER_NOT_OPEN` (409), `BILLING_NOT_SET_UP` (409, extra `missing`), `NOT_ENOUGH_CREDIT` (409), plus the phase 1 codes. Validation → `ValidationException` with the field.
- A customer with no subscription and the default setting still behaves exactly as before. Every phase 1 test must pass, except those of the direct-assignment endpoints that this plan removes (Task 15).
- Tests that change billing settings, groups, plans or subscriptions call `billing.reset()` (see `app.support.TestBilling`) in `@BeforeEach` and `@AfterEach`.
- Not in this phase: the wallet, `TOPUP`, `WALLET_REFUND`, per-message charging (phase 3); online payment; e-invoicing.
- Commit trailer as the session reminder says.

## Rulings made while planning

- Phase 2 order kinds: `NEW`, `RENEWAL`, `UPGRADE`, `CHANGE`, `ADDON`, `ADJUST`, `CANCEL_ADDON`, `CANCEL_PLAN`. `TOPUP` and `WALLET_REFUND` wait for the wallet.
- The phase 1 endpoints `PUT` and `DELETE /api/billing/customers/{id}/subscription` are removed: a plan is given by a `NEW` order (a 100% extra discount makes it free) and ended by `CANCEL_PLAN`. `GET` stays. Tests keep `TestBilling.assign`.
- An order reserves its invoice id and credit-note ids when it is raised, so the apply step can stamp them on the subscription before the documents exist; the documents are inserted with those ids, which also makes the inserts repeatable.
- A cancelled plan is marked `cancelled` on the subscription, which makes the state `EXPIRED` at once with no grace (spec 7.1); the next `NEW` order clears it.
- A limit add-on is bought in whole units of the plan's `unit` (5 users at a time when `unit` is 5); `quantity` on the subscription stays the number of extra items.
- GSTIN is checked for shape and state code only, not its check digit.
- Raising an order also runs under the customer lease, so "one open term-changing order" cannot be raced.
- The phase 1 review minors that this phase touches are fixed in Task 1.

## Review Focus

Each line has its test in the task named.

1. Paying an order twice, or two staff paying two orders of one customer at the same moment: each applies once, in order, and a quote made stale by the first is refused, not applied (Task 8).
2. A crash after any activation step: re-driving ends in the same subscription, the same invoice number and the same account credit as an uninterrupted run (Tasks 8, 9, 10, 13).
3. Credit never exceeds what was paid: credit notes against an invoice cannot total more than the invoice, a free or phase 1 term yields none, and a refund cannot exceed account credit (Tasks 6, 10, 12).
4. Proration at the edges: an add-on on the first and the last day of a term, a term anchored on the 31st, a renewal paid early then an upgrade before the old term ends, a leap February (Tasks 4, 5, 6).
5. Invoice numbers under contention: two invoices issued at once get consecutive numbers with no gap and no duplicate, and the sequence restarts on 1 April in the billing zone (Task 9).

---

## Worked example used across the tests

Billing settings: zone `Asia/Kolkata`, GST 18, supplier state `29` (Karnataka), prefixes `INV` and `CN`, quote valid 7 days, renewal window 30 days.
Plans: **Gold monthly** (`gold-monthly`, rank 2): includes `crm` at 1000, offers `automations` at 300, `USERS` limit 5 with extra units 100 per 5, discount 10 → price **900.00**. **Platinum monthly** (`platinum-monthly`, rank 3): includes `crm` 1000 and `automations` 500, no discount → **1500.00**.
Customer Acme, state `29`. Customer Bolt, state `27` (Maharashtra).

| Step | Date | What | Amounts |
|---|---|---|---|
| A | 1 Dec 2026 | `NEW` Gold, term 1 Dec → 1 Jan (31 days) | taxable 900.00; CGST 81.00, SGST 81.00; total **1062.00** |
| B | 11 Dec | `ADDON` automations, 21 of 31 days | 300 × 21/31 = **203.23**; tax 36.58 (CGST 18.29, SGST 18.29); total **239.81** |
| C | 11 Dec | `ADDON` 10 extra users (2 units) | 100 × 2 × 21/31 = **135.48** |
| D | 16 Dec | `UPGRADE` to Platinum (after A and B only), 16 days unused | credit on invoice A: 900 × 16/31 = **464.52**, tax 83.61 (CGST 41.81, SGST 41.80), total 548.13; credit on invoice B: 203.23 × 16/21 = **154.84**, tax 27.87 (CGST 13.94, SGST 13.93), total 182.71; credit **730.84**; new charges 1500.00 + tax 270.00 = 1770.00; payable **1039.16**; term 16 Dec → 16 Jan |
| E | 20 Dec | `RENEWAL` of Gold with automations (after A and B only) | 900.00 + 300.00 = 1200.00; tax 216.00; total **1416.00**; next segment 1 Jan → 1 Feb |
| F | 1 Dec | `NEW` Gold for Bolt | taxable 900.00; IGST 162.00; total 1062.00 |
| G | 1 Dec | `NEW` Gold with 50% extra discount | taxable 450.00; tax 81.00; total 531.00 |

---

## API contract (phase 2)

Money is a JSON number with 2 decimals; dates are ISO local dates in the billing zone; instants are ISO-8601 UTC.

```
Tax          {ratePercent, cgst, sgst, igst, interState}
OrderLine    {type: "PLAN"|"ADDON"|"LIMIT_ADDON", description, groupCode, limitKey, quantity, listAmount, amount}
OrderCredit  {invoiceId, invoiceNumber, description, taxable, tax: Tax, total}
QuoteView    {kind, lines: OrderLine[], extraDiscountPercent, taxable, tax: Tax, total,
              credits: OrderCredit[], creditFromNotes, accountCredit, accountCreditUsed, payable,
              planCode, planName, period, termStart, termEnd, validUntil}
OrderView    QuoteView + {id, number, customerId, customerName, status, raisedBy, raisedByName, raisedByStaff,
              raisedAt, reason, extraDiscountReason, payment: {method, reference, receivedOn, note,
              recordedByName, recordedAt} | null, invoiceId, invoiceNumber, creditNoteIds, rejectedReason}
              status: "REQUESTED"|"ACTIVATING"|"ACTIVATED"|"CANCELLED"|"REJECTED"|"EXPIRED"|"SUPERSEDED"
QuoteRequest {kind: "NEW"|"RENEWAL"|"UPGRADE"|"CHANGE"|"ADDON", planCode, startDate,
              addOns: [{kind, groupCode, limitKey, quantity}],     // NEW, CHANGE: the full set wanted
              keepAddOnIds: string[] | null,                       // RENEWAL: null keeps all
              addOn: {kind, groupCode, limitKey, quantity},        // ADDON
              extraDiscountPercent, extraDiscountReason}           // staff only
InvoiceView  {id, number, issuedAt, orderId, orderNumber, supplier: Party, customer: Party, sacCode,
              lines: [{description, amount}], taxable, tax: Tax, total, totalInWords, accountCreditUsed,
              amountPaid, payment, credited, hasPdf: true}
CreditNoteView {id, number, issuedAt, orderId, invoiceId, invoiceNumber, invoiceDate, supplier, customer,
              lines, taxable, tax, total, totalInWords, reason}
Party        {legalName, address, stateCode, stateName, gstin}
RefundView   {id, number, amount, method, reference, paidOn, note, recordedByName, recordedAt, status}
ProfileView  {legalName, address, stateCode, gstin, complete}
OptionsView  {renewal: {allowed, reason, from} , upgrades: PlanOption[], addOns: [{kind, groupCode, name, limitKey,
              label, unit, unitPrice}], paymentInstructions}   // PlanOption {code, name, period, rank, discountedPrice}
SettingsView (phase 1) + {supplierLegalName, supplierAddress, supplierStateCode, supplierGstin, sacCode,
              gstRatePercent, invoicePrefix, creditNotePrefix, quoteValidDays, renewalWindowDays,
              paymentInstructions, complete}
SubscriptionView (phase 1) + {creditBalance, next: {planName, termStart, termEnd} | null, cancelled}
```

Staff, under `/api/billing` (reads `subscription:view`; writes as stated):

| Method and path | Authority | Body → result |
|---|---|---|
| `GET /states` | read | → `[{code, name}]` |
| `PUT /settings` | `billing-settings:manage` | the extended settings |
| `GET/PUT /customers/{id}/billing-profile` | `subscription:manage` to write | `ProfileView` |
| `POST /customers/{id}/quotes` | `subscription:manage` | `QuoteRequest` → `QuoteView` (nothing stored) |
| `POST /customers/{id}/orders` | `subscription:manage` | `QuoteRequest` → 201 `OrderView` |
| `GET /customers/{id}/orders`, `GET /orders?status=&page=`, `GET /orders/{id}` | read | `OrderView[]`, `{items,total,page}`, `OrderView` |
| `POST /orders/{id}/pay` | `subscription:manage` | `{method, reference, receivedOn, note}` → `OrderView` (`method` `NONE` only when payable is 0) |
| `POST /orders/{id}/reject` | `subscription:manage` | `{reason}` → `OrderView` |
| `POST /orders/{id}/cancel` | `subscription:manage` | → `OrderView` |
| `POST /customers/{id}/adjustments` | `subscription:manage` for `termEnd`; `refund:manage` for `creditAmount` | `{termEnd, creditAmount, reason}` (one of the two) → `OrderView` (ACTIVATED) |
| `POST /customers/{id}/cancellations` | `refund:manage` | `{addOnId, creditTaxable, reason}` (`addOnId` null = the plan) → `OrderView` (ACTIVATED) |
| `POST /customers/{id}/cancellations/quote` | `refund:manage` | same → `QuoteView` (the credit it would give) |
| `GET /customers/{id}/invoices`, `GET /invoices/{id}`, `GET /invoices/{id}/pdf` | read | list, `InvoiceView`, `application/pdf` |
| `GET /customers/{id}/credit-notes`, `GET /credit-notes/{id}`, `GET /credit-notes/{id}/pdf` | read | as invoices |
| `GET /customers/{id}/refunds` | read | `RefundView[]` |
| `POST /customers/{id}/refunds` | `refund:manage` | `{amount, method, reference, paidOn, note}` → 201 `RefundView` |

Customer, under `/api/my/billing` (reads `billing:view`; writes `billing:request`; all writes are `@ReadOnlySafe`):
`GET subscription`, `GET options`, `POST quotes` (kinds `RENEWAL`, `UPGRADE`, `ADDON`; no extra discount), `GET orders`, `POST orders`, `POST orders/{id}/cancel` (own REQUESTED orders it raised), `GET invoices`, `GET invoices/{id}`, `GET invoices/{id}/pdf`, `GET credit-notes` (+ `/{id}`, `/pdf`), `GET refunds`, `GET/PUT profile` (PUT refused with 409 `ORDER_PENDING` while an order is REQUESTED).

Removed: `PUT` and `DELETE /api/billing/customers/{id}/subscription`.

---

### Task 1: Phase 1 carry-overs

**Files:** Modify `service/billing/{SubscriptionService,BillingSettingsService,FeatureGroupService,PlanService}.java`, `security/entitlement/Entitlements.java`, `app/api/ApiExceptionHandler.java`; tests in `app/billing/*IT.java`, `security/entitlement/EntitlementsTest.java`.

- [ ] **Step 1: Write the failing tests**
  - `FeatureGroupsApiIT`: a group whose `requires` names a DEFAULT group is refused (`requires`: "Inbox is in every plan already; nothing needs to require it").
  - `EntitlementsTest`: LIMIT add-ons of 2,000,000,000 and 2,000,000,000 on a limit of 5 give a limit of `Integer.MAX_VALUE`, not a negative number.
  - `BillingApiErrorsIT` (new): a `DuplicateKeyException` thrown by a controller answers 409 `CONFLICT`.
  - `EntitlementsEvictionIT` (new): with `BillingAudit` replaced by a `@MockitoBean` that throws, `BillingSettingsService.update` still leaves the new settings in force for the next `Entitlements.of` call.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** Refuse a `requires` on a DEFAULT group in `FeatureGroupService.apply`; sum limit add-ons as `long` and clamp; map `DuplicateKeyException` like `OptimisticLockingFailureException`; in every billing service call `evict`/`evictAll` straight after the save, before the audit row.
- [ ] **Step 4: Run; expected PASS.** Also the phase 1 billing ITs.
- [ ] **Step 5: Commit** `fix(billing): phase 1 review carry-overs`.

### Task 2: Authorities, documents and reference data

**Files:**
- Modify: `security/access/Authority.java` (+2), `AuthorityTest`, `RoleSeederIT` (pins 59), `db/billing/{BillingSettings,Subscription,TermSegment}.java`, `db/tenant/Customer.java`
- Create: `common/billing/{IndianStates,Gstin,OrderKind,OrderStatus}.java`; `db/billing/{BillingProfile,BillingOrder,OrderLine,OrderCredit,OrderTarget,OrderPayment,TaxAmounts,Invoice,CreditNote,DocumentParty,DocumentLine,Refund,RefundStatus,BillingLock,BillingCounter}.java` and their repositories; `db/billing/BillingDocumentsConfig.java` (GridFS bucket `billingDocuments`, bean `billingDocumentsGridFs`)
- Test: `common/billing/GstinTest.java`, `IndianStatesTest.java`, `app/billing/MoneyDocumentsIT.java`

**Interfaces (Produces):**
- `Authority.BILLING_REQUEST("billing:request", COMPANY, "Raise and cancel the company's own billing requests, edit its billing profile", CUSTOMER_ADMIN)`, `REFUND_MANAGE("refund:manage", PLATFORM, "Cancel a plan or add-on with credit, post account credit, record refunds")`. `SeedGroups` puts `billing:request` in Core by its prefix; `FeatureGroupSeeder` adds it to an existing Core.
- `IndianStates.all() : List<State>` (`record State(String code, String name)`, the 2-digit GST state codes, 01 Jammu and Kashmir … 38 Ladakh, plus 97 Other Territory), `nameOf(code) : Optional<String>`.
- `Gstin.problem(String gstin, String stateCode) : Optional<String>`: empty for null/blank; else must match `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$` ("is not a GSTIN") and start with `stateCode` ("does not belong to the chosen state").
- `enum OrderKind { NEW, RENEWAL, UPGRADE, CHANGE, ADDON, ADJUST, CANCEL_ADDON, CANCEL_PLAN }` with `boolean changesTerm()` (NEW, RENEWAL, UPGRADE, CHANGE, CANCEL_PLAN). `enum OrderStatus { REQUESTED, ACTIVATING, ACTIVATED, CANCELLED, REJECTED, EXPIRED, SUPERSEDED }`.
- `record BillingProfile(String legalName, String address, String stateCode, String gstin)` with `boolean complete()` (name, address and state present). `Customer.billing : BillingProfile`.
- `BillingSettings` gains `supplierLegalName, supplierAddress, supplierStateCode, supplierGstin, sacCode, BigDecimal gstRatePercent (18), invoicePrefix ("INV"), creditNotePrefix ("CN"), int quoteValidDays (7), int renewalWindowDays (30), String paymentInstructions` (how a customer pays a request: bank account, UPI id; shown to customers beside a pending request) and `boolean supplierComplete()`.
- `Subscription` gains `boolean cancelled`, `Set<String> creditedOrderIds, debitedOrderIds, refundIds`. `TermSegment` gains `String planInvoiceId`.
- `record TaxAmounts(BigDecimal ratePercent, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, boolean interState)` with `BigDecimal total()`.
- `BillingOrder` (`billingOrders`; unique `number`; index `{customerId, status}`, `{status, raisedAt}`): `id, number, customerId, OrderKind kind, OrderStatus status, List<OrderLine> lines, BigDecimal extraDiscountPercent, String extraDiscountReason, BigDecimal taxable, TaxAmounts tax, BigDecimal total, List<OrderCredit> credits, BigDecimal creditFromNotes, BigDecimal accountCredit, BigDecimal accountCreditUsed, BigDecimal payable, OrderTarget target, long quotedRevision, long quotedTermRevision, Instant validUntil, String raisedBy, boolean raisedByStaff, Instant raisedAt, String reason, OrderPayment payment, String invoiceId (reserved at raise when total > 0), String rejectedReason, Set<String> steps, @Version`.
  `record OrderLine(String type, String description, String groupCode, LimitKey limitKey, int quantity, BigDecimal listAmount, BigDecimal amount)`; `record OrderCredit(String creditNoteId, String invoiceId, String invoiceNumber, String description, BigDecimal taxable, TaxAmounts tax, BigDecimal total)`; `OrderTarget {String segment ("CURRENT"|"NEXT"|null), TermSegment segmentAfter, Integer anchorDay, String removeAddOnId, Instant newTermEnd, BigDecimal creditPosting, boolean cancelPlan}`; `record OrderPayment(String method, String reference, LocalDate receivedOn, String note, String recordedBy, Instant recordedAt)`.
- `Invoice` (`invoices`; unique `orderId`; unique `{financialYear, sequence}`): `id, number, financialYear, int sequence, orderId, orderNumber, customerId, Instant issuedAt, DocumentParty supplier, DocumentParty customer, sacCode, List<DocumentLine> lines, taxable, TaxAmounts tax, total, totalInWords, accountCreditUsed, amountPaid, OrderPayment payment, BigDecimal credited (0), Set<String> creditNoteIds, String pdfFileId`. `CreditNote` (`creditNotes`; unique `{financialYear, sequence}`): same snapshot fields plus `invoiceId, invoiceNumber, Instant invoiceDate, reason`. `record DocumentParty(String legalName, String address, String stateCode, String stateName, String gstin)`, `record DocumentLine(String description, BigDecimal amount)`.
- `Refund` (`refunds`; unique `number`): `id, number, customerId, amount, method, reference, LocalDate paidOn, note, recordedBy, recordedAt, RefundStatus status (RECORDING|RECORDED|FAILED)`.
- `BillingLock` (`billingLocks`, `_id` = customer id, `holder`, `lockedUntil`), `BillingCounter` (`billingCounters`, `_id` = name, `long value`).

- [ ] **Step 1: Write the failing tests.** `GstinTest`: `29ABCDE1234F1Z5` with state `29` → empty; with state `27` → "does not belong…"; `29abcde1234f1z5`, `29ABCDE1234F1Z`, `XX…` → "is not a GSTIN"; blank → empty. `IndianStatesTest`: 29 is Karnataka, 27 Maharashtra, codes unique, `nameOf("99")` empty. `MoneyDocumentsIT`: an order, an invoice and a credit note round-trip with `Decimal128` amounts; a second invoice with the same `orderId`, or the same `{financialYear, sequence}`, throws `DuplicateKeyException`. Pins 59 in `AuthorityTest` and `RoleSeederIT`; `FeatureGroupSeederIT`: Core contains `billing:request`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS** (with `SeedGroupsTest`, `AuthorityCheckScannerIT`).
- [ ] **Step 5: Commit** `feat(billing): order, invoice, credit note and refund documents`.

### Task 3: Billing settings and the customer's billing profile

**Files:** Modify `service/billing/{BillingSettingsService,SubscriptionViews}.java`, `app/billing/BillingSettingsController.java`; Create `service/billing/BillingProfileService.java`, `app/billing/{BillingProfileController,BillingReferenceController}.java`; Test `app/billing/{BillingSettingsApiIT (extend),BillingProfileApiIT}.java`.

**Interfaces (Produces):** the settings, states and staff profile rows of the contract. `BillingProfileService.get(customerId) : ProfileView`, `put(customerId, ProfileRequest, by)`, `require(customerId) : BillingProfile` (throws 409 `BILLING_NOT_SET_UP` with `missing: "customer billing profile"`). `BillingSettingsService.requireSupplier()` (409 `BILLING_NOT_SET_UP`, `missing: "supplier details in billing settings"`).
Validation: settings: `gstRatePercent` 0–100 with ≤ 2 decimals; prefixes `[A-Z0-9-]{1,10}`; `quoteValidDays` 1–60; `renewalWindowDays` 0–120; `paymentInstructions` at most 1000 characters; supplier state a known code; supplier GSTIN by `Gstin.problem`. Profile: legal name 1–200, address 1–500, state a known code, GSTIN by `Gstin.problem`.

- [ ] **Step 1: Write the failing tests.** Settings: the new fields save and come back; `complete` is false until name, address and state are set; each bad value → 400 on its field. Profile: `GET` for a new customer is empty with `complete: false`; `PUT` saves and records a `BillingChange` (target `SUBSCRIPTION`, kind `PROFILE`); bad GSTIN → 400 `gstin`; account manager may write, customer user gets 403; `GET /states` lists 29 Karnataka.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): supplier settings and customer billing profile`.

### Task 4: Money primitives

**Files:** Create `service/billing/money/{Money,Gst,FinancialYear,AmountInWords,Proration}.java`; Test `service/billing/money/*Test.java` (plain unit tests).

**Interfaces (Produces):**
- `Money.round(BigDecimal) : BigDecimal` (2 decimals HALF_UP), `Money.percentOff(amount, percent)`.
- `Gst.of(BigDecimal taxable, BigDecimal ratePercent, boolean interState) : TaxAmounts`: tax = `round(taxable × rate / 100)`; inter-state → all IGST; else SGST = tax halved and rounded **down** to the paisa, CGST = tax − SGST. `Gst.interState(supplierState, customerState)`.
- `FinancialYear.of(Instant, ZoneId) : String` → `"2026-27"` (April to March).
- `AmountInWords.rupees(BigDecimal) : String`, Indian grouping.
- `Proration.days(LocalDate from, LocalDate to) : long`; `Proration.part(BigDecimal amount, long numeratorDays, long denominatorDays) : BigDecimal` (rounded; 0 when numerator ≤ 0; the whole amount when numerator ≥ denominator).

- [ ] **Step 1: Write the failing tests**
```java
assertThat(Gst.of(bd("900.00"), bd("18"), false)).isEqualTo(new TaxAmounts(bd("18"), bd("81.00"), bd("81.00"), bd("0.00"), false));
assertThat(Gst.of(bd("900.00"), bd("18"), true).igst()).isEqualByComparingTo("162.00");
TaxAmounts odd = Gst.of(bd("100.03"), bd("18"), false);          // 18.0054 → 18.01
assertThat(odd.cgst()).isEqualByComparingTo("9.01"); assertThat(odd.sgst()).isEqualByComparingTo("9.00");
assertThat(Gst.of(bd("464.52"), bd("18"), false).cgst()).isEqualByComparingTo("41.81");   // tax 83.61
assertThat(FinancialYear.of(Instant.parse("2027-03-31T18:29:59Z"), IST)).isEqualTo("2026-27");
assertThat(FinancialYear.of(Instant.parse("2027-03-31T18:30:00Z"), IST)).isEqualTo("2027-28");
assertThat(AmountInWords.rupees(bd("1062.00"))).isEqualTo("Rupees One Thousand Sixty-Two Only");
assertThat(AmountInWords.rupees(bd("239.81"))).isEqualTo("Rupees Two Hundred Thirty-Nine and Eighty-One Paise Only");
assertThat(AmountInWords.rupees(bd("100000"))).isEqualTo("Rupees One Lakh Only");
assertThat(AmountInWords.rupees(bd("12345678.50"))).isEqualTo("Rupees One Crore Twenty-Three Lakh Forty-Five Thousand Six Hundred Seventy-Eight and Fifty Paise Only");
assertThat(AmountInWords.rupees(bd("0.00"))).isEqualTo("Rupees Zero Only");
assertThat(Proration.part(bd("300"), 21, 31)).isEqualByComparingTo("203.23");
assertThat(Proration.part(bd("900"), 16, 31)).isEqualByComparingTo("464.52");
assertThat(Proration.part(bd("203.23"), 16, 21)).isEqualByComparingTo("154.84");
assertThat(Proration.part(bd("300"), 0, 31)).isEqualByComparingTo("0.00");
assertThat(Proration.part(bd("300"), 31, 31)).isEqualByComparingTo("300.00");
```
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): tax, financial year, proration and amount in words`.

### Task 5: Quotes for a new plan and a renewal

**Files:** Create `service/billing/pricing/{Quote,QuoteInput,OrderPricing,QuoteProblems}.java`; Test `service/billing/pricing/NewAndRenewalPricingTest.java`.

**Interfaces (Produces):**
- `record QuoteInput(OrderKind kind, Subscription subscription /* may be null */, SubscriptionState state, Plan plan, Map<String, FeatureGroup> groups, BillingSettings settings, BillingProfile customer, LocalDate today, LocalDate startDate, List<AddOnRequest> addOns, List<String> keepAddOnIds, AddOnRequest addOn, BigDecimal extraDiscountPercent, boolean staff, Function<String, Invoice> invoices)`.
- `record Quote(OrderKind kind, List<OrderLine> lines, BigDecimal extraDiscountPercent, BigDecimal taxable, TaxAmounts tax, BigDecimal total, List<OrderCredit> credits, BigDecimal creditFromNotes, BigDecimal accountCredit, BigDecimal accountCreditUsed, BigDecimal payable, OrderTarget target, Plan plan)`.
- `OrderPricing.quote(QuoteInput) : Quote`, a pure function (no repositories, no clock). Refusals are `ValidationException` on `kind`, `planCode`, `startDate`, `addOns`, `keepAddOnIds`, `addOn`, `extraDiscountPercent`.
- Rules for this task:
  - **Lines.** The plan line: `listAmount` = actual price, `amount` = discounted price, description `"<plan name>, <period> (<first day> – <last day>)"`. Each add-on a line at its full period price (GROUP: the plan's add-on price; LIMIT: `unitPrice × quantity / unit`, quantity a positive multiple of `unit`). The extra discount then reduces every line's `amount` (`Money.percentOff`), staff only, 0–100, ≤ 2 decimals.
  - **Totals.** `taxable` = Σ line amounts; `tax` = `Gst.of(taxable, settings.gstRatePercent, interState)`; `total` = taxable + tax; `accountCredit` = the subscription's `creditBalance` (0 without one); `accountCreditUsed` = min(total, accountCredit + creditFromNotes); `payable` = total − used.
  - **NEW.** Allowed when there is no subscription, or its state is `EXPIRED`; otherwise `kind`: "The customer has a plan; change or renew it". Plan `ACTIVE`. `startDate` defaults to today; the term ends at `BillingPeriod.endOf(start, period, start.getDayOfMonth())` and must end after today. Add-ons validated as in phase 1 (`SubscriptionService.addOns`), but each must be **offered** by the plan. `target.segment = "CURRENT"`, `segmentAfter` complete with `planPaid` = the plan line's `amount`, each add-on's `paid` = its line's `amount`, `coveredFrom` = term start; `anchorDay` = start day.
  - **RENEWAL.** Needs a subscription with no `next` segment and not `cancelled`. Allowed when `ACTIVE` with today within `renewalWindowDays` of the last end, in `GRACE`, or `EXPIRED`; else `kind`: "Renewal opens on <date>". The plan is the current plan's code as it is **now**: `ACTIVE`, or `RETIRED` when staff. Add-ons: those of the segment in force whose id is in `keepAddOnIds` (null = all), each at the plan's current add-on price, or its old `unitPrice` when the plan no longer offers it. The new segment starts at the last end when `ACTIVE` or `GRACE` (`segment = "NEXT"` when `ACTIVE`, `"CURRENT"` in `GRACE`) and today when `EXPIRED` (`"CURRENT"`, new anchor day); it ends at `endOf(start, period, anchorDay)`.

- [ ] **Step 1: Write the failing tests** with the worked example: steps A, F and G give exactly the table's amounts, term dates and `planPaid`; A's `target.segmentAfter.groupCodes` is `{crm}` and `terms` equals `PlanTerms.of(gold)`; step E gives 1416.00, `segment NEXT`, 1 Jan → 1 Feb, the add-on line 300.00; a renewal on 15 Nov (47 days before the end) is refused with "Renewal opens on 2 Dec 2026"; in GRACE on 3 Jan it is `CURRENT` from 1 Jan; EXPIRED on 20 Jan starts 20 Jan with anchor 20; `keepAddOnIds: []` drops the add-on; an add-on the plan stopped offering keeps its old price; a term anchored on the 31st: NEW on 31 Jan 2027 ends 28 Feb, its renewal ends 31 Mar; a customer on `creditBalance` 500 pays 562.00 on step A; NEW for a customer that is ACTIVE is refused; a DRAFT plan is refused; a non-staff extra discount is refused; 10 extra users cost 200.00 for a full period and 7 extra users are refused ("extra users come in fives").
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): quotes for a new plan and a renewal`.

### Task 6: Quotes for add-ons, upgrades and changes

**Files:** Modify `service/billing/pricing/OrderPricing.java`; Create `service/billing/pricing/UnusedCredit.java`; Test `service/billing/pricing/{AddOnPricingTest,UpgradePricingTest,UnusedCreditTest}.java`.

**Interfaces (Produces):**
- `UnusedCredit.of(Subscription, LocalDate today, ZoneId zone, Function<String, Invoice> invoices, List<String> onlyAddOnIds /* null = everything */, BigDecimal overrideTaxable /* null = prorated */) : List<OrderCredit>`: for every paid component (the plan of `current` and `next`, each add-on) with an `invoiceId`: unused = `Proration.part(paid, days(today, segmentEnd), days(coveredFrom, segmentEnd))` for `current`, the whole `paid` for `next`; grouped by invoice into one `OrderCredit` each, with the tax of **that invoice** (`Gst.of(taxable, invoice.tax.ratePercent, invoice.tax.interState)`); the taxable of each is capped at `invoice.taxable − invoice.credited`; components with no invoice, and credits of 0, are left out. With `overrideTaxable` (one component) the taxable is that amount, at most its `paid`.
- **ADDON.** Needs state `ACTIVE` and at least 1 day remaining; the add-on must be offered by the segment's `terms` and not held (a GROUP), with its `requires` satisfied; amount = `Proration.part(fullPrice, remainingDays, termDays)` where `termDays` = days of the segment in force; with a `next` segment a second line charges the full price at the next segment's terms. `target.segmentAfter` = the segment in force with the add-on added (`paid` = the prorated amount, `coveredFrom` = today), and `next` likewise.
- **UPGRADE / CHANGE.** Needs a subscription that is not `cancelled`. Target plan `ACTIVE`. UPGRADE by a customer: the plan is `offered` and (rank higher, or rank equal and period longer), else `planCode`: "Only a higher plan can be requested". The new term starts today with the new plan's period and anchor day today. Add-ons: for UPGRADE every held add-on is carried; for CHANGE the request's `addOns` are the set wanted. A carried GROUP add-on whose group the new plan includes, or a LIMIT add-on whose key the new plan leaves unlimited, is dropped without a charge. The others are charged for a full period at the new plan's add-on price, or, where it offers none, at the old `unitPrice × newMonths / oldMonths`. The set must satisfy every group's `requires`. Credits = `UnusedCredit.of(subscription, today, …, null, null)`; `creditFromNotes` = Σ credit totals. `target.segment = "CURRENT"`, `next` dropped.

- [ ] **Step 1: Write the failing tests** with the worked example: steps B and C give 203.23 / 239.81 and 135.48; an add-on on the first day costs the full 300.00 and on the last day 300 × 1/31 = 9.68; in GRACE it is refused ("Add-ons can be bought while the plan is active"); a held group is refused; with a `next` segment (after step E) an add-on on 25 Dec charges 300 × 7/31 = 67.74 plus 300.00. Step D gives the two credits exactly (464.52 / 83.61 / 548.13 on invoice A; 154.84 / 27.87 / 182.71 on invoice B), `creditFromNotes` 730.84, total 1770.00, payable 1039.16, term 16 Dec → 16 Jan, no automations add-on in the new segment. For Bolt the credit on its invoice is IGST. A phase 1 term (`planInvoiceId` null) yields no credit and payable 1770.00. An upgrade after an early renewal (E then D on 16 Dec) credits 464.52 on invoice A, the add-on's 154.84 on invoice B and the **whole** 1200.00 on the renewal's invoice. A credit larger than what is left on its invoice is capped (invoice A already credited 500.00 → 400.00). Credit above the new total leaves payable 0 and the remainder is reported (`accountCreditUsed` = total). A customer asking for a lower-ranked plan is refused; staff CHANGE to it is priced. CHANGE with `addOns: []` drops the add-on and still credits it. An `ai` add-on carried onto a plan without chatbots is refused on `addOns`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): quotes for add-ons, upgrades and changes`.

### Task 7: Raising, listing, cancelling and rejecting orders

**Files:** Create `service/billing/order/{BillingLocks,BillingNumbers,OrderService,OrderViews,OrderOptions}.java`, `app/billing/{OrderController,MyOrderController}.java`; Modify `app/billing/MyBillingController.java`; Test `service/billing/order/BillingLocksIT.java` (in `omnichannel-app` tests), `app/billing/{OrdersApiIT,MyOrdersApiIT}.java`.

**Interfaces (Produces):**
- `BillingLocks.withCustomer(String customerId, Supplier<T> work) : T`: takes the customer's lease (`billingLocks`, 30 s, the `CrmJobLocks` upsert pattern), waits up to 5 s for a holder to finish (poll 100 ms), runs, gives the lease back; `ORDER_PENDING`-free: a lease not got in time → 409 `CONFLICT` "Another billing change for this customer is in progress; try again".
- `BillingNumbers.next(String series) : long` (`findAndModify` `$inc`, upsert) → order numbers `ORD-%06d`, refund numbers `RF-%06d`.
- `OrderService`: `quote(customerId, QuoteRequest, Caller) : QuoteView`; `raise(customerId, QuoteRequest, Caller) : OrderView` (under the lease: builds the `QuoteInput` from stored state, prices it, stores the order `REQUESTED` with `quotedRevision`, `quotedTermRevision`, `validUntil` = raisedAt + `quoteValidDays`, a reserved `invoiceId` when total > 0 and reserved `creditNoteId`s); `cancel(orderId, Caller)`; `reject(orderId, reason, by)`; `list(customerId)`, `queue(status, page)`, `get(orderId)`; `options(customerId) : OptionsView`. `record Caller(String userId, boolean staff, String customerId /* null for staff */)`.
- Rules: at most one REQUESTED order with `kind.changesTerm()` per customer (else 409 `ORDER_PENDING`); the customer's billing profile must be complete before its first order and the supplier settings before any (409 `BILLING_NOT_SET_UP`); a customer may raise only `RENEWAL`, `UPGRADE`, `ADDON`, never with an extra discount; cancel: REQUESTED only, by staff or by the customer that raised it (a staff-raised order cannot be cancelled by the customer); reject: staff, reason 1–300; both → 409 `ORDER_NOT_OPEN` otherwise. An order past `validUntil` reads as `EXPIRED` in every view even before the ticker has marked it.
- `OptionsView`: `paymentInstructions` from the settings; `renewal {allowed, reason, from}`; `upgrades` = the ACTIVE offered plans the customer may request; `addOns` = what the segment's terms offer that the customer does not hold.

- [ ] **Step 1: Write the failing tests.** `BillingLocksIT`: two threads on one customer run one after the other (a shared counter never sees both inside); a different customer is not held up; a lease left by a dead holder is taken after it expires (advance the clock). `OrdersApiIT`: quote equals the stored order's amounts; a second term-changing order → 409 `ORDER_PENDING`, an ADDON order beside it is fine; no profile → 409 `BILLING_NOT_SET_UP` with `missing`; cancel and reject change status and refuse a second time; an order 8 days old reads `EXPIRED`; the queue lists REQUESTED oldest first with the customer's name; an account manager may do all of this, a customer user gets 403 on `/api/billing/**`. `MyOrdersApiIT`: options list Platinum but not a lower plan; the admin raises an UPGRADE and an ADDON; a NEW or CHANGE or an extra discount → 400; it sees only its own orders (another customer's order id → 404); it cancels its own request, not a staff-raised one (403); a `WORKSPACE_MANAGER` gets 403; all of it works while the customer is read-only (EXPIRED) for RENEWAL.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): raising and managing orders`.

### Task 8: Recording a payment and applying the order

**Files:** Create `service/billing/order/{OrderActivator,OrderSteps}.java`; Modify `OrderService`, `OrderController` (`/pay`), `security/entitlement/SubscriptionClock.java` (`cancelled`); Test `app/billing/{ActivationIT,ActivationRaceIT}.java`, `security/entitlement/SubscriptionClockTest.java` (extend).

**Interfaces (Produces):**
- `OrderActivator.pay(String orderId, PayRequest, String by) : OrderView`. Under the customer's lease, in order:
  0. `finishPending(customerId)`: every `ACTIVATING` order of the customer, oldest first, runs steps 2–5.
  1. **Record.** Refuse unless `REQUESTED` (409 `ORDER_NOT_OPEN`), not past `validUntil` (409 `ORDER_EXPIRED`), and not stale (409 `ORDER_STALE`: for `RENEWAL`, `UPGRADE`, `CHANGE` the subscription's `revision` must equal `quotedRevision`; for `ADDON` its `termRevision` must equal `quotedTermRevision` and the term must not have ended; for `NEW` the customer must still have no plan or the same `revision`). `method` one of `BANK_TRANSFER`, `UPI`, `CHEQUE`, `CASH`, `OTHER` with a `reference` (1–100) and `receivedOn` (not in the future) when payable > 0, and `NONE` only when payable is 0. Then one `findAndModify` `{_id, status: REQUESTED}` → `ACTIVATING` with the payment.
  2. **Apply** (`OrderSteps.apply`): one update on the subscription guarded by `appliedOrderIds ≠ orderId` that sets the segment(s) from `target` (`CURRENT` replaces `current` and clears `next`; `NEXT` sets `next`; an ADDON adds to `current` and, when present, `next`), `anchorDay`, clears `cancelled`, `revision + 1`, `termRevision + 1` when `kind.changesTerm()`, and adds the order id. A `NEW` for a customer without a document is an upsert on `customerId`. The plan component gets `planInvoiceId` = the order's reserved `invoiceId`, each new add-on its `invoiceId` and `orderId`. Then a `BillingChange` with `_id` `"order:" + orderId` (so a repeat does not duplicate it), the superseding of stale orders, and `entitlements.evict`.
  3. Credit notes (Task 10). 4. Invoice (Task 9). 5. `status ACTIVATED`.
  Each finished step adds its name to `steps`; a step whose name is present is skipped.
- **Superseding.** After a term-changing order applies, the customer's other REQUESTED orders become `SUPERSEDED`. After an `ADDON` applies, its other REQUESTED `RENEWAL`, `UPGRADE` and `CHANGE` orders do; other `ADDON` orders stay.
- `SubscriptionClock.stateAt`: a `cancelled` subscription is `EXPIRED` once its last end has passed.

- [ ] **Step 1: Write the failing tests.** `ActivationIT` (worked example, `MutableClock`): pay step A → the customer is on Gold 1 Dec → 1 Jan, `planPaid` 900.00, `/me` loses unentitled features at once; pay again → 409 `ORDER_NOT_OPEN` and nothing changes; step B applies the add-on with `paid` 203.23 and `coveredFrom` 11 Dec; **two add-on orders (B and C) raised together both apply**; a RENEWAL quoted before B is `SUPERSEDED` when B applies and paying it → 409; step E paid on 20 Dec leaves the current segment and its entitlements untouched and sets `next`; paying step A's order 8 days later → 409 `ORDER_EXPIRED`; payable > 0 with `NONE` → 400; payable 0 (100% discount) activates with `NONE`; a missing reference → 400 `reference`; the history row names the order number and is written once. Review Focus 2: with a test double that throws after `apply`, the order stays `ACTIVATING`; calling `finishPending` completes it, and the subscription's `revision` rose by exactly 1.
  `ActivationRaceIT` (Review Focus 1): an ADDON and an UPGRADE quoted at the same revision, paid from two threads: exactly one of the two pays succeeds when the UPGRADE goes first (the ADDON → 409 `ORDER_STALE`), or both when the ADDON goes first then the UPGRADE is stale; in both cases the subscription matches exactly one legal sequence and no order is left `ACTIVATING`. Twenty threads paying the same order: one 200, nineteen 409.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement** steps 0, 1, 2 and 5 (steps 3 and 4 are no-ops until Tasks 9 and 10).
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): payment recording and order activation`.

### Task 9: Invoices

**Files:** Create `service/billing/document/{DocumentNumbers,InvoiceService,DocumentViews}.java`, `app/billing/InvoiceController.java`; Modify `OrderSteps` (step `invoiced`), `MyBillingController`; Test `app/billing/{InvoicesIT,DocumentNumbersIT}.java`.

**Interfaces (Produces):**
- `DocumentNumbers.insertNumbered(String series /* "invoices" | "creditNotes" */, String financialYear, IntFunction<T> withSequence) : T`: reads the highest `sequence` of the year, inserts with the next one, and on `DuplicateKeyException` of the `{financialYear, sequence}` index tries again (up to 50 times). Numbers are `<prefix>/<financialYear>/<6-digit sequence>`.
- `OrderSteps.invoice(order)`: nothing when the order's total is 0. Otherwise, if no invoice with `_id` = `order.invoiceId` exists, insert one through `DocumentNumbers`: supplier and customer snapshots (from the settings and profile **as they are at activation**), lines from the order's lines (`amount`), `taxable`, `tax`, `total`, `totalInWords`, `accountCreditUsed`, `amountPaid` = `payable`, the payment, `issuedAt` = now. Then one update on the subscription guarded by `debitedOrderIds ≠ orderId`: `creditBalance − accountCreditUsed`, add the id.
- `InvoiceService.list(customerId)`, `get(id)`, `getFor(customerId, id)` (404 for another customer's); the invoice rows of the staff and customer contract.

- [ ] **Step 1: Write the failing tests.** `InvoicesIT`: paying step A issues `INV/2026-27/000001` with the table's amounts, both parties' snapshots (state names included), `totalInWords` "Rupees One Thousand Sixty-Two Only", the payment reference, and `amountPaid` 1062.00; step B issues `…000002`; step F (Bolt) shows IGST; a free order issues none and the order's `invoiceNumber` is null; changing the customer's profile afterwards does not change the invoice; an order paid with 500.00 account credit shows `accountCreditUsed` 500.00, `amountPaid` 562.00, and the customer's `creditBalance` falls by 500.00 exactly once even when the step is run twice; the customer sees its own invoices and gets 404 for another's; staff without `subscription:view` get 403. `DocumentNumbersIT` (Review Focus 5): 20 invoices inserted from 8 threads get sequences 1–20 with no gap or duplicate; an invoice issued at `2027-03-31T18:30:00Z` is `INV/2027-28/000001` while the one a second earlier is in `2026-27`; re-running `invoice(order)` after a crash between the insert and the debit issues no second invoice.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): GST invoices with gapless numbers`.

### Task 10: Credit notes and the upgrade

**Files:** Create `service/billing/document/CreditNoteService.java`, `app/billing/CreditNoteController.java`; Modify `OrderSteps` (step `creditNoted`), `MyBillingController`; Test `app/billing/{UpgradeIT,CreditNotesIT}.java`.

**Interfaces (Produces):**
- `OrderSteps.creditNotes(order)`: for each `OrderCredit`: if no credit note with `_id` = `creditNoteId` exists, insert it through `DocumentNumbers` (series `creditNotes`, prefix from settings) with the invoice's snapshot of both parties, the invoice's number and date, one line (`description`, `taxable`), the credit's tax and total, `reason` = the order's kind in words ("Unused time on upgrade", "Add-on cancelled", "Plan cancelled") or the staff's reason; then one update on the invoice guarded by `creditNoteIds ≠ id`: `credited + taxable`, add the id. Finally one update on the subscription guarded by `creditedOrderIds ≠ orderId`: `creditBalance + creditFromNotes`, add the id. Runs **before** the invoice step, so the same order can use the credit.
- `CreditNoteService.list(customerId)`, `get`, `getFor`; the credit-note rows of the contract. `InvoiceView.credited` shows what has been credited against an invoice.

- [ ] **Step 1: Write the failing tests.** `UpgradeIT` (worked example A, B, then D): paying D issues `CN/2026-27/000001` against invoice A (464.52 / 83.61 / 548.13) and `…000002` against invoice B (154.84 / 27.87 / 182.71), then `INV/2026-27/000003` for 1770.00 with `accountCreditUsed` 730.84 and `amountPaid` 1039.16; the customer is on Platinum 16 Dec → 16 Jan without the add-on; `creditBalance` ends at 0.00; invoice A shows `credited` 464.52. A second upgrade the next day credits against invoice 3 only. Review Focus 3: a staff CHANGE down to a plan cheaper than the credit leaves payable 0, issues the notes, and the remainder is in `creditBalance`; an upgrade from a phase 1 (unpaid) term issues no credit note. Review Focus 2: a failure injected between the two credit notes, then `finishPending`: both notes exist once, each invoice's `credited` rose once, `creditBalance` was raised once. `CreditNotesIT`: a credit note carries the invoice's tax split (IGST for Bolt) even after the GST rate in settings is changed; lists and 404 rules as for invoices.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): credit notes and upgrades`.

### Task 11: Staff adjustments and cancellations

**Files:** Create `service/billing/order/StaffOrderService.java`, `app/billing/StaffOrderController.java`; Modify `OrderPricing` (cancellation quotes), `OrderSteps.apply`; Test `app/billing/{AdjustmentsIT,CancellationsIT}.java`.

**Interfaces (Produces):** the adjustments and cancellations rows of the contract. Each call raises an order and activates it at once through `OrderActivator` (payment method `NONE`), under the lease, with a mandatory `reason` (1–300).
- **ADJUST `termEnd`**: sets the end of the last paid segment to that date (after its start); `termRevision + 1`; clears nothing else. **ADJUST `creditAmount`** (signed, ≠ 0): `creditBalance + amount`, refused when the result would be negative (409 `NOT_ENOUGH_CREDIT`). Exactly one of the two.
- **CANCEL_ADDON**: removes the add-on from `current` and `next`; credit = `UnusedCredit` for that add-on, or `creditTaxable` when given (0 ≤ it ≤ what the add-on's invoice has left uncredited for it).
- **CANCEL_PLAN**: `current.termEnd` = 00:00 today, `next` removed, `cancelled = true`; credit = `UnusedCredit` for everything, or `creditTaxable` spread over the invoices in the order plan, add-ons, next (each capped at its `paid`).
- Both supersede every REQUESTED order of the customer.

- [ ] **Step 1: Write the failing tests.** `AdjustmentsIT`: extending the end by 10 days moves `termEnd`, keeps the plan, supersedes a pending renewal, needs only `subscription:manage`; a credit posting of +250.00 needs `refund:manage` (account manager 403), shows in `creditBalance` and the history with the reason; −300.00 with 250.00 held → 409 `NOT_ENOUGH_CREDIT`; both or neither field → 400; a missing reason → 400. `CancellationsIT` (after A and B, on 16 Dec): the quote for cancelling the add-on is 154.84 / 27.87 / 182.71; cancelling it issues one credit note, removes `automation:*` from the customer at once and raises `creditBalance` by 182.71; `creditTaxable` 203.23 credits the full price and 203.24 is refused; cancelling the plan makes the customer `EXPIRED` and read-only at once (no grace), issues credit notes for the plan and the remaining add-on, and a later `NEW` order clears `cancelled`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): staff adjustments and cancellations`.

### Task 12: Refunds

**Files:** Create `service/billing/refund/RefundService.java`, `app/billing/RefundController.java`; Modify `MyBillingController`; Test `app/billing/RefundsIT.java`.

**Interfaces (Produces):** the refund rows. `RefundService.record(customerId, RefundRequest, by) : RefundView` under the lease: validate (`amount` > 0 with ≤ 2 decimals, `method` as for payments except `NONE`, `reference` 1–100, `paidOn` not in the future); insert the refund `RECORDING` with the next `RF-` number; one update on the subscription guarded by `creditBalance ≥ amount` and `refundIds ≠ id` (subtract, add the id); then `RECORDED`. When the guard does not match and the id is not in `refundIds`: mark `FAILED` and answer 409 `NOT_ENOUGH_CREDIT`. `finishPending(customerId)` completes a refund left in `RECORDING`. A `BillingChange` records it.

- [ ] **Step 1: Write the failing tests.** After a credit of 182.71: a refund of 100.00 leaves 82.71 and is listed with its number, method and reference for staff and for the customer; 82.72 → 409 `NOT_ENOUGH_CREDIT` and the refund is `FAILED`; an account manager → 403; a refund left `RECORDING` after the balance update is completed by `finishPending` without subtracting again; one left `RECORDING` before the update is applied once; ten threads refunding 50.00 against 182.71 succeed exactly three times.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): refunds of account credit`.

### Task 13: The billing ticker

**Files:** Create `service/billing/{BillingTicker,BillingSchedulerConfig}.java`; Modify `common/config/OmnichannelProperties.java` (`billing.tick` default `30s`, `billing.autoTick`), `application-test.yml` (`omnichannel.billing.auto-tick: false`); Test `app/billing/BillingTickerIT.java`.

**Interfaces (Produces):** `BillingTicker.tick()`: (1) for each customer with an order `ACTIVATING` for more than 60 s, or a refund `RECORDING` for more than 60 s: under its lease, `finishPending`; (2) orders `REQUESTED` past `validUntil` → `EXPIRED` (one `updateMulti`); (3) subscriptions whose `next.termStart` has passed: one guarded update moves `next` into `current` (guard `next.termStart` equal to the value read). Scheduled like `ChatbotSchedulerConfig` (own one-thread scheduler `billingScheduler`, `@ConditionalOnProperty omnichannel.billing.auto-tick`).

- [ ] **Step 1: Write the failing tests.** An order forced to `ACTIVATING` with no steps done is activated by a tick 61 s later and not by one at 30 s; a tick while the customer's lease is held skips it and the next tick completes it; an order 8 days old becomes `EXPIRED`; after step E, a tick on 1 Jan moves the renewal into `current`, the customer stays ACTIVE across midnight without the tick having run, and a second tick changes nothing.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): ticker for unfinished orders, expiry and renewals`.

### Task 14: PDF files

**Files:** Modify `omnichannel-service/pom.xml` (the PDF library); Create `service/billing/document/{DocumentPdf,BillingDocumentFiles}.java`, a font under `omnichannel-service/src/main/resources/billing/fonts/`; Modify `InvoiceController`, `CreditNoteController`, `MyBillingController` (`/pdf`); Test `service/billing/document/DocumentPdfTest.java`, `app/billing/DocumentPdfIT.java`.

**Interfaces (Produces):**
- `DocumentPdf.invoice(Invoice) : byte[]`, `DocumentPdf.creditNote(CreditNote) : byte[]`: A4, one layout: title ("Tax invoice" / "Credit note"), number and date, supplier and customer blocks (name, address, state with code, GSTIN), for a credit note the invoice it is against, the lines, HSN/SAC, taxable value, CGST and SGST or IGST with the rate, total in figures and words, payment method and reference or the reason, account credit used, "This is a computer-generated document." Rendered from the stored document only.
- `BillingDocumentFiles.pdfOf(Invoice | CreditNote) : byte[]`: serves the GridFS file when `pdfFileId` is set; otherwise renders, stores it in `billingDocuments`, sets `pdfFileId` with an update guarded by `pdfFileId` being null (a lost race deletes its own file and serves the winner's), and returns the bytes.
- Endpoints answer `application/pdf` with `Content-Disposition: attachment; filename="<number with / as ->.pdf"`.
- **Library:** pure Java, no browser, licence fit for a commercial product (Apache-2.0 preferred; Apache PDFBox fits). Check its current documentation before using it. The font must have the rupee sign (for example Noto Sans, OFL); keep its licence file beside it.

- [ ] **Step 1: Write the failing tests.** `DocumentPdfTest`: the bytes start with `%PDF-`; text extracted from the invoice PDF contains the number, both legal names, both GSTINs, "CGST", "81.00", "1,062.00", the total in words and the payment reference; an inter-state invoice contains "IGST" and not "CGST"; the credit note names its invoice; a 40-line invoice has more than one page and every line's description appears. `DocumentPdfIT`: the first download stores one GridFS file and sets `pdfFileId`; a second download serves the same bytes without rendering again (file count unchanged); the customer downloads its own and gets 404 for another's; the headers are as stated.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): invoice and credit note PDF files`.

### Task 15: Views, read-only rules and removing direct assignment

**Files:** Modify `service/billing/{SubscriptionService,SubscriptionViews}.java`, `app/billing/{SubscriptionController,MyBillingController}.java`, `app/billing/ReadOnlySafeHandlersTest.java`, `SubscriptionApiIT.java`, `app/support/TestBilling.java`; Test `app/billing/{SubscriptionViewIT,MyBillingReadOnlyIT}.java`.

**Interfaces (Produces):**
- `SubscriptionView` gains `creditBalance`, `next {planName, termStart, termEnd} | null`, `cancelled`.
- `PUT` and `DELETE /api/billing/customers/{id}/subscription` and `SubscriptionService.assign/remove` are removed; `SubscriptionApiIT` keeps its view, history, list and authority tests, driven by orders or `TestBilling`.
- Every non-GET handler under `/api/my/billing` is `@ReadOnlySafe`; the pinned list in `ReadOnlySafeHandlersTest` gains exactly those.
- `GET/PUT /api/my/billing/profile`: the PUT needs `billing:request` and is refused with 409 `ORDER_PENDING` while the customer has a REQUESTED order.

- [ ] **Step 1: Write the failing tests.** `SubscriptionViewIT`: after step E the view shows `next` with the renewal's dates; after a credit it shows `creditBalance`; after CANCEL_PLAN `cancelled` is true and `state` EXPIRED. `MyBillingReadOnlyIT`: an EXPIRED customer's admin raises a RENEWAL, cancels it and edits its profile (all 2xx) while every other write is still 402; the profile PUT with an open order → 409. The two removed endpoints answer 405 or 404.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS** with all billing ITs.
- [ ] **Step 5: Commit** `feat(billing): subscription view, read-only rules; direct assignment removed`.

### Task 16: Documentation and whole-branch verification

- [ ] **Step 1: Documentation.** `README.md` "Subscription plans": orders and their kinds, activation, invoices, credit notes, account credit, refunds, PDF, the new endpoints and error codes, the removed endpoints. `docs/superpowers/known-limits.md`: a quote is fixed for its validity even if the days pass; GSTIN check digit not verified; no e-invoicing; credit notes are not limited by date (confirm with an accountant); a refund is recorded after the money was paid outside; changing the billing zone or supplier details does not change issued documents; phase 3 not built. `project-rules.md` section 3: where the new code is.
- [ ] **Step 2: Commit** `docs: subscription plans phase 2`.
- [ ] **Step 3: Run the full suite once, with nothing else running.** `./mvnw -q verify > /tmp/claude-501/subscriptions-money-verify.log 2>&1; echo "exit=$?"`. Expected: exit 0. A failure that passes alone is reported by name as flaky, then the full run is repeated.
- [ ] **Step 4: Stop.** Do not merge, push or deploy. Report tasks done, rulings, deferred minors.
