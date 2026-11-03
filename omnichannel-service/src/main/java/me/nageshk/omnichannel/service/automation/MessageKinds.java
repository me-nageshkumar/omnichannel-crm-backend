package me.nageshk.omnichannel.service.automation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The real WhatsApp message types that can start an automation, and the kind each is shown as. */
public final class MessageKinds {

    private static final Map<String, String> KIND_BY_TYPE = Map.ofEntries(Map.entry("chat", "TEXT"),
            Map.entry("image", "PHOTO"), Map.entry("video", "VIDEO"), Map.entry("audio", "AUDIO"),
            Map.entry("ptt", "AUDIO"), Map.entry("document", "DOCUMENT"), Map.entry("sticker", "STICKER"),
            Map.entry("location", "LOCATION"), Map.entry("vcard", "CONTACT_CARD"),
            Map.entry("multi_vcard", "CONTACT_CARD"), Map.entry("poll_creation", "POLL"));

    public static final Set<String> REAL_TYPES = KIND_BY_TYPE.keySet();

    public static final List<String> KINDS = List.of("TEXT", "PHOTO", "VIDEO", "AUDIO", "DOCUMENT", "STICKER",
            "LOCATION", "CONTACT_CARD", "POLL");

    private MessageKinds() {
    }

    /** The kind of a whatsapp-web.js message type; empty for types that start nothing. */
    public static Optional<String> kindOf(String waType) {
        return Optional.ofNullable(waType == null ? null : KIND_BY_TYPE.get(waType));
    }
}
