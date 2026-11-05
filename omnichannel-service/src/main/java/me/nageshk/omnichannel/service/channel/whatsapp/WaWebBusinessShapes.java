package me.nageshk.omnichannel.service.channel.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Button;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Media;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Order;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Business messages (interactive, buttons, lists, orders) and the replies to them, in Meta's shapes. Pure. */
final class WaWebBusinessShapes {

    static final Set<String> INTERACTIVE_TYPES = Set.of("interactive", "list", "buttons", "hsm", "template", "native_flow");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WaWebBusinessShapes() {
    }

    /** The interactive object and whether it carries a media header the page can download. */
    record Built(Interactive interactive, boolean media) {}

    static Built interactive(JsonNode raw, String id) {
        JsonNode header = raw.path("interactiveHeader");
        boolean hasMedia = header.path("hasMediaAttachment").asBoolean(false);
        Interactive.Header h = hasMedia ? mediaHeader(raw, header, id) : textHeader(raw);
        String bodyText = hasMedia ? text(raw.path("caption")) : text(raw.path("body"));
        String footer = text(raw.path("footer"));
        List<Interactive.ActionButton> buttons = buttons(raw.path("interactivePayload").path("buttons"));
        return new Built(new Interactive(interactiveType(raw), h, bodyText == null ? null : new Interactive.Body(bodyText),
                footer == null ? null : new Interactive.Footer(footer),
                buttons.isEmpty() ? null : new Interactive.Action(null, null, buttons, null), null, null, null),
                h != null && h.type() != null && !"text".equals(h.type()));
    }

    private static String interactiveType(JsonNode raw) {
        return switch (raw.path("nativeFlowName").asString("")) {
            case "quick_reply" -> "button";
            case "cta_url" -> "cta_url";
            case "cta_copy" -> "cta_copy";
            default -> "list";
        };
    }

    private static Interactive.Header textHeader(JsonNode raw) {
        String title = text(raw.path("title"));
        return title == null ? null : new Interactive.Header("text", title, null, null, null);
    }

    private static Interactive.Header mediaHeader(JsonNode raw, JsonNode header, String id) {
        String mime = text(raw.path("mimetype"));
        String kind = switch (header.path("mediaType").asString("").toUpperCase()) {
            case "IMAGE" -> "image";
            case "VIDEO" -> "video";
            case "DOCUMENT" -> "document";
            default -> mime == null ? null : mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "document";
        };
        if (kind == null) return null;
        Media media = new Media(id, mime, text(raw.path("filehash")), null, text(raw.path("filename")), null, null);
        return new Interactive.Header(kind, null, "image".equals(kind) ? media : null,
                "video".equals(kind) ? media : null, "document".equals(kind) ? media : null);
    }

    /** One reply button per entry that has a display text; the url of a cta_url button has no place in Meta's shape. */
    private static List<Interactive.ActionButton> buttons(JsonNode payload) {
        List<Interactive.ActionButton> out = new ArrayList<>();
        for (JsonNode b : payload) {
            JsonNode params = params(b.path("buttonParamsJson"));
            String title = text(params.path("display_text"));
            if (title == null) continue;
            String name = text(b.path("name"));
            out.add(new Interactive.ActionButton(name == null || "quick_reply".equals(name) ? "reply" : name,
                    new Interactive.Reply(text(params.path("id")), title)));
        }
        return out;
    }

    private static JsonNode params(JsonNode json) {
        if (!json.isTextual()) return json;
        try {
            return JSON.readTree(json.asString());
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    /** A customer's tap on a button: {@code selectedButtonId} and the label as the body. */
    static Button buttonReply(JsonNode raw) {
        return new Button(text(raw.path("selectedButtonId")), text(raw.path("body")));
    }

    static Interactive listReply(JsonNode raw) {
        String id = text(raw.path("listResponse").path("singleSelectReply").path("selectedRowId"));
        return new Interactive("list_reply", null, null, null, null,
                new Interactive.ListReply(id, text(raw.path("body")), null), null, null);
    }

    /** Null when the order names neither an id nor a title. */
    static Order order(JsonNode raw) {
        String title = text(raw.path("orderTitle"));
        if (title == null && text(raw.path("orderId")) == null) return null;
        return new Order(null, title, null);
    }

    /** The first non-empty string under a key containing "text", at any depth; null when none. */
    static String richText(JsonNode node) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                if (e.getKey().toLowerCase().contains("text") && e.getValue().isTextual()
                        && !e.getValue().asString().isBlank()) return e.getValue().asString();
            }
            for (var e : node.properties()) {
                String s = richText(e.getValue());
                if (s != null) return s;
            }
        } else if (node.isArray()) {
            for (JsonNode n : node) {
                String s = richText(n);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asString().isEmpty() ? n.asString() : null;
    }
}
