package me.nageshk.omnichannel.crm.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import org.junit.jupiter.api.Test;

class CrmDefaultsTest {

    @Test
    void salesPipelineHasSevenStagesWithReferenceProbabilities() {
        List<CrmDefaults.StageSpec> stages = CrmDefaults.salesStages();
        assertThat(stages).extracting(CrmDefaults.StageSpec::name)
                .containsExactly("New", "Contacted", "Qualified", "Proposal", "Negotiation", "Won", "Lost");
        assertThat(stages).extracting(CrmDefaults.StageSpec::probability)
                .containsExactly(10, 25, 50, 70, 85, 100, 0);
        assertThat(stages).extracting(CrmDefaults.StageSpec::type)
                .containsExactly("OPEN", "OPEN", "OPEN", "OPEN", "OPEN", "WON", "LOST");
        assertThat(stages).extracting(CrmDefaults.StageSpec::position)
                .containsExactly(1024.0, 2048.0, 3072.0, 4096.0, 5120.0, 6144.0, 7168.0);
    }

    @Test
    void picklistsMatchReference() {
        CrmSettings s = CrmDefaults.newSettings("ws1", "c1");
        assertThat(keys(s.getLifecycleStatuses())).containsExactly("lead", "prospect", "customer", "lost");
        assertThat(s.getLifecycleStatuses()).filteredOn(PicklistItem::isDefault).extracting(PicklistItem::key)
                .containsExactly("lead");
        assertThat(keys(s.getDealSources()))
                .containsExactly("whatsapp", "facebook_lead", "website", "referral", "other");
        assertThat(keys(s.getTaskTypes())).containsExactly("call", "follow_up", "meeting", "email", "other");
        assertThat(keys(s.getLostReasons()))
                .containsExactly("price", "competitor", "no_response", "not_interested", "other");
        assertThat(s.getIndustries()).isEmpty();
        assertThat(s.getLifecycleStatuses()).extracting(PicklistItem::position).containsExactly(1, 2, 3, 4);
        assertThat(s.getLifecycleStatuses()).allMatch(PicklistItem::active);
        assertThat(s.getDefaultCurrency()).isEqualTo("INR");
        assertThat(s.getDealRottingDays()).isEqualTo(14);
        assertThat(s.isAllowChannelMove()).isTrue();
        assertThat(s.isTimelineShowsWhatsappMessages()).isTrue();
        assertThat(s.isEnabled()).isFalse();
        assertThat(s.getWorkspaceId()).isEqualTo("ws1");
        assertThat(s.getCustomerId()).isEqualTo("c1");
    }

    private static List<String> keys(List<PicklistItem> items) {
        return items.stream().map(PicklistItem::key).toList();
    }
}
