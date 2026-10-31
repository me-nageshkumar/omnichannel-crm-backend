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
