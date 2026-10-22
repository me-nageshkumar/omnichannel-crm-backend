# Advanced Contact Filters (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brand users can build a nested AND/OR/NOT contact filter over contact fields, labels and attributes, check its count and a sample, see the compiled query, save it by name, and use it on the Contacts page and in campaigns beside today's simple saved filters.

**Architecture:** The rule tree is the spec's `RuleGroup`, typed in `api/contacts.ts` and edited through pure, unit-tested functions in `Contacts/Filters/filterModel.ts` (field table, operators, value kinds, edits at a path, caps, completeness, missing ids, server error paths). A recursive PatternFly editor (`RuleTreeEditor` + `RuleEditor`) renders the tree; `FilterBuilderPage` adds Name, count, sample, the `Rules`/`Query` tabs and save; `SavedFiltersPage` lists both kinds. The Contacts page learns the ADVANCED kind (label, filter ANDed with the panel, `Filter builder` button).

**Editor choice: hand-rolled tree editor** over the spec's `RuleGroup` (spec §8 and §11 allow this). `react-querybuilder@8.25.0` (MIT, peer `react >=18`, published 2026-10-12) was checked through its README and published types. It works with React 18 and has the hooks we would need (`controlElements`, `maxLevels`, `showNotToggle`, `listsAsArrays`). It is still a poor fit:
- Its "headless core", `@react-querybuilder/core`, holds only formatters and parsers. The editor is the `QueryBuilder` component of `react-querybuilder`, which brings `@reduxjs/toolkit`, `react-redux` and `immer`.
- Every visible piece would be a custom PF control anyway: the grouped field picker, the §8 operator labels, seven value editors, Not, the combinator, add and remove, the Missing state and the disabled `Add group` at the cap.
- It stamps an `id` on every rule and group. Those would have to be stripped from every preview and save body.
- The tree logic it would save is about ten pure functions, which Task 2 unit-tests anyway.

No new dependency.

