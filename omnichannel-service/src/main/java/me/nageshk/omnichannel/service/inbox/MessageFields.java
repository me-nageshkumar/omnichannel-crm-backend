package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.CallLog;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.ContactCard;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Location;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Media;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.PollCreation;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Referral;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WaWeb;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;

/**
 * Reads the stored fields of a message from the typed Cloud API webhook records, as {@link MessagePayloads} reads them
 * from WhatsApp Web's raw model. Every reader tolerates missing objects.
 */
public final class MessageFields {

    private static final String LID = "@lid";
    private static final String PHONE = "@c.us";

    private MessageFields() {
    }

    /** What a message and an echo share: the type, the type objects and {@code wa_web}. */
    public record Content(String type, String text, Media image, Media video, Media audio, Media document,
            Media sticker, Location location, List<ContactCard> contacts, String buttonText, Interactive interactive,
            String orderText, PollCreation pollCreation, CallLog callLog, String channelInviteName, WaWeb waWeb) {
    }

    public static Content of(Message m) {
        return new Content(m.type(), m.text() == null ? null : m.text().body(), m.image(), m.video(), m.audio(),
                m.document(), m.sticker(), m.location(), m.contacts(), m.button() == null ? null : m.button().text(),
                m.interactive(), m.order() == null ? null : m.order().text(), m.pollCreation(), m.callLog(),
                m.newsletterFollowerInvite() == null ? null : m.newsletterFollowerInvite().name(), m.waWeb());
    }

    public static Content of(MessageEcho e) {
        return new Content(e.type(), e.text() == null ? null : e.text().body(), e.image(), e.video(), e.audio(),
                e.document(), e.sticker(), e.location(), e.contacts(), e.button() == null ? null : e.button().text(),
                e.interactive(), e.order() == null ? null : e.order().text(), e.pollCreation(), e.callLog(),
                e.newsletterFollowerInvite() == null ? null : e.newsletterFollowerInvite().name(), e.waWeb());
    }

    // ---- the chat

    /** {@code wa_web.chat_id}, else the sender's lid or phone chat; null without either. */
    public static String chatId(Message m) {
        if (m.waWeb() != null && notBlank(m.waWeb().chatId())) return m.waWeb().chatId();
        if (notBlank(m.fromUserId())) return m.fromUserId() + LID;
        return notBlank(m.from()) ? m.from() + PHONE : null;
    }

    /** {@code wa_web.chat_id}, else the recipient's phone chat; null without either. */
    public static String chatId(MessageEcho e) {
        if (e.waWeb() != null && notBlank(e.waWeb().chatId())) return e.waWeb().chatId();
        return notBlank(e.to()) ? e.to() + PHONE : null;
    }

    /** {@code wa_web.chat_id}, else the recipient's lid or phone chat; null without either. */
    public static String chatId(Status s) {
        if (s.waWeb() != null && notBlank(s.waWeb().chatId())) return s.waWeb().chatId();
        if (notBlank(s.recipientUserId())) return s.recipientUserId() + LID;
        return notBlank(s.recipientId()) ? s.recipientId() + PHONE : null;
    }

    /** True for a chat with a person: {@code @c.us} or {@code @lid}. */
    static boolean isPersonChat(String chatId) {
        return chatId != null && (chatId.endsWith(PHONE) || chatId.endsWith(LID));
    }

    // ---- the row

    /**
     * The type as rows store it today: WhatsApp Web's name ({@code wa_web.original_type} whenever the webhook type
     * differs from it), else Meta's type mapped back ({@code text} → {@code chat}, a voice {@code audio} →
     * {@code ptt}, {@code contacts} → {@code vcard}/{@code multi_vcard}). Task 8 stores Meta's names and removes this.
     */
    static String storedType(Content c) {
        if (c.waWeb() != null && notBlank(c.waWeb().originalType())) return c.waWeb().originalType();
        String type = c.type() == null ? "text" : c.type();
        return switch (type) {
            case "text" -> "chat";
            case "audio" -> c.audio() != null && Boolean.TRUE.equals(c.audio().voice()) ? "ptt" : "audio";
            case "contacts" -> cardCount(c) == 1 ? "vcard" : "multi_vcard";
            default -> type;
        };
    }

    private static int cardCount(Content c) {
        if (c.waWeb() != null && c.waWeb().vcards() != null) return c.waWeb().vcards().size();
        return c.contacts() == null ? 0 : c.contacts().size();
    }

    /**
     * What the sender wrote: the text, a media caption, the vCards of a card message, the question of a poll, the
     * label of a button or list reply, the body of a business message, the channel of an invite; else null.
     */
    static String bodyOf(Content c) {
        String type = c.type() == null ? "" : c.type();
        return switch (type) {
            case "text" -> c.text();
            case "image", "video", "audio", "document", "sticker" -> {
                Media m = typeMedia(c);
                yield m == null ? null : m.caption();
            }
            case "contacts" -> c.waWeb() == null || c.waWeb().vcards() == null || c.waWeb().vcards().isEmpty() ? null
                    : String.join("\n", c.waWeb().vcards());
            case "poll_creation" -> c.pollCreation() == null ? null : c.pollCreation().name();
            case "button" -> c.buttonText();
            case "interactive" -> interactiveText(c.interactive());
            case "order" -> c.orderText();
            case "newsletter_follower_invite" -> c.channelInviteName();
            default -> null;
        };
    }

    private static String interactiveText(Interactive i) {
        if (i == null) return null;
        if (i.body() != null && i.body().text() != null) return i.body().text();
        return i.listReply() == null ? null : i.listReply().title();
    }

