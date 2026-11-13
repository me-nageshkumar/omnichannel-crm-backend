package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.List;
import java.util.Map;

public record UpdateContactSettings(Map<String, String> setAttributes, List<String> clearAttributes,
        List<String> addLabels, List<String> removeLabels, String nameIfBlank) {
}
