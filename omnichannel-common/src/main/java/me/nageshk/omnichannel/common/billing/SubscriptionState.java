package me.nageshk.omnichannel.common.billing;

/** Where a customer's subscription stands; always derived from its dates, never stored. */
public enum SubscriptionState { NONE, ACTIVE, GRACE, EXPIRED }
