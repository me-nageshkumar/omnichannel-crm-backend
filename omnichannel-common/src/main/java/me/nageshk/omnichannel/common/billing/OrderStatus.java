package me.nageshk.omnichannel.common.billing;

/** REQUESTED until staff record the payment; ACTIVATING while its steps run; then ACTIVATED. The rest are ends. */
public enum OrderStatus { REQUESTED, ACTIVATING, ACTIVATED, CANCELLED, REJECTED, EXPIRED, SUPERSEDED }
