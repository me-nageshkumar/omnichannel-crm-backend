package me.nageshk.omnichannel.app.crm;

import java.util.Comparator;
import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.contact.AdOrigin;
import me.nageshk.omnichannel.crm.contact.CaptureService;
import me.nageshk.omnichannel.crm.contact.Origin;
import me.nageshk.omnichannel.crm.contact.dto.CaptureResult;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.pipeline.StageType;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformAdClick;
import me.nageshk.omnichannel.crm.platform.PlatformChat;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import me.nageshk.omnichannel.service.automation.AutomationActionFailed;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import org.springframework.stereotype.Component;

@Component
class AutomationCrmAdapter implements AutomationCrm {

    private final CrmSettingsService settings;
    private final CaptureService capture;
    private final CrmAccess access;
    private final CrmPipelineService pipelines;
    private final ContactDirectory contacts;
    private final ChatDirectory chats;

    AutomationCrmAdapter(CrmSettingsService settings, CaptureService capture, CrmAccess access,
            CrmPipelineService pipelines, ContactDirectory contacts,
            ChatDirectory chats) {
        this.settings = settings;
        this.capture = capture;
        this.access = access;
        this.pipelines = pipelines;
        this.contacts = contacts;
        this.chats = chats;
    }

    @Override
    public boolean inCrm(String workspaceId, String contactId, String phoneNumberId) {
        return settings.find(workspaceId).filter(CrmSettings::isEnabled).isPresent()
                && capture.inCrm(workspaceId, contactId, phoneNumberId);
    }

    @Override
    public LeadResult addLead(LeadRequest r) {
        if (enabled(r.workspaceId()) == null) throw new AutomationActionFailed("CRM is off for this brand");
        CrmContext ctx = access.forSystem(r.workspaceId(), r.customerId());
        String pipelineId = r.pipelineId() != null ? r.pipelineId() : ctx.settings().getDefaultPipelineId();
        if (pipelineId == null) throw new AutomationActionFailed("The brand has no default pipeline");
        CrmPipeline pipeline;
        try {
            pipeline = pipelines.get(ctx, pipelineId);
        } catch (NotFoundException e) {
            throw new AutomationActionFailed("The pipeline no longer exists");
        }
        if (!pipeline.isActive()) throw new AutomationActionFailed("The pipeline is not active");
        if (r.stageId() != null && lane(pipeline, r.stageId()) == null) {
            throw new AutomationActionFailed("The lane no longer exists");
        }
        PlatformContact contact = contacts.find(r.workspaceId(), r.contactId())
                .orElseThrow(() -> new AutomationActionFailed("The contact no longer exists"));
        PlatformNumber number = ctx.numbers().stream().filter(n -> n.id().equals(r.phoneNumberId())).findFirst()
                .orElseThrow(() -> new AutomationActionFailed("The number no longer exists"));
        PlatformAdClick ad = chats.find(r.workspaceId(), r.chatId()).map(PlatformChat::ad).orElse(null);
        Origin origin = new Origin(AdOrigin.kindOf(ad), number.id(), r.chatId(), r.firstMessageAt(), "system",
                AdOrigin.of(ad));
        CaptureResult res;
        try {
            res = capture.captureForAutomation(ctx, contact, number, origin, pipelineId, r.stageId(), r.dealTitle(),
                    r.ownerUserId());
        } catch (NotFoundException e) {
            throw new AutomationActionFailed("The lane no longer exists");
        }
        CrmStage placed = lane(pipeline, res.deal().stageId());
        return new LeadResult(res.contact().id(), res.deal().id(), pipeline.getName(),
                placed == null ? null : placed.getName());
    }

    @Override
    public CrmOptions options(String workspaceId) {
        CrmSettings s = enabled(workspaceId);
        if (s == null) return new CrmOptions(false, null, List.of());
        CrmContext ctx = access.forSystem(workspaceId, s.getCustomerId());
        List<PipelineOption> list = pipelines.list(ctx).stream()
                .map(p -> new PipelineOption(p.getId(), p.getName(), p.isActive(), p.getStages().stream()
                        .sorted(Comparator.comparingDouble(CrmStage::getPosition))
                        .map(st -> new LaneOption(st.getId(), st.getName(), type(st))).toList()))
                .toList();
        return new CrmOptions(true, s.getDefaultPipelineId(), list);
    }

    /** The brand's settings when its CRM is on, with the default pipeline made if the CRM has not yet done so. */
    private CrmSettings enabled(String workspaceId) {
        CrmSettings s = settings.find(workspaceId).filter(CrmSettings::isEnabled).orElse(null);
        if (s != null && s.getDefaultPipelineId() == null) {
            pipelines.ensureDefault(workspaceId, s.getCustomerId(), "system");
            s = settings.find(workspaceId).filter(CrmSettings::isEnabled).orElse(null);
        }
        return s;
    }

    private static String type(CrmStage st) {
        return (st.getType() == null ? StageType.OPEN : st.getType()).name();
    }

    private static CrmStage lane(CrmPipeline p, String stageId) {
        return p.getStages().stream().filter(st -> stageId.equals(st.getId())).findFirst().orElse(null);
    }
}
