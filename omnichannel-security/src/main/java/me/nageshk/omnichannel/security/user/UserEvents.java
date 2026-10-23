package me.nageshk.omnichannel.security.user;

import me.nageshk.omnichannel.db.user.Availability;
import me.nageshk.omnichannel.db.user.User;

/** Changes to a user that other features react to. */
public final class UserEvents {
    private UserEvents() {
    }

    /** The user's availability status changed; published after the user is saved. */
    public record AvailabilityChanged(User user, Availability.Status previous) {
    }
}
