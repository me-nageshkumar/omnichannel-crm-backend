package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.events.LoggingEventListener;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads the fields of a whatsapp-web.js message payload; every helper tolerates missing nodes. */
final class MessagePayloads {

    private static final String LOCATION_TYPE = "location";
    private static final String POLL_TYPE = "poll_creation";
    private static final String MULTI_VCARD_TYPE = "multi_vcard";
    private static final String CHANNEL_INVITE_TYPE = "newsletter_follower_invite";
    private static final String CALL_TYPE = "call_log";
    static final Set<String> NOTICE_TYPES = Set.of("notification_template", "e2e_notification", "notification",
            "group_notification", "broadcast_notification");

    private static final int AD_TEXT_MAX = 500;
    private static final int AD_RAW_MAX_BYTES = 4096;
    private static final Set<String> AD_RAW_DROPPED = Set.of("thumbnail", "thumbnailUrl", "originalImageUrl", "mediaUrl",
            "conversionData");
    private static final ObjectMapper JSON = new ObjectMapper();

    private MessagePayloads() {
    }

    /** The chat id the message belongs to, e.g. {@code 919812345678@c.us}. */
    static String remote(JsonNode p) {
        return LoggingEventListener.wid(p.path("id").path("remote"));
    }

    /** {@code id._serialized}, else rebuilt as {@code fromMe_remote_id}; empty when neither is available. */
    static String waMessageId(JsonNode p) {
        JsonNode id = p.path("id");
        String serialized = text(id.path("_serialized"));
        if (serialized != null) return serialized;
        String remote = remote(p);
        String key = text(id.path("id"));
        if (remote.isEmpty() || key == null) return "";
        return fromMe(p) + "_" + remote + "_" + key;
    }

    static boolean fromMe(JsonNode p) {
        return p.path("fromMe").asBoolean(p.path("id").path("fromMe").asBoolean(false));
    }

    static String type(JsonNode p) {
        String t = text(p.path("type"));
        return t == null ? "chat" : t;
    }

    /**
     * What the sender wrote: the caption of a media message, the question of a poll, else the body unless it is the
     * media's thumbnail.
     */
    static String body(JsonNode p) {
        String type = type(p);
        if (MULTI_VCARD_TYPE.equals(type) && p.path("vcardList").isArray()) return vcards(p.path("vcardList"));
        if (CHANNEL_INVITE_TYPE.equals(type)) return text(p.path("newsletterFollowerInviteInfo").path("newsletterName"));
        String caption = text(p.path("caption"));
        if (caption != null && !MessageBodies.isText(type)) return caption;
        String body = MessageBodies.readable(type, p.path("body").isTextual() ? p.path("body").asString() : null);
        String question = POLL_TYPE.equals(type) && (body == null || body.isEmpty()) ? text(p.path("pollName")) : null;
        return question != null ? question : body;
    }

    /** WhatsApp's tag on a business template, such as AUTHENTICATION for a verification code; else null. */
    static String businessTag(JsonNode p) {
        return text(p.path("hsmTag"));
    }

    /** The cards of a {@code multi_vcard}, one after the other, as a {@code vcard} holds its one card in its body. */
    private static String vcards(JsonNode list) {
        StringJoiner cards = new StringJoiner("\n");
        for (JsonNode card : list) {
            String vcard = text(card.path("vcard"));
            if (vcard != null) cards.add(vcard);
        }
        return cards.length() == 0 ? null : cards.toString();
    }

    /** WhatsApp's time in seconds, else the capture time of the event, else now. */
    static Instant timestamp(JsonNode p, WhatsAppEvent event) {
        return timestamp(p, event.ts() == null ? Instant.now() : Instant.ofEpochMilli(event.ts()));
    }

    /** WhatsApp's time in seconds: {@code t} on the page's raw model, {@code timestamp} elsewhere; else {@code fallback}. */
    static Instant timestamp(JsonNode p, Instant fallback) {
        long seconds = p.path("t").asLong(p.path("timestamp").asLong(0));
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : fallback;
    }

    static int ack(JsonNode p) {
        return p.path("ack").asInt(0);
    }

    /** {@code hasMedia} is whatsapp-web.js's name for a {@code directPath}, which is what the page actually sends. */
    static boolean hasMedia(JsonNode p) {
        return p.path("hasMedia").asBoolean(false) || text(p.path("directPath")) != null;
    }

    /** {@code mimetype/filename/size} from the top level, falling back to {@code _data}; null without media. */
    static WaWebMessage.Media media(JsonNode p) {
        if (!hasMedia(p)) return null;
        JsonNode data = p.path("_data");
        String mimetype = firstText(p.path("mimetype"), data.path("mimetype"));
        String filename = firstText(p.path("filename"), data.path("filename"));
        Long size = null;
        for (JsonNode n : new JsonNode[] {p.path("size"), data.path("size")}) {
            if (n.isNumber()) {
                size = n.asLong();
                break;
            }
        }
        return new WaWebMessage.Media(mimetype, filename, size, null, null);
    }

