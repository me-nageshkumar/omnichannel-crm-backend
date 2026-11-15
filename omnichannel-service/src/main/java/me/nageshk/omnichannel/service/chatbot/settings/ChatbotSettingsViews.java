package me.nageshk.omnichannel.service.chatbot.settings;

import java.time.Instant;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;

/** Wire shapes of the platform and brand chatbot settings (spec 2026-11-14 §7). */
public final class ChatbotSettingsViews {

    private ChatbotSettingsViews() {}

    /** The platform defaults: every field filled. {@code version} is null until the super admin first saves. */
    public record PlatformView(ChatbotSettingsLayer settings, Long version, Instant lastUpdateTime, String updatedBy) {
    }

    /** A brand's own layer and the platform value of every field beneath it. */
    public record BrandView(ChatbotSettingsLayer own, ChatbotSettingsLayer inherited, Long version) {
    }

    public record PlatformSave(ChatbotSettingsLayer settings, Long version) {
    }

    public record BrandSave(ChatbotSettingsLayer own, Long version) {
    }
}
