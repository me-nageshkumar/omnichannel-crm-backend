package me.nageshk.omnichannel.db.page;

/** Whether a brand's link address has been shown to reach this server (spec 2026-11-18 link addresses §5.4). */
public enum PagesAddressStatus {
    UNCHECKED, WORKING, FAILING
}
