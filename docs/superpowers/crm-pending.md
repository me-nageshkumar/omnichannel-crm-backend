# CRM: pending work

Written 2026-10-31, to pick the work up in a later session. The CRM port is otherwise complete: phases A to F plus
the follow-up fixes are merged, pushed and running. Everything below is optional or waits on a decision.

**To resume, start a new session in `/Users/nagesh/Projects/WhatsAppWebJS/SCM` and say:**
> Read `omnichannel-crm-backend/docs/superpowers/crm-pending.md`, then let's do <item>.

## 1. State when this was written

| | Backend | Console |
|---|---|---|
| Repo | `omnichannel-crm-backend` | `omnichannel-crm-frontend` |
| Remote | `git@github.com:me-nageshkumar/omnichannel-crm-backend.git` | `git@github.com:me-nageshkumar/omnichannel-crm-frontend.git` |
| `main` | `04eba73` | `5f21ebf` |
| Tests | `./mvnw -q verify` passes, 1854 tests | `npx vitest run` passes, 1346 tests; lint, `tsc`, build clean |

- Both repos have only local `main`, in step with `origin/main`, no extra worktrees. Feature branches remain on GitHub.
- The app ran on port 8089 from `main`; it may not be running any more (see section 4 to start it).
- Nothing is half-finished. Every numbered step of the phase D, E and F walks was run in a real browser, apart from the
  side checks in section 2C.

## 2. What is pending

### A. New features, each needs design decisions from the user first

These are the only real functionality still missing. Both need a new **host** feature before the CRM side means
anything, so run `superpowers:brainstorming` first. The user usually says "skip the questions", but these two carry
real product choices, so raise the questions listed under each.

#### A1. Deal automation events

- **Reference:** `redlava-crm/src/main/java/com/redlava/crm/automation/` publishes `crm_deal_stage_changed`,
  `crm_deal_won` and `crm_deal_lost` to the main app's Kafka topic `automation-events`, keyed by the record's phone.
  `CrmDealAutomationEvents.stageChanged` never throws: the deal is already saved, so a failed event must not fail it.
  Payload: `CrmDealEventPayload`. The main app's list of events: `whatsapp/src/main/java/com/redlava/whatsapp/automation/enums/EventType.java`.
- **Engine design in the reference:** `whatsapp/docs/superpowers/specs/2026-08-13-automation-event-action-engine-design.md`
  (event, then tenant-set conditions on the JSON payload, then actions such as run a chatbot, send a message, set a
  label or attribute).
- **Our app:** no automation engine, no Kafka, nothing that listens. The CRM records every stage change as a
  `STAGE_CHANGED` activity already, and the writes happen in `crm/deal` (`DealService`, `Placement`, `DealPlacementImpl`).
- **Fits the module rule:** add a `crm.platform` port (for example `DealEventSink`) with an adapter in
  `omnichannel-app/.../app/crm`, so the CRM module stays free of host imports. It might go to a separate service later.
- **Questions:** is a general automation engine wanted, or just a few fixed reactions? Which triggers and actions come
  first? Kafka, or in-process events (the app is one deployable today)? Who sets rules, and in which screen?

#### A2. Lead conversion and lead channel defaults

- **Reference:** `redlava-crm/.../leadconversion/` (`LeadConversionServiceImpl`, `LeadConversionController`, the
  `POST /api/v1/crm/leads/{leadId}/convert`), `contact/LeadContactDetails.java`, `CrmContactServiceImpl.captureForLeadConversion`
  (no chat-assignment check, default or first accessible pipeline), and `settings/LeadChannelDefault.java` with
  `LeadChannelMatchType` (`FACEBOOK_PAGE`, `FACEBOOK_FORM`, ...; maps a lead source to the number it lands on).
- **Lead intake design in the reference:** `whatsapp/docs/superpowers/specs/2026-08-05-facebook-leads-management-design.md`.
  It is marked historical; read its header and `whatsapp/docs/facebook-leads/FRONTEND_IMPLEMENTATION.md`.
