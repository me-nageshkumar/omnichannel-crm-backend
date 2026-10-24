package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.ContactNoteDirectory;
import me.nageshk.omnichannel.crm.platform.EnablementAudit;
import me.nageshk.omnichannel.crm.platform.NumberDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.user.BrandDisable;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PlatformAdaptersIT extends CrmTestBase {

    @Autowired ContactDirectory contactDirectory;
    @Autowired ChatDirectory chatDirectory;
    @Autowired NumberDirectory numberDirectory;
    @Autowired PeopleDirectory peopleDirectory;
    @Autowired ContactNoteDirectory noteDirectory;
    @Autowired BrandDirectory brandDirectory;
    @Autowired EnablementAudit audit;
    @Autowired UserRepository userRepository;

    @Test
    void ensureReturnsExistingContactByNormalisedPhone() {
        Contact stored = contact("Asha", "919900000001");
        PlatformContact found = contactDirectory.ensure(brand.getId(), acme.getId(), "+91 99000 00001", "Other name");
        assertThat(found.id()).isEqualTo(stored.getId());
        assertThat(found.name()).isEqualTo("Asha");
        assertThat(found.phone()).isEqualTo("919900000001");
    }

    @Test
    void ensureCreatesManualContact() {
        PlatformContact created = contactDirectory.ensure(brand.getId(), acme.getId(), "+91 99000 00002", "Ravi");
        assertThat(created.phone()).isEqualTo("919900000002");
        Contact stored = contacts.findById(created.id()).orElseThrow();
        assertThat(stored.getSource()).isEqualTo(ContactSource.MANUAL);
        assertThat(stored.getWorkspaceId()).isEqualTo(brand.getId());
        assertThat(contactDirectory.find(brand.getId(), created.id())).isPresent();
        assertThat(contactDirectory.find(other.getId(), created.id())).isEmpty();
    }

    @Test
    void ensureRejectsBadPhone() {
        assertThatThrownBy(() -> contactDirectory.ensure(brand.getId(), acme.getId(), "12", "X"))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsOnlyKeys("phone"));
    }

    @Test
    void findAllIsBrandScoped() {
        Contact a = contact("A", "919900000003");
        Contact b = contact(other, "B", "919900000004");
        Map<String, PlatformContact> found = contactDirectory.findAll(brand.getId(), List.of(a.getId(), b.getId()));
        assertThat(found).containsOnlyKeys(a.getId());
    }

    @Test
    void chattableIsTeamOwnedNumbersForAgent() {
        assertThat(numberDirectory.chattable(auth(agent), brand.getId())).containsExactly(number.getId());
        assertThat(numberDirectory.chattable(auth(agent2), brand.getId())).isEmpty();
    }

    @Test
    void chattableIsEveryNumberWithViewAll() {
        assertThat(numberDirectory.chattable(auth(manager), brand.getId()))
                .containsExactlyInAnyOrder(number.getId(), number2.getId());
        assertThat(numberDirectory.numbers(brand.getId())).extracting("id")
                .containsExactlyInAnyOrder(number.getId(), number2.getId());
    }

    @Test
    void peopleAndTeams() {
        assertThat(peopleDirectory.teamIdsOf(brand.getId(), agent.getId())).isEqualTo(Set.of(support.getId()));
        assertThat(peopleDirectory.teamIdsOf(brand.getId(), agent2.getId())).isEmpty();
        assertThat(peopleDirectory.people(List.of(agent.getId(), "missing"))).containsOnlyKeys(agent.getId());
        assertThat(peopleDirectory.byUsername("agent")).hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(agent.getId()));
        assertThat(peopleDirectory.byUsername("nobody")).isEmpty();
    }

    @Test
    void isBrandUserTrueForRoleHolder() {
        assertThat(peopleDirectory.isBrandUser(brand.getId(), agent.getId())).isTrue();
        assertThat(peopleDirectory.isBrandUser(brand.getId(), "missing")).isFalse();
        assertThat(peopleDirectory.isBrandUser(brand.getId(), staff.getId())).isFalse();
    }

    @Test
    void isBrandUserTrueForCompanyRoleInCustomer() {
        User admin = testUsers.createInCustomer(acme.getId(), "admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        assertThat(peopleDirectory.isBrandUser(brand.getId(), admin.getId())).isTrue();
        assertThat(peopleDirectory.isBrandUser(other.getId(), admin.getId())).isTrue();
    }

    @Test
    void isBrandUserFalseForOtherBrandUser() {
        User outsider = testUsers.createInCustomer(acme.getId(), "outsider", null,
                List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        assertThat(peopleDirectory.isBrandUser(brand.getId(), outsider.getId())).isFalse();
        assertThat(peopleDirectory.isBrandUser(other.getId(), outsider.getId())).isTrue();
    }

    @Test
    void isBrandUserFalseWhenDisabledForTheBrand() {
        agent.getBrandDisables().add(new BrandDisable(brand.getId(), new Disabled("x", EnableLevel.BRAND, Instant.now())));
        agent.setBrandDisables(agent.getBrandDisables());
        userRepository.save(agent);
        assertThat(peopleDirectory.isBrandUser(brand.getId(), agent.getId())).isFalse();
    }

    @Test
    void findChatIsBrandScoped() {
        Contact c = contact("A", "919900000005");
        WaWebChat chat = chat(c, number, agent, support);
        assertThat(chatDirectory.find(brand.getId(), chat.getId())).hasValueSatisfying(p -> {
            assertThat(p.contactId()).isEqualTo(c.getId());
            assertThat(p.assignedUserId()).isEqualTo(agent.getId());
            assertThat(p.assignedTeamId()).isEqualTo(support.getId());
        });
        assertThat(chatDirectory.find(other.getId(), chat.getId())).isEmpty();
        assertThat(chatDirectory.findFor(number.getId(), c.getId())).isPresent();
        assertThat(chatDirectory.findFor(number2.getId(), c.getId())).isEmpty();

        Contact oc = contact(other, "B", "919900000006");
        WaWebChat otherChat = chat(oc, otherNumber, null, null);
        assertThat(chatDirectory.find(brand.getId(), otherChat.getId())).isEmpty();
    }

    @Test
    void notesNewestFirst() throws Exception {
        Contact c = contact("A", "919900000007");
        for (String text : List.of("first", "second")) {
            ContactNote n = new ContactNote();
            n.setWorkspaceId(brand.getId());
            n.setContactId(c.getId());
            n.setText(text);
            n.setCreatedByName("Agent");
            mongo.insert(n); // creationTime is set by auditing, so space the inserts out
            Thread.sleep(20);
        }
        assertThat(noteDirectory.notes(brand.getId(), c.getId())).extracting("text").containsExactly("second", "first");
    }

    @Test
    void brands() {
        assertThat(brandDirectory.find(brand.getId())).hasValueSatisfying(b -> {
            assertThat(b.customerId()).isEqualTo(acme.getId());
            assertThat(b.name()).isEqualTo("Brand A");
        });
        assertThat(brandDirectory.find("missing")).isEmpty();
        assertThat(brandDirectory.findByNames("Acme", "Brand B")).hasValueSatisfying(b -> assertThat(b.id()).isEqualTo(other.getId()));
        assertThat(brandDirectory.findByNames("Acme", "Nope")).isEmpty();
        assertThat(brandDirectory.allBrandIds()).containsExactlyInAnyOrder(brand.getId(), other.getId());
    }

    @Test
    void auditRecordsCrmSwitch() {
        audit.recordCrmSwitch(auth(staff), brand.getId(), false, true);
        List<EnablementChange> rows = mongo.findAll(EnablementChange.class);
        assertThat(rows).hasSize(1);
        EnablementChange row = rows.get(0);
        assertThat(row.getTargetType()).isEqualTo("crm");
        assertThat(row.getTargetId()).isEqualTo(brand.getId());
        assertThat(row.isEnabledBefore()).isFalse();
        assertThat(row.isEnabledAfter()).isTrue();
        assertThat(row.getBy()).isEqualTo("staff");
        assertThat(row.getLevel()).isEqualTo(EnableLevel.PLATFORM);
        assertThat(row.getAt()).isNotNull();
    }
}
