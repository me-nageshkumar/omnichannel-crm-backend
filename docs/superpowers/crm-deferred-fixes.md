# CRM deferred fixes

Small findings from the final reviews of phases D to G, kept here because the ledgers are scratch files.
`[ ]` to fix, `[x]` fixed, `won't fix` with the reason.

## Phase D, backend

- [x] `SegmentSets.needsUniverse` is unused by the resolver (only its test calls it).
- [x] `SegmentScope.flag` says INACCESSIBLE for a deleted pipeline/stage even for a view-all user, and checks a stage against all accessible stages rather than the group's pipeline.
- [x] `SegmentScope` lists inactive pipelines while `SegmentCatalog` offers only active ones; a rule on an inactive pipeline resolves unflagged.
- [x] `SegmentValidator.hasPipeline` is satisfied by any `deal.pipelineId` rule in the AND group, including `isNotSet`/`notIn`.
- [x] `SegmentService.nameKey` lower-cases with the default locale.
- [x] `SegmentService.delete` can answer 500 instead of 409 when the segment changes between read and delete.
- won't fix: `SegmentDates.instantStringCriteria` compares ISO strings lexicographically (zero fractions omitted by `Instant.toString()`); harmless at day boundaries. *Harmless at day boundaries, which is all it is used for.*
- [x] `CachedCounts.channelAccounts` sum to the loaded members (≤ 50,000) when truncated; document on the record.
- won't fix: `ActivityLog.append` has a `phoneNumberId` parameter (plan-sanctioned; it writes the phase A field). *Phase A field; existing names are not renamed (phase D C1).*
- won't fix: `CampaignActivityRecorder.recordSent` runs synchronously on the sender thread. *One insert on the sender thread; moving it off the thread risks losing timeline entries.*
- [ ] `SegmentService.create` builds the scope twice (caller, then system).
- [x] `SegmentsApiIT.needsSegmentsAuthority` does not cover GET /{id}, PUT, DELETE, POST /{id}/preview for the agent.

## Phase D, console

- [ ] `STAGE_HINT` wording wrong for an OR group that has a pipeline rule.
- [ ] flagged option label shows the raw key; Save silently disabled while a flagged rule remains.
- [ ] days input `min=0` while DATE day operators need 1–3650 on the server.
- [ ] `return field ? null : null;` in `SegmentValueEditor` to use an otherwise unused prop.
- [ ] preview card blank during the 600 ms debounce; `loading` can stick when the tree goes incomplete mid-flight.
- [ ] preview may show an error above an older count for the same key.
- [ ] popover trigger aria-label differs from its visible text; Edit/Send links carry no segment name.
- [ ] a search with no match shows an empty table without a message.
- [ ] `countsText` duplicated in SegmentsPage and SegmentPreview.
- won't fix: `key={i}` on builder rows (same as the contacts builder). *Same as the contacts builder, and rows are not reordered.*
- [ ] segment option text omits "as of <time>".
- [ ] inner `chosen` shadows the outer in ReviewStep.
- [ ] `withRuleChanged` resets stage values only on a pipeline value change, not on operator change or removal.
- [ ] the builder's root group is named "Filter rules".

## Phase E, backend

- [x] tick on a step removed by a concurrent save answers 400 TASK_NOT_OPEN instead of 404
- won't fix: catch-up loop in nextDue carries a DST-gap-shifted LocalTime into later steps (reference behaviour) *Same as the reference; only shifts a task inside a DST gap.*
- [x] bad enum values in a repeat rule (frequency, weekdays) get the framework's generic 400, not a field error
- [x] GET /tasks/delegated?status=foo says "is not a valid value" rather than "must be OPEN or DONE"
- [x] nextOccurrenceId is committed before the next occurrence is inserted (no transaction); a crash in between ends the series silently
- [x] no test exercises the version-guard race of two simultaneous completions
- [ ] merging the two notice lists after capping each at 20 can return fewer than 20 entries
- [x] if demo-manager is missing the freshly seeded tasks keep assignedBy = "system" and the agent sees nameless ASSIGNED notices

