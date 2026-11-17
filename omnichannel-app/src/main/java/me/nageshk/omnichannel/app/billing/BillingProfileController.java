package me.nageshk.omnichannel.app.billing;

import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingProfileService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: a customer's billing profile, as printed on its invoices. */
@RestController
@RequestMapping("/api/billing/customers/{customerId}/billing-profile")
public class BillingProfileController {

    private final BillingProfileService service;

    public BillingProfileController(BillingProfileService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('subscription:view')")
    public ProfileView get(@PathVariable String customerId) {
        return service.get(customerId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('subscription:manage')")
    public ProfileView put(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody ProfileRequest req) {
        return service.put(customerId, req, me.userId());
    }
}
