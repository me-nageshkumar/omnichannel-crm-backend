# CRM phase F (console): UX catch-up — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the eight catch-up items in the console: days in stage and Open chat on deal cards, several contacts on a deal, stage change and New deal in the inbox panel, labels and attributes in the contact drawer, Open chat from a company's contacts, CSV template download, a settings History tab, and readable redacted timeline entries.

**Architecture:** Small changes to existing screens plus four new units: `deals/daysInStage.ts`, `deals/OtherContactsField.tsx`, `deals/DealContacts.tsx` and `Settings/SettingsHistory.tsx`. `DealFormModal` gains two optional props so the inbox can reuse it. Calls go through `src/app/api/crm.ts`. No new dependency.

**Tech Stack:** as phases A–E (React 18, TypeScript, PatternFly 6.5, vitest + Testing Library).

**Spec:** `docs/superpowers/specs/2026-10-31-crm-ux-catch-up-design.md` (§6, C1, C2, C5, C6, C8, C9, C11, C12, C15, C18, C20). **Contract:** "Contract for the console" in `2026-10-31-crm-f-backend.md`, which wins over this plan. **Depends on:** that plan's branch (`omnichannel-crm-backend/.worktrees/crm-f`).

**Branch:** `crm-f` worktree at `omnichannel-crm-frontend/.worktrees/crm-f` cut from `main`. Commands (absolute paths): `npx vitest run <path> --maxWorkers=4`, `npx tsc --noEmit`, `npx eslint <files>` / `npm run lint`, `npm run build`. Prettier only on touched files.

## Global Constraints

- Phase A–E Global Constraints apply: CRM code under `src/app/Crm/`, PatternFly tokens only, sentence case, phone width (375 px) on every screen, `apiFetch` + `useLoad`/`useAction`, `useRefetchOnFocus`, `useCrmCan`.
- **Copy, exactly:** `New in stage`, `<n>d in stage`; button names `Open chat with <contact>`, `Open chat`; `Other contacts`, helper `Contacts on this deal's number.`, while disabled on create `Choose the primary contact first.`, failed load `Couldn't load this deal's contacts. They stay as they are.`, cap `At most 49 other contacts.`; tab `Contacts`, label `Primary`, empty `No contacts on this deal.`; panel `New deal`; rows `Labels`, `Attributes`; `Download template`; tab `History`, empty `No settings changes yet.`; field-change row `Changed contacts`.
- **Safety rule (spec C5):** `crmContactIds` is sent on `PUT` only when the contacts loaded successfully. A failed or pending load omits the key.
- **Naming:** new code says `channelAccountId`; the only `phoneNumberId` read is `deal.channel?.phoneNumberId` and `contact.channel?.phoneNumberId`, passed straight to it.
- Commits: one per task, body ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Editing a deal whose contacts failed to load:** saving must not unlink anyone (Task 2 `failedContactsLoadLeavesThemAlone`).
2. **The chat button on a card:** a click must not open the drawer, and a press must not start a drag (Task 1 `chatButtonDoesNotOpenTheDeal`, `chatButtonDoesNotStartADrag`).
3. **Changing the primary on a new deal:** contacts chosen for the old number must go (Task 2 `changingPrimaryClearsOtherContacts`).
4. **A chat switch while the inbox panel is moving a deal:** the late answer must not write into the next chat's panel (Task 3 `lateMoveDoesNotTouchTheNextChat`).
5. **A redacted entry beside an ordinary one:** only entries missing both fields of a side fall back to the summary, so a move from a deleted stage still reads as before (Task 5 `removedStageStillReads`).

---

### Task 1: API, days in stage, Open chat on cards

**Files:**
- Create: `src/app/Crm/deals/daysInStage.ts`, `daysInStage.test.ts`
- Modify: `src/app/api/crm.ts` (types `DealContact`, `ChannelDetails`; `ContactSearch.channelAccountId`; calls `dealContacts(token, ws, id)`, `channelDetails(token, ws, id)`, `importTemplate(token, ws, 'contacts' | 'companies')`, `settingsHistory(token, ws, {page, size})`), `src/test/crm.ts` (`sampleDealContact`), `src/app/Crm/deals/DealCard.tsx`, `src/app/Crm/deals/PipelinesPage.tsx`
- Test: `src/app/Crm/deals/pipelines.test.tsx` (extend)

**Interfaces:**
- `daysInStageLabel(deal: Pick<Deal, 'status' | 'stageEnteredAt'>, now?: Date): string | null` — null unless OPEN with a valid `stageEnteredAt`; whole days elapsed, never negative.
- `DealCard` gains optional `onOpenChat(deal: Deal): void`. The button is a plain icon button named `Open chat with <contact>`; its `onClick` and `onPointerDown` both stop propagation, so neither the card's click nor the card's drag listener fires.
- `PipelinesPage` holds one `useOpenChat`, passes `onOpenChat={(d) => d.primaryContact && chat.openChat(d.primaryContact.id, d.channel?.phoneNumberId)}`, and shows `chat.alert` as a dismissible warning beside the existing notice.

