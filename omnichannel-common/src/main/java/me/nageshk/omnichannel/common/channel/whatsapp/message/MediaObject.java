package me.nageshk.omnichannel.common.channel.whatsapp.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Meta's media object for {@code image}, {@code video}, {@code audio} and {@code document} messages: an uploaded
 * media {@code id} (or a {@code link}, which this project refuses), an optional {@code caption} (not for audio) and,
 * for documents, a {@code filename}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaObject(String id, String link, String caption, String filename) {}
