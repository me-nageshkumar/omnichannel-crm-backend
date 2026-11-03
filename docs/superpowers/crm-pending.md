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

## Known limits of the inbox, not tasks

The user chose (2026-11-03) to treat these as known limits to come back to later, like the features above. Each needs
a decision or more investigation.

- The same person can be two chats (`@c.us` and `@lid`); merging them is a design question.
- A console send whose model never came back is stored as `console:<id>` and again under its real id.
- The console shows poll votes but cannot vote.
- `interactive` business messages lose their buttons: which fields carry them is unknown.
- WhatsApp's own notices are not stored: showing their words needs a table of subtypes (each skipped one logs its
  subtype, the place to start).
- A dated fetch leaves out chats last active over a day before its range; whether `chat.t` always covers a chat's
  newest message is unconfirmed. Pending real-number checks: spec `2026-10-31-chat-history-import-design.md`.
