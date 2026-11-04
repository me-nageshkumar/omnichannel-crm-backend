package me.nageshk.omnichannel.db.inbox;

import java.time.Instant;
import java.util.Map;

/**
 * The click-to-WhatsApp ad a person tapped before writing, as read from the {@code ctwaContext} of their message.
 * {@code raw} is that context without its images and conversion data, null when it was too big to keep.
 */
public record AdClick(String sourceId, String sourceUrl, String sourceApp, String headline, String description,
        String conversionSource, String clickId, Instant at, Map<String, Object> raw) {
}
