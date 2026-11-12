package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.Map;

public record HttpRequestSettings(String method, String url, Map<String, String> headers, String body,
        String contentType) {
}
