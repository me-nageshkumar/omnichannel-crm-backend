package me.nageshk.omnichannel.service.billing;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.LimitAddOn;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.service.billing.BillingViews.DuplicateRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.BillingViews.PlanRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.PlanView;
import me.nageshk.omnichannel.service.billing.BillingViews.PriceRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.PriceView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The plans customers are put on. A subscription keeps a snapshot of the terms it was sold under, so nothing here
 * ever touches a subscription: an edit to an active plan counts as a new revision and applies at the next renewal.
 */
@Service
public class PlanService {

    static final Pattern CODE = Pattern.compile("[a-z0-9-]{2,40}");
    static final int MAX_NAME = 80;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PlanRepository plans;
    private final FeatureGroupService groups;
    private final BillingAudit audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public PlanService(PlanRepository plans, FeatureGroupService groups, BillingAudit audit, MongoTemplate mongo,
            Clock clock) {
        this.plans = plans;
        this.groups = groups;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** By rank, then period; every status when {@code status} is null. */
    public List<PlanView> list(PlanStatus status) {
        List<Plan> found = status == null ? plans.findAll() : plans.findByStatus(status);
        return found.stream()
                .sorted(Comparator.comparingInt(Plan::getRank).thenComparing(p -> p.getPeriod().months())
                        .thenComparing(Plan::getCode))
                .map(this::view).toList();
    }

    public PlanView get(String code) {
        return view(require(code));
    }

    public Plan require(String code) {
        return plans.findById(code).orElseThrow(() -> new NotFoundException("plan not found"));
    }

    public PriceView price(PriceRequest req) {
        PlanPricing.Price p = PlanPricing.price(req.items(), req.discountPercent());
        return new PriceView(p.actual(), p.discount(), p.discounted());
    }

    public List<EventView> history(String code) {
        return audit.history(BillingChange.PLAN, code);
    }

    public PlanView create(PlanRequest req, String by) {
        Plan p = new Plan();
        p.setCode(newCode(req.code()));
        p.setStatus(PlanStatus.DRAFT);
        p.setCreatedAt(clock.instant());
        p.setCreatedBy(by);
        apply(p, req);
        Plan saved = plans.insert(p);
        audit.record(BillingChange.PLAN, saved.getCode(), "CREATED", "Created", null, snapshot(saved), by);
        return view(saved);
    }

    public PlanView update(String code, PlanRequest req, String by) {
        Plan p = require(code);
        if (p.getStatus() == PlanStatus.RETIRED) {
            throw new ValidationException("status", "A retired plan cannot be edited; activate it first");
        }
        if (req.version() == null) throw new ValidationException("version", "is required");
        // a stale copy is a conflict whatever else is wrong with it
        if (!req.version().equals(p.getVersion())) {
            throw new OptimisticLockingFailureException("changed since it was read");
        }
        Map<String, Object> before = snapshot(p);
        apply(p, req);
        if (p.getStatus() == PlanStatus.ACTIVE) p.setRevision(p.getRevision() + 1);
        p.setVersion(req.version());
        p.setUpdatedAt(clock.instant());
        p.setUpdatedBy(by);
        Plan saved = plans.save(p);
        audit.record(BillingChange.PLAN, code, "UPDATED",
                saved.getStatus() == PlanStatus.ACTIVE ? "Edited; applies at each customer's next renewal" : "Edited",
                before, snapshot(saved), by);
        return view(saved);
    }

    /** A draft copy under another code, name and period: the four periods of one tier are made this way. */
    public PlanView duplicate(String code, DuplicateRequest req, String by) {
        Plan source = require(code);
        Map<String, String> errors = new LinkedHashMap<>();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) errors.put("name", "is required");
        else if (name.length() > MAX_NAME) errors.put("name", "at most " + MAX_NAME + " characters");
        if (req.period() == null) errors.put("period", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        Plan p = new Plan();
        p.setCode(newCode(req.code()));
        p.setName(name);
        p.setDescription(source.getDescription());
        p.setPeriod(req.period());
        p.setRank(source.getRank());
        p.setItems(new ArrayList<>(source.getItems()));
        p.setAddOns(new ArrayList<>(source.getAddOns()));
        p.setLimits(new EnumMap<>(LimitKey.class));
        p.getLimits().putAll(source.getLimits());
        p.setLimitAddOns(new ArrayList<>(source.getLimitAddOns()));
        p.setDiscountPercent(source.getDiscountPercent());
        p.setRates(new EnumMap<>(UsageMeter.class));
        p.getRates().putAll(source.getRates());
        p.setOffered(source.isOffered());
        p.setStatus(PlanStatus.DRAFT);
        p.setCreatedAt(clock.instant());
        p.setCreatedBy(by);
        Plan saved = plans.insert(p);
        audit.record(BillingChange.PLAN, saved.getCode(), "CREATED", "Copied from " + code, null, snapshot(saved), by);
        return view(saved);
    }

    public PlanView activate(String code, String by) {
        return move(code, PlanStatus.ACTIVE, Set.of(PlanStatus.DRAFT, PlanStatus.RETIRED), "ACTIVATED", "Activated", by);
    }

    public PlanView retire(String code, String by) {
        return move(code, PlanStatus.RETIRED, Set.of(PlanStatus.ACTIVE), "RETIRED", "Retired", by);
    }

    /** Only a draft that no customer is on can go. */
    public void delete(String code, String by) {
        Plan p = require(code);
        if (p.getStatus() != PlanStatus.DRAFT || customers(code) > 0) {
            throw new CodedException(HttpStatus.CONFLICT, "PLAN_IN_USE", null,
                    "Only a draft plan can be deleted; retire this one instead");
        }
        plans.delete(p);
        audit.record(BillingChange.PLAN, code, "DELETED", "Deleted", snapshot(p), null, by);
    }

    private PlanView move(String code, PlanStatus to, Set<PlanStatus> from, String kind, String summary, String by) {
        Plan p = require(code);
        if (!from.contains(p.getStatus())) {
            throw new ValidationException("status", "The plan is " + p.getStatus().name().toLowerCase());
        }
        p.setStatus(to);
        p.setUpdatedAt(clock.instant());
        p.setUpdatedBy(by);
        Plan saved = plans.save(p);
        audit.record(BillingChange.PLAN, code, kind, summary, null, null, by);
        return view(saved);
    }

    private String newCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (!CODE.matcher(code).matches()) {
            throw new ValidationException("code", "use 2 to 40 small letters, digits or hyphens");
        }
        if (plans.existsById(code)) throw new ValidationException("code", "is already used");
        return code;
    }

