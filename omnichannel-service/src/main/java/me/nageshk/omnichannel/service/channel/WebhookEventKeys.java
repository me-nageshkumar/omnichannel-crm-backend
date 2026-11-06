package me.nageshk.omnichannel.service.channel;

import java.util.List;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Entry;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;

/**
 * What a stored webhook event is about, read from its single change: the idempotency key and the event's own time. A
 * message or echo is {@code message:<id>} live and {@code history:<id>} from the history import; a status is
 * {@code status:<id>:<status>}, one per state; a poll vote is {@code vote:<id>:<timestamp>}, one per change of the
 * vote. A shell (no message, echo or status) has neither.
 */
public final class WebhookEventKeys {

    private static final String POLL_VOTE = "poll_vote";

    private WebhookEventKeys() {}

    /** The key, or null for a shell or an item with no id. */
    public static String of(WebhookEvent event, boolean imported) {
        Value v = valueOf(event);
        if (v == null) return null;
        Message m = first(v.messages());
        if (m != null) return messageKey(m.id(), m.type(), m.timestamp(), imported);
        MessageEcho e = first(v.messageEchoes());
        if (e != null) return messageKey(e.id(), e.type(), e.timestamp(), imported);
        Status s = first(v.statuses());
        if (s != null) return blank(s.id()) || blank(s.status()) ? null : "status:" + s.id() + ":" + s.status();
        return null;
    }

    /** The message's or status's timestamp in seconds; null when absent or not a number. */
    public static Long secondsOf(WebhookEvent event) {
        Value v = valueOf(event);
        if (v == null) return null;
        Message m = first(v.messages());
        MessageEcho e = first(v.messageEchoes());
        Status s = first(v.statuses());
        String ts = m != null ? m.timestamp() : e != null ? e.timestamp() : s != null ? s.timestamp() : null;
        if (blank(ts)) return null;
        try {
            return Long.parseLong(ts.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String messageKey(String id, String type, String timestamp, boolean imported) {
        if (blank(id)) return null;
        if (POLL_VOTE.equals(type)) return "vote:" + id + ":" + timestamp;
        return (imported ? "history:" : "message:") + id;
    }

    private static Value valueOf(WebhookEvent event) {
        if (event == null) return null;
        Entry entry = first(event.entry());
        if (entry == null) return null;
        Change change = first(entry.changes());
        return change == null ? null : change.value();
    }

    private static <T> T first(List<T> list) {
        return list == null || list.isEmpty() ? null : list.getFirst();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
