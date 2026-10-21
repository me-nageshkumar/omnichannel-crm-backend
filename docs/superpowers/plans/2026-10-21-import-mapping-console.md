# Contact Import Mapping (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Import contacts dialog becomes two steps: pick a CSV, then map its columns to contact fields (with defaults, label creation and saved mappings) before importing; the existing result summary follows.

**Architecture:** Four new API calls and their types in `api/contacts.ts`; a pure `importMapping.ts` holds every rule about fitting a mapping to a file (so the dialog stays thin); `ImportContactsModal` keeps one `ImportMapping` in state and renders it as a table. The label picker of `FilterPanel` is extracted and reused for the labels default.

**Tech Stack:** React 18 + TypeScript, PatternFly 6.5.1, Vitest + Testing Library (`mockFetch`, `requestTo`, `callsTo` under `src/test/fetchMock.ts`).

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-campaigns-design.md` §4 (§4.3 is the layout) and §9 (import codes). Backend contract: `2026-10-21-import-mapping-backend.md`. Repo: `omnichannel-crm-frontend`.

## Global Constraints

- Copy, verbatim: `Saved mapping`, `None`, `matches this file`, `Contact field`, `CSV column`, `Default value`, `Phone number (required)`, `Not imported`, `Create labels that do not exist yet`, `Save this mapping as`, `Import N rows` (the button reads `Import 1 row` for one).
- `messageFor` sentences: `mapping_column_reused: 'A CSV column can be used only once.'`, `phone_column_required: 'Choose the column that holds the phone number.'`.
- The mapping is sent as a multipart part named `mapping` holding JSON text; `saveAs` is part of that JSON, present only when the "Save this mapping as" box is ticked with a non-blank name.
- Only the phone column is required; everything else may stay "Not imported" (a contact with only a phone is valid).
- A saved mapping whose attribute no longer exists drops that row silently; the rest is shown.
- Preview endpoint `POST /api/workspaces/{ws}/contacts/import/preview`, saved mappings `GET /api/workspaces/{ws}/contacts/import-mappings`, `DELETE .../import-mappings/{id}`.
- `npm run lint` and `npm test` must pass. Unused imports are lint errors.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A saved mapping that names a column this file does not have must show that field as unset ("Not imported" / no phone), never send the stale name (Task 2 test `fitDropsColumnsTheFileLacks`, Task 4 test `savedMappingWithMissingColumnsLeavesThemUnset`).
- Choosing one CSV column for two fields must disable Import and say so, not wait for the server (Task 3 test `reusedColumnDisablesImport`).
- Going Back and choosing a different file must start from that file's mapping, not the previous file's (Task 3 test `backAndAnotherFileStartsOver`).
- A failed preview (unreadable or oversize file) must leave the user on step 1 with the server's sentence and no stale table (Task 3 test `previewErrorStaysOnStepOne`).
- A file with no data rows must not offer an import (Task 3 test `emptyFileOffersNoImport`).

---

### Task 1: API calls, types and the two sentences

**Files:**
- Modify: `src/app/api/contacts.ts`
- Modify: `src/app/api/messages.ts`
- Create: `src/app/api/contacts.test.ts`
- Test: `src/app/api/messages.test.ts`

**Interfaces:**
- Produces, exported from `@app/api/contacts`:
  - `interface ImportAttributeMapping { attributeId: string; column: string | null; defaultValue: string | null }`
  - `interface ImportMapping { phone: string; name: string | null; labels: { column: string | null; defaults: string[] }; attributes: ImportAttributeMapping[]; createMissingLabels: boolean; saveAs?: string | null }`
  - `interface ImportPreview { headers: string[]; sample: string[][]; rows: number; suggested: ImportMapping & { phone: string | null }; savedMappings: Array<{ id: string; name: string; matches: boolean }> }`
  - `interface SavedImportMapping { id: string; name: string; headerSignature: string[]; mapping: ImportMapping; createdBy: string; creationTime: string; lastUsedAt: string | null }`
  - `previewImport(token: string, ws: string, file: File): Promise<ImportPreview>` → `POST ${base(ws)}/contacts/import/preview`, FormData `file`, via `apiFetchFile` like `importContacts`.
  - `importContacts(token: string, ws: string, file: File, mapping?: ImportMapping): Promise<ImportResult>`: the FormData gets a `mapping` text part (`JSON.stringify(mapping)`) only when `mapping` is given.
  - `listImportMappings(token: string, ws: string): Promise<SavedImportMapping[]>` → `GET .../contacts/import-mappings`.
  - `deleteImportMapping(token: string, ws: string, id: string): Promise<void>` → `DELETE .../contacts/import-mappings/{id}` (encode the id).

- [ ] **Step 1: Write the failing tests.** In `contacts.test.ts` (new; start with `afterEach(() => vi.unstubAllGlobals())`), `B = '/api/workspaces/w1'`:

```ts
const MAPPING: ImportMapping = { phone: 'Mobile', name: null, labels: { column: null, defaults: [] }, attributes: [], createMissingLabels: false };
const sentForm = (fetch: ReturnType<typeof mockFetch>, key: string) =>
  fetch.mock.calls.find(([input, init]) => init?.body instanceof FormData && String(input).endsWith(key.split(' ')[1]))?.[1]?.body as FormData;

