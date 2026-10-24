package me.nageshk.omnichannel.app.crm;

import java.util.Optional;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformChat;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import org.springframework.stereotype.Component;

@Component
class ChatDirectoryAdapter implements ChatDirectory {

    private final WaWebChatRepository chats;

    ChatDirectoryAdapter(WaWebChatRepository chats) {
        this.chats = chats;
    }

    @Override
    public Optional<PlatformChat> find(String workspaceId, String chatId) {
        return chats.findByIdAndWorkspaceId(chatId, workspaceId).map(ChatDirectoryAdapter::of);
    }

    @Override
    public Optional<PlatformChat> findFor(String phoneNumberId, String contactId) {
        return chats.findFirstByPhoneNumberIdAndContactId(phoneNumberId, contactId).map(ChatDirectoryAdapter::of);
    }

    private static PlatformChat of(WaWebChat c) {
        return new PlatformChat(c.getId(), c.getWorkspaceId(), c.getPhoneNumberId(), c.getContactId(),
                c.getAssignedUserId(), c.getAssignedTeamId(), c.getLastInboundAt());
    }
}
