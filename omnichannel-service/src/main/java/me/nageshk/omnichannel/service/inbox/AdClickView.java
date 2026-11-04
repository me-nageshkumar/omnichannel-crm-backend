package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.AdClick;

/** The part of a click-to-WhatsApp ad the console shows; the raw context and click id stay server side. */
public record AdClickView(String sourceId, String sourceUrl, String sourceApp, String headline, String description) {

    static AdClickView of(AdClick a) {
        return a == null ? null
                : new AdClickView(a.sourceId(), a.sourceUrl(), a.sourceApp(), a.headline(), a.description());
    }
}
