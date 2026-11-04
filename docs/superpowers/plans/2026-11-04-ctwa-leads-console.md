# Click-to-WhatsApp ad leads — console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the ad context in the inbox and the CRM contact drawer, and offer the three ad conditions in the Automations editor and test page.

**Architecture:** Three API types gain an optional `ad` object (inbox message and chat, CRM origin, automation event data); two inbox components and one CRM helper render it; the Automations field table gains three entries that flow through the existing condition editor, text and label helpers.

**Tech Stack:** React 18, PatternFly 6.5, react-router, vitest; `tsconfig` target es5.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-11-04-ctwa-leads-design.md` (binding). Backend contract: `omnichannel-crm-backend/docs/superpowers/plans/2026-11-04-ctwa-leads-backend.md` Tasks 2–4 (Interfaces blocks).

## Global Constraints

- Work in `omnichannel-crm-frontend/.worktrees/ctwa-leads` (branch `ctwa-leads`, off `main` 4db57e2; `node_modules` is linked). Checks per task: `npx vitest run <paths> --maxWorkers=4`; before the last commit `npx tsc --noEmit && npm run lint && npm run build`. Prettier only on touched files.
- Copy fixed by the spec: "From ad", "Ad" (header label; title = the headline), "Ad headline", "Ad id", "Message came from an ad" / "Message did not come from an ad", "Came from a click-to-WhatsApp ad", "an ad" (fallback headline).
- Wire shapes (from the backend plan): inbox `adClick: { sourceId, sourceUrl, sourceApp, headline, description } | null` on `MessageView` and `ChatView`; CRM `origin.ad: { sourceId, sourceUrl, headline } | null`; automation `event.data.message.ad: { sourceId, sourceUrl, headline } | null`. Condition keys `message.ad` (boolean, operator `is`), `message.ad.headline` and `message.ad.sourceId` (text, the text operators).
- Links to `sourceUrl` open in a new tab with `rel="noopener noreferrer"`; only `http(s)` URLs are linked, anything else is shown as text.
- `tsconfig` targets es5: no `[...new Set()]`.
- Commits end with the trailer the session reminder names (`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` as of 2026-11-04).

## Plan calls (decisions the spec leaves to the builder)

- Plan call: the bubble line is a `<div>` with a small muted class above the body: text "From ad: " then the headline (linked when `sourceUrl` is http(s)), falling back to `description`, then "an ad".
- Plan call: the header label sits right after the number label (`ChatHeader.tsx:144-146`): `<Label isCompact color="purple" title={headline}>Ad</Label>`.
- Plan call: the origin line for `CTWA_AD` is rendered by a new `originNode(c)` returning a React node (the headline needs a link); `originLine` keeps returning a string for the table column, reading "Came from a click-to-WhatsApp ad “<headline>” on <date>" (or without the quoted part).
- Plan call: the test page adds the row `['From ad', <headline or 'an ad', linked>]` right after the Type row when `data.message.ad` is present.

## Review Focus

1. A message with `adClick` whose `sourceUrl` is missing or not http(s) must still show "From ad: <headline>" as plain text — Task 1 `adLineWithoutLinkIsText`.
2. A chat whose `adClick` is null (every chat today) must render the header exactly as before — Task 1 `noAdNoLabel`.
3. A `CTWA_AD` origin without `ad` (a contact captured before this feature could have the kind from an import, or `ad` could be null) must read "Came from a click-to-WhatsApp ad on <date>" — Task 2 `ctwaOriginWithoutAd`.
4. Picking "Ad" in the editor must save `{ field: 'message.ad', operator: 'is', value: true|false }` and offer only "is" — Task 3 `adConditionSavesABoolean`.
5. A captured test event without `ad` must show no "From ad" row — Task 3 `noAdRowWithoutAd`.

---

### Task 1: Inbox shows the ad

**Files:**
- Modify: `src/app/api/inbox.ts` (`AdClickView` type; `adClick: AdClickView | null` on `MessageView` and `ChatView`), `src/app/Inbox/MessageBubble.tsx`, `src/app/Inbox/ChatHeader.tsx:144-146`, `src/test/inbox.ts` fixtures that build messages/chats (`adClick: null`).
- Test: `src/app/Inbox/thread.test.tsx` (append), `src/app/Inbox/chatHeader.test.tsx` (append).

**Interfaces:**
- Produces: `export interface AdClickView { sourceId: string | null; sourceUrl: string | null; sourceApp: string | null; headline: string | null; description: string | null }`; `export const adLink = (url: string | null): string | null` in `src/app/Inbox/chatDisplay.ts` (http(s) only).

- [ ] **Step 1: Write the failing tests**: `thread.test.tsx` `showsTheAdLine` (message with `adClick {headline 'Diwali sale', sourceUrl 'https://fb.me/x'}` → text "From ad:" and a link "Diwali sale" with `href https://fb.me/x`, `target _blank`), `adLineWithoutLinkIsText` (`sourceUrl null` → "From ad: Diwali sale", no anchor), `adLineFallsBackToAnAd` (headline and description null → "From ad: an ad"); `chatHeader.test.tsx` `adChatShowsTheLabel` (chat with `adClick` → a "Ad" label with `title "Diwali sale"`), `noAdNoLabel`.
- [ ] **Step 2: Run** `npx vitest run src/app/Inbox/thread.test.tsx src/app/Inbox/chatHeader.test.tsx --maxWorkers=4` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS (and `npx tsc --noEmit`).
- [ ] **Step 5: Commit** — `feat(inbox): show the click-to-WhatsApp ad on messages and chats`

