package me.nageshk.omnichannel.crm.access;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.platform.NumberDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmDefaults;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.stereotype.Service;

/** Builds the per-request {@link CrmContext}: brand, then enabled, then {@code crm:view}, then pipeline access. */
@Service
public class CrmAccess {

    private final AccessService access;
    private final CrmSettingsService settings;
    private final NumberDirectory numbers;
    private final PipelineAccessReader assignments;
    private final PipelineIds pipelineIds;

    CrmAccess(AccessService access, CrmSettingsService settings, NumberDirectory numbers,
            PipelineAccessReader assignments, PipelineIds pipelineIds) {
        this.access = access;
        this.settings = settings;
        this.numbers = numbers;
        this.assignments = assignments;
        this.pipelineIds = pipelineIds;
    }

    /** @throws me.nageshk.omnichannel.common.api.NotFoundException (404) without access to the brand;
     *  402 CRM_NOT_ENABLED when off; ForbiddenException (403) without crm:view */
    public CrmContext resolve(AuthenticatedUser me, String workspaceId) {
        Set<String> authorities = access.inWorkspace(me, workspaceId);
        CrmSettings s = settings.find(workspaceId).filter(CrmSettings::isEnabled)
                .orElseThrow(CrmErrors::notEnabled);
        return build(me, workspaceId, authorities, s);
    }

    /** As {@link #resolve} but a brand with the CRM off yields a context with {@code enabled=false} instead of 402. */
    public CrmContext resolveForStatus(AuthenticatedUser me, String workspaceId) {
        Set<String> authorities = access.inWorkspace(me, workspaceId);
        CrmSettings s = settings.find(workspaceId).orElseGet(() -> CrmDefaults.newSettings(workspaceId, me.customerId()));
        return build(me, workspaceId, authorities, s);
    }

    private CrmContext build(AuthenticatedUser me, String workspaceId, Set<String> authorities, CrmSettings s) {
        CrmContext check = new CrmContext(workspaceId, me.customerId(), me.userId(), authorities, s, List.of(),
                Set.of(), PipelineAccess.ALL);
        check.require("crm:view");
        if (!s.isEnabled()) {
            return new CrmContext(workspaceId, me.customerId(), me.userId(), authorities, s, List.of(), Set.of(),
                    new PipelineAccess(false, Set.of()));
        }
        List<PlatformNumber> brandNumbers = numbers.numbers(workspaceId);
        Set<String> chattable = numbers.chattable(me, workspaceId);
        PipelineAccess pipelines;
        if (check.viewAll()) {
            pipelines = PipelineAccess.ALL;
        } else {
            Set<String> mine = new HashSet<>(assignments.pipelineIdsOf(workspaceId, me.userId()));
            mine.retainAll(pipelineIds.existing(workspaceId));
            pipelines = new PipelineAccess(false, mine);
        }
        return new CrmContext(workspaceId, me.customerId(), me.userId(), authorities, s, brandNumbers, chattable,
                pipelines);
    }
}
