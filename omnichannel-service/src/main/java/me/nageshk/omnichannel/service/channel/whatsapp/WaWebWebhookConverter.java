package me.nageshk.omnichannel.service.channel.whatsapp;

import me.nageshk.omnichannel.service.channel.MongoKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Contact;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.ContactCard;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Context;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Location;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Media;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageError;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Referral;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Text;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WaWeb;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.service.inbox.MessageBodies;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns WhatsApp Web's raw message model into a Cloud API shaped webhook event. Pure: no state, no I/O; every reader
 * tolerates missing nodes. Names of Meta's objects stay Meta's; what only WhatsApp Web knows goes into {@code wa_web}.
 */
@Component
public final class WaWebWebhookConverter {

    static final String CHANGE_FIELD = "messages";
    static final String ECHO_FIELD = "smb_message_echoes";
    private static final Set<String> NOTICE_TYPES = Set.of("notification_template", "e2e_notification", "notification",
            "group_notification", "broadcast_notification");
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video", "audio", "ptt", "document", "sticker");
    private static final int FREQUENTLY_FORWARDED = 5;
    private static final int AD_TEXT_MAX = 500;
    private static final int AD_RAW_MAX_BYTES = 4096;
    private static final Set<String> AD_RAW_DROPPED = Set.of("thumbnail", "thumbnailUrl", "originalImageUrl", "mediaUrl",
            "conversionData");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One event for a {@code message_create} (a person's message, or our own as an echo) or a {@code message_ack}
     * (a status of our message); empty for any other event or message.
     */
    public Optional<WebhookEvent> convert(ChannelContext ctx, String eventName, JsonNode payload, Instant capturedAt) {
        if (payload == null) return Optional.empty();
        if ("message_ack".equals(eventName)) {
            return status(ctx, payload, capturedAt).map(st -> WebhookEvent.of(ctx,
                    List.of(new Change(CHANGE_FIELD, Value.of(ctx).withStatuses(List.of(st))))));
        }
        if ("vote_update".equals(eventName)) return WaWebExtensionTypes.vote(ctx, payload, capturedAt);
        if (!"message_create".equals(eventName)) return Optional.empty();
        if (fromMe(payload)) return echoEvent(ctx, payload, capturedAt);
        return message(ctx, payload, capturedAt).map(m -> {
            Value value = Value.of(ctx).withContacts(List.of(contactOf(m, payload))).withMessages(List.of(m));
            return WebhookEvent.of(ctx, List.of(new Change(CHANGE_FIELD, value)));
        });
    }

    /** The echo as a change, followed by a {@code messages} change with its status when the ack is 1 or more. */
    private Optional<WebhookEvent> echoEvent(ChannelContext ctx, JsonNode raw, Instant capturedAt) {
        return echo(ctx, raw, capturedAt, null).map(e -> {
            List<Change> changes = new ArrayList<>();
            changes.add(new Change(ECHO_FIELD, Value.of(ctx).withMessageEchoes(List.of(e))));
            Status st = statusOf(e.id(), e.waWeb().ack(), capturedAt, e.waWeb().chatId(),
                    recipientDigits(e.waWeb().chatId()));
            if (st != null) changes.add(new Change(CHANGE_FIELD, Value.of(ctx).withStatuses(List.of(st))));
            return WebhookEvent.of(ctx, changes);
        });
    }

    /** An inbound message; empty for ours, for a notice, a chat of no person and a message with no id. */
    public Optional<Message> message(ChannelContext ctx, JsonNode raw, Instant fallbackTime) {
        if (fromMe(raw)) return Optional.empty();
        Parts p = parts(raw, fallbackTime);
        if (p == null) return Optional.empty();
        String user = WaIds.user(p.chatId);
        boolean lid = p.chatId.endsWith("@lid");
        String from = lid ? lidPhone(raw) : user;
        String fromUserId = lid ? user : null;
        return Optional.of(new Message(from, fromUserId, p.id, timestamp(raw, fallbackTime), p.type, p.context,
                p.referral, p.ext.errors(), p.text, p.image, p.video, p.audio, p.document, p.sticker, p.location,
                p.contacts, p.ext.button(), p.ext.interactive(), p.ext.order(), null, p.ext.pollCreation(), null,
                p.ext.callLog(), p.ext.revoked(), p.ext.bizContentPlaceholder(), p.ext.newsletterFollowerInvite(),
                p.ext.album(), p.waWeb));
    }

    /**
     * Our own message as Meta's echo; empty for a message from a person, a notice, a chat of no person and a message
     * with no id. {@code to} is the chat's digits, or for a {@code @lid} chat {@code toDigitsIfKnown} (else absent).
     */
    public Optional<MessageEcho> echo(ChannelContext ctx, JsonNode raw, Instant fallbackTime, String toDigitsIfKnown) {
        if (!fromMe(raw)) return Optional.empty();
        Parts p = parts(raw, fallbackTime);
        if (p == null) return Optional.empty();
        String to = p.chatId.endsWith("@lid") ? toDigitsIfKnown : WaIds.user(p.chatId);
        return Optional.of(new MessageEcho(ctx.displayPhoneNumber(), to, p.id, timestamp(raw, fallbackTime), p.type,
                p.context, p.text, p.image, p.video, p.audio, p.document, p.sticker, p.location, p.contacts,
                p.ext.button(), p.ext.interactive(), p.ext.order(), null, p.ext.pollCreation(), null, p.ext.callLog(),
                p.ext.revoked(), p.ext.bizContentPlaceholder(), p.ext.newsletterFollowerInvite(), p.ext.album(),
                p.waWeb));
    }

    /** The status of our message from a {@code message_ack} payload; empty for a person's message or an ack of 0. */
    public Optional<Status> status(ChannelContext ctx, JsonNode ackPayload, Instant capturedAt) {
        JsonNode raw = ackPayload.path("message");
        if (!fromMe(raw)) return Optional.empty();
        String chatId = chatIdOf(raw);
        String id = WaIds.messageId(raw.path("id"));
        if (!isPersonChat(chatId) || id.isEmpty()) return Optional.empty();
        return Optional.ofNullable(
                statusOf(id, ackPayload.path("ack").asInt(0), capturedAt, chatId, recipientDigits(chatId)));
    }

    /** The chat's digits for a {@code @c.us} chat; null for a {@code @lid} chat, whose phone is not invented. */
    private static String recipientDigits(String chatId) {
        return chatId.endsWith("@c.us") ? WaIds.user(chatId) : null;
    }

    /**
     * Meta's status for a WhatsApp Web ack: 1 sent, 2 delivered, 3 read, 4 played, -1 failed; null for 0 or any other.
     * {@code at} is the time of the status (now when null). A {@code @lid} chat gives {@code recipient_user_id}.
     */
    public static Status statusOf(String messageId, int ack, Instant at, String chatId, String recipientDigits) {
        String status = switch (ack) {
            case 1 -> "sent";
            case 2 -> "delivered";
            case 3 -> "read";
            case 4 -> "played";
            case -1 -> "failed";
            default -> null;
        };
        if (status == null) return null;
        String lidUser = chatId != null && chatId.endsWith("@lid") ? WaIds.user(chatId) : null;
        List<MessageError> errors = ack == -1 ? List.of(new MessageError(0, "Not sent",
                "WhatsApp Web reported the message as failed", null, null)) : null;
        long seconds = (at == null ? Instant.now() : at).getEpochSecond();
        WaWeb waWeb = new WaWeb(chatId, ack, null, null, null, null, null, null, null, null,
                at == null ? null : at.toEpochMilli());
        return new Status(messageId, status, String.valueOf(seconds), recipientDigits, lidUser, null, null, errors, null,
                waWeb);
    }

    /** What a message and an echo share: the type object and everything around it. */
    private record Parts(String id, String chatId, String type, Context context, Referral referral, Text text,
            Media image, Media video, Media audio, Media document, Media sticker, Location location,
            List<ContactCard> contacts, WaWeb waWeb, WaWebExtensionTypes.Mapped ext) {}

    /** The shared parts; null for a notice, a chat of no person, a message with no id and a type not carried. */
    private static Parts parts(JsonNode raw, Instant fallbackTime) {
        String rawType = rawType(raw);
        if (NOTICE_TYPES.contains(rawType)) return null;
        String chatId = chatIdOf(raw);
        if (!isPersonChat(chatId)) return null;
        String id = WaIds.messageId(raw.path("id"));
        if (id.isEmpty()) return null;
        String type = cloudType(rawType);
        WaWebExtensionTypes.Mapped ext = WaWebExtensionTypes.Mapped.NONE;
        if (type == null) {
            ext = WaWebExtensionTypes.map(raw, id, rawType);
            type = ext.type();
        }

        List<String> vcards = "contacts".equals(type) ? vcardTexts(raw) : null;
        Text text = ext.text() != null ? ext.text()
                : "text".equals(type) && ext.type() == null ? new Text(body(raw, rawType)) : null;
        Media media = MEDIA_TYPES.contains(rawType) ? media(raw, id, rawType) : null;
        Referral referral = referral(raw);
        return new Parts(id, chatId, type, context(raw), referral, text, "image".equals(type) ? media : null,
                "video".equals(type) ? media : null, "audio".equals(type) ? media : null,
                "document".equals(type) ? media : null, "sticker".equals(type) ? media : null,
                "location".equals(type) ? location(raw) : null, vcards == null ? null : VcardCards.parse(vcards),
                waWeb(raw, chatId, rawType, type, referral, vcards, fallbackTime, ext.media()), ext);
    }

    /** The one contact of the event: the person who wrote, by phone and lid, with the name WhatsApp showed. */
    public Contact contactOf(Message m, JsonNode raw) {
        String name = senderName(raw);
        return new Contact(name == null ? null : new Contact.Profile(name, null), m.from(), m.fromUserId());
    }

    /** The chat id the message belongs to, e.g. {@code 919812345678@c.us}. */
    public static String chatIdOf(JsonNode raw) {
        return WaIds.wid(raw.path("id").path("remote"));
    }

    /** True for a chat with a person: {@code @c.us} or {@code @lid}. */
    public static boolean isPersonChat(String chatId) {
        return chatId != null && (chatId.endsWith("@c.us") || chatId.endsWith("@lid"));
    }

    // ---- type, time, ids

    private static boolean fromMe(JsonNode raw) {
        return raw.path("fromMe").asBoolean(raw.path("id").path("fromMe").asBoolean(false));
    }

    private static String rawType(JsonNode raw) {
        String t = text(raw.path("type"));
        return t == null ? "chat" : t;
    }

    /** Meta's type for a raw one; null when this converter does not carry it. */
    private static String cloudType(String rawType) {
        return switch (rawType) {
            case "chat" -> "text";
            case "image", "video", "document", "sticker", "location" -> rawType;
            case "ptt", "audio" -> "audio";
            case "vcard", "multi_vcard" -> "contacts";
            default -> null;
        };
    }

    /** WhatsApp's time in seconds ({@code t}, else {@code timestamp}), else the capture time, else now. */
    private static String timestamp(JsonNode raw, Instant fallback) {
        long seconds = raw.path("t").asLong(raw.path("timestamp").asLong(0));
        if (seconds <= 0) seconds = (fallback == null ? Instant.now() : fallback).getEpochSecond();
        return String.valueOf(seconds);
    }

    /** The phone behind a {@code @lid} chat when WhatsApp exposed it, digits only; null otherwise. */
    private static String lidPhone(JsonNode raw) {
        for (JsonNode n : new JsonNode[] {raw.path("senderPn"), raw.path("_data").path("senderPn"), raw.path("author")}) {
            String w = WaIds.wid(n);
            if (w.endsWith("@c.us")) return WaIds.user(w);
        }
        return null;
    }

    private static String senderName(JsonNode raw) {
        for (JsonNode n : new JsonNode[] {raw.path("notifyName"), raw.path("_data").path("notifyName"),
                raw.path("pushname")}) {
            String s = n.isTextual() ? n.asString().trim() : "";
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    // ---- text and media

    /** The text a person wrote: the body, unless it is a media thumbnail. */
    private static String body(JsonNode raw, String rawType) {
        return MessageBodies.readable(rawType, raw.path("body").isTextual() ? raw.path("body").asString() : null);
    }

    /** {@code id} is the message id: the page downloads media by it. */
    private static Media media(JsonNode raw, String id, String rawType) {
        JsonNode data = raw.path("_data");
        String filename = "document".equals(rawType) ? firstText(raw.path("filename"), data.path("filename")) : null;
        Boolean voice = "ptt".equals(rawType) ? Boolean.TRUE : null;
        Boolean animated = "sticker".equals(rawType) && raw.path("isAnimated").asBoolean(false) ? Boolean.TRUE : null;
        return new Media(id, firstText(raw.path("mimetype"), data.path("mimetype")),
                firstText(raw.path("filehash"), data.path("filehash")), text(raw.path("caption")), filename, voice,
                animated);
    }

    private static Long fileSize(JsonNode raw) {
        for (JsonNode n : new JsonNode[] {raw.path("size"), raw.path("_data").path("size")}) {
            if (n.isNumber()) return n.asLong();
        }
        return null;
    }

    /** {@code hasMedia} is whatsapp-web.js's name for a {@code directPath}, which is what the page actually sends. */
    private static boolean mediaAvailable(JsonNode raw) {
        return raw.path("hasMedia").asBoolean(false) || text(raw.path("directPath")) != null;
    }

    // ---- location

    /**
     * {@code location.latitude/longitude/description}, else {@code lat/lng/loc} of a location message, else
     * {@code _data.*}; the first line of the description is the name, the rest the address.
     */
    private static Location location(JsonNode raw) {
        JsonNode loc = raw.path("location");
        JsonNode data = raw.path("_data");
        Double lat = number(loc.path("latitude"), raw.path("lat"), data.path("lat"));
        Double lng = number(loc.path("longitude"), raw.path("lng"), data.path("lng"));
        if (lat == null || lng == null) return null;
        String description = firstText(loc.path("description"), raw.path("loc"), data.path("loc"));
        String name = null;
        String address = null;
        if (description != null) {
            int nl = description.indexOf('\n');
            name = (nl < 0 ? description : description.substring(0, nl)).trim();
            address = nl < 0 ? "" : description.substring(nl + 1).trim();
            if (name.isEmpty()) name = null;
            if (address.isEmpty()) address = null;
        }
        return new Location(lat, lng, name, address);
    }

    // ---- cards

    private static List<String> vcardTexts(JsonNode raw) {
        List<String> cards = new ArrayList<>();
        if (raw.path("vcardList").isArray()) {
            for (JsonNode card : raw.path("vcardList")) {
                String vcard = text(card.path("vcard"));
                if (vcard != null) cards.add(vcard);
            }
        } else {
            String body = text(raw.path("body"));
            if (body != null) cards.add(body);
        }
        return cards.isEmpty() ? null : cards;
    }

    // ---- context and referral

    /** The quoted message and the forward flags; null when none applies. */
    private static Context context(JsonNode raw) {
        String quoted = text(raw.path("quotedStanzaID"));
        String from = null;
        if (quoted != null) {
            String participant = WaIds.wid(raw.path("quotedParticipant"));
            from = participant.isEmpty() ? null : WaIds.user(participant);
        }
        Boolean forwarded = raw.path("isForwarded").asBoolean(false) ? Boolean.TRUE : null;
        Boolean frequently = raw.path("forwardingScore").asInt(0) >= FREQUENTLY_FORWARDED ? Boolean.TRUE : null;
        if (quoted == null && forwarded == null && frequently == null) return null;
        return new Context(from, quoted, forwarded, frequently);
    }

    /** True for a {@code ctwaContext} naming an ad: a source id, a conversion source or a click id. */
    private static boolean isAd(JsonNode ctx) {
        return ctx.isObject() && (adText(ctx.path("sourceId")) != null || adText(ctx.path("conversionSource")) != null
                || clickId(ctx) != null);
    }

    private static String clickId(JsonNode ctx) {
        String id = adText(ctx.path("ctwaClid"));
        return id != null ? id : adText(ctx.path("ctwa_clid"));
    }

    private static Referral referral(JsonNode raw) {
        JsonNode ctx = raw.path("ctwaContext");
        if (!isAd(ctx)) return null;
        String headline = adText(ctx.path("title"));
        if (headline == null) headline = adText(ctx.path("headline"));
        String welcome = adText(ctx.path("greetingMessageBody"));
        return new Referral(adText(ctx.path("sourceUrl")), adText(ctx.path("sourceId")), "ad", headline,
                adText(ctx.path("description")), mediaType(ctx.path("mediaType")), null, null, null, clickId(ctx),
                welcome == null ? null : new Referral.WelcomeMessage(welcome));
    }

    private static String mediaType(JsonNode n) {
        return switch (n.asString("")) {
            case "1" -> "image";
            case "2" -> "video";
            default -> null;
        };
    }

    /** The context minus its images and conversion data; null when what is left is over 4 KB as JSON. */
    private static Map<String, Object> adRaw(JsonNode ctx) {
        ObjectNode kept = ((ObjectNode) ctx).deepCopy();
        kept.remove(AD_RAW_DROPPED);
        MongoKeys.dropRefused(kept);
        if (JSON.writeValueAsBytes(kept).length > AD_RAW_MAX_BYTES) return null;
        return JSON.convertValue(kept, new TypeReference<Map<String, Object>>() { });
    }

    // ---- wa_web

    private static WaWeb waWeb(JsonNode raw, String chatId, String rawType, String type, Referral referral,
            List<String> vcards, Instant capturedAt, boolean extMedia) {
        boolean media = MEDIA_TYPES.contains(rawType);
        JsonNode ctx = raw.path("ctwaContext");
        return new WaWeb(chatId, raw.path("ack").asInt(0), rawType.equals(type) ? null : rawType,
                media || extMedia ? fileSize(raw) : null, media || extMedia ? mediaAvailable(raw) : null,
                text(raw.path("hsmTag")),
                referral == null ? null : adRaw(ctx), referral == null ? null : adText(ctx.path("sourceApp")),
                referral == null ? null : adText(ctx.path("conversionSource")), vcards,
                capturedAt == null ? null : capturedAt.toEpochMilli());
    }

    // ---- small readers

    private static String adText(JsonNode n) {
        if (!n.isTextual() && !n.isNumber()) return null;
        String s = n.asString().trim();
        if (s.isEmpty()) return null;
        return s.length() > AD_TEXT_MAX ? s.substring(0, AD_TEXT_MAX) : s;
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asString().isEmpty() ? n.asString() : null;
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            String s = text(n);
            if (s != null) return s;
        }
        return null;
    }

    private static Double number(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n.isNumber()) return n.asDouble();
        }
        return null;
    }
}
