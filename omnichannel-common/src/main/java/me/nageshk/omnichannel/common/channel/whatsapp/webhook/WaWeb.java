package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What only WhatsApp Web knows of a message. {@code body} is the text a person wrote when Meta's shape has no place for
 * it ({@code unsupported}, {@code album}, an {@code order} without a title); null otherwise. {@code imported} is true on
 * a message the history import fetched, and on the status events of its receipts; absent on a live one.
 * {@code estimated} is true on an imported status whose time WhatsApp had no receipt for, so the fetch time stands in;
 * absent everywhere else.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaWeb(
        String chatId, Integer ack, String originalType, Long fileSize, Boolean mediaAvailable,
        String businessTag, Map<String, Object> ctwaContext, String ctwaSourceApp,
        String ctwaConversionSource, List<String> vcards, Long capturedAt, String body, Boolean imported,
        Boolean estimated) {

    /** Only {@code imported} set: what an imported message carries when it has nothing else of WhatsApp Web. */
    public static WaWeb importedOnly() {
        return new WaWeb(null, null, null, null, null, null, null, null, null, null, null, null, true, null);
    }

    public WaWeb withImported(Boolean imported) {
        return new WaWeb(chatId, ack, originalType, fileSize, mediaAvailable, businessTag, ctwaContext, ctwaSourceApp,
                ctwaConversionSource, vcards, capturedAt, body, imported, estimated);
    }
}
