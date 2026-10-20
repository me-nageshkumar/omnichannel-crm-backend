# Message Templates (console) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Templates page for each brand: a list with filters and row actions, an editor for all eight message types
with an insert-placeholder menu and a placeholders panel, and a WhatsApp-style live preview, including a preview with a
real contact.

**Architecture:**
- `src/app/api/templates.ts` is the client.
- `src/app/Templates/placeholders.ts` is a pure grammar and preview module that mirrors the backend `PlaceholderParser`.
- `src/app/Templates/` holds the pages and modals, built from `useLoad`, `useAction`, `ConfirmModal`, `PageShell` and
  PatternFly 6.

**Tech Stack:** React 18, TypeScript strict, PatternFly 6.5.1, @patternfly/react-table, React Router 7, Vitest +
Testing Library, `mockFetch` route keys `"METHOD /path"`.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-20-message-templates-design.md`. Consumes the **API
contract** in `omnichannel-crm-backend/docs/superpowers/plans/2026-10-20-message-templates-backend.md` exactly.

## Global Constraints

- **Repo and branch:** repo `omnichannel-crm-frontend`, branch `feature/message-templates` in the main checkout (no `.worktrees`).
- **Routes and nav** (Brand group, after Contact fields):
  - **Templates** `/templates`, `requires: a => a.hasInWorkspace('template:view')`;
  - `/templates/new?type=<TYPE>` and `/templates/:id`, same `requires`, no nav label.
- **Manage-only controls:** without `template:manage`, the Create button, row actions and editor inputs are hidden or
  read-only. The preview stays usable for everyone.
- **Grammar** is identical to the backend:
  - names `[a-z][a-z0-9_]{0,39}`;
  - `contact.name`, `contact.phone`, `contact.attributes.<key>`;
  - at most 20 distinct placeholders;
  - the same error messages as the backend Task 2.
- **Copy:**
  - type labels: Text, Image, Video, Document, Audio, Location, Contact card, Poll;
  - empty list: "No templates match.";
  - delete confirm: "Delete "<name>"? This cannot be undone.";
  - a 409 `TEMPLATE_IN_USE` shows the server message;
  - a 409 `CONFLICT` on save shows "This template was changed by someone else. Reload to see the latest version." and
    keeps the user's edits;
  - warning icon tooltip: the warning text.
- **Preview formatting:** `*bold*`, `_italic_`, `~strike~` and ` ```mono``` ` render as `<strong>`, `<em>`, `<s>` and
  `<code>`. Newlines are kept. The text is rendered with React elements, never with `dangerouslySetInnerHTML`.
- **Media display:** media needs the auth header. Add `apiFetchBlob(path, token): Promise<Blob>` (GET) to
  `api/client.ts`, show the media via `URL.createObjectURL`, and revoke the URL on unmount.
- **Test fixtures** in `src/test/session.ts`: add `template:view` and `template:manage` to `BRAND_CODES`, `template:view`
  and `template:manage` to `TEAM_LEAD`, and `template:view` to `AGENT_IN_BRAND`. A new `src/test/templates.ts` holds
  `template(over)` and `placeholderView(over)` factories.
- **Gate:** `npm run ci-checks` and `npm run build`. Format only touched files with `npx prettier --write <files>`.
  Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Placeholder settings survive editing.** Typing in the body re-derives the placeholder list, but settings already
   entered for a name that is still present are kept. Removing then re-adding a name within the session restores them.
   (Task 3 `settingsKeptWhileTyping`.)
2. **Insert at the cursor.** The insert-placeholder menu inserts at the caret of the focused field, not at the end.
   (Task 3 `insertAtCursor`.)
3. **Server field errors land on the right control.** For example `content.caption`, `placeholders.order_id.label` and
   `mediaId`. (Task 3 `serverFieldErrorsShownInline`.)
4. **A stale save (409 CONFLICT)** shows the reload message and does not lose edits. (Task 3 `conflictKeepsEdits`.)
5. **An agent's view is read-only.** There are no Create, Edit or Delete controls, the editor fields are disabled, and
   the preview works. (Task 2 `agentSeesReadOnly`, Task 4 `agentCanPreviewWithContact`.)

---

### Task 1: API client, grammar module, attribute keys

**Files:** `api/templates.ts` (all types and calls in the contract), `api/client.ts` (`apiFetchBlob`),
`Templates/placeholders.ts`, `api/contacts.ts` (`ContactAttribute.key`), `Contacts/ContactFieldsPage.tsx` (show the key
in the Attributes table as a "Placeholder" column `{{contact.attributes.<key>}}`), and `src/test/contacts.ts`
(`attribute()` default key).

**Interfaces — Produces:**
- `api/templates.ts` functions, each taking `(token, ws, …)` like `api/contacts.ts`:
  - `listTemplates(token, ws, {q, type, category, status})`, `getTemplate`, `getCategories`;
  - `createTemplate(token, ws, req)`, `updateTemplate(token, ws, id, req)`;
  - `archiveTemplate`, `unarchiveTemplate`, `deleteTemplate`;
  - `duplicateTemplate(token, ws, id, {targetWorkspaceId, name})`;
  - `renderTemplate(token, ws, id, {contactId?, values})`;
  - `uploadTemplateMedia(token, ws, file: File): Promise<MediaView>`;
  - `templateMediaUrl(ws, mediaId): string`, used with `apiFetchBlob`.
- `parsePlaceholders(text: string): { refs: PlaceholderRef[]; error: string | null }`, where
  `PlaceholderRef = { name: string; kind: 'CONTACT_FIELD' | 'CONTACT_ATTRIBUTE' | 'AGENT'; attributeKey: string | null }`.
- `fillPreview(text: string, values: Record<string, string>): string`. It replaces every `{{ name }}` with `values[name]`
  and leaves an unknown name as `[name]`.
- `formatWhatsApp(text: string): React.ReactNode[]`.
- `PLACEHOLDER_FIELDS: Record<TemplateType, (keyof TemplateContent)[]>` lists the fields that allow placeholders, per the backend's Global Constraints.

Tests:
- `Templates/placeholders.test.ts`: the same cases as backend `PlaceholderParserTest`, plus `fillPreview` and
  `formatWhatsApp` (bold, italic, strike, mono, nested text kept literal, `<script>` shown as text).
- `contactFields.test.tsx`: `attributeKeyShown`.

- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: template api client and placeholder grammar`.

