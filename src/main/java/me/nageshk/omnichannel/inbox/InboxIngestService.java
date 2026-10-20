package me.nageshk.omnichannel.inbox;

import tools.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.events.WhatsAppEvent;

/**
 * Persists the chats and messages seen by a WhatsApp Web number (contact, chat, message, chat summary, in that
 * order) and publishes {@link InboxEvents}. A duplicate-key or optimistic-lock failure is retried once.
 */
@Service
public class InboxIngestService {

    private static final Logger log = LoggerFactory.getLogger(InboxIngestService.class);
    private static final int PREVIEW_LENGTH = 120;

    /** What an event changed; {@code chat} and {@code message} are the stored rows after the change. */
    public record Ingested(WaWebChat chat, WaWebMessage message, boolean chatCreated, boolean reopened,
            boolean messageInserted) {
    }

    /** {@code changed} is false for a replay or a stale ack, which publishes nothing. */
    private record Stored(Ingested result, boolean changed, WaWebChatAction reopenedAction) {
    }

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

    /** A {@code message_create} event; empty when it is not a person's chat or has no usable phone or id. */
    public Optional<Ingested> ingestMessage(WhatsAppEvent event) {
        JsonNode p = event.payload();
        if (p == null || !p.isObject()) return Optional.empty();
        String remote = MessagePayloads.remote(p);
        boolean lid = remote.endsWith("@lid");
        if (!lid && !remote.endsWith("@c.us")) return Optional.empty();
        String waMessageId = MessagePayloads.waMessageId(p);
        if (waMessageId.isEmpty()) return Optional.empty();
        Progress progress = new Progress();
        return publish(retrying(() -> storeMessage(event, p, remote, lid, waMessageId, progress)));
    }

    /** A {@code message_ack} event; an ack for a message we never stored is dropped. */
    public Optional<Ingested> ingestAck(WhatsAppEvent event) {
        JsonNode p = event.payload();
        if (p == null || !p.isObject()) return Optional.empty();
        String waMessageId = MessagePayloads.waMessageId(p.path("message"));
        if (waMessageId.isEmpty()) return Optional.empty();
        return publish(retrying(() -> storeAck(event, p, waMessageId)));
    }

    /** What earlier attempts of one ingestion already persisted, so a retry still reports it. */
    private static final class Progress {
        boolean chatCreated;
        boolean reopened;
        boolean folded;
        WaWebChatAction reopenedAction;
    }

    /**
     * The chat summary is saved before the message row, so a failed chat save retries cleanly and a duplicate
     * key on the message insert means the chat already counts it ({@code progress.folded}).
     */
    private Optional<Stored> storeMessage(WhatsAppEvent event, JsonNode p, String remote, boolean lid,
            String waMessageId, Progress progress) {
        boolean fromMe = MessagePayloads.fromMe(p);
        Instant at = MessagePayloads.timestamp(p, event);
        Contact contact = contacts.upsertFromChat(event.workspaceId(), event.customerId(), remote,
                fromMe ? null : MessagePayloads.senderName(p), lid ? MessagePayloads.lidPhone(p) : null, at);
        if (contact == null) return Optional.empty();

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(event.phoneNumberId(), remote).orElse(null);
        if (chat == null) {
            chat = new WaWebChat();
            chat.setCustomerId(event.customerId());
            chat.setWorkspaceId(event.workspaceId());
            chat.setPhoneNumberId(event.phoneNumberId());
            chat.setRemoteId(remote);
            chat.setContactId(contact.getId());
            chat.setStatus(ChatStatus.OPEN);
            chat = chats.save(chat);
            progress.chatCreated = true;
        }

        Instant now = clock.instant();
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(event.phoneNumberId(), waMessageId)
                .orElse(null);
        if (message != null && !progress.folded) {
            // a message we already stored: only its status can move
            boolean statusChanged = applyAck(message, MessagePayloads.ack(p), now);
            if (statusChanged) {
                message = messages.save(message);
                if (isLast(chat, message)) {
                    chat.setLastMessage(withStatus(chat.getLastMessage(), message.getStatus()));
                    chat = chats.save(chat);
                }
            }
            return Optional.of(new Stored(new Ingested(chat, message, progress.chatCreated, false, false),
                    statusChanged || progress.chatCreated, null));
        }

        if (message == null) {
            message = newMessage(event, p, chat, waMessageId, fromMe, at);
            applyAck(message, MessagePayloads.ack(p), now);
        }
        if (!progress.folded) {
            if (foldIntoChat(chat, contact, message, at)) progress.reopened = true;
            chat = chats.save(chat);
            progress.folded = true;
            if (progress.reopened) progress.reopenedAction = recordReopened(chat, now);
        }
        if (message.getId() == null) message = messages.save(message);
        return Optional.of(new Stored(
                new Ingested(chat, message, progress.chatCreated, progress.reopened, true), true,
                progress.reopenedAction));
    }

