# Subscriptions phase 2 (money), console: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff raise priced orders for a customer, record payments from a requests queue, and handle credit and refunds; a Customer Admin can upgrade, renew, buy add-ons, see how to pay, and download invoices and credit notes.

**Architecture:** Every amount on screen is a field of a server response: a dialog asks the server for a quote, shows it, and raises the order from the same request. One shared quote panel renders any quote or order. Staff screens stay in `src/app/Billing/platform`, the customer's in `src/app/Billing/my`, shared pieces in `src/app/Billing/common`.

**Tech Stack:** React 18, TypeScript (target `es5`), PatternFly 6.5, vitest with `mockFetch` and `renderApp`.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` section 12 (phase 2 parts), in the backend repo.

**Contract:** section "API contract (phase 2)" of `docs/superpowers/plans/2026-11-18-subscriptions-2-money-backend.md`. Build this plan after the backend plan's Task 15. Before Task 1, compare the contract with the records the backend actually built (`service/billing/order/OrderViews.java`, `document/DocumentViews.java`, `SubscriptionViews.java`, the controllers in `app/billing`) and follow the code where they differ, recording the difference in the ledger.

## Global Constraints

- Work only in `omnichannel-crm-frontend/.worktrees/subscriptions-money` (branch `subscriptions-money` from `main`; `npm ci --prefer-offline`). No merge, push or deploy.
- Test first; `npx vitest run <path> --maxWorkers=4`. One render per test.
- **No money arithmetic in the browser**: no sums, percentages, proration or tax. Show server fields through `formatMoney(amount, 'INR')` (`src/app/Crm/common/money.ts`). A number typed by a person (an extra discount, a refund amount) is sent as typed and never combined with another.
- Term dates use `billingDate` and `localDateIn` from `src/app/Billing/common/billingDate.ts` with the zone the server sent.
- A blank number field is never sent as 0: block Save with a message, as `fieldProblems` does in the plan editor.
- Follow the existing patterns: `PageShell`, `useLoad`, `useAction(FIELDS)`, `ConfirmModal`, inline `Alert`s, PF `Table variant="compact"`, `BillingHistoryModal`.
- Fixtures must match the server's shapes field for field.
- Prettier on touched files only. Before finishing: `npx tsc --noEmit && npm run lint && npm run build`.
- Wording: "plan", "add-on", "request" (what a customer raises), "order" (staff screens), "invoice", "credit note", "account credit", "refund". Never "template".
- Commit trailer as the session reminder says.

## Review Focus

1. A quote that arrives after the person changed the form: the panel never shows figures for a request other than the one on screen, and Raise is disabled until the shown quote matches the form (Task 3).
2. Recording a payment twice (double click, or two staff): the second attempt shows the server's refusal and the list shows the order as activated once (Task 4).
3. A stale or expired request: paying it shows "This quote is out of date; raise it again" / "This request has expired", not a generic error, and the queue reloads (Task 4).
4. A PDF download that fails (404, network): an inline error beside the button, no blank tab, no unhandled rejection (Task 5).
5. A customer in read-only can still renew: the Renew action, its quote and its request work while every other action on the page is absent or refused with the plan message (Task 7).

---

### Task 1: API module, download helper and fixtures

**Files:** Modify `src/app/api/billing.ts`, `src/app/api/client.ts`, `src/test/billing.ts`, `src/test/session.ts`; Test `src/app/api/billing.test.ts` (extend), `src/app/api/client.test.ts` (extend).

**Interfaces (Produces):**
```ts
export type OrderKind = 'NEW' | 'RENEWAL' | 'UPGRADE' | 'CHANGE' | 'ADDON' | 'ADJUST' | 'CANCEL_ADDON' | 'CANCEL_PLAN';
export type OrderStatus = 'REQUESTED' | 'ACTIVATING' | 'ACTIVATED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED';
export type PaymentMethod = 'BANK_TRANSFER' | 'UPI' | 'CHEQUE' | 'CASH' | 'OTHER' | 'NONE';
export interface Tax { ratePercent: number; cgst: number; sgst: number; igst: number; interState: boolean }
export interface OrderLine { type: 'PLAN' | 'ADDON' | 'LIMIT_ADDON'; description: string; groupCode: string | null;
  limitKey: string | null; quantity: number; listAmount: number; amount: number }
