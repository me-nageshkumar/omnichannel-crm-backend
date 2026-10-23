package me.nageshk.omnichannel.crm.job;

import java.time.Clock;
import java.time.Instant;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Claims due task reminders: an open task with an assignee whose {@code reminderAt} has passed gets
 * {@code reminderSentAt} once. Delivery is the console's poll of {@code GET /crm/reminders}, so claiming is the whole
 * job; a reminder is claimed at most once per {@code reminderAt} (editing it re-arms the task).
 */
@Component
public class TaskReminderJob {

    static final int BATCH = 200;

    private final MongoTemplate mongo;
    private final Clock clock;

    TaskReminderJob(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Claims up to {@value #BATCH} due reminders; the number claimed. */
    public int run() {
        Instant now = Instant.now(clock);
        Query due = new Query(Criteria.where("status").is("OPEN").and("reminderAt").lte(now)
                .and("reminderSentAt").is(null).and("assigneeId").ne(null));
        int claimed = 0;
        while (claimed < BATCH) {
            // by collection name: claiming must not bump the task's version (the owner may be editing it)
            Document task = mongo.findAndModify(due, new Update().set("reminderSentAt", now),
                    FindAndModifyOptions.options().returnNew(true), Document.class, "crmTasks");
            if (task == null) break;
            claimed++;
        }
        return claimed;
    }
}
