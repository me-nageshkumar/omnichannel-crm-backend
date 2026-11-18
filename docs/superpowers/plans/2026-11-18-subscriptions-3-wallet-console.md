# Subscriptions phase 3 (usage wallet), console: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff see and manage a customer's wallet (balance, ledger, usage, top-up, adjustment, refund, low-balance threshold); a Customer Admin sees its wallet and usage and tops it up; everyone is told when the wallet is low or empty.

**Architecture:** As in phase 2: every amount on screen is a field of a server response. The top-up dialog reuses `useQuote` and `QuotePanel`; the wallet's ledger and usage are plain tables over the server's pages. One `WalletCard` serves both sides, told by props what its viewer may do.

**Tech Stack:** React 18, TypeScript (target `es5`), PatternFly 6.5, vitest with `mockFetch` and `renderApp`.

**Spec:** `docs/superpowers/specs/2026-11-17-subscription-plans-design.md` sections 6.5, 10 and the wallet lines of 12, in the backend repo.

**Contract:** section "API contract (phase 3)" of `docs/superpowers/plans/2026-11-18-subscriptions-3-wallet-backend.md`. Build this plan after the backend plan's Task 9. Before Task 1, compare the contract with the records the backend actually built (`service/billing/wallet/WalletViews.java`, `order/OrderViews.java`, the controllers in `app/billing`) and follow the code where they differ, recording the difference in the ledger.

## Global Constraints

- Work only in `omnichannel-crm-frontend/.worktrees/subscriptions-wallet` (branch `subscriptions-wallet` from `main`; `npm ci --offline`). No merge, push or deploy.
- Test first; `npx vitest run <path> --maxWorkers=4`.
- **No money arithmetic in the browser.** Wallet amounts have up to 4 decimals: show them through a new `inr4(amount)` in `src/app/Billing/common/money.ts` ("₹999.2500" for a ledger amount, and the balance as the server sent it). Order and invoice amounts keep `inr`.
- A blank number field is never sent as 0. A number a person types is sent as typed.
- Dates of ledger entries and usage days are shown in the billing zone (`billingDate`, `billingDateTime`).
- Follow the existing patterns: `PageShell`, `useLoad` (reload with `reload()`, not a key change), `useAction(FIELDS)`, `ConfirmModal`, inline `Alert`s, PF `Table variant="compact"`.
- Fixtures match the server's shapes field for field.
- Prettier on touched files only. Before finishing: `npx tsc --noEmit && npm run lint && npm run build`.
- Wording: "wallet", "top-up" (noun), "top up" (verb), "message rate", "usage". Never "template".

## Review Focus

1. A top-up amount typed and changed quickly: the price shown and the request sent are for the amount on screen (it is `useQuote`; pin it for this dialog).
2. `WALLET_EMPTY` from a send in the inbox: the composer keeps the typed text and says "The wallet balance is too low to send. Top up the wallet." with a link to Plan & billing for those who can see it.
3. A wallet that does not exist yet (a customer never topped up): balance ₹0.00, an empty ledger, no errors.
4. A customer whose plan has no message rate: the wallet card says messages are not charged and offers no top-up; nothing nags about a low wallet.
5. A campaign paused for an empty wallet says why, in words, where its pause reason is shown.

---

### Task 1: API module, formatting and fixtures

**Files:** Modify `src/app/api/billing.ts`, `src/app/Billing/common/money.ts`, `src/test/billing.ts`, `src/app/api/messages.ts`; Test `src/app/api/billing.test.ts` (extend), `src/app/Billing/common/money.test.ts`.