## Phase E, console

- [ ] step error indexes count the filtered (non-blank) steps, the editor shows them by position
- [ ] text typed in "Add a step" is dropped on Save without Enter/Add; Enter inside a "Step n" input submits the form
- [ ] one busyId shared by all rows (a finished tick re-enables another in-flight row); Edit/Delete not disabled while a row is busy
- [ ] on the Delegated tab the other tab counts still follow the hidden assignee picker
- [ ] TASK_NOT_OPEN / NOT_RECURRING show "Could not update the task. Try again." and do not refresh the list; a failed delete says "update" and shows in the list, not the dialog
- [ ] skip notice and action errors persist across tab switches and later actions until closed
- [ ] a11y — weekday/ends groups have no group name, Steps label points at no element, step buttons share identical names, progress button lacks aria-controls
- [ ] copy drift — visible "Times" / "Last date" / "Not done" labels; assignee shown twice on Delegated rows; done tasks get a Reopen link not a checkbox (phase A carry-over)
- [ ] default weekday/dayOfMonth computed in the browser zone while the stored zone is kept (midnight edge)
- won't fix: a monthly series' day cannot be changed from the form while it stays monthly (spec C17) *Decided in spec C17.*
- won't fix: the plan's contract line "a task may appear once per kind" contradicts spec C20 (the server sends one entry per task; the console keys by taskId) *A note about a finished plan; the code follows the spec.*

## Phase F, backend

- [ ] named() gives "Changed CRM pipelines of " for a person with a blank display name; should fall back to username like ref()
- [ ] channel-details emits an attribute with a null value when the host stores one
- [ ] contactNames calls the directory per contact on each contact-list save; CrmContactService.views already batches
- [ ] crm.contact now imports crm.segment.ChannelAccounts for FIELD; the constant would sit better beside Channel
- [ ] linkedContactIsListedForARestrictedUser uses a contact already in Sales, so it does not pin that linking alone carries the pipeline (verified by reading ContactCounters)
- [ ] template round trips do not assert stored lifecycle, source, annual revenue, city or country; templateNeedsEdit covers contacts only

## Phase F, console

- [ ] two identical "Search contacts" inputs on the create form
- [ ] Download template shares the import's useAction, so it spins the Import button and clears its error
- [ ] settings history shows no "System" byline for actor-less entries (the plan asked for it; none are written today)
- [ ] the drag overlay holds a second Open chat button in the accessibility tree
- [ ] the panel's pipelines and settings load once, so a stage added elsewhere needs a page reload
- [ ] the company drawer keeps a chat refusal when moving to another company
- [ ] channel-details loads even when the contact failed, and does not refetch on focus
- [ ] no hint while the edit form's contacts are still loading
- won't fix: the console types label colours as LabelColor while the wire type is a string (safe: the host validates them) *Safe: the host validates colours.*

## Phase G

- [ ] Add contact shows for a user with no number to chat from; hide it or say so before they search
- [ ] A contact with a hidden number and no name is listed by its raw id
- [ ] The Add contact search shows the first 50 matches with no hint to narrow it down
- [ ] The "already in the CRM" notice comes back on a refresh, because it lives in history state
- [ ] A failed Add contact search is shown twice, in the alert and the select
- [ ] Leftover `CampaignSender` fixture in PlatformAdaptersIT that the adapter no longer reads
- [ ] No direct `/contacts/add` test for a number in the brand that the caller cannot chat from
- won't fix: The server's `add` does not check `contact:view` itself *Not a hole: anyone with `crm:edit` can already create a contact by typing a phone.*

## Not small: also pending

- [ ] Segment rules on companies show ids instead of names (console, `SegmentValueEditor`)
- [ ] Job lock: CRM jobs run on every instance unless `omnichannel.crm.jobs.enabled=false`