### Task 2: CRM origin line

**Files:**
- Modify: `src/app/api/crm.ts:171-177` (`origin.ad: { sourceId: string | null; sourceUrl: string | null; headline: string | null } | null`), `src/app/Crm/Contacts/contactColumns.ts:44-54` (`originLine`, new `originNode`), `src/app/Crm/Contacts/CrmContactDrawer.tsx:200,248` (use `originNode`), test fixtures that build `origin` (`contactDrawer.test.tsx:127` and any others tsc names).
- Test: `src/app/Crm/Contacts/contactDrawer.test.tsx` (append).

- [ ] **Step 1: Write the failing tests**: `ctwaOriginNamesTheAd` (origin `CTWA_AD` with `ad {headline 'Diwali sale', sourceUrl 'https://fb.me/x'}` → "Came from a click-to-WhatsApp ad" and a link "Diwali sale"), `ctwaOriginWithoutAd` (`ad null` → "Came from a click-to-WhatsApp ad on …", no link), `inboundOriginUnchanged` ("Captured from a chat …").
- [ ] **Step 2: Run** `npx vitest run src/app/Crm/Contacts --maxWorkers=4` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(crm): ad origin in the contact drawer`

### Task 3: Automation conditions and the test page row

**Files:**
- Modify: `src/app/api/automations.ts` (`EventData.message` gains `ad: { sourceId: string | null; sourceUrl: string | null; headline: string | null } | null`), `src/app/Automations/automationModel.ts` (`FIELDS.MESSAGE_RECEIVED` + 3 entries after `message.type`; `BOOLEAN_CHOICES['message.ad'] = ['Message came from an ad', 'Message did not come from an ad']`; `conditionText` handles it like `chat.new`), `src/app/Automations/automationDisplay.ts` (`actualLabel` treats `message.ad` as a boolean), `src/app/Automations/AutomationTestPage.tsx:104-106` (the "From ad" row), `src/test/automations.ts` (`message.ad: null` in `CAPTURED_TEST`).
- Test: `src/app/Automations/automationModel.test.ts` (append), `src/app/Automations/automationEditor.test.tsx` (append), `src/app/Automations/automationTest.test.tsx` (append).

**Interfaces:**
- Consumes: catalogue keys and the `ad` object from backend Task 4.
- Field entries: `{ field: 'message.ad', label: 'Ad', description: 'Whether the message came from a click-to-WhatsApp ad.', type: 'boolean', operators: ['is'] }`, `{ field: 'message.ad.headline', label: 'Ad headline', description: 'The headline of the ad the contact tapped.', placeholder: 'For example: Diwali sale', type: 'text', operators: TEXT_OPERATORS }`, `{ field: 'message.ad.sourceId', label: 'Ad id', description: 'The id of the ad, as Meta reports it.', type: 'text', operators: TEXT_OPERATORS }`.

- [ ] **Step 1: Write the failing tests**: `automationModel.test.ts` `adFieldsFollowTheContract` (the three keys, their types and operators; `conditionText` for `message.ad is true` → "Message came from an ad"); `automationEditor.test.tsx` `adConditionSavesABoolean` (add a condition, pick "Ad", choose "Message did not come from an ad", save → request body leaf `{ field: 'message.ad', operator: 'is', value: false }`; the operator select offers only "is"); `automationTest.test.tsx` `capturedEventShowsFromAd` (CAPTURED test with `message.ad {headline 'Diwali sale', sourceUrl 'https://fb.me/x'}` → row "From ad" with a link "Diwali sale"), `noAdRowWithoutAd`.
- [ ] **Step 2: Run** `npx vitest run src/app/Automations --maxWorkers=4` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; then `npx tsc --noEmit && npm run lint && npm run build` → clean.
- [ ] **Step 5: Commit** — `feat(automations): conditions on the click-to-WhatsApp ad`

### Task 4: End-to-end check against the running stack

Needs the backend and console branches deployed on 8089 (project rules §2) and **the user tapping one of their own ads (or an ad preview) from a phone they name**; never message anyone.

- [ ] **Step 1:** The user taps the ad and sends a message. The inbox thread shows "From ad: <headline>" and the chat header shows "Ad"; note the stored `adClick` (and `raw`) of that message in Mongo: if the real `ctwaContext` differs from the spec's field names, record the difference in `crm-pending.md` for a follow-up commit to `MessagePayloads.adClick`.
- [ ] **Step 2:** Add to CRM from the chat; the contact drawer reads "Came from a click-to-WhatsApp ad “<headline>”".
- [ ] **Step 3:** A draft rule "Ad is yes" tested on that contact matches in the test page; the event rows show "From ad".
- [ ] **Step 4:** Note anything surprising in `docs/superpowers/crm-pending.md` (backend repo) and commit there.