**Tech Stack:** React 18 + TypeScript (strict), PatternFly 6.5.1 (`FormSelect`, `FormSelectOptionGroup`, `ToggleGroup`, `Checkbox`, `Tabs`, `CodeBlock`, `Card` from `@patternfly/react-core`, all present in `node_modules`; `@patternfly/react-table`), React Router 7, Vitest + Testing Library (`mockFetch`, `requestTo`, `callsTo` in `src/test/fetchMock.ts`; `gate` in `src/test/gate.ts`; `urlsOf`, `rowWith`, `rowAction`, `fill` in `src/test/admin.ts`).

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-22-advanced-contact-filters-design.md` §3, §7, §8, §9, §10 (console half), §12. Repo: `omnichannel-crm-frontend` (all paths below are relative to it). The backend is a separate plan.

## Contract assumptions

Where the spec leaves a name or shape open, this plan uses the following. Reconcile them with the backend plan before either plan is executed.

1. Paths under `/api/workspaces/{ws}`: `GET /contact-filters`, `GET|PUT|DELETE /contact-filters/{id}`, `POST /contact-filters`, `POST /contact-filters/preview`. An unknown id → 404 `NOT_FOUND`.
2. One filter view for list items and `GET /{id}`: `{ id, name, kind: 'SIMPLE'|'ADVANCED'|null, filter: ContactFilterSpec|null, rules: RuleGroup|null, query: object|null, uses: { attributeIds: string[], labelIds: string[] }|null, createdBy, creationTime, lastUpdateTime }`.
   - `kind` null or absent reads as SIMPLE. `filter` is null for ADVANCED; `rules`, `query` and `uses` are null for SIMPLE.
   - `query` is the compiled query, parsed into a JSON object (extended JSON for dates), without the workspace clause.
   - `lastUpdateTime` is new; it fills the "Updated" column. The console falls back to `creationTime` when it is absent.
   - The console never reads `uses`; it finds missing ids from the tree itself.
3. Creating or updating an ADVANCED filter sends exactly `{ name, rules }`. The reply is the filter view (201 on create).
4. Preview requests:
   - `Check count` and Save's re-check send `{ rules }`. `Show sample` sends `{ rules, sample: 10 }`.
   - The reply is `{ count, sample: Contact[], query }`. When `sample` is absent the server may return any sample; the console ignores it.
5. Value shapes in a `Rule`:
   - text: a string. Enum `is`/`isNot`: `'CHAT'|'IMPORT'|'MANUAL'`; `in`/`notIn`: `string[]`. Boolean: `'true'|'false'`.
   - Date: `'YYYY-MM-DD'`, sent as typed with no time-zone conversion; `between`: `[from, to]`.
   - Labels `has`/`hasNot`: one label id; `hasAny`/`hasAll`/`hasNone`: `string[]`.
   - `isSet`, `isNotSet`, `isEmpty`, `isNotEmpty`: `null`.
   - String values are sent trimmed. Every group carries `not` (false when unchecked).
6. Depth: the root group is level 1, and a group may contain a group only while its own level is below 5. "50 rules" counts rules, not groups.
7. A VALIDATION reply is `{ code: 'VALIDATION', message, fields: { '<path>': '<sentence>' } }`:
   - Paths are written as in §3 (`rules[2].rules[0].value`). `rules` alone means the root group; `name` means the name.
   - A trailing `.value`, `.field`, `.operator`, `.combinator`, `.not` or `.rules` refers to the node before it.
8. A 409 `FILTER_IN_USE` reply is `{ code, message, filters: [{ id, name }], campaigns: [{ id, name }] }`. Both arrays are top-level and either may be empty.
9. A timeout reply is 504 `{ code: 'QUERY_TIMEOUT', message }`, from preview, search, count, export and campaign start.
10. **Backend change:** `POST /contacts/count` and `POST /contacts/bulk` accept a `selection` of `{ filterId, filter }` together, ANDed the way search and export already AND them. `{ ids }` stays exclusive. This lets "Select all N matching" act on what the table shows when the Filters panel is applied on top of an ADVANCED filter. Today `ContactBulkService.criteria` rejects two keys.

## Global Constraints

- React 18 + TypeScript strict + PatternFly 6.5.1; no new dependencies.
- Authorities (§9): `contact:view` opens `/contacts/filters` and runs Count. `contact:bulk` shows `Filter builder`, `New filter`, Edit and Delete, and opens the builder: `/contacts/filters/new` and `/contacts/filters/:id` render `<NotFound />` without it, the same pattern as `CampaignSettingsPage`.
- Routes: `/contacts/filters`, `/contacts/filters/new` and `/contacts/filters/:id`, none of them in the nav. They sit in `routes.tsx` before `/contacts/:id`, under the existing ordering comment style.
- Caps (§3): 5 levels, 50 rules, 20 values per list, 200 characters per value. Sample: 10 rows (name, phone, labels).
- Copy, verbatim from the spec:
  - Page and builder copy: `Filter builder`, `Advanced`, `Check count`, `N contacts` (via `countText`), `Show sample`, tabs `Rules` / `Query`, `Add rule`, `Add group`, `All of these` / `Any of these`, `Not`, `Missing attribute` / `Missing label`, `Save`, `Cancel`.
  - Field groups: `Contact fields`, `Labels`, `Attributes`.
  - Operator labels: `is`, `is not`, `contains`, `does not contain`, `starts with`, `ends with`, `is set`, `is not set`, `is any of`, `is none of`, `is before`, `is after`, `is between`, `has`, `does not have`, `has any of`, `has all of`, `has none of`, `has no labels`, `has labels`.
- Copy chosen here (the spec is silent):
  - Page titles `Saved filters`, `New filter`, `Edit filter`; links `Back to contacts`.
  - Kinds `Simple` / `Advanced`; per-row button `Count`; empty list `No saved filters yet.`.
  - Query tab before any preview: `Check the count to see the query.`.
  - Field labels `Name`, `Phone`, `Source`, `Number hidden`, `Do not contact`, `Created`, `Last message`; boolean values `Yes` / `No`.
- `messageFor` sentences (§7), verbatim:
  - `query_timeout: 'This filter takes too long to run. Make it more specific.'`
  - `filter_in_use: 'This is used by a saved filter or a campaign. Remove it there first.'`, followed by ` Saved filters: A, B.` and ` Campaigns: C.` when those lists are non-empty.
- Wording: "Enabled/Disabled", never "block", in any user-facing text.
- Tests drive the UI by role and accessible name and assert on `requestTo(...).body`. Date inputs are set with `fireEvent.change(input, { target: { value } })`; user-event typing into `type="date"` is unreliable in jsdom.
- `npm run type-check`, `npm run lint` and `npm test` pass at the end of every task.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- **Panel edits on an ADVANCED filter.** After picking an ADVANCED filter, editing the Filters panel or the search box must AND onto it. Today any panel change clears `savedId`, which would silently drop the advanced part. `Save filter` must be disabled while an ADVANCED filter is picked, because it would save the panel alone. Task 4: `panelEditsKeepTheAdvancedFilter`, `saveFilterIsOffForAnAdvancedFilter`.
- **A stale count after an edit.** A count must never stand for a tree it was not computed for. After an edit, Save re-runs the preview, and a preview answer for an older tree, arriving after the edit, must neither show its count nor let Save skip the re-check. Task 5: `saveRePreviewsAChangedTree`, `aStalePreviewDoesNotCountForTheNewTree`.
- **Value shape after a field or operator change.** Changing a rule's field or operator must reset its value to the new kind's shape (from `is any of` `[...]` to `is` `''`, or from text to date). Otherwise an array goes where a string belongs and the server answers 400. Task 2: `withFieldPicksTheFirstOperatorAndAnEmptyValue`, `withOperatorKeepsACompatibleValue`; Task 3: `changingTheFieldResetsTheValue`.
- **Removing a group's last rule.** It must not leave an empty nested group, which the server rejects (`rules` non-empty). An emptied root disables Check count and Save. Task 2: `removingTheLastRuleRemovesItsGroup`; Task 5: `checkAndSampleNeedACompleteTree`.
- **Dates and the time zone.** Dates go as typed (`2026-09-01`): the server applies the workspace zone. They are never converted through the browser's zone, as `FilterPanel`'s `dayStart` does. A `between` whose From is after its To is incomplete. Task 2: `isCompleteChecksEveryRule`; Task 3: `buildsTheSpecTreeByRoleAndName` (exact date string in the tree).

---

### Task 1: API types and calls, error sentences, test builders

**Files:**
- Modify: `src/app/api/contacts.ts`, `src/app/api/client.ts`, `src/app/api/messages.ts`, `src/test/contacts.ts`, `src/app/Contacts/ContactsPage.tsx` (the `createFilter` call site only)
- Create: `src/app/api/contacts.test.ts`
- Test: `src/app/api/contacts.test.ts`, `src/app/api/messages.test.ts`, `src/app/api/client.test.ts`, `src/app/Contacts/contactFields.test.tsx`, `src/app/Campaigns/newCampaign.test.tsx`

**Interfaces:**
- Produces, in `api/contacts.ts`:
  - `type Combinator = 'and' | 'or'`
  - `interface Rule { field: string; operator: string; value: string | string[] | null }`
  - `interface RuleGroup { combinator: Combinator; not: boolean; rules: (Rule | RuleGroup)[] }`
  - `type FilterKind = 'SIMPLE' | 'ADVANCED'`
  - `interface FilterUses { attributeIds: string[]; labelIds: string[] }`
  - `SavedFilter` becomes `{ id; name; kind?: FilterKind | null; filter: ContactFilterSpec | null; rules?: RuleGroup | null; query?: Record<string, unknown> | null; uses?: FilterUses | null; createdBy; creationTime; lastUpdateTime?: string }`
  - `isAdvanced(f: Pick<SavedFilter, 'kind'>): boolean` (null or undefined → false)
  - `interface FilterPreview { count: number; sample: Contact[]; query: Record<string, unknown> }`
  - `FILTER_SAMPLE_SIZE = 10`
  - `type FilterBody = { name: string; filter: ContactFilterSpec } | { name: string; rules: RuleGroup }`
  - `createFilter(token, ws, body: FilterBody): Promise<SavedFilter>` and `updateFilter(token, ws, id, body: FilterBody): Promise<SavedFilter>`. These replace the `(name, filter)` signatures; `ContactsPage`'s `NameModal` call becomes `createFilter(t, ws, { name, filter })`.
  - `getFilter(token, ws, id): Promise<SavedFilter>` → `GET ${base(ws)}/contact-filters/${id}`
  - `previewFilter(token, ws, rules: RuleGroup, sample?: number): Promise<FilterPreview>` → `POST ${base(ws)}/contact-filters/preview` with body `{ rules }`, or `{ rules, sample }` when `sample` is given
  - `Selection` gains the member `{ filterId: string; filter: ContactFilterSpec }` (Contract assumption 10). `exportContacts`' `scope` gains the same member.
- Produces, in `api/client.ts`:
  - `interface NamedRef { id: string; name: string }`
  - `ApiError` gains a 9th constructor parameter `usedBy?: { filters: NamedRef[]; campaigns: NamedRef[] }`. `failure()` sets it when `data.filters` or `data.campaigns` is an array, with the missing one as `[]`.
- Produces, in `api/messages.ts`: `query_timeout` in `BY_CODE`, and a `filter_in_use` branch in `messageFor` that builds the Global Constraints sentence. Names are joined with `, `; a part is omitted when its list is empty or `usedBy` is absent.
- Produces, in `src/test/contacts.ts`:
  - `savedFilter()` defaults gain `kind: 'SIMPLE'` and `lastUpdateTime: '2026-10-20T10:00:00Z'`.
  - `LAPSED_TREE: RuleGroup`:
    ```ts
    { combinator: 'and', not: false, rules: [
      { field: 'labels', operator: 'has', value: 'l1' },
      { field: 'lastMessageAt', operator: 'before', value: '2026-09-01' },
      { combinator: 'or', not: true, rules: [
        { field: 'attributes.a1', operator: 'is', value: 'Pune' },
        { field: 'source', operator: 'in', value: ['CHAT', 'IMPORT'] },
      ] },
    ] }
    ```
  - `LAPSED_QUERY = { $and: [{ labels: 'l1' }, { lastMessageAt: { $lt: { $date: '2026-08-31T18:30:00Z' } } }, { $nor: [{ $or: [{ 'attributes.a1': { $regex: '^Pune$', $options: 'i' } }, { source: { $in: ['CHAT', 'IMPORT'] } }] }] }] }`
  - `advancedFilter(over)`, defaulting to `{ kind: 'ADVANCED', filter: null, rules: LAPSED_TREE, query: LAPSED_QUERY, uses: { attributeIds: ['a1'], labelIds: ['l1'] }, createdBy: 'meera', creationTime: '2026-10-20T10:00:00Z', lastUpdateTime: '2026-10-21T10:00:00Z' }`
  - `filterPreview(over)`, defaulting to `{ count: 1204, sample: [], query: LAPSED_QUERY }`

- [ ] **Step 1: Write the failing tests.** `contacts.test.ts` (`B = '/api/workspaces/w1'`):

```ts
it('previewSendsTheTreeAndSample', ...)    // previewFilter(t,'w1',LAPSED_TREE,10) → requestTo(POST `${B}/contact-filters/preview`).body toEqual {rules: LAPSED_TREE, sample: 10}; without sample → toEqual {rules: LAPSED_TREE} (no 'sample' key); resolves the reply
it('getsOneFilter', ...)                  // GET `${B}/contact-filters/f2` → resolves advancedFilter({id:'f2', name:'Lapsed VIPs'})
it('createsAnAdvancedFilter', ...)        // createFilter(t,'w1',{name:'Lapsed VIPs', rules: LAPSED_TREE}) → POST body toEqual exactly that
it('updatesAnAdvancedFilter', ...)        // updateFilter(t,'w1','f2',{name:'Lapsed VIPs 2', rules: LAPSED_TREE}) → PUT `${B}/contact-filters/f2` body toEqual that
it('createsASimpleFilterAsBefore', ...)   // {name:'Chat people', filter:{source:['CHAT']}} → POST body toEqual that
it('isAdvancedReadsNullAsSimple', ...)    // isAdvanced({kind: null}) false; ({}) false; ({kind:'SIMPLE'}) false; ({kind:'ADVANCED'}) true
```

`messages.test.ts`:
- `new ApiError(504, 'QUERY_TIMEOUT')` → `This filter takes too long to run. Make it more specific.`
- `new ApiError(409, 'FILTER_IN_USE', …, usedBy {filters: [{id:'f2', name:'Lapsed VIPs'}, {id:'f3', name:'Pune'}], campaigns: [{id:'c1', name:'Diwali'}]})` → `This is used by a saved filter or a campaign. Remove it there first. Saved filters: Lapsed VIPs, Pune. Campaigns: Diwali.`
- Only campaigns → `… Remove it there first. Campaigns: Diwali.`
- No `usedBy` → the bare sentence.

`client.test.ts`: a 409 body `{code:'FILTER_IN_USE', filters:[{id:'f2', name:'Lapsed VIPs'}]}` → `error.usedBy` toEqual `{filters:[{id:'f2', name:'Lapsed VIPs'}], campaigns:[]}`; a body without either key → `usedBy` undefined.

`contactFields.test.tsx`:

```ts
it('deleteLabelInUseNamesTheUsers', ...)     // DELETE `${B}/contact-labels/l1` 409 {code:'FILTER_IN_USE', message:'x', filters:[{id:'f2',name:'Lapsed VIPs'}], campaigns:[{id:'c1',name:'Diwali'}]} → rowAction VIP 'Delete', confirm 'Delete' → dialog shows 'This is used by a saved filter or a campaign. Remove it there first. Saved filters: Lapsed VIPs. Campaigns: Diwali.'; row 'VIP' still listed after Cancel
it('deleteAttributeInUseNamesTheUsers', ...) // attribute City, 409 with filters only → '… Remove it there first. Saved filters: Lapsed VIPs.'
```

`newCampaign.test.tsx`:

```ts
it('listsBothFilterKinds', ...)  // contact-filters [PUNE, advancedFilter({id:'f2', name:'Lapsed VIPs'})] → combobox 'Saved contact filter' has options 'Pune leads' and 'Lapsed VIPs'; choose 'Lapsed VIPs' → requestTo(COUNT).body toEqual {selection:{filterId:'f2'}}; '1,204 contacts'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/api src/app/Contacts/contactFields.test.tsx src/app/Campaigns/newCampaign.test.tsx`
Expected:
- The API tests FAIL (missing `previewFilter`, `getFilter`, the new `createFilter` signature).
- The messages tests FAIL with the generic sentence; the fields-page tests FAIL the same way.
- `listsBothFilterKinds` passes already: `AudienceStep` lists every filter. It pins §8 and needs no wizard change.

