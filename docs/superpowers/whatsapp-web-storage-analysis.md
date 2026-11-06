# How WhatsApp Web stores and models its data — analysis of a live tab

Read on 2026-11-05 from the Main line's WhatsApp Web tab (build 2.3000.1053060988, Chrome 150) through Claude in
Chrome, read-only. Numbers are from that one account at that moment. The design that uses this is
`specs/2026-11-05-cloud-webhook-shape-design.md`; the fields our page script reads are in §3 below.

## 1. Where the data lives in the browser

Storage use: 123 MB. Cache Storage 61 MB, IndexedDB 56 MB, service worker 6 MB. No OPFS.

| Store | What it holds |
|---|---|
| IndexedDB `model-storage` (v2140, 118 object stores) | the models: `message` (2 966), `chat` (729), `contact` (6 942), `group-metadata` and `participant` (72), `message-info` (receipts, 63), `reactions` (127), `poll-votes` (3), `message-association` (albums, 76), `active-message-ranges` (per-chat history windows, 8), `lid-pn-mapping` (empty on this account), `profile-pic-thumb`, `blocklist`, `label*`, `device-list`, `sync-actions` (6 599, app-state sync), `abpropConfigs` (feature flags), … |
| IndexedDB `fts-storage` (v90) | full-text search: `fts-v3-index` (2 815 rows: `chatId`, `id`, `prefixes`, `timestamp`), a purge queue and a manifest |
| IndexedDB `signal-storage` (v90) | the end-to-end encryption state: `identity-store` (1 546), `session-store` (262), `prekey-store` (524), `senderkey-store` (222), signed and kyber prekeys |
| IndexedDB `lru-media-storage-idb` + Cache Storage `lru-media-array-buffer-cache` | downloaded media: meta rows (`id`, `size`, `timestamp`, 174) with the bytes in the cache |
| IndexedDB `wawc` | telemetry and 25 000 log lines (`logs`), user prefs (`user`), tokens |
| IndexedDB `wawc_db_enc` | the keys that encrypt message content at rest (`keys`, `fts_hmac_keys`, `worm_ear_keys`) |
| IndexedDB `offd-storage`, `jobs-storage`, `worker-storage`, `status-storage`, `/wa_voip_storage` | offline receipts, pending device sync (277), jobs, status, calls |
| `localStorage` (149 keys) | settings and migration flags; most keys are base64-obfuscated names; readable ones include `WALangUserPref`, `theme`, `WANoiseInfoIv`, `WebEncKeySalt`, `*LidMigrationComplete` |

**Message content is encrypted at rest.** An IndexedDB `message` record has its indexable fields in the clear —
`id`, `internalId`, `rowId`, `type`, `typeFlag`, `t`, `from`, `to`, `ack`, `author`, `hsmTag`, `isMediaMsg`,
`isDocMsg`, `isCallLogMsg`, `isStarred`, `hasLink`, `hasReaction`, `encFilehash`, `expiredTimestamp`,
`revokeTimestamp`, `vcardWAids`, `messageRangeIndex`, … — and everything else (the body, caption, media keys,
quoted message, buttons) inside `msgRowOpaqueData = {_data: ArrayBuffer, iv: Uint8Array(16), _keyId, _scheme}`,
decrypted with a key from `wawc_db_enc` when the model is loaded. Reading the database directly therefore yields
metadata only; the readable message is the in-memory model (§2). The `chat` and `contact` records are in the clear.

Keys and indexes worth knowing:
- `message.id` is the serialized key `fromMe_remote_id` (string); `internalId` is `<chat>_<hash>_m` (unique index);
  `rowId` is a unique monotonic integer; `[typeFlag+t+internalId]` is the index a chat's timeline is read from;
  `protocolMessageKey` and `latestEditMsgKey` index revokes and edits to their target.
- `chat.id` is the chat's wid string; indexes `accountLid` (unique), `historyChatId` (unique), `tcTokenTimestamp`.
  Of 729 chats, 478 carry `historyChatId` and 51 a `name` (the rest are named through the contact).
- `contact.id` is the wid string; a lid contact carries `phoneNumber` (the `@c.us` wid), `pnContactHash`,
  `name`, `pushname`, `shortName`, `isAddressBookContact`, `isUsernameContact`.
- `message-info` is keyed `[msgKey, receiverUserJid]`: `delivery`, `read`, `played` times, `deviceDelivered`
  (per-device list). `reactions` is keyed `[parentMsgKey, senderUserJid]`; `poll-votes` keyed
  `[parentMsgKey, sender]` with `selectedOptionLocalIds`, `senderTimestampMs`, `ack`, `read`.
- `message-association` links an album's pictures to their parent (`associationType: MEDIA_ALBUM`).
- `active-message-ranges` records, per chat, which message range is loaded and what happened to it
  (`action: deleteChat_0`, `remainingMessages`).

