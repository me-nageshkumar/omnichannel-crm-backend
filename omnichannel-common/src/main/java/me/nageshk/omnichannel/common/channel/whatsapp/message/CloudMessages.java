package me.nageshk.omnichannel.common.channel.whatsapp.message;

import java.util.Map;
import me.nageshk.omnichannel.common.api.ValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Converts {@link CloudMessage}s to and from the map shape they are stored in. Parsing is strict: a key Meta does not
 * document is a {@link ValidationException} on {@code message}, so stored objects stay exactly the Cloud shape.
 */
@Component
public class CloudMessages {

    /** Meta's documented limits (developers.facebook.com, 2026-11-12); the one place to update them. */
    public static final class Limits {
        public static final int BODY_INTERACTIVE = 1024;
        public static final int BODY_LIST = 4096;
        public static final int BODY_TEXT = 4096;
        public static final int FOOTER = 60;
        public static final int HEADER_TEXT = 60;
        public static final int BUTTONS = 3;
        public static final int BUTTON_TITLE = 20;
        public static final int BUTTON_ID = 256;
        public static final int LIST_BUTTON = 20;
        public static final int SECTIONS = 10;
        public static final int ROWS = 10;
        public static final int SECTION_TITLE = 24;
        public static final int ROW_ID = 200;
        public static final int ROW_TITLE = 24;
        public static final int ROW_DESCRIPTION = 72;
        public static final int CTA_TEXT = 20;
        public static final int URL = 2000;

        private Limits() {}
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public CloudMessages(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reader = mapper.readerFor(CloudMessage.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** The message as a map with Meta's snake_case keys, nulls left out. */
    public Map<String, Object> toMap(CloudMessage message) {
        return mapper.convertValue(message, MAP);
    }

    public CloudMessage fromMap(Map<String, Object> map) {
        return parse(mapper.valueToTree(map));
    }

    /** @throws ValidationException on a key Meta does not document, as {@code message: "unknown field <name>"} */
    public CloudMessage parse(JsonNode node) {
        try {
            return reader.readValue(node);
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException("message", "unknown field " + e.getPropertyName());
        }
    }
}
