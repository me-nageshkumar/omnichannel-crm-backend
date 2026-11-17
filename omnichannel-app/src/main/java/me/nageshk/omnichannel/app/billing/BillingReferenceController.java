package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.common.billing.IndianStates;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.StateView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed lists the billing screens choose from. */
@RestController
@RequestMapping("/api/billing")
public class BillingReferenceController {

    /** The states and union territories by GST state code. */
    @GetMapping("/states")
    @PreAuthorize(FeatureGroupController.READ)
    public List<StateView> states() {
        return IndianStates.all().stream().map(s -> new StateView(s.code(), s.name())).toList();
    }
}
