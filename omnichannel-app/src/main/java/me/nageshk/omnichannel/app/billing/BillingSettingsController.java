package me.nageshk.omnichannel.app.billing;

import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingSettingsService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: the billing zone, the grace period and how customers without a plan are treated. */
@RestController
@RequestMapping("/api/billing/settings")
public class BillingSettingsController {

    private final BillingSettingsService service;

    public BillingSettingsController(BillingSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('subscription:view', 'plan:manage', 'billing-settings:manage')")
    public SettingsView get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('billing-settings:manage')")
    public SettingsView update(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody SettingsRequest req) {
        return service.update(req, me.userId());
    }
}
