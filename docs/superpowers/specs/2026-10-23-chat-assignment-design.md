# Chat assignment — Design

> **Status: approved 2026-10-23.** Plans: `docs/superpowers/plans/2026-10-23-chat-assignment-backend.md`, `2026-10-23-chat-assignment-console.md`. The CRM design (`2026-10-23-crm-design.md`) is deferred and will be re-based on this one.

## 1. Context and intent

A brand such as *Demo Fashion* has several WhatsApp numbers; some are worked by its Sales team, some by Support.
Today the inbox works **one number at a time**: a user picks a number, sees that number's chats, and the number's
own routing setting (`inboxSettings`, `ChatRoutingService`) decides who gets new chats. Nothing says which numbers a
team *works*, agents aren't asked whether they are free, round robin walks `memberIds` blindly per number, closing a
chat forgets who handled it, and an agent can't hand a chat on. This design makes the inbox **team-centred**: teams
own numbers, routing is a brand default that a team can override, agents work all their teams' numbers from one
list with a phone-number filter, and chats can be claimed, handed back or moved between teams.

## 2. Decisions (user's)

| # | Decision |
|---|---|
| A1 | **One owning team per number** (or none). Only the owning team's members and brand admins see the number. |
| A2 | Routing is **"assign to team"** (the owning team) with an optional **round robin**: team only → the chat waits in the team queue and agents take it themselves; team + round robin → the next available member. Round robin is **team-wide**: one rotation covers the incoming traffic of all the numbers the team owns. |
| A3 | **The `TEAM_LEAD` role is removed.** Brand roles are WORKSPACE_MANAGER and AGENT (plus CUSTOMER_ADMIN above). Everything TEAM_LEAD had beyond AGENT goes to WORKSPACE_MANAGER; existing holders become AGENT (§0). No lead field, no escalation. |
| A4 | **Sticky within a window:** a returning customer goes back to the agent who last handled them on that number if that was within the sticky window and the agent is still eligible; else normal routing. |
| A5 | **Availability is an agent-set status** (Available / Away). Routing and stickiness skip Away agents. |
| A6 | **Nobody available → queue silently.** No auto-reply. |
| A7 | **Anyone in the owning team can claim from the team queue;** they see the queue but not teammates' assigned chats (new `inbox:view-queue`, §3.5). |
| A8 | **An agent can reassign a chat assigned to them** (§3.6): (1) to another agent of the same team; (2) back to their own team — routing runs again and skips them; (3) to another team — that team's routing runs. |
| A9 | **Routing settings have two levels:** the brand has exactly one **default** routing setting; a **team can override** it. Settings are no longer per number. |
| A10 | **The inbox shows all chats assigned to the user across their teams' numbers** (which may be several), with a **phone-number filter** (one or some numbers) on the inbox page. |

Non-goals: working-hours schedules, presence from activity, load balancing, lead escalation, auto-replies, team
deletion. All can be added later without changing this model.

## 0. Prerequisite: remove the TEAM_LEAD role

- **Authorities:** drop `TEAM_LEAD` from every default-role list in `access/Authority.java` (16 entries). Because
  CUSTOMER_ADMIN and WORKSPACE_MANAGER already appear on each, "everything to WORKSPACE_MANAGER" is exactly that
  deletion. AGENT keeps `inbox:view`, `inbox:send`, `contact:view`, `contact:annotate`, `template:view` and gains the
  new **`inbox:view-queue`** (§3.5). Teammates' assigned chats (`inbox:view-team`) and assigning *arbitrary* people
  (`inbox:assign`) stay manager powers; the agent's own hand-offs in §3.6 need only `inbox:send`.
- **Role catalogue:** remove the constant from `BuiltInRoles`, the entry from `RoleSeeder.BUILT_INS`, the reference in
  `RoleService`. `AssignmentPolicy`/`AuthorityCatalog` stop knowing it.