## 2. The in-memory models (`window.require('WAWebCollections')`)

The page decrypts records into Backbone-style collections. On this account: `Msg` 1 023 models (not every stored
message is in memory), `Chat` 728 (653 `@lid`, 72 `@g.us`, 2 `@c.us`, 1 broadcast), `Contact` 7 064 (3 825 `@lid`,
3 164 `@c.us`, 72 groups, a bot, a newsletter), `Presence`, `Mute`, `Blocklist` 26, `BusinessProfile` 1 317,
`ProfilePicThumb` 518, `Label` 3, `Status` 29, `WAWebGroupMetadataCollection` 72, `WAWebCallCollection`,
`PollVote`, `Reactions`, `StarredMsg`, `WAWebNewsletterCollection`, …

Almost every person's chat is a `@lid` chat on this build (653 of 655). The phone behind a lid is not on the message:
`senderPn` was on none of 839 stored lid messages (it arrives only with a live stanza). It is on the contact
(`contact.phoneNumber`, set on 3 089 of 3 825 lid contacts) and through `WAWebApiContact.getPhoneNumber(lid)`,
which is what our page method `resolveIds` calls; `lid-pn-mapping` in the database was empty, so the page keeps the
mapping in `lidPnCache` and on the contacts.

### 2.1 Message key

`msg.id` has keys `fromMe`, `remote`, `id`, `$1`, `participant`, `self`: **`_serialized` is gone from the message
key and `$1` holds its value**, while a Wid (`remote`, `author`, `callCreator`, `quotedParticipant`, …) still has
`{server, user, _serialized}`. `protocolMessageKey` on a revoke is a message key of the same shape (with `$1`). Our
page normalises keys (`messageKey`) and Wids (`widKey`) for both forms.

### 2.2 Message types seen (1 023 models) and the fields each carries beyond the common ones

Common to every type: `id`, `from`, `to`, `t`, `type`, `rowId`, `viewMode`, and often `ack`, `invis`,
`forwardsCount`, `lastUpdateFromServerTs`, `serverStoreTimeMicros`, `isMdHistoryMsg` (611: loaded from history
sync, not received live), `messageSecret`, `author` (groups). `ack`: 0 ×513, 1 ×147, 2 ×46, 3 ×96, 4 ×2, absent
×219 (notices and call logs).

