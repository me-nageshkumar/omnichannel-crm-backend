# One contact across two WhatsApp ids — backend and extension implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A person seen under a phone id and a hidden id is one contact: ids are joined on ingest, resolved through the extension, duplicates are merged, and "chat is new" means new person on the number.

**Architecture:** `Contact.waId` becomes `waIds` (startup migration). `ContactService` joins a lid to the phone's contact when the message carries the phone. A new page method `resolveIds` answers the counterpart id; `ContactIdResolver` asks it off the ingest thread and adds or merges. `ContactMergeService` moves every reference and publishes `ContactsMerged`, which `app.crm` handles for CRM records. `AutomationEventWriter` computes "new" per contact and number; `ChatView` gains `otherChats`.

**Tech Stack:** Java 21, Spring Boot 4.1, MongoDB (modules db/service/crm/app); the extension's page script (Node 24, `node --test`), rebuilt into the backend resources.

**Spec:** `docs/superpowers/specs/2026-11-04-one-contact-design.md` (binding; its "(my call)" items are decided).

## Global Constraints

- Backend worktree `omnichannel-crm-backend/.worktrees/one-contact` (branch `one-contact`, off main 709a4eb); extension worktree `whatsapp-web-extension/.worktrees/one-contact` (off main 342da41, `node_modules` linked). Tests: `./mvnw -q -pl omnichannel-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false` (`-am` always, also for `-pl omnichannel-service`); extension `npm test` from its worktree. Full `./mvnw -q verify` once, in the last task.
- Module rule (`CrmModuleBoundaryTest`, `ServiceBoundaryTest`): `omnichannel-crm` imports only `common`, `security.access`, `security.auth`; the merge reaches the CRM through the Spring event `ContactsMerged` handled in `omnichannel-app/.../app/crm/`. `omnichannel-service` never imports `omnichannel-crm`.
- `Contact.waIds: List<String>` (order learnt, first = creation id); unique sparse multikey index `ws_waIds` on `{workspaceId:1, waIds:1}`; the old `ws_waId` index is dropped by the migration. Collections camelCase; no other schema change.
- The resolver never runs on the ingest thread; one attempt per contact per hour; at most one device call per 300 ms per device; read-only RPC.
- Merge survivor: the contact with a phone, else the older `creationTime`. Nothing deleted but the empty duplicate row; every `contactId` reference in `waWebChats`, `contactNotes`, `campaignRecipients` re-pointed first; CRM records re-pointed by the app adapter, except a conflicting pair on one number (left, warned). Automation rows untouched.
- `isNew` = no inbound real message, other than this one, in any chat on this number whose `contactId` is this message's contact.
- Never send a message. Commits end with the trailer the session reminder names (`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` as of 2026-11-04).

## Plan calls (decisions the spec leaves to the builder)

