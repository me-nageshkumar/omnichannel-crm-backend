package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.crm.demo.CrmDemoSeeder;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.service.contact.ContactDemoSeeder;
import me.nageshk.omnichannel.service.phone.DemoSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest(properties = "omnichannel.seed.demo=true")
class CrmDemoSeederIT {

    @Autowired DemoSeeder demoSeeder;
    @Autowired ContactDemoSeeder contactSeeder;
    @Autowired CrmDemoSeeder seeder;
    @Autowired TestUsers testUsers;
    @Autowired CustomerRepository customers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        wipe();
    }

    @AfterEach
    void cleanAfter() {
        wipe();
    }

    private void wipe() {
        testUsers.deleteAll();
        for (String name : mongo.getCollectionNames())
            if (name.startsWith("crm")) mongo.remove(new Query(), name);
    }

    private long count(String collection, Criteria c) {
        return mongo.count(new Query(c), collection);
    }

    @Test
    void seedsDemoFashionOnce() {
        demoSeeder.run(null);
        contactSeeder.run(null);
        seeder.run(null);
        Customer retail = customers.findAll().stream().filter(x -> x.getName().equals(DemoSeeder.CUSTOMER))
                .findFirst().orElseThrow();
        Workspace fashion = workspaces.findByCustomerIdOrderByName(retail.getId()).stream()
                .filter(w -> w.getName().equals("Demo Fashion")).findFirst().orElseThrow();
        java.util.function.Supplier<Criteria> ws = () -> Criteria.where("workspaceId").is(fashion.getId());

        assertThat(count("crmSettings", ws.get().and("enabled").is(true))).isEqualTo(1);
        assertThat(count("crmCompanies", ws.get())).isEqualTo(3);
        assertThat(count("crmContacts", ws.get())).isEqualTo(8);
        assertThat(count("crmDeals", ws.get())).isEqualTo(10);
        assertThat(count("crmDeals", Criteria.where("workspaceId").is(fashion.getId()).and("status").is("WON")))
                .isEqualTo(1);
        assertThat(count("crmDeals", Criteria.where("workspaceId").is(fashion.getId()).and("status").is("LOST")))
                .isEqualTo(1);
        assertThat(count("crmDeals", Criteria.where("workspaceId").is(fashion.getId()).and("rotting").is(true)))
                .isEqualTo(1);
        List<String> lanes = mongo.findDistinct(new Query(Criteria.where("workspaceId").is(fashion.getId())),
                "stageId", "crmDeals", String.class);
        assertThat(lanes).hasSizeGreaterThanOrEqualTo(6);
        assertThat(count("crmActivities", Criteria.where("workspaceId").is(fashion.getId())
                .and("type").is("CALL_LOGGED"))).isEqualTo(2);
        assertThat(count("crmPipelineAccess", ws.get())).isEqualTo(1);
        assertThat(mongo.findDistinct(new Query(ws.get().and("entityType").is("DEAL")), "key",
                "crmFieldDefinitions", String.class)).containsExactlyInAnyOrder("tier", "budget");
        assertThat(count("crmTasks", ws.get())).isEqualTo(3);
        assertThat(count("crmTasks", ws.get().and("dueAt").lt(java.time.Instant.now()))).isEqualTo(1);
        assertThat(count("crmTasks", ws.get().and("reminderAt").lt(java.time.Instant.now()))).isEqualTo(1);
        assertThat(count("crmNotes", ws.get().and("pinned").is(true))).isEqualTo(1);
        // backdated for the reports (phase C §7): creation spread over the last 60 days, outcomes 3 and 10 days ago
        java.time.Instant now = java.time.Instant.now();
        List<java.util.Date> created = mongo.findDistinct(new Query(ws.get()), "creationTime", "crmDeals",
                java.util.Date.class);
        assertThat(created.stream().map(d -> d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .distinct().count()).isGreaterThanOrEqualTo(8);
        assertThat(created).allSatisfy(d -> assertThat(d.toInstant())
                .isAfter(now.minus(61, java.time.temporal.ChronoUnit.DAYS)).isBefore(now));
        org.bson.Document won = mongo.findOne(new Query(ws.get().and("status").is("WON")), org.bson.Document.class,
                "crmDeals");
        org.bson.Document lost = mongo.findOne(new Query(ws.get().and("status").is("LOST")), org.bson.Document.class,
                "crmDeals");
        assertThat(((java.util.Date) won.get("closedAt")).toInstant())
                .isBetween(now.minus(4, java.time.temporal.ChronoUnit.DAYS), now.minus(2, java.time.temporal.ChronoUnit.DAYS));
        assertThat(((java.util.Date) lost.get("closedAt")).toInstant())
                .isBetween(now.minus(11, java.time.temporal.ChronoUnit.DAYS), now.minus(9, java.time.temporal.ChronoUnit.DAYS));
        List<org.bson.Document> moves = mongo.find(new Query(ws.get().and("type").is("STAGE_CHANGED")),
                org.bson.Document.class, "crmActivities");
        assertThat(moves).isNotEmpty().allSatisfy(m -> {
            assertThat(m.get("payload", org.bson.Document.class)).containsKey("fromStageEnteredAt");
            assertThat(((java.util.Date) m.get("happenedAt")).toInstant()).isBefore(now.minus(1, java.time.temporal.ChronoUnit.HOURS));
        });
        assertThat(mongo.find(new Query(ws.get().and("type").is("CALL_LOGGED")), org.bson.Document.class,
                "crmActivities")).hasSize(2).allSatisfy(c -> assertThat(((java.util.Date) c.get("happenedAt")).toInstant())
                .isAfter(now.minus(7, java.time.temporal.ChronoUnit.DAYS)));
        // every seeded record belongs to a real demo user, never to the system actor that created it
        assertThat(count("crmContacts", ws.get().and("ownerId").is("system"))).isZero();
        assertThat(count("crmDeals", ws.get().and("ownerId").is("system"))).isZero();

        seeder.run(null);
        assertThat(count("crmTasks", ws.get())).isEqualTo(3);
        assertThat(count("crmNotes", ws.get())).isEqualTo(1);
        assertThat(count("crmCompanies", ws.get())).isEqualTo(3);
        assertThat(count("crmContacts", ws.get())).isEqualTo(8);
        assertThat(count("crmDeals", ws.get())).isEqualTo(10);
    }

    @Test
    void skipsWithoutDemoBrand() {
        seeder.run(null);
        assertThat(mongo.count(new Query(), "crmSettings")).isZero();
    }
}
