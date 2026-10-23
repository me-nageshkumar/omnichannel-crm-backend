package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.RoutedReason;

/** An audit-trail entry as the console sees it; {@code by} is null for automatic routing ("System"). */
public record ActionView(
        String id,
        ActionType type,
        ChatView.Ref by,
        ChatView.Ref toUser,
        ChatView.Ref toTeam,
        Instant at,
        RoutedReason reason,
        ChatView.Ref fromTeam) {
}