| type (subtype) | n | fields of note |
|---|---|---|
| `chat` | 349 | `body`; a business template adds `hsmTag`, `hsmCategory`, `templateId`, `isFromTemplate`, `hydratedButtons[]`, `footer`, `title`; `privacyModeWhenSent` |
| `chat:url` | 17 | a link preview: `title`, `description`, `matchedText`, `richPreviewType`, `thumbnail*`, `canonicalUrl`, `inviteGrp*` for a group invite |
| `image` | 186 | `directPath`, `mediaKey`, `mediaKeyTimestamp`, `filehash` (44-char base64 = SHA-256), `encFilehash`, `mimetype`, `size`, `width`, `height`, `caption`, `body` = the base64 thumbnail, `thumbnail*`, `staticUrl`, `deprecatedMms3Url`; a view-once image has `mimetype` but no `directPath` |
| `video` | 46 | as image plus `duration`, `streamingSidecar`, `isGif` (none here) |
| `ptt` | 1 | as image plus `duration`, `waveform` |
| `document` | 18 | as image plus `filename`, `pageCount`, `caption` |
| `sticker` | 1 | as image plus `stickerPremiumStatus`, `experienceIds` |
| `album` | 1 | the parent of several pictures (`message-association`) |
| `location` | 4 | `lat`, `lng`, `loc` (often empty), `body` (thumbnail, may be empty) |
| `vcard` | 1 | `body` = the vCard text, `vcardFormattedName` (`vcardList[]` on a `multi_vcard`) |
| `poll_creation` | 1 | `pollName`, `pollOptions[{name, localId}]`, `pollSelectableOptionsCount` (1 = single choice), `pollType: POLL`, `pollContentType: TEXT`, `pollVotesSnapshot` |
| `call_log` | 201 | `callOutcome` (`Completed`, `Missed`, `Rejected`, `AcceptedElsewhere`), `callDuration` (s), `isVideoCall`, `callCreator` (wid), `callParticipants[{participant, outcome}]`, `expiredTimestamp`; no `ack` |
| `interactive` | 87, all inbound from businesses | `interactiveType` (`native_flow`, `carousel`), `nativeFlowName` (`quick_reply`, `cta_url`, `cta_copy`, `mixed`), `interactiveHeader {hasMediaAttachment, mediaType}`, `interactivePayload {messageVersion, buttons[{name, buttonParamsJson}], messageParamsJson}`, `title`, `footer`, `caption` (the text), `body` (thumbnail when media), `hsmTag`, `templateId`, `isFromTemplate`, `bizSource`; 63 of 87 carry media (`directPath`, `mimetype`, …) |
| `revoked:sender` | 8 | `protocolMessageKey` (the deleted message's key), `revokeSender`, `revokeTimestamp` |
| `biz_content_placeholder` | 10 | `bizContentPlaceholderType`, `hsmTag` (`AUTHENTICATION`) |
| `newsletter_follower_invite` | 1 | `newsletterFollowerInviteInfo {newsletterId, newsletterName, inviteMessage, pictureThumbnail}` |
| `unknown:phone_only_feature` | 4 | `futureproofBuffer`, `futureproofParams`, `hsmTag`, `templateId` |
| `groups_v4_invite` | 2 | `inviteCode`, `inviteCodeExp`, `inviteGrp`, `inviteGrpName`, `comment` |
| `rich_response` | 1 | `richResponse`, `unifiedResponse` (an AI/bot answer) |
| `notification_template:*`, `e2e_notification:*`, `gp2:*`, `protocol:ephemeral_setting` | 90 | WhatsApp's notices (chat became hosted, encryption, group changes, disappearing mode); no body |

Other fields seen: `quotedStanzaID` + `quotedParticipant` + `quotedMsg` (the whole quoted model) on 10 replies;
`isForwarded` 44 and `forwardingScore` 81; `notifyName` on 1 (a live arrival; stored models lose it);
`latestEditMsgKey` on 1 edit; `ctwaContext` on 1 (§2.3).

### 2.3 `ctwaContext` on the live build

The one message with `ctwaContext` is a business's link preview of a WhatsApp group invite, not an ad:
`{sourceUrl: "https://chat.whatsapp.com/…", title, description, thumbnail (base64), mediaType: 1, thumbnailUrl,
mediaUrl}`. Confirmed names: `sourceUrl`, `title`, `description`, `thumbnail`, `thumbnailUrl`, `mediaType`
(**a number**, not a string), `mediaUrl`. Not present on this sample: `sourceId`, `sourceType`, `conversionSource`,
`ctwaClid`, `sourceApp`, `greetingMessageBody`. The ad rule of the click-to-WhatsApp design (`sourceUrl` or
`sourceId` or `conversionSource` present) would have called this message an ad click. A real ad tap (still owed by
the user) will show which of `sourceId`, `conversionSource` and `ctwaClid` an ad carries; the rule may then need
to require one of those rather than `sourceUrl` alone.

### 2.4 Chat and contact models

`Chat.serialize()`: `id`, `t`, `unreadCount`, `unreadMentionCount`, `unreadDividerOffset`, `lastReceivedKey`,
`lastChatEntryTimestamp`, `msgs` (the loaded window), `accountLid`, `historyChatId`, `lidOriginType`,
`endOfHistoryTransferType`, `hasOpened`, `muteExpiration`, `notSpam`, `draftMessage`, `ephemeral*`,
`disappearingMode*`, `tcToken*`, `capiThreadControl`, `limitSharing`. The displayed name is a getter
(`formattedTitle`) resolved through the contact, not a stored field on most chats.

`Contact.serialize()`: `id`, `name`, `pushname`, `shortName`, `type`, `phoneNumber` (on a lid contact whose phone is
known), `isContactSyncCompleted`, `syncToAddressbook`, `textStatus*`, `disappearingMode*`.

## 3. What this means for our page and backend

- The page script's reading of keys (`messageKey`, `$1`) and Wids (`widKey`) matches the live build. The extension's
  `WWebJS` helper was not visible from the analysis tool's JavaScript context although the device was connected and
  forwarding events, so the models above are WhatsApp's own `serialize()`; `getMessageModel` adds `links`,
  `isEphemeral`, `isStatusV3`, normalised `id.remote` and `id._serialized` on top.
- Fields the converter can rely on are those of §2.2. Two of the design's assumptions change: polls say
  `pollSelectableOptionsCount` (not `allowMultipleAnswers`), and the vCard's display name is `vcardFormattedName`.
  Business interactive messages carry their buttons after all (`interactivePayload.buttons[].buttonParamsJson`
  holds `display_text` and `id`), so the "buttons are lost" limit can be lifted when the converter reads them.
- The phone behind a lid comes from the contact or `getPhoneNumber`, never from the stored message; the one-contact
  design's `resolveIds` path is the right one and `senderPn` only helps on a live arrival.
- A fetch of history (`fetchChatHistory`) reads decrypted models, so it sees everything the live event sees; nothing
  extra is reachable by reading IndexedDB directly, because the content there is encrypted.
