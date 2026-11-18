# Subscriptions phase 3 (usage wallet), backend: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A customer holds a prepaid wallet in rupees, tops it up through a paid order, and every WhatsApp message the server sends for it is charged at its plan's rate; an empty wallet stops sending without burning work, and staff can adjust or refund the wallet.

**Architecture:** The wallet is one document per customer. Every change to it is a single `findAndModify` that moves the balance and pushes the complete ledger entry onto the document's own `pending` array, so balance and ledger can never disagree and the balance cannot go below zero; a mover copies pending entries into the kept `walletEntries` collection. A send is charged just before it reaches the device and the charge is returned only when the device definitely refused. Top-ups and wallet refunds are orders, so they reuse phase 2's lease, steps, invoices and credit notes.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), MongoDB standalone (no transactions), embedded Mongo and `MutableClock` in tests.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` sections 6.5, 7.1 (`TOPUP`, `WALLET_REFUND`), 9.2 step 3, 9.5, 10, and the wallet lines of 11.

## Global Constraints

- Work only in `omnichannel-crm-backend/.worktrees/subscriptions-wallet` (branch `subscriptions-wallet` from `main`). No merge, push or deploy.
- Test first. `./mvnw -q -o -pl omnichannel-app -am test -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false`; one Maven build at a time; the full `./mvnw -q -o verify` once, alone, at the end.
- Wallet amounts and rates are `BigDecimal` rupees at **4 decimals**, stored as `Decimal128`; order, invoice and credit-note amounts stay at 2 decimals. A top-up or wallet-refund amount has at most 2 decimals.
- The wallet balance is never negative. A debit is one update guarded by `balance ≥ amount`.
- Collections camelCase, kept, no TTL: `wallets`, `walletEntries`.
- Every change to a subscription or a wallet made by an order goes through `OrderActivator` under the customer's lease. Usage debits and reversals do **not** take the lease (they are single guarded updates and must not wait behind billing work).
- A customer with no subscription, or whose term's snapshot has no rate above 0 for `WHATSAPP_MESSAGE`, is never charged and its sends never touch the wallet.
- Incoming messages are always stored. Messages typed on the phone and the Super Admin bridge endpoints are never charged.
- Error codes: 402 `WALLET_EMPTY` (a send refused for lack of balance), 409 `NOT_ENOUGH_WALLET` (an adjustment or wallet refund above what may be taken).
- Never "template" for anything here. Commit trailer as the session reminder says.

## Review Focus

1. Many sends at once against a nearly empty wallet: exactly as many are charged as the balance covers, the rest are refused with `WALLET_EMPTY`, and the balance ends at or above 0 (Task 3).
2. A crash between a wallet update and the mover: the balance already reflects the entry, the entry is in `pending`, and the ledger a user sees (entries plus pending) always sums to the balance; moving twice writes one row (Tasks 2, 3).
3. A top-up or wallet refund re-driven after a stop: the wallet is credited or debited once, even after its entry has left `pending` (Task 5, 6).
4. A send whose outcome is unknown (timeout, device disconnected mid-send) stays charged; a send the device refused, or that failed before reaching the device, is not charged or is returned (Task 7).
5. A campaign that meets an empty wallet pauses with its reason and loses no recipient: the claimed recipient goes back to pending and nothing is marked failed (Task 8).

## Worked example (used by the tests)

Fixture: `MoneyApiTestBase` (clock at 1 Dec 2026 10:30 IST; supplier in state 29; Acme in 29, Bolt in 27). Gold gets `rates: {WHATSAPP_MESSAGE: 0.25}`.

| Step | Action | Expected |
|---|---|---|
| T | Acme on Gold. `TOPUP` 1000.00 | taxable 1000.00, CGST 90.00, SGST 90.00, total 1180.00, payable 1180.00 (account credit is never used for a top-up); once paid: wallet 1000.0000, entry `TOPUP +1000.0000`, invoice with line "Wallet top-up" |
| U | three messages sent | three `USAGE −0.2500` entries, wallet 999.2500 |
| V | a fourth send the device refuses (`SEND_FAILED`) | `USAGE −0.2500` then `REVERSAL +0.2500`, wallet 999.2500 |
| W | staff adjustment −999.0500 "test" | wallet 0.2000; the next send → 402 `WALLET_EMPTY`, nothing dispatched |
| X | (fresh, after T) wallet refund 400.00 | credit note on the top-up invoice: taxable 400.00, tax 72.00, total 472.00; wallet 600.0000; account credit +472.00 |
| Y | (after T) staff adjustment +500 then wallet refund 1200.00 | 409 `NOT_ENOUGH_WALLET`: only 1000.00 came from top-ups |

## API contract (phase 3)

```
QuoteRequest  + topUpAmount: number                      // TOPUP only
OrderLine.type + "TOPUP";  OrderKind + "TOPUP" | "WALLET_REFUND"
WalletView    {balance, lowBalanceAt, low: boolean, messageRate: number | null,   // null = not charged
               refundable}                                // what a wallet refund could return, before tax
