package me.nageshk.omnichannel.service.contact;

/** Spring events of contacts. */
public final class ContactEvents {

    private ContactEvents() {
    }

    /** {@code otherId} was merged into {@code survivorId}; the other contact is deleted right after. */
    public record ContactsMerged(String workspaceId, String survivorId, String otherId) {
    }
}
