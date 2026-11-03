package me.nageshk.omnichannel.app.automation;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.automation.AutomationOptionsService;
import me.nageshk.omnichannel.service.automation.AutomationRuleService;
import me.nageshk.omnichannel.service.automation.OptionsView;
import me.nageshk.omnichannel.service.automation.RuleRequest;
import me.nageshk.omnichannel.service.automation.RuleSummaryView;
import me.nageshk.omnichannel.service.automation.RuleView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Automation rules of a brand. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/automations")
class AutomationController {

    private final AutomationRuleService rules;
    private final AutomationOptionsService options;

    AutomationController(AutomationRuleService rules, AutomationOptionsService options) {
        this.rules = rules;
        this.options = options;
    }

    @GetMapping("/options")
    @PreAuthorize("@access.has(#workspaceId, 'automation:view')")
    OptionsView options(@PathVariable String workspaceId) {
        return options.options(workspaceId);
    }

    @GetMapping("/rules")
    @PreAuthorize("@access.has(#workspaceId, 'automation:view')")
    List<RuleSummaryView> list(@PathVariable String workspaceId) {
        return rules.list(workspaceId);
    }

    @PostMapping("/rules")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    ResponseEntity<RuleView> create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody RuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rules.create(me, workspaceId, req));
    }

    @GetMapping("/rules/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'automation:view')")
    RuleView get(@PathVariable String workspaceId, @PathVariable String id) {
        return rules.get(workspaceId, id);
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    RuleView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody RuleRequest req) {
        return rules.update(me, workspaceId, id, req);
    }

    @PostMapping("/rules/{id}/publish")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    RuleView publish(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return rules.publish(me, workspaceId, id);
    }

    @PostMapping("/rules/{id}/unpublish")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    RuleView unpublish(@PathVariable String workspaceId, @PathVariable String id) {
        return rules.unpublish(workspaceId, id);
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    ResponseEntity<Void> delete(@PathVariable String workspaceId, @PathVariable String id) {
        rules.delete(workspaceId, id);
        return ResponseEntity.noContent().build();
    }
}
