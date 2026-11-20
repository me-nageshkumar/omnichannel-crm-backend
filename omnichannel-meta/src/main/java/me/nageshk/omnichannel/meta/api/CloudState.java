package me.nageshk.omnichannel.meta.api;

/** What a Cloud number can do now, most blocking first: token refused, not registered, no webhook yet, ready. */
public enum CloudState { READY, NOT_REGISTERED, WAITING_FOR_WEBHOOK, TOKEN_INVALID }