- [ ] **Step 1: Failing tests.** `daysInStage.test.ts`: `newInStageUnderADay`, `countsWholeDays`, `closedDealShowsNothing`, `missingOrBadDateShowsNothing`, `futureDateIsNotNegative`. `pipelines.test.tsx`: `cardShowsDaysInStage`, `chatButtonOpensTheChat`, `chatButtonDoesNotOpenTheDeal`, `chatButtonDoesNotStartADrag` (`fireEvent.pointerDown` on the button does not reach the card's listener; assert with the board's drag announcements unchanged), `cannotChatShowsTheReason`.
- [ ] **Step 2: Run** `npx vitest run src/app/Crm/deals --maxWorkers=4` → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; `npx tsc --noEmit`.
- [ ] **Step 5: Commit** `Show days in stage and open chats from deal cards`.

### Task 2: Several contacts on a deal

**Files:**
- Create: `src/app/Crm/deals/OtherContactsField.tsx`, `src/app/Crm/deals/DealContacts.tsx`, `src/app/Crm/deals/dealContacts.test.tsx`
- Modify: `src/app/Crm/deals/ContactPicker.tsx`, `src/app/Crm/deals/DealFormModal.tsx`, `src/app/Crm/deals/DealDrawer.tsx`

**Interfaces:**
- `ContactPicker` gains `channelAccountId?: string` (passed to the search, part of the load key), and `onChange(id: string, contact?: CrmContact)`.
- `OtherContactsField: FC<{value: {id: string; label: string}[]; onChange(next): void; channelAccountId?: string; primaryId: string; disabled?: boolean; disabledHint?: string; error?: string | null}>` — a `ContactPicker` that resets after each pick, plus a PatternFly `LabelGroup` of removable chips; the primary and already chosen contacts are refused; at 49 the picker disables with the cap copy.
- `DealFormModal`: on create the channel comes from the picked primary; changing the primary clears others. On edit `useLoad(dealContacts)` seeds the chips; the state records `loaded: boolean`. Create body sends `crmContactIds: [primary, ...others]`; edit body adds `crmContactIds` only when `loaded`. `CHANNEL_MISMATCH` and `crmContactIds` field errors show under the field.
- `DealContacts: FC<{dealId: string}>` — `useLoad(dealContacts)`, rows per spec C6, Open chat through `useOpenChat` with no number, its alert above the list.
- `DealDrawer`: a `contacts` tab after `details`.

- [ ] **Step 1: Failing tests.** `dealContacts.test.tsx`: `tabListsContactsPrimaryFirst`, `chatOpensThatContactsOwnChat`, `pickerOffersOnlyTheDealsChannelAccount` (the search body carries `channelAccountId`), `addsAndRemovesOtherContactsOnEdit`, `failedContactsLoadLeavesThemAlone` (PUT body lacks `crmContactIds`, field disabled with the failed-load copy), `disabledUntilPrimaryChosen`, `changingPrimaryClearsOtherContacts`, `createSendsPrimaryFirst`, `channelMismatchShowsUnderTheField`, `capAt49Others`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `npx vitest run src/app/Crm/deals --maxWorkers=4` → PASS (existing `dealForm.test.tsx` bodies stay valid: create still sends `crmContactIds: ['c1']`); `npx tsc --noEmit`.
- [ ] **Step 5: Commit** `Put several contacts on a deal`.

### Task 3: Inbox panel stage change and New deal

**Files:**
- Modify: `src/app/Crm/deals/DealFormModal.tsx`, `src/app/Crm/inbox/CrmChatPanel.tsx`
- Test: `src/app/Crm/inbox/crmChatPanel.test.tsx` (extend), `src/app/Crm/deals/dealForm.test.tsx` (extend)

