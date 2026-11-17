package me.nageshk.omnichannel.common.billing;

/** What a billing order does to a customer's subscription. */
public enum OrderKind {
    NEW, RENEWAL, UPGRADE, CHANGE, ADDON, ADJUST, CANCEL_ADDON, CANCEL_PLAN;

    /** Whether it replaces or ends a term, which makes every other open request of the customer stale. */
    public boolean changesTerm() {
        return this == NEW || this == RENEWAL || this == UPGRADE || this == CHANGE || this == CANCEL_PLAN;
    }
}
