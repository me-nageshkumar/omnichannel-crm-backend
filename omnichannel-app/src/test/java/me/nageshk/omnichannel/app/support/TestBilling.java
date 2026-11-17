package me.nageshk.omnichannel.app.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.security.entitlement.FeatureGroupSeeder;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Test-only helper that puts a customer on a plan by writing the subscription straight into the database. */
@Component
@Profile("test")
public class TestBilling {

    private final SubscriptionRepository subscriptions;
    private final FeatureGroupRepository groups;
    private final BillingSettingsRepository settings;
    private final FeatureGroupSeeder seeder;
    private final Entitlements entitlements;
    private final MongoTemplate mongo;
    private final Clock clock;

    public TestBilling(SubscriptionRepository subscriptions, FeatureGroupRepository groups,
            BillingSettingsRepository settings, FeatureGroupSeeder seeder, Entitlements entitlements,
            MongoTemplate mongo, Clock clock) {
        this.subscriptions = subscriptions;
        this.groups = groups;
        this.settings = settings;
        this.seeder = seeder;
        this.entitlements = entitlements;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** No subscriptions, plans or settings; the built-in feature groups as seeded. */
    public void reset() {
        subscriptions.deleteAll();
        settings.deleteAll();
        groups.deleteAll();
        mongo.remove(new Query(), "plans");
        mongo.remove(new Query(), "billingChanges");
        seeder.seed();
        entitlements.evictAll();
    }

    /** Puts the customer on a plan with these paid groups and limits, from yesterday for thirty days. */
    public Subscription assign(String customerId, Set<String> paidGroups, Map<LimitKey, Integer> limits) {
        Instant now = clock.instant();
        return assign(customerId, paidGroups, limits, now.minus(1, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS));
    }

    public Subscription assign(String customerId, Set<String> paidGroups, Map<LimitKey, Integer> limits,
            Instant start, Instant end) {
        Subscription s = subscriptions.findByCustomerId(customerId).orElseGet(Subscription::new);
        TermSegment seg = new TermSegment();
        seg.setPlanCode("test-plan");
        seg.setTerms(new PlanTerms("Test plan", BillingPeriod.MONTHLY, 1, null, List.of(), List.of(), List.of(),
                limits, Map.of()));
        seg.setGroupCodes(new HashSet<>(paidGroups));
        seg.setAddOns(new ArrayList<>());
        seg.setTermStart(start);
        seg.setTermEnd(end);
        s.setCustomerId(customerId);
        s.setCurrent(seg);
        s.setNext(null);
        s.setRevision(s.getRevision() + 1);
        Subscription saved = subscriptions.save(s);
        entitlements.evict(customerId);
        return saved;
    }

    public void remove(String customerId) {
        subscriptions.findByCustomerId(customerId).ifPresent(subscriptions::delete);
        entitlements.evict(customerId);
    }
}
