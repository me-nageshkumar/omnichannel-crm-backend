package me.nageshk.omnichannel.phone;

import java.time.Instant;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.phone.PhoneNumberStatus.DisabledBecause;
import me.nageshk.omnichannel.phone.PhoneNumberStatus.Status;

/** A number as the console shows it. Never carries the device key or its hash. */
public record PhoneNumberView(
        String id,
        String number,
        String label,
        ConnectionType connectionType,
        String customerId,
        String customerName,
        String workspaceId,
        String workspaceName,
        Status status,
        Disabled disabled,
        DisabledBecause disabledBecause,
        Instant lastConnectedAt,
        Instant lastSeenAt,
        String wrongNumber,
        Instant wrongNumberAt,
        boolean hasDevice,
        Instant creationTime,
        TeamRef team,
        Boolean guest) {
    public record TeamRef(String id, String name) {}

    /** The same view flagged as a guest number (or not) for the inbox's number list; null elsewhere. */
    public PhoneNumberView asGuest(boolean guest) {
        return new PhoneNumberView(id, number, label, connectionType, customerId, customerName, workspaceId,
                workspaceName, status, disabled, disabledBecause, lastConnectedAt, lastSeenAt, wrongNumber, wrongNumberAt,
                hasDevice, creationTime, team, guest);
    }

    public record Page(java.util.List<PhoneNumberView> items, long total, int page) {}
}