- Plan call: the resolver runs on its own one-thread `ThreadPoolTaskExecutor` bean `contactResolverExecutor` (copy `CampaignSchedulerConfig`'s shape), with an in-memory `ConcurrentHashMap<String, Instant> lastAttempt` keyed by contact id and a per-device `Semaphore`/sleep for the 300 ms pacing; both are reset on restart (acceptable).
- Plan call: the `MessageUpserted` listener triggers resolution only for `inserted` inbound messages whose contact lacks a phone or has no `@lid` id, and only when `InboxDevices` reports the number's device ready; otherwise it does nothing (the next message retries).
- Plan call: `ContactsMerged(String workspaceId, String survivorId, String otherId)` is a record in `service.contact.ContactEvents`; `app.crm.ContactMergeAdapter` handles it with `@EventListener` and re-points `crmContacts` through a new `CrmContactService.repointContact(ws, otherId, survivorId)` that skips a record whose `(contactId=survivor, channel.phoneNumberId)` already exists and logs a warning.
- Plan call: `POST /contacts/resolve-ids` answers `202 { "queued": n }`; `n` counts contacts of the brand lacking a phone or a lid that have at least one chat on a number whose device is ready now.
- Plan call: `ChatView.otherChats` is computed in `InboxViews.chats(...)`/`chat(...)` from one query `findAllByPhoneNumberIdAndContactIdIn(...)` for the batch, so the chat list costs one extra query, not one per chat.

## Review Focus

1. A `@lid` message whose `senderPn` phone belongs to an existing contact must join that contact, not create a phoneless duplicate — Task 2 `lidWithPhoneJoinsTheExistingContact`.
2. A `@c.us` message from a person who already exists as a lid-only contact must, after the resolver answers, end as one contact with both ids and both chats pointing at it — Task 5 `phoneChatJoinsTheLidContactAfterResolution`.
3. A merge must re-point every chat, note, campaign recipient and CRM record, and a second merge of the same pair must be a no-op — Task 3 `mergeMovesEveryReference`, `mergeIsIdempotent`; Task 3 `crmRecordFollowsTheSurvivor`, `conflictingCrmRecordIsLeftAndWarned`.
4. A device that answers `resolveIds` with nothing (privacy) must leave the contact unchanged and not be asked again within the hour — Task 5 `unknownCounterpartLeavesTheContact`, `oneAttemptPerHour`.
5. The same person's second chat on a number must not be "new" for Automations, while another person's first chat is — Task 6 `secondChatOfTheSamePersonIsNotNew`, `anotherPersonIsNew`.

---

### Task 1: `waIds` on the contact, with the migration

**Files:**
- Modify: `omnichannel-db/.../db/contact/Contact.java` (replace `waId` with `List<String> waIds = new ArrayList<>()`; index annotations), `omnichannel-db/.../db/contact/ContactRepository.java` (`findByWorkspaceIdAndWaIdsContaining` replaces `findByWorkspaceIdAndWaId`), `omnichannel-service/.../service/contact/ContactView.java` (`List<String> waIds` replaces `waId`), `omnichannel-service/.../service/contact/ContactDemoSeeder.java:112-116`, `omnichannel-service/.../service/inbox/history/HistoryImportService.java:91-98` (all ids), every other `getWaId`/`setWaId` site the compiler names (`ContactService` is rewritten in Task 2; make it compile here with the minimal rename).
- Create: `omnichannel-db/.../db/config/ContactIdsMigration.java`.
- Test: create `omnichannel-app/src/test/.../app/contact/ContactIdsMigrationIT.java`; modify `ContactIndexesIT.java` (index names), `ContactDemoSeederIT.java` (waIds).

**Interfaces:**
- Produces: `Contact.getWaIds()`/`setWaIds(List<String>)`; `Optional<Contact> findByWorkspaceIdAndWaIdsContaining(String workspaceId, String waId)`; `ContactView.waIds`; JSON `waIds` on every contact view (console plan Task 1 consumes it).

- [ ] **Step 1: Write the failing tests.** `ContactIdsMigrationIT`: `oldWaIdBecomesWaIds` (insert a raw document with `waId` and no `waIds`, run `migration.migrate()`, read back `waIds == [id]` and no `waId` key); `migrationIsIdempotent` (second run changes nothing, count logged once); `oldUniqueIndexIsDropped` (create `ws_waId` by hand first, run, index list has `ws_waIds` and not `ws_waId`). `ContactIndexesIT`: the expected index list gains `ws_waIds` and loses `ws_waId`.
- [ ] **Step 2: Run** `-Dtest=ContactIdsMigrationIT,ContactIndexesIT` → FAIL.
- [ ] **Step 3: Implement.** `ContactIdsMigration implements SmartInitializingSingleton` (like `CollectionRenames`; order it after it with `@DependsOn` or by calling it from `CollectionRenames.afterSingletonsInstantiated` — pick one and say which): `updateMany({waId:{$exists:true}, waIds:{$exists:false}}, [{$set:{waIds:["$waId"]}}, {$unset:"waId"}])` then `dropIndex("ws_waId")` when present.
- [ ] **Step 4: Run** → PASS; also `-Dtest=ContactsApiIT,ContactDemoSeederIT,HistoryImportApiIT` → PASS.
- [ ] **Step 5: Commit** — `feat(contacts): a contact holds every WhatsApp id it was seen under`

### Task 2: Ingest joins a lid to the phone's contact

**Files:**
- Modify: `omnichannel-service/.../service/contact/ContactService.java` (`findForChat` :215-222, `linkLidToPhone` :224-246, `upsert` :247-282), `omnichannel-service/.../service/inbox/InboxIngestService.java` (no logic change expected; confirm the two `upsertFromChat` calls pass `lidPhone`).
- Test: create `omnichannel-app/src/test/.../app/inbox/ContactIdentityIT.java` (copy the setup of `WaWebInboxListenerIT`).

**Interfaces:**
- Consumes: Task 1.
- Produces: `ContactService.findForChat(ws, remote)` resolving `@c.us` by phone then `waIds`, `@lid` by `waIds`; `upsertFromChat(...)` joining a lid with a known `lidPhone` to the phone's contact; `linkLidToPhone(...)` merging a non-empty lid contact into the phone's contact through `ContactMergeService` (Task 3 — in this task, call a package-private hook `mergeInto(survivor, other)` that Task 3 fills; until then it does today's delete-when-empty and keeps a non-empty one, and the test for that case lives in Task 3).

- [ ] **Step 1: Write the failing tests** in `ContactIdentityIT`: `lidWithPhoneJoinsTheExistingContact` (contact exists with phone P and `waIds [P@c.us]`; inbound from `L@lid` with `senderPn P@c.us` → one contact, `waIds [P@c.us, L@lid]`, chat's `contactId` = that contact; no second contact); `lidWithoutPhoneCreatesAHiddenContact` (no `senderPn` → new contact, `waIds [L@lid]`, phone null); `secondMessageUnderTheLidFindsTheJoinedContact` (after the join, another `L@lid` message creates no contact); `phoneMessageFindsTheContactByWaIds` (a contact with `waIds [L@lid, P@c.us]` and phone null — phone taken elsewhere — is found by a `P@c.us` message through `waIds`).
- [ ] **Step 2: Run** `-Dtest=ContactIdentityIT` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; also `-Dtest=WaWebInboxListenerIT,InboxSendIT,NewChatIT,HistoryImportApiIT` → PASS.
- [ ] **Step 5: Commit** — `feat(contacts): a lid message with a phone joins the phone's contact`

### Task 3: Merging two contacts

**Files:**
- Create: `omnichannel-service/.../service/contact/ContactMergeService.java`, `omnichannel-service/.../service/contact/ContactEvents.java` (`record ContactsMerged(String workspaceId, String survivorId, String otherId)`), `omnichannel-app/.../app/crm/ContactMergeAdapter.java`.
- Modify: `omnichannel-db/.../db/inbox/WaWebChatRepository.java` (`List<WaWebChat> findAllByWorkspaceIdAndContactId`), `omnichannel-db/.../db/contact/ContactNoteRepository.java`, `omnichannel-db/.../db/campaign/CampaignRecipientRepository.java` (bulk re-point via `MongoTemplate.updateMulti` inside the service is fine; add repository methods only if the service needs reads), `omnichannel-crm/.../crm/contact/CrmContactService.java` (`repointContact(String ws, String otherId, String survivorId)`), `ContactService.linkLidToPhone` (use the merge for a non-empty lid contact).
- Test: create `omnichannel-app/src/test/.../app/contact/ContactMergeIT.java`; `omnichannel-app/src/test/.../app/crm/ContactMergeCrmIT.java`.

**Interfaces:**
- Produces: `Contact ContactMergeService.merge(String workspaceId, String survivorId, String otherId)` (returns the saved survivor; idempotent; publishes `ContactsMerged` once and `InboxEvents.ChatChanged` per re-pointed chat); `static String ContactMergeService.survivorOf(Contact a, Contact b)` (phone first, else older `creationTime`); `CrmContactService.repointContact(ws, otherId, survivorId)` returning the number of records moved.

- [ ] **Step 1: Write the failing tests.** `ContactMergeIT`: `mergeMovesEveryReference` (two contacts, each with a chat on the same number, a note, a campaign recipient; merge → every row's `contactId` is the survivor's, `waIds` union in order, labels union, blank attribute filled, `lastMessageAt` max, `doNotContact` kept, other contact gone, one `ContactsMerged` and two `ChatChanged` events); `survivorHasThePhoneElseIsOlder`; `mergeIsIdempotent`; `consoleFoldMergesANamedLidContact` (through `InboxSendService`'s path or `linkLidToPhone` directly: a lid contact with a name is merged into the phone's contact, not kept). `ContactMergeCrmIT`: `crmRecordFollowsTheSurvivor` (CRM contact on the other → `contactId` = survivor); `conflictingCrmRecordIsLeftAndWarned` (both have a CRM contact on the same number → the survivor's stays, the other's keeps the old id; `repointContact` returns 0 for it); `CrmModuleBoundaryTest` still green.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `-Dtest=CrmModuleBoundaryTest,ServiceBoundaryTest,InboxSendIT` → PASS.
- [ ] **Step 5: Commit** — `feat(contacts): merge two contacts that are one person`

### Task 4: Extension method `resolveIds`

**Files (extension worktree `whatsapp-web-extension/.worktrees/one-contact`):**
- Modify: `src/page/methods.js` (new method after `getNumberId`), `tests/methods.test.js` (append; extend `fakeWindow` with `getPhoneNumber`).
- Then rebuild the page script into the **backend worktree**: `OMNICHANNEL_BACKEND_DIR=/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel-crm-backend/.worktrees/one-contact npm run -s build` from the extension worktree; commit the regenerated `omnichannel-service/src/main/resources/wabridge/scripts/page.js` in the backend worktree.

**Interfaces:**
- Produces: page method `resolveIds({ id })` → `{ phone: string | null, lid: string | null }` (serialised wids `…@c.us` / `…@lid`); throws `WHATSAPP_NOT_READY` when `window.WWebJS` is absent, `PAGE_ERROR` when `id` is not a non-empty string ending in `@c.us` or `@lid`. Task 5 calls it with the contact's known id.

- [ ] **Step 1: Write the failing tests** (`tests/methods.test.js`): `resolveIds → lid for a phone id` (`getCurrentLid` answers → `{phone: 'P@c.us', lid: 'L@lid'}`, no existence query); `resolveIds → phone for a lid id` (`getPhoneNumber` answers); `resolveIds → runs the existence query once when the counterpart is missing, then asks again` (first lookup null, after `queryWidExists` second lookup answers; `created` shows one wid); `resolveIds → both null when WhatsApp knows nothing` (query returns no wid → `{phone, lid}` with the known side filled and the other null); `resolveIds → PAGE_ERROR on a bad id`; `resolveIds → WHATSAPP_NOT_READY without WWebJS`.
- [ ] **Step 2: Run** `npm test` → FAIL. **Step 3: Implement** mirroring `WWebJS.enforceLidAndPnRetrieval` (served `page.js` lines ~1230-1246) with `try/catch` around each lookup. **Step 4: Run** `npm test` → PASS.
- [ ] **Step 5: Commit** in the extension worktree — `feat(page): resolveIds answers the other id of a person`. Rebuild into the backend worktree and commit there — `chore(wabridge): page script with resolveIds`.

### Task 5: `ContactIdResolver`

**Files:**
- Create: `omnichannel-service/.../service/contact/ContactIdResolver.java`, `ContactResolverConfig.java` (executor bean), `omnichannel-service/.../service/contact/ContactResolveListener.java` (`@EventListener` on `InboxEvents.MessageUpserted`), `omnichannel-app/.../app/contact/ContactResolveController.java` (or a method on `ContactController`: `POST /resolve-ids`, `@PreAuthorize("@access.has(#workspaceId, 'contact:edit')")`, 202 `{queued}`).
- Modify: `omnichannel-common/.../config/OmnichannelProperties.java` (`Contacts contacts(Duration resolveRetry = 1h, Duration resolvePace = 300ms)` + yml defaults), `omnichannel-db/.../db/contact/ContactRepository.java` (a query for "lacks a phone or has no `@lid` in `waIds`" per workspace, streamed or paged).
- Test: create `omnichannel-app/src/test/.../app/contact/ContactIdResolverIT.java` with a fake device answering `resolveIds` (extend `app/support/TestDevices`/`FakeDevice` usage as `AutomationDevice` does for `getNumberId`; a small `ResolverDevice` test class in the same package is fine).

**Interfaces:**
- Consumes: Task 3 `ContactMergeService.merge`, Task 4 `resolveIds`.
- Produces: `CompletableFuture<Void> ContactIdResolver.resolve(String workspaceId, String phoneNumberId, String contactId)`; `int ContactIdResolver.queueBrand(String workspaceId)`; REST `POST /api/workspaces/{ws}/contacts/resolve-ids` → `202 {"queued": n}`.

- [ ] **Step 1: Write the failing tests** in `ContactIdResolverIT`: `phoneChatJoinsTheLidContactAfterResolution` (lid-only contact A with a chat; a `P@c.us` message creates contact B; the device answers `{phone P@c.us, lid L@lid}` → one contact with both ids, both chats point at it); `counterpartNobodyHoldsIsAdded` (device answers a lid nobody has → `waIds` gains it, no merge); `unknownCounterpartLeavesTheContact` (device answers nulls → unchanged); `oneAttemptPerHour` (two messages within the hour → one `resolveIds` request; with the clock moved an hour → a second); `notReadyDeviceSkips` (no device → no request, no error); `endpointQueuesUnresolvedContacts` (`POST /resolve-ids` as manager → 202 and `queued` equals the unresolved count with a chat on the ready device; a viewer → 403); `resolutionNeverRunsOnTheIngestThread` (assert the request arrives after `message_create` returned, e.g. the listener thread name starts with `contact-resolver-`).
- [ ] **Step 2: Run** `-Dtest=ContactIdResolverIT` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `-Dtest=ContactIdentityIT,ContactMergeIT,WaWebInboxListenerIT` → PASS.
- [ ] **Step 5: Commit** — `feat(contacts): resolve a person's other WhatsApp id through the extension`

### Task 6: "Chat is new" per person and number

**Files:**
- Modify: `omnichannel-service/.../service/automation/AutomationEventWriter.java:66`, `omnichannel-db/.../db/inbox/WaWebChatRepository.java` (`List<WaWebChat> findAllByPhoneNumberIdAndContactId`), `omnichannel-db/.../db/inbox/WaWebMessageRepository.java` (`boolean existsByChatIdInAndDirectionAndTypeInAndIdNot(Collection<String> chatIds, MessageDirection d, Collection<String> types, String id)`).
- Test: `omnichannel-app/src/test/.../app/automation/MessageEventIT.java` (append).

- [ ] **Step 1: Write the failing tests**: `secondChatOfTheSamePersonIsNotNew` (one contact with two chats on the number — e.g. a `@c.us` chat with a prior inbound message and a `@lid` chat created by a lid message with `senderPn` → the lid message's event has `chat.isNew == false`); `anotherPersonIsNew` (a different contact's first message → `true`); the existing `fetchedChatIsNotNew`/`firstRealMessageQueuesNewChatEvent` stay green.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `-Dtest=MessageEventIT,Automation*IT` → PASS.
- [ ] **Step 5: Commit** — `feat(automation): chat is new per person on the number`

### Task 7: `otherChats`, docs, full verify

**Files:**
- Modify: `omnichannel-service/.../service/inbox/ChatView.java` (component `List<OtherChat> otherChats` after `adClick`, with `record OtherChat(String id, String remoteId)` nested in `ChatView`; serialised `otherChats: [{id, remoteId}]`), `InboxViews.java:29-47` (one batch query), `omnichannel-db/.../db/inbox/WaWebChatRepository.java` (`findAllByPhoneNumberIdAndContactIdIn`), every `new ChatView(` site the compiler names; `README.md` (contacts section: one contact across ids, "Resolve hidden numbers"), `docs/superpowers/known-limits.md` (inbox list: replace the two-chats line with "two chats, one contact; merging the threads is a follow-on"; Automations list: drop "new twice" and add "a lid-only chat whose phone is not yet resolved can be new once more"), `docs/superpowers/crm-rulings.md` (one line per plan call above).
- Test: `omnichannel-app/src/test/.../app/inbox/AdClickApiIT.java` or a new `ChatViewIT` (append `otherChatsListThePersonsOtherChats` (id and remoteId of each), `noOtherChatsIsEmpty`).

- [ ] **Step 1: Write the failing tests.** **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Edit the three docs.**
- [ ] **Step 6: Run** `./mvnw -q verify` → all green (about 5 minutes).
- [ ] **Step 7: Commit** — `feat(inbox): a chat names the person's other chats; docs`
