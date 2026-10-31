package me.nageshk.omnichannel.crm.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.settings.dto.SettingsRequest;

/** Which editable settings a save changes, in words for the settings history (spec F C17). */
final class SettingsChanges {

    private static final Map<String, String> LABELS = labels();

    private SettingsChanges() {}

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("defaultCurrency", "default currency");
        m.put("dealRottingDays", "rotting days");
        m.put("allowChannelMove", "channel moves");
        m.put("timelineShowsWhatsappMessages", "channel messages on timelines");
        m.put("lifecycleStatuses", "lifecycle statuses");
        m.put("dealSources", "deal sources");
        m.put("taskTypes", "task types");
        m.put("lostReasons", "lost reasons");
        m.put("industries", "industries");
        return m;
    }

    /** The changed keys, in the order of the labels. */
    static List<String> between(CrmSettings current, SettingsRequest req) {
        List<String> changed = new ArrayList<>();
        check(changed, "defaultCurrency", current.getDefaultCurrency(), req.defaultCurrency());
        check(changed, "dealRottingDays", current.getDealRottingDays(), req.dealRottingDays());
        check(changed, "allowChannelMove", current.isAllowChannelMove(), req.allowChannelMove());
        check(changed, "timelineShowsWhatsappMessages", current.isTimelineShowsWhatsappMessages(),
                req.timelineShowsWhatsappMessages());
        checkList(changed, "lifecycleStatuses", current.getLifecycleStatuses(), req.lifecycleStatuses());
        checkList(changed, "dealSources", current.getDealSources(), req.dealSources());
        checkList(changed, "taskTypes", current.getTaskTypes(), req.taskTypes());
        checkList(changed, "lostReasons", current.getLostReasons(), req.lostReasons());
        checkList(changed, "industries", current.getIndustries(), req.industries());
        return changed;
    }

    static String summary(List<String> keys) {
        return "Updated CRM settings: " + keys.stream().map(LABELS::get).collect(Collectors.joining(", "));
    }

    private static void check(List<String> changed, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) changed.add(key);
    }

    private static void checkList(List<String> changed, String key, List<PicklistItem> before, List<PicklistItem> after) {
        check(changed, key, before == null ? List.of() : before, after == null ? List.of() : after);
    }
}
