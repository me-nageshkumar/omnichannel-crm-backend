package me.nageshk.omnichannel.service.contact;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Two contacts of a brand that are one person become one (spec 4.4): the survivor takes the other's ids, labels and
 * missing details, every chat, note and campaign recipient of the other moves to it, and the other is deleted.
 * Listeners of {@link ContactEvents.ContactsMerged} move what this module does not own (the CRM's records).
 */
@Service
public class ContactMergeService {

    private final ContactRepository contacts;
    private final WaWebChatRepository chats;
    private final MongoTemplate mongo;
    private final ApplicationEventPublisher publisher;

    public ContactMergeService(ContactRepository contacts, WaWebChatRepository chats, MongoTemplate mongo,
            ApplicationEventPublisher publisher) {
        this.contacts = contacts;
        this.chats = chats;
        this.mongo = mongo;
        this.publisher = publisher;
    }

    /** The id of the contact that should survive a merge of the two: the one with a phone, else the older. */
    public static String survivorOf(Contact a, Contact b) {
        if ((a.getPhone() == null) != (b.getPhone() == null)) return a.getPhone() != null ? a.getId() : b.getId();
        Instant at = a.getCreationTime();
        Instant bt = b.getCreationTime();
        return bt != null && (at == null || bt.isBefore(at)) ? b.getId() : a.getId();
    }

    /**
     * Folds {@code otherId} into {@code survivorId} and returns the saved survivor. A second run finds the other
     * gone and changes nothing; a run cut short before the delete is finished by the next one.
     *
     * @throws NotFoundException when the survivor is not a contact of the brand
     */
    public Contact merge(String workspaceId, String survivorId, String otherId) {
        Contact survivor = require(workspaceId, survivorId);
        Contact other = contacts.findByIdAndWorkspaceId(otherId, workspaceId).orElse(null);
        if (other == null || survivorId.equals(otherId)) return survivor;

        // the ids and the phone are unique per brand: the other lets go of them before the survivor takes them
        mongo.updateFirst(byId(workspaceId, otherId), new Update().set("waIds", List.of()).unset("phone"),
                Contact.class);
        Contact merged = InboxIngestService.retrying(
                () -> contacts.save(combined(require(workspaceId, survivorId), other)));

        Query ofOther = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(otherId));
        Update toSurvivor = new Update().set("contactId", survivorId);
        mongo.updateMulti(ofOther, toSurvivor, WaWebChat.class);
        mongo.updateMulti(ofOther, toSurvivor, ContactNote.class);
        mongo.updateMulti(ofOther, toSurvivor, CampaignRecipient.class);
        publisher.publishEvent(new ContactEvents.ContactsMerged(workspaceId, survivorId, otherId));
        contacts.deleteById(otherId);

        // every chat of the person shows the merged contact (and, now, its other chats)
        for (WaWebChat chat : chats.findAllByWorkspaceIdAndContactId(workspaceId, survivorId)) {
            publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        }
        return merged;
    }

    /** The survivor with the other's details folded in by the rules of spec 4.4. */
    private static Contact combined(Contact survivor, Contact other) {
        for (String id : other.getWaIds()) ContactService.addWaId(survivor, id);
        if (survivor.getPhone() == null) survivor.setPhone(other.getPhone());
        if (!blank(other.getName())
                && (blank(survivor.getName()) || (!survivor.isNameEdited() && other.isNameEdited()))) {
            survivor.setName(other.getName());
            survivor.setNameEdited(other.isNameEdited());
        }
        survivor.getLabels().addAll(other.getLabels());
        other.getAttributes().forEach((k, v) -> {
            if (blank(survivor.getAttributes().get(k)) && !blank(v)) survivor.getAttributes().put(k, v);
        });
        Instant last = other.getLastMessageAt();
        if (last != null && (survivor.getLastMessageAt() == null || last.isAfter(survivor.getLastMessageAt()))) {
            survivor.setLastMessageAt(last);
        }
        if (survivor.getDoNotContact() == null) survivor.setDoNotContact(other.getDoNotContact());
        return survivor;
    }

    private Contact require(String workspaceId, String id) {
        return contacts.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("contact not found"));
    }

    private static Query byId(String workspaceId, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
