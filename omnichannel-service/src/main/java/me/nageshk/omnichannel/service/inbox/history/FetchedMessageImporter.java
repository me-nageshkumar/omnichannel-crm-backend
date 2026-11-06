package me.nageshk.omnichannel.service.inbox.history;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WaWeb;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.channel.whatsapp.WaIds;
import me.nageshk.omnichannel.service.channel.whatsapp.WaWebWebhookConverter;
import me.nageshk.omnichannel.service.channel.whatsapp.WhatsAppWebhookProcessor;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportOutcome;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportResult;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.Receipt;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Imports one message model of a fetched history page: the converter turns it into a person's message or our echo,
 * as it does for a live event, and the import stores that. Each converted message is first kept in the webhook event
 * store as a one-change event marked {@code wa_web.imported}, with its raw model; its key makes a refetch store
 * nothing. A model the converter drops (a notice, a chat of no person, no id) or cannot read is SKIPPED.
 *
 * <p>After a page, {@link #importReceipts} gives our messages the fetch found delivered or later their receipt times:
 * each state from delivered up to the row's status becomes its own {@code status:} event, stored and processed in
 * that order as a live status is.
 */
@Component
public class FetchedMessageImporter {

    private static final Logger log = LoggerFactory.getLogger(FetchedMessageImporter.class);
    private static final ImportResult SKIPPED = new ImportResult(ImportOutcome.SKIPPED, null, null);
    /** The most ids the page's {@code messageInfo} takes in one call. */
    static final int MAX_RECEIPT_IDS = 100;
    /** The states a receipt can tell, in the order they are reached; never {@code sent}. */
    private static final List<MessageStatus> RECEIPT_STATES = List.of(MessageStatus.DELIVERED, MessageStatus.READ,
            MessageStatus.PLAYED);

    private final WaWebWebhookConverter converter;
    private final InboxIngestService ingest;
    private final InboxDevices devices;
    private final ChannelWebhookEventStore store;
    private final Clock clock;
    private final HistoryDeviceClient client;
    private final WhatsAppWebhookProcessor processor;
    private final WaWebChatRepository chats;
    private final int receiptsBatch;

    public FetchedMessageImporter(WaWebWebhookConverter converter, InboxIngestService ingest, InboxDevices devices,
            ChannelWebhookEventStore store, Clock clock, HistoryDeviceClient client,
            WhatsAppWebhookProcessor processor, WaWebChatRepository chats, OmnichannelProperties props) {
        this.converter = converter;
        this.ingest = ingest;
        this.devices = devices;
        this.store = store;
        this.clock = clock;
        this.client = client;
        this.processor = processor;
        this.chats = chats;
        this.receiptsBatch = Math.clamp(props.history().receiptsBatch(), 1, MAX_RECEIPT_IDS);
    }

    /** @see InboxIngestService#importMessage */
    public ImportResult importMessage(ImportContext ctx, JsonNode raw) {
        if (raw == null || !raw.isObject()) return SKIPPED;
        ChannelContext channel = channelOf(ctx);
        Instant now = clock.instant();
        Optional<Message> inbound;
        Optional<MessageEcho> echo;
        try {
            // each is empty for the other direction
            inbound = converter.message(channel, raw, now);
            echo = inbound.isPresent() ? Optional.empty() : converter.echo(channel, raw, now, null);
        } catch (RuntimeException e) {
            log.warn("could not convert fetched message {} of {}: {}", WaIds.messageId(raw.path("id")),
                    ctx.phoneNumberId(), e.toString());
            return SKIPPED;
        }
        if (inbound.isPresent()) {
            Message m = inbound.get();
            Contact contact = converter.contactOf(m, raw);
            keep(channel, new Change(WaWebWebhookConverter.CHANGE_FIELD, Value.of(channel)
                    .withContacts(List.of(contact)).withMessages(List.of(m.withWaWeb(imported(m.waWeb()))))), raw, now);
            return ingest.importMessage(ctx, contact, m);
        }
        if (echo.isEmpty()) return SKIPPED;
        MessageEcho e = echo.get();
        keep(channel, new Change(WaWebWebhookConverter.ECHO_FIELD,
                Value.of(channel).withMessageEchoes(List.of(e.withWaWeb(imported(e.waWeb()))))), raw, now);
        return ingest.importEcho(ctx, e);
    }

    /**
     * The receipts of {@code wanted}, the stored rows of our messages a page found delivered or later
     * ({@link ImportResult#receiptsWanted}), asked of the page {@code omnichannel.history.receipts-batch} ids at a
     * time. Each state from delivered up to the row's status becomes a status event, timed by WhatsApp's receipt, or by
     * {@code fetchedAt} with {@code wa_web.estimated} when WhatsApp has none for it; a message's events are stored, the
     * first with the page's receipt as its raw, then processed in order. A call that fails (a timeout, the device
     * gone) is logged and its batch skipped: the rows keep their ticks, and the next fetch asks again.
     */
    public void importReceipts(ImportContext ctx, DeviceSession device, List<WaWebMessage> wanted, Instant fetchedAt) {
        if (wanted == null || wanted.isEmpty()) return;
        ChannelContext channel = channelOf(ctx);
        Map<String, String> remoteIds = new HashMap<>();
        for (int from = 0; from < wanted.size(); from += receiptsBatch) {
            List<WaWebMessage> batch = wanted.subList(from, Math.min(wanted.size(), from + receiptsBatch));
            List<String> ids = batch.stream().map(WaWebMessage::getWaMessageId).toList();
            List<Receipt> receipts;
            try {
                receipts = client.messageInfo(device, ids).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | RuntimeException e) {
                log.warn("could not read the receipts of {} fetched messages of {}: {}", ids.size(),
                        ctx.phoneNumberId(), (e instanceof ExecutionException && e.getCause() != null
                                ? e.getCause() : e).toString());
                continue;
            }
            Map<String, Receipt> byId = new HashMap<>();
            for (Receipt r : receipts) byId.putIfAbsent(r.id(), r);
            for (WaWebMessage m : batch) {
                String remote = remoteIds.computeIfAbsent(m.getChatId(),
                        id -> chats.findById(id).map(WaWebChat::getRemoteId).orElse(null));
                if (remote == null) continue;
                Receipt r = byId.getOrDefault(m.getWaMessageId(), new Receipt(m.getWaMessageId(), null, null, null));
                receiptsOf(channel, m, remote, r, fetchedAt);
            }
        }
    }

    /** One message's status events, delivered first, stored and then processed as one unit. */
    private void receiptsOf(ChannelContext channel, WaWebMessage m, String chatId, Receipt r, Instant fetchedAt) {
        List<WebhookEvent> events = new ArrayList<>();
        for (MessageStatus state : RECEIPT_STATES) {
            if (!m.getStatus().isAtLeast(state)) break;
            Instant at = switch (state) {
                case DELIVERED -> r.delivered();
                case READ -> r.read();
                default -> r.played();
            };
            Status status = receiptStatus(m.getWaMessageId(), state, at, chatId, fetchedAt);
            WebhookEvent event = WebhookEvent.of(channel, List.of(new Change(WaWebWebhookConverter.CHANGE_FIELD,
                    Value.of(channel).withStatuses(List.of(status)))));
            store.save(channel, event, events.isEmpty() ? rawOf(r) : null, null, fetchedAt, events.size(), true);
            events.add(event);
        }
        try {
            processor.process(channel, events);
        } catch (RuntimeException e) {
            log.warn("could not apply the receipts of fetched message {} of {}: {}", m.getWaMessageId(),
                    channel.channelAccountId(), e.toString());
        }
    }

    /**
     * The status of one receipt state, as the converter builds a live one; {@code at} null (no receipt) uses the fetch
     * time and says {@code estimated}. {@code captured_at} is the fetch time.
     */
    static Status receiptStatus(String messageId, MessageStatus state, Instant at, String chatId, Instant fetchedAt) {
        int ack = switch (state) {
            case DELIVERED -> 2;
            case READ -> 3;
            case PLAYED -> 4;
            default -> throw new IllegalArgumentException("no receipt for " + state);
        };
        boolean estimated = at == null;
        Status s = WaWebWebhookConverter.statusOf(messageId, ack, estimated ? fetchedAt : at, chatId,
                WaWebWebhookConverter.recipientDigits(chatId));
        WaWeb waWeb = new WaWeb(chatId, ack, null, null, null, null, null, null, null, null, fetchedAt.toEpochMilli(),
                null, true, estimated ? Boolean.TRUE : null);
        return new Status(s.id(), s.status(), s.timestamp(), s.recipientId(), s.recipientUserId(), s.conversation(),
                s.pricing(), s.errors(), s.bizOpaqueCallbackData(), waWeb);
    }

    /** The page's receipt object for the message, {@code {id, delivered, read, played}} in seconds or null. */
    private static ObjectNode rawOf(Receipt r) {
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("id", r.id());
        putSeconds(n, "delivered", r.delivered());
        putSeconds(n, "read", r.read());
        putSeconds(n, "played", r.played());
        return n;
    }

    private static void putSeconds(ObjectNode n, String field, Instant at) {
        if (at == null) n.putNull(field);
        else n.put(field, at.getEpochSecond());
    }

    private ChannelContext channelOf(ImportContext ctx) {
        return new ChannelContext(ChannelKind.WHATSAPP_WEB, ctx.customerId(), ctx.workspaceId(), ctx.phoneNumberId(),
                devices.digitsOf(ctx.phoneNumberId()));
    }

    private static WaWeb imported(WaWeb waWeb) {
        return waWeb == null ? WaWeb.importedOnly() : waWeb.withImported(true);
    }

    /** Best effort, as for a live event: the stored event is for debugging and replay, the message is what matters. */
    private void keep(ChannelContext channel, Change change, JsonNode raw, Instant fetchedAt) {
        try {
            store.save(channel, WebhookEvent.of(channel, List.of(change)), raw, null, fetchedAt, 0, true);
        } catch (RuntimeException ex) {
            log.warn("could not keep fetched message {} of {}: {}", WaIds.messageId(raw.path("id")),
                    channel.channelAccountId(), ex.toString());
        }
    }
}
