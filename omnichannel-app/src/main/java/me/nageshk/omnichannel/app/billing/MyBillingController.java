package me.nageshk.omnichannel.app.billing;

import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.SubscriptionService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own plan and usage, for whoever in the company may see billing. */
@RestController
@RequestMapping("/api/my/billing")
public class MyBillingController {

    private final SubscriptionService service;

    public MyBillingController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('billing:view')")
    public SubscriptionView subscription(@AuthenticationPrincipal AuthenticatedUser me) {
        if (me.customerId() == null) throw new NotFoundException("no customer");
        return service.view(me.customerId());
    }
}
