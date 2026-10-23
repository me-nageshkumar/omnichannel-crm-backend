package me.nageshk.omnichannel.crm.task;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import me.nageshk.omnichannel.crm.task.dto.TaskCounts;
import me.nageshk.omnichannel.crm.task.dto.TaskRequest;
import me.nageshk.omnichannel.crm.task.dto.TaskView;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Follow-up tasks on contacts, companies and deals (spec §6.4). */
@Service
public class TaskService {

    public static final int MAX_TITLE = 200;
    public static final int MAX_DESCRIPTION = 5000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_PER_TARGET = 1000;

    private final MongoTemplate mongo;
    private final TaskAccess access;
    private final TaskCounters counters;
    private final PeopleDirectory people;
    private final ActivityLog activities;
    private final Clock clock;

    TaskService(MongoTemplate mongo, TaskAccess access, TaskCounters counters, PeopleDirectory people,
            ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.access = access;
        this.counters = counters;
        this.people = people;
        this.activities = activities;
        this.clock = clock;
    }

    // --- writes --------------------------------------------------------------------------------------------

    public TaskView create(CrmContext ctx, TaskRequest req) {
        ctx.require("crm:edit");
        TaskAccess.TargetInfo info = access.assertVisible(ctx, req.target());
        Map<String, String> errors = new LinkedHashMap<>();
        Fields f = fields(ctx, req, null, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        CrmTask t = new CrmTask();
        t.setWorkspaceId(ctx.workspaceId());
        t.setCustomerId(ctx.customerId());
        t.setTarget(new CrmTarget(req.target().type(), req.target().id()));
        t.setPipelineId(info.pipelineId());
        t.setPhoneNumberId(info.phoneNumberId());
        f.applyTo(t);
        t.setAssigneeId(f.assigneeId != null ? f.assigneeId : ctx.userId());
        t.setStatus(TaskStatus.OPEN);
        t.setCreatedBy(ctx.userId());
        t.setLastModifiedBy(ctx.userId());
        CrmTask saved = mongo.insert(t);
        counters.recompute(ctx.workspaceId(), saved.getTarget());
        record(ctx, ActivityType.TASK_CREATED, saved, "Task: " + saved.getTitle(), null);
        return views(ctx, List.of(saved)).get(0);
    }

    public TaskView update(CrmContext ctx, String id, TaskRequest req) {
        ctx.require("crm:edit");
        CrmTask current = load(ctx, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        Fields f = fields(ctx, req, current, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        Update u = new Update().set("title", f.title).set("description", f.description).set("type", f.type)
                .set("priority", f.priority).set("dueAt", f.dueAt).set("reminderAt", f.reminderAt)
                .set("assigneeId", f.assigneeId != null ? f.assigneeId : current.getAssigneeId());
        if (!java.util.Objects.equals(f.reminderAt, current.getReminderAt())) {
            u.unset("reminderSentAt").unset("reminderSeenAt"); // a new reminder time re-arms the reminder
        }
        CrmTask saved = guarded(ctx, id, req.version(), u);
        counters.recompute(ctx.workspaceId(), saved.getTarget());
        record(ctx, ActivityType.TASK_UPDATED, saved, "Task changed: " + saved.getTitle(), "UPDATED");
        return views(ctx, List.of(saved)).get(0);
    }

    public TaskView complete(CrmContext ctx, String id) {
        ctx.require("crm:edit");
        CrmTask current = load(ctx, id);
        if (current.getStatus() == TaskStatus.DONE) return views(ctx, List.of(current)).get(0);
        CrmTask saved = guarded(ctx, id, current.getVersion(), new Update().set("status", TaskStatus.DONE)
                .set("completedAt", Instant.now(clock)).set("completedBy", ctx.userId()));
        counters.recompute(ctx.workspaceId(), saved.getTarget());
        record(ctx, ActivityType.TASK_COMPLETED, saved, "Task done: " + saved.getTitle(), null);
        return views(ctx, List.of(saved)).get(0);
    }

    public TaskView reopen(CrmContext ctx, String id) {
        ctx.require("crm:edit");
        CrmTask current = load(ctx, id);
        if (current.getStatus() == TaskStatus.OPEN) return views(ctx, List.of(current)).get(0);
        CrmTask saved = guarded(ctx, id, current.getVersion(), new Update().set("status", TaskStatus.OPEN)
                .unset("completedAt").unset("completedBy"));
        counters.recompute(ctx.workspaceId(), saved.getTarget());
        record(ctx, ActivityType.TASK_UPDATED, saved, "Task reopened: " + saved.getTitle(), "REOPENED");
        return views(ctx, List.of(saved)).get(0);
    }

    public void delete(CrmContext ctx, String id) {
        ctx.require("crm:edit");
        CrmTask current = load(ctx, id);
        mongo.remove(byId(ctx, id), CrmTask.class);
        counters.recompute(ctx.workspaceId(), current.getTarget());
        record(ctx, ActivityType.TASK_UPDATED, current, "Task deleted: " + current.getTitle(), "DELETED");
    }

    // --- reads ---------------------------------------------------------------------------------------------

    /** A target's tasks: open ones first, then by due time (undated last). */
    public CrmPage<TaskView> forTarget(CrmContext ctx, CrmTarget target, String status, int page, int size) {
        access.assertVisible(ctx, target);
        int p = Math.max(0, page);
        int s = size <= 0 ? DEFAULT_LIMIT : Math.min(size, MAX_LIMIT);
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId()).and("target.type").is(target.type())
                .and("target.id").is(target.id());
        if (status != null && !status.isBlank()) c = c.and("status").is(parse(TaskStatus.class, status, "status"));
        List<CrmTask> all = new ArrayList<>(mongo.find(new Query(c).limit(MAX_PER_TARGET), CrmTask.class));
        all.sort(Comparator.comparing((CrmTask t) -> t.getStatus() != TaskStatus.OPEN)
                .thenComparing(CrmTask::getDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CrmTask::getId));
        List<CrmTask> slice = all.stream().skip((long) p * s).limit(s).toList();
        return new CrmPage<>(views(ctx, slice), all.size(), p);
    }

    /** The caller's (or, with {@code crm:view-all}, someone's) open tasks in one bucket. */
    public List<TaskView> mine(CrmContext ctx, Bucket bucket, ZoneId zone, String assigneeId, Integer limit) {
        String who = assignee(ctx, assigneeId);
        int n = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Buckets b = Buckets.of(Instant.now(clock), zone);
        Criteria base = openOf(ctx, who);
        List<CrmTask> out = new ArrayList<>();
        switch (bucket) {
            case OVERDUE -> out.addAll(find(new Criteria().andOperator(base, Criteria.where("dueAt").lt(b.now())), n));
            case TODAY -> out.addAll(find(new Criteria().andOperator(base,
                    Criteria.where("dueAt").gte(b.now()).lt(b.tomorrowStart())), n));
            case UPCOMING -> {
                out.addAll(find(new Criteria().andOperator(base, Criteria.where("dueAt").gte(b.tomorrowStart())), n));
                if (out.size() < n) out.addAll(find(new Criteria().andOperator(base, Criteria.where("dueAt").is(null)),
                        n - out.size()));
            }
        }
        return views(ctx, out);
    }

    public TaskCounts counts(CrmContext ctx, ZoneId zone, String assigneeId) {
        String who = assignee(ctx, assigneeId);
        Buckets b = Buckets.of(Instant.now(clock), zone);
        Criteria base = openOf(ctx, who);
        long overdue = mongo.count(new Query(new Criteria().andOperator(base, Criteria.where("dueAt").lt(b.now()))),
                CrmTask.class);
        long today = mongo.count(new Query(new Criteria().andOperator(base,
                Criteria.where("dueAt").gte(b.now()).lt(b.tomorrowStart()))), CrmTask.class);
        long upcoming = mongo.count(new Query(new Criteria().andOperator(base, new Criteria().orOperator(
                Criteria.where("dueAt").gte(b.tomorrowStart()), Criteria.where("dueAt").is(null)))), CrmTask.class);
        return new TaskCounts(overdue, today, upcoming);
    }

    /** The earliest open task on the contact or one of the given (already visible) deals, or null. */
    public TaskView nextFor(CrmContext ctx, String crmContactId, Collection<String> dealIds) {
        List<Criteria> on = new ArrayList<>();
        on.add(new Criteria().andOperator(Criteria.where("target.type").is(TargetType.CONTACT),
                Criteria.where("target.id").is(crmContactId)));
        if (!dealIds.isEmpty()) {
            on.add(new Criteria().andOperator(Criteria.where("target.type").is(TargetType.DEAL),
                    Criteria.where("target.id").in(dealIds)));
        }
        Criteria c = new Criteria().andOperator(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("status").is(TaskStatus.OPEN), new Criteria().orOperator(on));
        List<CrmTask> dated = mongo.find(new Query(new Criteria().andOperator(c, Criteria.where("dueAt").ne(null)))
                .with(Sort.by(Sort.Direction.ASC, "dueAt")).limit(1), CrmTask.class);
        if (dated.isEmpty()) {
            dated = mongo.find(new Query(c).with(Sort.by(Sort.Direction.ASC, "_id")).limit(1), CrmTask.class);
        }
        return dated.isEmpty() ? null : views(ctx, dated).get(0);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private List<CrmTask> find(Criteria c, int limit) {
        return mongo.find(new Query(c).with(Sort.by(Sort.Direction.ASC, "dueAt").and(Sort.by("_id"))).limit(limit),
                CrmTask.class);
    }

    private Criteria openOf(CrmContext ctx, String assigneeId) {
        return new Criteria().andOperator(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("assigneeId").is(assigneeId).and("status").is(TaskStatus.OPEN), access.visibleCriteria(ctx));
    }

    private static String assignee(CrmContext ctx, String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank() || assigneeId.equals(ctx.userId())) return ctx.userId();
        if (!ctx.viewAll()) throw new ForbiddenException("seeing someone else's tasks requires crm:view-all");
        return assigneeId.trim();
    }

    private CrmTask load(CrmContext ctx, String id) {
        CrmTask t = mongo.findOne(byId(ctx, id), CrmTask.class);
        if (t == null) throw new NotFoundException("Task not found");
        access.assertVisible(ctx, t.getTarget());
        return t;
    }

    private static Query byId(CrmContext ctx, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId()));
    }

