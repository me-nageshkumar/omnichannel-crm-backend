package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What automations need from the CRM; implemented by the application, which knows both modules. */
public interface AutomationCrm {

    /** True when the contact is a CRM contact on the number; false when it is not or the brand's CRM is off. */
    boolean inCrm(String workspaceId, String contactId, String phoneNumberId);

    /**
     * Puts the contact in the CRM on the number with a deal. A null pipeline means the brand's default; a null
     * lane its first open lane.
     *
     * @throws AutomationActionFailed with the reason when the CRM, the pipeline or the lane cannot be used
     */
    LeadResult addLead(LeadRequest r);

    /** The CRM's pipelines and lanes for the rule editor; a brand with the CRM off gets {@code (false, null, [])}. */
    CrmOptions options(String workspaceId);

    /**
     * Gives the contact's unowned CRM lead (the contact on the number and its open deals) to the user, for what the
     * user can see in the CRM; an owner already set is kept. Never throws for nothing to do: the CRM off, no CRM
     * contact on the number, the user not in the brand.
     */
    FillResult fillOwner(String workspaceId, String customerId, String contactId, String phoneNumberId,
            String userId);

    /**
     * The pipeline and the owner's name of each deal, for the chatbot report's runs list; one call per page. Deals not
     * in the brand are absent; a deal without an owner has a null owner name.
     */
    Map<String, DealInfo> dealInfo(String workspaceId, List<String> dealIds);

    record DealInfo(String pipelineId, String ownerName) {}

    /** What {@link #fillOwner} set; a null contact id and no deals when nothing changed. */
    record FillResult(String crmContactId, List<String> dealIds) {}

    record LeadRequest(String workspaceId, String customerId, String contactId, String phoneNumberId, String chatId,
            Instant firstMessageAt, String pipelineId, String stageId, String dealTitle, String ownerUserId) {}

    record LeadResult(String crmContactId, String dealId, String pipelineName, String laneName) {}

    record LaneOption(String id, String name, String type) {}

    record PipelineOption(String id, String name, boolean active, List<LaneOption> lanes) {}

    record CrmOptions(boolean enabled, String defaultPipelineId, List<PipelineOption> pipelines) {}
}
