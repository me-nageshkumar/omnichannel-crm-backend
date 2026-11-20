package me.nageshk.omnichannel.service.inbox;

import java.util.Map;

/**
 * Starts a chat with a phone number: the number in any common format, then exactly one of {@code text} and
 * {@code templateId} as in {@link SendRequest}; on a Cloud API number, a Meta template ({@code cloudTemplate}) instead.
 */
public record NewChatRequest(String phone, String text, String templateId, Map<String, String> values,
        CloudTemplateRequest cloudTemplate) {

    public NewChatRequest(String phone, String text, String templateId, Map<String, String> values) {
        this(phone, text, templateId, values, null);
    }
}