it('previews a file as multipart', async () => {
  const fetch = mockFetch({ [`POST ${B}/contacts/import/preview`]: { status: 200, body: { headers: ['Mobile'], sample: [['1']], rows: 1, suggested: MAPPING, savedMappings: [] } } });
  const file = new File(['Mobile\n1\n'], 'p.csv', { type: 'text/csv' });
  await expect(previewImport('tok', 'w1', file)).resolves.toMatchObject({ rows: 1 });
  expect((sentForm(fetch, `POST ${B}/contacts/import/preview`).get('file') as File).name).toBe('p.csv');
  expect(requestTo(fetch, `POST ${B}/contacts/import/preview`)?.headers.get('Authorization')).toBe('Bearer tok');
});
it('imports with a mapping part holding the JSON', async () => { /* form.get('mapping') parses back to MAPPING; get('file') present */ });
it('imports without a mapping part when none is given', async () => { /* form.has('mapping') === false */ });
it('lists and deletes saved mappings', async () => { /* GET returns [..]; DELETE .../import-mappings/m%201 called for id 'm 1' via urlsOf; resolves undefined on 204 */ });
```

In `messages.test.ts`: the two codes produce the two sentences (build `ApiError` as the file's `chat_taken` test does).

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/api/contacts.test.ts src/app/api/messages.test.ts`
Expected: FAIL (`previewImport` not exported; generic sentence returned).

- [ ] **Step 3: Implement** the types and calls in `contacts.ts` (extend the existing `importContacts`, keep its current callers compiling) and the two `BY_CODE` entries in `messages.ts`.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/api && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/api/contacts.ts src/app/api/contacts.test.ts src/app/api/messages.ts src/app/api/messages.test.ts
git commit -m "feat(contacts): import preview, mapping and saved-mapping API calls"
```

---

### Task 2: `importMapping.ts`, the rules for fitting a mapping to a file

**Files:**
- Create: `src/app/Contacts/importMapping.ts`
- Test: `src/app/Contacts/importMapping.test.ts`

**Interfaces:**
- Consumes: Task 1's `ImportMapping`, `ImportPreview`, `SavedImportMapping`; `ContactLabel`, `ContactAttribute` from `@app/api/contacts`.
- Produces (all pure, exported):
  - `fitMapping(m: ImportMapping, headers: string[], labels: ContactLabel[], attributes: ContactAttribute[]): ImportMapping` — each column (phone, name, labels column, attribute columns) is matched to a header ignoring case and surrounding spaces and replaced by that header's exact spelling; a column the file lacks becomes `null` (`''` for `phone`); attribute rows whose `attributeId` is not in `attributes` are dropped, as are repeated ids after the first; `labels.defaults` ids not in `labels` are dropped; `saveAs` is removed.
  - `startingMapping(preview: ImportPreview, saved: SavedImportMapping[], labels: ContactLabel[], attributes: ContactAttribute[]): { savedId: string | null; mapping: ImportMapping }` — when exactly one `preview.savedMappings` entry has `matches` and its id is in `saved`, that mapping fitted; otherwise `preview.suggested` fitted (a null suggested phone becomes `''`) with `savedId: null`.
  - `reusedColumns(m: ImportMapping): string[]` — the headers (as written in `m`, compared ignoring case and spaces) chosen for more than one of phone, name, labels column and attribute columns; empty when none.
  - `requestOf(m: ImportMapping, saveAs: string | null): ImportMapping` — what is sent: phone as is, attribute rows kept only when they have a column or a non-blank default, blank defaults turned into `null` and non-blank ones trimmed, `saveAs` included (trimmed) only when non-blank.
  - `setAttribute(m: ImportMapping, attributeId: string, patch: Partial<Omit<ImportAttributeMapping, 'attributeId'>>): ImportMapping` — updates the row for that attribute or appends `{ attributeId, column: null, defaultValue: null, ...patch }`.

- [ ] **Step 1: Write the failing tests** (builders from `src/test/contacts.ts`: `label`, `attribute`):

```ts
const CITY = attribute({ id: 'a1', name: 'City' });
const VIP = label({ id: 'l1', name: 'VIP' });
const base = (over: Partial<ImportMapping> = {}): ImportMapping => ({ phone: 'Mobile', name: null, labels: { column: null, defaults: [] }, attributes: [], createMissingLabels: false, ...over });

