# CRM: pending work, rules and how to run things

Rewritten short on 2026-11-03. The CRM port and the inbox work are merged and pushed: in all three repos `main`
matches GitHub and no branch is left unmerged. What is below is optional or waits on a decision; decisions already
made live in the specs, history in git.

**To resume:** start a session in `/Users/nagesh/Projects/WhatsAppWebJS/SCM` and say
> Read `omnichannel-crm-backend/docs/superpowers/crm-pending.md`, then let's do <item>.

## 1. Open work

### Features that need the user's decisions first

Both need a new host feature before the CRM side means anything. Run `superpowers:brainstorming` and raise the
questions, even though the user usually skips them.

- **Deal automation events.** The reference publishes `crm_deal_stage_changed`, `crm_deal_won`, `crm_deal_lost` to Kafka
  (`redlava-crm/.../automation/`; engine design `whatsapp/docs/superpowers/specs/2026-08-13-automation-event-action-engine-design.md`).
  Our app has no engine and no Kafka; stage changes are already `STAGE_CHANGED` activities (`crm/deal`). Add a
  `crm.platform` port (say `DealEventSink`) with an adapter in `app.crm`. An event must never fail the deal save.
  Ask: a general engine or a few fixed reactions? Which triggers and actions? Kafka or in-process? Who sets rules, where?
- **Lead conversion and lead channel defaults.** Reference `redlava-crm/.../leadconversion/`, `LeadChannelDefault`;
  intake design `whatsapp/docs/superpowers/specs/2026-08-05-facebook-leads-management-design.md` (historical, read its
  header). Our app has no lead intake; `OriginKind` already has `FACEBOOK_LEAD`, `WEBSITE_LEAD`, `CTWA_AD`, and
  `CaptureService.findOrCreate` takes an `Origin`. Ask: which sources first? Own screen? Does conversion create a deal?
  How is the number chosen when a brand has several?

### Waiting on the user

- **"CRM contacts reached"** on the Campaigns report follows the pipeline filter (default Sales), so a contact with no
  deal counts 0. Leave it, ignore the filter for this column, or relabel it.
- **5 lost senders.** Messages sent from the console or a campaign before the 2026-11-01 inbox reset and fetched again
  show "WhatsApp Web" as sender. Copying `source` and `sentBy*` back from the backup (section 4) by
  `(phoneNumberId, waMessageId)` would restore them.
