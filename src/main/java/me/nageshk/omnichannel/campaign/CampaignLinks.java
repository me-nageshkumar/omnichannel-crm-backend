package me.nageshk.omnichannel.campaign;

import java.util.Locale;

/** Campaign messages may not carry links. */
final class CampaignLinks {

    private CampaignLinks() {
    }

    static boolean containsLink(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("http://") || t.contains("https://") || t.contains("www.");
    }
}
