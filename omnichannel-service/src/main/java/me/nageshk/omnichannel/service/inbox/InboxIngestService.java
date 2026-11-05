package me.nageshk.omnichannel.service.inbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.PollVote;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.events.IngestionFailedException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.channel.whatsapp.WaWebWebhookConverter;
import me.nageshk.omnichannel.service.contact.ContactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Persists the chats and messages seen by a WhatsApp Web number (contact, chat row, then the chat summary, then the
 * message row, in that order) and publishes {@link InboxEvents}. A duplicate-key or optimistic-lock failure is
 * retried once; a database failure that still prevents persisting throws {@link IngestionFailedException}.
 */
@Service
public class InboxIngestService {

    private static final Logger log = LoggerFactory.getLogger(InboxIngestService.class);

    /** What an event changed; {@code chat} and {@code message} are the stored rows after the change. */
    public record Ingested(WaWebChat chat, WaWebMessage message, boolean chatCreated, boolean reopened,
            boolean messageInserted) {
    }

    /**
     * {@code changed} is false for a replay or a stale ack, which publishes nothing; {@code campaignFirstReply} is
     * whether the message is the contact's first answer since our last message to an unassigned campaign chat.
     */
    private record Stored(Ingested result, boolean changed, WaWebChatAction reopenedAction,
            boolean campaignFirstReply) {
    }

    /** The number a history import stores messages for. */
    /**
     * {@code historyChatId}, the history import item doing the import, is noted on a chat this creates, so the import
     * can tell which chat it created even when a crash lost its own note.
     */
    public record ImportContext(String customerId, String workspaceId, String phoneNumberId, String chatName,
            String historyChatId) {

        public ImportContext(String customerId, String workspaceId, String phoneNumberId) {
            this(customerId, workspaceId, phoneNumberId, null, null);
        }
    }

    public enum ImportOutcome { INSERTED, UPDATED, UNCHANGED, SKIPPED }

    /**
     * {@code chat} and {@code message} are the stored rows; both null when {@code SKIPPED}. {@code chatCreated} is
     * true when this message created the chat.
     */
    public record ImportResult(ImportOutcome outcome, WaWebChat chat, WaWebMessage message, boolean chatCreated) {
        public ImportResult(ImportOutcome outcome, WaWebChat chat, WaWebMessage message) {
            this(outcome, chat, message, false);
        }
    }

    private static final ImportResult SKIPPED = new ImportResult(ImportOutcome.SKIPPED, null, null);

