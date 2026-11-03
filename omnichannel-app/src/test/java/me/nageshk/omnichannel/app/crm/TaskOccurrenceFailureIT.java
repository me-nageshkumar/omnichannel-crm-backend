package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoClient;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.nageshk.omnichannel.app.support.MongoTestCommands;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Spec E C13: completing an occurrence when the writes race or break half way, on a real server with failpoints. */
@Import(MongoTestCommands.class)
class TaskOccurrenceFailureIT extends CrmTestBase {

    @Autowired MongoClient client;

    String taskId;

    @BeforeEach
    void repeatingTask() throws Exception {
        enableCrm();
        String dealId = captured(contact("Asha", "919900000601"), number).get("deal").get("id").asText();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("frequency", "DAILY");
        rule.put("interval", 1);
        rule.put("timeZone", "UTC");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Stock check");
        body.put("target", Map.of("type", "DEAL", "id", dealId));
        body.put("dueAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        body.put("recurrence", rule);
        taskId = json(send(post(crm() + "/tasks"), managerToken, body).andExpect(status().isOk())).get("id").asText();
    }

    @AfterEach
    void failpointOff() {
        MongoTestCommands.allowCommands(client);
    }

    private int complete() throws Exception {
        return act("complete");
    }

    /** The answer; the broken write surfaces as the exception a real server would turn into a 500. */
    private int act(String action) throws Exception {
        try {
            return send(post(crm() + "/tasks/" + taskId + "/" + action), managerToken, null).andReturn().getResponse()
                    .getStatus();
        } catch (ServletException broken) {
            return 500;
        }
    }

    private List<Document> tasks() {
        return mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId())), Document.class, "crmTasks");
    }

    private Document original() {
        return tasks().stream().filter(t -> t.get("_id").toString().equals(taskId)).findFirst().orElseThrow();
    }

    @Test
    void twoSimultaneousCompletionsMakeOneNextOccurrence() throws Exception {
        // both requests read the same version, then each write waits until the other has read too
        MongoTestCommands.delayNext(client, mongo.getDb().getName(), "crmTasks", "findAndModify", 2, 700);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> answers = new ArrayList<>();
        try {
            List<Future<Integer>> calls = List.of(pool.submit(this::complete), pool.submit(this::complete));
            for (Future<Integer> call : calls) answers.add(call.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(answers).containsExactlyInAnyOrder(200, 409);
        assertThat(tasks()).hasSize(2);
        Document next = tasks().stream().filter(t -> !t.get("_id").toString().equals(taskId)).findFirst().orElseThrow();
        assertThat(next.getString("status")).isEqualTo("OPEN");
        assertThat(original().getString("status")).isEqualTo("DONE");
        assertThat(original().getString("nextOccurrenceId")).isEqualTo(next.get("_id").toString());
    }

    @Test
    void aFailedInsertKeepsTheSeriesAliveAndARetryFinishesIt() throws Exception {
        MongoTestCommands.failNext(client, mongo.getDb().getName(), "crmTasks", "insert", 1);
        assertThat(complete()).isEqualTo(500);
        assertThat(original().getString("status")).as("not done until the next one is stored").isEqualTo("OPEN");
        assertThat(tasks()).hasSize(1);
        assertThat(complete()).isEqualTo(200);
        assertThat(tasks()).hasSize(2);
        assertThat(original().getString("status")).isEqualTo("DONE");
        assertThat(original().get("pendingNextOccurrenceId")).isNull();
        String nextId = original().getString("nextOccurrenceId");
        assertThat(tasks()).filteredOn(t -> t.get("_id").toString().equals(nextId)).hasSize(1);
    }

    @Test
    void aRetryAfterTheInsertDoesNotMakeASecondOccurrence() throws Exception {
        // the claim goes through, the next occurrence is stored, closing the task fails
        MongoTestCommands.failAfter(client, mongo.getDb().getName(), "crmTasks", "findAndModify", 1);
        assertThat(complete()).isEqualTo(500);
        MongoTestCommands.allowCommands(client);
        assertThat(tasks()).hasSize(2);
        assertThat(original().getString("status")).isEqualTo("OPEN");
        assertThat(complete()).isEqualTo(200);
        assertThat(tasks()).as("the stored occurrence is adopted").hasSize(2);
        assertThat(original().getString("status")).isEqualTo("DONE");
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId()).and("type").is("TASK_CREATED")
                .and("payload.recurringFromTaskId").is(taskId)), "crmActivities")).isEqualTo(1);
    }

    @Test
    void aChangeWhileCompletingDoesNotLeaveTheNextOccurrenceBesideAnOpenTask() throws Exception {
        // the claim goes through, then a tick lands while the next occurrence is being stored
        MongoTestCommands.delayNext(client, mongo.getDb().getName(), "crmTasks", "insert", 1, 700);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> call = pool.submit(this::complete);
            while (original().get("pendingNextOccurrenceId") == null) Thread.onSpinWait();
            mongo.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(taskId))),
                    new org.springframework.data.mongodb.core.query.Update().inc("version", 1), "crmTasks");
            assertThat(call.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(original().getString("status")).isEqualTo("DONE");
        assertThat(original().get("pendingNextOccurrenceId")).isNull();
        assertThat(tasks()).filteredOn(t -> "OPEN".equals(t.getString("status"))).hasSize(1);
    }

    @Test
    void anEditEndingTheRepeatWhileCompletingLeavesNoNextOccurrence() throws Exception {
        // the claim goes through, then an edit removes the repeat rule while the next occurrence is being stored
        MongoTestCommands.delayNext(client, mongo.getDb().getName(), "crmTasks", "insert", 1, 700);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> call = pool.submit(this::complete);
            while (original().get("pendingNextOccurrenceId") == null) Thread.onSpinWait();
            mongo.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(taskId))),
                    new org.springframework.data.mongodb.core.query.Update().unset("recurrence").inc("version", 1),
                    "crmTasks");
            assertThat(call.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(original().getString("status")).isEqualTo("DONE");
        assertThat(original().get("pendingNextOccurrenceId")).isNull();
        assertThat(original().get("nextOccurrenceId")).isNull();
        assertThat(tasks()).as("the task no longer repeats, so nothing follows it").hasSize(1);
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId()).and("type").is("TASK_CREATED")
                .and("payload.recurringFromTaskId").is(taskId)), "crmActivities")).isZero();
    }

    @Test
    void skippingFinishesTheSameWayAfterAFailure() throws Exception {
        MongoTestCommands.failNext(client, mongo.getDb().getName(), "crmTasks", "insert", 1);
        assertThat(act("skip")).isEqualTo(500);
        assertThat(original().getString("status")).isEqualTo("OPEN");
        assertThat(act("skip")).isEqualTo(200);
        assertThat(tasks()).hasSize(2);
        assertThat(original().getString("status")).isEqualTo("CANCELLED");
    }
}
