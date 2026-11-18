# Knowledge bases and moving a number: console implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the Knowledge tab on Brand setup → AI (knowledge bases and their sources), knowledge bases in the AI profile
form, and the super admin's **Move to brand…** with archived numbers shown as such.

**Architecture:** new API module `src/app/api/aiKnowledge.ts`; knowledge screens in `src/app/AI/knowledge/`; the move
dialog beside `AllPhoneNumbersPage`. Everything talks to the backend plan's endpoints
(`docs/superpowers/plans/2026-11-18-bimal-pitch-backend.md`, Tasks 2 and 6), which are the contract.

**Tech Stack:** React 18, TypeScript (target es5), PatternFly 6.5, react-router 6, vitest + testing-library.

**Spec:** `docs/superpowers/specs/2026-11-18-knowledge-bases-and-number-move-design.md` (§6.1, §7.1).

Work in `omnichannel-crm-frontend/.worktrees/bimal-pitch` (run `npm ci --prefer-offline` first).

## Global Constraints

- Copy exactly as in the spec: tab **Knowledge**; buttons **New knowledge base**, **Add file**, **Add web page**,
  **Add note**, **Fetch again**, **Retry**, **Edit**, **View text**, **Remove**; states shown as **Adding** (QUEUED,
  UPLOADING, INDEXING), **Ready**, **Failed: <error>**, **Removing**; the move dialog lines of spec §7.1; composer
  notice `This number moved to another brand on <d MMM>.`
- `ai:view` sees, `ai:manage` changes (`useAccess().hasInWorkspace`); the move needs `phone-number:manage-all`
  (`useAccess().has`).
- A source list refreshes itself every 3 s only while some source is QUEUED, UPLOADING, INDEXING or REMOVING, and only
  while the page is mounted.
- File input accepts `.pdf,.docx,.pptx,.txt,.md,.html`, up to 16 MB (checked before upload: `"Files up to 16 MB"`).
- Errors through `aiMessage(e)` (`src/app/AI/aiMessages.ts`) for AI screens; add `ai_knowledge_in_use`,
  `ai_knowledge_limit`, `ai_knowledge_file_type`, `ai_knowledge_no_store_support` to its server-worded codes.
- Prettier only on touched files (`{"singleQuote": true, "printWidth": 120}`); test first;
  `npx vitest run <path> --maxWorkers=4` while working; `npx tsc --noEmit && npm run lint && npm run build` and the
  full vitest run before merge. Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A source list left open after every source turned Ready must stop polling (no request every 3 s forever): pinned
   in Task 2 (`stopsRefreshingWhenNothingIsAdding`).
2. Removing a source, then the list refreshing before the server deleted the row, must show it as **Removing**, not
   bring it back as Ready: pinned in Task 2 (`removedSourceShowsRemovingUntilGone`).
3. A file over 16 MB must be refused in the browser with `"Files up to 16 MB"`, not sent: pinned in Task 2
   (`bigFileRefusedBeforeUpload`).
4. The profile form must show only knowledge bases on the profile's own connection, and must not list our own stores
   a second time under "Other stores": pinned in Task 3 (`knowledgeBasesGroupedAndNotRepeated`).
5. A move that did not finish must show **Retry** on the number's row after a reload: pinned in Task 4
   (`unfinishedMoveShowsRetry`).

---

### Task 1: API modules and fixtures

**Files:**
- Create: `src/app/api/aiKnowledge.ts`, `src/app/api/aiKnowledge.test.ts`, `src/test/aiKnowledge.ts`
- Modify: `src/app/api/phoneNumbers.ts` (+ status `'ARCHIVED'` in `PhoneNumberStatus` and `STATUSES`, fields
  `archiveOf: string | null`, `archivedAt: string | null`, `moveUnfinished?: boolean | null`; move functions),
  `src/app/api/ai.ts` (`AiProvider` gains `knowledgeBases: boolean`; fixture `PROVIDERS` in `src/test/ai.ts`: openai
  true, scripted false)