WalletEntryView {id, type: "TOPUP"|"USAGE"|"REVERSAL"|"ADJUSTMENT"|"REFUND", amount, balanceAfter,
               meter, source: "CONSOLE"|"CAMPAIGN"|"AUTOMATION"|"CHATBOT"|null, workspaceId, workspaceName,
               ref, note, byName, at}
EntryPage     {items: WalletEntryView[], total, page}     // 50 per page, newest first
UsageDay      {day: "2026-12-01", source, messages, amount}   // amount = usage minus reversals
OptionsView   + topUp: {allowed, reason, min, max}, messageRate
SubscriptionSummary (in /api/users/me) + lowWallet: boolean
```

Staff, under `/api/billing` (reads `subscription:view`):

| Method and path | Authority | Body → result |
|---|---|---|
| `GET /customers/{id}/wallet` | read | → `WalletView` |
| `GET /customers/{id}/wallet/entries?page=` | read | → `EntryPage` |
| `GET /customers/{id}/wallet/usage?days=` (1–90, default 30) | read | → `UsageDay[]` |
| `POST /customers/{id}/wallet/adjustments` | `subscription:manage` | `{amount, note}` (signed, ≠ 0, ≤ 4 decimals) → `WalletView` |
| `PUT /customers/{id}/wallet/threshold` | `subscription:manage` | `{lowBalanceAt}` (≥ 0 or null) → `WalletView` |
| `POST /customers/{id}/wallet/refunds/quote`, `POST /customers/{id}/wallet/refunds` | `refund:manage` | `{amount, reason}` → `QuoteView` / `OrderView` (ACTIVATED) |
| `POST /customers/{id}/quotes`, `/orders` with `kind: "TOPUP"` | `subscription:manage` | as phase 2 |

Customer, under `/api/my/billing` (reads `billing:view`; top-up through the existing `quotes` and `orders` with `billing:request`, `@ReadOnlySafe` as they are): `GET wallet`, `GET wallet/entries?page=`, `GET wallet/usage?days=`. A customer's entries carry no `byName` and no `note` of an adjustment.

---

### Task 1: Documents, enums and the rate in the entitlement

**Files:** Create `common/billing/{WalletEntryType,UsageSource}.java`, `db/billing/{Wallet,WalletEntry,WalletRepository,WalletEntryRepository}.java`; Modify `common/billing/OrderKind.java`, `db/billing/{OrderLine,Invoice,BillingOrder}.java`, `security/entitlement/{Entitlement,Entitlements}.java`, `app/support/TestBilling.java`; Test `app/billing/WalletDocumentsIT.java`, `security/entitlement/EntitlementsTest.java` (extend).

**Interfaces (Produces):**
- `enum WalletEntryType { TOPUP, USAGE, REVERSAL, ADJUSTMENT, REFUND }`; `enum UsageSource { CONSOLE, CAMPAIGN, AUTOMATION, CHATBOT }` with `static UsageSource of(MessageSource)`.
- `Wallet` (`wallets`, unique `customerId`): `id, customerId, BigDecimal balance (Decimal128, 0), BigDecimal lowBalanceAt (nullable), List<WalletEntry> pending, Set<String> orderIds, @Version`.
- `WalletEntry` (`walletEntries`; index `{customerId, at desc}`, `{customerId, type, at}`): `id, customerId, WalletEntryType type, BigDecimal amount (signed), BigDecimal balanceAfter, UsageMeter meter, UsageSource source, String workspaceId, String ref, String note, String by, Instant at`. The same class is the element of `Wallet.pending`.
- `OrderKind` gains `TOPUP`, `WALLET_REFUND` (neither `changesTerm()`); `OrderLine.TOPUP = "TOPUP"`; `Invoice.orderKind` (set from the order when issued; null on phase 2 invoices); `BillingOrder.STEP_WALLET = "wallet"`.
- `Entitlement` gains `Map<UsageMeter, BigDecimal> rates` (from the term in force's `terms.rates()`, empty when none) and `BigDecimal rate(UsageMeter)` (null when absent or not above 0).
- `TestBilling.reset()` also clears `wallets` and `walletEntries`; `TestBilling.wallet(customerId, balance)` writes a wallet directly.

- [ ] **Step 1: Write the failing tests.** `WalletDocumentsIT`: a wallet with a pending entry round-trips with `Decimal128` amounts at 4 decimals (`0.2500` stays `0.2500`); a second wallet for the same customer throws `DuplicateKeyException`. `EntitlementsTest`: a term with `rates {WHATSAPP_MESSAGE: 0.25}` gives `rate(WHATSAPP_MESSAGE)` 0.25; a rate of 0 or no rate gives null; a customer without a plan gives null.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS** with `AuthorityTest`, `MoneyDocumentsIT`.
- [ ] **Step 5: Commit** `feat(billing): wallet documents and the message rate in the entitlement`.

### Task 2: Wallet operations and the mover

**Files:** Create `service/billing/wallet/{WalletService,WalletMover,WalletEmptyException}.java` (the exception in `common/api`); Test `app/billing/WalletOperationsIT.java`.

**Interfaces (Produces):**
- `WalletService`:
  - `String debit(String customerId, BigDecimal amount, WalletEntry draft) : entryId` — one `findAndModify` `{customerId, balance ≥ amount}` → `$inc balance −amount`, `$push pending` the entry (fresh id, `balanceAfter` filled from the returned document by a second cheap update is **not** allowed: compute `balanceAfter` in the same update with an aggregation-pipeline update, `balanceAfter = balance − amount`). No match → `WalletEmptyException` (carries `customerId`).
  - `String credit(String customerId, BigDecimal amount, WalletEntry draft)` — same shape, no balance guard, upsert on `customerId` (a first top-up creates the wallet).
  - `boolean creditOnce(String orderId, …)` / `boolean debitOnce(String orderId, …)` — as above with the extra guard `orderIds ≠ orderId` and `$addToSet orderIds`; false when the order was already applied.
  - `Wallet require(customerId)` / `Optional<Wallet> find(customerId)`.
- `WalletMover.move(customerId)`: for each pending entry, upsert it into `walletEntries` by id, then `$pull` it by id. Called after every operation (failures are logged, never thrown) and from the ticker.
- Rounding: every amount is set to scale 4, `HALF_UP`, before it reaches an update.

- [ ] **Step 1: Write the failing tests.** Credit 1000 on a customer with no wallet creates it at 1000.0000 with one ledger row `balanceAfter 1000.0000`; three debits of 0.25 leave 999.2500 and three rows with `balanceAfter` 999.7500, 999.5000, 999.2500; a debit above the balance throws `WalletEmptyException` and changes nothing; `creditOnce` twice with one order id credits once, **also after the mover has run** (Review Focus 3); with the mover made to fail (spy), the balance is right, the entry is in `pending`, and a later `move` writes exactly one row; `move` twice writes one row (Review Focus 2). Review Focus 1: 40 threads each debit 0.25 from a wallet of 5.0000 → exactly 20 succeed, 20 throw, balance 0.0000, 20 rows.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): wallet operations with an in-document outbox`.

