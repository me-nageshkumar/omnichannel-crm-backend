package me.nageshk.omnichannel.inbox;

import java.time.Instant;

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
