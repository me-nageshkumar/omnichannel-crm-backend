# Chatbot settings in three layers: platform defaults, brand, chatbot

Status: design, 2026-11-14. Supersedes the "no global layer" call in
`2026-11-13-advanced-chatbot-design.md` §4.1 and its non-goal "a global settings page".

## 1. Intent

Today every chatbot carries a full copy of its settings, filled from code defaults when it is created. The user wants
three layers:

- **Platform defaults**, set by the super admin: what a brand starts with (the user's words: "super admin sets
  defaults for new brands").
- **Brand settings**: each brand writes its own messages in its own voice ("brands set their own messages").
- **Chatbot settings**: one chatbot changes only what is special about it.

A chatbot setting overrides the brand's, the brand's overrides the platform default. The user chose **live
fall-through** (a layer stores only what it changed; anything it never touched follows the layer above as it is now,
so a super admin's later fix reaches every brand that never changed that field) and **field by field** (each setting
is inherited or set on its own, as in the reference project, so rewriting one message does not freeze the numbers
beside it).

Reference: `RedLava/SCM/CRM/whatsapp` `ChatbotSettingServiceImpl.getComputedChatbotSettings` (two layers, per phone
number and per chatbot, defaults hard-coded; field-by-field merge; "Delete custom settings" returns to global).

## 2. Goals, non-goals, anti-goals

Goals
- One resolved set of settings per chatbot, computed from three layers field by field.
- A super-admin page for the platform defaults, a brand page, and the existing editor dialog, all showing for each
  field whether it is set here or inherited, and from where.
- Existing chatbots behave exactly as before after the change.

Non-goals
- Settings per phone number (the reference's layer): the user kept "Number" as a rule condition earlier; a number
  layer can come later.
- History of who changed what, notifications to brands when a default changes.
- New settings. The nine fields stay as they are (section 3).
- Copying defaults into a brand at creation (the user chose live fall-through instead).

Anti-goals
- A brand silently stuck with the super admin's wording after it typed its own.
- A save at one layer making some other layer's resolved settings invalid (section 4.3 rules this out by design).
- Any change in behaviour for Lead qualifier or any other existing chatbot on the day this ships.

## 3. The fields

Today's eight `ChatbotSettings` fields, plus one split out, nine in all (my call: `reminderAfterMinutes == null` meant "off"; with
inheritance, "off" and "inherit" must differ, so on/off becomes its own field):

| Field | Type | Range / rule | Platform default (code, used until the super admin saves) |
|---|---|---|---|
| `reminderOn` | boolean | | `false` |
| `reminderAfterMinutes` | int | 1–1440 | `30` (my call) |
| `reminderMessage` | text | 1–1024, never "none" | `"Are you still there? Reply to carry on."` (my call) |
| `endAfterMinutes` | int | 5–2880 | `120` |
| `endMessage` | optional text | ≤1024, may be "none" | none |
| `invalidReplyRetries` | int | 0–5 | `2` |
| `retryMessage` | text | 1–1024, never "none" | `"Sorry, I didn't get that. Please choose one of the options."` |
| `giveUpMessage` | optional text | ≤1024, may be "none" | none |
| `stopOnAgentReply` | boolean | | `true` |

Messages keep their `{{…}}` variables, rendered per run as today.

## 4. Resolution

### 4.1 Three states per field
At the brand and chatbot layers every field is **inherited** (absent / `null`) or **set**. The two optional messages
have a third state, **set to none**, stored as the empty string `""` (my call: compact, and `null` already means
inherit). At the platform layer every field has a value; optional messages may be none (`""`).

### 4.2 The rule
For each field: the chatbot's value if set, else the brand's if set, else the platform's (the stored platform document,
or the code defaults of section 3 when the super admin has never saved). `""` resolves to "send no message".

