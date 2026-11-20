package me.nageshk.omnichannel.meta.api;

import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;

/** Meta's {@code POST /{phone-number-id}/messages} for a Cloud number (spec 2026-11-20 §5.4). */
public interface CloudApi {

    /**
     * Sends {@code message} with {@code messaging_product "whatsapp"}. A person known only by Meta's user id is
     * addressed by {@code recipientUserId} ({@code recipient}, no {@code to}); else {@code message.to()} is used.
     * A refused token marks the connection invalid before the exception is thrown.
     *
     * @return Meta's message id ({@code wamid…})
     * @throws CloudSendException when Meta refuses or cannot be reached
     */
    String send(String phoneNumberId, CloudMessage message, String recipientUserId);

    /** Marks an incoming message read (the blue ticks); best effort, failures are logged. */
    void markRead(String phoneNumberId, String waMessageId);
}
