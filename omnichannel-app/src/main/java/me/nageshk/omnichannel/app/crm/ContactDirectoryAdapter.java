package me.nageshk.omnichannel.app.crm;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.contact.Phones;
import org.springframework.stereotype.Component;

@Component
class ContactDirectoryAdapter implements ContactDirectory {

    private final ContactRepository contacts;
    private final ContactService contactService;

    ContactDirectoryAdapter(ContactRepository contacts, ContactService contactService) {
        this.contacts = contacts;
        this.contactService = contactService;
    }

    @Override
    public Optional<PlatformContact> find(String workspaceId, String contactId) {
        return contacts.findByIdAndWorkspaceId(contactId, workspaceId).map(ContactDirectoryAdapter::of);
    }

    @Override
    public Map<String, PlatformContact> findAll(String workspaceId, Collection<String> contactIds) {
        return contacts.findAllById(contactIds).stream()
                .filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .collect(Collectors.toMap(Contact::getId, ContactDirectoryAdapter::of));
    }

    @Override
    public PlatformContact ensure(String workspaceId, String customerId, String rawPhone, String name) {
        String phone = Phones.normalise(rawPhone);
        if (!Phones.isValid(phone)) throw new ValidationException("phone", Phones.MESSAGE);
        return contacts.findByWorkspaceIdAndPhone(workspaceId, phone).map(ContactDirectoryAdapter::of)
                .orElseGet(() -> create(workspaceId, customerId, phone, name));
    }

    private PlatformContact create(String workspaceId, String customerId, String phone, String name) {
        try {
            return of(contactService.create(workspaceId, customerId, name, phone, Set.of(), Map.of()));
        } catch (DuplicateException e) {
            return contacts.findByWorkspaceIdAndPhone(workspaceId, phone).map(ContactDirectoryAdapter::of)
                    .orElseThrow(() -> e);
        }
    }

    private static PlatformContact of(Contact c) {
        return new PlatformContact(c.getId(), c.getWorkspaceId(), c.getName(), c.getPhone());
    }
}
