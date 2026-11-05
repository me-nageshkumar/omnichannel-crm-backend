package me.nageshk.omnichannel.service.channel.whatsapp;

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
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Referral;
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

    private static final String CHANGE_FIELD = "messages";
    private static final Set<String> NOTICE_TYPES = Set.of("notification_template", "e2e_notification", "notification",
            "group_notification", "broadcast_notification");
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video", "audio", "ptt", "document", "sticker");
    private static final int FREQUENTLY_FORWARDED = 5;
    private static final int AD_TEXT_MAX = 500;
    private static final int AD_RAW_MAX_BYTES = 4096;
    private static final Set<String> AD_RAW_DROPPED = Set.of("thumbnail", "thumbnailUrl", "originalImageUrl", "mediaUrl",
            "conversionData");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One event for a {@code message_create} of a person's message; empty for any other event or message. */
    public Optional<WebhookEvent> convert(ChannelContext ctx, String eventName, JsonNode payload, Instant capturedAt) {
        if (!"message_create".equals(eventName) || payload == null) return Optional.empty();
        return message(ctx, payload, capturedAt).map(m -> {
            Value value = Value.of(ctx).withContacts(List.of(contactOf(m, payload))).withMessages(List.of(m));
            return WebhookEvent.of(ctx, List.of(new Change(CHANGE_FIELD, value)));
        });
    }

    /** An inbound message; empty for ours, for a notice, a chat of no person and a message with no id. */
    public Optional<Message> message(ChannelContext ctx, JsonNode raw, Instant fallbackTime) {
        if (fromMe(raw)) return Optional.empty();
        String rawType = rawType(raw);
        if (NOTICE_TYPES.contains(rawType)) return Optional.empty();
        String chatId = chatIdOf(raw);
        if (!isPersonChat(chatId)) return Optional.empty();
        String id = WaIds.messageId(raw.path("id"));
        if (id.isEmpty()) return Optional.empty();
        String type = cloudType(rawType);
        if (type == null) return Optional.empty();

        String user = WaIds.user(chatId);
        boolean lid = chatId.endsWith("@lid");
        String from = lid ? lidPhone(raw) : user;
        String fromUserId = lid ? user : null;

        List<String> vcards = "contacts".equals(type) ? vcardTexts(raw) : null;
        Text text = "text".equals(type) ? new Text(body(raw, rawType)) : null;
        Media media = MEDIA_TYPES.contains(rawType) ? media(raw, id, rawType) : null;
        Referral referral = referral(raw);
        Message m = new Message(from, fromUserId, id, timestamp(raw, fallbackTime), type, context(raw), referral, null,
                text, "image".equals(type) ? media : null, "video".equals(type) ? media : null,
                "audio".equals(type) ? media : null, "document".equals(type) ? media : null,
                "sticker".equals(type) ? media : null, "location".equals(type) ? location(raw) : null,
                vcards == null ? null : VcardCards.parse(vcards), null, null, null, null, null, null, null, null, null,
                null, null, waWeb(raw, chatId, rawType, type, referral, vcards, fallbackTime));
        return Optional.of(m);
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
        dropMongoKeys(kept);
        if (JSON.writeValueAsBytes(kept).length > AD_RAW_MAX_BYTES) return null;
        return JSON.convertValue(kept, new TypeReference<Map<String, Object>>() { });
    }

    /** Removes, at any depth, the keys Mongo refuses to store: those with a dot or a leading dollar sign. */
    private static void dropMongoKeys(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).propertyNames().stream().filter(k -> k.contains(".") || k.startsWith("$")).toList()
                    .forEach(((ObjectNode) node)::remove);
        }
        node.forEach(WaWebWebhookConverter::dropMongoKeys);
    }

    // ---- wa_web

    private static WaWeb waWeb(JsonNode raw, String chatId, String rawType, String type, Referral referral,
            List<String> vcards, Instant capturedAt) {
        boolean media = MEDIA_TYPES.contains(rawType);
        JsonNode ctx = raw.path("ctwaContext");
        return new WaWeb(chatId, raw.path("ack").asInt(0), rawType.equals(type) ? null : rawType,
                media ? fileSize(raw) : null, media ? mediaAvailable(raw) : null, text(raw.path("hsmTag")),
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
