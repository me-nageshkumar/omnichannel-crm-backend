package me.nageshk.omnichannel.service.contact;

import java.time.Instant;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    private static final Logger log = LoggerFactory.getLogger(ContactMergeService.class);

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
     * gone and changes nothing; a run cut short before the delete is finished by the next one, from the ids and
     * phone the other stashed when it let go of them.
     *
     * @throws NotFoundException when the survivor is not a contact of the brand
     * @throws DuplicateKeyException when another contact took one of the stashed ids meanwhile; the stash stays
     */
    public Contact merge(String workspaceId, String survivorId, String otherId) {
        Contact survivor = require(workspaceId, survivorId);
        if (survivorId.equals(otherId) || contacts.findByIdAndWorkspaceId(otherId, workspaceId).isEmpty()) {
            return survivor;
        }
        Contact merged;
        try {
            merged = InboxIngestService.retrying(() -> {
                Contact other = released(workspaceId, otherId, survivorId);
                return contacts.save(combined(require(workspaceId, survivorId), other));
            });
        } catch (DuplicateKeyException e) {
            log.warn("contact {} was not merged into {} in {}: {}; its ids stay stashed on it", otherId, survivorId,
                    workspaceId, heldElsewhere(workspaceId, otherId, survivorId));
            throw e;
        }

        Query ofOther = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(otherId));
        Update toSurvivor = new Update().set("contactId", survivorId);
        long movedChats = mongo.updateMulti(ofOther, toSurvivor, WaWebChat.class).getModifiedCount();
        long movedNotes = mongo.updateMulti(ofOther, toSurvivor, ContactNote.class).getModifiedCount();
        long movedRecipients = mongo.updateMulti(ofOther, toSurvivor, CampaignRecipient.class).getModifiedCount();
        publisher.publishEvent(new ContactEvents.ContactsMerged(workspaceId, survivorId, otherId));
        contacts.deleteById(otherId);
        log.info("contact {} merged into {} in {}: {} chats, {} notes, {} recipients", otherId, survivorId,
                workspaceId, movedChats, movedNotes, movedRecipients);

        // every chat of the person shows the merged contact (and, now, its other chats)
        for (WaWebChat chat : chats.findAllByWorkspaceIdAndContactId(workspaceId, survivorId)) {
            publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        }
        return merged;
    }

    /**
     * The other contact once it has let go of its ids and phone (unique per brand, so the survivor can take them):
     * in one update they move to its stash, which names the survivor, so a run that stops after this loses nothing.
     * Ids already stashed by an earlier run are kept. A concurrent change of the other is a version clash.
     */
    private Contact released(String workspaceId, String otherId, String survivorId) {
        Contact other = require(workspaceId, otherId);
        if (other.getWaIds().isEmpty() && other.getPhone() == null && survivorId.equals(other.getMergingInto())) {
            return other;
        }
        List<String> stashed = new ArrayList<>(other.getMergingWaIds() == null ? List.of() : other.getMergingWaIds());
        for (String id : other.getWaIds()) if (!stashed.contains(id)) stashed.add(id);
        String phone = other.getPhone() != null ? other.getPhone() : other.getMergingPhone();
        Update release = new Update().set("waIds", List.of()).unset("phone").set("mergingInto", survivorId)
                .set("mergingWaIds", stashed);
        if (phone != null) release.set("mergingPhone", phone);
        Query unchanged = byId(workspaceId, otherId).addCriteria(Criteria.where("version").is(other.getVersion()));
        if (mongo.updateFirst(unchanged, release, Contact.class).getModifiedCount() == 0) {
            throw new OptimisticLockingFailureException("contact " + otherId + " changed while being merged");
        }
        return require(workspaceId, otherId);
    }

    /** Which stashed id or phone of the other a third contact holds, for the log. */
    private String heldElsewhere(String workspaceId, String otherId, String survivorId) {
        Contact other = contacts.findByIdAndWorkspaceId(otherId, workspaceId).orElse(null);
        if (other == null || other.getMergingWaIds() == null) return "the other contact is gone";
        for (String id : other.getMergingWaIds()) {
            Contact holder = contacts.findByWorkspaceIdAndWaIdsContaining(workspaceId, id).orElse(null);
            if (holder != null && !holder.getId().equals(survivorId)) {
                return "contact " + holder.getId() + " holds " + id;
            }
        }
        Contact holder = other.getMergingPhone() == null ? null
                : contacts.findByWorkspaceIdAndPhone(workspaceId, other.getMergingPhone()).orElse(null);
        return holder != null && !holder.getId().equals(survivorId)
                ? "contact " + holder.getId() + " holds phone " + other.getMergingPhone()
                : "an id is held by another contact";
    }

    /** The survivor with the released other's details (its ids and phone from the stash) folded in, spec 4.4. */
    private static Contact combined(Contact survivor, Contact other) {
        for (String id : other.getMergingWaIds()) ContactService.addWaId(survivor, id);
        if (survivor.getPhone() == null) survivor.setPhone(other.getMergingPhone());
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