### Task 3: Views, adjustments, threshold and usage totals

**Files:** Create `service/billing/wallet/{WalletViews,WalletQueryService}.java`, `app/billing/WalletController.java`; Modify `app/billing/MyBillingController.java`; Test `app/billing/WalletApiIT.java`.

**Interfaces (Produces):** the wallet rows of the contract except refunds.
- `WalletQueryService.view(customerId) : WalletView` (a customer without a wallet reads as balance 0); `entries(customerId, page, Caller)` = `walletEntries` plus `pending`, newest first, deduplicated by id, 50 per page, names resolved (customer callers get no `byName` and no adjustment `note`); `usage(customerId, days, zone)` = an aggregation over `walletEntries` of types `USAGE` and `REVERSAL` grouped by day in the billing zone and `source` (pending entries are moved first).
- `WalletService.adjust(customerId, amount, note, by)`: `ADJUSTMENT` entry; a negative one that would go below zero → 409 `NOT_ENOUGH_WALLET`; note 1–300; `BillingAudit` row. `setThreshold(customerId, lowBalanceAt, by)`.
- `low` = `lowBalanceAt` is set and `balance < lowBalanceAt`. `messageRate` from `Entitlement.rate`.

- [ ] **Step 1: Write the failing tests.** Worked example W; an adjustment of 0, with 5 decimals, or without a note → 400; −1000.0001 against 1000 → 409 and nothing changes; the entries page lists newest first with `byName` for staff and without it for the customer, and includes an entry still in `pending`; `usage?days=7` after steps U and V shows one row for today, source `CONSOLE`, `messages` 3, `amount` 0.75; threshold 100 with balance 50 → `low` true; a customer admin reads its own wallet and gets 403 on the staff paths; an account manager may adjust; a `WORKSPACE_MANAGER` gets 403.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): wallet views, adjustments and usage totals`.

### Task 4: Top-up quotes and orders

**Files:** Modify `service/billing/pricing/{QuoteInput,OrderPricing}.java`, `service/billing/order/{OrderViews,OrderService,OrderOptions,OrderSteps}.java`; Test `service/billing/pricing/TopUpPricingTest.java`, `app/billing/TopUpOrdersIT.java`.

**Interfaces (Produces):**
- `QuoteRequest` gains `BigDecimal topUpAmount`; `QuoteInput` gains it too. `OrderPricing.quote` for `TOPUP`: one line `OrderLine.TOPUP` "Wallet top-up", amount = the top-up; GST as for any order; **`accountCreditUsed` 0** (payable = total); no extra discount (`extraDiscountPercent` → `ValidationException`); `target` with only `segmentAfter` = the term in force (for the views). Refused when the customer has no subscription ("The customer has no plan"), or the amount is below `TOP_UP_MIN` 100.00, above `TOP_UP_MAX` 10,00,000.00, or has more than 2 decimals (`topUpAmount`).
- `OrderService`: customers may raise `TOPUP`; a `TOPUP` never collides with `ORDER_PENDING` and several may wait at once.
- `OrderSteps.supersede` and `OrderActivator.requireFresh`: a `TOPUP` is never superseded and never stale (it does not depend on the plan); the tax-settings check of phase 2 still applies.
- `OptionsView` gains `topUp {allowed, reason, min, max}` and `messageRate`.

- [ ] **Step 1: Write the failing tests.** `TopUpPricingTest`: worked example T's amounts; Bolt → IGST 180.00; a customer holding 500.00 account credit still pays 1180.00; 99.99, 1000000.01, 10.005 and an extra discount are refused on their fields; no subscription → refused. `TopUpOrdersIT`: staff and the customer admin each raise a top-up; two top-ups wait side by side; a plan upgrade paid meanwhile leaves the top-up `REQUESTED`, and it can still be paid; options carry `topUp.allowed` true with min and max and `messageRate` 0.25.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS** with `OrdersApiIT`, `MyOrdersApiIT`, `ActivationIT`.
- [ ] **Step 5: Commit** `feat(billing): wallet top-up orders`.

### Task 5: Activating a top-up

**Files:** Modify `service/billing/order/{OrderActivator,OrderSteps}.java`, `service/billing/document/InvoiceService.java`; Test `app/billing/TopUpActivationIT.java`.

**Interfaces (Produces):**
- `OrderActivator.finish` runs the steps in this order: `applied`, `creditNoted`, **`wallet`**, `invoiced`. `OrderSteps.apply` for `TOPUP` and `WALLET_REFUND` changes nothing on the subscription (no revision) and writes the history row; `OrderSteps.wallet(order)`: `TOPUP` → `walletService.creditOnce(orderId, taxable, TOPUP entry ref=order number)`; `WALLET_REFUND` → Task 6; any other kind → nothing.
- `InvoiceService.issue` stamps `orderKind`.

- [ ] **Step 1: Write the failing tests.** Paying worked example T: wallet 1000.0000, one `TOPUP` entry with `ref` `ORD-000002`, invoice line "Wallet top-up" 1000.00 with `orderKind` `TOPUP`, subscription revision unchanged, history row names the order. Review Focus 3: with `OrderSteps.invoice` made to throw once, the order stays `ACTIVATING` and the wallet is already 1000.0000; `finishPending` completes it and the wallet is still 1000.0000 with one entry; the same after the mover has emptied `pending`. A top-up for a customer whose plan was cancelled meanwhile still credits the wallet.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): a paid top-up credits the wallet`.

