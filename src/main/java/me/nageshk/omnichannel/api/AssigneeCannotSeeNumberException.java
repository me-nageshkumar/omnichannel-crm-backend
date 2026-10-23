package me.nageshk.omnichannel.api;

/**
 * A chat was to be assigned to someone who cannot open its number (not in the owning team, no view-all):
 * 422 {@code ASSIGNEE_CANNOT_SEE_NUMBER} (chat-assignment design §3.1).
 */
public class AssigneeCannotSeeNumberException extends RuntimeException {
    public static final String CODE = "ASSIGNEE_CANNOT_SEE_NUMBER";

    public AssigneeCannotSeeNumberException() {
        super("That person cannot see this chat's number");
    }
}
