package me.nageshk.omnichannel.service.ws;

import java.util.Optional;
import me.nageshk.omnichannel.service.phone.LiveDevices;
import org.springframework.stereotype.Component;

/** Live state from the registry: connected once the number is confirmed, waiting for login before. */
@Component
public class RegistryLiveDevices implements LiveDevices {

    private final DeviceRegistry registry;

    public RegistryLiveDevices(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<LiveState> stateOf(String phoneNumberId) {
        return registry.find(phoneNumberId)
                .filter(DeviceSession::isOpen)
                .map(d -> d.confirmed() ? LiveState.CONNECTED : LiveState.WAITING_FOR_LOGIN);
    }
}