- **Migration** (one-off `SmartInitializingSingleton`, same pattern as `UserRoleMigration`): every `User.roleId` or
  `workspaceRoles[].roleId` pointing at the built-in `TEAM_LEAD` → built-in `AGENT`; a `RoleChange` per user (actor
  `system`, reason "TEAM_LEAD removed"); then delete the role document. Idempotent.
- **Demo seed:** "Demo Lead" (`demo-lead`) becomes an AGENT; `DemoSeederIT` updated.
- **Tests:** ~15 backend and ~13 frontend files use TEAM_LEAD as a fixture; each moves to WORKSPACE_MANAGER where the
  test needs team-level powers, else AGENT. `AuthorityTest`/`RoleSeederIT`/`AssignmentPolicyTest` assert the new
  catalogue. Console: `RoleSelect` reads roles from the API; only `src/test/session.ts` fixtures change.

## 3. Model

### 3.1 Owning team
- `PhoneNumber.teamId` (nullable), same workspace. Set via `PUT /api/workspaces/{ws}/phone-numbers/{id}/team`
  (`phone-number:manage`); the Teams page lists each team's numbers (read-only). Teams cannot be deleted today and
  this design does not add deletion (*my call*).
- **Visibility:** a user without `inbox:view-all` sees the numbers owned by their teams **plus any number on which they
  have a chat their scope covers** (own, or in their team's queue — the hand-off case; marked `guest: true` so the
  console shows "via transfer"; on a guest number only those chats are listed). Admins see all, including unowned.
  Every chat endpoint checks the chat's number against this set; otherwise 404.
- **Assignees restricted to people who can see the number** (*my call*): `GET /inbox/assignees?phoneNumberId=`
  returns the owning team's eligible members plus `inbox:view-all` holders; `assign` to anyone else → 422
  `ASSIGNEE_CANNOT_SEE_NUMBER`. When a number's owner changes or a member leaves the team, their **open** chats on it
  are unassigned and re-routed (§3.4; `ROUTED` reason `OWNER_CHANGED` / `MEMBER_REMOVED`).
- Unowned number: chats stay unassigned, admins only; the number page warns "No team — nobody but admins sees this
  number".

### 3.2 Availability
- `User.availability {status AVAILABLE|AWAY, at}`, one per user across brands (*my call*). Default AVAILABLE.
- `PUT /api/me/availability {status}`; `GET /api/me` and the assignees list include it.
- Eligible = active, not disabled (user or brand), member of the team being routed to, **AVAILABLE**. Manual
  assignment by a manager ignores availability.
- Going AWAY leaves open chats alone. Going AVAILABLE re-runs routing for queued chats of the agent's teams whose
  effective setting has round robin on, oldest first, normal team-wide rotation across all available members (*my
  call*: one returning agent isn't handed the whole backlog; team-only settings don't auto-assign).
- Console: a status toggle in the masthead; status dots next to names in assign menus and the chat header.

### 3.3 Sticky (returning customer)
- `WaWebChat.lastAgentUserId`, `lastAgentAt` — set when a user sends a message (`InboxSendService`) or is assigned.
  Never cleared by close. A chat is per number + person, so "last handled on that number" is this chat.
- `stickyDays` is part of the routing setting (§3.4; default 30, 0 = off).
- Runs wherever routing touches an **existing** chat: reopen by an inbound message of an unassigned chat, and
  `CampaignChatFirstReply` (*my call*). Within window + eligible → assign, `ROUTED` reason `STICKY`; else the routing
  setting applies. New chats always use the setting. Campaign sends set no `lastAgentUserId`; `ROUTED` never
  overwrites it. Agent AWAY on return → normal routing; the sticky link is kept for next time.
- Sticky is skipped when routing is re-run by a hand-back that excludes that very agent (§3.6).

### 3.4 Routing settings: brand default, team override (A2, A9)
- **`inboxSettings` is re-keyed:** one document per **scope** — `{workspaceId, teamId: null}` is the brand default
  (always present, created with the workspace or lazily), `{workspaceId, teamId}` is a team's override (optional).
  Unique index `{workspaceId, teamId}`. Fields: `routing {assignToTeam, roundRobin, userId?}`, `stickyDays`,
  `claimOnReply`, `keepAssignmentOnClose`. **Per-number settings and the `phone-numbers/{id}/settings` endpoints go
  away.**
