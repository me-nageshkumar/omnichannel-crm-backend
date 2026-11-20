package me.nageshk.omnichannel.meta.api;

import java.util.List;
import java.util.Optional;

/** The Cloud side of the host's phone numbers (spec 2026-11-20 §5.2, §5.7). */
public interface CloudAccounts {

    /**
     * Reads the number from Meta and checks it is in the WABA; writes nothing.
     *
     * @throws me.nageshk.omnichannel.common.api.ValidationException (field {@code cloud}) with Meta's or our reason
     */
    CloudAccountInfo preview(String workspaceId, CloudNumberRequest request);

    /** Stores the account of a number just added, and subscribes its WABA to the connection's app. */
    CloudAccountInfo create(String workspaceId, String customerId, String phoneNumberId, CloudAccountInfo previewed);

    Optional<CloudAccountInfo> find(String phoneNumberId);

    Optional<CloudAccountInfo> findByMetaPhoneNumberId(String metaPhoneNumberId);

    List<CloudAccountInfo> byConnection(String connectionId);

    /** Registers the number with its two-step PIN (never stored), then reads its state again. */
    CloudAccountInfo register(String phoneNumberId, String pin);

    void delete(String phoneNumberId);
}