    private final ContactService contacts;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final WaWebChatActionRepository actions;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public InboxIngestService(ContactService contacts, WaWebChatRepository chats, WaWebMessageRepository messages,
            WaWebChatActionRepository actions, ApplicationEventPublisher publisher, Clock clock) {
        this.contacts = contacts;
        this.chats = chats;
        this.messages = messages;
        this.actions = actions;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * A person's vote on a poll ({@code fromMe} false) or ours, folded into the poll row; empty when the poll is not
     * stored or the vote names no voter. {@code chatId} is the poll's chat, for the log.
     */
    public Optional<Ingested> ingestVote(ChannelContext ctx, String chatId, PollVote pollVote, String voter,
            boolean fromMe, Instant at) {
        if (pollVote == null || pollVote.messageId() == null || voter == null || voter.isBlank()) {
            return Optional.empty();
        }
        String pollId = pollVote.messageId();
        WaWebMessage.PollVote vote = new WaWebMessage.PollVote(voter, fromMe,
                pollVote.selectedOptions() == null ? List.of() : List.copyOf(pollVote.selectedOptions()), at);
        try {
            return retrying(() -> storeVote(ctx, chatId, pollId, vote));
        } catch (DataAccessException e) {
            throw new IngestionFailedException("poll_vote", pollId, e);
        }
    }

    /**
     * A person's message, with the {@code contacts[]} entry that names them (null when none does); empty when it is
     * not a person's chat or has no usable phone or id. It is stored {@code PENDING} with ack 0: an inbound message
     * has no delivery state.
     */
    public Optional<Ingested> ingestInbound(ChannelContext ctx,
            me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact contact, Message message) {
        if (message == null) return Optional.empty();
        String remote = MessageFields.chatId(message);
        if (!MessageFields.isPersonChat(remote) || message.id() == null || message.id().isEmpty()) {
            return Optional.empty();
        }
        Instant at = MessageFields.timestampOf(message.timestamp(), clock.instant());
        Incoming in = new Incoming(remote, message.id(), false, at, MessageFields.senderNameOf(contact),
                MessageFields.lidPhoneOf(message), MessageFields.of(message), MessageFields.adClickOf(message, at),
                null);
        return ingest(ctx, in, "messages");
    }

    /**
     * Our own message, with the status that came with it in the same webhook entry (null when none did); empty when it
     * is not a person's chat or has no id. A row the console or a campaign already stored only takes the status.
     */
    public Optional<Ingested> ingestEcho(ChannelContext ctx, MessageEcho echo, Status status) {
        if (echo == null) return Optional.empty();
        String remote = MessageFields.chatId(echo);
        if (!MessageFields.isPersonChat(remote) || echo.id() == null || echo.id().isEmpty()) return Optional.empty();
        Instant at = MessageFields.timestampOf(echo.timestamp(), clock.instant());
        Incoming in = new Incoming(remote, echo.id(), true, at, null, null, MessageFields.of(echo), null,
                status == null ? null : MessageFields.ackOf(status));
        return ingest(ctx, in, "smb_message_echoes");
    }

    /** The delivery state of our message; a status for a message we never stored is dropped. */
    public Optional<Ingested> ingestStatus(ChannelContext ctx, Status status) {
        if (status == null || status.id() == null || status.id().isEmpty()) return Optional.empty();
        try {
            return publish(retrying(() -> storeStatus(ctx, status)));
        } catch (DataAccessException e) {
            throw new IngestionFailedException("statuses", status.id(), e);
        }
    }

    private Optional<Ingested> ingest(ChannelContext ctx, Incoming in, String field) {
        Progress progress = new Progress();
        try {
            return publish(retrying(() -> storeMessage(ctx, in, progress)));
        } catch (DataAccessException e) {
            throw new IngestionFailedException(field, in.waMessageId(), e);
        }
    }

    /**
     * A person's message read from the phone's history, with the {@code contacts[]} entry that names them (null when
     * none does). Unlike {@link #ingestInbound} it publishes no event, never reopens a chat or counts it unread,
     * creates chats {@code CLOSED}, records no status times and names only a nameless contact; SKIPPED when it is not
     * a person's chat or has no id. Stored {@code PENDING}: an inbound message has no delivery state.
     */
    public ImportResult importMessage(ImportContext ctx,
            me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact contact, Message message) {
        if (message == null) return SKIPPED;
        Instant at = MessageFields.timestampOf(message.timestamp(), clock.instant());
        return imported(ctx, new Incoming(MessageFields.chatId(message), message.id(), false, at,
                MessageFields.senderNameOf(contact), MessageFields.lidPhoneOf(message), MessageFields.of(message),
                MessageFields.adClickOf(message, at), 0));
    }

    /**
     * Our own message read from the phone's history, with the ack WhatsApp Web had for it ({@code wa_web.ack}); the
     * same rules as {@link #importMessage}.
     */
    public ImportResult importEcho(ImportContext ctx, MessageEcho echo) {
        if (echo == null) return SKIPPED;
        Integer ack = echo.waWeb() == null ? null : echo.waWeb().ack();
        return imported(ctx, new Incoming(MessageFields.chatId(echo), echo.id(), true,
                MessageFields.timestampOf(echo.timestamp(), clock.instant()), null, null, MessageFields.of(echo), null,
                ack == null ? 0 : ack));
    }

    private ImportResult imported(ImportContext ctx, Incoming in) {
        if (!MessageFields.isPersonChat(in.remote()) || in.waMessageId() == null || in.waMessageId().isEmpty()) {
            return SKIPPED;
        }
        try {
            return retrying(() -> storeImported(ctx, in));
        } catch (DataAccessException e) {
            throw new IngestionFailedException("history_import", in.waMessageId(), e);
        }
    }

    /**
     * Like {@link #storeMessage}, the chat summary is saved before the message row: a retry after a failed chat save
     * folds again, and a duplicate key on the insert re-reads the message as existing.
     */
    private ImportResult storeImported(ImportContext ctx, Incoming in) {
        String remote = in.remote();
        Instant at = in.at();
        // the import walks backwards, so a push name it reads is older than the one the contact has; the name WhatsApp
        // lists the chat under is current, serves a chat we only wrote to, and goes on below as a name of record
        String senderName = ctx.chatName() != null ? null : in.senderName();
        if (senderName != null) {
            Contact known = contacts.findForChat(ctx.workspaceId(), remote);
            if (known != null && known.getName() != null) senderName = null;
        }
        // on our own message the phone behind a lid is the business's number, so an echo carries none
        Contact contact = contacts.upsertFromChat(ctx.workspaceId(), ctx.customerId(), remote, senderName,
                in.lidPhone(), at);
        if (contact == null) return SKIPPED;
        // not over a name cleared by hand; of record, or the contact's next push name would replace it
        if (ctx.chatName() != null && !contact.isNameEdited()) {
            contacts.nameIfBlank(ctx.workspaceId(), contact.getId(), ctx.chatName());
        }

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(ctx.phoneNumberId(), remote).orElse(null);
        boolean created = chat == null;
        if (chat == null) {
            chat = new WaWebChat();
            chat.setCustomerId(ctx.customerId());
            chat.setWorkspaceId(ctx.workspaceId());
            chat.setPhoneNumberId(ctx.phoneNumberId());
            chat.setRemoteId(remote);
            chat.setContactId(contact.getId());
            chat.setStatus(ChatStatus.CLOSED);
            chat.setUnreadCount(0);
            chat.setCreatedByHistoryChatId(ctx.historyChatId());
            chat = chats.save(chat);
        }

        int ack = in.ack();
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(ctx.phoneNumberId(), in.waMessageId())
                .orElse(null);
        if (message != null) {
            boolean acked = applyAck(message, ack, null);
            if (!fillIn(message, in.content()) && !acked) {
                return new ImportResult(ImportOutcome.UNCHANGED, chat, message);
            }
            message = messages.save(message);
            if (isLast(chat, message)) {
                chat.setLastMessage(new WaWebChat.LastMessage(message.getDirection(), message.getType(),
                        preview(message), message.getStatus()));
                chat = chats.save(chat);
            }
            return new ImportResult(ImportOutcome.UPDATED, chat, message);
        }

        message = newMessage(ctx.customerId(), ctx.workspaceId(), ctx.phoneNumberId(), chat, in);
        message.setAck(ack);
        message.setStatus(MessageStatus.ofAck(ack));
        message.setStatusTimes(null);
        fillIn(message, in.content());
        foldPreview(chat, message, at);
        keepFirstAd(chat, message);
        if (message.getDirection() == MessageDirection.IN) chat.setLastInboundAt(later(chat.getLastInboundAt(), at));
        else chat.setLastOutboundAt(later(chat.getLastOutboundAt(), at));
        chat = chats.save(chat);
        message = messages.insert(message);
        return new ImportResult(ImportOutcome.INSERTED, chat, message, created);
    }

    /**
     * Takes what only a fetch tells: the votes on a poll, a call stored before calls were read, and what was stored
     * before it was read: a business tag, and the channel name of an invite. Never replaces what is stored.
     */
    private static boolean fillIn(WaWebMessage message, MessageFields.Content c) {
        WaWebMessage.Call call = MessageFields.callOf(c);
        boolean changed = call != null && !call.equals(message.getCall());
        if (changed) message.setCall(call);
        String tag = MessageFields.businessTagOf(c);
        if (message.getBusinessTag() == null && tag != null) {
            message.setBusinessTag(tag);
            changed = true;
        }
        String body = "newsletter_follower_invite".equals(message.getType()) ? MessageFields.bodyOf(c) : null;
        if (message.getBody() == null && body != null) {
            message.setBody(body);
            changed = true;
        }
        List<WaWebMessage.PollVote> votes = message.getPollVotes();
        for (WaWebMessage.PollVote vote : MessageFields.pollVotesOf(c)) votes = PollVotes.merged(votes, vote);
        if (votes == message.getPollVotes()) return changed;
        message.setPollVotes(votes);
        return true;
    }

    /**
     * A webhook message or echo as the store needs it: {@code remote} is the chat id, {@code ack} the status that came
     * with our own message (null when none did; always null for a person's message). An import's {@code ack} is never
     * null: 0 for a person's message, else WhatsApp Web's ack of ours.
     */
    private record Incoming(String remote, String waMessageId, boolean fromMe, Instant at, String senderName,
            String lidPhone, MessageFields.Content content, AdClick adClick, Integer ack) {
    }

    /** What earlier attempts of one ingestion already persisted, so a retry still reports it. */
    private static final class Progress {
        boolean chatCreated;
        boolean reopened;
        boolean folded;
        boolean campaignFirstReply;
        WaWebChatAction reopenedAction;
    }

    /**
     * The chat summary is saved before the message row, so a failed chat save retries cleanly and a duplicate
     * key on the message insert means the chat already counts it ({@code progress.folded}).
     */
    private Optional<Stored> storeMessage(ChannelContext ctx, Incoming in, Progress progress) {
        String phoneNumberId = ctx.channelAccountId();
        String remote = in.remote();
        Instant at = in.at();
        // a name and the phone behind a lid only on a person's message: on ours they are the business's
        Contact contact = contacts.upsertFromChat(ctx.workspaceId(), ctx.customerId(), remote, in.senderName(),
                in.lidPhone(), at);
        if (contact == null) return Optional.empty();

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remote).orElse(null);
        if (chat == null) {
            chat = new WaWebChat();
            chat.setCustomerId(ctx.customerId());
            chat.setWorkspaceId(ctx.workspaceId());
            chat.setPhoneNumberId(phoneNumberId);
            chat.setRemoteId(remote);
            chat.setContactId(contact.getId());
            chat.setStatus(ChatStatus.OPEN);
            chat = chats.save(chat);
            progress.chatCreated = true;
        }

        Instant now = clock.instant();
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(phoneNumberId, in.waMessageId())
                .orElse(null);
        if (message != null && !progress.folded) {
            // a message we already stored: only the status that came with our own can move it
            boolean statusChanged = in.ack() != null && applyAck(message, in.ack(), now);
            if (statusChanged) {
                message = messages.save(message);
                if (isLast(chat, message)) {
                    chat.setLastMessage(withStatus(chat.getLastMessage(), message.getStatus()));
                    chat = chats.save(chat);
                }
            }
            return Optional.of(new Stored(new Ingested(chat, message, progress.chatCreated, false, false),
                    statusChanged || progress.chatCreated, null, false));
        }

        if (message == null) {
            message = newMessage(ctx, chat, in);
            if (in.ack() != null) applyAck(message, in.ack(), now);
        }
        if (!progress.folded) {
            // a campaign chat waits unassigned for the contact's first reply since our last message; a reopen is
            // routed as such instead
            boolean awaitingReply = chat.getCampaignId() != null && chat.getAssignedUserId() == null
                    && chat.getAssignedTeamId() == null && (chat.getLastInboundAt() == null
                            || chat.getLastOutboundAt() != null
                                    && chat.getLastInboundAt().isBefore(chat.getLastOutboundAt()));
            if (foldIntoChat(chat, contact, message, at)) progress.reopened = true;
            progress.campaignFirstReply = awaitingReply && message.getDirection() == MessageDirection.IN
                    && !progress.reopened;
            chat = chats.save(chat);
            progress.folded = true;
            if (progress.reopened) progress.reopenedAction = recordReopened(chat, now);
        }
        if (message.getId() == null) message = messages.save(message);
        return Optional.of(new Stored(
                new Ingested(chat, message, progress.chatCreated, progress.reopened, true), true,
                progress.reopenedAction, progress.campaignFirstReply));
    }

