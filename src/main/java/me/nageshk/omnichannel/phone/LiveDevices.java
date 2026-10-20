package me.nageshk.omnichannel.phone;

import java.util.Optional;

/** Whether a number has a live extension connection right now, and whether its number is confirmed. */
public interface LiveDevices {

    enum LiveState { WAITING_FOR_LOGIN, CONNECTED }

    Optional<LiveState> stateOf(String phoneNumberId);
}
