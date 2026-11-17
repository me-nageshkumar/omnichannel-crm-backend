package me.nageshk.omnichannel.app.billing;

import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.order.OrderViews.AdjustRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.CancelRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
import me.nageshk.omnichannel.service.billing.order.StaffOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: adjustments and cancellations, which take effect at once. */
@RestController
@RequestMapping("/api/billing/customers/{customerId}")
public class StaffOrderController {

    static final String REFUND = "hasAuthority('refund:manage')";

    private final StaffOrderService service;

    public StaffOrderController(StaffOrderService service) {
        this.service = service;
    }

    /** Moving a term's end is a plan change; posting credit is money, and needs the right to refund as well. */
    @PostMapping("/adjustments")
    @PreAuthorize(OrderController.MANAGE)
    public OrderView adjust(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody AdjustRequest req) {
        if (StaffOrderService.postsCredit(req) && !me.hasAuthority("refund:manage")) {
            throw new ForbiddenException("Posting account credit needs the right to manage refunds");
        }
        return service.adjust(customerId, req, me.userId());
    }

    @PostMapping("/cancellations/quote")
    @PreAuthorize(REFUND)
    public QuoteView cancellationQuote(@PathVariable String customerId, @RequestBody CancelRequest req) {
        return service.cancellationQuote(customerId, req);
    }

    @PostMapping("/cancellations")
    @PreAuthorize(REFUND)
    public OrderView cancel(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody CancelRequest req) {
        return service.cancel(customerId, req, me.userId());
    }
}
