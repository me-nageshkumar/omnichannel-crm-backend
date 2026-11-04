package me.nageshk.omnichannel.app.crm;

import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.service.contact.ContactEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Two brand contacts were merged: their CRM records follow the survivor. Automation history keeps the old id, as
 * history is not rewritten (spec 4.4).
 */
@Component
class ContactMergeAdapter {

    private final CrmContactService crmContacts;

    ContactMergeAdapter(CrmContactService crmContacts) {
        this.crmContacts = crmContacts;
    }

    @EventListener
    void on(ContactEvents.ContactsMerged e) {
        crmContacts.repointContact(e.workspaceId(), e.otherId(), e.survivorId());
    }
}
