package me.nageshk.omnichannel.crm.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The brand's campaigns, read for the campaigns report. */
public interface CampaignDirectory {

    /** Every read runs under this limit; a timeout surfaces as the driver's (possibly wrapped) timeout exception. */
    Duration MAX_TIME = Duration.ofSeconds(10);

    /**
     * Campaigns of the brand started in {@code [from, to)}, newest first, at most {@code limit}; the caller passes
     * one more than it shows to learn whether the list was cut.
     */
    List<PlatformCampaign> campaigns(String workspaceId, Instant from, Instant to, int limit);

    /** The brand's unfinished (draft, running, paused) campaigns whose audience is the segment, by name. */
    List<CampaignRef> usingSegment(String workspaceId, String segmentId);
}