### Task 6: Wallet refunds

**Files:** Create `service/billing/pricing/WalletRefundPricing.java`; Modify `service/billing/order/{StaffOrderService,OrderSteps}.java`, `service/billing/document/CreditNoteService.java`, `app/billing/StaffOrderController.java`, `service/billing/wallet/WalletQueryService.java`; Test `service/billing/pricing/WalletRefundPricingTest.java`, `app/billing/WalletRefundsIT.java`.

**Interfaces (Produces):**
- `WalletRefundPricing.quote(amount, wallet, topUpInvoices /* newest first */, settings) : Quote` (kind `WALLET_REFUND`, no lines): the amount is taken from the top-up invoices newest first, each up to `taxable − credited`, through `UnusedCredit.forAmounts`; `creditFromNotes` = the notes' totals (amount + the GST paid on it). Refused with 409 `NOT_ENOUGH_WALLET` when the amount is above the balance or above what the top-up invoices have left; `refundable` in `WalletView` = min of the two, at 2 decimals, rounded down.
- `StaffOrderService.walletRefund(customerId, {amount, reason}, by)` and `walletRefundQuote`: activated at once through `OrderActivator.activateNow`. Steps: credit notes (existing step, reason "Wallet refunded"), then `wallet`: `debitOnce(orderId, amount, REFUND entry)`. If the wallet no longer covers it at that moment the step throws and the order is cancelled by `activateNow`'s rule only when nothing was applied — so the **wallet debit runs before the credit notes** for this kind: order of steps for `WALLET_REFUND` is `applied`, `wallet`, `creditNoted`, `invoiced` (no invoice). State this in `OrderActivator`.
- The credit lands in account credit (phase 2), from where staff record the refund paid.

