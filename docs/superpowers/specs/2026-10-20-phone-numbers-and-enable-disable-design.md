# Phone numbers, extension connect, and enable/disable — design

Date: 2026-10-20. Repos: `omnichannel-crm-backend`, `omnichannel-crm-frontend`, `whatsapp-web-extension`.
The console calls workspaces "brands"; this document uses both words for the same thing.

## Why

Contacts (the next project) come from WhatsApp chats, and a chat is only useful if we know which
brand it belongs to. Today the Chrome extension logs in as a shared `wabridge` user with a
username and password, and nothing ties a WhatsApp number to a brand. This project makes a
brand's WhatsApp number a first-class thing the platform knows about, gives the extension a
credential that belongs to that number instead of a person, and gives every level a consistent
way to switch things off: numbers, brands, customers and people.

The customer opens WhatsApp Web in one tab of their own browser. The browser is only a pipe for
the brand's number: agents will send messages and campaigns from our application (a later
project). A reply typed directly in WhatsApp Web will later be stored as "sent from WhatsApp
Web" with no agent — also a later project. So who is sitting at the browser does not matter here.

## Goals

1. Brand managers onboard their brand's WhatsApp numbers in the console.
2. The extension connects for one onboarded number using a one-time connect code, then a device
   key; no human username/password is stored in the extension.
3. The server only keeps a WhatsApp Web connection that is logged in to the onboarded number, and
   only while the number, its brand and its customer are all enabled.
4. "Enabled / Disabled" everywhere (replacing "block" and "deactivate/reactivate"), for numbers,
   brands, customers and people, with one rule: **you cannot enable something that was disabled
   at a higher level than yours.**
5. Super Admin and Account Managers see every number on the platform.

## Non-goals (later projects)

- Contacts, conversations, messages storage, campaigns, agent attribution, "sent from WhatsApp
  Web" marking.
- Contacts whose WhatsApp id is a privacy id (`…@lid`) — matters for contacts, not here.
- Sending messages from the console.

## Anti-goals

- A disabled number, brand or customer that can still push events from a browser.
- A brand manager able to undo what platform staff switched off.
- One brand ever receiving another brand's WhatsApp traffic (wrong-number connections must be
  refused, never accepted with a warning).

## Levels used by the enable/disable rule

Each disable records **who** and **at which level** they acted:

| Level | Who acts at it |
|---|---|
| PLATFORM | holders of a PLATFORM-level role (Super Admin, Account Manager) |
| COMPANY | holders of a COMPANY-level role (Customer Admin) |
| BRAND | holders of a BRAND-level role in that brand (Workspace Manager) |

