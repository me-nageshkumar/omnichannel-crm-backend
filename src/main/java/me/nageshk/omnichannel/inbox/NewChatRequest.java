package me.nageshk.omnichannel.inbox;

import java.util.Map;

/** Starts a chat with a phone number: the number in any common format, then exactly one of {@code text} and {@code templateId} as in {@link SendRequest}. */
public record NewChatRequest(String phone, String text, String templateId, Map<String, String> values) {
}