- [ ] **Step 1: Write the failing tests.** Worked examples X and Y; the quote equals the order; a refund spanning two top-up invoices credits the newest first; after usage has taken the wallet to 300, a refund of 400 → 409; a re-driven refund debits once and issues its notes once (inject a failure after the debit); an account manager → 403; the credit note's reason is "Wallet refunded" and its tax is the invoice's (IGST for Bolt).
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS** with `UpgradeIT`, `CancellationsIT`.
- [ ] **Step 5: Commit** `feat(billing): wallet refunds`.

### Task 7: Charging a send

**Files:** Create `service/billing/wallet/UsageCharger.java`; Modify `service/inbox/InboxSendService.java`, `db/…/WaWebMessage.java` (`usageEntryId`), `app/api/ApiExceptionHandler.java` (402 `WALLET_EMPTY`), `README.md` error table; Test `service/billing/wallet/UsageChargerTest.java`, `app/billing/SendChargingIT.java`.

**Interfaces (Produces):**
- `UsageCharger.charge(String workspaceId, SendActor actor) : Charge | null` — null when the customer has no rate; otherwise debits the rate (`USAGE`, meter `WHATSAPP_MESSAGE`, source from the actor, `workspaceId`, `ref` = the campaign id or null) and returns `Charge(customerId, entryId, amount)`. Throws `WalletEmptyException`.
- `UsageCharger.giveBack(Charge, String why)` — a `REVERSAL` entry of `+amount` with `ref` = the usage entry's id.
- `InboxSendService.send` and `sendCloud`: after `workGate.requireSend` and after the message is validated and built (`outgoing`), **charge, then dispatch**. If building the device call throws → give back and rethrow. When the device future fails: give back for an `RpcException` whose code is not `DEVICE_DISCONNECTED`; keep the charge for `DEVICE_DISCONNECTED`, a timeout, or any other failure (the message may have gone). When it succeeds: store `usageEntryId` on the message row; a failure while recording keeps the charge.
- `WalletEmptyException` → 402 `{code: "WALLET_EMPTY", message: "The wallet balance is too low to send"}`.