it('fitSnapsColumnsToTheHeadersSpelling', () => {
  const m = fitMapping(base({ phone: ' mobile ', name: 'FULL NAME' }), ['Mobile', 'Full name'], [], []);
  expect(m.phone).toBe('Mobile'); expect(m.name).toBe('Full name');
});
it('fitDropsColumnsTheFileLacks', () => {
  const m = fitMapping(base({ phone: 'Phone', name: 'Name', labels: { column: 'Tags', defaults: [] }, attributes: [{ attributeId: 'a1', column: 'Town', defaultValue: 'Pune' }] }), ['Mobile'], [], [CITY]);
  expect(m.phone).toBe(''); expect(m.name).toBeNull(); expect(m.labels.column).toBeNull();
  expect(m.attributes).toEqual([{ attributeId: 'a1', column: null, defaultValue: 'Pune' }]);
});
it('fitDropsDeletedAttributesAndLabelsSilently', () => {
  const m = fitMapping(base({ labels: { column: null, defaults: ['l1', 'gone'] }, attributes: [{ attributeId: 'gone', column: 'Mobile', defaultValue: null }, { attributeId: 'a1', column: null, defaultValue: 'x' }, { attributeId: 'a1', column: null, defaultValue: 'y' }] }), ['Mobile'], [VIP], [CITY]);
  expect(m.labels.defaults).toEqual(['l1']); expect(m.attributes).toEqual([{ attributeId: 'a1', column: null, defaultValue: 'x' }]);
});
it('startingMapping preselects the one matching saved mapping', ...) // savedMappings [{id:'s1',matches:true},{id:'s2',matches:false}] -> savedId 's1', mapping fitted from saved s1
it('startingMapping falls back to the suggestion with none or two matches', ...) // savedId null, mapping = fitted suggested; suggested.phone null -> ''
it('startingMapping ignores a matching entry missing from the loaded list', ...) // savedId null
it('reusedColumns finds a header used twice ignoring case', () => {
  expect(reusedColumns(base({ name: 'mobile' }))).toEqual(['Mobile']);
  expect(reusedColumns(base({ name: 'Name', attributes: [{ attributeId: 'a1', column: 'Name', defaultValue: null }] }))).toEqual(['Name']);
  expect(reusedColumns(base())).toEqual([]);
});
it('requestOf drops empty attribute rows, nulls blank defaults and adds saveAs only when named', () => {
  const r = requestOf(base({ attributes: [{ attributeId: 'a1', column: null, defaultValue: '  ' }, { attributeId: 'a2', column: 'Town', defaultValue: ' ' }, { attributeId: 'a3', column: null, defaultValue: ' x ' }] }), '  Marketplace ');
  expect(r.attributes).toEqual([{ attributeId: 'a2', column: 'Town', defaultValue: null }, { attributeId: 'a3', column: null, defaultValue: 'x' }]);
  expect(r.saveAs).toBe('Marketplace');
  expect('saveAs' in requestOf(base(), '  ')).toBe(false);
});
it('setAttribute updates or appends a row', ...)
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Contacts/importMapping.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement** the five functions in `src/app/Contacts/importMapping.ts` with the signatures above (header comparison key: `trim().toLowerCase()`).

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Contacts/importMapping.test.ts && npm run lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/importMapping.ts src/app/Contacts/importMapping.test.ts
git commit -m "feat(contacts): rules for fitting an import mapping to a file"
```

---

### Task 3: The two-step dialog (file, then column mapping)

**Files:**
- Modify: `src/app/Contacts/ImportContactsModal.tsx`
- Modify: `src/app/Contacts/ContactsPage.tsx:387-395` (new props)
- Modify: `src/app/Contacts/contactImportExport.test.tsx` (import tests go through the new steps)
- Test: `src/app/Contacts/importMappingDialog.test.tsx`

**Interfaces:**
- Consumes: Task 1 calls; Task 2's `startingMapping`, `fitMapping`, `reusedColumns`, `requestOf`, `setAttribute`.
- Produces: `ImportContactsModal` props become
  `{ labels: ContactLabel[]; attributes: ContactAttribute[]; preview(file: File): Promise<ImportPreview>; loadSaved(): Promise<SavedImportMapping[]>; upload(file: File, mapping: ImportMapping): Promise<ImportResult>; onImported(): void; onClose(): void }`.
  `ContactsPage` passes `preview={(f) => previewImport(t, ws, f)}`, `loadSaved={() => listImportMappings(t, ws)}`, `upload={(f, m) => importContacts(t, ws, f, m)}`, and `labels.data ?? []`, `attributes.data ?? []`. `IMPORT_HINT` stays exported and shown on step 1.

Behaviour:
- Step 1: the current FileUpload and hint; footer `Next` (disabled without a file or while busy) and `Cancel`. `Next` runs `preview(file)` and `loadSaved()` together (one `action.run`); on success the dialog shows step 2 with `startingMapping(...)`. A failure shows the `messageFor` sentence as an inline danger alert and stays on step 1.
- Step 2 (title stays "Import contacts"): a table (`Table variant="compact"`, `aria-label="Column mapping"`) with headers `Contact field`, `CSV column`, `Default value`. Rows: `Phone number (required)` (FormSelect `aria-label="Phone number column"`, placeholder option `Choose a column` with value `''`), `Name` (`aria-label="Name column"`), `Labels` (`aria-label="Labels column"`), then one row per attribute (`aria-label="<attribute name> column"`). Every select except phone has first option `Not imported` (value `''`, stored as `null`); the other options are the non-blank headers. Default cells are `–` for phone and name; this task leaves the labels and attribute default cells empty (Task 4).
- Below the table: the sample table (`aria-label="Sample rows"`, columns `Phone` and `Name`, the five `preview.sample` rows read from the chosen phone and name columns; the Name column only when a name column is chosen), and the sentence `N rows in this file`.
- When `reusedColumns(mapping)` is non-empty: an inline danger alert `A CSV column can be used only once.` and the primary button disabled.
- Footer: primary `Import N rows` (`Import 1 row` for one), disabled while the phone column is `''`, `rows === 0`, columns are reused, or busy; `Back` (returns to step 1 keeping the chosen file; choosing another file there and pressing `Next` rebuilds the mapping from that file's preview); `Cancel`. Submitting calls `upload(file, requestOf(mapping, null))`, then shows the existing summary (and `onImported()`); errors show through `action.error`.

- [ ] **Step 1: Write the failing tests** in `importMappingDialog.test.tsx` (copy the setup of `contactImportExport.test.tsx`: `B`, `routes()` with `meAs(WORKSPACE_MANAGER)`, search, `contact-labels` `[VIP]`, `contact-attributes` `[CITY]` (`attribute({id:'a1', name:'City'})`), `contact-filters` `[]`, plus `PREVIEW = POST ${B}/contacts/import/preview`, `MAPPINGS = GET ${B}/contacts/import-mappings` returning `[]`, `IMPORT`). Helpers: `PREVIEW_BODY(over)` = `{ headers: ['Mobile','Full name','Town'], sample: [['9812345678','Asha','Pune'], ...5 rows], rows: 7, suggested: { phone: null, name: null, labels: {column:null, defaults:[]}, attributes: [], createMissingLabels: false }, savedMappings: [] }`; `openAndNext(user)` opens the dialog, uploads `people.csv`, clicks `Next`; `sentMapping(fetch)` returns `JSON.parse((formData.get('mapping')) as string)` of the FormData POSTed to `IMPORT`.

```ts
it('stepOneNextPreviewsTheFile', async () => { /* Next disabled before a file; after upload click Next; callsTo(PREVIEW) 1, callsTo(MAPPINGS) 1; dialog shows 'Column mapping' table with headers Contact field / CSV column / Default value and the rows Phone number (required), Name, Labels, City */ });
it('suggestedColumnsArePreselected', async () => { /* suggested {phone:'Mobile', name:'Full name'} -> selects show those values; City row 'Not imported' */ });
it('importSendsTheChosenMapping', async () => {
  /* suggested phone null: Import 7 rows is disabled; choose 'Mobile' in 'Phone number column', name 'Full name', City column 'Town';
     click 'Import 7 rows'; expect(sentMapping(fetch)).toEqual({ phone:'Mobile', name:'Full name', labels:{column:null, defaults:[]},
       attributes:[{attributeId:'a1', column:'Town', defaultValue:null}], createMissingLabels:false });
     expect(JSON.stringify(sentMapping(fetch))).not.toContain('saveAs'); summary shows '3 created' etc. as before; (file part is people.csv) */ });