**Interfaces:**
- Produces:
  ```ts
  export type KnowledgeSourceState = 'QUEUED' | 'UPLOADING' | 'INDEXING' | 'READY' | 'FAILED' | 'REMOVING';
  export type KnowledgeSourceKind = 'FILE' | 'WEB_PAGE' | 'NOTE';
  export interface KnowledgeBase { id; name; description: string | null; connectionId; connectionName; provider;
    providerStoreId; status: 'READY' | 'FAILED'; error: string | null;
    counts: { queued: number; adding: number; ready: number; failed: number }; sourceCount: number;
    usedBy: string[]; createdAt; updatedAt; version: number }
  export interface KnowledgeSource { id; kind: KnowledgeSourceKind; title: string; url: string | null;
    fileName: string | null; contentType: string | null; size: number | null; state: KnowledgeSourceState;
    error: string | null; addedAt; addedBy: string | null; fetchedAt: string | null; version: number }
  listKnowledgeBases(token, ws): Promise<KnowledgeBase[]>
  getKnowledgeBase(token, ws, id); createKnowledgeBase(token, ws, {name, description, connectionId});
  saveKnowledgeBase(token, ws, id, {name, description, version}); deleteKnowledgeBase(token, ws, id): Promise<void>
  listKnowledgeSources(token, ws, kbId): Promise<KnowledgeSource[]>
  addKnowledgeFile(token, ws, kbId, file: File)          // FormData "file", apiFetchFile, then res.json()
  addKnowledgeWebPage(token, ws, kbId, url: string); addKnowledgeNote(token, ws, kbId, title, text)
  updateKnowledgeSource(token, ws, kbId, sourceId, body: {title?, text?, action?: 'FETCH_AGAIN' | 'RETRY'})
  removeKnowledgeSource(token, ws, kbId, sourceId): Promise<KnowledgeSource>
  knowledgeSourceText(token, ws, kbId, sourceId): Promise<string>   // GET .../content as text
  downloadKnowledgeSource(token, ws, kbId, sourceId): Promise<Blob>   // GET .../content as a blob (files)
  isAdding(s: KnowledgeSourceState): boolean                         // QUEUED | UPLOADING | INDEXING
  // phoneNumbers.ts
  export interface MovePreview { fromWorkspaceName; toWorkspaceName; chats: number; activeRuns: number;
    teamName: string | null; archiveLabel: string; movingBack: boolean; returningChats: number;
    rulesNamingNumber: string[] }
  export interface PhoneNumberMove { id; phoneNumberId; fromWorkspaceId; fromWorkspaceName; toWorkspaceId;
    toWorkspaceName; step: string; done: boolean; error: string | null;
    counts: { chats: number; messages: number; runsEnded: number }; startedAt; finishedAt: string | null }
  previewNumberMove(token, id, workspaceId): Promise<MovePreview>
  moveNumber(token, id, workspaceId): Promise<PhoneNumberMove>
  lastNumberMove(token, id): Promise<PhoneNumberMove>; retryNumberMove(token, id): Promise<PhoneNumberMove>
  ```
  Paths exactly as backend Task 2 (`/api/workspaces/{ws}/ai/knowledge-bases…`) and Task 6
  (`/api/phone-numbers/{id}/move…`). Fixtures in `src/test/aiKnowledge.ts`: `KB` (id `kb1`, "Bimal courses", on
  connection `k1`, store `vs_kb1`, counts {0,1,10,1}, usedBy ["Bimal counsellor"]), `SOURCES` (a READY file
  "CFTP Digital Brochure.pdf", a READY web page `https://bimalinstitute.com/crypto-forex-trading/`, a READY note
  "Counsellor pitch", a FAILED web page with error "The page answered 403", an INDEXING note), and
  `knowledgeRoutes(overrides)` in the style of `aiRoutes`.

- [ ] **Step 1: Write the failing tests** in `aiKnowledge.test.ts`: each function hits its method and path
  (`requestTo`); `addKnowledgeFile` sends a `FormData` whose `file` is the File (read from `fetch.mock.calls[0][1].body`);
  `knowledgeSourceText` returns the body text; `isAdding` true for the three adding states only; `moveNumber` POSTs
  `{workspaceId}`; `previewNumberMove` sends `?workspaceId=` encoded.