- [ ] **Step 3: Implement** the types, calls, `ApiError.usedBy`, the sentences, the builders, and the `ContactsPage` call site, as listed.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run type-check && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/api src/test/contacts.ts src/app/Contacts/ContactsPage.tsx src/app/Contacts/contactFields.test.tsx src/app/Campaigns/newCampaign.test.tsx
git commit -m "feat(contacts): advanced filter API, in-use and timeout sentences"
```

---

### Task 2: `filterModel.ts`, the pure rule-tree model

**Files:**
- Create: `src/app/Contacts/Filters/filterModel.ts`
- Test: `src/app/Contacts/Filters/filterModel.test.ts`

**Interfaces:**
- Consumes: Task 1 `Rule`, `RuleGroup`; `ContactAttribute`, `ContactLabel`, `SOURCES` from `api/contacts.ts`.
- Produces:
  - Caps: `MAX_DEPTH = 5`, `MAX_RULES = 50`, `MAX_VALUES = 20`, `MAX_VALUE_LENGTH = 200`.
  - `type FieldType = 'text' | 'enum' | 'boolean' | 'date' | 'labels'`; `type FieldGroup = 'Contact fields' | 'Labels' | 'Attributes'`; `interface FieldOption { field: string; label: string; group: FieldGroup; type: FieldType }`.
  - `CONTACT_FIELDS: FieldOption[]`, in this order:

    | label | field | type |
    |---|---|---|
    | `Name` | `name` | text |
    | `Phone` | `phone` | text |
    | `Source` | `source` | enum |
    | `Number hidden` | `numberHidden` | boolean |
    | `Do not contact` | `doNotContact` | boolean |
    | `Created` | `creationTime` | date |
    | `Last message` | `lastMessageAt` | date |

  - `fieldOptions(attributes: ContactAttribute[]): FieldOption[]` returns the contact fields, then `{ field: 'labels', label: 'Labels', group: 'Labels', type: 'labels' }`, then one text option per attribute (`attributes.<id>`, labelled with the attribute's name, in API order).
  - `fieldType(field: string): FieldType | null`: known names from the table, `labels`, and the `attributes.` prefix → `text`; anything else → null.
  - `OPERATORS: Record<FieldType, string[]>`, in the §3 table order. `OPERATOR_LABELS: Record<string, string>` maps the §3 operators to the §8 labels: `isNot` → `is not`, `notContains` → `does not contain`, `in` → `is any of`, `notIn` → `is none of`, `before` → `is before`, `hasNot` → `does not have`, `isEmpty` → `has no labels`, `isNotEmpty` → `has labels`, and so on.
  - `type ValueKind = 'none' | 'text' | 'option' | 'options' | 'date' | 'dateRange' | 'label' | 'labels'`; `valueKind(type: FieldType, operator: string): ValueKind`:
    - text: `isSet`/`isNotSet` → none, else text.
    - enum: `is`/`isNot` → option; `in`/`notIn` → options.
    - boolean → option.
    - date: `before`/`after` → date; `between` → dateRange; `isSet`/`isNotSet` → none.
    - labels: `has`/`hasNot` → label; `hasAny`/`hasAll`/`hasNone` → labels; `isEmpty`/`isNotEmpty` → none.
  - `emptyValue(kind: ValueKind): Rule['value']`: none → `null`; text, option, date, label → `''`; options, labels → `[]`; dateRange → `['', '']`.
  - `newRule(): Rule` → `{ field: '', operator: '', value: null }`; `emptyGroup(): RuleGroup` → `{ combinator: 'and', not: false, rules: [newRule()] }`.
  - `type Path = number[]` (child indices from the root); `isGroup(n: Rule | RuleGroup): n is RuleGroup`.
  - `nodeAt(root: RuleGroup, path: Path): Rule | RuleGroup | undefined`.
  - `replaceAt(root, path, node): RuleGroup`, `addTo(root, groupPath, child): RuleGroup` and `removeAt(root, path): RuleGroup` are all immutable. `removeAt` also removes each non-root ancestor left with no rules; the root may become empty.
  - `withField(rule: Rule, field: string): Rule`: the operator becomes the first of the type and the value becomes `emptyValue(...)`; an unknown type gives operator `''` and value `null`. `withOperator(rule: Rule, operator: string): Rule` keeps the value when `valueKind` is unchanged, else sets `emptyValue`.
  - `countRules(root): number` counts rules only; `depth(root): number` counts the root alone as 1.
  - `groupLevel(path: Path): number` is `path.length + 1`. `canAddRule(root): boolean` is `countRules < MAX_RULES`. `canAddGroup(root, groupPath): boolean` is `groupLevel(groupPath) < MAX_DEPTH && canAddRule(root)`, since a new group carries one rule.
  - `interface Missing { attributeIds: string[]; labelIds: string[] }`; `missingIds(root, labels: ContactLabel[], attributes: ContactAttribute[]): Missing` (distinct, in tree order); `ruleMissing(rule, labels, attributes): 'attribute' | 'label' | null`.
  - `isComplete(root: RuleGroup): boolean`. The tree is complete when:
    - every group has at least one rule, `countRules <= MAX_RULES` and `depth <= MAX_DEPTH`;
    - every rule's `fieldType` is non-null and its operator is in `OPERATORS[type]`;
    - every value fits its kind:
      - none: `null`.
      - text: a string, non-blank after trim, of at most 200 characters.
      - option: a source (enum) or `'true'`/`'false'` (boolean).
      - options: 1–20 sources.
      - label: a non-empty id. labels: 1–20 ids.
      - date: matches `/^\d{4}-\d{2}-\d{2}$/`.
      - dateRange: two such dates with `from <= to`.
  - `cleanRules(root): RuleGroup`: the same tree with every string value and array element trimmed. It is the body every preview and save sends.
  - `rulePath(path: Path): string`: `[]` → `'rules'`, `[2, 0]` → `'rules[2].rules[0]'`.
  - `splitErrors(fields: Record<string, string>, root): { byPath: Record<string, string>; name?: string; other: string[] }` (Contract assumption 7). A key whose node exists goes to `byPath[rulePath(path)]`; `name` goes to `name`; any other key becomes `'<key>: <sentence>'` in `other`.
  - `nodeName(path: Path, group: boolean): string`: `[]` → `'Filter rules'`; otherwise `` `${group ? 'Group' : 'Rule'} ${path.map((i) => i + 1).join('.')}` ``.

- [ ] **Step 1: Write the failing tests** (`filterModel.test.ts`; `CITY = attribute({id:'a1', name:'City'})`, `VIP = label({id:'l1', name:'VIP'})`):

```ts
it('fieldOptionsGroupContactFieldsLabelsAndAttributes', ...) // fieldOptions([CITY]).map(o => [o.group, o.label, o.field]) toEqual the 7 contact rows, ['Labels','Labels','labels'], ['Attributes','City','attributes.a1']
it('operatorsFollowTheFieldType', ...)      // OPERATORS toEqual the §3 table exactly; OPERATORS.text.map(o => OPERATOR_LABELS[o]) toEqual ['is','is not','contains','does not contain','starts with','ends with','is set','is not set']; labels → ['has','does not have','has any of','has all of','has none of','has no labels','has labels']; date → ['is before','is after','is between','is set','is not set']; enum → ['is','is not','is any of','is none of']
it('withFieldPicksTheFirstOperatorAndAnEmptyValue', ...) // newRule()+'source' → {field:'source', operator:'is', value:''}; 'labels' → operator 'has', ''; 'creationTime' → 'before', ''; 'attributes.a1' → 'is', ''; 'nope' → operator '', value null
it('withOperatorKeepsACompatibleValue', ...) // name is 'Asha' → contains keeps 'Asha'; → isSet null; source in ['CHAT'] → is ''; date before '2026-09-01' → after keeps; → between ['','']; labels has 'l1' → hasAny []
it('addRemoveReplaceAtAPath', ...)          // r = emptyGroup(); addTo(r, [], emptyGroup()) has 2 rules, rules[1] a group; replaceAt(that, [1,0], X) → nodeAt [1,0] toBe X; removeAt([0]) → rules[0] is the group; r.rules still has length 1 (no mutation)
it('removingTheLastRuleRemovesItsGroup', ...) // {rules:[R1, {rules:[R2]}]} removeAt [1,0] → rules toEqual [R1]; {rules:[R1,{rules:[{rules:[R2]}]}]} removeAt [1,0,0] → [R1]; root {rules:[R1]} removeAt [0] → root.rules toEqual []
it('countsRulesAndDepth', ...)              // LAPSED_TREE → countRules 4, depth 2; a five-level chain → depth 5, canAddGroup at the level-5 path false, at the level-4 path true; 50 rules → canAddRule false, canAddGroup(root, []) false
it('missingIdsFindsDeletedLabelsAndAttributes', ...) // [attributes.a9 is 'x', labels has 'l9', labels hasAny ['l1','l8']] with [VIP], [CITY] → {attributeIds:['a9'], labelIds:['l9','l8']}; ruleMissing → 'attribute', 'label', 'label'; LAPSED_TREE rules → null
it('isCompleteChecksEveryRule', ...)        // LAPSED_TREE true; text '  ' false; 201 chars false; name isSet null true; source in [] false; 21 label ids false; date '2026-9-1' false; between ['2026-09-10','2026-09-01'] false, ['2026-09-01','2026-09-10'] true; a nested group with rules [] false; root rules [] false; boolean 'yes' false; field '' false
it('cleanRulesTrimsValues', ...)            // ' Pune ' → 'Pune'; [' l1 '] → ['l1']; null kept; otherwise toEqual the input
it('rulePathAndErrorsUseTheServerPaths', ...) // rulePath([2,0]) 'rules[2].rules[0]'; rulePath([]) 'rules'; splitErrors({'rules[2].rules[0].value':'must be at most 200 characters', 'rules':'is too deep', name:'is required', 'rules[9]':'x'}, LAPSED_TREE) toEqual {byPath:{'rules[2].rules[0]':'must be at most 200 characters', rules:'is too deep'}, name:'is required', other:['rules[9]: x']}
it('nodeNamesNumberFromOne', ...)           // ([],true) 'Filter rules'; ([0],false) 'Rule 1'; ([2,1],false) 'Rule 3.2'; ([2],true) 'Group 3'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts/Filters/filterModel.test.ts`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `filterModel.ts`** as listed.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Contacts/Filters && npm run type-check`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/Filters/filterModel.ts src/app/Contacts/Filters/filterModel.test.ts
git commit -m "feat(contacts): rule tree model for advanced filters"
```

---

### Task 3: The rule tree editor

**Files:**
- Create: `src/app/Contacts/Filters/RuleTreeEditor.tsx`, `src/app/Contacts/Filters/RuleEditor.tsx`
- Modify: `src/app/Contacts/FilterPanel.tsx` (export `LabelPicker`; add `max?: number`)
- Test: `src/app/Contacts/Filters/ruleTreeEditor.test.tsx`; existing `contactFilters.test.tsx` stays green unchanged

**Interfaces:**
- Consumes: Task 2 (everything), `SOURCE_TEXT`, `LabelPicker`.
- Produces:
  - `LabelPicker` is exported with props `{ title; labels; selected; onChange; max?: number }`. When `selected.length >= max`, the unselected options are disabled.
  - `RuleTreeEditor: FC<{ value: RuleGroup; onChange(next: RuleGroup): void; labels: ContactLabel[]; attributes: ContactAttribute[]; errors?: Record<string, string> }>`. `errors` is keyed by `rulePath`.
  - `RuleEditor: FC<{ rule: Rule; path: Path; labels; attributes; error?: string; onChange(rule: Rule): void; onRemove(): void }>`.

**Group** (recursive, inside `RuleTreeEditor`). A `div role="group"` with `aria-label={nodeName(path, true)}`, indented with a left border when nested.
- It starts with one `div role="toolbar"`, `aria-label={`${nodeName(path, true)} actions`}`, holding only this group's controls:
  - a `ToggleGroup` (`aria-label="Combinator"`) with items `All of these` (and) and `Any of these` (or);
  - `Checkbox` `Not` (id `not-${rulePath(path)}`);
  - `Add rule` (adds `newRule()`; disabled when `!canAddRule`);
  - `Add group` (adds `emptyGroup()`; disabled when `!canAddGroup(value, path)`);
  - `Remove group` (not on the root).
- `errors[rulePath(path)]` shows as an error `HelperTextItem` under the toolbar.
- Then the children, in order.

**Rule** (`RuleEditor`). A `div role="group"` with `aria-label={nodeName(path, false)}`, holding:
- `FormSelect` `Field`:
  - a placeholder `''` `Choose a field`, then `FormSelectOptionGroup`s `Contact fields`, `Labels` and `Attributes` from `fieldOptions`;
  - when `ruleMissing === 'attribute'`, an extra disabled option whose value is `rule.field` and whose label is `—`;
  - changes go through `withField`.
- `FormSelect` `Operator`, disabled until a field is chosen. Its options are `OPERATORS[type]`, labelled with `OPERATOR_LABELS`; changes go through `withOperator`.
- A value editor by `valueKind`:
  - text: `TextInput` `Value` with `maxLength={MAX_VALUE_LENGTH}`.
  - option: `FormSelect` `Value`, with the placeholder `Choose a value` and either `SOURCES` labelled by `SOURCE_TEXT` (enum) or `Yes` → `'true'` / `No` → `'false'` (boolean).
  - options: a `div role="group" aria-label="Value"` with checkboxes `Chat`, `Import`, `Manual`.
  - date: `TextInput type="date"` `Value`. dateRange: two such inputs, `From` and `To`.
  - label: `FormSelect` `Value`, with the placeholder `Choose a label`, the labels by name, and a disabled `—` option for a missing id.
  - labels: `LabelPicker title="Choose labels" max={MAX_VALUES}`.
  - none: no editor.
- A plain button `Remove rule`.
- An error `HelperTextItem`, in red, showing `Missing attribute` or `Missing label` when `ruleMissing` says so, and showing `error` when one is given.

- [ ] **Step 1: Write the failing tests.** `ruleTreeEditor.test.tsx` renders a `Harness` that holds the tree in `useState` (initial value from a prop, default `emptyGroup()`) and records each `onChange` in an array. The labels are `VIP` (l1) and `Lead` (l2); the attributes are `CITY` (a1). Helpers:
- `rule(n) = within(screen.getByRole('group', { name: n }))`
- `bar(n) = within(screen.getByRole('toolbar', { name: `${n} actions` }))`
- `last()` returns the latest tree.

```ts
it('buildsTheSpecTreeByRoleAndName', ...)  // Rule 1: Field 'Labels', Value 'VIP'; bar('Filter rules') 'Add rule'; Rule 2: Field 'Last message', Operator 'is before', fireEvent date '2026-09-01'; bar('Filter rules') 'Add group'; bar('Group 3') 'Any of these' + 'Not'; Rule 3.1: Field 'City', Operator 'is', type 'Pune'; bar('Group 3') 'Add rule'; Rule 3.2: Field 'Source', Operator 'is any of', check 'Chat','Import' → last() toEqual LAPSED_TREE
it('fieldPickerIsGrouped', ...)            // within Rule 1's Field select: groups 'Contact fields' (options Name, Phone, Source, Number hidden, Do not contact, Created, Last message), 'Labels' (Labels), 'Attributes' (City)
it('operatorLabelsFollowTheField', ...)    // Field 'Labels' → Operator option names toEqual the 7 label operators; 'Phone' → the 8 text labels; 'Created' → the 5 date labels; 'Do not contact' → ['is'] and Value options 'Choose a value','Yes','No'
it('valueEditorsFollowTheOperator', ...)   // Name + 'is set' → no 'Value' in Rule 1, value null; Created + 'is between' → textboxes 'From','To', set both → value ['2026-09-01','2026-09-30']; Labels + 'has any of' → button /^Choose labels/, pick VIP, Lead → ['l1','l2']
it('changingTheFieldResetsTheValue', ...)  // Name 'contains' 'Asha' → Field 'Source' → last() rules[0] toEqual {field:'source', operator:'is', value:''}
it('addGroupIsDisabledAtTheFifthLevel', ...) // bar('Filter rules') Add group, bar('Group 2') Add group, bar('Group 2.2') Add group, bar('Group 2.2.2') Add group → bar('Group 2.2.2.2'): 'Add group' disabled, 'Add rule' enabled; bar('Group 2.2.2') 'Add group' enabled
it('ruleCapDisablesAdding', ...)           // value = root with 50 {field:'name', operator:'isSet', value:null} → bar('Filter rules') 'Add rule' and 'Add group' disabled
it('removesRulesAndGroups', ...)           // value LAPSED_TREE: bar('Group 3') 'Remove group' → last().rules length 2; Rule 1 'Remove rule' → last().rules toEqual [LAPSED_TREE.rules[1]]
it('removingTheOnlyRuleOfAGroupRemovesIt', ...) // Add group → Group 2 has Rule 2.1; Rule 2.1 'Remove rule' → no group 'Group 2'; last().rules length 1
it('showsMissingAttributeAndLabel', ...)   // value rules [{attributes.a9 is 'x'}, {labels has 'l9'}] → Rule 1 has 'Missing attribute', Rule 2 'Missing label'; Field select of Rule 1 has value 'attributes.a9'
it('showsAServerErrorUnderItsRule', ...)   // errors {'rules[1]': 'must be a date'} with LAPSED_TREE → Rule 2 contains 'must be a date'; Rule 1 does not
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts/Filters/ruleTreeEditor.test.tsx`
Expected: FAIL (components missing).

- [ ] **Step 3: Implement** `RuleEditor`, `RuleTreeEditor`, and the `LabelPicker` export and `max`.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Contacts && npm run lint`
Expected: PASS; `contactFilters.test.tsx` unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/Filters src/app/Contacts/FilterPanel.tsx
git commit -m "feat(contacts): rule tree editor with PatternFly controls"
```

---

### Task 4: Contacts page knows ADVANCED filters

**Files:**
- Modify: `src/app/Contacts/ContactsPage.tsx`, `src/app/Contacts/SavedFilterBar.tsx`, `src/test/renderApp.tsx`
- Test: `src/app/Contacts/contactFilters.test.tsx`, `src/app/Contacts/contactImportExport.test.tsx`

**Interfaces:**
- Consumes: Task 1 `isAdvanced`, `Selection`, `exportContacts`, `advancedFilter`.
- Produces:
  - `renderApp(path: InitialEntry = '/', extra?, userOptions?)`, where `type InitialEntry = NonNullable<MemoryRouterProps['initialEntries']>[number]`, so a test can pass `{ pathname, state }`.
  - `LocationProbe: FC`, exported from `src/test/renderApp.tsx`: `<output aria-label="Location">{useLocation().pathname}</output>`.
  - The Contacts page accepts `location.state = { filterId: string }` and opens with that filter picked. Tasks 5 and 6 navigate with it.

**Behaviour (ContactsPage):**
- Let `picked` be the saved filter whose id is `savedId`, and `advanced = !!picked && isAdvanced(picked)`.
- One `scope()` result feeds search, export and the "all matching" selection:
  - `{ filterId }` for a SIMPLE pick, or for an ADVANCED pick with an empty panel and search;
  - `{ filterId, filter }` for an ADVANCED pick with a non-empty `filter` (Contract assumption 10);
  - `{ filter }` when nothing is picked.
- Panel and search changes clear `savedId` only when the pick is not ADVANCED.
- Picking an ADVANCED filter resets the panel and search to empty and bumps `panelEpoch`, so the table first shows the advanced filter alone. Picking a SIMPLE one behaves as today.
- `canSaveCurrent = !advanced && Object.keys(filter).length > 0`.
- `savedId` starts at `location.state?.filterId ?? null`. When `saved.data` first arrives:
  - if that id is SIMPLE, run the existing `applySaved` once, so its spec fills the panel;
  - if the id is not in the list, `setSavedId(null)`.
- With `contact:bulk`, a `ToolbarItem` after `SavedFilterBar` holds a secondary `Button` `Filter builder` that navigates to `/contacts/filters`.

`SavedFilterBar`: an ADVANCED option renders `{f.name} <Label isCompact color="purple">Advanced</Label>`.

- [ ] **Step 1: Write the failing tests.** In `contactFilters.test.tsx`, `routes()` lists `[SAVED, ADV]` with `ADV = advancedFilter({ id: 'f2', name: 'Lapsed VIPs' })`. `pick(name)` opens `Saved filter: …` and clicks `option` `/^name/`.

```ts
it('advancedFilterIsLabelled', ...)          // option /^Lapsed VIPs/ contains 'Advanced'; option 'Jaipur VIPs' does not
it('advancedFilterSendsItsId', ...)          // pick Lapsed VIPs → lastSearch toEqual {filterId:'f2', sort:'LAST_MESSAGE', page:0}
it('panelEditsKeepTheAdvancedFilter', ...)   // pick; Filters; check 'Chat' → lastSearch toEqual {filterId:'f2', filter:{source:['CHAT']}, sort:'LAST_MESSAGE', page:0}; toggle still 'Saved filter: Lapsed VIPs'; type 'asha' in Search contacts → waitFor lastSearch.filter toEqual {source:['CHAT'], q:'asha'} and filterId 'f2'
it('pickingAnAdvancedFilterClearsThePanel', ...) // check 'Chat', then pick Lapsed VIPs → lastSearch has no 'filter' key; checkbox 'Chat' not checked
it('saveFilterIsOffForAnAdvancedFilter', ...) // pick Lapsed VIPs, check 'Chat' → 'Save filter' disabled; pick None → enabled again
it('bulkAllMatchingOnAdvancedSendsBoth', ...) // search total 300; pick; check 'Chat'; Select all rows → 'Select all 300 matching' → 'Delete' → requestTo(COUNT).body toEqual {selection:{filterId:'f2', filter:{source:['CHAT']}}}
it('opensWithTheHandedOverFilter', ...)      // renderApp({pathname:'/contacts', state:{filterId:'f2'}}) → 'Saved filter: Lapsed VIPs'; every search body has filterId 'f2'; with 'f1' → 'Saved filter: Jaipur VIPs' and, after opening 'Filters', button /^Any of these labels/ shows badge '1'
it('filterBuilderNeedsBulk', ...)            // renderApp('/contacts', <LocationProbe />): WORKSPACE_MANAGER clicks 'Filter builder' → status 'Location' text '/contacts/filters'; AGENT_IN_BRAND: no 'Filter builder'
```

In `contactImportExport.test.tsx`:

```ts
it('exportUsesAdvancedFilterAndPanel', ...) // contact-filters [advancedFilter f2]; pick; Filters → 'Chat'; Export → requestTo(EXPORT).body toEqual {filterId:'f2', filter:{source:['CHAT']}}
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts/contactFilters.test.tsx src/app/Contacts/contactImportExport.test.tsx`
Expected: the new tests FAIL; the existing ones pass.

- [ ] **Step 3: Implement** the `renderApp` widening, `LocationProbe`, the `SavedFilterBar` label and the `ContactsPage` changes.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run type-check && npm run lint && npm test`
Expected: PASS, whole suite green (the existing saved-filter tests are untouched).

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts src/test/renderApp.tsx
git commit -m "feat(contacts): advanced filters on the contacts page, ANDed with the panel"
```

---

### Task 5: The builder page

**Files:**
- Create: `src/app/Contacts/Filters/FilterBuilderPage.tsx`
- Modify: `src/app/routes.tsx`
- Test: `src/app/Contacts/Filters/filterBuilder.test.tsx`

**Interfaces:**
- Consumes: Task 1 `getFilter`, `previewFilter`, `createFilter`, `updateFilter`, `FILTER_SAMPLE_SIZE`, `isAdvanced`; Task 2 `emptyGroup`, `cleanRules`, `isComplete`, `missingIds`, `splitErrors`; Task 3 `RuleTreeEditor`; Task 4 `LocationProbe` and the `state.filterId` hand-over; `countText`, `displayName`, `phoneText`, `LabelChip`, `NotFound`.
- Produces: routes `/contacts/filters/new` and `/contacts/filters/:id`, both `requires: a => a.hasInWorkspace('contact:view')`. They go before `/contacts/:id`, with the comment `// Keep before /contacts/:id, which would otherwise capture "filters".`