### Task 2: Templates list

**Files:** `Templates/TemplatesPage.tsx`, `Templates/DuplicateModal.tsx`, `Templates/typeMeta.ts` (label and icon per
type), `routes.tsx` and the routes test.

Layout:
- Toolbar: a SearchInput (debounced 300 ms → `q`), Type and Category selects, a Status toggle (Active/Archived), and a
  "Create template" button. The button opens a dropdown of the eight types and navigates to `/templates/new?type=…`.
- Table: Name (link to the editor), Type (icon + label), Category, Placeholders (count), Last edited ("name · relative
  time"), and a warning icon when `warnings.length`.
- Row kebab: Edit, Duplicate, Archive or Unarchive, Delete.
- Duplicate modal: a target brand select listing the user's brands where they have `template:manage` (from the session
  access), and a name defaulting to "<name> (copy)". When the target is another brand, the success alert says
  "Copied to <brand>", with no navigation.

Tests `Templates/templates.test.tsx`:
- `listsTemplates`, `filtersSendQuery`, `archivedToggle`, `warningIconShown`;
- `createMenuNavigatesWithType`, `duplicateToSisterBrand`, `deleteConfirmAndInUseMessage`, `archiveUnarchive`;
- `agentSeesReadOnly`, `hiddenWithoutTemplateView` (no nav entry).

- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: templates list`.

### Task 3: Editor

**Files:** `Templates/TemplateEditorPage.tsx`, `Templates/ContentForm.tsx` (per-type fields), `Templates/InsertPlaceholderMenu.tsx`,
`Templates/PlaceholdersPanel.tsx`, `Templates/MediaField.tsx`.

**Interfaces — Consumes:** Task 1's `parsePlaceholders` and `PLACEHOLDER_FIELDS`, `listAttributes` from `api/contacts.ts`,
and `uploadTemplateMedia` and `getCategories` from `api/templates.ts`.

Header and fields:
- Name, plus Category as a typeahead from `getCategories` that accepts a new value. The type is shown as a fixed label.
- `ContentForm` shows only the fields for the type:
  - TEXT: a TextArea with a toolbar (B, I, S, mono) that wraps the selection in `*`, `_`, `~` or ```` ``` ````, a
    character counter `n / 4096`, and a "Show link preview" switch;
  - media types: `MediaField` (a FileUpload drop zone with an accept list per type and a 16 MB client check), the
    current file name and size, a Replace button, and a caption with counter `n / 1024`; AUDIO also has a "Send as
    voice note" switch, enabled only for `audio/ogg`;
  - LOCATION: latitude, longitude, name, address;
  - CONTACT_CARD: full name, phone, organization, email;
  - POLL: question, an options list with add/remove (2–12), and an "Allow multiple answers" switch.
