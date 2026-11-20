# Campaigns on Cloud API numbers — Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The new-campaign wizard, campaign list and detail let a brand build, start and follow a campaign on Cloud
API numbers, while WhatsApp Web campaigns look and work as today.

**Architecture:** The wizard becomes Audience → Numbers → Message → Review. The Numbers step chooses the kind first;
the Message step shows saved templates (WhatsApp Web, unchanged) or one imported Meta template (Cloud). API types
follow the backend plan's shared contract.

**Tech Stack:** React 18, TypeScript, PatternFly 6.5, vitest + Testing Library, the repo's `apiFetch` mocks.

**Spec:** backend repo `docs/superpowers/specs/2026-11-21-cloud-campaigns-design.md` (§5.9); contract: backend plan
`docs/superpowers/plans/2026-11-21-cloud-campaigns-backend.md`, section "Shared contract".

## Global Constraints

- Work in `omnichannel-crm-frontend/.worktrees/cloud-campaigns` (branch `cloud-campaigns`), never on `main`.
- WhatsApp Web campaigns: same fields, texts and behaviour; existing tests in `src/app/Campaigns/*.test.tsx` keep
  passing — the only edits allowed there are the step order (Numbers before Message) where a test walks the wizard.
- Copy: "WhatsApp Web numbers", "Cloud API numbers", "WhatsApp template" for Meta templates (never "template" alone
  for anything else new). Pause texts: `TEMPLATE_UNAVAILABLE` "Meta paused or rejected the WhatsApp template",
  `CLOUD_QUALITY_LOW` "A number's quality rating is red", `CLOUD_AUTH` "Meta refused the access token".
- Review note for Cloud (exact): "Meta charges for every message sent, and may hold back marketing messages to people
  who received many recently."
- Prettier only on touched files. Checks: `npx vitest run <files>`, then `npx tsc --noEmit`, `npm run lint`,
  `npm run build` once at the end.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Switching the kind after picking numbers or a template: the other kind's choices are cleared, never sent (Task 2
   test `switchingKindClearsChoices`).
2. A Cloud number at its daily limit in the wizard: still selectable, with "Limit reached — goes on as the 24 hours
   roll" shown (Task 2 test `atLimitNumberIsSelectableWithNote`).
3. A Meta template with a media header and no file chosen: Next is disabled with the reason (Task 3 test
   `mediaHeaderNeedsAFile`).
4. A campaign detail of an old campaign without `channel` in the reply: shown as WhatsApp Web (Task 4 test
   `missingChannelReadsAsWeb`).
5. Cloud numbers from two WhatsApp accounts selected: blocked with "Choose numbers of one WhatsApp account" (Task 2
   test `twoWabasBlocked`).

---

### Task 1: API types and calls

**Files:**
- Modify: `src/app/api/campaigns.ts`
- Test: `src/app/api/campaigns.test.ts` (create if absent)

**Interfaces:**
- Produces: `type CampaignChannel = 'WHATSAPP_WEB' | 'WHATSAPP_CLOUD_API'`; `interface CloudSenderView` (contract
  fields); `CampaignView.channel?: CampaignChannel` (absent → web), `cloudTemplate: {cloudTemplateId; name; language;
  status; headerMediaId: string | null} | null`, `cloudSenders: CloudSenderView[] | null`; `pauseReason` union gains
  the three new values; `CampaignRequest.channel`, `CampaignRequest.cloudTemplate: {cloudTemplateId: string;
  headerMediaId: string | null} | null`; `listCloudSenders(token, ws): Promise<CloudSenderView[]>` →
  `GET …/campaigns/senders?channel=WHATSAPP_CLOUD_API`; `channelOf(c: CampaignView): CampaignChannel`.

- [ ] **Step 1: Failing test** — `listCloudSenders` hits `/api/workspaces/w1/campaigns/senders?channel=WHATSAPP_CLOUD_API`;
  `channelOf({...})` returns `'WHATSAPP_WEB'` when `channel` is absent.
- [ ] **Step 2: Run** `npx vitest run src/app/api/campaigns.test.ts` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; `npx tsc --noEmit` → no errors.
- [ ] **Step 5: Commit** — `feat(campaigns): Cloud campaign API types`.

---

### Task 2: Wizard order and the Numbers step

**Files:**
- Modify: `src/app/Campaigns/NewCampaign/NewCampaignPage.tsx` (order Audience → Numbers → Message → Review; step
  name "Numbers"), `src/app/Campaigns/NewCampaign/draft.ts` (`channel: CampaignChannel` default `'WHATSAPP_WEB'`,
  `cloudTemplate: CloudTemplate | null`, `headerMediaId: string | null`, `headerFileName: string | null`),
  `src/app/Campaigns/NewCampaign/SendersStep.tsx`
- Test: `src/app/Campaigns/newCampaign.test.tsx` (add cases; adjust existing walks to the new order only)

**Interfaces:**
- Consumes: Task 1 `listCloudSenders`, `CloudSenderView`.
- Produces: `sendersReady(d: Draft, cloud: CloudSenderView[]): string | null` in `draft.ts` (null = ready, else the
  reason shown under Next); `setChannel(d, channel): Partial<Draft>` clearing `senderIds`, `templates`,
  `cloudTemplate`, `mappings`, `headerMediaId`, `headerFileName`.

