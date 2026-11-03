package me.nageshk.omnichannel.service.inbox.history;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
        return views(List.of(i)).getFirst();
    }

    /** The views of a page of imports, looking their numbers up in one query. */
    public List<HistoryImportView> views(List<WaWebHistoryImport> imports) {
        Map<String, PhoneNumber> numbers = new HashMap<>();
        phoneNumbers.findAllById(imports.stream().map(WaWebHistoryImport::getPhoneNumberId).distinct().toList())
                .forEach(n -> numbers.put(n.getId(), n));
        return imports.stream().map(i -> view(i, numbers.get(i.getPhoneNumberId()))).toList();
    }

    private static HistoryImportView view(WaWebHistoryImport i, PhoneNumber n) {
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

    /** The views of a page of chat items, looking their chats up in one query per number. */
    public List<HistoryChatView> chats(List<WaWebHistoryImportChat> items) {
        Map<String, String> chatIds = new HashMap<>();
        items.stream().collect(Collectors.groupingBy(WaWebHistoryImportChat::getPhoneNumberId,
                Collectors.mapping(WaWebHistoryImportChat::getRemoteId, Collectors.toSet())))
                .forEach((phoneNumberId, remoteIds) -> chats.findByPhoneNumberIdAndRemoteIdIn(phoneNumberId, remoteIds)
                        .forEach(c -> chatIds.put(c.getPhoneNumberId() + "|" + c.getRemoteId(), c.getId())));
        return items.stream().map(r -> new HistoryChatView(r.getId(), r.getRemoteId(), r.getRequestedPhone(),
                r.getChatName(), chatIds.get(r.getPhoneNumberId() + "|" + r.getRemoteId()), r.getStatus(),
                r.getSkipReason(), r.getError(), r.getCounts(), r.isCapped(), r.isOlderUnavailable(),
                r.getAttemptedAt(), r.getFinishedAt())).toList();
    }
}
