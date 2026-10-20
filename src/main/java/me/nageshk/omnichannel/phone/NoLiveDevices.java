package me.nageshk.omnichannel.phone;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Placeholder until the WebSocket layer reports live connections: nothing is ever live. */
@Component
public class NoLiveDevices implements LiveDevices {

    @Override
    public Optional<LiveState> stateOf(String phoneNumberId) {
        return Optional.empty();
    }
}
