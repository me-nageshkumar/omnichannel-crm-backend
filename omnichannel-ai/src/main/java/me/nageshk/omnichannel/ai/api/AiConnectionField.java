package me.nageshk.omnichannel.ai.api;

/**
 * A plain (never secret) setting a provider's connections take beside the API key, such as an account id or an
 * endpoint. The connection form renders one input per field; the value reaches the provider in its credential.
 * A {@code URL} value is checked (https, public address) when the connection is saved only: a provider that calls it
 * must check the address again with {@code HttpAddressGuard} at call time, since DNS can change after the save.
 */
public record AiConnectionField(String key, String label, String help, Kind kind, boolean required) {
    public enum Kind { TEXT, URL }
}
