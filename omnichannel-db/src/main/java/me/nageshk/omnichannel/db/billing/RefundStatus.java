package me.nageshk.omnichannel.db.billing;

/** RECORDING until the account credit has been taken; FAILED when there was not enough. */
public enum RefundStatus { RECORDING, RECORDED, FAILED }