    private void apply(Plan p, PlanRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) errors.put("name", "is required");
        else if (name.length() > MAX_NAME) errors.put("name", "at most " + MAX_NAME + " characters");
        if (req.period() == null) errors.put("period", "is required");
        if (req.rank() == null) errors.put("rank", "is required");
        else if (req.rank() < 0 || req.rank() > 999) errors.put("rank", "must be between 0 and 999");

        BigDecimal discount = req.discountPercent() == null ? BigDecimal.ZERO : req.discountPercent();
        if (discount.signum() < 0 || discount.compareTo(HUNDRED) > 0) {
            errors.put("discountPercent", "must be between 0 and 100");
        } else if (decimals(discount) > 2) {
            errors.put("discountPercent", "at most 2 decimals");
        }

        Map<String, FeatureGroup> all = groups.byCode();
        List<PlanItem> items = req.items() == null ? List.of() : req.items();
        List<PlanItem> addOns = req.addOns() == null ? List.of() : req.addOns();
        Set<String> included = new HashSet<>();
        for (PlanItem i : items) check(i, all, included, Set.of(), "items", errors);
        Set<String> offered = new HashSet<>();
        for (PlanItem i : addOns) check(i, all, offered, included, "addOns", errors);
        for (String code : included) {
            for (String needs : all.get(code).getRequires()) {
                if (!included.contains(needs)) {
                    errors.putIfAbsent("items", all.get(code).getName() + " needs " + nameOf(needs, all) + " to be included");
                }
            }
        }
        for (String code : offered) {
            for (String needs : all.get(code).getRequires()) {
                if (!included.contains(needs) && !offered.contains(needs)) {
                    errors.putIfAbsent("addOns", all.get(code).getName() + " needs " + nameOf(needs, all)
                            + " to be included or offered as an add-on");
                }
            }
        }

