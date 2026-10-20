package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.phone.DemoSeeder;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "omnichannel.seed.demo=true")
class ContactDemoSeederIT {

    @Autowired DemoSeeder demoSeeder;
    @Autowired ContactDemoSeeder seeder;
    @Autowired TestUsers testUsers;
    @Autowired CustomerRepository customers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired ContactRepository contacts;
    @Autowired ContactLabelRepository labels;
    @Autowired ContactAttributeRepository attributes;
    @Autowired ContactNoteRepository notes;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        testUsers.deleteAll();
    }

    private Workspace fashion() {
        Customer c = customers.findAll().stream().filter(x -> x.getName().equals(DemoSeeder.CUSTOMER)).findFirst().orElseThrow();
        return workspaces.findByCustomerIdOrderByName(c.getId()).stream()
                .filter(w -> w.getName().equals("Demo Fashion")).findFirst().orElseThrow();
    }

    @Test
    void seedsWhenBrandExists() {
        demoSeeder.run(null);
        seeder.run(null);
        Workspace brand = fashion();

        assertThat(labels.findByWorkspaceIdOrderByNameKey(brand.getId()))
                .extracting(ContactLabel::getName, ContactLabel::getColor)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("VIP", "purple"),
                        org.assertj.core.groups.Tuple.tuple("Lead", "blue"),
                        org.assertj.core.groups.Tuple.tuple("Customer", "green"),
                        org.assertj.core.groups.Tuple.tuple("Follow-up", "orange"));
        assertThat(attributes.findByWorkspaceIdOrderByNameKey(brand.getId())).extracting(ContactAttribute::getName)
                .containsExactlyInAnyOrder("City", "Email", "Preferred size");

        List<Contact> all = contacts.findAll();
        assertThat(all).hasSize(40);
        assertThat(all).allMatch(c -> c.getWorkspaceId().equals(brand.getId()));
        assertThat(all.stream().filter(c -> c.getPhone() == null)).hasSize(4)
                .allMatch(c -> c.getWaId().matches("9000000000000\\d\\d@lid"));
        assertThat(all.stream().filter(c -> c.getPhone() != null))
                .allMatch(c -> c.getPhone().matches("91900000\\d{4}"));
        assertThat(all).extracting(Contact::getSource).contains(ContactSource.CHAT, ContactSource.IMPORT, ContactSource.MANUAL);
        assertThat(all.stream().filter(c -> !c.getLabels().isEmpty())).hasSizeGreaterThan(10);
        assertThat(all.stream().filter(c -> !c.getAttributes().isEmpty())).hasSizeGreaterThan(10);
        Instant now = Instant.now();
        assertThat(all.stream().map(Contact::getLastMessageAt).filter(t -> t != null))
                .isNotEmpty()
                .allMatch(t -> t.isBefore(now.plusSeconds(60)) && t.isAfter(now.minus(Duration.ofDays(31))));

        assertThat(notes.findAll()).hasSize(6).allMatch(n -> n.getCreatedByUsername().equals("demo-manager"))
                .allMatch(n -> n.getWorkspaceId().equals(brand.getId()));
    }

    @Test
    void isIdempotent() {
        demoSeeder.run(null);
        seeder.run(null);
        seeder.run(null);
        assertThat(contacts.count()).isEqualTo(40);
        assertThat(labels.count()).isEqualTo(4);
        assertThat(notes.count()).isEqualTo(6);
    }

    @Test
    void skipsWhenBrandAlreadyHasLabels() {
        demoSeeder.run(null);
        ContactLabel mine = new ContactLabel();
        mine.setWorkspaceId(fashion().getId());
        mine.setName("Mine");
        mine.setColor("red");
        labels.save(mine);
        seeder.run(null);
        assertThat(contacts.count()).isZero();
        assertThat(labels.count()).isEqualTo(1);
    }

    @Test
    void skipsWithoutDemoBrand() {
        seeder.run(null);
        assertThat(contacts.count()).isZero();
        assertThat(labels.count()).isZero();
    }
}
