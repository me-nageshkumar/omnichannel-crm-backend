package me.nageshk.omnichannel.crm.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import org.junit.jupiter.api.Test;

/** Whether the CRM is on in a brand, and under which limits, once the customer may have a plan. */
class CrmSwitchTest {

    @Test
    void withoutAPlanTheBrandSwitchDecides() {
        assertThat(CrmSwitch.apply(Entitlement.UNRESTRICTED, stored(false, 10, 20)).isEnabled()).isFalse();
        CrmSettings on = CrmSwitch.apply(Entitlement.UNRESTRICTED, stored(true, 10, 20));
        assertThat(on.isEnabled()).isTrue();
        assertThat(on.getMaxPipelines()).isEqualTo(10);
        assertThat(on.getMaxCustomFields()).isEqualTo(20);
        assertThat(CrmSwitch.planDecides(Entitlement.UNRESTRICTED)).isFalse();
    }

    @Test
    void aPlanWithTheCrmSwitchesItOnWhateverTheBrandSwitchSays() {
        Entitlement plan = plan(Set.of("crm:view"), Map.of());
        assertThat(CrmSwitch.planDecides(plan)).isTrue();
        assertThat(CrmSwitch.on(plan)).isTrue();
        assertThat(CrmSwitch.apply(plan, stored(false, null, null)).isEnabled()).isTrue();
    }

    @Test
    void aPlanWithoutTheCrmSwitchesItOff() {
        Entitlement plan = plan(Set.of("inbox:view"), Map.of());
        assertThat(CrmSwitch.on(plan)).isFalse();
        assertThat(CrmSwitch.apply(plan, stored(true, null, null)).isEnabled()).isFalse();
    }

    @Test
    void thePlansLimitWinsAndTheBrandsFillsIn() {
        CrmSettings both = CrmSwitch.apply(plan(Set.of("crm:view"), Map.of(LimitKey.CRM_PIPELINES, 3)),
                stored(true, 10, 20));
        assertThat(both.getMaxPipelines()).isEqualTo(3);
        assertThat(both.getMaxCustomFields()).isEqualTo(20);
        CrmSettings neither = CrmSwitch.apply(plan(Set.of("crm:view"), Map.of()), stored(true, null, null));
        assertThat(neither.getMaxPipelines()).isNull();
        assertThat(neither.getMaxCustomFields()).isNull();
    }

    private static Entitlement plan(Set<String> authorities, Map<LimitKey, Integer> limits) {
        return new Entitlement(SubscriptionState.ACTIVE, authorities, limits, false, "Gold", null, null, null, "UTC");
    }

    private static CrmSettings stored(boolean enabled, Integer maxPipelines, Integer maxCustomFields) {
        CrmSettings s = new CrmSettings();
        s.setEnabled(enabled);
        s.setMaxPipelines(maxPipelines);
        s.setMaxCustomFields(maxCustomFields);
        return s;
    }
}
