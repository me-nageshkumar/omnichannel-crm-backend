package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.List;

public record FormField(String key, String label, FormFieldType type, boolean required, List<String> options) {
}
