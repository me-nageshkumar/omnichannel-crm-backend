package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.security.entitlement.FeatureGroupSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FeatureGroupSeederIT {

    @Autowired FeatureGroupRepository groups;
    @Autowired FeatureGroupSeeder seeder;

    @Test
    void seedsTheNineBuiltInGroups() {
        groups.deleteAll();
        seeder.seed();
        assertThat(groups.findAllByOrderBySortOrderAscCodeAsc()).extracting(FeatureGroup::getCode)
                .containsExactly("core", "inbox", "contacts", "templates", "campaigns", "automations", "chatbots",
                        "ai", "crm");
        FeatureGroup core = groups.findById("core").orElseThrow();
        assertThat(core.isBuiltIn()).isTrue();
        assertThat(core.getCategory()).isEqualTo(GroupCategory.DEFAULT);
        assertThat(core.getAuthorities()).contains("billing:view", "billing:request", "user:manage");
        assertThat(groups.findById("crm").orElseThrow().getAuthorities()).contains("crm:view").doesNotContain("crm:enable");
        assertThat(groups.findById("ai").orElseThrow().getRequires()).containsExactly("chatbots");
    }

    @Test
    void putsAMissingCoreAuthorityBackAndLeavesOtherEditsAlone() {
        groups.deleteAll();
        seeder.seed();
        FeatureGroup core = groups.findById("core").orElseThrow();
        core.getAuthorities().remove("billing:view");
        groups.save(core);
        FeatureGroup crm = groups.findById("crm").orElseThrow();
        crm.setName("Sales CRM");
        crm.getAuthorities().remove("crm:export");
        groups.save(crm);

        seeder.seed();

        assertThat(groups.findById("core").orElseThrow().getAuthorities()).contains("billing:view");
        FeatureGroup after = groups.findById("crm").orElseThrow();
        assertThat(after.getName()).isEqualTo("Sales CRM");
        assertThat(after.getAuthorities()).doesNotContain("crm:export");
    }
}
