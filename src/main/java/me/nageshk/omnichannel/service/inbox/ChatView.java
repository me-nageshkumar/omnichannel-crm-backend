package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageStatus;

/** A chat as the console sees it. {@code assignedUser} and {@code assignedTeam} are null when unset. */
public record ChatView(
        String id,
        String phoneNumberId,
        String remoteId,
        Contact contact,
        ChatStatus status,
        Ref assignedUser,
        Ref assignedTeam,
        int unreadCount,
        Instant lastMessageAt,
        LastMessage lastMessage,
        Instant creationTime,
        Ref lastAgent) {

    /** {@code numberHidden} is true when the contact has no phone number (WhatsApp privacy ids). */
    public record Contact(String id, String name, String phone, boolean numberHidden) {
    }

    /** A user or team by id and display name; the name is null when the row has since been deleted. */
    public record Ref(String id, String name) {
    }

    public record LastMessage(MessageDirection direction, String type, String text, MessageStatus status) {
    }
}