    /** Updates the chat summary for a new message; returns whether a closed chat was reopened. */
    private static boolean foldIntoChat(WaWebChat chat, Contact contact, WaWebMessage message, Instant at) {
        boolean reopened = false;
        if (chat.getContactId() == null) chat.setContactId(contact.getId());
        foldPreview(chat, message, at);
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

    /** Makes the message the chat's preview unless a newer one already is. */
    private static void foldPreview(WaWebChat chat, WaWebMessage message, Instant at) {
        if (chat.getLastMessageAt() == null || !at.isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(at);
            chat.setLastMessage(new WaWebChat.LastMessage(message.getDirection(), message.getType(),
                    preview(message.getType(), message.getBody(),
                            message.getMedia() == null ? null : message.getMedia().filename()),
                    message.getStatus()));
        }
    }

    /** Folds a message sent from the console into the chat summary: the preview and the last outbound time. */
    static void foldOutbound(WaWebChat chat, WaWebMessage message, Instant at) {
        foldPreview(chat, message, at);
        chat.setLastOutboundAt(later(chat.getLastOutboundAt(), at));
    }

    private Optional<Stored> storeAck(WhatsAppEvent event, JsonNode p, String waMessageId) {
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(event.phoneNumberId(), waMessageId)
                .orElse(null);
        WaWebChat chat = message == null ? null : chats.findById(message.getChatId()).orElse(null);
        if (chat == null) {
            log.debug("dropping ack for unknown message {} of {}", waMessageId, event.phoneNumberId());
            return Optional.empty();
        }
        boolean changed = applyAck(message, MessagePayloads.ack(p), clock.instant());
        if (changed) {
            message = messages.save(message);
            if (isLast(chat, message)) {
                chat.setLastMessage(withStatus(chat.getLastMessage(), message.getStatus()));
                chat = chats.save(chat);
            }
        }
        return Optional.of(new Stored(new Ingested(chat, message, false, false, false), changed, null));
    }

    private WaWebMessage newMessage(WhatsAppEvent event, JsonNode p, WaWebChat chat, String waMessageId,
            boolean fromMe, Instant at) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(event.customerId());
        m.setWorkspaceId(event.workspaceId());
        m.setPhoneNumberId(event.phoneNumberId());
        m.setChatId(chat.getId());
        m.setWaMessageId(waMessageId);
        m.setDirection(fromMe ? MessageDirection.OUT : MessageDirection.IN);
        m.setSource(fromMe ? MessageSource.WHATSAPP_WEB : MessageSource.CONTACT);
        m.setType(MessagePayloads.type(p));
        m.setBody(MessagePayloads.body(p));
        m.setMedia(MessagePayloads.media(p));
        m.setLocation(MessagePayloads.location(p));
        m.setTimestamp(at);
        m.setStatus(MessageStatus.PENDING);
        m.setAck(MessagePayloads.ack(p));
        m.setStatusTimes(new WaWebMessage.StatusTimes());
        return m;
    }

    /**
     * Moves the status up (never down; FAILED is reachable from any state and final) and stamps the time the
     * status was first reached. Returns whether anything changed.
     */
    static boolean applyAck(WaWebMessage message, int ack, Instant now) {
        MessageStatus next = MessageStatus.ofAck(ack);
        MessageStatus current = message.getStatus();
        if (next == current || current == MessageStatus.FAILED) return false;
        if (next != MessageStatus.FAILED && !next.isAtLeast(current)) return false;
        message.setStatus(next);
        message.setAck(ack);
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
        if (i.chatCreated() || i.reopened()) chat = chats.findById(chat.getId()).orElse(chat);
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, i.message(), i.messageInserted()));
        publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        return Optional.of(new Ingested(chat, i.message(), i.chatCreated(), i.reopened(), i.messageInserted()));
    }

    static <T> T retrying(Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException | OptimisticLockingFailureException e) {
            return action.get();
        }
    }

    /** A label for body-less or non-text types, else the first 120 characters of the body (the caption). */
    static String preview(String type, String body, String filename) {
        if (!labelFirst(type) && body != null && !body.isBlank()) {
            if (body.codePointCount(0, body.length()) <= PREVIEW_LENGTH) return body;
            return body.substring(0, body.offsetByCodePoints(0, PREVIEW_LENGTH));
        }
        return switch (type == null ? "" : type) {
            case "image" -> "📷 Photo";
            case "video" -> "🎥 Video";
            case "audio", "ptt" -> "🎤 Voice message";
            case "document" -> "📄 " + (filename == null || filename.isBlank() ? "Document" : filename);
            case "sticker" -> "Sticker";
            case "location" -> "📍 Location";
            case "vcard", "multi_vcard" -> "👤 Contact card";
            case "poll_creation" -> "📊 Poll";
            case "revoked" -> "This message was deleted";
            default -> "Unsupported message";
        };
    }

    /** For these types the body is a thumbnail or raw card text, never a caption. */
    private static boolean labelFirst(String type) {
        return switch (type == null ? "" : type) {
            case "location", "vcard", "multi_vcard", "sticker", "poll_creation", "revoked" -> true;
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
