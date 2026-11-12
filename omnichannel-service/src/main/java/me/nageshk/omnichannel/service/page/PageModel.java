package me.nageshk.omnichannel.service.page;

import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;

/**
 * What a message page is rendered from. {@code preview} is the composer's variant: no beacon, inert actions, and
 * the header media inlined as {@code headerDataUrl} since the sandboxed iframe cannot fetch it.
 *
 * @param senderDigits the sending number without {@code +}, for the wa.me reply links and the header strip
 */
public record PageModel(CloudMessage message, String token, String baseUrl, String senderDigits, boolean preview,
        String headerDataUrl) {

    public PageModel(CloudMessage message, String token, String baseUrl, String senderDigits, boolean preview) {
        this(message, token, baseUrl, senderDigits, preview, null);
    }
}
