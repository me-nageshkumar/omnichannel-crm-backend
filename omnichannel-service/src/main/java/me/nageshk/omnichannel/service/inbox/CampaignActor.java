package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.MessageSource;

/** A campaign sending one of its messages: no user, shown as {@code "Campaign: <name>"}. */
public record CampaignActor(String campaignId, String campaignName) implements SendActor {

    @Override
    public String userId() {
        return null;
    }

    @Override
    public String displayName() {
        return "Campaign: " + campaignName;
    }

    @Override
    public MessageSource source() {
        return MessageSource.CAMPAIGN;
    }
}
