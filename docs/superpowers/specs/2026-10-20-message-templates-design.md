# Message templates — design

Date: 2026-10-20. Status: approved for planning.

## Why

Sending messages and campaigns from the console is next. Before that, a brand needs reusable messages that
agents pick instead of typing the same text again and again. A template is a ready-made WhatsApp message (text,
media with a caption, location, contact card or poll). Its text has named placeholders, such as
`{{contact.name}}` or `{{delivery_date}}`. These are filled in at send time from the recipient's contact record
or from what the agent types.

This feature covers designing, storing, previewing and rendering templates. It does not cover sending them.
Sending and campaigns are separate features built on the render step defined here.

## Goals

- Customer admins, workspace managers and team leads create, edit, duplicate, archive and delete templates for a brand.
- Agents (and everyone above) can see a brand's templates. Later the send screen lets them pick one.
- Every message type WhatsApp Web can reliably send from a normal account is supported:
  text, image, video, document, audio, location, contact card and poll.
- Placeholders are names, never ordinals (`{{first_name}}`, never `{{1}}`).
- Rendering is safe: a template written by a customer can never run code on our server.
- The model can later carry WhatsApp Cloud API templates without being redesigned.

## Non-goals (this feature)

- Sending a message or running a campaign. Only the render/preview step is built here.
- An approval workflow. A saved template is usable straight away.
- Version history. Only "created by/at" and "last edited by/at" are kept.
- Buttons, list messages and stickers. WhatsApp no longer reliably delivers buttons and list messages from
  non-Business-API accounts, and a sticker has no text to template.
- Conditional text, loops and includes. Placeholders are plain substitution.
- Meta approval sync for Cloud API templates, and multi-language variants of one template.
- Sharing a template across brands. Templates are copied between brands instead (see Duplicate).

## Anti-goals (a failure even if it works)

- A template that can execute anything on the server (server-side template injection).
- Past messages changing when a template is edited.
- Broken sentences such as "Hi , your order…" because a contact value was blank.
- An attribute rename silently breaking templates.

## Template engine

**Mustache syntax, limited to named variables, rendered with JMustache** (`com.samskivert:jmustache`, a
single jar with no other dependencies).

- Mustache has no logic, so a template can't execute code. FreeMarker, Velocity, Thymeleaf and SpEL can,
  and templates here are written by customers.
- `{{name}}` is the same syntax as WhatsApp Cloud API named-parameter templates, so templates carry over when Cloud
  API numbers are supported.
- `{{ }}` does not clash with WhatsApp formatting (`*bold*`, `_italic_`, `~strike~`, ```` ```mono``` ````).
- JMustache settings: HTML escaping **off** (the output is WhatsApp text, not HTML). Missing variables throw, so
  every placeholder must have a value in the context. That is guaranteed because fallbacks are applied
  before rendering (see Rendering).
- Only the placeholder grammar below is accepted on save. Sections, inverted sections, partials, comments,
  triple braces, `&` and delimiter changes (`{{#`, `{{^`, `{{/`, `{{>`, `{{!`, `{{{`, `{{&`, `{{=`) are
  rejected with a field error. Any `{{` not followed by a valid placeholder and `}}` is rejected. A literal `{{` in
  message text is not supported.
- The console preview uses a small TypeScript function with the same grammar, so no JavaScript Mustache library is needed.

## Placeholders

### Grammar

Whitespace inside the braces is allowed and normalised away: `{{ contact.name }}` is the same as `{{contact.name}}`.

| Form | Kind | Value comes from |
|---|---|---|
| `{{contact.name}}` | CONTACT_FIELD | the contact's name |
| `{{contact.phone}}` | CONTACT_FIELD | the contact's phone number as `+` followed by the stored digits (e.g. `+919876543210`); empty when the number is hidden |
| `{{contact.attributes.<key>}}` | CONTACT_ATTRIBUTE | the contact's value for the brand attribute with that key |
| `{{<name>}}` | AGENT | typed by the agent at send time |

