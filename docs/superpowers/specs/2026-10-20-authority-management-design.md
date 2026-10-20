# Design: Authority management

Date: 2026-10-20. Builds on `2026-10-20-customers-workspaces-and-roles-design.md` (the roles spec)
and changes its "Authorities" section: authorities are now stored in Mongo and created from the
console, not only in code.

## Intent

The Super Admin should be able to see, describe, group and create authorities from the console.
Today the list of authorities lives only in a Java enum, and the codes the system has already seen
sit as one array in `system_state/known-authorities`.

The working pattern the user wants is:
1. Guard a controller method in code, e.g. `@PreAuthorize("hasAuthority('order:refund')")`.
2. Deploy.
3. Create `order:refund` in the console with a group and a description.
4. Tick it into the roles that should have it.

An authority only does something where server code checks it. So the console must tell the Super
Admin which codes the code checks, and warn when the two disagree.

## Goals

- One Mongo document per authority, with the code as its id, plus group, level and description.
- The Super Admin can list, search, create, edit and delete authorities from a console page.
- At startup the server reads every `@PreAuthorize` check and knows which codes the code uses.
- The console lists codes that are checked in code but not created yet, ready to create.
- Authorities that are created but not checked, or created at a level their check can never see,
  are flagged.
- The role editor shows authorities by group, with one search across group, code and description.
- Every authority change is recorded: who, when, before and after.
- The `system_state/known-authorities` array goes away.

## Non-goals

- Scanning the frontend. Console menus check authority codes too (`routes.tsx` `requires`), and the
  server can't see those.
- Finding checks written outside `@PreAuthorize`, such as `user.hasAuthority(...)` in Java code.
  Those authorities are built-in (see below), so they always exist.
- Renaming an authority's code.
- Letting anyone but the Super Admin manage authorities.

## Anti-goals

- An authority that looks like it restricts something but has no effect, with nothing on screen
  saying so.
- A Super Admin edit that breaks a server rule built on a specific authority, such as deleting
  `user:manage`, which the strict-subset rule depends on.
- Losing a role's authorities during the migration from the enum.

## Decided by the user

- Authorities are stored one document per authority, with `customer:create`-style codes as `_id`.
- Each authority has a group (e.g. `customer`) for searching in the role editor.
- The Super Admin manages authorities from the console, including creating new ones.
- Creating offers the codes found in code to pick from, and also allows typing a code by hand.
- A hand-typed code that no code checks is allowed, with a warning.

## Data model

### `authorities` collection

| Field | Notes |
|---|---|
| `_id` | The code, `thing:action`. Never changes. |
| `group` | Lower-case group name, e.g. `customer`. |
| `level` | `PLATFORM`, `COMPANY` or `BRAND`. Same meaning as in the roles spec. |
| `description` | One line shown in the console. |
| `builtIn` | `true` for authorities the server's own logic depends on. See below. |
| `createdAt`, `createdBy`, `updatedAt`, `updatedBy` | Usernames and instants. |
| `version` | `@Version`. A clashing save returns 409 `CONFLICT`, as for users. |

Validation:
- **Code:** lower-case segments of letters and digits, joined by single hyphens, written as two
  such segments with a colon between them. Pattern
  `^[a-z0-9]+(-[a-z0-9]+)*:[a-z0-9]+(-[a-z0-9]+)*$`, at most 64 characters.
- **Group:** one segment in the same format (`^[a-z0-9]+(-[a-z0-9]+)*$`), at most 40 characters.
  *(My call: keeping groups in one format stops `Customer` and `customer` becoming two groups.)*
- **Description:** required, trimmed, 1–200 characters.

Invalid input returns 400 `VALIDATION` with `fields`, as elsewhere.

### `authority_changes` collection

The same shape as `role_changes`:
- `authority` (the code)
- `action`: `CREATED`, `UPDATED` or `DELETED`
- `before` and `after` (group, level, description)
- `by` and `at`

Retention is the same as `role_changes`.

### Removed

- The `system_state` document `known-authorities` and its classes (`KnownAuthorities`,
  `KnownAuthoritiesRepository`).
- The migration deletes the document (see "Migration").

## Built-in authorities

The 11 authorities in today's enum are built-in:
- `platform-user:manage`, `customer:view`, `customer:create`, `customer:manage`, `whatsapp:bridge`
- `workspace:create`, `company-user:manage`
- `workspace:settings`, `user:view`, `user:manage`, `team:manage`

Server logic refers to these by name:
- the strict-subset rule and role validation (`user:manage`, `platform-user:manage`,
  `customer:manage`, `customer:create`, `company-user:manage`)
- the bridge WebSocket (`whatsapp:bridge`)
- the company user controller (`company-user:manage`)

So for a built-in authority:
- The description and group can be edited.
- The level can't change.
- It can't be deleted. Attempting it returns 403 `FORBIDDEN`, the same as deleting a built-in role.

The enum stays in code as the seed list and as constants for those references. It holds code,
level, description, group and default roles. The group defaults to the part before the colon.

A future feature that checks an authority outside `@PreAuthorize` adds it to the enum, so it is
built-in.