- [ ] **Step 2: Run** — `npx vitest run src/app/api/aiKnowledge.test.ts --maxWorkers=4`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** with `apiFetch` / `apiFetchFile` from `src/app/api/client.ts`.
- [ ] **Step 4: Run again** — same command, plus `npx tsc --noEmit`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(console): knowledge-base and number-move API clients`

### Task 2: The Knowledge tab and a knowledge base's page

**Files:**
- Create: `src/app/AI/knowledge/KnowledgeTab.tsx` (list + **New knowledge base**), `KnowledgeBaseModal.tsx` (create /
  rename), `KnowledgeBasePage.tsx` (route `/ai/knowledge/:id`: sources table and actions), `AddWebPageModal.tsx`,
  `NoteModal.tsx` (add and edit), `SourceTextModal.tsx`, `SourceState.tsx` (the state label), `useRefreshWhile.ts`
  (`useRefreshWhile(active: boolean, reload: () => void, ms = 3000)`), `knowledge.test.tsx`
- Modify: `src/app/AI/AiPage.tsx` (third tab `knowledge`: `?tab=knowledge`), `src/app/AI/routes.tsx` (the page
  route, `ai:view`), `src/app/AI/aiMessages.ts` (the four codes)

**Interfaces:**
- Consumes: Task 1.
- Layout (spec §6.1): the tab lists knowledge bases as rows — name, "OpenAI · <connection name>", "<n> sources ·
  <r> ready · <f> failed" (adding shown as "· <a> adding" when non-zero), "Used by: <names>", updated date — each
  linking to its page; row actions **Rename**, **Delete** (ConfirmModal; a 409 shows the server sentence). The create
  modal: Name (required), Description, Connection (select of the brand's connections whose provider has
  `knowledgeBases`; none → an inline info "Add an OpenAI connection first" with a link to the Connections tab). The
  page: title = name, breadcrumb back to AI → Knowledge, toolbar **Add file** (hidden file input), **Add web page**,
  **Add note**; a table with columns Source, Kind (File, Web page, Note), Size (KB/MB, "–" when unknown), State
  (`SourceState`) and an actions column: **View text** (web page, note) or **Download** (file, through
  `downloadKnowledgeSource`, saved with its `fileName`), **Fetch again** (web page, READY or FAILED), **Edit** (note), **Retry**
  (FAILED), **Remove** (ConfirmModal "Remove <title> from <kb name>? The AI stops using it."). Viewers (`ai:view`
  only) see no toolbar or row changes.

- [ ] **Step 1: Write the failing tests** in `knowledge.test.tsx` (`renderApp('/ai?tab=knowledge')` and
  `renderApp('/ai/knowledge/kb1')`, `meAs(WORKSPACE_MANAGER)`, `knowledgeRoutes()`, fake timers where polling is
  tested):
  - `listsKnowledgeBasesWithCountsAndUse`: row "Bimal courses" shows `12 sources · 10 ready · 1 failed · 1 adding`
    and `Used by: Bimal counsellor`.
  - `createsAKnowledgeBaseOnAnOpenAiConnection`: the connection select offers only `k1`; Save POSTs
    `{name, description, connectionId: 'k1'}` and the new row appears.
  - `deleteInUseShowsServerSentence`: 409 `ai_knowledge_in_use` "Used by profiles: Bimal counsellor" shown in the
    confirm modal.
  - `sourcesShowStates`: Ready, `Failed: The page answered 403`, Adding.
  - `stopsRefreshingWhenNothingIsAdding`: with one INDEXING source, advancing 3 s refetches sources; after the
    response has all READY, advancing 9 s makes no further request.
  - `addsAWebPageAndANote`: modal posts `{url}` / `{title, text}`; the new row shows Adding.
  - `bigFileRefusedBeforeUpload`: a 17 MB File → `Files up to 16 MB`, no request.
  - `uploadsAFile`: a small PDF → POST multipart, row Adding.
  - `removedSourceShowsRemovingUntilGone`: Remove → row label **Removing**; next refresh without the row → row gone.
  - `fetchAgainAndRetrySendActions`: PUT bodies `{action:'FETCH_AGAIN'}` and `{action:'RETRY'}`.
  - `viewTextShowsStoredMarkdown`.
  - `viewerSeesNoChangeButtons` (`ai:view` only).
- [ ] **Step 2: Run** — `npx vitest run src/app/AI/knowledge --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement** the components with `useLoad`, `useAction`, `ConfirmModal`, `PageShell` as in
  `ConnectionsTab` / `ProfilesTab`.
- [ ] **Step 4: Run again**, plus `src/app/AI` (existing AI tests stay green). Expected: PASS.
- [ ] **Step 5: Commit** — `feat(console): Knowledge tab with sources, states and self-refresh`

### Task 3: Knowledge bases in the AI profile form

**Files:**
- Modify: `src/app/AI/ProfileModal.tsx` (the Knowledge group, lines ~251–269), `src/app/AI/ProfilesTab.tsx:79`
  (count text), `src/app/AI/aiProfiles.test.tsx`

