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
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.meta.api.CloudChange;
import me.nageshk.omnichannel.meta.api.CloudWebhookSink;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore.Outcome;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore.SaveResult;
import me.nageshk.omnichannel.service.channel.whatsapp.WhatsAppWebhookProcessor;
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

    private record Stored(ChannelContext ctx, WebhookEvent event) {}

    private final ChannelWebhookEventStore store;
    private final WhatsAppWebhookProcessor processor;
    private final ObjectMapper mapper;

    public CloudWebhookIngest(ChannelWebhookEventStore store, WhatsAppWebhookProcessor processor, ObjectMapper mapper) {
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
                else if (r.outcome() == Outcome.STORED) fresh.add(new Stored(ctx, events.get(i)));
            }
        }
        return new Delivery(failed, () -> process(fresh));
    }

    private void process(List<Stored> fresh) {
        for (Stored s : fresh) {
            try {
                processor.process(s.ctx(), s.event());
            } catch (RuntimeException e) {
                log.warn("could not ingest a Meta event of {}: {}", s.ctx().channelAccountId(), e.toString());
            }
        }
    }

    /** One event per message and per status (as the bridge stores them); a change with neither keeps its errors. */
    private List<WebhookEvent> split(CloudChange change) {
        Value v = mapper.treeToValue(change.value(), Value.class);
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