    /** Updates the chat summary for a new message; returns whether a closed chat was reopened. */
    private static boolean foldIntoChat(WaWebChat chat, Contact contact, WaWebMessage message, Instant at) {
        boolean reopened = false;
        chat.setContactId(contact.getId());
        foldPreview(chat, message, at);
        keepFirstAd(chat, message);
        if (message.getDirection() == MessageDirection.IN) {
            chat.setUnreadCount(chat.getUnreadCount() + 1);
            chat.setLastInboundAt(later(chat.getLastInboundAt(), at));
            if (chat.getStatus() == ChatStatus.CLOSED) {
                chat.setStatus(ChatStatus.OPEN);
                reopened = true;
            }
        } else {
            chat.setLastOutboundAt(later(chat.getLastOutboundAt(), at));
        }
        return reopened;
    }

    /** Gives the chat the ad of an inbound message when it has none or this one is earlier; the earliest ad stays. */
    private static void keepFirstAd(WaWebChat chat, WaWebMessage message) {
        AdClick ad = message.getAdClick();
        if (ad == null || message.getDirection() != MessageDirection.IN) return;
        AdClick kept = chat.getAdClick();
        if (kept == null || kept.at() == null || ad.at().isBefore(kept.at())) chat.setAdClick(ad);
    }

