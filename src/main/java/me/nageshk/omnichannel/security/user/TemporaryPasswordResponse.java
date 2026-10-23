package me.nageshk.omnichannel.security.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The reply to an action that sets a temporary password: {@code { user, temporaryPassword }} on
 * create, {@code { temporaryPassword }} on reset. The only place a temporary password is returned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemporaryPasswordResponse(UserSummary user, String temporaryPassword) {

    public static TemporaryPasswordResponse created(UserSummary user, String temporaryPassword) {
        return new TemporaryPasswordResponse(user, temporaryPassword);
    }

    public static TemporaryPasswordResponse reset(String temporaryPassword) {
        return new TemporaryPasswordResponse(null, temporaryPassword);
    }
}