**Page.** `FilterBuilderPage` returns `<NotFound />` without `contact:bulk`, before any fetch.
- With an `:id`, it loads the filter (`useLoad(getFilter)`), the labels and the attributes:
  - a SIMPLE filter → `<Navigate to="/contacts" replace state={{ filterId: id }} />`;
  - a load error → a danger alert with the message.
- It renders `<FilterBuilderForm key={id ?? 'new'} existing={…} labels attributes />`.

**Form layout.**
- `PageShell` title `New filter` or `Edit filter`.
- `Name` (`TextInput`, required).
- A `Grid`:
  - Left (`md=8`): `Tabs` with `Rules` (the `RuleTreeEditor`, errors = `ruleErrors`) and `Query`. The Query tab shows `CodeBlock`/`CodeBlockCode` containing `JSON.stringify(query, null, 2)`, where `query` is the last preview's query, else `existing.query`; with neither it shows `Check the count to see the query.`.
  - Right (`md=4`, a `Card`): `Check count`, then `countText(count)`; `Show sample`, then a compact table `Sample contacts` with Name, Phone and Labels; and the preview error as an inline danger alert.
- `Save` (primary) and `Cancel` (link, navigates to `/contacts/filters`).

**State.**
- `rules` starts at `existing?.rules ?? emptyGroup()`. Let `key = JSON.stringify(cleanRules(rules))`, mirrored in a ref.
- `checked: { key; count; query; sample: Contact[] | null } | null`. The count and sample show only while `checked?.key === key`.
- Any tree change clears `ruleErrors` and the preview error.
- `blocked = !isComplete(rules) || missing.attributeIds.length + missing.labelIds.length > 0`.