- **Demo test data** left in brand Demo Fashion, removable on request: users `walk-nocrm`, `walk-segments`; roles
  `NO_CRM_WALK`, `AGENT_SEGMENTS_WALK` (they show in `demo-manager`'s role picker); pipeline "Walk check"; segment
  "Festive buyers"; deal "Acceptance test deal"; campaign "Walk send check".

### Small leftovers, pick up when working nearby

- Three tiny fixes in `docs/superpowers/crm-deferred-fixes.md`, under "From the review of the last fixes".
- Never checked in a browser, and no test: the segment editor's light theme, the audience popover by keyboard.
- Inbox, cleanup: delete the `call_log fields: [...]` log line and its flag in `InboxIngestService.importMessage`, and
  the `subtype` reads in `MessagePayloads.call` (a `call_log` has no subtype).
- Inbox, limits worth knowing:
  - The same person can be two chats (`@c.us` and `@lid`); a console send whose model never came back is stored as
    `console:<id>` and again under its real id.
  - The console shows poll votes but cannot vote; `interactive` messages lose their buttons; WhatsApp's own notices are
    not stored (each skipped one logs its subtype, the place to start). The extension still owes one reload for
    `vote_update` to become a durable event.
  - A dated fetch leaves out chats last active over a day before its range; an all-time fetch takes about 12 min
    because `chat-delay` follows every chat.
  - A device that stops answering with its socket open ends chats FAILED TIMEOUT instead of waiting.
  - A fetched chat's open/route decision: a crash mid-page loses `createdChatId` (stays Closed); a Stop still decides
    the chat in flight; a routing retry moves the round-robin cursor once.
  - An ALL import whose item insert fails partway is never re-listed. `HistoryImportViews` does N+1 lookups.
  - More detail and the pending real-number checks: spec `2026-10-31-chat-history-import-design.md`.

## 2. Rules the user set

- Merge, push and restart only when asked. `--no-ff` merges; push the feature branch with `main`. Pushed history is not
  rewritten (the fast-forwarded history import of 2026-10-31 stays as it is).
- Work in `.worktrees/<branch>` in each repo, never on `main`. One Maven build at a time per worktree.
- Do not wipe or re-seed the demo database without asking; take a `mongodump` first.
- **Never send a real message without the user naming the recipient.** Sending hours are Mon to Sat, 10:00 to 18:00 IST.
- The demo password is not recorded on purpose: ask the user. Do not repeat credential values.
- Do not change a shared role's permissions; use a separate test role and user, through the app's screens.
- Deleting records: ask, unless they are throwaways made in the same task.
- A history fetch syncs with WhatsApp Web **keeping the database's state**: stored messages keep sender, source and
  text; stored chats keep status, agent, team and unread count. Only status moves up and missing details fill in.
- "Skip the questions" is the standing choice for specs: flag your own calls in the spec (section 1's features excepted).
- Channel-neutral names for new things (`channelAccountId`, `ChannelKind`). Mongo collections camelCase. Files in
  GridFS, never S3. `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the host is reached
  through `crm.platform` ports with adapters in `app.crm`.
- Prettier only on touched files. Never bare `git stash`. Absolute paths.
- Test first. A fresh reviewer on the whole branch, then one fix pass; re-grade findings by effect on users.
- While fixing run only the tests that matter; the full `./mvnw -q verify` once, before a merge. Tests in 4 processes
  (`forkCount`, not Failsafe); a run peaks near 6 GB.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or as the session's reminder says).

## 3. Running things

```bash
B=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend
F=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend

# Backend tests, from the repo or worktree root: full run about 5 min. -am is needed, or stale ~/.m2 jars are used.
./mvnw -q verify
./mvnw -q -pl omnichannel-app -am test -Dtest=SomeIT -Dsurefire.failIfNoSpecifiedTests=false

# Console (a new worktree needs `npm ci --prefer-offline` first)
npx vitest run <path> --maxWorkers=4
npx tsc --noEmit && npm run lint && npm run build

# Deploy on 8089: stop the old process and wait for it to exit (up to 30 s), since the build replaces its jar
kill $(lsof -nP -iTCP:8089 -sTCP:LISTEN -t)
(cd "$F" && npm run -s build)
(cd "$B" && ./mvnw -q -Pwith-frontend -DskipTests -Dfrontend.dist="$F/dist" package)
(cd "$B" && nohup java -jar omnichannel-app/target/omnichannel-app-0.1.0-SNAPSHOT.jar --server.port=8089 \
  > /tmp/claude-501/app-8089.log 2>&1 &)

# Extension page script into the backend, from the extension's main checkout (writes into the sibling backend)
(cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web-extension && npm run -s build)
```

Java 21, Spring Boot 4.1, Node 24, React 18, PatternFly 6.5. In background Bash runs wrap with
`bash -c 'cd <dir> && ...'`. The page script is served by the backend and picked up by hash: a deploy updates it
without an extension reload.

**Demo data**, Mongo database `omnichannel_crm`: brand Demo Fashion `6ad7380562bdf17ec6a0a2ed`, customer
`6ad7380562bdf17ec6a0a2ec`, number Main line `6ad7380662bdf17ec6a0a2f5`, Sales pipeline
`seed-sales-6ad7380562bdf17ec6a0a2ed`. Users `demo-manager` (WORKSPACE_MANAGER), `demo-agent` (AGENT, Sales only),
`demo-lead` (AGENT), `demo-admin` (CUSTOMER_ADMIN), `nageshk` (Super Admin, not in the brand). The database holds real
WhatsApp messages: take care sharing it or a backup.

## 4. Where everything is

- **Specs** in `docs/superpowers/specs/` (CRM port `2026-10-23-crm-port-design.md` and its follow-ups, chat history
  import `2026-10-31-`, calls and poll votes `2026-11-01-`), plans in `docs/superpowers/plans/`,
  `crm-deferred-fixes.md` (small findings and outcomes), `crm-rulings.md` (calls made while building). `README.md` has
  the API and demo-seed sections.
- **Backup** from before the 2026-11-01 inbox reset:
  `/Users/nagesh/Projects/WhatsAppWebJS/SCM/backups/mongo/omnichannel_crm-20261101-1147-before-chat-reset`.
- **Reference RedLava CRM:** service `/Users/nagesh/RedLava/SCM/CRM/redlava-crm` (branch `crm-addon-phase-3`), screens
  `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm`, main app `/Users/nagesh/RedLava/SCM/CRM/whatsapp`.
  Check capabilities against the reference code, not its old deferred design.

## 5. Traps already hit

- **Console build:** `tsconfig` targets `es5` but tests run through esbuild. `class X extends Error` needs
  `Object.setPrototypeOf` (see `ApiError`); use `Array.from`, not `[...new Set()]`.
- **Page script:** read `messageKey` (`page/model.js`), never `m.id._serialized` (newer builds mangle it to undefined).
  The page sends the raw `getMessageModel` output, not the whatsapp-web.js `Message` class.
- **Page script built in a worktree** gets another hash. Build from a sibling folder: `git archive <branch>` into
  `SCM/.ext-build`, link the worktree's `node_modules`, run `OMNICHANNEL_BACKEND_DIR=<backend worktree> npm run -s build`,
  delete the folder. An extension worktree needs `.worktrees/whatsapp-web.js` linked to `../../whatsapp-web.js`, and
  that link must go before `git worktree remove`.
- **Test 404s** came from VS Code holding the test port on 127.0.0.1; test servers now bind 127.0.0.1 (`TestServerAddressIT`).
- **The app log holds NUL bytes:** use `grep -a`.
- **Browser automation:** `left_click_drag` does not trigger dnd-kit (use stepped PointerEvents); for 375 px width open a
  same-origin popup from a real click and `resizeTo` after every navigation; the tool hides token-like values, so read
  them from `mongosh`. A restart can sign the browser out (`localStorage` `omnichannel.session`). If Claude in Chrome
  reports itself disconnected, `list_connected_browsers` then a new tab brings it back. To force failures, wrap
  `window.fetch` and stay on the page.
- **Demo segments show 0 on purpose:** no deal has the gold tier and messages are recent.
