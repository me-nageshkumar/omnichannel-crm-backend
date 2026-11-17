package me.nageshk.omnichannel.security.entitlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.billing.WithoutPlan;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import org.springframework.stereotype.Service;

/**
 * What each customer may use: its plan's feature groups, the default groups and its add-ons, with the plan's
 * limits. The state (active, grace, expired) is worked out from the dates on every call.
 *
 * <p>Subscriptions and feature groups are held in memory for {@link #TTL}; every code path that changes one must
 * call {@link #evict} or {@link #evictAll}, so a change is seen on the next request. The time limit only bounds how
 * stale a second server could be. Single-instance only, like {@code RoleCache}.
 */
@Service
public class Entitlements {

    static final Duration TTL = Duration.ofSeconds(30);

    private record CachedSubscription(Optional<Subscription> subscription, Instant loadedAt) {}

    private record CachedGroups(List<FeatureGroup> groups, Instant loadedAt) {}

    private final SubscriptionRepository subscriptions;
    private final FeatureGroupRepository groups;
    private final BillingSettingsStore settings;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    private final Map<String, CachedSubscription> byCustomer = new ConcurrentHashMap<>();
    /** A workspace never moves to another customer, so this is kept for good. */
    private final Map<String, String> customerOfWorkspace = new ConcurrentHashMap<>();
    private volatile CachedGroups cachedGroups;
    /** Counts evictions: a load that began before one is used once but never kept, so it cannot outlive the change. */
    private final AtomicLong generation = new AtomicLong();

    public Entitlements(SubscriptionRepository subscriptions, FeatureGroupRepository groups,
            BillingSettingsStore settings, WorkspaceRepository workspaces, Clock clock) {
        this.subscriptions = subscriptions;
        this.groups = groups;
        this.settings = settings;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    public Entitlement of(String customerId) {
        if (customerId == null) return Entitlement.NOTHING;
        Instant now = clock.instant();
        BillingSettings s = settings.get();
        Optional<Subscription> found = subscription(customerId, now);
        if (found.isEmpty()) {
            if (s.getCustomersWithoutPlan() == WithoutPlan.UNRESTRICTED) return Entitlement.UNRESTRICTED;
            return new Entitlement(SubscriptionState.NONE, null, Map.of(), true, null, null, null, null, s.getZone());
        }
        Subscription sub = found.get();
        ZoneId zone = ZoneId.of(s.getZone());
        TermSegment segment = SubscriptionClock.segmentAt(sub, now);
        SubscriptionState state = SubscriptionClock.stateAt(sub, s.getGraceDays(), zone, now);
        return new Entitlement(state, authoritiesOf(segment, now), limitsOf(segment),
                state == SubscriptionState.EXPIRED, segment.getTerms() == null ? null : segment.getTerms().planName(),
                segment.getTermStart(), SubscriptionClock.lastEnd(sub),
                SubscriptionClock.graceEnd(sub, s.getGraceDays(), zone), s.getZone());
    }

    /** The entitlement of the workspace's customer; a workspace that does not exist is allowed nothing. */
    public Entitlement ofWorkspace(String workspaceId) {
        if (workspaceId == null) return Entitlement.NOTHING;
        String customerId = customerOfWorkspace.get(workspaceId);
        if (customerId == null) {
            customerId = workspaces.findById(workspaceId).map(Workspace::getCustomerId).orElse(null);
            if (customerId == null) return Entitlement.NOTHING;
            customerOfWorkspace.put(workspaceId, customerId);
        }
        return of(customerId);
    }

    public boolean allows(String customerId, String code) {
        return of(customerId).allows(code);
    }

    public boolean allowsInWorkspace(String workspaceId, String code) {
        return ofWorkspace(workspaceId).allows(code);
    }

    /** After a change to this customer's subscription. */
    public synchronized void evict(String customerId) {
        generation.incrementAndGet();
        if (customerId != null) byCustomer.remove(customerId);
    }

    /** After a change to a feature group or to the billing settings. */
    public synchronized void evictAll() {
        generation.incrementAndGet();
        byCustomer.clear();
        cachedGroups = null;
        settings.evict();
    }

    private Optional<Subscription> subscription(String customerId, Instant now) {
        CachedSubscription cached = byCustomer.get(customerId);
        if (cached == null || !now.isBefore(cached.loadedAt().plus(TTL)) || now.isBefore(cached.loadedAt())) {
            long loadedAt = generation.get();
            cached = new CachedSubscription(subscriptions.findByCustomerId(customerId)
                    .filter(s -> s.getCurrent() != null), now);
            synchronized (this) {
                if (generation.get() == loadedAt) byCustomer.put(customerId, cached);
            }
        }
        return cached.subscription();
    }

    private List<FeatureGroup> groups(Instant now) {
        CachedGroups cached = cachedGroups;
        if (cached == null || !now.isBefore(cached.loadedAt().plus(TTL)) || now.isBefore(cached.loadedAt())) {
            long loadedAt = generation.get();
            cached = new CachedGroups(List.copyOf(groups.findAll()), now);
            synchronized (this) {
                if (generation.get() == loadedAt) cachedGroups = cached;
            }
        }
        return cached.groups();
    }

    private Set<String> authoritiesOf(TermSegment segment, Instant now) {
        Set<String> held = new HashSet<>(segment.getGroupCodes());
        for (SubscriptionAddOn a : segment.getAddOns()) {
            if (a.getKind() == AddOnKind.GROUP && a.getGroupCode() != null) held.add(a.getGroupCode());
        }
        Set<String> codes = new HashSet<>();
        for (FeatureGroup g : groups(now)) {
            if (g.getCategory() == GroupCategory.DEFAULT || held.contains(g.getCode())) codes.addAll(g.getAuthorities());
        }
        return Set.copyOf(codes);
    }

    private static Map<LimitKey, Integer> limitsOf(TermSegment segment) {
        Map<LimitKey, Integer> limits = new EnumMap<>(LimitKey.class);
        if (segment.getTerms() != null) limits.putAll(segment.getTerms().limits());
        for (SubscriptionAddOn a : segment.getAddOns()) {
            // extra units only mean something under a limit the plan sets
            if (a.getKind() == AddOnKind.LIMIT && limits.containsKey(a.getLimitKey())) {
                limits.merge(a.getLimitKey(), a.getQuantity(), Integer::sum);
            }
        }
        return Map.copyOf(limits);
    }
}
