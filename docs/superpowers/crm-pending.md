# CRM: pending work

Rewritten short on 2026-11-03; the rules, commands and traps moved to `project-rules.md` next to this file. The CRM
port and the inbox work are merged and pushed: in all three repos `main` matches GitHub and no branch is left
unmerged. What is below is optional or waits on a decision; decisions already made live in the specs, history in git.

**To resume:** start a session in `/Users/nagesh/Projects/WhatsAppWebJS/SCM` and say
> Read `omnichannel-crm-backend/docs/superpowers/crm-pending.md` and `project-rules.md`, then let's do <item>.

## Features that need the user's decisions first

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

## Waiting on the user

- **"CRM contacts reached"** on the Campaigns report follows the pipeline filter (default Sales), so a contact with no
  deal counts 0. Leave it, ignore the filter for this column, or relabel it.
- **5 lost senders.** Messages sent from the console or a campaign before the 2026-11-01 inbox reset and fetched again
  show "WhatsApp Web" as sender. Copying `source` and `sentBy*` back from the backup (`project-rules.md`, section 3) by
  `(phoneNumberId, waMessageId)` would restore them.
- **Demo test data** left in brand Demo Fashion, removable on request: users `walk-nocrm`, `walk-segments`; roles
  `NO_CRM_WALK`, `AGENT_SEGMENTS_WALK` (they show in `demo-manager`'s role picker); pipeline "Walk check"; segment
  "Festive buyers"; deal "Acceptance test deal"; campaign "Walk send check".

## Small leftovers, pick up when working nearby

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
