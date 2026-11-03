package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;

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

    record LeadRequest(String workspaceId, String customerId, String contactId, String phoneNumberId, String chatId,
            Instant firstMessageAt, String pipelineId, String stageId, String dealTitle, String ownerUserId) {}

    record LeadResult(String crmContactId, String dealId, String pipelineName, String laneName) {}

    record LaneOption(String id, String name, String type) {}

    record PipelineOption(String id, String name, boolean active, List<LaneOption> lanes) {}

    record CrmOptions(boolean enabled, String defaultPipelineId, List<PipelineOption> pipelines) {}
}
