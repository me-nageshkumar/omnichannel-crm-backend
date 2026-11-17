package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.SubscriptionService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AssignRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.LimitKeyView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: a customer's plan. */
@RestController
@RequestMapping("/api/billing")
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/limit-keys")
    @PreAuthorize(FeatureGroupController.READ)
    public List<LimitKeyView> limitKeys() {
        return service.limitKeys();
    }

    @GetMapping("/customers/{customerId}/subscription")
    @PreAuthorize("hasAuthority('subscription:view')")
    public SubscriptionView get(@PathVariable String customerId) {
        return service.view(customerId);
    }

    @GetMapping("/customers/{customerId}/subscription/events")
    @PreAuthorize("hasAuthority('subscription:view')")
    public List<EventView> events(@PathVariable String customerId) {
        return service.events(customerId);
    }

    @PutMapping("/customers/{customerId}/subscription")
    @PreAuthorize("hasAuthority('subscription:manage')")
    public SubscriptionView assign(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody AssignRequest req) {
        return service.assign(customerId, req, me.userId());
    }

    @DeleteMapping("/customers/{customerId}/subscription")
    @PreAuthorize("hasAuthority('subscription:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId) {
        service.remove(customerId, me.userId());
    }
}
