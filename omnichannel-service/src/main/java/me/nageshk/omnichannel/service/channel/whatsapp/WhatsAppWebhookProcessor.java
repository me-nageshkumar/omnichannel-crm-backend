package me.nageshk.omnichannel.service.channel.whatsapp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Entry;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.inbox.MessageFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a Cloud API shaped webhook event and hands each message, echo, vote and status to the inbox ingest. An entry
 * is one unit: a status whose id matches an echo of the same entry is applied while the echo is stored, so the row is
 * written once with its status; the other statuses follow on their own. The entry point a Cloud controller would call.
 */
@Component
public class WhatsAppWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookProcessor.class);
    private static final String POLL_VOTE = "poll_vote";

    private final InboxIngestService ingest;

    public WhatsAppWebhookProcessor(InboxIngestService ingest) {
        this.ingest = ingest;
    }

    public void process(ChannelContext ctx, WebhookEvent event) {
        if (event == null || event.entry() == null) return;
        for (Entry entry : event.entry()) {
            if (entry != null) process(ctx, entry);
        }
    }

    private void process(ChannelContext ctx, Entry entry) {
        List<Value> values = entry.changes() == null ? List.of()
                : entry.changes().stream().filter(Objects::nonNull).map(Change::value).filter(Objects::nonNull)
                        .toList();
        List<Status> statuses = new ArrayList<>();
        Map<String, Status> statusById = new HashMap<>();
        for (Value v : values) {
            for (Status s : orEmpty(v.statuses())) {
                if (s == null) continue;
                statuses.add(s);
                if (s.id() != null) statusById.put(s.id(), s);
            }
        }
        Set<Status> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Value v : values) {
            for (Message m : orEmpty(v.messages())) {
                if (m == null) continue;
                if (POLL_VOTE.equals(m.type())) vote(ctx, m);
                else ingest.ingestInbound(ctx, contactFor(v.contacts(), m), m);
            }
            for (MessageEcho e : orEmpty(v.messageEchoes())) {
                if (e == null) continue;
                if (POLL_VOTE.equals(e.type())) {
                    vote(ctx, e);
                    continue;
                }
                if (MessageFields.chatId(e) == null) {
                    log.warn("skipping echo {} of {}: it names no chat", e.id(), ctx.channelAccountId());
                    continue;
                }
                Status status = e.id() == null ? null : statusById.get(e.id());
                ingest.ingestEcho(ctx, e, status);
                if (status != null) consumed.add(status);
            }
        }
        for (Status s : statuses) {
            if (!consumed.contains(s)) ingest.ingestStatus(ctx, s);
        }
    }

    private void vote(ChannelContext ctx, Message m) {
        if (m.pollVote() == null) return;
        String fallback = m.fromUserId() != null ? m.fromUserId() + "@lid" : m.from() != null ? m.from() + "@c.us" : null;
        ingest.ingestVote(ctx, MessageFields.chatId(m), m.pollVote(), voterOf(m.id(), m.pollVote().messageId(), fallback),
                false, MessageFields.timestampOf(m.timestamp(), Instant.EPOCH));
    }

    private void vote(ChannelContext ctx, MessageEcho e) {
        if (e.pollVote() == null) return;
        String fallback = e.from() == null ? null : e.from() + "@c.us";
        ingest.ingestVote(ctx, MessageFields.chatId(e), e.pollVote(), voterOf(e.id(), e.pollVote().messageId(), fallback),
                true, MessageFields.timestampOf(e.timestamp(), Instant.EPOCH));
    }

    /**
     * The voter's chat key from a vote's id {@code vote:<poll id>:<voter>} (spec §4.6), which keeps WhatsApp Web's own
     * key for our votes too; else {@code fallback}, the sender's key.
     */
    static String voterOf(String id, String pollId, String fallback) {
        String prefix = "vote:" + pollId + ":";
        if (id != null && pollId != null && id.startsWith(prefix) && id.length() > prefix.length()) {
            return id.substring(prefix.length());
        }
        return fallback;
    }

    /** The {@code contacts[]} entry naming the sender, by phone or by lid; null when none does. */
    static Contact contactFor(List<Contact> contacts, Message m) {
        for (Contact c : orEmpty(contacts)) {
            if (c == null) continue;
            if (m.from() != null && m.from().equals(c.waId())) return c;
            if (m.fromUserId() != null && m.fromUserId().equals(c.userId())) return c;
        }
        return null;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