- **Our app:** no lead intake at all. `OriginKind` already has `FACEBOOK_LEAD`, `WEBSITE_LEAD` and `CTWA_AD`, kept for
  this (port spec section on what was left out). `CaptureService.findOrCreate` is the natural entry point, and it
  already takes an `Origin`.
- **Questions:** which lead sources first (Facebook, website form, click-to-WhatsApp ads, Instagram)? Are leads a
  host feature with their own screen? Does conversion create a deal? How is the number chosen when a brand has several?

### B. Tiny fixes, not worth doing alone

All three are in `docs/superpowers/crm-deferred-fixes.md`, under "From the review of the last fixes". Pick them up
when working nearby.

1. An edit removing a task's repeat rule in the exact moment a completion closes it still closes it, with the next
   occurrence stored (`TaskService.close`). Only a form opened in that split second can do it.
2. Step errors in the task form are `<small>` (`ChecklistEditor.tsx`), so a failed save does not scroll to them.
   Hardly reachable, given the length limit and blank filtering.
3. A company lookup failing just as the segment editor closes makes one stray read (`SegmentValueEditor.tsx`,
   `useCompanyNames`).

### C. Checks never run in a browser

| Check | Where | Automated test? |
|---|---|---|
| Light theme of the segment editor | Phase D walk, step 9 | No |
| Audience popover by keyboard | Phase D walk, step 9 | No |
| Chat refusal when opening a chat from a card or company | Phase F walk, step 6 | Yes |
| A real CSV import | Phase F walk, step 7 | Yes |
| "Add contact" hidden for `demo-agent` | CRM Contacts page | Yes |
| Job lock when the lease cannot be given back | `CrmJobLocks` | Yes (needs a broken database to see) |

Walk steps are in section 8 or 9 of each spec. Phone width is measured with a same-origin popup, see section 6.

### D. A judgement call for the user

"CRM contacts reached" on the Campaigns report card follows the pipeline filter, which defaults to Sales. A contact
with no deal therefore counts 0 there and 1 with all pipelines. It works as built (`CampaignsReport`), but it reads as
if nobody was reached. Options: leave it, ignore the pipeline filter for this column, or relabel it.

### E. Housekeeping, only if the user asks

- **Test data in the local demo database** (brand Demo Fashion), left in place:
  - Users `walk-nocrm`, `walk-segments`; roles `NO_CRM_WALK`, `AGENT_SEGMENTS_WALK`. The two roles show up in
    `demo-manager`'s role picker, so they are the ones most worth removing.
  - Pipeline "Walk check" with "Walk check deal" (on Ananya Patel), and its leftover timeline entries.
  - Segment "Festive buyers", deal "Acceptance test deal", two extra contacts on Deal 01.
  - Ananya Patel and Veenu as CRM contacts; completed campaign "Walk send check", one real message sent.
  - Agent tasks moved on by the walks: "Send the festive price list" is done, "Confirm the delivery date" is assigned
    to `demo-manager`, "Call about uniform sizes" is at occurrence 3.
- **Real data:** connecting the WhatsApp number brought real incoming messages into the local database. Take care if
  sharing it or a backup.
- **Backup:** the database from before the demo seed is at
  `/private/tmp/claude-501/-Users-nagesh-Projects-WhatsAppWebJS-SCM/6388a6d2-df07-4534-8c7a-ed25963e4ede/scratchpad/omnichannel_crm-before-seed.archive.gz`.
  That is a temp folder and may vanish. Pending work does not need it.
- **Remote branches:** feature branches are still on GitHub (the user deleted local ones only).

### F. Chat history import

