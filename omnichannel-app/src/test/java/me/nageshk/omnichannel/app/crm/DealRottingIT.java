package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import me.nageshk.omnichannel.crm.job.DealRottingJob;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

class DealRottingIT extends CrmTestBase {

    @Autowired DealRottingJob job;

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private String deal(String name, String phone) throws Exception {
        return captured(contact(name, phone), number).get("deal").get("id").asText();
    }

    /** Sets the deal's three timestamps; a null one is removed. */
    private void age(String dealId, Instant lastActivity, Instant stageEntered, Instant created) {
        Update u = new Update();
        if (lastActivity == null) u.unset("lastActivityAt");
        else u.set("lastActivityAt", lastActivity);
        if (stageEntered == null) u.unset("stageEnteredAt");
        else u.set("stageEnteredAt", stageEntered);
        u.set("creationTime", created);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(dealId))), u, "crmDeals");
    }

    private void brandDays(Integer days) {
        Update u = days == null ? new Update().unset("dealRottingDays") : new Update().set("dealRottingDays", days);
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(brand.getId())), u, "crmSettings");
    }

    private boolean rotting(String dealId) throws Exception {
        JsonNode d = json(send(get(crm() + "/deals/" + dealId), managerToken, null).andExpect(status().isOk()));
        return d.get("rotting").asBoolean();
    }

    private long version(String dealId) throws Exception {
        return json(send(get(crm() + "/deals/" + dealId), managerToken, null)).get("version").asLong();
    }

    @Test
    void quietOpenDealFlagged() throws Exception {
        enableCrm();
        brandDays(14);
        String quiet = deal("Asha", "919900000501");
        String byStage = deal("Ravi", "919900000502");
        String byCreation = deal("Meena", "919900000503");
        String fresh = deal("Nila", "919900000504");
        age(quiet, daysAgo(20), daysAgo(30), daysAgo(40));
        age(byStage, null, daysAgo(15), daysAgo(1));
        age(byCreation, null, null, daysAgo(15));
        age(fresh, daysAgo(2), daysAgo(30), daysAgo(40));
        long before = version(quiet);
        assertThat(job.run()).isEqualTo(3);
        assertThat(rotting(quiet)).isTrue();
        assertThat(rotting(byStage)).isTrue();
        assertThat(rotting(byCreation)).isTrue();
        assertThat(rotting(fresh)).isFalse();
        assertThat(version(quiet)).isEqualTo(before);
    }

    @Test
    void recentActivityUnflags() throws Exception {
        enableCrm();
        brandDays(14);
        String d = deal("Asha", "919900000505");
        age(d, daysAgo(20), daysAgo(20), daysAgo(20));
        job.run();
        assertThat(rotting(d)).isTrue();
        age(d, daysAgo(1), daysAgo(20), daysAgo(20));
        job.run();
        assertThat(rotting(d)).isFalse();
    }

    @Test
    void pipelineDaysOverrideSettings() throws Exception {
        enableCrm();
        brandDays(30);
        String d = deal("Asha", "919900000506");
        age(d, daysAgo(10), daysAgo(10), daysAgo(10));
        job.run();
        assertThat(rotting(d)).isFalse();
        mongo.updateFirst(new Query(Criteria.where("_id").is(salesId())), new Update().set("rottingDays", 5),
                "crmPipelines");
        job.run();
        assertThat(rotting(d)).isTrue();
    }

    @Test
    void closedDealsNeverFlagged() throws Exception {
        enableCrm();
        brandDays(14);
        String d = deal("Asha", "919900000507");
        send(post(crm() + "/deals/" + d + "/close"), managerToken, Map.of("outcome", "WON"))
                .andExpect(status().isOk());
        age(d, daysAgo(40), daysAgo(40), daysAgo(40));
        assertThat(job.run()).isZero();
        assertThat(rotting(d)).isFalse();
    }

    @Test
    void noDaysMeansNeverRotting() throws Exception {
        enableCrm();
        brandDays(14);
        String d = deal("Asha", "919900000508");
        age(d, daysAgo(40), daysAgo(40), daysAgo(40));
        job.run();
        assertThat(rotting(d)).isTrue();
        brandDays(null);
        job.run();
        assertThat(rotting(d)).isFalse();
    }

    @Test
    void brandWithCrmOffSkipped() throws Exception {
        enableCrm();
        brandDays(14);
        String d = deal("Asha", "919900000509");
        age(d, daysAgo(40), daysAgo(40), daysAgo(40));
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(brand.getId())),
                new Update().set("enabled", false), "crmSettings");
        assertThat(job.run()).isZero();
        assertThat(mongo.getCollection("crmDeals").find(new Document("_id", new ObjectId(d)))
                .first().getBoolean("rotting")).isFalse();
    }
}