it('phoneOnlyMappingIsEnough', async () => { /* only phone chosen -> mapping name null, no attributes; import succeeds */ });
it('sampleRowsFollowTheChosenColumns', async () => { /* Sample rows table shows 5 rows; switching phone column to 'Town' changes the first cell to 'Pune'; no Name column when name is Not imported */ });
it('reusedColumnDisablesImport', async () => { /* name -> 'Mobile' while phone 'Mobile': 'A CSV column can be used only once.' visible, Import disabled; fix it, enabled; callsTo(IMPORT) 0 throughout */ });
it('importButtonSaysOneRow', async () => { /* rows: 1 -> 'Import 1 row' */ });
it('emptyFileOffersNoImport', async () => { /* rows: 0 -> 'Import 0 rows' disabled */ });
it('previewErrorStaysOnStepOne', async () => { /* PREVIEW 400 {code:'VALIDATION', message:'file is empty', fields:{file:'is empty; a header row with a phone column is required'}} -> alert text from messageFor, no 'Column mapping' table, Next still present */ });
it('backAndAnotherFileStartsOver', async () => { /* first file suggests phone 'Mobile'; Back; upload other.csv whose PREVIEW answer (function route, second call) suggests 'Phone'; Next; phone select shows 'Phone'; callsTo(PREVIEW) 2 */ });
it('serverMappingErrorShowsItsSentence', async () => { /* IMPORT 400 {code:'PHONE_COLUMN_REQUIRED'} -> 'Choose the column that holds the phone number.' stays on step 2 */ });
it('importCannotBeSubmittedTwice', async () => { /* IMPORT answered by a deferred Response; double click 'Import 7 rows' -> callsTo(IMPORT) 1 */ });
```

Update `contactImportExport.test.tsx`: its `uploadAndImport` helper clicks `Next` after choosing the file, then `Import 1 row`-style button (adapt: add a `PREVIEW` route with `rows: 1` and `suggested.phone: 'phone'`, headers `['phone','name']`) and the `importShowsSummary` assertion on the sent FormData keeps checking `file` and now also that `mapping` is present. The test that checks `IMPORT_HINT` stays on step 1.

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Contacts/importMappingDialog.test.tsx src/app/Contacts/contactImportExport.test.tsx`
Expected: FAIL (no `Next` button, no mapping table).

