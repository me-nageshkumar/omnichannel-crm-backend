package me.nageshk.omnichannel.service.chatbot.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;

/** Field-by-field merge and checks of chatbot settings layers (spec 2026-11-14 §3–§4). */
public final class SettingsLayers {

    public static final List<String> FIELDS = List.of("reminderOn", "reminderAfterMinutes", "reminderMessage",
            "endAfterMinutes", "endMessage", "invalidReplyRetries", "retryMessage", "giveUpMessage", "stopOnAgentReply");

    private static final int MAX_TEXT = 1024;

    private static final Map<String, Function<ChatbotSettingsLayer, Object>> GET = getters();

    private SettingsLayers() {
    }

    private static Map<String, Function<ChatbotSettingsLayer, Object>> getters() {
        Map<String, Function<ChatbotSettingsLayer, Object>> m = new LinkedHashMap<>();
        m.put("reminderOn", ChatbotSettingsLayer::reminderOn);
        m.put("reminderAfterMinutes", ChatbotSettingsLayer::reminderAfterMinutes);
        m.put("reminderMessage", ChatbotSettingsLayer::reminderMessage);
        m.put("endAfterMinutes", ChatbotSettingsLayer::endAfterMinutes);
        m.put("endMessage", ChatbotSettingsLayer::endMessage);
        m.put("invalidReplyRetries", ChatbotSettingsLayer::invalidReplyRetries);
        m.put("retryMessage", ChatbotSettingsLayer::retryMessage);
        m.put("giveUpMessage", ChatbotSettingsLayer::giveUpMessage);
        m.put("stopOnAgentReply", ChatbotSettingsLayer::stopOnAgentReply);
        return m;
    }

    private static ChatbotSettingsLayer orEmpty(ChatbotSettingsLayer l) {
        return l == null ? ChatbotSettingsLayer.empty() : l;
    }

    private static <T> T pick(T top, T below) {
        return top != null ? top : below;
    }

    /** {@code top}'s value for each field it sets, else {@code below}'s. Either may be null. */
    public static ChatbotSettingsLayer over(ChatbotSettingsLayer top, ChatbotSettingsLayer below) {
        ChatbotSettingsLayer t = orEmpty(top);
        ChatbotSettingsLayer b = orEmpty(below);
        return new ChatbotSettingsLayer(pick(t.reminderOn(), b.reminderOn()),
                pick(t.reminderAfterMinutes(), b.reminderAfterMinutes()), pick(t.reminderMessage(), b.reminderMessage()),
                pick(t.endAfterMinutes(), b.endAfterMinutes()), pick(t.endMessage(), b.endMessage()),
                pick(t.invalidReplyRetries(), b.invalidReplyRetries()), pick(t.retryMessage(), b.retryMessage()),
                pick(t.giveUpMessage(), b.giveUpMessage()), pick(t.stopOnAgentReply(), b.stopOnAgentReply()));
    }

    /** The settings a run uses, from a complete layer; a blank optional message becomes {@code null} (none). */
    public static ChatbotSettings resolve(ChatbotSettingsLayer complete) {
        ChatbotSettingsLayer c = orEmpty(complete);
        for (String f : FIELDS) {
            if (!f.equals("endMessage") && !f.equals("giveUpMessage") && GET.get(f).apply(c) == null) {
                throw new IllegalArgumentException("settings layer is missing " + f);
            }
        }
        return new ChatbotSettings(c.reminderOn(), c.reminderAfterMinutes(), c.reminderMessage(), c.endAfterMinutes(),
                none(c.endMessage()), c.invalidReplyRetries(), c.retryMessage(), none(c.giveUpMessage()),
                c.stopOnAgentReply());
    }

    private static String none(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Per field: CHATBOT if the chatbot sets it, else BRAND if the brand does, else PLATFORM. */
    public static Map<String, SettingSource> sources(ChatbotSettingsLayer chatbot, ChatbotSettingsLayer brand) {
        ChatbotSettingsLayer c = orEmpty(chatbot);
        ChatbotSettingsLayer b = orEmpty(brand);
        Map<String, SettingSource> out = new LinkedHashMap<>();
        for (String f : FIELDS) {
            out.put(f, GET.get(f).apply(c) != null ? SettingSource.CHATBOT
                    : GET.get(f).apply(b) != null ? SettingSource.BRAND : SettingSource.PLATFORM);
        }
        return out;
    }

    /**
     * The layer, normalised (optional messages trimmed, whitespace-only means none), or a {@link ValidationException}
     * keyed {@code prefix.field}. {@code complete} (the platform layer) also requires every field but the optional
     * messages.
     */
    public static ChatbotSettingsLayer check(ChatbotSettingsLayer layer, String prefix, boolean complete) {
        ChatbotSettingsLayer l = orEmpty(layer);
        Map<String, String> errors = new LinkedHashMap<>();
        range(errors, prefix, "reminderAfterMinutes", l.reminderAfterMinutes(), 1, 1440, "must be 1 to 1440 minutes");
        range(errors, prefix, "endAfterMinutes", l.endAfterMinutes(), 5, 2880, "must be 5 to 2880 minutes");
        range(errors, prefix, "invalidReplyRetries", l.invalidReplyRetries(), 0, 5, "must be 0 to 5");
        required(errors, prefix, "reminderMessage", l.reminderMessage());
        required(errors, prefix, "retryMessage", l.retryMessage());
        String endMessage = l.endMessage() == null ? null : l.endMessage().trim();
        String giveUpMessage = l.giveUpMessage() == null ? null : l.giveUpMessage().trim();
        length(errors, prefix, "reminderMessage", l.reminderMessage());
        length(errors, prefix, "endMessage", endMessage);
        length(errors, prefix, "retryMessage", l.retryMessage());
        length(errors, prefix, "giveUpMessage", giveUpMessage);
        if (complete) {
            for (String f : FIELDS) {
                if (f.equals("endMessage") || f.equals("giveUpMessage")) continue;
                if (GET.get(f).apply(l) == null) errors.putIfAbsent(prefix + "." + f, "is required");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new ChatbotSettingsLayer(l.reminderOn(), l.reminderAfterMinutes(), l.reminderMessage(),
                l.endAfterMinutes(), endMessage, l.invalidReplyRetries(), l.retryMessage(), giveUpMessage,
                l.stopOnAgentReply());
    }

    private static void range(Map<String, String> errors, String prefix, String field, Integer v, int lo, int hi,
            String message) {
        if (v != null && (v < lo || v > hi)) errors.put(prefix + "." + field, message);
    }

    private static void required(Map<String, String> errors, String prefix, String field, String v) {
        if (v != null && v.isBlank()) errors.put(prefix + "." + field, "is required");
    }

    private static void length(Map<String, String> errors, String prefix, String field, String v) {
        if (v != null && v.length() > MAX_TEXT) errors.putIfAbsent(prefix + "." + field, "must be at most 1024 characters");
    }
}
