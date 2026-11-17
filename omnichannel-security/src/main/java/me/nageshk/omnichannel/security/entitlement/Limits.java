package me.nageshk.omnichannel.security.entitlement;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The caps of a customer's plan. Ask before creating or re-enabling something; what already exists is never
 * touched, so a cap lowered below what a customer has only stops new ones.
 *
 * <p>Counting and creating are two steps: two requests at the same moment can end one over the cap.
 */
@Service
public class Limits {

    public static final String LIMIT_REACHED = "LIMIT_REACHED";

    private final Entitlements entitlements;
    private final Map<LimitKey, LimitCounter> counters = new EnumMap<>(LimitKey.class);

    public Limits(Entitlements entitlements, List<LimitCounter> counters) {
        this.entitlements = entitlements;
        counters.forEach(c -> this.counters.put(c.key(), c));
    }

    /** For a key counted across the customer. A null customer (a platform user) is never capped. */
    public void require(String customerId, LimitKey key) {
        if (customerId == null) return;
        Optional<Integer> limit = entitlements.of(customerId).limit(key);
        if (limit.isEmpty()) return;
        LimitCounter counter = counters.get(key);
        if (counter == null) throw new IllegalStateException("no counter for " + key);
        check(key, limit.get(), counter.count(customerId));
    }

    /** For a key the caller counts itself (per brand, per chatbot): {@code used} is what exists now. */
    public void require(String customerId, LimitKey key, long used) {
        if (customerId == null) return;
        entitlements.of(customerId).limit(key).ifPresent(limit -> check(key, limit, used));
    }

    /** The cap in force, or empty when the plan sets none. */
    public Optional<Integer> effective(String customerId, LimitKey key) {
        return customerId == null ? Optional.empty() : entitlements.of(customerId).limit(key);
    }

    /** What the customer has now; empty for a key that is not counted across the customer. */
    public OptionalLong used(String customerId, LimitKey key) {
        LimitCounter counter = counters.get(key);
        return counter == null || customerId == null ? OptionalLong.empty() : OptionalLong.of(counter.count(customerId));
    }

    private static void check(LimitKey key, int limit, long used) {
        if (used < limit) return;
        throw reached(key, limit, used);
    }

    public static CodedException reached(LimitKey key, int limit, long used) {
        return new CodedException(HttpStatus.CONFLICT, LIMIT_REACHED, null,
                "Your plan allows " + limit + " " + key.noun(),
                Map.of("key", key.name(), "limit", String.valueOf(limit), "used", String.valueOf(used)));
    }
}