- Each field in `PLACEHOLDER_FIELDS[type]` has an `InsertPlaceholderMenu` button:
  - groups: Contact (Name, Phone, then each attribute "City · {{contact.attributes.city}}") and **New field…**;
  - New field… opens a small modal that validates the name with the grammar and inserts `{{name}}`;
  - the text is inserted at the stored caret position of that field.
- `PlaceholdersPanel` re-derives refs on every change from all placeholder-capable fields, in field order:
  - contact rows: the field or attribute name and a Fallback input (required);
  - agent rows: Label (required), Example, a Required checkbox, and Fallback (shown only when not required);
  - settings are stored in a `Record<name, settings>` that is never pruned while the editor is open, so typing doesn't
    lose them; only the current refs are sent.
- Client checks before save: grammar errors on the field, counters, required settings. The server's 400 `fields` are
  mapped onto controls by key (`content.<f>`, `placeholders.<name>.<setting>`, `mediaId`, `name`, `category`).
- Save: POST, or PUT with `version`. After a create, navigate to `/templates/:id`. A 409 CONFLICT shows the alert and
  keeps the state.
- An Archive/Unarchive button in the header, for manage only.
- Without `template:manage`, every input is disabled and the Save, Insert and Upload controls are hidden.

Tests `Templates/templateEditor.test.tsx`:
- `createTextTemplate` (sends the exact request body), `settingsKeptWhileTyping`, `insertAtCursor`,
  `insertContactAttribute`, `newFieldValidatesName`;
- `requiredHidesFallback`, `grammarErrorShownBeforeSave`, `serverFieldErrorsShownInline`, `conflictKeepsEdits`;
- `imageUploadSetsMedia` (FormData POST then mediaId in the save), `voiceNoteOnlyForOgg`, `pollOptionsAddRemove`,
  `locationAndContactCardFields`;
- `editLoadsExisting`, `agentEditorIsReadOnly`.

- [ ] Failing tests → implement → `npm run ci-checks` → commit `feat: template editor`.

### Task 4: Preview

**Files:** `Templates/TemplatePreview.tsx` (right column of the editor), `Templates/PreviewWithContactModal.tsx`.

Behaviour:
- **Local preview:** a WhatsApp-style bubble (light green outgoing bubble, works in dark mode via tokens) that shows the
  draft. Values come from `fillPreview`:
  - agent placeholders get the example, else `[label]`, else `[name]`;
  - contact placeholders get the fallback, else `[name]`.
- **Per type:**
  - media: the thumbnail via `apiFetchBlob`, or a file card for documents and audio, with the caption below;
  - location: a pin card with the name and address;
  - contact card: a card with the name and phone;
  - poll: the question with radio or checkbox options.
- **Preview with a contact:**
  - a button opens a modal with a contact search (`POST …/contacts/search` with `{filter: {q}}`, first 10 of page 0) and inputs for the agent
    placeholders, pre-filled with examples;
  - "Preview" calls `POST …/templates/{id}/render` and shows the returned rendered content in the same bubble, plus
    `missing` ("Fill in: Delivery date") and `warnings`;
  - the button is enabled only for a saved template with no unsaved changes, with the tooltip "Save first to preview
    with a contact";
  - it is hidden without `contact:view`.

Tests `Templates/templatePreview.test.tsx`:
- `previewFillsExamplesAndFallbacks`, `formattingRendered`, `scriptTextNotExecuted`;
- `pollPreview`, `imagePreviewUsesBlob`;
- `previewWithContactCallsRender`, `missingAndWarningsShown`, `disabledWhileDirty`, `agentCanPreviewWithContact`.

- [ ] Failing tests → implement → `npm run ci-checks && npm run build` → commit `feat: template preview`.
