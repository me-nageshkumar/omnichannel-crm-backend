# Advanced Contact Filters Design

Date: 2026-10-22. Repos: `omnichannel-crm-backend`, `omnichannel-crm-frontend`.

## 1. Intent

Let a workspace build any segment of its contacts with nested AND/OR conditions over contact fields, labels and attributes, save it by name, see its count and a sample before saving, and use it anywhere a saved filter is used today (the Contacts page, campaigns). The reference CRM does this by storing a raw MongoDB query the browser wrote and letting the server AND the tenant clause in. We keep the expressiveness and the "saved as a Mongo query" shape, but the server, not the browser, writes the query: the browser sends a rule tree, the server compiles it against a fixed table of fields and operators, stores both the tree and the compiled query, and always forces the workspace clause in. That gives arbitrary conditions without arbitrary queries.

## 2. Goals, non-goals, anti-goals

**Goals**
- A visual builder: nested groups with AND / OR / NOT, rules over contact fields, labels and attributes, live count and sample, save, edit, delete.
- Saved advanced filters appear beside today's simple saved filters and work everywhere a `filterId` is accepted: contact search, bulk operations, export, campaign audiences.
- The stored Mongo query is visible read-only in the builder ("Query" tab) and is what runs.
- Server guards: workspace scope forced, field and operator whitelist by construction, depth and size caps, query timeouts, clamped sample size, validation run on save.

**Non-goals (later)**
- Typing raw Mongo JSON (would need a validating walker; the compiled query is shown but not editable).
- Numeric or date comparisons on attribute values (attributes are strings today).
- Relative dates ("in the last 7 days"), filters over chats or messages, sharing filters across workspaces.

**Anti-goals**
- Any path where client-supplied JSON reaches `Document.parse` or where a `$where`/`$expr`/`$function` can run. (The server parses only query strings it wrote itself; see §5.)
- A saved filter silently matching a different set after a label or attribute is deleted.

## 3. Rule tree (the thing the browser sends and the server stores)

```
RuleGroup { combinator: "and" | "or", not: boolean, rules: (Rule | RuleGroup)[] }
Rule      { field: string, operator: string, value: string | string[] | null }
```
`field` is one of:
- contact fields: `name`, `phone`, `source`, `numberHidden`, `doNotContact`, `creationTime`, `lastMessageAt`
- `labels`
- `attributes.<attributeId>`

Operators by field type:

| type | fields | operators | value |
|---|---|---|---|
| text | `name`, `phone`, `attributes.<id>` | `is`, `isNot`, `contains`, `notContains`, `startsWith`, `endsWith`, `isSet`, `isNotSet` | string (≤ 200 chars); none for isSet/isNotSet. For `phone` the value is reduced to digits before matching (stored phones are digits with the country code), as the simple filter already does |
| enum | `source` | `is`, `isNot`, `in`, `notIn` | `CHAT` / `IMPORT` / `MANUAL` (array for in/notIn) |
| boolean | `numberHidden`, `doNotContact` | `is` | `"true"` / `"false"` |
| date | `creationTime`, `lastMessageAt` | `before`, `after`, `between`, `isSet`, `isNotSet` | ISO date `YYYY-MM-DD` (two for between), interpreted in the workspace's campaign timezone (§7.3 of the campaigns design; default `Asia/Kolkata`) |
| labels | `labels` | `has`, `hasNot`, `hasAny`, `hasAll`, `hasNone`, `isEmpty`, `isNotEmpty` | label id, or array of label ids |

Limits: at most 5 levels of nesting, 50 rules in total, 20 values in an array, 200 characters per value; `rules` of a group non-empty. Violations → 400 `VALIDATION` on `rules` with the offending path (`rules[2].rules[0].value`).

## 4. Compilation (server)

