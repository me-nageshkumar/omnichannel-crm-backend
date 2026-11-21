package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Meta's {@code context} of a message: the quoted message ({@code from}, {@code id}) and the forward flags.
 * {@code waWeb} is ours, from the WhatsApp Web bridge only: the quoted message's own type and text, which WhatsApp Web
 * hands over with the reply (Meta's Cloud API does not).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Context(String from, String id, Boolean forwarded, Boolean frequentlyForwarded, WaWeb waWeb) {

    public Context(String from, String id, Boolean forwarded, Boolean frequentlyForwarded) {
        this(from, id, forwarded, frequentlyForwarded, null);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WaWeb(QuotedPreview quoted) {}

    /** The quoted message as WhatsApp Web saw it: Meta's type name and its text or caption. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuotedPreview(String type, String body) {}
}
