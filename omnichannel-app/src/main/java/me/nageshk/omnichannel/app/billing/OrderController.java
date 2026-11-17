package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.order.Caller;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.order.OrderService;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderPage;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PayRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.RejectRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: quotes and orders for any customer. */
@RestController
@RequestMapping("/api/billing")
public class OrderController {

    static final String VIEW = "hasAuthority('subscription:view')";
    static final String MANAGE = "hasAuthority('subscription:manage')";

    private final OrderService service;
    private final OrderActivator activator;

    public OrderController(OrderService service, OrderActivator activator) {
        this.service = service;
        this.activator = activator;
    }

    @PostMapping("/customers/{customerId}/quotes")
    @PreAuthorize(MANAGE)
    public QuoteView quote(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody QuoteRequest req) {
        return service.quote(customerId, req, Caller.staff(me.userId()));
    }

    @PostMapping("/customers/{customerId}/orders")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView raise(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody QuoteRequest req) {
        return service.raise(customerId, req, Caller.staff(me.userId()));
    }

    @GetMapping("/customers/{customerId}/orders")
    @PreAuthorize(VIEW)
    public List<OrderView> list(@PathVariable String customerId) {
        return service.list(customerId);
    }

    @GetMapping("/orders")
    @PreAuthorize(VIEW)
    public OrderPage queue(@RequestParam(required = false) OrderStatus status, @RequestParam(defaultValue = "0") int page) {
        return service.queue(status, page);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize(VIEW)
    public OrderView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId) {
        return service.get(orderId, Caller.staff(me.userId()));
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize(MANAGE)
    public OrderView cancel(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId) {
        return service.cancel(orderId, Caller.staff(me.userId()));
    }

    /** Records that the order was paid, which puts it into effect. */
    @PostMapping("/orders/{orderId}/pay")
    @PreAuthorize(MANAGE)
    public OrderView pay(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId,
            @RequestBody PayRequest req) {
        return activator.pay(orderId, req, me.userId());
    }

    @PostMapping("/orders/{orderId}/reject")
    @PreAuthorize(MANAGE)
    public OrderView reject(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId,
            @RequestBody RejectRequest req) {
        return service.reject(orderId, req.reason(), me.userId());
    }
}
