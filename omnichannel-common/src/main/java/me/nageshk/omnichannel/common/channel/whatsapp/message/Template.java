package me.nageshk.omnichannel.common.channel.whatsapp.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** The {@code template} object of a Cloud API {@code /messages} request, Meta's names exactly (a Meta template). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Template(String name, Language language, List<Component> components) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Language(String code) {}

    /** {@code type} header | body | button; a button also has {@code sub_type} and {@code index}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Component(String type, String subType, String index, List<Parameter> parameters) {}

    /** {@code type} text | image | video | document | payload | coupon_code. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameter(String type, String text, String parameterName, MediaObject image, MediaObject video,
            MediaObject document, String payload, String couponCode) {

        public static Parameter text(String text, String parameterName) {
            return new Parameter("text", text, parameterName, null, null, null, null, null);
        }
    }
}
