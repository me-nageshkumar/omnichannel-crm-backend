package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingViews.DuplicateRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.BillingViews.PlanRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.PlanView;
import me.nageshk.omnichannel.service.billing.BillingViews.PriceRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.PriceView;
import me.nageshk.omnichannel.service.billing.PlanService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: the plans customers are put on. Every amount in a response is worked out here, never by a client. */
@RestController
@RequestMapping("/api/billing/plans")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(FeatureGroupController.READ)
    public List<PlanView> list(@RequestParam(required = false) PlanStatus status) {
        return service.list(status);
    }

    @GetMapping("/{code}")
    @PreAuthorize(FeatureGroupController.READ)
    public PlanView get(@PathVariable String code) {
        return service.get(code);
    }

    @GetMapping("/{code}/history")
    @PreAuthorize(FeatureGroupController.READ)
    public List<EventView> history(@PathVariable String code) {
        return service.history(code);
    }

    @PostMapping
    @PreAuthorize(FeatureGroupController.WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView create(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody PlanRequest req) {
        return service.create(req, me.userId());
    }

    /** The price a plan with these groups and this discount would have: for the editor, before anything is saved. */
    @PostMapping("/price")
    @PreAuthorize(FeatureGroupController.WRITE)
    public PriceView price(@RequestBody PriceRequest req) {
        return service.price(req);
    }

    @PutMapping("/{code}")
    @PreAuthorize(FeatureGroupController.WRITE)
    public PlanView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code,
            @RequestBody PlanRequest req) {
        return service.update(code, req, me.userId());
    }

    @PostMapping("/{code}/duplicate")
    @PreAuthorize(FeatureGroupController.WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView duplicate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code,
            @RequestBody DuplicateRequest req) {
        return service.duplicate(code, req, me.userId());
    }

    @PostMapping("/{code}/activate")
    @PreAuthorize(FeatureGroupController.WRITE)
    public PlanView activate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code) {
        return service.activate(code, me.userId());
    }

    @PostMapping("/{code}/retire")
    @PreAuthorize(FeatureGroupController.WRITE)
    public PlanView retire(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code) {
        return service.retire(code, me.userId());
    }

    @DeleteMapping("/{code}")
    @PreAuthorize(FeatureGroupController.WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String code) {
        service.delete(code, me.userId());
    }
}
