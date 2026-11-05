package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** The digits of a number, which a webhook names as {@code display_phone_number}. */
class InboxDevicesTest {

    private final PhoneNumberRepository numbers = mock(PhoneNumberRepository.class);
    private final AccessService access = mock(AccessService.class);
    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final InboxDevices devices = new InboxDevices(numbers, access, registry);

    private static PhoneNumber number(String id, String digits) {
        PhoneNumber n = new PhoneNumber();
        n.setId(id);
        n.setWorkspaceId("ws");
        n.setNumber(digits);
        return n;
    }

    @Test
    void theDigitsHaveNoPlusAndAreReadOnce() {
        when(numbers.findById("pn-1")).thenReturn(Optional.of(number("pn-1", "+919928088743")));

        assertThat(devices.digitsOf("pn-1")).isEqualTo("919928088743");
        assertThat(devices.digitsOf("pn-1")).isEqualTo("919928088743");
        verify(numbers, times(1)).findById("pn-1");
    }

    @Test
    void aNumberSeenWhenFindingItsDeviceNeedsNoRead() {
        when(numbers.findByIdAndWorkspaceId("pn-1", "ws")).thenReturn(Optional.of(number("pn-1", "+919928088743")));
        when(access.isWorkspaceUsable("ws")).thenReturn(true);
        when(registry.find("pn-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> devices.requireReady("ws", "pn-1")).isInstanceOf(NoDeviceException.class);

        assertThat(devices.digitsOf("pn-1")).isEqualTo("919928088743");
        verify(numbers, never()).findById("pn-1");
    }

    @Test
    void anUnknownOrUnreadableNumberHasNoDigits() {
        when(numbers.findById("gone")).thenReturn(Optional.empty());
        when(numbers.findById("down")).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(devices.digitsOf("gone")).isNull();
        assertThat(devices.digitsOf("down")).isNull();
        assertThat(devices.digitsOf(null)).isNull();
    }
}