## Seeding and migration

The seeder runs at startup, after the built-in roles are seeded. For each enum entry:

- **If no `authorities` document exists for the code:**
  - Insert it from the enum, with `createdBy` set to `system`.
  - If the code is in `system_state/known-authorities`, it was seen before the migration. Don't
    touch any role.
  - Otherwise, add it to its default roles, as the seeder does today.
- **If the document exists:** leave it alone. The console owns description and group from now on.
  `builtIn` is forced to `true` and `level` is forced to the enum's value, in case either drifted.

After the loop, delete `system_state/known-authorities` if it exists.

Default roles therefore get a new built-in authority only when its document is first created. An
authority the Super Admin removes from a role stays removed, which is the job `known-authorities`
did.

Authorities created from the console have no default roles. The Super Admin ticks them into roles.

## Reading the checks in code

At startup, once the application context is ready, the server walks every bean whose class is under
`me.nageshk.omnichannel`. Use the target class, not the proxy. On each class and each method it
reads `@PreAuthorize` and pulls out codes from these forms:

| Form in the expression | Codes found | Suggested level |
|---|---|---|
| `hasAuthority('x:y')` | `x:y` | PLATFORM or COMPANY (none suggested) |
| `hasAnyAuthority('a:b', 'c:d')` | each quoted code | PLATFORM or COMPANY (none suggested) |
| `@access.has(<anything>, 'x:y')` | `x:y` | BRAND |

- Strings that aren't in code format, such as `'SUPER_ADMIN'`, are ignored.
- Several forms in one expression (`... or ...`) each count.
- Each code found keeps its locations, as `SimpleClassName.methodName`, or the class name alone
  for a class-level annotation, and the kinds of check it appeared in.
- The result is held in memory for the life of the process. Code can't change while the server
  runs.
- An expression that builds a code at runtime (from a variable or concatenation) is not found. That
  is a known limit, worth one line in the README.

## Status of an authority

The status is computed on each request from the stored documents and the scan. It is never stored.

| Status | Condition | Console |
|---|---|---|
| `ACTIVE` | Created, and checked in code with a kind that fits its level. | Normal. |
| `NOT_CHECKED` | Created, but no `@PreAuthorize` checks it. | Warning: "No code checks this yet. It has no effect until it does." Built-ins checked only in Java show this too; see below. |
| `WRONG_LEVEL` | Created as BRAND but checked only with `hasAuthority`, or created as PLATFORM but checked only with `@access.has`. A COMPANY authority fits both kinds of check. | Danger: "Checked as a brand authority in code but created as Platform" (or the reverse). That check can never pass for this authority. |
| `NEEDS_CREATING` | Checked in code, no document. | Listed separately, with a Create button. |

Why `WRONG_LEVEL` can never pass:
- `hasAuthority` only sees the platform and company authorities in the user's granted authorities.
- `@access.has` looks at the user's company role (its COMPANY codes) plus their role in that brand
  (its BRAND codes). It never sees PLATFORM codes; platform staff get 404 there.

*(My call: the rule follows `AccessService.inWorkspace` as it is today. The access check itself
does not change in this work.)*

Built-ins checked only in Java (`company-user:manage` is checked in `CompanyUserController` code) are
marked `ACTIVE`, not `NOT_CHECKED`, because the server knows it uses them. *(My call: flag
`builtIn` authorities as checked in code, so the page doesn't warn about the server's own rules.)*

## Granting and checking

These places read the `authorities` collection instead of the enum:
- `AccessService`, for the level of each code a role holds
- role validation in `RoleService`
- the preview in `AssignmentPolicy`

They read it through an `AuthorityCache`:
- It works like `RoleCache`: generation-guarded, and evicted on every authority create, update and
  delete.
- Evicting it also evicts `RoleCache`, because the authorities a role grants depend on levels.

Rules:
- A role can only hold authorities that have a document and whose level the role may hold. The
  `mayHold` rules are unchanged. A code with no document is rejected with 400 `VALIDATION` on the
  `authorities` field.
- `NOT_CHECKED` and `WRONG_LEVEL` authorities can still be put in roles, and are granted as usual.
  They do no harm, and the console marks them.
- **Super Admin:** holds every PLATFORM-level authority in the collection, plus the `SUPER_ADMIN`
  marker. A newly created PLATFORM authority applies from the Super Admin's next request.
- A role that holds a code whose document no longer exists ignores that code. This can't happen
  through the API, because deletion requires no role to hold it. It is defensive only.

## Editing and deleting rules

| Change | Allowed when | Otherwise |
|---|---|---|
| Edit description or group | Always | — |
| Change level | Not built-in, and no role holds it | 409 `AUTHORITY_IN_USE` (in a role) or 403 `FORBIDDEN` (built-in) |
| Delete | Not built-in, and no role holds it | 409 `AUTHORITY_IN_USE` or 403 `FORBIDDEN` |
| Create with an existing code | Never | 409 `DUPLICATE`, `field: "code"` |

