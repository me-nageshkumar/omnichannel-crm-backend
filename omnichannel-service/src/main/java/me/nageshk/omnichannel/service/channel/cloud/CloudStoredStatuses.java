package me.nageshk.omnichannel.service.channel.cloud;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Entry;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Meta may send a Cloud message's {@code sent} or {@code failed} status before our send has recorded the row, and no
 * echo exists on Cloud to make up for it: such statuses wait in the event store and are applied once the row exists.
 */
@Component
public class CloudStoredStatuses {

    private static final Logger log = LoggerFactory.getLogger(CloudStoredStatuses.class);

    private final ChannelWebhookEventStore store;
    private final InboxIngestService ingest;

    public CloudStoredStatuses(ChannelWebhookEventStore store, InboxIngestService ingest) {
        this.store = store;
        this.ingest = ingest;
    }

    public void apply(ChannelContext ctx, String waMessageId) {
        for (WebhookEvent e : store.statusesFor(waMessageId)) {
            for (Status s : statusesOf(e)) {
                try {
                    ingest.ingestStatus(ctx, s);
                } catch (RuntimeException ex) {
                    log.warn("could not apply a stored status of {}: {}", waMessageId, ex.toString());
                }
            }
        }
    }

    private static List<Status> statusesOf(WebhookEvent e) {
        List<Status> out = new ArrayList<>();
        if (e == null || e.entry() == null) return out;
        for (Entry entry : e.entry()) {
            if (entry == null || entry.changes() == null) continue;
            for (Change change : entry.changes()) {
                if (change != null && change.value() != null && change.value().statuses() != null) {
                    out.addAll(change.value().statuses());
                }
            }
        }
        return out;
    }
}
