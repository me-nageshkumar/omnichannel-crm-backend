package me.nageshk.omnichannel.security.phone;

/**
 * Some numbers may no longer keep their connection. {@code phoneNumberId} null means every number of
 * the workspace, or of the customer when {@code workspaceId} is null too.
 */
public record DeviceAccessChanged(String phoneNumberId, String customerId, String workspaceId, Reason reason) {

    public enum Reason { DISABLED, REVOKED, REPLACED, DELETED }
}