- `<key>` and `<name>` match `[a-z][a-z0-9_]{0,39}`.
- `contact` is reserved: `{{contact}}` and any other `contact.…` form are rejected.
- A template has at most **20 distinct placeholders**.

### Per-placeholder settings

The server finds the placeholders in the content on every save. The client sends a settings entry for each
distinct placeholder. An entry for a placeholder that isn't in the content is dropped, and a placeholder
without an entry is a field error.

| Setting | CONTACT_FIELD / CONTACT_ATTRIBUTE | AGENT |
|---|---|---|
| `fallback` | **required, non-blank**, ≤ 200 chars. Used when the contact has no value | only for optional fields, ≤ 200 chars. Used when the field is left empty. A required field has no fallback; one sent is a field error |
| `label` | not used (the field or attribute name is shown) | required, ≤ 60 chars, e.g. "Delivery date" |
| `example` | not used | optional, ≤ 200 chars, e.g. "25 Oct". Shown as a hint and used in the preview |
| `required` | not used | true/false. A required field must be filled in before sending |

A non-blank fallback for contact placeholders is what prevents "Hi , your order…".

### Attribute keys and bindings

Contact attribute values are stored by attribute id (`contact.attributes = {"<attributeId>": "Jaipur"}`), and attribute
names are free text ("Preferred size"). To get a stable, readable name:

- **`ContactAttribute` gets a `key` field.** It is generated from the name when the attribute is created:
  - lowercase the name;
  - replace each run of characters other than `a-z0-9` with `_` and trim `_` from both ends;
  - prefix `a_` if the result doesn't start with a letter;
  - cut to 40 characters;
  - on a clash within the brand, append `_2`, `_3` and so on.

  So "Preferred size" becomes `preferred_size`.
- **The key never changes**, even when the attribute is renamed. It is unique per brand (a unique index on
  `workspaceId + key`). The console shows it next to the attribute name.
- **Existing attributes** get keys from a one-off startup backfill, using the same rules in creation order.
  The demo seeder's attributes become `city`, `email` and `preferred_size`.
- **Binding on save.** Each `{{contact.attributes.<key>}}` is resolved against the brand's current attributes,
  and the attribute id is stored with the placeholder (`attributeId`):
  - The key matches a current attribute → bind to that attribute's id.
  - The key matches nothing, but the template's previous saved version already had this key → keep the old binding.
    It is now a "missing attribute", and rendering uses the fallback.
  - The key matches nothing and is new to this template → field error `Unknown contact attribute "<key>"`.
- **Render follows the stored `attributeId`, not the key.** An attribute rename never affects templates, because the id and
  key don't change.
- **Deleting an attribute is always allowed.** Templates that use it keep working on their fallback. The template
  list and editor show a warning on such templates: "Attribute deleted — fallback will be used". The warning is computed
  when the template is read: the bound id no longer exists in the brand.
- **Delete, then recreate under the same key** (e.g. "City" is deleted and created again as `city`). The template stays on
  the fallback until someone saves it again; that save re-binds to the new attribute. The warning then reads
  "Attribute deleted — a new \"city\" exists; save the template to use it". Templates are never re-bound behind the
  editor's back.

## Message types and content

`type` is chosen when the template is created and can't be changed afterwards. To change it, duplicate the
template and pick a new type. Placeholders may appear only in the fields marked ✓. The limits follow the Cloud API
where it has one, which keeps that door open.

| Type | Content fields | Placeholders allowed in |
|---|---|---|
| TEXT | `body` (1–4096 chars), `linkPreview` (bool, default true) | body ✓ |
| IMAGE | media (JPEG, PNG), `caption` (0–1024) | caption ✓ |
| VIDEO | media (MP4, 3GPP), `caption` (0–1024) | caption ✓ |
| DOCUMENT | media (PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, TXT, CSV, ZIP), `caption` (0–1024) | caption ✓ |
| AUDIO | media (MP3, M4A/AAC, OGG), `voiceNote` (bool; allowed only for OGG/Opus) | — |
| LOCATION | `latitude` (−90..90), `longitude` (−180..180), `name` (0–256), `address` (0–256) | name ✓, address ✓ |
| CONTACT_CARD | `fullName` (1–256), `phone` (digits, `^[1-9][0-9]{7,14}$`), `organization` (0–256), `email` (0–256, valid email) | fullName ✓, organization ✓ |
| POLL | `question` (1–255), `options` (2–12, each 1–100, distinct ignoring case), `allowMultiple` (bool) | question ✓, options ✓ |

