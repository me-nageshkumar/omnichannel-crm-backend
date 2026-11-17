package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.security.entitlement.FeatureGroupSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EntitlementsIT {

    @Autowired Entitlements entitlements;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired FeatureGroupRepository groups;
    @Autowired FeatureGroupSeeder seeder;

    @BeforeEach
    void clean() {
        subscriptions.deleteAll();
        groups.deleteAll();
        seeder.seed();
        entitlements.evictAll();
    }

    @Test
    void aStoredSubscriptionGivesTheSeededGroups() {
        assertThat(entitlements.of("ent-c1")).isSameAs(Entitlement.UNRESTRICTED);

        TermSegment seg = new TermSegment();
        seg.setPlanCode("gold");
        seg.setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 1, null, List.of(), List.of(), List.of(),
                Map.of(LimitKey.USERS, 5), Map.of()));
        seg.setGroupCodes(Set.of("crm"));
        seg.setTermStart(Instant.now().minus(1, ChronoUnit.DAYS));
        seg.setTermEnd(Instant.now().plus(20, ChronoUnit.DAYS));
        Subscription s = new Subscription();
        s.setCustomerId("ent-c1");
        s.setCurrent(seg);
        subscriptions.save(s);
        entitlements.evict("ent-c1");

        Entitlement e = entitlements.of("ent-c1");
        assertThat(e.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(e.authorities()).contains("crm:view", "inbox:send", "contact:view", "template:view", "user:manage",
                "billing:view").doesNotContain("campaign:manage", "chatbot:manage", "ai:manage", "crm:enable");
        assertThat(e.limit(LimitKey.USERS)).contains(5);
        assertThat(e.zone()).isEqualTo("Asia/Kolkata");
    }
}
