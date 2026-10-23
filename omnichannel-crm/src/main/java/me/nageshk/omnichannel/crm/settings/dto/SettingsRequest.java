package me.nageshk.omnichannel.crm.settings.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.common.PicklistItem;

/** Replaces the whole editable set; {@code version} is the one read. A null {@code dealRottingDays} restores 14. */
public record SettingsRequest(String defaultCurrency, String defaultPipelineId, Integer dealRottingDays,
        Boolean allowChannelMove, Boolean timelineShowsWhatsappMessages, List<PicklistItem> lifecycleStatuses,
        List<PicklistItem> dealSources, List<PicklistItem> taskTypes, List<PicklistItem> lostReasons,
        List<PicklistItem> industries, Long version) {}
