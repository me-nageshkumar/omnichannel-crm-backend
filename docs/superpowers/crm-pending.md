# CRM: pending work

Written 2026-10-31 and brought up to date on 2026-11-02, to pick the work up in a later session. The CRM port is
complete: phases A to F plus the follow-up fixes are merged and pushed, and so is the inbox work of 2026-11-01 (chat
history import with pause, play and stop; calls; poll votes; notices; business messages; contact names). Everything
below is optional or waits on a decision.

**To resume, start a new session in `/Users/nagesh/Projects/WhatsAppWebJS/SCM` and say:**
> Read `omnichannel-crm-backend/docs/superpowers/crm-pending.md`, then let's do <item>.

## 1. State on 2026-11-02

| | Backend | Console | Extension |
|---|---|---|---|
| Repo | `omnichannel-crm-backend` | `omnichannel-crm-frontend` | `whatsapp-web-extension` |
| Remote | `git@github.com:me-nageshkumar/omnichannel-crm-backend.git` | `git@github.com:me-nageshkumar/omnichannel-crm-frontend.git` | `git@github-yassg:me-nageshkumar/whatsapp-web-extension.git` |
| `main` | `b38b64d` | `ae24a7c` | `c002372` |
| Tests | `./mvnw -q verify` passes, 2073 tests, about 5 min | `npx vitest run` passes, 1444 tests; lint, `tsc` clean | `npm test` passes, 175 tests |

- Branch `message-types` in the backend and the console, in `.worktrees/message-types`, is done and not merged yet:
  contact cards, verification codes, channel invites and phone-only messages (section 2F). The test counts above are
  on it. Merging it needs the app rebuilt for the console to show them.
- Otherwise only `main`, plus `pending-update` on the backend, locally and on GitHub.
- The app runs on port 8089 from `main` (started 2026-11-02). The dev console on port 9000 may still
  run from the console's main checkout.
- The extension's page script is served by the backend and picked up by hash, so a backend deploy updates it without
  an extension reload. A reload is still owed once, for `vote_update` to become a durable event (section 2G).
- Nothing is half-finished.

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
- **Real data:** connecting the WhatsApp number brought real messages into the local database. Take care if sharing it
  or a backup.
- **Inbox reset, 2026-11-01:** at the user's request every chat, message, chat action and cached media file was deleted,
  then all chats were fetched again for 1 Oct to 1 Nov: 62 chats, 258 messages. Imported chats start CLOSED and
  unassigned, so 61 of them sit under the Closed tab until their contact writes (see 2H). Backup from just before:
  `/Users/nagesh/Projects/WhatsAppWebJS/SCM/backups/mongo/omnichannel_crm-20261101-1147-before-chat-reset`.
- **Backup:** the database from before the demo seed is at
  `/private/tmp/claude-501/-Users-nagesh-Projects-WhatsAppWebJS-SCM/6388a6d2-df07-4534-8c7a-ed25963e4ede/scratchpad/omnichannel_crm-before-seed.archive.gz`.
  That is a temp folder and may vanish. Pending work does not need it.

### F. Chat history import

- A contact with no name takes the name WhatsApp lists its chat under: the title, or for a business or a person not in the phone's contacts the verified name, then the profile name (`page/names.js`). It is stored as a name of record, so a later push name does not replace it. A contact with a name keeps it, and a name cleared by hand stays cleared. Seen on a real phone on 2026-11-01: 60 of 62 chats named, all 17 business chats by their verified name (for example "TATA AIG General Insurance Co. Ltd."). Only an all-chats fetch has these names; a single-chat fetch does not. An inbox already open shows new names after a reload: a fetch that stores no messages announces nothing. The number in a number-only title is not put on a `@lid` contact (the user said "Number hidden" is fine). Our own number's `@lid` chat is not filtered from `listChats`, so it could be named after us.
- Whether `chat.t` always covers a chat's newest message is unconfirmed: a chat it lags on by over a day is left out of a dated fetch. See the spec, section 5.
- On a real phone 5 chats stalled after one message with the cursor on 20 Oct 12:12, none of which was stored, so most likely a WhatsApp notice sent to many chats. `loadEarlierMsgs` is believed to hand back what is already loaded, which is inferred, not seen. The page now compares message ids, and `max-idle-pages` bounds a chat whatever the cause. The next dated fetch on the real phone took 81 s with no stall; those 5 chats were left out as inactive.
- A dated fetch leaves out chats last active over a day before its range (585 of 650 on the real phone). An all-time fetch skips nothing and still takes about 12 minutes: `chat-delay` (1 s) follows every chat, though most only read what is already in the browser. Skipping the pause for a chat that made no load would bring it near a minute.
- `interactive` business messages show their text and file, drawn by mimetype. Their buttons are not stored, so they do not show, and it is not known which fields carry them.
- Read on the real phone on 2026-11-02 (`Msg.get(id).serialize()` in the WhatsApp Web tab, which the user moved into the
  Claude tab group) and shown since:
  - `vcard` / `multi_vcard`: each card's name, numbers and emails; the preview is the name, or "2 contacts". A
    `multi_vcard` stores its `vcardList` cards one after the other as its body.
  - `biz_content_placeholder`: tagged `hsmTag` AUTHENTICATION, a verification code WhatsApp keeps off linked devices;
    `bizContentPlaceholderType` 0. Stored as `businessTag`; "A verification code only the phone can show".
  - `newsletter_follower_invite`: `newsletterFollowerInviteInfo.newsletterName` is the body; "An invitation to follow
    the channel “…”".
  - `unknown`: subtype `phone_only_feature`, a MARKETING template with an image, no text anywhere on the model, not
    even in `futureproofParams`. "A message only the phone can show".
  - The 6 rows stored before this lack the tag and the channel name; the next fetch of their chats fills them in.
    A fetch fills in only what is missing (`fillIn`), never replacing what is stored.
