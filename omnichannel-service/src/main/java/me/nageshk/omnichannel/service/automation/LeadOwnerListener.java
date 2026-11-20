package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The lead follows the chat (spec 2026-11-20 §6.1): when a chat is given to a person, the CRM lead of its contact on
 * the number takes that person as owner if it has none. Unassigning never clears an owner. A failure is logged and
 * never fails the assignment.
 */
@Component
public class LeadOwnerListener {

    private static final Logger log = LoggerFactory.getLogger(LeadOwnerListener.class);

    private final AutomationCrm crm;

    LeadOwnerListener(AutomationCrm crm) {
        this.crm = crm;
    }

    @EventListener
    void on(InboxEvents.ActionRecorded e) {
        WaWebChat chat = e.chat();
        WaWebChatAction action = e.action();
        if (chat == null || action == null || chat.getContactId() == null || action.getToUserId() == null) return;
        ActionType type = action.getType();
        if (type != ActionType.ROUTED && type != ActionType.ASSIGNED && type != ActionType.TRANSFERRED) return;
        try {
            crm.fillOwner(chat.getWorkspaceId(), chat.getCustomerId(), chat.getContactId(), chat.getPhoneNumberId(),
                    action.getToUserId());
        } catch (RuntimeException ex) {
            log.warn("could not fill the lead owner for chat {}: {}", chat.getId(), ex.toString());
        }
    }
}
