package me.nageshk.omnichannel.crm.company;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.FieldDiff;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.dto.CompanyRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanySearchRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanySummaryView;
import me.nageshk.omnichannel.crm.company.dto.CompanyView;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.deal.DealService;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class CrmCompanyService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    static final int TYPEAHEAD = 10;
    private static final Set<String> SORTS = Set.of("name", "creationTime", "lastActivityAt");

    private final MongoTemplate mongo;
    private final PeopleDirectory people;
    private final CrmContactService contacts;
    private final DealService deals;
    private final CompanyCounters counters;
    private final ActivityLog activities;
    private final Clock clock;

    CrmCompanyService(MongoTemplate mongo, PeopleDirectory people, CrmContactService contacts, DealService deals,
            CompanyCounters counters, ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.deals = deals;
        this.people = people;
        this.contacts = contacts;
        this.counters = counters;
        this.activities = activities;
        this.clock = clock;
    }

    public CrmPage<CompanyView> search(CrmContext ctx, CompanySearchRequest req) {
        int page = req.page() == null ? 0 : req.page();
        int size = req.size() == null ? DEFAULT_SIZE : req.size();
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must not be negative");
        if (size < 1) errors.put("size", "must be at least 1");
        String sort = present(req.sort()) ? req.sort() : "name";
        if (!SORTS.contains(sort)) errors.put("sort", "must be one of " + String.join(", ", new TreeSet<>(SORTS)));
        String dir = present(req.dir()) ? req.dir().toLowerCase() : sort.equals("name") ? "asc" : "desc";
        if (!dir.equals("asc") && !dir.equals("desc")) errors.put("dir", "must be asc or desc");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        size = Math.min(size, MAX_SIZE);

        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        if (present(req.ownerId())) all.add(Criteria.where("ownerId").is(req.ownerId()));
        if (present(req.industry())) all.add(Criteria.where("industry").is(req.industry()));
        if (present(req.q())) {
            Pattern p = Pattern.compile(Pattern.quote(req.q().trim()), Pattern.CASE_INSENSITIVE);
            all.add(new Criteria().orOperator(Criteria.where("name").regex(p), Criteria.where("domain").regex(p),
                    Criteria.where("email").regex(p), Criteria.where("phone").regex(p)));
        }
        Criteria filter = new Criteria().andOperator(all);
        long total = mongo.count(new Query(filter), CrmCompany.class);
        Sort order = Sort.by(dir.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sort)
                .and(Sort.by(Sort.Direction.DESC, "_id"));
        List<CrmCompany> found = mongo.find(new Query(filter).with(order).skip((long) page * size).limit(size),
                CrmCompany.class);
        return new CrmPage<>(views(found), total, page);
    }

    /** Case-insensitive prefix match on the name, first ten by name. */
    public List<Ref> typeahead(CrmContext ctx, String q) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId());
        if (present(q)) {
            c = c.and("name").regex(Pattern.compile("^" + Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE));
        }
        Query query = new Query(c).with(Sort.by("name").and(Sort.by("_id"))).limit(TYPEAHEAD);
        query.fields().include("name");
        return mongo.find(query, CrmCompany.class).stream().map(x -> new Ref(x.getId(), x.getName())).toList();
    }

    public CompanyView get(CrmContext ctx, String id) {
        return views(List.of(load(ctx, id))).get(0);
    }

    /** @throws NotFoundException when missing or of another brand */
    public CrmCompany load(CrmContext ctx, String id) {
        CrmCompany c = mongo.findOne(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                CrmCompany.class);
        if (c == null) throw new NotFoundException("Company not found");
        return c;
    }

    public CompanySummaryView summary(CrmContext ctx, String id) {
        CrmCompany c = load(ctx, id);
        BigDecimal amount = counters.openAmount(ctx.workspaceId(), id, ctx.pipelines());
        return new CompanySummaryView(views(List.of(c)).get(0), contacts.linkedTo(ctx, id),
                deals.openDealsOfCompany(ctx, id), c.getContactCount(), amount);
    }

    public CompanyView create(CrmContext ctx, CompanyRequest req) {
        ctx.require("crm:edit");
        Map<String, String> errors = new LinkedHashMap<>();
        CrmCompany c = new CrmCompany();
        Map<String, Object> f = resolve(ctx, req, new CrmCompany(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        apply(c, f);
        c.setWorkspaceId(ctx.workspaceId());
        c.setCustomerId(ctx.customerId());
        c.setCreatedBy(ctx.userId());
        c.setLastModifiedBy(ctx.userId());
        CrmCompany saved = mongo.insert(c);
        activities.record(ctx, ActivityType.RECORD_CREATED, new CrmTarget(TargetType.COMPANY, saved.getId()),
                List.of(), null, null, "Created company " + saved.getName(), new LinkedHashMap<>());
        return views(List.of(load(ctx, saved.getId()))).get(0);
    }

    public CompanyView update(CrmContext ctx, String id, CompanyRequest req) {
        ctx.require("crm:edit");
        CrmCompany current = load(ctx, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        Map<String, Object> after = resolve(ctx, req, current, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        Map<String, Object> before = snapshot(current);
        List<FieldDiff> diffs = ActivityLog.diff(before, after);
        if (diffs.isEmpty()) {
            if (!req.version().equals(current.getVersion())) throw versionConflict();
            return views(List.of(current)).get(0);
        }
        Update u = new Update().set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now(clock))
                .inc("version", 1);
        diffs.forEach(d -> u.set(d.field(), d.to() instanceof BigDecimal b ? new Decimal128(b) : d.to()));
        CrmCompany saved = mongo.findAndModify(
                new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())
                        .and("version").is(req.version())),
                u, FindAndModifyOptions.options().returnNew(true), CrmCompany.class);
        if (saved == null) {
            if (mongo.exists(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                    CrmCompany.class)) throw versionConflict();
            throw new NotFoundException("Company not found");
        }
        List<Map<String, Object>> changes = diffs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", d.field());
            m.put("from", plain(d.from()));
            m.put("to", plain(d.to()));
            return m;
        }).toList();
        activities.record(ctx, ActivityType.FIELD_CHANGED, new CrmTarget(TargetType.COMPANY, id), List.of(), null,
                null, "Changed " + String.join(", ", diffs.stream().map(FieldDiff::field).toList()),
                new LinkedHashMap<>(Map.of("changes", changes)));
        return views(List.of(saved)).get(0);
    }

    public void delete(CrmContext ctx, String id) {
        ctx.require("crm:delete");
        ctx.require("crm:edit");
        load(ctx, id);
        String ws = ctx.workspaceId();
        if (mongo.exists(new Query(Criteria.where("workspaceId").is(ws).and("companyId").is(id).and("status")
                .is("OPEN")), CompanyCounters.DEALS)) {
            throw CrmErrors.conflict("COMPANY_HAS_OPEN_DEALS", "The company still has open deals", Map.of());
        }
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(ws).and("companyId").is(id)),
                new Update().unset("companyId"), CompanyCounters.CONTACTS);
        mongo.remove(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ws)), CrmCompany.class);
    }

    static RuntimeException versionConflict() {
        return CrmErrors.conflict("VERSION_CONFLICT", "The company was changed by someone else; reload and retry",
                Map.of());
    }

    /** The edited fields after applying the request over {@code current}, collecting validation errors. */
    private Map<String, Object> resolve(CrmContext ctx, CompanyRequest req, CrmCompany current,
            Map<String, String> errors) {
        String name = text(req.name(), current.getName(), "name", 200, errors);
        if (name == null) errors.putIfAbsent("name", "is required");
        String domain = text(req.domain(), current.getDomain(), "domain", 200, errors);
        String website = text(req.website(), current.getWebsite(), "website", 500, errors);
        String industry = text(req.industry(), current.getIndustry(), "industry", 64, errors);
        String size = text(req.size(), current.getSize(), "size", 64, errors);
        String phone = text(req.phone(), current.getPhone(), "phone", 50, errors);
        String email = text(req.email(), current.getEmail(), "email", 254, errors);
        String description = text(req.description(), current.getDescription(), "description", 5000, errors);
        String ownerId = text(req.ownerId(), current.getOwnerId(), "ownerId", 64, errors);
        String teamId = text(req.teamId(), current.getTeamId(), "teamId", 64, errors);
        if (industry != null && !industry.equals(current.getIndustry())) {
            List<PicklistItem> items = ctx.settings().getIndustries();
            if (items != null && !items.isEmpty()
                    && items.stream().noneMatch(i -> i.active() && i.key().equals(industry))) {
                errors.put("industry", "is not an active option");
            }
        }
        if (ownerId != null && !ownerId.equals(current.getOwnerId()) && !people.isBrandUser(ctx.workspaceId(), ownerId))
            errors.put("ownerId", "must be a user of this brand");
        BigDecimal revenue = req.annualRevenue() != null ? req.annualRevenue() : current.getAnnualRevenue();
        if (req.annualRevenue() != null && req.annualRevenue().signum() < 0)
            errors.put("annualRevenue", "must not be negative");
        Map<String, Object> address = req.address() != null ? address(req.address(), errors) : addressMap(current.getAddress());
        List<String> labels = req.labelIds() != null ? req.labelIds().stream().filter(x -> x != null && !x.isBlank())
                .map(String::trim).distinct().toList() : current.getLabelIds();
        Map<String, Object> custom = req.customFields() != null ? new LinkedHashMap<>(req.customFields())
                : current.getCustomFields();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("domain", domain);
        m.put("website", website);
        m.put("industry", industry);
        m.put("size", size);
        m.put("annualRevenue", revenue);
        m.put("phone", phone);
        m.put("email", email);
        m.put("address", address);
        m.put("description", description);
        m.put("ownerId", ownerId);
        m.put("teamId", teamId);
        m.put("labelIds", labels == null ? List.of() : List.copyOf(labels));
        m.put("customFields", custom == null ? new LinkedHashMap<>() : custom);
        return m;
    }

    private static Map<String, Object> snapshot(CrmCompany c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("domain", c.getDomain());
        m.put("website", c.getWebsite());
        m.put("industry", c.getIndustry());
        m.put("size", c.getSize());
        m.put("annualRevenue", c.getAnnualRevenue());
        m.put("phone", c.getPhone());
        m.put("email", c.getEmail());
        m.put("address", addressMap(c.getAddress()));
        m.put("description", c.getDescription());
        m.put("ownerId", c.getOwnerId());
        m.put("teamId", c.getTeamId());
        m.put("labelIds", c.getLabelIds() == null ? List.of() : List.copyOf(c.getLabelIds()));
        m.put("customFields", c.getCustomFields() == null ? new LinkedHashMap<>() : c.getCustomFields());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void apply(CrmCompany c, Map<String, Object> f) {
        c.setName((String) f.get("name"));
        c.setDomain((String) f.get("domain"));
        c.setWebsite((String) f.get("website"));
        c.setIndustry((String) f.get("industry"));
        c.setSize((String) f.get("size"));
        c.setAnnualRevenue((BigDecimal) f.get("annualRevenue"));
        c.setPhone((String) f.get("phone"));
        c.setEmail((String) f.get("email"));
        Map<String, Object> a = (Map<String, Object>) f.get("address");
        c.setAddress(a == null ? null : new Address((String) a.get("line1"), (String) a.get("city"),
                (String) a.get("state"), (String) a.get("postalCode"), (String) a.get("country")));
        c.setDescription((String) f.get("description"));
        c.setOwnerId((String) f.get("ownerId"));
        c.setTeamId((String) f.get("teamId"));
        c.setLabelIds(new ArrayList<>((List<String>) f.get("labelIds")));
        c.setCustomFields((Map<String, Object>) f.get("customFields"));
    }

    private static Map<String, Object> address(Address a, Map<String, String> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line1", text(a.line1(), null, "address.line1", 200, errors));
        m.put("city", text(a.city(), null, "address.city", 100, errors));
        m.put("state", text(a.state(), null, "address.state", 100, errors));
        m.put("postalCode", text(a.postalCode(), null, "address.postalCode", 20, errors));
        m.put("country", text(a.country(), null, "address.country", 100, errors));
        return m.values().stream().allMatch(v -> v == null) ? null : m;
    }

    private static Map<String, Object> addressMap(Address a) {
        if (a == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line1", a.line1());
        m.put("city", a.city());
        m.put("state", a.state());
        m.put("postalCode", a.postalCode());
        m.put("country", a.country());
        return m;
    }

    private static Object plain(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : v;
    }

    /** Absent = unchanged, blank = cleared, else trimmed; over-long values are reported. */
    private static String text(String given, String current, String field, int max, Map<String, String> errors) {
        if (given == null) return current;
        String t = given.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) errors.put(field, "must be at most " + max + " characters");
        return t;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private List<CompanyView> views(List<CrmCompany> list) {
        Set<String> ownerIds = new java.util.HashSet<>();
        list.forEach(c -> {
            if (c.getOwnerId() != null) ownerIds.add(c.getOwnerId());
        });
        Map<String, PlatformPerson> persons = ownerIds.isEmpty() ? Map.of() : people.people(ownerIds);
        return list.stream().map(c -> {
            Ref owner = null;
            if (c.getOwnerId() != null) {
                PlatformPerson p = persons.get(c.getOwnerId());
                owner = new Ref(c.getOwnerId(), p == null ? null
                        : p.name() != null && !p.name().isBlank() ? p.name() : p.username());
            }
            return new CompanyView(c.getId(), c.getName(), c.getDomain(), c.getWebsite(), c.getIndustry(),
                    c.getSize(), c.getAnnualRevenue(), c.getPhone(), c.getEmail(), c.getAddress(),
                    c.getDescription(), owner, c.getTeamId(), c.getLabelIds(), c.getCustomFields(),
                    c.getContactCount(), c.getOpenDealAmount(), c.getLastActivityAt(), c.getCreationTime(),
                    c.getVersion());
        }).toList();
    }
}
