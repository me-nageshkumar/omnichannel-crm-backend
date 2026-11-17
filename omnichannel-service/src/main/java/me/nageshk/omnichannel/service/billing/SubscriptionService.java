package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.GroupCategory;
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
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.security.entitlement.Limits;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AssignRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.GroupRef;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.LimitKeyView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.LimitView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.PlanRef;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * A customer's plan, as platform staff assign it. Until orders exist a plan is assigned directly, with its dates
 * and any add-ons, and nothing is paid: such a term yields no credit later. Every change is one save of the
 * customer's one subscription document, is kept in the history, and reaches the customer's users at once.
 */
@Service
public class SubscriptionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

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
        return new SubscriptionView(e.state(), e.readOnly(), plan, none ? null : e.termStart(),
                none ? null : e.termEnd(), none ? null : e.graceEnd(), settings.get().getZone(), held, addOns,
                limitViews, sub == null ? 0 : sub.getRevision());
    }

    public SubscriptionView assign(String customerId, AssignRequest req, String by) {
        requireCustomer(customerId);
        Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
        boolean had = sub != null && sub.getCurrent() != null;
        if (had && (req.revision() == null || req.revision() != sub.getRevision())) {
            throw new OptimisticLockingFailureException("the subscription changed since it was read");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Plan plan = req.planCode() == null ? null : plans.findById(req.planCode()).orElse(null);
        if (plan == null) errors.put("planCode", "is not a plan");
        else if (plan.getStatus() != PlanStatus.ACTIVE
                && !(plan.getStatus() == PlanStatus.RETIRED && had && plan.getCode().equals(sub.getCurrent().getPlanCode()))) {
            errors.put("planCode", "Only an active plan can be assigned");
        }
        if (req.startDate() == null) errors.put("startDate", "is required");
        if (req.endDate() == null) errors.put("endDate", "is required");
        else if (req.startDate() != null && !req.endDate().isAfter(req.startDate())) {
            errors.put("endDate", "must be after the start date");
        }
        List<SubscriptionAddOn> addOns = plan == null ? List.of() : addOns(plan, req.addOns(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        ZoneId zone = settings.zone();
        Instant start = req.startDate().atStartOfDay(zone).toInstant();
        TermSegment segment = new TermSegment();
        segment.setPlanCode(plan.getCode());
        segment.setPlanRevision(plan.getRevision());
        segment.setTerms(PlanTerms.of(plan));
        Set<String> groupCodes = new HashSet<>();
        plan.getItems().forEach(i -> groupCodes.add(i.groupCode()));
        segment.setGroupCodes(groupCodes);
        addOns.forEach(a -> a.setCoveredFrom(start));
        segment.setAddOns(addOns);
        segment.setTermStart(start);
        segment.setTermEnd(req.endDate().atStartOfDay(zone).toInstant());

        if (sub == null) {
            sub = new Subscription();
            sub.setCustomerId(customerId);
        }
        sub.setCurrent(segment);
        sub.setNext(null);
        sub.setAnchorDay(req.startDate().getDayOfMonth());
        sub.setTermRevision(sub.getTermRevision() + 1);
        sub.setRevision(sub.getRevision() + 1);
        subscriptions.save(sub);
        audit.record(BillingChange.SUBSCRIPTION, customerId, had ? "CHANGED" : "ASSIGNED",
                (had ? "Changed to " : "Assigned ") + plan.getName() + ", " + DAY.format(req.startDate()) + " to "
                        + DAY.format(req.endDate().minusDays(1))
                        + (addOns.isEmpty() ? "" : ", with " + addOns.size() + " add-on" + (addOns.size() == 1 ? "" : "s")),
                null, snapshot(segment), by);
        entitlements.evict(customerId);
        return view(customerId);
    }

    /** Takes the customer off its plan; it is then treated as any customer without one. */
    public void remove(String customerId, String by) {
        requireCustomer(customerId);
        Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
        if (sub == null) return;
        subscriptions.delete(sub);
        audit.record(BillingChange.SUBSCRIPTION, customerId, "REMOVED", "Plan removed",
                sub.getCurrent() == null ? null : snapshot(sub.getCurrent()), null, by);
        entitlements.evict(customerId);
    }

    /** The add-ons of an assignment, priced as the plan offers them (0 when it does not). */
    private List<SubscriptionAddOn> addOns(Plan plan, List<AddOnRequest> requested, Map<String, String> errors) {
        List<SubscriptionAddOn> out = new ArrayList<>();
        if (requested == null) return out;
        Map<String, FeatureGroup> all = groups.byCode();
        Set<String> included = new HashSet<>();
        plan.getItems().forEach(i -> included.add(i.groupCode()));
        Set<String> added = new HashSet<>();
        Set<LimitKey> raised = new HashSet<>();
        for (AddOnRequest r : requested) {
            if (r == null || r.kind() == null) {
                errors.putIfAbsent("addOns", "each add-on needs a kind");
                continue;
            }
            SubscriptionAddOn a = new SubscriptionAddOn();
            a.setId(UUID.randomUUID().toString());
            a.setKind(r.kind());
            if (r.kind() == AddOnKind.GROUP) {
                FeatureGroup g = r.groupCode() == null ? null : all.get(r.groupCode());
                if (g == null) errors.putIfAbsent("addOns", r.groupCode() + " is not a feature group");
                else if (g.getCategory() != GroupCategory.PAID) errors.putIfAbsent("addOns", g.getName() + " is in every plan already");
                else if (included.contains(g.getCode())) errors.putIfAbsent("addOns", g.getName() + " is in the plan already");
                else if (!added.add(g.getCode())) errors.putIfAbsent("addOns", g.getName() + " is listed twice");
                a.setGroupCode(r.groupCode());
                a.setUnitPrice(plan.getAddOns().stream().filter(i -> i.groupCode().equals(r.groupCode()))
                        .map(PlanItem::price).findFirst().orElse(BigDecimal.ZERO));
            } else {
                LimitKey key = r.limitKey();
                int quantity = r.quantity() == null ? 0 : r.quantity();
                if (key == null) errors.putIfAbsent("addOns", "extra units need a limit");
                else if (!plan.getLimits().containsKey(key)) {
                    errors.putIfAbsent("addOns", "the plan sets no limit on " + key.noun());
                } else if (quantity < 1) errors.putIfAbsent("addOns", "extra " + key.noun() + " must be at least 1");
                else if (!raised.add(key)) errors.putIfAbsent("addOns", key.label() + " is listed twice");
                a.setLimitKey(key);
                a.setQuantity(quantity);
                a.setUnitPrice(plan.getLimitAddOns().stream().filter(l -> l.key() == key)
                        .map(LimitAddOn::unitPrice).findFirst().orElse(BigDecimal.ZERO));
            }
            out.add(a);
        }
        for (String code : added) {
            FeatureGroup g = all.get(code);
            for (String needs : g.getRequires()) {
                if (!included.contains(needs) && !added.contains(needs)) {
                    FeatureGroup needed = all.get(needs);
                    errors.putIfAbsent("addOns", g.getName() + " needs " + (needed == null ? needs : needed.getName()));
                }
            }
        }
        return out;
    }

    private void requireCustomer(String customerId) {
        if (customerId == null || !customers.existsById(customerId)) throw new NotFoundException("customer not found");
    }

    private static GroupRef ref(FeatureGroup g, String source) {
        return new GroupRef(g.getCode(), g.getName(), source);
    }

    private static Map<String, Object> snapshot(TermSegment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planCode", s.getPlanCode());
        m.put("planRevision", s.getPlanRevision());
        m.put("termStart", String.valueOf(s.getTermStart()));
        m.put("termEnd", String.valueOf(s.getTermEnd()));
        m.put("addOns", s.getAddOns().stream().map(a -> a.getKind() == AddOnKind.GROUP ? a.getGroupCode()
                : a.getLimitKey() + " +" + a.getQuantity()).toList());
        return m;
    }
}
