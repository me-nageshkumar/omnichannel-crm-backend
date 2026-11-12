package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.List;

/** Spec §4.2 Ask with a form: {@code linkMessage} carries {@code {{form.url}}} and goes out over WhatsApp. */
public record AskFormSettings(String title, String intro, List<FormField> fields, String submitText, String thanksText,
        String linkMessage) {
}
