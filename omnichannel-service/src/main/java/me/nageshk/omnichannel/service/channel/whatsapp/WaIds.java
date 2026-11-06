package me.nageshk.omnichannel.service.channel.whatsapp;

import tools.jackson.databind.JsonNode;

/** Reads WhatsApp Web's wids and message keys in the forms the page and whatsapp-web.js send them. */
public final class WaIds {

    private WaIds() {
    }

    /**
     * A wid as {@code user@server}: a plain string, an object with {@code _serialized} or {@code $1}, else rebuilt from
     * {@code user} and {@code server}; empty when absent.
     */
    public static String wid(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asString();
        if (node.isObject()) {
            String s = text(node.get("_serialized"));
            if (s != null) return s;
            s = text(node.get("$1"));
            if (s != null) return s;
            JsonNode user = node.get("user");
            JsonNode server = node.get("server");
            if (user != null && server != null) return user.asString() + "@" + server.asString();
        }
        return node.toString();
    }

    /** A message key: {@code _serialized}, else {@code $1}, else rebuilt as {@code fromMe_remote_id}; empty when none. */
    public static String messageId(JsonNode id) {
        if (id == null || !id.isObject()) return "";
        String s = text(id.get("_serialized"));
        if (s != null) return s;
        s = text(id.get("$1"));
        if (s != null) return s;
        String remote = wid(id.get("remote"));
        if (remote.isEmpty() || !id.hasNonNull("id")) return "";
        return id.path("fromMe").asBoolean(false) + "_" + remote + "_" + id.path("id").asString();
    }

    /** The part of a wid before the {@code @}. */
    public static String user(String wid) {
        int at = wid.indexOf('@');
        return at < 0 ? wid : wid.substring(0, at);
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() && !n.asString().isEmpty() ? n.asString() : null;
    }
}
