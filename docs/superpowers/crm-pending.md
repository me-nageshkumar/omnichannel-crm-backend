# CRM: pending work

Rewritten short on 2026-11-03; the rules, commands and traps moved to `project-rules.md` next to this file. The CRM
port and the inbox work are merged and pushed: in all three repos `main` matches GitHub and no branch is left
unmerged. What is below is optional or waits on a decision; decisions already made live in the specs, history in git.

**To resume:** start a session in `/Users/nagesh/Projects/WhatsAppWebJS/SCM` and say
> Read `omnichannel-crm-backend/docs/superpowers/crm-pending.md` and `project-rules.md`, then let's do <item>.

## Parked features

- **Facebook Lead Ads conversion.** Parked by the user on 2026-11-04: there is no Facebook (Meta) app yet, so no lead
  intake, webhook or page connection can be built. Reference when the time comes: `redlava-crm/.../leadconversion/`,
  `LeadChannelDefault`, and the historical intake design
  `whatsapp/docs/superpowers/specs/2026-08-05-facebook-leads-management-design.md` (read its header). Our side already
  has `OriginKind.FACEBOOK_LEAD` / `WEBSITE_LEAD` and `CaptureService.findOrCreate(…, Origin, …)`. Click-to-WhatsApp ad
  leads shipped on 2026-11-04 (spec `specs/2026-11-04-ctwa-leads-design.md`) and need no Meta app. Website-form leads
  would need the same decisions (a leads collection, intake, a screen, the number choice) and are not asked for.

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

## Automations: done

Events, rules and actions (backend) are built: spec `docs/superpowers/specs/2026-11-03-automations-design.md`, plan
`docs/superpowers/plans/2026-11-03-automations-backend.md`. Replaces the old "Deal automation events" item.

## Automations: known limits

- An event is lost if the app stops between saving a message or a move and writing its event.
- The same person as `@c.us` and `@lid` can be "new" twice.
- A typed test number whose first message arrives with a hidden number is never captured (use an existing contact
  with "Treat this chat as new").
- A crash mid-rule marks an unfinished send Interrupted and runs the rest of the rule.
- A send that timed out is not retried (it may have gone); only a send that never reached the extension is.
- No retention setting: nothing expires.
- No lease renewal while a long event is processed.
- A published rule can be moved to draft while a retry waits, which closes its run as Interrupted.
- Message text and the contact's name and phone stay for good in `automationEventHistory` and `automationRuns`:
  removing a number or a contact leaves them.
- One worker thread serves every brand, so a hung extension delays all brands by up to the send timeout per send.

## Click-to-WhatsApp ads: known limits

- The `ctwaContext` field names come from the wa-js model and are unconfirmed until the end-to-end check, which needs the
  user to tap one of their own ads from a named phone. If the real shape differs, fix `MessagePayloads.adClick` from
  the stored `adClick.raw`.
