package me.nageshk.omnichannel.security.entitlement;

import me.nageshk.omnichannel.common.billing.LimitKey;

/** Counts what a customer has of one thing a plan caps across the whole customer. One bean per such key. */
public interface LimitCounter {

    LimitKey key();

    long count(String customerId);
}
