# CRM deferred fixes

Small findings from the final reviews of phases D to G, kept here because the ledgers are scratch files.
`[ ]` to fix, `[x]` fixed, `won't fix` with the reason.

## Phase D, backend

- [x] `SegmentSets.needsUniverse` is unused by the resolver (only its test calls it).
- [x] `SegmentScope.flag` says INACCESSIBLE for a deleted pipeline/stage even for a view-all user, and checks a stage against all accessible stages rather than the group's pipeline.
- won't fix: `SegmentScope` lists inactive pipelines while `SegmentCatalog` offers only active ones; a rule on an inactive pipeline resolves unflagged. *Tried and undone: excluding them made deactivating a pipeline silently shrink campaign audiences and blocked every save of the segment.*
- [x] `SegmentValidator.hasPipeline` is satisfied by any `deal.pipelineId` rule in the AND group, including `isNotSet`/`notIn`.
- [x] `SegmentService.nameKey` lower-cases with the default locale.
- [x] `SegmentService.delete` can answer 500 instead of 409 when the segment changes between read and delete.
- won't fix: `SegmentDates.instantStringCriteria` compares ISO strings lexicographically (zero fractions omitted by `Instant.toString()`); harmless at day boundaries. *Harmless at day boundaries, which is all it is used for.*
- [x] `CachedCounts.channelAccounts` sum to the loaded members (≤ 50,000) when truncated; document on the record.
- won't fix: `ActivityLog.append` has a `phoneNumberId` parameter (plan-sanctioned; it writes the phase A field). *Phase A field; existing names are not renamed (phase D C1).*
- won't fix: `CampaignActivityRecorder.recordSent` runs synchronously on the sender thread. *One insert on the sender thread; moving it off the thread risks losing timeline entries.*
- won't fix: `SegmentService.create` builds the scope twice (caller, then system). *The two scopes differ on purpose: the caller's validates, the system one gives brand-wide counts.*
- [x] `SegmentsApiIT.needsSegmentsAuthority` does not cover GET /{id}, PUT, DELETE, POST /{id}/preview for the agent.

## Phase D, console

- [x] `STAGE_HINT` wording wrong for an OR group that has a pipeline rule.
- [x] flagged option label shows the raw key; Save silently disabled while a flagged rule remains.
- [x] days input `min=0` while DATE day operators need 1–3650 on the server.
- [x] `return field ? null : null;` in `SegmentValueEditor` to use an otherwise unused prop.
- [x] preview card blank during the 600 ms debounce; `loading` can stick when the tree goes incomplete mid-flight.
- [x] preview may show an error above an older count for the same key.
- [x] popover trigger aria-label differs from its visible text; Edit/Send links carry no segment name.
- [x] a search with no match shows an empty table without a message.
- [x] `countsText` duplicated in SegmentsPage and SegmentPreview.
- won't fix: `key={i}` on builder rows (same as the contacts builder). *Same as the contacts builder, and rows are not reordered.*
- [x] segment option text omits "as of <time>".
- [x] inner `chosen` shadows the outer in ReviewStep.
- [x] `withRuleChanged` resets stage values only on a pipeline value change, not on operator change or removal.
- [x] the builder's root group is named "Filter rules".

## Phase E, backend

- [x] tick on a step removed by a concurrent save answers 400 TASK_NOT_OPEN instead of 404
- won't fix: catch-up loop in nextDue carries a DST-gap-shifted LocalTime into later steps (reference behaviour) *Same as the reference; only shifts a task inside a DST gap.*
- [x] bad enum values in a repeat rule (frequency, weekdays) get the framework's generic 400, not a field error
- [x] GET /tasks/delegated?status=foo says "is not a valid value" rather than "must be OPEN or DONE"
- [x] nextOccurrenceId is committed before the next occurrence is inserted (no transaction); a crash in between ends the series silently
- [x] no test exercises the version-guard race of two simultaneous completions
- won't fix: merging the two notice lists after capping each at 20 can return fewer than 20 entries *The finding is wrong: if either list hits the cap the union has at least 20, otherwise both are complete.*
- [x] if demo-manager is missing the freshly seeded tasks keep assignedBy = "system" and the agent sees nameless ASSIGNED notices

## Phase E, console

