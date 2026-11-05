package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaWeb(
        String chatId, Integer ack, String originalType, Long fileSize, Boolean mediaAvailable,
        String businessTag, Map<String, Object> ctwaContext, String ctwaSourceApp,
        String ctwaConversionSource, List<String> vcards, Long capturedAt) {}
