package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ReadOnlySafe;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.order.Caller;
import me.nageshk.omnichannel.service.billing.order.OrderOptions;
import me.nageshk.omnichannel.service.billing.order.OrderService;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OptionsView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
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

/**
 * A customer's own requests: to renew, to move to a higher plan, to buy an add-on. Asking is allowed while the
 * customer is read-only, since that is how it gets out of it.
 */
@RestController
@RequestMapping("/api/my/billing")
public class MyOrderController {

    static final String VIEW = "hasAuthority('billing:view')";
    static final String REQUEST = "hasAuthority('billing:request')";

    private final OrderService service;
    private final OrderOptions options;

    public MyOrderController(OrderService service, OrderOptions options) {
        this.service = service;
        this.options = options;
    }

    @GetMapping("/options")
    @PreAuthorize(VIEW)
    public OptionsView options(@AuthenticationPrincipal AuthenticatedUser me) {
        Caller caller = caller(me);
        return options.of(caller.customerId(), caller);
    }

    @PostMapping("/quotes")
    @PreAuthorize(REQUEST)
    @ReadOnlySafe
    public QuoteView quote(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody QuoteRequest req) {
        Caller caller = caller(me);
        return service.quote(caller.customerId(), req, caller);
    }

    @PostMapping("/orders")
    @PreAuthorize(REQUEST)
    @ReadOnlySafe
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView raise(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody QuoteRequest req) {
        Caller caller = caller(me);
        return service.raise(caller.customerId(), req, caller);
    }

    @GetMapping("/orders")
    @PreAuthorize(VIEW)
    public List<OrderView> list(@AuthenticationPrincipal AuthenticatedUser me) {
        return service.list(caller(me).customerId());
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize(VIEW)
    public OrderView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId) {
        return service.get(orderId, caller(me));
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize(REQUEST)
    @ReadOnlySafe
    public OrderView cancel(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String orderId) {
        return service.cancel(orderId, caller(me));
    }

    private static Caller caller(AuthenticatedUser me) {
        if (me.customerId() == null) throw new NotFoundException("no customer");
        return Caller.customer(me.userId(), me.customerId());
    }
}
