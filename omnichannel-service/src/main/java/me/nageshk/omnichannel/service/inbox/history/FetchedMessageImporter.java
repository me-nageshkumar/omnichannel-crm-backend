package me.nageshk.omnichannel.service.inbox.history;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WaWeb;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.channel.whatsapp.WaIds;
import me.nageshk.omnichannel.service.channel.whatsapp.WaWebWebhookConverter;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportOutcome;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Imports one message model of a fetched history page: the converter turns it into a person's message or our echo,
 * as it does for a live event, and the import stores that. Each converted message is first kept in the webhook event
 * store as a one-change event marked {@code wa_web.imported}, with its raw model; its key makes a refetch store
 * nothing. A model the converter drops (a notice, a chat of no person, no id) or cannot read is SKIPPED.
 */
@Component
public class FetchedMessageImporter {

    private static final Logger log = LoggerFactory.getLogger(FetchedMessageImporter.class);
    private static final ImportResult SKIPPED = new ImportResult(ImportOutcome.SKIPPED, null, null);

    private final WaWebWebhookConverter converter;
    private final InboxIngestService ingest;
    private final InboxDevices devices;
    private final ChannelWebhookEventStore store;
    private final Clock clock;

    public FetchedMessageImporter(WaWebWebhookConverter converter, InboxIngestService ingest, InboxDevices devices,
            ChannelWebhookEventStore store, Clock clock) {
        this.converter = converter;
        this.ingest = ingest;
        this.devices = devices;
        this.store = store;
        this.clock = clock;
    }

    /** @see InboxIngestService#importMessage */
    public ImportResult importMessage(ImportContext ctx, JsonNode raw) {
        if (raw == null || !raw.isObject()) return SKIPPED;
        ChannelContext channel = new ChannelContext(ChannelKind.WHATSAPP_WEB, ctx.customerId(), ctx.workspaceId(),
                ctx.phoneNumberId(), devices.digitsOf(ctx.phoneNumberId()));
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
