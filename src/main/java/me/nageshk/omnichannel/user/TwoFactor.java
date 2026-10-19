package me.nageshk.omnichannel.user;

/** Embedded two-factor state. {@code totpSecret} is null whenever {@code enabled} is false. */
public record TwoFactor(boolean enabled, String totpSecret) {

    public static TwoFactor disabled() {
        return new TwoFactor(false, null);
    }

    public static TwoFactor enabledWith(String secret) {
        return new TwoFactor(true, secret);
    }
}
