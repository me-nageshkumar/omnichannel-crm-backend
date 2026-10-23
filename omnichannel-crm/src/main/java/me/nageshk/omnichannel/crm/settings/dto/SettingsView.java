package me.nageshk.omnichannel.crm.settings.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.settings.CrmSettings;

/** The editable CRM settings of a brand (not the platform switch or limits). */
public record SettingsView(String defaultCurrency, String defaultPipelineId, Integer dealRottingDays,
        boolean allowChannelMove, boolean timelineShowsWhatsappMessages, List<PicklistItem> lifecycleStatuses,
        List<PicklistItem> dealSources, List<PicklistItem> taskTypes, List<PicklistItem> lostReasons,
        List<PicklistItem> industries, Long version) {

    public static SettingsView of(CrmSettings s) {
        return new SettingsView(s.getDefaultCurrency(), s.getDefaultPipelineId(), s.getDealRottingDays(),
                s.isAllowChannelMove(), s.isTimelineShowsWhatsappMessages(), s.getLifecycleStatuses(),
                s.getDealSources(), s.getTaskTypes(), s.getLostReasons(), s.getIndustries(), s.getVersion());
    }
}
