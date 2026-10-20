package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import java.util.List;

/** One page of a chat's thread, oldest first; {@code hasMore} when older items remain. */
public record ThreadPage(List<ThreadItem> items, boolean hasMore) {

    /** A message or an audit entry; {@code kind} is {@code "message"} or {@code "action"} and the other view is null. */
    public record ThreadItem(String kind, MessageView message, ActionView action, Instant at) {
    }
}