- [x] step error indexes count the filtered (non-blank) steps, the editor shows them by position
- [x] text typed in "Add a step" is dropped on Save without Enter/Add; Enter inside a "Step n" input submits the form
- [x] one busyId shared by all rows (a finished tick re-enables another in-flight row); Edit/Delete not disabled while a row is busy
- [x] on the Delegated tab the other tab counts still follow the hidden assignee picker
- [x] TASK_NOT_OPEN / NOT_RECURRING show "Could not update the task. Try again." and do not refresh the list; a failed delete says "update" and shows in the list, not the dialog
- [x] skip notice and action errors persist across tab switches and later actions until closed
- [x] a11y — weekday/ends groups have no group name, Steps label points at no element, step buttons share identical names, progress button lacks aria-controls
- [x] copy drift — visible "Times" / "Last date" / "Not done" labels; assignee shown twice on Delegated rows; done tasks get a Reopen link not a checkbox (phase A carry-over)
- [x] default weekday/dayOfMonth computed in the browser zone while the stored zone is kept (midnight edge)
- won't fix: a monthly series' day cannot be changed from the form while it stays monthly (spec C17) *Decided in spec C17.*
- won't fix: the plan's contract line "a task may appear once per kind" contradicts spec C20 (the server sends one entry per task; the console keys by taskId) *A note about a finished plan; the code follows the spec.*

## Phase F, backend

- [x] named() gives "Changed CRM pipelines of " for a person with a blank display name; should fall back to username like ref()
- [x] channel-details emits an attribute with a null value when the host stores one
- [x] contactNames calls the directory per contact on each contact-list save; CrmContactService.views already batches
- [x] crm.contact now imports crm.segment.ChannelAccounts for FIELD; the constant would sit better beside Channel
- [x] linkedContactIsListedForARestrictedUser uses a contact already in Sales, so it does not pin that linking alone carries the pipeline (verified by reading ContactCounters)
- [x] template round trips do not assert stored lifecycle, source, annual revenue, city or country; templateNeedsEdit covers contacts only

## Phase F, console

- [x] two identical "Search contacts" inputs on the create form
- [x] Download template shares the import's useAction, so it spins the Import button and clears its error
- [x] settings history shows no "System" byline for actor-less entries (the plan asked for it; none are written today)
- [x] the drag overlay holds a second Open chat button in the accessibility tree
- [x] the panel's pipelines and settings load once, so a stage added elsewhere needs a page reload
- [x] the company drawer keeps a chat refusal when moving to another company
- [x] channel-details loads even when the contact failed, and does not refetch on focus
- [x] no hint while the edit form's contacts are still loading
- won't fix: the console types label colours as LabelColor while the wire type is a string (safe: the host validates them) *Safe: the host validates colours.*

## Phase G

- [x] Add contact shows for a user with no number to chat from; hide it or say so before they search
- [x] A contact with a hidden number and no name is listed by its raw id
- [x] The Add contact search shows the first 50 matches with no hint to narrow it down
- [x] The "already in the CRM" notice comes back on a refresh, because it lives in history state
- [x] A failed Add contact search is shown twice, in the alert and the select
- [x] Leftover `CampaignSender` fixture in PlatformAdaptersIT that the adapter no longer reads
- [x] No direct `/contacts/add` test for a number in the brand that the caller cannot chat from
- won't fix: The server's `add` does not check `contact:view` itself *Not a hole: anyone with `crm:edit` can already create a contact by typing a phone.*

## Not small: also pending

- [x] Segment rules on companies show ids instead of names (console, `SegmentValueEditor`)
- [x] Job lock: CRM jobs run on every instance unless `omnichannel.crm.jobs.enabled=false`

## From the review of these fixes

- [x] Completing a repeating task inserts the next occurrence before closing the current one, so a conflict in that two-step window leaves the new occurrence beside the open one until a retry
- [x] One shared action under per-row busy in `useTaskActions`: a run on one row clears another row's error
- [x] A 404 on deleting a task keeps the dialog open with an error instead of removing the row
- [x] A failed company lookup in a segment rule shows "Unknown company" for a live company and is not retried
- [x] `CrmJobLocks`: a failure giving the lock back hides the job's own exception
- [x] A stage of another active pipeline is labelled missing rather than no access

## Found in the phase D and E browser walks, fixed

- [x] `ApiError` lost its prototype at tsconfig target es5, so every `instanceof ApiError` was false in production and every server error read "Something went wrong"
- [x] "1 CRM contacts" and "1 contacts" in segments and the campaign wizard
- [x] The repeat unit labels wrapped mid-word in the task form
- [x] The masthead made every page 464 px wide at phone width
- [x] A task form problem could sit out of sight at the edge of the scrolling form

## From the review of the last fixes

- [ ] An edit removing a task's repeat rule in the moment a completion is closing it still closes it with the occurrence already stored; only a form opened in that window can do it
- [ ] Step errors in the task form are `<small>`, so a failed save does not scroll to them; hardly reachable given the length limit
- [ ] A company lookup failing just as the editor closes makes one stray read