export interface OrderCredit { invoiceId: string; invoiceNumber: string; description: string; taxable: number; tax: Tax; total: number }
export interface Quote { kind: OrderKind; lines: OrderLine[]; extraDiscountPercent: number; taxable: number; tax: Tax;
  total: number; credits: OrderCredit[]; creditFromNotes: number; accountCredit: number; accountCreditUsed: number;
  payable: number; planCode: string | null; planName: string | null; period: BillingPeriod | null;
  termStart: string | null; termEnd: string | null; validUntil: string | null }
export interface Order extends Quote { id: string; number: string; customerId: string; customerName: string;
  status: OrderStatus; raisedBy: string; raisedByName: string; raisedByStaff: boolean; raisedAt: string;
  reason: string | null; extraDiscountReason: string | null;
  payment: { method: PaymentMethod; reference: string | null; receivedOn: string | null; note: string | null;
    recordedByName: string; recordedAt: string } | null;
  invoiceId: string | null; invoiceNumber: string | null; creditNoteIds: string[]; rejectedReason: string | null }
export interface QuoteRequest { kind: OrderKind; planCode?: string; startDate?: string; addOns?: SubscriptionAddOnInput[];
  keepAddOnIds?: string[] | null; addOn?: SubscriptionAddOnInput; extraDiscountPercent?: number; extraDiscountReason?: string }
export interface Party { legalName: string; address: string; stateCode: string; stateName: string; gstin: string | null }
export interface Invoice { id: string; number: string; issuedAt: string; orderId: string; orderNumber: string;
  supplier: Party; customer: Party; sacCode: string | null; lines: { description: string; amount: number }[];
  taxable: number; tax: Tax; total: number; totalInWords: string; accountCreditUsed: number; amountPaid: number;
  payment: Order['payment']; credited: number }
export interface CreditNote { id: string; number: string; issuedAt: string; orderId: string; invoiceId: string;
  invoiceNumber: string; invoiceDate: string; supplier: Party; customer: Party;
  lines: { description: string; amount: number }[]; taxable: number; tax: Tax; total: number; totalInWords: string; reason: string }
export interface Refund { id: string; number: string; amount: number; method: PaymentMethod; reference: string;
  paidOn: string; note: string | null; recordedByName: string; recordedAt: string; status: 'RECORDING' | 'RECORDED' | 'FAILED' }
export interface BillingProfile { legalName: string; address: string; stateCode: string; gstin: string | null; complete: boolean }
export interface BillingOptions { paymentInstructions: string | null;
  renewal: { allowed: boolean; reason: string | null; from: string | null };
  upgrades: { code: string; name: string; period: BillingPeriod; rank: number; discountedPrice: number }[];
  addOns: { kind: 'GROUP' | 'LIMIT'; groupCode: string | null; name: string | null; limitKey: string | null;
    label: string | null; unit: number; unitPrice: number }[] }
