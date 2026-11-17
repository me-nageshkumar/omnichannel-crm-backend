package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.BillingViews.GroupRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.GroupView;
import me.nageshk.omnichannel.service.billing.BillingViews.UngroupedView;
import me.nageshk.omnichannel.service.billing.FeatureGroupService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: the feature groups plans are made of. */
@RestController
@RequestMapping("/api/billing/feature-groups")
public class FeatureGroupController {

    static final String READ = "hasAnyAuthority('subscription:view', 'plan:manage')";
    static final String WRITE = "hasAuthority('plan:manage')";

    private final FeatureGroupService service;

    public FeatureGroupController(FeatureGroupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public List<GroupView> list() {
        return service.list();
    }

    @GetMapping("/ungrouped")
    @PreAuthorize(READ)
    public List<UngroupedView> ungrouped() {
        return service.ungrouped();
    }

    @GetMapping("/{code}/history")
    @PreAuthorize(READ)
    public List<EventView> history(@PathVariable String code) {
        return service.history(code);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView create(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody GroupRequest req) {
        return service.create(req, me.userId());
    }

    @PutMapping("/{code}")
    @PreAuthorize(WRITE)
    public GroupView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code,
            @RequestBody GroupRequest req) {
        return service.update(code, req, me.userId());
    }

    @DeleteMapping("/{code}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code) {
        service.delete(code, me.userId());
    }
}
