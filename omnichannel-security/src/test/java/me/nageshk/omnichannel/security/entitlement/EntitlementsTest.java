package me.nageshk.omnichannel.security.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.billing.WithoutPlan;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntitlementsTest {

    private static final Instant START = Instant.parse("2026-11-30T18:30:00Z");
    private static final Instant END = Instant.parse("2026-12-31T18:30:00Z");

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-12-10T06:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    FeatureGroupRepository groups = mock(FeatureGroupRepository.class);
    BillingSettingsRepository settingsRepo = mock(BillingSettingsRepository.class);
    WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    TestClock clock = new TestClock();
    BillingSettings settings = new BillingSettings();
    Entitlements entitlements;

    @BeforeEach
    void setUp() {
        when(settingsRepo.findById(BillingSettings.ID)).thenAnswer(i -> Optional.of(settings));
        when(groups.findAll()).thenReturn(List.of(
                group("core", GroupCategory.DEFAULT, "user:manage", "billing:view"),
                group("inbox", GroupCategory.DEFAULT, "inbox:view"),
                group("crm", GroupCategory.PAID, "crm:view", "crm:edit"),
                group("campaigns", GroupCategory.PAID, "campaign:manage")));
        when(subscriptions.findByCustomerId("c1")).thenAnswer(i -> Optional.empty());
        entitlements = new Entitlements(subscriptions, groups, new BillingSettingsStore(settingsRepo), workspaces, clock);
    }

    @Test
    void aCustomerWithoutAPlanIsUnrestrictedByDefault() {
        Entitlement e = entitlements.of("c1");
        assertThat(e).isSameAs(Entitlement.UNRESTRICTED);
        assertThat(e.unrestricted()).isTrue();
        assertThat(e.allows("anything:at-all")).isTrue();
        assertThat(e.readOnly()).isFalse();
        assertThat(e.state()).isEqualTo(SubscriptionState.NONE);
    }

    @Test
    void aCustomerWithoutAPlanIsReadOnlyWhenTheSettingSaysSo() {
        settings.setCustomersWithoutPlan(WithoutPlan.READ_ONLY);
        Entitlement e = entitlements.of("c1");
        assertThat(e.unrestricted()).isTrue();
        assertThat(e.readOnly()).isTrue();
        assertThat(e.state()).isEqualTo(SubscriptionState.NONE);
    }

    @Test
    void aPlanGivesItsGroupsAndTheDefaultOnes() {
        has(subscription(Set.of("crm"), Map.of()));
        Entitlement e = entitlements.of("c1");
        assertThat(e.authorities()).containsExactlyInAnyOrder("user:manage", "billing:view", "inbox:view", "crm:view",
                "crm:edit");
        assertThat(e.allows("crm:view")).isTrue();
        assertThat(e.allows("campaign:manage")).isFalse();
        assertThat(e.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(e.planName()).isEqualTo("Gold");
        assertThat(e.termEnd()).isEqualTo(END);
        assertThat(e.graceEnd()).isEqualTo(Instant.parse("2027-01-07T18:30:00Z"));
    }

    @Test
    void aGroupAddOnAddsItsAuthorities() {
        Subscription s = subscription(Set.of("crm"), Map.of());
        s.getCurrent().getAddOns().add(groupAddOn("campaigns"));
        has(s);
        assertThat(entitlements.of("c1").allows("campaign:manage")).isTrue();
    }

    @Test
    void aLimitAddOnRaisesALimitThePlanSets() {
        Subscription s = subscription(Set.of(), Map.of(LimitKey.USERS, 5));
        s.getCurrent().getAddOns().add(limitAddOn(LimitKey.USERS, 5));
        s.getCurrent().getAddOns().add(limitAddOn(LimitKey.CHATBOTS, 3));
        has(s);
        Entitlement e = entitlements.of("c1");
        assertThat(e.limit(LimitKey.USERS)).contains(10);
        assertThat(e.limit(LimitKey.CHATBOTS)).isEmpty();
        assertThat(e.limit(LimitKey.BRANDS)).isEmpty();
    }

    @Test
    void aGroupThatNoLongerExistsIsIgnored() {
        has(subscription(Set.of("crm", "gone"), Map.of()));
        assertThat(entitlements.of("c1").allows("crm:view")).isTrue();
    }

    @Test
    void anExpiredPlanIsReadOnlyAndKeepsItsAuthorities() {
        has(subscription(Set.of("crm"), Map.of()));
        clock.now = Instant.parse("2027-01-08T00:00:00Z");
        Entitlement e = entitlements.of("c1");
        assertThat(e.state()).isEqualTo(SubscriptionState.EXPIRED);
        assertThat(e.readOnly()).isTrue();
        assertThat(e.allows("crm:view")).isTrue();
    }

    @Test
    void readsAreCachedButTheStateFollowsTheClock() {
        has(subscription(Set.of("crm"), Map.of()));
        clock.now = END.minusSeconds(10);
        assertThat(entitlements.of("c1").state()).isEqualTo(SubscriptionState.ACTIVE);
        clock.now = END.plusSeconds(10);
        assertThat(entitlements.of("c1").state()).isEqualTo(SubscriptionState.GRACE);
        verify(subscriptions, times(1)).findByCustomerId("c1");

        clock.now = clock.now.plus(Duration.ofSeconds(31));
        entitlements.of("c1");
        verify(subscriptions, times(2)).findByCustomerId("c1");
    }

    @Test
    void anEvictionIsSeenAtOnce() {
        has(subscription(Set.of(), Map.of()));
        assertThat(entitlements.of("c1").allows("crm:view")).isFalse();
        has(subscription(Set.of("crm"), Map.of()));
        assertThat(entitlements.of("c1").allows("crm:view")).isFalse();
        entitlements.evict("c1");
        assertThat(entitlements.of("c1").allows("crm:view")).isTrue();
    }

    @Test
    void aWorkspaceIsResolvedToItsCustomer() {
        Workspace w = new Workspace();
        w.setId("w1");
        w.setCustomerId("c1");
        when(workspaces.findById("w1")).thenReturn(Optional.of(w));
        when(workspaces.findById("missing")).thenReturn(Optional.empty());
        has(subscription(Set.of("crm"), Map.of()));

        assertThat(entitlements.allowsInWorkspace("w1", "crm:view")).isTrue();
        assertThat(entitlements.allowsInWorkspace("w1", "campaign:manage")).isFalse();
        Entitlement unknown = entitlements.ofWorkspace("missing");
        assertThat(unknown.allows("crm:view")).isFalse();
        assertThat(unknown.readOnly()).isTrue();
    }

    @Test
    void aReadThatOverlapsAnEvictionIsNotKept() {
        // the read sees the plan without the CRM, and while it is still loading the plan is changed and evicted
        Subscription before = subscription(Set.of(), Map.of());
        Subscription after = subscription(Set.of("crm"), Map.of());
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        when(subscriptions.findByCustomerId("c1")).thenAnswer(i -> {
            if (changed.compareAndSet(false, true)) {
                entitlements.evict("c1");
                return Optional.of(before);
            }
            return Optional.of(after);
        });

        entitlements.of("c1");

        assertThat(entitlements.of("c1").allows("crm:view")).isTrue();
    }

    @Test
    void aGroupReadThatOverlapsAnEvictionIsNotKept() {
        has(subscription(Set.of("crm"), Map.of()));
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        when(groups.findAll()).thenAnswer(i -> {
            if (changed.compareAndSet(false, true)) {
                entitlements.evictAll();
                return List.of(group("crm", GroupCategory.PAID, "crm:view", "crm:export"));
            }
            return List.of(group("crm", GroupCategory.PAID, "crm:view"));
        });

        entitlements.of("c1");

        assertThat(entitlements.of("c1").allows("crm:export")).isFalse();
    }

    @Test
    void aSettingsReadThatOverlapsAnEvictionIsNotKept() {
        BillingSettingsStore store = new BillingSettingsStore(settingsRepo);
        BillingSettings old = new BillingSettings();
        BillingSettings saved = new BillingSettings();
        saved.setCustomersWithoutPlan(WithoutPlan.READ_ONLY);
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        when(settingsRepo.findById(BillingSettings.ID)).thenAnswer(i -> {
            if (changed.compareAndSet(false, true)) {
                store.evict();
                return Optional.of(old);
            }
            return Optional.of(saved);
        });

        store.get();

        assertThat(store.get().getCustomersWithoutPlan()).isEqualTo(WithoutPlan.READ_ONLY);
    }

    private void has(Subscription s) {
        when(subscriptions.findByCustomerId("c1")).thenAnswer(i -> Optional.of(s));
    }

    private static Subscription subscription(Set<String> groupCodes, Map<LimitKey, Integer> limits) {
        TermSegment seg = new TermSegment();
        seg.setPlanCode("gold");
        seg.setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 1, null, List.of(), List.of(), List.of(), limits,
                Map.of()));
        seg.setGroupCodes(new HashSet<>(groupCodes));
        seg.setAddOns(new ArrayList<>());
        seg.setTermStart(START);
        seg.setTermEnd(END);
        Subscription s = new Subscription();
        s.setCustomerId("c1");
        s.setCurrent(seg);
        return s;
    }

    private static SubscriptionAddOn groupAddOn(String code) {
        SubscriptionAddOn a = new SubscriptionAddOn();
        a.setKind(AddOnKind.GROUP);
        a.setGroupCode(code);
        return a;
    }

    private static SubscriptionAddOn limitAddOn(LimitKey key, int quantity) {
        SubscriptionAddOn a = new SubscriptionAddOn();
        a.setKind(AddOnKind.LIMIT);
        a.setLimitKey(key);
        a.setQuantity(quantity);
        return a;
    }

    private static FeatureGroup group(String code, GroupCategory category, String... authorities) {
        FeatureGroup g = new FeatureGroup();
        g.setCode(code);
        g.setCategory(category);
        g.setAuthorities(new HashSet<>(Set.of(authorities)));
        return g;
    }

    @Test
    void hugeExtraUnitsDoNotWrapTheLimitAround() {
        Subscription s = subscription(Set.of(), Map.of(LimitKey.USERS, 5));
        s.getCurrent().getAddOns().add(limitAddOn(LimitKey.USERS, 2_000_000_000));
        s.getCurrent().getAddOns().add(limitAddOn(LimitKey.USERS, 2_000_000_000));
        has(s);
        assertThat(entitlements.of("c1").limit(LimitKey.USERS)).contains(Integer.MAX_VALUE);
    }
}
