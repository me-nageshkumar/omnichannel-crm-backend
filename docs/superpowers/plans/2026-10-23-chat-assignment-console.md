# Chat Assignment (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the console the team-centred inbox: owning team per number, availability toggle, routing settings per brand and team, a cross-number inbox with a phone-number filter, Claim and Reassign actions, and the TEAM_LEAD role gone from fixtures.

**Architecture:** React 18 + PatternFly 6 in `omnichannel-crm-frontend` (worktree `.worktrees/chat-assignment`). API modules in `src/app/api/*.ts` change to the backend contract; the inbox page drops its per-number URL for `/inbox` + `/inbox/chats/:chatId` with a filter held in a small `inboxFilterStore`; `useChatList`/`useInboxStream` take a number set instead of one number. New pages: Settings → Inbox routing. Tests are vitest + Testing Library with `mockFetch` routes, next to the code.

**Tech Stack:** React 18, PatternFly 6.5 (`@patternfly/react-core`, `react-table`), react-router-dom 7, vitest + jsdom + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-23-chat-assignment-design.md` (backend repo). **Contract:** "Contract for the console" in `2026-10-23-chat-assignment-backend.md` — the JSON truth; where this plan and the contract disagree, the contract wins.

**Branch:** `chat-assignment` worktree at `omnichannel-crm-frontend/.worktrees/chat-assignment`. Run `npm test -- --run <file>`; `npm run lint`; `npm run build`.

## Global Constraints

- Follow PatternFly 6 components and tokens (`var(--pf-t--global--…)`) as the existing inbox does; light/dark/contrast come free from PF classes — no hard-coded colours.
- Every page works at phone width; the inbox falls back to list-or-thread (one pane) under 768px as today's layout does.
- Copy is sentence case; British spelling as in the app ("Unassigned", "Reassign", "Back to team").
- No new data-fetching library; `apiFetch` + `useLoad`/`useAction`.
- The removed endpoints (`/phone-numbers/{id}/chats|stream|settings`) must not be called anywhere after Task 4.
- Fixtures: `src/test/session.ts` loses `TEAM_LEAD`; `AGENT_IN_BRAND` gains `inbox:view`, `inbox:send`, `inbox:view-queue`.
- Commits: one per task, body ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

1. A user whose teams own **no number** opens `/inbox`: an empty state "No numbers assigned to your teams yet" (admins: a link to Phone numbers), no failing request loop (Task 4 test `showsEmptyStateWhenNoNumbersVisible`).
2. The **persisted filter** names a number that is no longer visible (owner changed): the filter must drop it silently and fall back to all (Task 4 test `staleFilterFallsBackToAll`).
3. A **guest number** appears in the filter list with a "via transfer" hint and never offers New chat on it (Task 4 test `guestNumberIsMarkedAndNotStartable`).
4. **Claim race**: the Claim button answers 409 `CHAT_TAKEN`; the header shows "Someone else took this chat" and refreshes (Task 5 test `claimTakenShowsNoticeAndRefreshes`).
5. **Availability toggle** fails (network): the switch must revert and show the error, not stay flipped (Task 2 test `availabilityRevertsOnError`).

---

### Task 1: Fixtures and API types for the new contract

**Files:**
- Modify: `src/test/session.ts` (remove `TEAM_LEAD`; `AGENT_IN_BRAND` authorities += `inbox:view`, `inbox:send`, `inbox:view-queue`; `WORKSPACE_MANAGER` += `inbox:view-queue`), every test that imported `TEAM_LEAD` (`routes.test.tsx`, `Contacts/*.test.tsx`, `Users/users.test.tsx`, `Teams/teams.test.tsx`, `api/authorities.test.ts`, `Campaigns/*.test.tsx`, `PhoneNumbers/phoneNumbers.test.tsx`) → `WORKSPACE_MANAGER`
- Modify: `src/app/api/inbox.ts`, `src/app/api/phoneNumbers.ts` (`PhoneNumber` +`team: IdName | null`, `guest?: boolean`), `src/app/api/teams.ts` (`Team` +`phoneNumbers: Array<{id; number; label: string|null}>`), `src/app/api/users.ts` (`CurrentUser` +`availability: {status: Availability; at: string|null}`; `setAvailability`)
- Test: `src/app/api/inbox.test.ts`, `src/app/api/users.test.ts` (new)

**Interfaces (api/inbox.ts):**
- `export type Availability = 'AVAILABLE' | 'AWAY'`
- `ActionType` += `'TRANSFERRED'`; `ActionView` += `reason: 'STICKY'|'HANDED_BACK'|'OWNER_CHANGED'|'MEMBER_REMOVED'|null`, `fromTeam: IdName | null`; `ChatView` += `lastAgent: IdName | null`.
- `export interface RoutingSetting { teamId: string|null; inherited: boolean; assignToTeam: boolean; roundRobin: boolean; userId: string|null; stickyDays: number; claimOnReply: boolean; keepAssignmentOnClose: boolean }` (replaces `InboxSettings`).
- `Assignees.users: Array<IdName & { username: string; availability: Availability }>`.
- `listChats(token, ws, filters: ChatFilters & { phoneNumberIds?: string[] })` → `GET /inbox/chats?phoneNumberIds=a,b&…` (omit the param when empty).
- `streamUrl(ws, phoneNumberIds: string[])` → `/inbox/stream?phoneNumberIds=…`.
- `claimChat(token, ws, chatId)`, `handoffChat(token, ws, chatId, body: {kind:'TO_USER'; userId} | {kind:'TO_TEAM'; teamId?: string})`, `getAssignees(token, ws, phoneNumberId)`.
- `getRoutingDefault(token, ws)`, `putRoutingDefault(token, ws, s)`, `listTeamRouting(token, ws)`, `putTeamRouting(token, ws, teamId, s)`, `deleteTeamRouting(token, ws, teamId)`.
- `api/phoneNumbers.ts`: `setNumberTeam(token, ws, id, teamId: string|null)` → `PUT /phone-numbers/{id}/team`.
- `api/users.ts`: `setAvailability(token, status)` → `PUT /api/users/me/availability`.
- Remove: `getInboxSettings`, `putInboxSettings`, `InboxSettings`, the `pn` parameter of `listChats`/`streamUrl`.

- [ ] **Step 1: Failing tests** `api/inbox.test.ts`: `listChatsJoinsPhoneNumberIds` (URL contains `phoneNumberIds=a%2Cb`), `listChatsOmitsEmptyFilter`, `streamUrlCarriesNumbers`, `handoffPostsKind`, `assigneesTakePhoneNumber`. `api/users.test.ts`: `setAvailabilityPuts`.
- [ ] **Step 2: Run** `npm test -- --run src/app/api` → FAIL. **Step 3: Implement**; update fixtures; `npm test -- --run` will now fail only in components still using the old API (expected until Tasks 3–6). **Step 4:** `npm run lint` clean. **Step 5: Commit** `Move inbox API types to the team-centred contract`.

### Task 2: Availability toggle in the masthead (§3.2)

**Files:**
- Create: `src/app/AppLayout/AvailabilitySwitch.tsx`, `src/app/AppLayout/availabilitySwitch.test.tsx`
- Modify: `src/app/AppLayout/AppLayout.tsx` (a `ToolbarItem` before `UserMenu`), `src/app/session/SessionContext.tsx` (`Session` +`setUser(user: CurrentUser)` or reuse `refreshUser`)

**Interfaces:**
- `AvailabilitySwitch`: PatternFly `Switch` labelled "Available" / "Away", `isChecked = user.availability.status === 'AVAILABLE'`; on change calls `setAvailability` then updates the session user with the response; on error reverts and shows a `Tooltip`/`Alert` with `messageFor(e)`. Hidden when `user.customer` is null (platform staff) or the user has no workspace with `inbox:view`.

- [ ] **Step 1: Failing tests**: `rendersCheckedWhenAvailable`, `togglePutsAwayAndUpdatesLabel`, `availabilityRevertsOnError`, `hiddenForPlatformStaff`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add the availability switch`.

### Task 3: Owning team on Phone numbers and Teams pages (§3.1)

**Files:**
- Modify: `src/app/PhoneNumbers/PhoneNumbersPage.tsx` (+column "Team", action "Set team…", warning icon + tooltip "No team — nobody but admins sees this number" when `team` is null), `src/app/Teams/TeamsPage.tsx` (+column "Numbers": `+91… , +44…` or "—"), `src/app/Inbox/InboxNumbersPage.tsx` (delete; replaced in Task 4)
- Create: `src/app/PhoneNumbers/TeamSelectModal.tsx` (FormSelect of `listTeams` + "No team", saves with `setNumberTeam`)
- Test: `src/app/PhoneNumbers/phoneNumbers.test.tsx`, `src/app/Teams/teams.test.tsx`

- [ ] **Step 1: Failing tests**: `showsTeamColumnAndWarningForUnowned`, `setTeamSavesAndRefreshes` (PUT body `{teamId:'t1'}`), `clearTeamSendsNull`; `teamsPageListsNumbers`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Show and set a number's owning team`.

### Task 4: Cross-number inbox with a phone-number filter (§3.7)

**Files:**
- Create: `src/app/Inbox/inboxFilterStore.ts` (+test), `src/app/Inbox/NumberFilter.tsx`, `src/app/Inbox/NumberChip.tsx`
- Modify: `src/app/routes.tsx` (`/inbox` → `InboxPage`; `/inbox/chats/:chatId` → `InboxPage`; `/inbox/:phoneNumberId` and `/inbox/:phoneNumberId/:chatId` → `<InboxRedirect />` that writes the number into the filter store and navigates to `/inbox` or `/inbox/chats/:chatId`), `src/app/Inbox/InboxPage.tsx`, `src/app/Inbox/ChatList.tsx` (row gets `<NumberChip>` when `numbers.length > 1`; header gets `<NumberFilter>`), `src/app/Inbox/useChatList.ts` (`useChatList(phoneNumberIds: string[], filters)`), `src/app/Inbox/useInboxStream.ts` (`useInboxStream(phoneNumberIds: string[], onEvent, onReconnect)` — reconnects when the set changes), `src/app/Inbox/NewChatModal.tsx` (+`numbers: PhoneNumber[]` prop; picker when more than one non-guest number; preselected when one), `src/app/Inbox/ChatHeader.tsx` (shows the chat's number: `shown(number.number)` + label)
- Test: `src/app/Inbox/inboxPage.test.tsx` (rewrite paths), `chatList.test.tsx`, `useInboxStream.test.tsx`, `newChatModal.test.tsx`, `routes.test.tsx`

**Interfaces:**
- `inboxFilterStore`: `read(userId, ws): string[]` (empty = all), `write(userId, ws, ids: string[])`, `clear(userId, ws)`; localStorage key `inbox.filter.<userId>.<ws>`; every access in try/catch.
- `NumberFilter` props `{ numbers: PhoneNumber[]; selected: string[]; onChange(ids: string[]): void }` — PatternFly `Select` with `isCheckbox`-style multi-select (`MenuToggle` text "All numbers" / "2 of 4 numbers"), each option `+<number> · <label>`, guest numbers suffixed with a `Label isCompact` "via transfer".
- `InboxPage`: `numbers = useLoad(listInboxNumbers)`; `selected = read(...) ∩ numbers.ids` (stale ids dropped → written back); `list = useChatList(selected, filters)`; `useInboxStream(selected, …)`; `select(chat)` → `/inbox/chats/${id}`; `leave()` → `/inbox`; `numberConnected` from the open chat's number; empty state when `numbers.data.length === 0`; New chat only when at least one non-guest number is `CONNECTED`-capable (as today's `numberConnected` rule, per picked number).
- Removed UI: "Change number" link and `InboxNumbersPage`.

- [ ] **Step 1: Failing tests** `inboxFilterStore.test.ts`: round trip, bad JSON → `[]`. `inboxPage.test.tsx`: `listsChatsAcrossAllVisibleNumbersWithChips` (GET `/inbox/chats` without `phoneNumberIds`; rows show chips), `filterToOneNumberRefetchesAndReconnectsStream` (second GET has `phoneNumberIds=p2`; stream URL changes), `staleFilterFallsBackToAll`, `showsEmptyStateWhenNoNumbersVisible`, `guestNumberIsMarkedAndNotStartable`, `oldUrlRedirectsToFilteredInbox` (`/inbox/p1/c1` → location `/inbox/chats/c1`, filter `[p1]`), plus the existing behaviours (open chat, mark read, visible:false notice, reconnect catch-up, new chat) re-pointed at the new URLs. `newChatModal.test.tsx`: `pickerShownForTwoNumbersAndPreselectedForOne`.
- [ ] **Step 2–5:** FAIL → implement → `npm test -- --run src/app/Inbox src/app/routes.test.tsx` PASS → commit `Make the inbox span all visible numbers with a phone-number filter`.

### Task 5: Claim, Reassign and the Unassigned tab (§3.5, §3.6)

**Files:**
- Create: `src/app/Inbox/ReassignMenu.tsx` (+test)
- Modify: `src/app/Inbox/ChatHeader.tsx`, `src/app/Inbox/AssignMenu.tsx` (`getAssignees(token, ws, chat.phoneNumberId)`; status dot per user: `●` green AVAILABLE / grey AWAY via `Label` variant), `src/app/Inbox/chatDisplay.ts` (`allowedScopes`: `UNASSIGNED` when `inbox:view-queue`; `SCOPE_TEXT.UNASSIGNED = 'Unassigned'`), `src/app/Inbox/Thread.tsx` (action lines: `TRANSFERRED` → "Transferred from {fromTeam} to {toTeam} by {by}"; `ROUTED` with `reason` → "Routed to {to} (returning customer)" / "(handed back by {excluded})" / "(number changed team)" / "(left the team)")
- Test: `chatHeader.test.tsx` (new), `inboxPage.test.tsx`, `thread.test.tsx`

**Interfaces:**
- `ChatHeader`: when `!chat.assignedUser && access.hasInWorkspace('inbox:send')` → `Button "Claim"` → `claimChat`; 409 → notice "Someone else took this chat" and `onChange(await getChat(...))`. When `chat.assignedUser?.id === user.id` (or `inbox:assign`) → `<ReassignMenu>`; managers additionally keep `<AssignMenu>`.
- `ReassignMenu` props `{ chat; onChange; onError }`: dropdown "Reassign" with items "Teammate…" (flyout from `getAssignees(ws, chat.phoneNumberId).users` minus me, with availability dots), "Back to team" (`handoffChat(..., {kind:'TO_TEAM'})`, disabled when `!chat.assignedTeam`), "Another team…" (flyout of `assignees.teams` minus current → `{kind:'TO_TEAM', teamId}`).

- [ ] **Step 1: Failing tests**: `claimButtonForUnassignedChatWithSend`, `claimTakenShowsNoticeAndRefreshes`, `reassignMenuOnlyForAssigneeOrManager`, `teammateHandoffPostsToUser`, `backToTeamPostsToTeamWithoutId`, `anotherTeamPostsTeamId`, `unassignedTabShownWithViewQueue`, `threadRendersTransferredAndRoutedReasons`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `Add Claim and Reassign to the chat header`.

### Task 6: Settings → Inbox routing page (§3.4)

**Files:**
- Create: `src/app/Inbox/RoutingSettingsPage.tsx`, `src/app/Inbox/RoutingSettingsCard.tsx`, `src/app/Inbox/routingSettings.test.tsx`
- Delete: `src/app/Inbox/RoutingSettingsModal.tsx`
- Modify: `src/app/routes.tsx` (`NAV_SECTIONS`: `Inbox` becomes an expandable group `{ label: 'Inbox', links: [{label:'Inbox', path:'/inbox'}, {label:'Routing', path:'/inbox/routing'}] }`; route `/inbox/routing` requires `inbox:settings`)

**Interfaces:**
- `RoutingSettingsCard` props `{ title: string; setting: RoutingSetting; members?: TeamMember[]; onSave(s: RoutingSetting): Promise<void>; onUseDefault?(): Promise<void> }`: PatternFly `Card` with switches "Assign incoming chats to the team", "Round robin among available members" (disabled unless the first is on; turning it on clears `userId`), an `ExpandableSection` "Advanced" holding "Always assign to one member" (`FormSelect` of `members`, only when `members` given, i.e. team cards), `NumberInput` "Sticky window (days)" 0–365 with helper "0 turns it off", switches "Claim on reply" and "Keep assignment on close"; Save button; for team cards a `Label` "Using brand default" when `inherited` and a link-button "Override" that enables the form / "Use brand default" that calls `onUseDefault`.
- `RoutingSettingsPage`: `PageShell` titled "Inbox routing"; first card "Brand default" from `getRoutingDefault`; then one card per team from `listTeamRouting` joined with `listTeams` for names/members.

- [ ] **Step 1: Failing tests**: `rendersDefaultAndTeamCards`, `roundRobinSwitchDisabledUntilAssignToTeam`, `savingDefaultPutsSwitchesMappedToBody`, `overrideThenUseDefaultDeletes`, `oneMemberOnlyOnTeamCards`, `navShowsRoutingUnderInboxForSettingsHolders`.
- [ ] **Step 2–5:** FAIL → implement → PASS → `npm run lint && npm run build` → commit `Add the inbox routing settings page`.

### Task 7: Sweep and build

- [ ] **Step 1:** `grep -rn "phone-numbers/\${\|/settings\`\|TEAM_LEAD\|InboxNumbersPage\|RoutingSettingsModal" src` → nothing left.
- [ ] **Step 2:** `npm test -- --run` green; `npm run lint` clean; `npm run build`.
- [ ] **Step 3:** Rebuild the jar from the backend worktree with `OMNICHANNEL_BACKEND_DIR` pointing at it (memory: `npm run build` → `./mvnw -q -Pwith-frontend -DskipTests package`), run on 8089 with `OMNICHANNEL_SEED_DEMO=true`, and walk spec §6 steps 1, 2, 5 by hand: demo-agent sees only Demo Support's number, claims from the queue, reassigns; demo-manager sets the routing override.
- [ ] **Step 4: Commit** `Finish the team-centred inbox console`.