**Preview.** `runPreview(sample?: number): Promise<FilterPreview | undefined>` sends `previewFilter(t, ws, cleanRules(rules), sample)`.
- When the answer arrives, it is dropped if the ref's key no longer equals the key it was sent for.
- On success it sets `checked`.
- On a VALIDATION error with `fields`, `splitErrors` puts `byPath` into `ruleErrors`, `name` under the Name field, and `other` (joined `; `) into the preview error. Any other error goes to the preview error through `messageFor`.
- `Check count` calls `runPreview()`; `Show sample` calls `runPreview(FILTER_SAMPLE_SIZE)`. Both are disabled while `blocked` or busy.

**Save.**
- Save is disabled while the name is blank, while `blocked`, or while busy.
- If `checked?.key !== key`, Save first awaits `runPreview()` and stops when it fails.
- It then runs `useAction(['name']).run(() => existing ? updateFilter(t, ws, existing.id, body) : createFilter(t, ws, body))` with `body = { name: name.trim(), rules: cleanRules(rules) }`. A field error shows under Name; any other error shows as a danger alert by the buttons.
- On success it navigates to `/contacts/filters`.

- [ ] **Step 1: Write the failing tests.**
- Constants: `B = '/api/workspaces/w1'`, `PREVIEW = `POST ${B}/contact-filters/preview``, `CREATE = `POST ${B}/contact-filters``.
- `routes()` serves: labels `[VIP, LEAD]`; attributes `[CITY]`; `PREVIEW` → `filterPreview({ sample: [ASHA] })`, where `ASHA = contact({ id: 'c1', name: 'Asha Rao', phone: '919900000001', labels: ['l1'] })`; `CREATE` → 201 `advancedFilter({ id: 'f9' })`; `GET ${B}/contact-filters/f2` → `advancedFilter({ id: 'f2', name: 'Lapsed VIPs' })`; plus the routes the Contacts page needs.
- `ONE = { combinator: 'and', not: false, rules: [{ field: 'labels', operator: 'has', value: 'l1' }] }`. Helper `buildOne(user)` fills Rule 1 as Labels / has / VIP.
- `TWO` = `ONE` plus `{ field: 'lastMessageAt', operator: 'before', value: '2026-09-01' }`.
- Pages render with `<LocationProbe />`.

