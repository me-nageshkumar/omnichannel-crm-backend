package me.nageshk.omnichannel.crm.platform;

import java.util.Optional;

/** Brand chats, read only. */
public interface ChatDirectory {

    Optional<PlatformChat> find(String workspaceId, String chatId);

    /** The chat of a number with a contact, if any. */
    Optional<PlatformChat> findFor(String phoneNumberId, String contactId);
}
