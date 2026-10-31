package me.nageshk.omnichannel.crm.platform;

import java.util.List;
import java.util.Set;

/** The channel-contact side of segments: what a brand's contacts carry on their channel, and who matches a rule. */
public interface ChannelContactQuery {

    List<ChannelLabel> labels(String workspaceId);

    List<ChannelAttribute> attributes(String workspaceId);

    /**
     * The labels and attributes of one channel contact of the brand. Ids whose definition is gone are dropped; a
     * contact that does not exist in the brand has neither.
     */
    ChannelContactDetails details(String workspaceId, String contactId);

    /** The brand's channel accounts, ordered by address. */
    List<ChannelAccount> accounts(String workspaceId);

    /**
     * Ids of the brand's channel contacts matching the rule, at most {@code limit + 1} of them: a result larger than
     * {@code limit} tells the caller the rule is too broad. Dates in the rule are day-granular in the brand's zone.
     */
    Set<String> matching(String workspaceId, ChannelRule rule, int limit);
}