Notes:
- **Contact card.** A vCard 3.0 is generated at render time from the rendered fields. whatsapp-web.js sends it
  with `parseVCards`.
- **Poll.** Options must still be distinct after rendering. If a rendered poll has duplicate options, rendering
  fails with a clear error.

## Media

- **Storage.** Files go in MongoDB GridFS, bucket `template_media`. There's no new infrastructure. The metadata saved with
  each file is `workspaceId`, `fileName`, `mimeType`, `size`, `uploadedBy` and `uploadedAt`.
- **Size.** At most **16 MB** per file, matching WhatsApp's media limit. `spring.servlet.multipart.max-file-size` and
  `max-request-size` are raised to 16 MB and 17 MB. Contact import keeps its own 5 MB check in `ContactImportService`.
- **Upload flow.** Upload first, then reference: `POST /api/workspaces/{ws}/template-media` (multipart, `template:manage`)
  returns `{mediaId, fileName, mimeType, size}`. The template's `media.mediaId` refers to it.
  - On save, the server checks that the media belongs to the same brand, that its MIME type is allowed for the template type,
    and that no other template already references it. Each file belongs to exactly one template, and a shared reference
    is a field error.
  - The MIME type is detected from the file's first bytes, not trusted from the browser. Apache Tika is not added;
    a small magic-byte check for the listed formats is enough. The one exception: TXT and CSV are accepted by extension
    when the file contains no NUL bytes.
- **Orphan sweep.** A daily job deletes uploads older than 24 hours that no template refers to. Replacing a
  template's media deletes the old file when the template is saved. Deleting a template deletes its file.
- **Download.** `GET /api/workspaces/{ws}/template-media/{mediaId}` (`template:view`) streams the file, for the
  console preview and later for sending.

## Template record

Collection `message_templates`:

| Field | Notes |
|---|---|
| `id`, `workspaceId`, `customerId` | the brand it belongs to |
| `channel` | `ConnectionType`; always `WHATSAPP_WEB` for now. Cloud API templates will use `WHATSAPP_CLOUD_API`, plus fields for Meta's category, language and approval status, which are not built now |
| `name`, `nameKey` | 1–100 chars, unique per brand ignoring case, **archived templates included** (`nameKey` = lowercased, unique index `workspaceId + nameKey`) |
| `category` | optional free text, 1–40 chars, e.g. "Greeting". The editor suggests the brand's existing categories |
| `type` | one of the eight types above |
| `content` | type-specific fields as above (one sub-document; unused fields are null) |
| `media` | `{mediaId, fileName, mimeType, size}` for the media types, else null |
| `placeholders` | list of `{name, kind, attributeKey?, attributeId?, label?, example?, required, fallback?}` in order of first appearance |
| `status` | `ACTIVE` or `ARCHIVED` |
| `lastUsedAt` | null until the send feature sets it. A template with a non-null `lastUsedAt` can be archived but not deleted |
| `createdBy`, `creationTime`, `updatedBy`, `lastUpdateTime` | user ids and instants, shown as names in the console |
| `version` | `@Version`. A concurrent edit returns 409 "This template was changed by someone else. Reload and try again." |

## Lifecycle

- **Create / edit.** The change is live as soon as it is saved, with no approval. Editing affects future sends only. Each
  sent message (send feature) stores its own rendered output, so history never changes.
- **Archive / unarchive.** Archived templates are hidden from the send picker (send feature) and from the
  default list view, but kept, because past messages refer to them. Unarchive makes the template Active again.
