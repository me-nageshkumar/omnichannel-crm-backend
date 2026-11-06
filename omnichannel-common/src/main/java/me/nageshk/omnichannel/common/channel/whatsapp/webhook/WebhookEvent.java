package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;

import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEvent(String object, List<Entry> entry) {

    public static final String WHATSAPP_BUSINESS_ACCOUNT = "whatsapp_business_account";

    public static final String WHATSAPP_WEB_ACCOUNT = "whatsapp_web_account";

    /**
     * One entry whose id is the workspace (the brand, the analogue of Meta's business account); {@code object} says
     * where the event came from.
     */
    public static WebhookEvent of(ChannelContext ctx, List<Change> changes) {
        String object = ctx.kind() == ChannelKind.WHATSAPP_CLOUD ? WHATSAPP_BUSINESS_ACCOUNT : WHATSAPP_WEB_ACCOUNT;
        return new WebhookEvent(object, List.of(new Entry(ctx.workspaceId(), changes, null)));
    }
}