**Interfaces:**
- Consumes: `listKnowledgeBases` (Task 1), existing `listAiVectorStores`.
- The Knowledge group shows two sub-lists: **Knowledge bases** — this brand's knowledge bases on the selected
  connection, each a checkbox labelled `<name> (<ready> ready<, n adding><, n failed>)` whose value is its
  `providerStoreId`; then **Other stores in this OpenAI account** — today's list minus the store ids of those
  knowledge bases, labelled as today. Empty knowledge-base list: "No knowledge bases yet." with a link to
  `/ai?tab=knowledge`. The saved field stays `vectorStoreIds`. ProfilesTab's count reads `<n> knowledge source(s)`
  **(the words "vector store" leave the profile list)**.

- [ ] **Step 1: Write the failing tests** in `aiProfiles.test.tsx`:
  - `knowledgeBasesGroupedAndNotRepeated`: KB `kb1` (store `vs_kb1`, connection k1) and a KB on another connection;
    the provider list returns `vs_kb1` and `vs2` → group "Knowledge bases" has only "Bimal courses (10 ready, 1
    adding, 1 failed)"; "Other stores" has only "Return policy"; checking Bimal courses saves `vectorStoreIds`
    containing `vs_kb1`.
  - `noKnowledgeBasesLinksToTheTab`.
- [ ] **Step 2: Run** — `npx vitest run src/app/AI/aiProfiles.test.tsx --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run again**, plus `src/app/AI`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(console): AI profiles pick knowledge bases by name`

### Task 4: Move to brand, and archived numbers

**Files:**
- Create: `src/app/PhoneNumbers/MoveNumberModal.tsx`, `src/app/PhoneNumbers/moveNumber.test.tsx`
- Modify: `src/app/PhoneNumbers/AllPhoneNumbersPage.tsx` (row action **Move to brand…** for non-archived numbers
  when `has('phone-number:manage-all')`; a row with `moveUnfinished` shows a warning label "Move did not finish" and
  the action **Retry move**), `PhoneNumberStatusLabel.tsx` (`ARCHIVED` → "Archived", grey),
  `src/app/PhoneNumbers/PhoneNumbersPage.tsx` (`actionsFor`: an archive offers only Relabel and Delete),
  `src/app/Inbox/Composer.tsx` and `InboxPage.tsx:239` (an archived number → the notice `This number moved to another
  brand on <d MMM>.` from `archivedAt`, buttons disabled), `NumberFilter.tsx` (archived options end with
  " · archived")

**Interfaces:**
- Consumes: Task 1 move functions; `getCustomer(token, customerId)` (`src/app/api/customers.ts:58`) for the
  customer's brands.
- The modal (spec §7.1): title `Move <label> (<number>) from <brand>`; a select **To brand** of the customer's other
  brands (disabled ones excluded); once a brand is chosen it loads the preview and shows the bullet lines of spec §7.1
  from it (the moving-back variant when `movingBack`); a warning list "Rules in <from brand> that name this number:
  <names>" when any; **Move** (busy while posting) then closes and replaces the row (the number now shows the
  target brand); an error stays in the modal. Retry calls `retryNumberMove` and refreshes the row.

- [ ] **Step 1: Write the failing tests** in `moveNumber.test.tsx` (`meAs(SUPER_ADMIN)`, `/platform/phone-numbers`):
  - `moveShowsPreviewAndMoves`: choose "Bimal Institute" → text "Its 75 chats stay in Demo Fashion as "Main line
    (until 18 Nov)", read only."; Move POSTs `{workspaceId}`; the row shows brand Bimal Institute.
  - `movingBackWording`: preview `movingBack: true, returningChats: 75` → "The 75 archived chats in Demo Fashion
    return to Main line."
  - `rulesNamingNumberWarned`.
  - `unfinishedMoveShowsRetry`: a row with `moveUnfinished: true` → label "Move did not finish", action "Retry move"
    POSTs `/move/retry`.
  - `noMoveForArchivesOrWithoutManageAll`.
  - In the inbox tests (`src/app/Inbox/*.test.tsx`, the composer's file): `archivedNumberComposerNotice`.
  - In the brand page test: `archiveOffersOnlyRelabelAndDelete`.
- [ ] **Step 2: Run** — `npx vitest run src/app/PhoneNumbers src/app/Inbox --maxWorkers=4`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run again**, then the full gate: `npx vitest run --maxWorkers=4 && npx tsc --noEmit && npm run lint &&
  npm run build`. Expected: all pass.
- [ ] **Step 5: Commit** — `feat(console): move a number to another brand; archived numbers read only`
