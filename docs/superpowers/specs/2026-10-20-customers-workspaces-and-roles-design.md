# Design: Customers, workspaces, roles and authorities

Status: draft 2026-10-20. Spans `omnichannel-crm-backend` and `omnichannel-crm-frontend`.
Builds on `2026-10-19-users-and-two-factor-login-design.md` and `2026-10-19-admin-console-design.md`,
both merged. Replaces the placeholder `UserRole` enum (`SUPER_ADMIN`, `APP_ADMIN`, `ADMIN`, `USER`).

## Intent

The product is a multi-tenant omnichannel CRM. The platform owner (the Super Admin) and their staff
sell it to customer companies. A customer company can run several brands, each with its own
WhatsApp number and store, and each brand's data must stay separate. The owner of a customer
company oversees the account; day-to-day work is done by managers, team leads and agents.

This design fixes the tenancy structure (customer → workspace → team), the people in it, and the
authorization model every later feature (inbox, channels, leads, reports) will be checked against.
Its first slice delivers user and customer management only: a working chain from the Super Admin
down to an Agent logging in and seeing only their own brands.

## Goals

- Tenancy: customer (company) → workspaces (brands) → teams.
- Full isolation between customers, and between workspaces of the same customer.
- Authorization by fine-grained authorities. Roles are named bundles of authorities, stored in the
  database, editable and creatable by the Super Admin from the console.
- One rule decides who may give which role to whom (strict subset, below).
- The console renders menus from the logged-in user's authorities, per workspace.
- First slice screens: platform users, roles, customers, workspaces, workspace users, teams,
  workspace switcher, first-login password change.

## Non-goals (later slices)

- Conversations, contacts, leads, orders, channel connection screens, reports. (The model below is
  what they will be checked against; they are not built now.)
- Roles defined by customers themselves.
- Assigning customers to particular Account Managers.
- Customer Admin forcing two-factor on their staff.
- Email invitations, forgot-password, self-service sign-up. Temporary passwords are handed over out
  of band by whoever created the user.
- Deactivating or deleting workspaces and teams; deleting users or customers.
- Audit log of user changes (only role changes are audited in this slice).
- One login working for two customers.

## Anti-goals

- Any request reaching another customer's data, or another workspace's data without access to it.
- Anyone giving out more power than they hold.
- The Super Admin being able to lock themselves out.
- Breaking the Chrome extension: the device user's `POST /api/auth/login` contract and WebSocket
  `auth` frame keep working unchanged.

## Decided by the user

- Roles: `SUPER_ADMIN` → `ACCOUNT_MANAGER` → `CUSTOMER_ADMIN` → `WORKSPACE_MANAGER` → `TEAM_LEAD` →
  `AGENT`.
- Super Admin and Account Managers are platform staff. Every Account Manager sees all customers.
- Account Managers create customers together with the customer's Customer Admin.
- Customer Admin is the company owner: oversight, not day-to-day work. In a very small company the
  Customer Admin does everything.
- Workspace Managers run day-to-day work with Team Leads and Agents. Only the Customer Admin creates
  Workspace Managers.
- A workspace is a separate brand with its own WhatsApp number and store. Brand data is isolated;
  the same person messaging two brands is two separate contacts. Only the Customer Admin sees across
  brands.
- Creating a customer also creates its first workspace.
- Users are given access per workspace. One login belongs to exactly one customer.
- Roles are containers of authorities; code checks authorities (`hasAuthority`-style). The UI shows
  menus and submenus from authorities.
- The Super Admin edits which authorities a role has, and creates new roles, from the console.

## Tenancy model

```
Customer (company)                 users with a COMPANY role (Customer Admin) act in every workspace
 ├── Workspace "Brand A"           channels (WhatsApp number, store) belong to exactly one workspace
 │     ├── Team "General"
 │     └── Team "Returns"
 └── Workspace "Brand B"
       └── Team "General"
Platform users (Super Admin, Account Managers, the device user) belong to no customer.
```

- `customers`: `id`, `name`, `active`, `creationTime`, `lastUpdateTime`, `createdBy` (user id).
- `workspaces`: `id`, `customerId`, `name` (unique within the customer), timestamps.
- `teams`: `id`, `customerId`, `workspaceId`, `name` (unique within the workspace), `memberIds`
  (user ids; each must have access to the workspace), timestamps.
