package me.nageshk.omnichannel.crm.platform;

import java.time.Instant;
import java.util.List;

/** The brand's campaigns, read for the campaigns report. */
public interface CampaignDirectory {

    /**
     * Campaigns of the brand started in {@code [from, to)}, newest first, at most {@code limit}; the caller passes
     * one more than it shows to learn whether the list was cut.
     */
    List<PlatformCampaign> campaigns(String workspaceId, Instant from, Instant to, int limit);
}