    /** Makes the message the chat's preview unless a newer one already is. */
    private static void foldPreview(WaWebChat chat, WaWebMessage message, Instant at) {
        if (chat.getLastMessageAt() == null || !at.isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(at);
            chat.setLastMessage(new WaWebChat.LastMessage(message.getDirection(), message.getType(),
                    preview(message), message.getStatus()));
        }
    }

    /** Folds a message sent through the inbox into the chat summary: the preview and the last outbound time. */
    static void foldOutbound(WaWebChat chat, WaWebMessage message, Instant at) {
        foldPreview(chat, message, at);
        chat.setLastOutboundAt(later(chat.getLastOutboundAt(), at));
    }

    private Optional<Stored> storeStatus(ChannelContext ctx, Status status) {
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(ctx.channelAccountId(), status.id())
                .orElse(null);
        WaWebChat chat = message == null ? null : chats.findById(message.getChatId()).orElse(null);
        if (chat == null) {
            log.debug("dropping status for unknown message {} of {}", status.id(), ctx.channelAccountId());
            return Optional.empty();
        }
        boolean changed = applyAck(message, MessageFields.ackOf(status), clock.instant());
        if (changed) {
            message = messages.save(message);
            if (isLast(chat, message)) {
                chat.setLastMessage(withStatus(chat.getLastMessage(), message.getStatus()));
                chat = chats.save(chat);
            }
        }
        return Optional.of(new Stored(new Ingested(chat, message, false, false, false), changed, null, false));
    }