Ordering PLATFORM > COMPANY > BRAND. Disabling is allowed to anyone with the authority for that
thing. Enabling is allowed only if the actor's level is at least the level stored on the
disable; otherwise 403 with a message naming the level ("Disabled by platform staff. Only platform
staff can enable it."). The level is the level of the **role through which the actor holds the
authority used**, not the authority's own level: a Customer Admin holds BRAND authorities such as
`phone-number:manage` through its COMPANY role, so it acts at COMPANY. If the actor holds it
through several roles, the highest counts. This applies to custom roles the same way. Disabling something already disabled keeps the original record (the higher one is
never overwritten by a lower one; a higher actor re-disabling replaces a lower record with theirs).

Stored on each thing as `disabled: { by: <username>, level: PLATFORM|COMPANY|BRAND, at: Instant }`,
null when enabled. Every enable/disable also writes a change record (`enablement_changes`: target
type, target id, enabled before/after, by, level, at) so the console can show history later.

## Phone numbers

### Data

Collection `phone_numbers`:

- `_id`: generated id.
- `number`: digits only, country code first, no `+` (e.g. `919812345678`), 8–15 digits. Unique
  across the platform — one number belongs to exactly one brand.
- `customerId`, `workspaceId`.
- `label`: optional, ≤ 60 chars ("Sales line").
- `disabled`: as above.
- Connection facts kept by the server: `lastConnectedAt`, `lastSeenAt`, `lastWrongNumber` (the
  number WhatsApp Web reported when it did not match, with time), `deviceKeyIssuedAt`.
- Audit: `createdBy`, `creationTime`, `lastUpdateTime`, `@Version`.

A brand may have several numbers.

Shown status (computed, not stored): **Disabled** (number, brand or customer disabled — say which),
**Not connected yet** (no key ever redeemed), **Connected** (live socket, number matches),
**Waiting for WhatsApp login** (live socket, WhatsApp Web not logged in yet), **Wrong number**
(last attempt reported a different number; shows it), **Disconnected** (key exists, no live
socket; shows last seen).

### Console

- **Brand → Phone numbers** page (Workspace Manager, Customer Admin; everyone with
  `phone-number:view` in that brand): list, add, edit label, enable/disable, get connect code,
  revoke device, delete.
- **Platform → Phone numbers** page (Super Admin, Account Manager): every number with its customer
  and brand, search by number/customer/brand, filter by status, enable/disable. Platform staff do
  not add numbers (brands do), but can delete.
- Deleting a number disconnects it and invalidates its key; refused while it has data that
  depends on it (none yet — the rule exists for the contacts project).

### Connect code and device key

1. "Get connect code" shows an 8-character code (letters/digits, no look-alikes, shown grouped
   `ABCD-EFGH`) valid **15 minutes**, single use. Getting a new code invalidates any unused one for
   that number. Stored hashed.
2. In the extension popup the person enters the server URL and the code. The extension calls
   `POST /api/devices/connect {code}`; the server answers `{deviceKey, phoneNumberId, number,
   brandName}`. The extension stores the key and shows "Connected for +91 98123 45678 (Brand)".
3. The device key is a long random secret (≥ 256 bits), stored hashed; one per number. Redeeming a
   new code replaces the old key, and the old extension is disconnected ("replaced by a new
   device"). "Revoke device" deletes the key and disconnects.
4. The key never expires on its own. Disabled number/brand/customer → key is refused while
   disabled and works again when enabled (no new code needed).
5. Wrong or expired codes: the same "invalid or expired code" answer; rate-limited per client IP
   (reuse the login attempt approach: 10 failures in 15 min locks that IP for 15 min).

### WebSocket and HTTP

- The WebSocket `auth` frame carries the device key (replacing the JWT). The registry keys
  devices by phone number id, not username.
- On `hello` (and on every later `hello`/state update) the server compares the phone-number
  user part of `info.wid` with `number`. The extension's `wid` can fall back to a privacy id
  (`…@lid`, see `src/page/inject.js` `buildInfo`); a `@lid` wid never confirms a number: the
  connection stays "Waiting for WhatsApp login" (shown as "Can't read the logged-in number yet")
  and forwards nothing. The extension should send the phone-number id separately when it can
  read it. Mismatch → record `lastWrongNumber`, close with a distinct code/reason
  ("WhatsApp Web is logged in to +X, this device is for +Y"); the extension shows that reason and
  does not retry until the person re-opens the popup or WhatsApp Web's account changes. No
  `info` yet (QR not scanned) → stay connected, status "Waiting for WhatsApp login", but forward no
  events to listeners until the number is confirmed.
- Events before the number is confirmed are **not acknowledged**, so the extension's outbox
  replays them after confirmation. The extension tags every outbox entry with the device key id
  and the WhatsApp account it was captured under; the outbox is cleared on Disconnect, on a key
  change and when WhatsApp Web's account changes; the server drops (and acknowledges) entries
  whose tag doesn't match the connection. `EventSequenceTracker` and `RecentEventsStore` are
  keyed by phone number id.
- `WhatsAppEvent` carries `phoneNumberId`, `workspaceId`, `customerId` instead of `username`.
- Disabling a number, its brand or its customer, revoking or replacing the key, or deleting the
  number closes the live socket immediately; reconnects are refused while it stays so.
- The extension's script download (`/api/scripts`) authenticates with the device key.
- The PoC test endpoints (`/api/status`, `/api/events`, `/api/messages`, `/api/rpc`) move to
  `/api/phone-numbers/{id}/bridge/...`, Super Admin only, until the messaging project replaces them.
- Removed: the `wabridge` seeded user, the `WHATSAPP_DEVICE` role, the `whatsapp:bridge` authority,
  and the extension's username/password login (including the managed-storage username/password).
  A startup migration deletes the seeded device user, the `WHATSAPP_DEVICE` role and the
  `whatsapp:bridge` authority document explicitly (the normal rules refuse to delete built-ins,
  and the seeder never clears `builtIn`). `UserRoleMigration`'s old `"USER"` → `WHATSAPP_DEVICE`
  mapping goes too (such users are deleted, not mapped).

### Extension

- Popup: server URL + connect code, then the connected number/brand, the live status and the last
  error (wrong number, disabled, replaced, revoked). A "Disconnect" button forgets the key.
- Managed storage (policy-provisioned browsers): `serverUrl` stays; `username`/`password` are
  replaced by `connectCode` (redeemed once if no key is stored yet).
- Server says key invalid/revoked → forget the key, show "Enter a new connect code".

## Enable/disable for brands, customers and people

- **Customers**: Super Admin / Account Manager (existing `customer:manage`). Existing behaviour stays: while disabled,
  all its users' tokens are refused (so they are effectively signed out) and work again on enable;
  new: all its numbers disconnect. This renames deactivate and adds the level record.
- **Brands**: Super Admin / Account Manager (`customer:manage`) and Customer Admin (new
  COMPANY authority `workspace:enable`). A disabled brand: its numbers disconnect, its users
  can't use it (the brand disappears from their switcher and its brand-scoped API answers 403);
  users with access to other brands keep those. Customer Admin and platform staff
  still see a disabled brand (with a Disabled label) on the Brands and Customer pages so they can
  enable it; every other action inside a disabled brand is refused until it is enabled.
- **People**:
  - Super Admin / Account Manager can disable any customer's user — whole login, signs them out
    (existing token-version bump). Today Account Managers only reach Customer Admins, and no
    endpoint lets platform staff reach other users; so Customer detail gets a **Users** tab listing
    all the customer's users with Disable/Enable.
  - Customer Admin: any user of its company — whole login (existing `company-user:manage`).
  - Workspace Manager: a Team Lead or Agent's **access to that brand** (new BRAND authority
    `user:enable`), not the whole login, because the person may work in other brands. Stored on
    the person's role entry for that brand. If it was their only brand they effectively can't do
    anything until enabled. A brand-access disable survives removing and re-adding the person to
    that brand, and role changes there (kept per user per brand), so it can't be bypassed.
  - The level rule applies to each: a Workspace Manager can't enable a brand access that a
    Customer Admin disabled; a Customer Admin can't enable a login platform staff disabled.
  - Existing assignment rules still apply (you can only disable people whose roles are a strict
    subset of yours).
- Console wording: "Disable"/"Enable" buttons and an "Enabled"/"Disabled" label (with "by
  <level>" on hover) on every list. API endpoints become `/disable` and `/enable`; the old
  `/deactivate` `/reactivate` are removed (frontend and backend deploy together, as before).

## New authorities (built-in)

| Code | Level | Default roles |
|---|---|---|
| `phone-number:view` | BRAND | Customer Admin, Workspace Manager, Team Lead |
| `phone-number:manage` | BRAND | Customer Admin, Workspace Manager — add, label, connect code, revoke, delete, enable/disable |
| `phone-number:view-all` | PLATFORM | Account Manager (Super Admin has all) |
| `phone-number:manage-all` | PLATFORM | Account Manager — enable/disable, delete any number |
| `workspace:enable` | COMPANY | Customer Admin |
| `user:enable` | BRAND | Customer Admin, Workspace Manager |

`company-user:manage` and `customer:manage` descriptions change "deactivate/reactivate" to
"disable/enable". Platform staff disabling company users uses `customer:manage`.

## Seed data

Off unless `omnichannel.seed.demo=true` (env `OMNICHANNEL_SEED_DEMO`; there is no dev profile today):
an idempotent seeder that, when no customer named "Demo Retail" exists, creates a demo customer "Demo Retail" with
brands "Demo Fashion" and "Demo Electronics" through the normal customer-creation
service (so it gets its Customer Admin owner), one Workspace Manager, Team Lead and Agent each
(throwaway passwords printed to the log once), and the user's real number `919928088743`
(+91 99280 88743, the one they will log in to WhatsApp Web with) on "Demo Fashion". "Demo
Electronics" gets no number, so the empty state is visible. If that number already exists in
another brand the seeder leaves it alone and logs a warning. The contacts
project will extend this seeder with contacts, labels and attributes.

## Left to the builder

- Exact WebSocket close codes (distinct ones for wrong number, disabled, replaced, revoked).
- Table layouts and how history is surfaced (stored now; a History dialog is welcome but optional).
- How the connect-code IP limiter shares code with the login limiter.
- Phone number display formatting.

## My calls (not discussed with the user — check these)

1. One number belongs to one brand platform-wide; a brand can have several.
2. Connect code: 8 characters, 15 minutes, single use; device key never expires on its own.
3. Customer Admin can disable its own brands; Workspace Managers can't disable their brand.
4. A Workspace Manager disables a person's access to their brand only; whole-login disable is for
   Customer Admin and platform staff.
5. Platform staff can enable/disable and delete numbers but not add them.
6. PoC test endpoints move under the phone number and become Super Admin only.
7. Seed data only when `omnichannel.seed.demo=true`.
9. A disable's level comes from the role the authority was held through (Customer Admin acts at
   COMPANY even with BRAND authorities).
10. A `@lid`-only WhatsApp id never confirms a number; it waits.
11. Outbox entries are tagged with key and account, cleared on change; unconfirmed events are not
    acknowledged so they replay.
12. Customer Admin and platform staff still see disabled brands; Customer detail gets a Users tab.
13. Brand-access disables survive remove/re-add.
8. "Disable/Enable" replaces "Deactivate/Reactivate" in the API as well as the UI.
