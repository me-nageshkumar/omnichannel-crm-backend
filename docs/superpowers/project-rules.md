# Project rules and how to run things

The user's standing rules for this project (backend, console and extension), the commands to test, build and deploy,
where things are, and traps already hit. Read sections 1 and 2 before any git, deploy or data work. Open work is in
`known-limits.md`, next to this file.

## 1. Rules the user set

- Merge, push and restart only when asked. `--no-ff` merges; push the feature branch with `main`. Pushed history is not
  rewritten (the fast-forwarded history import of 2026-10-31 stays as it is).
- Work in `.worktrees/<branch>` in each repo, never on `main`. One Maven build at a time per worktree.
- Do not wipe or re-seed the demo database without asking; take a `mongodump` first.
- **Never send a real message without the user naming the recipient.** Sending hours are Mon to Sat, 10:00 to 18:00 IST,
  for messages the app sends **first**. A chatbot or rule answering a message the user has just sent from the test
  number may be exercised at any hour (the user's choice, 2026-11-13); the user still names the recipient.
- The demo password is not recorded on purpose: ask the user. Do not repeat credential values.
- Do not change a shared role's permissions; use a separate test role and user, through the app's screens.
- Deleting records: ask, unless they are throwaways made in the same task.
- The walk test data in brand Demo Fashion stays (the user's choice, 2026-11-03): users `walk-nocrm`, `walk-segments`,
  roles `NO_CRM_WALK`, `AGENT_SEGMENTS_WALK`, pipeline "Walk check", segment "Festive buyers", deal "Acceptance test
  deal", campaign "Walk send check". Do not offer to remove it.
- A history fetch syncs with WhatsApp Web **keeping the database's state**: stored messages keep sender, source and
  text; stored chats keep status, agent, team and unread count. Only status moves up and missing details fill in;
  delivered/read/played times fill in from WhatsApp's receipts (2026-11-06).
- "Skip the questions" is the standing choice for specs: flag your own calls in the spec. The features in
  `known-limits.md` that need decisions are the exception.
- Channel-neutral names for new things (`channelAccountId`, `ChannelKind`). Mongo collections camelCase. Files in
  GridFS, never S3. `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the host is reached
  through `crm.platform` ports with adapters in `app.crm`.
- Prettier only on touched files. Never bare `git stash`. Absolute paths.
- Test first. A fresh reviewer on the whole branch, then one fix pass; re-grade findings by effect on users.
- While fixing run only the tests that matter; the full `./mvnw -q verify` once, before a merge. Tests in 4 processes
  (`forkCount`, not Failsafe); a run peaks near 6 GB.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or as the session's reminder says).

## 2. Running things

```bash
B=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend
F=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-frontend

# Backend tests, from the repo or worktree root: full run about 5 min. -am is needed, or stale ~/.m2 jars are used.
./mvnw -q verify
./mvnw -q -pl omnichannel-app -am test -Dtest=SomeIT -Dsurefire.failIfNoSpecifiedTests=false

# Console (a new worktree needs `npm ci --prefer-offline` first)
npx vitest run <path> --maxWorkers=4
npx tsc --noEmit && npm run lint && npm run build

# Settings in MongoDB (pre-production stop-gap, 2026-11-16): the `appConfig` collection, one document {_id: "app"},
# holds `secretsKeys` (the sealing keys for the brands' AI and MCP secrets, id:base64 list, first seals, all open) and
# `pagesBaseUrl` (the public origin WhatsApp fetches message-page previews from). They are read at startup; environment
# variables (OMNICHANNEL_SECRETS_KEYS, OMNICHANNEL_PAGES_BASE_URL) and command-line arguments still win. The first start
# with no key generates one (id g<date-time>) and stores it, unless sealed secrets already exist (then it warns and
# asks for the key that sealed them); a pages URL given as OMNICHANNEL_PAGES_BASE_URL is stored for later starts,
# so after a new tunnel just start once with it. Edit with mongosh and restart. The key now lives in the database and in
# every mongodump: never share a dump, and move the key out of the database before production. Never print the key.
# The `chat_created` index on waWebMessages is built at startup (a moment on a big collection).
# Deploy on 8089: stop the old process and wait for it to exit (up to 30 s), since the build replaces its jar
kill $(lsof -nP -iTCP:8089 -sTCP:LISTEN -t)
(cd "$F" && npm run -s build)
(cd "$B" && ./mvnw -q -Pwith-frontend -DskipTests -Dfrontend.dist="$F/dist" package)
# OMNICHANNEL_PAGES_BASE_URL is needed only when appConfig has no pagesBaseUrl yet or the tunnel changed (it is stored)
(cd "$B" && OMNICHANNEL_PAGES_BASE_URL=https://<tunnel-host> nohup java -jar omnichannel-app/target/omnichannel-app-0.1.0-SNAPSHOT.jar --server.port=8089 \
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

- **Leftover test databases.** Every test process starts embedded MongoDB servers and stops them when it exits; a
  process that is killed (a build stopped half way, a machine put to sleep) leaves them running. If builds turn slow,
  count them with `pgrep -f '/.embedmongo/' | wc -l`: with no build running the answer should be 0, and any that are
  there can be stopped with `pkill -f '/.embedmongo/'` (your own MongoDB is not under that path).

## 3. Where everything is

- **Specs** in `docs/superpowers/specs/` (CRM port `2026-10-23-crm-port-design.md` and its follow-ups, chat history
  import `2026-10-31-`, calls and poll votes `2026-11-01-`), plans in `docs/superpowers/plans/`,
  `crm-deferred-fixes.md` (small findings and outcomes), `crm-rulings.md` (calls made while building). `README.md` has
  the API and demo-seed sections.
- **WhatsApp Web events as Cloud webhooks:** the webhook model is in `omnichannel-common`
  (`common.channel.whatsapp.webhook`); the converter (`WaWebWebhookConverter`, `WaIds`) in `omnichannel-service`
  `service.channel.whatsapp`, with `WhatsAppWebhookProcessor` and `WaWebWebhookListener`; the store is
  `ChannelWebhookEventStore` (collection `channelWebhookEvents`). Spec `specs/2026-11-05-cloud-webhook-shape-design.md`,
  plan `plans/2026-11-05-cloud-webhook-shape-backend.md`, field evidence `whatsapp-web-storage-analysis.md`.
- **Advanced chatbot:** spec `specs/2026-11-13-advanced-chatbot-design.md` (rule-test addendum `2026-11-13-chatbot-in-rule-test-design.md`), plans `plans/2026-11-13-advanced-chatbot-backend.md`
  and `-console.md`. Code in `service.chatbot` (`flow` parser and settings, `node` executors, `run` runner, ticker and
  answer sources, `form` pages), documents in `db.chatbot`, API in `app.chatbot`; the automation action is
  `service.automation.StartChatbotHandler`. Tests in `app.chatbot` extend `AutomationTestBase` through `ChatbotTestBase`.
- **Subscription plans:** spec `specs/2026-11-17-subscription-plans-design.md` (three phases; 1 and 2 built), plans
  `plans/2026-11-17-subscriptions-1-entitlements-backend.md` and `-console.md`,
  `plans/2026-11-18-subscriptions-2-money-backend.md` and `-console.md`. Enums in `common.billing`, documents
  in `db.billing`, `Entitlements`/`Limits`/`SeedGroups` in `security.entitlement`, services in `service.billing`
  (`WorkGate` is the gate for work without a user), API in `app.billing` (`ReadOnlyInterceptor`). Money:
  `service.billing.money` (rounding, GST, proration, financial year, amount in words), `.pricing` (pure quotes:
  `OrderPricing`, `CancellationPricing`, `UnusedCredit`), `.order` (`OrderService`, `OrderActivator` and its
  repeatable `OrderSteps`, `StaffOrderService`, the per-customer `BillingLocks`), `.document` (invoices, credit
  notes, gapless `DocumentNumbers`, `DocumentPdf`, `BillingDocumentFiles`), `.refund`, and `BillingTicker`. Every
  change to a subscription's plan or balance goes through an order under the customer's lease; do not write to
  `subscriptions` any other way. Tests: `app.support.TestBilling` puts a customer on an unpaid plan;
  `MoneyApiTestBase` is the fixture of the money tests (a fixed clock at 1 Dec 2026, Gold and Platinum, two
  customers with billing profiles). A test that changes billing settings or groups must call `billing.reset()`
  when it ends.
- **Running on AWS cheaply** (nothing deployed yet): `aws-deployment.md` next to this file.
- **Backup** from before the 2026-11-01 inbox reset:
  `/Users/nagesh/Projects/WhatsAppWebJS/SCM/backups/mongo/omnichannel_crm-20261101-1147-before-chat-reset`.
- **Reference RedLava CRM:** service `/Users/nagesh/RedLava/SCM/CRM/redlava-crm` (branch `crm-addon-phase-3`), screens
  `/Users/nagesh/RedLava/SCM/CRM/whatsapp-frontend/src/Components/Crm`, main app `/Users/nagesh/RedLava/SCM/CRM/whatsapp`.
  Check capabilities against the reference code, not its old deferred design.

## 4. Traps already hit

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
- **Fake devices in tests:** never sleep after connecting a `FakeDevice`. The server handles "ready" on its own
  thread, so wait with `testDevices.awaitReady(number)` (or `awaitConnected` for a device kept not ready); a fixed
  200 ms sleep made `SendTemplateActionIT.whatsappRefusalFails` flaky under the parallel run (2026-11-14).
- **React Flow controlled nodes:** a card stays `visibility: hidden` until React Flow knows its size, and it forgets the
  size whenever the node object is replaced unless `measured` is handed back (`FlowCanvas` keeps the `dimensions`
  changes). Opening the editor with `?run=` broke this way on 2026-11-14: the run arrived right after mount.