    /**
     * {@code location.latitude/longitude/description}, else the raw model's {@code lat/lng/loc} on a location message,
     * else {@code _data.lat/lng/loc}; null when absent.
     */
    static WaWebMessage.Location location(JsonNode p) {
        JsonNode loc = p.path("location");
        JsonNode data = p.path("_data");
        JsonNode raw = LOCATION_TYPE.equals(type(p)) ? p : data;
        Double lat = number(loc.path("latitude"), raw.path("lat"), data.path("lat"));
        Double lng = number(loc.path("longitude"), raw.path("lng"), data.path("lng"));
        if (lat == null || lng == null) return null;
        return new WaWebMessage.Location(lat, lng,
                firstText(loc.path("description"), raw.path("loc"), data.path("loc")));
    }

    /**
     * The choices of a poll; null on any other message or when there are none. They are raw {@code {name, localId}}
     * objects, or plain strings on a poll the console sent before it used that shape.
     */
    static List<String> pollOptions(JsonNode p) {
        if (!POLL_TYPE.equals(type(p))) return null;
        List<String> names = new ArrayList<>();
        for (JsonNode option : p.path("pollOptions")) {
            String name = text(option.isObject() ? option.path("name") : option);
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names.isEmpty() ? null : names;
    }

    /**
     * The click-to-WhatsApp ad behind an inbound message, from its {@code ctwaContext}; null for anything else: a
     * message we sent, no such object, or one naming no source url, id or conversion source.
     */
    static AdClick adClick(JsonNode p, Instant at) {
        JsonNode ctx = p.path("ctwaContext");
        if (fromMe(p) || !ctx.isObject()) return null;
        String sourceUrl = adText(ctx.path("sourceUrl"));
        String sourceId = adText(ctx.path("sourceId"));
        String conversionSource = adText(ctx.path("conversionSource"));
        if (sourceUrl == null && sourceId == null && conversionSource == null) return null;
        String headline = adText(ctx.path("title"));
        if (headline == null) headline = adText(ctx.path("headline"));
        String clickId = adText(ctx.path("ctwaClid"));
        if (clickId == null) clickId = adText(ctx.path("ctwa_clid"));
        return new AdClick(sourceId, sourceUrl, adText(ctx.path("sourceApp")), headline,
                adText(ctx.path("description")), conversionSource, clickId, at, adRaw((ObjectNode) ctx));
    }

    /** The context minus its images and conversion data; null when what is left is over 4 KB as JSON. */
    private static Map<String, Object> adRaw(ObjectNode ctx) {
        ObjectNode kept = ctx.deepCopy();
        kept.remove(AD_RAW_DROPPED);
        if (JSON.writeValueAsBytes(kept).length > AD_RAW_MAX_BYTES) return null;
        return JSON.convertValue(kept, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    private static String adText(JsonNode n) {
        if (!n.isTextual()) return null;
        String s = n.asString().trim();
        if (s.isEmpty()) return null;
        return s.length() > AD_TEXT_MAX ? s.substring(0, AD_TEXT_MAX) : s;
    }

    static String senderName(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("notifyName"), p.path("_data").path("notifyName"), p.path("pushname")}) {
            String s = n.isTextual() ? n.asString().trim() : "";
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    /** The phone behind a {@code @lid} chat when WhatsApp exposed it, digits only. */
    static String lidPhone(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("senderPn"), p.path("_data").path("senderPn"), p.path("author")}) {
            String w = LoggingEventListener.wid(n);
            if (w.endsWith("@c.us")) return w.substring(0, w.length() - "@c.us".length());
        }
        return null;
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

    /**
     * What the payload says of a call; null unless it is a {@code call_log}. A real one carries {@code isVideoCall},
     * {@code callDuration}, {@code callOutcome} and {@code callParticipants} and no {@code subtype} (seen on a phone,
     * 2026-11-02); {@code duration} is the published model's name. A call saying none of this is still a call.
     */
    static WaWebMessage.Call call(JsonNode p) {
        if (!CALL_TYPE.equals(type(p))) return null;
        boolean video = p.path("isVideoCall").asBoolean(false);
        boolean missed = lowerText(p.path("callOutcome")).contains("miss");
        int seconds = Math.max(p.path("callDuration").asInt(0), p.path("duration").asInt(0));
        return new WaWebMessage.Call(video, missed, seconds > 0 ? seconds : null);
    }

    /** The votes sent with a fetched poll; empty for anything else. */
    static List<WaWebMessage.PollVote> pollVotes(JsonNode p) {
        List<WaWebMessage.PollVote> votes = new ArrayList<>();
        p.path("pollVotes").forEach(v -> {
            WaWebMessage.PollVote vote = PollVotes.parse(v);
            if (vote != null) votes.add(vote);
        });
        return votes;
    }

    private static String lowerText(JsonNode n) {
        return n.isString() ? n.asString().toLowerCase(Locale.ROOT) : "";
    }

    /** True for a type WhatsApp uses for its own notices, such as "this chat is with a business". */
    static boolean isNoticeType(String type) {
        return type != null && NOTICE_TYPES.contains(type);
    }

    /**
     * True for WhatsApp's own notice rather than something a person sent. It has no text, as the phone draws the words
     * itself, and is not part of the conversation. By type only: this drops the row for good, so a flag on a type not
     * known here must not.
     */
    static boolean isNotice(JsonNode p) {
        return isNoticeType(type(p));
    }
}
