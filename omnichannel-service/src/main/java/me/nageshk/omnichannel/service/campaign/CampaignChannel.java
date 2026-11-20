package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;

/**
 * How a kind of number sends campaigns (spec 2026-11-21 §5.2): whether a sender may send now, and its work for one
 * tick. The runner keeps what both kinds share; a sender's campaigns are always of its own kind.
 */
sealed interface CampaignChannel permits WebCampaignChannel, CloudCampaignChannel {

    /** Whether the sender may start work in this tick. */
    boolean eligible(String ws, String senderId, CampaignSettings settings, Instant now);

    /** The sender's work for one tick over {@code campaigns}, all of this channel. */
    void process(String ws, String senderId, List<Campaign> campaigns, CampaignSettings settings);
}