`AUTHORITY_IN_USE` carries the names of the roles that hold it, so the console can say which. Every
create, update and delete writes an `authority_changes` entry.

## API

All endpoints are Super Admin only (`hasAuthority('SUPER_ADMIN')`). The code is a path segment;
the console URL-encodes it.

| Method | Path | Body / reply |
|---|---|---|
| GET | `/api/authorities` | All created authorities. Each has `code`, `group`, `level`, `description`, `builtIn`, `status`, `checkedIn` (locations), `roles` (names of roles holding it), `updatedAt`, `updatedBy`. Sorted by group, then code. **Replaces** today's reply of the same path. The role editor uses it. |
| GET | `/api/authorities/needs-creating` | Codes checked in code with no document: `code`, `suggestedLevel` (or null), `suggestedGroup` (the prefix), `checkedIn`. |
| POST | `/api/authorities` | `{code, group, level, description}` → 201 with the authority, as listed above. |
| PUT | `/api/authorities/{code}` | `{group, level, description}` → the authority. |
| DELETE | `/api/authorities/{code}` | 204. |
| GET | `/api/authorities/{code}/history` | `authority_changes` for the code, newest first. |

The builder confirms that a `:` in a path variable reaches the controller unchanged, through Spring
and the firewall. If it doesn't, the builder takes the code as a query parameter on those three
paths and notes it. *(My call.)*

## Console

### Authorities page

The page is called **Authorities**. It sits in the Platform nav group after Roles, and only the
Super Admin sees it.

**"Needs creating" panel** (shown only when there are any):
- Heading: "Checked in code, not created yet (N)".
- Each code shows where it is checked and has a **Create** button. The button opens the create
  dialog filled in with the code, the suggested level and the group.

**Table:**
- Columns: Code, Group, Level, Description, Status, Roles, Built-in.
- **Status labels:** none for Active, a warning label for Not checked, a danger label for Wrong
  level. The label's tooltip carries the sentence from "Status of an authority".
- **Roles:** the count, with role names in a tooltip.

**Toolbar:**
- One search box matching code, group or description, case-insensitive.
- A status filter: All, Active, Not checked, Wrong level.

**Row actions:**
- **Edit:** Delete is disabled for built-ins and for authorities in a role, with a tooltip saying
  why.
- **History:** opens a dialog like the role history dialog.
- **Delete:** confirms first.

### Create dialog

- **Code:** a typeahead offering the Needs-creating codes. Picking one fills in the suggested level
  and group.
  - A typed code that matches no scanned code shows an inline warning: "No code checks `x:y` yet.
    It will have no effect until it does."
  - A typed code in the wrong format shows the format error before saving.
- **Group:** a typeahead offering the groups already in use. A new group can be typed.
- **Level:** select.
  - For a code checked in code, choosing a level that doesn't fit the check shows the wrong-level
    warning inline. The fit rule is the one in "Status of an authority".
- **Description:** text.

Server errors map onto fields, as in the other dialogs.

### Edit dialog

- The code is shown read-only.
- The level is disabled for built-ins and for authorities in a role, with helper text saying why.

### Role editor

- Authorities are listed by group. Each group heading shows its name and "n of m selected".
- One search box above the list matches code, group or description. Groups with no match are
  hidden; a group whose name matches shows all its authorities.
- Not checked and Wrong level authorities carry the same labels as on the Authorities page.
- The existing rules stay: only levels the role may hold are offered, and the preview warning still
  shows.

## Testing

Backend, at least:
- **Scanner:** finds all three forms. Ignores `'SUPER_ADMIN'`. Reads class-level annotations.
  Handles `or` expressions. Reports locations. Ignores beans outside the package.
- **Seeding:** fresh database (documents inserted, defaults given). Database with
  `known-authorities` (documents inserted, no role changed, document deleted). Second start (no
  change). A built-in whose level drifted is corrected.
- **Status:** each of the four statuses, including built-ins that are only checked in Java.
- **API rules:** each row of the "Editing and deleting rules" table. Validation of code, group and
  description. Not Super Admin → 403.
- **Granting:** a console-created PLATFORM authority reaches the Super Admin on the next request. A
  console-created BRAND authority put in a brand role makes `@access.has` pass. Deleting an
  authority evicts the caches. A role save with an uncreated code → 400.
- **History:** entries for create, update and delete.
- **End to end:** a test controller with `@PreAuthorize("hasAuthority('test:probe')")` returns 403
  until the authority is created and put in the caller's role, then 200.

Frontend, at least:
- The Authorities page lists, searches and filters.
- The needs-creating panel opens a filled-in dialog.
- The typed-code warning and the wrong-level warning appear.
- Delete and level-change are disabled for built-ins and authorities in use.
- The role editor groups, searches, and counts per group.
- Server error codes `AUTHORITY_IN_USE` and `DUPLICATE` show readable messages.

## Left to the builder

- Class and package names, beyond those named here.
- How the scanner parses expressions. A regex over the expression string is fine.
- Whether `checkedIn` is collapsed when long.
- Copy for the new error messages, in the existing tone.
- PatternFly component choices for typeahead and grouping.
