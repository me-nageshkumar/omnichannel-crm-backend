package me.nageshk.omnichannel.service.phone;

import java.util.Optional;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.service.phone.LiveDevices.LiveState;

/** The status a number shows, computed from its record, its brand, its customer and the live connection. */
public record PhoneNumberStatus(Status status, DisabledBecause disabledBecause) {

    public enum Status { DISABLED, NOT_CONNECTED_YET, WAITING_FOR_LOGIN, CONNECTED, WRONG_NUMBER, DISCONNECTED }

    public enum DisabledBecause { NUMBER, BRAND, CUSTOMER }

    public static PhoneNumberStatus of(PhoneNumber n, Workspace brand, Customer customer, Optional<LiveState> live) {
        if (customer == null || !customer.isActive()) return disabled(DisabledBecause.CUSTOMER);
        if (brand == null || brand.getDisabled() != null) return disabled(DisabledBecause.BRAND);
        if (n.getDisabled() != null) return disabled(DisabledBecause.NUMBER);
        if (live.isPresent()) {
            return new PhoneNumberStatus(
                    live.get() == LiveState.CONNECTED ? Status.CONNECTED : Status.WAITING_FOR_LOGIN, null);
        }
        if (n.getLastWrongNumberAt() != null
                && (n.getLastConnectedAt() == null || n.getLastWrongNumberAt().isAfter(n.getLastConnectedAt()))) {
            return new PhoneNumberStatus(Status.WRONG_NUMBER, null);
        }
        if (n.getLastConnectedAt() == null) return new PhoneNumberStatus(Status.NOT_CONNECTED_YET, null);
        return new PhoneNumberStatus(Status.DISCONNECTED, null);
    }

    private static PhoneNumberStatus disabled(DisabledBecause why) {
        return new PhoneNumberStatus(Status.DISABLED, why);
    }
}
