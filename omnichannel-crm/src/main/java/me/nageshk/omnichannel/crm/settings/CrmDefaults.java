package me.nageshk.omnichannel.crm.settings;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.crm.common.PicklistItem;

/** The reference defaults a brand's CRM settings and first pipeline start with. */
public final class CrmDefaults {

    /** A stage of the default pipeline; {@code type} is OPEN, WON or LOST. */
    public record StageSpec(String name, String color, double position, int probability, String type) {}

    private CrmDefaults() {}

    public static CrmSettings newSettings(String workspaceId, String customerId) {
        CrmSettings s = new CrmSettings();
        s.setWorkspaceId(workspaceId);
        s.setCustomerId(customerId);
        s.setEnabled(false);
        s.setDefaultCurrency("INR");
        s.setDealRottingDays(14);
        s.setAllowChannelMove(true);
        s.setTimelineShowsWhatsappMessages(true);
        s.setLifecycleStatuses(items("lead", List.of(
                new String[] {"lead", "Lead", "#3b82f6"}, new String[] {"prospect", "Prospect", "#8b5cf6"},
                new String[] {"customer", "Customer", "#22c55e"}, new String[] {"lost", "Lost", "#ef4444"})));
        s.setDealSources(items(null, List.of(
                new String[] {"whatsapp", "WhatsApp", "#22c55e"}, new String[] {"facebook_lead", "Facebook Lead", "#3b82f6"},
                new String[] {"website", "Website", "#8b5cf6"}, new String[] {"referral", "Referral", "#f59e0b"},
                new String[] {"other", "Other", "#6b7280"})));
        s.setTaskTypes(items(null, List.of(
                new String[] {"call", "Call", "#3b82f6"}, new String[] {"follow_up", "Follow-up", "#f59e0b"},
                new String[] {"meeting", "Meeting", "#8b5cf6"}, new String[] {"email", "Email", "#22c55e"},
                new String[] {"other", "Other", "#6b7280"})));
        s.setLostReasons(items(null, List.of(
                new String[] {"price", "Price", "#ef4444"}, new String[] {"competitor", "Competitor", "#f59e0b"},
                new String[] {"no_response", "No response", "#6b7280"},
                new String[] {"not_interested", "Not interested", "#8b5cf6"},
                new String[] {"other", "Other", "#9ca3af"})));
        s.setIndustries(new ArrayList<>());
        return s;
    }

    public static List<StageSpec> salesStages() {
        return List.of(
                new StageSpec("New", "#3b82f6", 1024, 10, "OPEN"),
                new StageSpec("Contacted", "#6366f1", 2048, 25, "OPEN"),
                new StageSpec("Qualified", "#8b5cf6", 3072, 50, "OPEN"),
                new StageSpec("Proposal", "#f59e0b", 4096, 70, "OPEN"),
                new StageSpec("Negotiation", "#f97316", 5120, 85, "OPEN"),
                new StageSpec("Won", "#22c55e", 6144, 100, "WON"),
                new StageSpec("Lost", "#ef4444", 7168, 0, "LOST"));
    }

    private static List<PicklistItem> items(String defaultKey, List<String[]> rows) {
        List<PicklistItem> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            out.add(new PicklistItem(r[0], r[1], r[2], i + 1, true, r[0].equals(defaultKey)));
        }
        return out;
    }
}