- **Effective setting** for a chat = the owning team's override if present, else the brand default. Resolved by
  `InboxSettingService.effective(workspaceId, teamId)`.
- Routing modes as two switches, the team always being the owning (or target) team:
  - *Assign incoming chats to the team* off → chats stay unassigned (admins and the team's queue view see them).
  - on, *Round robin among available members* off → **team queue** (`assignedTeamId = team`, no user); members claim.
  - on, round robin on → next **available** member of the team; nobody available → team queue (A6).
  - *My call:* "Always assign to one member" (today's `USER` mode, member picker) stays as an advanced option in a
    **team override only** (it names a member of that team), falling back to the team queue when that member is
    unavailable. Stored as the existing `RoutingMode` enum.
- **Round robin is team-wide:** the cursor lives on **`Team.roundRobinCursor`**, so chats from all the team's numbers
  rotate over one sequence; advanced atomically with `updateFirst`.
- **Default brand setting** (*my call*): assign to team on, round robin off, sticky 30 days, claimOnReply on,
  keepAssignmentOnClose off — i.e. team queue. Unowned numbers ignore routing regardless.
- **Migration** (*my call*): existing per-number `inboxSettings` documents are moved to `inboxSettingsLegacy` and
  the brand default is seeded from the most common routing among that brand's numbers (`teamId`/cursor dropped).
  When an admin later sets a number's owner and a legacy document for that number named the same team, the team's
  override is created from it if none exists yet. Release note: set owning teams, then check routing per team.
- Console: **Settings → Inbox routing** page: the brand default card, and a card per team with "Use brand default /
  Override" and the switches. The per-number Routing modal is removed from the numbers page.

### 3.5 Queue visibility and claiming (A7)
- New authority **`inbox:view-queue`** (BRAND; default CUSTOMER_ADMIN, WORKSPACE_MANAGER, AGENT). `InboxScope.Level`
  becomes **ALL > TEAM > QUEUE > MINE**. QUEUE criteria: `assignedUserId = me` OR (`assignedUserId = null` AND
  (`assignedTeamId ∈ myTeams` OR `assignedTeamId = null`)); teammates' **assigned** chats are not visible.
  `ListScope.UNASSIGNED` allowed from QUEUE; `TEAM` still needs TEAM.
- **Claim:** `POST /inbox/chats/{chatId}/claim` (`inbox:send`): visible chat with no assignee → me (team kept),
  `ASSIGNED` by me to me, `ChatChanged`; race → 409 `CHAT_TAKEN`. `claimOnReply` keeps doing the same implicitly.
  Console: **Claim** button in the chat header; "Unassigned" list tab from QUEUE level.

### 3.6 Hand-offs by the assigned agent (A8) and transfers
All three are `POST /inbox/chats/{chatId}/handoff` with a body naming the kind; they need `inbox:send` and the chat
must be **assigned to the caller** (managers with `inbox:assign` may do the same on any chat they see, and keep
today's free `assign`/`unassign`). Each records a `WaWebChatAction` (`ASSIGNED`, `ROUTED` or new `TRANSFERRED`,
with the actor) and publishes `ChatChanged`.
1. **To a teammate** `{kind: TO_USER, userId}`: target must be a member of the chat's current team and able to see
   the number; availability is not checked (the agent decides). Action `ASSIGNED` by me to them.
2. **Back to my team** `{kind: TO_TEAM}`: `assignedUserId` cleared, then the team's effective routing runs with
   **`excludeUserId = me`** and **sticky skipped**: round robin picks the next available member other than me; team
   only → queue; nobody else available → queue. Action `ROUTED` with `reason = HANDED_BACK`, `excludedUserId`.
3. **To another team** `{kind: TO_TEAM, teamId}` (any other team of the brand): `assignedTeamId = target`, user
   cleared, action `TRANSFERRED` (from/to team); then the **target team's effective routing runs** (A8.3, replacing
   the earlier "always queue"): round robin → next available member of that team, team only → their queue. The chat
   **stays on its number** (replies still leave from the original number); the target team sees the number as a guest
   number (§3.1). Sticky applies as normal on later returns; if the last agent is no longer eligible, routing falls
   back to the **owning** team's setting.
- The agent loses sight of the chat after 2 and 3 unless it comes back to them (it is no longer theirs or in their
  queue). The hand-off shows in the chat's actions timeline ("Handed back by P", "Transferred to Support by P").
- Console: a **Reassign** menu in the chat header for the assignee — "Teammate…" (member list with status dots),
  "Back to team", "Another team…"; managers see it alongside today's Assign/Unassign.

### 3.7 Multi-number inbox with a phone-number filter (A10)
- **One list across numbers.** `GET /inbox/chats?phoneNumberIds=a,b&scope=MINE|UNASSIGNED|TEAM|ALL&…` replaces the
  per-number list: `phoneNumberIds` ⊆ the user's visible numbers (empty = all of them; a value outside → 404);
  existing filters (status, search, assignee) unchanged. Sort by `lastMessageAt` across numbers (existing
  `phone_*_last_message` indexes serve the `$in` on `phoneNumberId`; verify with `explain` in the IT).
- **One stream.** `GET /inbox/stream?phoneNumberIds=` (SSE) replaces the per-number stream: `InboxStreamBroker`
  subscribes per **workspace** with the viewer's number set and scope, re-authenticating per push as today; the
  console reconnects when the filter changes. `useInboxStream` keeps its fetch-reader shape.
- **Routes:** `/inbox` (list over the filter) and `/inbox/chats/:chatId`; the old `/inbox/:phoneNumberId[/:chatId]`
  redirect to the new ones (filter preset to that number). `InboxNumbersPage` becomes the filter's source, not a page.
- **Filter UI:** a phone-number multi-select in the chat-list header (all / some of the visible numbers), persisted
  per user + brand in localStorage, with a **number chip** on each row when more than one number is visible. The
  thread header shows the chat's number. Composer sends through the chat's number (already chat-based). **New chat**
  gains a number picker limited to the user's visible numbers (one visible number → preselected, no picker).
- `GET /inbox/phone-numbers` stays as the filter's data source (with `guest` and owner team).

## 4. Changes to existing code (in place)

Backend (§0): `access/Authority`, `BuiltInRoles`, `RoleSeeder`, `RoleService`, new `TeamLeadRoleMigration`,
`phone/DemoSeeder`.
Backend (§3.1–3.3): `phone/PhoneNumber` (+teamId) + service/controller, `tenant/Team` (+roundRobinCursor),
`tenant/TeamService` (member removal re-routes), `tenant/TeamController` (numbers), `user/User` (+availability) + me
endpoint, `inbox/WaWebChat` (+lastAgentUserId/At), `inbox/InboxSendService` (lastAgent), `ChatRoutingService`
(owning team, availability, sticky, excludeUserId, team-wide cursor), `ChatRoutingListener` (sticky on campaign
reply), `InboxDemoSeeder` (Demo Support owns the demo number).
Backend (§3.4): `InboxSetting` (re-keyed, +stickyDays, −phoneNumberId/−roundRobinCursor), `InboxSettingService`
(effective, validation), `InboxSettingRepository`, new `InboxSettingsMigration`, `InboxController` (settings
endpoints move to `/inbox/settings` and `/inbox/settings/teams/{teamId}`), `CollectionRenames` untouched.
Backend (§3.5–3.7): `Authority` (+`inbox:view-queue`), `InboxScope` (+QUEUE), `InboxScopeService`,
`ChatAssignmentService` (claim, handoff, assignee check), `ActionType` (+TRANSFERRED), `WaWebChatAction` (+reason,
fromTeam, excludedUserId), `InboxQueryService` (cross-number list, number visibility, guest), `InboxStreamBroker` +
`InboxPushListener` (workspace subscriptions with number sets), `NewChatService` unchanged (number comes from the
request as today), `AuthorityCheckScanner` picks up new codes.

Console: masthead availability toggle; Teams page numbers column; Phone numbers page team select (routing modal
removed); new Settings → Inbox routing page (brand default + team overrides); Inbox: cross-number list with
phone-number filter and chips, new routes + redirects, single stream, Claim button, Reassign menu, Unassigned tab,
New chat number picker, assign menu status dots.

Migrations: TEAM_LEAD → AGENT (§0); per-number `inboxSettings` → brand default + `inboxSettingsLegacy` (§3.4); other
fields nullable/defaulted. After deploy agents see no numbers until admins set owning teams; the inbox says so.

## 5. Left to the builder
DTO shapes; `lastAgent*` on the chat (recommended) vs derived from actions; toggle placement; whether the Teams page
edits numbers inline; redirect implementation for old inbox URLs; how the stream signals a filter change (reconnect
is fine).

## 6. Verification
0. TEAM_LEAD gone from `GET /api/roles`; holders are AGENT with `roleChanges`; `demo-lead` sees own chats + queue,
   not a teammate's chat; existing tests pass with new fixtures.
1. Sales owns A and B, Support owns C, D unowned. Sales agent P's inbox lists A and B in the filter and one chat list
   across both, newest first, with number chips; filtering to B shows B only; C → 404 on list, stream, send; admin
   sees A–D with the D warning. Old URL `/inbox/A` redirects to `/inbox` filtered to A.
2. Brand default = team queue. Sales override = round robin. Members P (Available), Q (Away), R (Available): chats on
   A, B, A, B → P, R, P, R; Q none. Q flips Available → queued chats on A and B routed across P, Q, R oldest first.
   Support (no override) gets chats on C in its queue; Support agent S claims one; a second claim → 409 `CHAT_TAKEN`.
3. All Sales Away → chats queue silently; P can't open Q's assigned chat (404) or assign to Q via `assign` (403).
4. Sticky: P handles and closes; customer returns within 30 days → P (STICKY); after 30 days → round robin; P Away →
   round robin, link kept. Customer handled by P replies to a campaign on A → P (STICKY).
5. Hand-offs: P reassigns own chat to R → R; to a non-member → 422. P hands back to Sales → round robin picks R, not
   P (`HANDED_BACK`, excluded P); with only P available → queue. P moves a support question to Support → Support's
   setting (queue) → S claims, replies from A; the number shows as guest for S; P no longer sees it; the thread shows
   "Transferred to Support by P"; the customer returns → S (sticky); S disabled → Sales' round robin.
6. Changing A's owner to Support hides A from Sales live and re-routes P's open chats on A per Support's setting;
   setting the first owner on a number whose legacy routing named that team creates the team override from it.
7. Settings: `PUT /inbox/settings` (brand default) and `/inbox/settings/teams/{id}` (override, "use default" deletes
   it) with `inbox:settings`; "one member" refused on the brand default and for a non-member (422).
8. Existing inbox scope, claim-on-reply, close/reopen and campaign tests still pass (adapted to the new endpoints).

## 7. Calls made by Claude (check these)
1. No team deletion.
2. Assignees restricted to number viewers; open chats re-routed when the owner changes or a member leaves.
3. Sticky also runs on the first reply to a campaign; skipped on hand-back.
4. Demo Fashion is a test fixture; the demo seed only gives the demo number to Demo Support.
5. Availability per user, not per brand; `ROUTED` never overwrites `lastAgentUserId`.
6. "One member" mode survives as an advanced team-override option.
7. Default brand setting = team queue, sticky 30 days; per-number settings migrate to a brand default seeded from the
   most common routing, legacy documents kept and used to seed a team override when that team becomes an owner.
8. Hand-off to a teammate doesn't check availability; hand-back excludes only the caller.
9. One SSE stream per viewer over the selected numbers, reconnecting on filter change; old inbox URLs redirect.
