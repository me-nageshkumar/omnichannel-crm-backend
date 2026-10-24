package me.nageshk.omnichannel.crm.platform;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Brand contacts, read and created on the CRM's behalf. */
public interface ContactDirectory {

    Optional<PlatformContact> find(String workspaceId, String contactId);

    /** The found contacts by id; ids that do not exist in the brand are absent. */
    Map<String, PlatformContact> findAll(String workspaceId, Collection<String> contactIds);

    /**
     * The brand's contact with this phone (normalised), created as a manual contact when new.
     *
     * @throws me.nageshk.omnichannel.common.api.ValidationException field {@code phone} if the phone is invalid
     */
    PlatformContact ensure(String workspaceId, String customerId, String rawPhone, String name);
}
