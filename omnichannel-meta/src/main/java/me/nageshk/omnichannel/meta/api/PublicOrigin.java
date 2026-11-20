package me.nageshk.omnichannel.meta.api;

/** The platform's public origin ({@code https://host}), where Meta reaches our webhook; null when none is set. */
public interface PublicOrigin {
    String platformOrigin();
}
