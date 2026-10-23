package me.nageshk.omnichannel.app.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.service.contact.ContactDemoSeeder;
import me.nageshk.omnichannel.service.phone.DemoSeeder;
import me.nageshk.omnichannel.service.template.TemplateDemoSeeder;
import me.nageshk.omnichannel.service.template.TemplateMediaService;
import me.nageshk.omnichannel.service.template.TemplateService;
import me.nageshk.omnichannel.service.template.TemplateView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

@SpringBootTest(properties = "omnichannel.seed.demo=true")
class TemplateDemoSeederIT {

    @Autowired DemoSeeder demoSeeder;
    @Autowired ContactDemoSeeder contactSeeder;
    @Autowired TemplateDemoSeeder seeder;
    @Autowired TestUsers testUsers;
    @Autowired CustomerRepository customers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired TemplateService service;
    @Autowired TemplateMediaService media;
    @Autowired MessageTemplateRepository templates;
    @Autowired @Qualifier("templateMediaGridFs") GridFsTemplate gridFs;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        testUsers.deleteAll();
    }

    private Workspace brand(String name) {
        Customer c = customers.findAll().stream().filter(x -> x.getName().equals(DemoSeeder.CUSTOMER)).findFirst()
                .orElseThrow();
        return workspaces.findByCustomerIdOrderByName(c.getId()).stream().filter(w -> w.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private TemplateView named(Workspace brand, String name) {
        return service.list(brand.getId(), null, null, null, null).stream().filter(t -> t.name().equals(name))
                .findFirst().orElseThrow();
    }

    private long files() {
        return gridFs.find(new Query()).into(new java.util.ArrayList<>()).size();
    }

    @Test
    void seedsFiveTemplatesPerDemoBrand() throws IOException {
        demoSeeder.run(null);
        contactSeeder.run(null);
        seeder.run(null);

        for (String name : List.of("Demo Fashion", "Demo Electronics")) {
            Workspace brand = brand(name);
            List<TemplateView> all = service.list(brand.getId(), null, null, null, null);
            assertThat(all).extracting(TemplateView::name)
                    .containsExactly("Feedback poll", "New collection", "Order shipped", "Store location", "Welcome");
            assertThat(all).extracting(TemplateView::category)
                    .containsExactly("Feedback", "Promotion", "Orders", "Store", "Greeting");
            assertThat(all).allMatch(t -> t.createdBy().username().equals("demo-manager"));

            TemplateView welcome = named(brand, "Welcome");
            assertThat(welcome.type()).isEqualTo(TemplateType.TEXT);
            assertThat(welcome.content().getBody())
                    .isEqualTo("Hi {{contact.name}}, welcome to " + name + "! Reply here any time.");
            assertThat(welcome.placeholders()).singleElement().satisfies(p -> {
                assertThat(p.name()).isEqualTo("contact.name");
                assertThat(p.fallback()).isEqualTo("there");
            });

            TemplateView shipped = named(brand, "Order shipped");
            assertThat(shipped.placeholders()).extracting(TemplateView.PlaceholderView::name, p -> p.kind(),
                    p -> p.required(), p -> p.label(), p -> p.example())
                    .contains(org.assertj.core.groups.Tuple.tuple("order_id", PlaceholderKind.AGENT, true, "Order ID",
                            "FS-10234"),
                            org.assertj.core.groups.Tuple.tuple("delivery_date", PlaceholderKind.AGENT, true,
                                    "Delivery date", "25 Oct"));

            TemplateView location = named(brand, "Store location");
            assertThat(location.type()).isEqualTo(TemplateType.LOCATION);
            assertThat(location.content().getLatitude()).isEqualTo(19.0760);
            assertThat(location.content().getLongitude()).isEqualTo(72.8777);
            assertThat(location.content().getName()).isEqualTo(name + " flagship");
            assertThat(location.content().getAddress()).isEqualTo("Linking Road, Bandra West, Mumbai");

            TemplateView poll = named(brand, "Feedback poll");
            assertThat(poll.type()).isEqualTo(TemplateType.POLL);
            assertThat(poll.content().getQuestion()).isEqualTo("How was your last visit, {{contact.name}}?");
            assertThat(poll.content().getOptions()).containsExactly("Great", "Okay", "Not good");
            assertThat(poll.content().getAllowMultiple()).isFalse();

            TemplateView collection = named(brand, "New collection");
            assertThat(collection.type()).isEqualTo(TemplateType.IMAGE);
            assertThat(collection.media().mimeType()).isEqualTo("image/png");
            try (InputStream in = media.open(brand.getId(), collection.media().mediaId()).resource()
                    .getInputStream()) {
                byte[] bytes = in.readAllBytes();
                assertThat(bytes).hasSize((int) collection.media().size()).hasSizeLessThan(20 * 1024);
                assertThat(bytes[1]).isEqualTo((byte) 'P');
            }
        }

        TemplateView fashion = named(brand("Demo Fashion"), "New collection");
        assertThat(fashion.content().getCaption())
                .isEqualTo("Our new collection is in store in {{contact.attributes.city}}!");
        assertThat(fashion.placeholders()).singleElement().satisfies(p -> {
            assertThat(p.attributeName()).isEqualTo("City");
            assertThat(p.fallback()).isEqualTo("your city");
            assertThat(p.missing()).isFalse();
        });
        TemplateView electronics = named(brand("Demo Electronics"), "New collection");
        assertThat(electronics.content().getCaption()).contains("{{contact.name}}")
                .doesNotContain("contact.attributes");
    }

    @Test
    void secondRunIsNoOp() {
        demoSeeder.run(null);
        contactSeeder.run(null);
        seeder.run(null);
        long files = files();
        seeder.run(null);
        assertThat(templates.count()).isEqualTo(10);
        assertThat(files()).isEqualTo(files).isEqualTo(2);
    }

    @Test
    void skipsBrandWithTemplatesAndMissingDemoData() {
        seeder.run(null);
        assertThat(templates.count()).isZero();

        demoSeeder.run(null);
        MessageTemplate mine = new MessageTemplate();
        mine.setWorkspaceId(brand("Demo Electronics").getId());
        mine.setName("Mine");
        mine.setNameKey("mine");
        mine.setType(TemplateType.TEXT);
        mine.setStatus(TemplateStatus.ACTIVE);
        mine.setChannel(me.nageshk.omnichannel.db.phone.ConnectionType.WHATSAPP_WEB);
        templates.save(mine);
        seeder.run(null);
        assertThat(templates.count()).isEqualTo(6);
        assertThat(service.list(brand("Demo Electronics").getId(), null, null, null, TemplateStatus.ACTIVE))
                .hasSize(1);
    }
}
