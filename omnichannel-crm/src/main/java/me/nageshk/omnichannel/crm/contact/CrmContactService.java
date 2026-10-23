package me.nageshk.omnichannel.crm.contact;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.FieldDiff;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmSort;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.CompanyCounters;
import me.nageshk.omnichannel.crm.contact.dto.ContactSearchRequest;
import me.nageshk.omnichannel.crm.contact.dto.ContactUpdateRequest;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class CrmContactService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    private static final Set<String> SORTS = Set.of("lastActivityAt", "creationTime", "firstName");

    private final MongoTemplate mongo;
    private final ContactDirectory contactDirectory;
    private final PeopleDirectory people;
    private final ContactCounters counters;
    private final CompanyCounters companyCounters;
    private final ActivityLog activities;
    private final Clock clock;

    CrmContactService(MongoTemplate mongo, ContactDirectory contactDirectory, PeopleDirectory people,
            ContactCounters counters, CompanyCounters companyCounters, ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.contactDirectory = contactDirectory;
        this.people = people;
        this.counters = counters;
        this.companyCounters = companyCounters;
        this.activities = activities;
        this.clock = clock;
    }

    public CrmPage<CrmContactView> search(CrmContext ctx, ContactSearchRequest req) {
        int page = req.page() == null ? 0 : req.page();
        int size = req.size() == null ? DEFAULT_SIZE : req.size();
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must not be negative");
        if (size < 1) errors.put("size", "must be at least 1");
        CrmSort sorting = CrmSort.parse(req.sort(), req.dir(), SORTS, "creationTime", f -> "desc", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        size = Math.min(size, MAX_SIZE);

        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        if (!ctx.pipelines().all()) all.add(ctx.pipelines().criteria("pipelineIds"));
        if (present(req.pipelineId())) all.add(Criteria.where("pipelineIds").is(req.pipelineId()));
        if (present(req.ownerId())) all.add(Criteria.where("ownerId").is(req.ownerId()));
        if (present(req.lifecycleStatus())) all.add(Criteria.where("lifecycleStatus").is(req.lifecycleStatus()));
        if (present(req.companyId())) all.add(Criteria.where("companyId").is(req.companyId()));
        if (present(req.q())) {
            Pattern p = Pattern.compile(Pattern.quote(req.q().trim()), Pattern.CASE_INSENSITIVE);
            all.add(new Criteria().orOperator(Criteria.where("firstName").regex(p), Criteria.where("lastName").regex(p),
                    Criteria.where("contactSnapshot.name").regex(p), Criteria.where("phone").regex(p),
                    Criteria.where("email").regex(p)));
        }
        Query filter = new Query(new Criteria().andOperator(all));
        long total = mongo.count(filter, CrmContact.class);
        Sort order = Sort.by(sorting.direction(), sorting.field()).and(Sort.by(Sort.Direction.DESC, "_id"));
        List<CrmContact> found = mongo.find(
                new Query(new Criteria().andOperator(all)).with(order).skip((long) page * size).limit(size),
                CrmContact.class);
        return new CrmPage<>(views(ctx, found), total, page);
    }

    public CrmContactView get(CrmContext ctx, String id) {
        return view(ctx, load(ctx, id));
    }

    /** @throws NotFoundException when missing, of another brand, or outside the caller's pipelines */
    public CrmContact load(CrmContext ctx, String id) {
        CrmContact c = mongo.findOne(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                CrmContact.class);
        if (c == null || !visible(ctx, c)) throw new NotFoundException("Contact not found");
        return c;
    }

    /** The brand contact on the channel number when the caller may see it (see {@link #load}), else empty. */
    public java.util.Optional<CrmContact> findVisible(CrmContext ctx, String contactId, String phoneNumberId) {
        CrmContact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("contactId").is(contactId).and("channel.phoneNumberId").is(phoneNumberId)), CrmContact.class);
        return java.util.Optional.ofNullable(c).filter(x -> visible(ctx, x));
    }

    private static boolean visible(CrmContext ctx, CrmContact c) {
        return ctx.pipelines().all() || c.getPipelineIds().stream().anyMatch(ctx.pipelines()::allows);
    }

    public CrmContactView update(CrmContext ctx, String id, ContactUpdateRequest req) {
        ctx.require("crm:edit");
        CrmContact current = load(ctx, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        String firstName = text(req.firstName(), current.getFirstName(), "firstName", 200, errors);
        String lastName = text(req.lastName(), current.getLastName(), "lastName", 200, errors);
        String email = text(req.email(), current.getEmail(), "email", 254, errors);
        String jobTitle = text(req.jobTitle(), current.getJobTitle(), "jobTitle", 100, errors);
        String ownerId = text(req.ownerId(), current.getOwnerId(), "ownerId", 64, errors);
        String lifecycle = pick(req.lifecycleStatus(), current.getLifecycleStatus(), "lifecycleStatus",
                ctx.settings().getLifecycleStatuses(), errors);
        String source = pick(req.source(), current.getSource(), "source", ctx.settings().getDealSources(), errors);
        if (ownerId != null && !ownerId.equals(current.getOwnerId()) && !people.isBrandUser(ctx.workspaceId(), ownerId))
            errors.put("ownerId", "must be a user of this brand");
        String companyId = text(req.companyId(), current.getCompanyId(), "companyId", 64, errors);
        if (companyId != null && !companyId.equals(current.getCompanyId()) && !mongo.exists(
                new Query(Criteria.where("_id").is(companyId).and("workspaceId").is(ctx.workspaceId())),
                CompanyCounters.COMPANIES)) errors.put("companyId", "must be a company of this brand");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Map<String, Object> before = fields(current.getFirstName(), current.getLastName(), current.getEmail(),
                current.getJobTitle(), current.getOwnerId(), current.getLifecycleStatus(), current.getSource(),
                current.getCompanyId());
        Map<String, Object> after = fields(firstName, lastName, email, jobTitle, ownerId, lifecycle, source,
                companyId);
        List<FieldDiff> diffs = ActivityLog.diff(before, after);
        if (diffs.isEmpty()) {
            if (!req.version().equals(current.getVersion())) throw versionConflict();
            return view(ctx, current);
        }
        Update u = new Update().set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now(clock))
                .inc("version", 1);
        diffs.forEach(d -> u.set(d.field(), d.to()));
        CrmContact saved = mongo.findAndModify(
                new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())
                        .and("version").is(req.version())),
                u, FindAndModifyOptions.options().returnNew(true), CrmContact.class);
        if (saved == null) {
            if (mongo.exists(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                    CrmContact.class)) throw versionConflict();
            throw new NotFoundException("Contact not found");
        }
        List<Map<String, Object>> changes = diffs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", d.field());
            m.put("from", d.from());
            m.put("to", d.to());
            return m;
        }).toList();
        if (!Objects.equals(current.getCompanyId(), saved.getCompanyId())) {
            companyCounters.recompute(ctx.workspaceId(), current.getCompanyId());
            companyCounters.recompute(ctx.workspaceId(), saved.getCompanyId());
        }
        activities.record(ctx, ActivityType.FIELD_CHANGED, new CrmTarget(TargetType.CONTACT, id), List.of(), null,
                saved.getChannel().phoneNumberId(),
                "Changed " + String.join(", ", diffs.stream().map(FieldDiff::field).toList()),
                new LinkedHashMap<>(Map.of("changes", changes)));
        return view(ctx, saved);
    }

    static RuntimeException versionConflict() {
        return CrmErrors.conflict("VERSION_CONFLICT", "The contact was changed by someone else; reload and retry",
                Map.of());
    }

    private static Map<String, Object> fields(String firstName, String lastName, String email, String jobTitle,
            String ownerId, String lifecycle, String source, String companyId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", firstName);
        m.put("lastName", lastName);
        m.put("email", email);
        m.put("jobTitle", jobTitle);
        m.put("ownerId", ownerId);
        m.put("lifecycleStatus", lifecycle);
        m.put("source", source);
        m.put("companyId", companyId);
        return m;
    }

    /** Absent = unchanged, blank = cleared, else trimmed; over-long values are reported. */
    private static String text(String given, String current, String field, int max, Map<String, String> errors) {
        if (given == null) return current;
        String t = given.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) errors.put(field, "must be at most " + max + " characters");
        return t;
    }

    /** As {@link #text} for a picklist: a non-empty list restricts to its active keys; the stored value always passes. */
    private static String pick(String given, String current, String field, List<PicklistItem> items,
            Map<String, String> errors) {
        String v = text(given, current, field, 64, errors);
        if (v == null || v.equals(current) || items == null || items.isEmpty()) return v;
        if (items.stream().noneMatch(i -> i.active() && i.key().equals(v))) errors.put(field, "is not an active option");
        return v;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    public CrmContactView view(CrmContext ctx, CrmContact c) {
        return views(ctx, List.of(c)).get(0);
    }

    public List<CrmContactView> views(CrmContext ctx, List<CrmContact> list) {
        if (list.isEmpty()) return List.of();
        String ws = ctx.workspaceId();
        Map<String, PlatformContact> live = contactDirectory.findAll(ws, list.stream().map(CrmContact::getContactId).toList());
        Set<String> userIds = new HashSet<>();
        for (CrmContact c : list) {
            if (c.getOwnerId() != null) userIds.add(c.getOwnerId());
            if (c.getOrigin() != null && c.getOrigin().capturedById() != null) userIds.add(c.getOrigin().capturedById());
            c.getChannelHistory().forEach(h -> {
                if (h.byUserId() != null) userIds.add(h.byUserId());
            });
        }
        Map<String, PlatformPerson> persons = people.people(userIds);
        Map<String, Ref> companies = companyRefs(ws, list);
        Map<String, ContactCounters.Totals> restricted = ctx.pipelines().all() ? null
                : counters.openTotals(ws, list.stream().map(CrmContact::getId).toList(), ctx.pipelines());
        List<CrmContactView> out = new ArrayList<>();
        for (CrmContact c : list) {
            PlatformContact p = live.get(c.getContactId());
            int count = c.getOpenDealCount();
            java.math.BigDecimal amount = c.getOpenDealAmount();
            if (restricted != null) {
                ContactCounters.Totals t = restricted.getOrDefault(c.getId(), ContactCounters.Totals.NONE);
                count = t.count();
                amount = t.amount();
            }
            Origin o = c.getOrigin();
            CrmContactView.OriginView origin = o == null ? null : new CrmContactView.OriginView(o.kind(),
                    o.phoneNumberId(), o.chatId(), o.firstMessageAt(), ref(persons, o.capturedById()));
            out.add(new CrmContactView(c.getId(), c.getContactId(), c.getChannel(),
                    p != null && p.phone() != null ? p.phone() : c.getPhone(), name(c, p), c.getFirstName(),
                    c.getLastName(), c.getEmail(), c.getJobTitle(), companies.get(c.getCompanyId()), ref(persons, c.getOwnerId()),
                    c.getLifecycleStatus(), c.getSource(), origin, c.getCustomFields(), count, amount,
                    c.getLastActivityAt(), c.getNextTaskDueAt(), p == null,
                    c.getChannelHistory().stream().map(h -> new CrmContactView.ChannelChangeView(
                            h.fromPhoneNumberId(), h.toPhoneNumberId(), ref(persons, h.byUserId()), h.at(),
                            h.reason())).toList(),
                    c.getCreationTime(), c.getVersion()));
        }
        return out;
    }

    private Map<String, Ref> companyRefs(String ws, List<CrmContact> list) {
        Set<String> ids = new HashSet<>();
        list.forEach(c -> {
            if (c.getCompanyId() != null) ids.add(c.getCompanyId());
        });
        if (ids.isEmpty()) return new HashMap<>();
        Query q = new Query(Criteria.where("workspaceId").is(ws).and("_id").in(ids));
        q.fields().include("name");
        Map<String, Ref> out = new HashMap<>();
        for (org.bson.Document d : mongo.find(q, org.bson.Document.class, CompanyCounters.COMPANIES)) {
            String id = d.get("_id").toString();
            out.put(id, new Ref(id, d.getString("name")));
        }
        return out;
    }

    /** The CRM contacts linked to the company that the caller may see, newest first (at most {@value #MAX_SIZE}). */
    public List<CrmContactView> linkedTo(CrmContext ctx, String companyId) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        all.add(Criteria.where("companyId").is(companyId));
        if (!ctx.pipelines().all()) all.add(ctx.pipelines().criteria("pipelineIds"));
        return views(ctx, mongo.find(new Query(new Criteria().andOperator(all))
                .with(Sort.by(Sort.Direction.DESC, "creationTime").and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(MAX_SIZE), CrmContact.class));
    }

    private static Ref ref(Map<String, PlatformPerson> persons, String userId) {
        if (userId == null) return null;
        PlatformPerson p = persons.get(userId);
        return new Ref(userId, p == null ? null : p.name() != null && !p.name().isBlank() ? p.name() : p.username());
    }

    /** "first last" if set, else the live brand contact's name, else the snapshot, else the phone. */
    public static String name(CrmContact c, PlatformContact live) {
        String own = ((c.getFirstName() == null ? "" : c.getFirstName()) + " "
                + (c.getLastName() == null ? "" : c.getLastName())).trim();
        if (!own.isEmpty()) return own;
        if (live != null && live.name() != null && !live.name().isBlank()) return live.name();
        if (c.getContactSnapshot() != null && c.getContactSnapshot().name() != null
                && !c.getContactSnapshot().name().isBlank()) return c.getContactSnapshot().name();
        return live != null && live.phone() != null ? live.phone() : c.getPhone();
    }
}