```ts
it('builderIsNotFoundWithoutBulk', ...)   // AGENT_IN_BRAND at '/contacts/filters/new' → '404 Page not found'; at '/contacts/filters/f2' → same and callsTo(GET `${B}/contact-filters/f2`) 0; callsTo(PREVIEW) 0
it('checkAndSampleNeedACompleteTree', ...) // new: 'Check count', 'Show sample', 'Save' disabled; buildOne → Check/Show enabled, Save still disabled (blank name); Name 'Lapsed VIPs' → Save enabled; Rule 1 'Remove rule' → all three disabled
it('checkCountSendsTheTree', ...)         // buildOne; 'Check count' → requestTo(PREVIEW).body toEqual {rules: ONE}; text '1,204 contacts'
it('showSampleListsTheContacts', ...)     // buildOne; 'Show sample' → body toEqual {rules: ONE, sample: 10}; table 'Sample contacts' row 'Asha Rao' has '+919900000001' and 'VIP'
it('queryTabShowsTheCompiledQuery', ...)  // tab 'Query' → 'Check the count to see the query.'; back to 'Rules', buildOne, Check count; tab 'Query' → code element textContent === JSON.stringify(LAPSED_QUERY, null, 2)
it('saveSendsNameAndRules', ...)          // Name 'Lapsed VIPs'; build TWO; Save → requestTo(PREVIEW).body toEqual {rules: TWO}; requestTo(CREATE).body toEqual {name:'Lapsed VIPs', rules: TWO}; Location '/contacts/filters'
it('saveAfterCheckDoesNotPreviewAgain', ...) // buildOne, Name, Check count, Save → callsTo(PREVIEW) 1, callsTo(CREATE) 1
it('saveRePreviewsAChangedTree', ...)     // buildOne, Name, Check count; Rule 1 Value 'Lead' → '1,204 contacts' gone; Save → callsTo(PREVIEW) 2; requestTo(PREVIEW,1).body.rules.rules[0].value === 'l2'; CREATE body rules equal the same tree
it('aStalePreviewDoesNotCountForTheNewTree', ...) // gate(fetch, PREVIEW, 1); buildOne, Name, Check count; Value 'Lead'; release → no '1,204 contacts'; Save → callsTo(PREVIEW) 2
it('previewTimeoutShowsTheSentenceAndDoesNotSave', ...) // PREVIEW 504 {code:'QUERY_TIMEOUT'}; Save → 'This filter takes too long to run. Make it more specific.'; callsTo(CREATE) 0
it('validationErrorShowsUnderTheRule', ...) // PREVIEW 400 {code:'VALIDATION', fields:{'rules[1].value':'must be a date'}}; build TWO; Check count → within group 'Rule 2' 'must be a date'
it('duplicateNameShowsUnderName', ...)    // CREATE 409 {code:'DUPLICATE', field:'name'} → 'Name is already in use.'; Location still '/contacts/filters/new'
it('editLoadsTheFilterAndPuts', ...)      // '/contacts/filters/f2': heading 'Edit filter'; Name value 'Lapsed VIPs'; Rule 1 Field value 'labels'; tab Query shows JSON.stringify(LAPSED_QUERY, null, 2); Name → 'Lapsed VIPs 2'; Save → PREVIEW body {rules: LAPSED_TREE}; PUT `${B}/contact-filters/f2` body toEqual {name:'Lapsed VIPs 2', rules: LAPSED_TREE}
it('missingAttributeBlocksSave', ...)     // GET f3 advancedFilter rules {and, not:false, rules:[{attributes.a9 is 'x'}, {labels has 'l9'}]} → Rule 1 'Missing attribute', Rule 2 'Missing label'; Save and Check count disabled; Rule 1 'Remove rule'; Rule 1 Value 'VIP' → Save and Check count enabled
it('simpleFilterOpensTheContactsPage', ...) // GET f1 savedFilter Jaipur VIPs (SIMPLE) at '/contacts/filters/f1' → heading 'Contacts'; 'Saved filter: Jaipur VIPs'
it('cancelLeavesWithoutSaving', ...)      // Cancel → Location '/contacts/filters'; callsTo(CREATE) 0
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts/Filters/filterBuilder.test.tsx`
Expected: FAIL (404 page; no builder).

