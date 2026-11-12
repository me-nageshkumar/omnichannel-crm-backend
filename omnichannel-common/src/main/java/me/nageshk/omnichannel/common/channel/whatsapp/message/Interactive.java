package me.nageshk.omnichannel.common.channel.whatsapp.message;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Cloud API interactive message object (reply buttons, list, call-to-action URL, location request), field for field
 * as Meta documents it. Unknown keys are rejected when parsed through {@link CloudMessages}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Interactive(String type, Header header, Body body, Footer footer, Action action) {

    public static final String BUTTON = "button";
    public static final String LIST = "list";
    public static final String CTA_URL = "cta_url";
    public static final String LOCATION_REQUEST = "location_request_message";
    public static final Set<String> INTERACTIVE_TYPES = Set.of(BUTTON, LIST, CTA_URL, LOCATION_REQUEST);

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Header(String type, String text, MediaRef image, MediaRef video, MediaRef document) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaRef(String id, String link) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String text) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Footer(String text) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(List<ActionButton> buttons, String button, List<Section> sections, String name,
            CtaParameters parameters) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionButton(String type, Reply reply) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String id, String title) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section(String title, List<Row> rows) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(String id, String title, String description) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CtaParameters(String displayText, String url) {}
}
