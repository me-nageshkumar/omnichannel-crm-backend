package me.nageshk.omnichannel.db.page;

import java.time.Instant;

/** A host no longer in use whose links may still be open until {@code until} (the pages' lifetime after the change). */
public record FormerHost(String host, Instant until) {
}