    private Optional<Ingested> storeVote(ChannelContext ctx, String chatId, String pollId,
            WaWebMessage.PollVote vote) {
        WaWebMessage poll = messages.findByPhoneNumberIdAndWaMessageId(ctx.channelAccountId(), pollId).orElse(null);
        WaWebChat chat = poll == null ? null : chats.findById(poll.getChatId()).orElse(null);
        if (chat == null) {
            log.debug("dropping vote on unknown poll {} in {} of {}", pollId, chatId, ctx.channelAccountId());
            return Optional.empty();
        }
        List<WaWebMessage.PollVote> merged = PollVotes.merged(poll.getPollVotes(), vote);
        if (merged != poll.getPollVotes()) {
            poll.setPollVotes(merged);
            poll = messages.save(poll);
            // the message only, like the media cache: a vote moves neither the chat's preview nor its unread count
            publisher.publishEvent(new InboxEvents.MessageUpserted(chat, poll, false));
        }
        return Optional.of(new Ingested(chat, poll, false, false, false));
    }

    /** A row read from a webhook message or echo; ack 0 until a status moves it. */
    private static WaWebMessage newMessage(ChannelContext ctx, WaWebChat chat, Incoming in) {
        return newMessage(ctx.customerId(), ctx.workspaceId(), ctx.channelAccountId(), chat, in);
    }