- Creating a customer creates, in one operation: the customer, its first workspace (name defaults to
  the customer name, editable on the form), a team named `General` in it, and the Customer Admin user.
  If any part fails, nothing is left behind. (My call: Mongo here is standalone, so the service
  creates in order and deletes what it created on failure, instead of a transaction.)
- Every later brand-scoped document (contacts, conversations, …) carries both `customerId` and
  `workspaceId`. Repository methods for brand data take the `workspaceId` as a required argument.

## Users

Changes to `users`:

- New `customerId`: null for platform users, set for everyone else, never changes.
- `role` (enum) is replaced by:
  - `roleId`: one PLATFORM or COMPANY role, or null.
  - `workspaceRoles`: list of `{ workspaceId, roleId }`, BRAND roles only, at most one entry per
    workspace. Empty for platform users.
- New `mustChangePassword` (boolean).
- `username` is stored lower-case and unique across the whole platform; login lower-cases the input.
  (My call.)
- A customer user must hold a COMPANY role or at least one workspace role, so nobody exists who can
  log in to nothing.
- Team membership is separate from roles: a `TEAM_LEAD` is a role in the workspace; which team they
  lead is just team membership. (My call; leading specific teams matters only once conversations are
  routed, a later slice.)

Migration (startup, idempotent): `role: SUPER_ADMIN` → `roleId` of the built-in `SUPER_ADMIN` role;
`role: USER` (the device user) → built-in `WHATSAPP_DEVICE` role; any other value → startup fails
with a clear message (local data has only those two). The `role` field is then removed.

## Authorities

An authority is a code-defined permission named `thing:action`, with a fixed **level**: PLATFORM,
COMPANY or BRAND. The full list lives in one place in code (an enum or catalogue), each entry with
its name, level, a one-line description shown in the console, and the built-in roles that get it by
default. New features add authorities; authorities are never created from the console.

> Superseded in part: `docs/superpowers/specs/2026-10-20-authority-management-design.md` lets the Super Admin create authorities from the console, replacing "never created from the console".

First-slice catalogue:

| Authority | Level | Meaning | Default roles |
|---|---|---|---|
| `platform-user:manage` | PLATFORM | Create, edit, deactivate platform users and give platform roles | — (Super Admin only) |
| `customer:view` | PLATFORM | See the customer list and details | ACCOUNT_MANAGER |
| `customer:create` | PLATFORM | Create a customer with its owner and first workspace | ACCOUNT_MANAGER |
| `customer:manage` | PLATFORM | Edit, deactivate/reactivate a customer, add a Customer Admin, reset its password | ACCOUNT_MANAGER |
| `whatsapp:bridge` | PLATFORM | Use the WhatsApp bridge endpoints and WebSocket | WHATSAPP_DEVICE |
| `workspace:create` | COMPANY | Create workspaces | CUSTOMER_ADMIN |
| `company-user:manage` | COMPANY | Deactivate/reactivate any user of the company | CUSTOMER_ADMIN |
| `workspace:settings` | BRAND | Rename the workspace (later: hours, routing) | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `user:view` | BRAND | See users with access to the workspace | CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD |
| `user:manage` | BRAND | Create users in the workspace, give/change/remove their role here, reset their password | CUSTOMER_ADMIN, WORKSPACE_MANAGER |
| `team:manage` | BRAND | Create and rename teams, change members | CUSTOMER_ADMIN, WORKSPACE_MANAGER |

`AGENT` has no authorities in this slice: it can log in, switch workspace, see the dashboard and its
profile. Its authorities arrive with the inbox.

Role editing is **not** an authority: only the Super Admin can do it, and it cannot be granted.

## Roles

`roles`: `id`, `name` (unique, upper snake case, e.g. `SENIOR_AGENT`), `description`, `level`,
`authorities` (list of names), `builtIn` (boolean), timestamps.

