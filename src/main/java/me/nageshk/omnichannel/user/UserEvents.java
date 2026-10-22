package me.nageshk.omnichannel.user;

/** Changes to a user that other features react to. */
public final class UserEvents {
    private UserEvents() {
    }

    /** The user's availability status changed; published after the user is saved. */
    public record AvailabilityChanged(User user, Availability.Status previous) {
    }
}
