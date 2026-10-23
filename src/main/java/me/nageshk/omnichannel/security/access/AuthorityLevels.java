package me.nageshk.omnichannel.security.access;

import java.util.Optional;
import me.nageshk.omnichannel.db.access.RoleLevel;

/** Resolves an authority code to the level it lives at; empty if the code is not a known authority. */
@FunctionalInterface
public interface AuthorityLevels {

    /** The built-in enum only; for tests that need no catalogue. */
    AuthorityLevels BUILT_IN = code -> Authority.fromCode(code).map(Authority::level);

    Optional<RoleLevel> levelOf(String code);
}
