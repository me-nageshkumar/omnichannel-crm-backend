# Calls and poll votes

Status: built 2026-11-01. The request skipped the questions, so every call below is mine.

## Intent

Call logs showed as "Unsupported message" and could fill a thread: 156 of one chat's 187 rows. A tap on a poll showed on
the phone only. A fetch also spent its last 20 seconds retrying pages that could never come.

## Calls

- A `call_log` is a compact line in the thread with no bubble and no ticks, because it happened in the chat rather than
  being said in it. The chat list previews it as "📞 Call".
- `WaWebMessage.call` is `{video, missed, durationSeconds}`, read by `MessagePayloads.call`.
- The line never says "voice": a call that does not say it is video may still be one.
- Rows stored before this have no `call`; the next fetch fills them in, and corrects a `call` read differently.
- **Unconfirmed:** the published model has `subtype` and `duration` only, and the live model could not be read without
  the Super Admin login. A wrong guess costs detail ("Incoming call" for a call that was missed), not correctness of the
  row. See `crm-pending.md` section G.

## Poll votes

- A vote is not a `Msg`. It goes to WhatsApp's votes table, so the page hooks
  `WAWebAddonPollVoteTableMode.pollVoteTableMode.bulkUpsert` as `whatsapp-web.js` does, and emits `vote_update`.
- `WaWebMessage.pollVotes` is one entry per voter holding their latest choice. Taking back every option removes the
  voter; an older or repeated vote changes nothing, so an outbox replay is harmless.
- A vote travels as positions in `pollOptions`, not WhatsApp's `localId`s, because that list is what the backend keeps.
- A live vote publishes `MessageUpserted` alone: it moves neither the preview nor the unread count, and does not bring
  the chat up the list.
- Past votes come with a fetch: `fetchChatHistory` adds `pollVotes` from `WAWebPollsVotesSchema` to each poll.
- The hook wraps WhatsApp's function and cannot be removed, so it is installed once and calls whoever is attached;
  installing it per injection would report each vote twice.
- "Response" was read as seeing responses. Voting from the console is not built.

## The wait

- A dated fetch noticed it had passed the range only after a load, so with older messages already in memory it never
  did. It now checks before loading, and ends with no empty page.
- An all-time fetch ends on empty pages, because WhatsApp's flag stayed false at the true start of a chat.
  `max-empty-pages` goes from 50 to 3: about one second. Only a page that loaded nothing counts: the page now reports
  `loaded`, so a range far back keeps walking through pages it keeps nothing from. A load that came back empty when it
  was merely slow ends the chat early, and a repeat fetch is free.
- The end of such a fetch reads "Reached the oldest message WhatsApp Web would load", because it cannot tell the start
  of a chat from a slow load.

## WhatsApp's own notices

- Added 2026-11-01, my call as the request skipped the questions. `notification_template` rows came in on the live path
  only: a fetch has always left out what WhatsApp marks as a notification. Three arrived in half a second when a
  business first wrote, with no text and no WhatsApp timestamp, because the phone draws the words itself. They added
  three to the chat's unread count and took over its preview from the photo that came with them.
- A notice is now skipped on both paths: no row, no chat, no unread, no reopen, no routing. The phone still has it.
- By type only, not by WhatsApp's `isNotification` flag: skipping drops the row for good, so a flag on a type not known
  here must not.
- The rows already stored are kept, as deleting needs asking, and show as a quiet "WhatsApp notice" line. A chat whose
  preview was a notice shows the real message before it instead.
- Showing the real words is not built: it needs a table of `subtype`s, which are not published. Each skipped notice logs
  its subtype and field names.
