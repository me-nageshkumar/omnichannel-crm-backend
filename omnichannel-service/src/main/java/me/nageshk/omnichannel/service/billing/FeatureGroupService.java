package me.nageshk.omnichannel.service.billing;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.security.access.AuthorityCatalog;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.security.entitlement.SeedGroups;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import me.nageshk.omnichannel.service.billing.BillingViews.GroupRequest;
import me.nageshk.omnichannel.service.billing.BillingViews.GroupView;
import me.nageshk.omnichannel.service.billing.BillingViews.UngroupedView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The feature groups plans are made of. A change to a group's authorities reaches every customer on a plan at once,
 * so each write evicts the entitlement cache.
 */
@Service
public class FeatureGroupService {

    static final Pattern CODE = Pattern.compile("[a-z0-9-]{2,30}");
    static final int MAX_NAME = 60;

    private final FeatureGroupRepository groups;
    private final PlanRepository plans;
    private final AuthorityCatalog catalog;
    private final Entitlements entitlements;
    private final BillingAudit audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public FeatureGroupService(FeatureGroupRepository groups, PlanRepository plans, AuthorityCatalog catalog,
            Entitlements entitlements, BillingAudit audit, MongoTemplate mongo, Clock clock) {
        this.groups = groups;
        this.plans = plans;
        this.catalog = catalog;
        this.entitlements = entitlements;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    public List<GroupView> list() {
        Map<String, List<String>> planCodes = planCodesByGroup();
        return groups.findAllByOrderBySortOrderAscCodeAsc().stream().map(g -> view(g, planCodes)).toList();
    }

    /** COMPANY and BRAND authorities that are in no group: no customer on a plan can use them. */
    public List<UngroupedView> ungrouped() {
        Set<String> grouped = new HashSet<>();
        groups.findAll().forEach(g -> grouped.addAll(g.getAuthorities()));
        return catalog.all().stream()
                .filter(a -> a.getLevel() != RoleLevel.PLATFORM && !grouped.contains(a.getCode()))
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .map(a -> new UngroupedView(a.getCode(), a.getDescription(), a.getLevel()))
                .toList();
    }

    public List<EventView> history(String code) {
        return audit.history(BillingChange.GROUP, code);
    }

    public GroupView create(GroupRequest req, String by) {
        String code = req.code() == null ? "" : req.code().trim();
        if (!CODE.matcher(code).matches()) {
            throw new ValidationException("code", "use 2 to 30 small letters, digits or hyphens");
        }
        if (groups.existsById(code)) throw new ValidationException("code", "is already used");
        FeatureGroup g = new FeatureGroup();
        g.setCode(code);
        g.setCreatedAt(clock.instant());
        g.setCreatedBy(by);
        apply(g, req);
        FeatureGroup saved = groups.insert(g);
        entitlements.evictAll();
        audit.record(BillingChange.GROUP, code, "CREATED", "Created with " + saved.getAuthorities().size()
                + " permissions", null, snapshot(saved), by);
        return view(saved, planCodesByGroup());
    }

    public GroupView update(String code, GroupRequest req, String by) {
        FeatureGroup g = require(code);
        if (req.version() == null) throw new ValidationException("version", "is required");
        // a stale copy is a conflict whatever else is wrong with it
        if (!req.version().equals(g.getVersion())) {
            throw new OptimisticLockingFailureException("changed since it was read");
        }
        Map<String, Object> before = snapshot(g);
        Set<String> oldAuthorities = new TreeSet<>(g.getAuthorities());
        GroupCategory oldCategory = g.getCategory();
        String oldName = g.getName();
        apply(g, req);
        if (oldCategory != g.getCategory()) {
            List<String> listedIn = planCodesByGroup().getOrDefault(code, List.of());
            if (!listedIn.isEmpty()) throw inUse(listedIn);
        }
        g.setVersion(req.version());
        g.setUpdatedAt(clock.instant());
        g.setUpdatedBy(by);
        FeatureGroup saved = groups.save(g);
        entitlements.evictAll();
        audit.record(BillingChange.GROUP, code, "UPDATED", summary(oldName, oldCategory, oldAuthorities, saved), before,
                snapshot(saved), by);
        return view(saved, planCodesByGroup());
    }

    public void delete(String code, String by) {
        FeatureGroup g = require(code);
        if (g.isBuiltIn()) {
            // a built-in group would be seeded again at the next start
            throw new CodedException(HttpStatus.CONFLICT, "BUILT_IN", null, "A built-in group cannot be deleted");
        }
        List<String> listedIn = planCodesByGroup().getOrDefault(code, List.of());
        if (!listedIn.isEmpty()) throw inUse(listedIn);
        if (heldBySubscription(code)) {
            throw new CodedException(HttpStatus.CONFLICT, "GROUP_IN_USE", null,
                    "The group is part of a customer's subscription", Map.of("plans", ""));
        }
        groups.delete(g);
        entitlements.evictAll();
        audit.record(BillingChange.GROUP, code, "DELETED", "Deleted", snapshot(g), null, by);
    }

    FeatureGroup require(String code) {
        return groups.findById(code).orElseThrow(() -> new NotFoundException("feature group not found"));
    }

    private void apply(FeatureGroup g, GroupRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) errors.put("name", "is required");
        else if (name.length() > MAX_NAME) errors.put("name", "at most " + MAX_NAME + " characters");
        boolean core = SeedGroups.CORE.equals(g.getCode());
        GroupCategory category = req.category();
        if (category == null) errors.put("category", "is required");
        else if (core && category != GroupCategory.DEFAULT) errors.put("category", "Core is always a default group");

        Set<String> authorities = req.authorities() == null ? Set.of() : req.authorities();
        Map<String, String> owner = ownersOfAuthorities(g.getCode());
        for (String code : new TreeSet<>(authorities)) {
            RoleLevel level = catalog.levelOf(code).orElse(null);
            if (level == null) errors.putIfAbsent("authorities", code + " is not a permission");
            else if (level == RoleLevel.PLATFORM) errors.putIfAbsent("authorities", code + " is a platform permission");
            else if (owner.containsKey(code)) {
                errors.putIfAbsent("authorities", code + " is already in group " + owner.get(code));
            }
        }
        if (core) {
            Set<String> missing = new TreeSet<>(SeedGroups.coreBuiltIns());
            missing.removeAll(authorities);
            if (!missing.isEmpty()) errors.putIfAbsent("authorities", "Core always holds " + String.join(", ", missing));
        }

        Set<String> requires = req.requires() == null ? Set.of() : req.requires();
        Map<String, FeatureGroup> all = new HashMap<>();
        groups.findAll().forEach(x -> all.put(x.getCode(), x));
        for (String r : new TreeSet<>(requires)) {
            if (r.equals(g.getCode())) errors.putIfAbsent("requires", "a group cannot require itself");
            else if (!all.containsKey(r)) errors.putIfAbsent("requires", r + " is not a group");
            else if (all.get(r).getCategory() == GroupCategory.DEFAULT) {
                errors.putIfAbsent("requires", all.get(r).getName() + " is in every plan already; nothing needs to require it");
            }
        }
        if (!errors.containsKey("requires") && reaches(requires, g.getCode(), all)) {
            errors.put("requires", "the groups would require each other");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        g.setName(name);
        g.setDescription(req.description() == null ? "" : req.description().trim());
        g.setCategory(category);
        g.setAuthorities(new HashSet<>(authorities));
        g.setRequires(new HashSet<>(requires));
        if (req.sortOrder() != null) g.setSortOrder(req.sortOrder());
    }

    /** Whether following {@code requires} from these groups leads back to {@code target}. */
    private static boolean reaches(Set<String> from, String target, Map<String, FeatureGroup> all) {
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>(from);
        while (!todo.isEmpty()) {
            String next = todo.remove(todo.size() - 1);
            if (next.equals(target)) return true;
            if (!seen.add(next)) continue;
            FeatureGroup g = all.get(next);
            if (g != null) todo.addAll(g.getRequires());
        }
        return false;
    }

    /** Authority code → the name of the other group that holds it. */
    private Map<String, String> ownersOfAuthorities(String exceptCode) {
        Map<String, String> owner = new HashMap<>();
        for (FeatureGroup other : groups.findAll()) {
            if (other.getCode().equals(exceptCode)) continue;
            other.getAuthorities().forEach(a -> owner.put(a, other.getName()));
        }
        return owner;
    }

    private Map<String, List<String>> planCodesByGroup() {
        Map<String, List<String>> out = new HashMap<>();
        for (Plan p : plans.findAll()) {
            List<PlanItem> listed = new ArrayList<>(p.getItems());
            listed.addAll(p.getAddOns());
            for (PlanItem i : listed) out.computeIfAbsent(i.groupCode(), k -> new ArrayList<>()).add(p.getCode());
        }
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    private boolean heldBySubscription(String code) {
        Criteria held = new Criteria().orOperator(where("current.groupCodes").is(code),
                where("next.groupCodes").is(code), where("current.addOns.groupCode").is(code),
                where("next.addOns.groupCode").is(code));
        return mongo.exists(new Query(held), Subscription.class);
    }

    private static CodedException inUse(List<String> planCodes) {
        return new CodedException(HttpStatus.CONFLICT, "GROUP_IN_USE", null,
                "The group is listed in a plan", Map.of("plans", String.join(", ", planCodes)));
    }

    private static String summary(String oldName, GroupCategory oldCategory, Set<String> oldAuthorities,
            FeatureGroup now) {
        List<String> parts = new ArrayList<>();
        Set<String> added = new TreeSet<>(now.getAuthorities());
        added.removeAll(oldAuthorities);
        Set<String> removed = new TreeSet<>(oldAuthorities);
        removed.removeAll(now.getAuthorities());
        if (!added.isEmpty()) parts.add("Added " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("Removed " + String.join(", ", removed));
        if (!now.getName().equals(oldName)) parts.add("Renamed to " + now.getName());
        if (now.getCategory() != oldCategory) parts.add("Category " + now.getCategory());
        return parts.isEmpty() ? "Edited" : String.join("; ", parts);
    }

    private static Map<String, Object> snapshot(FeatureGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.getName());
        m.put("category", String.valueOf(g.getCategory()));
        m.put("authorities", new ArrayList<>(new TreeSet<>(g.getAuthorities())));
        m.put("requires", new ArrayList<>(new TreeSet<>(g.getRequires())));
        return m;
    }

    private static GroupView view(FeatureGroup g, Map<String, List<String>> planCodes) {
        return new GroupView(g.getCode(), g.getName(), g.getDescription() == null ? "" : g.getDescription(),
                g.getCategory(), new ArrayList<>(new TreeSet<>(g.getAuthorities())),
                new ArrayList<>(new TreeSet<>(g.getRequires())), g.isBuiltIn(), g.getSortOrder(), g.getVersion(),
                planCodes.getOrDefault(g.getCode(), List.of()));
    }

    /** For the other billing services: every group by code. */
    public Map<String, FeatureGroup> byCode() {
        Map<String, FeatureGroup> all = new HashMap<>();
        groups.findAll().forEach(g -> all.put(g.getCode(), g));
        return all;
    }
}