- **Delete.** Allowed only while `lastUsedAt` is null; otherwise the response is 409 "This template has been used; archive it instead".
  Until the send feature exists, every template can be deleted.
- **Duplicate.** `POST …/templates/{id}/duplicate` with `{targetWorkspaceId, name}`.
  - The target can be the same brand or another brand of the same customer where the user has `template:manage`.
  - The media file is copied, so the two templates' files are independent.
  - Attribute placeholders are re-bound by key in the target brand. A key the target lacks is copied as a missing-attribute
    binding with the warning, which is the one case where a save may hold a key unknown to the brand.
  - Status of the copy is ACTIVE.

## Authorities

They are added to the `Authority` enum and granted to the built-in roles by the existing `RoleSeeder`
(new codes are added to their default roles once). Both are brand-level (`RoleLevel.BRAND`):

| Code | Description | Default roles |
|---|---|---|
| `template:view` | See the brand's message templates and preview them | Customer Admin, Workspace Manager, Team Lead, Agent |
| `template:manage` | Create, edit, duplicate, archive and delete message templates, upload template media | Customer Admin, Workspace Manager, Team Lead |

Super Admin has everything, as always. Account Manager gets neither by default, and either can be granted through role
management. Anyone with `template:manage` in a brand can edit any template in it, not only their own.

## API

All endpoints are under `/api/workspaces/{workspaceId}` and follow the contact endpoints' conventions: brand-access check,
field errors as 400 `{errors: {field: message}}`, and 404 for another brand's ids.

| Method & path | Authority | Purpose |
|---|---|---|
| `GET /templates?q=&type=&category=&status=` | view | list; `status` defaults to ACTIVE; `q` matches name, case-insensitive; sorted by name |
| `GET /templates/categories` | view | the brand's distinct categories, for the editor's suggestions |
| `GET /templates/{id}` | view | one template, with a `warnings` list (e.g. missing attributes) |
| `POST /templates` | manage | create |
| `PUT /templates/{id}` | manage | replace name, category, content, media and placeholders (`version` required) |
| `POST /templates/{id}/archive`, `/unarchive` | manage | change status |
| `DELETE /templates/{id}` | manage | delete (409 if used) |
| `POST /templates/{id}/duplicate` | manage | copy into this or another brand |
| `POST /templates/{id}/render` | view (+ `contact:view` when `contactId` is given) | see Rendering |
| `POST /template-media`, `GET /template-media/{mediaId}` | manage / view | upload / download |

## Rendering

`POST /templates/{id}/render` with `{contactId?, values: {<agentPlaceholderName>: string}}` returns the rendered message:

```
{ type, text fields rendered (body / caption / name / address / fullName / organization / question / options),
  untouched fields (media, latitude, …), vcard? (CONTACT_CARD),
  missing: [names of required agent placeholders with no value],
  warnings: [e.g. "Attribute \"city\" was deleted — fallback used"] }
```

The context is built first, then rendered with JMustache:

1. **Contact values.** From the contact when `contactId` is given (same brand, or 404). With no contact, as in a plain
   preview, every contact placeholder uses its fallback.
2. **Fallbacks.** A blank contact value, or a missing attribute, uses the placeholder's fallback.
3. **Agent values.** Values are trimmed and capped at 1000 chars.
   - An empty optional value uses its fallback, or empty text if it has none.
   - A required placeholder whose trimmed value is empty goes into `missing`. Required fields have no fallback. The render
     still returns, showing `[label]` in that spot, so the preview can display it. The send feature will refuse to send
     while `missing` is non-empty.
4. **Length check.** After rendering, the type's length limits are checked again. A body pushed over 4096 by long values
   returns 400 with a field error naming the field.

The render endpoint is the single place where text is produced. The send and campaign features call the same service method.

## Console

New **Templates** page in the brand navigation, shown to users with `template:view`. Its controls follow the Contacts
page's PatternFly look.

