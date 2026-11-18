package me.nageshk.omnichannel.security.entitlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.common.billing.UsageMeter;

/**
 * What a customer may use right now.
 *
 * @param authorities the codes its users can hold at most; null when nothing is held back
 * @param limits the caps in force; a key that is absent is unlimited
 * @param readOnly nothing may be changed, sent or run
 * @param zone the billing zone the dates are midnights in
 */
public record Entitlement(SubscriptionState state, Set<String> authorities, Map<LimitKey, Integer> limits,
        boolean readOnly, String planName, Instant termStart, Instant termEnd, Instant graceEnd, String zone,
        Map<UsageMeter, BigDecimal> rates) {

    /** An entitlement that charges for nothing. */
    public Entitlement(SubscriptionState state, Set<String> authorities, Map<LimitKey, Integer> limits, boolean readOnly,
            String planName, Instant termStart, Instant termEnd, Instant graceEnd, String zone) {
        this(state, authorities, limits, readOnly, planName, termStart, termEnd, graceEnd, zone, Map.of());
    }

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

    /** What one unit of the meter costs this customer, in rupees; null when it is not charged. */
    public BigDecimal rate(UsageMeter meter) {
        BigDecimal rate = rates == null ? null : rates.get(meter);
        return rate == null || rate.signum() <= 0 ? null : rate;
    }

    public Optional<Integer> limit(LimitKey key) {
        return Optional.ofNullable(limits.get(key));
    }
}