```
`BillingSettings` gains `supplierLegalName, supplierAddress, supplierStateCode, supplierGstin, sacCode, gstRatePercent, invoicePrefix, creditNotePrefix, quoteValidDays, renewalWindowDays, paymentInstructions, complete`. `Subscription` gains `creditBalance, next: {planName, termStart, termEnd} | null, cancelled`.
Functions (all `(token, …)`): staff `listStates`, `getBillingProfile(customerId)`, `putBillingProfile`, `quoteFor(customerId, req)`, `raiseOrder(customerId, req)`, `listCustomerOrders`, `listOrders({status, page})`, `getOrder`, `payOrder(id, {method, reference, receivedOn, note})`, `rejectOrder(id, reason)`, `cancelOrder(id)`, `adjust(customerId, {termEnd?, creditAmount?, reason})`, `quoteCancellation(customerId, {addOnId, creditTaxable?, reason})`, `cancelSubscriptionPart(customerId, …)`, `listCustomerInvoices`, `getInvoice`, `listCustomerCreditNotes`, `getCreditNote`, `listCustomerRefunds`, `recordRefund`; customer `getMyOptions`, `myQuote`, `raiseMyOrder`, `listMyOrders`, `cancelMyOrder`, `listMyInvoices`, `getMyInvoice`, `listMyCreditNotes`, `getMyCreditNote`, `listMyRefunds`, `getMyProfile`, `putMyProfile`. PDF paths: `invoicePdfPath(id, mine)`, `creditNotePdfPath(id, mine)`.
Removed: `putCustomerSubscription`, `deleteCustomerSubscription`, `AssignInput`.
`client.ts`: `apiDownload(path, token): Promise<Blob>` (same error handling as `apiFetch`, including the 401 and 402 handlers). `src/app/Billing/common/download.ts`: `saveBlob(blob, filename)` (object URL + a temporary `a[download]`, revoked afterwards).
Fixtures: `quote(over)`, `order(over)`, `invoice(over)`, `creditNote(over)`, `refund(over)`, `PROFILE`, `OPTIONS`, with the backend plan's worked example (step A: 900.00 / CGST 81.00 / SGST 81.00 / 1062.00; step D: two credits, payable 1039.16). Personas: `SUPER_ADMIN` gains `refund:manage`; `CUSTOMER_ADMIN` gains `billing:request`.

- [ ] **Step 1: Write the failing tests:** `raiseOrder` posts the request body to `/api/billing/customers/c1/orders`; `payOrder` posts to `/orders/o1/pay`; `listOrders({status: 'REQUESTED', page: 0})` adds both query parameters; `myQuote` posts to `/api/my/billing/quotes`; `apiDownload` returns a Blob for a 200 and rejects with an `ApiError` carrying the code for a 404 JSON body.
- [ ] **Step 2: Run; expected FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expected PASS.** Removing `putCustomerSubscription` and `deleteCustomerSubscription` breaks `AssignPlanModal` and the card's Remove action: delete `AssignPlanModal.tsx`, its use and the Remove action in `SubscriptionCard.tsx`, and their tests, in this commit (the card has no plan actions until Task 3). `npx tsc --noEmit` must be clean.
- [ ] **Step 5: Commit** `feat(billing): orders, invoices, credit notes and refunds in the API module`.

### Task 2: Billing settings and the customer's billing profile

**Files:** Modify `src/app/Billing/platform/BillingSettingsPage.tsx`; Create `src/app/Billing/common/{ProfileForm,StateSelect}.tsx`, `src/app/Billing/platform/BillingProfileCard.tsx`; Modify `src/app/Customers/CustomerDetailPage.tsx`; Test `billingSettings.test.tsx` (extend), `billingProfile.test.tsx`.

**Interfaces (Produces):**
- Settings page gains a "Supplier" section (legal name, address, `StateSelect`, GSTIN, HSN/SAC code), "Invoices" (GST rate %, invoice prefix, credit-note prefix, quote valid days, renewal window days) and "How customers pay" (a text area, shown to customers beside a pending request). A warning `Alert` "Fill in the supplier details: no order can be raised until they are complete." while `complete` is false.
- `StateSelect {id, value, onChange, states}` (PF `FormSelect`, "29 · Karnataka").
- `ProfileForm {value, states, readOnly, errors, onChange}`: legal name, address, state, GSTIN (optional), used by staff and customer.
- `BillingProfileCard {customerId}` on the customer page (with `subscription:view`; editable with `subscription:manage`): shows the profile or "Not filled in yet", Edit opens a modal with `ProfileForm`; server field errors under the fields.

- [ ] **Step 1: Write the failing tests:** the settings page saves the new fields in one PUT and shows the incomplete warning until the server says `complete`; blank GST rate or validity days blocks Save with a message; a 400 `supplierGstin` shows under the field; the profile card shows "Not filled in yet", saves, and shows a 400 `gstin`; without `subscription:manage` there is no Edit.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): supplier settings and billing profile`.

### Task 3: The quote panel and the staff order dialog

