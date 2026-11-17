package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.refund.RefundService;
import me.nageshk.omnichannel.service.billing.refund.RefundService.RefundRequest;
import me.nageshk.omnichannel.service.billing.refund.RefundService.RefundView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: refunds of a customer's account credit. */
@RestController
@RequestMapping("/api/billing/customers/{customerId}/refunds")
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OrderController.VIEW)
    public List<RefundView> list(@PathVariable String customerId) {
        return service.list(customerId);
    }

    @PostMapping
    @PreAuthorize(StaffOrderController.REFUND)
    @ResponseStatus(HttpStatus.CREATED)
    public RefundView record(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody RefundRequest req) {
        return service.record(customerId, req, me.userId());
    }
}
