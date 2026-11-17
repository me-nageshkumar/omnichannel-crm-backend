package me.nageshk.omnichannel.security.entitlement;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;

/**
 * What a customer may use right now.
 *
 * @param authorities the codes its users can hold at most; null when nothing is held back
 * @param limits the caps in force; a key that is absent is unlimited
 * @param readOnly nothing may be changed, sent or run
 * @param zone the billing zone the dates are midnights in
 */
public record Entitlement(SubscriptionState state, Set<String> authorities, Map<LimitKey, Integer> limits,
        boolean readOnly, String planName, Instant termStart, Instant termEnd, Instant graceEnd, String zone) {

    /** A customer without a plan while the platform leaves such customers alone. */
    public static final Entitlement UNRESTRICTED =
            new Entitlement(SubscriptionState.NONE, null, Map.of(), false, null, null, null, null, null);

    /** For a customer that cannot be found: nothing is allowed. */
    static final Entitlement NOTHING =
            new Entitlement(SubscriptionState.NONE, Set.of(), Map.of(), true, null, null, null, null, null);

    public boolean unrestricted() {
        return authorities == null;
    }

    public boolean allows(String code) {
        return authorities == null || authorities.contains(code);
    }

    public Optional<Integer> limit(LimitKey key) {
        return Optional.ofNullable(limits.get(key));
    }
}