- `@c.us` / `@lid` duplicate-chat limitation (inherited): the same person can end up as two chats.
- `console:<id>` duplicate limitation (inherited).
- Pending real-number spike: see spec `2026-10-31-chat-history-import-design.md` §3, "Pending verification".
- A device that stops answering with its socket open ends each chat FAILED TIMEOUT after `max-timeouts-per-chat` rounds and the import COMPLETED instead of waiting (the counter now resets after every page that arrives, so only consecutive timeouts count). Pending refinement: probe the device before charging a timeout to the chat.
- The claim sort `(attemptedAt, _id)` has no dedicated index: `(importId, status, _id)` serves the claim filter and the PENDING items of one import are sorted in memory. The `(status, attemptedAt)` index serves `releaseStuck`, not the claim.
- `HistoryImportViews` does N+1 lookups (phone number per import, chat per item).
- Imported rows have `statusTimes` null; the console must tolerate it.
- A chat that ends with "WhatsApp Web gave no older messages" (`olderUnavailable`) shows it only in the chat's row on the imports page, not in the chat header, and costs `max-empty-pages` retries (about 20 s) first. Whether older messages exist on the phone in that case is unknown.
- Never read `m.id._serialized` from a live message model: use `messageKey` (`page/model.js`). Newer WhatsApp Web builds mangle it, and it reads as undefined rather than failing.
- An ALL import whose `listChats` item insert fails partway is never re-listed.

### G. Location, poll and call messages

Tested on a real phone on 2026-11-01: a shared location and a poll coming in, and a poll template sent from the console,
whose options show and can be tapped on the phone. Votes, the poll card and call lines are built (spec
`2026-11-01-calls-and-poll-votes-design.md`). Still open:
- Not seen on a real phone: a live vote, a stored vote fetched from WhatsApp's votes table, a location with a place name,
  a location template sent from the console.
- Which fields a `call_log` really carries is unconfirmed: `subtype` and `duration` are published, the rest are guesses
  read defensively. The first call fetched after a start logs `call_log fields: [...]` (names only). Check it, correct
  `MessagePayloads.call`, then delete that log line and its flag in `InboxIngestService.importMessage`.
- `vote_update` joined `DURABLE_EVENTS`, which ships in the extension package: until the extension is reloaded a vote
  made while the backend is down is lost, though a later fetch recovers it.
- The console cannot vote on a poll, only show votes.
- Cosmetic: a location template joins name and address with ", " where whatsapp-web.js uses a line break, so the
  recipient may see both as the title.

## 3. Rules the user set

- Merge, push and restart only when asked. `--no-ff` merges; push the feature branch with `main`.
- Work in `.worktrees/<branch>` in each repo; never on `main` directly. One Maven build at a time per worktree.
- Do not wipe or re-seed the demo database without asking. `OMNICHANNEL_SEED_DEMO=true` was safe last time (each
  seeder skips what exists), but take a `mongodump` first.
- **Never send a real message without the user naming the recipient.** A campaign in a walk goes through the live
  WhatsApp number. Sending hours are Mon to Sat, 10:00 to 18:00 IST; changing them needs the user's say-so.
- The demo password is not recorded here on purpose. Ask the user; it is the same for all demo users, and only for
  this local app.
- Do not change a shared role's permissions; use a separate test role and user (the app's own screens, not Mongo).
- Deleting records: ask, unless they are throwaways made in the same task.
- "Skip the questions" is the user's standing choice for specs: flag your own calls in the spec. Section A above is
  the exception worth raising.
- Channel-neutral names for anything new: `channelAccountId`, `ChannelKind`, "channel contact". Existing
  `phoneNumberId` fields are not renamed. Mongo collections are camelCase. Files go to GridFS, never S3.
- `omnichannel-crm` imports only `common`, `security.access` and `security.auth`; the host is reached through
  `crm.platform` ports with adapters in `omnichannel-app` (`app.crm`), because it may become a separate service.
- Prettier only on touched files. Never bare `git stash`. Use absolute paths; the shell's directory drifts.
- Test first. A fresh reviewer on the whole branch, then one fix pass. Re-grade findings by effect on users.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or as the session's reminder says).

## 4. Running things