    /**
     * The media of the row, from the type object or a business message's header; null unless
     * {@code wa_web.media_available} says the bytes can be fetched (view-once and expired media cannot).
     */
    static WaWebMessage.Media mediaOf(Content c) {
        if (c.waWeb() == null || !Boolean.TRUE.equals(c.waWeb().mediaAvailable())) return null;
        Media m = typeMedia(c);
        if (m == null && c.interactive() != null && c.interactive().header() != null) {
            Interactive.Header h = c.interactive().header();
            m = h.image() != null ? h.image() : h.video() != null ? h.video() : h.document();
        }
        if (m == null) return null;
        return new WaWebMessage.Media(m.mimeType(), m.filename(), c.waWeb().fileSize(), null, null);
    }

    private static Media typeMedia(Content c) {
        return switch (c.type() == null ? "" : c.type()) {
            case "image" -> c.image();
            case "video" -> c.video();
            case "audio" -> c.audio();
            case "document" -> c.document();
            case "sticker" -> c.sticker();
            default -> null;
        };
    }

    /** The place, its name and address joined back into WhatsApp's one description; null without coordinates. */
    static WaWebMessage.Location locationOf(Content c) {
        Location l = c.location();
        if (l == null || l.latitude() == null || l.longitude() == null) return null;
        String description = l.name() != null && l.address() != null ? l.name() + "\n" + l.address()
                : l.name() != null ? l.name() : l.address();
        return new WaWebMessage.Location(l.latitude(), l.longitude(), description);
    }

    /** The choices of a poll; null on any other message or when there are none. */
    static List<String> pollOptionsOf(Content c) {
        if (!"poll_creation".equals(c.type()) || c.pollCreation() == null || c.pollCreation().options() == null)
            return null;
        List<String> names = new ArrayList<>();
        for (PollCreation.Option o : c.pollCreation().options()) {
            if (o != null && o.name() != null && !o.name().isBlank()) names.add(o.name());
        }
        return names.isEmpty() ? null : names;
    }

    /**
     * The votes sent with a fetched poll; empty for anything else. The webhook's vote does not say whose side the
     * voter is on, so {@code fromMe} is false.
     */
    static List<WaWebMessage.PollVote> pollVotesOf(Content c) {
        List<WaWebMessage.PollVote> votes = new ArrayList<>();
        if (c.pollCreation() == null || c.pollCreation().votes() == null) return votes;
        for (PollCreation.Vote v : c.pollCreation().votes()) {
            if (v == null || !notBlank(v.voter())) continue;
            votes.add(new WaWebMessage.PollVote(v.voter(), false,
                    v.selectedOptions() == null ? List.of() : v.selectedOptions(),
                    Instant.ofEpochMilli(v.at() == null ? 0 : v.at())));
        }
        return votes;
    }

    /** What the message says of a call; null unless it is a {@code call_log}. */
    static WaWebMessage.Call callOf(Content c) {
        if (!"call_log".equals(c.type())) return null;
        CallLog call = c.callLog();
        if (call == null) return new WaWebMessage.Call(false, false, null);
        Long seconds = call.duration();
        return new WaWebMessage.Call(Boolean.TRUE.equals(call.video()), Boolean.TRUE.equals(call.missed()),
                seconds == null || seconds <= 0 ? null : Math.toIntExact(seconds));
    }

    /** WhatsApp's tag on a business template, such as AUTHENTICATION for a verification code; else null. */
    static String businessTagOf(Content c) {
        return c.waWeb() == null ? null : c.waWeb().businessTag();
    }

    /**
     * The click-to-WhatsApp ad behind an inbound message: its {@code referral}, with the source app, the conversion
     * source and the raw context that only {@code wa_web} carries; null without a referral.
     */
    static AdClick adClickOf(Message m, Instant at) {
        Referral r = m.referral();
        if (r == null) return null;
        WaWeb w = m.waWeb();
        return new AdClick(r.sourceId(), r.sourceUrl(), w == null ? null : w.ctwaSourceApp(), r.headline(), r.body(),
                w == null ? null : w.ctwaConversionSource(), r.ctwaClid(), at, w == null ? null : w.ctwaContext());
    }

    // ---- the sender

    /** The name WhatsApp shows for the sender; null when blank or absent. */
    static String senderNameOf(Contact contact) {
        if (contact == null || contact.profile() == null || contact.profile().name() == null) return null;
        String name = contact.profile().name().trim();
        return name.isEmpty() ? null : name;
    }

    /** The phone behind a {@code @lid} sender when WhatsApp exposed it: {@code from} beside a {@code from_user_id}. */
    static String lidPhoneOf(Message m) {
        return notBlank(m.fromUserId()) && notBlank(m.from()) ? m.from() : null;
    }

    // ---- time and status

    /** Meta's time in seconds; {@code fallback} when absent or unreadable. */
    public static Instant timestampOf(String seconds, Instant fallback) {
        if (seconds == null) return fallback;
        try {
            long s = Long.parseLong(seconds.trim());
            return s > 0 ? Instant.ofEpochSecond(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** WhatsApp Web's ack for a Meta status: sent 1, delivered 2, read 3, played 4, failed -1; else 0. */
    static int ackOf(Status s) {
        if (s == null || s.status() == null) return 0;
        return switch (s.status()) {
            case "sent" -> 1;
            case "delivered" -> 2;
            case "read" -> 3;
            case "played" -> 4;
            case "failed" -> -1;
            default -> 0;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
