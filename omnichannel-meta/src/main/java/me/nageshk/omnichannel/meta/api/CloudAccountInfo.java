package me.nageshk.omnichannel.meta.api;

/** A Cloud number as Meta knows it, beside our phone number ({@code phoneNumberId}, null before it is added). */
public record CloudAccountInfo(String phoneNumberId, String workspaceId, String customerId, String connectionId,
        String connectionName, String wabaId, String metaPhoneNumberId, String displayPhoneNumber, String verifiedName,
        String nameStatus, String qualityRating, String messagingLimitTier, boolean registered, CloudState state,
        String lastError) {

    /** Whether a message may be sent through it now (a number waiting for its first webhook may send). */
    public boolean canSend() {
        return state == CloudState.READY || state == CloudState.WAITING_FOR_WEBHOOK;
    }
}
