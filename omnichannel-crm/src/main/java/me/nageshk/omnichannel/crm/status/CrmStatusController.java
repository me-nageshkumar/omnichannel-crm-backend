package me.nageshk.omnichannel.crm.status;

import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm")
public class CrmStatusController {

    private final CrmAccess crmAccess;

    CrmStatusController(CrmAccess crmAccess) {
        this.crmAccess = crmAccess;
    }

    /** Answers even when the CRM is off; that is how the console knows. */
    @GetMapping("/status")
    @PreAuthorize("@access.has(#workspaceId, 'crm:view')")
    public CrmStatusView status(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        CrmContext ctx = crmAccess.resolveForStatus(me, workspaceId);
        List<CrmStatusView.Phone> phones = ctx.numbers().stream()
                .map(n -> new CrmStatusView.Phone(n.id(), n.number(), n.label(), ctx.chattable().contains(n.id())))
                .toList();
        Object pipelineAccess = ctx.pipelines().all() ? "ALL" : ctx.pipelines().pipelineIds().stream().sorted().toList();
        List<String> held = ctx.authorities().stream().filter(c -> c.startsWith("crm:")).sorted().toList();
        return new CrmStatusView(ctx.settings().isEnabled(),
                new CrmStatusView.Limits(ctx.settings().getMaxPipelines(), ctx.settings().getMaxCustomFields()),
                phones, pipelineAccess, held, true);
    }
}
