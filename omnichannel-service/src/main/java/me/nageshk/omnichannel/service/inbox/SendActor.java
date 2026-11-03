package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.MessageSource;

/**
 * Who a message goes out as through {@link InboxSendService}: a console user, a campaign or an automation rule, which have no user. It
 * names the message's source, sender and campaign, and the author of any action the send records.
 */
public sealed interface SendActor permits UserActor, CampaignActor, AutomationActor {

    /** The sending user, or null for a campaign. */
    String userId();

    /** Stored as the message's {@code sentByName} and an action's {@code byName}. */
    String displayName();

    MessageSource source();

    /** The campaign the message belongs to, or null for a console send. */
    String campaignId();
}