**Interfaces:**
- `DealFormModal` gains `contact?: {id: string; label: string; channelAccountId?: string}` (fixes the primary; picker disabled) and `pipelines?: Pipeline[]` (a `Pipeline` select when more than one; changing it resets the stage to that pipeline's first OPEN stage). `pipeline` stays the initial value.
- `CrmChatPanel`: for `canEdit`, each open deal row holds a `FormSelect` named `Stage of <title>` with the pipeline's OPEN stages (plus the current stage when it is not OPEN). `moveDeal` then `setTick`; failures through `moveMessage` into the panel's existing `saveError`, a `VERSION_CONFLICT` also refetches. A `New deal` button shows when `canEdit` and a usable pipeline exists (active and within `status.pipelineAccess`); it opens `DealFormModal` with `contact`, `pipelines={usable}`, the settings from `refs`, and refetches on save. Every async result is ignored when the chat changed meanwhile.

- [ ] **Step 1: Failing tests.** `crmChatPanel.test.tsx`: `stageChangeMovesTheDealAndRefreshes`, `onlyOpenStagesAreOffered`, `viewerSeesNoStageSelect`, `moveFailureShowsInThePanel`, `lateMoveDoesNotTouchTheNextChat`, `newDealOpensTheFormWithTheContactFixed`, `newDealHiddenWithoutAUsablePipeline`, `newDealRefreshesTheSummary`. `dealForm.test.tsx`: `pipelineSelectResetsTheStage`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `npx vitest run src/app/Crm/inbox src/app/Crm/deals --maxWorkers=4` → PASS; `npx tsc --noEmit`.
- [ ] **Step 5: Commit** `Move deals and start new ones from the inbox panel`.

### Task 4: Contact drawer, company drawer, CSV template

**Files:**
- Modify: `src/app/Crm/Contacts/CrmContactDrawer.tsx`, `src/app/Crm/Companies/CompanyDrawer.tsx`, `src/app/Crm/csv/ImportModal.tsx`, `CompanyImportModal.tsx`, `ContactImportModal.tsx`
- Test: `src/app/Crm/Contacts/contactDrawer.test.tsx`, `src/app/Crm/Companies/companies.test.tsx`, `src/app/Crm/csv/csv.test.tsx` (extend)

**Interfaces:**
- Contact drawer: `useLoad(channelDetails)` with its error ignored; `Labels` as coloured compact labels and `Attributes` as `name: value` rows, each only when non-empty.
- Company drawer: an `Open chat` button per contact row, named `Open chat with <contact>`, using `useOpenChat` with `c.channel?.phoneNumberId`; the alert shows above the list.
- `ImportModal` gains `template: 'contacts' | 'companies'`; a `Download template` secondary button beside `Choose file` runs `importTemplate` then `saveFile`, its failure in the modal's alert. Hidden once a result shows.

- [ ] **Step 1: Failing tests.** `contactDrawer.test.tsx`: `labelsAndAttributesShow`, `emptyDetailsShowNoRows`, `failedDetailsHideTheRows`. `companies.test.tsx`: `openChatFromCompanyContacts`, `cannotChatShowsTheReason`. `csv.test.tsx`: `downloadsContactTemplate`, `downloadsCompanyTemplate`, `templateFailureShows`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `npx vitest run src/app/Crm/Contacts src/app/Crm/Companies src/app/Crm/csv --maxWorkers=4` → PASS; `npx tsc --noEmit`.
- [ ] **Step 5: Commit** `Show channel details on contacts, chat from companies, download import templates`.

### Task 5: Settings history, redacted timeline, build

**Files:**
- Create: `src/app/Crm/Settings/SettingsHistory.tsx`
- Modify: `src/app/Crm/Settings/CrmSettingsPage.tsx`, `src/app/Crm/activity/CrmTimeline.tsx`
- Test: `src/app/Crm/Settings/settings.test.tsx`, `src/app/Crm/activity/timeline.test.tsx` (extend)

**Interfaces:**
- `SettingsHistory: FC` — pages through `settingsHistory` (size 50) into `Timeline` with Load more; rows reuse `itemsFor`, whose default case shows the summary; `by` is the actor's name, or `System`.
- `CrmSettingsPage`: a `History` tab last, rendered only with `crm:settings`.
- `CrmTimeline.itemsFor`: `STAGE_CHANGED` missing both `fromStageId` and `fromStageName`, or both `toStageId` and `toStageName`, returns the entry's `summary` as its title; a `FIELD_CHANGED` change with `field === 'contacts'` titles `Changed contacts` and keeps the `from → to` body.

- [ ] **Step 1: Failing tests.** `settings.test.tsx`: `historyTabListsChanges`, `historyTabHiddenWithoutSettingsAuthority`, `historyLoadsMore`, `historyEmptyState`. `timeline.test.tsx`: `redactedMoveShowsItsSummary`, `redactedDestinationShowsItsSummary`, `removedStageStillReads` (an entry with `fromStageId` and no name keeps `Stage changed from a removed stage to …`), `contactsChangeReadsPlainly`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `npx vitest run --maxWorkers=4`, `npx tsc --noEmit`, `npm run lint`, `npm run build` → all clean.
- [ ] **Step 5: Commit** `Add the settings history tab and read redacted moves plainly`.

## Acceptance walk (after both plans, as `demo-manager` and `demo-agent`, app on :8089)

Spec §8, steps 1–9, in order.