- [ ] **Step 1: Write the failing tests.** `UsageChargerTest` (mocks): no rate → null and the wallet is not touched; the source follows the actor. `SendChargingIT` (the inbox test device): worked examples U, V and W; a send that times out stays charged (Review Focus 4); `DEVICE_DISCONNECTED` stays charged; a validation failure charges nothing; a customer on a plan with no rate, and a customer with no plan, send freely with no wallet; the stored message carries `usageEntryId`; an incoming message costs nothing; with 0.20 left the send answers 402 `WALLET_EMPTY` and the device saw no call.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS** with the inbox send ITs.
- [ ] **Step 5: Commit** `feat(billing): messages are charged to the wallet`.

### Task 8: An empty wallet in campaigns, automations and chatbots

**Files:** Modify `service/campaign/CampaignRunner.java`, `service/automation/SendTemplateHandler.java` (and any other automation send handler that catches `SubscriptionInactiveException`), `service/chatbot/run/ChatbotSender.java`; Test `app/campaign/CampaignWalletIT.java`, `app/automation/AutomationWalletIT.java`, `app/chatbot/ChatbotWalletIT.java`.

**Interfaces (Produces):**
- Campaign: `WalletEmptyException` from the send → the claim is **released** (as for `NoDeviceException`) and the campaign is paused by the system with `pauseReason` `"WALLET_EMPTY"` (one guarded update `{status: RUNNING}`); it is resumed by a person, as any paused campaign.
- Automation action: `ActionResult.failed("the wallet balance is too low")`. Chatbot node: `NodeFailed("the wallet balance is too low")`, taking its failure path.

- [ ] **Step 1: Write the failing tests.** Review Focus 5: a campaign of 5 recipients against a wallet that covers 2 sends 2, pauses with `WALLET_EMPTY`, and leaves 3 recipients `PENDING` with none `FAILED`; after a top-up and a resume it sends the other 3. An automation's send action fails with the reason and the rule's run shows it. A chatbot's message node takes its failure path.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS** with the campaign, automation and chatbot ITs that touch sending.
- [ ] **Step 5: Commit** `feat(billing): an empty wallet pauses campaigns and fails automated sends`.

### Task 9: Low wallet in the session, and the ticker

**Files:** Create `security/entitlement/WalletReader.java`; Modify `security/user/UserService.java` (`SubscriptionSummary.lowWallet`), `service/billing/BillingTicker.java`; Test `app/billing/LowWalletIT.java`, `app/billing/BillingTickerIT.java` (extend).

**Interfaces (Produces):**
- `WalletReader.low(customerId) : boolean` (reads `wallets` directly; false without a wallet or a threshold). `SubscriptionSummary` gains `boolean lowWallet`; it is true only when the customer is charged (`Entitlement.rate` not null) and the wallet is low.
- `BillingTicker.tick()` also moves pending entries older than 60 s for every wallet that has any.

- [ ] **Step 1: Write the failing tests.** `/api/users/me` shows `lowWallet` true below the threshold and false above it, false for a customer whose plan has no rate, false for platform staff; a wallet with a pending entry 61 s old is moved by a tick, one 30 s old is not.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): low-wallet flag and wallet ledger mover in the ticker`.

### Task 10: Documentation and whole-branch verification

- [ ] **Step 1: Documentation.** `README.md` "Subscription plans": the wallet, top-ups, what is charged and what is not, what happens when it is empty, wallet refunds, the endpoints and the two error codes. `docs/superpowers/known-limits.md`: a send whose outcome is unknown stays charged; the rate is the one in the term's snapshot (a plan's new rate applies from the next term); adjustments are not refundable; a top-up's GST is invoiced when it is paid; the wallet survives a cancelled plan; usage totals are by day in the billing zone. `project-rules.md` section 3: where the wallet code is; "do not write to `wallets` except through `WalletService`".
- [ ] **Step 2: Commit** `docs: subscription plans phase 3`.
- [ ] **Step 3: Run the full suite once, alone.** `./mvnw -q -o verify > /tmp/claude-501/subscriptions-wallet-verify.log 2>&1; echo "exit=$?"`; then `pgrep -f '/.embedmongo/' | wc -l` must print 0. Expected: exit 0. A failure that passes alone is reported by name as flaky, then the full run is repeated.
- [ ] **Step 4: Stop.** Do not merge, push or deploy. Report tasks done, rulings, deferred minors.
