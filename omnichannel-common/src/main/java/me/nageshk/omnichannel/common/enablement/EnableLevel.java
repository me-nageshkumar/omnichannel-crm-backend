package me.nageshk.omnichannel.common.enablement;

/** Who switched something off, by scope: platform staff above a company's admins above a brand's managers. */
public enum EnableLevel {
    BRAND, COMPANY, PLATFORM;

    public boolean atLeast(EnableLevel other) {
        return compareTo(other) >= 0;
    }
}