**Interfaces (Produces):**
```ts
export type WalletEntryType = 'TOPUP' | 'USAGE' | 'REVERSAL' | 'ADJUSTMENT' | 'REFUND';
export type UsageSource = 'CONSOLE' | 'CAMPAIGN' | 'AUTOMATION' | 'CHATBOT';
export interface Wallet { balance: number; lowBalanceAt: number | null; low: boolean; messageRate: number | null; refundable: number }
export interface WalletEntry { id: string; type: WalletEntryType; amount: number; balanceAfter: number; meter: string | null;
  source: UsageSource | null; workspaceId: string | null; workspaceName: string | null; ref: string | null;
  note: string | null; byName: string | null; at: string }
export interface WalletEntryPage { items: WalletEntry[]; total: number; page: number }
export interface UsageDay { day: string; source: UsageSource; messages: number; amount: number }
```
`OrderKind` gains `'TOPUP' | 'WALLET_REFUND'`; `OrderLine.type` gains `'TOPUP'`; `QuoteRequest` gains `topUpAmount?: number`; `BillingOptions` gains `topUp: {allowed: boolean; reason: string | null; min: number; max: number}` and `messageRate: number | null`; `SubscriptionSummary` gains `lowWallet: boolean`.
Functions: staff `getWallet(customerId)`, `listWalletEntries(customerId, page)`, `getWalletUsage(customerId, days)`, `adjustWallet(customerId, {amount, note})`, `setWalletThreshold(customerId, lowBalanceAt | null)`, `quoteWalletRefund(customerId, {amount})`, `refundWallet(customerId, {amount, reason})`; customer `getMyWallet`, `listMyWalletEntries(page)`, `getMyWalletUsage(days)`.
`inr4(amount)`: rupees with at least 2 and at most 4 decimals ("₹1,000.00", "₹999.25", "₹0.2575").
`messages.ts`: `wallet_empty` "The wallet balance is too low to send.", `not_enough_wallet` "The wallet does not hold that much that can be taken."; `orderText.ts` labels: `TOPUP` "Wallet top-up", `WALLET_REFUND` "Wallet refund".
Fixtures: `wallet(over)`, `walletEntry(over)`, `USAGE_DAYS`, `topUpQuote()` (worked example T: 1000.00 / 90.00 / 90.00 / 1180.00), and `OPTIONS`, `summaryIn` extended.

- [ ] **Step 1: Write the failing tests:** each new function's method, path and body; `inr4` for 1000, 999.25, 0.2575 and null.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS;** `npx tsc --noEmit` clean.
- [ ] **Step 5: Commit** `feat(billing): the wallet in the API module`.

### Task 2: The wallet card, ledger and usage

**Files:** Create `src/app/Billing/common/{WalletCard,WalletEntriesTable,UsageTable}.tsx`; Modify `src/app/Billing/platform/CustomerBillingTabs.tsx` (tabs "Wallet" and "Usage"), `src/app/Customers/CustomerDetailPage.tsx`, `src/app/Billing/my/MyBillingPage.tsx`, `src/app/Billing/my/MyDocuments.tsx`; Test `src/app/Billing/common/wallet.test.tsx`.

**Interfaces (Produces):**
- `WalletCard {wallet, actions?: React.ReactNode}`: region "Wallet": the balance, "Message rate ₹0.25 per message" or "Messages are not charged on this plan", a warning line when `low`, the threshold when set; `actions` in the header.
- `WalletEntriesTable {load(page), zone, reloadKey}`: When, What ("Top-up", "Message", "Returned", "Adjustment", "Refund"), Source, Brand, Amount (signed, `inr4`), Balance after, Note / reference, By (column only when any row has a name); pagination when `total` > 50.
- `UsageTable {load(days), zone}`: a 7 | 30 | 90 days toggle; rows Day, Source, Messages, Amount.
- Staff customer page: the card under the subscription card; the tabs gain Wallet and Usage. Customer page: the card after "Your plan"; Wallet and Usage tabs in "Invoices and credit" (renamed "Invoices, credit and wallet").

