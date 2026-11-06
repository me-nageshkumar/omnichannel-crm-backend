package me.nageshk.omnichannel.service.inbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEventRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import org.springframework.stereotype.Component;

/**
 * Removes everything the inbox keeps for a phone number. It depends on repositories and the media store only, never
 * on {@code PhoneNumberService}, which calls it.
 */
@Component
@RequiredArgsConstructor
public class InboxCleanup {

    private static final int BATCH = 1000;

    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final WaWebChatActionRepository actions;
    private final WaWebHistoryImportRepository historyImports;
    private final WaWebHistoryImportChatRepository historyImportChats;
    private final ChannelWebhookEventRepository webhookEvents;
    private final ChatMediaService media;

    /** Deletes the number's chats, messages, chat actions, history imports, webhook events, routing settings and media. Safe to run again. */
    public void deleteForNumber(String phoneNumberId) {
        List<String> chatIds = chats.findIdsByPhoneNumberId(phoneNumberId).stream().map(WaWebChat::getId).toList();
        for (int from = 0; from < chatIds.size(); from += BATCH) {
            actions.deleteByChatIdIn(chatIds.subList(from, Math.min(from + BATCH, chatIds.size())));
        }
        messages.deleteByPhoneNumberId(phoneNumberId);
        chats.deleteByPhoneNumberId(phoneNumberId);
        historyImports.deleteByPhoneNumberId(phoneNumberId);
        historyImportChats.deleteByPhoneNumberId(phoneNumberId);
        webhookEvents.deleteByNumber(phoneNumberId);
        media.deleteByPhoneNumber(phoneNumberId);
    }
}
