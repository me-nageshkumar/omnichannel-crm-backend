package me.nageshk.omnichannel.common.channel.whatsapp.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The Cloud API's answer to {@code GET /media/{id}}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudMediaInfo(String messagingProduct, String url, String mimeType, String sha256, long fileSize,
        String id) {}
