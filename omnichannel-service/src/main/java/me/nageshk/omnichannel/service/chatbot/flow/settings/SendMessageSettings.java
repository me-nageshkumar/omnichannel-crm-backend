package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.Map;

/** A WhatsApp Cloud API message object (snake_case inside), without {@code to} or {@code messaging_product}. */
public record SendMessageSettings(Map<String, Object> message) {
}
