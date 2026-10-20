package me.nageshk.omnichannel.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.phone.LiveDevices.LiveState;
import me.nageshk.omnichannel.phone.PhoneNumberStatus.DisabledBecause;
import me.nageshk.omnichannel.phone.PhoneNumberStatus.Status;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import org.junit.jupiter.api.Test;

class PhoneNumberStatusTest {

    static final Instant T1 = Instant.parse("2026-10-20T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-10-20T11:00:00Z");
    static final Disabled OFF = new Disabled("x", EnableLevel.PLATFORM, T1);

    final PhoneNumber number = new PhoneNumber();
    final Workspace brand = new Workspace();
    final Customer customer = new Customer();

    PhoneNumberStatusTest() {
        customer.setActive(true);
    }

    private PhoneNumberStatus of(Optional<LiveState> live) {
        return PhoneNumberStatus.of(number, brand, customer, live);
    }

    @Test
    void neverConnectedIsNotConnectedYet() {
        assertThat(of(Optional.empty())).isEqualTo(new PhoneNumberStatus(Status.NOT_CONNECTED_YET, null));
    }

    @Test
    void liveConfirmedIsConnected() {
        assertThat(of(Optional.of(LiveState.CONNECTED)).status()).isEqualTo(Status.CONNECTED);
    }

    @Test
    void liveUnconfirmedIsWaitingForLogin() {
        assertThat(of(Optional.of(LiveState.WAITING_FOR_LOGIN)).status()).isEqualTo(Status.WAITING_FOR_LOGIN);
    }

    @Test
    void connectedBeforeAndGoneIsDisconnected() {
        number.setLastConnectedAt(T1);
        assertThat(of(Optional.empty()).status()).isEqualTo(Status.DISCONNECTED);
    }

    @Test
    void wrongNumberAfterLastConnectIsWrongNumber() {
        number.setLastConnectedAt(T1);
        number.setLastWrongNumberAt(T2);
        assertThat(of(Optional.empty()).status()).isEqualTo(Status.WRONG_NUMBER);
    }

    @Test
    void wrongNumberWithoutAnyConnectIsWrongNumber() {
        number.setLastWrongNumberAt(T1);
        assertThat(of(Optional.empty()).status()).isEqualTo(Status.WRONG_NUMBER);
    }

    @Test
    void wrongNumberBeforeLastConnectIsForgotten() {
        number.setLastWrongNumberAt(T1);
        number.setLastConnectedAt(T2);
        assertThat(of(Optional.empty()).status()).isEqualTo(Status.DISCONNECTED);
    }

    @Test
    void disabledNumber() {
        number.setDisabled(OFF);
        assertThat(of(Optional.of(LiveState.CONNECTED)))
                .isEqualTo(new PhoneNumberStatus(Status.DISABLED, DisabledBecause.NUMBER));
    }

    @Test
    void disabledBrandWinsOverNumber() {
        number.setDisabled(OFF);
        brand.setDisabled(OFF);
        assertThat(of(Optional.empty()).disabledBecause()).isEqualTo(DisabledBecause.BRAND);
    }

    @Test
    void customerDisabledWinsOverBrand() {
        brand.setDisabled(OFF);
        customer.setActive(false);
        assertThat(of(Optional.empty()).disabledBecause()).isEqualTo(DisabledBecause.CUSTOMER);
    }
}
