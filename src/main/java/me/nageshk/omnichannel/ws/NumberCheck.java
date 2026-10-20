package me.nageshk.omnichannel.ws;

import tools.jackson.databind.JsonNode;
import me.nageshk.omnichannel.events.LoggingEventListener;

/**
 * Whether the account logged in to WhatsApp Web is the onboarded number. The page reports
 * {@code pnWid} (the phone-number id) when it can, and {@code wid}, which may be a privacy id
 * ({@code …@lid}) that never confirms a number.
 */
public record NumberCheck(Result result, String reported) {

    public enum Result { MATCH, MISMATCH, UNKNOWN }

    private static final String PHONE_SERVER = "@c.us";

    public static NumberCheck check(String number, JsonNode info) {
        if (info == null || !info.isObject()) return unknown();
        String reported = phoneDigits(info.get("pnWid"));
        if (reported == null) reported = phoneDigits(info.get("wid"));
        if (reported == null) return unknown();
        return new NumberCheck(reported.equals(number) ? Result.MATCH : Result.MISMATCH, reported);
    }

    private static String phoneDigits(JsonNode node) {
        String wid = LoggingEventListener.wid(node);
        if (!wid.endsWith(PHONE_SERVER)) return null;
        String digits = wid.substring(0, wid.length() - PHONE_SERVER.length());
        int device = digits.indexOf(':');
        if (device >= 0) digits = digits.substring(0, device);
        return digits.isEmpty() ? null : digits;
    }

    private static NumberCheck unknown() {
        return new NumberCheck(Result.UNKNOWN, null);
    }
}
