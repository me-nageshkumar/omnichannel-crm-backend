package me.nageshk.omnichannel.phone;

/** Changes to a number's set-up that other features react to. */
public final class PhoneNumberEvents {
    private PhoneNumberEvents() {
    }

    /** The owning team changed (either side may be null); published after the number is saved. */
    public record TeamChanged(PhoneNumber number, String previousTeamId) {
    }
}
