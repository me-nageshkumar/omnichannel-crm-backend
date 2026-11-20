# Cloud API numbers — Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins add a Cloud number through a Meta connection and import WhatsApp templates; counsellors see the 24-hour window, send templates from the inbox (and start new chats with one), and rules can send a template.

**Architecture:** New API modules `metaConnections.ts` and `cloudTemplates.ts`; additions to `phoneNumbers.ts`, `inbox.ts`, `automations.ts` matching the backend plan's **API contract** exactly. UI: a Cloud branch in the Phone numbers add flow plus a Meta connections section; a **WhatsApp templates** page; a window line, a template picker and a template bubble in the inbox; a rule action form.

**Tech Stack:** React 18, PatternFly 6.5, TypeScript, vitest + Testing Library (the repo's existing patterns: `src/app/AI/*` for write-only secrets, `src/app/Inbox/*` for the composer).

**Spec:** `docs/superpowers/specs/2026-11-20-cloud-numbers-design.md` (backend repo). **Contract:** the "API contract" section of `docs/superpowers/plans/2026-11-20-cloud-numbers-backend.md` — field names, codes and paths are copied from there; do not invent others.

## Global Constraints

- Work in `omnichannel-crm-frontend/.worktrees/cloud-number` (create it from `main` with branch `cloud-number`; `npm ci --prefer-offline` first). Prettier on touched files only. `npx tsc --noEmit && npm run lint && npm run build` and the touched vitest files pass at every task end.
- Secrets (`appSecret`, `accessToken`) are write-only fields: never pre-filled, never shown; views show `tokenHint · set <date>` and a **Replace** action, as `src/app/AI/SecretField.tsx` does (reuse it).
- Copy (exact): page title **WhatsApp templates**; nav label **WhatsApp templates** placed right after **Templates**; add choice **WhatsApp Web** / **WhatsApp Cloud API**; window line **Replies close in {relative}** when open and **24-hour window closed — send a WhatsApp template** when closed; picker button **WhatsApp template**; rule action label **Send WhatsApp template**; states **Ready**, **Not registered**, **Waiting for first webhook**, **Token invalid**.
- The existing **Templates** page, `TemplatePickerModal` and `sendTemplate` are WhatsApp Web saved messages: do not rename or extend them for Meta templates (new names: `CloudTemplate*`, `sendCloudTemplate`).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A chat whose window closes while it is open: the composer switches to the closed state on the next minute tick without a reload, and a send that races it shows the server's `WINDOW_CLOSED` message instead of a generic error — pinned in Task 4 (`windowClosesWhileOpen`, `windowClosedErrorShown`).
2. A connection saved with a bad token: the add-number flow shows `lastError` and does not offer numbers — pinned in Task 2 (`invalidConnectionShowsErrorNoNumbers`).
3. A template with a header image: the picker refuses to send until a file is chosen, then sends `headerMediaId` — pinned in Task 4 (`headerImageNeedsFile`).
4. A WhatsApp Web chat: nothing of the window line or the template button appears — pinned in Task 4 (`webChatHasNoWindowLine`).
5. A user without `phone-number:manage`: connections and template fills are read-only — pinned in Tasks 2 and 3 (`viewerSeesNoActions`).

---

### Task 1: API modules

**Files:**
- Create: `src/app/api/metaConnections.ts`, `src/app/api/metaConnections.test.ts`, `src/app/api/cloudTemplates.ts`, `src/app/api/cloudTemplates.test.ts`.
- Modify: `src/app/api/phoneNumbers.ts` (`PhoneNumber.cloud`, `CloudState`, `addCloudNumber`, `registerCloudNumber`), `src/app/api/inbox.ts` (`ChatView.window`, `ChatView.channel`, `MessageView.failure`, `MessageView.template`, `sendCloudTemplate`, `NewChatRequest.cloudTemplate`), `src/app/api/automations.ts` (`AutomationAction` variant `SEND_CLOUD_TEMPLATE`, `AutomationOptions.cloudTemplates`), their existing tests.

**Interfaces:**
- Produces: `listMetaConnections(token, ws)`, `getMetaConnection(token, ws, id)`, `createMetaConnection(token, ws, body: {name, appId, appSecret, accessToken})`, `updateMetaConnection(token, ws, id, body: {name, appSecret?, accessToken?})`, `checkMetaConnection(token, ws, id) -> ConnectionCheck`, `retryWebhookSetup(token, ws, id)`, `deleteMetaConnection(token, ws, id)`; types `MetaConnection`, `ConnectionCheck`, `WabaNumbers`, `WebhookSetup`.
- Produces: `listCloudTemplates(token, ws, {phoneNumberId?, sendable?})`, `syncCloudTemplates(token, ws, connectionId?)`, `saveCloudTemplateFills(token, ws, id, fills)`; types `CloudTemplate`, `CloudTemplateVariable`, `CloudTemplateFill`.
- Produces: `addCloudNumber(token, ws, {label, cloud: {connectionId, wabaId, metaPhoneNumberId}})`, `registerCloudNumber(token, ws, id, pin)`; `sendCloudTemplate(token, ws, chatId, {cloudTemplateId, values, headerMediaId?})`.

- [ ] **Step 1: Write the failing tests** (as `api/ai.test.ts` does, with the fetch mock): each function hits the contract's method and path; `createMetaConnection` sends the secrets and `updateMetaConnection` omits absent ones (`'accessToken' in body === false`); `listCloudTemplates` builds `?phoneNumberId=p1&sendable=true`; `sendCloudTemplate` posts to `/inbox/chats/c1/cloud-templates`.
- [ ] **Step 2: Run** `npx vitest run src/app/api --maxWorkers=4`. Expected: FAIL (modules missing).
- [ ] **Step 3: Implement** the modules and types, copying field names from the contract.
- [ ] **Step 4: Run** the same command and `npx tsc --noEmit`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(console): API for Meta connections, Cloud numbers and WhatsApp templates`.

### Task 2: Phone numbers — Meta connections and adding a Cloud number

**Files:**
- Create: `src/app/PhoneNumbers/MetaConnectionsSection.tsx`, `MetaConnectionModal.tsx`, `AddCloudNumberModal.tsx`, `RegisterNumberModal.tsx`, `cloudNumbers.test.tsx`.
- Modify: `PhoneNumbersPage.tsx` (add flow asks **WhatsApp Web** / **WhatsApp Cloud API**; Cloud rows hide connect-code and revoke-device; show verified name, quality, limit tier), `PhoneNumberStatusLabel.tsx` (Cloud states), `AllPhoneNumbersPage.tsx` (connection type column; move hidden for Cloud).

**Interfaces:**
- Consumes: Task 1 functions; `SecretField` from `src/app/AI/SecretField.tsx`.

- [ ] **Step 1: Write the failing tests** in `cloudNumbers.test.tsx`:
  - `connectionModalSendsSecretsOnce` — create sends `appSecret` and `accessToken`; editing shows `EAA…1234 · set 20 Nov` with **Replace** and a save without replacing omits both.
  - `webhookDetailsAreCopyable` — a connection with `setup: "MANUAL"` shows the URL and verify token with copy buttons and `webhook.error`; **Retry webhook setup** calls `retryWebhookSetup`.
  - `addCloudNumberPicksFromCheck` — choose **WhatsApp Cloud API**, pick a connection, the check's numbers listed with verified name and quality; a number with `phoneNumberId` set is disabled ("Already added"); save calls `addCloudNumber` with the picked WABA and number.
  - `invalidConnectionShowsErrorNoNumbers` — check returns `status INVALID`, `lastError "Token expired"` → error shown, no number list, save disabled.
  - `statusLabels` — each `cloud.state` renders its exact label; **Not registered** offers **Register** which posts a 6-digit PIN (the field accepts digits only, 6 long).
  - `deleteConnectionInUseShowsNames` — 409 message shown.
  - `viewerSeesNoActions` — without `phone-number:manage` no add, edit, register, retry or delete controls.
- [ ] **Step 2: Run** `npx vitest run src/app/PhoneNumbers --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.** A short checklist of Meta-side steps (Meta app, system user and token, Live mode, payment method, add number) sits in the Cloud modal as text with a link to Meta's getting-started page.
- [ ] **Step 4: Run** the same command plus `npx tsc --noEmit && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(console): Meta connections and Cloud numbers`.

### Task 3: WhatsApp templates page

**Files:**
- Create: `src/app/CloudTemplates/CloudTemplatesPage.tsx`, `CloudTemplatePreview.tsx` (draws header, body with `{{…}}` shown as chips, footer, buttons from `components`), `FillsModal.tsx`, `cloudTemplates.test.tsx`.
- Modify: `src/app/routes.tsx` (route `/whatsapp-templates`, nav **WhatsApp templates** after **Templates**, authority `phone-number:view`), `src/app/routes.test.tsx`.

**Interfaces:**
- Consumes: Task 1 `listCloudTemplates`, `syncCloudTemplates`, `saveCloudTemplateFills`; the contact field / attribute options the saved-message placeholder editor already loads (reuse its hook or API call; do not import `MessageTemplate` types into the new page).
- Produces: `CloudTemplatePreview` (props `{template: CloudTemplate, values?: Record<string,string>}`), reused by Task 4.

- [ ] **Step 1: Write the failing tests:** `listsWithStatusAndSendable` (APPROVED/REJECTED/PAUSED labels, `rejectedReason` shown, unsupported shows `unsupportedReason` and no fills action); `syncShowsCounts` ("2 added, 1 updated, 0 removed"); `fillsSaveKindAndKey` (body.1 → Contact field "name"; saves `{"body.1": {kind: "CONTACT_FIELD", attributeKey: "name"}}`); `previewRendersValues` (values replace chips); `navOrder` in `routes.test.tsx` (WhatsApp templates right after Templates); `viewerSeesNoActions`.
- [ ] **Step 2: Run** `npx vitest run src/app/CloudTemplates src/app/routes.test.tsx --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command plus `npx tsc --noEmit && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(console): WhatsApp templates page`.

### Task 4: Inbox — window, template picker, template bubble, failures

**Files:**
- Create: `src/app/Inbox/WindowLine.tsx`, `CloudTemplatePickerModal.tsx`, `TemplateBubble.tsx`, `cloudInbox.test.tsx`.
- Modify: `Composer.tsx` (window line above the box on `channel === 'WHATSAPP_CLOUD'`; closed → text box and saved-message picker disabled, **WhatsApp template** button prominent; open → **WhatsApp template** still available in the menu; saved-message picker hides POLL and CONTACT_CARD on Cloud chats), `NewChatModal.tsx` (a Cloud number → template picker instead of text), `MessageBubble.tsx` (a `template` message draws `TemplateBubble`; a `failure` shows its `title` under the bubble in the danger colour), `src/app/Inbox/chatDisplay.ts` (`windowText(window, now)`).

**Interfaces:**
- Consumes: Task 1 `sendCloudTemplate`, `listCloudTemplates({phoneNumberId, sendable: true})`; the existing Cloud media upload (`api/cloudMessages.ts`) for the header file; Task 3 `CloudTemplatePreview`.

- [ ] **Step 1: Write the failing tests** in `cloudInbox.test.tsx`:
  - `windowLineOpen` — `window {open: true, closesAt: now+3h}` → "Replies close in 3 hours".
  - `windowClosedDisablesText` — closed → "24-hour window closed — send a WhatsApp template", textarea disabled, **WhatsApp template** enabled.
  - `windowClosesWhileOpen` — fake timers: `closesAt` 30 s ahead, advance 61 s → closed state without refetch.
  - `windowClosedErrorShown` — `sendText` rejects 409 `{code: "WINDOW_CLOSED"}` → the closed message, not the generic error.
  - `pickerPrefillsAndSends` — template with `body.1` filled from contact name shows "Veenu" editable; send calls `sendCloudTemplate` with `{cloudTemplateId, values: {"body.1": "Veenu"}}`; an empty required value disables Send.
  - `headerImageNeedsFile` — header IMAGE: Send disabled until a file is uploaded; then `headerMediaId` sent.
  - `newChatOnCloudUsesTemplate` — New chat on a Cloud number shows the template picker, no text box.
  - `templateBubbleDraws` — header text, body, footer, two quick-reply buttons rendered from `message.template`.
  - `failedMessageShowsMetaTitle`.
  - `webChatHasNoWindowLine` — `channel: 'WHATSAPP_WEB'`, `window: null` → no window line, no WhatsApp template button; existing composer tests unchanged.
- [ ] **Step 2: Run** `npx vitest run src/app/Inbox --maxWorkers=4`. Expected: FAIL (new tests), existing PASS.
- [ ] **Step 3: Implement.** Relative time uses the formatter the inbox already uses for timestamps; the minute tick is a `setInterval` in `WindowLine` cleared on unmount.
- [ ] **Step 4: Run** the same command plus `npx tsc --noEmit && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(console): 24-hour window and WhatsApp templates in the inbox`.

### Task 5: Rule action "Send WhatsApp template"

**Files:**
- Create: `src/app/Automations/SendCloudTemplateFields.tsx`, `sendCloudTemplate.test.tsx`.
- Modify: `ActionList.tsx` (action **Send WhatsApp template**), `automationModel.ts` (default action, validation: template chosen, every variable has a value unless filled), `automationDisplay.ts` and `RulePopover.tsx` (summary "Send WhatsApp template *name*"), `RunSteps.tsx` if it labels action types.

**Interfaces:**
- Consumes: `AutomationOptions.cloudTemplates` (Task 1), `CloudTemplatePreview` (Task 3), the rule variable picker the saved-message action uses (`SendTemplateFields.tsx`'s value inputs).

- [ ] **Step 1: Write the failing tests:** `addsActionWithValues` (choosing a template lists its variables, typed values saved as `{type: "SEND_CLOUD_TEMPLATE", cloudTemplateId, values}`); `filledVariablesOptional`; `summaryNamesTemplate` (popover text); `noCloudTemplatesShowsHint` ("No approved WhatsApp templates yet — sync them on WhatsApp templates").
- [ ] **Step 2: Run** `npx vitest run src/app/Automations --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command, then the full gate: `npx vitest run --maxWorkers=4 && npx tsc --noEmit && npm run lint && npm run build`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(console): Send WhatsApp template rule action`.
