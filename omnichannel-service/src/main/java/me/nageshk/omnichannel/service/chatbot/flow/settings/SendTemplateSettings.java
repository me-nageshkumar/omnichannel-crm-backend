package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.Map;

/** One of the brand's saved templates with fixed values for its agent placeholders (variables allowed). */
public record SendTemplateSettings(String templateId, Map<String, String> values) {
}
