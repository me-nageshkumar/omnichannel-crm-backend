package me.nageshk.omnichannel.inbox;

import java.util.Map;

/** A reply from the console: exactly one of {@code text} and {@code templateId}; {@code values} fill the template's agent placeholders. */
public record SendRequest(String text, String templateId, Map<String, String> values) {
}
