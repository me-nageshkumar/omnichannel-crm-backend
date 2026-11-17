package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.security.entitlement.Limits;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.GroupRef;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.LimitKeyView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.LimitView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.NextTerm;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.PlanRef;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.stereotype.Service;

/**
 * A customer's plan, as platform staff assign it. Until orders exist a plan is assigned directly, with its dates
 * and any add-ons, and nothing is paid: such a term yields no credit later. Every change is one save of the
 * customer's one subscription document, is kept in the history, and reaches the customer's users at once.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final FeatureGroupService groups;
    private final BillingSettingsStore settings;
    private final Entitlements entitlements;
    private final Limits limits;
    private final BillingAudit audit;
    private final java.time.Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptions, PlanRepository plans, CustomerRepository customers,
            FeatureGroupService groups, BillingSettingsStore settings, Entitlements entitlements, Limits limits,
            BillingAudit audit, java.time.Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.customers = customers;
        this.groups = groups;
        this.settings = settings;
        this.entitlements = entitlements;
        this.limits = limits;
        this.audit = audit;
        this.clock = clock;
    }

    public List<LimitKeyView> limitKeys() {
        return Arrays.stream(LimitKey.values()).map(k -> new LimitKeyView(k, k.scope(), k.label())).toList();
    }

    public List<EventView> events(String customerId) {
        requireCustomer(customerId);
        return audit.history(BillingChange.SUBSCRIPTION, customerId);
    }

    /** @throws NotFoundException if there is no such customer */
    public SubscriptionView view(String customerId) {
        requireCustomer(customerId);
        Entitlement e = entitlements.of(customerId);
        Subscription sub = subscriptions.findByCustomerId(customerId).filter(s -> s.getCurrent() != null).orElse(null);
        Map<String, FeatureGroup> all = groups.byCode();
        List<GroupRef> held = new ArrayList<>();
        List<AddOnView> addOns = new ArrayList<>();
        PlanRef plan = null;
        if (sub != null) {
            TermSegment segment = SubscriptionClock.segmentAt(sub, clock.instant());
            PlanTerms terms = segment.getTerms();
            plan = new PlanRef(segment.getPlanCode(), terms.planName(), terms.period(), terms.rank());
            all.values().stream().filter(g -> g.getCategory() == GroupCategory.DEFAULT)
                    .sorted(FeatureGroupOrder.COMPARATOR).forEach(g -> held.add(ref(g, "DEFAULT")));
            segment.getGroupCodes().stream().map(all::get).filter(g -> g != null && g.getCategory() != GroupCategory.DEFAULT)
                    .sorted(FeatureGroupOrder.COMPARATOR).forEach(g -> held.add(ref(g, "PLAN")));
            for (SubscriptionAddOn a : segment.getAddOns()) {
                addOns.add(new AddOnView(a.getId(), a.getKind(), a.getGroupCode(), a.getLimitKey(), a.getQuantity()));
                FeatureGroup g = a.getKind() == AddOnKind.GROUP ? all.get(a.getGroupCode()) : null;
                if (g != null) held.add(ref(g, "ADDON"));
            }
        }
        List<LimitView> limitViews = new ArrayList<>();
        for (LimitKey key : LimitKey.values()) {
            OptionalLong used = limits.used(customerId, key);
            limitViews.add(new LimitView(key, key.label(), key.scope(), e.limit(key).orElse(null),
                    used.isPresent() ? used.getAsLong() : null));
        }
        boolean none = e.state() == SubscriptionState.NONE;
        // a renewal that was paid early and has not begun; once it begins it is simply the term
        TermSegment next = sub == null ? null : sub.getNext();
        NextTerm waiting = next == null || !clock.instant().isBefore(next.getTermStart()) ? null
                : new NextTerm(next.getTerms() == null ? null : next.getTerms().planName(), next.getTermStart(),
                        next.getTermEnd());
        Subscription stored = sub != null ? sub : subscriptions.findByCustomerId(customerId).orElse(null);
        BigDecimal credit = stored == null || stored.getCreditBalance() == null ? BigDecimal.ZERO : stored.getCreditBalance();
        return new SubscriptionView(e.state(), e.readOnly(), plan, none ? null : e.termStart(),
                none ? null : e.termEnd(), none ? null : e.graceEnd(), settings.get().getZone(), held, addOns,
                limitViews, sub == null ? 0 : sub.getRevision(), credit.setScale(2, java.math.RoundingMode.HALF_UP), waiting,
                sub != null && sub.isCancelled());
    }

    private void requireCustomer(String customerId) {
        if (customerId == null || !customers.existsById(customerId)) throw new NotFoundException("customer not found");
    }

    private static GroupRef ref(FeatureGroup g, String source) {
        return new GroupRef(g.getCode(), g.getName(), source);
    }
}
