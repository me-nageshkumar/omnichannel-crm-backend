package me.nageshk.omnichannel.crm.task;

import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Keeps {@code nextTaskDueAt} on deals and contacts: the earliest due time of their open tasks. */
@Component
public class TaskCounters {

    private final MongoTemplate mongo;

    TaskCounters(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void recompute(String workspaceId, CrmTarget target) {
        String collection = target.type() == TargetType.DEAL ? "crmDeals"
                : target.type() == TargetType.CONTACT ? "crmContacts" : null;
        if (collection == null) return; // companies keep no counter
        CrmTask next = mongo.findOne(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("target.type").is(target.type()).and("target.id").is(target.id())
                .and("status").is(TaskStatus.OPEN).and("dueAt").ne(null))
                .with(Sort.by(Sort.Direction.ASC, "dueAt")).limit(1), CrmTask.class);
        Update u = next == null ? new Update().unset("nextTaskDueAt") : new Update().set("nextTaskDueAt", next.getDueAt());
        // by collection name: an update through the entity class would bump the record's version
        mongo.updateFirst(new Query(Criteria.where("_id").is(target.id()).and("workspaceId").is(workspaceId)), u,
                collection);
    }
}
