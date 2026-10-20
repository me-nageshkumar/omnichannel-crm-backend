package me.nageshk.omnichannel.contact;

import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Contact logic that is not tied to one endpoint: creating and updating contacts from chats, CRUD, labels. */
@Service
public class ContactService {

    private final ContactRepository contacts;
    private final Clock clock;

    public ContactService(ContactRepository contacts, Clock clock) {
        this.contacts = contacts;
        this.clock = clock;
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
