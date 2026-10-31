package me.nageshk.omnichannel.crm.task;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.task.dto.ReminderView;
import me.nageshk.omnichannel.crm.task.dto.TaskView;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The caller's undismissed notices, polled by the console (spec §6.4 delivery, E C4): reminders the job claimed, and
 * tasks someone else handed to them. One entry per task; a task that is both is listed as ASSIGNED until seen.
 */
@Service
public class ReminderService {

    static final int LIMIT = 20;

    private final MongoTemplate mongo;
    private final TaskAccess access;
    private final TaskService tasks;
    private final Clock clock;

    ReminderService(MongoTemplate mongo, TaskAccess access, TaskService tasks, Clock clock) {
        this.mongo = mongo;
        this.access = access;
        this.tasks = tasks;
        this.clock = clock;
    }

    public List<ReminderView> due(CrmContext ctx) {
        Criteria mine = Criteria.where("workspaceId").is(ctx.workspaceId()).and("assigneeId").is(ctx.userId())
                .and("status").is(TaskStatus.OPEN);
        Criteria visible = access.visibleCriteria(ctx);
        List<CrmTask> assigned = mongo.find(new Query(new Criteria().andOperator(mine, visible,
                        Criteria.where("assignedBy").ne(ctx.userId()).and("assignmentSeenAt").is(null)))
                .with(Sort.by(Sort.Direction.DESC, "assignedAt")).limit(LIMIT), CrmTask.class);
        List<CrmTask> due = mongo.find(new Query(new Criteria().andOperator(mine, visible,
                        Criteria.where("reminderSentAt").ne(null).and("reminderSeenAt").is(null)))
                .with(Sort.by(Sort.Direction.DESC, "reminderSentAt")).limit(LIMIT), CrmTask.class);
        // one entry per task: the hand-off is the news while it is unseen
        Map<String, Notice> byTask = new LinkedHashMap<>();
        assigned.forEach(t -> byTask.put(t.getId(), new Notice(t, ReminderKind.ASSIGNED, t.getAssignedAt())));
        due.forEach(t -> byTask.putIfAbsent(t.getId(), new Notice(t, ReminderKind.DUE, t.getReminderSentAt())));
        List<Notice> notices = new ArrayList<>(byTask.values());
        notices.sort(Comparator.comparing(Notice::at, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Notice> top = notices.subList(0, Math.min(LIMIT, notices.size()));
        List<TaskView> views = tasks.views(ctx, top.stream().map(Notice::task).toList());
        List<ReminderView> out = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            TaskView v = views.get(i);
            ReminderKind kind = top.get(i).kind();
            out.add(new ReminderView(v.id(), v.title(), v.dueAt(), v.target(), kind,
                    kind == ReminderKind.ASSIGNED ? v.assignedBy() : null));
        }
        return out;
    }

    private record Notice(CrmTask task, ReminderKind kind, Instant at) {}

    /** Dismisses the notices of one of the caller's own tasks: the hand-off, and the due reminder if claimed. */
    public void seen(CrmContext ctx, String taskId) {
        Criteria own = Criteria.where("_id").is(taskId).and("workspaceId").is(ctx.workspaceId())
                .and("assigneeId").is(ctx.userId());
        if (!mongo.exists(new Query(own), "crmTasks")) throw new NotFoundException("Reminder not found");
        Instant now = Instant.now(clock);
        // by collection name: dismissing must not bump the task's version
        mongo.updateFirst(new Query(new Criteria().andOperator(own, Criteria.where("assignmentSeenAt").is(null))),
                new Update().set("assignmentSeenAt", now), "crmTasks");
        mongo.updateFirst(new Query(new Criteria().andOperator(own, Criteria.where("reminderSentAt").ne(null))),
                new Update().set("reminderSeenAt", now), "crmTasks");
    }
}