- A role may hold authorities of its own level or below: a PLATFORM role only PLATFORM authorities
  (platform staff do not act inside customers' brands in this slice); a COMPANY role COMPANY and
  BRAND authorities; a BRAND role only BRAND authorities. The API rejects anything else.
- A COMPANY role's BRAND authorities apply in every workspace of the user's customer. This is how the
  Customer Admin "does everything" in a small company, while staying editable.
- `SUPER_ADMIN` is built in, not stored with a list: it holds every PLATFORM-level authority, always. It has no customer, so it holds no COMPANY authorities and no brand access (see "Platform staff and brand pages").
  It cannot be edited, deleted or given to anyone; it exists only on the seeded user.
- Built-in roles (`ACCOUNT_MANAGER`, `WHATSAPP_DEVICE`, `CUSTOMER_ADMIN`, `WORKSPACE_MANAGER`,
  `TEAM_LEAD`, `AGENT`) are seeded on first start, can have their authorities, description edited,
  but not their name or level, and cannot be deleted.
- Custom roles: the Super Admin chooses name, description, level and authorities. A role that is
  held by any user cannot be deleted.
- New authorities in a release: at startup, any catalogue authority the system has never seen before
  is added to the built-in roles listed as its defaults. Authorities the system has already seen are
  never re-added, so a Super Admin's removal sticks. The seen list is kept in Mongo. An authority
  removed from code is ignored where still stored.
- Every create, edit and delete of a role writes a `roleChanges` document: `roleId`, `roleName`,
  `changedBy`, `at`, `action`, `added`, `removed`, and before/after description.

## Who may give a role: the strict-subset rule

Effective authorities of a user **in a workspace W** = their COMPANY role's authorities (if W is in
their customer) ∪ their role in W. Effective **company** authorities = their COMPANY role's. Platform
users' effective authorities = their PLATFORM role's. The Super Admin has everything.

To give user X role R in scope S (a workspace, or the company, or the platform), the acting user A
must:

1. hold the managing authority for that scope: `user:manage` in W for a BRAND role; for a COMPANY
   role, `customer:manage` (Account Manager adding a Customer Admin); for a PLATFORM role,
   `platform-user:manage`;
2. have R.authorities be a **strict subset** of A's effective authorities in S. This applies at every
   level, so a custom PLATFORM role holding `platform-user:manage` still cannot hand out a role equal
   to or above its own. The one exception is COMPANY roles given by platform staff (different
   levels, nothing to compare): `customer:manage` alone allows it;
