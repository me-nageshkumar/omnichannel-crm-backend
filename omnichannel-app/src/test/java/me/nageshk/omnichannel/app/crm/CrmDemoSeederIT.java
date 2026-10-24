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