**Files:** Create `src/app/Billing/common/{QuotePanel,useQuote}.tsx`, `src/app/Billing/platform/OrderDialog.tsx`; Delete `src/app/Billing/platform/AssignPlanModal.tsx`; Modify `SubscriptionCard.tsx`; Test `quotePanel.test.tsx`, `orderDialog.test.tsx`, `subscriptionCard.test.tsx` (rewrite the assign cases).

**Interfaces (Produces):**
- `QuotePanel {quote: Quote | null, pending, error}`: a region "Price" with a line per `lines[]` (description, and `listAmount` struck through when it differs from `amount`), "Extra discount N%" when > 0, "Taxable value", "CGST 9% / SGST 9%" or "IGST 18%" from `tax`, "Total"; then, when present, a line per credit ("Credit for unused time on <invoiceNumber>"), "Account credit used", and **"Amount payable"** in bold; the term ("1 Dec 2026 – 31 Dec 2026") and "Valid until <date>". Dimmed while `pending`; an inline error when `error`.
- `useQuote(request: QuoteRequest | null, fetchQuote)`: asks 300 ms after the last change; ignores an answer for an older request; returns `{quote, pending, error, matches}` where `matches` is true only when `quote` answers the current `request`.
- `OrderDialog {customerId, subscription, kind: 'NEW' | 'CHANGE' | 'RENEWAL' | 'ADDON', onRaised(order), onCancel}`:
  - NEW and CHANGE: plan select (ACTIVE plans), start date (NEW only, default today in the zone), add-ons the chosen plan offers (CHANGE prefilled with what is held), extra discount % with a reason (required when > 0).
  - RENEWAL: the held add-ons as ticked checkboxes; extra discount.
  - ADDON: one add-on from the plan's offers (a group, or a limit with a quantity in steps of its `unit`); extra discount.
  - `QuotePanel` underneath; **Raise order** is disabled unless `matches`, and posts the same request.
  - Field errors from quote and raise under their fields; 409 `BILLING_NOT_SET_UP` shows what is missing with a link to the settings page or the profile card; 409 `ORDER_PENDING` says "This customer already has an open request that changes its plan."
- `SubscriptionCard`: actions become "New plan" (no plan, or EXPIRED), "Change plan", "Renew", "Add add-on" (with `subscription:manage`); "Remove plan" is gone (Task 6 adds Cancel plan); the card shows "Account credit ₹…" when `creditBalance` > 0, "Renewed: <next plan>, from <date>" when `next`, and "Cancelled" when `cancelled`.

- [ ] **Step 1: Write the failing tests:** `QuotePanel` renders step A (intra-state) and step F (IGST) and step D (two credit lines, "Amount payable ₹1,039.16") from fixtures, with no figure that is not a fixture field. Review Focus 1: in `OrderDialog`, change the plan while the first quote is in flight; the first answer is ignored, Raise stays disabled until the second arrives, and the raised request equals the form. The dialog for each kind sends the right `QuoteRequest`; an extra discount without a reason blocks Raise; `BILLING_NOT_SET_UP` and `ORDER_PENDING` show their sentences; after raising, the card's orders reload (callback called).
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): priced orders from the customer page`.

### Task 4: The requests queue and recording a payment

**Files:** Create `src/app/Billing/platform/{RequestsPage,OrderDetailModal,RecordPaymentForm,OrderStatusLabel}.tsx`, `src/app/Billing/common/orderText.ts`; Modify `src/app/Billing/routes.tsx`, `src/app/routes.tsx` (nav "Billing requests" under Platform, `subscription:view`), `src/app/routes.test.tsx`, `src/app/api/messages.ts`; Test `requests.test.tsx`.

**Interfaces (Produces):**
- `/platform/billing-requests`: status toggle (Open | All), table Number, Customer (link), Kind, Amount payable, Raised (who, when), Valid until, Status; row opens `OrderDetailModal`.
- `OrderDetailModal {orderId, onChanged}`: `QuotePanel` for the order, who raised it, and with `subscription:manage` on a REQUESTED order: **Record payment** (`RecordPaymentForm`: method select, reference, received on (default today), note; when payable is 0 a single **Approve** button instead), **Reject** (reason required), **Cancel request**. After activation: links to the invoice and credit notes.
- `orderText.ts`: `KIND_LABEL` ("New plan", "Renewal", "Upgrade", "Plan change", "Add-on", "Adjustment", "Add-on cancelled", "Plan cancelled"), `STATUS_LABEL`.
- `messages.ts` codes: `order_stale` "This quote is out of date; raise it again.", `order_expired` "This request has expired; raise it again.", `order_not_open` "This request is no longer open.", `order_pending` "There is already an open request that changes the plan.", `billing_not_set_up` "Billing is not set up yet: <missing>.", `not_enough_credit` "There is not that much account credit."

- [ ] **Step 1: Write the failing tests:** the queue lists open orders oldest first and filters; recording a payment posts `{method, reference, receivedOn, note}`, closes the form, shows the order ACTIVATED with its invoice number; a missing reference blocks Save; Review Focus 2: a second submit answered 409 `ORDER_NOT_OPEN` shows its sentence and reloads the order; Review Focus 3: 409 `ORDER_STALE` and `ORDER_EXPIRED` show their sentences and the queue reloads; a payable of 0 shows Approve and sends `method: 'NONE'`; Reject needs a reason; a viewer without `subscription:manage` sees no actions; nav pins updated.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): requests queue and payment recording`.