3. for X already existing, depending on what the action touches:
   - **one workspace** (change or remove X's role in W): X's role in W must be a strict subset of
     A's effective authorities in W;
   - **the whole login** (reset password, edit name/email/mobile, deactivate/reactivate): A must pass
     the check above in **every** workspace where X has a BRAND role, and X must hold no COMPANY role
     unless A is platform staff with `customer:manage`. So a Workspace Manager of Brand A cannot
     reset the password of someone who is also Workspace Manager of Brand B, nor the Customer Admin's;
     someone with roles in a brand A cannot manage is left to the Customer Admin;
4. belong to X's customer (platform staff excepted for COMPANY roles via `customer:manage`).

Deactivating/reactivating a whole login also needs `company-user:manage` (Customer Admin) on top of
rule 3. Platform staff deactivate a Customer Admin through `/api/customers/{id}/admins/{userId}/…`
with `customer:manage`; the company endpoint never accepts a PLATFORM authority. (Builder-check calls.)

With the default roles this gives: Customer Admin assigns Workspace Manager, Team Lead, Agent;
Workspace Manager assigns Team Lead and Agent in their own workspaces but not another Workspace
Manager; Team Lead assigns nothing (no `user:manage`). Only Customer Admin creates Workspace
Managers, as decided.

The Account Manager → Customer Admin step is not a subset (different levels); it is allowed by
`customer:manage`/`customer:create` alone. Super Admin is exempt from all of the above (within platform and customer pages; see "Platform staff and brand pages").

**Edit warning.** Before saving a role edit, the console asks the server for a preview: the list of
(role that assigns, role assigned) pairs among all roles that change between assignable and not
assignable because of the edit, e.g. "Team Leads will no longer be able to assign Agent." The Super
Admin confirms or cancels. Saving does not depend on the preview.

## Checking authorities

- Authorities are not put in the JWT. The access token loses its `role` claim. On each request the
  user is already loaded from Mongo (tokenVersion check); roles are read through an in-memory cache
  cleared on every role change, so edits apply on the user's next request without logging out.
  (Single-instance deployment; a multi-instance cache is a later concern.)
- Inactive customer: its users' logins fail with `account_inactive` and their existing tokens are
  rejected on the next request, same as an inactive user.
- PLATFORM and COMPANY authorities are Spring `GrantedAuthority`s: `@PreAuthorize("hasAuthority('customer:create')")`.
- BRAND authorities go through a bean: `@PreAuthorize("@access.has(#workspaceId, 'user:manage')")`.
  A workspace that does not exist, belongs to another customer, or that the user has no access to
  answers 404 (the bean throws); a reachable workspace where the user lacks the authority answers 403.
  Platform staff, including the Super Admin, have no brand access in this slice.
- Brand APIs take the workspace in the path: `/api/workspaces/{workspaceId}/…`.
- A workspace or customer the caller may not see answers **404**, not 403, so ids of other tenants
  are not confirmed to exist. (My call.) A known resource the caller may see but not change answers
  403 `{ code: "FORBIDDEN", message }`.
- The WhatsApp REST endpoints and the WebSocket `auth` frame require `whatsapp:bridge`. This closes
  the gap where any token could use them. The device user keeps working via `WHATSAPP_DEVICE`; the
  Super Admin has it too.

`GET /api/users/me` adds (existing fields kept, `role` replaced):

```json
{
  "superAdmin": false,
  "customer": { "id": "…", "name": "Acme" },
  "role": { "id": "…", "name": "CUSTOMER_ADMIN" },
  "authorities": ["workspace:create", "company-user:manage"],
  "workspaces": [
    { "id": "…", "name": "Brand A", "role": null, "authorities": ["user:view", "user:manage", "…"] }
  ],
  "mustChangePassword": false
}
```

`workspaces` lists every workspace the user can act in, each with effective authorities. For the
Super Admin, `superAdmin: true`, `customer: null`, `workspaces: []` and the UI treats every authority
as held.

**Access to a workspace** means a BRAND role in it, or a COMPANY role in its customer. So Customer
Admins appear in a workspace's user list (marked "All brands", with actions the viewer cannot take
disabled), can be team members, and are never given a BRAND role as well (the API refuses it as
redundant). (Builder-check call.)

**Platform staff and brand pages.** In this slice the Super Admin and Account Managers do not use a
customer's brand pages: no switcher across customers, and `@access.has` grants the Super Admin
nothing it cannot reach through its own pages. The rescue path for a locked-out customer is the
customer detail page (reset or add a Customer Admin). Keeps customers' brand data out of platform
staff's everyday view until support access is designed. (Builder-check call.)

## First-login password change

- Users created by someone else get a temporary password and `mustChangePassword = true`. Resetting a
  password does the same and bumps `tokenVersion`.
- `POST /api/auth/login` (and `verify-otp`, if two-factor is on) for such a user answers
  `200 { passwordChangeRequired: true, passwordChangeToken }` instead of a token. The change token is
  a JWT with `authState = PASSWORD_CHANGE_PENDING`, ten minutes, accepted only by
  `POST /api/auth/initial-password { newPassword }` → `200 { token, expiresAt }`.
- New password: at least 10 characters, not equal to the temporary one. Otherwise
  `400 { code: "WEAK_PASSWORD", message }`; a bad or expired change token answers
  `401 { code: "INVALID_TOKEN", message }`.
- The seeded users never have `mustChangePassword`, so the extension's login is unchanged.

## API summary (first slice)

