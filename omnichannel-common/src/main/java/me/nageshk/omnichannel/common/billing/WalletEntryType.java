package me.nageshk.omnichannel.common.billing;

/** What moved a wallet's balance: a paid top-up, a message sent, a charge given back, a staff adjustment, a refund. */
public enum WalletEntryType { TOPUP, USAGE, REVERSAL, ADJUSTMENT, REFUND }