### Task 5: Orders, invoices, credit notes and refunds on the customer page

**Files:** Create `src/app/Billing/common/{DocumentView,DocumentsTable,PdfButton,OrdersTable,RefundsTable}.tsx`, `src/app/Billing/platform/{CustomerBillingTabs,InvoicePage,CreditNotePage}.tsx`; Modify `src/app/Billing/routes.tsx` (`/platform/invoices/:id`, `/platform/credit-notes/:id`), `CustomerDetailPage.tsx`; Test `customerBilling.test.tsx`, `documentView.test.tsx`.

**Interfaces (Produces):**
- `CustomerBillingTabs {customerId}` under the subscription card: tabs Orders (`OrdersTable`, row opens `OrderDetailModal`), Invoices, Credit notes (`DocumentsTable`: Number (link), Date, Taxable, Tax, Total, and for invoices Credited), Refunds (`RefundsTable`).
- `DocumentView {kind: 'invoice' | 'creditNote', document}`: title, number, date, supplier and customer blocks (name, address, "29 · Karnataka", GSTIN), for a credit note the invoice it is against, lines, HSN/SAC, taxable value, tax lines, total, total in words, payment or reason, account credit used.
- `PdfButton {path, filename}`: "Download PDF"; `apiDownload` then `saveBlob`; busy while downloading; an inline error on failure.
- `InvoicePage`, `CreditNotePage`: `PageShell` + `DocumentView` + `PdfButton`.

- [ ] **Step 1: Write the failing tests:** the four tabs list fixtures with server amounts; the invoice page shows every block of step A including "Rupees One Thousand Sixty-Two Only"; the credit note names its invoice; an inter-state document shows IGST only; clicking Download PDF requests the PDF path with the Authorization header and calls `saveBlob` with `INV-2026-27-000001.pdf`; Review Focus 4: a 404 shows "That item no longer exists or you can't see it." beside the button and nothing is saved.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): invoices, credit notes and refunds for staff`.

### Task 6: Credit actions for staff

**Files:** Create `src/app/Billing/platform/{CancelDialog,AdjustDialog,RefundDialog}.tsx`; Modify `SubscriptionCard.tsx`; Test `creditActions.test.tsx`.

**Interfaces (Produces):** with `refund:manage` the card gains, in a kebab "More": **Cancel add-on** (per held add-on), **Cancel plan**, **Post account credit**, **Record refund**; with `subscription:manage`: **Extend term**.
- `CancelDialog {customerId, addOn | null}`: a reason; the server's cancellation quote in `QuotePanel` ("Credit ₹182.71"); an optional "Credit a different taxable amount" field sent as `creditTaxable` (the quote is asked again with it); for the plan a danger note "The customer becomes read-only at once."; Confirm posts the cancellation.
- `AdjustDialog {customerId, mode: 'termEnd' | 'credit'}`: a date (the day after the last day) or a signed amount, and a reason.
- `RefundDialog {customerId, creditBalance}`: amount (shows "Account credit ₹…" from the server), method, reference, paid on, note. The amount is sent as typed; the console does not compare it with the balance.

- [ ] **Step 1: Write the failing tests:** each dialog posts its body and the card reloads; a blank reason or amount blocks Confirm; the cancellation quote panel shows the fixture credit and is asked again when a different amount is typed; 409 `NOT_ENOUGH_CREDIT` shows its sentence in the refund dialog; an account manager (no `refund:manage`) sees only Extend term.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): cancellations, account credit and refunds`.

