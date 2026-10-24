package me.nageshk.omnichannel.crm.task;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.task.dto.ReminderView;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** The caller's claimed, undismissed reminders, polled by the console (spec §6.4 delivery). */
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
                .and("status").is(TaskStatus.OPEN).and("reminderSentAt").ne(null).and("reminderSeenAt").is(null);
        List<CrmTask> due = mongo.find(new Query(new Criteria().andOperator(mine, access.visibleCriteria(ctx)))
                .with(Sort.by(Sort.Direction.DESC, "reminderSentAt")).limit(LIMIT), CrmTask.class);
        return tasks.views(ctx, due).stream()
                .map(t -> new ReminderView(t.id(), t.title(), t.dueAt(), t.target())).toList();
    }

    /** Dismisses the reminder of one of the caller's own tasks. */
    public void seen(CrmContext ctx, String taskId) {
        long updated = mongo.updateFirst(new Query(Criteria.where("_id").is(taskId).and("workspaceId")
                        .is(ctx.workspaceId()).and("assigneeId").is(ctx.userId())),
                new Update().set("reminderSeenAt", Instant.now(clock)), "crmTasks").getMatchedCount();
        if (updated == 0) throw new NotFoundException("Reminder not found");
    }
}
