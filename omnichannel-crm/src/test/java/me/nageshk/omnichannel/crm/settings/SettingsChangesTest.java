package me.nageshk.omnichannel.crm.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.settings.dto.SettingsRequest;
import org.junit.jupiter.api.Test;

class SettingsChangesTest {

    private static final PicklistItem LATE = new PicklistItem("late", "Too late", null, 0, true, false);

    private static CrmSettings stored() {
        CrmSettings s = new CrmSettings();
        s.setDefaultCurrency("INR");
        s.setDealRottingDays(14);
        s.setAllowChannelMove(true);
        s.setTimelineShowsWhatsappMessages(false);
        s.setLostReasons(List.of(LATE));
        return s;
    }

    private static SettingsRequest request(String currency, Integer rotting, List<PicklistItem> lost) {
        return new SettingsRequest(currency, null, rotting, true, false, null, List.of(), List.of(), lost, List.of(), 1L);
    }

    @Test
    void listsChangedKeysInOrder() {
        assertThat(SettingsChanges.between(stored(), request("USD", 14, List.of()))).containsExactly("defaultCurrency",
                "lostReasons");
    }

    @Test
    void sameValuesAreNoChange() {
        assertThat(SettingsChanges.between(stored(), request("INR", 14, List.of(LATE)))).isEmpty();
    }

    @Test
    void nullAndEmptyPicklistAreEqual() {
        CrmSettings s = stored();
        s.setLifecycleStatuses(null);
        assertThat(SettingsChanges.between(s, request("INR", 14, List.of(LATE)))).isEmpty();
    }

    @Test
    void summaryUsesLabels() {
        assertThat(SettingsChanges.summary(List.of("defaultCurrency", "lostReasons")))
                .isEqualTo("Updated CRM settings: default currency, lost reasons");
        assertThat(SettingsChanges.summary(List.of("timelineShowsWhatsappMessages")))
                .isEqualTo("Updated CRM settings: channel messages on timelines");
    }
}
