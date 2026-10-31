package me.nageshk.omnichannel.crm.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Spec E C9: what a tick answers when the atomic write finds the task changed since it was read. */
class TaskTickRaceTest {

    final CrmContext ctx = new CrmContext("w1", "c1", "u1", Set.of("crm:view", "crm:edit"), null, List.of(), Set.of(),
            PipelineAccess.ALL);
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final TaskService service = new TaskService(mongo, mock(TaskAccess.class), mock(TaskCounters.class),
            mock(PeopleDirectory.class), mock(ActivityLog.class), Clock.systemUTC(), mock(TaskAssigneeCheck.class));

    @BeforeEach
    void theWriteFindsNothing() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(CrmTask.class))).thenReturn(null);
    }

    private static CrmTask task(TaskStatus status, String... stepIds) {
        CrmTask t = new CrmTask();
        t.setId("t1");
        t.setStatus(status);
        t.setAssigneeId("u1");
        t.setTarget(new CrmTarget(TargetType.DEAL, "d1"));
        t.setChecklist(java.util.Arrays.stream(stepIds).map(id -> {
            ChecklistItem i = new ChecklistItem();
            i.setId(id);
            return i;
        }).toList());
        return t;
    }

    private void readAs(CrmTask first, CrmTask second) {
        when(mongo.findOne(any(Query.class), eq(CrmTask.class))).thenReturn(first, second);
    }

    @Test
    void aStepRemovedByAConcurrentSaveIsNotFound() {
        readAs(task(TaskStatus.OPEN, "s1"), task(TaskStatus.OPEN));
        assertThatThrownBy(() -> service.tick(ctx, "t1", "s1", true)).isInstanceOf(NotFoundException.class)
                .hasMessage("Checklist item not found");
    }

    @Test
    void aTaskClosedMeanwhileIsNotOpen() {
        readAs(task(TaskStatus.OPEN, "s1"), task(TaskStatus.DONE, "s1"));
        assertThatThrownBy(() -> service.tick(ctx, "t1", "s1", true)).isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.code()).isEqualTo(TaskErrors.NOT_OPEN));
    }

    @Test
    void aTaskDeletedMeanwhileIsNotFound() {
        readAs(task(TaskStatus.OPEN, "s1"), null);
        assertThatThrownBy(() -> service.tick(ctx, "t1", "s1", true)).isInstanceOf(NotFoundException.class)
                .hasMessage("Task not found");
    }
}
