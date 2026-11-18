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

    /** A group as an installation stored it before groups recorded which built-in authorities they were given. */
    private FeatureGroup savedBeforeTracking(String code, String... without) {
        FeatureGroup g = groups.findById(code).orElseThrow();
        for (String c : without) g.getAuthorities().remove(c);
        g.setReleasedAuthorities(null);
        return groups.save(g);
    }

    @Test
    void anAuthorityReleasedLaterReachesItsExistingGroupOnce() {
        groups.deleteAll();
        seeder.seed();
        savedBeforeTracking("inbox", "inbox:view-bot");

        seeder.seed();

        FeatureGroup inbox = groups.findById("inbox").orElseThrow();
        assertThat(inbox.getAuthorities()).contains("inbox:view-bot");
        assertThat(inbox.getReleasedAuthorities()).contains("inbox:view-bot", "inbox:view-all");
        // the staff take it out again: it stays out
        inbox.getAuthorities().remove("inbox:view-bot");
        groups.save(inbox);
        seeder.seed();
        assertThat(groups.findById("inbox").orElseThrow().getAuthorities()).doesNotContain("inbox:view-bot");
    }

    @Test
    void anAuthorityTheStaffRemovedBeforeTrackingStaysRemoved() {
        groups.deleteAll();
        seeder.seed();
        savedBeforeTracking("inbox", "inbox:view-bot", "inbox:import-history");

        seeder.seed();

        assertThat(groups.findById("inbox").orElseThrow().getAuthorities()).contains("inbox:view-bot")
                .doesNotContain("inbox:import-history");
    }

    @Test
    void recordingWhatAGroupWasGivenIsNotAnEdit() {
        groups.deleteAll();
        seeder.seed();
        savedBeforeTracking("crm");

        seeder.seed();

        FeatureGroup crm = groups.findById("crm").orElseThrow();
        assertThat(crm.getReleasedAuthorities()).isNotNull();
        assertThat(crm.getUpdatedBy()).isNull();
    }

    @Test
    void aNewGroupRecordsWhatItWasGiven() {
        groups.deleteAll();
        seeder.seed();
        FeatureGroup chatbots = groups.findById("chatbots").orElseThrow();
        assertThat(chatbots.getReleasedAuthorities()).containsExactlyInAnyOrderElementsOf(chatbots.getAuthorities());
    }
}