| Endpoint | Needs |
|---|---|
| `GET/POST /api/platform-users`, `PUT /api/platform-users/{id}`, `POST …/{id}/deactivate`, `…/reactivate`, `…/reset-password` | `platform-user:manage` |
| `GET /api/roles`, `GET /api/authorities` | Super Admin |
| `GET /api/platform-users/assignable-roles`, `GET /api/customers/assignable-roles`, `GET /api/workspaces/{workspaceId}/assignable-roles` | `platform-user:manage` / `customer:manage` or `customer:create` / `user:manage` — the roles the caller may give there, for role pickers |
| `POST /api/roles`, `PUT /api/roles/{id}`, `DELETE /api/roles/{id}`, `POST /api/roles/{id}/preview`, `GET /api/roles/{id}/changes` | Super Admin |
| `GET /api/customers`, `GET /api/customers/{id}` | `customer:view` |
| `POST /api/customers` (company name, first workspace name, owner name/username/email/mobile/temporary password) | `customer:create` |
| `PUT /api/customers/{id}`, `…/deactivate`, `…/reactivate`, `POST /api/customers/{id}/admins`, `…/admins/{userId}/reset-password`, `…/admins/{userId}/deactivate`, `…/admins/{userId}/reactivate` | `customer:manage` |
| `GET /api/workspaces` (mine), `POST /api/workspaces` | logged in / `workspace:create` |
| `PUT /api/workspaces/{workspaceId}` | `workspace:settings` |
| `GET /api/workspaces/{workspaceId}/users` | `user:view` |
| `POST /api/workspaces/{workspaceId}/users` (new user, or existing company user by id, with a role) | `user:manage` + subset rule |
| `PUT …/users/{userId}` (details, role here), `DELETE …/users/{userId}` (remove access here), `POST …/users/{userId}/reset-password` | `user:manage` + subset rule |
| `POST /api/users/{userId}/deactivate`, `…/reactivate` | `company-user:manage` + rule 3 |
| `GET/POST /api/workspaces/{workspaceId}/teams`, `PUT …/teams/{teamId}` | `user:view` / `team:manage` |

Errors use the existing API shape `{ code, message }` (login errors keep their `{ error }` shape for the
extension). Validation errors answer `400 { code: "VALIDATION", message, fields: { name: "…" } }`;
duplicates (username, role name, workspace or team name) answer `409 { code: "DUPLICATE", message, field }`.

## Console (frontend)

- Every logged-in user can use the console (the SUPER_ADMIN-only gate goes). Menus show from
  authorities; a page reached without the authority shows the not-found page.
- Masthead workspace switcher, shown when the user has any workspace. The current workspace is kept
  in `localStorage` (falls back to the first in the list); brand pages use it.
- Pages and who sees them:
  - **Platform users** (`platform-user:manage`): list, create (with a PLATFORM role), edit,
    deactivate/reactivate, reset password.
  - **Roles** (Super Admin): list by level with built-in/custom marker and user count; create; edit
    authorities as a checklist grouped by level with descriptions; edit-warning dialog from the
    preview; delete when unused; change history.
  - **Customers** (`customer:view`): list with search and active filter; create form; detail page
    with workspaces, Customer Admins (add, reset password), edit, deactivate/reactivate.
  - **Workspaces** (`workspace:create`): list the company's workspaces, create, rename.
  - **Users** (`user:view`, current workspace): list with role; create user, add existing company
    user, change role, remove from workspace, reset password (`user:manage`); deactivate/reactivate
    (`company-user:manage`). Role pickers offer only roles the server says the user may assign.
  - **Teams** (`user:view`, current workspace): list; create, rename, edit members (`team:manage`).
  - **Change password** page for the first-login flow, before anything else.
- Show the temporary password once after creating or resetting, with a copy button.
- Follows PatternFly guidelines; no mockups, as for the first console slice.

## Testing

- Isolation is the property that must not break: integration tests that a user of customer A gets 404
  for every brand endpoint with a workspace of customer B, and for a workspace of their own customer
  they have no access to.
- Strict-subset rule tested on the rule itself (unit) and through the endpoints with default roles:
  the table above as tests, including Workspace Manager vs Workspace Manager and Customer Admin.
- Role edit takes effect on the next request without re-login.
- Extension compatibility: device-user login, REST bridge calls and WebSocket auth still pass;
  a Customer Admin token is refused by the WhatsApp endpoints.

## Left to the builder

- Package layout, class names, how the authority catalogue is expressed in code.
- Exact copy and layout of console pages within PatternFly guidelines.
- How the preview computes pairs, as long as it reflects the strict-subset rule.
- Pagination: lists may be unpaginated in this slice except customers (paged, 50).
