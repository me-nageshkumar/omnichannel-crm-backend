package me.nageshk.omnichannel.service.inbox.history;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import org.springframework.stereotype.Component;

/** Builds the API views of history imports and their chats. */
@Component
public class HistoryImportViews {

    private final PhoneNumberRepository phoneNumbers;
    private final WaWebChatRepository chats;

    HistoryImportViews(PhoneNumberRepository phoneNumbers, WaWebChatRepository chats) {
        this.phoneNumbers = phoneNumbers;
        this.chats = chats;
    }

    public HistoryImportView view(WaWebHistoryImport i) {
        PhoneNumber n = phoneNumbers.findById(i.getPhoneNumberId()).orElse(null);
        HistoryImportView.NumberRef ref = new HistoryImportView.NumberRef(i.getPhoneNumberId(),
                n == null ? null : n.getNumber(), n == null ? null : n.getLabel());
        WaWebHistoryImport.Scope s = i.getScope();
        WaWebHistoryImport.Counts c = i.getCounts() == null ? WaWebHistoryImport.Counts.ZERO : i.getCounts();
        Integer progress = c.chatsTotal() == 0 ? null
                : (c.chatsDone() + c.chatsSkipped() + c.chatsFailed()) * 100 / c.chatsTotal();
        return new HistoryImportView(i.getId(), ref,
                new HistoryImportView.ScopeView(s.kind(), s.phones(), s.remoteIds().size()), i.getRange(),
                i.getStatus(), i.getWaiting(), c, new HistoryImportView.Ref(i.getRequestedByUserId(),
                        i.getRequestedByName()),
                i.getCreationTime(), i.getStartedAt(), i.getFinishedAt(), progress, i.getError());
    }

    public List<HistoryChatView> chats(List<WaWebHistoryImportChat> items) {
        return items.stream().map(r -> {
            String chatId = chats.findByPhoneNumberIdAndRemoteId(r.getPhoneNumberId(), r.getRemoteId())
                    .map(WaWebChat::getId).orElse(null);
            return new HistoryChatView(r.getId(), r.getRemoteId(), r.getRequestedPhone(), r.getChatName(), chatId,
                    r.getStatus(), r.getSkipReason(), r.getError(), r.getCounts(), r.isCapped(), r.isOlderUnavailable(),
                    r.getAttemptedAt(),
                    r.getFinishedAt());
        }).toList();
    }
}
