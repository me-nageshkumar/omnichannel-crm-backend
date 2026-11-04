package me.nageshk.omnichannel.crm.contact;

import me.nageshk.omnichannel.crm.platform.PlatformAdClick;

/** The click-to-WhatsApp ad a contact came from. */
public record AdOrigin(String sourceId, String sourceUrl, String sourceApp, String headline, String clickId) {

    public static AdOrigin of(PlatformAdClick a) {
        return a == null ? null : new AdOrigin(a.sourceId(), a.sourceUrl(), a.sourceApp(), a.headline(), a.clickId());
    }

    /** CTWA_AD when the chat came from an ad, else INBOUND_CHAT. */
    public static OriginKind kindOf(PlatformAdClick a) {
        return a == null ? OriginKind.INBOUND_CHAT : OriginKind.CTWA_AD;
    }
}
