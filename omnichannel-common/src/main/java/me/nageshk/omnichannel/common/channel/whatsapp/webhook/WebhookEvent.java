package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;

import me.nageshk.omnichannel.common.channel.ChannelContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEvent(String object, List<Entry> entry) {

    public static final String WHATSAPP_BUSINESS_ACCOUNT = "whatsapp_business_account";

    /** One entry whose id is the channel account (phone number) id. */
    public static WebhookEvent of(ChannelContext ctx, List<Change> changes) {
        return new WebhookEvent(
                WHATSAPP_BUSINESS_ACCOUNT, List.of(new Entry(ctx.channelAccountId(), changes, null)));
    }
}
