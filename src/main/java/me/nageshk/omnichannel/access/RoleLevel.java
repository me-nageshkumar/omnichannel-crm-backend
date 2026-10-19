package me.nageshk.omnichannel.access;

/** The scope an authority or role lives at. A role may hold authorities of its own level or below. */
public enum RoleLevel {
    PLATFORM, COMPANY, BRAND;

    public boolean mayHold(RoleLevel authorityLevel) {
        return switch (this) {
            case PLATFORM -> authorityLevel == PLATFORM;
            case COMPANY -> authorityLevel == COMPANY || authorityLevel == BRAND;
            case BRAND -> authorityLevel == BRAND;
        };
    }
}
