package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;

/** Told when a campaign message went out (spec D C13); implemented in the application module, never fails a send. */
public interface CampaignObserver {

    /** One sent message: the campaign, its segment when it has one, the host contact and the channel account used. */
    record CampaignSent(String workspaceId, String campaignId, String campaignName, String segmentId,
            String segmentName, String contactId, String channelAccountId, Instant sentAt) {}

    void sent(CampaignSent event);
}