    /** A row of a live event or a history import, {@code PENDING} with ack 0. */
    private static WaWebMessage newMessage(String customerId, String workspaceId, String phoneNumberId,
            WaWebChat chat, Incoming in) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(customerId);
        m.setWorkspaceId(workspaceId);
        m.setPhoneNumberId(phoneNumberId);
        m.setChatId(chat.getId());
        m.setWaMessageId(in.waMessageId());
        m.setDirection(in.fromMe() ? MessageDirection.OUT : MessageDirection.IN);
        m.setSource(in.fromMe() ? MessageSource.WHATSAPP_WEB : MessageSource.CONTACT);
        m.setTimestamp(in.at());
        m.setStatus(MessageStatus.PENDING);
        m.setStatusTimes(new WaWebMessage.StatusTimes());
        MessageFields.Content c = in.content();
        m.setType(MessageFields.storedType(c));
        m.setBody(MessageFields.bodyOf(c));
        m.setMedia(MessageFields.mediaOf(c));
        m.setLocation(MessageFields.locationOf(c));
        m.setPollOptions(MessageFields.pollOptionsOf(c));
        m.setCall(MessageFields.callOf(c));
        m.setBusinessTag(MessageFields.businessTagOf(c));
        m.setAdClick(in.fromMe() ? null : in.adClick());
        m.setAck(0);
        return m;
    }

    /**
     * Moves the status up (never down; FAILED is reachable from any state and final) and stamps the time the
     * status was first reached, unless {@code now} is null (a history import, which leaves the times as they are).
     * Returns whether anything changed.
     */
    static boolean applyAck(WaWebMessage message, int ack, Instant now) {
        MessageStatus next = MessageStatus.ofAck(ack);
        MessageStatus current = message.getStatus();
        if (next == current || current == MessageStatus.FAILED) return false;
        if (next != MessageStatus.FAILED && !next.isAtLeast(current)) return false;
        message.setStatus(next);
        message.setAck(ack);
        if (now == null) return true;
        if (message.getStatusTimes() == null) message.setStatusTimes(new WaWebMessage.StatusTimes());
        WaWebMessage.StatusTimes times = message.getStatusTimes();
        switch (next) {
            case SENT -> times.setSentAt(first(times.getSentAt(), now));
            case DELIVERED -> times.setDeliveredAt(first(times.getDeliveredAt(), now));
            case READ -> times.setReadAt(first(times.getReadAt(), now));
            case PLAYED -> times.setPlayedAt(first(times.getPlayedAt(), now));
            case FAILED -> times.setFailedAt(first(times.getFailedAt(), now));
            case PENDING -> { }
        }
        return true;
    }

    private WaWebChatAction recordReopened(WaWebChat chat, Instant now) {
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(ActionType.REOPENED);
        a.setAt(now);
        return actions.save(a);
    }

    /** Routing may change the chat while handling the first events, so the streams get a fresh copy. */
    private Optional<Ingested> publish(Optional<Stored> stored) {
        if (stored.isEmpty()) return Optional.empty();
        Ingested i = stored.get().result();
        if (!stored.get().changed()) return Optional.of(i);
        WaWebChat chat = i.chat();
        if (i.chatCreated()) publisher.publishEvent(new InboxEvents.ChatCreated(chat, i.message().getDirection()));
        if (i.reopened()) {
            if (stored.get().reopenedAction() != null)
                publisher.publishEvent(new InboxEvents.ActionRecorded(chat, stored.get().reopenedAction()));
            publisher.publishEvent(new InboxEvents.ChatReopened(chat));
        }
        if (stored.get().campaignFirstReply()) publisher.publishEvent(new InboxEvents.CampaignChatFirstReply(chat));
        if (i.chatCreated() || i.reopened() || stored.get().campaignFirstReply()) {
            chat = chats.findById(chat.getId()).orElse(chat);
        }
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, i.message(), i.messageInserted()));
        publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        return Optional.of(new Ingested(chat, i.message(), i.chatCreated(), i.reopened(), i.messageInserted()));
    }

    /** Runs {@code action}, and once more when it lost a race on a unique index or a version. */
    public static <T> T retrying(Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException | OptimisticLockingFailureException e) {
            return action.get();
        }
    }

    /** The preview of a stored message: a verification code only the phone has is told apart by its tag. */
    static String preview(WaWebMessage m) {
        if ("biz_content_placeholder".equals(m.getType()) && "AUTHENTICATION".equals(m.getBusinessTag())) {
            return "🔒 Verification code";
        }
        return preview(m.getType(), m.getBody(), m.getMedia() == null ? null : m.getMedia().filename());
    }

    /** A label for body-less or non-text types, else the whole body (the caption); the console cuts it to fit. */
    static String preview(String type, String body, String filename) {
        if (WaWebWebhookConverter.isNoticeType(type)) return "WhatsApp notice";
        String text = MessageBodies.readable(type, body);
        if (!labelFirst(type) && text != null && !text.isBlank()) return text;
        return switch (type == null ? "" : type) {
            case "image" -> "📷 Photo";
            case "video" -> "🎥 Video";
            case "audio", "ptt" -> "🎤 Voice message";
            case "document" -> "📄 " + (filename == null || filename.isBlank() ? "Document" : filename);
            case "sticker" -> "Sticker";
            case "location" -> "📍 Location";
            case "vcard", "multi_vcard" -> ContactCards.preview(text);
            case "poll_creation" -> "📊 Poll";
            case "call_log" -> "📞 Call";
            case "revoked" -> "This message was deleted";
            // WhatsApp Web itself cannot show these, so neither can we; the phone can
            case "biz_content_placeholder" -> "💼 Business message";
            case "unknown" -> "Message to view on the phone";
            case "newsletter_follower_invite" ->
                    text == null || text.isBlank() ? "📢 Channel invite" : "📢 Channel invite: " + text;
            default -> "Unsupported message";
        };
    }

    /** For these types the body is a thumbnail or raw card text, never a caption. */
    private static boolean labelFirst(String type) {
        return switch (type == null ? "" : type) {
            case "location", "vcard", "multi_vcard", "sticker", "poll_creation", "revoked",
                    "newsletter_follower_invite" -> true;
            default -> false;
        };
    }

    private static boolean isLast(WaWebChat chat, WaWebMessage message) {
        return chat.getLastMessage() != null
                && chat.getLastMessage().direction() == message.getDirection()
                && Objects.equals(chat.getLastMessageAt(), message.getTimestamp());
    }

    private static WaWebChat.LastMessage withStatus(WaWebChat.LastMessage last, MessageStatus status) {
        return new WaWebChat.LastMessage(last.direction(), last.type(), last.text(), status);
    }

    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static Instant first(Instant existing, Instant now) {
        return existing != null ? existing : now;
    }
}