- **List.** A table with columns Name, Type (icon + label), Category, Placeholders (count), Last edited (who/when) and
  a warning icon for missing attributes. There is a search box and filters for Type, Category and Status (Active/Archived).
  Row actions (manage only) are Edit, Duplicate, Archive/Unarchive and Delete. A "Create template" button opens the
  type picker.
- **Editor** (create and edit; read-only for view-only users). Two columns:
  - **Left:** Name and Category (typeahead from categories), then the type-specific form: a text area with a
    formatting toolbar (*B*, _I_, ~S~, mono), a media upload drop zone, poll options and so on. Each text field that
    allows placeholders has an **Insert placeholder** menu:
    - Contact → Name, Phone, and each brand attribute (name + key);
    - **New field…**, which asks for a name and inserts `{{name}}`.
  - Below the form, a **Placeholders** panel lists every detected placeholder with its settings (fallback;
    or label, example and required). It updates live as the text changes.
  - **Right:** a WhatsApp-style **preview** bubble that renders formatting and fills placeholders. Agent placeholders show
    their example, or `[label]`, and contact placeholders show their fallback. **Preview with a contact** lets you pick a
    contact and calls the render endpoint to show their real values.
- Text that is invalid on the client (bad placeholder syntax, limits) shows inline before save. The server stays the authority.

## Demo seed

With `OMNICHANNEL_SEED_DEMO=true`, the seed for each demo brand (Demo Fashion, Demo Electronics) is skipped if that
brand already has templates. Otherwise each brand gets five templates:

- "Welcome" (TEXT, `{{contact.name}}`)
- "Order shipped" (TEXT, `{{contact.name}}`, `{{order_id}}`, `{{delivery_date}}`)
- "New collection" (IMAGE with caption and `{{contact.attributes.city}}`, using a small bundled PNG)
- "Store location" (LOCATION)
- "Feedback poll" (POLL)

## Testing

- **Unit tests** for the placeholder parser:
  - every accepted form and whitespace normalisation;
  - each rejected Mustache construct;
  - reserved `contact`, the 20-placeholder limit, unclosed `{{`.
- **Unit tests** for attribute key generation, including clashes, non-letter starts and the 40-char cut.
- **ITs** following the existing `…IT` style:
  - create/edit/list/filter per type, with the limits;
  - duplicate name (409 or 400 consistent with contacts' label uniqueness);
  - optimistic-lock 409;
  - archive/unarchive, delete;
  - duplicate across brands with a missing attribute;
  - authorities: an agent can view and render but not manage, and another customer's brand gives 404;
  - media upload type sniffing and the size limit, the orphan sweep, media copy on duplicate and deletion on delete;
  - render with and without a contact, with fallbacks, missing required values, a deleted attribute and a post-render length overflow;
  - attribute key backfill.
- **Console tests** (vitest + Testing Library, as for Contacts): list filters, the editor's placeholder panel
  updating from the text, insert-placeholder menu, preview fill, and view-only mode.

## Brand and customer deletion

Brands and customers can only be disabled today, never deleted, so nothing cascades. When brand or customer deletion is
built, it must delete the brand's templates and their `template_media` files.

## Calls made without asking (check these)

- Render follows the stored attribute id, and re-binding after delete-and-recreate happens only on the next save.
- `{{contact.phone}}` renders with a leading `+`.
- Required agent placeholders have no fallback.
- Each media file belongs to exactly one template.
- Name uniqueness includes archived templates.
- 16 MB media limit; text 4096 and captions 1024 (Cloud API limits); at most 20 placeholders.
- Template type can't change after creation.
- Category is free text with suggestions, not a fixed list.
- Contact card placeholders are allowed in name and organization only.
- Voice note is allowed only for OGG/Opus audio.
- Archived templates stay in the console list behind the Status filter.
- Account Manager gets no template authorities by default.

## Left to the builder

- Package layout (suggested: `me.nageshk.omnichannel.template`), class names, DTO shapes beyond the fields listed.
- The exact console layout within the PatternFly patterns already used.
- The magic-byte table for the listed formats.
- How the daily orphan sweep is scheduled (`@Scheduled` is fine).
