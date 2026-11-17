package me.nageshk.omnichannel.service.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.Gstin;
import me.nageshk.omnichannel.common.billing.IndianStates;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Who a customer is on its invoices: its legal name, address, state and, if it has one, GSTIN. */
@Service
public class BillingProfileService {

    private final CustomerRepository customers;
    private final BillingAudit audit;
    private final BillingOrderRepository orders;
    private final Clock clock;

    public BillingProfileService(CustomerRepository customers, BillingAudit audit, BillingOrderRepository orders,
            Clock clock) {
        this.customers = customers;
        this.audit = audit;
        this.orders = orders;
        this.clock = clock;
    }

    public ProfileView get(String customerId) {
        return view(customer(customerId).getBilling());
    }

    /**
     * Staff edit. Refused while an order waits for payment if it would move the customer to another state: the
     * order's tax was worked out for the state it has.
     */
    public ProfileView put(String customerId, ProfileRequest req, String by) {
        return save(customerId, req, by, false);
    }

    /**
     * The customer's own edit. Refused while one of its orders waits for payment: the order was priced, and will
     * be invoiced, for the customer as it is.
     *
     * @throws me.nageshk.omnichannel.common.api.CodedException 409 ORDER_PENDING
     */
    public ProfileView putOwn(String customerId, ProfileRequest req, String by) {
        return save(customerId, req, by, true);
    }

    private ProfileView save(String customerId, ProfileRequest req, String by, boolean own) {
        Customer c = customer(customerId);
        Map<String, String> errors = new LinkedHashMap<>();
        String name = trim(req.legalName());
        String address = trim(req.address());
        String state = trim(req.stateCode());
        String gstin = trim(req.gstin());
        if (name.isEmpty()) errors.put("legalName", "is required");
        else if (name.length() > 200) errors.put("legalName", "at most 200 characters");
        if (address.isEmpty()) errors.put("address", "is required");
        else if (address.length() > 500) errors.put("address", "at most 500 characters");
        if (state.isEmpty()) errors.put("stateCode", "is required");
        else if (IndianStates.nameOf(state).isEmpty()) errors.put("stateCode", "is not a state");
        Gstin.problem(gstin, state).ifPresent(problem -> errors.put("gstin", problem));
        if (!errors.isEmpty()) throw new ValidationException(errors);
        boolean movesState = c.getBilling() != null && c.getBilling().stateCode() != null
                && !c.getBilling().stateCode().equals(state);
        if ((own || movesState) && hasOpenOrder(customerId)) {
            throw new CodedException(HttpStatus.CONFLICT, "ORDER_PENDING", null, own
                    ? "A request is waiting for payment; cancel it or wait until it is paid before changing these details"
                    : "An order is waiting for payment and was taxed for the customer's present state; cancel it first");
        }

        c.setBilling(new BillingProfile(name, address, state, gstin.isEmpty() ? null : gstin));
        customers.save(c);
        audit.record(BillingChange.SUBSCRIPTION, customerId, "PROFILE", "Billing profile saved for " + name, null, null, by);
        return view(c.getBilling());
    }

    /**
     * The customer's profile, once it is enough for an invoice.
     *
     * @throws me.nageshk.omnichannel.common.api.CodedException 409 BILLING_NOT_SET_UP while it is not
     */
    public BillingProfile require(String customerId) {
        BillingProfile p = customer(customerId).getBilling();
        if (p == null || !p.complete()) throw BillingSettingsService.notSetUp("customer billing profile");
        return p;
    }

    private boolean hasOpenOrder(String customerId) {
        Instant now = clock.instant();
        return orders.findByCustomerIdAndStatusOrderByRaisedAtAscIdAsc(customerId, OrderStatus.REQUESTED).stream()
                .anyMatch(o -> now.isBefore(o.getValidUntil()));
    }

    private Customer customer(String customerId) {
        return customers.findById(customerId).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static ProfileView view(BillingProfile p) {
        if (p == null) return new ProfileView(null, null, null, null, false);
        return new ProfileView(p.legalName(), p.address(), p.stateCode(), p.gstin(), p.complete());
    }
}
