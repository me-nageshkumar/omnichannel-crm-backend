package me.nageshk.omnichannel.service.phone.move;

import java.util.List;

/**
 * What the move dialog says before a move (spec §7.1): the brands, the chats that stay behind on the archive, the
 * chatbot runs that end, the team that is cleared, and, when moving back, the archived chats that return.
 * {@code activeCampaigns} names the running or paused campaigns that send from the number: while there are any, the
 * move is refused.
 */
public record MovePreview(String fromWorkspaceName, String toWorkspaceName, long chats, long activeRuns,
        String teamName, String archiveLabel, boolean movingBack, long returningChats,
        List<String> rulesNamingNumber, List<String> activeCampaigns) {
}
