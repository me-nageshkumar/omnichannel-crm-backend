package me.nageshk.omnichannel.service.channel.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Entry;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Metadata;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.meta.api.CloudChange;
import me.nageshk.omnichannel.meta.api.CloudWebhookSink;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore.Outcome;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore.SaveResult;
import me.nageshk.omnichannel.service.channel.whatsapp.WhatsAppWebhookProcessor;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.inbox.ChatMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Meta's verified {@code messages} changes into the inbox pipeline (spec 2026-11-20 §5.3): each message and each status
 * is stored as its own one-change event, keyed so Meta's retries are found; only the events stored for the first time
 * are processed, after Meta has its answer.
 */
@Component
public class CloudWebhookIngest implements CloudWebhookSink {

    private static final Logger log = LoggerFactory.getLogger(CloudWebhookIngest.class);

    private record Stored(ChannelContext ctx, WebhookEvent event, String docId) {}

    private final ChannelWebhookEventStore store;
    private final WhatsAppWebhookProcessor processor;
    private final ObjectMapper mapper;
    private final WaWebMessageRepository messages;
    private final WaWebChatRepository chats;
    private final ChatMediaService media;

    public CloudWebhookIngest(ChannelWebhookEventStore store, WhatsAppWebhookProcessor processor, ObjectMapper mapper,
            WaWebMessageRepository messages, WaWebChatRepository chats, ChatMediaService media) {
        this.messages = messages;
        this.chats = chats;
        this.media = media;
        this.store = store;
        this.processor = processor;
        this.mapper = mapper;
    }

    @Override
    public Delivery accept(List<CloudChange> changes) {
        boolean failed = false;
        List<Stored> fresh = new ArrayList<>();
        for (CloudChange change : changes) {
            ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_CLOUD, change.customerId(),
                    change.workspaceId(), change.phoneNumberId(), change.displayDigits());
            List<WebhookEvent> events;
            try {
                events = split(change);
            } catch (RuntimeException e) {
                log.warn("could not read a Meta messages change of {}: {}", change.phoneNumberId(), e.toString());
                events = List.of();
                // kept as a shell so it is found by number, as an unconvertible WhatsApp Web event is
                if (store.saveResult(ctx, null, change.value(), null, change.receivedAt(), 0).outcome()
                        == Outcome.FAILED) {
                    failed = true;
                }
            }
            for (int i = 0; i < events.size(); i++) {
                SaveResult r = store.saveResult(ctx, events.get(i), change.value(), null, change.receivedAt(), i);
                if (r.outcome() == Outcome.FAILED) failed = true;
                else if (r.outcome() == Outcome.STORED) fresh.add(new Stored(ctx, events.get(i), r.doc().getId()));
            }
        }
        return new Delivery(failed, () -> process(fresh));
    }

    /**
     * Processes the stored Cloud events nobody processed (a database failure mid-ingest, a full queue, a restart):
     * Meta has had its 200 and would not send them again. Returns how many were processed.
     */
    public int replay(java.time.Duration minAge) {
        int done = 0;
        for (var doc : store.claimPending(minAge, java.time.Duration.ofMinutes(2), 100)) {
            var t = doc.getCloud();
            WebhookEvent event = store.eventOf(doc);
            if (t == null || event == null) {
                store.markProcessed(doc.getId());
                continue;
            }
            ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_CLOUD, t.customerId(), t.workspaceId(),
                    t.phoneNumberId(), t.displayDigits());
            if (processOne(new Stored(ctx, event, doc.getId()))) done++;
        }
        return done;
    }

    /** True when the event was processed (and marked so); false leaves it for the next replay. */
    private boolean processOne(Stored s) {
        try {
            processor.process(s.ctx(), s.event());
        } catch (RuntimeException e) {
            log.warn("could not ingest a Meta event of {} (it is replayed later): {}", s.ctx().channelAccountId(),
                    e.toString());
            return false;
        }
        store.markProcessed(s.docId());
        fetchMedia(s);
        return true;
    }

    private void process(List<Stored> fresh) {
        for (Stored s : fresh) processOne(s);
    }

    /** Meta keeps an incoming file a week and its address minutes: the bytes are fetched at once. */
    private void fetchMedia(Stored s) {
        for (Message m : messagesOf(s.event())) {
            if (m.image() == null && m.video() == null && m.audio() == null && m.document() == null
                    && m.sticker() == null) {
                continue;
            }
            try {
                messages.findByPhoneNumberIdAndWaMessageId(s.ctx().channelAccountId(), m.id()).ifPresent(row -> {
                    if (row.getMedia() == null || row.getMedia().mediaId() != null) return;
                    chats.findById(row.getChatId()).ifPresent(chat -> media.fetchIfNeeded(chat, row).join());
                });
            } catch (RuntimeException e) {
                log.info("could not fetch the file of Meta message {}: {}", m.id(), e.toString());
            }
        }
    }

    private static List<Message> messagesOf(WebhookEvent e) {
        List<Message> out = new ArrayList<>();
        for (Entry entry : orEmpty(e.entry())) {
            for (Change c : orEmpty(entry.changes())) {
                if (c.value() != null) out.addAll(orEmpty(c.value().messages()));
            }
        }
        return out;
    }

    /** One event per message and per status (as the bridge stores them); a change with neither keeps its errors. */
    private List<WebhookEvent> split(CloudChange change) {
        Value meta = mapper.treeToValue(change.value(), Value.class);
        // stored and processed under our number, as WhatsApp Web events are; raw keeps Meta's own ids
        Value v = new Value(meta.messagingProduct(), new Metadata(change.displayDigits(), change.phoneNumberId()),
                meta.contacts(), meta.messages(), meta.messageEchoes(), meta.statuses(), meta.errors());
        List<WebhookEvent> out = new ArrayList<>();
        for (Message m : orEmpty(v.messages())) {
            out.add(event(change, new Value(v.messagingProduct(), v.metadata(), contactsOf(v, m), List.of(m), null,
                    null, null)));
        }
        for (Status s : orEmpty(v.statuses())) {
            out.add(event(change, new Value(v.messagingProduct(), v.metadata(), null, null, null, List.of(s), null)));
        }
        if (out.isEmpty()) out.add(event(change, v));
        return out;
    }

    private static List<Contact> contactsOf(Value v, Message m) {
        List<Contact> all = orEmpty(v.contacts());
        List<Contact> mine = all.stream().filter(c -> c != null && ((m.from() != null && m.from().equals(c.waId()))
                || (m.fromUserId() != null && m.fromUserId().equals(c.userId())))).toList();
        return mine.isEmpty() ? (all.isEmpty() ? null : all) : mine;
    }

    private static WebhookEvent event(CloudChange change, Value value) {
        return new WebhookEvent(WebhookEvent.WHATSAPP_BUSINESS_ACCOUNT,
                List.of(new Entry(change.wabaId(), List.of(new Change(change.field(), value)), null)));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