- [ ] **Step 1: Failing tests**
- `numbersStepComesBeforeMessage()` — step nav order is Audience, Numbers, Message, Review.
- `cloudKindListsCloudNumbers()` — choose "Cloud API numbers" → rows from `listCloudSenders` with quality, "250 a
  day", "12 used in the last 24 hours".
- `atLimitNumberIsSelectableWithNote()` — `usedLast24h == dailyLimit` → checkbox enabled, text "Limit reached — goes
  on as the 24 hours roll".
- `twoWabasBlocked()` — two rows with different `wabaId` selected → Next disabled, "Choose numbers of one WhatsApp
  account".
- `cannotSendRowDisabled()` — `canSend false` → checkbox disabled with "Not ready to send".
- `switchingKindClearsChoices()` — pick a Web sender, switch to Cloud, back to Web → no sender selected.
- [ ] **Step 2: Run** `npx vitest run src/app/Campaigns/newCampaign.test.tsx` → new cases FAIL.
- [ ] **Step 3: Implement** (a PatternFly `ToggleGroup` with the two kinds at the top of the step; Web list as today).
- [ ] **Step 4: Run** → PASS (all cases, old and new).
- [ ] **Step 5: Commit** — `feat(campaigns): Numbers step with the number kind`.

---

### Task 3: Message step for Cloud, and the request

**Files:**
- Modify: `src/app/Campaigns/NewCampaign/MessageStep.tsx` (Cloud branch), `draft.ts` (`messageReady`, `toRequest`)
- Test: `src/app/Campaigns/newCampaign.test.tsx`

**Interfaces:**
- Consumes: `listCloudTemplates(token, ws, {phoneNumberId: senderIds[0]})` (all of the WABA, sendable or not) from
  `src/app/api/cloudTemplates.ts`; `CloudTemplatePreview` (`src/app/CloudTemplates/CloudTemplatePreview.tsx`);
  `uploadCloudMedia(token, ws, phoneNumberId, kind, file)` from `src/app/api/cloudMessages.ts`;
  `PlaceholderMapping` with one entry per `CloudTemplate.variables[].key`.
- Produces: `toRequest` for Cloud: `{channel: 'WHATSAPP_CLOUD_API', templateIds: [], cloudTemplate: {cloudTemplateId,
  headerMediaId}, placeholders: one per variable key, senderIds}`; for Web adds `channel: 'WHATSAPP_WEB',
  cloudTemplate: null` and is otherwise unchanged.

- [ ] **Step 1: Failing tests**
- `cloudShowsWhatsAppTemplates()` — approved ones selectable; a PENDING one and an unsupported one disabled with
  their reasons ("Waiting for Meta's approval", the `unsupportedReason`).
- `cloudVariablesAreMapped()` — template with `body:1` → one mapping row labelled `body:1`, default from its fill.
- `mediaHeaderNeedsAFile()` — IMAGE header, no file → Next disabled, "Choose the header image"; after upload Next
  enabled and `headerMediaId` set from the upload reply.
- `cloudRequestShape()` — `toRequest` equals the contract object above for a Cloud draft.
- `webRequestUnchanged()` — `toRequest` of a Web draft equals today's plus `channel` and `cloudTemplate: null`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(campaigns): WhatsApp template message step for Cloud campaigns`.

---

### Task 4: Review, list, detail and settings texts

**Files:**
- Modify: `ReviewStep.tsx`, `src/app/Campaigns/CampaignsPage.tsx`, `CampaignDetailPage.tsx`, `campaignDisplay.ts`,
  `CampaignSettingsPage.tsx`
- Test: `src/app/Campaigns/campaigns.test.tsx`, `campaignDetail.test.tsx`

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `pauseText(reason)` in `campaignDisplay.ts` covering the three new reasons (exact copy in Global
  Constraints); `channelLabel(c)` → "WhatsApp Web" | "Cloud API".

- [ ] **Step 1: Failing tests**
- `reviewShowsCloudNote()` — Cloud draft review shows the template name, the estimate ("about 2 days at 250 a day")
  and the exact cost note.
- `listShowsKindAndNewPauseReasons()` — a Cloud campaign row shows "Cloud API"; a paused one shows
  "Meta paused or rejected the WhatsApp template".
- `detailShowsTemplateAndCloudNumbers()` — detail of a Cloud campaign shows the WhatsApp template name and language,
  one row per `cloudSenders` entry with "used 120 of 250 in the last 24 hours", and "Waiting for Meta's daily limit
  until 14:05" when `heldUntil` is set or used ≥ limit.
- `recipientShowsMetaReason()` — recipient error `"131026 Message undeliverable"` shown as is.
- `missingChannelReadsAsWeb()` — detail without `channel` renders as today's Web detail.
- `settingsSaysWhatAppliesToWhom()` — settings page shows "Warm-up, gaps and caps apply to WhatsApp Web numbers.
  Working days and hours apply to both."
- [ ] **Step 2: Run** `npx vitest run src/app/Campaigns/campaigns.test.tsx src/app/Campaigns/campaignDetail.test.tsx` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; then `npx vitest run src/app/Campaigns`, `npx tsc --noEmit`, `npm run lint`,
  `npm run build` → all green.
- [ ] **Step 5: Commit** — `feat(campaigns): Cloud campaign review, list and detail`.
