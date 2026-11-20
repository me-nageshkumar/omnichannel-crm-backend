package me.nageshk.omnichannel.meta.api;

import java.util.List;

/** The host's numbers of a Meta connection (labels live with the host's phone numbers). */
public interface ConnectionNumbers {
    List<ConnectionNumber> numbers(String connectionId);
}
