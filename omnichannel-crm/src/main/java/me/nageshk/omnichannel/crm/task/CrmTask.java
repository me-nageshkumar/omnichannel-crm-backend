package me.nageshk.omnichannel.crm.task;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A follow-up on a contact, company or deal (spec §6.4); its visibility follows the target. */
@Document("crmTasks")
@CompoundIndex(name = "ws_assignee_status_due", def = "{'workspaceId': 1, 'assigneeId': 1, 'status': 1, 'dueAt': 1}")
@CompoundIndex(name = "ws_target_status_due",
        def = "{'workspaceId': 1, 'target.type': 1, 'target.id': 1, 'status': 1, 'dueAt': 1}")
@CompoundIndex(name = "ws_status_reminder", def = "{'workspaceId': 1, 'status': 1, 'reminderAt': 1}")
@Getter
@Setter
public class CrmTask {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String title;
    private String description;
    /** A {@code taskTypes} picklist key, or null. */
    private String type;
    private TaskPriority priority = TaskPriority.NORMAL;
    private Instant dueAt;
    /** Not after {@code dueAt}. */
    private Instant reminderAt;
    private TaskStatus status = TaskStatus.OPEN;
    private Instant completedAt;
    private String completedBy;
    private String assigneeId;
    private CrmTarget target;
    /** The deal's pipeline for deal targets, else null. */
    private String pipelineId;
    /** The target's channel; null for companies. */
    private String phoneNumberId;
    /** Set once the reminder job claims the task (at most one reminder per reminderAt). */
    private Instant reminderSentAt;
    /** Set when the assignee dismisses the reminder in the console. */
    private Instant reminderSeenAt;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
