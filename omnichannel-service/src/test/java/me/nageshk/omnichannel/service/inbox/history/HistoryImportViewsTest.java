package me.nageshk.omnichannel.service.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import org.junit.jupiter.api.Test;

/** A page of imports or of their chats is one lookup per kind, not one per row. */
class HistoryImportViewsTest {

    private final PhoneNumberRepository phoneNumbers = mock(PhoneNumberRepository.class);
    private final WaWebChatRepository chats = mock(WaWebChatRepository.class);
    private final HistoryImportViews views = new HistoryImportViews(phoneNumbers, chats);

    private static WaWebHistoryImport imp(String id, String phoneNumberId) {
        WaWebHistoryImport i = new WaWebHistoryImport();
        i.setId(id);
        i.setPhoneNumberId(phoneNumberId);
        i.setScope(new WaWebHistoryImport.Scope(HistoryScopeKind.ALL, List.of(), List.of()));
        return i;
    }

    private static PhoneNumber number(String id, String digits) {
        PhoneNumber n = new PhoneNumber();
        n.setId(id);
        n.setNumber(digits);
        return n;
    }

    private static WaWebHistoryImportChat item(String id, String phoneNumberId, String remoteId) {
        WaWebHistoryImportChat c = new WaWebHistoryImportChat();
        c.setId(id);
        c.setPhoneNumberId(phoneNumberId);
        c.setRemoteId(remoteId);
        return c;
    }

    private static WaWebChat chat(String id, String phoneNumberId, String remoteId) {
        WaWebChat c = new WaWebChat();
        c.setId(id);
        c.setPhoneNumberId(phoneNumberId);
        c.setRemoteId(remoteId);
        return c;
    }

    @Test
    void aPageOfImportsLooksTheNumbersUpOnce() {
        when(phoneNumbers.findAllById(any())).thenReturn(List.of(number("pn-1", "911"), number("pn-2", "912")));

        List<HistoryImportView> page = views.views(List.of(imp("i1", "pn-1"), imp("i2", "pn-2"), imp("i3", "pn-1")));

        assertThat(page).extracting(v -> v.phoneNumber().number()).containsExactly("911", "912", "911");
        verify(phoneNumbers, times(1)).findAllById(any());
        verify(phoneNumbers, never()).findById(anyString());
    }

    @Test
    void aPageOfChatsLooksTheChatsUpOncePerNumber() {
        when(chats.findByPhoneNumberIdAndRemoteIdIn(any(), any()))
                .thenReturn(List.of(chat("c1", "pn-1", "a@c.us"), chat("c2", "pn-1", "b@c.us")));

        List<HistoryChatView> page = views.chats(List.of(item("r1", "pn-1", "a@c.us"), item("r2", "pn-1", "b@c.us"),
                item("r3", "pn-1", "gone@c.us")));

        assertThat(page).extracting(HistoryChatView::chatId).containsExactly("c1", "c2", null);
        verify(chats, times(1)).findByPhoneNumberIdAndRemoteIdIn(any(), any());
        verify(chats, never()).findByPhoneNumberIdAndRemoteId(anyString(), anyString());
    }
}