- `@c.us` / `@lid` duplicate-chat limitation (inherited): the same person can end up as two chats.
- `console:<id>` duplicate limitation (inherited).
- Pending real-number spike: see spec `2026-10-31-chat-history-import-design.md` §3, "Pending verification".
- A device that stops answering with its socket open ends each chat FAILED TIMEOUT after `max-timeouts-per-chat` rounds and the import COMPLETED instead of waiting (the counter now resets after every page that arrives, so only consecutive timeouts count). Pending refinement: probe the device before charging a timeout to the chat.
- The claim sort `(attemptedAt, _id)` has no dedicated index: `(importId, status, _id)` serves the claim filter and the PENDING items of one import are sorted in memory. The `(status, attemptedAt)` index serves `releaseStuck`, not the claim.
- `HistoryImportViews` does N+1 lookups (phone number per import, chat per item).
- Imported rows have `statusTimes` null; the console must tolerate it.
- A chat that ends with "Reached the oldest message WhatsApp Web would load" (`olderUnavailable`) shows it only in the chat's row on the imports page, not in the chat header. A fetch cannot tell the start of a chat from a load that came back empty: WhatsApp's flag stayed false at the true start. It costs `max-empty-pages` empty pages, about a second.
- Never read `m.id._serialized` from a live message model: use `messageKey` (`page/model.js`). Newer WhatsApp Web builds mangle it, and it reads as undefined rather than failing.
- An ALL import whose `listChats` item insert fails partway is never re-listed.

### G. Location, poll and call messages

Tested on a real phone on 2026-11-01: a shared location and a poll coming in, and a poll template sent from the console,
whose options show and can be tapped on the phone. Votes, the poll card and call lines are built (spec
`2026-11-01-calls-and-poll-votes-design.md`). Still open:
- Seen on the real phone: a vote on a console poll reaches the console ("Great: 1 vote"), at least through a fetch.
  Not seen: a live vote arriving while the thread is open, a location with a place name, a location template sent from
  the console.
- A `call_log` carries `isVideoCall`, `callDuration`, `callOutcome` and `callParticipants`, seen on the real phone; there
  is no `subtype`. The user confirmed the calls shown as video are video calls. Still to do: delete the
  `call_log fields: [...]` log line and its flag in `InboxIngestService.importMessage`, and the `subtype` reads in
  `MessagePayloads.call`. 5 incoming calls with no duration and not marked missed are probably rejected ones: the
  values of `callOutcome` other than missed are not known.
- `vote_update` joined `DURABLE_EVENTS`, which ships in the extension package: until the extension is reloaded a vote
  made while the backend is down is lost, though a later fetch recovers it.
- The console cannot vote on a poll, only show votes.
- WhatsApp's own notices (`notification_template` and the like) are no longer stored: they have no text, because the phone draws the words itself. Rows from before this show as "WhatsApp notice". Showing the real words would need a table of `subtype`s, and each skipped notice logs its subtype and field names, which is where to start.
- A first message arriving as `ciphertext` is dropped by the page (`events.js`), which is older than the notices work. A chat used to appear anyway when a notice came with it; now it appears with the next real message.
- Cosmetic: a location template joins name and address with ", " where whatsapp-web.js uses a line break, so the
  recipient may see both as the title.

### H. Open since 2026-11-01