`AdvancedFilterCompiler.compile(workspaceId, RuleGroup, Resolved) -> org.bson.Document` is a pure function over the tree and a `Resolved` set (the workspace's attribute ids and label ids). It produces exactly the Mongo operators `$and`, `$or`, `$nor`, `$eq`, `$ne`, `$in`, `$nin`, `$regex` (+ `$options: "i"`), `$exists`, `$gte`, `$lt`, `$size`. Nothing else can appear. Rules:
- text: `is` → `{field: {$regex: "^" + escape(v) + "$", $options: "i"}}`; `isNot` → `$not` of that; `contains` → `escape(v)`; `startsWith` → `"^" + escape(v)`; `endsWith` → `escape(v) + "$"`; `notContains` → `$not`; `isSet` → `{$exists: true, $nin: [null, ""]}`; `isNotSet` → `$or` of `$exists: false`, `null`, `""`. `escape` quotes every regex metacharacter (`Pattern.quote` semantics expressed as a literal class escape, not `\Q…\E`, so the driver treats it as a plain regex).
- enum: `$eq` / `$ne` / `$in` / `$nin` on `source`.
- boolean: `doNotContact is true` → `{doNotContact: {$ne: null}}`, `false` → `{doNotContact: null}`; `numberHidden is true` → `{phone: null}`, `false` → `{phone: {$ne: null}}` (how the simple filter already models "number hidden").
- date: the day boundaries in the workspace zone: `before d` → `{field: {$lt: start(d)}}`, `after d` → `{$gte: start(d + 1 day)}`, `between a b` → `{$gte: start(a), $lt: start(b + 1 day)}`; `isSet` / `isNotSet` → `$ne null` / `null`.
- labels: `has id` → `{labels: id}`, `hasNot` → `{labels: {$ne: id}}`, `hasAny` → `$in`, `hasAll` → `{$all: ids}` is NOT used (keeps the operator set small): `$and` of `{labels: id}`; `hasNone` → `{labels: {$nin: ids}}`; `isEmpty` → `$or` of `labels` missing / `{$size: 0}`; `isNotEmpty` → `{labels.0: {$exists: true}}`.
- groups: `and` → `$and`, `or` → `$or`, `not` → wrap in `$nor: [ … ]`.
- unknown field, operator, attribute id not in the workspace, label id not in the workspace, wrong value shape → `VALIDATION` with the rule path.

The compiled document is serialised with `Document.toJson()` and stored as a string (`queryJson`): Mongo field names starting with `$` or containing dots cannot be stored as a sub-document through Spring Data's converter. Consumers rebuild it with `Document.parse(queryJson)`; that string is always server-written, never client input. It is what every consumer runs, always as `{$and: [{workspaceId: ws}, query]}` built in code (`Criteria.where("workspaceId").is(ws).andOperator(new BasicQuery(query))` or equivalent). The server never parses client-supplied Mongo JSON.

## 5. Storage

`ContactFilter` (collection `contactFilters`) gains:
- `kind: SIMPLE | ADVANCED` (existing documents are SIMPLE; null reads as SIMPLE)
- `rules: RuleGroup` (ADVANCED only, stored as a sub-document verbatim)
- `queryJson: String` (ADVANCED only, the compiled query as extended JSON; exposed to the console as `query`, parsed)
- `queryVersion: int` (1; bumped when the compiler's output for the same tree changes, so a migration can recompile)
- `uses: { attributeIds: [..], labelIds: [..] }` (ADVANCED only; derived from the tree on save; used for the delete guard below)

`ContactFilterService` recompiles on every save (create/update); the stored `query` is never accepted from the client.

**Delete guard.** Deleting a label or attribute that an ADVANCED filter uses, or that the audience snapshot of a campaign in DRAFT, RUNNING or PAUSED uses (`Campaign.Audience.uses`, derived the same way at creation), → 409 `FILTER_IN_USE` with `{ filters: [{id, name}], campaigns: [{id, name}] }`; the console shows the names and refuses. COMPLETED and TERMINATED campaigns do not guard. A SIMPLE filter referencing the id keeps today's behaviour (the condition just stops matching) to avoid changing existing flows; noted as a known asymmetry.

**A stored filter with a missing id** (a race, or an id removed before this shipped) runs exactly as compiled: the rule on the missing id matches nothing, no error. The builder, on load, resolves `uses` against the workspace's current labels and attributes and shows the `Missing …` state (§8); `FilterView.kind` null reads as SIMPLE everywhere.

## 6. Execution paths

- `ContactQuery.forRequest(ws, inlineSpec, filterId)`: for an ADVANCED filter, the criteria is `workspaceId = ws AND <stored query>` (plus the inline simple spec as today). All existing consumers (search, count, bulk, export) get advanced filters for free.
- Campaigns: `Campaign.Audience` gains `filterKind`, `filterQueryJson` (the compiled query snapshotted at creation, as `filterSpec` is today) and `uses`; `CampaignValidator` snapshots whichever the filter has; `CampaignMaterialiser` gets a second branch (it calls `ContactQuery.build(ws, spec)` today) paging with `workspaceId = ws AND query AND phone ≠ null AND _id > last`. The wizard lists both kinds.
- Timeouts: count and sample calls run with `maxTime(5s)`; search pages `maxTime(10s)`; materialisation pages `maxTime(30s)`. A timeout → 504 `QUERY_TIMEOUT` "This filter takes too long to run. Make it more specific." (new: no timeout handling exists today; `MongoExecutionTimeoutException` is mapped in `ApiExceptionHandler`). On campaign start the materialiser's existing cleanup runs (recipients removed, campaign stays DRAFT) and the user sees the 504.
- Sample size: `limit` clamped to 1–25.

## 7. API (`/api/workspaces/{ws}`)

- `GET /contact-filters` → list items gain `kind`; ADVANCED items also carry `rules` and `query` (small documents; fine in the list).
- `POST /contact-filters` body `{name, filter}` (SIMPLE, as today) or `{name, rules}` (ADVANCED). Exactly one of `filter` / `rules`. `PUT /contact-filters/{id}` likewise; the kind of an existing filter cannot change (400 `VALIDATION` on `rules`/`filter`). Authority unchanged (`contact:bulk`).
- `POST /contact-filters/preview` body `{rules, sample?: number}` (`contact:view`) → `{count, sample: ContactView[], query}`. Compiles, counts and samples without saving. Validation errors as above.
- `GET /contact-filters/{id}` → the full filter incl. `rules`, `query`, `uses` (new endpoint; today only the list exists).
- `DELETE /contact-labels/{id}` and `/contact-attributes/{id}` → 409 `FILTER_IN_USE` when referenced by an ADVANCED filter or an unfinished campaign's audience.

Errors (`messages.ts` sentences): `filter_in_use: 'This is used by a saved filter or a campaign. Remove it there first.'`; `query_timeout: 'This filter takes too long to run. Make it more specific.'`.

## 8. Console

- **Contacts page.** `SavedFilterBar` lists both kinds; an ADVANCED entry shows a small "Advanced" label. Selecting one applies it (`filterId`) as today; the simple Filters panel stays usable on top (ANDed). A new toolbar button `Filter builder` (needs `contact:bulk`) opens `/contacts/filters`.
- **Saved filters page** `/contacts/filters` (`contact:view`; write actions need `contact:bulk`): table Name, Kind, Contacts (count fetched lazily per row with a `Count` button, not on load), Updated, actions Edit (ADVANCED opens the builder; SIMPLE opens the Contacts page with it selected), Delete (confirm).
- **Builder** `/contacts/filters/new` and `/contacts/filters/:id` (`contact:bulk`; renders the 404 page otherwise, same pattern as the campaign pages): `Name`; the rule tree editor; a right column with `Check count` → `N contacts` and `Show sample` (10 rows: name, phone, labels); tabs `Rules` / `Query` (read-only pretty JSON of the compiled query from the last preview); `Save` (preview must have succeeded for the current tree; the button re-runs the preview if the tree changed); `Cancel`.
  - Field picker grouped: Contact fields, Labels, Attributes (by name). Operator select per type (§3, with the labels `is`, `is not`, `contains`, `does not contain`, `starts with`, `ends with`, `is set`, `is not set`, `is any of`, `is none of`, `is before`, `is after`, `is between`, `has`, `does not have`, `has any of`, `has all of`, `has none of`, `has no labels`, `has labels`). Value editors: text input, select, multi-select of labels, date picker(s), none.
  - Groups: `Add rule`, `Add group`, combinator toggle `All of these` / `Any of these`, `Not` checkbox, remove; depth capped at 5 with the `Add group` button disabled at the cap.
  - A rule whose attribute or label no longer exists shows `Missing attribute` / `Missing label` in red; the filter cannot be saved until the rule is fixed or removed.
  - Implementation: the `react-querybuilder` package (MIT; its headless core only) with PatternFly components supplied as its control elements, so the look matches the console. My call: it saves a hand-written tree editor, and its JSON shape is the `RuleGroup` above. If its control-element API fights PatternFly too much, the builder may hand-roll the tree editor using the same `RuleGroup` shape; the server does not care which.
- **Campaign wizard.** The saved-filter select lists both kinds; count and preview work unchanged.
- **Labels / attributes pages.** Delete shows the `filter_in_use` sentence with the filter and campaign names from the 409 body.

## 9. Authorities

No new authorities. Reads `contact:view`; builder and saves `contact:bulk` (the authority that already owns saved filters). The description of `contact:bulk` is updated to mention saved filters and the builder.

## 10. Testing expectations

- Backend: compiler unit tests per operator and type (including regex escaping of `.*+?()[]{}|^$\\`, case-insensitivity, date boundaries in the zone, labels variants, NOT groups, depth/size caps, unknown ids); ITs for preview (count + sample + query), save/update/delete of ADVANCED filters, search and count with an ADVANCED `filterId` (and ANDed with an inline simple spec), bulk and export through an ADVANCED filter, campaign create/start over an ADVANCED filter and its snapshot, the delete guard 409 on labels and attributes, timeouts (a `maxTime` of 1 ms in a test property) → 504, sample clamp, authorities.
- Console: builder tests driving fields/operators/values by role and name, count and sample calls with the exact rule tree in the body, save sends `{name, rules}`, missing-attribute state blocks save, depth cap, query tab shows the compiled JSON; saved filters page; contacts page selecting an advanced filter sends `filterId`; labels page shows the in-use sentence; campaign wizard lists both kinds.

## 11. Left to the builder

- Exact class split (`contact/advanced/` package: `RuleGroup`/`Rule` records, `FilterFields` table, `AdvancedFilterCompiler`, `AdvancedFilterValidator`); whether `ContactQuery` takes a `Document` criteria via `BasicQuery` or `Criteria`.
- Whether to use `react-querybuilder` or hand-roll (§8).
- How the Query tab formats JSON.

## 12. Decisions the user may want to check

- The browser sends a rule tree; the server compiles and stores the Mongo query. Raw Mongo input is a later enhancement, not v1.
- Text matches are case-insensitive, with the value escaped (no user regex).
- Deleting a label or attribute used by an advanced filter or an unfinished campaign is refused; simple filters keep today's behaviour.
- The compiled query is stored as a JSON string the server wrote (Mongo cannot store `$`-keys as sub-documents); a filter whose label or attribute vanished runs as compiled and is flagged in the builder.
- Phone rules match on digits.
- Advanced filters reuse `contact:bulk`; no new authority.
- Dates are interpreted in the workspace's campaign timezone.
- `react-querybuilder` is a new console dependency unless the builder finds it a poor fit.
- Caps: 5 levels, 50 rules, 20 values per list, 200 characters per value; sample ≤ 25; timeouts 5 s / 10 s / 30 s.
