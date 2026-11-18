# Link addresses: console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task
> (the user's choice: inline, on Opus). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A super admin sets, checks and removes a brand's link address on the customer's page, and edits the
platform's address on its own page.

**Architecture:** One API module, one card with a dialog on the customer page, one small platform page. The server
decides everything (validity, status, which address links use); the console shows it and words the customer's
set-up step from what is typed.

**Tech stack:** React 18, TypeScript (es5 target: no `Array.prototype.at`), PatternFly 6.5, vitest (`mockFetch`
fails on unmocked requests, `renderApp`, `requestTo`).

**Spec:** `docs/superpowers/specs/2026-11-18-link-addresses-design.md` §5.8, §5.9 (backend repo, branch
`link-addresses`). Server contract: the last section of
`docs/superpowers/plans/2026-11-18-link-addresses-backend.md`.

## Global constraints

- Work only in `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend/.worktrees/link-addresses`
  (branch `link-addresses`); run `npm ci --prefer-offline` there first. No merge, push or deploy.
- Tests: `npx vitest run <path> --maxWorkers=4`. Do not run the whole vitest suite while a Maven build runs.
  Prettier only on touched files. No money arithmetic, no "template" naming. Copy fixed below is used verbatim.
- Screens say "link address"; code says `pagesAddress`.
- Model split and commit trailer: as in the backend plan's Global constraints.

Paths are under `src/app/` unless they start with `src/`.

## Review focus

1. A person without `pages-address:manage` (an account manager on the customer page): no card, no request, no
   menu entry, and the platform page's route refuses them. (Tasks 2, 3)
2. The instruction text while typing: nothing for an empty or unparseable address, the CNAME sentence for a plain
   subdomain, the forwarding sentence for anything with a folder, never a broken half-sentence. (Task 2)
3. A check that takes 20 seconds: the row shows it is checking, its buttons are disabled, and a second click
   cannot start another. (Task 2)
4. A 409 on a stale version or a taken domain keeps the dialog open with what was typed. (Task 2)
5. A brand whose address was removed still shows its former hosts and their end dates. (Task 2)

---

### Task 1: The API module and fixtures

**Files:**
- Create: `api/pagesAddresses.ts`, `api/pagesAddresses.test.ts`, `src/test/pagesAddresses.ts`
- Modify: `src/test/session.ts` (`PLATFORM_CODES` gains `'pages-address:manage'`; `ACCOUNT_MANAGER` does not)

**Interfaces — produces:**
```ts
export type PagesAddressStatus = 'UNCHECKED' | 'WORKING' | 'FAILING';
export interface PagesAddressView { baseUrl: string; status: PagesAddressStatus; checkedAt: string | null; lastError: string | null; inUse: boolean }
export interface FormerHost { host: string; until: string }
export interface BrandPagesAddress { workspaceId: string; workspaceName: string; address: PagesAddressView | null; formerHosts: FormerHost[]; version: number | null }
export interface CustomerPagesAddresses { platformBaseUrl: string; brands: BrandPagesAddress[] }
export interface PlatformPagesAddress { baseUrl: string; source: 'SAVED' | 'STARTUP'; startupBaseUrl: string; version: number | null; warning: string | null }
getCustomerPagesAddresses(token, customerId): Promise<CustomerPagesAddresses>
setBrandPagesAddress(token, customerId, workspaceId, baseUrl: string | null, version: number | null): Promise<BrandPagesAddress>
checkBrandPagesAddress(token, customerId, workspaceId): Promise<BrandPagesAddress>
getPlatformPagesAddress(token): Promise<PlatformPagesAddress>
savePlatformPagesAddress(token, baseUrl: string | null, version: number | null): Promise<PlatformPagesAddress>
```
Fixtures: `PAGES_ADDRESSES` (platform base `https://go.example.in`; brand `w1` "Brand A" with a `WORKING`
`https://links.branda.in`; brand `w2` "Brand B" with none and one former host `old.brandb.in` until
`2027-02-01T00:00:00Z`), `brandRow(over)`, `PLATFORM_ADDRESS`.

- [ ] **Step 1: failing test** `api/pagesAddresses.test.ts`: each function calls its method and path with the
  bearer and, for the two writes, the body `{baseUrl, version}` (null `baseUrl` sent as `null`, not left out).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement** with `apiFetch`, as `api/chatbotSettings` does.
- [ ] **Step 4: run, expect PASS**; `npx tsc --noEmit`.
- [ ] **Step 5: commit** `feat(console): link address API`.

### Task 2: The card on the customer page

**Files:**
- Create: `Customers/LinkAddressesCard.tsx`, `Customers/LinkAddressDialog.tsx`, `Customers/linkAddressText.ts`,
  `Customers/linkAddresses.test.tsx`, `Customers/linkAddressText.test.ts`
- Modify: `Customers/CustomerDetailPage.tsx` (the card after "Brands", only with `pages-address:manage`)

**Interfaces — produces:**
- `linkAddressText.ts`:
  - `customerStep(typed: string, platformBaseUrl: string): string | null` — null unless `typed` parses with `new
    URL` as `https://` with a host containing a dot. No folder →
    `Add a CNAME record for <host> pointing to <platform host>.`; with a folder (`/wa`) →
    `Forward https://<host>/wa/t/ and https://<host>/wa/f/ to <platformBaseUrl>, removing /wa.`; a bare domain
    without a folder gets both sentences joined by " If the domain already serves a website, give the address a
    folder (for example https://<host>/wa) and forward that instead." A bare domain is a host with exactly two
    labels, or three when the last two are a two-letter pair such as `co.in`.
  - `STATUS_TEXT: Record<PagesAddressStatus, string>` = `Not checked`, `Working`, `Failing`.
- `LinkAddressesCard({ customerId })`: title "Link addresses"; intro "Message and form links of a brand open under
  its own address once the check passes; until then they use the platform address."; a table, one row per brand:
  Brand · Address (or "Uses the platform address") · Status (label: green Working, grey Not checked, red Failing,
  with `lastError` beside a failing one and "checked <relative time>") · actions **Set…** (or **Change…**),
  **Check**, **Remove**. Under a row with former hosts: "Earlier address <host> must keep working until <date>,
  for links already sent." While a check or save runs on a row, its buttons are disabled and the status reads
  "Checking…". Remove asks first: "Remove the link address of <brand>? Its links will use the platform address.
  <host> must keep working for 90 days, for links already sent."
- `LinkAddressDialog`: title "Link address of <brand>"; one field "Address" with placeholder
  `https://links.example.com`, focused on open; under it the `customerStep` text as helper text, and "A new
  subdomain can need a minute for its certificate. If the first check fails, check again."; Save and Cancel.
  A 400 shows `fields.baseUrl` on the field; 409 `PAGES_HOST_TAKEN` shows "Another customer uses this domain." on
  the field; a stale-version 409 shows "Someone else changed this address. Close and open it again."; in each
  case the dialog stays open with the typed text. On success the dialog closes and the row shows the returned
  status. When the brand already has an address, a line above the buttons: "<old host> must keep working for
  90 days after the change, for links already sent."

- [ ] **Step 1: failing tests.** `linkAddressText.test.ts` (Review focus 2): empty, `links`, `http://x.in`,
  `https://x` → null; `https://user-task.bimalinstitute.com` → the CNAME sentence with the platform host;
  `https://bimalinstitute.com/wa` → the forwarding sentence with `removing /wa`; `https://bimalinstitute.com` and
  `https://bimal.co.in` → both sentences; `https://a.b.bimal.co.in` → CNAME only.
  `linkAddresses.test.tsx` (render the customer page as `SUPER_ADMIN` with the page's existing routes mocked plus
  `GET /api/customers/c1/pages-addresses`): `shows each brand with its address and status`;
  `a brand without an address says it uses the platform address`; `former hosts show with their end date`
  (Review focus 5); `Set saves and shows the checked status` (PUT body `{baseUrl, version}`);
  `the dialog words the customer's step as the address is typed`; `a refused address stays in the dialog with the
  server's message` and `a taken domain says so` and `a stale version says so` (Review focus 4);
  `Check disables the row while it runs and shows the result` (hold the POST open; Review focus 3);
  `Remove asks first and sends baseUrl null`; `an account manager sees no card and no request is made`
  (Review focus 1: `mockFetch` without the route passes only if nothing is requested).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement** (`useLoad`, `useAction` as the billing cards on this page do).
- [ ] **Step 4: run, expect PASS**; `npx vitest run src/app/Customers --maxWorkers=4`.
- [ ] **Step 5: commit** `feat(customers): link addresses of a customer's brands`.

### Task 3: The platform page

**Files:**
- Create: `Platform/LinkAddressPage.tsx` (put it beside the other platform pages if they live elsewhere),
  `Platform/linkAddressPage.test.tsx`
- Modify: `routes.tsx` (route `/platform/link-address`, `requires: (a) => a.has('pages-address:manage')`; nav link
  "Link address" after "Chatbot defaults")

**Produces:** heading "Link address"; text "Message and form links use this address unless the brand has a working
address of its own."; field "Address"; under it, when `source === 'STARTUP'`: "From the startup settings. Saving
here replaces it until you choose Use the startup setting."; when `'SAVED'`: "Saved here. The startup setting is
<startupBaseUrl>." with a link button **Use the startup setting** (sends `baseUrl: null`); **Save**; after a save,
"Address saved" and, when `warning` is set, a warning alert "The address was saved, but the check failed:
<warning>". A 400 shows `fields.baseUrl` on the field.

- [ ] **Step 1: failing tests:** `shows the address and where it comes from` (both sources);
  `Save sends the address and its version`; `a failed check shows as a warning, not an error`;
  `Use the startup setting sends null`; `a refused address shows the field message`;
  `the page and its menu entry need pages-address:manage` (as `ACCOUNT_MANAGER`: no nav link, and the route shows
  the app's not-allowed page; Review focus 1).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(platform): the platform's link address page`.

### Task 4: Whole-console check

- [ ] **Step 1:** `npx tsc --noEmit && npm run lint` → no errors.
- [ ] **Step 2:** `npx vitest run --maxWorkers=4` (no Maven build running) → all pass.
- [ ] **Step 3:** `npm run -s build` → succeeds.
- [ ] **Step 4: commit** anything Steps 1–3 changed: `chore(console): checks after link addresses`.
