package me.nageshk.omnichannel.meta.api;

/**
 * How a connection's webhook reaches us: AUTO (the app's callback is ours), OVERRIDE (the app sends elsewhere, so each
 * WABA is pointed at us), MANUAL (the admin pastes the address and verify token into the app dashboard).
 */
public enum WebhookSetup { AUTO, OVERRIDE, MANUAL }