### Task 7: The customer's Plan & billing page

**Files:** Modify `src/app/Billing/my/MyBillingPage.tsx`; Create `src/app/Billing/my/{MyOrderDialog,MyRequests,MyDocuments,MyProfileCard,MyInvoicePage,MyCreditNotePage}.tsx`; Modify `src/app/Billing/routes.tsx` (`/billing/invoices/:id`, `/billing/credit-notes/:id`); Test `myBilling.test.tsx` (extend), `myOrders.test.tsx`.

**Interfaces (Produces):** with `billing:request`:
- On the plan card: **Renew** (enabled when `options.renewal.allowed`, else disabled with `options.renewal.reason` as a tooltip), **Upgrade** (when `options.upgrades` is not empty).
- "Add-ons you can buy": a row per `options.addOns` with **Buy**.
- `MyOrderDialog {kind: 'RENEWAL' | 'UPGRADE' | 'ADDON'}`: as the staff dialog without an extra discount; the upgrade lists `options.upgrades` with their prices; `QuotePanel`; **Send request**.
- `MyRequests`: the customer's orders, newest first; an open one shows "Amount payable ₹…, valid until …", the settings' payment instructions under "How to pay", and **Cancel request** for requests it raised; others show their status, and the rejection reason when rejected.
- `MyDocuments`: Invoices and Credit notes tables linking to `MyInvoicePage` / `MyCreditNotePage` (`DocumentView` + `PdfButton` with the `/api/my/billing/…/pdf` paths); "Account credit ₹…" when > 0; Refunds.
- `MyProfileCard`: `ProfileForm`; Save disabled with a note while a request is open; "Fill in your billing details before sending a request" when incomplete.
- Without `billing:request` (a custom role with `billing:view` only): everything is shown, no actions.

- [ ] **Step 1: Write the failing tests:** Upgrade shows Platinum with the fixture quote (credits and "Amount payable ₹1,039.16") and sends `{kind: 'UPGRADE', planCode: 'platinum-monthly'}`; Buy sends the add-on; Renew is disabled with "Renewal opens on 2 Dec 2026" and enabled when allowed; an open request shows how to pay and can be cancelled; a rejected one shows the reason; invoices list and the PDF button uses the `my` path; the profile card saves and is locked with an open request. Review Focus 5: with the persona EXPIRED (banner shown) Renew works end to end while Buy and Upgrade are absent. A viewer without `billing:request` sees no buttons.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): upgrades, renewals and add-ons for the customer`.

### Task 8: Whole-branch checks

- [ ] **Step 1:** `npx vitest run --maxWorkers=4 > /tmp/claude-501/subscriptions-money-console.log 2>&1; tail -30 /tmp/claude-501/subscriptions-money-console.log`. Expected: all pass.
- [ ] **Step 2:** `npx tsc --noEmit && npm run lint && npm run build`. Expected: no errors.
- [ ] **Step 3:** `grep -rnE "[-+*/] *(price|amount|total|taxable|payable|credit)|toFixed|Math\.round" src/app/Billing --include='*.ts' --include='*.tsx' | grep -v test`. Expected: nothing that computes money (explain any line it prints).
- [ ] **Step 4: Stop.** Do not merge, push or deploy. Report tasks done, rulings and deferred minors. The walk on 8089 (fill supplier settings and Demo Retail's profile, raise a NEW order, record the payment, open the invoice and its PDF, buy an add-on as the customer, upgrade, cancel the add-on, record a refund) is done with the user after the deploy.