        Map<LimitKey, Integer> limits = new EnumMap<>(LimitKey.class);
        if (req.limits() != null) {
            req.limits().forEach((key, value) -> {
                if (value == null) return;
                if (value < 0) errors.putIfAbsent("limits", key.label() + " cannot be negative");
                else limits.put(key, value);
            });
        }
        List<LimitAddOn> limitAddOns = req.limitAddOns() == null ? List.of() : req.limitAddOns();
        Set<LimitKey> seen = new HashSet<>();
        for (LimitAddOn a : limitAddOns) {
            if (a == null || a.key() == null) errors.putIfAbsent("limitAddOns", "each needs a limit");
            else if (!seen.add(a.key())) errors.putIfAbsent("limitAddOns", a.key().label() + " is listed twice");
            else if (!limits.containsKey(a.key())) {
                errors.putIfAbsent("limitAddOns", "extra " + a.key().noun() + " need a limit on " + a.key().noun());
            } else if (a.unit() < 1) errors.putIfAbsent("limitAddOns", "the unit must be at least 1");
            else if (badPrice(a.unitPrice(), 2)) errors.putIfAbsent("limitAddOns", "the price must be 0 or more, at most 2 decimals");
        }
        Map<UsageMeter, BigDecimal> rates = new EnumMap<>(UsageMeter.class);
        if (req.rates() != null) {
            req.rates().forEach((meter, rate) -> {
                if (rate == null) return;
                if (badPrice(rate, 4)) errors.putIfAbsent("rates", "a rate must be 0 or more, at most 4 decimals");
                else rates.put(meter, rate);
            });
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        p.setName(name);
        p.setDescription(req.description() == null ? "" : req.description().trim());
        p.setPeriod(req.period());
        p.setRank(req.rank());
        p.setItems(new ArrayList<>(items));
        p.setAddOns(new ArrayList<>(addOns));
        p.setLimits(limits);
        p.setLimitAddOns(new ArrayList<>(limitAddOns));
        p.setDiscountPercent(discount);
        p.setRates(rates);
        p.setOffered(Boolean.TRUE.equals(req.offered()));
    }

    /** One listed group: it exists, is PAID, is listed once, is not in {@code taken}, and has a sound price. */
    private static void check(PlanItem i, Map<String, FeatureGroup> all, Set<String> listed, Set<String> taken,
            String field, Map<String, String> errors) {
        if (i == null || i.groupCode() == null) {
            errors.putIfAbsent(field, "each needs a feature group");
            return;
        }
        FeatureGroup g = all.get(i.groupCode());
        if (g == null) errors.putIfAbsent(field, i.groupCode() + " is not a feature group");
        else if (g.getCategory() != GroupCategory.PAID) {
            errors.putIfAbsent(field, g.getName() + " is in every plan already");
        } else if (taken.contains(i.groupCode())) errors.putIfAbsent(field, g.getName() + " is already included");
        else if (!listed.add(i.groupCode())) errors.putIfAbsent(field, g.getName() + " is listed twice");
        else if (badPrice(i.price(), 2)) {
            listed.remove(i.groupCode());
            errors.putIfAbsent(field, "the price of " + g.getName() + " must be 0 or more, at most 2 decimals");
        }
        if (g == null || g.getCategory() != GroupCategory.PAID) listed.remove(i.groupCode());
    }

    private static boolean badPrice(BigDecimal price, int maxDecimals) {
        return price == null || price.signum() < 0 || decimals(price) > maxDecimals;
    }

    private static int decimals(BigDecimal n) {
        return Math.max(0, n.stripTrailingZeros().scale());
    }

    private static String nameOf(String code, Map<String, FeatureGroup> all) {
        FeatureGroup g = all.get(code);
        return g == null ? code : g.getName();
    }

    private long customers(String code) {
        Criteria on = new Criteria().orOperator(where("current.planCode").is(code), where("next.planCode").is(code));
        return mongo.count(new Query(on), Subscription.class);
    }

    private static Map<String, Object> snapshot(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("period", String.valueOf(p.getPeriod()));
        m.put("rank", p.getRank());
        m.put("items", p.getItems().stream().map(i -> i.groupCode() + " " + i.price().toPlainString()).toList());
        m.put("addOns", p.getAddOns().stream().map(i -> i.groupCode() + " " + i.price().toPlainString()).toList());
        Map<String, Object> limits = new LinkedHashMap<>();
        p.getLimits().forEach((k, v) -> limits.put(k.name(), v));
        m.put("limits", limits);
        m.put("discountPercent", p.getDiscountPercent() == null ? "0" : p.getDiscountPercent().toPlainString());
        m.put("offered", p.isOffered());
        return m;
    }

    PlanView view(Plan p) {
        PlanPricing.Price price = PlanPricing.price(p.getItems(), p.getDiscountPercent());
        return new PlanView(p.getCode(), p.getName(), p.getDescription() == null ? "" : p.getDescription(),
                p.getPeriod(), p.getRank(), p.getItems(), p.getAddOns(), p.getLimits(), p.getLimitAddOns(),
                p.getDiscountPercent() == null ? BigDecimal.ZERO : p.getDiscountPercent(), p.getRates(), p.isOffered(),
                p.getStatus(), p.getRevision(), p.getVersion(), price.actual(), price.discount(), price.discounted(),
                customers(p.getCode()));
    }
}
