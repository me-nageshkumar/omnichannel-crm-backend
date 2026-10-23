package me.nageshk.omnichannel.crm.contact;

import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.contact.dto.InboxSummaryView;
import me.nageshk.omnichannel.crm.deal.DealService;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformChat;
import org.springframework.stereotype.Service;

/** The inbox panel's view of a chat: may the caller capture it, and what the CRM knows of its contact. */
@Service
public class InboxSummaryService {

    private final ChatDirectory chats;
    private final CaptureService capture;
    private final CrmContactService contacts;
    private final DealService deals;

    InboxSummaryService(ChatDirectory chats, CaptureService capture, CrmContactService contacts, DealService deals) {
        this.chats = chats;
        this.capture = capture;
        this.contacts = contacts;
        this.deals = deals;
    }

    /**
     * @throws NotFoundException when the chat is not one of the brand. The contact is null when the chat is not
     *         captured, or when a restricted caller has no access to any of its pipelines.
     */
    public InboxSummaryView summary(CrmContext ctx, String chatId) {
        PlatformChat chat = chats.find(ctx.workspaceId(), chatId).orElseThrow(() -> new NotFoundException("Chat not found"));
        String blocked = capture.captureBlockedReason(ctx, chat);
        CrmContact contact = contacts.findVisible(ctx, chat.contactId(), chat.phoneNumberId()).orElse(null);
        CrmContactView view = null;
        List<DealView> open = List.of();
        if (contact != null) {
            view = contacts.view(ctx, contact);
            open = deals.search(ctx, new DealSearchRequest(null, null, "OPEN", null, null, contact.getId(), null, null,
                    null, 0, 200)).items();
        }
        return new InboxSummaryView(chat.id(), blocked == null, blocked, view, open, null);
    }
}