- [ ] **Step 3: Implement** the new props, the two steps and the page wiring as described under Behaviour. Keep the summary block unchanged. Use `useAction()` for both the preview and the import so `busy` guards double submits.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Contacts && npm run lint`
Expected: PASS (the other Contacts tests untouched).

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/ImportContactsModal.tsx src/app/Contacts/ContactsPage.tsx src/app/Contacts/contactImportExport.test.tsx src/app/Contacts/importMappingDialog.test.tsx
git commit -m "feat(contacts): import dialog maps CSV columns before importing"
```

---

### Task 4: Saved mappings, defaults, label creation and Save as

**Files:**
- Create: `src/app/Contacts/LabelPicker.tsx` (moved out of `FilterPanel.tsx`, exported with the same props: `{ title: string; labels: ContactLabel[]; selected: string[]; onChange(ids: string[]): void }`)
- Modify: `src/app/Contacts/FilterPanel.tsx` (import `LabelPicker` instead of defining it; drop the imports that became unused)
- Modify: `src/app/Contacts/ImportContactsModal.tsx`
- Test: `src/app/Contacts/importMappingDialog.test.tsx`

**Interfaces:**
- Consumes: Task 2's `startingMapping`, `fitMapping`, `setAttribute`, `requestOf`; Task 3's modal state.
- Produces: no new exports besides `LabelPicker`.

