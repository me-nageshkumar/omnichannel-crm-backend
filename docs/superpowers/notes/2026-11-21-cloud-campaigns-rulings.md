# Cloud campaigns: rulings and deferred minors

Calls made during execution (2026-11-21) and review findings left for later, from the two plan ledgers.

## Backend rulings

- Task 1: Ruling: CloudCampaignSenders.usedLast24h/dailyLimit/atLimit take the workspace id too (numbers of a WABA are looked up among the workspace's Cloud numbers; CloudAccounts has no by-WABA lookup) — signature differs from the plan — costs nothing if wrong (one more argument)
- Task 2: Ruling: a create without channel means WHATSAPP_WEB (plan said required) — the existing Web campaign tests create without it and must stay unedited; the console always sends it — costs nothing if wrong
- Task 2: Ruling: an unmapped Cloud blank with a contact field/attribute fill stores no mapping and takes the template's fill at send; errors on blanks are fields placeholders.<key> — matches spec §5.7 — a CSV audience relying on a contact fill gets the chat contact's value
- Task 2: Ruling: cloudTemplate.status in views is read live from the imported template (DELETED when gone) — spec §5.8 view has status — one lookup per listed Cloud campaign
- Task 3: Ruling: CampaignClaim carries the CampaignSender too (null on Cloud) — Web pacing needs its half-pace time, and a separate Web claim type would double the helpers — plan's record had no sender field; costs nothing if wrong
- Task 3: Ruling: RecipientClaims.sent() is the shared part of today's succeeded (named sent, not succeeded); enterPhone/leavePhone also refresh the counter as the old finally did — same behaviour
- Task 5: Ruling: the inbox maps TOKEN_REFUSED/RATE_LIMITED to NoDeviceException; it now keeps Meta's CloudSendException as the cause (initCause) and the campaign channel reads it — needed for the WABA/number holds and CLOUD_AUTH — other callers only read the code, unchanged
- Task 5: Ruling: TICK_BUDGET is wall time (System.nanoTime), not the app clock — the budget bounds real work per tick; tests use a fixed clock
- Task 5: Ruling: daily limit counted once per tick (used at loop start, minus each accepted send), not re-queried per send — one distinct query per tick instead of per message
- Task 5: Ruling: preferred-sender skips now cover Cloud campaigns (CloudCampaignSenders.mayBeWaitedFor: still a non-disabled Cloud number of the workspace) — Task 3 note; test disabledPreferredNumberSkipsItsRecipients
- Task 5: Ruling: test blankValueFailsRecipientNotCampaign uses a contact with a blank name instead of a CSV row with an empty cell — same path (PlaceholderValues blank → template fill → values.* refusal); dropped webCampaignOnTheSameTickUnchanged — Web behaviour pinned by the unchanged 169 campaign tests
- Task 6: Ruling: the checks live in a new CloudCampaignChecks component (not CloudCampaignChannel.checkMeta) — the lifecycle service and the runner both use them without depending on the channel — costs nothing if wrong
- Task 6: Ruling: red quality is tested by setting cloudAccounts.qualityRating directly; the webhook-to-stored-field path is MetaWebhookIT's — the campaign's reaction is what this task adds
- Task 6: Ruling: a blocked Cloud resume answers 409 CAMPAIGN_BLOCKED with the reason as message (new code beside CAMPAIGN_STATE) — the console can show the message as is
- Task 7: Ruling: a failed status fails a recipient only when its message carries a Meta template (cloudTemplateId) — WhatsApp Web messages never do, so Web recipients are untouched — no campaign lookup per status
- Final: Ruling: I4 index is not partial on cloudTemplateId — the query's $ne null would not let the planner use a partial index on $exists — a slightly larger index
- Final: Ruling: I5 fixed by serialising a WABA's numbers, not a shared counter — simplest correct; numbers of one account no longer send in parallel (known-limits says one after another anyway)
- Final: Ruling (declined): whether 131048 is Meta's tier-limit code — stays the WABA-hold code; if Meta reports overshoot as a failed status, those recipients fail and the 24 h count stops the next tick — cost: a few FAILED recipients at the limit edge
- Final: Ruling (declined): one RED number pauses the whole campaign — kept (spec §5.5 my call: a red number is a ban risk for the brand's official line)
- Final: Ruling (declined): in-memory holds on multi-instance — single instance today (known-limits)

## Console rulings

- Task 1: Ruling: CampaignView.channel/cloudTemplate/cloudSenders are optional fields — old replies and existing fixtures stay valid; channelOf() reads absent as WHATSAPP_WEB
- Task 2: Ruling: the step keeps its name "Senders" (plan said "Numbers") — existing tests and copy use Senders; renaming adds churn with no gain — cosmetic if wrong
- Task 2: Ruling: test helper fetchMock now prefers a route keyed with the query string — the Web and Cloud sender lists share a path
- Task 2: Ruling: the new wizard cases live in cloudCampaign.test.tsx; newCampaign.test.tsx changed only its walk helpers and two back() steps for the new order (plus an unused helper removed)
- Task 2: Ruling: segmentAudience.test.tsx walks reordered too (two walks), same reason
- Task 3: Ruling: a WhatsApp Web create request is unchanged (no channel, no cloudTemplate; plan said add channel: WHATSAPP_WEB) — the backend reads a missing channel as Web, and the existing draft-save test pins the old body — only Cloud requests carry the new fields
- Task 3: Ruling: a Meta template blank maps as a placeholder named by its key; its fill gives the default kind (contact field / attribute), else none (AGENT) so it must be mapped — phone-field fills default to the name field (minor)
- Task 3: Ruling: the template list is radio buttons with the reason as description; the header file uses PatternFly FileUpload and uploads to the first chosen number
- Task 4: Ruling: pause texts extended in CampaignsPage.systemPauseText (where the existing ones live), not a new campaignDisplay.pauseText; channelOf() from the api module, no separate channelLabel — reuse
- Task 4: Ruling: the Cloud review estimate uses the first chosen number's dailyLimit (numbers of one WhatsApp account share it); detail shows a Cloud API numbers table instead of Senders today; recipient errors were already shown as is
- Final: Ruling (declined): header upload needs inbox:send on the backend — a campaign manager without it cannot upload — backend permission design, flag to the user

## Fixed after the final reviews

- Final: fixed C1 header upload sends messaging_product whatsapp and shows messageFor(e) — mediaHeaderNeedsAFile (now asserts the product) RED→GREEN, suite 2207/2207 (24b4b32)
- Final: fixed I1 CAMPAIGN_BLOCKED shows the server's message — blockedResumeShowsTheServerReason RED→GREEN, suite 2207/2207
- Final: fixed I2 choosing another Meta template clears the mappings — switchingTemplateDropsItsMappings RED→GREEN, suite 2207/2207
- Final: fixed I3 defaultSource takes a Cloud blank's fill (contact field by attributeKey name/phone; attribute by key when not missing) — savedFillsBecomeTheDefaults RED→GREEN, suite 2207/2207
- Final: fixed I1 campaign-wide refusals — TEMPLATE_CODES {132001,132015,132016} → release+pause TEMPLATE_UNAVAILABLE; CAMPAIGN_CODES {131031,131042,132000,132012} and header refusal → release+pause SEND_REFUSED; breaker 3 identical refusals in a row → release+pause SEND_REFUSED — templatePausedRefusalPausesNotFails, accountRefusalPausesNotFails, repeatedRefusalTripsTheBreaker RED→GREEN, suite 3986/3986 (09137a5)
- Final: fixed I2 claims.sent moved out of the send try (a record failure leaves SENDING for failStuck) — failureToRecordAnAcceptedSendIsNotResent RED→GREEN, suite 3986/3986
- Final: fixed I3 MISSING_VALUE and Meta-refused recipients unset dispatchedAt (failedUnsent); OUTCOME_UNKNOWN keeps it — refusedRecipientIsNotRecentlyMessaged RED→GREEN, suite 3986/3986
- Final: fixed I4 index phone_timestamp {phoneNumberId:1,timestamp:-1} on waWebMessages; process counts the limit once (ready() without the limit) — the24HourCountHasAnIndex RED→GREEN, suite 3986/3986
- Final: fixed I5 one number per WhatsApp account sends at a time (CloudCampaignSenders.enterWaba/leaveWaba), so they share the daily limit — numbersOfOneWabaShareTheDailyLimit RED→GREEN, suite 3986/3986

## Deferred minors

- backend: Final: minor (deferred): M1 no "goes on at" time while a number is at its count-based limit
- backend: Final: minor (deferred): M2 a failed status arriving before SENT is recorded leaves the recipient SENT
- backend: Final: minor (deferred): M3 header upload cache not evicted on a media refusal (now pauses SEND_REFUSED, restart clears)
- backend: Final: minor (deferred): M4 terminate mid-loop can leave one PENDING row in a TERMINATED campaign
- backend: Final: minor (deferred): M5 headerMediaId not checked at save (first send pauses SEND_REFUSED)
- backend: Final: minor (deferred): M6 a MISSING_VALUE recipient's closed chat is reopened with no message
- backend: Final: minor (deferred): M7 beforeResume returns Problem(null, …) for a number that cannot send
- console: Final: minor (deferred): M1 changing numbers to another WhatsApp account keeps the old template in the draft (server refuses on Review)
- console: Final: minor (deferred): M2 Cloud field errors on Review: cloudTemplate.* shown twice; placeholders.<key> only in the banner
- console: Final: minor (deferred): M3 campaign list banner keeps the generic system-pause text for the new reasons (detail shows them)
- console: Final: minor (deferred): M4 Review estimate lacks "at N a day" and "if nothing else uses the daily limit"
- console: Final: minor (deferred): M5 detail Waiting column shows the limit wait on finished/draft campaigns too
- console: Final: minor (deferred): M6 live template status (PAUSED, DELETED) not shown beside the name on detail
- console: Final: minor (deferred): M7 header file name shown twice in the upload