### 4.3 Why no layer can break another
Every required field (`reminderMessage`, `retryMessage`, the numbers, the booleans) always has a value at the
platform layer and can only be set to a valid value or inherited at the others. So any mix resolves to a valid set.
Each layer's save is checked on its own fields only. "Reminder on" with an inherited message always finds a message.

The one rule across two fields, "the reminder comes before the end" (today only the console's `settingsProblems`
checks it), could be broken by a change at another layer, so it is **not a save error any more** (my call). It is
handled where it matters: a run whose resolved reminder minutes are at or past its end minutes gets no reminder (the
ticker sends a reminder only when `remindAt` is before `endAt`). The form shows a warning, not an error, when the
resolved values conflict: "The reminder comes at or after the end, so it will never be sent."

### 4.4 When it is resolved
Wherever today's code reads `chatbot.getSettings()`: each walk of a run (`ChatbotRunner.walk` building `RunContext`),
the ticker (reminder and end messages), the hand-over listener (`stopOnAgentReply`, which today reads the chatbot
directly), the form page lifetime (`AskFormExecutor`). Resolved fresh each time (as the reference does, and as today
reads the chatbot fresh): a change reaches runs in progress at their next wait, reminder, end or retry. A wait's
`remindAt`/`endAt` already set keep their time; the next wait uses the new minutes. A reminder already scheduled is
sent only if the resolved `reminderOn` is still true when it falls due (my call: turning the reminder off takes effect
at once). Test runs resolve the same way.

## 5. Storage

- New collection `chatbotSettings` (camelCase per project rule): one document per layer, `{_id, scope: PLATFORM |
  BRAND, workspaceId (null for PLATFORM, unique per brand), <the nine fields, all nullable>, lastUpdateTime, updatedBy,
  version}`; unique index on `{scope, workspaceId}`. `@Version` for stale saves (409), as `campaignSettings` does.
  No document = everything inherited (brand) or the code defaults (platform).
- `Chatbot.settings` keeps its name and becomes the chatbot layer: the same nine nullable fields
  (`ChatbotSettingsLayer`, or the builder's name). The old full record type is replaced.
- **Migration (startup, idempotent, the pattern of `CollectionRenames`/`MessageTypeMigration`):** for each chatbot
  with old-shape settings, each field equal to today's code default becomes inherited; any other value stays set on
  the chatbot. An old non-null `reminderAfterMinutes` becomes `reminderOn: true` + that minutes + its message set on
  the chatbot. Old `null` end/give-up messages become inherited (the default is none, so behaviour is the same).
  Today that turns Lead qualifier's settings into all-inherited. Take a `mongodump` before the first deploy (project
  rule).

## 6. Who can do what

- Platform defaults: a new PLATFORM authority `chatbot-defaults:manage` ("Set the chatbot settings every brand starts
  with"). The Super Admin holds every PLATFORM code automatically; no existing role is changed (project rule), so the
  Account Manager does not get it unless someone grants it.
- Brand settings: read with `chatbot:view`, change with `chatbot:manage` (my call: the people who edit chatbots set
  their defaults; no new brand right, which would mean touching the built-in roles).
- Chatbot layer: as today, part of the chatbot's draft save (`chatbot:manage`); a published chatbot's settings are
  read-only like the rest of it.

## 7. API

All bodies use the field names of section 3. "Layer" below is the nine nullable fields.

- `GET /api/platform/chatbot-settings` (`chatbot-defaults:manage`) → `{settings: <all nine, filled>, version,
  lastUpdateTime, updatedBy}`; `PUT` with `{settings, version}` → same; every field required except the optional
  messages (`""` = none). Validation errors as field keys `settings.<field>` through the existing JSON advice.
- `GET /api/workspaces/{ws}/chatbots/settings` (`chatbot:view`) → `{own: <layer>, inherited: <all nine>,
  version}` where `inherited` is the platform value of every field, whether or not the brand sets it (the brand
  page needs it for placeholders and Reset; a brand user may read the platform defaults this way though not through
  the platform endpoint). `PUT` (`chatbot:manage`) with `{own, version}` → same.
- Chatbot view (`GET /chatbots/{id}` and the save response): `settings` is now the chatbot's own layer; add
  `inheritedSettings` (the brand-or-platform value of every field, as if the chatbot set nothing) and
  `inheritedSources` (`BRAND | PLATFORM` per field). The save request's `settings` is the chatbot's own layer.
- The console computes the effective value itself as `own ?? inherited`; there is one resolver on the server and the
  console's merge is the same one-liner (my call: sending `inherited` rather than `effective` is what lets the form
  show the value above a field that is set).