- [ ] **Step 3: Implement** the routes and `FilterBuilderPage` / `FilterBuilderForm` as described.

- [ ] **Step 4: Run to verify they pass**

Run: `npx vitest run src/app/Contacts && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/Filters src/app/routes.tsx
git commit -m "feat(contacts): advanced filter builder with count, sample and query"
```

---

### Task 6: Saved filters page

**Files:**
- Create: `src/app/Contacts/Filters/SavedFiltersPage.tsx`
- Modify: `src/app/routes.tsx`
- Test: `src/app/Contacts/Filters/savedFilters.test.tsx`

**Interfaces:**
- Consumes: Task 1 `listFilters`, `deleteFilter`, `countContacts`, `isAdvanced`; Task 4 `state.filterId`; Task 5 builder routes; `relative`, `countText`, `messageFor`, `ConfirmModal`, `useAction`.
- Produces: route `/contacts/filters` (`requires: contact:view`), placed before the two builder routes and `/contacts/:id`.

**Page.**
- `PageShell` title `Saved filters`. Actions: `New filter` (`contact:bulk`; navigates to `/contacts/filters/new`) and a link `Back to contacts`.
- A compact table:
  - Name; Kind (`Simple` or `Advanced`).
  - Contacts: a `Count` button. Its result is `countText`, shown in the cell; while counting, a small spinner labelled `Counting contacts`; on an error, the `messageFor` text in the cell. Per-row state is `Record<id, …>`, and nothing is counted on load.
  - Updated: `relative(lastUpdateTime ?? creationTime)`.
  - A kebab, only with `contact:bulk`:
    - `Edit`: ADVANCED → `/contacts/filters/:id`; SIMPLE → `navigate('/contacts', { state: { filterId } })`.
    - `Delete`: a `ConfirmModal` titled `Delete this saved filter?`, body `Contacts are not affected. Everyone in the brand loses the filter.`, button `Delete` (danger), then `deleteFilter`, then the row is removed.
