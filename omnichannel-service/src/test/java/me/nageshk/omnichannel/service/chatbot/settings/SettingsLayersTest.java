package me.nageshk.omnichannel.service.chatbot.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;
import org.junit.jupiter.api.Test;

class SettingsLayersTest {

    private static final ChatbotSettingsLayer PLATFORM = ChatbotSettings.codeDefaults().asLayer();

    private static ChatbotSettingsLayer layer(Map<String, Object> values) {
        return new ChatbotSettingsLayer((Boolean) values.get("reminderOn"), (Integer) values.get("reminderAfterMinutes"),
                (String) values.get("reminderMessage"), (Integer) values.get("endAfterMinutes"),
                (String) values.get("endMessage"), (Integer) values.get("invalidReplyRetries"),
                (String) values.get("retryMessage"), (String) values.get("giveUpMessage"),
                (Boolean) values.get("stopOnAgentReply"));
    }

    private static ChatbotSettings resolve(ChatbotSettingsLayer chatbot, ChatbotSettingsLayer brand,
            ChatbotSettingsLayer platform) {
        return SettingsLayers.resolve(SettingsLayers.over(chatbot, SettingsLayers.over(brand, platform)));
    }

    private static Map<String, String> errors(ChatbotSettingsLayer l, String prefix, boolean complete) {
        try {
            SettingsLayers.check(l, prefix, complete);
        } catch (ValidationException e) {
            return e.fields();
        }
        return Map.of();
    }

    @Test
    void chatbotWinsOverBrandWinsOverPlatform() {
        ChatbotSettingsLayer brand = layer(Map.of("endAfterMinutes", 60, "retryMessage", "B"));
        ChatbotSettingsLayer chatbot = layer(Map.of("endAfterMinutes", 30));

        ChatbotSettings s = resolve(chatbot, brand, PLATFORM);

        assertThat(s.endAfterMinutes()).isEqualTo(30);
        assertThat(s.retryMessage()).isEqualTo("B");
        assertThat(s.invalidReplyRetries()).isEqualTo(2);
        Map<String, SettingSource> sources = SettingsLayers.sources(chatbot, brand);
        assertThat(sources.get("endAfterMinutes")).isEqualTo(SettingSource.CHATBOT);
        assertThat(sources.get("retryMessage")).isEqualTo(SettingSource.BRAND);
        assertThat(sources.get("invalidReplyRetries")).isEqualTo(SettingSource.PLATFORM);
        assertThat(sources).hasSize(9);
    }

    @Test
    void noneAtChatbotBeatsBrandMessage() {
        ChatbotSettings s = resolve(layer(Map.of("endMessage", "")), layer(Map.of("endMessage", "Bye")), PLATFORM);
        assertThat(s.endMessage()).isNull();
    }

    @Test
    void nullInheritsNone() {
        assertThat(resolve(ChatbotSettingsLayer.empty(), layer(Map.of("endMessage", "")),
                SettingsLayers.over(layer(Map.of("endMessage", "P")), PLATFORM)).endMessage()).isNull();
        assertThat(resolve(ChatbotSettingsLayer.empty(), ChatbotSettingsLayer.empty(),
                SettingsLayers.over(layer(Map.of("endMessage", "P")), PLATFORM)).endMessage()).isEqualTo("P");
        assertThat(resolve(null, null, PLATFORM).endMessage()).isNull();
    }

    @Test
    void blankOptionalMessageMeansNone() {
        ChatbotSettingsLayer checked = SettingsLayers.check(layer(Map.of("endMessage", "   ", "giveUpMessage", " Bye ")),
                "own", false);
        assertThat(checked.endMessage()).isEqualTo("");
        assertThat(checked.giveUpMessage()).isEqualTo("Bye");
    }

    @Test
    void rangesAreCheckedPerField() {
        Map<String, String> e = errors(layer(Map.of("reminderAfterMinutes", 0, "endAfterMinutes", 4,
                "invalidReplyRetries", 6, "giveUpMessage", "x".repeat(1025))), "own", false);

        assertThat(e).containsEntry("own.reminderAfterMinutes", "must be 1 to 1440 minutes")
                .containsEntry("own.endAfterMinutes", "must be 5 to 2880 minutes")
                .containsEntry("own.invalidReplyRetries", "must be 0 to 5")
                .containsEntry("own.giveUpMessage", "must be at most 1024 characters");
        assertThat(errors(layer(Map.of("reminderAfterMinutes", 1441, "endAfterMinutes", 2881, "invalidReplyRetries", -1)),
                "own", false)).hasSize(3);
        assertThat(errors(layer(Map.of("reminderAfterMinutes", 1440, "endAfterMinutes", 5, "invalidReplyRetries", 0)),
                "own", false)).isEmpty();
    }

    @Test
    void requiredMessagesCannotBeBlank() {
        Map<String, String> e = errors(layer(Map.of("retryMessage", "", "reminderMessage", " ")), "settings", false);
        assertThat(e).containsEntry("settings.retryMessage", "is required")
                .containsEntry("settings.reminderMessage", "is required");
    }

    @Test
    void completeLayerNeedsEveryRequiredField() {
        Map<String, String> e = errors(ChatbotSettingsLayer.empty(), "settings", true);
        assertThat(e).containsOnlyKeys("settings.reminderOn", "settings.reminderAfterMinutes",
                "settings.reminderMessage", "settings.endAfterMinutes", "settings.invalidReplyRetries",
                "settings.retryMessage", "settings.stopOnAgentReply");
        assertThat(errors(PLATFORM, "settings", true)).isEmpty();
    }

    @Test
    void reminderBeforeEndIsNotASaveRule() {
        assertThat(errors(layer(Map.of("reminderOn", true, "reminderAfterMinutes", 200, "endAfterMinutes", 100)),
                "own", false)).isEmpty();
    }

    @Test
    void codeDefaultsMatchTheSpec() {
        ChatbotSettings d = ChatbotSettings.codeDefaults();
        assertThat(d.reminderOn()).isFalse();
        assertThat(d.reminderAfterMinutes()).isEqualTo(30);
        assertThat(d.reminderMessage()).isEqualTo("Are you still there? Reply to carry on.");
        assertThat(d.endAfterMinutes()).isEqualTo(120);
        assertThat(d.endMessage()).isNull();
        assertThat(d.invalidReplyRetries()).isEqualTo(2);
        assertThat(d.retryMessage()).isEqualTo("Sorry, I didn't get that. Please choose one of the options.");
        assertThat(d.giveUpMessage()).isNull();
        assertThat(d.stopOnAgentReply()).isTrue();
        assertThat(PLATFORM.endMessage()).isEqualTo("");
        assertThat(SettingsLayers.FIELDS).containsExactly("reminderOn", "reminderAfterMinutes", "reminderMessage",
                "endAfterMinutes", "endMessage", "invalidReplyRetries", "retryMessage", "giveUpMessage",
                "stopOnAgentReply");
    }

    @Test
    void resolveRefusesAnIncompleteLayer() {
        assertThatThrownBy(() -> SettingsLayers.resolve(ChatbotSettingsLayer.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