- `POST /chatbots` (create): the new chatbot's layer is empty (all inherited), not a copy of anything.

## 8. Console

One shared settings form used at all three layers, the four groups of today's dialog (Inactivity reminder,
Inactivity end, Invalid replies, Agent reply).

- **Inherited field:** the input is empty and its placeholder shows the inherited value and its source, for example
  `120 — brand setting` or `Sorry, I didn't get… — platform default`. Switches show the inherited state with a
  helper line "From the brand's settings". Typing sets the field.
- **Set field:** a small "Reset" link beside it returns it to inherited.
- **Optional messages:** a "Send no message" checkbox (set to none); unticked and empty = inherited.
- At the platform layer there is nothing to inherit: plain fields, the optional messages with "Send no message".

Pages
- **Platform → Chatbot defaults** (`/platform/chatbot-settings`, needs `chatbot-defaults:manage`), next to Customers
  and All phone numbers. Copy at the top: "Every brand uses these until it sets its own."
- **Chatbots → Settings** (`/chatbots/settings`): the Chatbots nav item becomes a group "All chatbots" / "Settings",
  as Campaigns has. Read-only for `chatbot:view` without `chatbot:manage`. Copy: "Your chatbots use these unless a
  chatbot sets its own. Empty fields follow the platform default."
- **Editor → Settings dialog:** the same form at the chatbot layer, sources `brand setting` / `platform default`.

## 9. Testing (what must be proven)

- Resolution unit tests: every field through every combination of set / inherited / none at three layers; code
  defaults when no platform document exists.
- Migration: an old full settings object turns into the expected layer (defaults dropped, non-defaults kept, reminder
  split); running twice changes nothing.
- Runs: a brand change of `retryMessage` reaches a running chatbot's next retry; a chatbot override wins over the
  brand; `stopOnAgentReply` false at the brand stops hand-over for chatbots that inherit it.
- Ticker: a scheduled reminder is not sent once the resolved `reminderOn` is false, nor when `remindAt` is not before
  `endAt`.
- Access: platform endpoints refuse brand users and platform users without the code; brand `PUT` refuses
  `chatbot:view`-only users; another brand's settings are a 404.
- Stale `PUT` at either layer is a 409.
- Console: placeholders and sources, Reset, "Send no message", read-only brand page, the two new routes' guards.

## 10. Calls I made (for the user to check)

1. `reminderOn` split out of `reminderAfterMinutes` so "off" and "inherit" differ.
2. Default reminder: 30 minutes, "Are you still there? Reply to carry on." (shown only once someone turns it on).
3. `""` stores "send no message"; `null` is inherited.
4. Required messages can never be "none" at any layer, so no save can break another layer.
5. Brand settings use `chatbot:manage`; platform defaults get a new PLATFORM code only the Super Admin holds.
6. Settings resolve fresh on every use; runs in progress pick up changes at their next wait.
7. Migration drops values equal to today's defaults, so existing chatbots follow their brand from now on.
8. A new chatbot starts with every field inherited.
9. "Reminder before end" is a warning in the form, not a save error; at run time a reminder at or after the end is
   not sent.
10. Turning the reminder off stops reminders already scheduled.
11. Brand users can read the platform defaults (through their own settings page), not change them.

## 11. Left to the builder

Class and package names, the resolver's shape, how the console form component is split, index names, the migration
class name, copy wording beyond what section 8 fixes.
