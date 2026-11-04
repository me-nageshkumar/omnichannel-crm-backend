# CRM rulings

Calls made while building phases D to F, copied from the plan ledgers, which are scratch files.

## Phase D, backend

- Task 3: Ruling: `Campaign.Audience.segmentId/segmentName` and `AudienceKind.SEGMENT` added in Task 3 instead of Task 7 — the adapter and the raw-document tests cannot map a SEGMENT audience without them; the host validator still refuses the kind until Task 7 — cost if wrong: none (Task 7 would add the same lines).
- Task 4: Ruling: `FieldSpec` gained a fifth component `fieldType` (the custom field's own `FieldType`, null for built-ins) — DATE custom values are stored as `YYYY-MM-DD` strings and DATETIME ones as ISO strings, so the query must know which to compare — cost if wrong: one record component nobody else reads.
- Task 4: Ruling: where negatives are complemented — contact-rule negatives run as Mongo negations inside the universe query (same result as a complement, no universe load); deal-rule negatives apply to the deal itself (spec C22, "has a deal where status is not WON"); channel-rule negatives are complemented within the universe inside `channelRule`; `needsUniverse` stays true for NOT groups and negative channel rules — cost if wrong: an extra universe load, never a wrong set.
- Task 7: Ruling: `SegmentCampaignIT` lives in `app.campaign` (not `app.crm`) — it extends the package-private `CampaignTestBase` for the REST client, templates, the auto-answering device and the tick; CRM setup is done through the REST API — cost if wrong: a file location.
- Task 7: Ruling: `CampaignObserver.CampaignSent` carries `channelAccountId` (the runner's `Claim.senderId()` is already the sender's number id), not `senderId` — the spec's naming rule (C1); the adapter only looks up the number's connection type for the kind — cost if wrong: one field name in an internal record.
- Task 7: Ruling: no `NoCampaignObserver` fallback bean — `omnichannel-service` has no Spring-context tests and the application module always provides the adapter — cost if wrong: a context failure in a hypothetical service-only boot, visible at once.
- Task 7: Ruling: `CampaignService.create` and `update` check `crm:view`+`crm:segments` for a SEGMENT audience through `SegmentAudiences.requireAccess` (403), not only `start` — a draft nobody may start is a trap; `update` and the controller's two update endpoints gained the `me` parameter for it — cost if wrong: a stricter 403 than the spec names, at creation time.
- Task 8: Ruling: `CrmDemoSeederIT.seedsTwoSegmentsWithCounts` asserts the "Quiet contacts" segment is counted, not that it has members — every demo host contact carries a last message within 30 days, so the spec's rule is legitimately empty on a fresh seed; the gold segment keeps its >= 1 — cost if wrong: a weaker demo assertion.
- Final: Ruling: `SegmentResolver.members()` now scopes its read by `workspaceId` (#4) without its own failing test — the ids it reads come only from brand-scoped sets, so no behavioural test can tell the two queries apart; the change is defence in depth for the phase-wide "every query scoped by workspaceId" rule — cost if wrong: none observable.
- Final: Ruling: preview `total` counts CRM contacts whose host contact is gone (#5) — `people` already shows the honest reachable figure and such records are rare (a deleted host contact); documenting rather than changing the count — cost if wrong: a marketer reads a slightly high record count.
- Final: Ruling: per-query 10 s budgets rather than one deadline per resolution (#6) — the spec's rule is per query and the 200,000-id cap bounds a rule to at most 20 pages; a resolution-level deadline is a reasonable later improvement — cost if wrong: a slow preview on a very large brand, never a wrong set.

## Phase D, console

- Ruling: the task scripts compute BASE/commit ranges from this backend repo, which do not apply to console code — every "Task N: complete" line below names the frontend commit range by hand, and test commands are run in the frontend worktree through `bash -c 'cd <frontend worktree> && …'` — cost if wrong: a bookkeeping mismatch, no code effect.
- Task 2: frontend commits 5837b5f..fe7d04c (segmentModel.test.ts 7, segmentRuleEditor.test.tsx 12; tsc, eslint clean). Ruling: date values use plain type="date" inputs like the contacts builder, not PF DatePicker — same look across both builders and testable with fireEvent — cost if wrong: a less rich picker.
- Task 4: frontend commits 5657cf0..10607a0 (segmentAudience.test.tsx 8, overview +1, timeline +1; suite 1175/1175; npm run lint, npm run build clean). Ruling: the campaign detail page shows no audience line today, so only the campaigns list says "Segment: <name>" — cost if wrong: one missing label on a page that never named its audience. Ruling: on Review, accounts that are chosen senders read "<label>: n contacts"; only the others carry the "landed on … not a chosen sender" warning — cost if wrong: wording.
- Final: Ruling: company rules show the company id in the typeahead and chips rather than the name (#4) — the rule works and saves correctly; resolving names needs a per-id lookup; left for a polish pass — cost if wrong: a marketer reopening a company rule sees an id instead of a name.

## Phase E, backend

- Task 1: Ruling: four phase-A tests (TasksApiIT.restrictedAgentCannotSeeTaskOnInaccessibleDeal, taskFollowsDealToAnotherPipeline; RemindersIT.remindersListsOnlyOwnUnseen, reminderOnHiddenDealNotListed) handed tasks to an agent with no pipeline access — now a 400 per spec C2; tests grant Sales first, then withdraw — cost if wrong: none, the spec requires the check
- Task 6: Ruling: seedTaskExtras treats assignedBy in {null, "system"} as 'not handed over yet' — Task 1 stamps the system actor on the seeder's own creates, so spec §7's 'assignedBy is still null' alone matched nothing — cost if wrong: a real hand-over by a user named system (there is none) would be overwritten
- Final: Ruling: embedded checklist ids are stored as `_id` inside the step document (Spring Data maps a property named id), the wire name stays `id` — spec §4 says `id`; renaming the Java field would touch every query — cost if wrong: a raw query on checklist.id finds nothing (mapped queries are fine)

## Phase E, console

- Ruling: console ledger lives in the frontend crm-e worktree (.superpowers/sdd/2026-10-31-crm-e-console), where the sdd-workspace script put it, not the backend worktree the plan named — same content; cost if wrong: none

## Phase F, backend

- Task 3: Ruling: CsvExportService rebuilds ContactSearchRequest per page, so it passes channelAccountId through (contactExportHonoursTheChannelAccountFilter) although the plan did not name it — otherwise an export with the filter would silently widen — cost if wrong: none
- Final: Ruling: deleting a hidden pipeline removes its stage ids from hiddenStageIds, so its within-pipeline moves become readable (stage names only, never the pipeline name) once the deals relocate into a visible pipeline — spec C19 accepts this limit, the pipeline no longer exists, and stamping stored entries would rewrite history outside this phase's "no migration" rule — cost if wrong: an agent reads stage names of a deleted pipeline

## Phase F, console

- Task 1: Ruling: the two no-drag/no-open tests live in a new dealCard.test.tsx with a pointer-down spy on the wrapper, not pipelines.test.tsx — jsdom has no pointer sensor set-up there and the card's contract is what matters — cost if wrong: none, the board's listener sits on that same wrapper
- Task 3: Ruling: crmChatPanel.test capturedShowsDealsAndLifecycleSaves asserted the stage as plain text; for crm:edit users it is now the select's value (plain text stays for viewers, covered by viewerSeesNoStageSelect) — cost if wrong: none

## Automations, backend

- Plan: Ruling: `chat.new` is computed by a query when the event is written, not stored as a marker
- Plan: Ruling: the real message types are `chat`, `image`, `video`, `audio`, `ptt`, `document`, `sticker`, `location`, `vcard`, `multi_vcard`, `poll_creation`; every other type starts nothing
- Plan: Ruling: the message event is written from an `@EventListener` on `InboxEvents.MessageUpserted`, not from a new inbox event
- Plan: Ruling: a message event's id is `m-<stored message id>`, a deal event's `"d-" + UUID`
- Plan: Ruling: the worker ticks every 2 s with a 5 min lease, 3 attempts, 20 events per tick, on its own `automationScheduler`; a send waits 60 s
- Plan: Ruling: a run document is inserted RUNNING before its actions run, and a re-run resumes it at the first step without an outcome
- Plan: Ruling: a transient send failure is retried (RETRYING, 2 min delay, 3 claims in all), and later actions wait with it
- Plan: Ruling: there is no cache of published rules; one indexed `exists` per incoming message gates the event, and while only a test waits it takes the test contact's events alone
- Plan: Ruling: only matching rules get a run; the history keeps `ruleIds` and `testIds`, and lists processed events only
- Plan: Ruling: an automation send skips the "chat must be OPEN" check and leaves a closed chat CLOSED
- Plan: Ruling: a placeholder is "still empty" at run time only when a required AGENT placeholder has no rule value
- Plan: Ruling: all three assign choices record a `ROUTED` action with `RoutedReason.AUTOMATION` and do not set `lastAgent*`
- Plan: Ruling: Add to CRM acts as `CrmAccess.forSystem` (user id `system`, origin `INBOUND_CHAT`)
- Plan: Ruling: the deal title pattern knows `{{name}}` and `{{phone}}`, an empty pattern meaning `{{name}} – WhatsApp`
- Plan: Ruling: publishing a rule with Add to CRM is refused while the brand's CRM is off
- Plan: Ruling: rules are listed in run order (priority largest first, then creation order), priority 0–9999 or null
- Plan: Ruling: conditions are a `ConditionNode` tree in the segment editor's JSON shape, at most 3 levels and 30 conditions
- Plan: Ruling: the editor's choices come from one `GET /automations/options` under `automation:view`
- Plan: Ruling: the test page polls `GET /tests/{id}` every 2 s, a poll counts as activity, and a test ends after 30 idle minutes
- Plan: Ruling: a typed test number is refused when that phone already has a real inbound message
- Plan: Ruling: the test preflight reuses `RulePublishValidator.problems` and adds a no-extension blocker
- Plan: Ruling: "Replay this event" reruns the captured event under a fresh run id and adds a run, not a history row
- Plan: Ruling: test runs are left out of the list's last-run, runs and failures counts and shown marked as tests
- Final: Ruling: `DealService.update` refuses a stage change inside the same pipeline (400), so the lane-change event's form path is the pipeline change only; `move` and `close` cover the rest
- Final: Ruling: a send is retried only when it never reached the extension (extension offline, WhatsApp Web not ready, or the request could not be handed over, up to three attempts); a send the extension got but did not answer in time, or that was cut by a disconnect, is marked Interrupted ("may have gone") and is never sent again — never messaging twice outranks the retry
- Final: Ruling: the `automationRuns` `event_rule` unique index is partial on `{test: false}`: one live run per (event, rule), test runs may repeat (replay); nothing is deployed so no migration, a database created from an earlier build of this branch needs the old index dropped once
- Final: Ruling: the event history's `ruleIds` lists live (non-test) runs only; the tests that captured an event are in `testIds`
- Final: Ruling: `RuleSummary.lastRunAt` is the latest live run of all time; `runs7d` and `failures7d` are the last 7 days
- Final: Ruling: a brand whose CRM is on but was never opened gets its default pipeline created when Automations first needs it (`ensureDefault`, as the CRM does on its first request)
- Final: Ruling: an automation assignment does not make the customer stick to the agent (no `lastAgent*`), like routing; the agent's first reply does
- Final: Ruling: while only a draft is under test, the queue takes the test contact's events alone
- Final: Ruling: the branch's commit trailer is `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` because project-rules.md defers to the session's attribution reminder

## Click-to-WhatsApp ads, backend

- Plan: Ruling: `AdClick` lives in `omnichannel-db` and its console view `AdClickView` in `omnichannel-service`, so the entity sits with the other inbox documents — cost if wrong: a file location.
- Plan: Ruling: `MessagePayloads.adClick` takes the message timestamp so the record carries `at` without a second parse — cost if wrong: one extra parameter on a private helper.
- Plan: Ruling: a missing chat on Add to CRM means no ad (origin stays `INBOUND_CHAT`), not a failure — cost if wrong: a lead without its origin label, never a failed capture.
- Plan: Ruling: `message.ad` is a boolean on every message event (false without an ad), while the headline and source id are set only when present, so "is empty" works — cost if wrong: a condition that never matches a plain message.
- Ruling: raw keys containing '.' or starting with '$' are dropped before storing, because Mongo refuses them — cost if wrong: a field of the raw copy missing, the parsed fields are kept.
- Ruling: the chat's ad is the earliest ad by message time, so an older ad message replaces a newer one on live ingest and history import — cost if wrong: the chat shows a later campaign than the one that brought the person in.
- Ruling: numeric JSON values count as text for the ad fields — cost if wrong: a numeric source id is stored as text, not dropped.
- Ruling: the API tests for the views live in `AdClickApiIT` rather than `AdClickIngestIT` — cost if wrong: a file location.


## One contact across two WhatsApp ids, backend

- Plan: Ruling: the resolver runs on its own one-thread executor bean `contactResolverExecutor`, with an in-memory last-attempt map per contact and a per-device 300 ms pacing; both reset on restart — cost if wrong: one extra attempt per contact after a restart.
- Plan: Ruling: the per-message trigger acts only on inserted inbound messages whose contact lacks a phone or an `@lid` id, and only when the number's device is ready; otherwise the next message retries — cost if wrong: a resolution waits for the next message or the manual button.
- Plan: Ruling: `ContactsMerged(workspaceId, survivorId, otherId)` is published by the merge and `app.crm.ContactMergeAdapter` re-points CRM records, skipping and warning on a duplicate `(contact, number)` — cost if wrong: a CRM record left on the deleted contact.
- Plan: Ruling: `POST /contacts/resolve-ids` answers `202 {queued: n}` where `n` counts contacts lacking a phone or a lid with a chat on a number whose device is ready now — cost if wrong: a count that differs from what the resolver finally does.
- Plan: Ruling: `ChatView.otherChats` comes from one batch query per distinct number in the list, not one per chat — cost if wrong: one extra query per number on a chat list.
