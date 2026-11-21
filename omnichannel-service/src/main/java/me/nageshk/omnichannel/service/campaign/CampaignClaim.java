package me.nageshk.omnichannel.service.campaign;

import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;

/**
 * A recipient a sender claimed (now SENDING), with its campaign. {@code sender} is the WhatsApp Web sender row; a
 * Cloud number has none, so it is null there.
 */
record CampaignClaim(Campaign campaign, CampaignRecipient recipient, String senderId, CampaignSender sender,
        CampaignSettings settings) {
}