```bash
B=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend
F=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend

# Backend tests (embedded Mongo), from the repo or worktree root
./mvnw -q verify
./mvnw -q -pl omnichannel-app -am test -Dtest=SomeIT -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl omnichannel-app -am test '-Dtest=**/crm/**/*' -Dsurefire.failIfNoSpecifiedTests=false

# Console, from the repo or worktree root (a new worktree needs `npm ci --prefer-offline` first)
npx vitest run <path> --maxWorkers=4
npx tsc --noEmit && npm run lint && npm run build

# Deploy on 8089: stop the old process first, because the build replaces its jar
kill $(lsof -nP -iTCP:8089 -sTCP:LISTEN -t)
(cd "$F" && npm run -s build)
(cd "$B" && ./mvnw -q -Pwith-frontend -DskipTests -Dfrontend.dist="$F/dist" package)
(cd "$B" && nohup java -jar omnichannel-app/target/omnichannel-app-0.1.0-SNAPSHOT.jar --server.port=8089 \
  > /tmp/claude-501/app-8089.log 2>&1 &)
```

Tooling used: Java 21, Spring Boot 4.1, Node 24, React 18, PatternFly 6.5. In background Bash runs, wrap with
`bash -c 'cd <dir> && ...'`; a plain `cd` was lost once and the run silently did nothing.

**Demo data**, Mongo database `omnichannel_crm`:

| What | Value |
|---|---|
| Brand Demo Fashion, `workspaceId` | `6ad7380562bdf17ec6a0a2ed` |
| Customer | `6ad7380562bdf17ec6a0a2ec` |
| Sales pipeline | `seed-sales-6ad7380562bdf17ec6a0a2ed` |
| Number Main line | `6ad7380662bdf17ec6a0a2f5` |
| `demo-manager` | `6ad7380662bdf17ec6a0a2f2`, WORKSPACE_MANAGER, all pipelines |
| `demo-agent` | `6ad7380662bdf17ec6a0a2f4`, AGENT role, limited to Sales |
| `demo-lead` | AGENT role |
| `demo-admin` | CUSTOMER_ADMIN |
| `nageshk` | Super Admin, the only one who can create roles; not a member of the brand |
| Counts when written | 11 CRM contacts, 13 deals, 2 pipelines, 3 segments, 2 open tasks, 1 campaign |

## 5. Where everything is

- **This repo:** `docs/superpowers/specs/2026-10-23-crm-port-design.md` (scope and module), `-crm-reports-`,
  `-crm-segments-campaigns-`, `-crm-tasks-delegation-`, `-crm-ux-catch-up-design.md`; plans in `docs/superpowers/plans/`;
  `docs/superpowers/crm-deferred-fixes.md` (every small finding, with its outcome) and `crm-rulings.md` (calls made
  while building). `README.md` has the CRM API and demo data sections.
- **Reference RedLava CRM:** service `/Users/nagesh/RedLava/SCM/CRM/redlava-crm` (branch `crm-addon-phase-3`), screens
  `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm`, main app and specs
  `/Users/nagesh/RedLava/SCM/CRM/whatsapp`. Its backlog: `whatsapp/docs/superpowers/specs/2026-10-27-crm-pending-phases.md`.
  Check capabilities against the reference code, not the old deferred design.

## 6. Traps already hit

- **`tsconfig` target is `es5`, and tests run through esbuild,** so they do not see browser-build problems. `class X
  extends Error` needs `Object.setPrototypeOf` (see `ApiError`, and `client.build.test.ts`, which compiles the tsconfig
  way). `tsc` refuses `[...new Set()]`: use `Array.from`.
- **Browser automation:** `left_click_drag` does not trigger dnd-kit; use stepped PointerEvents. Coordinates go stale
  when the window changes, so prefer element references. The window cannot go below desktop width, and the app refuses
  iframes. For a true 375 px width, open a same-origin popup from a real click (`window.open(url, 'x',
  'popup=yes,width=375,height=780')`), and call `resizeTo` after every navigation. The tool's output filter hides
  token-like values and ids that look encoded; print them from `mongosh` instead.
- **Forcing failures:** wrap `window.fetch` in the page and stay on the same page, since a full navigation drops it.
- **Demo segments show 0 on purpose:** no deal has the gold tier here, and messages are recent.
- **A restart signs the browser out** at times; the session is in `localStorage` under `omnichannel.session`.
- **Stage history:** `Placement.retag` moves a deal's history into its new pipeline, so redaction works by stage id too.
