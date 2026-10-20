# New chat from the inbox — design

Addendum to `2026-10-21-chat-inbox-design.md`. Lets a console user start a WhatsApp Web
conversation with any phone number from the inbox, with a text or a template as the first message.

## 1. Intent

The inbox only shows chats that already exist. Agents also need to reach out first: a lead from a
form, a callback promised on the phone, a follow-up to a contact who never wrote on WhatsApp. The
user asked for: a textbox for the phone number, template selection when the first message is a
template, and a "New chat" button on the inbox page.

### Goals
- Start a chat with a number not yet in the inbox and send the first message in one go.
- When the number already has a chat, land in that chat instead of creating a second one.
- Reuse the inbox send path (`InboxSendService`), the template picker and the composer rules.

### Non-goals
- Checking that the number is registered on WhatsApp before sending (needs a new extension page
  method; a typo'd number produces a chat whose message fails to deliver). Deferred.
- Bulk or scheduled outreach (campaigns reuse this later).
- Cloud API numbers (they report `NO_DEVICE` like any send today).

### Anti-goals
- An agent taking over a chat that belongs to someone else by "starting" it.
- An empty chat row left behind when the first message never left (so the chat is only stored
  once the device accepted the message).

## 2. Behaviour

`POST /api/workspaces/{ws}/inbox/phone-numbers/{pn}/chats`, `inbox:send`, body
`{"phone": "…", "text": "…"}` or `{"phone": "…", "templateId": "…", "values": {…}}`.
Returns the `MessageView` of the first message (its `chatId` is where the console goes next).

1. `phone` is cleaned with `Phones.normalise` and must pass `Phones.isValid`, else 400 VALIDATION
   `fields.phone = "must be 8 to 15 digits with the country code, e.g. 919812345678"`. The remote
   id is `<digits>@c.us`.
2. The number must be in the workspace (404) and its device ready (`InboxDevices`; 409 `NO_DEVICE`
   / `WHATSAPP_NOT_READY`), checked before anything is written.
3. If a chat `(pn, remoteId)` exists:
   - not visible to the caller **and** assigned to a user → 409 `CHAT_TAKEN`
     "This number already has a chat assigned to someone else"; nothing is sent.
   - CLOSED → reopened by the caller (records `REOPENED`).
   - no `assignedUserId` → assigned to the caller (records `ASSIGNED`, via
     `ChatAssignmentService.assign(me, chat, me.userId(), null)`), whatever `claimOnReply` says:
     starting a chat is an explicit claim.
   - then the message is sent exactly as `POST /chats/{id}/messages` would.
4. If no chat exists: the contact is upserted for the remote id (`ContactService.upsertFromChat`
   with no name), and an **unsaved** `WaWebChat` (OPEN, `assignedUserId = me`, `assignedAt = now`)
   is handed to `InboxSendService.send`. The chat row is saved only when the device answered, in
   the same step that stores the message. No `ASSIGNED` action is recorded for a chat born
   assigned to its creator (the first message, "sent by me", says it). A failed RPC leaves no chat
   row; the contact may remain (harmless, and it is what the agent typed).
   - Race: an inbound message creates the same chat between the lookup and the save. The unique
     index rejects the save, the existing retry re-runs the step, which then finds the chat by
     `(pn, remoteId)` and stores the message into it (so the routed or unassigned state wins;
     claim-on-reply applies as usual).
5. The live stream pushes the chat (`ChatChanged`) and the message as for any send, so the chat
   appears at the top of the sender's list.

## 3. Console

- **New chat** button (secondary) in the inbox header row next to "Change number", shown to holders
  of `inbox:send`. Opens the **New chat** modal.
- Modal: "Phone number" text input (helper "Country code first, digits only, e.g. 919812345678";
  client check after stripping `+`, spaces, dashes and brackets: `^[1-9][0-9]{7,14}$`, error
  "Enter 8 to 15 digits with the country code"), "Message" textarea (same 4096 limit and counter
  as the composer), **Template…** button (opens the template picker; the template becomes the first
  message), **Send**, **Cancel**. Send and Template… are disabled until the phone is valid; both are
  disabled, with the composer's notice, when the number is not CONNECTED. Server VALIDATION errors
  land under the field they name; `CHAT_TAKEN` and the rest show as an inline alert through
  `messageFor`.
- The template picker no longer needs a chat: it takes `contactId: string | null` (preview without
  a contact for a new number) and a `send(templateId, values)` callback, so the composer and the
  new-chat modal share it unchanged otherwise.
- After the first message is accepted: close the modal, reload the list, navigate to
  `/inbox/:phoneNumberId/:chatId`.

## 4. Calls made without asking

- Explicit claim on start, independent of `claimOnReply`.
- Born-assigned chat records no `ASSIGNED` action.
- A chat assigned to someone the caller cannot see is refused rather than reassigned or silently
  posted into.
- Registration check on WhatsApp deferred (see non-goals).