    private CrmTask guarded(CrmContext ctx, String id, Long version, Update u) {
        u.set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now(clock)).inc("version", 1);
        CrmTask saved = mongo.findAndModify(new Query(Criteria.where("_id").is(id).and("workspaceId")
                .is(ctx.workspaceId()).and("version").is(version)), u, FindAndModifyOptions.options().returnNew(true),
                CrmTask.class);
        if (saved != null) return saved;
        if (mongo.exists(byId(ctx, id), CrmTask.class)) {
            throw CrmErrors.conflict("VERSION_CONFLICT", "The task was changed by someone else; reload and retry",
                    Map.of());
        }
        throw new NotFoundException("Task not found");
    }

    private void record(CrmContext ctx, ActivityType type, CrmTask t, String summary, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", t.getId());
        if (action != null) payload.put("action", action);
        activities.record(ctx, type, t.getTarget(), List.of(), t.getPipelineId(), t.getPhoneNumberId(), summary,
                payload);
    }

    /** The validated editable fields of a create or a full update. */
    private static final class Fields {
        String title;
        String description;
        String type;
        TaskPriority priority;
        Instant dueAt;
        Instant reminderAt;
        String assigneeId;

        void applyTo(CrmTask t) {
            t.setTitle(title);
            t.setDescription(description);
            t.setType(type);
            t.setPriority(priority);
            t.setDueAt(dueAt);
            t.setReminderAt(reminderAt);
        }
    }

    private Fields fields(CrmContext ctx, TaskRequest req, CrmTask current, Map<String, String> errors) {
        Fields f = new Fields();
        f.title = req.title() == null ? "" : req.title().trim();
        if (f.title.isEmpty()) errors.put("title", "is required");
        else if (f.title.length() > MAX_TITLE) errors.put("title", "must be at most " + MAX_TITLE + " characters");
        f.description = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        if (f.description != null && f.description.length() > MAX_DESCRIPTION)
            errors.put("description", "must be at most " + MAX_DESCRIPTION + " characters");
        f.type = req.type() == null || req.type().isBlank() ? null : req.type().trim();
        if (f.type != null && !(current != null && f.type.equals(current.getType()))) {
            List<PicklistItem> types = ctx.settings().getTaskTypes();
            if (types != null && !types.isEmpty() && types.stream().noneMatch(i -> i.active() && i.key().equals(f.type)))
                errors.put("type", "is not an active task type");
        }
        try {
            f.priority = req.priority() == null || req.priority().isBlank() ? TaskPriority.NORMAL
                    : TaskPriority.valueOf(req.priority().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            errors.put("priority", "must be LOW, NORMAL or HIGH");
        }
        f.dueAt = req.dueAt();
        f.reminderAt = req.reminderAt();
        if (f.dueAt != null && f.reminderAt != null && f.reminderAt.isAfter(f.dueAt))
            errors.put("reminderAt", "must not be after the due time");
        if (req.assigneeId() != null && !req.assigneeId().isBlank()) {
            String a = req.assigneeId().trim();
            if (!(current != null && a.equals(current.getAssigneeId())) && !people.isBrandUser(ctx.workspaceId(), a))
                errors.put("assigneeId", "must be a user of this brand");
            f.assigneeId = a;
        }
        if (current != null && req.target() != null && (req.target().type() != current.getTarget().type()
                || !java.util.Objects.equals(req.target().id(), current.getTarget().id())))
            errors.put("target", "cannot change");
        return f;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field, "is not a valid value");
        }
    }

    List<TaskView> views(CrmContext ctx, List<CrmTask> tasks) {
        if (tasks.isEmpty()) return List.of();
        Set<String> users = new LinkedHashSet<>();
        Map<TargetType, Set<String>> ids = new HashMap<>();
        for (CrmTask t : tasks) {
            if (t.getAssigneeId() != null) users.add(t.getAssigneeId());
            if (t.getCompletedBy() != null) users.add(t.getCompletedBy());
            ids.computeIfAbsent(t.getTarget().type(), k -> new LinkedHashSet<>()).add(t.getTarget().id());
        }
        Map<String, PlatformPerson> who = people.people(users);
        Map<String, String> names = new HashMap<>();
        names(ctx, ids.get(TargetType.DEAL), "crmDeals", names, d -> d.getString("title"));
        names(ctx, ids.get(TargetType.COMPANY), "crmCompanies", names, d -> d.getString("name"));
        names(ctx, ids.get(TargetType.CONTACT), "crmContacts", names, TaskService::contactName);
        Instant now = Instant.now(clock);
        return tasks.stream().map(t -> new TaskView(t.getId(), t.getTitle(), t.getDescription(), t.getType(),
                t.getPriority(), t.getDueAt(), t.getReminderAt(), t.getStatus(), t.getCompletedAt(),
                ref(t.getCompletedBy(), who), ref(t.getAssigneeId(), who),
                new TaskView.Target(t.getTarget().type(), t.getTarget().id(), names.get(t.getTarget().id())),
                t.getStatus() == TaskStatus.OPEN && t.getDueAt() != null && t.getDueAt().isBefore(now),
                t.getVersion())).toList();
    }

    private void names(CrmContext ctx, Set<String> ids, String collection, Map<String, String> out,
            java.util.function.Function<Document, String> name) {
        if (ids == null || ids.isEmpty()) return;
        for (Document d : mongo.find(new Query(Criteria.where("_id").in(toObjectIds(ids)).and("workspaceId")
                .is(ctx.workspaceId())), Document.class, collection)) {
            out.put(String.valueOf(d.get("_id")), name.apply(d));
        }
    }

    /** Record ids are stored as ObjectIds when they look like one (Spring's mapping of String ids). */
    private static List<Object> toObjectIds(Collection<String> ids) {
        List<Object> out = new ArrayList<>();
        for (String id : ids) {
            out.add(id);
            if (org.bson.types.ObjectId.isValid(id)) out.add(new org.bson.types.ObjectId(id));
        }
        return out;
    }

    private static String contactName(Document d) {
        String full = ((d.getString("firstName") == null ? "" : d.getString("firstName")) + " "
                + (d.getString("lastName") == null ? "" : d.getString("lastName"))).trim();
        if (!full.isEmpty()) return full;
        Object snap = d.get("contactSnapshot");
        if (snap instanceof Document s && s.getString("name") != null && !s.getString("name").isBlank())
            return s.getString("name");
        return d.getString("phone");
    }

    private static Ref ref(String id, Map<String, PlatformPerson> who) {
        if (id == null) return null;
        PlatformPerson p = who.get(id);
        return new Ref(id, p == null ? null : p.name());
    }
}
