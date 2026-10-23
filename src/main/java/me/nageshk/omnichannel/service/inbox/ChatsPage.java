package me.nageshk.omnichannel.service.inbox;

import java.util.List;

/** One page of a chat list, newest message first; {@code hasMore} when older chats remain. */
public record ChatsPage(List<ChatView> items, boolean hasMore) {
}
