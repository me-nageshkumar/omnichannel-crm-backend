package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

@SpringBootTest
class ContactIndexesIT {

    @Autowired ContactRepository contacts;
    @Autowired TestUsers testUsers;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
    }

    private Contact contact(String workspaceId, String phone, String waId) {
        Contact c = new Contact();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId("cust");
        c.setPhone(phone);
        c.setWaId(waId);
        c.setSource(ContactSource.MANUAL);
        return c;
    }

    @Test
    void twoHiddenContactsCoexist() {
        contacts.save(contact("w1", null, "111111111111111@lid"));
        contacts.save(contact("w1", null, "222222222222222@lid"));
        assertThat(contacts.count()).isEqualTo(2);
    }

    @Test
    void twoImportedWithoutWaIdCoexist() {
        contacts.save(contact("w1", "919812345678", null));
        contacts.save(contact("w1", "919812345679", null));
        assertThat(contacts.count()).isEqualTo(2);
    }

    @Test
    void duplicatePhoneInBrandRejected() {
        contacts.save(contact("w1", "919812345678", null));
        assertThatThrownBy(() -> contacts.save(contact("w1", "919812345678", null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateWaIdInBrandRejected() {
        contacts.save(contact("w1", null, "111111111111111@lid"));
        assertThatThrownBy(() -> contacts.save(contact("w1", null, "111111111111111@lid")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void samePhoneInTwoBrandsAllowed() {
        contacts.save(contact("w1", "919812345678", null));
        contacts.save(contact("w2", "919812345678", null));
        assertThat(contacts.count()).isEqualTo(2);
    }
}
