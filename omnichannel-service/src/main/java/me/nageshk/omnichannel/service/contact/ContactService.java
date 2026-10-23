package me.nageshk.omnichannel.service.contact;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactNoteRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Contact logic that is not tied to one endpoint: creating and updating contacts from chats, CRUD, labels. */
@Service
public class ContactService {

    public static final int MAX_NAME = 100;
    public static final int MAX_ATTRIBUTE_VALUE = 500;

    private final ContactRepository contacts;
    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final ContactNoteRepository notes;
    private final MongoTemplate mongo;
    private final Clock clock;

    public ContactService(ContactRepository contacts, ContactLabelRepository labels,
            ContactAttributeRepository attributes, ContactNoteRepository notes, MongoTemplate mongo, Clock clock) {
        this.contacts = contacts;
        this.labels = labels;
        this.attributes = attributes;
        this.notes = notes;
        this.mongo = mongo;
        this.clock = clock;
    }

    public Contact require(String workspaceId, String id) {
        return contacts.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(() -> new NotFoundException("contact not found"));
    }

    /** A contact added by hand; the phone must be valid and new to the brand. */
    public Contact create(String workspaceId, String customerId, String name, String rawPhone, Set<String> labelIds,
            Map<String, String> attributeValues) {
        Map<String, String> errors = new LinkedHashMap<>();
        String phone = Phones.normalise(rawPhone);
        if (!Phones.isValid(phone)) errors.put("phone", "must be 8 to 15 digits with the country code, e.g. 919812345678");
        String n = cleanName(name, errors);
        Set<String> ls = checkedLabels(workspaceId, labelIds, errors);
        Map<String, String> attrs = checkedAttributes(workspaceId, attributeValues, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (contacts.findByWorkspaceIdAndPhone(workspaceId, phone).isPresent()) throw duplicatePhone();

        Contact c = new Contact();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId(customerId);
        c.setPhone(phone);
        c.setName(n);
        c.setNameEdited(true);
        c.setSource(ContactSource.MANUAL);
        c.setLabels(ls);
        attrs.values().removeIf(String::isEmpty);
        c.setAttributes(attrs);
        try {
            return contacts.save(c);
        } catch (DuplicateKeyException e) {
            throw duplicatePhone();
        }
    }

    /** Sets the name when given (blank clears it) and merges attribute values (a null or blank value clears one). */
    public Contact update(String workspaceId, String id, String name, Map<String, String> attributeValues) {
        Contact c = require(workspaceId, id);
        Map<String, String> errors = new LinkedHashMap<>();
        String n = name == null ? null : cleanName(name, errors);
        Map<String, String> attrs = checkedAttributes(workspaceId, attributeValues, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (name != null) {
            c.setName(n);
            c.setNameEdited(true);
        }
        attrs.forEach((k, v) -> {
            if (v.isEmpty()) c.getAttributes().remove(k);
            else c.getAttributes().put(k, v);
        });
        return contacts.save(c);
    }

    /** Deletes the contact and its notes. */
    public void delete(String workspaceId, String id) {
        Contact c = require(workspaceId, id);
        contacts.delete(c);
        notes.deleteByWorkspaceIdAndContactId(workspaceId, id);
    }

    /** Marks the contact do-not-contact; an existing mark is kept as it is. */
    public Contact markDoNotContact(String workspaceId, String id, DoNotContact.Reason reason, String campaignId) {
        Contact c = require(workspaceId, id);
        if (c.getDoNotContact() != null) return c;
        mongo.updateFirst(byId(workspaceId, id).addCriteria(Criteria.where("doNotContact").is(null)),
                new Update().set("doNotContact", new DoNotContact(clock.instant(), reason, campaignId)), Contact.class);
        return require(workspaceId, id);
    }

    /** Names the contact, as if typed by a person, only while it has no name; a blank {@code name} does nothing. */
    public void nameIfBlank(String workspaceId, String contactId, String name) {
        String n = name == null ? "" : name.trim();
        if (contactId == null || n.isEmpty()) return;
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME);
        mongo.updateFirst(byId(workspaceId, contactId).addCriteria(new Criteria().orOperator(
                        Criteria.where("name").is(null), Criteria.where("name").regex("^\\s*$"))),
                new Update().set("name", n).set("nameEdited", true), Contact.class);
    }

    public Contact clearDoNotContact(String workspaceId, String id) {
        require(workspaceId, id);
        mongo.updateFirst(byId(workspaceId, id), new Update().unset("doNotContact"), Contact.class);
        return require(workspaceId, id);
    }

    public Contact addLabel(String workspaceId, String id, String labelId) {
        require(workspaceId, id);
        requireLabel(workspaceId, labelId);
        mongo.updateFirst(byId(workspaceId, id), new Update().addToSet("labels", labelId), Contact.class);
        return require(workspaceId, id);
    }

    public Contact removeLabel(String workspaceId, String id, String labelId) {
        require(workspaceId, id);
        requireLabel(workspaceId, labelId);
        mongo.updateFirst(byId(workspaceId, id), new Update().pull("labels", labelId), Contact.class);
        return require(workspaceId, id);
    }

    private void requireLabel(String workspaceId, String labelId) {
        labels.findByIdAndWorkspaceId(labelId, workspaceId).orElseThrow(() -> new NotFoundException("label not found"));
    }

    private static Query byId(String workspaceId, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId));
    }

    private static DuplicateException duplicatePhone() {
        return new DuplicateException("phone", "this brand already has a contact with that phone number");
    }

    private static String cleanName(String raw, Map<String, String> errors) {
        String n = raw == null ? "" : raw.trim();
        if (n.length() > MAX_NAME) errors.put("name", "must be at most " + MAX_NAME + " characters");
        return n.isEmpty() ? null : n;
    }

    private Set<String> checkedLabels(String workspaceId, Set<String> ids, Map<String, String> errors) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null || ids.isEmpty()) return out;
        Set<String> mine = labels.findAllById(ids).stream().filter(l -> workspaceId.equals(l.getWorkspaceId()))
                .map(ContactLabel::getId).collect(Collectors.toSet());
        if (!mine.containsAll(ids)) errors.put("labels", "contains a label that does not exist in this brand");
        out.addAll(ids);
        return out;
    }

    /** Values trimmed (blank becomes the empty string, meaning clear); keys must be the brand's attribute ids. */
    private Map<String, String> checkedAttributes(String workspaceId, Map<String, String> in, Map<String, String> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty()) return out;
        Set<String> mine = attributes.findAllById(in.keySet()).stream()
                .filter(a -> workspaceId.equals(a.getWorkspaceId())).map(ContactAttribute::getId)
                .collect(Collectors.toSet());
        for (Map.Entry<String, String> e : in.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!mine.contains(e.getKey())) errors.put("attributes", "contains an attribute that does not exist in this brand");
            else if (v.length() > MAX_ATTRIBUTE_VALUE)
                errors.put("attributes", "values must be at most " + MAX_ATTRIBUTE_VALUE + " characters");
            out.put(e.getKey(), v);
        }
        return out;
    }

    /**
     * Creates or updates the brand's contact for a chat partner. {@code remote} is {@code …@c.us} or
     * {@code …@lid}; a null {@code senderName} leaves the name alone; {@code lidPhone} is the person's
     * phone when WhatsApp exposed it for a {@code @lid} chat; {@code lastMessageAt} only moves forward.
     * Retried once when a concurrent upsert wins the unique index. Returns null when the chat id is not a person.
     */
    public Contact upsertFromChat(String workspaceId, String customerId, String remote, String senderName,
            String lidPhone, Instant at) {
        try {
            return upsert(workspaceId, customerId, remote, senderName, lidPhone, at);
        } catch (DuplicateKeyException | OptimisticLockingFailureException e) {
            return upsert(workspaceId, customerId, remote, senderName, lidPhone, at);
        }
    }

    /**
     * The console wrote to {@code phone} and WhatsApp answered under {@code lid}: both ids are one person. The
     * contact of that phone takes the lid; a nameless hidden contact that ingest just created for the lid (its
     * {@code message_create} can beat the reply) is folded away, as the lid is unique. A lid contact with a name or
     * a phone of its own is someone we already know and is kept as the chat's contact instead. Without a contact for
     * the phone this is {@link #upsertFromChat} with the phone filled in.
     */
    public Contact linkLidToPhone(String workspaceId, String customerId, String lid, String phone, Instant at) {
        Contact byPhone = contacts.findByWorkspaceIdAndPhone(workspaceId, phone).orElse(null);
        if (byPhone == null) return upsertFromChat(workspaceId, customerId, lid, null, phone, at);
        Contact byLid = contacts.findByWorkspaceIdAndWaId(workspaceId, lid).orElse(null);
        if (byLid != null && !byLid.getId().equals(byPhone.getId())) {
            if (byLid.getPhone() != null || byLid.getName() != null) return byLid;
            contacts.delete(byLid);
        }
        byPhone.setWaId(lid);
        Instant when = at != null ? at : clock.instant();
        if (byPhone.getLastMessageAt() == null || when.isAfter(byPhone.getLastMessageAt())) {
            byPhone.setLastMessageAt(when);
        }
        return contacts.save(byPhone);
    }

    private Contact upsert(String workspaceId, String customerId, String remote, String senderName,
            String lidPhone, Instant at) {
        boolean lid = remote.endsWith("@lid");
        String phone = lid ? Phones.normalise(lidPhone) : remote.substring(0, remote.indexOf('@'));
        if (!lid && !Phones.isValid(phone)) return null;
        if (lid && !Phones.isValid(phone)) phone = null;

        Contact c = null;
        if (!lid) c = contacts.findByWorkspaceIdAndPhone(workspaceId, phone).orElse(null);
        if (c == null) c = contacts.findByWorkspaceIdAndWaId(workspaceId, remote).orElse(null);
        boolean changed = false;
        if (c == null) {
            c = new Contact();
            c.setWorkspaceId(workspaceId);
            c.setCustomerId(customerId);
            c.setSource(ContactSource.CHAT);
            changed = true;
        }
        if (c.getWaId() == null) {
            c.setWaId(remote);
            changed = true;
        }
        if (c.getPhone() == null && phone != null
                && contacts.findByWorkspaceIdAndPhone(workspaceId, phone).isEmpty()) {
            c.setPhone(phone);
            changed = true;
        }
        if (senderName != null && !c.isNameEdited() && !senderName.equals(c.getName())) {
            c.setName(senderName);
            changed = true;
        }
        Instant when = at != null ? at : clock.instant();
        if (c.getLastMessageAt() == null || when.isAfter(c.getLastMessageAt())) {
            c.setLastMessageAt(when);
            changed = true;
        }
        return changed ? contacts.save(c) : c;
    }
}
