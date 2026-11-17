package me.nageshk.omnichannel.service.billing.order;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.LimitAddOn;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.FeatureGroupOrder;
import me.nageshk.omnichannel.service.billing.FeatureGroupService;
import me.nageshk.omnichannel.service.billing.PlanPricing;
import me.nageshk.omnichannel.service.billing.order.OrderViews.AddOnOption;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OptionsView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PlanOption;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.RenewalOption;
import me.nageshk.omnichannel.service.billing.pricing.OrderPricing;
import org.springframework.stereotype.Component;

/** What a customer may ask for now: a renewal, the plans above its own, and the add-ons its term offers. */
@Component
public class OrderOptions {

    private final OrderService orders;
    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final FeatureGroupService groups;
    private final BillingSettingsStore settings;
    private final java.time.Clock clock;

    public OrderOptions(OrderService orders, SubscriptionRepository subscriptions, PlanRepository plans,
            CustomerRepository customers, FeatureGroupService groups, BillingSettingsStore settings,
            java.time.Clock clock) {
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.customers = customers;
        this.groups = groups;
        this.settings = settings;
        this.clock = clock;
    }

    public OptionsView of(String customerId, Caller caller) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("customer not found"));
        BillingSettings s = settings.get();
        ZoneId zone = ZoneId.of(s.getZone());
        Instant now = clock.instant();
        Subscription sub = subscriptions.findByCustomerId(customerId).filter(x -> x.getCurrent() != null).orElse(null);
        SubscriptionState state = OrderService.stateOf(sub, s, zone, now);
        if (sub == null) {
            return new OptionsView(s.getPaymentInstructions(),
                    new RenewalOption(false, "There is no plan to renew; ask your account manager for one", null),
                    List.of(), List.of());
        }
        TermSegment current = SubscriptionClock.segmentAt(sub, now);
        PlanTerms held = current.getTerms();

        List<PlanOption> upgrades = new ArrayList<>();
        if (!sub.isCancelled()) {
            plans.findByStatus(PlanStatus.ACTIVE).stream().filter(Plan::isOffered)
                    .filter(p -> p.getRank() > held.rank()
                            || (p.getRank() == held.rank() && p.getPeriod().months() > held.period().months()))
                    .sorted(Comparator.comparingInt(Plan::getRank).thenComparingInt(p -> p.getPeriod().months())
                            .thenComparing(Plan::getCode))
                    .forEach(p -> upgrades.add(new PlanOption(p.getCode(), p.getName(), p.getPeriod(), p.getRank(),
                            PlanPricing.price(p.getItems(), p.getDiscountPercent()).discounted())));
        }

        List<AddOnOption> addOns = new ArrayList<>();
        if (state == SubscriptionState.ACTIVE) {
            Map<String, FeatureGroup> all = groups.byCode();
            Set<String> has = new HashSet<>(current.getGroupCodes());
            for (SubscriptionAddOn a : current.getAddOns()) if (a.getKind() == AddOnKind.GROUP) has.add(a.getGroupCode());
            held.addOns().stream().filter(i -> !has.contains(i.groupCode()) && all.containsKey(i.groupCode()))
                    .sorted(Comparator.comparing((PlanItem i) -> all.get(i.groupCode()), FeatureGroupOrder.COMPARATOR))
                    .forEach(i -> addOns.add(new AddOnOption(AddOnKind.GROUP, i.groupCode(),
                            all.get(i.groupCode()).getName(), null, null, 0, i.price())));
            for (LimitAddOn l : held.limitAddOns()) {
                addOns.add(new AddOnOption(AddOnKind.LIMIT, null, "Extra " + l.key().noun(), l.key(), l.key().label(),
                        l.unit(), l.unitPrice()));
            }
        }
        return new OptionsView(s.getPaymentInstructions(), renewal(customer, sub, state, s, zone, now, caller), upgrades,
                addOns);
    }

    /** Asks pricing itself, so the answer here and the answer to a real request cannot differ. */
    private RenewalOption renewal(Customer customer, Subscription sub, SubscriptionState state, BillingSettings s,
            ZoneId zone, Instant now, Caller caller) {
        try {
            OrderPricing.quote(orders.input(customer.getId(), new QuoteRequest(OrderKind.RENEWAL, null, null, null, null,
                    null, null, null), caller, s, customer.getBilling()));
            return new RenewalOption(true, null, null);
        } catch (ValidationException e) {
            LocalDate from = null;
            if (state == SubscriptionState.ACTIVE && sub.getNext() == null && !sub.isCancelled()) {
                LocalDate opens = SubscriptionClock.lastEnd(sub).atZone(zone).toLocalDate().minusDays(s.getRenewalWindowDays());
                if (now.atZone(zone).toLocalDate().isBefore(opens)) from = opens;
            }
            return new RenewalOption(false, String.join("; ", e.fields().values()), from);
        }
    }
}