- [ ] **Step 1: Write the failing tests:** the card for a charged plan, an uncharged plan (Review Focus 4), a low wallet, and a wallet that does not exist yet (Review Focus 3); the ledger shows the worked example's rows with signs and 4 decimals and asks for page 1 on Next; the usage table asks again with `days=7` and shows the fixture rows; the customer's ledger has no By column.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): wallet balance, ledger and usage`.

### Task 3: Topping up

**Files:** Create `src/app/Billing/common/TopUpDialog.tsx`; Modify `SubscriptionCard.tsx` or the wallet card's actions on both pages, `MyRequests.tsx` (a top-up request reads "Wallet top-up ₹1,000.00"); Test `src/app/Billing/common/topUp.test.tsx`.

**Interfaces (Produces):** `TopUpDialog {zone, min, max, fetchQuote(request), raise(request), sendLabel, onRaised(order), onCancel}`: an amount field with "Between ₹100.00 and ₹10,00,000.00" from the options (min and max are the server's), `QuotePanel` with the top-up line, tax and amount payable, and for the customer the note that it takes effect when the payment is recorded. Staff: a **Top up** button on the wallet card with `subscription:manage`, raising through `raiseOrder`. Customer: **Top up** with `billing:request` when `options.topUp.allowed`, else the server's reason beside the card.

- [ ] **Step 1: Write the failing tests:** staff top up 1000 → the quote request and the order request are `{kind: 'TOPUP', topUpAmount: 1000}` and the panel shows ₹1,180.00 payable; Review Focus 1: the amount changed while a quote is in flight — the stale answer is not shown and Send stays disabled until the right one arrives; a blank or non-numeric amount asks for nothing; a server refusal on `topUpAmount` shows under the field; the customer's request appears in Requests with how to pay; without `billing:request` there is no button.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): wallet top-ups`.

### Task 4: Staff wallet actions

**Files:** Create `src/app/Billing/platform/{WalletAdjustDialog,WalletRefundDialog,WalletThresholdDialog}.tsx`; Modify the staff wallet card's actions; Test `src/app/Billing/platform/walletActions.test.tsx`.

**Interfaces (Produces):** on the staff wallet card, a "More" menu: with `subscription:manage` **Adjust wallet** (signed amount up to 4 decimals, note required) and **Low-balance warning** (an amount, or empty for none); with `refund:manage` **Refund wallet** (an amount up to the server's `refundable`, shown; the server's quote in `QuotePanel` — the credit note total that goes to account credit; a reason; a line saying the money is then paid out with Record refund).

- [ ] **Step 1: Write the failing tests:** each dialog posts its body and the card and ledger reload; a blank amount or note blocks; the refund quote is asked again when the amount changes and Confirm is disabled until it matches; 409 `NOT_ENOUGH_WALLET` shows its sentence; an account manager sees Adjust and the warning but not Refund wallet.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): wallet adjustments, threshold and refunds`.

### Task 5: Low and empty wallet, where people work

**Files:** Modify `src/app/Billing/common/SubscriptionBanner.tsx`, the inbox composer's send-error handling, the campaign page's pause-reason text; Test `src/app/Billing/common/banner.test.tsx` (extend), the inbox composer test and the campaign detail test (extend).

**Interfaces (Produces):**
- Banner: when `subscription.lowWallet`, a warning "Your wallet balance is low. Messages stop sending when it runs out." with a "Top up" link to `/billing` for those with `billing:view`; it does not replace the grace or expired banner, it stands under it.
- Inbox: a send refused with `WALLET_EMPTY` keeps the typed text and shows the sentence of Review Focus 2.
- Campaign: `pauseReason` `WALLET_EMPTY` reads "Paused: the wallet balance is too low. Top up the wallet, then resume."

- [ ] **Step 1: Write the failing tests:** the banner shows for `lowWallet` and not otherwise, with the link only for those who may open it; Review Focus 2; Review Focus 5.
- [ ] **Step 2: Run; expected FAIL.** - [ ] **Step 3: Implement.** - [ ] **Step 4: Run; expected PASS.**
- [ ] **Step 5: Commit** `feat(billing): low and empty wallet where people work`.

### Task 6: Whole-branch checks

- [ ] **Step 1:** `npx vitest run --maxWorkers=4 > /tmp/claude-501/subscriptions-wallet-console.log 2>&1; tail -30 /tmp/claude-501/subscriptions-wallet-console.log`. Expected: all pass.
- [ ] **Step 2:** `npx tsc --noEmit && npm run lint && npm run build`. Expected: no errors.
- [ ] **Step 3:** `grep -rnE "[-+*/] *(price|amount|total|taxable|payable|credit|balance|rate)|toFixed|Math\.round" src/app/Billing --include='*.ts' --include='*.tsx' | grep -v test`. Expected: nothing that computes money (explain any line it prints).
- [ ] **Step 4: Stop.** Do not merge, push or deploy. Report tasks done, rulings and deferred minors. The walk on 8089 (set Walk plan's message rate, top up Demo Retail, send a message to a recipient the user names, see the charge, adjust, refund the wallet) is done with the user after the deploy; a real message is sent only to a recipient the user names.