- Empty list: `No saved filters yet.`. A load error shows as a danger alert.

- [ ] **Step 1: Write the failing tests** (`savedFilters.test.tsx`; filters `[SAVED (f1 Jaipur VIPs), ADV (f2 Lapsed VIPs)]`, `COUNT = `POST ${B}/contacts/count``):

```ts
it('listsBothKinds', ...)                // rowWith('Jaipur VIPs') has 'Simple'; rowWith('Lapsed VIPs') has 'Advanced'; columnheaders Name, Kind, Contacts, Updated
it('countsOnlyOnRequest', ...)           // after load callsTo(COUNT) 0; 'Count' in row Lapsed VIPs → requestTo(COUNT).body toEqual {selection:{filterId:'f2'}}; row text '1,204 contacts'; row Jaipur VIPs still has button 'Count'
it('countTimeoutStaysInTheRow', ...)     // COUNT 504 {code:'QUERY_TIMEOUT'} → row Lapsed VIPs has 'This filter takes too long to run. Make it more specific.'
it('editAdvancedOpensTheBuilder', ...)   // rowAction 'Lapsed VIPs' 'Edit' → heading 'Edit filter'; callsTo(GET `${B}/contact-filters/f2`) 1
it('editSimpleOpensContactsWithIt', ...) // rowAction 'Jaipur VIPs' 'Edit' → heading 'Contacts'; 'Saved filter: Jaipur VIPs'; last search body filterId 'f1'
it('deleteConfirmsFirst', ...)           // rowAction 'Lapsed VIPs' 'Delete' → dialog 'Delete this saved filter?'; Cancel → DELETE 0; again + 'Delete' → callsTo(DELETE `${B}/contact-filters/f2`) 1; no row 'Lapsed VIPs'
it('newFilterOpensTheBuilder', ...)      // 'New filter' → heading 'New filter'
it('viewerSeesNoWriteActions', ...)      // AGENT_IN_BRAND: rows listed; 'Count' present; no 'New filter'; no 'Kebab toggle'
it('emptyListSaysSo', ...)               // [] → 'No saved filters yet.'
it('filterBuilderButtonOpensThisPage', ...) // '/contacts' → 'Filter builder' → heading 'Saved filters'
it('builderSaveLandsHere', ...)          // '/contacts/filters/new' → Name, Rule 1 Labels/has/VIP, Save → heading 'Saved filters'
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/app/Contacts/Filters/savedFilters.test.tsx`
Expected: FAIL (404 page at `/contacts/filters`).

- [ ] **Step 3: Implement** the route and the page.

- [ ] **Step 4: Run to verify they pass**

Run: `npm run type-check && npm run lint && npm test`
Expected: PASS, whole suite green.

- [ ] **Step 5: Commit**

```bash
git add src/app/Contacts/Filters src/app/routes.tsx
git commit -m "feat(contacts): saved filters page with lazy counts"
```