Behaviour:
- Above the table, `FormSelect` `aria-label="Saved mapping"` (visible label `Saved mapping`): option `None` (value `''`) then the saved mappings in the order of `preview.savedMappings`; an entry with `matches` reads `<name> (matches this file)`. Entries are the names of the loaded `SavedImportMapping`s that appear in `preview.savedMappings`. Its initial value is `startingMapping(...).savedId`. Choosing a saved mapping sets the table to `fitMapping(saved.mapping, headers, labels, attributes)`; choosing `None` sets it to the fitted `preview.suggested`. Later edits to the table keep the select as it is.
- Labels row, default cell: `LabelPicker` with title `Labels` (its toggle shows the count badge); the picked ids are `mapping.labels.defaults`.
- Attribute rows, default cell: `TextInput` `aria-label="<attribute name> default"` bound to `defaultValue` (empty string shown for null).
- Checkbox `Create labels that do not exist yet` bound to `createMissingLabels`.
- Checkbox `Save this mapping as` and, enabled only when ticked, a `TextInput` `aria-label="Mapping name"`; Import stays enabled while the box is ticked with an empty name, and an empty or blank name sends no `saveAs`. A `DUPLICATE` error on `name` (use `useAction(['name'])`) shows under the name box as the field error text and keeps the dialog on step 2; `name` errors do not appear in the top alert.
- Submitting sends `requestOf(mapping, saveAsTicked ? name : null)`.

- [ ] **Step 1: Write the failing tests** (same file and helpers as Task 3; `SAVED_A = {id:'s1', name:'Marketplace', headerSignature:['full name','mobile','town'], mapping:{phone:'Mobile', name:'Full name', labels:{column:null, defaults:['l1']}, attributes:[{attributeId:'a1', column:'Town', defaultValue:null}], createMissingLabels:true}, createdBy:'meera', creationTime:'t', lastUsedAt:null}`, `SAVED_B` similar named `Other`):

```ts
it('oneMatchingSavedMappingIsPreselected', async () => {
  /* MAPPINGS [SAVED_A, SAVED_B]; preview savedMappings [{s1, matches:true},{s2, matches:false}];
     'Saved mapping' select shows 'Marketplace (matches this file)'; phone select 'Mobile', City column 'Town';
     'Create labels that do not exist yet' is checked; Labels toggle shows badge 1; options are None, Marketplace (matches this file), Other in that order */ });
it('twoMatchesOrNoneShowTheSuggestion', async () => { /* both matches:true -> select 'None', table from suggested */ });
it('choosingAndClearingASavedMapping', async () => { /* start None with suggestion phone 'Full name'; choose 'Other' -> table follows SAVED_B; choose None -> back to suggestion */ });
it('savedMappingWithAMissingAttributeDropsThatRow', async () => { /* SAVED_A has attributeId 'gone' besides a1; the table has no extra row and the sent mapping has only a1 */ });
it('savedMappingWithMissingColumnsLeavesThemUnset', async () => { /* headers ['Mobile'] only; saved name 'Full name' -> 'Name column' shows 'Not imported'; sent mapping.name null */ });
it('defaultsAreSent', async () => {
  /* type 'Pune' into 'City default'; pick label VIP in the Labels picker (button /^Labels/ then option 'VIP'); tick 'Create labels that do not exist yet';
     Import -> sentMapping has attributes [{attributeId:'a1', column:null, defaultValue:'Pune'}], labels.defaults ['l1'], createMissingLabels true */ });
it('saveAsIsSentOnlyWhenTickedAndNamed', async () => {
  /* tick 'Save this mapping as', type 'Marketplace' in 'Mapping name' -> sentMapping.saveAs 'Marketplace';
     a second test: ticked but blank -> no saveAs key; unticked after typing -> no saveAs key */ });
it('duplicateSaveAsNameShowsUnderTheBox', async () => { /* IMPORT 409 {code:'DUPLICATE', field:'name', message:'x'} -> text under the name box, still on step 2, no summary */ });
```

Also run `contactFilters.test.tsx` unchanged to prove the `LabelPicker` extraction.

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/app/Contacts/importMappingDialog.test.tsx`
Expected: new tests FAIL (no Saved mapping select, no defaults).

- [ ] **Step 3: Implement.** Extract `LabelPicker` verbatim first (run `npx vitest run src/app/Contacts/contactFilters.test.tsx` green), then extend the modal as described.

- [ ] **Step 4: Run to verify it passes**

Run: `npx vitest run src/app/Contacts && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/LabelPicker.tsx src/app/Contacts/FilterPanel.tsx src/app/Contacts/ImportContactsModal.tsx src/app/Contacts/importMappingDialog.test.tsx
git commit -m "feat(contacts): saved mappings, defaults, label creation and save-as in the import dialog"
```