- Decided and built 2026-11-02: a chat a fetch **creates** lands in Open and is routed like a new chat when it waits on
  us: unread on the phone (a chat marked unread by hand counts) or the contact wrote last, the contact wrote within 7
  days (`waiting-window`), and it is not a business (verified name, business or enterprise account). Else it stays
  Closed and unassigned. Decided once, when the chat's fetch ends DONE; a stopped or failed chat is not decided, and a
  chat that existed before the fetch is never touched. The "Routed" line says "fetched from WhatsApp, waiting for a
  reply". Opens nothing when the page does not say the chat is a person (a page script from before), nor when the
  fetch's "To" date is before it was asked for (it cannot see our later replies). Opens the chat the fetch created by
  its id: WhatsApp may keep a chat listed under a number under the person's lid.
  Left as they are (review of 2026-11-02):
  - "Business" includes anyone on the WhatsApp Business app (`contact.isBusiness`), small shops among them, so their
    chats stay Closed. Narrowing it to official accounts (`verifiedLevel` high, or enterprise) waits on the user.
  - A crash after a page created the chat and before its progress was saved loses `createdChatId`: that chat stays
    Closed (fails safe). Marking the chat itself at creation would make it exact.
  - A Stop lets the page already on its way finish; if it ends the chat DONE, the chat is still decided.
  - A routing retry after a collision has already moved the round-robin cursor once.
- Decided 2026-11-02: the history import that was fast-forwarded into `main` on 2026-10-31 (about 20 backend, 8 console
  and 3 extension commits with no merge commit) **stays as it is**. The user chose not to rewrite pushed history.
- The user's rule for a fetch (2026-11-02): it syncs the inbox with WhatsApp Web **keeping the database's state**. A
  stored message keeps its sender, source and text; only its status moves up and missing details are filled in. A
  stored chat keeps its status, agent, team and unread count. Pinned by `ImportMessageIT`
  (`aFetchKeepsWhoSentAStoredMessage`, `anAssignedChatStaysWithItsAgent`).
- Messages sent from the console or a campaign before the 2026-11-01 reset and fetched again since show "WhatsApp
  Web" as their sender: who sent them was on the deleted rows. Only the backup in section 2E still has it: of its 15
  rows sent from the console or a campaign, 5 were fetched again and lost their sender (counted 2026-11-02). Copying
  `source` and `sentBy*` back by `(phoneNumberId, waMessageId)` would restore them, if the user wants it.
- A full backend run in 4 processes peaks at about 6 GB; the user chose to keep 4 processes for now.
- Solved 2026-11-02, the "unexplained 404" of the tests: a test server bound its random port on every address while
  VS Code's helper held the same port on 127.0.0.1 alone, which macOS allows; the tests' calls to localhost reached
  VS Code, so a whole class (`InboxSendIT` that time, `HistoryImportRunnerEventsIT` before) got 404s. Test servers now
  listen on 127.0.0.1 (`server.address` in `application-test.yml`), pinned by `TestServerAddressIT`.

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
- While fixing, run only the tests that matter; run the full `./mvnw -q verify` once, right before a merge. The user found
  repeated full runs too slow.
- Keep the backend's tests in processes, not threads: `forkCount` in the pom, not Failsafe (the user's choice).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or as the session's reminder says).

## 4. Running things

```bash
B=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend
F=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend

# Backend tests (embedded Mongo), from the repo or worktree root. Runs in 4 processes (`forkCount` in the pom), about 5 min;
# run it once before a merge, and only the tests that matter while fixing. -DforkCount=1 runs one class at a time.
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

# Extension page script into the backend, from the extension's main checkout (it writes into the sibling backend)
(cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web-extension && npm run -s build)
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
- **Stopping the app takes up to 30 s** while a console holds its event stream open. Wait for the old process to exit
  before building: the build replaces the jar under it.
- **The page script built in a worktree** embeds the worktree's relative paths and gets another hash, so `main` would need
  a second commit. Build it from a sibling folder at the extension's depth instead: `git archive <branch>` into
  `SCM/.ext-build`, link the worktree's `node_modules`, run `OMNICHANNEL_BACKEND_DIR=<backend worktree> npm run -s build`,
  then delete the folder.
- **An extension worktree** needs `.worktrees/whatsapp-web.js` linked to `../../whatsapp-web.js`, as its sources import
  that repo by relative path. `.worktrees` is ignored there only through `.git/info/exclude`.
- **The app log holds NUL bytes**, so `grep` calls it binary and finds nothing: use `grep -a` or `tr -d '\000'`.
- **Claude in Chrome** sometimes reports itself disconnected; `list_connected_browsers` then a new tab brings it back.
